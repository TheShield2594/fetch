import { type FormEvent, useCallback, useEffect, useState } from "react";
import { AlertCircle, Check, Loader2, Pencil, Tv, X } from "lucide-react";
import { api, ApiError, type Device } from "../../api";
import { useToast } from "../../hooks/useToast";
import { timeAgo } from "../../utils/timeAgo";

/**
 * What a person types: letters and digits, uppercased, with the dash put back
 * after the fourth character so it reads the way the TV shows it.
 */
export function formatPairingCodeInput(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
}

const isCompleteCode = (code: string) => code.replace("-", "").length === 8;

const INPUT =
  "w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm field-control";
const INPUT_STYLE = { borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" };

/**
 * The code box: confirms the code a TV is showing, which signs that TV in with
 * a token of its own. Shared by Settings and the `/pair` page a TV's QR code
 * opens.
 */
export function PairingCodeForm({
  initialCode = "",
  onPaired,
}: {
  initialCode?: string;
  onPaired: (deviceName: string) => void;
}) {
  const [code, setCode] = useState(() => formatPairingCodeInput(initialCode));
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!isCompleteCode(code)) return;
    setSaving(true);
    setError(null);
    try {
      const { deviceName } = await api.confirmPairing(code, name.trim() || undefined);
      setCode("");
      setName("");
      onPaired(deviceName);
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 404
          ? "That code is wrong or has expired. Check the TV for the current one."
          : err instanceof Error
            ? err.message
            : "Could not confirm the code."
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <input
          type="text"
          value={code}
          onChange={(e) => setCode(formatPairingCodeInput(e.target.value))}
          placeholder="ABCD-EFGH"
          aria-label="Code shown on the TV"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          className={`${INPUT} max-w-[11rem] font-mono tracking-[0.12em]`}
          style={INPUT_STYLE}
        />
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Name (optional, e.g. Living room)"
          aria-label="Name for this TV"
          maxLength={80}
          className={`${INPUT} flex-1`}
          style={INPUT_STYLE}
        />
        <button type="submit" disabled={saving || !isCompleteCode(code)} className="btn-primary btn-sm">
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} Sign in TV
        </button>
      </div>
      {error && (
        <p role="alert" className="flex items-center gap-1.5 text-xs text-danger">
          <AlertCircle size={13} /> {error}
        </p>
      )}
    </form>
  );
}

/** The API keeps the first 80 characters of a device name; the field says so up front. */
const MAX_DEVICE_NAME_LENGTH = 80;

function DeviceRow({
  device,
  onRevoked,
  onRenamed,
}: {
  device: Device;
  onRevoked: (id: string) => void;
  onRenamed: (device: Device) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draftName, setDraftName] = useState(device.name);
  const [renaming, setRenaming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const revoke = async () => {
    setRevoking(true);
    setError(null);
    try {
      await api.revokeDevice(device.id);
      onRevoked(device.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not revoke this device.");
      setRevoking(false);
    }
  };

  const startEditing = () => {
    setDraftName(device.name);
    setError(null);
    setConfirming(false);
    setEditing(true);
  };

  const rename = async (e: FormEvent) => {
    e.preventDefault();
    const name = draftName.trim();
    if (!name) return;
    if (name === device.name) {
      setEditing(false);
      return;
    }
    setRenaming(true);
    setError(null);
    try {
      const { device: updated } = await api.renameDevice(device.id, name);
      onRenamed(updated);
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not rename this device.");
    } finally {
      setRenaming(false);
    }
  };

  return (
    <li className="rounded-xl border px-3 py-2.5" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
      <div className="flex items-center gap-3">
        <Tv size={18} className="flex-none" style={{ color: "var(--text-mute)" }} />
        {editing ? (
          <form onSubmit={rename} className="flex min-w-0 flex-1 items-center gap-1">
            <input
              // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the Rename button, so focus belongs here
              autoFocus
              value={draftName}
              onChange={(e) => setDraftName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  e.stopPropagation();
                  setEditing(false);
                }
              }}
              maxLength={MAX_DEVICE_NAME_LENGTH}
              aria-label={`New name for ${device.name}`}
              className={INPUT}
              style={INPUT_STYLE}
            />
            <button type="submit" disabled={renaming || !draftName.trim()} aria-label="Save name" className="btn-secondary btn-sm flex-none">
              {renaming ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />}
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              aria-label="Cancel renaming"
              className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)]"
              style={{ color: "var(--text-mute)" }}
            >
              <X size={16} />
            </button>
          </form>
        ) : (
          <>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium" style={{ color: "var(--text)" }}>{device.name}</p>
              <p className="text-xs" style={{ color: "var(--text-mute)" }}>
                {device.lastSeenAt ? `Last seen ${timeAgo(device.lastSeenAt)}` : "Not seen since pairing"} · paired{" "}
                {new Date(device.createdAt).toLocaleDateString()}
              </p>
            </div>
            {confirming ? (
              <div className="flex flex-none items-center gap-1">
                <button type="button" onClick={revoke} disabled={revoking} className="btn-danger btn-sm">
                  {revoking ? <Loader2 size={14} className="animate-spin" /> : null} Sign out
                </button>
                <button
                  type="button"
                  onClick={() => setConfirming(false)}
                  aria-label="Keep this device"
                  className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)]"
                  style={{ color: "var(--text-mute)" }}
                >
                  <X size={16} />
                </button>
              </div>
            ) : (
              <div className="flex flex-none items-center gap-1">
                <button
                  type="button"
                  onClick={startEditing}
                  aria-label={`Rename ${device.name}`}
                  className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)]"
                  style={{ color: "var(--text-mute)" }}
                >
                  <Pencil size={16} />
                </button>
                <button
                  type="button"
                  onClick={() => setConfirming(true)}
                  aria-label={`Revoke ${device.name}`}
                  className="btn-secondary btn-sm"
                >
                  Revoke
                </button>
              </div>
            )}
          </>
        )}
      </div>
      {error && <p role="alert" className="mt-1.5 text-xs text-danger">{error}</p>}
    </li>
  );
}

/**
 * Settings → Devices: the TVs signed in to this install, each with its own
 * token, and the box to sign in another one.
 */
export function DevicesSettings() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { showToast } = useToast();

  const load = useCallback(async () => {
    try {
      const { devices: fetched } = await api.getDevices();
      setDevices(fetched);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load devices");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        On the TV, open <strong>Settings → Account → Fetch website</strong>. It shows a code: type it here, or scan
        its QR code with your phone. The TV doesn&apos;t need this site&apos;s address. Each TV gets its own sign-in,
        so revoking one leaves the others alone.
      </p>

      <PairingCodeForm
        onPaired={(deviceName) => {
          showToast(`${deviceName} is signed in`);
          void load();
        }}
      />

      {loading ? (
        <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading devices...
        </div>
      ) : error ? (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      ) : devices.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--text-mute)" }}>No TVs are signed in yet.</p>
      ) : (
        <ul className="space-y-2" aria-label="Signed-in devices">
          {devices.map((device) => (
            <DeviceRow
              key={device.id}
              device={device}
              onRevoked={(id) => {
                setDevices((prev) => prev.filter((d) => d.id !== id));
                showToast(`${device.name} was signed out`);
              }}
              onRenamed={(updated) => setDevices((prev) => prev.map((d) => (d.id === updated.id ? updated : d)))}
            />
          ))}
        </ul>
      )}
    </div>
  );
}
