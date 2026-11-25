// Fills ADDON_PUBLIC_BASE and FETCH_WEB_PUBLIC from FETCH_PUBLIC_URL when they
// aren't set (#644). Imported first by index.ts for its side effect: config.ts
// reads both at import time.
import { describeExpansion, expandPublicUrlEnv } from "@fetch/shared";

const expansion = expandPublicUrlEnv(process.env, ["ADDON_PUBLIC_BASE", "FETCH_WEB_PUBLIC"]);

const summary = describeExpansion(expansion, process.env);
if (summary) console.log(summary);
for (const warning of expansion.warnings) console.warn(`WARNING: ${warning}`);

export const publicUrlExpansion = expansion;
