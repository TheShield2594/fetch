import { type FormEvent, useCallback, useEffect, useState } from "react";
import { AlertCircle, Check, Loader2, Tv, X } from "lucide-react";
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
  "w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm focus:border-claw-500 focus:outline-none focus:ring-2 focus:ring-claw-500/15";
const INPUT_STYLE = { borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-1)" };

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

function DeviceRow({ device, onRevoked }: { device: Device; onRevoked: (id: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const [revoking, setRevoking] = useState(false);
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

  return (
    <li className="rounded-xl border px-3 py-2.5" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
      <div className="flex items-center gap-3">
        <Tv size={18} className="flex-none" style={{ color: "var(--text-mute)" }} />
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
          <button
            type="button"
            onClick={() => setConfirming(true)}
            aria-label={`Revoke ${device.name}`}
            className="btn-secondary btn-sm flex-none"
          >
            Revoke
          </button>
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
        On the TV, open <strong>Settings → Account → Fetch website</strong> and enter this site&apos;s API address.
        The TV shows a code: type it here, or scan its QR code with your phone. Each TV gets its own sign-in, so
        revoking one leaves the others alone.
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
            />
          ))}
        </ul>
      )}
    </div>
  );
}
