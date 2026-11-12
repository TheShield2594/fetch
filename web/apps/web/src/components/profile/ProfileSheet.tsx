import { type FormEvent, type ReactNode, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, Check, Loader2, Lock, Trash2, Unlock, X } from "lucide-react";
import { isValidPinFormat, MAX_PIN_LENGTH, MIN_PIN_LENGTH, PIN_FORMAT_ERROR } from "@fetch/shared/pin";
import {
  api,
  ApiError,
  RATING_LEVELS,
  type KidsRules,
  type Profile,
  type ProfileParental,
  type ProfileSettingsUpdate,
  type ProfileSummary,
  type RatingLevel,
} from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { useEscapeKey } from "../../hooks/useEscapeKey";
import { useExitAnimation } from "../../hooks/useExitAnimation";
import { useFocusTrap } from "../../hooks/useFocusTrap";
import { useScrollLock } from "../../hooks/useScrollLock";
import { useToast } from "../../hooks/useToast";
import { useAccount } from "../../hooks/useAccount";
import { ConfirmReview } from "../ConfirmReview";
import { PasswordField } from "../PasswordField";
import { ProfileAvatar } from "../ProfileAvatar";
import { SelectField } from "../SelectField";
import { AvatarPicker, type AvatarChoice } from "./AvatarPicker";
import { PinField } from "./PinField";
import { ProfileBadges } from "./ProfileBadges";

export const RATING_LABELS: Record<RatingLevel, string> = {
  ALL_AGES: "All ages (G, TV-Y, TV-G)",
  OLDER_KIDS: "Older kids (TV-Y7)",
  PARENTAL_GUIDANCE: "Parental guidance (PG, TV-PG)",
  TEENS: "Teens (PG-13, TV-14)",
  MATURE: "Mature (R, TV-MA)",
  ADULTS_ONLY: "Adults only (NC-17)",
};

/** What the TV applies when a profile is marked as a kids profile. */
const KIDS_PRESET: Pick<ProfileParental, "maxRating" | "allowUnrated"> = {
  maxRating: "PARENTAL_GUIDANCE",
  allowUnrated: false,
};

type Limits = Omit<ProfileParental, "kidsRules">;

const UNRESTRICTED: Limits = { isKidsProfile: false, maxRating: null, allowUnrated: true };

/** The rule fields edited here; approved titles are added on a TV. */
type RulesDraft = Omit<KidsRules, "approvedTitles" | "updatedAt">;

/** What a TV starts a kids profile with (`KidsRules.Default`). */
const DEFAULT_RULES: RulesDraft = {
  autoplayEpisodeCap: 3,
  approvedTitlesOnly: false,
  dailyMinutes: null,
  bedtimeStartMinute: null,
  bedtimeEndMinute: null,
  hardStop: false,
};

/** The TV's choices, so a rule set on either side reads the same on both. */
const AUTOPLAY_CHOICES = [1, 2, 3, 5];
const DAILY_CHOICES = [30, 60, 90, 120, 180];
const DEFAULT_BEDTIME = { start: 20 * 60, end: 7 * 60 };

const rulesOf = (rules: KidsRules | null | undefined): RulesDraft => ({
  autoplayEpisodeCap: rules ? rules.autoplayEpisodeCap : DEFAULT_RULES.autoplayEpisodeCap,
  approvedTitlesOnly: rules?.approvedTitlesOnly ?? DEFAULT_RULES.approvedTitlesOnly,
  dailyMinutes: rules?.dailyMinutes ?? null,
  bedtimeStartMinute: rules?.bedtimeStartMinute ?? null,
  bedtimeEndMinute: rules?.bedtimeEndMinute ?? null,
  hardStop: rules?.hardStop ?? DEFAULT_RULES.hardStop,
});

const sameRules = (a: RulesDraft, b: RulesDraft) =>
  (Object.keys(a) as (keyof RulesDraft)[]).every((key) => a[key] === b[key]);

/** "20:00" for a minute of the day, and back. */
const timeOf = (minute: number) =>
  `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
const minuteOf = (time: string): number | null => {
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
};

const minutesLabel = (minutes: number) =>
  minutes < 60 ? `${minutes} min` : minutes % 60 === 0 ? `${minutes / 60} h` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;

/** The choices, plus the current value when a TV set one that isn't among them. */
const withCurrent = (choices: number[], current: number | null) =>
  current === null || choices.includes(current) ? choices : [...choices, current].sort((a, b) => a - b);

const FIELD_CLASS = "w-full rounded-xl border px-3.5 py-2.5 text-sm field-control";
const FIELD_STYLE = { borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" };
const LABEL = "block text-xs font-semibold";

const plural = (count: number, one: string, many: string) => `${count.toLocaleString()} ${count === 1 ? one : many}`;

/**
 * "Its 312 watched entries, 4 lists (58 titles) and 6 resume points", or null
 * when the profile holds nothing worth naming.
 */
export function describeProfileContents(summary: ProfileSummary): string | null {
  const parts = [
    summary.watchEvents > 0 && plural(summary.watchEvents, "watched entry", "watched entries"),
    summary.lists > 0 &&
      `${plural(summary.lists, "list", "lists")}${summary.listItems > 0 ? ` (${plural(summary.listItems, "title", "titles")})` : ""}`,
    summary.playbackProgress > 0 && plural(summary.playbackProgress, "resume point", "resume points"),
    summary.ratings > 0 && plural(summary.ratings, "rating", "ratings"),
    summary.collections > 0 && plural(summary.collections, "collection", "collections"),
  ].filter((part): part is string => Boolean(part));
  if (parts.length === 0) return null;
  if (parts.length === 1) return parts[0]!;
  return `${parts.slice(0, -1).join(", ")} and ${parts[parts.length - 1]}`;
}

/** A labelled section of the sheet. */
function SheetSection({ title, children, description }: { title: string; description?: ReactNode; children: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="space-y-3 border-t pt-5" style={{ borderColor: "var(--border)" }}>
      <div>
        <h3 id={id} className="text-sm font-semibold" style={{ color: "var(--text)" }}>{title}</h3>
        {description && <p className="mt-0.5 text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>{description}</p>}
      </div>
      {children}
    </section>
  );
}

/** A labelled switch row, as everywhere else in Settings. */
function SwitchRow({
  checked,
  onChange,
  disabled,
  label,
  description,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean | undefined;
  label: string;
  description?: ReactNode;
}) {
  const id = useId();
  return (
    <label
      htmlFor={id}
      className={`flex items-start gap-3 rounded-xl border px-4 py-3.5 transition-colors ${disabled ? "opacity-60" : "cursor-pointer hover:bg-[var(--surface-strong)]"}`}
      style={{ borderColor: "var(--border)", background: "var(--surface)" }}
    >
      <input
        id={id}
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="switch-control mt-0.5"
      />
      <span>
        <span className="block text-sm font-medium" style={{ color: "var(--text)" }}>{label}</span>
        {description && <span className="mt-0.5 block text-xs" style={{ color: "var(--text-mute)" }}>{description}</span>}
      </span>
    </label>
  );
}

const errorText = (err: unknown, fallback: string, wrongPin = "Incorrect PIN.") => {
  if (err instanceof ApiError && err.status === 401) return wrongPin;
  if (err instanceof ApiError && err.status === 429) {
    return err.retryAfterSec
      ? `Too many wrong PINs. Try again in ${Math.ceil(err.retryAfterSec / 60)} min.`
      : "Too many wrong PINs. Try again in a few minutes.";
  }
  return err instanceof Error ? err.message : fallback;
};

/** A new PIN, typed twice. */
function NewPinFields({
  pin,
  confirm,
  onPin,
  onConfirm,
  onReject,
  label,
  errorId,
}: {
  pin: string;
  confirm: string;
  onPin: (value: string) => void;
  onConfirm: (value: string) => void;
  onReject: () => void;
  label: string;
  /** The section's error line, when it has one, for the fields to point at. */
  errorId?: string | undefined;
}) {
  const pinId = useId();
  const hintId = useId();
  const confirmId = useId();
  const mismatchId = useId();
  const mismatch = confirm.length === pin.length && pin.length > 0 && confirm !== pin;
  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={pinId} className={LABEL} style={{ color: "var(--text)" }}>{label}</label>
        <p id={hintId} className="mt-0.5 text-xs" style={{ color: "var(--text-mute)" }}>{MIN_PIN_LENGTH}–{MAX_PIN_LENGTH} digits</p>
        <div className="mt-2">
          <PinField
            id={pinId}
            label={label}
            value={pin}
            onChange={(next) => {
              onPin(next);
              onConfirm("");
            }}
            onReject={onReject}
            invalid={!!errorId}
            describedBy={[hintId, errorId].filter(Boolean).join(" ")}
            autoComplete="new-password"
            autoFocus
          />
        </div>
      </div>
      {pin.length >= MIN_PIN_LENGTH && (
        <div>
          <label htmlFor={confirmId} className={LABEL} style={{ color: "var(--text)" }}>Type it again</label>
          <div className="mt-2 flex items-center gap-3">
            <PinField
              id={confirmId}
              label={`${label}, again`}
              value={confirm}
              onChange={onConfirm}
              length={pin.length}
              invalid={mismatch}
              describedBy={mismatch ? mismatchId : undefined}
              autoComplete="new-password"
            />
            {confirm.length === pin.length && !mismatch && <Check size={18} className="flex-none text-success" aria-label="PINs match" />}
          </div>
          {mismatch && <p id={mismatchId} className="mt-1.5 text-xs text-danger">The two PINs don&apos;t match.</p>}
        </div>
      )}
    </div>
  );
}

/**
 * A forgotten PIN, cleared by the admin with their account password (the
 * server allows it only for a profile in the admin's own account). The
 * profile is then open to anyone using the account until a new PIN is set.
 */
function ClearPinReview({ profile, onCleared, onCancel }: { profile: Profile; onCleared: (p: Profile) => void; onCancel: () => void }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  // The password's own errors go on its field, where they mark it invalid
  // and are read with it; anything else is the review's.
  const [passwordError, setPasswordError] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  const clear = async () => {
    if (!password) {
      setPasswordError("Enter your account password.");
      return;
    }
    setBusy(true);
    setError(null);
    setPasswordError(undefined);
    try {
      const { profile: updated } = await api.clearProfilePin(profile.id, password);
      onCleared(updated);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) setPasswordError("Incorrect password.");
      else setError(err instanceof Error ? err.message : "Could not clear the PIN.");
      setBusy(false);
    }
  };

  return (
    <ConfirmReview
      label={`Clear the PIN on ${profile.name}?`}
      message={
        <>
          Clear the PIN on <strong>{profile.name}</strong>? Anyone using this account will be able to open it until you
          set a new one.
        </>
      }
      confirmLabel="Clear PIN"
      icon={<Unlock size={14} />}
      onConfirm={() => void clear()}
      onCancel={onCancel}
      busy={busy}
      error={error}
    >
      <PasswordField
        label="Your account password"
        value={password}
        onChange={setPassword}
        error={passwordError}
        autoComplete="current-password"
        autoFocus
      />
    </ConfirmReview>
  );
}

/**
 * Setting, changing and removing the PIN. A new PIN is typed twice: one typo
 * in a single masked field locked the profile with a PIN nobody knew.
 */
function PinSection({
  profile,
  isActive,
  unlockPin,
  onSaved,
  onSwitch,
}: {
  profile: Profile;
  isActive: boolean;
  /** The PIN typed to unlock the sheet, standing in for "current PIN" when there is one. */
  unlockPin: string | null;
  /** `newPin` is the PIN now set, or null when it was removed or cleared. */
  onSaved: (updated: Profile, message: string, newPin: string | null) => void;
  onSwitch: () => void;
}) {
  const [mode, setMode] = useState<"idle" | "set" | "remove" | "clear">("idle");
  // Cancelling the clear goes back to the form it was opened from.
  const [clearFrom, setClearFrom] = useState<"set" | "remove">("set");
  // Only a signed-in admin may clear a forgotten PIN (`account` is null under
  // the API token, which has the server's `fetch clear-pin` instead).
  const { account, isAdmin } = useAccount();
  const canClearPin = isAdmin && account !== null;
  const forgotRef = useRef<HTMLButtonElement>(null);
  const [refocusForgot, setRefocusForgot] = useState(false);
  useEffect(() => {
    if (!refocusForgot) return;
    forgotRef.current?.focus();
    setRefocusForgot(false);
  }, [refocusForgot, mode]);
  const [current, setCurrent] = useState("");
  const [pin, setPin] = useState("");
  const [confirm, setConfirm] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const currentId = useId();
  const errorId = useId();

  const reset = () => {
    setMode("idle");
    setCurrent("");
    setPin("");
    setConfirm("");
    setError(null);
  };

  // A first PIN has nothing to prove, so the server only takes it from the
  // profile it belongs to: set on a household member's profile, it would lock
  // them out with a PIN they never chose.
  if (!profile.hasPin && !isActive) {
    return (
      <SheetSection title="PIN">
        <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
          {profile.name} has no PIN. A first PIN can only be set from inside {profile.name}, so nobody is
          locked out by a PIN they didn&apos;t choose.
        </p>
        <button type="button" onClick={onSwitch} className="btn-secondary btn-sm">
          Switch to {profile.name}
        </button>
      </SheetSection>
    );
  }

  // Changing or removing an existing PIN needs the current one (the server
  // checks too), or anyone with the app open could strip another profile's.
  const currentPin = (unlockPin ?? current).trim();
  const needsCurrentField = profile.hasPin && unlockPin === null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    if (profile.hasPin && !currentPin) {
      setError(unlockPin === null ? "Enter the current PIN." : `Enter ${profile.name}'s PIN at the top first.`);
      return;
    }
    if (mode === "set") {
      if (!isValidPinFormat(pin)) {
        setError(`${PIN_FORMAT_ERROR}.`);
        return;
      }
      if (confirm !== pin) {
        setError("The two PINs don't match. Type the same PIN in both.");
        return;
      }
    }
    setSaving(true);
    setError(null);
    try {
      const { profile: updated } = await api.updateProfile(profile.id, {
        pin: mode === "set" ? pin : null,
        ...(profile.hasPin ? { currentPin } : {}),
      });
      const message = mode === "remove" ? `PIN removed for ${updated.name}` : `PIN ${profile.hasPin ? "changed" : "set"} for ${updated.name}`;
      const newPin = mode === "set" ? pin : null;
      reset();
      onSaved(updated, message, newPin);
    } catch (err) {
      setError(errorText(err, mode === "set" ? "Could not set the PIN." : "Could not remove the PIN.", "Incorrect current PIN."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <SheetSection
      title="PIN"
      description={
        profile.hasPin
          ? `${profile.name} asks for its PIN when it is opened, here and on every paired TV.`
          : `Ask for a PIN whenever someone opens ${profile.name}, here and on every paired TV.`
      }
    >
      {mode === "clear" ? (
        <ClearPinReview
          profile={profile}
          onCleared={(updated) => {
            reset();
            onSaved(updated, `PIN cleared for ${updated.name}`, null);
          }}
          onCancel={() => {
            setMode(clearFrom);
            setRefocusForgot(true);
          }}
        />
      ) : mode === "idle" ? (
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => setMode("set")} className="btn-secondary btn-sm">
            <Lock size={14} /> {profile.hasPin ? "Change PIN" : "Set a PIN"}
          </button>
          {profile.hasPin && (
            <button type="button" onClick={() => setMode("remove")} className="btn-secondary btn-sm">
              <Unlock size={14} /> Remove PIN
            </button>
          )}
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          {needsCurrentField && (
            <div>
              <label htmlFor={currentId} className={LABEL} style={{ color: "var(--text)" }}>Current PIN</label>
              {/* Any text: a PIN chosen before the 4–8 digit rule still has to work. */}
              <input
                id={currentId}
                autoFocus={mode === "remove"}
                type="password"
                inputMode="numeric"
                autoComplete="current-password"
                value={current}
                onChange={(e) => setCurrent(e.target.value)}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
                className={`mt-1.5 max-w-[12rem] ${FIELD_CLASS}`}
                style={FIELD_STYLE}
              />
            </div>
          )}
          {mode === "set" && (
            <NewPinFields
              pin={pin}
              confirm={confirm}
              onPin={setPin}
              onConfirm={setConfirm}
              onReject={() => setError(`${PIN_FORMAT_ERROR}.`)}
              label={profile.hasPin ? "New PIN" : "PIN"}
              errorId={error ? errorId : undefined}
            />
          )}
          {error && <p id={errorId} role="alert" className="flex items-start gap-1.5 text-xs text-danger"><AlertCircle size={13} className="mt-px flex-none" /> {error}</p>}
          {profile.hasPin && (
            canClearPin ? (
              <button
                ref={forgotRef}
                type="button"
                onClick={() => {
                  setClearFrom(mode === "remove" ? "remove" : "set");
                  setMode("clear");
                }}
                className="text-xs font-medium text-accent-text hover:underline"
              >
                Forgot the PIN?
              </button>
            ) : (
              <p className="text-xs" style={{ color: "var(--text-mute)" }}>Forgot the PIN? Ask the admin to clear it.</p>
            )
          )}
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={saving || (mode === "set" && (pin.length < MIN_PIN_LENGTH || confirm.length !== pin.length))}
              className={mode === "remove" ? "btn-danger btn-sm" : "btn-primary btn-sm"}
            >
              {saving ? <Loader2 size={14} className="animate-spin" /> : mode === "remove" ? <Unlock size={14} /> : <Check size={14} />}
              {mode === "remove" ? "Remove PIN" : "Save PIN"}
            </button>
            <button type="button" onClick={reset} className="btn-secondary btn-sm">Cancel</button>
          </div>
        </form>
      )}
    </SheetSection>
  );
}

/**
 * The confirmation before a profile goes, naming what goes with it. Counts
 * come from the server, so "its history" is a number rather than a guess.
 */
function DeleteSection({
  profile,
  isOnlyProfile,
  unlockPin,
  onDeleted,
}: {
  profile: Profile;
  isOnlyProfile: boolean;
  unlockPin: string | null;
  onDeleted: (id: string) => void;
}) {
  const [reviewing, setReviewing] = useState(false);
  const [summary, setSummary] = useState<ProfileSummary | null>(null);
  const [failed, setFailed] = useState(false);
  const [deleting, setDeleting] = useState(false);
  // Asked for only once the server says it wants one, so deleting the profile
  // you are in stays a single confirm.
  const [askPin, setAskPin] = useState(false);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const pinId = useId();
  const errorId = useId();
  const deleteRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  // Where focus goes on the next render: into the review as it opens (its
  // Delete button has just gone), back to Delete as it closes.
  const [focusTarget, setFocusTarget] = useState<"cancel" | "delete" | null>(null);
  useEffect(() => {
    if (!focusTarget) return;
    (focusTarget === "cancel" ? cancelRef : deleteRef).current?.focus();
    setFocusTarget(null);
  }, [focusTarget]);

  const openReview = () => {
    // A reopened review asks again: the last answer's counts must not stand in
    // for this one's, nor enable Delete before it arrives.
    setSummary(null);
    setFailed(false);
    setReviewing(true);
    setFocusTarget("cancel");
  };

  const closeReview = () => {
    setReviewing(false);
    setAskPin(false);
    setPin("");
    setError(null);
    setFocusTarget("delete");
  };

  useEffect(() => {
    if (!reviewing) return;
    let cancelled = false;
    api.getProfileSummary(profile.id).then(
      (result) => { if (!cancelled) setSummary(result); },
      () => { if (!cancelled) setFailed(true); }
    );
    return () => { cancelled = true; };
  }, [profile.id, reviewing]);

  const remove = async () => {
    const proof = (unlockPin ?? pin).trim() || undefined;
    if (askPin && !proof) return;
    setDeleting(true);
    setError(null);
    try {
      await api.deleteProfile(profile.id, proof);
      onDeleted(profile.id);
    } catch (err) {
      // 401 means the server wants proof of this profile's PIN: ask for it
      // rather than report a failure the user can actually get past.
      if (err instanceof ApiError && err.status === 401 && !askPin && unlockPin === null) {
        setAskPin(true);
      } else {
        setError(errorText(err, "Could not delete the profile."));
      }
      setDeleting(false);
    }
  };

  const contents = summary ? describeProfileContents(summary) : null;

  return (
    <SheetSection title="Delete profile">
      {isOnlyProfile ? (
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          {profile.name} is the only profile, and an account always keeps one. Add another to delete this one.
        </p>
      ) : !reviewing ? (
        <button ref={deleteRef} type="button" onClick={openReview} className="btn-secondary btn-sm text-danger">
          <Trash2 size={14} /> Delete {profile.name}…
        </button>
      ) : (
        <div role="alertdialog" aria-label={`Delete ${profile.name}?`} className="space-y-3 rounded-xl border p-4" style={{ borderColor: "var(--border-strong)" }}>
          {!summary && !failed ? (
            <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
              <Loader2 size={14} className="animate-spin" /> Checking what {profile.name} holds…
            </p>
          ) : (
            <p className="text-sm" style={{ color: "var(--text)" }}>
              Delete <strong>{profile.name}</strong>?{" "}
              {contents
                ? <>Its {contents} will be deleted with it, here and on every paired TV.</>
                : failed
                  ? <>Its watch history, lists, resume points, ratings and collections will be deleted with it, here and on every paired TV.</>
                  : <>It has no history, lists or resume points yet. It will also disappear from every paired TV.</>}{" "}
              This can&apos;t be undone.
            </p>
          )}
          {askPin && (
            <div>
              <label htmlFor={pinId} className={LABEL} style={{ color: "var(--text)" }}>PIN for {profile.name}, to confirm</label>
              <input
                id={pinId}
                autoFocus
                type="password"
                inputMode="numeric"
                value={pin}
                onChange={(e) => setPin(e.target.value)}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
                className={`mt-1.5 max-w-[12rem] ${FIELD_CLASS}`}
                style={FIELD_STYLE}
              />
            </div>
          )}
          {error && <p id={errorId} role="alert" className="flex items-start gap-1.5 text-xs text-danger"><AlertCircle size={13} className="mt-px flex-none" /> {error}</p>}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => void remove()}
              disabled={deleting || (!summary && !failed) || (askPin && !pin.trim())}
              className="btn-danger btn-sm"
            >
              {deleting ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />} Delete permanently
            </button>
            {/* Not while the delete is out: it can't be called back, and a
                review that closed would say it had been. */}
            <button ref={cancelRef} type="button" onClick={closeReview} disabled={deleting} className="btn-secondary btn-sm">
              Cancel
            </button>
          </div>
        </div>
      )}
    </SheetSection>
  );
}

type Draft = {
  name: string;
  look: AvatarChoice;
  usesPrimaryAddons: boolean;
  parental: Limits;
  rules: RulesDraft;
};

const draftOf = (profile: Profile): Draft => {
  const { kidsRules, ...limits }: ProfileParental = profile.parental ?? UNRESTRICTED;
  return {
    name: profile.name,
    look: { avatarColorHex: profile.avatarColorHex ?? null, avatarId: profile.avatarId ?? null },
    usesPrimaryAddons: profile.usesPrimaryAddons ?? false,
    parental: limits,
    rules: rulesOf(kidsRules),
  };
};

const sameDraft = (a: Draft, b: Draft) =>
  a.name.trim() === b.name.trim() &&
  a.look.avatarColorHex === b.look.avatarColorHex &&
  a.look.avatarId === b.look.avatarId &&
  a.usesPrimaryAddons === b.usesPrimaryAddons &&
  a.parental.isKidsProfile === b.parental.isKidsProfile &&
  a.parental.maxRating === b.parental.maxRating &&
  a.parental.allowUnrated === b.parental.allowUnrated &&
  sameRules(a.rules, b.rules);

/**
 * Everything about one profile, in one sheet: its name and picture, PIN,
 * parental controls, TV addons, and deleting it.
 *
 * It replaces a row of five unlabeled 14px icons, each opening its own inline
 * form in place of the row. The name, picture, parental limit and addon choice
 * are saved together, so a TV that syncs mid-edit never sees half a change;
 * the PIN and deleting are their own actions, as they need their own proof.
 */
export function ProfileSheet({
  profile,
  isActive,
  isPrimary,
  isOnlyProfile,
  onUpdated,
  onDeleted,
  onSwitch,
  onClose,
}: {
  profile: Profile;
  isActive: boolean;
  /** The oldest profile: the one others can share addons with, so it can't share its own. */
  isPrimary: boolean;
  isOnlyProfile: boolean;
  onUpdated: (profile: Profile) => void;
  onDeleted: (id: string) => void;
  /** Open the switcher (to set a first PIN, which needs the profile itself). */
  onSwitch: () => void;
  onClose: () => void;
}) {
  const { showToast } = useToast();
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);
  const panelRef = useFocusTrap<HTMLDivElement>();
  useScrollLock();
  useEscapeKey(requestClose);
  const headingId = useId();
  const nameId = useId();
  const unlockId = useId();

  const [saved, setSaved] = useState<Profile>(profile);
  const [draft, setDraft] = useState<Draft>(() => draftOf(profile));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorField, setErrorField] = useState<"name" | "unlock" | null>(null);
  const errorId = useId();
  // A PIN-protected profile this browser hasn't unlocked: the server wants its
  // PIN with any change, as it does to open it. Asked once, at the top.
  const needsPin = saved.hasPin && !isActive;
  const [unlock, setUnlock] = useState("");

  const dirty = !sameDraft(draft, draftOf(saved));
  // Kids rules are a grown-up's to change (the API refuses a kids profile), so
  // acting as one shows them read-only.
  const parentalLocked = useProfile().profile?.parental?.isKidsProfile === true;

  const setParental = (next: Partial<Limits>) =>
    setDraft((d) => ({ ...d, parental: { ...d.parental, ...next } }));

  const setRules = (next: Partial<RulesDraft>) =>
    setDraft((d) => ({ ...d, rules: { ...d.rules, ...next } }));

  const toggleKids = (isKidsProfile: boolean) =>
    setParental(isKidsProfile ? { isKidsProfile, ...KIDS_PRESET } : { isKidsProfile });

  const accept = (updated: Profile) => {
    setSaved(updated);
    onUpdated(updated);
  };

  const save = async () => {
    const name = draft.name.trim();
    if (!name) {
      setError("Give the profile a name.");
      setErrorField("name");
      return;
    }
    if (needsPin && !unlock.trim()) {
      setError(`Enter ${saved.name}'s PIN at the top to save changes.`);
      setErrorField("unlock");
      return;
    }
    setSaving(true);
    setError(null);
    setErrorField(null);
    try {
      // Kids rules go only when changed: what is sent is stamped now and
      // replaces what a TV set, rule by rule.
      const rulesChanged =
        !parentalLocked && draft.parental.isKidsProfile && !sameRules(draft.rules, draftOf(saved).rules);
      const parental: ProfileSettingsUpdate["parental"] = rulesChanged
        ? { ...draft.parental, kidsRules: draft.rules }
        : draft.parental;
      const { profile: updated } = await api.updateProfile(saved.id, {
        ...(name !== saved.name ? { name } : {}),
        avatarColorHex: draft.look.avatarColorHex,
        avatarId: draft.look.avatarId,
        ...(isPrimary ? {} : { usesPrimaryAddons: draft.usesPrimaryAddons }),
        parental,
        ...(needsPin ? { currentPin: unlock.trim() } : {}),
      });
      accept(updated);
      showToast(`Saved ${updated.name}`);
      requestClose();
    } catch (err) {
      setError(errorText(err, "Could not save this profile."));
      // A wrong or locked-out PIN is the PIN field's error.
      setErrorField(needsPin && err instanceof ApiError && (err.status === 401 || err.status === 429) ? "unlock" : null);
    } finally {
      setSaving(false);
    }
  };

  const preview = { name: draft.name.trim() || saved.name, ...draft.look };
  const approvedTitles = saved.parental?.kidsRules?.approvedTitles ?? [];
  const hasBedtime = draft.rules.bedtimeStartMinute !== null && draft.rules.bedtimeEndMinute !== null;

  return (
    <div className="fixed inset-0 z-[150]" role="presentation">
      <button
        type="button"
        aria-hidden="true"
        tabIndex={-1}
        onClick={requestClose}
        className={`overlay-scrim overlay-fade absolute inset-0 h-full w-full ${exiting ? "overlay-exit" : ""}`}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
        tabIndex={-1}
        inert={exiting}
        onAnimationEnd={onExitAnimationEnd}
        className={`overlay-side-sheet absolute inset-x-0 bottom-0 flex max-h-[92dvh] flex-col rounded-t-3xl shadow-e3 focus:outline-none sm:inset-y-0 sm:left-auto sm:right-0 sm:max-h-none sm:w-[30rem] sm:rounded-none sm:rounded-l-3xl ${exiting ? "overlay-exit" : ""}`}
        style={{ background: "var(--bg-1)", borderLeft: "0.5px solid var(--border)" }}
      >
        <header className="flex items-center gap-3 px-5 pb-4 pt-5 sm:px-6">
          <ProfileAvatar profile={preview} size="lg" />
          <div className="min-w-0 flex-1">
            <h2 id={headingId} className="truncate text-lg font-semibold" style={{ color: "var(--text)" }}>
              Edit {saved.name}
            </h2>
            <ProfileBadges profile={saved} isActive={isActive} isPrimary={isPrimary} className="mt-1" />
          </div>
          <button
            type="button"
            onClick={requestClose}
            aria-label="Close"
            className="tap-target flex h-9 w-9 flex-none items-center justify-center rounded-full transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            style={{ color: "var(--text-mute)" }}
          >
            <X size={18} />
          </button>
        </header>

        <div className="flex-1 space-y-5 overflow-y-auto overscroll-contain px-5 pb-6 sm:px-6">
          {needsPin && (
            <div className="space-y-2 rounded-xl border p-4" style={{ borderColor: "var(--border-strong)", background: "var(--surface)" }}>
              <label htmlFor={unlockId} className={LABEL} style={{ color: "var(--text)" }}>
                <Lock size={12} className="mr-1 inline" aria-hidden="true" />
                {saved.name}&apos;s PIN
              </label>
              <p className="text-xs" style={{ color: "var(--text-mute)" }}>
                {saved.name} is PIN-protected. Its PIN is needed to change anything here.
              </p>
              <input
                id={unlockId}
                aria-invalid={errorField === "unlock" || undefined}
                aria-describedby={errorField === "unlock" ? errorId : undefined}
                type="password"
                inputMode="numeric"
                autoComplete="off"
                value={unlock}
                onChange={(e) => setUnlock(e.target.value)}
                className={`max-w-[12rem] ${FIELD_CLASS}`}
                style={FIELD_STYLE}
              />
            </div>
          )}

          <section className="space-y-4" aria-label="Name and picture">
            <div>
              <label htmlFor={nameId} className={LABEL} style={{ color: "var(--text)" }}>Name</label>
              <input
                id={nameId}
                aria-invalid={errorField === "name" || undefined}
                aria-describedby={errorField === "name" ? errorId : undefined}
                type="text"
                value={draft.name}
                maxLength={40}
                onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                className={`mt-1.5 ${FIELD_CLASS}`}
                style={FIELD_STYLE}
              />
            </div>
            <AvatarPicker value={draft.look} onChange={(look) => setDraft((d) => ({ ...d, look }))} />
          </section>

          <SheetSection
            title="Parental controls"
            description={
              <>
                <strong>These limits apply on paired TVs only.</strong> A TV applies them on its next sync and keeps
                applying them offline. This website doesn&apos;t filter titles by rating: a kids profile here can
                search and browse everything, but can&apos;t open Settings or sign in a TV.
              </>
            }
          >
            <SwitchRow
              checked={draft.parental.isKidsProfile}
              onChange={toggleKids}
              label="Kids profile"
              description="On the TV: titles up to parental guidance, unrated ones hidden, and addons and settings behind a grown-up's PIN."
            />
            <div>
              <label htmlFor={`${nameId}-rating`} className={LABEL} style={{ color: "var(--text)" }}>Highest rating allowed on the TV</label>
              <SelectField
                id={`${nameId}-rating`}
                value={draft.parental.maxRating ?? ""}
                onChange={(e) => setParental({ maxRating: (e.target.value || null) as RatingLevel | null })}
                wrapperClassName="mt-1.5"
                className="w-full rounded-xl border py-2.5 pl-3.5 pr-9 text-sm"
                style={FIELD_STYLE}
                chevronSize={16}
              >
                <option value="">No limit</option>
                {RATING_LEVELS.map((level) => (
                  <option key={level} value={level}>{RATING_LABELS[level]}</option>
                ))}
              </SelectField>
            </div>
            <SwitchRow
              checked={draft.parental.allowUnrated}
              disabled={draft.parental.maxRating === null}
              onChange={(allowUnrated) => setParental({ allowUnrated })}
              label="Show titles with no rating"
              description={draft.parental.maxRating === null ? "Applies once a rating limit is set." : undefined}
            />
          </SheetSection>

          {draft.parental.isKidsProfile && (
            <SheetSection
              title="Kids rules"
              description={
                parentalLocked
                  ? "Switch to a grown-up's profile to change these."
                  : "Every paired TV applies these rules. Each TV counts the screen time used today on its own, so the limit keeps working when a TV is offline."
              }
            >
              <fieldset className="space-y-3" disabled={parentalLocked}>
                <legend className="sr-only">Kids rules</legend>
                <div>
                  <label htmlFor={`${nameId}-autoplay`} className={LABEL} style={{ color: "var(--text)" }}>Autoplay in a row</label>
                  <SelectField
                    id={`${nameId}-autoplay`}
                    value={draft.rules.autoplayEpisodeCap ?? ""}
                    onChange={(e) => setRules({ autoplayEpisodeCap: e.target.value ? Number(e.target.value) : null })}
                    wrapperClassName="mt-1.5"
                    className="w-full rounded-xl border py-2.5 pl-3.5 pr-9 text-sm"
                    style={FIELD_STYLE}
                    chevronSize={16}
                  >
                    {withCurrent(AUTOPLAY_CHOICES, draft.rules.autoplayEpisodeCap).map((cap) => (
                      <option key={cap} value={cap}>{cap === 1 ? "1 episode" : `${cap} episodes`}</option>
                    ))}
                    <option value="">No limit</option>
                  </SelectField>
                </div>
                <SwitchRow
                  checked={draft.rules.approvedTitlesOnly}
                  disabled={parentalLocked}
                  onChange={(approvedTitlesOnly) => setRules({ approvedTitlesOnly })}
                  label="Approved titles only"
                  description={
                    approvedTitles.length === 0
                      ? "No titles approved yet. Allow a title from its poster on a TV, on the main profile."
                      : `${approvedTitles.length} ${approvedTitles.length === 1 ? "title" : "titles"} approved: ${approvedTitles
                          .slice(0, 5)
                          .map((title) => title.name || title.ids[0])
                          .join(", ")}${approvedTitles.length > 5 ? ", …" : ""}. Add more from a TV.`
                  }
                />
                <div>
                  <label htmlFor={`${nameId}-daily`} className={LABEL} style={{ color: "var(--text)" }}>Daily screen time</label>
                  <SelectField
                    id={`${nameId}-daily`}
                    value={draft.rules.dailyMinutes ?? ""}
                    onChange={(e) => setRules({ dailyMinutes: e.target.value ? Number(e.target.value) : null })}
                    wrapperClassName="mt-1.5"
                    className="w-full rounded-xl border py-2.5 pl-3.5 pr-9 text-sm"
                    style={FIELD_STYLE}
                    chevronSize={16}
                  >
                    <option value="">No limit</option>
                    {withCurrent(DAILY_CHOICES, draft.rules.dailyMinutes).map((minutes) => (
                      <option key={minutes} value={minutes}>{minutesLabel(minutes)}</option>
                    ))}
                  </SelectField>
                </div>
                <SwitchRow
                  checked={hasBedtime}
                  disabled={parentalLocked}
                  onChange={(on) =>
                    setRules(
                      on
                        ? { bedtimeStartMinute: DEFAULT_BEDTIME.start, bedtimeEndMinute: DEFAULT_BEDTIME.end }
                        : { bedtimeStartMinute: null, bedtimeEndMinute: null }
                    )
                  }
                  label="Bedtime"
                />
                {hasBedtime && (
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label htmlFor={`${nameId}-bed-from`} className={LABEL} style={{ color: "var(--text)" }}>From</label>
                      <input
                        id={`${nameId}-bed-from`}
                        type="time"
                        value={timeOf(draft.rules.bedtimeStartMinute ?? DEFAULT_BEDTIME.start)}
                        onChange={(e) => {
                          const minute = minuteOf(e.target.value);
                          if (minute !== null) setRules({ bedtimeStartMinute: minute });
                        }}
                        className={`mt-1.5 ${FIELD_CLASS}`}
                        style={FIELD_STYLE}
                      />
                    </div>
                    <div>
                      <label htmlFor={`${nameId}-bed-until`} className={LABEL} style={{ color: "var(--text)" }}>Until</label>
                      <input
                        id={`${nameId}-bed-until`}
                        type="time"
                        value={timeOf(draft.rules.bedtimeEndMinute ?? DEFAULT_BEDTIME.end)}
                        onChange={(e) => {
                          const minute = minuteOf(e.target.value);
                          if (minute !== null) setRules({ bedtimeEndMinute: minute });
                        }}
                        className={`mt-1.5 ${FIELD_CLASS}`}
                        style={FIELD_STYLE}
                      />
                    </div>
                  </div>
                )}
                {(draft.rules.dailyMinutes !== null || hasBedtime) && (
                  <SwitchRow
                    checked={draft.rules.hardStop}
                    disabled={parentalLocked}
                    onChange={(hardStop) => setRules({ hardStop })}
                    label="At the limit, stop right away instead of finishing what's playing"
                  />
                )}
              </fieldset>
            </SheetSection>
          )}

          <SheetSection
            title="TV addons"
            description={
              isPrimary
                ? `${saved.name} is the primary profile, the oldest on this account. Other profiles can use its addons instead of their own.`
                : undefined
            }
          >
            {isPrimary ? null : (
              <SwitchRow
                checked={draft.usesPrimaryAddons}
                onChange={(usesPrimaryAddons) => setDraft((d) => ({ ...d, usesPrimaryAddons }))}
                label="Use the primary profile's addons"
                description="On the TV, this profile sees the addons installed for the primary profile instead of its own."
              />
            )}
          </SheetSection>

          {error && <p id={errorId} role="alert" className="flex items-start gap-1.5 text-sm text-danger"><AlertCircle size={15} className="mt-0.5 flex-none" /> {error}</p>}
          <div className="flex gap-2">
            <button type="button" onClick={() => void save()} disabled={saving || !dirty} className="btn-primary">
              {saving ? <Loader2 size={15} className="animate-spin" /> : <Check size={15} />} Save changes
            </button>
            <button type="button" onClick={requestClose} className="btn-secondary">Cancel</button>
          </div>

          <PinSection
            profile={saved}
            isActive={isActive}
            unlockPin={needsPin ? unlock : null}
            onSaved={(updated, message, newPin) => {
              accept(updated);
              showToast(message);
              // The PIN typed at the top is the old one now: carry on with the
              // new one, so the next change doesn't send a wrong PIN (and count
              // towards a lockout).
              setUnlock(newPin ?? "");
            }}
            onSwitch={() => {
              requestClose();
              onSwitch();
            }}
          />

          <DeleteSection
            profile={saved}
            isOnlyProfile={isOnlyProfile}
            unlockPin={needsPin && unlock.trim() ? unlock : null}
            onDeleted={(id) => {
              onDeleted(id);
              onClose();
            }}
          />
        </div>
      </div>
    </div>
  );
}
