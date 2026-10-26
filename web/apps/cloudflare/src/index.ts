import { Container, getContainer } from "@cloudflare/containers";

interface Env {
  API: DurableObjectNamespace<ApiContainer>;
  ASSETS: Fetcher;
  PUBLIC_ORIGIN: string;
  // Secrets (wrangler secret put / the deploy workflow).
  API_TOKEN: string;
  DATABASE_URL: string;
  // Optional integration secrets: unset ones are simply not passed through,
  // which is what the API treats as "not configured".
  TMDB_API_KEY?: string;
  TRAKT_CLIENT_ID?: string;
  TRAKT_CLIENT_SECRET?: string;
  TWITCH_CLIENT_ID?: string;
  TWITCH_CLIENT_SECRET?: string;
  STEAM_API_KEY?: string;
  STEAM_ID?: string;
  WEBHOOK_SECRET?: string;
  SENTRY_DSN?: string;
}

const API_PORT = 7000;
const API_PREFIX = "/api";

const OPTIONAL_PASSTHROUGH = [
  "TMDB_API_KEY",
  "TRAKT_CLIENT_ID",
  "TRAKT_CLIENT_SECRET",
  "TWITCH_CLIENT_ID",
  "TWITCH_CLIENT_SECRET",
  "STEAM_API_KEY",
  "STEAM_ID",
  "WEBHOOK_SECRET",
  "SENTRY_DSN",
] as const;

/** The API's environment, as docker-compose.yml would give it on a self-hosted install. */
export const apiEnvVars = (env: Env): Record<string, string> => {
  const origin = env.PUBLIC_ORIGIN;
  const vars: Record<string, string> = {
    PORT: String(API_PORT),
    NODE_ENV: "production",
    DATABASE_URL: env.DATABASE_URL,
    API_TOKEN: env.API_TOKEN,
    // The site and the API share one origin, so this is the only origin a
    // browser will ever send.
    CATALOGGY_ALLOWED_ORIGINS: origin,
    CATALOGGY_API_PUBLIC: `${origin}${API_PREFIX}`,
    CATALOGGY_WEB_PUBLIC: origin,
    // The Worker strips nothing; the API removes the /api prefix itself.
    PROXY_PATH_PREFIXES: API_PREFIX,
    // Trusting the whole X-Forwarded-For chain is normally wrong (see
    // .env.example), but here nothing can reach the container except this
    // Worker, and forwardToApi() replaces the header rather than appending to
    // it. Without it every visitor shares the Worker's address, and so one
    // rate-limit budget.
    TRUST_PROXY: "true",
  };
  for (const name of OPTIONAL_PASSTHROUGH) {
    const value = env[name];
    if (value) vars[name] = value;
  }
  // Without a Trakt app the poll can never import anything, but each tick
  // still starts with a database query. Every five minutes is exactly often
  // enough to stop a scale-to-zero Postgres (Neon's free plan suspends after
  // five idle minutes) from ever suspending, which would use up its monthly
  // compute allowance.
  if (!env.TRAKT_CLIENT_ID) vars.TRAKT_POLL_INTERVAL_SEC = "0";
  return vars;
};

export class ApiContainer extends Container<Env> {
  defaultPort = API_PORT;
  // Never actually reached: onActivityExpired() below keeps the container
  // running. It only sets how often that hook is asked.
  sleepAfter = "1h";
  enableInternet = true;
  envVars = apiEnvVars(this.env);

  // The API runs Trakt polling, episode notifications and the Steam sync on
  // timers inside its own process, so letting it sleep when nobody has visited
  // for a while would silently stop them. Not calling stop() renews the timer.
  override async onActivityExpired(): Promise<void> {}
}

const isApiPath = (pathname: string) =>
  pathname === API_PREFIX || pathname.startsWith(`${API_PREFIX}/`);

const isAddonPath = (pathname: string) => pathname === "/addon" || pathname.startsWith("/addon/");

const api = (env: Env) => getContainer(env.API);

export const forwardToApi = (request: Request, env: Env): Promise<Response> => {
  const headers = new Headers(request.headers);
  // Set, not appended: a client-supplied X-Forwarded-For must not survive,
  // because the API is told to trust this header (TRUST_PROXY above).
  headers.set("X-Forwarded-For", request.headers.get("CF-Connecting-IP") ?? "");
  headers.set("X-Forwarded-Proto", "https");
  headers.set("X-Forwarded-Host", new URL(request.url).host);
  return api(env).fetch(new Request(request, { headers }));
};

export default {
  async fetch(request, env): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (isApiPath(pathname)) return forwardToApi(request, env);

    // The Stremio addon service is not hosted yet: its install URL carries no
    // secret (docs/cloudflare-migration-audit.md §10), so it stays off the
    // public internet until that is fixed.
    if (isAddonPath(pathname)) {
      return Response.json(
        { error: "The Stremio addon is not available on the hosted site yet." },
        { status: 503 },
      );
    }

    return env.ASSETS.fetch(request);
  },

  // Starts the API again whenever it is not running: after a deploy rolls the
  // container, after a crash, or on the very first deploy before anyone visits.
  async scheduled(_controller, env, ctx): Promise<void> {
    ctx.waitUntil(
      api(env)
        .fetch(new Request(`http://api.internal/health`))
        .then((response) => {
          if (!response.ok) console.error(`API health check returned ${response.status}`);
        }),
    );
  },
} satisfies ExportedHandler<Env>;
