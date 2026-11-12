import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { Check, Loader2, AlertCircle, ArrowLeft, ArrowRight } from "lucide-react";
import { type Account, api, ApiError, inviteCodeFromPath, runtimeConfig } from "../api";
import { BrandLockup } from "../components/BrandMark";
import { TmdbSettings } from "../components/settings/TmdbSettings";
import { TraktSettings } from "../components/settings/TraktSettings";
import { SECTION_TITLE } from "../components/typography";
import {
  FIELD_LABEL_CLASS as LABEL_CLASS,
  FieldError,
  newPasswordProblem,
  PASSWORD_INPUT_CLASS as INPUT_CLASS,
  PasswordField,
} from "../components/PasswordField";

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
                className={`h-1.5 w-8 rounded-full transition-colors ${i <= index ? "bg-accent-500" : ""}`}
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

type AccountField = "apiToken" | "username" | "password" | "confirm";

/** Where an admin who forgot their password is sent: the server-side reset. */
export const RECOVERY_GUIDE_URL =
  "https://github.com/TheShield2594/fetch/blob/main/web/docs/troubleshooting.md#forgotten-password-or-pin";

const ACCOUNT_COPY: Record<AccountMode, { title: string; body: string; submit: string }> = {
  bootstrap: {
    title: "Create the admin account",
    body: "Paste the API token your server was configured with, then choose the username and password you'll sign in with.",
    submit: "Create account",
  },
  login: {
    title: "Sign in",
    body: "Sign in with the username and password for your Fetch account.",
    submit: "Sign in",
  },
  register: {
    title: "Join Fetch",
    body: "You've been invited. Choose a username and password for your account.",
    submit: "Create account",
  },
};

function AccountStep({
  mode,
  inviteCode,
  onSignedIn,
}: {
  mode: AccountMode;
  inviteCode: string | null;
  onSignedIn: (account: Account, password: string) => void;
}) {
  // A token already on this device from before accounts existed is the API
  // token, so it fills in the first-run form rather than being asked for again.
  const [apiToken, setApiToken] = useState(() => (runtimeConfig.hasSession() ? "" : runtimeConfig.getLegacyApiToken()));
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<AccountField, string>>>({});
  const copy = ACCOUNT_COPY[mode];
  const creating = mode !== "login";
  const apiTokenRef = useRef<HTMLInputElement>(null);
  const usernameRef = useRef<HTMLInputElement>(null);
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
    if (!username.trim()) errors.username = "Enter a username.";
    if (!password) errors.password = "Enter a password.";
    else if (creating) {
      const problem = newPasswordProblem(password);
      if (problem) errors.password = problem;
    }
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
    const first = (["apiToken", "username", "password", "confirm"] as const).find((f) => errors[f]);
    if (first) {
      setError(null);
      const refs = { apiToken: apiTokenRef, username: usernameRef, password: passwordRef, confirm: confirmRef };
      refs[first].current?.focus();
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const account =
        mode === "bootstrap"
          ? await api.bootstrapAdmin(apiToken.trim(), username.trim(), password)
          : mode === "register"
            ? await api.register(inviteCode ?? "", username.trim(), password)
            : await api.login(username.trim(), password);
      onSignedIn(account, password);
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
        <PasswordField
          label="API token"
          value={apiToken}
          onChange={setApiToken}
          autoComplete="off"
          error={fieldErrors.apiToken}
          inputRef={apiTokenRef}
          maxLength={null}
          required
        />
      )}
      <TextField
        label="Username"
        autoFocus={mode !== "bootstrap"}
        type="text"
        value={username}
        onChange={setUsername}
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        error={fieldErrors.username}
        inputRef={usernameRef}
        required
      />
      <PasswordField
        label="Password"
        value={password}
        onChange={setPassword}
        autoComplete={creating ? "new-password" : "current-password"}
        error={fieldErrors.password}
        inputRef={passwordRef}
        newPassword={creating}
        required
      />
      {creating && (
        <PasswordField
          label="Confirm password"
          value={confirm}
          onChange={setConfirm}
          autoComplete="new-password"
          error={fieldErrors.confirm}
          inputRef={confirmRef}
          required
        />
      )}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={submitting} className="btn-primary btn-lg w-full">
        {submitting ? <><Loader2 size={16} className="animate-spin" /> Working…</> : <>{copy.submit} <ArrowRight size={16} /></>}
      </button>
      {mode === "login" && (
        <p className="text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Forgot your password? Ask the admin to set a temporary one. If you are the admin, see the{" "}
          <a href={RECOVERY_GUIDE_URL} target="_blank" rel="noreferrer" className="font-medium text-accent-text underline">
            recovery guide
          </a>
          .
        </p>
      )}
    </form>
  );
}

/**
 * After signing in with a password someone else chose — a temporary one from
 * the admin, or one reset on the server — the account picks its own before
 * going any further.
 */
function NewPasswordStep({
  account,
  currentPassword: knownPassword,
  onDone,
}: {
  account: Account;
  /** The temporary password just typed, or null when it must be asked again (after a reload). */
  currentPassword: string | null;
  onDone: (account: Account) => void;
}) {
  const [typedCurrent, setTypedCurrent] = useState("");
  const currentPassword = knownPassword ?? typedCurrent;
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<{ password?: string | undefined; confirm?: string | undefined }>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!currentPassword) {
      setError("Enter the temporary password you signed in with.");
      return;
    }
    const next = {
      password: newPasswordProblem(password) ?? (password === currentPassword ? "Choose a different password." : undefined),
      confirm: !confirm ? "Enter the password again." : confirm !== password ? "The passwords don't match." : undefined,
    };
    setErrors(next);
    if (next.password || next.confirm) return;
    setSaving(true);
    setError(null);
    try {
      await api.changePassword(currentPassword, password);
      onDone({ ...account, mustChangePassword: false });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not change the password.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <div>
        <h1 className={SECTION_TITLE}>Choose a new password</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          You signed in with a temporary password. Choose your own to continue.
        </p>
      </div>
      {knownPassword === null && (
        <PasswordField
          label="Temporary password"
          value={typedCurrent}
          onChange={setTypedCurrent}
          autoComplete="current-password"
          autoFocus
          required
        />
      )}
      <PasswordField
        label="New password"
        value={password}
        onChange={setPassword}
        autoComplete="new-password"
        error={errors.password}
        newPassword
        autoFocus={knownPassword !== null}
        required
      />
      <PasswordField
        label="Confirm new password"
        value={confirm}
        onChange={setConfirm}
        autoComplete="new-password"
        error={errors.confirm}
        required
      />
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={saving} className="btn-primary btn-lg w-full">
        {saving ? <><Loader2 size={16} className="animate-spin" /> Saving…</> : <>Save and continue <ArrowRight size={16} /></>}
      </button>
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
          Add a few titles from Search, or connect a source in Settings, and your shelf fills itself in. The TV app reads the same lists.
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
  // The temporary password just used to sign in, while a new one is chosen.
  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  // A session that is still waiting on a new password (the page was reloaded
  // after signing in with a temporary one) goes straight back to that step.
  useEffect(() => {
    if (inviteCode || !runtimeConfig.hasSession()) return;
    let cancelled = false;
    api.getMe().then(
      (me) => {
        if (cancelled || !me.account?.mustChangePassword) return;
        setAccount(me.account);
        setTemporaryPassword("");
      },
      () => {}
    );
    return () => {
      cancelled = true;
    };
  }, [inviteCode]);

  useEffect(() => {
    if (inviteCode) return;
    let cancelled = false;
    api
      .getAuthStatus()
      .then((status) => {
        if (!cancelled) setMode(status.setupRequired ? "bootstrap" : "login");
      })
      .catch((err: unknown) => {
        if (!cancelled) setStatusError(err instanceof Error ? err.message : "Could not reach the Fetch server.");
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

  const signedIn = (next: Account, password?: string) => {
    setAccount(next);
    if (next.mustChangePassword && password) {
      setTemporaryPassword(password);
      return;
    }
    setTemporaryPassword(null);
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
      {step === "account" && account && temporaryPassword !== null && (
        <NewPasswordStep
          account={account}
          currentPassword={temporaryPassword || null}
          onDone={(next) => signedIn(next)}
        />
      )}
      {step === "account" && account && temporaryPassword === null && (
        <SignedInStep account={account} onContinue={() => setStep("tmdb")} />
      )}
      {step === "account" && !account && mode && (
        <AccountStep mode={mode} inviteCode={inviteCode} onSignedIn={signedIn} />
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
