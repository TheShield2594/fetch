import { type FormEvent, useCallback, useEffect, useId, useMemo, useState } from "react";
import { AlertCircle, Check, Copy, Download, KeyRound, Loader2, RefreshCw, ShieldCheck, ShieldOff } from "lucide-react";
import { api, ApiError, type SecondFactor, type TotpStatus } from "../../api";
import { useTransientFlag } from "../../hooks/useTransientFlag";
import { encodeQr, qrSvgPath } from "../../utils/qrCode";
import { ConfirmReview } from "../ConfirmReview";
import { FIELD_LABEL_CLASS, PASSWORD_INPUT_CLASS, PasswordField } from "../PasswordField";

/** "Incorrect password" or "wrong code" for a 401, the server's own sentence otherwise. */
const reauthError = (err: unknown, fallback: string) => {
  if (err instanceof ApiError && err.status === 401) {
    return err.code === "password_incorrect" ? "That password is incorrect." : "That code isn't right.";
  }
  return err instanceof Error ? err.message : fallback;
};

/** The secret as an app's "enter a key" screen wants it: groups of four. */
const groupSecret = (secret: string) => secret.replace(/(.{4})/g, "$1 ").trim();

function QrCode({ value, label }: { value: string; label: string }) {
  const { path, size } = useMemo(() => qrSvgPath(encodeQr(value)), [value]);
  return (
    // Always dark on white, whatever the theme: that is what scanners expect.
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${size} ${size}`}
      className="h-44 w-44 flex-none rounded-lg"
      style={{ background: "#fff" }}
      shapeRendering="crispEdges"
    >
      <path d={path} fill="#000" />
    </svg>
  );
}

function CodeField({
  label,
  value,
  onChange,
  recovery = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  recovery?: boolean;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className={FIELD_LABEL_CLASS}>{label}</label>
      <input
        id={id}
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        inputMode={recovery ? "text" : "numeric"}
        autoComplete="one-time-code"
        autoCapitalize="none"
        spellCheck={false}
        maxLength={recovery ? 16 : 7}
        className={`mt-1.5 ${PASSWORD_INPUT_CLASS} font-mono tracking-widest`}
      />
    </div>
  );
}

/** The recovery codes, shown once, with ways to keep them. */
function RecoveryCodes({ codes, username, onDone }: { codes: string[]; username: string | null; onDone: () => void }) {
  const [copied, setCopied] = useTransientFlag();
  const text = codes.join("\n");
  const download = () => {
    const blob = new Blob(
      [`Fetch recovery codes${username ? ` for ${username}` : ""}\nEach code works once.\n\n${text}\n`],
      { type: "text/plain" }
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "fetch-recovery-codes.txt";
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-3 rounded-xl border p-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Save your recovery codes</p>
      <p className="text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
        If you lose your phone, each of these signs you in once in place of a code. They won&apos;t be shown again:
        keep them somewhere safe, away from your phone.
      </p>
      <ul className="grid grid-cols-2 gap-x-4 gap-y-1 font-mono text-sm" style={{ color: "var(--text-dim)" }} aria-label="Recovery codes">
        {codes.map((code) => <li key={code}>{code}</li>)}
      </ul>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn-secondary btn-sm"
          onClick={() => {
            navigator.clipboard.writeText(text).then(() => setCopied(true), () => undefined);
          }}
        >
          {copied ? <><Check size={14} /> Copied</> : <><Copy size={14} /> Copy</>}
        </button>
        <button type="button" className="btn-secondary btn-sm" onClick={download}>
          <Download size={14} /> Download
        </button>
        <button type="button" className="btn-primary btn-sm" onClick={onDone}>
          I&apos;ve saved them
        </button>
      </div>
    </div>
  );
}

/** Turning two-factor on: password, then the QR code and a code from the app. */
function Enrol({ username, onEnabled, onCancel }: { username: string | null; onEnabled: (codes: string[]) => void; onCancel: () => void }) {
  const [password, setPassword] = useState("");
  const [setup, setSetup] = useState<{ secret: string; uri: string } | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setSetup(await api.startTotpSetup(password));
      setPassword("");
    } catch (err) {
      setError(reauthError(err, "Could not start setting up two-factor sign-in."));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { recoveryCodes } = await api.enableTotp(code);
      onEnabled(recoveryCodes);
    } catch (err) {
      setError(err instanceof ApiError && err.code === "totp_incorrect"
        ? "That code isn't right. Check the time on your phone, and enter the code it shows now."
        : reauthError(err, "Could not turn on two-factor sign-in."));
      setCode("");
    } finally {
      setBusy(false);
    }
  };

  if (!setup) {
    return (
      <form onSubmit={start} noValidate className="space-y-3">
        <PasswordField label="Current password" value={password} onChange={setPassword} autoComplete="current-password" autoFocus />
        {error && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>}
        <div className="flex gap-2">
          <button type="submit" disabled={busy || !password} className="btn-primary">
            {busy ? <Loader2 size={16} className="animate-spin" /> : null} Continue
          </button>
          <button type="button" onClick={onCancel} className="btn-secondary">Cancel</button>
        </div>
      </form>
    );
  }

  return (
    <form onSubmit={confirm} noValidate className="space-y-4">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <QrCode value={setup.uri} label="QR code to add Fetch to an authenticator app" />
        <div className="min-w-0 space-y-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <p>
            Scan this with an authenticator app (such as 1Password, Bitwarden, Google Authenticator or Aegis), then enter the
            6-digit code it shows.
          </p>
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>Can&apos;t scan? Enter this key in the app instead:</p>
          <code className="block break-all rounded-lg border px-3 py-2 font-mono text-xs select-all" style={{ borderColor: "var(--border)", background: "var(--bg-0)" }}>
            {groupSecret(setup.secret)}
          </code>
          {username && <p className="text-xs" style={{ color: "var(--text-mute)" }}>It appears in the app as Fetch ({username}).</p>}
        </div>
      </div>
      <CodeField label="Code from the app" value={code} onChange={setCode} />
      {error && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>}
      <div className="flex gap-2">
        <button type="submit" disabled={busy || code.replace(/\s/g, "").length !== 6} className="btn-primary">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <ShieldCheck size={16} />} Turn on
        </button>
        <button type="button" onClick={onCancel} className="btn-secondary">Cancel</button>
      </div>
    </form>
  );
}

/** Password and a current code (or a recovery code), for turning off or new recovery codes. */
function Reauth({
  label,
  message,
  confirmLabel,
  icon,
  tone,
  onSubmit,
  onCancel,
}: {
  label: string;
  message: string;
  confirmLabel: string;
  icon: React.ReactNode;
  tone: "danger" | "primary";
  onSubmit: (password: string, factor: SecondFactor) => Promise<void>;
  onCancel: () => void;
}) {
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [useRecovery, setUseRecovery] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit(password, useRecovery ? { recoveryCode: code } : { code });
    } catch (err) {
      setError(reauthError(err, "Could not change two-factor sign-in."));
      setCode("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ConfirmReview
      label={label}
      message={message}
      confirmLabel={confirmLabel}
      icon={icon}
      tone={tone}
      onConfirm={() => void submit()}
      onCancel={onCancel}
      busy={busy}
      disabled={!password || !code.trim()}
      error={error}
    >
      <PasswordField label="Current password" value={password} onChange={setPassword} autoComplete="current-password" />
      <CodeField
        label={useRecovery ? "Recovery code" : "Code from the app"}
        value={code}
        onChange={setCode}
        recovery={useRecovery}
      />
      <button
        type="button"
        className="text-xs font-medium text-accent-text underline"
        onClick={() => {
          setUseRecovery((v) => !v);
          setCode("");
        }}
      >
        {useRecovery ? "Use a code from the app instead" : "Use a recovery code instead"}
      </button>
    </ConfirmReview>
  );
}

/**
 * Settings → Account → Two-factor sign-in (#564). Optional and off by default:
 * a server only on the home network has little to gain from it, one reachable
 * from the internet a lot.
 */
export function TwoFactorSettings({ username, onChanged }: { username: string | null; onChanged: () => void }) {
  const [status, setStatus] = useState<TotpStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<"idle" | "enrol" | "disable" | "regenerate">("idle");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await api.getTotpStatus());
      setLoadError(null);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Could not load two-factor sign-in.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (codes) {
    return (
      <RecoveryCodes
        codes={codes}
        username={username}
        onDone={() => {
          setCodes(null);
          void load();
        }}
      />
    );
  }

  if (loadError) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-sm text-danger" role="alert">
        <AlertCircle size={16} /> {loadError}
        <button type="button" className="btn-secondary btn-sm" onClick={() => void load()}>
          <RefreshCw size={14} /> Retry
        </button>
      </div>
    );
  }

  if (!status) {
    return <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}><Loader2 size={16} className="animate-spin" /> Loading…</p>;
  }

  if (!status.enabled) {
    return (
      <div className="space-y-3">
        <p className="text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
          Ask for a code from an authenticator app as well as your password when signing in to the website. Worth turning on
          if your server can be reached from the internet. TVs aren&apos;t affected: they sign in by pairing.
        </p>
        {notice && <p role="status" className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}><Check size={16} /> {notice}</p>}
        {mode === "enrol" ? (
          <Enrol
            username={username}
            onEnabled={(recoveryCodes) => {
              setMode("idle");
              setNotice(null);
              setCodes(recoveryCodes);
              onChanged();
            }}
            onCancel={() => setMode("idle")}
          />
        ) : (
          <button type="button" className="btn-secondary" onClick={() => setMode("enrol")}>
            <ShieldCheck size={16} /> Turn on two-factor sign-in
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <p className="flex flex-wrap items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <ShieldCheck size={16} className="text-success" aria-hidden="true" />
        <span>
          On. Signing in asks for a code from your authenticator app.{" "}
          <span className={status.recoveryCodesLeft <= 2 ? "text-warning" : undefined} style={status.recoveryCodesLeft <= 2 ? undefined : { color: "var(--text-mute)" }}>
            {status.recoveryCodesLeft === 1 ? "1 recovery code left." : `${status.recoveryCodesLeft} recovery codes left.`}
          </span>
        </span>
      </p>
      {mode === "disable" ? (
        <Reauth
          label="Turn off two-factor sign-in?"
          message="Signing in will ask for your password alone. Your recovery codes stop working."
          confirmLabel="Turn off"
          icon={<ShieldOff size={14} />}
          tone="danger"
          onSubmit={async (password, factor) => {
            await api.disableTotp(password, factor);
            setMode("idle");
            setNotice("Two-factor sign-in is off.");
            onChanged();
            await load();
          }}
          onCancel={() => setMode("idle")}
        />
      ) : mode === "regenerate" ? (
        <Reauth
          label="Make new recovery codes?"
          message="Your old recovery codes stop working, and ten new ones are shown once."
          confirmLabel="Make new codes"
          icon={<KeyRound size={14} />}
          tone="primary"
          onSubmit={async (password, factor) => {
            const { recoveryCodes } = await api.regenerateRecoveryCodes(password, factor);
            setMode("idle");
            setCodes(recoveryCodes);
            onChanged();
          }}
          onCancel={() => setMode("idle")}
        />
      ) : (
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-secondary" onClick={() => setMode("regenerate")}>
            <KeyRound size={16} /> New recovery codes
          </button>
          <button type="button" className="btn-secondary" onClick={() => setMode("disable")}>
            <ShieldOff size={16} /> Turn off
          </button>
        </div>
      )}
    </div>
  );
}
