import { type FormEvent, useState } from "react";
import { AlertCircle, AlertTriangle, Check, Loader2, LogOut, Pencil, Trash2 } from "lucide-react";
import { api, ApiError } from "../../api";
import { useAccount } from "../../hooks/useAccount";
import { ConfirmReview } from "../ConfirmReview";
import { newPasswordProblem, PasswordField } from "../PasswordField";
import { SignInsSettings } from "./SignInsSettings";
import { ActivitySettings } from "./ActivitySettings";

const SUBHEADING = "text-sm font-semibold";
const DIVIDED = "space-y-3 border-t pt-6";

/** "Incorrect password" for a 401, the server's own sentence otherwise. */
const passwordError = (err: unknown, fallback: string) =>
  err instanceof ApiError && err.status === 401
    ? "That password is incorrect."
    : err instanceof Error
      ? err.message
      : fallback;

const USERNAME_PATTERN = /^[a-z0-9_.-]{3,32}$/;

function UsernameForm({ current, onDone, onCancel }: { current: string; onDone: () => void; onCancel: () => void }) {
  const [username, setUsername] = useState(current);
  const [password, setPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const normalized = username.trim().toLowerCase();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!USERNAME_PATTERN.test(normalized)) {
      setError("Use 3–32 characters: letters, numbers, dots, dashes or underscores.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api.changeUsername(normalized, password);
      onDone();
    } catch (err) {
      setError(passwordError(err, "Could not change the username."));
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-3">
      <div>
        <label htmlFor="account-username" className="block text-xs font-semibold" style={{ color: "var(--text)" }}>
          New username
        </label>
        <input
          id="account-username"
          // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the Change button, so focus belongs here
          autoFocus
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          maxLength={32}
          aria-describedby="account-username-rule"
          className="mt-1.5 w-full rounded-xl border border-[var(--border-field)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] field-control"
        />
        <p id="account-username-rule" className="mt-1.5 text-xs" style={{ color: "var(--text-mute)" }}>
          3–32 characters: letters, numbers, dots, dashes or underscores. You sign in with it; it is stored in lower case.
        </p>
      </div>
      <PasswordField label="Current password" value={password} onChange={setPassword} autoComplete="current-password" />
      {error && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>}
      <div className="flex gap-2">
        <button type="submit" disabled={saving || !password || normalized === current} className="btn-primary btn-sm">
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} Save username
        </button>
        <button type="button" onClick={onCancel} disabled={saving} className="btn-secondary btn-sm">Cancel</button>
      </div>
    </form>
  );
}

function ChangePasswordForm({ onChanged }: { onChanged: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [signOutTvs, setSignOutTvs] = useState(false);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<{ next?: string | undefined; confirm?: string | undefined }>({});
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const found = {
      next: newPasswordProblem(next) ?? undefined,
      confirm: confirm !== next ? "The new passwords don't match." : undefined,
    };
    setErrors(found);
    setDone(null);
    if (found.next || found.confirm) return;
    setSaving(true);
    setError(null);
    try {
      await api.changePassword(current, next, { signOutTvs });
      setCurrent("");
      setNext("");
      setConfirm("");
      setDone(
        signOutTvs
          ? "Password changed. Your other browsers and every TV have been signed out."
          : "Password changed. Your other browsers have been signed out."
      );
      setSignOutTvs(false);
      onChanged();
    } catch (err) {
      setError(passwordError(err, "Could not change the password."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-3">
      <PasswordField label="Current password" value={current} onChange={setCurrent} autoComplete="current-password" />
      <PasswordField
        label="New password"
        value={next}
        onChange={setNext}
        autoComplete="new-password"
        error={errors.next}
        newPassword
      />
      <PasswordField
        label="Confirm new password"
        value={confirm}
        onChange={setConfirm}
        autoComplete="new-password"
        error={errors.confirm}
      />
      <label className="flex items-start gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <input
          type="checkbox"
          checked={signOutTvs}
          onChange={(e) => setSignOutTvs(e.target.checked)}
          className="mt-0.5"
        />
        <span>
          Also sign out every TV. Your other browsers are always signed out; a TV keeps its own sign-in unless you choose
          this, and will need pairing again.
        </span>
      </label>
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}
      {done && (
        <p role="status" className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {done}</p>
      )}
      <button type="submit" disabled={saving || !current || !next || !confirm} className="btn-secondary">
        {saving ? <Loader2 size={16} className="animate-spin" /> : null} Change password
      </button>
    </form>
  );
}

/**
 * Deleting your own account. The admin can't: the install would be left with
 * nobody to run it, so the admin role is handed on first (Invites & accounts).
 */
function DeleteAccount({ isAdmin }: { isAdmin: boolean }) {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (isAdmin) {
    return (
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        You are the admin, so this account can&apos;t be deleted while it runs the server. Make another account the admin
        in Invites &amp; accounts first.
      </p>
    );
  }

  const remove = async () => {
    setDeleting(true);
    setError(null);
    try {
      await api.deleteMyAccount(password);
      window.dispatchEvent(new Event("fetch:unauthorized"));
    } catch (err) {
      setError(passwordError(err, "Could not delete the account."));
      setDeleting(false);
    }
  };

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="btn-secondary btn-sm text-danger">
        <Trash2 size={14} /> Delete my account
      </button>
    );
  }

  return (
    <ConfirmReview
      label="Delete your account?"
      className="space-y-3"
      message={
        <>
          This deletes your account and every profile in it: lists, watch history, ratings and resume points, and signs out
          your TVs. It can&apos;t be undone. Export anything you want to keep from Data first.
        </>
      }
      confirmLabel="Delete my account"
      onConfirm={() => void remove()}
      onCancel={() => {
        setOpen(false);
        setPassword("");
        setError(null);
      }}
      busy={deleting}
      disabled={!password}
      error={error}
    >
      <PasswordField label="Your password" value={password} onChange={setPassword} autoComplete="current-password" autoFocus />
    </ConfirmReview>
  );
}

export function AccountSettings() {
  const { account, refresh } = useAccount();
  const [signingOut, setSigningOut] = useState(false);
  const [editingName, setEditingName] = useState(false);
  // Bumped when something here changes the sign-ins or the activity, so those
  // lists read again rather than show what was true before.
  const [epoch, setEpoch] = useState(0);
  const changed = () => setEpoch((n) => n + 1);

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
      {account?.mustChangePassword && (
        <p
          role="status"
          className="flex items-start gap-2 rounded-xl px-3 py-2.5 text-sm"
          style={{ border: "1px solid var(--border)", background: "var(--bg-1)", color: "var(--text)" }}
        >
          <AlertTriangle size={16} className="mt-0.5 flex-none text-warning" />
          Your password was set by someone else. Choose your own below.
        </p>
      )}

      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          {account ? (
            <>
              Signed in as <strong style={{ color: "var(--text)" }}>{account.username}</strong>
              {account.isAdmin && <span className="ml-2 text-xs text-accent-text">Admin</span>}
            </>
          ) : (
            "Signed in"
          )}
        </p>
        <div className="flex gap-2">
          {account && !editingName && (
            <button type="button" onClick={() => setEditingName(true)} className="btn-secondary">
              <Pencil size={16} /> Change username
            </button>
          )}
          <button type="button" onClick={() => void signOut()} disabled={signingOut} className="btn-secondary">
            <LogOut size={16} /> Sign out
          </button>
        </div>
      </div>

      {account && editingName && (
        <UsernameForm
          current={account.username}
          onDone={() => {
            setEditingName(false);
            refresh();
            changed();
          }}
          onCancel={() => setEditingName(false)}
        />
      )}

      {account && (
        <>
          <section aria-labelledby="account-password" className={DIVIDED} style={{ borderColor: "var(--border)" }}>
            <h3 id="account-password" className={SUBHEADING} style={{ color: "var(--text)" }}>Change password</h3>
            <ChangePasswordForm
              onChanged={() => {
                refresh();
                changed();
              }}
            />
          </section>

          <section aria-labelledby="account-signins" className={DIVIDED} style={{ borderColor: "var(--border)" }}>
            <h3 id="account-signins" className={SUBHEADING} style={{ color: "var(--text)" }}>Where you&apos;re signed in</h3>
            <SignInsSettings epoch={epoch} onChanged={changed} />
          </section>

          <section aria-labelledby="account-activity" className={DIVIDED} style={{ borderColor: "var(--border)" }}>
            <h3 id="account-activity" className={SUBHEADING} style={{ color: "var(--text)" }}>Recent activity</h3>
            <ActivitySettings epoch={epoch} />
          </section>

          <section aria-labelledby="account-delete" className={DIVIDED} style={{ borderColor: "var(--border)" }}>
            <h3 id="account-delete" className={SUBHEADING} style={{ color: "var(--text)" }}>Delete account</h3>
            <DeleteAccount isAdmin={account.isAdmin} />
          </section>
        </>
      )}
    </div>
  );
}

