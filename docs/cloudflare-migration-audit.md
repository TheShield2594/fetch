# Cloudflare migration: Phase 0 audit

Status: **not pursued.** The website stays self-hosted with Docker Compose,
behind a reverse proxy. This audit is kept for its findings: the existing bugs
in §10 apply whatever the hosting, and §5-7 are the starting point if moving to
Workers comes up again. Audited at commit `29be6e4`. Paths are relative to the
repo root.

Cloudflare limits quoted here (CPU time, subrequests, memory, body size, cron wall time)
come from memory of the published limits and change often. Check them against
<https://developers.cloudflare.com/workers/platform/limits/> before sizing anything. None
of the conclusions depends on the exact numbers.

## Contents

1. [Summary and decisions needed](#1-summary-and-decisions-needed)
2. [Inventory of the backend](#2-inventory-of-the-backend)
3. [What the TV app calls: the contract that must not break](#3-what-the-tv-app-calls-the-contract-that-must-not-break)
4. [The other contracts: PWA ↔ API and addon ↔ API](#4-the-other-contracts-pwa--api-and-addon--api)
5. [What won't run on Workers](#5-what-wont-run-on-workers)
6. [Database](#6-database)
7. [Background jobs](#7-background-jobs)
8. [Environment variables](#8-environment-variables)
9. [Web PWA hosting, ops and CI](#9-web-pwa-hosting-ops-and-ci)
10. [Existing bugs found during the audit](#10-existing-bugs-found-during-the-audit)
11. [Regression safety net](#11-regression-safety-net)
- [Appendix A: full API route table](#appendix-a-full-api-route-table)
- [Appendix B: TV app Supabase contract](#appendix-b-tv-app-supabase-contract)

---

## 1. Summary and decisions needed

### The short version

- **The backend is `web/`.** It has three deployables plus Postgres:
  - `apps/api`: Fastify 5 + Prisma 7 + Postgres 16, 148 routes.
  - `apps/addon`: a Fastify Stremio addon, 21 routes.
  - `apps/web`: a React/Vite PWA served by `serve@14`.
- **The TV app calls no `web/apps/api` endpoint today.** Its sync, auth, watch-party and
  avatar traffic goes to a Supabase-protocol server inherited from NuvioTV. That server is
  not in this repo, and it is blank in shipped builds, so release APKs are local-only.
- **The one `web/` service the TV can reach is the Stremio addon**, and only if a user
  installed it by URL. Moving to Cloudflare breaks nothing on the TV as long as the
  addon's public install URL and its Stremio paths keep working (§3).
- **The contracts that actually constrain the migration** are PWA → API (about 100
  endpoint/method pairs), addon → API (14 endpoints) and the Stremio protocol the addon
  serves (§4). Every derived secret is keyed off `API_TOKEN`, so the Worker must get the
  identical value (§5.4).
- **Biggest blockers:**
  1. Fastify itself.
  2. LAN-only integrations that Cloudflare can't reach.
  3. The module-level Postgres pool.
  4. Two jobs too long for one cron run: Trakt backfill and the first Steam→IGDB sync.
  5. About 30 pieces of in-process state, several of which are correctness-critical locks.
- **Native modules:** none at runtime. There is no bcrypt, argon2, sharp or pg-native.
  PIN hashing uses `node:crypto` scrypt.

### Decisions for review before Phase 1

| # | Decision | Recommendation | Why |
|---|---|---|---|
| D1 | **Is the TV's Supabase sync in scope?** | Not in this migration. Keep it as its own project, following the plan in `docs/ECOSYSTEM.md`, phases 3-7. | Nothing in this repo implements it. It is roughly 55 RPCs plus GoTrue auth, Storage, Realtime and an edge function (Appendix B). Moving `web/` doesn't touch it. If the goal is "the TV syncs to our Cloudflare backend", that is new API surface, not a port. |
| D2 | **Database: Hyperdrive + Postgres, or D1?** | **Hyperdrive + managed Postgres.** | It keeps the schema, all 38 migrations, raw SQL, partial and expression unique indexes, `FOR UPDATE` and interactive transactions unchanged. D1 would be a real port with correctness risk (§6.3). |
| D3 | **HTTP framework** | **Port to Hono.** | Fastify 5 needs Node `http` listen semantics. Cloudflare's `node:http` bridge is unproven for Fastify 5 plus its plugins. Hono's handler model maps closely to the existing route modules. |
| D4 | **LAN integrations** (Ollama/LAN LLM, ntfy, Gotify, Jellyseerr, generic webhooks on `192.168.x.x`) | Decide: Cloudflare Tunnel + Workers VPC, or require public endpoints and document it. | A Worker cannot reach RFC1918 addresses. The code deliberately allows them today (`web/apps/api/src/lib/ssrf.ts:53-62`). |
| D5 | **Who runs it?** Is it one Cloudflare deployment per household (today's self-host model), or a hosted multi-tenant service? | Assume one deployment per household. | Everything (the `API_TOKEN` model, singleton Trakt and Stremio connections, install-global settings) is single-tenant. Multi-tenant would be a redesign. |
| D6 | **Does Docker Compose self-hosting stay?** | Keep it working through the migration if possible. | Existing installs and the `CATALOGGY_*` compatibility promise in `web/README.md`. This affects whether the code keeps a Node entrypoint alongside the Worker one. |
| D7 | **Same origin for web, API and addon?** | Yes: one zone, with `/api/*` and `/addon/*` as routes. | It removes CORS (§10, bug 1), shrinks CSP `connect-src` to `'self'`, and the `/api` and `/addon` prefixes are already the defaults. |
| D8 | **Addon URL continuity** | Keep the addon's hostname and path shape, or plan a reinstall prompt. | TVs and Stremio clients store the full manifest URL, and the TV's addon sync propagates it to other devices (§3.2). |

---

## 2. Inventory of the backend

### 2.1 Layout

```text
web/
  apps/api/      Fastify 5 API, Prisma 7 (@prisma/adapter-pg + pg), web-push, lru-cache
  apps/addon/    Fastify 5 Stremio addon (catalog/meta/stream/subtitles), calls the API
  apps/web/      React + Vite PWA, vite-plugin-pwa injectManifest service worker
  packages/shared/   contracts, catalog registry, proxy prefix, service-token, Sentry init
  packages/migrate/  Prisma CLI only (the migrate image)
  docker-compose.yml db (postgres:16) → migrate → api → addon, web
```

| Service | Port | Image | Runtime |
|---|---|---|---|
| `db` | 5432 (loopback) | `postgres:16@sha256:…` | Postgres |
| `migrate` | – | `fetch-migrate` | `prisma migrate deploy`, then exits |
| `api` | 7000 | `fetch-api` | Node 26, Fastify |
| `addon` | 7001 | `fetch-addon` | Node 26, Fastify |
| `web` | 7002 | `fetch-web` | Node 26, `serve -s dist`, plus an entrypoint that writes `config.js` and the CSP |

### 2.2 Framework and request pipeline (API)

These are assembled in `web/apps/api/src/index.ts`:

- **Sentry:** `@sentry/node`, opt-in via `SENTRY_DSN`.
- **Fastify options:**
  - Log serializer that redacts URLs.
  - `bodyLimit` = `MAX_BODY_SIZE_MB` (default 32, range 1-1024).
  - `trustProxy` from `TRUST_PROXY`.
  - `rewriteUrl` strips `PROXY_PATH_PREFIXES` (default `/api`).
- **`@fastify/rate-limit`** (in-memory, per process):
  - 200/min per IP globally.
  - 1000/min for the addon's service token.
  - 10/min on PIN verify, Trakt OAuth and Stremio connect.
  - 240/min on `/addon/stremio/*`.
  - 60/min on webhooks.
- **ETag/Cache-Control** `onSend` hook (`lib/http-cache.ts`). The path table is in
  `packages/shared/src/api-cache-routes.ts`.
- **`@fastify/compress`** (br/gzip/deflate).
- **Global `onRequest` hook:** CORS (`lib/cors.ts`), security headers, and `OPTIONS` → 204.
- **`onResponse` metrics** (`lib/metrics.ts`). This also monkey-patches global `fetch`.
- **`preParsing` auth gate** (`lib/auth-gate.ts`).
- **29 route plugins** (`index.ts:249-277`). Each scopes its own `resolveProfile`
  `preHandler`.
- **Startup:**
  - Production guard (refuses a missing or `dev-token` `API_TOKEN`, or a `:postgres@`
    DB URL).
  - `ensureDefaultWatchlist`, `ensureDefaultCollection`, `encryptStoredSecrets`.
  - The `setInterval` schedulers (§7).
  - Then `listen`.

### 2.3 Auth model

1. **Shared bearer `API_TOKEN`.** `Authorization: Bearer`, compared via SHA-256 +
   `timingSafeEqual` (`lib/auth.ts`). The PWA keeps it in `localStorage`
   (`cataloggy_token`); the addon holds it as `CATALOGGY_API_TOKEN`.
   - Public paths are `/health`, `/health/ready`, `/addon/stremio/<64-hex>/…`,
     `/trakt/oauth/callback*` and `/webhooks/*`.
   - `/addon` is also exempted, but no route exists there.
2. **Profiles.** Selected with `x-profile-id` (UUIDv4).
   - If the header is missing, the only profile is auto-selected; with two or more, the
     API returns 400.
   - PIN-protected profiles also need `x-profile-token`. That is a stateless
     `v1.<expiryMs>.<base64url HMAC-SHA256(API_TOKEN, "v1:<id>:<exp>")>` with a 12 h TTL,
     issued by `POST /profiles/:id/verify` (`lib/profile-token.ts`).
3. **PIN hash.** `node:crypto` scrypt, N=16384, r=8, p=1, 32-byte key, 16-byte salt, stored
   as `scrypt$N$r$p$salt$key` (`lib/pin-hash.ts`).
   - Legacy unsalted SHA-256 hashes still verify and are re-hashed on success
     (`needsRehash`).
   - Lockout: 5 failures, then 5 min, doubling up to 1 h. Stored in a `KV` row under
     `SELECT … FOR UPDATE`.
4. **Derived secrets, all HMACs of `API_TOKEN`:**
   - The API's built-in addon secret URL (per profile).
   - The addon **service token** (`x-cataloggy-service-token`). It unlocks `/rpdb/config`
     and `/stremio/play-signal`.
   - The addon **scrobble mutation token**.
   - The addon's 12 h **mark-watched capabilities**.
5. **Secrets at rest.** AES-256-GCM, with the key = HKDF-SHA256(`API_TOKEN`, fixed
   salt/info). Format `cgy1.<iv>.<tag>.<ct>` (`lib/secret-box.ts`). This covers the
   TMDB/OMDb/RPDB keys, AI config, Jellyseerr, the VAPID keys, Trakt and Stremio tokens,
   and notification-channel tokens.
6. **Webhooks.** `WEBHOOK_SECRET`, sent as header `x-webhook-secret` or `?token=` (Plex),
   plus an optional `WEBHOOK_ALLOWED_IPS`. The profile comes from `?profile=`, then a
   media-server account name match, then the default.
7. **Trakt OAuth.** A single-use 32-byte `state` stored in `KV` for 10 min. Trakt and the
   Stremio account are **install-global** (singleton rows) and write into the oldest
   profile.

### 2.4 Database

- **Engine:** Postgres 16, accessed through Prisma 7 (`prisma-client-js` generator) with
  `@prisma/adapter-pg` and `pg` 8.23.
- **Schema:** 21 models, 7 native enums and 38 migrations
  (`web/apps/api/prisma/schema.prisma`).

| Model | Notes |
|---|---|
| `Profile` | id, name, pinHash. Every child model cascades on delete. |
| `List` / `ListItem` | kind `watchlist\|custom\|collection`. **SQL-only partial unique** indexes keep one watchlist and one collection per profile. |
| `WatchEvent` | `traktHistoryId BigInt @unique`. **Partial expression unique** dedup key on `(profileId, type, COALESCE(seriesImdbId, imdbId), COALESCE(season,-1), COALESCE(episode,-1), (watchedAt AT TIME ZONE 'UTC')::date) WHERE traktHistoryId IS NULL`. |
| `SeriesProgress`, `PlaybackProgress`, `ScrobbleSession`, `CheckIn`, `PlaySignal` | Watch state. `PlaySignal` is DB-backed delayed work (a `dueAt` index). |
| `Rating`, `Tag`, `ItemTag`, `Item` | |
| `Game` | `genres String[]`. Partial uniques on `igdbId` and `steamAppId`, plus a CHECK that at least one is set. |
| `Metadata` | `genres String[]`, cached TMDB/OMDb fields. |
| `KV` | A general key/value store: settings, encrypted secrets, job status, watermarks, PIN lockouts, addon config, Trakt OAuth state. |
| `TraktToken`, `StremioAuth` | Singletons (`id = "default"`). |
| `PushSubscription`, `NotificationChannel`, `NotifiedEpisode` | Notifications. |

Postgres-specific usage is listed in §6.

### 2.5 Background jobs

All of them run in-process with `setInterval` (`web/apps/api/src/lib/scheduler.ts`, wired
at `index.ts:326-434`). There is an in-memory `inFlight` guard, and outcomes are written to
`KV` as `job-status:<name>`.

| Job | Default interval |
|---|---|
| Trakt history poll + watchlist sync | `TRAKT_POLL_INTERVAL_SEC`, 300 s |
| Stremio library sync | `STREMIO_POLL_INTERVAL_SEC`, 0 = off |
| Play-signal settling | 60 s, only if `STREMIO_PLAY_DETECTION` |
| Scrobble cleanup | 1 h |
| Episode notifications (TMDB + web push + ntfy/Gotify/Discord/webhook) | `NOTIFICATION_CHECK_INTERVAL_SEC`, 3600 s |
| AI recommendations refresh | `AI_REFRESH_INTERVAL_SEC`, 86400 s, first run deferred 2 min |
| Steam library sync (+ IGDB matching) | `STEAM_SYNC_INTERVAL_SEC`, 86400 s, first run deferred 2 min |

Request-driven background work:

- TMDB/OMDb metadata backfill.
- Jellyseerr requests on watchlist add.
- Trakt push on watch.
- Post-import TMDB backfill.
- AI regeneration after a watch.

How each one maps to Workers is in §7.

### 2.6 Stremio addon endpoints

There are **two** Stremio addons:

| | Built into the API (`routes/stremio.ts`) | Addon service (`apps/addon`) |
|---|---|---|
| Manifest id | `com.cataloggy.api` | `com.cataloggy.addon` |
| Resources | catalog | catalog, meta, stream, subtitles |
| URL / credential | `/addon/stremio/<64-hex HMAC secret>/…`; the bare paths need the bearer | `/<route>` or `/p/<profile-uuid>/<route>`, **no credential** |
| Shown in the PWA | No. `GET /addon/config` computes it, but the UI never displays it. | Yes. `AddonSettings.tsx:18-21` hands out `${VITE_ADDON_BASE}/p/<id>/manifest.json`. |

Addon service routes (each also under `/p/:profileId`, except `/health`):

| Method | Path | Response |
|---|---|---|
| GET | `/health` | `{status, service:"addon", publicBase}` |
| GET | `/manifest.json` | Stremio manifest: `resources:["catalog","meta","subtitles","stream"]`, `idPrefixes:["tt"]` |
| GET | `/catalog/:type/:id.json`, `/catalog/:type/:id/:extra.json` | `{metas}`; always 200, `{metas:[]}` on error. `search=` / `genre=` extras. |
| GET | `/meta/:type/:id.json` | `{meta:{id,type,name,poster,background,description,releaseInfo,year,genres?,imdbRating?,totalSeasons?,totalEpisodes?}}` |
| GET | `/stream/:type/:id.json` | `{streams:[]}`. Optionally forwards a play signal. |
| GET | `/subtitles/:type/:id.json` | `{subtitles:[{id, url:"…/mark-watched/….srt?token=<cap>", lang}]}` |
| GET | `/mark-watched/:type/:imdbId.srt`, `/mark-watched/:type/:imdbId/:season/:episode.srt` | `text/srt`. Calls `POST /watch`. |
| POST | `/scrobble/:action` (start/pause/stop) | API passthrough. Needs the mutation token. |
| GET | `/configure` | 302 → `${CATALOGGY_WEB_PUBLIC}/settings` |

Addon caches are in-process maps with a TTL (manifest 60 s, list items 30 s, discovery
30 min, and so on), and the addon serves stale data when the API errors.

---

## 3. What the TV app calls: the contract that must not break

**Finding: the TV app (`app/`) makes no calls to `web/apps/api`.** This confirms
`docs/ECOSYSTEM.md`. A grep for every API path, the bearer header and `API_TOKEN` finds
only on-device phone-hub routes and the Supabase bearer. `app/build.gradle.kts` and
`local.example.properties` have no field that points at the web API.

The TV talks to four kinds of server. Only the second is part of `web/`.

### 3.1 The Supabase-protocol sync server: not in this repo, unaffected

- **What it is:** one configurable server.
  - The default comes from `BuildConfig.SUPABASE_URL` and `SUPABASE_ANON_KEY`, which are
    blank, so the backend is `https://sync.invalid`.
  - The `full` flavor can also discover a server via `<host>/.well-known/fetch`.
- **Where it's configured:** `app/src/main/java/com/fetch/tv/data/local/ServerConfigurationStore.kt:66-82`
  and `core/di/SupabaseModule.kt:57-130`.
- **Surface:**
  - GoTrue `/auth/v1/*`.
  - About 55 PostgREST RPCs (`/rest/v1/rpc/*`).
  - Two direct table reads (`linked_devices`, `addons`).
  - Two Storage buckets plus public avatars.
  - One Realtime channel (`watch-party:<id>`).
  - One edge function (`/functions/v1/tv-logins-exchange`).

  Full table in Appendix B.
- **Implementation:** none in this repo. There is no `supabase/` directory, no SQL
  functions and no edge functions; the only `.sql` files are the web API's Prisma
  migrations. It lives in the upstream NuvioTV Supabase project or whatever a builder
  self-hosts.
- **Release builds:** CI doesn't inject `FETCH_SUPABASE_*`, so shipped APKs never contact
  a sync server.

Moving `web/` to Cloudflare doesn't touch this. Pointing the TV at a Cloudflare backend is
the separate ECOSYSTEM.md plan (decision D1).

### 3.2 The website's Stremio addon: the only `web/` dependency

The TV only reaches the addon when a user installs it by URL. Nothing hard-codes it; the
default addons are Cinemeta and OpenSubtitles (`data/local/AddonPreferences.kt:242-243`).
The calls go through `data/remote/api/AddonApi.kt`.

| TV request | Addon response the TV relies on |
|---|---|
| `GET <base>/manifest.json` | Stremio manifest JSON (`id`, `version`, `name`, `resources`, `types`, `idPrefixes`, `catalogs[{type,id,name,extra?}]`, `behaviorHints?`) |
| `GET <base>/catalog/{type}/{id}.json`, `…/{id}/skip=N.json`, `…/{id}/{extra}.json` | `{metas:[{id,type,name,poster?,year?,description?,genres?,…}]}` |
| `GET <base>/meta/{type}/{id}.json` | `{meta:{…}}` |
| `GET <base>/stream/{type}/{id}.json` | `{streams:[]}` |
| `GET <base>/subtitles/{type}/{id}[/{extra}].json` | `{subtitles:[{id,url,lang}]}` |
| `GET <mark-watched .srt URL from the subtitles response>` | `text/srt`, which records the watch server-side |
| `<base>/configure` (opened on the phone via QR) | 302 to the web Settings page |

`<base>` is `https://host[/addon]` or `…/p/<profileId>`, and query strings on the base are
preserved. Responses are JSON or SRT with `Access-Control-Allow-Origin: *`.

**What must hold after the move:**

- The same public base URL: host, the `/addon` prefix if used, and the `/p/<uuid>` segment.
- The same paths and JSON shapes.
- Mark-watched tokens minted before the cut-over stay valid. That follows from keeping
  `API_TOKEN` identical, and they expire after 12 h anyway.

If the hostname must change, every TV and Stremio client has to reinstall the addon. The
TV's `AddonSyncService` stores full URLs, so the old one would also spread to other
devices.

### 3.3 On-device servers: unaffected

`PhoneHubServer` (port 8095+), `AddonConfigServer` (8080+) and `AddonConfigureServer`
(8100+) are NanoHTTPD servers running on the TV itself.

### 3.4 Configured but unimplemented: no change

- `PLAYBACK_REPORTS_BASE_URL` (`POST api/playback-reports`, `POST api/auth-diagnostics`)
  is blank and has no server anywhere in the repo.
- The `/.well-known/fetch` discovery document, and the `/link` and `/tv-login` web pages,
  are expected on the Supabase-compatible host. `web/` serves none of them.

### 3.5 Out of scope

These are third-party services the TV calls directly:

- TMDB, Trakt, Simkl, MDBList
- IntroDB, AniSkip, Anime-Skip
- tiffara
- YouTube, Google OAuth, SponsorBlock
- fast.com
- Plex, Jellyfin, Emby
- user-installed addons
- torrent trackers
- the GitHub releases API (update check)

---

## 4. The other contracts: PWA ↔ API and addon ↔ API

Since the TV doesn't call the API, these are what the port must keep byte-compatible.

### 4.1 PWA → API

About 100 endpoint/method pairs from `web/apps/web/src/api/*.ts`; the full list is in
Appendix A.

- **Headers:** every request sends `Authorization: Bearer`, `x-profile-id` and
  `x-profile-token` from `localStorage`.
- **Base URL:** chosen in this order: a `localStorage` override,
  `window.__CATALOGGY_API_BASE__` from `/config.js`, `VITE_API_BASE`, then localhost.
- **Behaviour the client relies on:**
  - **401 handling.** `code:"profile_verification_required"` locks the profile.
    `WWW-Authenticate: Bearer` clears the token.
  - **Error bodies** are always `{error, code?}`.
  - **Bare array.** `/search` returns a bare array.
  - **Cache headers.** ETag plus `Vary: Authorization, X-Profile-Id` on the cached routes.
    The service worker's read cache keys on the profile and a hash of `Authorization`.
  - **Offline queue.** The service worker queues `POST /watch`,
    `POST /series/:id/season/:n/episode/:e/watch` and `POST /series/:id/season/:n/watch-all`
    while offline. It answers them with a synthetic `202` and `X-Cataloggy-Queued: 1`, then
    replays them. The replayed requests must stay idempotent; the watch-event dedup unique
    index guarantees that.
  - **Preload.** `index.html:89-140` preloads `GET /lists`, `/series/progress` and
    `/games?sort=recent`.
- **Cross-origin by default.** The PWA runs on 7002 and the API on 7000, so CORS is on the
  critical path.
- **Runtime-typed responses.** `packages/shared/src/api-contracts.ts` parses only
  `/calendar`, `/series/progress` and `/watch/history`. Every other PWA response is
  untyped at runtime, so keep shapes exact.

### 4.2 Addon → API

These go over the Docker network today (`CATALOGGY_API_BASE=http://api:7000`). Each
request carries the bearer, the service token and `x-profile-id`.

| Endpoint | Parsed by |
|---|---|
| `GET /profiles` | `parseProfilesResponse` |
| `GET /lists`, `GET /lists/:id/items?type=` | `parseListsResponse`, `parseListItemsResponse` |
| `GET /genres` | `parseGenresResponse` |
| `GET /rpdb/config` (service token) | `parseRpdbConfigResponse` |
| `GET /addon/config` | `parseAddonConfigResponse` |
| `GET /settings/preferences` | `parsePreferencesResponse` |
| `GET /trending`, `/popular`, `/recommendations/personal`, `/recommendations/ai`, `/anime`, `/streaming` | `parseMetasResponse` |
| `GET /meta/:type/:imdbId`, `GET /series/progress/:imdbId` | raw / `parseSeriesProgressResponse` |
| `POST /watch`, `POST /scrobble/{start,pause,stop}`, `POST /stremio/play-signal` (service token) | – |

On Workers this hop becomes a **Service Binding** (`env.API.fetch`). A Stremio home screen
fans out to about 20 catalogs per load, so watch the subrequest and concurrent-connection
limits on catalog routes.

### 4.3 Inbound integrations

- **Webhooks:** Plex (multipart), Jellyfin, Emby → `/webhooks/*`.
- **Trakt OAuth redirect:** → `/trakt/oauth/callback`, which returns HTML.

These keep working on a public Worker URL, but their configured URLs in Plex, Jellyfin,
Emby and the Trakt app must be updated if the host changes.

---

## 5. What won't run on Workers

Severity: **B** = blocker (replace), **R** = rework, **C** = works under `nodejs_compat`.

### 5.1 Runtime and framework

| Finding | Where | Sev | Replacement |
|---|---|---|---|
| Fastify 5 server, hooks, plugins, `rewriteUrl`, `trustProxy`, ajv | `web/apps/api/src/index.ts`, `web/apps/addon/src/app.ts` | **B** | Hono (fetch-native). That means 148 API routes in 29 modules plus 21 addon routes; the handlers port mostly as-is. |
| `@fastify/rate-limit` (in-memory store) | api `index.ts:156-165`, addon `app.ts:45-50`, per-route overrides | **R** | Workers Rate Limiting binding keyed on `CF-Connecting-IP`, or a Durable Object. Per-isolate memory makes the current limits meaningless. |
| `@fastify/compress` | api `index.ts:180-186` | **R** | Drop it; Cloudflare compresses at the edge. Check that weak ETags survive. |
| `@sentry/node` (OpenTelemetry, require-in-the-middle) | `packages/shared/src/sentry.ts`, `setupFastifyErrorHandler` | **B** | `@sentry/cloudflare`. The web PWA stays on `@sentry/react`. |
| `process.on(SIGINT/SIGTERM)`, `process.exit` | api `index.ts:311,449-465`, addon `index.ts:21-39` | **R** | Delete. Make the production config guard a deploy check or a per-request 500. |
| `process.memoryUsage()`, `process.uptime()`, per-process counters, global `fetch` patch | `routes/metrics.ts:63,69`, `lib/metrics.ts` | **R** | Workers Analytics Engine or the dashboard. `/metrics` is meaningless per isolate. |
| Module-scope `process.env` reads | nearly every config module | **C** | Works with `nodejs_compat` and a recent compat date. Cleaner to read `env` bindings. |
| Startup tasks (`ensureDefault*`, `encryptStoredSecrets`) | `index.ts:319-324` | **R** | A deploy step or a lazy init guarded by a DB flag. |
| Plex `multipart/form-data` content-type parser | `routes/webhooks/plex.ts:28-34` | **R** | `request.formData()` in the fetch API. |

### 5.2 Database driver

| Finding | Where | Sev | Replacement |
|---|---|---|---|
| `pg` raw TCP via `@prisma/adapter-pg` | `lib/prisma.ts` | **C** with rework | `pg` 8.23 includes `pg-cloudflare` (already in the lockfile) and uses `cloudflare:sockets`. Point it at **Hyperdrive**. |
| Module-level `PrismaClient` + pool, imported by 52 modules | `lib/prisma.ts:17-32` | **R** | A client per request or per cron invocation (Workers forbid sharing sockets across requests). Expose it via `AsyncLocalStorage` so importers don't change, and `$disconnect` in `ctx.waitUntil`. |
| `prisma-client-js` generator | `schema.prisma:1-3` | **R** | `provider = "prisma-client"` with a Workers/`cloudflare` runtime and an explicit output. Prisma 7 needs no engine binary at runtime. |
| Session settings as connection options (`statement_timeout`, `idle_in_transaction_session_timeout`, `application_name`) | `lib/prisma.ts:24-29`, `lib/db-config.ts` | **R** | Hyperdrive pools connections and may not forward startup parameters. Use `ALTER ROLE … SET statement_timeout`. |
| `prisma migrate deploy` in the migrate container | `packages/migrate`, compose | **R** | A CI step against the **direct** DB URL, never through Hyperdrive. |

### 5.3 Native modules

**None at runtime.** `pnpm-lock.yaml` has no `bcrypt`, `argon2`, `sharp`, `better-sqlite3`,
`pg-native` or node-gyp build. The native packages it does have are build-time only
(`esbuild`, `@rollup/rollup-*`, `lightningcss`, `@tailwindcss/oxide`, `fsevents`). Prisma
engines are needed only by the CLI in CI.

### 5.4 Crypto: must stay byte-compatible

Every token, secret URL and stored ciphertext is derived from `API_TOKEN`. The Worker must
get the identical `API_TOKEN` and reproduce these formats exactly, or the following break:

- every logged-in browser
- every PIN token
- every installed addon URL
- every encrypted integration key

| Primitive | Where | Sev | Notes |
|---|---|---|---|
| AES-256-GCM + `hkdfSync` + `randomBytes` | `lib/secret-box.ts` | **C** | A WebCrypto port is format-compatible: split the 16-byte tag off the end of `ct‖tag`. The callers become async. |
| scrypt N=16384 (about 16 MB, tens of ms of CPU per verify) | `lib/pin-hash.ts` | **C**, CPU-heavy | Too slow for the Free plan's CPU budget; fine on Paid. `needsRehash` already allows a transparent upgrade to PBKDF2-SHA256 (WebCrypto, which Workers caps at 100k iterations) or `@noble/hashes/scrypt`. |
| HMAC-SHA256 + `timingSafeEqual` | `lib/profile-token.ts`, `lib/stremio-secret.ts`, `packages/shared/src/service-client.ts`, addon `lib/mutation-auth.ts`, addon `lib/mark-watched.ts`, `lib/auth.ts`, `lib/webhook-auth.ts` | **C** | Or WebCrypto HMAC plus `crypto.subtle.timingSafeEqual`. |
| `createHash("sha1")` for ETags | `lib/http-cache.ts:46` | **C** | |
| `Buffer` (base64url, cursors, RFC 2047) | about 10 sites | **C** | |

### 5.5 Network

| Finding | Where | Sev | Notes |
|---|---|---|---|
| **LAN targets unreachable** | `lib/ssrf.ts:53-76`. Private addresses are deliberately allowed for AI providers, ntfy/Gotify/Discord/webhook channels and Jellyseerr. | **B** for users with LAN integrations | A Worker cannot reach RFC1918 or loopback. Use Cloudflare Tunnel + Workers VPC, or require public URLs (decision D4). |
| `node:dns/promises` `lookup`, `node:net` `isIP` | `lib/ssrf.ts:27-28,81-115` | **C** | `nodejs_compat` provides them (verify `all`/`verbatim`), or use DNS-over-HTTPS. |
| `request.ip` for rate limits and `WEBHOOK_ALLOWED_IPS` | `index.ts:164`, `lib/webhook-auth.ts` | **R** | Use `CF-Connecting-IP`; `TRUST_PROXY` goes away. |
| web-push 3.6.7 (Node `https`, `crypto.createECDH`, `jws`/`asn1.js`) | `lib/push.ts` | **R**, treat as incompatible | A WebCrypto RFC 8291 + RFC 8292 (VAPID) implementation over `fetch`. The stored VAPID keys import as JWK, so **browsers do not need to re-subscribe**. |

### 5.6 Long-running and fire-and-forget work

- **The `setInterval` scheduler** (`lib/scheduler.ts:132,138`) is **B**. It becomes a Cron
  Trigger `scheduled()` handler (§7).
- **15 un-awaited `void` promises** are **R**. Workers cancel them when the response
  returns. Wrap them in `ctx.waitUntil`, or move them to a Queue:
  - `lib/metadata.ts:131`
  - `lib/ai.ts:326`
  - `lib/trakt-client.ts:111`
  - `lib/watch-event.ts:107,156`
  - `routes/lists.ts:220,235,278,324`
  - `routes/series.ts:123`
  - `routes/scrobble.ts:292,334,410`
  - `routes/trakt.ts:434-456`
  - addon `lib/play-signal.ts:55`

  The post-Trakt-import TMDB backfill (`routes/trakt.ts:434`, possibly thousands of calls)
  and AI regeneration need a **Queue**.

### 5.7 In-process state that assumes one long-lived process

**Correctness issues (R):**

| State | Where | Effect on Workers | Fix |
|---|---|---|---|
| Trakt client token singleton + refresh | `lib/trakt-client.ts:18-29`, `trakt.ts:545-582` | Two isolates refresh at once. Trakt rotates refresh tokens, so the loser gets `invalid_grant` and the connection breaks. | A Durable Object, or a conditional DB update on `TraktToken` |
| Stremio `withSyncLock` | `lib/stremio-library.ts:150-157` | Overlapping passes record duplicate watches | DB lease or Durable Object |
| Trakt `importInProgress` flag | `routes/trakt.ts:40-58` | The 409 guard stops working | DB lease or Durable Object |
| Scheduler `inFlight` | `lib/scheduler.ts:86` | Cron runs overlap | DB lease |
| AI recommendations kept only in `trendingCache` | `lib/cache.ts:26`, `lib/ai.ts:398`, `index.ts:395` | The cron isolate warms memory that request isolates never see, so the job does nothing | Persist to a table or Workers KV |
| `kvCache` read-through (30 s TTL, invalidates only locally) | `lib/kv.ts`, `lib/cache.ts:147-155` | Settings and secrets up to 30 s stale per isolate; Hyperdrive query caching compounds it | Accept it, shorten the TTL, or bypass for sensitive keys |

**Performance only (C):** these caches will mostly miss on cold isolates. Move the hot
ones to the Cache API or Workers KV:

- About 20 LRU/Map caches: TMDB cast, seasons and episodes, providers, external ids, show
  details, search, profiles, AniList, the IGDB search and **Twitch token** (put this one
  in KV), coalescing maps, host gates and the addon's caches.

### 5.8 Request and response size

- **Request size.** `MAX_BODY_SIZE_MB` allows up to 1024 MB, but Workers take 100 MB
  request bodies on Free/Pro and have a 128 MB isolate memory limit.
- **Fully buffered endpoints.** `POST /import`, `/import/csv`, `/import/external` and
  `GET /export` are all fully buffered. A typical household export (tens of MB) fits.
- **Larger files.** Upload to R2 and process with a Queue consumer; stream the export or
  write it to R2.

### 5.9 Works as-is

- `lib/http.ts` `fetchWithPolicy` (web `fetch`, `AbortSignal.any/timeout`).
- `lib/concurrency.ts`.
- `lib/html.ts`.
- Play-signal "timers", which are already DB rows with a delete-as-claim.
- Job-status recording.
- Filesystem: none in runtime code. `fs` appears only in build, ops scripts and the web
  container entrypoint.

---

## 6. Database

### 6.1 Postgres-specific features in use

- **Raw SQL:**
  - `routes/watch.ts:325-397` (`/watch/stats/detailed`) uses gaps-and-islands streaks,
    `AT TIME ZONE`, `date_trunc`, `FILTER`, `json_agg`, `unnest(text[])` and `::uuid`.
  - `routes/profiles.ts:49` uses `SELECT … FOR UPDATE` inside an interactive transaction.
  - `lib/db-health.ts:37` runs `SELECT 1`.
- **Interactive `$transaction`, 7 sites:** `lib/watch-event.ts:39,125` (dedup with P2002
  retry), `routes/lists.ts:206`, `routes/series.ts:342`, `routes/watch.ts:166`,
  `routes/scrobble.ts:266`, `routes/profiles.ts:44`.
- **Batch `$transaction`:** `lib/trakt-client.ts:626`, `lib/playback.ts:216`.
- **`createMany({ skipDuplicates: true })`:** in `lib/trakt-import.ts`,
  `lib/batch-watch-events.ts`, `lib/batch-ratings.ts` and `routes/export.ts`.
- **`mode: "insensitive"`:** `lib/webhook-profile.ts:52`.
- **Native enums (7)** and `text[]` columns (2).
- **Partial and expression unique indexes**, plus a CHECK constraint, defined only in
  migration SQL.
- **Migration SQL** that uses `jsonb` functions, `IS JSON OBJECT` (PG 16), `ON CONFLICT`,
  window functions and `gen_random_uuid()`.
- **Not used:** JSON columns and advisory locks.

### 6.2 Hyperdrive + Postgres (recommended)

The schema, raw SQL, indexes, enums, arrays, `FOR UPDATE`, transactions and
`skipDuplicates` all work unchanged. Rework:

1. A client per request (§5.2).
2. **Hyperdrive query caching is on by default.** It would let `SELECT 1` health checks
   and `KV` reads (watermarks, job status, Trakt tokens, PIN lockout) come back stale.
   Disable caching, or use a second, uncached binding for those paths.
3. Session settings move to `ALTER ROLE`.
4. Migrations run in CI against the direct URL.
5. **Where Postgres lives:** a managed provider (Neon, Supabase, RDS, …), or the
   household's own database reached through Cloudflare Tunnel.

### 6.3 D1 / SQLite (not recommended): porting cost

- `String[]` columns become JSON text. That breaks `unnest` and every array write.
- The stats SQL has to be rewritten (`strftime`, `json_group_array`, `json_each`).
- `FOR UPDATE` is a syntax error. The PIN lockout needs `UPDATE … RETURNING` or a Durable
  Object.
- **No interactive transactions** through Prisma. Watch-event dedup, the PIN lockout and
  watermark atomicity would rely on unique indexes alone.
- `skipDuplicates` and `mode: "insensitive"` aren't supported.
- All `@db.*` annotations are removed, and timestamps lose their time zone.
- The 38-migration history isn't reusable. It needs a new baseline plus a one-off data
  conversion.
- The **100 bound parameters per statement** limit breaks large `in` lists (for example,
  the Trakt import) and multi-row inserts unless they're chunked.

---

## 7. Background jobs

Mapping: one `* * * * *` Cron Trigger dispatches each job whose interval has elapsed,
using a last-run marker in the DB. A DB lease replaces the in-memory `inFlight` guard.

| Job | Per-run work | Fits one cron invocation? | Workers design |
|---|---|---|---|
| Play-signal settling (60 s) | DB only, delete-as-claim | Yes | Cron, as-is |
| Scrobble cleanup (1 h) | One `updateMany` | Yes | Cron |
| Trakt incremental poll + watchlist sync (300 s) | A few Trakt GETs, 2-3 DB round trips per entry | Yes | Cron, plus a **serialised token refresh** (§5.7) |
| **Trakt backfill** (first connect or `POST /trakt/poll {full:true}`) | Up to `TRAKT_BACKFILL_MAX_PAGES` = 500 pages **per endpoint**, about 2,000 Trakt GETs and tens of thousands of DB round trips, all buffered in memory before writing (`trakt.ts:423-478`) | **No** | **Workflow** (one step per page, resumable cursor) or a Queue |
| Stremio library sync (off by default) | One `datastoreMeta` + chunked `datastoreGet` + a watch per item | Yes | Cron, plus a DB lease to replace `withSyncLock` |
| Episode notifications (1 h) | One TMDB call per tracked series, then web push + channels per new episode | Yes for a household | Cron. Move `showDetailsCache` to KV. LAN ntfy/Gotify per D4. |
| AI recommendations refresh (24 h) | 2 LLM calls (60 s timeout) + about 15 TMDB searches each | Fits, but its output is in-memory only | Cron or Queue, **persisting** the results |
| **Steam sync, first or large** (24 h) | One Steam call, then **per game** DB queries + 2 IGDB searches through a 4 req/s limiter. 1,000 games ≈ 2,000 IGDB calls, ≥ 500 s. | **No** | **Queue** (N games per message) with the IGDB rate limit in a Durable Object, or a Workflow. Incremental runs fit. |
| IGDB/Twitch token | Cached in module memory | n/a | Workers KV with a TTL |
| Request-driven backfills (metadata ≤ 25 calls, Jellyseerr, Trakt push) | Small | n/a | `ctx.waitUntil` |
| Post-Trakt-import TMDB backfill | Possibly thousands of calls | n/a | **Queue** |

---

## 8. Environment variables

Secrets are marked **S** (on Workers they become `wrangler secret put`); everything else
becomes `vars`. Values stored in the DB and encrypted under `API_TOKEN` are not listed:
the TMDB/OMDb/RPDB keys, AI config, Jellyseerr, VAPID keys, Trakt and Stremio tokens, and
channel tokens.

### API (`web/apps/api`)

| Var | Default | Purpose | Workers fate |
|---|---|---|---|
| `DATABASE_URL` **S** | required | PG connection | Hyperdrive binding. The direct URL is kept as a CI secret for migrations. |
| `DATABASE_POOL_MAX` | 10 | pg pool size | Drop (Hyperdrive pools) |
| `DATABASE_STATEMENT_TIMEOUT_MS` | 30000 | `statement_timeout` | Move to `ALTER ROLE` |
| `DATABASE_SLOW_QUERY_MS` | 1000 | slow-query log | Keep |
| `API_TOKEN` **S** | required | Bearer **and** root key for every derived secret | Secret. **Must be the same value.** |
| `PORT` | 7000 | listen port | Drop |
| `NODE_ENV` | production | guards, CORS dev wildcard, Sentry env | Var |
| `CATALOGGY_ALLOWED_ORIGINS` | LAN defaults | CORS allowlist | Var, or drop if same-origin (D7) |
| `CATALOGGY_API_PUBLIC` | `http://localhost:7000` | Trakt redirect default, addon URL | Var |
| `CATALOGGY_WEB_PUBLIC` / `WEB_PUBLIC_BASE` | unset | Configure link, notification links | Var |
| `TRUST_PROXY` | unset | client IP | Drop (`CF-Connecting-IP`) |
| `PROXY_PATH_PREFIXES` (compose: `API_PROXY_PATH_PREFIXES`) | `/api` | prefix stripping | Keep, or handle in routing |
| `MAX_BODY_SIZE_MB` | 32 | body limit | Cap below 100 |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` **S**, `TRAKT_REDIRECT_URI` | unset | Trakt OAuth | Var / secret |
| `TRAKT_POLL_INTERVAL_SEC` (300), `TRAKT_POLL_MAX_PAGES` (20), `TRAKT_BACKFILL_MAX_PAGES` (500), `TRAKT_WATCHLIST_MIRROR_DELETES` (false) | | Trakt sync | Vars |
| `STREMIO_POLL_INTERVAL_SEC` (0), `STREMIO_API_BASE` (`https://api.strem.io`), `STREMIO_PLAY_DETECTION` (false) | | Stremio sync and play detection | Vars. Play detection must match the addon. |
| `TMDB_API_KEY` **S** | unset; a DB value wins | TMDB | Secret |
| `AI_REFRESH_INTERVAL_SEC` (86400), `NOTIFICATION_CHECK_INTERVAL_SEC` (3600), `STEAM_SYNC_INTERVAL_SEC` (86400) | | job cadence | Vars read by the cron dispatcher |
| `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET` **S** | unset | IGDB | Var / secret |
| `STEAM_API_KEY` **S**, `STEAM_ID` | unset | Steam | Secret / var |
| `VAPID_SUBJECT` | `mailto:admin@cataloggy.local` | VAPID `sub` | Var |
| `WEBHOOK_SECRET` **S**, `WEBHOOK_ALLOWED_IPS` | unset | webhook auth | Secret / var |
| `SENTRY_DSN` | unset | errors | Var |

### Addon (`web/apps/addon/src/lib/config.ts`)

| Var | Default | Workers fate |
|---|---|---|
| `CATALOGGY_API_BASE` | `http://api:7000` | Service Binding |
| `CATALOGGY_API_TOKEN` **S** | = `API_TOKEN` | Secret (same value) |
| `ADDON_PUBLIC_BASE` | `http://localhost:7001` | Var. **Keep the published value** (§3.2). |
| `CATALOGGY_WEB_PUBLIC` / `WEB_PUBLIC_BASE` | unset | Var |
| `PROXY_PATH_PREFIXES` (compose: `ADDON_PROXY_PATH_PREFIXES`) | `/addon` | Keep or route |
| `TRUST_PROXY` | unset | Drop |
| `STREMIO_PLAY_DETECTION` | false | Var |
| `LOG_LEVEL` | info | Var |
| `PORT` | 7001 | Drop |
| `SENTRY_DSN`, `NODE_ENV` | – | Vars |

### Web (`web/apps/web`)

| Var | Read when | Workers fate |
|---|---|---|
| `VITE_API_BASE`, `VITE_ADDON_BASE` | build **and** container start (written into `config.js`, used in the CSP) | Build-time, or served from a Worker route |
| `SENTRY_DSN` / `VITE_SENTRY_DSN` | runtime / build | Same |
| `CSP_CONNECT_SRC_EXTRA` | container start | `_headers` at build time, or the Worker |
| `ALLOWED_HOSTS` | Vite dev only | Unchanged |

### Compose and scripts only

- **Compose:** `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` **S** and
  `CATALOGGY_IMAGE_TAG`.
- **Scripts:**
  - `BACKUP_DIR`
  - `API_BASE`, `ADDON_BASE` (`scripts/smoke.ts`)
  - `DATABASE_URL_TEST` (integration tests)

---

## 9. Web PWA hosting, ops and CI

### 9.1 PWA

**Today:**

- `node:26-alpine` runs `serve -s dist -l 7002`.
- `docker-entrypoint.sh` writes `dist/config.js` (`window.__CATALOGGY_API_BASE__`,
  `__CATALOGGY_ADDON_BASE__`, `__CATALOGGY_SENTRY_DSN__`).
- It also renders `serve.json` with the CSP:
  - `script-src` = SHA-256 hashes of the inline scripts in the built `index.html`.
  - `connect-src` = API, addon and Sentry origins + image CDNs + `CSP_CONNECT_SRC_EXTRA`.

**On Workers Static Assets:**

- **SPA fallback.** Use `not_found_handling = "single-page-application"`.
- **`config.js` and the CSP.** Generate them at build time per environment (as a
  `_headers` file), or serve `/config.js` and add headers from a small Worker
  (`run_worker_first`).
- **Caching.** Serve `sw.js` and `index.html` with `Cache-Control: no-cache`, or PWA
  updates stall.
- **Same origin.** Going same-origin (D7) removes CORS and most of the `connect-src` list.

### 9.2 Ops and CI

| Today | After |
|---|---|
| `scripts/backup.sh` / `restore.sh` (`docker compose exec db pg_dump/psql`) | `pg_dump`/`psql` against the direct DB URL, plus the provider's PITR. Never through Hyperdrive. |
| `scripts/smoke.ts` | Mostly portable: point it at the Worker URLs. `/health/ready` must bypass the Hyperdrive cache. Trakt import and Steam sync become 202 + enqueue. |
| `scripts/check-compose-env.mjs` | Re-target to `wrangler.toml` vars and secrets, or replace with `wrangler types`. |
| `.github/workflows/web-dockerpublish.yml` (4 GHCR images, `latest`/`sha-*`/`vX.Y.Z`) | `wrangler-action` deploys (api, addon, web). `prisma migrate deploy` runs before the deploy. Workers versions + rollback replace image tags. Needs `CLOUDFLARE_API_TOKEN` and the account ID. |
| `.github/workflows/web-verify.yml` | Keeps its Postgres service. Add `@cloudflare/vitest-pool-workers` tests for the fetch, `scheduled` and queue handlers. |

---

## 10. Existing bugs found during the audit

These exist today, independent of Cloudflare. The first two were confirmed by reading the
code; none were confirmed at runtime. They are listed so Phase 1 doesn't faithfully port
them, and so they can be fixed separately.

1. **CORS omits `PUT`.** *Fixed: `PUT` added, with a regression test.*
   - **Where:** `web/apps/api/src/lib/cors.ts:10` sends
     `Access-Control-Allow-Methods: GET,POST,DELETE,PATCH,OPTIONS`. The PWA calls
     `PUT /playback` (`web/apps/web/src/api/watch.ts:128`).
   - **Effect:** in the default cross-origin deployment the browser should block saving a
     resume point outside development.
2. **The CI migration steps do nothing.** *Fixed: the filter now targets `@fetch/api`.
   All 38 migrations were checked against a clean Postgres 16 with the corrected command.*
   - **Where:** `.github/workflows/web-verify.yml:86,93` filter on `@cataloggy/api`, but
     the package is `@fetch/api`.
   - **Effect:** "Generate Prisma client" and "Run database migrations" match no project.
     Migrations are exercised only by `test:int`.
3. **The installed addon URL has no secret.**
   - **Where:** the PWA hands out `${ADDON_BASE}/p/<profile-uuid>/manifest.json`
     (`AddonSettings.tsx:18-21`).
   - **Effect:** the addon service is unauthenticated and holds the full `API_TOKEN`, so
     anyone who can reach it and knows a profile UUID can read that profile's lists. The
     unprefixed URL serves the default profile.
   - **Scope:** PIN profiles are spared only because the addon never sends a profile token.
   - **Unused fix:** the API's HMAC secret URL exists but isn't shown.
   - **Matters more on Cloudflare:** a LAN-only addon becomes internet-reachable.
4. **The addon lets anyone record watches.**
   - **Mark-watched links:** `/subtitles` mints a valid mark-watched capability for any
     title and UUID-shaped profile without auth.
   - **Play signals:** with `STREMIO_PLAY_DETECTION` on, `/stream` and `/subtitles`
     forward play signals, and `/stremio/play-signal` doesn't check the PIN.
   - **Matters more on Cloudflare:** the same internet exposure as item 3.
5. **Possible BigInt serialization 500.**
   - **Where:** the webhooks and `/scrobble/stop` return the raw `watchEvent` row.
   - **Effect:** if it dedups onto a Trakt-imported event (a `traktHistoryId` BigInt),
     `JSON.stringify` throws. Unverified.
6. **`web/docs/api.md` drift.**
   - **Wrong or missing behaviour:**
     - The bare `/addon/stremio/*` routes are documented as public, but they aren't.
     - `GET /addon` doesn't exist. Its auth-gate exemption is dead code.
     - Without `x-profile-id` the API returns 400 once two or more profiles exist; the
       docs don't say so.
     - The Emby webhook isn't mentioned.
   - **Incomplete details:**
     - The 240/min and 60/min rate limits aren't listed.
     - `/watch/stats/detailed` is documented with a runtime field it doesn't return.
     - The docs don't say `/search` returns a bare array.
   - **Elsewhere:** `api-cache-routes.ts:158` lists a nonexistent `/collection` route.

---

## 11. Regression safety net

| Suite | Use during the migration |
|---|---|
| `web/apps/addon/src/app.test.ts` (52 cases, HTTP-level with the API mocked) | The best black-box suite. Point it at a `wrangler dev` build of the addon. |
| API auth/crypto tests: `auth`, `auth-gate`, `profile-token`, `pin-hash`, `stremio-secret`, `webhook-auth`, `trakt-oauth-state`, `secret-box`, `cors`, `http-cache`, `body-limit` | **Must pass unchanged.** They guard byte-compatibility with existing databases, tokens and addon URLs. |
| `web/apps/addon/src/api-contracts.test.ts`, `web/packages/shared/src/api-contracts.web.test.ts` | Fixture-based shape checks. Feed them real responses from the Worker. |
| `web/apps/api/src/routes/*.test.ts` (27 files) | Status codes, validation messages and shapes per route. Reusable if the handlers keep their signatures; otherwise rewrite them as HTTP cases. |
| `pnpm test:int` (real Postgres + migrations) | The DB-level suite for the Hyperdrive path. |
| **Missing: a recorded contract suite** | Before Phase 1, record request and response pairs for every PWA and addon call against the current stack, then replay them against the Worker. This is the one thing that proves "the contract didn't break". |

---

## Appendix A: full API route table

Auth legend:

| Code | Meaning |
|---|---|
| **Pub** | No bearer |
| **T** | Bearer `API_TOKEN` |
| **T+P** | Bearer + `x-profile-id` (+ `x-profile-token` if the profile has a PIN) |
| **T+Pq** | Bearer + legacy `?profileId=` (default-profile fallback) + PIN check |
| **T+S** | Bearer + addon service token (404 otherwise) |
| **Sec** | 64-hex addon secret in the path |
| **WH** | Webhook secret |

Errors are always `{error, code?}`. Sources are under `web/apps/api/src/`. The **PWA**
column marks the routes the web app calls.

### Health and metrics

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET | `/health` | Pub | – | `{status:"ok", service:"api"}` | |
| GET | `/health/ready` | Pub | – | `{status, service, database:{ok, latencyMs}}`; 503 | |
| GET | `/metrics` | T | – | `{uptimeSeconds, memory, database, requests, upstream, jobs, jobsError}` | |

### Profiles (`routes/profiles.ts`)

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET | `/profiles` | T | – | `{profiles:[{id,name,hasPin}]}` | ✓ |
| POST | `/profiles` | T | `{name, pin?}` | 201 `{profile}` | ✓ |
| POST | `/profiles/:id/verify` | T (10/min) | `{pin}` | `{id,name,profileToken}`; 401; 429 `{error,retryAfterSec}` | ✓ |
| PATCH | `/profiles/:id` | T + PIN rules | `{name?, pin?: string\|null, currentPin?}` | `{profile}` | ✓ |
| DELETE | `/profiles/:id` | T + token or `currentPin` | `{currentPin?}` | 204 | ✓ |

### Search, metadata, discovery

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET | `/search` | T+P | `q`\|`query`, `type` | **bare array** `[{imdbId,type,name,year,poster,description,genres,rating,inWatchlist,inCollection,lists}]` | ✓ |
| GET | `/meta/:type/:imdbId` | T | – | Metadata row | addon |
| GET | `/meta/:type/:imdbId/cast` | T | – | `{cast, director}` | |
| GET | `/meta/series/:imdbId/seasons` | T | – | `{seasons}` | |
| GET | `/meta/series/:imdbId/season/:n/episodes` | T | – | `{episodes}` | ✓ |
| GET | `/meta/:type/:imdbId/providers` | T | – | `{providers}` | ✓ |
| GET | `/meta/:type/:imdbId/bundle` | T+P | – | `{meta, cast, director, providers, recommendations, seasons, dropped}` | ✓ |
| POST | `/metadata/sync` | T | `{imdbId, type}` | metadata row | |
| POST | `/metadata/refresh-all` | T | `?limit` | `{refreshed,total,limit}` | ✓ |
| GET | `/metadata/anime-search` | T | `q` | `{results}` | |
| GET | `/genres` | T | – | `{genres:string[]}` | addon |
| GET | `/recommendations` | T+P | `imdbId, type` | `{metas}` | |
| GET | `/recommendations/personal` | T+P | `type, limit` | `{metas}` | ✓ addon |
| GET | `/recommendations/ai` | T+P | `type, limit` | `{metas, reasons}` | ✓ addon |
| POST | `/recommendations/ai/refresh` | T+P | – | `{refreshed:true}` | ✓ |
| GET | `/trending` | T+P | `type, window` | `{metas}` | ✓ addon |
| GET | `/popular` | T+P | `type` | `{metas}` | ✓ addon |
| GET | `/streaming` | T | `provider, type, region` | `{metas, provider}` | ✓ addon |
| GET | `/streaming/providers` | T | – | `{providers:[{key,id,name}]}` | ✓ |
| GET | `/anime` | T | `type` | `{metas}` | ✓ addon |
| GET/POST/DELETE | `/ai/config` | T+P | `{config:{url,headers,payload}}` | `{configured, config, lastGeneratedAt}` | ✓ |
| POST | `/ai/test` | T+P | `{config}` | `{success, response\|outcome,error}` | ✓ |

`{metas}` items are `MetaPreview = {id,type,name,poster?,year?,description?,genres?,rating?}`.

### Watch history, series, check-in, scrobble, playback

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| POST | `/watch` | T+P | `{type:movie\|episode, imdbId, seriesImdbId?, season?, episode?, watchedAt?, dateUnknown?, note?}` | 201/200 `{watchEvent}` | ✓ (offline-queued) addon |
| PATCH | `/watch/:eventId` | T+P | `{note}` | `{watchEvent}` | ✓ |
| DELETE | `/watch/:eventId` | T+P | – | 204 | ✓ |
| GET | `/watch/history` | T+P | `limit, offset, imdbId, type` | `{history:[…]}` | ✓ |
| GET | `/watch/stats` | T+P | – | `{totalMovies,totalEpisodes,totalPlays,playsThisWeek}` | ✓ |
| GET | `/watch/stats/detailed` | T+P | – | `{monthly, genreDistribution, currentStreak, longestStreak, topRated}` | ✓ |
| GET | `/watch/stats/year/:year` | T+P | – | `{year,totalMovies,totalEpisodes,totalRuntimeMinutes,topGenres,topRated,busiestMonth,busiestMonthCount}` | ✓ |
| GET | `/show/:imdbId/dropped` | T+P | – | `{dropped}` | |
| POST/DELETE | `/show/:imdbId/drop` | T+P | – | `{dropped}` | ✓ |
| GET | `/series/progress` | T+P | – | `{progress:[…]}` | ✓ |
| GET | `/series/progress/:imdbId` | T+P | – | `{progress}`; 404 | addon |
| POST | `/series/:imdbId/watch-next` | T+P | – | 204; 409 | ✓ |
| GET | `/series/:imdbId/watched-episodes` | T+P | – | `{episodes:[{season,episode}]}` | ✓ |
| POST/DELETE | `/series/:imdbId/season/:s/episode/:e/watch` | T+P | – | 204 | ✓ (POST offline-queued) |
| POST | `/series/:imdbId/season/:s/watch-all` | T+P | `{episodeNumbers}` | `{marked,total}` | ✓ (offline-queued) |
| GET | `/checkin` | T+P | – | `{checkin\|null}` | ✓ |
| POST | `/checkin` | T+P | `{type, imdbId, name, seriesImdbId?, poster?, season?, episode?, runtime?}` | `{checkin}` | ✓ |
| DELETE | `/checkin` | T+P | `?log=true` | 204 | ✓ |
| POST | `/scrobble/start\|pause\|stop` | T+P | `{type, imdbId, seriesImdbId?, season?, episode?, progress?, positionSeconds?, durationSeconds?}` | `{session}` / stop: `{session, recorded, watchEvent}` | addon |
| GET | `/scrobble/now-playing` | T+P | – | `{sessions}` | ✓ |
| GET | `/playback` | T+P | `type, limit` | `{playback:[{id,type,imdbId,season,episode,progress,positionSeconds,durationSeconds,pausedAt,name,poster}]}` | ✓ |
| GET | `/playback/:imdbId` | T+P | `season, episode` | `{playback\|null}` | |
| PUT | `/playback` | T+P | `{type, imdbId, seriesImdbId?, season?, episode?, progress?, positionSeconds?, durationSeconds?}` | `{playback}` | ✓ (CORS bug, §10) |
| DELETE | `/playback/:id` | T+P | – | 204 | ✓ |

### Ratings, lists, tags, calendar

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| POST | `/ratings` | T+P | `{imdbId, type:movie\|series\|season\|episode, rating 1-10, season?, episode?, note?}` | `{rating}` | ✓ |
| GET/DELETE | `/ratings/:type/:imdbId` | T+P | `season, episode` | `{rating}` / 204 | ✓ |
| GET | `/ratings` | T+P | `type, limit, imdbId` | `{ratings}` | ✓ |
| GET/POST | `/lists` | T+P | `{name, kind:"custom"}` | `{lists:[{id,name,kind,createdAt,itemCount}]}` / 201 `{list}` | ✓ addon |
| PATCH/DELETE | `/lists/:id` | T+P | `{name}` | `{list}` / 204 | ✓ |
| POST | `/lists/:listId/items` | T+P | `{type, imdbId, title?}` | 201 `{listItem}`; 409 | ✓ |
| GET | `/lists/:listId/items` | T+P | `type, limit, cursor` | `{items:[{…, title, metadata\|null}], nextCursor?}` | ✓ addon |
| DELETE | `/lists/:listId/items/:type/:imdbId` | T+P | – | 204 | ✓ |
| DELETE | `/lists/:listId/items/:imdbId` | T+P | `?type` | 204 | |
| GET | `/items/:imdbId/lists` | T+P | `type` | `{lists:[{listId,listName,listKind,type,addedAt}]}` | ✓ |
| GET | `/tags` | T+P | `type?, imdbId?` | `{tags}` | ✓ |
| POST | `/tags` | T+P | `{name}` | `{tag}` | |
| DELETE | `/tags/:tagId` | T+P | – | 204 | |
| POST | `/tags/assign` | T+P | `{tagName, type, imdbId}` | `{tag}` | ✓ |
| DELETE | `/tags/assign` | T+P | body `{tagId, type, imdbId}` | 204 | ✓ |
| GET | `/calendar` | T+P | `days` | `{calendar:[{seriesImdbId,seriesName,poster,season,episode,episodeName,airDate,overview}]}` | ✓ |

### Games

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET | `/games` | T+P | `sort` | `{games}` | ✓ |
| GET | `/games/search` | T+P | `q` | `{results}`; 503 `igdb_not_configured` | ✓ |
| POST | `/games` | T+P | `{igdbId, title, coverUrl?, releaseDate?, genres?}` | 201 `{game}` | ✓ |
| PATCH/DELETE | `/games/:id` | T+P | `{rating?, notes?, finished?, finishedAt?}` | `{game}` / 204 | ✓ |
| GET | `/games/steam/status` | T | – | `{configured, player}` | ✓ |
| POST | `/games/steam/sync` | T+P | – | `{total,created,updated,matched,unmatched}` | ✓ |
| GET | `/games/igdb/status` | T | – | `{configured}` | ✓ |

### Settings and integrations (install-global)

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET/POST | `/settings/preferences` | T | `{language?, region?, spoilerProtection?}` | `{language, region, spoilerProtection, availableProviders?}` | ✓ addon |
| GET | `/tmdb/status`, `/omdb/status`, `/rpdb/status` | T | – | `{configured, …}` | ✓ |
| POST/DELETE | `/tmdb/key`, `/omdb/key`, `/rpdb/key` | T | `{apiKey}` | `{configured, …}` | ✓ |
| GET | `/rpdb/config` | T+S | – | `{enabled, apiKey}` | addon |
| GET/POST/DELETE | `/settings/jellyseerr` | T | `{url, apiKey?, requestOnAdd?, cancelOnRemove?}` | `{configured, config}` | ✓ |
| POST | `/settings/jellyseerr/test` | T | – | `{success, …}` | ✓ |
| GET | `/settings/job-status` | T | – | `{failures, runs}` | ✓ |
| GET | `/trakt/status` | T | – | `{connected, configured, expiresAt, redirectUri}` | ✓ |
| GET | `/trakt/oauth/authorize` | T (10/min) | – | `{url, redirectUri}` | ✓ |
| GET | `/trakt/oauth/callback` | Pub (state) | `code, state` | **text/html** | browser redirect |
| POST | `/trakt/disconnect` | T | – | `{disconnected:true}` | ✓ |
| POST | `/trakt/import` | T+P (lock → 409) | – | `{imported:{…}}` | ✓ |
| POST | `/trakt/poll` | T | `{full?}` | `{since, fullBackfill, polledAt, …}` | |
| GET | `/stremio/library/status` | T | – | `{connected, email, apiBase, connectedAt, …}` | ✓ |
| POST | `/stremio/library/connect` | T+P (10/min) | `{email, password}` | status | ✓ |
| POST | `/stremio/library/disconnect` | T | – | `{connected:false}` | ✓ |
| POST | `/stremio/library/import`, `/stremio/library/sync` | T+P | – | `{scanned,fetched,recorded,skipped}` | ✓ |
| POST | `/stremio/play-signal` | T+S | `{type, imdbId, seriesImdbId?, season?, episode?, resource, profileId?, client?}` | 202 `{status}` | addon |
| GET | `/stremio/play-signals` | T+P | – | `{enabled, signals}` | ✓ |

### Built-in Stremio addon and legacy catalog JSON (`routes/stremio.ts`)

| Method | Path | Auth | Response | PWA |
|---|---|---|---|---|
| GET | `/stremio/catalog/my_watchlist_movies`, `…/my_watchlist_series`, `…/my_recent_movies`, `…/my_continue_series` | T+Pq | `{metas}` | |
| GET | `/stremio/list/:listId` | T+Pq | `{metas}` | |
| GET | `/watchlist`, `/continue`, `/recent` | T+Pq | `{metas}` | ✓ |
| GET/POST | `/addon/config` | T+P | `{config:{enabledCatalogs}, availableCatalogs, availableLists, aiConfigured, stremioManifestPath, stremioManifestUrl}` | ✓ addon |
| GET | `/addon/stremio/manifest.json`, `/addon/stremio/catalog/:type/:id.json`, `/addon/stremio/configure` | T(+Pq) | manifest / `{metas}` / 302 | |
| GET | `/addon/stremio/:secret/manifest.json`, `…/:secret/catalog/:type/:id.json`, `…/:secret/configure` | Sec | manifest / `{metas}` / 302 | |

### Push, notifications, export/import, webhooks

| Method | Path | Auth | Request | Response | PWA |
|---|---|---|---|---|---|
| GET | `/push/public-key` | T | – | `{publicKey}` | ✓ |
| POST | `/push/subscribe` | T+P | `{endpoint, keys:{p256dh, auth}}` | 201 `{subscribed:true}` | ✓ |
| POST | `/push/unsubscribe` | T+P | `{endpoint}` | `{subscribed:false}` | ✓ |
| GET/POST | `/notifications/channels` | T+P | `{kind:ntfy\|gotify\|discord\|webhook, url, name?, token?, enabled?}` | `{channels}` / 201 `{channel}` | ✓ |
| PATCH/DELETE | `/notifications/channels/:id` | T+P | `{name?, url?, token?, enabled?}` | `{channel}` / `{deleted:true}` | ✓ |
| POST | `/notifications/channels/:id/test` | T+P | – | `{success, …}` | ✓ |
| GET | `/export` | T+P | – | `{version:1, exportedAt, profile, lists, watchEvents, seriesProgress, ratings, playback}` | ✓ |
| POST | `/import` | T+P | the export payload (200k rows/collection cap) | `{status, summary}` | ✓ |
| POST | `/import/csv` | T+P | `{csv}` | `{status, summary}` | ✓ |
| POST | `/import/external` | T+P | `{format: letterboxd-diary\|letterboxd-ratings\|imdb-ratings\|simkl, csv}` | `{status, format, summary}` | ✓ |
| POST | `/webhooks/plex` | WH (`?token=`) | multipart `payload` or JSON; `?profile=` | `{status:"ignored"\|"skipped"\|"recorded", …}` | Plex |
| POST | `/webhooks/jellyfin` | WH (header) | Jellyfin `PlaybackStop` JSON | same | Jellyfin |
| POST | `/webhooks/emby` | WH | Emby JSON | same | Emby |

---

## Appendix B: TV app Supabase contract

This is for reference only. **The server side is not in this repo** (§3.1). Paths are
under `app/src/main/java/com/fetch/tv/`.

- **RPCs** are `POST /rest/v1/rpc/<name>` with an `apikey` header and the user's JWT.
- **"+origin"** means the call also sends `p_origin_client_id`.

| Area | Calls | Source |
|---|---|---|
| Auth (GoTrue) | `POST /auth/v1/signup`; `POST /auth/v1/token?grant_type=password\|refresh_token`; `GET /auth/v1/user`; `POST /auth/v1/logout` | `core/auth/AuthManager.kt:59-61`, `AuthSessionValidator.kt:43` |
| QR/TV login | RPCs `start_device_login_session(p_device_nonce, p_redirect_base_url, p_device_type, p_device_name?)`, `start_tv_login_session(…)`, `poll_tv_login_session(p_code, p_device_nonce)`; **edge function** `POST /functions/v1/tv-logins-exchange {code, device_nonce}` | `AuthManager.kt:62-65` |
| Account / linking | `get_sync_owner`, `register_current_device`, `get_sync_overview`, `generate_sync_code`, `get_sync_code`, `claim_sync_code`, `unlink_device`; **table** `GET /rest/v1/linked_devices?owner_id=eq.<uid>` | `AuthManager.kt:286`, `DeviceSessionRegistration.kt:103`, `data/repository/SyncRepositoryImpl.kt` |
| Profiles | `sync_push_profiles` (+origin), `sync_pull_profiles`, `sync_delete_profile_data` (+origin), `sync_pull_profile_locks`, `set_profile_pin`, `clear_profile_pin`, `verify_profile_pin`, `get_avatar_catalog`, `get_member_profile_avatar_catalog`, `get_member_profile_background_catalog` | `core/sync/ProfileSyncService.kt`, `data/remote/supabase/AvatarRepository.kt`, `ProfileBackgroundRepository.kt` |
| Addons | `sync_push_addons(p_addons:[{url,sort_order,enabled,name?}], p_profile_id)` (+origin); **table** `GET /rest/v1/addons?user_id=eq.&profile_id=eq.` | `core/sync/AddonSyncService.kt:60-108` |
| Library | `sync_pull_library`, `sync_get_library_delta_cursor`, `sync_pull_library_delta`, `sync_push_library_items` (+origin), `sync_delete_library_items` (+origin) | `data/remote/supabase/SupabaseLibrarySyncRemoteDataSource.kt` |
| Watch progress | `sync_get_watch_progress_delta_cursor`, `sync_pull_watch_progress_delta`, `sync_push_watch_progress` (+origin), `sync_pull_watch_progress`, `sync_delete_watch_progress` (+origin) | `core/sync/WatchProgressSyncService.kt` |
| Watched items | `sync_get_watched_items_delta_cursor`, `sync_pull_watched_items_delta`, `sync_push_watched_items` (+origin), `sync_pull_watched_items`, `sync_delete_watched_items` (+origin) | `core/sync/WatchedItemsSyncService.kt` |
| Settings blobs | `sync_push/pull_profile_settings_blob` (platform `"tv"`), `sync_copy_profile_setup`, `sync_push/pull_home_catalog_settings`, `sync_push/pull_collections`, `sync_push/pull_media_servers`, `sync_seed/push/pull_provider_credentials` | `core/sync/ProfileSettingsSyncService.kt`, `HomeCatalogSettingsSyncService.kt`, `CollectionSyncService.kt`, `MediaServerSyncService.kt`, `ProviderCredentialSyncService.kt` |
| Watch party | `watch_party_create`, `_join`, `_preview`, `_heartbeat`, `_leave`, `_end`, `_update`, `_set_host_only`, `_remove_member`, `_clock`, `_enabled`; **realtime** private channel `watch-party:<id>` (9 broadcast events + presence) | `core/party/PartyApi.kt`, `PartyChannel.kt` |
| Storage | buckets `membership-profile-avatars`, `membership-profile-backgrounds` (authenticated download, signed URLs); public `/storage/v1/object/public/avatars/<path>` | `AvatarRepository.kt`, `ProfileBackgroundRepository.kt` |
| Discovery | `GET <host>/.well-known/fetch` → `{version:1, service:"fetch", self_hosted, backend_url, publishable_key, capabilities}` | `data/remote/ServerDiscovery.kt` |

The DTOs (request params and response fields) are in
`data/remote/supabase/SupabaseModels.kt` and the per-service files above.
