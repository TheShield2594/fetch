# API reference

The Fetch API is a Fastify service on port `7000`. Everything the web UI
does goes through it, so anything here is fair game for a script, a shortcut, a
Home Assistant automation, or your own client.

This is a reference for the endpoints as they behave today. It is not a
stability promise — Fetch is a self-hosted app with one supported client, and
routes do change between versions. Pin `FETCH_IMAGE_TAG` if you build
something on top of it.

- [Authentication](#authentication)
- [Profiles](#profiles)
- [Conventions](#conventions)
- [Endpoints](#endpoints)
  - [Health and metrics](#health-and-metrics)
  - [Search and metadata](#search-and-metadata)
  - [Discovery](#discovery)
  - [Watch history](#watch-history)
  - [Series progress](#series-progress)
  - [Check-in and scrobbling](#check-in-and-scrobbling)
  - [Playback progress (resume points)](#playback-progress-resume-points)
  - [Ratings](#ratings)
  - [Lists](#lists)
  - [Tags](#tags)
  - [Calendar](#calendar)
  - [Games](#games)
  - [Profiles API](#profiles-api)
  - [Devices](#devices)
  - [Collections](#collections)
  - [Settings and API keys](#settings-and-api-keys)
  - [AI recommendations](#ai-recommendations)
  - [Trakt](#trakt)
  - [Stremio library](#stremio-library)
  - [Stremio add-on](#stremio-add-on)
  - [Push notifications](#push-notifications)
  - [Notification channels](#notification-channels)
  - [Export and import](#export-and-import)
  - [Webhooks](#webhooks)

## Authentication

Every endpoint needs a bearer token, which is one of three things:

- **A session token**, from signing in (`POST /auth/login`, `/auth/register`
  or `/auth/bootstrap`). This is what the web app uses. A session only ever
  sees its own account's profiles. Session tokens start with `fs1_` and last
  30 days, renewed as they are used.
- **A paired device's token** (see [Devices](#devices)), which a TV gets by
  pairing instead of being given `API_TOKEN`.
- **`API_TOKEN`**, the server's own token. This is the operator: the add-on
  service, scripts, and creating the first account. It sees every account's
  profiles.

```bash
curl -H "Authorization: Bearer $API_TOKEN" http://192.168.1.25:7000/watch/stats
```

A missing, wrong or expired token gets `401` with a `WWW-Authenticate: Bearer`
header. That header is how the web client tells "you are signed out" apart from
other 401s (an incorrect profile PIN, for instance), so keep it in mind if you
write your own client.

A device belongs to the account that confirmed its pairing code (the admin's,
when confirmed with `API_TOKEN`), and its token sees only that account's
profiles, the same way a signed-in session does. It is never the admin, and it
can't pair or revoke devices (`403 owner_token_required`). Once a device is
revoked, or its account deleted, its token gets the same `401` with
`WWW-Authenticate: Bearer`, which is how a TV knows it was signed out.

These paths are exempt, because they cannot carry a bearer token by construction:

| Path | Why it is exempt | What guards it instead |
| --- | --- | --- |
| `GET /auth/status`, `POST /auth/login`, `POST /auth/register` | They are how a browser gets a token | A password, or a single-use invite code; 10/minute |
| `GET /health` | Liveness probe | Nothing — it reports no data |
| `GET /health/ready` | Readiness probe (the container healthcheck) | Nothing — it reports only whether the database answered, never why |
| `/addon/stremio/<secret>/*` | Stremio sends no credentials | The unguessable per-profile secret in the path. The same routes without a secret need a token. |
| `/trakt/oauth/callback` | Browser redirect from trakt.tv | The `state` value that started the flow |
| `/webhooks/*` | Plex, Jellyfin and Emby post directly | An account's webhook key, or `WEBHOOK_SECRET` plus optional `WEBHOOK_ALLOWED_IPS` |
| `POST /devices/pair/start` | A TV has no credential yet | A code someone signed in must confirm; rate-limited, and capped at 20 pending |
| `GET /devices/pair/poll` | As above | The poll secret `start` returned, in `x-pairing-secret` |

### Accounts

Accounts are invite-only. The first account is created with `API_TOKEN` and is
the **admin**; everyone else redeems an invite link the admin made. Each account
owns its profiles, and through them its lists, history, ratings, games and
Trakt and Stremio connections (one per profile, as on the TV), and has its own
media-server webhook key, AI provider, streaming region and spoiler
protection.

Settings that belong to the whole install are shared by every account and can
only be changed by the admin; anyone else gets `403` with
`code: "admin_required"`. That covers the TMDB, OMDb and RPDB keys, the
metadata language, play detection, the Steam sync, the metadata refresh, `/settings/job-status` and `/metrics`.
`API_TOKEN` counts as the admin.

## Profiles

Most endpoints act on one profile's data. The profile comes from the
`x-profile-id` header:

```bash
curl -H "Authorization: Bearer $TOKEN" \
     -H "x-profile-id: 3f1c…" \
     http://192.168.1.25:7000/lists
```

Omit it and the only profile is used, when there is exactly one (in the
caller's account, for a session); with two or more it is a `400`. A malformed
value is a `400`, not a silent fallback — a typo'd profile id should not
quietly write to someone else's history.

Profiles protected by a PIN also need `x-profile-token`, which
`POST /profiles/:id/verify` returns.

Anything scoped to a profile only ever sees that profile's rows. Asking for
another profile's list returns `404`, not `403` — the lookup is scoped, so the
row genuinely is not found. Another account's profile is a `404` the same way.

## Conventions

- **Errors** are always `{ "error": "<human-readable message>" }`. Some also
  carry a `code` (`body_too_large`, `too_many_rows`) for cases where the fix is
  a specific config change.
- **Rate limits** are 200 requests/minute per IP, and 1000/minute for the add-on
  service, which authenticates with a derived service token. Credential-taking
  routes (sign-in, sign-up, password changes, profile PIN verification, Stremio
  connect, the Trakt OAuth pair) are
  limited to 10/minute on top of that.
- **Types** are `movie` and `series` almost everywhere. Watch events use `movie`
  and `episode`; tags additionally accept `episode`.
- **IDs** are IMDb ids (`tt0111161`) for films and shows, UUIDs for
  Fetch's own rows (lists, games, profiles, tags, watch events). Those are
  uuid columns, so a malformed id is a `400` before any query runs rather than
  an empty result.
- **Validation** rejects a request before it reaches the database, either way it
  is written: a route's JSON Schema (`lib/request-schema.ts`, which is also what
  phrases the message) runs before its handler is entered, and a route with
  explicit checks makes them first thing inside the handler. Either way the
  answer is a `400` naming the field. Types are taken as sent — a number where a
  string belongs is rejected, not coerced.
- **Caching**: `GET` responses carry an ETag. Send `If-None-Match` and you get a
  `304` with no body. Responses over 1 KB are compressed (`br`, `gzip`,
  `deflate`).
- **Body size** is capped by `MAX_BODY_SIZE_MB` (default 32, allowed range
  1–1024). This is the whole request body, so restoring a large backup is the
  case that hits it; the `413` says so. Row counts are a separate, per-route
  limit — see [Export and import](#export-and-import).

## Endpoints

### Health and metrics

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/health` | Liveness: `{ status, service }`. No auth, and no database — it keeps answering 200 during an outage, because restarting the container would not fix one. |
| `GET` | `/health/ready` | Readiness: `SELECT 1` with a two-second deadline. `200` with `{ status, service, database: { ok, latencyMs } }`, or `503` when the database does not answer — which is also what an exhausted connection pool looks like. No auth; the failing body says only that the database did not answer, never why. This is what the `api` container's Docker healthcheck uses. |
| `GET` | `/metrics` | Needs the token. Per-process counters as JSON: `uptimeSeconds`, `memory`, `requests` (total, by status class, and per route template with count/failures/total/max/average duration), `upstream` (the same per outbound hostname), `database` (the readiness probe, with its error), and `jobs` — the last run of every scheduled background job, the same rows Settings → Sync Status shows. Counters reset when the process does. `jobs` is `null` with `jobsError` set when the database is down, so the rest still answers. |

### Search and metadata

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/search?q=&type=` | `type` is `movie`, `series` or `all` (default). `query` works as an alias for `q`. Max 20 results, best title match first, each flagged with `inWatchlist` / `inCollection` / `lists`. `q` also takes an IMDb id (`tt0110912`) or a themoviedb.org link, which resolve to that one title. A query matching no title is retried as a person, and answers with what they are known for. |
| `GET` | `/meta/:type/:imdbId` | Full metadata for one title. |
| `GET` | `/meta/:type/:imdbId/bundle` | Everything the detail panel needs — meta, cast, providers, recommendations, seasons, dropped state — in one round trip. Optional sections degrade to empty rather than failing the response. |
| `GET` | `/meta/:type/:imdbId/cast` | Cast and director. |
| `GET` | `/meta/:type/:imdbId/providers` | Where to stream it, for the configured region. |
| `GET` | `/meta/series/:imdbId/seasons` | Season list. |
| `GET` | `/meta/series/:imdbId/season/:seasonNumber/episodes` | Episodes for one season; `seasonNumber` must be a positive integer. Fetched on demand as a season is expanded. |
| `POST` | `/metadata/sync` | `{ imdbId, type }` — refresh one title from TMDB. |
| `POST` | `/metadata/refresh-all?limit=` | Re-fetch stored metadata in batches of 5. `limit` defaults to 50, caps at 500. |
| `GET` | `/metadata/anime-search?q=` | AniList search. |
| `GET` | `/genres` | Every genre present in your stored metadata. |

### Discovery

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/trending?type=&window=` | `window` is `day` or `week` (default). |
| `GET` | `/popular?type=` | |
| `GET` | `/streaming?provider=&type=&region=` | Provider key from `/streaming/providers`. `region` falls back to the stored setting. |
| `GET` | `/streaming/providers` | The nine supported providers and their TMDB ids. |
| `GET` | `/anime?type=` | Anime discovery, `series` by default. |
| `GET` | `/recommendations?imdbId=&type=` | Similar titles to one title. |
| `GET` | `/recommendations/personal?type=&limit=` | Seeded from your recent watches. Uses the AI provider when one is configured, TMDB otherwise. `limit` caps at 40. |

Discovery responses are cached in memory and returned as Stremio meta previews
(`{ id, type, name, poster, year, description, genres, rating }`).

### Watch history

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/watch` | Record a watch. `{ type: "movie" \| "episode", imdbId, seriesImdbId?, season?, episode?, watchedAt?, dateUnknown?, note? }`. `note` is capped at 2,000 characters. |
| `GET` | `/watch/history?limit=&offset=&imdbId=&type=` | `limit` is 1–200, default 50. `type` is `movie` or `episode`; anything else is a `400` rather than a silently unfiltered page. Filtering happens server-side so it searches every row, not just the loaded page. |
| `PATCH` | `/watch/:eventId` | `{ note }` — sets or clears the note on an existing watch (`null`, or a blank string, clears it), capped at 2,000 characters like `POST /watch`. It does not move the watch date. |
| `DELETE` | `/watch/:eventId` | Deleting an episode rewinds series progress to the next-latest episode watched, or clears the progress row when none is left. |
| `GET` | `/watch/state` | Everything the profile has watched, once per title with its latest watch: `{ movies: [{ imdbId, watchedAt, name, poster }], episodes: [{ imdbId, season, episode, watchedAt, name, poster }], dropped }`, episodes under their series id with the series' name and poster. `dropped` lists the watched shows hidden from Continue Watching (`POST /show/:imdbId/drop`). What a TV reads to badge posters and work out Next Up, in one request. |
| `DELETE` | `/watch/movie/:imdbId` | Unmarks a film: removes every play of it. `204`. Episodes are unmarked under [Series progress](#series-progress). |
| `GET` | `/watch/stats` | Headline counts. |
| `GET` | `/watch/stats/detailed` | Genres, runtime, patterns over time. |
| `GET` | `/watch/stats/year/:year` | Year in Review. |

### Series progress

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/series/progress` | Every show in flight. |
| `GET` | `/series/progress/:imdbId` | One show. |
| `GET` | `/series/:imdbId/watched-episodes` | |
| `POST` | `/series/:imdbId/watch-next` | Mark the next unwatched episode. |
| `POST` | `/series/:imdbId/season/:seasonNumber/episode/:episodeNumber/watch` | |
| `DELETE` | `/series/:imdbId/season/:seasonNumber/episode/:episodeNumber/watch` | |
| `POST` | `/series/:imdbId/season/:seasonNumber/watch-all` | |
| `GET` | `/show/:imdbId/dropped` | |
| `POST` | `/show/:imdbId/drop` | Hide a show from Continue Watching without deleting its history. |
| `DELETE` | `/show/:imdbId/drop` | Un-drop. |

### Check-in and scrobbling

Check-ins are manual ("I'm watching this now"); scrobble sessions are what the
Plex and Jellyfin webhooks drive.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/checkin` | The current check-in, if any. |
| `POST` | `/checkin` | `{ type, imdbId, name, poster?, seriesImdbId?, season?, episode?, runtime? }`. `runtime` is in **minutes** (1–1440), and sets an expiry that far ahead so an abandoned check-in clears itself. `name` is capped at 300 characters and `poster` at 2,048. |
| `DELETE` | `/checkin?log=` | Clears the check-in. `?log=true` records a watch event from it first — that is the "finished watching" action, where the bare call is "cancel". A failure to record still clears the check-in. |
| `GET` | `/scrobble/now-playing` | |
| `POST` | `/scrobble/start` \| `/scrobble/pause` \| `/scrobble/stop` | `{ type, imdbId, seriesImdbId?, season?, episode?, progress?, positionSeconds?, durationSeconds? }` (`pause` and `stop` find the session by `imdbId`/`season`/`episode`). `progress` is a 0–100 percentage; without one it is worked out from `positionSeconds / durationSeconds`. A `stop` at 80% or more records a watch. A `pause`, or a `stop` short of 80%, saves a [resume point](#playback-progress-resume-points) once playback is past 1%. Stale sessions are swept periodically. |

### Playback progress (resume points)

Where a profile stopped part-way through a film or an episode, so a player can
offer "resume from 42:10" — the same idea as Trakt's `/sync/playback`. There is
one resume point per title: saving again moves it, and recording a watch of the
title (from any source — `POST /watch`, a scrobble `stop` past 80%, a webhook,
Stremio) clears it. A watch dated *before* the resume point was saved, such as
a history import, leaves it alone.

Episodes are stored against the **series** id plus `season` and `episode`, the
way watch history stores them. A player can send the episode's own IMDb id as
long as it sends `seriesImdbId` beside it.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/playback?type=&limit=` | Every resume point, most recently paused first, each with the title's `name` and `poster`. `type` is `movie` or `episode`; `limit` is 1–200, default 50. This is the "Continue Watching" row. |
| `GET` | `/playback/:imdbId?season=&episode=` | One title's resume point, or `{ playback: null }`. Pass `season` and `episode` together for an episode (`:imdbId` is the series). Ask before starting playback to decide where to seek. |
| `PUT` | `/playback` | `{ type, imdbId, seriesImdbId?, season?, episode?, progress?, positionSeconds?, durationSeconds? }` — save where playback is now. Needs `progress` (0–100), or `positionSeconds` with `durationSeconds` (whole seconds, up to 12 hours), from which `progress` is worked out. An explicit `progress` wins over the seconds. Episodes need `season` and `episode`. This never records a watch: use `POST /watch` or `/scrobble/stop` for that. |
| `DELETE` | `/playback/:id` | Remove a resume point by its `id` from the list ("remove from Continue Watching"). `204`, or `404` when it is not this profile's. |

A resume point looks like:

```json
{
  "id": "6f0c…",
  "type": "episode",
  "imdbId": "tt0903747",
  "season": 2,
  "episode": 7,
  "progress": 39.9,
  "positionSeconds": 1130,
  "durationSeconds": 2832,
  "pausedAt": "2026-10-20T20:15:00.000Z",
  "name": "Breaking Bad",
  "poster": "https://image.tmdb.org/…"
}
```

`name` and `poster` are only on the list. `positionSeconds` is
`null` when the latest save reported only a percentage; a save that reports no
position at all keeps the stored seconds. `durationSeconds` is kept until a
save reports a new one.

#### Wiring a player to it

A TV or mobile player needs four calls. The scrobble routes do the bookkeeping,
so a player that uses them never has to call `PUT /playback` itself:

1. **Opening a title** — `GET /playback/:imdbId` (with `season`/`episode` for
   an episode). If it returns a resume point, offer to resume from
   `positionSeconds` (or `progress`% of the runtime).
2. **Playback starts or resumes** — `POST /scrobble/start` with the current
   position.
3. **Paused, or backed out of** — `POST /scrobble/pause` or
   `POST /scrobble/stop` with the position. Short of 80%, that saves the resume
   point; at 80% or more, `stop` marks the title watched and clears it.
4. **The "Continue Watching" row** — `GET /playback`.

If the app might be killed without getting to send a `pause` or `stop` — a TV
switched off at the wall — also send `PUT /playback` every 30 seconds or so
while playing, so the resume point is never more than one heartbeat stale.
Marking a title watched without playing it is still `POST /watch`.

```bash
# Started watching from the beginning
curl -X POST -H "Authorization: Bearer $API_TOKEN" -H "Content-Type: application/json" \
     -d '{"type":"episode","imdbId":"tt0903747","season":2,"episode":7,"positionSeconds":0,"durationSeconds":2832}' \
     http://192.168.1.25:7000/scrobble/start

# Paused at 18:50 of a 47:12 episode
curl -X POST -H "Authorization: Bearer $API_TOKEN" -H "Content-Type: application/json" \
     -d '{"type":"episode","imdbId":"tt0903747","season":2,"episode":7,"positionSeconds":1130,"durationSeconds":2832}' \
     http://192.168.1.25:7000/scrobble/pause

# Later, on another device: where was I?
curl -H "Authorization: Bearer $API_TOKEN" \
     "http://192.168.1.25:7000/playback/tt0903747?season=2&episode=7"
```

Resume points are per profile, like everything else: send `x-profile-id` (and
`x-profile-token` for a PIN-protected profile).

### Ratings

Five stars, stored as 1–10 so half-stars survive a round trip.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/ratings?type=&limit=&imdbId=` | `limit` is 1–200, default 50. Filtering by `imdbId` drops the limit entirely, so a show's episode ratings come back whole rather than truncated to the most recent. |
| `POST` | `/ratings` | `{ imdbId, type, rating, season?, episode?, note? }`. `type` is `movie`, `series`, `season` or `episode`. |
| `GET` | `/ratings/:type/:imdbId?season=&episode=` | |
| `DELETE` | `/ratings/:type/:imdbId?season=&episode=` | |

Seasons are their own rating type rather than a series row carrying a season
number — otherwise a Specials (season 0) rating would overwrite the show's own.

### Lists

Three kinds: `watchlist`, `collection`, `custom`. A default watchlist and
collection are created on first start; the watchlist is the one that mirrors to
Trakt.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/lists` | Each list with its item count. |
| `POST` | `/lists` | `{ name, kind }`. `400` for a kind that isn't one of the three; `409` for `watchlist` or `collection`, which are per-profile singletons the app creates for you — only `custom` can be created here. |
| `PATCH` | `/lists/:id` | `{ name }`. Renaming a default list is allowed — both defaults are looked up by kind, not by name. |
| `DELETE` | `/lists/:id` | `409` for the default watchlist or collection — items cascade off the list, so deleting one takes its contents with it. The default is the oldest row of its kind, so a duplicate left by an older version is still deletable. |
| `GET` | `/lists/:listId/items` | Items with metadata joined; anything still missing metadata falls back to the title captured when it was added, and a backfill is queued. |
| `POST` | `/lists/:listId/items` | `{ type, imdbId, title? }`. `409` if already present. |
| `DELETE` | `/lists/:listId/items/:type/:imdbId` | |
| `DELETE` | `/lists/:listId/items/:imdbId?type=` | Infers the type when only one item matches; `400` asking for `?type=` when a movie and a series share the id. |
| `GET` | `/items/:imdbId/lists?type=` | Which of your lists hold this title. |

### Tags

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/tags` | All tags with counts. |
| `GET` | `/tags?type=&imdbId=` | Tags on one item. Both params or neither. |
| `POST` | `/tags` | `{ name }`, max 50 chars. Returns the existing tag if the name is taken. |
| `DELETE` | `/tags/:tagId` | Deleting a tag that doesn't exist is a no-op, not a `404`. |
| `POST` | `/tags/assign` | `{ tagName, type, imdbId }`. Creates the tag if needed; idempotent. |
| `DELETE` | `/tags/assign` | `{ tagId, type, imdbId }`. |

### Calendar

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/calendar?days=` | Upcoming episodes for shows you follow. `days` is 1–90, default 30. Respects the spoiler-protection setting. |

### Games

Needs `TWITCH_CLIENT_ID` / `TWITCH_CLIENT_SECRET` for search, and
`STEAM_API_KEY` / `STEAM_ID` for library sync.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/games?sort=` | `recent` (default), `playtime` or `rating`. |
| `GET` | `/games/search?q=` | IGDB search, each result flagged `inLibrary`. |
| `POST` | `/games` | `{ igdbId, title, coverUrl?, releaseDate?, genres? }`. `409` if already held. |
| `PATCH` | `/games/:id` | `{ rating?, notes?, finished?, finishedAt? }`. Marking `finished` stamps today unless a date is given; un-marking clears it. |
| `DELETE` | `/games/:id` | |
| `GET` | `/games/steam/status` | Reports `configured: true` even if the player-summary lookup fails — the summary is display only. |
| `POST` | `/games/steam/sync` | Syncs into the calling profile. |

### Profiles API

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/profiles` | Every profile with its appearance, sharing and parental settings (see below). |
| `POST` | `/profiles` | `{ name, pin?, …settings }`. A PIN is 4–128 characters. |
| `PATCH` | `/profiles/:id` | `{ name?, pin?, …settings }`. Changing or clearing an existing PIN also needs `currentPin`; `pin: null` removes it. A *first* PIN has nothing to prove, so it is instead only accepted for the profile the caller is acting as (`x-profile-id`, or the only profile on a single-profile install) — `403 profile_not_active` otherwise. Without that, one PIN could lock a household member out of their own profile. |
| `GET` | `/profiles/:id/summary` | Counts of what deleting the profile takes with it: `watchEvents`, `lists`, `listItems`, `playbackProgress`, `ratings`, `collections`. |
| `DELETE` | `/profiles/:id` | Deletes the profile and everything it owns — history, lists, resume points, ratings, collections — and its slot on every paired TV. A PIN-protected profile needs `currentPin` unless the caller's profile token is for it. |
| `POST` | `/profiles/:id/verify` | `{ pin }` → a profile token for `x-profile-token`. `401` on a wrong PIN, `429` once the profile is locked out after repeated failures — separate from the global rate limit. |

The settings mirror what the TV keeps per profile, so a profile edited here reads
the same on every paired TV. All are optional on write; `null` clears the
nullable ones.

| Field | Type | Notes |
| --- | --- | --- |
| `avatarColorHex` | string \| null | `#RRGGBB` or `#RRGGBBAA`. |
| `avatarId` | string \| null | A built-in avatar is `fetch:<name>` (`fetch:fox`, `fetch:robot`, …), the same ids the TV ships. |
| `profileBackgroundId` | string \| null | |
| `usesPrimaryAddons` | boolean | Use the primary profile's addons instead of its own. |
| `usesPrimaryPlugins` | boolean | Kept for the TV's sync schema. |
| `parental.isKidsProfile` | boolean | Switching it on without a `maxRating` applies the kids preset: `PARENTAL_GUIDANCE`, unrated hidden. |
| `parental.maxRating` | string \| null | One of `ALL_AGES`, `OLDER_KIDS`, `PARENTAL_GUIDANCE`, `TEENS`, `MATURE`, `ADULTS_ONLY`; `null` is unrestricted. Movie and TV ratings share the ladder. |
| `parental.allowUnrated` | boolean | Whether titles with no rating pass a limit. |

Responses also carry `hasPin`, `createdAt` and `updatedAt`. The parental fields
are accepted flat as well as under `parental`.

### Devices

A TV signs in by pairing rather than by being given `API_TOKEN`, so each one has
its own token that can be revoked on its own.

1. The TV calls `POST /devices/pair/start` with `{ name? }` and gets
   `{ code, pollSecret, expiresAt, intervalSec, confirmUrl }`. `code` looks like
   `ABCD-EFGH` and lives for five minutes. `confirmUrl` is the website's
   `/pair?code=…` page for a QR code, built from `FETCH_WEB_PUBLIC`, and
   `null` when that isn't set.
2. Someone signed in to the website confirms it: `POST /devices/pair/confirm`
   with `{ code, name? }`. Case, spaces and the dash don't matter. A wrong or
   expired code is `404`, one already confirmed `409`.
3. Meanwhile the TV polls `GET /devices/pair/poll` with `x-pairing-secret:
   <pollSecret>` every `intervalSec`: `202 { status: "pending" }` until then,
   `200 { status: "approved", token, device }` once, and `404 { status:
   "expired" }` after. The token is shown that one time and stored only as a hash.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/devices` | The caller's account's paired devices (every one, for `API_TOKEN`): `id`, `name`, `createdAt`, `lastSeenAt`. Not with a device token. |
| `PATCH` | `/devices/:id` | `{ name }`. Only your own account's devices (`404` otherwise). Not with a device token. |
| `DELETE` | `/devices/:id` | Revokes the device; its next request is `401`. Only your own account's devices. Not with a device token. |
| `GET` | `/devices/me` | The calling device. Device token only. |
| `GET` | `/devices/me/profiles` | Every profile of the device's account with the `slot` (1–6) it occupies on this device, plus `isPrimary` for slot 1. Slots are assigned on first sight and kept; when slot 1 is empty the oldest profile moves into it. Device token only. |
| `PUT` | `/devices/me/profiles/:slot` | `{ name?, …settings }`. Updates the profile in that slot, or creates one there (`201`, `name` required). Never takes a PIN: PINs are set on the website and checked with `POST /profiles/:id/verify`. Device token only. |
| `PUT` | `/devices/me/report` | `{ ok, appVersion?, flavor?, error? }`, sent by the TV after every sync: its build, and whether the sync worked. `GET /sync/overview` shows it. An `error` is kept to 500 characters and cleared by the next `ok: true`. Device token only. |

### Collections

A profile's collections — custom Home rows and the folders inside them — stored
as the TV writes them, so one made on the TV comes back unchanged. Profile-scoped
like every other per-profile route.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/collections` | `{ collections, updatedAt }`. An empty list and `null` when there are none yet. |
| `PUT` | `/collections` | `{ collections, baseUpdatedAt? }` replaces the whole list. With `baseUpdatedAt` (the value last read), a list changed elsewhere in the meantime is `409 collections_conflict` carrying the current one. |
| `POST` | `/collections/preview` | `{ source }` → `{ metas }`, the first 20 titles a source yields. TMDB needs a key (`409 tmdb_not_configured`), Trakt a connection (`409 trakt_not_connected`). Addon catalogs are `422 preview_in_browser`: the website fetches those from the addon directly, at the source's `addonUrl`. |

Each collection is `{ id, title, backdropImageUrl?, pinToTop?, viewMode?, showAllTab?, folders }`,
`viewMode` one of `TABBED_GRID`, `ROWS`, `FOLLOW_LAYOUT`. Each folder is `{ id,
title, coverImageUrl?, coverEmoji?, tileShape?, hideTitle?, sources }`. A source
is tagged by `provider`:

| `provider` | Fields |
| --- | --- |
| `addon` | `addonId`, `type`, `catalogId`, `genre?`, `addonUrl?`. The TV uses its installed addon with that id, and loads the catalog from `addonUrl` (http(s) only) when it has none. |
| `tmdb` | `tmdbSourceType` (`LIST`, `COLLECTION`, `COMPANY`, `NETWORK`, `DISCOVER`, `PERSON`, `DIRECTOR`), `tmdbId` (all but `DISCOVER`), `title`, `mediaType` (`MOVIE`/`TV`), `sortBy`, `filters` (the TV's `TmdbCollectionFilters`: `withGenres`, `releaseDateGte`, `voteAverageGte`, `year`, …). |
| `trakt` | `traktListId`, `title`, `mediaType`, `sortBy`, `sortHow`. |
| `fetch` | `listId` — one of this profile's lists on the website — `title`, `mediaType?`. Lets a list made here become a row on the TV without Trakt. |

Fields the API doesn't know are kept as sent.
### What a TV syncs, per profile

The resources a TV pointed at this server syncs: its addons, its Home row order
and its settings. A paired TV syncs all three for its active profile on start,
on returning to the foreground and on a profile switch, and pushes a change
made on it shortly after. The profile is named in the path rather than by `x-profile-id`,
because the profile being arranged is the subject of the request; a
PIN-protected one still needs its own `x-profile-token` (`401
profile_verification_required` otherwise), whoever is asking.

**Addons.** A manifest URL can carry the addon's own credentials (a debrid key
in the path, a token in the query), so the API logs only its host and never
repeats it in an error. Manifests are fetched under the notification-channel
rules: http(s) only, LAN hosts allowed, never the cloud-metadata range, checked
by DNS, redirects refused. `stremio://` URLs are read as `https://`.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/profiles/:id/addons` | `{ addons, updatedAt }`, in order. `updatedAt` is when the list last changed from anywhere, and `null` until it first does — how a TV pairing for the first time knows to send its own addons rather than adopt an empty list. Each has its `manifestUrl`, `enabled`, the manifest's `addonId`, `name`, `version`, `logo`, `types`, `resources`, `catalogs`, `configurable` and `configureUrl`. `reachable: false` means the manifest has never been readable: the addon is kept and named by its host, but its catalogs are unknown. |
| `POST` | `/profiles/:id/addons` | `{ manifestUrl, manifest? }`. Fetches and validates the manifest and installs the addon at the end. `400 manifest_invalid` with a reason when the address doesn't answer or isn't a Stremio manifest, `409` when it is already installed. `manifest` is a fallback used only when the server can't reach the address itself (the website's own addon on a public URL the API container can't resolve); it is validated the same way. 100 addons per profile. |
| `PATCH` | `/profiles/:id/addons/:addonId` | `{ enabled?, manifestUrl?, manifest? }`. A new `manifestUrl` (what an addon's configure page hands back) is fetched and validated like an add. |
| `POST` | `/profiles/:id/addons/:addonId/refresh` | Fetches the manifest again. `502 manifest_unreachable` with a reason when it can't. |
| `DELETE` | `/profiles/:id/addons/:addonId` | Also drops the addon's rows from the stored Home layout. |
| `PUT` | `/profiles/:id/addons/order` | `{ ids }`: every installed addon's id, once each, in the new order. |
| `PUT` | `/profiles/:id/addons` | `{ addons: [{ manifestUrl, enabled?, name?, manifest? }] }`, the whole list in order — the TV's sync. Installed addons keep their cached manifest; new ones are fetched, and one that can't be read is still kept, named by `name` or its host, so a TV's list survives the round trip. Addons not in the list are removed. |

**Versioned documents.** TV settings and the Home layout are each one JSON
document per profile, read with a `version` (also the `ETag`) and replaced whole
by a `PUT` that sends `If-Match: "<version>"` — `"0"` when there is none yet. A
stale version gets `412 version_conflict` with the current `version`, so a TV
and a browser can't overwrite each other; re-read, merge and retry. A missing
`If-Match` is `428 if_match_required`.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/profiles/:id/settings/tv` | `{ settings, version, updatedAt }`. `settings` is `{}` at version 0. |
| `PUT` | `/profiles/:id/settings/tv` | `{ settings }`, with `If-Match`. An opaque JSON object of at most 256 KB: the API checks its size and shape and never reads it, because the TV app owns the schema (today `{ version, features: { <feature>: { <key>: { type, value } } } }`, as `ProfileSettingsSyncService` writes it). Device-only settings are never in it. |
| `GET` | `/profiles/:id/home-layout` | `{ rows, version, updatedAt }`. `rows` is the full order: what was arranged, then every row the profile's addons and lists provide that hasn't been placed yet (visible), minus rows whose addon or list is gone. Each row has `key`, `title`, `hidden`, `source` (`addon`, `list`, `collection`, `builtin` or `other`), `addonName`, `type` and `available` (false for a row whose catalog this server can't currently read; it is kept). |
| `PUT` | `/profiles/:id/home-layout` | `{ rows: [{ key, hidden?, title? }] }`, with `If-Match`, at most 500 rows. Keys are `addon:<addonId>:<type>:<catalogId>` (each part URI-encoded), `list:<listId>`, `collection:<collectionId>` (one of the profile's `/collections`, URI-encoded), `builtin:continue_watching`, or any other `prefix:value` the TV owns, which is kept as sent. A collection row is dropped once the collection is deleted, and kept while the profile has no collections stored here. A row left out isn't hidden: it goes back to unplaced and reappears at the end. |

**Overview.**

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/sync/overview` | `devices`: every paired TV with `lastSeenAt` and what it last reported — `appVersion`, `flavor`, `lastSyncAt`, `lastSyncOk`, `lastSyncError` (all `null` until its first report); left out when a device asks. Every profile with counts of `addons`, `enabledAddons`, `lists`, `resumePoints` and `watchedItems`, and when its TV settings and Home layout last changed; plus `server.database` (the `/health/ready` probe) and `server.jobs` (the last run of each scheduled job). Counts only, so locked profiles are included, as `GET /profiles` lists their names. |

### Sign-in and accounts

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/auth/status` | Public. `{ setupRequired }`: true until the admin account exists. |
| `POST` | `/auth/bootstrap` | `API_TOKEN` only. `{ username, password }` → `{ token, account }`. Creates the admin, which takes every profile that existed before accounts. `409` once an account exists. |
| `POST` | `/auth/login` | Public. `{ username, password }` → `{ token, account }`. `401` for a wrong password or an unknown name alike; `429 { retryAfterSec }` after repeated failures, with the same escalating lockout as profile PINs. |
| `POST` | `/auth/logout` | Ends this session. `204`. |
| `GET` | `/auth/me` | `{ account: { id, username, isAdmin } \| null, isAdmin }`. `account` is null for `API_TOKEN`. |
| `POST` | `/auth/password` | `{ currentPassword, newPassword }`. Signs out every other session. `204`. |
| `POST` | `/auth/invites` | Admin. `{ expiresInDays? }` (1–30, default 7) → `{ invite: { id, code, expiresAt }, url }`. The code is shown only here; only its hash is stored. `url` is built from `FETCH_WEB_PUBLIC`, or null when that is unset. |
| `GET` | `/auth/invites` | Admin. `{ invites: [{ id, createdAt, expiresAt, usedAt, usedBy }] }`. |
| `DELETE` | `/auth/invites/:id` | Admin. Revokes an invite. |
| `POST` | `/auth/register` | Public. `{ inviteCode, username, password }` → `{ token, account }`. Creates the account and a first profile named after it. `400 invite_invalid` for a used, expired or unknown code; `409` for a taken name, which leaves the invite usable. |
| `GET` | `/auth/accounts` | Admin. `{ accounts: [{ id, username, isAdmin, createdAt, profileCount }] }`. |
| `DELETE` | `/auth/accounts/:id` | Admin. Deletes the account and everything its profiles own. The admin account can't be deleted. |

Usernames are 3–32 characters of letters, numbers, `.`, `-` and `_`, and are
case-insensitive. Passwords are 8–256 characters, hashed with scrypt.

`GET /profiles` lists the caller's own profiles (every profile, for
`API_TOKEN`), and a new profile belongs to the caller's account. An account
can't delete its last profile.

### Settings and API keys

Keys saved here take precedence over the matching environment variable, so a
rotated key doesn't mean editing `.env` and restarting.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/settings/preferences` | Language, region, spoiler protection, and the provider catalogue. Region and spoiler protection are the caller's account's (an account that never set one gets the install's value); the language is the install's. |
| `POST` | `/settings/preferences` | `{ language?, region?, spoilerProtection? }`. Language is `en` or `en-US` shaped; region is two letters. Both are normalized. Region and spoiler protection are saved for the caller's account; changing the language takes the admin (`403 admin_required` otherwise, though sending the current one back is accepted). Clears the discovery cache. |
| `GET` | `/settings/job-status` | Background jobs that are currently failing — the Steam sync, Trakt poll, episode notifications, AI refresh. Otherwise these are only visible in the logs. |
| `GET` | `/tmdb/status` | `{ configured, source }` where source is `db`, `env` or `none`. |
| `POST` | `/tmdb/key` | Validated against TMDB before saving, so a typo can't overwrite a working key. |
| `DELETE` | `/tmdb/key` | Reports the env fallback that remains, if any. |
| `GET` | `/omdb/status` | `{ configured }`. |
| `POST` | `/omdb/key` | Validated against OMDb before saving; its own error text is passed through. Posting an empty key deletes the stored one and returns `{ configured: false }`. |
| `DELETE` | `/omdb/key` | |
| `GET` | `/rpdb/status` | `{ configured, hasKey }`. |
| `POST` | `/rpdb/key` | Not validated on save — RPDB has no cheap check, so a bad key shows up as missing posters rather than an error here. Posting an empty key deletes the stored one. |
| `DELETE` | `/rpdb/key` | |
| `GET` | `/rpdb/config` | Used by the add-on service, which needs the key to build poster URLs itself. There is no per-title poster endpoint: everywhere else the API substitutes RPDB posters into the metadata it already returns. This is the one route that hands a stored third-party key back out, so it takes the add-on's service token and 404s for anything else — a browser holds `API_TOKEN` too. `/rpdb/status` is what Settings reads. |

### AI recommendations

Any OpenAI-compatible chat-completions endpoint, including a local one.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/ai/config` | The caller's account's provider. Headers are redacted. Includes `lastGeneratedAt`. |
| `POST` | `/ai/config` | `{ config: { url, headers, payload: { model, max_tokens? } } }`, saved for the caller's account and used for its profiles' recommendations. `max_tokens` below 2048 is clamped up — under that, responses truncate mid-JSON. Only the admin's URL may be a private-network or loopback address; anyone else's must be public (400 otherwise). The same rule applies to notification channel URLs. |
| `DELETE` | `/ai/config` | |
| `POST` | `/ai/test` | Sends one trivial prompt. On failure returns `{ success: false, outcome, error }`, where `outcome` is one of `blocked` / `misconfigured` / `unreachable` / `rejected` / `failed` — never the status code, the socket error or the upstream body, so it can't be used as an SSRF probe. Redirects are not followed; the detail is in the server log. |
| `GET` | `/recommendations/ai?type=&limit=` | With reasons per title. Falls back to TMDB seeds when no provider is configured. `limit` caps at 20. |
| `POST` | `/recommendations/ai/refresh` | Drops the cached set; the next request regenerates. |

URLs are checked twice: syntactically on save, and against DNS immediately
before each outbound request — records can change after a config is saved.

### Trakt

Each profile has its own Trakt connection; these routes act on the requesting
profile's (`x-profile-id`). The OAuth `state` records which profile started the
flow, so the callback, which has no session, stores the tokens under that
profile, and refuses them if the profile was deleted in the meantime.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/trakt/status` | |
| `GET` | `/trakt/oauth/authorize` | Starts the flow. |
| `GET` | `/trakt/oauth/callback` | The redirect target. No bearer token; bound to the flow by `state`. |
| `POST` | `/trakt/import` | One-time history import. `409` if an import is already running. |
| `POST` | `/trakt/poll` | Run the scheduled poll now: history, watchlist, then resume points from Trakt's `/sync/playback`. The response adds `playback: { fetched, resumePoints, removedFromTrakt }`, with an `error` in it when only that step failed. |
| `POST` | `/trakt/disconnect` | |

### Simkl

Each profile has its own Simkl connection; every route acts on the requesting
profile's. Sign-in is Simkl's PIN flow. 409 `simkl_not_configured` when the
server has no `SIMKL_CLIENT_ID`.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/simkl/status` | `{ configured, connected, username, lastSyncedAt, pending }`; `pending` is the code being waited on, if any. |
| `POST` | `/simkl/pin` | Starts a sign-in: `{ userCode, verificationUrl, expiresAt, interval }`. The person enters `userCode` at `verificationUrl`. |
| `POST` | `/simkl/pin/poll` | Checks once: `{ result: "pending" \| "connected" \| "expired" \| "none", status }`. Call it every `interval` seconds. |
| `POST` | `/simkl/sync` | `{ full? }`. Reads the Simkl library (only what changed since the last sync unless `full`) into the profile: `{ movies, episodes, watchlist, skipped, full }`. 401 when Simkl has rejected the sign-in. |
| `POST` | `/simkl/disconnect` | Forgets the sign-in. Simkl tokens don't expire and have no revoke call. |

Plays recorded in Fetch, watchlist adds and scrobbles are sent to the profile's
Simkl as they happen, best-effort.

### MDBList

The same shape as Simkl, with MDBList's OAuth device flow (write scope). 409
`mdblist_not_configured` without `MDBLIST_CLIENT_ID`.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/mdblist/status` | `{ configured, connected, username, lastSyncedAt, pending }`. |
| `POST` | `/mdblist/device` | Starts a sign-in: `{ userCode, verificationUrl, verificationUrlComplete, expiresAt, interval }`. The sign-in page is always https://mdblist.com. The device code itself stays on the server. |
| `POST` | `/mdblist/device/poll` | `{ result: "pending" \| "connected" \| "expired" \| "denied" \| "none", status }`. |
| `POST` | `/mdblist/sync` | `{ full? }`. Reads watched history and the watchlist into the profile, unless MDBList's last activities show no change (`unchanged: true`). 401 when MDBList revoked the sign-in. |
| `POST` | `/mdblist/disconnect` | Revokes the token with MDBList, then forgets it. |

### Media servers

A profile's Plex, Jellyfin and Emby sign-ins; every route acts on the
requesting profile's. Addresses are checked like an AI provider's, under the
account's scope: only the admin's profiles may reach a private-network server
(`422 server_blocked` otherwise).

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/media-servers` | `{ providers, servers: [{ id, provider, serverName, address, userName, importWatchState, lastSyncedAt, connectedAt }], plexLink }`. |
| `POST` | `/media-servers/plex/link` | Starts a Plex sign-in: `{ code, linkUrl, expiresAt, interval }`; the code is entered at plex.tv/link. |
| `POST` | `/media-servers/plex/link/poll` | `{ result: "pending" \| "connected" \| "expired" \| "none", servers }`. On `connected`, each server on the account (Plex Media Server 1.30+) that answers at an allowed address is saved. |
| `POST` | `/media-servers/:provider/sign-in` | `jellyfin` or `emby`: `{ address, username, password }` (Jellyfin 10.9+, Emby 4.7+). 201 `{ serverName, userName }`; 401 `server_auth` on a wrong password. |
| `PATCH` | `/media-servers/:id` | `{ importWatchState }`. |
| `POST` | `/media-servers/:id/sync` | Reads the server now: `{ movies, episodes, resumePoints, skipped }`. |
| `DELETE` | `/media-servers/:id` | Signs out (Jellyfin and Emby) and forgets the sign-in. |

### Stremio library

Each profile has its own Stremio connection, which syncs into that profile.
Every route here acts on the requesting profile's (`x-profile-id`).

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/stremio/library/status` | |
| `POST` | `/stremio/library/connect` | `{ email, password }`. Takes a baseline without recording history, so connecting an account with years of history doesn't backdate a flood of watch events. If the baseline fails the connection is undone — a connection with no baseline would make the next incremental pass treat the whole library as newly watched. |
| `POST` | `/stremio/library/import` | The opt-in backfill, dated from Stremio's own `lastWatched`. |
| `POST` | `/stremio/library/sync` | The incremental pass, on demand. |
| `POST` | `/stremio/library/disconnect` | |
| `POST` | `/stremio/play-signal` | Written by the add-on service, and only by it: the profile id picks which profile a signal is written to, so the route takes the add-on's service token and 404s for anything else. The id may come as `x-profile-id` (what the add-on sends) or in the body, and is validated rather than trusted either way; absent both, the default profile. |
| `GET` | `/stremio/play-signals` | Diagnostics: what the add-on has seen but not yet acted on. |

### Stremio add-on

These serve Stremio clients directly. The `:secret` variants are the per-profile
URLs; the unprefixed ones use the default profile.

| Method | Path |
| --- | --- |
| `GET` | `/addon/stremio/manifest.json` |
| `GET` | `/addon/stremio/catalog/:type/:id.json` |
| `GET` | `/addon/stremio/configure` |
| `GET` | `/addon/stremio/:secret/manifest.json` |
| `GET` | `/addon/stremio/:secret/catalog/:type/:id.json` |
| `GET` | `/addon/stremio/:secret/configure` |
| `GET`/`POST` | `/addon/config` |

`GET /addon/config` answers for the calling profile and also returns
`stremioManifestPath`/`stremioManifestUrl` (the API's own catalog addon) and
`stremioProfileKey`, the same per-profile key, which the website puts in the
addon service's install URL: `<ADDON_PUBLIC_BASE>/u/<profileId>/<key>/manifest.json`.
With `ADDON_REQUIRE_SIGNED_URLS=true` that is the only URL form the addon
service serves.

Catalog data also has plain-JSON equivalents used by the web UI: `/watchlist`,
`/continue`, `/recent`, `/stremio/list/:listId`, and
`/stremio/catalog/my_{watchlist_movies,watchlist_series,continue_series,recent_movies}`.

### Push notifications

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/push/public-key` | VAPID public key. |
| `POST` | `/push/subscribe` | `{ endpoint, keys: { p256dh, auth } }`. The endpoint must resolve to a real push service over https — including its DNS result — so a stolen API token can't turn the notification job into an SSRF proxy. |
| `POST` | `/push/unsubscribe` | `{ endpoint }`. |

### Notification channels

Non-push notification targets, scoped to the calling profile: `ntfy`, `gotify`,
`discord` and `webhook`. Unlike a push endpoint, these may point at a LAN or
loopback address — reaching a self-hosted ntfy is the point — so the URL is held
to the same rules as the AI provider endpoint instead: http(s) only, never the
cloud-metadata/link-local range, checked by DNS as well as by name, and
re-resolved immediately before each send. Redirects are refused, not followed.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/notifications/channels` | The profile's channels. The stored token is never returned — only `hasToken`. |
| `POST` | `/notifications/channels` | `{ kind, url, name?, token?, enabled? }`. An ntfy URL must include its topic; Gotify requires a token. Ten channels per profile. |
| `PATCH` | `/notifications/channels/:id` | `{ name?, url?, token?, enabled? }`. Omitting `token` keeps the stored one; `""` clears it. |
| `DELETE` | `/notifications/channels/:id` | |
| `POST` | `/notifications/channels/:id/test` | Sends a real test notification. Returns `{ success }`, or `{ success: false, outcome, error }` with the same collapsed `outcome` set as `/ai/test` — an upstream failure is a result, not a `500`, and neither the status code nor the socket error is echoed back. |

A generic `webhook` receives `{ event, title, message, url, data }`; for an
upcoming episode, `data` carries `seriesImdbId`, `seriesName`, `season`,
`episode` and `episodeName`. Links (`url`, and the tap target on ntfy, Gotify
and Discord) are only included when `FETCH_WEB_PUBLIC` tells the API where
the web UI lives.

### Export and import

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/export` | The profile's data as JSON: lists and their items, watch events, series progress, ratings, resume points (`playback`), collections, and the profile's appearance and parental settings (`profile.settings`). Carries a `version` field. Paired devices are not included: they belong to the install, not a profile, and a restored server pairs its TVs again. |
| `POST` | `/import` | Restores an `/export` payload. The body's `version` must be exactly `1`; anything else is a `400` rather than a partial restore. Resume points only move forward: one is skipped when the profile already has a newer pause for that title, or a watch of it at or after the pause, so restoring an old backup can't bring back something finished since. The summary's `playback` count is the points actually written. A file from before resume points were exported has no `playback` and imports fine. Profile settings are restored as exported. Collections are added when the profile doesn't already have one with the same id, and a website-list source is pointed at the list it was restored into. |
| `POST` | `/import/csv` | Header must include `imdbId,type,watchedAt`; `season,episode` optional. |
| `POST` | `/import/external` | `{ format, csv }` where format is `letterboxd-diary`, `letterboxd-ratings`, `imdb-ratings` or `simkl`. |

Letterboxd exports carry no IMDb ids, so each unique title costs a TMDB search.
Those two formats are capped at 10,000 rows; the id-carrying formats allow
200,000 per collection. Over the cap is a `413` with
`code: "too_many_rows"` — a row limit, not a byte limit, so raising
`MAX_BODY_SIZE_MB` does not affect it.

### Webhooks

No bearer token. Authenticated by an account's webhook key (`fwh_…`, see
`/auth/webhook-key` below) or by `WEBHOOK_SECRET`, in the `x-webhook-secret`
header where the sender supports one and in the `token` query parameter where it
doesn't (Plex). A key limits the play to its account's profiles, and
`WEBHOOK_SECRET` to the admin account's: `profile=<id>` naming a profile outside
that account is a 404, and the account-name match only looks inside it. Prefer
the header: Fetch redacts `token` from its own application logs
(`?token=REDACTED`), but a reverse proxy or any other hop in front of it keeps
its own access log, and that copy still carries the query string.

| Method | Path |
| --- | --- |
| `POST` | `/webhooks/plex` |
| `POST` | `/webhooks/jellyfin` |
| `POST` | `/webhooks/emby` |

| `GET` | `/auth/webhook-key` |
| `POST` | `/auth/webhook-key` |
| `DELETE` | `/auth/webhook-key` |

The `/auth/webhook-key` routes take a signed-in session (400 otherwise) and
manage that account's key: `GET` returns `{ configured, createdAt }`, `POST`
makes a key and returns `{ key, createdAt }` with a 201, replacing any earlier
key, and `DELETE` removes it (204). The key is stored as a SHA-256 hash, so
`POST` is the only time it is returned.

Set `WEBHOOK_ALLOWED_IPS` to also restrict `WEBHOOK_SECRET` by source address;
it doesn't apply to account keys. See the
[webhook secret placement](../README.md#webhook-secret-placement) section of the
README for which field each server can actually send.
