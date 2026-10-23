import { Sentry, initNodeSentry } from "@fetch/shared";

export const sentryEnabled = initNodeSentry("addon");

export { Sentry };
