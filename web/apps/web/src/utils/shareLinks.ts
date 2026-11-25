import { runtimeConfig } from "../api";

/**
 * Links that work without signing in. The API hands back a path (it doesn't
 * know the address it is reached at); these make the address to give someone.
 */

/** The API's own address for one of its paths: absolute, even when the API is a path on this origin (`/api`). */
export const apiUrl = (path: string): string => {
  const base = new URL(runtimeConfig.getApiBase().replace(/\/+$/, "") + "/", window.location.origin);
  return new URL(path.replace(/^\/+/, ""), base).toString();
};

const SHARED_LIST_API_PATH = /^\/shared\/lists\/([0-9a-f-]{36})\/([A-Za-z0-9_-]{43})$/i;
const SHARED_LIST_PAGE_PATH = /^\/shared\/list\/([0-9a-f-]{36})\/([A-Za-z0-9_-]{43})\/?$/i;

/**
 * The website page for a shared list, from the API path `GET /lists` reports:
 * a friend opens the page, and the page reads the API path.
 */
export const sharedListPageUrl = (sharePath: string): string | null => {
  const match = SHARED_LIST_API_PATH.exec(sharePath);
  return match ? `${window.location.origin}/shared/list/${match[1]}/${match[2]}` : null;
};

/** The list and key a shared-list page address names, or null for any other page. */
export const sharedListFromPath = (pathname: string): { listId: string; key: string } | null => {
  const match = SHARED_LIST_PAGE_PATH.exec(pathname);
  return match ? { listId: match[1]!, key: match[2]! } : null;
};
