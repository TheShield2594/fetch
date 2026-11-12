import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, ArrowRight, Check, Loader2 } from "lucide-react";
import { isValidPinFormat, MAX_PIN_LENGTH, MIN_PIN_LENGTH, PIN_FORMAT_ERROR } from "@fetch/shared/pin";
import { api, type Profile, runtimeConfig } from "../../api";
import { SECTION_TITLE } from "../typography";
import { ProfileAvatar } from "../ProfileAvatar";
import { AvatarPicker, type AvatarChoice } from "./AvatarPicker";
import { PinField } from "./PinField";

/**
 * Starts a request flow and returns whether it is still the one that counts.
 * A flow is overtaken by the next one, by closing the surface it started on,
 * or by leaving the form it started from; its late answer must then change
 * nothing.
 */
export type StartFlow = () => () => boolean;

/** A flow tracker for a caller that has none of its own: overtaken by the next flow or by unmounting. */
function useOwnFlows(): StartFlow {
  const flow = useRef(0);
  useEffect(() => () => {
    flow.current += 1;
  }, []);
  return useCallback(() => {
    flow.current += 1;
    const id = flow.current;
    return () => flow.current === id;
  }, []);
}

const LABEL_CLASS = "block text-xs font-semibold";
const INPUT_CLASS = "w-full rounded-xl border px-4 py-3 text-sm field-control";
const INPUT_STYLE = { borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" };

/**
 * Switches this browser into `profile`: checks its PIN (or its absence) for a
 * profile token, then makes it the active one. Resolves to false when the flow
 * was overtaken meanwhile, in which case nothing changed.
 *
 * POST /profiles mints no profile token, and the one in storage (if any)
 * belongs to the previous profile. Without a fresh one, the first request as a
 * PIN-protected profile 401s and drops the user straight back to "Who's
 * watching?" to type the PIN they set a second ago. Verifying with no PIN also
 * clears the stale token for an unprotected profile.
 */
async function enterNewProfile(profile: Profile, pin: string, isCurrent: () => boolean): Promise<boolean> {
  let profileToken: string | null;
  try {
    profileToken = (await api.verifyProfile(profile.id, pin || undefined, { persist: false })).profileToken ?? "";
  } catch {
    // The profile exists either way; worst case the shell asks for the PIN
    // once. Don't send a stale token.
    profileToken = null;
  }
  if (!isCurrent()) return false;
  if (profileToken === null) runtimeConfig.clearProfileToken();
  else runtimeConfig.setProfileToken(profileToken);
  runtimeConfig.setProfileId(profile.id);
  return true;
}

/**
 * Making a profile, the one way it is done everywhere: "Who's watching?", the
 * first run, and Settings → Profiles.
 *
 * A name, a picture and colour, and an optional PIN typed twice. Then, unless
 * this is the household's first profile (which is entered at once, as there
 * is nobody else to be), it asks whether to switch to the new profile now.
 */
export function CreateProfileFlow({
  title,
  subtitle,
  headingTag: Heading = "h2",
  startFlow: startFlowProp,
  enterImmediately = false,
  currentProfileName,
  onCreated,
  onEntered,
  onDone,
  onCancel,
}: {
  title: string;
  subtitle: string;
  headingTag?: "h1" | "h2" | "h3" | undefined;
  startFlow?: StartFlow | undefined;
  /** Skip the question and switch straight in: the first profile on a new install. */
  enterImmediately?: boolean | undefined;
  /** Who the browser is now, for "Stay as …". */
  currentProfileName?: string | undefined;
  /** The profile exists. Called before the switch question, so a list can show it at once. */
  onCreated?: ((profile: Profile) => void) | undefined;
  /** The browser has switched into the new profile. */
  onEntered: (profile: Profile) => void;
  /** Answered "not now": the profile exists, nothing else changed. */
  onDone?: (() => void) | undefined;
  onCancel?: (() => void) | undefined;
}) {
  const ownFlows = useOwnFlows();
  const startFlow = startFlowProp ?? ownFlows;
  const [name, setName] = useState("");
  const [look, setLook] = useState<AvatarChoice>({ avatarColorHex: null, avatarId: null });
  const [pin, setPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [creating, setCreating] = useState(false);
  const [entering, setEntering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<Profile | null>(null);
  const nameId = useId();
  const pinId = useId();
  const pinHintId = useId();
  const confirmId = useId();

  const wantsPin = pin.length > 0;
  const pinsMatch = !wantsPin || pin === confirmPin;

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName || creating) return;
    if (wantsPin && !isValidPinFormat(pin)) {
      setError(`${PIN_FORMAT_ERROR}.`);
      return;
    }
    if (!pinsMatch) {
      setError("The two PINs don't match. Type the same PIN in both.");
      return;
    }

    const isCurrent = startFlow();
    setCreating(true);
    setError(null);
    try {
      const { profile } = await api.createProfile({
        name: trimmedName,
        pin: pin || undefined,
        ...(look.avatarId ? { avatarId: look.avatarId } : {}),
        ...(look.avatarColorHex ? { avatarColorHex: look.avatarColorHex } : {}),
      });
      if (!enterImmediately) {
        if (!isCurrent()) return;
        onCreated?.(profile);
        setCreated(profile);
        return;
      }
      // Closed, cancelled or overtaken meanwhile: the profile exists, but the
      // user has moved on, so the active profile and its token stay as they are.
      if (await enterNewProfile(profile, pin, isCurrent)) onEntered(profile);
    } catch (err) {
      if (isCurrent()) setError(err instanceof Error ? err.message : "Could not create the profile.");
    } finally {
      setCreating(false);
    }
  };

  const switchNow = async () => {
    if (!created) return;
    const isCurrent = startFlow();
    setEntering(true);
    try {
      if (await enterNewProfile(created, pin, isCurrent)) onEntered(created);
    } finally {
      setEntering(false);
    }
  };

  if (created) {
    return (
      <div className="space-y-5 text-center">
        <div className="flex justify-center">
          <ProfileAvatar profile={created} size="xl" />
        </div>
        <div>
          <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>{created.name} is ready</Heading>
          <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
            Switch to {created.name} now?{created.hasPin ? " The PIN you just set opens it from now on." : ""}
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <button type="button" onClick={() => onDone?.()} className="btn-secondary btn-lg flex-1">
            {currentProfileName ? `Stay as ${currentProfileName}` : "Not now"}
          </button>
          <button type="button" onClick={() => void switchNow()} disabled={entering} className="btn-primary btn-lg flex-1">
            {entering ? <Loader2 size={16} className="animate-spin" /> : <ArrowRight size={16} />} Switch to {created.name}
          </button>
        </div>
      </div>
    );
  }

  const preview = { name: name.trim() || "?", ...look };

  return (
    <form onSubmit={submit} className="space-y-5">
      <div>
        <Heading className={SECTION_TITLE} style={{ color: "var(--text)" }}>{title}</Heading>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>{subtitle}</p>
      </div>

      <div className="flex items-center gap-4">
        <ProfileAvatar profile={preview} size="lg" />
        {/* Visible labels rather than placeholders, which vanished on the first keystroke. */}
        <div className="min-w-0 flex-1">
          <label htmlFor={nameId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>Profile name</label>
          <input
            id={nameId}
            autoFocus
            type="text"
            value={name}
            maxLength={40}
            onChange={(e) => setName(e.target.value)}
            className={`mt-1.5 ${INPUT_CLASS}`}
            style={INPUT_STYLE}
          />
        </div>
      </div>

      <AvatarPicker value={look} onChange={setLook} />

      <div className="space-y-3">
        <div>
          <label htmlFor={pinId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>
            PIN <span className="font-normal" style={{ color: "var(--text-mute)" }}>(optional)</span>
          </label>
          <p id={pinHintId} className="mt-0.5 text-xs" style={{ color: "var(--text-mute)" }}>
            {MIN_PIN_LENGTH}–{MAX_PIN_LENGTH} digits. Asked for whenever someone opens this profile, here and on the TV.
          </p>
          <div className="mt-2">
            {/* The same 4–8 digit rule the API enforces and the TV keypad asks
                for, checked on submit so the message says "digits" rather than
                the browser's generic one. */}
            <PinField
              id={pinId}
              label="PIN"
              value={pin}
              onChange={(next) => {
                setPin(next);
                if (!next) setConfirmPin("");
              }}
              onReject={() => setError(`${PIN_FORMAT_ERROR}.`)}
              describedBy={pinHintId}
              autoComplete="new-password"
            />
          </div>
        </div>
        {wantsPin && (
          <div>
            <label htmlFor={confirmId} className={LABEL_CLASS} style={{ color: "var(--text)" }}>Type the PIN again</label>
            <div className="mt-2 flex items-center gap-3">
              <PinField
                id={confirmId}
                label="Type the PIN again"
                value={confirmPin}
                onChange={setConfirmPin}
                length={pin.length}
                invalid={confirmPin.length === pin.length && !pinsMatch}
                autoComplete="new-password"
              />
              {confirmPin.length === pin.length && pinsMatch && pin.length >= MIN_PIN_LENGTH && (
                <Check size={18} className="flex-none text-success" aria-label="PINs match" />
              )}
            </div>
          </div>
        )}
      </div>

      {error && (
        <p role="alert" className="flex items-start gap-2 text-sm text-danger"><AlertCircle size={16} className="mt-0.5 flex-none" /> {error}</p>
      )}
      <div className="flex gap-2">
        {onCancel && (
          <button type="button" onClick={onCancel} className="btn-secondary btn-lg flex-1">
            Cancel
          </button>
        )}
        <button type="submit" disabled={creating || !name.trim()} className="btn-primary btn-lg flex-1">
          {creating ? <><Loader2 size={16} className="animate-spin" /> Creating…</> : <>Create <ArrowRight size={16} /></>}
        </button>
      </div>
    </form>
  );
}
