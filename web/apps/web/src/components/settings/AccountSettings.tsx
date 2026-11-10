import { type FormEvent, useEffect, useState } from "react";
import { AlertCircle, Check, Loader2, LogOut, Mail } from "lucide-react";
import { type Account, api } from "../../api";
import { useAccount } from "../../hooks/useAccount";

const INPUT_CLASS =
  "w-full rounded-xl border border-[var(--border-field)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] field-control";

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

/**
 * The account's email address: adding, changing or removing it (with the
 * password, since whoever has the address can reset it), confirming it, and
 * email notifications once it is confirmed.
 */
function EmailSection({ account, onChanged }: { account: Account; onChanged: () => void }) {
  const [emailEnabled, setEmailEnabled] = useState<boolean | null>(null);
  const [editing, setEditing] = useState(false);
  const [email, setEmail] = useState(account.email ?? "");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getAuthStatus()
      .then((status) => {
        if (!cancelled) setEmailEnabled(status.emailEnabled === true);
      })
      .catch(() => {
        if (!cancelled) setEmailEnabled(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const run = async (work: () => Promise<string | null>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      setNotice(await work());
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  };

  const save = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const result = await api.setEmail(email.trim(), password);
      setPassword("");
      setEditing(false);
      if (!result.account.email) return "Email address removed.";
      return result.verificationSent
        ? `Saved. We sent a link to ${result.account.email} to confirm it.`
        : "Saved.";
    });
  };

  const resend = () =>
    void run(async () => {
      await api.resendEmailVerification();
      return `We sent a new confirmation link to ${account.email}.`;
    });

  const toggleNotifications = (enabled: boolean) =>
    void run(async () => {
      await api.setEmailNotifications(enabled);
      return enabled ? "Notifications will be emailed to you." : "Email notifications are off.";
    });

  const verified = account.emailVerified === true;

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Email</p>
      {!editing && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
            <Mail size={16} aria-hidden="true" />
            {account.email ? (
              <>
                <span style={{ color: "var(--text)" }}>{account.email}</span>
                <span className={`text-xs ${verified ? "text-success" : ""}`}>{verified ? "Confirmed" : "Not confirmed"}</span>
              </>
            ) : (
              "No email address"
            )}
          </p>
          <div className="flex gap-2">
            {account.email && !verified && emailEnabled && (
              <button type="button" onClick={resend} disabled={busy} className="btn-secondary">
                Resend link
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                setEmail(account.email ?? "");
                setEditing(true);
                setError(null);
                setNotice(null);
              }}
              className="btn-secondary"
            >
              {account.email ? "Change" : "Add email"}
            </button>
          </div>
        </div>
      )}
      {editing && (
        <form onSubmit={save} className="space-y-3">
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-label="Email address"
            placeholder="Email address (leave empty to remove)"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            className={INPUT_CLASS}
          />
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-label="Current password"
            placeholder="Current password"
            autoComplete="current-password"
            className={INPUT_CLASS}
          />
          <div className="flex gap-2">
            <button type="submit" disabled={busy || !password} className="btn-primary">
              {busy ? <Loader2 size={16} className="animate-spin" /> : null} Save
            </button>
            <button type="button" onClick={() => setEditing(false)} className="btn-secondary">
              Cancel
            </button>
          </div>
        </form>
      )}
      {emailEnabled === false && (
        <p className="text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
          You can sign in with your email address. This server isn't set up to send email (SMTP), so it can't be
          confirmed yet, and password resets and email notifications aren't available.
        </p>
      )}
      {emailEnabled && account.email && (
        <div className="flex items-start gap-3 text-sm" style={{ color: "var(--text-dim)" }}>
          <input
            id="email-notifications"
            type="checkbox"
            className="mt-0.5"
            aria-describedby="email-notifications-hint"
            checked={account.emailNotifications === true}
            disabled={busy || !verified}
            onChange={(e) => toggleNotifications(e.target.checked)}
          />
          <div>
            <label htmlFor="email-notifications" style={{ color: "var(--text)" }}>
              Email me notifications
            </label>
            <p id="email-notifications-hint" className="text-xs">
              New episodes of the shows you follow, for every profile on this account.
              {!verified && " Confirm your address first."}
            </p>
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      {notice && (
        <p role="status" className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {notice}</p>
      )}
    </div>
  );
}

export function AccountSettings() {
  const { account, refresh } = useAccount();
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
      {account && <EmailSection account={account} onChanged={refresh} />}
      {account && <ChangePasswordForm />}
    </div>
  );
}
