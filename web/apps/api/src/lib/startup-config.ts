/**
 * The shortest `API_TOKEN` production accepts. It is the root of every key
 * the API derives — the at-rest encryption key for stored credentials, profile
 * unlock tokens, per-profile add-on URL keys, the addon's service token — as
 * well as an admin bearer on every route, so a short one weakens all of them,
 * and a database copy could be brute-forced offline against the encrypted
 * columns. 32 characters is what `openssl rand -hex 16` gives; the README
 * recommends `openssl rand -hex 32` (64).
 */
export const MIN_API_TOKEN_LENGTH = 32;

/**
 * Configuration that production refuses to start with, as messages for the
 * log. Empty when there is nothing to refuse.
 */
export const productionConfigIssues = (env: NodeJS.ProcessEnv): string[] => {
  const apiToken = env.API_TOKEN ?? "";
  return [
    !apiToken && "API_TOKEN is not set",
    apiToken === "dev-token" && "API_TOKEN is set to the development default \"dev-token\"",
    apiToken &&
      apiToken !== "dev-token" &&
      apiToken.length < MIN_API_TOKEN_LENGTH &&
      `API_TOKEN is shorter than ${MIN_API_TOKEN_LENGTH} characters; every encryption and signing key is derived from it, so generate one with \`openssl rand -hex 32\``,
    // A missing DATABASE_URL is not checked here: `lib/prisma.ts` refuses at
    // import time, which is long before this runs, and in every environment
    // rather than production alone.
    (env.DATABASE_URL ?? "").includes(":postgres@") && "DATABASE_URL uses the development default Postgres password",
  ].filter((message): message is string => Boolean(message));
};
