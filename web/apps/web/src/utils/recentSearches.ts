import { getCacheScope } from "./dataCache";

/**
 * The last few things searched for, so the Search page has something to offer
 * before anything is typed.
 *
 * Kept per profile — a child's searches are not the parent's — and in this
 * browser only. A convenience, so every read and write tolerates storage being
 * unavailable and the page renders the same without it.
 */

const LIMIT = 8;
const key = () => `fetch:recent-searches:${getCacheScope()}`;

export function loadRecentSearches(): string[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(key()) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((q): q is string => typeof q === "string").slice(0, LIMIT) : [];
  } catch {
    return [];
  }
}

/** Puts `query` first, dropping an earlier copy of it (case-insensitively), and returns the new list. */
export function rememberSearch(query: string): string[] {
  const trimmed = query.trim();
  if (!trimmed) return loadRecentSearches();
  const next = [trimmed, ...loadRecentSearches().filter((q) => q.toLowerCase() !== trimmed.toLowerCase())].slice(0, LIMIT);
  try {
    window.localStorage.setItem(key(), JSON.stringify(next));
  } catch {
    // Private mode or a full quota: the list just isn't remembered.
  }
  return next;
}

export function clearRecentSearches(): void {
  try {
    window.localStorage.removeItem(key());
  } catch {
    // As above.
  }
}
