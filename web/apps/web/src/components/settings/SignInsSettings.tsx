import { useCallback, useEffect, useRef, useState } from "react";
import { AlertCircle, Globe, Loader2, LogOut, Tv } from "lucide-react";
import { api, type SignIn } from "../../api";
import { useToast } from "../../hooks/useToast";
import { timeAgo } from "../../utils/timeAgo";
import { describeUserAgent } from "../../utils/userAgent";
import { ConfirmReview } from "../ConfirmReview";

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

const nameOf = (signIn: SignIn) => (signIn.kind === "tv" ? signIn.name : describeUserAgent(signIn.userAgent));

function describeSignIn(signIn: SignIn): string {
  if (signIn.kind === "tv") {
    const seen = signIn.lastUsedAt ? `Last seen ${timeAgo(signIn.lastUsedAt)}` : "Not seen since pairing";
    return [`TV${signIn.appVersion ? ` · Fetch ${signIn.appVersion}` : ""}`, seen, `paired ${formatDate(signIn.createdAt)}`].join(" · ");
  }
  return [
    signIn.current ? "Active now" : `Last active ${timeAgo(signIn.lastUsedAt)}`,
    `signed in ${formatDate(signIn.createdAt)}`,
    signIn.ipAddress ? `from ${signIn.ipAddress}` : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

function SignInRow({ signIn, onEnded }: { signIn: SignIn; onEnded: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const [ending, setEnding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const name = nameOf(signIn);

  const end = async () => {
    setEnding(true);
    setError(null);
    try {
      if (signIn.kind === "tv") await api.revokeDevice(signIn.id);
      else await api.revokeSession(signIn.id);
      onEnded();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not sign that out.");
      setEnding(false);
    }
  };

  return (
    <li className="rounded-xl border px-3 py-2.5" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
      <div className="flex items-center gap-3">
        {signIn.kind === "tv" ? (
          <Tv size={18} className="flex-none" style={{ color: "var(--text-mute)" }} />
        ) : (
          <Globe size={18} className="flex-none" style={{ color: "var(--text-mute)" }} />
        )}
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium" style={{ color: "var(--text)" }}>
            {name}
            {signIn.current && <span className="ml-2 text-xs text-accent-text">This browser</span>}
          </p>
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>{describeSignIn(signIn)}</p>
        </div>
        {!signIn.current && !confirming && (
          <button
            type="button"
            onClick={() => setConfirming(true)}
            aria-label={`Sign out ${name}`}
            className="btn-secondary btn-sm flex-none"
          >
            Sign out
          </button>
        )}
      </div>
      {confirming && (
        <ConfirmReview
          label={`Sign out ${name}?`}
          message={
            signIn.kind === "tv" ? (
              <>Sign out <strong>{name}</strong>? It will need pairing again to use this account.</>
            ) : (
              <>Sign out <strong>{name}</strong>? Whoever is using it will have to sign in again.</>
            )
          }
          confirmLabel="Sign out"
          icon={<LogOut size={14} />}
          onConfirm={() => void end()}
          onCancel={() => setConfirming(false)}
          busy={ending}
          error={error}
        />
      )}
    </li>
  );
}

/**
 * Settings → Account → "Where you're signed in": every browser session and
 * every paired TV, each of which can be signed out, and a way to sign out
 * everywhere but here.
 */
export function SignInsSettings({ epoch = 0, onChanged }: { epoch?: number; onChanged?: () => void }) {
  const [signIns, setSignIns] = useState<SignIn[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingAll, setConfirmingAll] = useState(false);
  const [includeTvs, setIncludeTvs] = useState(false);
  const [endingAll, setEndingAll] = useState(false);
  const [allError, setAllError] = useState<string | null>(null);
  const { showToast } = useToast();

  // Only the newest read is applied: a slower, older one coming back after a
  // later sign-out would otherwise put the signed-out row back on the list.
  const latestLoad = useRef(0);
  const load = useCallback(async () => {
    const attempt = ++latestLoad.current;
    try {
      const { sessions } = await api.getSignIns();
      if (attempt !== latestLoad.current) return;
      setSignIns(sessions);
      setError(null);
    } catch (err) {
      if (attempt !== latestLoad.current) return;
      setError(err instanceof Error ? err.message : "Could not load where you're signed in.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load, epoch]);

  const others = signIns?.filter((s) => !s.current) ?? [];
  const otherBrowsers = others.filter((s) => s.kind === "browser").length;
  const tvs = others.filter((s) => s.kind === "tv").length;

  // With no other browser signed in, the only thing the bulk action can end is
  // the TVs, so it says so and does that rather than offering a default that
  // signs nothing out.
  const tvsOnly = otherBrowsers === 0;
  const endAll = async () => {
    setEndingAll(true);
    setAllError(null);
    try {
      const result = await api.revokeOtherSessions(tvsOnly || includeTvs);
      const parts = [
        result.browsers > 0 && `${result.browsers} ${result.browsers === 1 ? "browser" : "browsers"}`,
        result.tvs > 0 && `${result.tvs} ${result.tvs === 1 ? "TV" : "TVs"}`,
      ].filter(Boolean);
      showToast(parts.length ? `Signed out ${parts.join(" and ")}` : "Nothing else was signed in");
      setConfirmingAll(false);
      setIncludeTvs(false);
      await load();
      onChanged?.();
    } catch (err) {
      setAllError(err instanceof Error ? err.message : "Could not sign out everywhere.");
    } finally {
      setEndingAll(false);
    }
  };

  if (error) {
    return (
      <div className="space-y-2">
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
        <button type="button" onClick={() => void load()} className="btn-secondary btn-sm">Try again</button>
      </div>
    );
  }
  if (!signIns) {
    return (
      <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading…
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Browsers stay signed in for 30 days after they were last used, and never more than 90 days. TVs stay signed in
        until you sign them out.
      </p>
      <ul className="space-y-2" aria-label="Where you're signed in">
        {signIns.map((signIn) => (
          <SignInRow
            key={`${signIn.kind}-${signIn.id}`}
            signIn={signIn}
            onEnded={() => {
              showToast(`Signed out ${nameOf(signIn)}`);
              void load();
              onChanged?.();
            }}
          />
        ))}
      </ul>
      {others.length > 0 &&
        (confirmingAll ? (
          <ConfirmReview
            label={tvsOnly ? "Sign out all TVs?" : "Sign out everywhere else?"}
            className="space-y-3"
            message={
              tvsOnly ? (
                <>
                  Sign out {tvs === 1 ? "the TV" : `all ${tvs} TVs`}? {tvs === 1 ? "It" : "They"} will need pairing again.
                  This browser stays signed in.
                </>
              ) : (
                <>Sign out every other browser ({otherBrowsers})? This browser stays signed in.</>
              )
            }
            confirmLabel={tvsOnly ? "Sign out all TVs" : "Sign out everywhere else"}
            icon={<LogOut size={14} />}
            onConfirm={() => void endAll()}
            onCancel={() => {
              setConfirmingAll(false);
              setIncludeTvs(false);
            }}
            busy={endingAll}
            error={allError}
          >
            {tvs > 0 && !tvsOnly && (
              <label className="flex items-start gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
                <input type="checkbox" checked={includeTvs} onChange={(e) => setIncludeTvs(e.target.checked)} className="mt-0.5" />
                <span>
                  Sign out {tvs === 1 ? "the TV" : `all ${tvs} TVs`} too. {tvs === 1 ? "It" : "They"} will need pairing again.
                </span>
              </label>
            )}
          </ConfirmReview>
        ) : (
          <button type="button" onClick={() => setConfirmingAll(true)} className="btn-secondary">
            <LogOut size={16} /> {tvsOnly ? "Sign out all TVs" : "Sign out everywhere else"}
          </button>
        ))}
    </div>
  );
}
