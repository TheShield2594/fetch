import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, Check, Loader2, Lock, Pencil, Plus, SlidersHorizontal, Trash2, Unlock, Users, X } from "lucide-react";
import { isValidPinFormat, MAX_PIN_LENGTH, MIN_PIN_LENGTH, PIN_FORMAT_ERROR } from "@fetch/shared/pin";
import { api, ApiError, type Profile, type ProfileSummary } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { useToast } from "../../hooks/useToast";
import { useAccount } from "../../hooks/useAccount";
import { IDLE_LOCK_CHOICES, setIdleLockMinutes, useIdleLockMinutes } from "../../hooks/useIdleLock";
import { ProfileAvatar } from "../ProfileAvatar";
import { ConfirmReview } from "../ConfirmReview";
import { PasswordField } from "../PasswordField";
import { SelectField } from "../SelectField";
import { ProfileEditor } from "./ProfileEditor";

// A PIN-protected profile this browser hasn't unlocked needs its PIN for any
// change (enforced server-side), so `needsPin` asks for it alongside the name.
function RenameForm({ profile, needsPin, onSaved, onCancel }: { profile: Profile; needsPin: boolean; onSaved: (p: Profile) => void; onCancel: () => void }) {
  const [name, setName] = useState(profile.name);
  const [currentPin, setCurrentPin] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || trimmed === profile.name) return onCancel();
    setSaving(true);
    setError(null);
    try {
      const { profile: updated } = await api.updateProfile(profile.id, {
        name: trimmed,
        ...(needsPin ? { currentPin: currentPin.trim() } : {}),
      });
      onSaved(updated);
    } catch (err) {
      if (needsPin && err instanceof ApiError && err.status === 401) {
        setError("Incorrect PIN.");
      } else {
        setError(err instanceof Error ? err.message : "Could not rename profile.");
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="flex flex-1 items-center gap-2">
      <input
        autoFocus
        type="text"
        value={name}
        onChange={(e) => setName(e.target.value)}
        aria-label={`Rename ${profile.name}`}
        className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      {needsPin && (
        <input
          type="password"
          inputMode="numeric"
          value={currentPin}
          onChange={(e) => setCurrentPin(e.target.value)}
          required
          aria-required="true"
          placeholder="PIN"
          aria-label={`PIN for ${profile.name}`}
          className="w-24 flex-none rounded-lg border px-2.5 py-1.5 text-sm field-control"
          style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
        />
      )}
      <button type="submit" disabled={saving || (needsPin && !currentPin.trim())} aria-label="Save name" className="flex-none rounded-lg p-1.5 text-success hover:bg-[var(--surface-strong)]">
        {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />}
      </button>
      <button type="button" onClick={onCancel} aria-label="Cancel rename" className="flex-none rounded-lg p-1.5 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
        <X size={16} />
      </button>
      {error && <p role="alert" className="w-full text-xs text-danger">{error}</p>}
    </form>
  );
}

// Changing or removing an *existing* PIN requires the current one (enforced
// server-side too) — otherwise anyone with the app open could strip another
// profile's PIN protection without ever knowing it, defeating its purpose.
function PinForm({ profile, onSaved, onCancel }: { profile: Profile; onSaved: (p: Profile) => void; onCancel: () => void }) {
  const [pin, setPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [currentPin, setCurrentPin] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pinHintId = useId();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = pin.trim();
    if (!trimmed) return;
    if (profile.hasPin && !currentPin.trim()) return;
    if (!isValidPinFormat(trimmed)) {
      setError(`${PIN_FORMAT_ERROR}.`);
      return;
    }
    // Typed twice: one slip here and the profile, with its history, is behind
    // a PIN nobody knows.
    if (trimmed !== confirmPin.trim()) {
      setError("The PINs don't match.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const { profile: updated } = await api.updateProfile(profile.id, {
        pin: trimmed,
        ...(profile.hasPin ? { currentPin: currentPin.trim() } : {}),
      });
      onSaved(updated);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError("Incorrect current PIN.");
      } else {
        setError(err instanceof Error ? err.message : "Could not set PIN.");
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="flex flex-1 flex-wrap items-center gap-2">
      {profile.hasPin && (
        <input
          autoFocus
          type="password"
          inputMode="numeric"
          value={currentPin}
          onChange={(e) => setCurrentPin(e.target.value)}
          placeholder="Current PIN"
          aria-label={`Current PIN for ${profile.name}`}
          className="w-full min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 text-sm field-control"
          style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
        />
      )}
      <input
        autoFocus={!profile.hasPin}
        type="password"
        inputMode="numeric"
        // The 4–8 digit rule the API enforces, only on the set/change field —
        // a PIN chosen before the rule existed still has to unlock, so the
        // current-PIN field above takes anything. Checked on submit, so the
        // message names the rule rather than the browser's generic one.
        autoComplete="new-password"
        aria-describedby={pinHintId}
        value={pin}
        onChange={(e) => setPin(e.target.value)}
        placeholder={profile.hasPin ? "New PIN" : "PIN"}
        aria-label={profile.hasPin ? `New PIN for ${profile.name}` : `PIN for ${profile.name}`}
        className="w-full min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      <input
        type="password"
        inputMode="numeric"
        autoComplete="new-password"
        value={confirmPin}
        onChange={(e) => setConfirmPin(e.target.value)}
        placeholder="Confirm PIN"
        aria-label={`Confirm the new PIN for ${profile.name}`}
        className="w-full min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      <button
        type="submit"
        disabled={saving || !pin.trim() || !confirmPin.trim() || (profile.hasPin && !currentPin.trim())}
        aria-label="Save PIN"
        className="flex-none rounded-lg p-1.5 text-success hover:bg-[var(--surface-strong)] disabled:opacity-50"
      >
        {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />}
      </button>
      <button type="button" onClick={onCancel} aria-label="Cancel" className="flex-none rounded-lg p-1.5 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
        <X size={16} />
      </button>
      <p id={pinHintId} className="w-full text-xs" style={{ color: "var(--text-mute)" }}>
        {profile.hasPin ? "New PIN" : "PIN"}: {MIN_PIN_LENGTH}–{MAX_PIN_LENGTH} digits
      </p>
      {error && <p role="alert" className="w-full text-xs text-danger">{error}</p>}
    </form>
  );
}

function RemovePinForm({ profile, onSaved, onCancel }: { profile: Profile; onSaved: (p: Profile) => void; onCancel: () => void }) {
  const [currentPin, setCurrentPin] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = currentPin.trim();
    if (!trimmed) return;
    setSaving(true);
    setError(null);
    try {
      const { profile: updated } = await api.updateProfile(profile.id, { pin: null, currentPin: trimmed });
      onSaved(updated);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError("Incorrect current PIN.");
      } else {
        setError(err instanceof Error ? err.message : "Could not remove PIN.");
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="flex flex-1 items-center gap-2">
      <input
        autoFocus
        type="password"
        inputMode="numeric"
        value={currentPin}
        onChange={(e) => setCurrentPin(e.target.value)}
        placeholder="Current PIN to confirm removal"
        aria-label={`Current PIN for ${profile.name}, to confirm removal`}
        className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      <button type="submit" disabled={saving || !currentPin.trim()} aria-label="Confirm PIN removal" className="flex-none rounded-lg p-1.5 text-success hover:bg-[var(--surface-strong)] disabled:opacity-50">
        {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />}
      </button>
      <button type="button" onClick={onCancel} aria-label="Cancel" className="flex-none rounded-lg p-1.5 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
        <X size={16} />
      </button>
      {error && <p role="alert" className="w-full text-xs text-danger">{error}</p>}
    </form>
  );
}

// Deleting a PIN-protected profile needs its PIN unless the browser already
// holds a profile token for it (i.e. it is the profile you unlocked and are
// using). We only ask after the server says it wants one, so deleting the
// profile you are in stays a single confirm.
function DeleteConfirmForm({ profile, onDeleted, onCancel }: { profile: Profile; onDeleted: (id: string) => void; onCancel: () => void }) {
  const [pin, setPin] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = pin.trim();
    if (!trimmed) return;
    setDeleting(true);
    setError(null);
    try {
      await api.deleteProfile(profile.id, trimmed);
      onDeleted(profile.id);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError("Incorrect PIN.");
      } else {
        setError(err instanceof Error ? err.message : "Could not delete profile.");
      }
      setDeleting(false);
    }
  };

  return (
    <form onSubmit={submit} className="flex flex-1 items-center gap-2">
      <input
        autoFocus
        type="password"
        inputMode="numeric"
        value={pin}
        onChange={(e) => setPin(e.target.value)}
        placeholder={`PIN for ${profile.name} to confirm deletion`}
        aria-label={`PIN for ${profile.name}, to confirm deletion`}
        className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      <button
        type="submit"
        disabled={deleting || !pin.trim()}
        aria-label={`Confirm deletion of ${profile.name}`}
        className="flex-none rounded-lg p-1.5 text-danger hover:bg-rose-500/10 disabled:opacity-50"
      >
        {deleting ? <Loader2 size={16} className="animate-spin" /> : <Trash2 size={16} />}
      </button>
      <button type="button" onClick={onCancel} aria-label="Cancel deletion" className="flex-none rounded-lg p-1.5 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
        <X size={16} />
      </button>
      {error && <p role="alert" className="w-full text-xs text-danger">{error}</p>}
    </form>
  );
}

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

/**
 * The confirmation before a profile goes, naming what goes with it. Counts
 * come from the server, so "its history" is a number rather than a guess.
 */
function DeleteReview({
  profile,
  onConfirm,
  onCancel,
  deleting,
}: {
  profile: Profile;
  onConfirm: () => void;
  onCancel: () => void;
  deleting: boolean;
}) {
  const [summary, setSummary] = useState<ProfileSummary | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.getProfileSummary(profile.id).then(
      (result) => { if (!cancelled) setSummary(result); },
      () => { if (!cancelled) setFailed(true); }
    );
    return () => { cancelled = true; };
  }, [profile.id]);

  const contents = summary ? describeProfileContents(summary) : null;

  return (
    <div role="alertdialog" aria-label={`Delete ${profile.name}?`} className="mt-3 space-y-3 border-t pt-3" style={{ borderColor: "var(--border)" }}>
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
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onConfirm}
          disabled={deleting || (!summary && !failed)}
          className="btn-danger btn-sm"
        >
          {deleting ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />} Delete permanently
        </button>
        <button type="button" onClick={onCancel} className="btn-secondary btn-sm">Cancel</button>
      </div>
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
  const [error, setError] = useState<string | null>(null);

  const clear = async () => {
    if (!password) {
      setError("Enter your account password.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const { profile: updated } = await api.clearProfilePin(profile.id, password);
      onCleared(updated);
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? "Incorrect password."
          : err instanceof Error
            ? err.message
            : "Could not clear the PIN."
      );
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
        autoComplete="current-password"
        autoFocus
      />
    </ConfirmReview>
  );
}

function ProfileRow({
  profile,
  isActive,
  isPrimary,
  isOnlyProfile,
  onUpdated,
  onDeleted,
}: {
  profile: Profile;
  isActive: boolean;
  isPrimary: boolean;
  isOnlyProfile: boolean;
  onUpdated: (p: Profile) => void;
  onDeleted: (id: string) => void;
}) {
  const [editing, setEditing] = useState<
    "none" | "name" | "pin" | "removePin" | "clearPin" | "settings" | "reviewDelete" | "confirmDelete"
  >("none");
  // Only a signed-in admin may clear a forgotten PIN (`account` is null under
  // the API token, which has the server's `fetch clear-pin` instead).
  const { account, isAdmin } = useAccount();
  const canClearPin = isAdmin && account !== null;
  // Cancelling the clear-PIN review goes back to the PIN form it was opened
  // from, with focus on "Forgot the PIN?" rather than the row's first control.
  const [clearPinFrom, setClearPinFrom] = useState<"pin" | "removePin">("pin");
  const forgotRef = useRef<HTMLButtonElement>(null);
  const [refocusForgot, setRefocusForgot] = useState(false);
  useEffect(() => {
    if (!refocusForgot) return;
    forgotRef.current?.focus();
    setRefocusForgot(false);
  }, [refocusForgot, editing]);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast } = useToast();

  // Changing an existing PIN needs the current one, which is proof enough from
  // any profile. A *first* PIN has nothing to prove, so the server only accepts
  // it from the profile it belongs to — otherwise setting one on a household
  // member's profile would lock them out of it with a PIN they never chose.
  const canSetFirstPin = profile.hasPin || isActive;
  // The browser holds a profile token only for the profile it is in, so a
  // change to any other PIN-protected profile has to carry that profile's PIN.
  const needsPin = profile.hasPin && !isActive;

  const remove = async () => {
    setDeleting(true);
    setError(null);
    try {
      await api.deleteProfile(profile.id);
      onDeleted(profile.id);
    } catch (err) {
      // 401 means the server wants proof of this profile's PIN — ask for it
      // rather than reporting a failure the user can actually get past.
      if (err instanceof ApiError && err.status === 401) {
        setEditing("confirmDelete");
      } else {
        setError(err instanceof ApiError ? err.message : "Could not delete profile.");
      }
      setDeleting(false);
    }
  };

  return (
    <div className="rounded-xl border px-3 py-2.5" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
      <div className="flex items-center gap-3">
        <ProfileAvatar profile={profile} />

        {editing === "name" ? (
          <RenameForm
            profile={profile}
            needsPin={needsPin}
            onSaved={(p) => { onUpdated(p); setEditing("none"); }}
            onCancel={() => setEditing("none")}
          />
        ) : editing === "pin" ? (
          <PinForm
            profile={profile}
            onSaved={(p) => { onUpdated(p); setEditing("none"); showToast(`PIN ${profile.hasPin ? "changed" : "set"} for ${p.name}`); }}
            onCancel={() => setEditing("none")}
          />
        ) : editing === "removePin" ? (
          <RemovePinForm
            profile={profile}
            onSaved={(p) => { onUpdated(p); setEditing("none"); showToast(`PIN removed for ${p.name}`); }}
            onCancel={() => setEditing("none")}
          />
        ) : editing === "confirmDelete" ? (
          <DeleteConfirmForm
            profile={profile}
            onDeleted={onDeleted}
            onCancel={() => setEditing("none")}
          />
        ) : (
          <>
            <div className="flex min-w-0 flex-1 items-center gap-2">
              <span className="truncate text-sm font-medium" style={{ color: "var(--text)" }}>{profile.name}</span>
              {isActive && (
                <span className="flex-none rounded-full bg-accent-500/10 px-2 py-0.5 text-2xs font-medium text-accent-text">Active</span>
              )}
              {profile.parental?.isKidsProfile && (
                <span className="flex-none rounded-full px-2 py-0.5 text-2xs font-medium" style={{ background: "var(--surface-strong)", color: "var(--text-dim)" }}>Kids</span>
              )}
              {profile.hasPin ? (
                <Lock size={13} className="flex-none" style={{ color: "var(--text-mute)" }} />
              ) : null}
            </div>
            <button
              type="button"
              onClick={() => setEditing("name")}
              aria-label={`Rename ${profile.name}`}
              className="flex-none rounded-lg p-1.5 transition-colors hover:bg-[var(--surface-strong)]"
              style={{ color: "var(--text-mute)" }}
            >
              <Pencil size={14} />
            </button>
            <button
              type="button"
              onClick={() => setEditing(editing === "settings" ? "none" : "settings")}
              aria-label={`Appearance and parental controls for ${profile.name}`}
              aria-expanded={editing === "settings"}
              className="flex-none rounded-lg p-1.5 transition-colors hover:bg-[var(--surface-strong)]"
              style={{ color: "var(--text-mute)" }}
            >
              <SlidersHorizontal size={14} />
            </button>
            <button
              type="button"
              onClick={() => setEditing("pin")}
              disabled={!canSetFirstPin}
              title={canSetFirstPin ? undefined : `Switch to ${profile.name} to set a PIN`}
              aria-label={profile.hasPin ? `Change PIN for ${profile.name}` : `Set PIN for ${profile.name}`}
              className="flex-none rounded-lg p-1.5 transition-colors hover:bg-[var(--surface-strong)] disabled:opacity-30 disabled:hover:bg-transparent"
              style={{ color: "var(--text-mute)" }}
            >
              <Lock size={14} />
            </button>
            {profile.hasPin && (
              <button
                type="button"
                onClick={() => setEditing("removePin")}
                aria-label={`Remove PIN for ${profile.name}`}
                className="flex-none rounded-lg p-1.5 transition-colors hover:bg-[var(--surface-strong)]"
                style={{ color: "var(--text-mute)" }}
              >
                <Unlock size={14} />
              </button>
            )}
            <button
              type="button"
              onClick={() => setEditing("reviewDelete")}
              disabled={deleting || isOnlyProfile}
              title={isOnlyProfile ? "Can't delete the only profile" : "Delete profile"}
              aria-label={`Delete ${profile.name}`}
              className="flex-none rounded-lg p-1.5 transition-colors hover:bg-rose-500/10 hover:text-danger disabled:opacity-30 disabled:hover:bg-transparent disabled:hover:text-[var(--text-mute)]"
              style={{ color: "var(--text-mute)" }}
            >
              {deleting ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
            </button>
          </>
        )}
      </div>
      {profile.hasPin && (editing === "pin" || editing === "removePin") && (
        canClearPin ? (
          <button
            ref={forgotRef}
            type="button"
            onClick={() => {
              setClearPinFrom(editing === "removePin" ? "removePin" : "pin");
              setEditing("clearPin");
            }}
            className="mt-1.5 text-xs font-medium text-accent-text hover:underline"
          >
            Forgot the PIN?
          </button>
        ) : (
          <p className="mt-1.5 text-xs" style={{ color: "var(--text-mute)" }}>
            Forgot the PIN? Ask the admin to clear it.
          </p>
        )
      )}
      {editing === "clearPin" && (
        <ClearPinReview
          profile={profile}
          onCleared={(p) => { onUpdated(p); setEditing("none"); showToast(`PIN cleared for ${p.name}`); }}
          onCancel={() => {
            setEditing(clearPinFrom);
            setRefocusForgot(true);
          }}
        />
      )}
      {editing === "settings" && (
        <ProfileEditor
          profile={profile}
          isPrimary={isPrimary}
          needsPin={needsPin}
          onSaved={(p) => { onUpdated(p); setEditing("none"); showToast(`Saved ${p.name}`); }}
          onCancel={() => setEditing("none")}
        />
      )}
      {editing === "reviewDelete" && (
        <DeleteReview
          profile={profile}
          deleting={deleting}
          onConfirm={() => void remove()}
          onCancel={() => setEditing("none")}
        />
      )}
      {error && <p role="alert" className="mt-1.5 flex items-center gap-1.5 text-xs text-danger"><AlertCircle size={13} /> {error}</p>}
    </div>
  );
}

/**
 * How long a PIN profile stays open on this browser without use. Per browser,
 * not per profile: it is about whether this computer is shared.
 */
function IdleLockSetting() {
  const minutes = useIdleLockMinutes();
  const id = useId();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <label htmlFor={id} className="text-sm" style={{ color: "var(--text-dim)" }}>
        Lock PIN profiles on this browser after
      </label>
      <SelectField
        id={id}
        value={String(minutes)}
        onChange={(e) => setIdleLockMinutes(Number(e.target.value))}
        className="rounded-xl px-3 py-2 text-sm font-medium"
      >
        {IDLE_LOCK_CHOICES.map((choice) => (
          <option key={choice} value={choice}>
            {choice === 0 ? "Never (stay open 12 hours)" : `${choice} minutes of inactivity`}
          </option>
        ))}
      </SelectField>
    </div>
  );
}

function AddProfileForm({ onCreated }: { onCreated: (profile: Profile) => void }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    setSaving(true);
    setError(null);
    try {
      const { profile } = await api.createProfile({ name: trimmed });
      onCreated(profile);
      setName("");
      setOpen(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not add the profile.");
    } finally {
      setSaving(false);
    }
  };

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="btn-secondary">
        <Plus size={16} /> Add profile
      </button>
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
      <input
        autoFocus
        type="text"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Name"
        aria-label="New profile name"
        className="min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 text-sm field-control"
        style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
      />
      <button type="submit" disabled={saving || !name.trim()} className="btn-primary btn-sm">
        {saving ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />} Add
      </button>
      <button type="button" onClick={() => setOpen(false)} className="btn-secondary btn-sm">Cancel</button>
      {error && <p role="alert" className="w-full text-xs text-danger">{error}</p>}
    </form>
  );
}

export function ProfileSettings() {
  const { profile: activeProfile, setProfile: setActiveProfile, openSwitcher } = useProfile();
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { showToast } = useToast();

  useEffect(() => {
    void (async () => {
      try {
        const { profiles: fetched } = await api.getProfiles();
        setProfiles(fetched);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load profiles");
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const handleUpdated = (updated: Profile) => {
    setProfiles((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
    if (activeProfile?.id === updated.id) setActiveProfile(updated);
  };

  const handleDeleted = (id: string) => {
    setProfiles((prev) => prev.filter((p) => p.id !== id));
    showToast("Profile deleted");
    if (activeProfile?.id === id) {
      // The profile we were using is gone — force re-selection instead of
      // leaving every subsequent API call scoped to a now-nonexistent profile.
      openSwitcher();
    }
  };

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        {activeProfile ? <>Currently watching as <strong>{activeProfile.name}</strong>. </> : null}
        Each profile has its own watch history, lists, and stats. Rename, PIN-protect, or delete profiles below, and
        set how each one looks and what it may watch — paired TVs pick the changes up on their next sync.
      </p>

      <button
        type="button"
        onClick={openSwitcher}
        className="btn-secondary"
      >
        <Users size={16} /> Switch Profile
      </button>

      {profiles.some((p) => p.hasPin) && <IdleLockSetting />}

      {loading ? (
        <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading profiles…
        </div>
      ) : error ? (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      ) : (
        <div className="space-y-2">
          {profiles.map((profile, index) => (
            <ProfileRow
              key={profile.id}
              profile={profile}
              isActive={activeProfile?.id === profile.id}
              // The oldest profile is the primary one, as on the TV.
              isPrimary={index === 0}
              isOnlyProfile={profiles.length === 1}
              onUpdated={handleUpdated}
              onDeleted={handleDeleted}
            />
          ))}
          <AddProfileForm
            onCreated={(created) => {
              setProfiles((prev) => [...prev, created]);
              showToast(`Added ${created.name}`);
            }}
          />
        </div>
      )}
    </div>
  );
}
