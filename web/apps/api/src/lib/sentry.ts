import { Sentry, initNodeSentry } from "@fetch/shared";

// Initialised on import, so importing this first (index.ts) sets Sentry up.
initNodeSentry("api");

export { Sentry };
