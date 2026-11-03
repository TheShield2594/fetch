const htmlEscapeMap: Record<string, string | undefined> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

export const escapeHtml = (str: string): string =>
  // Only the five characters in the map can match, so the fallback is the
  // checker's question rather than a reachable branch — and escaping to the
  // character itself is the safe way to answer it.
  str.replace(/[&<>"']/g, (ch) => htmlEscapeMap[ch] ?? ch);

const CLOSING_LINE = "You can close this tab and return to Fetch.";

/**
 * The page Trakt's OAuth redirect lands on. It is often opened on a phone, so
 * it carries a viewport meta (without one the page rendered at ~980px and
 * shrank its text to a third), and `min-height` with padding rather than a
 * fixed `height` so a long error can scroll instead of being clipped.
 */
export const renderOAuthHtml = (detail: string, title = "Trakt connection failed"): string => {
  // The closing line is printed once: the success page used to pass it as the
  // detail as well and so said it twice.
  const closing = detail.trim() === CLOSING_LINE ? "" : `<p style="color:#94a3b8;margin-top:1rem">${CLOSING_LINE}</p>`;
  return `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escapeHtml(title)}</title></head><body style="margin:0;background:#0f172a;color:#e2e8f0;font-family:system-ui,sans-serif;font-size:16px;line-height:1.5">
  <main style="box-sizing:border-box;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:2rem 1.5rem;text-align:center;max-width:36rem;margin:0 auto"><h1 style="font-size:1.5rem;margin:0 0 0.5rem">${escapeHtml(title)}</h1><p style="margin:0">${escapeHtml(detail)}</p>${closing}</main>
</body></html>`;
};
