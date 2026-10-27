import { useCallback, useEffect, useState } from "react";
import { AlertCircle, Check, Copy, Loader2, Trash2, UserPlus } from "lucide-react";
import { type AccountSummary, api, type Invite } from "../../api";
import { useAccount } from "../../hooks/useAccount";
import { useTransientFlag } from "../../hooks/useTransientFlag";

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

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
          className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 font-mono text-xs"
          style={{ borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-0)" }}
        />
        <button type="button" onClick={copy} className="btn-secondary flex-none" aria-label="Copy invite link">
          {copied ? <Check size={16} /> : <Copy size={16} />}
        </button>
      </div>
      {copyError && <p role="alert" className="text-xs text-danger">Couldn't copy. Select the link and copy it by hand.</p>}
    </div>
  );
}

export function InvitesSettings() {
  const { account: me } = useAccount();
  const [invites, setInvites] = useState<ListedInvite[] | null>(null);
  const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);
  const [newUrl, setNewUrl] = useState<string | null>(null);
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
      const { invite, url } = await api.createInvite();
      // The server builds the link from CATALOGGY_WEB_PUBLIC. Without it, this
      // page's own address is the next best guess.
      setNewUrl(url ?? `${window.location.origin}/invite/${invite.code}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create an invite.");
    } finally {
      setCreating(false);
    }
  };

  const revoke = async (invite: Invite) => {
    try {
      await api.revokeInvite(invite.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not revoke the invite.");
    }
  };

  const remove = async (account: AccountSummary) => {
    if (
      !window.confirm(
        `Remove ${account.username}? This deletes their profiles and everything in them: lists, watch history, ratings and games.`
      )
    ) {
      return;
    }
    try {
      await api.deleteAccount(account.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove the account.");
    }
  };

  return (
    <div className="space-y-5">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        Nobody can sign up without an invite. Each person gets their own account with their own profiles, lists and
        history.
      </p>

      <button type="button" onClick={() => void createInvite()} disabled={creating} className="btn-primary">
        {creating ? <Loader2 size={16} className="animate-spin" /> : <UserPlus size={16} />} Create invite link
      </button>
      {newUrl && <NewInvite url={newUrl} />}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      )}

      {accounts && (
        <div className="space-y-2">
          <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Accounts</p>
          <ul className="space-y-1">
            {accounts.map((account) => (
              <li key={account.id} className="flex items-center justify-between gap-3 text-sm" style={{ color: "var(--text-dim)" }}>
                <span>
                  <strong style={{ color: "var(--text)" }}>{account.username}</strong>
                  {account.isAdmin && <span className="ml-2 text-xs text-claw-text">Admin</span>}
                  <span className="ml-2 text-xs">
                    {account.profileCount} {account.profileCount === 1 ? "profile" : "profiles"}
                  </span>
                </span>
                {!account.isAdmin && account.id !== me?.id && (
                  <button
                    type="button"
                    onClick={() => void remove(account)}
                    aria-label={`Remove ${account.username}`}
                    className="rounded-lg p-1.5 text-danger hover:bg-[var(--surface-strong)]"
                  >
                    <Trash2 size={16} />
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {invites && invites.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Invites</p>
          <ul className="space-y-1">
            {invites.map((invite) => {
              return (
                <li key={invite.id} className="flex items-center justify-between gap-3 text-sm" style={{ color: "var(--text-dim)" }}>
                  <span>
                    Created {formatDate(invite.createdAt)} · {invite.state}
                  </span>
                  {invite.open && (
                    <button
                      type="button"
                      onClick={() => void revoke(invite)}
                      aria-label="Revoke invite"
                      className="rounded-lg p-1.5 text-danger hover:bg-[var(--surface-strong)]"
                    >
                      <Trash2 size={16} />
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
