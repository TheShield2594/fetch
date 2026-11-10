import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { Check, Eye, EyeOff, Loader2, AlertCircle, ArrowLeft, ArrowRight } from "lucide-react";
import { type Account, api, ApiError, type EmailLink, inviteCodeFromPath, runtimeConfig } from "../api";
import { BrandLockup } from "../components/BrandMark";
import { TmdbSettings } from "../components/settings/TmdbSettings";
import { TraktSettings } from "../components/settings/TraktSettings";
import { SECTION_TITLE } from "../components/typography";

type Step = "account" | "tmdb" | "trakt" | "done";

/** The admin's first run. Signing in or joining by invite stops after "account". */
export const WIZARD_STEPS: Step[] = ["account", "tmdb", "trakt", "done"];

/** The step to return to from `step`, or null if it is the entry point. */
export function previousStep(step: Step): Step | null {
  const index = WIZARD_STEPS.indexOf(step);
  return index > 0 ? (WIZARD_STEPS[index - 1] ?? null) : null;
}

function BackButton({ onBack }: { onBack: () => void }) {
  return (
    <button
      type="button"
      onClick={onBack}
      className="btn-secondary btn-lg w-full"
    >
      <ArrowLeft size={16} /> Back
    </button>
  );
}

function WizardShell({
  step,
  showProgress,
  children,
}: {
  step: Step;
  showProgress: boolean;
  children: React.ReactNode;
}) {
  const index = WIZARD_STEPS.indexOf(step);
  const position = index + 1;
  const total = WIZARD_STEPS.length;
  const label = `Step ${position} of ${total}`;

  return (
    <div className="flex min-h-screen items-center justify-center px-6 py-12">
      <div className="w-full max-w-md space-y-6">
        <BrandLockup />

        {/* The bars alone say nothing to a screen reader and give no sense of
            how much is left to anyone else, so the count carries the meaning
            and the bars are decoration on top of it. */}
        {showProgress && (
        <div className="space-y-2">
          <div
            className="flex items-center justify-center gap-2"
            role="progressbar"
            aria-label="Setup progress"
            aria-valuemin={1}
            aria-valuemax={total}
            aria-valuenow={position}
            aria-valuetext={label}
          >
            {WIZARD_STEPS.map((s, i) => (
              <span
                key={s}
                className={`h-1.5 w-8 rounded-full transition-colors ${i <= index ? "bg-claw-500" : ""}`}
                style={i <= index ? undefined : { backgroundColor: "var(--surface)" }}
              />
            ))}
          </div>
          <p className="text-center text-xs font-medium" style={{ color: "var(--text-mute)" }} aria-hidden="true">
            {label}
          </p>
        </div>
        )}

        <div
          className="rounded-2xl p-6 shadow-e1"
          style={{ border: "1px solid var(--border)", backgroundColor: "var(--bg-0)" }}
        >
          {children}
        </div>
      </div>
    </div>
  );
}

type AccountMode = "bootstrap" | "login" | "register";

const INPUT_CLASS =
  "w-full rounded-xl border border-[var(--border-field)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] field-control";

const LABEL_CLASS = "block text-xs font-semibold";

/** The message under a field that failed validation, read out as its description. */
function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null;
  return (
    <p id={id} className="mt-1.5 flex items-center gap-1.5 text-xs text-danger">
      <AlertCircle size={14} aria-hidden="true" /> {message}
    </p>
  );
}

/**
 * A labelled text field. The label sits above the field and stays while you
 * type: a placeholder standing in for one vanished on the first keystroke, and
 * on the account form left three rows of identical dots.
 */
function TextField({
  label,
  value,
  onChange,
  error,
  inputRef,
  ...rest
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string | undefined;
  inputRef?: React.Ref<HTMLInputElement>;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange">) {
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS} style={{ color: "var(--text)" }}>
        {label}
      </label>
      <input
        {...rest}
        id={id}
        ref={inputRef}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className={`mt-1.5 ${INPUT_CLASS}`}
      />
      <FieldError id={errorId} message={error} />
    </div>
  );
}

/** A labelled password field with a show/hide toggle. */
function PasswordInput({
  label,
  value,
  onChange,
  autoComplete,
  error,
  inputRef,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete: string;
  error?: string | undefined;
  inputRef?: React.Ref<HTMLInputElement>;
}) {
  const [visible, setVisible] = useState(false);
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS} style={{ color: "var(--text)" }}>
        {label}
      </label>
      <div className="relative mt-1.5">
        <input
          id={id}
          ref={inputRef}
          type={visible ? "text" : "password"}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          autoComplete={autoComplete}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className={`${INPUT_CLASS} pr-12`}
        />
        <button
          type="button"
          onClick={() => setVisible((p) => !p)}
          className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1.5 text-[var(--text-mute)] transition-colors hover:bg-[var(--surface-strong)] hover:text-[var(--text)]"
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
        >
          {visible ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
      <FieldError id={errorId} message={error} />
    </div>
  );
}

type AccountField = "apiToken" | "username" | "email" | "password" | "confirm";

/** Enough to catch a username typed into the email field; the server checks properly. */
const looksLikeEmail = (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());

/** Under the optional email field: what it is for, and whether this server can confirm it. */
const emailHint = (emailEnabled: boolean) =>
  emailEnabled
    ? "Optional. Sign in with it, reset a forgotten password, and get notifications. We'll send a link to confirm it."
    : "Optional. You can sign in with it. This server isn't set up to send email yet, so resets and email notifications come later.";

const ACCOUNT_COPY: Record<AccountMode, { title: string; body: string; submit: string }> = {
  bootstrap: {
    title: "Create the admin account",
    body: "Paste the API token your server was configured with, then choose the username and password you'll sign in with.",
    submit: "Create account",
  },
  login: {
    title: "Sign in",
    body: "Sign in with the username or email address and the password for your Fetch account.",
    submit: "Sign in",
  },
  register: {
    title: "Join Fetch",
    body: "You've been invited. Choose a username and password for your account.",
    submit: "Create account",
  },
};

/** Asks for a reset link. Shown in place of the sign-in form. */
function ForgotPasswordForm({ onBack }: { onBack: () => void }) {
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!looksLikeEmail(email)) {
      setError("Enter the email address on your account.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.forgotPassword(email.trim());
      setSent(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reach the Fetch server.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>Reset your password</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Enter the confirmed email address on your account and we'll send a link to choose a new password.
        </p>
      </div>
      {sent ? (
        <p role="status" className="flex items-start gap-2 text-sm text-success">
          <Check size={16} className="mt-0.5 flex-none" /> If that address belongs to an account and has been confirmed,
          a reset link is on its way. It works for one hour.
        </p>
      ) : (
        <>
          <TextField
            label="Email"
            type="email"
            value={email}
            onChange={setEmail}
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
          />
          {error && (
            <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
          )}
          <button type="submit" disabled={submitting} className="btn-primary btn-lg w-full">
            {submitting ? <><Loader2 size={16} className="animate-spin" /> Sending…</> : <>Send reset link <ArrowRight size={16} /></>}
          </button>
        </>
      )}
      <BackButton onBack={onBack} />
    </form>
  );
}

function AccountStep({
  mode,
  inviteCode,
  emailEnabled,
  onSignedIn,
}: {
  mode: AccountMode;
  inviteCode: string | null;
  emailEnabled: boolean;
  onSignedIn: (account: Account) => void;
}) {
  const [forgot, setForgot] = useState(false);
  if (forgot) return <ForgotPasswordForm onBack={() => setForgot(false)} />;
  return (
    <AccountForm
      mode={mode}
      inviteCode={inviteCode}
      emailEnabled={emailEnabled}
      onSignedIn={onSignedIn}
      onForgot={() => setForgot(true)}
    />
  );
}

function AccountForm({
  mode,
  inviteCode,
  emailEnabled,
  onSignedIn,
  onForgot,
}: {
  mode: AccountMode;
  inviteCode: string | null;
  emailEnabled: boolean;
  onSignedIn: (account: Account) => void;
  onForgot: () => void;
}) {
  // A token already on this device from before accounts existed is the API
  // token, so it fills in the first-run form rather than being asked for again.
  const [apiToken, setApiToken] = useState(() => (runtimeConfig.hasSession() ? "" : runtimeConfig.getToken()));
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<AccountField, string>>>({});
  const copy = ACCOUNT_COPY[mode];
  const creating = mode !== "login";
  const apiTokenRef = useRef<HTMLInputElement>(null);
  const usernameRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLInputElement>(null);

  /**
   * What's wrong with the form, field by field, in the order the fields
   * appear. Checked on submit rather than by holding the button disabled: a
   * greyed-out button doesn't say which field it is waiting for, and on the
   * first-run form that is usually the API token, above the fold on a phone.
   */
  const validate = (): Partial<Record<AccountField, string>> => {
    const errors: Partial<Record<AccountField, string>> = {};
    if (mode === "bootstrap" && !apiToken.trim()) errors.apiToken = "Enter the API token your server was configured with.";
    if (!username.trim()) errors.username = creating ? "Enter a username." : "Enter your username or email.";
    if (creating && email.trim() && !looksLikeEmail(email)) errors.email = "Enter a valid email address, or leave it empty.";
    if (!password) errors.password = "Enter a password.";
    if (creating) {
      if (!confirm) errors.confirm = "Enter the password again.";
      else if (password && password !== confirm) errors.confirm = "The passwords don't match.";
    }
    return errors;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const errors = validate();
    setFieldErrors(errors);
    const first = (["apiToken", "username", "email", "password", "confirm"] as const).find((f) => errors[f]);
    if (first) {
      setError(null);
      const refs = {
        apiToken: apiTokenRef,
        username: usernameRef,
        email: emailRef,
        password: passwordRef,
        confirm: confirmRef,
      };
      refs[first].current?.focus();
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const account =
        mode === "bootstrap"
          ? await api.bootstrapAdmin(apiToken.trim(), username.trim(), password, email.trim())
          : mode === "register"
            ? await api.register(inviteCode ?? "", username.trim(), password, email.trim())
            : await api.login(username.trim(), password);
      onSignedIn(account);
    } catch (err) {
      if (mode === "bootstrap" && err instanceof ApiError && err.status === 401) {
        setError("That API token was rejected by the server. Double-check it and try again.");
      } else {
        setError(err instanceof Error ? err.message : "Could not reach the Fetch server.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <div>
        {/* Not "Welcome to Fetch": the lockup above already says the name,
            and a step's heading is better spent saying what the step wants. */}
        <h1 className={SECTION_TITLE}>{copy.title}</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          {copy.body}
        </p>
      </div>
      {mode === "bootstrap" && (
        <PasswordInput
          label="API token"
          value={apiToken}
          onChange={setApiToken}
          autoComplete="off"
          error={fieldErrors.apiToken}
          inputRef={apiTokenRef}
        />
      )}
      <TextField
        label={creating ? "Username" : "Username or email"}
        autoFocus={mode !== "bootstrap"}
        type="text"
        value={username}
        onChange={setUsername}
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        error={fieldErrors.username}
        inputRef={usernameRef}
      />
      {creating && (
        <div>
          <TextField
            label="Email (optional)"
            type="email"
            value={email}
            onChange={setEmail}
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            error={fieldErrors.email}
            inputRef={emailRef}
          />
          {!fieldErrors.email && (
            <p className="mt-1.5 text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
              {emailHint(emailEnabled)}
            </p>
          )}
        </div>
      )}
      <PasswordInput
        label="Password"
        value={password}
        onChange={setPassword}
        autoComplete={creating ? "new-password" : "current-password"}
        error={fieldErrors.password}
        inputRef={passwordRef}
      />
      {creating && (
        <PasswordInput
          label="Confirm password"
          value={confirm}
          onChange={setConfirm}
          autoComplete="new-password"
          error={fieldErrors.confirm}
          inputRef={confirmRef}
        />
      )}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={submitting} className="btn-primary btn-lg w-full">
        {submitting ? <><Loader2 size={16} className="animate-spin" /> Working…</> : <>{copy.submit} <ArrowRight size={16} /></>}
      </button>
      {mode === "login" && emailEnabled && (
        <button type="button" onClick={onForgot} className="mx-auto block text-sm text-claw-text underline underline-offset-2 hover:decoration-2">
          Forgot your password?
        </button>
      )}
    </form>
  );
}

/** Shown when stepping back to the account step after it has been completed. */
function SignedInStep({ account, onContinue }: { account: Account; onContinue: () => void }) {
  return (
    <div className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>Signed in</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          You're signed in as <strong>{account.username}</strong>.
        </p>
      </div>
      <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
        Continue <ArrowRight size={16} />
      </button>
    </div>
  );
}

function TmdbStep({ onContinue, onBack }: { onContinue: () => void; onBack: () => void }) {
  return (
    <div className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>TMDB metadata</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Add a key now, or skip and add one later in Settings.
        </p>
      </div>

      <TmdbSettings />

      <button
        type="button"
        onClick={onContinue}
        className="btn-secondary btn-lg w-full"
      >
        Continue <ArrowRight size={16} />
      </button>
      <BackButton onBack={onBack} />
    </div>
  );
}

function TraktStep({ onContinue, onBack }: { onContinue: () => void; onBack: () => void }) {
  return (
    <div className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>Connect Trakt (optional)</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Sync your watch history and watchlist from Trakt. You can also do this later in Settings.
        </p>
      </div>

      <TraktSettings />

      <button
        type="button"
        onClick={onContinue}
        className="btn-secondary btn-lg w-full"
      >
        Continue <ArrowRight size={16} />
      </button>
      <BackButton onBack={onBack} />
    </div>
  );
}

function DoneStep({ onFinish, onBack }: { onFinish: () => void; onBack: () => void }) {
  return (
    <div className="space-y-4 text-center">
      <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/15 ring-1 ring-emerald-500/20">
        <Check className="h-7 w-7 text-success" />
      </div>
      <div>
        <h1 className={SECTION_TITLE}>You're all set</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Start searching for movies and shows to build your catalog.
        </p>
      </div>
      <button
        type="button"
        onClick={onFinish}
        className="btn-primary btn-lg w-full"
      >
        Get started
      </button>
      <BackButton onBack={onBack} />
    </div>
  );
}

export function SetupWizard({ onComplete }: { onComplete: () => void }) {
  const [step, setStep] = useState<Step>("account");
  const [inviteCode] = useState(() => inviteCodeFromPath(window.location.pathname));
  // Which account form to show: from the link for an invite, otherwise from
  // whether the server has an admin yet.
  const [mode, setMode] = useState<AccountMode | null>(inviteCode ? "register" : null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [emailEnabled, setEmailEnabled] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .getAuthStatus()
      .then((status) => {
        if (cancelled) return;
        setEmailEnabled(status.emailEnabled === true);
        if (!inviteCode) setMode(status.setupRequired ? "bootstrap" : "login");
      })
      .catch((err: unknown) => {
        // An invite already says which form to show; only the email hint
        // depends on the answer, and it falls back to "can't send yet".
        if (!cancelled && !inviteCode) {
          setStatusError(err instanceof Error ? err.message : "Could not reach the Fetch server.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [inviteCode, attempt]);

  const finish = () => {
    // An invite link has done its job; the app's own routes take over.
    if (inviteCode) window.history.replaceState(null, "", "/");
    onComplete();
  };

  const signedIn = (next: Account) => {
    setAccount(next);
    // Only the admin's first run goes on to the server's integrations; anyone
    // signing in to an install that is already set up goes straight in.
    if (mode === "bootstrap") setStep("tmdb");
    else finish();
  };

  // Every step past the first is reachable in both directions: a Trakt
  // connection started by mistake was otherwise only undoable from Settings.
  const goBack = () => setStep((current) => previousStep(current) ?? current);

  return (
    <WizardShell step={step} showProgress={mode === "bootstrap"}>
      {step === "account" && account && <SignedInStep account={account} onContinue={() => setStep("tmdb")} />}
      {step === "account" && !account && mode && (
        <AccountStep mode={mode} inviteCode={inviteCode} emailEnabled={emailEnabled} onSignedIn={signedIn} />
      )}
      {step === "account" && !account && !mode && (
        <div className="space-y-4">
          {statusError ? (
            <>
              <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {statusError}</p>
              <button
                type="button"
                onClick={() => {
                  setStatusError(null);
                  setAttempt((n) => n + 1);
                }}
                className="btn-secondary btn-lg w-full"
              >
                Try again
              </button>
            </>
          ) : (
            <p className="flex items-center justify-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
              <Loader2 size={16} className="animate-spin" /> Connecting…
            </p>
          )}
        </div>
      )}
      {step === "tmdb" && <TmdbStep onContinue={() => setStep("trakt")} onBack={goBack} />}
      {step === "trakt" && <TraktStep onContinue={() => setStep("done")} onBack={goBack} />}
      {step === "done" && <DoneStep onFinish={finish} onBack={goBack} />}
    </WizardShell>
  );
}

/**
 * Where the links in account mails land: `/verify-email/<token>` confirms an
 * address, `/reset-password/<token>` sets a new password. Shown on its own,
 * signed in or not, since a mail is often opened on another device.
 */
export function EmailLinkPage({ link, onDone }: { link: EmailLink; onDone: () => void }) {
  const leave = () => {
    window.history.replaceState(null, "", "/");
    onDone();
  };
  return (
    <div className="flex min-h-screen items-center justify-center px-6 py-12">
      <div className="w-full max-w-md space-y-6">
        <BrandLockup />
        {link.kind === "verify" ? (
          <VerifyEmail token={link.token} onContinue={leave} />
        ) : (
          <ResetPassword token={link.token} onContinue={leave} />
        )}
      </div>
    </div>
  );
}

function VerifyEmail({ token, onContinue }: { token: string; onContinue: () => void }) {
  const [state, setState] = useState<"working" | "done" | { error: string }>("working");
  // The link is single-use, so React's development double-run of effects
  // must not spend it twice.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    api
      .verifyEmail(token)
      .then(() => setState("done"))
      .catch((err: unknown) =>
        setState({ error: err instanceof Error ? err.message : "Could not reach the Fetch server." })
      );
  }, [token]);

  return (
    <div className="space-y-4">
      <h1 className={SECTION_TITLE}>Confirm your email</h1>
      {state === "working" && (
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
          <Loader2 size={16} className="animate-spin" /> Confirming…
        </p>
      )}
      {state === "done" && (
        <p role="status" className="flex items-center gap-2 text-sm text-success">
          <Check size={16} /> Your email address is confirmed.
        </p>
      )}
      {typeof state === "object" && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {state.error} You can send a new link from Settings → Account.
        </p>
      )}
      {state !== "working" && (
        <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
          Continue to Fetch <ArrowRight size={16} />
        </button>
      )}
    </div>
  );
}

function ResetPassword({ token, onContinue }: { token: string; onContinue: () => void }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [signedInAs, setSignedInAs] = useState<Account | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!password) {
      setError("Enter a new password.");
      return;
    }
    if (password !== confirm) {
      setError("The passwords don't match.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      setSignedInAs(await api.resetPassword(token, password));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reach the Fetch server.");
    } finally {
      setSubmitting(false);
    }
  };

  if (signedInAs) {
    return (
      <div className="space-y-4">
        <h1 className={SECTION_TITLE}>Password changed</h1>
        <p role="status" className="text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          You're signed in as <strong>{signedInAs.username}</strong>. Every other device has been signed out.
        </p>
        <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
          Continue to Fetch <ArrowRight size={16} />
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>Choose a new password</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          This signs out every device that's signed in to your account.
        </p>
      </div>
      <PasswordInput label="New password" value={password} onChange={setPassword} autoComplete="new-password" />
      <PasswordInput label="Confirm password" value={confirm} onChange={setConfirm} autoComplete="new-password" />
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={submitting} className="btn-primary btn-lg w-full">
        {submitting ? <><Loader2 size={16} className="animate-spin" /> Working…</> : <>Set password <ArrowRight size={16} /></>}
      </button>
    </form>
  );
}
