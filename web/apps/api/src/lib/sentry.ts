import { Sentry, initNodeSentry } from "@fetch/shared";

export const sentryEnabled = initNodeSentry("api");

export { Sentry };
