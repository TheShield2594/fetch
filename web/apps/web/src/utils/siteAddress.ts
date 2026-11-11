/**
 * Turns what someone types for their Fetch website into a URL to open.
 *
 * With no scheme, a host that can only be on the home network (a private IP,
 * `.local`, `.lan`, a single-label name) gets `http://`, since a server at home
 * rarely has a certificate; anything else gets `https://`. The TV's
 * `FetchServerAddress.normalize` follows the same rule.
 */
export function normalizeSiteAddress(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)
    ? trimmed
    : `${isLocalNetworkHost(hostOf(trimmed)) ? "http" : "https"}://${trimmed}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (!url.hostname) return null;
  return `${url.origin}${url.pathname}`.replace(/\/+$/, "");
}

function hostOf(address: string): string {
  const authority = address.split(/[/?#]/, 1)[0] ?? "";
  const hostAndPort = authority.slice(authority.lastIndexOf("@") + 1);
  if (hostAndPort.startsWith("[")) return hostAndPort.slice(1, hostAndPort.indexOf("]"));
  return hostAndPort.split(":", 1)[0] ?? "";
}

export function isLocalNetworkHost(host: string): boolean {
  const name = host.trim().replace(/\.$/, "").toLowerCase();
  if (!name) return false;
  if (name.includes(":")) {
    if (name === "::1") return true;
    const first = parseInt(name.split(":", 1)[0] || "0", 16);
    return (first & 0xffc0) === 0xfe80 || (first & 0xfe00) === 0xfc00;
  }
  const octets = name.split(".");
  if (octets.length === 4 && octets.every((o) => /^\d{1,3}$/.test(o) && Number(o) <= 255)) {
    const [a, b] = octets.map(Number) as [number, number];
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  return !name.includes(".") || name.endsWith(".local") || name.endsWith(".lan");
}
