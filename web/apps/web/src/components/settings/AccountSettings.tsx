import { type FormEvent, useState } from "react";
import { AlertCircle, Check, Loader2, LogOut } from "lucide-react";
import { api } from "../../api";
import { useAccount } from "../../hooks/useAccount";

const INPUT_CLASS =
  "w-full rounded-xl border border-[var(--border)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] focus:border-claw-500 focus:outline-none focus:ring-2 focus:ring-claw-500/15";

function ChangePasswordForm() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (next !== confirm) {
      setError("The new passwords don't match.");
      return;
    }
    setSaving(true);
    setError(null);
    setDone(false);
    try {
      await api.changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not change the password.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-3">
      <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Change password</p>
      <input
        type="password"
        value={current}
        onChange={(e) => setCurrent(e.target.value)}
        aria-label="Current password"
        placeholder="Current password"
        autoComplete="current-password"
        className={INPUT_CLASS}
      />
      <input
        type="password"
        value={next}
        onChange={(e) => setNext(e.target.value)}
        aria-label="New password"
        placeholder="New password"
        autoComplete="new-password"
        className={INPUT_CLASS}
      />
      <input
        type="password"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
        aria-label="Confirm new password"
        placeholder="Confirm new password"
        autoComplete="new-password"
        className={INPUT_CLASS}
      />
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      {done && (
        <p role="status" className="flex items-center gap-2 text-sm text-success">
          <Check size={16} /> Password changed. Your other devices have been signed out.
        </p>
      )}
      <button type="submit" disabled={saving || !current || !next || !confirm} className="btn-secondary">
        {saving ? <Loader2 size={16} className="animate-spin" /> : null} Change password
      </button>
    </form>
  );
}

export function AccountSettings() {
  const { account } = useAccount();
  const [signingOut, setSigningOut] = useState(false);

  const signOut = async () => {
    setSigningOut(true);
    try {
      await api.logout();
    } catch {
      // Signed out on this device regardless; the server forgets the session
      // when it next expires.
    } finally {
      window.dispatchEvent(new Event("fetch:unauthorized"));
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          {account ? (
            <>
              Signed in as <strong style={{ color: "var(--text)" }}>{account.username}</strong>
              {account.isAdmin && <span className="ml-2 text-xs text-claw-text">Admin</span>}
            </>
          ) : (
            "Signed in"
          )}
        </p>
        <button type="button" onClick={() => void signOut()} disabled={signingOut} className="btn-secondary">
          <LogOut size={16} /> Sign out
        </button>
      </div>
      {account && <ChangePasswordForm />}
    </div>
  );
}
