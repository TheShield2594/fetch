import { type FormEvent, type ReactNode, useCallback, useEffect, useId, useRef, useState } from "react";
import { Check, CheckCircle2, ChevronDown, Loader2, AlertCircle, AlertTriangle, ArrowLeft, ArrowRight, Link as LinkIcon, ListChecks, Server, Tv } from "lucide-react";
import { type Account, api, ApiError, inviteCodeFromPath, type Profile, runtimeConfig, type TmdbStatus } from "../api";
import { BrandLockup } from "../components/BrandMark";
import { TmdbSettings } from "../components/settings/TmdbSettings";
import { TraktSettings } from "../components/settings/TraktSettings";
import { MdbListSettings, SimklSettings } from "../components/settings/TrackerSignInSettings";
import { MediaServersSettings } from "../components/settings/MediaServersSettings";
import { PairingCodeForm } from "../components/settings/DevicesSettings";
import { AvatarPicker, type AvatarChoice } from "../components/profile/AvatarPicker";
import { SECTION_TITLE } from "../components/typography";
import {
  FIELD_LABEL_CLASS as LABEL_CLASS,
  FieldError,
  newPasswordProblem,
  PASSWORD_INPUT_CLASS as INPUT_CLASS,
  PasswordField,
} from "../components/PasswordField";

type Step = "account" | "profile" | "tmdb" | "history" | "tv" | "done";

/**
 * The admin's first run: the account, who is watching, the metadata key, where
 * history comes from, and the TV. Signing in or joining by invite stops after
 * "account".
 */
export const WIZARD_STEPS: Step[] = ["account", "profile", "tmdb", "history", "tv", "done"];

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

/** A step's heading and the line under it. */
function StepHeading({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h1 className={SECTION_TITLE}>{title}</h1>
      <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
        {children}
      </p>
    </div>
  );
}

/**
 * Who is watching: the first profile's name and picture.
 *
 * The server makes that profile as "Default" (initials "DE") and nobody was
 * ever asked their name; it was the first thing on the profile picker after
 * setup. This renames it, and opens it, so setup ends on the Shelf rather than
 * on a picker of one. An install that already has several profiles (from
 * before accounts) keeps them as they are and picks one after setup.
 */
function ProfileStep({
  suggestedName,
  onContinue,
  onBack,
}: {
  suggestedName: string;
  onContinue: () => void;
  onBack: () => void;
}) {
  const [profiles, setProfiles] = useState<Profile[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [look, setLook] = useState<AvatarChoice>({ avatarColorHex: null, avatarId: null });
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api.getProfiles().then(
      ({ profiles: list }) => {
        if (cancelled) return;
        setProfiles(list);
        const first = list[0];
        if (first) {
          setName(first.name === "Default" ? suggestedName : first.name);
          setLook({ avatarColorHex: first.avatarColorHex ?? null, avatarId: first.avatarId ?? null });
        }
      },
      (err: unknown) => {
        if (!cancelled) setLoadError(err instanceof Error ? err.message : "Could not load the profile.");
      }
    );
    return () => {
      cancelled = true;
    };
  }, [suggestedName, attempt]);

  const target = profiles?.length === 1 ? profiles[0] : undefined;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!target) return;
    if (!name.trim()) {
      setNameError("Enter a name for this profile.");
      return;
    }
    setNameError(undefined);
    setSaving(true);
    setError(null);
    try {
      const { profile } = await api.updateProfile(target.id, {
        name: name.trim(),
        avatarId: look.avatarId,
        avatarColorHex: look.avatarColorHex,
      });
      // Opened here, so the rest of setup (a Trakt sign-in is the profile's)
      // and the first screen after it belong to this profile.
      const verified = await api.verifyProfile(profile.id);
      runtimeConfig.setProfileId(verified.id);
      onContinue();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the profile.");
    } finally {
      setSaving(false);
    }
  };

  if (loadError) {
    return (
      <div className="space-y-4">
        <StepHeading title="Who's watching?">Your profile holds your history, lists and ratings.</StepHeading>
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {loadError}</p>
        <button type="button" onClick={() => { setLoadError(null); setAttempt((n) => n + 1); }} className="btn-secondary btn-lg w-full">
          Try again
        </button>
        <button type="button" onClick={onContinue} className="btn-secondary btn-lg w-full">
          Skip for now <ArrowRight size={16} />
        </button>
      </div>
    );
  }

  if (!profiles) {
    return (
      <p className="flex items-center justify-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading…
      </p>
    );
  }

  // A profile that already has a PIN (one adopted from before accounts) can't
  // be edited without it, and setup has no business asking for it: it is
  // picked, PIN and all, on the profile picker after setup.
  if (target?.hasPin) {
    return (
      <div className="space-y-4">
        <StepHeading title="Who's watching?">
          Your profile, {target.name}, has a PIN. You&apos;ll enter it when setup is done, and can rename it in Settings
          → Profiles.
        </StepHeading>
        <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
          Continue <ArrowRight size={16} />
        </button>
        <BackButton onBack={onBack} />
      </div>
    );
  }

  if (!target) {
    return (
      <div className="space-y-4">
        <StepHeading title="Who's watching?">
          This server already has {profiles.length} profiles. You&apos;ll pick yours when setup is done, and can
          rename any of them in Settings → Profiles.
        </StepHeading>
        <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
          Continue <ArrowRight size={16} />
        </button>
        <BackButton onBack={onBack} />
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <StepHeading title="Who's watching?">
        Your profile holds your history, lists and ratings, here and on the TV. Others in the house can add their own
        later.
      </StepHeading>
      <TextField
        label="Your name"
        type="text"
        value={name}
        onChange={setName}
        autoComplete="nickname"
        maxLength={40}
        error={nameError}
        autoFocus
        required
      />
      <AvatarPicker value={look} onChange={setLook} />
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <button type="submit" disabled={saving} className="btn-primary btn-lg w-full">
        {saving ? <><Loader2 size={16} className="animate-spin" /> Saving…</> : <>Continue <ArrowRight size={16} /></>}
      </button>
      <BackButton onBack={onBack} />
    </form>
  );
}

/** What stops working without a TMDB key, said where someone decides to go on without one. */
export const TMDB_MISSING_EFFECTS = [
  "No posters, backdrops or episode stills anywhere",
  "Search finds nothing",
  "Discover has no trending titles",
  "New titles arrive without cast, runtime or seasons",
];

/**
 * TMDB: required, with a way past it. The key is checked with TMDB when it is
 * saved; going on without one asks once, with what it costs, rather than
 * calling a near-requirement optional and then showing "needs a TMDB API key"
 * on four pages.
 */
function TmdbStep({
  onContinue,
  onBack,
}: {
  /** Whether a key is in place as the step is left. */
  onContinue: (tmdbConfigured: boolean) => void;
  onBack: () => void;
}) {
  const [status, setStatus] = useState<TmdbStatus | null>(null);
  const [confirmingSkip, setConfirmingSkip] = useState(false);
  const onStatus = useCallback((next: TmdbStatus) => {
    setStatus(next);
    if (next.configured) setConfirmingSkip(false);
  }, []);
  const configured = status?.configured === true;

  return (
    <div className="space-y-4">
      <StepHeading title="Artwork and search">
        Fetch gets posters, search results and episode data from TMDB. It needs a free key, which is checked with
        TMDB when you save it.
      </StepHeading>

      <TmdbSettings onStatus={onStatus} />

      {confirmingSkip && !configured && (
        <div role="alert" className="space-y-2 rounded-xl border p-4" style={{ borderColor: "var(--border-strong)", background: "var(--surface)" }}>
          <p className="flex items-center gap-2 text-sm font-semibold" style={{ color: "var(--text)" }}>
            <AlertTriangle size={16} className="text-warning" /> Without a key:
          </p>
          <ul className="list-disc space-y-0.5 pl-6 text-sm" style={{ color: "var(--text-dim)" }}>
            {TMDB_MISSING_EFFECTS.map((effect) => <li key={effect}>{effect}</li>)}
          </ul>
          <p className="text-sm" style={{ color: "var(--text-dim)" }}>You can add one later in Settings → TMDB.</p>
        </div>
      )}

      {configured ? (
        <button type="button" onClick={() => onContinue(true)} className="btn-primary btn-lg w-full">
          Continue <ArrowRight size={16} />
        </button>
      ) : confirmingSkip ? (
        <button type="button" onClick={() => onContinue(false)} className="btn-secondary btn-lg w-full">
          Go on without artwork <ArrowRight size={16} />
        </button>
      ) : (
        <button type="button" onClick={() => setConfirmingSkip(true)} disabled={status === null} className="btn-secondary btn-lg w-full">
          Skip for now
        </button>
      )}
      <BackButton onBack={onBack} />
    </div>
  );
}

type HistorySource = { id: string; name: string; blurb: string; icon: ReactNode; content: ReactNode };

/**
 * Which history sources this server can offer: each one's sign-in needs the
 * server's own client id (Trakt, Simkl, MDBList), and the media servers come
 * from the list it supports. One that can't be offered isn't shown, rather
 * than shown with a Connect button that cannot work.
 */
export async function availableHistorySources(): Promise<{ available: string[]; failed: boolean }> {
  // A check that fails is "we could not ask", not "not offered": it is
  // reported as such, with a retry, rather than quietly dropping the source.
  let failed = false;
  const ask = <T,>(read: () => Promise<T>) =>
    Promise.resolve()
      .then(read)
      .catch(() => {
        failed = true;
        return null;
      });
  const [trakt, simkl, mdblist, media] = await Promise.all([
    ask(() => api.getTraktStatus()),
    ask(() => api.getSimklStatus()),
    ask(() => api.getMdbListStatus()),
    ask(() => api.getMediaServers()),
  ]);
  const available: string[] = [];
  if (media && media.providers.length > 0) available.push("media-servers");
  if (trakt?.configured) available.push("trakt");
  if (simkl?.configured) available.push("simkl");
  if (mdblist?.configured) available.push("mdblist");
  return { available, failed };
}

const HISTORY_SOURCES: HistorySource[] = [
  { id: "media-servers", name: "Plex, Jellyfin or Emby", blurb: "What you watch there is marked watched here, with resume points.", icon: <Server size={18} />, content: <MediaServersSettings /> },
  { id: "trakt", name: "Trakt", blurb: "Your history, watchlist and ratings, kept in sync both ways.", icon: <LinkIcon size={18} />, content: <TraktSettings /> },
  { id: "simkl", name: "Simkl", blurb: "Your watched history and plan-to-watch list.", icon: <ListChecks size={18} />, content: <SimklSettings /> },
  { id: "mdblist", name: "MDBList", blurb: "Your watched history and watchlist.", icon: <ListChecks size={18} />, content: <MdbListSettings /> },
];

/** "Bring your history": every source this server supports, each opening in place. */
function HistoryStep({ onContinue, onBack }: { onContinue: () => void; onBack: () => void }) {
  const [available, setAvailable] = useState<string[] | null>(null);
  const [checkFailed, setCheckFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void availableHistorySources().then((result) => {
      if (cancelled) return;
      setAvailable(result.available);
      setCheckFailed(result.failed);
    });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const retry = () => {
    setAvailable(null);
    setCheckFailed(false);
    setAttempt((n) => n + 1);
  };

  const sources = HISTORY_SOURCES.filter((source) => available?.includes(source.id));

  return (
    <div className="space-y-4">
      <StepHeading title="Bring your history">
        Connect where you already keep track, and what you&apos;ve watched fills in on its own. You can add more later
        in Settings.
      </StepHeading>

      {available === null ? (
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
          <Loader2 size={16} className="animate-spin" /> Checking what this server offers…
        </p>
      ) : sources.length === 0 && !checkFailed ? (
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          This server isn&apos;t set up for any history sources yet. You can add titles yourself from Search.
        </p>
      ) : sources.length === 0 ? null : (
        <ul className="space-y-2">
          {sources.map((source) => {
            const expanded = open === source.id;
            const panelId = `history-source-${source.id}`;
            return (
              <li key={source.id} className="rounded-xl border" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
                <button
                  type="button"
                  onClick={() => setOpen(expanded ? null : source.id)}
                  aria-expanded={expanded}
                  aria-controls={panelId}
                  className="flex w-full items-center gap-3 rounded-xl p-3 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                >
                  <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg" style={{ background: "var(--surface-strong)", color: "var(--text-mute)" }}>
                    {source.icon}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold" style={{ color: "var(--text)" }}>{source.name}</span>
                    <span className="block text-xs leading-snug" style={{ color: "var(--text-mute)" }}>{source.blurb}</span>
                  </span>
                  <ChevronDown size={16} className={`flex-none transition-transform ${expanded ? "rotate-180" : ""}`} style={{ color: "var(--text-mute)" }} />
                </button>
                {expanded && (
                  <div id={panelId} className="border-t p-4" style={{ borderColor: "var(--border)" }}>
                    {source.content}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {available !== null && checkFailed && (
        <div role="alert" className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-danger">
          <span className="flex items-center gap-2"><AlertCircle size={16} /> Couldn&apos;t check every source with the server.</span>
          <button type="button" onClick={retry} className="btn-secondary btn-sm">Try again</button>
        </div>
      )}

      <button type="button" onClick={onContinue} className="btn-primary btn-lg w-full">
        Continue <ArrowRight size={16} />
      </button>
      <BackButton onBack={onBack} />
    </div>
  );
}

/** "Have a Fetch TV?": the pairing box, skippable. */
function TvStep({ onContinue, onBack }: { onContinue: () => void; onBack: () => void }) {
  const [paired, setPaired] = useState<string[]>([]);

  return (
    <div className="space-y-4">
      <StepHeading title="Have a Fetch TV? Sign it in">
        On the TV, open <strong>Settings → Account → Fetch website</strong>. It shows a code: type it here, or scan its
        QR code with your phone.
      </StepHeading>

      {paired.length > 0 && (
        <ul className="space-y-1" aria-label="TVs signed in">
          {paired.map((name) => (
            <li key={name} className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
              <CheckCircle2 size={16} className="text-success" /> {name} is signed in.
            </li>
          ))}
        </ul>
      )}

      <div className="flex items-start gap-3">
        <Tv size={20} className="mt-1.5 flex-none" style={{ color: "var(--text-mute)" }} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <PairingCodeForm onPaired={(name) => setPaired((prev) => [...prev, name])} />
        </div>
      </div>

      <button type="button" onClick={onContinue} className={`${paired.length > 0 ? "btn-primary" : "btn-secondary"} btn-lg w-full`}>
        {paired.length > 0 ? <>Continue <ArrowRight size={16} /></> : "Skip, I'll do it later"}
      </button>
      <BackButton onBack={onBack} />
    </div>
  );
}

function DoneStep({ tmdbConfigured, onFinish, onBack }: { tmdbConfigured: boolean; onFinish: () => void; onBack: () => void }) {
  return (
    <div className="space-y-4 text-center">
      <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/15 ring-1 ring-emerald-500/20">
        <Check className="h-7 w-7 text-success" />
      </div>
      <div>
        <h1 className={SECTION_TITLE}>You're all set</h1>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          {/* Only promises the trending starter when it can appear: it needs
              a TMDB key, and only shows on a Shelf with nothing on it yet. */}
          {tmdbConfigured
            ? "If your shelf is still empty, it opens on what's trending this week: tap + on anything you want to watch. "
            : "Your shelf fills in from Search and the sources you connected. Add a TMDB key in Settings for artwork and search. "}
          The TV reads the same lists, and the TV page is where you manage it.
        </p>
      </div>
      <button
        type="button"
        onClick={onFinish}
        className="btn-primary btn-lg w-full"
      >
        Open my shelf
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
  const [tmdbConfigured, setTmdbConfigured] = useState(false);

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
    if (mode === "bootstrap") setStep("profile");
    else finish();
  };

  // Every step past the first is reachable in both directions: a sign-in
  // started by mistake was otherwise only undoable from Settings.
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
        <SignedInStep account={account} onContinue={() => setStep("profile")} />
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
      {step === "profile" && (
        <ProfileStep suggestedName={account?.username ?? ""} onContinue={() => setStep("tmdb")} onBack={goBack} />
      )}
      {step === "tmdb" && (
        <TmdbStep
          onContinue={(configured) => {
            setTmdbConfigured(configured);
            setStep("history");
          }}
          onBack={goBack}
        />
      )}
      {step === "history" && <HistoryStep onContinue={() => setStep("tv")} onBack={goBack} />}
      {step === "tv" && <TvStep onContinue={() => setStep("done")} onBack={goBack} />}
      {step === "done" && <DoneStep tmdbConfigured={tmdbConfigured} onFinish={finish} onBack={goBack} />}
    </WizardShell>
  );
}
