import { type FormEvent, useState } from "react";
import { Navigate, useSearchParams } from "react-router";
import { ArrowRight, Tv } from "lucide-react";
import { runtimeConfig } from "../api";
import { formatPairingCodeInput } from "../components/settings/DevicesSettings";
import { PAGE_TITLE } from "../components/typography";
import { normalizeSiteAddress } from "../utils/siteAddress";

/** The website this browser went on to last time, so the next TV is one tap. */
export const LINK_SITE_KEY = "fetch_link_site";

const readRemembered = (): string => {
  try {
    return window.localStorage.getItem(LINK_SITE_KEY) ?? "";
  } catch {
    return "";
  }
};

const remember = (site: string) => {
  try {
    window.localStorage.setItem(LINK_SITE_KEY, site);
  } catch {
    /* private mode: the next visit asks again */
  }
};

const hostLabel = (site: string): string => {
  try {
    return new URL(site).host;
  } catch {
    return site;
  }
};

/**
 * Where the QR code on a TV lands: `watchfetch.tv/link?code=ABCD-EFGH`.
 *
 * The TV got its code from the pairing hub because it doesn't know where your
 * Fetch website is. This page sends the phone on to that website's `/pair`
 * page with the code filled in; the website then tells the hub where it is,
 * and the TV finishes on its own. The address is asked for once and
 * remembered in this browser. Someone signed in here is on their own website
 * already, so they go straight to its `/pair`.
 */
export function LinkPage() {
  const [searchParams] = useSearchParams();
  const code = formatPairingCodeInput(searchParams.get("code") ?? "");
  const [remembered] = useState(readRemembered);
  const [editing, setEditing] = useState(() => !remembered);
  const [address, setAddress] = useState(() => (remembered ? hostLabel(remembered) : ""));
  const [error, setError] = useState<string | null>(null);

  if (runtimeConfig.hasSession()) {
    return <Navigate to={`/pair${code ? `?code=${encodeURIComponent(code)}` : ""}`} replace />;
  }

  const goTo = (site: string) => {
    remember(site);
    window.location.assign(`${site}/pair${code ? `?code=${encodeURIComponent(code)}` : ""}`);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const site = normalizeSiteAddress(address);
    if (!site) {
      setError("That doesn't look like a web address.");
      return;
    }
    setError(null);
    goTo(site);
  };

  return (
    <main className="px-6">
      <div className="mx-auto max-w-xl space-y-6 py-10">
        <div className="flex items-center gap-3">
          <Tv size={28} style={{ color: "var(--text-mute)" }} />
          <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Sign in a TV</h1>
        </div>

        {code && (
          <p className="text-sm" style={{ color: "var(--text-dim)" }}>
            Code on the TV:{" "}
            <strong className="font-mono text-base tracking-[0.12em]" style={{ color: "var(--text)" }}>{code}</strong>
          </p>
        )}

        {!editing && remembered ? (
          <div className="space-y-3">
            <button type="button" className="btn-primary" onClick={() => goTo(remembered)}>
              Continue to {hostLabel(remembered)} <ArrowRight size={16} />
            </button>
            <p className="text-sm">
              <button type="button" className="underline" style={{ color: "var(--text-dim)" }} onClick={() => setEditing(true)}>
                Use a different Fetch website
              </button>
            </p>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-3">
            <label className="block space-y-1.5">
              <span className="text-sm font-medium" style={{ color: "var(--text)" }}>Your Fetch website</span>
              <input
                type="text"
                inputMode="url"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
                placeholder="fetch.example.com or 192.168.1.25:7002"
                autoComplete="url"
                autoCapitalize="none"
                spellCheck={false}
                className="w-full rounded-lg border px-3 py-2 text-sm field-control"
                style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
              />
            </label>
            {error && <p role="alert" className="text-sm text-danger">{error}</p>}
            <button type="submit" className="btn-primary" disabled={!address.trim()}>
              Continue <ArrowRight size={16} />
            </button>
            <p className="text-xs" style={{ color: "var(--text-mute)" }}>
              The address you open your Fetch website at. This browser remembers it, so the next TV is one tap.
            </p>
          </form>
        )}

        <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
          Or open your Fetch website on any phone or computer, go to <strong>TV</strong> and
          type the code there. The TV finishes on its own.
        </p>
      </div>
    </main>
  );
}
