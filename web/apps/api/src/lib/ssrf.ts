// Guards for outbound fetches whose target URL is user-configurable.
//
// Two policies live behind these helpers, applied to three sinks:
//
//   * The AI provider endpoint may point at a LAN or localhost LLM server
//     (Ollama, LM Studio, a self-hosted vLLM, etc.), so we cannot simply
//     reject private IPs. Instead we block what is *never* a legitimate LLM
//     host but *is* a classic SSRF target: non-HTTP schemes, the
//     cloud-metadata / link-local range, and the unspecified address.
//   * Notification channels (ntfy, Gotify, Discord, generic webhooks) get the
//     same treatment for the same reason: reaching a self-hosted ntfy on the
//     LAN is the whole point of the feature, so a private-range block would
//     reject the intended configuration.
//   * The web-push endpoint only ever talks to public browser push services,
//     so it blocks every private range as well.
//
// The first two allow LAN and loopback targets only for the admin account (and
// API_TOKEN): reaching the home network is the admin's own setup to make.
// Another account gets the push policy's private-range block too, so a friend's
// AI endpoint or webhook can't make this server send requests into the network
// it runs on. Callers pass that choice as an `OutboundScope`.
//
// Combined with disabling redirect following at the fetch call sites, this
// stops a stolen API token from turning either feature into a
// metadata-service / internal-probe proxy while preserving local-model setups.
//
// A hostname is only half the story: `169.254.169.254.nip.io` and
// `localtest.me` are ordinary public names that resolve to blocked addresses,
// and an attacker who controls a domain can point an A record anywhere. So the
// async validators below also resolve the hostname and apply the same rules to
// every address the request could actually connect to.

import { type LookupAddress, lookup as dnsLookup } from "node:dns";
import { lookup } from "node:dns/promises";
import { BlockList, isIP, type LookupFunction } from "node:net";
import { Agent, fetch as undiciFetch } from "undici";

// Each IPv6 form that carries an IPv4 address in its low 32 bits, and the
// prefix that marks it: written that way, a blocked IPv4 address must not get
// past the IPv4 rules below. "::ffff:a9fe:a9fe" is the form Node normalizes
// "[::ffff:169.254.169.254]" into; NAT64 (64:ff9b::/96) is translated to the
// embedded address by the network, so it reaches it just the same.
const EMBEDDED_IPV4_PREFIXES: readonly (readonly number[])[] = [
  [0, 0, 0, 0, 0, 0xffff], // IPv4-mapped, ::ffff:0:0/96
  [0, 0, 0, 0, 0xffff, 0], // IPv4-translated (SIIT), ::ffff:0:0:0/96
  [0, 0, 0, 0, 0, 0], // IPv4-compatible, ::/96 (deprecated, but parsed)
  [0x64, 0xff9b, 0, 0, 0, 0], // NAT64 well-known prefix, 64:ff9b::/96
];

/** The eight 16-bit groups of an IPv6 literal, or null when it is not one. */
const ipv6Groups = (host: string): number[] | null => {
  if (isIP(host) !== 6) return null;
  let text = host;
  // A trailing dotted quad is the last two groups.
  const dotted = text.match(/^(.*:)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (dotted) {
    const [, head = "", a, b, c, d] = dotted;
    const octets = [a, b, c, d].map(Number) as [number, number, number, number];
    text = `${head}${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`;
  }
  const parse = (part: string | undefined): number[] =>
    part ? part.split(":").map((group) => Number.parseInt(group, 16)) : [];
  const [headText, tailText] = text.split("::");
  const head = parse(headText);
  if (tailText === undefined) return head;
  const tail = parse(tailText);
  return [...head, ...new Array<number>(8 - head.length - tail.length).fill(0), ...tail];
};

// Lowercases and strips brackets and any zone id; an IPv6 literal that embeds
// an IPv4 address (see above) becomes that address in dotted-quad form, so it
// is judged by the IPv4 rules.
const normalizeHostname = (hostname: string): string => {
  const host = hostname.toLowerCase().replace(/^\[/, "").replace(/\]$/, "").replace(/%.*$/, "");
  const groups = ipv6Groups(host);
  if (!groups) return host;
  const embedded = EMBEDDED_IPV4_PREFIXES.some((prefix) => prefix.every((group, index) => groups[index] === group));
  if (!embedded) return host;
  const [high = 0, low = 0] = groups.slice(6);
  return [high >> 8, high & 0xff, low >> 8, low & 0xff].join(".");
};

/** An address-range blocklist, from CIDR strings of either family. */
const rangeBlockList = (ranges: readonly string[]): BlockList => {
  const list = new BlockList();
  for (const range of ranges) {
    const [network = "", prefix] = range.split("/");
    const family = isIP(network) === 6 ? "ipv6" : "ipv4";
    list.addSubnet(network, Number(prefix), family);
  }
  return list;
};

const isInRanges = (list: BlockList, host: string): boolean => {
  const family = isIP(host);
  if (family === 4) return list.check(host, "ipv4");
  if (family === 6) return list.check(host, "ipv6");
  return false;
};

// Never a legitimate LLM or notification host, wherever it is configured from.
const AI_BLOCKED_RANGES = rangeBlockList([
  "0.0.0.0/32", // unspecified
  "169.254.0.0/16", // IPv4 link-local, incl. 169.254.169.254 (cloud metadata)
  "::/128", // unspecified
  "fe80::/10", // IPv6 link-local
  "fd00:ec2::254/128", // AWS IMDS over IPv6
]);

// Everything that is not a public unicast address.
const PUSH_BLOCKED_RANGES = rangeBlockList([
  "0.0.0.0/8", // "this network"; 0.x connects to the local host on Linux
  "10.0.0.0/8",
  "100.64.0.0/10", // carrier-grade NAT, Tailscale
  "127.0.0.0/8",
  "169.254.0.0/16",
  "172.16.0.0/12",
  "192.168.0.0/16",
  "198.18.0.0/15", // benchmarking, used internally by some networks
  "224.0.0.0/4", // multicast
  "240.0.0.0/4", // reserved, incl. 255.255.255.255 broadcast
  "::/128",
  "::1/128",
  "fc00::/7", // unique-local
  "fe80::/10",
  "ff00::/8", // multicast
]);

const isBlockedAiHostname = (hostname: string): boolean =>
  isInRanges(AI_BLOCKED_RANGES, normalizeHostname(hostname));

const isBlockedPushHostname = (hostname: string): boolean => {
  const host = normalizeHostname(hostname);
  return isBlockedAiHostname(host) || host === "localhost" || isInRanges(PUSH_BLOCKED_RANGES, host);
};

/** Whether an outbound target may be on a private network: see the top of this file. */
export type OutboundScope = { allowPrivateNetwork: boolean };

export const PRIVATE_NETWORK_ALLOWED: OutboundScope = { allowPrivateNetwork: true };
export const PUBLIC_ONLY: OutboundScope = { allowPrivateNetwork: false };

const blockerFor = (scope: OutboundScope) =>
  scope.allowPrivateNetwork ? isBlockedAiHostname : isBlockedPushHostname;

/** Every address a connection to `hostname` could land on. */
export type HostResolver = (hostname: string) => Promise<string[]>;

const dnsResolver: HostResolver = async (hostname) => {
  const records = await lookup(hostname, { all: true, verbatim: true });
  return records.map((record) => record.address);
};

// Applies `isBlocked` to the addresses the hostname resolves to. IP literals
// are returned as-is: the caller has already run them through `isBlocked`, and
// handing one to a DNS lookup buys nothing.
//
// A resolution failure is treated as "not allowed" rather than "allowed" — the
// request could not have succeeded anyway, so failing closed costs nothing and
// keeps a resolver that errors out from becoming a way past the check.
//
// This alone closes the static-DNS bypass, not DNS rebinding: fetch resolves
// the name again when it connects, so a resolver that answers differently on
// the second lookup can still slip through. `guardedFetch` below pins the
// connection to checked addresses; call sites that still use plain fetch rely
// on redirect:"error" to keep the cheaper redirect-based pivot closed.
const hostnameResolvesSafely = async (
  hostname: string,
  isBlocked: (host: string) => boolean,
  resolveHost: HostResolver
): Promise<boolean> => {
  const host = normalizeHostname(hostname);
  if (isIP(host)) return true;

  let addresses: string[];
  try {
    addresses = await resolveHost(host);
  } catch {
    return false;
  }

  return addresses.length > 0 && !addresses.some(isBlocked);
};

/**
 * Validates the *syntax* of a user-supplied AI-provider URL. Returns the parsed
 * URL when its scheme and literal hostname are acceptable, or null when it must
 * be rejected. Use this for save-time validation; `resolveAiProviderUrl` is the
 * check to run immediately before an outbound request.
 */
export const validateAiProviderUrl = (raw: string, scope: OutboundScope = PRIVATE_NETWORK_ALLOWED): URL | null => {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (blockerFor(scope)(url.hostname)) return null;
  return url;
};

/**
 * `validateAiProviderUrl` plus a DNS check on the hostname. Returns the parsed
 * URL only when neither the literal host nor any address it resolves to is a
 * blocked outbound target.
 */
export const resolveAiProviderUrl = async (
  raw: string,
  resolveHost: HostResolver = dnsResolver,
  scope: OutboundScope = PRIVATE_NETWORK_ALLOWED
): Promise<URL | null> => {
  const url = validateAiProviderUrl(raw, scope);
  if (!url) return null;
  return (await hostnameResolvesSafely(url.hostname, blockerFor(scope), resolveHost)) ? url : null;
};

/**
 * Validates the *syntax* of a user-supplied notification-channel URL (ntfy
 * topic, Gotify server, Discord webhook, generic webhook). Same policy as the
 * AI provider endpoint — a LAN or loopback host is the expected configuration
 * here, so only the never-legitimate targets are blocked.
 */
export const validateNotificationUrl = (raw: string, scope: OutboundScope = PRIVATE_NETWORK_ALLOWED): URL | null =>
  validateAiProviderUrl(raw, scope);

/**
 * `validateNotificationUrl` plus a DNS check. Run immediately before each send:
 * a channel is stored once and then POSTed to by a background job for as long
 * as it exists, so the records behind its hostname can change after it was
 * saved.
 */
export const resolveNotificationUrl = async (
  raw: string,
  resolveHost: HostResolver = dnsResolver,
  scope: OutboundScope = PRIVATE_NETWORK_ALLOWED
): Promise<URL | null> => resolveAiProviderUrl(raw, resolveHost, scope);

/**
 * Validates the syntax of a browser push-service endpoint: https only, and no
 * literal private/loopback/link-local host.
 */
export const validatePushEndpoint = (raw: string): URL | null => {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  if (isBlockedPushHostname(url.hostname)) return null;
  return url;
};

/**
 * `validatePushEndpoint` plus a DNS check, so a public name pointing at a
 * private address can't be stored as a subscription and replayed by the
 * notification job.
 */
export const resolvePushEndpoint = async (
  raw: string,
  resolveHost: HostResolver = dnsResolver
): Promise<URL | null> => {
  const url = validatePushEndpoint(raw);
  if (!url) return null;
  return (await hostnameResolvesSafely(url.hostname, isBlockedPushHostname, resolveHost)) ? url : null;
};

// ─── Connection pinning ───
//
// The resolve* checks above look a hostname up once, and fetch looks it up
// again when it connects, so a name whose records change between the two
// (DNS rebinding) could still land on a blocked address. `guardedFetch` closes
// that: it connects through an agent whose DNS lookup applies the scope's
// blocklist to the very addresses the socket is about to use, so there is no
// second, unchecked resolution. IP literals never reach a lookup, which is why
// the URL still goes through the resolve* check first.

export class BlockedAddressError extends Error {
  constructor(hostname: string) {
    super(`${hostname} resolves to an address this server may not connect to`);
  }
}

const pinnedLookup =
  (isBlocked: (host: string) => boolean): LookupFunction =>
  (hostname, options, callback) => {
    dnsLookup(hostname, { ...options, all: true, verbatim: true }, (error, addresses) => {
      if (error) return callback(error, "", 0);
      if (addresses.length === 0 || addresses.some((entry) => isBlocked(entry.address))) {
        return callback(Object.assign(new BlockedAddressError(hostname), { code: "EBLOCKED" }), "", 0);
      }
      if (options.all) return (callback as unknown as (e: null, a: LookupAddress[]) => void)(null, addresses);
      const [first] = addresses as [LookupAddress];
      callback(null, first.address, first.family);
    });
  };

/**
 * The most a guarded response body may hold. A server the user points us at
 * could otherwise stream without end into memory; undici aborts the read once
 * the body passes this, and the fetch rejects as if the server were unreachable.
 */
export const MAX_GUARDED_RESPONSE_BYTES = 32 * 1024 * 1024;

export class ResponseTooLargeError extends Error {
  constructor(limit: number) {
    super(`The response body is larger than ${limit} bytes`);
  }
}

/**
 * Reads a response body as text, counting the bytes as they arrive *after*
 * decoding. The agent's maxResponseSize counts the bytes on the wire, so a
 * small gzip or brotli body could still inflate past it here; this cancels the
 * read as soon as the decoded total passes `limit`.
 */
export const readBodyText = async (response: Response, limit = MAX_GUARDED_RESPONSE_BYTES): Promise<string> => {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let total = 0;
  let text = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel().catch(() => undefined);
      throw new ResponseTooLargeError(limit);
    }
    text += decoder.decode(value, { stream: true });
  }
  return text + decoder.decode();
};

const agents = new Map<boolean, Agent>();
const agentFor = (scope: OutboundScope): Agent => {
  let agent = agents.get(scope.allowPrivateNetwork);
  if (!agent) {
    agent = new Agent({
      connect: { lookup: pinnedLookup(blockerFor(scope)) },
      maxResponseSize: MAX_GUARDED_RESPONSE_BYTES,
    });
    agents.set(scope.allowPrivateNetwork, agent);
  }
  return agent;
};

export type GuardedRequest = { method?: string; headers?: Record<string, string>; body?: string; signal?: AbortSignal };

/**
 * Fetches a user-configured URL under `scope`: the URL is checked with
 * `resolveAiProviderUrl` (scheme, literal host, DNS), redirects are refused, and
 * the connection is pinned to addresses that pass the same check. Returns null
 * when the URL is refused before any request is made; a lookup refused at
 * connect time rejects with the fetch error, like any unreachable host.
 */
export const guardedFetch = async (
  raw: string,
  scope: OutboundScope,
  init: GuardedRequest = {}
): Promise<Response | null> => {
  const target = await resolveAiProviderUrl(raw, dnsResolver, scope);
  if (!target) return null;
  const response = await undiciFetch(target.href, {
    method: init.method ?? "GET",
    ...(init.headers !== undefined ? { headers: init.headers } : {}),
    ...(init.body !== undefined ? { body: init.body } : {}),
    ...(init.signal !== undefined ? { signal: init.signal } : {}),
    redirect: "error",
    dispatcher: agentFor(scope),
  });
  return response as unknown as Response;
};
