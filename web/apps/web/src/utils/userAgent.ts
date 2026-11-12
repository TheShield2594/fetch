/**
 * "Firefox on Windows" from a browser's User-Agent header, for the list of
 * places an account is signed in. A guess from well-known tokens, in the order
 * that tells them apart (Edge and Opera also say Chrome; Chrome also says
 * Safari), not a full parser: the point is that a person can recognise their
 * own devices, and the raw string is still there for anyone who wants it.
 */
export function describeUserAgent(userAgent: string | null | undefined): string {
  if (!userAgent) return "Unknown browser";
  const ua = userAgent;

  const browser = /Edg(?:e|A|iOS)?\//.test(ua)
    ? "Edge"
    : /OPR\/|Opera/.test(ua)
      ? "Opera"
      : /Firefox\/|FxiOS\//.test(ua)
        ? "Firefox"
        : /SamsungBrowser\//.test(ua)
          ? "Samsung Internet"
          : /Chrome\/|CriOS\//.test(ua)
            ? "Chrome"
            : /Safari\//.test(ua)
              ? "Safari"
              : null;

  const os = /iPhone/.test(ua)
    ? "iPhone"
    : /iPad/.test(ua)
      ? "iPad"
      : /Android/.test(ua)
        ? "Android"
        : /CrOS/.test(ua)
          ? "ChromeOS"
          : /Windows/.test(ua)
            ? "Windows"
            : /Mac OS X|Macintosh/.test(ua)
              ? "macOS"
              : /Linux/.test(ua)
                ? "Linux"
                : null;

  if (browser && os) return `${browser} on ${os}`;
  return browser ?? os ?? "Unknown browser";
}
