import { type FormEvent, useEffect, useState } from "react";
import { Check, Eye, EyeOff, Loader2, AlertCircle, ArrowLeft, ArrowRight } from "lucide-react";
import { type Account, api, ApiError, inviteCodeFromPath, runtimeConfig } from "../api";
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
  "w-full rounded-xl border border-[var(--border-strong)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] focus:border-claw-500 focus:outline-none focus:ring-2 focus:ring-claw-500/15";

/** A password field with a show/hide toggle. */
function PasswordInput({
  label,
  value,
  onChange,
  autoComplete,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete: string;
}) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <input
        type={visible ? "text" : "password"}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={label}
        aria-label={label}
        autoComplete={autoComplete}
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
  );
}

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
  onSignedIn: (account: Account) => void;
}) {
  // A token already on this device from before accounts existed is the API
  // token, so it fills in the first-run form rather than being asked for again.
  const [apiToken, setApiToken] = useState(() => (runtimeConfig.hasSession() ? "" : runtimeConfig.getToken()));
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const copy = ACCOUNT_COPY[mode];
  const creating = mode !== "login";

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (creating && password !== confirm) {
      setError("The passwords don't match.");
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

  const ready =
    username.trim() && password && (!creating || confirm) && (mode !== "bootstrap" || apiToken.trim());

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        {/* Not "Welcome to Fetch": the lockup above already says the name,
            and a step's heading is better spent saying what the step wants. */}
        <h1 className={SECTION_TITLE}>{copy.title}</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          {copy.body}
        </p>
      </div>
      {mode === "bootstrap" && (
        <PasswordInput label="API token" value={apiToken} onChange={setApiToken} autoComplete="off" />
      )}
      <input
        autoFocus={mode !== "bootstrap"}
        type="text"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        placeholder="Username"
        aria-label="Username"
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        className={INPUT_CLASS}
      />
      <PasswordInput
        label="Password"
        value={password}
        onChange={setPassword}
        autoComplete={creating ? "new-password" : "current-password"}
      />
      {creating && (
        <PasswordInput label="Confirm password" value={confirm} onChange={setConfirm} autoComplete="new-password" />
      )}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={submitting || !ready} className="btn-primary btn-lg w-full">
        {submitting ? <><Loader2 size={16} className="animate-spin" /> Working...</> : <>{copy.submit} <ArrowRight size={16} /></>}
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
        <h1 className={SECTION_TITLE}>TMDB Metadata</h1>
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
        Get Started
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
  const [attempt, setAttempt] = useState(0);

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
