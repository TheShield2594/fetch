import {
  formatPairingCode,
  generatePairingCode,
  generatePollSecret,
  hashSecret,
  normalizePairingCode,
  PAIRING_CODE_LENGTH,
} from "./device-token.js";
import { fetchWithPolicy } from "./http.js";

/**
 * The pairing hub: how a TV finds a Fetch website nobody told it the address of.
 *
 * Every household runs its own website, so a TV can't know where one is.
 * Instead it asks the hub (watchfetch.tv by default) for a code and shows it.
 * Someone signed in to their own website types the code there, or scans the
 * TV's QR code, which opens the hub's `/link` page and goes on to their
 * website. Their website then *claims* the code at the hub, saying where its
 * API is. The TV polls the hub, learns that address, and finishes pairing with
 * the website directly.
 *
 * The hub never sees a credential. When it starts, the TV makes up a handoff
 * secret and gives the hub only its hash. The claiming website stores that
 * hash as the poll secret of a pairing it has already confirmed, and the TV
 * collects its device token by polling the website's ordinary
 * `/devices/pair/poll` with the secret itself. All the hub can learn is that a
 * TV of some name was paired with a website at some address.
 *
 * Any install can serve as the hub (`FETCH_PAIRING_HUB_SERVE=true`); the store
 * is in memory because codes live for minutes and one process serves the API.
 * Every install claims codes through the hub at `FETCH_PAIRING_HUB_URL`.
 */

/** How long a hub code stays usable: long enough to find a phone and sign in. */
export const HUB_CODE_TTL_MS = 10 * 60 * 1000;
export const HUB_POLL_INTERVAL_SEC = 3;
/** Codes in flight across the whole hub; each entry is a few hundred bytes. */
const MAX_HUB_ENTRIES = 20_000;
/** Codes one address may hold. Higher than a website's own limit, since TVs behind one CGNAT address share it. */
const MAX_HUB_ENTRIES_PER_IP = 6;
const MAX_NAME_LENGTH = 80;
const MAX_ADDRESS_LENGTH = 500;
const SHA256_HEX = /^[0-9a-f]{64}$/;

export const DEFAULT_PAIRING_HUB_URL = "https://watchfetch.tv/api";

const stripTrailingSlash = (url: string): string => url.replace(/\/+$/, "");

/** True when this install serves the hub's routes. */
export const servesPairingHub = (): boolean => process.env.FETCH_PAIRING_HUB_SERVE?.trim().toLowerCase() === "true";

/**
 * The hub this install claims codes through: `FETCH_PAIRING_HUB_URL`, the
 * public hub when unset, and none when set to `off` (or empty). An install that
 * serves the hub itself claims straight from its own store.
 */
export const pairingHubUrl = (): string | null => {
  const raw = process.env.FETCH_PAIRING_HUB_URL;
  if (raw === undefined) return DEFAULT_PAIRING_HUB_URL;
  const trimmed = raw.trim();
  if (!trimmed || ["off", "false", "none", "0"].includes(trimmed.toLowerCase())) return null;
  return stripTrailingSlash(trimmed);
};

/** Where the hub's own `/link` page is, for the QR code a TV shows. */
const hubLinkBase = (): string | null => {
  // `||`, not `??`: compose passes an unset variable through as "".
  const raw =
    process.env.FETCH_PAIRING_HUB_LINK_BASE?.trim() ||
    process.env.FETCH_WEB_PUBLIC?.trim() ||
    process.env.WEB_PUBLIC_BASE?.trim();
  return raw ? stripTrailingSlash(raw) : null;
};

export const hubLinkUrl = (code: string): string | null => {
  const base = hubLinkBase();
  return base ? `${base}/link?code=${encodeURIComponent(code)}` : null;
};

/**
 * [raw] as an http(s) URL with no trailing slash, or null when it isn't one.
 * No credentials in it: the address is handed to a TV and kept there.
 */
export const parsePublicAddress = (raw: unknown): string | null => {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > MAX_ADDRESS_LENGTH) return null;
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  url.hash = "";
  url.search = "";
  return stripTrailingSlash(url.toString());
};

/** Loopback addresses only work on the machine itself, so they're no use to a TV. */
export const isLoopbackAddress = (address: string): boolean => {
  try {
    const host = new URL(address).hostname.replace(/^\[|\]$/g, "").toLowerCase();
    return host === "localhost" || host.endsWith(".localhost") || host === "::1" || /^127\./.test(host);
  } catch {
    return false;
  }
};

// ─── The hub's store ───

export type HubLink = { api: string; web: string | null };

type HubEntry = {
  code: string;
  pollSecretHash: string;
  handoffHash: string;
  name: string;
  ip: string;
  expiresAt: number;
  link: HubLink | null;
};

const entries = new Map<string, HubEntry>();
const byPollSecret = new Map<string, HubEntry>();

/** Drops expired codes; run on a timer by the hub's routes, so an idle hub keeps nothing past expiry. */
export const sweepPairingHub = (now: number = Date.now()): void => sweep(now);

const sweep = (now: number) => {
  for (const entry of entries.values()) {
    if (entry.expiresAt <= now) {
      entries.delete(entry.code);
      byPollSecret.delete(entry.pollSecretHash);
    }
  }
};

/** For tests. */
export const resetPairingHub = (): void => {
  entries.clear();
  byPollSecret.clear();
};

export type HubStartResult =
  | { ok: true; code: string; pollSecret: string; expiresAt: number }
  | { ok: false; reason: "invalid" | "busy_ip" | "busy" };

export const startHubPairing = (input: { name: unknown; handoffHash: unknown; ip: string }): HubStartResult => {
  const handoffHash = typeof input.handoffHash === "string" ? input.handoffHash.trim().toLowerCase() : "";
  if (!SHA256_HEX.test(handoffHash)) return { ok: false, reason: "invalid" };
  const now = Date.now();
  sweep(now);
  let fromIp = 0;
  for (const entry of entries.values()) if (entry.ip === input.ip && !entry.link) fromIp += 1;
  if (fromIp >= MAX_HUB_ENTRIES_PER_IP) return { ok: false, reason: "busy_ip" };
  if (entries.size >= MAX_HUB_ENTRIES) return { ok: false, reason: "busy" };

  let code = generatePairingCode();
  while (entries.has(code)) code = generatePairingCode();
  const pollSecret = generatePollSecret();
  const name =
    typeof input.name === "string" && input.name.trim() ? input.name.trim().slice(0, MAX_NAME_LENGTH) : "TV";
  const entry: HubEntry = {
    code,
    pollSecretHash: hashSecret(pollSecret),
    handoffHash,
    name,
    ip: input.ip,
    expiresAt: now + HUB_CODE_TTL_MS,
    link: null,
  };
  entries.set(code, entry);
  byPollSecret.set(entry.pollSecretHash, entry);
  return { ok: true, code: formatPairingCode(code), pollSecret, expiresAt: entry.expiresAt };
};

export type HubPollResult =
  | { status: "pending"; expiresAt: number }
  | { status: "linked"; link: HubLink }
  | { status: "expired" };

export const pollHubPairing = (pollSecret: string): HubPollResult => {
  const entry = byPollSecret.get(hashSecret(pollSecret));
  if (!entry || entry.expiresAt <= Date.now()) return { status: "expired" };
  if (!entry.link) return { status: "pending", expiresAt: entry.expiresAt };
  return { status: "linked", link: entry.link };
};

export type HubClaimResult =
  | { status: "claimed"; handoffHash: string; name: string }
  | { status: "not_found" }
  | { status: "taken" };

/**
 * A website taking the code on behalf of its signed-in user. Unknown and
 * expired answer alike, as the website's own confirm route does.
 */
export const claimHubPairing = (rawCode: string, link: HubLink): HubClaimResult => {
  const code = normalizePairingCode(rawCode);
  const entry = entries.get(code);
  if (code.length !== PAIRING_CODE_LENGTH || !entry || entry.expiresAt <= Date.now()) return { status: "not_found" };
  if (entry.link) {
    // The same website asking again (its first attempt failed to store the pairing) gets
    // the same answer, so a retry can finish; any other website is refused.
    if (entry.link.api === link.api) return { status: "claimed", handoffHash: entry.handoffHash, name: entry.name };
    return { status: "taken" };
  }
  entry.link = link;
  // Long enough for the TV's next poll, however close to the end it was claimed.
  entry.expiresAt = Math.max(entry.expiresAt, Date.now() + 60_000);
  return { status: "claimed", handoffHash: entry.handoffHash, name: entry.name };
};

// ─── Claiming, from any install ───

export class PairingHubUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PairingHubUnavailableError";
  }
}

/**
 * Claims [code] at this install's hub for the website at [link]. Null when no
 * hub is configured. Throws [PairingHubUnavailableError] when the hub can't be
 * reached or answers with something unexpected.
 */
export const claimThroughHub = async (code: string, link: HubLink): Promise<HubClaimResult | null> => {
  if (servesPairingHub()) return claimHubPairing(code, link);
  const hub = pairingHubUrl();
  if (!hub) return null;

  let response: Response;
  try {
    response = await fetchWithPolicy(
      `${hub}/hub/pair/claim`,
      {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ code, api: link.api, web: link.web }),
      },
      { timeoutMs: 8_000, retries: 0 }
    );
  } catch (error) {
    throw new PairingHubUnavailableError(error instanceof Error ? error.message : "No answer");
  }
  if (response.status === 404) return { status: "not_found" };
  if (response.status === 409) return { status: "taken" };
  if (!response.ok) throw new PairingHubUnavailableError(`The pairing hub answered ${response.status}`);
  const body = (await response.json().catch(() => null)) as { handoffHash?: unknown; name?: unknown } | null;
  const handoffHash = typeof body?.handoffHash === "string" ? body.handoffHash.toLowerCase() : "";
  if (!SHA256_HEX.test(handoffHash)) throw new PairingHubUnavailableError("The pairing hub's answer was not understood");
  return { status: "claimed", handoffHash, name: typeof body?.name === "string" ? body.name : "TV" };
};
