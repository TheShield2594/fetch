import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, ArrowLeft, Loader2, Lock, Plus, RotateCw, X } from "lucide-react";
import { api, ApiError, type Profile, runtimeConfig } from "../api";
import { BrandLockup, BrandMark, BrandWordmark } from "../components/BrandMark";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { useExitAnimation } from "../hooks/useExitAnimation";
import { useFocusTrap } from "../hooks/useFocusTrap";
import { useScrollLock } from "../hooks/useScrollLock";
import { lockActiveProfile } from "../hooks/useProfile";
import { formatCountdown, useCountdown } from "../hooks/useCountdown";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { ProfileAvatar } from "../components/ProfileAvatar";
import { KidsChip } from "../components/profile/ProfileBadges";
import { CreateProfileFlow, type StartFlow } from "../components/profile/CreateProfileFlow";
import { PinField } from "../components/profile/PinField";

// Standing in for the switcher's own title. As a full page (first run) it is the
// document's h1; opened as a modal over the app, the page behind already owns the
// h1, so the same title drops a level.
type HeadingTag = "h1" | "h2";

/**
 * The whole screen, in both its uses: the gate before the app (first run, a
 * lapsed PIN) and the switcher opened over it. It was a 448px card of rows in
 * both, which made the 40 illustrated avatars 32px in the one place they
 * should be the hero.
 */
function Shell({
  children,
  onClose,
  onExiting,
  wide = true,
}: {
  children: React.ReactNode;
  onClose?: (() => void) | undefined;
  /** Called as the close animation starts, before `onClose`: the user has left. */
  onExiting?: (() => void) | undefined;
  /** The picker's grid; forms keep a reading width. */
  wide?: boolean | undefined;
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

  const width = wide ? "max-w-4xl" : "max-w-lg";

  if (onClose) {
    return (
      <div
        className={`overlay-fade fixed inset-0 z-[200] overflow-y-auto ${exiting ? "overlay-exit" : ""}`}
        style={{ background: "var(--bg-0)" }}
        role="dialog"
        aria-modal="true"
        aria-label="Switch profile"
      >
        <div
          ref={dialogRef}
          tabIndex={-1}
          inert={exiting}
          className={`overlay-dialog relative flex min-h-full flex-col items-center px-5 pb-12 pt-6 focus:outline-none sm:px-8 ${exiting ? "overlay-exit" : ""}`}
          onAnimationEnd={onExitAnimationEnd}
        >
          <div className="flex w-full max-w-4xl items-center justify-between">
            <div className="flex items-center gap-2.5">
              <BrandMark className="h-8 w-8 flex-none" />
              <BrandWordmark className="h-4 w-auto" style={{ color: "var(--text)" }} />
            </div>
            <button
              type="button"
              onClick={requestClose}
              aria-label="Close"
              className="tap-target flex h-9 w-9 items-center justify-center rounded-full transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
              style={{ color: "var(--text-mute)" }}
            >
              <X className="h-5 w-5" />
            </button>
          </div>
          <div className={`my-auto w-full ${width} py-10`}>{children}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col items-center px-5 pb-12 pt-8 sm:px-8">
      <BrandLockup />
      <div className={`my-auto w-full ${width} py-10`}>{children}</div>
    </div>
  );
}

/** "Too many tries" with the time left, ticking, or nothing once it has run out. */
function LockoutNotice({ until, onExpired }: { until: number; onExpired: () => void }) {
  const left = useCountdown(until);
  useEffect(() => {
    if (left === 0) onExpired();
  }, [left, onExpired]);
  if (left === 0) return null;
  return (
    <p role="status" className="flex items-center justify-center gap-2 text-sm text-danger">
      <Lock size={15} aria-hidden="true" />
      <span>
        Too many wrong PINs. Try again in <span className="font-semibold tabular-nums">{formatCountdown(left)}</span>.
      </span>
    </p>
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
  const [shake, setShake] = useState(0);
  const [lockedUntil, setLockedUntil] = useState<number | null>(null);
  // A PIN chosen before the 4–8 digit rule may hold letters or run longer, and
  // still has to open the profile: a plain field takes anything.
  const [typed, setTyped] = useState(false);
  const pinId = useId();
  const locked = lockedUntil !== null;

  const verify = async (value: string) => {
    const attempt = value.trim();
    if (!attempt || verifying || locked) return;
    const isCurrent = startFlow();
    setVerifying(true);
    setError(null);
    try {
      const result = await api.verifyProfile(profile.id, attempt, { persist: false });
      if (!isCurrent()) return;
      runtimeConfig.setProfileToken(result.profileToken ?? "");
      onVerified({ ...profile, id: result.id, name: result.name, hasPin: true });
    } catch (err) {
      if (!isCurrent()) return;
      setPin("");
      if (err instanceof ApiError && err.status === 429) {
        // The server's lockout says how long; the limiter in front of it may
        // not, and then a minute is the honest guess.
        setLockedUntil(Date.now() + (err.retryAfterSec ?? 60) * 1000);
      } else if (err instanceof ApiError && err.status === 401) {
        setError("Incorrect PIN.");
        setShake((n) => n + 1);
      } else {
        setError(err instanceof Error ? err.message : "Could not check the PIN.");
      }
    } finally {
      if (isCurrent()) setVerifying(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void verify(pin);
  };

  const unlockLockout = useCallback(() => setLockedUntil(null), []);

  return (
    <form onSubmit={submit} className="mx-auto max-w-md space-y-6 text-center">
      <div className="flex flex-col items-center gap-4">
        <ProfileAvatar profile={profile} size="xl" />
        <div>
          <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>Enter PIN for {profile.name}</Heading>
          <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>This profile is PIN-protected.</p>
        </div>
      </div>
      <div className="flex flex-col items-center gap-3">
        {typed ? (
          <input
            id={pinId}
            autoFocus
            type="password"
            aria-label="PIN"
            value={pin}
            disabled={locked}
            onChange={(e) => setPin(e.target.value)}
            className="w-full max-w-xs rounded-xl border px-4 py-3 text-center text-sm field-control"
            style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
          />
        ) : (
          <PinField
            id={pinId}
            label="PIN"
            size="lg"
            autoFocus
            value={pin}
            onChange={(next) => {
              setPin(next);
              if (error) setError(null);
            }}
            onComplete={(full) => void verify(full)}
            disabled={locked || verifying}
            invalid={!!error}
            shakeKey={shake}
          />
        )}
        <button
          type="button"
          onClick={() => {
            setTyped((was) => !was);
            setPin("");
          }}
          className="text-xs underline-offset-2 hover:underline"
          style={{ color: "var(--text-mute)" }}
        >
          {typed ? "Use the number boxes" : "PIN has letters or more than 8 digits?"}
        </button>
      </div>
      {lockedUntil !== null && <LockoutNotice key={lockedUntil} until={lockedUntil} onExpired={unlockLockout} />}
      {error && (
        <p role="alert" className="flex items-center justify-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <div className="flex gap-2">
        <button type="button" onClick={onBack} className="btn-secondary btn-lg flex-1">
          <ArrowLeft size={16} /> Back
        </button>
        <button type="submit" disabled={verifying || locked || !pin.trim()} className="btn-primary btn-lg flex-1">
          {verifying ? <Loader2 size={16} className="animate-spin" /> : null} Unlock
        </button>
      </div>
    </form>
  );
}

/** What a tile says to a screen reader beyond the name. */
const tileDescription = (profile: Profile, isActive: boolean) =>
  [
    isActive && "in use",
    profile.parental?.isKidsProfile && "kids profile",
    profile.hasPin && "PIN protected",
  ].filter(Boolean).join(", ");

function ProfilePicker({
  profiles,
  activeId,
  headingTag: Heading,
  error,
  pending,
  onSelect,
  onCreateNew,
  lockable,
}: {
  profiles: Profile[];
  activeId: string | null;
  headingTag: HeadingTag;
  /** Why the last pick didn't go through; the grid stays, so another try is one click. */
  error: string | null;
  /** The profile being opened, while its request is out. */
  pending: string | null;
  onSelect: (profile: Profile) => void;
  onCreateNew: () => void;
  /** The PIN profile in use, when the switcher is opened over the app: it can be locked from here. */
  lockable?: Profile | null | undefined;
}) {
  return (
    <div className="space-y-8 text-center">
      <Heading className={PAGE_TITLE} style={{ color: "var(--text)" }}>Who&apos;s watching?</Heading>
      {error && (
        <p role="alert" className="flex items-center justify-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      <ul className="flex flex-wrap justify-center gap-x-5 gap-y-3 sm:gap-x-8 sm:gap-y-6">
        {profiles.map((profile) => {
          const isActive = profile.id === activeId;
          const description = tileDescription(profile, isActive);
          return (
            <li key={profile.id} className="w-28 sm:w-36">
              <button
                type="button"
                onClick={() => onSelect(profile)}
                disabled={pending !== null}
                aria-label={description ? `${profile.name}, ${description}` : profile.name}
                className="group flex w-full flex-col items-center gap-2.5 rounded-2xl p-1.5 focus:outline-none disabled:cursor-wait"
              >
                <span
                  className="relative rounded-full transition-[transform,box-shadow] duration-base group-hover:scale-[1.04] group-active:scale-[0.97] group-focus-visible:ring-4 group-focus-visible:ring-focus"
                  style={{ boxShadow: isActive ? "0 0 0 3px var(--bg-0), 0 0 0 6px rgb(var(--accent-rgb))" : undefined }}
                >
                  <ProfileAvatar profile={profile} size="xl" />
                  {profile.hasPin && (
                    <span
                      aria-hidden="true"
                      className="absolute bottom-0.5 right-0.5 flex h-8 w-8 items-center justify-center rounded-full"
                      style={{ background: "var(--bg-1)", border: "2px solid var(--bg-0)", color: "var(--text-dim)" }}
                    >
                      <Lock size={14} />
                    </span>
                  )}
                  {pending === profile.id && (
                    <span aria-hidden="true" className="absolute inset-0 flex items-center justify-center rounded-full bg-black/40">
                      <Loader2 size={28} className="animate-spin text-white" />
                    </span>
                  )}
                </span>
                <span className="w-full truncate text-base font-semibold" style={{ color: isActive ? "var(--text)" : "var(--text-dim)" }}>
                  {profile.name}
                </span>
                <span aria-hidden="true" className="-mt-1 flex min-h-5 items-center gap-1.5">
                  {profile.parental?.isKidsProfile && <KidsChip />}
                  {isActive && <span className="text-2xs font-semibold text-accent-text">In use</span>}
                </span>
              </button>
            </li>
          );
        })}
        <li className="w-28 sm:w-36">
          <button
            type="button"
            onClick={onCreateNew}
            disabled={pending !== null}
            className="group flex w-full flex-col items-center gap-2.5 rounded-2xl p-1.5 focus:outline-none"
          >
            <span
              className="flex h-24 w-24 items-center justify-center rounded-full border-2 border-dashed transition-[transform,border-color] duration-base group-hover:scale-[1.04] group-hover:border-[var(--text-mute)] group-focus-visible:ring-4 group-focus-visible:ring-focus sm:h-32 sm:w-32"
              style={{ borderColor: "var(--border-strong)", color: "var(--text-mute)" }}
            >
              <Plus className="h-8 w-8 sm:h-10 sm:w-10" aria-hidden="true" />
            </span>
            <span className="text-base font-semibold" style={{ color: "var(--text-dim)" }}>Add profile</span>
          </button>
        </li>
      </ul>
      {/* The phone's way to lock (the rail's profile menu has its own): back to
          this grid with the profile closed, still signed in. */}
      {lockable && (
        <button type="button" onClick={lockActiveProfile} className="btn-secondary mx-auto">
          <Lock size={16} /> Lock {lockable.name}
        </button>
      )}
    </div>
  );
}

export function ProfileSwitcher({ onSelected, onClose }: { onSelected: (profile: Profile) => void; onClose?: (() => void) | undefined }) {
  const [loading, setLoading] = useState(true);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [mode, setModeState] = useState<"picker" | "create" | "pin">("picker");
  const [pinTarget, setPinTarget] = useState<Profile | null>(null);
  const headingTag: HeadingTag = onClose ? "h2" : "h1";
  // Read once: the profile this browser is in as the picker opens, marked in the grid.
  const [activeId] = useState(() => runtimeConfig.getProfileId() || null);

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
    setPending(null);
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
    setPending(profile.id);
    try {
      const result = await api.verifyProfile(profile.id, undefined, { persist: false });
      if (!isCurrent()) return;
      runtimeConfig.setProfileToken(result.profileToken ?? "");
      runtimeConfig.setProfileId(result.id);
      onSelected({ ...profile, id: result.id, name: result.name });
    } catch (err) {
      if (isCurrent()) setSelectError(err instanceof Error ? err.message : "Could not open that profile.");
    } finally {
      if (isCurrent()) setPending(null);
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
          <Loader2 size={16} className="animate-spin" /> Loading profiles…
        </div>
      </Shell>
    );
  }

  // Only a failed load takes the whole surface, since there is no list to show;
  // it keeps a way forward (on /pair from a TV's QR code there is no other).
  if (loadError) {
    return (
      <Shell onClose={onClose} onExiting={cancelFlows} wide={false}>
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

  const activeName = profiles.find((p) => p.id === activeId)?.name;

  return (
    <Shell onClose={onClose} onExiting={cancelFlows} wide={mode === "picker"}>
      {mode === "picker" && (
        <ProfilePicker
          profiles={profiles}
          activeId={activeId}
          headingTag={headingTag}
          error={selectError}
          pending={pending}
          onSelect={(profile) => void selectProfile(profile)}
          onCreateNew={() => setMode("create")}
          lockable={onClose ? profiles.find((p) => p.hasPin && p.id === activeId) : null}
        />
      )}
      {mode === "create" && (
        <div className="rounded-3xl border p-6 shadow-e1 sm:p-8" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
          <CreateProfileFlow
            title={profiles.length === 0 ? "Create your first profile" : "Add a profile"}
            subtitle="Each profile gets its own watch history, lists, and stats."
            headingTag={headingTag}
            startFlow={startFlow}
            // The first profile is the only one there is to be.
            enterImmediately={profiles.length === 0}
            currentProfileName={activeName}
            onCreated={(created) => setProfiles((prev) => [...prev, created])}
            onEntered={(profile) => {
              runtimeConfig.setProfileId(profile.id);
              onSelected(profile);
            }}
            onDone={() => setMode("picker")}
            onCancel={profiles.length > 0 ? () => setMode("picker") : undefined}
          />
        </div>
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
