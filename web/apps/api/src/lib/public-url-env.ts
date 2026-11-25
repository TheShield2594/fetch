// Fills FETCH_API_PUBLIC, FETCH_WEB_PUBLIC, FETCH_ALLOWED_ORIGINS and
// TRAKT_REDIRECT_URI from FETCH_PUBLIC_URL when they aren't set (#644).
//
// Imported for its side effect, first, by every entry point: several modules
// read these variables once at import time, and ESM evaluates imports in order,
// so anything imported before this one would see them unfilled.
import { describeExpansion, expandPublicUrlEnv } from "@fetch/shared";

const expansion = expandPublicUrlEnv(process.env, [
  "FETCH_API_PUBLIC",
  "FETCH_WEB_PUBLIC",
  "FETCH_ALLOWED_ORIGINS",
  "TRAKT_REDIRECT_URI",
]);

const summary = describeExpansion(expansion, process.env);
if (summary) console.log(summary);
for (const warning of expansion.warnings) console.warn(`WARNING: ${warning}`);

export const publicUrlExpansion = expansion;
