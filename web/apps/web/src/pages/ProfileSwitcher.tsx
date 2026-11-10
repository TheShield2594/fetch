import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, ArrowRight, Loader2, Lock, Plus, RotateCw, X } from "lucide-react";
import { isValidPinFormat, MAX_PIN_LENGTH, MIN_PIN_LENGTH, PIN_FORMAT_ERROR } from "@fetch/shared/pin";
import { api, ApiError, type Profile, runtimeConfig } from "../api";
import { BrandLockup, BrandMark, BrandWordmark } from "../components/BrandMark";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { useExitAnimation } from "../hooks/useExitAnimation";
import { useFocusTrap } from "../hooks/useFocusTrap";
import { useScrollLock } from "../hooks/useScrollLock";
import { SECTION_TITLE } from "../components/typography";
import { ProfileAvatar } from "../components/ProfileAvatar";

const LABEL_CLASS = "block text-xs font-semibold";
const INPUT_CLASS = "w-full rounded-xl border px-4 py-3 text-sm field-control";
const INPUT_STYLE = { borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" };

// Standing in for the switcher's own title. As a full page (first run) it is the
// document's h1; opened as a modal over the app, the page behind already owns the
// h1, so the same title drops a level.
type HeadingTag = "h1" | "h2";

/**
 * Starts a request flow and returns whether it is still the one that counts.
 * A flow is overtaken by the next one, by closing the switcher, or by leaving
 * the form it started from; its late answer must then change nothing.
 */
type StartFlow = () => () => boolean;

function Shell({
  children,
  onClose,
  onExiting,
}: {
  children: React.ReactNode;
  onClose?: (() => void) | undefined;
  /** Called as the close animation starts, before `onClose`: the user has left. */
  onExiting?: (() => void) | undefined;
}) {
  // All three gated on `onClose`: without it this renders as a full page
  // (first-run profile setup), not a modal, and must not lock scroll or
  // swallow Escape.
  const isModal = !!onClose;
  const dialogRef = useFocusTrap<HTMLDivElement>(isModal);
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(() => onClose?.());
  useScrollLock(isModal);
  useEscapeKey(requestClose, isModal);
  useEffect(() => {
    if (exiting) onExiting?.();
  }, [exiting, onExiting]);

  if (onClose) {
    return (
      <div
        className={`overlay-scrim overlay-fade fixed inset-0 z-[200] flex items-center justify-center px-6 py-12 ${exiting ? "overlay-exit" : ""}`}
        onClick={requestClose}
        role="dialog"
        aria-modal="true"
        aria-label="Switch profile"
      >
        <div
          ref={dialogRef}
          tabIndex={-1}
          inert={exiting}
          className={`overlay-dialog w-full max-w-md space-y-6 ${exiting ? "overlay-exit" : ""}`}
          onClick={(e) => e.stopPropagation()}
          onAnimationEnd={onExitAnimationEnd}
        >
          <div className="flex items-center justify-center gap-2.5">
            <BrandMark className="h-10 w-10 flex-none" />
            <BrandWordmark className="h-5 w-auto" style={{ color: "var(--text)" }} />
          </div>

          <div className="glass-surface relative rounded-3xl border p-6 shadow-e3" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
            <button
              type="button"
              onClick={requestClose}
              aria-label="Close"
              className="tap-target absolute right-4 top-4 flex h-8 w-8 items-center justify-center rounded-full transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
              style={{ color: "var(--text-mute)" }}
            >
              <X className="h-4 w-4" />
            </button>
            {children}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-6 py-12">
      <div className="w-full max-w-md space-y-6">
        <BrandLockup />

        <div className="rounded-2xl border p-6 shadow-e1" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
          {children}
        </div>
      </div>
    </div>
  );
}

function CreateProfileForm({
  title,
  subtitle,
  headingTag: Heading,
  startFlow,
  onCreated,
  onCancel,
}: {
  title: string;
  subtitle: string;
  headingTag: HeadingTag;
  startFlow: StartFlow;
  onCreated: (profile: Profile) => void;
  onCancel?: (() => void) | undefined;
}) {
  const [name, setName] = useState("");
  const [pin, setPin] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameId = useId();
  const pinId = useId();
  const pinHintId = useId();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName) return;
    const trimmedPin = pin.trim();
    if (trimmedPin && !isValidPinFormat(trimmedPin)) {
      setError(`${PIN_FORMAT_ERROR}.`);
      return;
    }

    const isCurrent = startFlow();
    setCreating(true);
    setError(null);
    try {
      const { profile } = await api.createProfile({
        name: trimmedName,
        pin: trimmedPin || undefined,
      });
      // POST /profiles mints no profile token, and the one in storage (if any)
      // belongs to the previous profile. Without a fresh one, the first request
      // as a PIN-protected profile 401s and drops the user straight back to
      // "Who's watching?" to type the PIN they set a second ago. Verifying with
      // no PIN also clears the stale token for an unprotected profile.
      let profileToken: string | null;
      try {
        profileToken = (await api.verifyProfile(profile.id, trimmedPin || undefined, { persist: false })).profileToken ?? "";
      } catch {
        // The profile exists either way; worst case the shell asks for the
        // PIN once, which is what happened before. Don't send a stale token.
        profileToken = null;
      }
      // Closed, cancelled or overtaken meanwhile: the profile exists, but the
      // user has moved on, so the active profile and its token stay as they are.
      if (!isCurrent()) return;
      if (profileToken === null) runtimeConfig.clearProfileToken();
      else runtimeConfig.setProfileToken(profileToken);
      onCreated(profile);
    } catch (err) {
      if (isCurrent()) setError(err instanceof Error ? err.message : "Could not create profile.");
    } finally {
      setCreating(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>{title}</Heading>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>{subtitle}</p>
      </div>
      {/* Visible labels rather than placeholders, which vanished on the
          first keystroke. */}
      <div>
        <label htmlFor={nameId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>Profile name</label>
        <input
          id={nameId}
          autoFocus
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          className={`mt-1.5 ${INPUT_CLASS}`}
          style={INPUT_STYLE}
        />
      </div>
      <div>
        <label htmlFor={pinId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>
          PIN <span className="font-normal" style={{ color: "var(--text-mute)" }}>(optional)</span>
        </label>
        <input
          id={pinId}
          type="password"
          inputMode="numeric"
          autoComplete="new-password"
          // The same 4–8 digit rule the API enforces and the TV keypad asks for.
          // Checked on submit rather than with minLength/pattern, so the message
          // says "digits" instead of the browser's generic one.
          aria-describedby={pinHintId}
          value={pin}
          onChange={(e) => setPin(e.target.value)}
          className={`mt-1.5 ${INPUT_CLASS}`}
          style={INPUT_STYLE}
        />
        <p id={pinHintId} className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
          {MIN_PIN_LENGTH}–{MAX_PIN_LENGTH} digits
        </p>
      </div>
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <div className="flex gap-2">
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="btn-secondary btn-lg flex-1"
          >
            Cancel
          </button>
        )}
        <button
          type="submit"
          disabled={creating || !name.trim()}
          className="btn-primary btn-lg flex-1"
        >
          {creating ? <><Loader2 size={16} className="animate-spin" /> Creating…</> : <>Create <ArrowRight size={16} /></>}
        </button>
      </div>
    </form>
  );
}

function PinPrompt({
  profile,
  headingTag: Heading,
  startFlow,
  onVerified,
  onBack,
}: {
  profile: Profile;
  headingTag: HeadingTag;
  startFlow: StartFlow;
  onVerified: (profile: Profile) => void;
  onBack: () => void;
}) {
  const [pin, setPin] = useState("");
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pinId = useId();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const isCurrent = startFlow();
    setVerifying(true);
    setError(null);
    try {
      const result = await api.verifyProfile(profile.id, pin.trim(), { persist: false });
      if (!isCurrent()) return;
      runtimeConfig.setProfileToken(result.profileToken ?? "");
      onVerified({ id: result.id, name: result.name, hasPin: true });
    } catch (err) {
      if (!isCurrent()) return;
      if (err instanceof ApiError && err.status === 401) {
        setError("Incorrect PIN.");
      } else {
        setError(err instanceof Error ? err.message : "Could not verify PIN.");
      }
    } finally {
      setVerifying(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>Enter PIN for {profile.name}</Heading>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>This profile is PIN-protected.</p>
      </div>
      <div>
        <label htmlFor={pinId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>PIN</label>
        <input
          id={pinId}
          autoFocus
          type="password"
          inputMode="numeric"
          value={pin}
          onChange={(e) => setPin(e.target.value)}
          className={`mt-1.5 ${INPUT_CLASS}`}
          style={INPUT_STYLE}
        />
      </div>
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onBack}
          className="btn-secondary btn-lg flex-1"
        >
          Back
        </button>
        <button
          type="submit"
          disabled={verifying || !pin.trim()}
          className="btn-primary btn-lg flex-1"
        >
          {verifying ? <Loader2 size={16} className="animate-spin" /> : <>Unlock <ArrowRight size={16} /></>}
        </button>
      </div>
    </form>
  );
}

function ProfilePicker({
  profiles,
  headingTag: Heading,
  error,
  onSelect,
  onCreateNew,
}: {
  profiles: Profile[];
  headingTag: HeadingTag;
  /** Why the last pick didn't go through; the list stays, so another try is one click. */
  error: string | null;
  onSelect: (profile: Profile) => void;
  onCreateNew: () => void;
}) {
  return (
    <div className="space-y-4">
      <div>
        <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>Who's watching?</Heading>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>Choose a profile to continue.</p>
      </div>
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <div className="space-y-2">
        {profiles.map((profile) => (
          <button
            key={profile.id}
            type="button"
            onClick={() => onSelect(profile)}
            className="flex w-full items-center gap-3 rounded-xl border px-4 py-3 text-left text-sm font-medium transition-colors hover:border-claw-500/60 hover:bg-[var(--surface)]"
            style={{ borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-1)" }}
          >
            <ProfileAvatar profile={profile} />
            <span className="flex-1">{profile.name}</span>
            {profile.hasPin && <Lock className="h-4 w-4" style={{ color: "var(--text-mute)" }} />}
          </button>
        ))}
      </div>
      <button
        type="button"
        onClick={onCreateNew}
        className="btn-secondary btn-lg w-full"
      >
        <Plus size={16} /> New Profile
      </button>
    </div>
  );
}

export function ProfileSwitcher({ onSelected, onClose }: { onSelected: (profile: Profile) => void; onClose?: (() => void) | undefined }) {
  const [loading, setLoading] = useState(true);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [mode, setModeState] = useState<"picker" | "create" | "pin">("picker");
  const [pinTarget, setPinTarget] = useState<Profile | null>(null);
  const headingTag: HeadingTag = onClose ? "h2" : "h1";

  // Which request flow (a pick, a create, a PIN check) is current. Bumped by
  // every new flow, every change of screen, closing, and unmounting, so a
  // request that comes back after any of those changes nothing: no profile
  // token written or cleared, no switch to a profile the user walked away from.
  const flow = useRef(0);
  const cancelFlows = useCallback(() => {
    flow.current += 1;
  }, []);
  const startFlow = useCallback<StartFlow>(() => {
    flow.current += 1;
    const id = flow.current;
    return () => flow.current === id;
  }, []);
  useEffect(() => cancelFlows, [cancelFlows]);

  const setMode = (next: "picker" | "create" | "pin") => {
    cancelFlows();
    setSelectError(null);
    setModeState(next);
  };

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { profiles: fetched } = await api.getProfiles();
        if (cancelled) return;
        setProfiles(fetched);
        setLoadError(null);
        setModeState(fetched.length === 0 ? "create" : "picker");
      } catch (err) {
        if (!cancelled) setLoadError(err instanceof Error ? err.message : "Could not load profiles.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadAttempt]);

  const retryLoad = () => {
    setLoading(true);
    setLoadError(null);
    setLoadAttempt((n) => n + 1);
  };

  const selectProfile = async (profile: Profile) => {
    if (profile.hasPin) {
      setPinTarget(profile);
      setMode("pin");
      return;
    }
    const isCurrent = startFlow();
    setSelectError(null);
    try {
      const result = await api.verifyProfile(profile.id, undefined, { persist: false });
      if (!isCurrent()) return;
      runtimeConfig.setProfileToken(result.profileToken ?? "");
      runtimeConfig.setProfileId(result.id);
      onSelected({ id: result.id, name: result.name, hasPin: profile.hasPin });
    } catch (err) {
      if (isCurrent()) setSelectError(err instanceof Error ? err.message : "Could not select profile.");
    }
  };

  // Neither branch has a title to show, but both are still the whole surface, so
  // they carry a hidden one rather than leaving the outline empty.
  const Heading = headingTag;

  if (loading) {
    return (
      <Shell onClose={onClose} onExiting={cancelFlows}>
        <Heading className="sr-only">Profiles</Heading>
        <div className="flex items-center justify-center gap-2 py-6 text-sm" style={{ color: "var(--text-mute)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading profiles...
        </div>
      </Shell>
    );
  }

  // Only a failed load takes the whole surface, since there is no list to show;
  // it keeps a way forward (on /pair from a TV's QR code there is no other).
  if (loadError) {
    return (
      <Shell onClose={onClose} onExiting={cancelFlows}>
        <Heading className="sr-only">Profiles</Heading>
        <div className="space-y-4">
          <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {loadError}</p>
          <button type="button" onClick={retryLoad} className="btn-secondary btn-lg w-full">
            <RotateCw size={16} /> Try again
          </button>
        </div>
      </Shell>
    );
  }

  return (
    <Shell onClose={onClose} onExiting={cancelFlows}>
      {mode === "picker" && (
        <ProfilePicker
          profiles={profiles}
          headingTag={headingTag}
          error={selectError}
          onSelect={selectProfile}
          onCreateNew={() => setMode("create")}
        />
      )}
      {mode === "create" && (
        <CreateProfileForm
          title={profiles.length === 0 ? "Create your first profile" : "New Profile"}
          subtitle="Each profile gets its own watch history, lists, and stats."
          headingTag={headingTag}
          startFlow={startFlow}
          onCreated={(profile) => {
            runtimeConfig.setProfileId(profile.id);
            onSelected(profile);
          }}
          onCancel={profiles.length > 0 ? () => setMode("picker") : undefined}
        />
      )}
      {mode === "pin" && pinTarget && (
        <PinPrompt
          profile={pinTarget}
          headingTag={headingTag}
          startFlow={startFlow}
          onVerified={(profile) => {
            runtimeConfig.setProfileId(profile.id);
            onSelected(profile);
          }}
          onBack={() => setMode("picker")}
        />
      )}
    </Shell>
  );
}
