import { type FormEvent, useCallback, useEffect, useState } from "react";
import { AlertCircle, Check, Copy, KeyRound, Loader2, ShieldCheck, ShieldOff, Trash2, UserPlus } from "lucide-react";
import { type AccountSummary, api, ApiError, type Invite } from "../../api";
import { useAccount } from "../../hooks/useAccount";
import { useTransientFlag } from "../../hooks/useTransientFlag";
import { ConfirmReview } from "../ConfirmReview";
import { newPasswordProblem, PasswordField } from "../PasswordField";
import { SelectField } from "../SelectField";

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

/** How long a new invite link works. The API takes 1–30 days. */
const EXPIRY_CHOICES = [1, 3, 7, 14, 30] as const;
const DEFAULT_EXPIRY_DAYS = 7;

/** An invite with what it says about itself worked out as of `now`, when the list loads. */
type ListedInvite = Invite & { open: boolean; state: string };

const describeInvite = (invite: Invite, now: number): ListedInvite => {
  const expired = new Date(invite.expiresAt).getTime() <= now;
  const state = invite.usedAt
    ? invite.usedBy
      ? `Used by ${invite.usedBy}`
      : "Used"
    : expired
      ? "Expired"
      : `Open until ${formatDate(invite.expiresAt)}`;
  return { ...invite, open: !invite.usedAt && !expired, state };
};

/** "Incorrect password" for the admin's re-entered password, the server's words otherwise. */
const describeError = (err: unknown, fallback: string) =>
  err instanceof ApiError && err.status === 401
    ? "Your password is incorrect."
    : err instanceof Error
      ? err.message
      : fallback;

/** A link made just now. Its code is never shown again, so this is the only chance to copy it. */
function NewInvite({ url }: { url: string }) {
  const [copied, setCopied] = useTransientFlag();
  const [copyError, setCopyError] = useTransientFlag(3000);

  const copy = () => {
    navigator.clipboard.writeText(url).then(() => setCopied(true)).catch(() => setCopyError(true));
  };

  return (
    <div className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Send this link to the person you're inviting. It works once, and it won't be shown again.
      </p>
      <div className="flex items-center gap-2">
        <input
          readOnly
          value={url}
          aria-label="Invite link"
          onFocus={(e) => e.currentTarget.select()}
          className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 font-mono text-xs field-control"
          style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-0)" }}
        />
        <button type="button" onClick={copy} className="btn-secondary flex-none" aria-label="Copy invite link">
          {copied ? <Check size={16} /> : <Copy size={16} />}
        </button>
      </div>
      {copyError && <p role="alert" className="text-xs text-danger">Couldn't copy. Select the link and copy it by hand.</p>}
    </div>
  );
}

/**
 * A temporary password for a member who forgot theirs. The admin types it,
 * passes it on, and the member chooses their own at their next sign-in.
 */
function TemporaryPasswordForm({
  account,
  onDone,
  onCancel,
}: {
  account: AccountSummary;
  onDone: () => void;
  onCancel: () => void;
}) {
  const [password, setPassword] = useState("");
  const [adminPassword, setAdminPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | undefined>();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const problem = newPasswordProblem(password);
    setFieldError(problem ?? undefined);
    if (problem || !adminPassword) return;
    setSaving(true);
    setError(null);
    try {
      await api.setMemberPassword(account.id, password, adminPassword);
      onDone();
    } catch (err) {
      setError(describeError(err, "Could not set the password."));
      setSaving(false);
    }
  };

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label={`Temporary password for ${account.username}`}
      className="mt-3 space-y-3 border-t pt-3"
      style={{ borderColor: "var(--border)" }}
    >
      <p className="text-sm" style={{ color: "var(--text)" }}>
        Give <strong>{account.username}</strong> a temporary password. They&apos;re signed out of every browser and asked
        to choose their own when they next sign in. Their TVs stay signed in. Pass the password on to them yourself.
      </p>
      <PasswordField
        label="Temporary password"
        value={password}
        onChange={setPassword}
        autoComplete="new-password"
        newPassword
        error={fieldError}
        // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the Temporary password button, so focus belongs here
        autoFocus
      />
      <PasswordField label="Your password" value={adminPassword} onChange={setAdminPassword} autoComplete="current-password" />
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
      <div className="flex flex-wrap gap-2">
        <button type="submit" disabled={saving || !password || !adminPassword} className="btn-primary btn-sm">
          {saving ? <Loader2 size={14} className="animate-spin" /> : <KeyRound size={14} />} Set temporary password
        </button>
        <button type="button" onClick={onCancel} disabled={saving} className="btn-secondary btn-sm">Cancel</button>
      </div>
    </form>
  );
}

type AccountAction = "remove" | "password" | "admin" | "totp";

function AccountRow({
  account,
  isMe,
  onChanged,
  onMadeAdmin,
}: {
  account: AccountSummary;
  isMe: boolean;
  onChanged: () => void;
  onMadeAdmin: () => void;
}) {
  const [action, setAction] = useState<AccountAction | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adminPassword, setAdminPassword] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const member = !account.isAdmin && !isMe;

  const open = (next: AccountAction) => {
    setAction(next);
    setError(null);
    setNotice(null);
    setAdminPassword("");
  };

  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.deleteAccount(account.id);
    } catch (err) {
      setError(describeError(err, "Could not remove the account."));
      setBusy(false);
      return;
    }
    // Done whether or not the list reloads: a failed reload must not leave
    // the review stuck on a busy button for an account that is gone.
    setBusy(false);
    setAction(null);
    onChanged();
  };

  const makeAdmin = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.makeAdmin(account.id, adminPassword);
      onMadeAdmin();
    } catch (err) {
      setError(describeError(err, "Could not make this account the admin."));
      setBusy(false);
    }
  };

  const removeTotp = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.removeMemberTotp(account.id, adminPassword);
    } catch (err) {
      setError(describeError(err, "Could not turn off two-factor sign-in."));
      setBusy(false);
      return;
    }
    setBusy(false);
    setAction(null);
    setNotice(`Two-factor sign-in is off for ${account.username}. They sign in with their password alone.`);
    onChanged();
  };

  const profiles = `${account.profileCount} ${account.profileCount === 1 ? "profile" : "profiles"}`;

  return (
    <li className="rounded-xl border px-3 py-2.5 text-sm" style={{ borderColor: "var(--border)", background: "var(--bg-1)", color: "var(--text-dim)" }}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span>
          <strong style={{ color: "var(--text)" }}>{account.username}</strong>
          {account.isAdmin && <span className="ml-2 text-xs text-accent-text">Admin</span>}
          {isMe && <span className="ml-2 text-xs">You</span>}
          {account.totpEnabled && <span className="ml-2 text-xs">Two-factor on</span>}
          <span className="ml-2 text-xs">{profiles}</span>
        </span>
        {member && action === null && (
          <span className="flex flex-wrap items-center gap-1">
            <button type="button" onClick={() => open("password")} className="btn-secondary btn-sm" aria-label={`Set a temporary password for ${account.username}`}>
              <KeyRound size={14} /> Temporary password
            </button>
            {account.totpEnabled && (
              <button type="button" onClick={() => open("totp")} className="btn-secondary btn-sm" aria-label={`Turn off two-factor sign-in for ${account.username}`}>
                <ShieldOff size={14} /> Turn off two-factor
              </button>
            )}
            <button type="button" onClick={() => open("admin")} className="btn-secondary btn-sm" aria-label={`Make ${account.username} the admin`}>
              <ShieldCheck size={14} /> Make admin
            </button>
            <button
              type="button"
              onClick={() => open("remove")}
              aria-label={`Remove ${account.username}`}
              className="rounded-lg p-1.5 text-danger hover:bg-[var(--surface-strong)]"
            >
              <Trash2 size={16} />
            </button>
          </span>
        )}
      </div>
      {notice && (
        <p role="status" className="mt-2 flex items-center gap-1.5 text-xs text-success"><Check size={14} /> {notice}</p>
      )}
      {action === "remove" && (
        <ConfirmReview
          label={`Remove ${account.username}?`}
          message={
            <>
              Remove <strong>{account.username}</strong>? Their {profiles} and everything in them go too: lists, watch
              history, ratings and resume points. Their TVs are signed out. This can&apos;t be undone.
            </>
          }
          confirmLabel="Remove account"
          onConfirm={() => void remove()}
          onCancel={() => setAction(null)}
          busy={busy}
          error={error}
        />
      )}
      {action === "password" && (
        <TemporaryPasswordForm
          account={account}
          onCancel={() => setAction(null)}
          onDone={() => {
            setAction(null);
            setNotice(`${account.username} has a temporary password. Pass it on to them.`);
          }}
        />
      )}
      {action === "totp" && (
        <ConfirmReview
          label={`Turn off two-factor sign-in for ${account.username}?`}
          message={
            <>
              Turn off two-factor sign-in for <strong>{account.username}</strong>? Do this when they&apos;ve lost their
              authenticator and their recovery codes. They sign in with their password alone until they turn it on again.
            </>
          }
          confirmLabel="Turn off two-factor"
          icon={<ShieldOff size={14} />}
          onConfirm={() => void removeTotp()}
          onCancel={() => setAction(null)}
          busy={busy}
          disabled={!adminPassword}
          error={error}
        >
          <PasswordField
            label="Your password"
            value={adminPassword}
            onChange={setAdminPassword}
            autoComplete="current-password"
            // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the Turn off two-factor button, so focus belongs here
            autoFocus
          />
        </ConfirmReview>
      )}
      {action === "admin" && (
        <ConfirmReview
          label={`Make ${account.username} the admin?`}
          message={
            <>
              Make <strong>{account.username}</strong> the admin? There is one admin at a time, so you stop being it:
              they will look after the server&apos;s settings, invites and accounts, and you keep your own profiles.
            </>
          }
          confirmLabel="Make admin"
          icon={<ShieldCheck size={14} />}
          tone="primary"
          onConfirm={() => void makeAdmin()}
          onCancel={() => setAction(null)}
          busy={busy}
          disabled={!adminPassword}
          error={error}
        >
          <PasswordField
            label="Your password"
            value={adminPassword}
            onChange={setAdminPassword}
            autoComplete="current-password"
            // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the Make admin button, so focus belongs here
            autoFocus
          />
        </ConfirmReview>
      )}
    </li>
  );
}

function InviteRow({ invite, onRevoked }: { invite: ListedInvite; onRevoked: () => Promise<void> }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const revoke = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.revokeInvite(invite.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not revoke the invite.");
      setBusy(false);
      return;
    }
    // As for an account: the review closes on success, whether or not the
    // list then reloads (the reload reports its own failure).
    setBusy(false);
    setConfirming(false);
    await onRevoked();
  };

  return (
    <li className="text-sm" style={{ color: "var(--text-dim)" }}>
      <div className="flex items-center justify-between gap-3">
        <span>
          Created {formatDate(invite.createdAt)} · {invite.state}
        </span>
        {invite.open && !confirming && (
          <button
            type="button"
            onClick={() => setConfirming(true)}
            aria-label="Revoke invite"
            className="rounded-lg p-1.5 text-danger hover:bg-[var(--surface-strong)]"
          >
            <Trash2 size={16} />
          </button>
        )}
      </div>
      {confirming && (
        <ConfirmReview
          label="Revoke this invite?"
          message="Revoke this invite? The link stops working, so whoever has it can't make an account with it."
          confirmLabel="Revoke invite"
          onConfirm={() => void revoke()}
          onCancel={() => setConfirming(false)}
          busy={busy}
          error={error}
          className="mt-2 space-y-3 rounded-xl border p-3"
        />
      )}
    </li>
  );
}

export function InvitesSettings() {
  const { account: me, refresh: refreshAccount } = useAccount();
  const [invites, setInvites] = useState<ListedInvite[] | null>(null);
  const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);
  const [newUrl, setNewUrl] = useState<string | null>(null);
  const [expiryDays, setExpiryDays] = useState<number>(DEFAULT_EXPIRY_DAYS);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [inviteList, accountList] = await Promise.all([api.getInvites(), api.getAccounts()]);
      const now = Date.now();
      setInvites(inviteList.invites.map((invite) => describeInvite(invite, now)));
      setAccounts(accountList.accounts);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load invites.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const createInvite = async () => {
    setCreating(true);
    setError(null);
    try {
      const { invite, url } = await api.createInvite(expiryDays);
      // The server builds the link from FETCH_WEB_PUBLIC. Without it, this
      // page's own address is the next best guess.
      setNewUrl(url ?? `${window.location.origin}/invite/${invite.code}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create an invite.");
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="space-y-5">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        Nobody can sign up without an invite. Each person gets their own account with their own profiles, lists and
        history.
      </p>

      <div className="space-y-1 text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        <p className="font-medium" style={{ color: "var(--text)" }}>What members can do</p>
        <p>
          Members have their own profiles, lists, history, TVs and webhook key. Only the admin changes the
          server&apos;s settings (API keys, the Trakt app, the Stremio addon, the AI provider), invites people and
          manages accounts.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <SelectField
          aria-label="Invite link works for"
          value={String(expiryDays)}
          onChange={(e) => setExpiryDays(Number(e.target.value))}
          className="rounded-xl px-4 py-2.5 text-sm font-medium"
        >
          {EXPIRY_CHOICES.map((days) => (
            <option key={days} value={days}>
              Works for {days} {days === 1 ? "day" : "days"}
            </option>
          ))}
        </SelectField>
        <button type="button" onClick={() => void createInvite()} disabled={creating} className="btn-primary">
          {creating ? <Loader2 size={16} className="animate-spin" /> : <UserPlus size={16} />} Create invite link
        </button>
      </div>
      {newUrl && <NewInvite url={newUrl} />}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}

      {accounts && (
        <div className="space-y-2">
          <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Accounts</p>
          <ul className="space-y-2">
            {accounts.map((account) => (
              <AccountRow
                key={account.id}
                account={account}
                isMe={account.id === me?.id}
                onChanged={() => void load()}
                // Handing admin on takes this section away from the caller;
                // asking who they are again is what hides it.
                onMadeAdmin={refreshAccount}
              />
            ))}
          </ul>
        </div>
      )}

      {invites && invites.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Invites</p>
          <ul className="space-y-1">
            {invites.map((invite) => (
              <InviteRow key={invite.id} invite={invite} onRevoked={load} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
