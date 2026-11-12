# The Fetch ecosystem

Fetch is two things in one repository:

| | Where | What it is |
| --- | --- | --- |
| **Fetch for Android TV** | `app/` | The player. Works on its own, with no account and no server. |
| **Fetch website** | `web/` | A self-hosted server and web app (formerly [Cataloggy](https://github.com/TheShield2594/cataloggy)): profiles, addons, lists, watch history and settings, editable from a phone or computer. |

The website is optional. A TV with no server configured stays exactly as it is
today: local-first, everything on the device. Pointing the TV at a Fetch website
turns the website into the place your account lives, the way Nuvio's web app is
for Nuvio, except that you run it.

## Why one project

- **Lists without Trakt.** Watchlists, collections and custom lists ("playlists")
  are created on the website and show up as rows on the TV. Trakt and Simkl
  become optional imports, not the thing your library depends on. A TV with
  neither still has lists: each profile can make its own under Library → Manage
  Lists, kept on the device (`data/local/LocalListsDataStore.kt`) in the same shape as
  the website's `/lists`, and add one as a Home row through a collection.
- **Settings in one place.** Profiles, PINs, installed addons and their order,
  home rows and stream ranking rules are edited on a real keyboard and screen,
  and every TV in the house picks them up.
- **One release, one contract.** The TV client and the API it talks to change in
  the same pull request, so they cannot drift apart.

## How they connect

```text
 phone / laptop browser                 Android TV
          │                                  │
          ▼                                  ▼
   web/apps/web (PWA) ──► web/apps/api ◄── app/ (sync client)
                              │   ▲
                         Postgres │
                                  └── web/apps/addon (Stremio addon:
                                      lists and Continue Watching as
                                      catalogs, for Stremio/Omni too)
```

The TV talks to the API directly for everything it syncs: progress, watched
state, lists, profiles, addons, Home rows and settings. The Stremio addon is for
clients that only speak the addon protocol (Stremio, Omni); a paired TV reads the
same lists natively through the **Fetch website** library source.

## What the TV syncs, and what the website already has

The TV's sync layer (`app/src/main/java/com/fetch/tv/core/sync/`) is split by
domain. Each service decides when to push and what to apply, and hands the
transport to a backend behind one interface, `SyncBackend`
(`core/sync/backend/`), whose one implementation is `FetchWebsiteSyncBackend`.
`SyncBackends` says whether it is in use, from the choice in Settings → Account
(see [Choosing a backend](#choosing-a-backend)). Watch progress, watched items and
the library are the exception: the website carries them as a tracking provider
(`data/website/FetchWebsiteTracking.kt`). Each domain maps onto the website like
this:

Titles are keyed by IMDb id where they have one, and otherwise by the prefixed id
the TV uses (`tmdb:603`, `kitsu:1376`, `mal:1`, …; `web/apps/api/src/lib/content-id.ts`
and `shared/…/data/website/FetchWebsiteContentIds.kt`), so anime and TMDB-only titles
sync too. The columns are still called `imdbId`.

| TV sync service | Website today | Gap |
| --- | --- | --- |
| `ProfileSyncService` | `/profiles` with colour, avatar, parental limits and kids rules; `/devices/me/profiles` maps them to the TV's slots; PINs checked by `/profiles/:id/verify` | None — the TV uses it when paired (#96). The day's screen time stays counted on each TV |
| Watch progress (tracking provider) | `/playback`, `/scrobble/*` (resume points) | None — while paired the website is a tracking provider (`data/website/FetchWebsiteTracking.kt`): scrobbles and resume points go to it, and Continue Watching reads from it when chosen as the watch progress source |
| Watched marks (tracking provider) | `/watch`, `/watch/state`, `/series/progress` | None — watched marks go to the website, and badges and Next Up read `/watch/state` |
| Library (tracking provider) | `/lists` (watchlist, collection, custom) | None — the **Fetch website** library source shows and edits the website's lists |
| `AddonSyncService` | `/profiles/:id/addons`, Settings → TV → TV Addons | None — the TV uses it when paired |
| `ProfileSettingsSyncService` | `/profiles/:id/settings/tv`, Settings → TV → TV Playback Settings | None — the TV uses it when paired |
| `HomeCatalogSettingsSyncService` | `/profiles/:id/home-layout`, Settings → TV → Home Rows | None — the TV uses it when paired |
| `CollectionSyncService` | `/collections`, plus a `fetch` source for the website's own lists | None — the TV uses it when paired (#95) |
| Provider credentials | Trakt, TMDB, OMDb, RPDB keys | Not synced; they stay on the device |
| Episode queue (#48) | Rides in the settings blob (`EpisodeQueueStore.FEATURE`), so `/profiles/:id/settings/tv` | None |

## Auth

People sign in to the website with their own account (username and password;
the install is invite-only, see "Accounts and invites" in `web/README.md`), and
each sign-in is a session the account can see and revoke in Settings → Account → Where you're signed in
(`GET /auth/sessions`, `web/apps/api/src/lib/sessions.ts`), alongside its TVs.
The browser holds that session, and a PIN profile's access token, as `HttpOnly`
cookies the page cannot read (#455). The shared `API_TOKEN` in `.env` only creates
the first (admin) account and stays as a recovery key against the API. A TV never
types a password or the API token: it signs in by **device pairing** (#91), and
each paired TV gets its own token, sent as `Authorization: Bearer` (which the API
always prefers over a cookie), that can be revoked on its own:

1. Settings → Account → Sync → Fetch website on the TV (or the first-run Server
   step) opens on a **code from the pairing hub** at `watchfetch.tv`
   (`/hub/pair/*`, `web/apps/api/src/lib/pairing-hub.ts`), so no address is
   typed. Confirming that code on any Fetch website (Settings → TVs & Devices,
   or the hub's `/link` page that the TV's QR code opens, which goes on to your
   website's `/pair`) makes the website *claim* it at the hub with its own API
   address. The TV learns the address from the hub and collects its token from
   the website with a handoff secret whose hash is all the hub ever saw
   (`FetchPairingHub` in `shared/`). The hub runs on the install with
   `FETCH_PAIRING_HUB_SERVE=true`; every install claims through
   `FETCH_PAIRING_HUB_URL` (default `https://watchfetch.tv/api`, `off` to turn
   it off).

   **Enter the address instead** (or a hub that can't be reached) falls back to
   the websites the TV finds on the LAN (mDNS), or **the address
   you open the website at**, such as `fetch.example.com` or `192.168.1.25:7002`.
   The API's own address works too. The TV finds the API from the website's
   `fetch-server.json` (written by the web container at start from
   `VITE_API_BASE`), and otherwise tries `<website>/api` and the same host on
   port 7000 (`FetchServerAddress` in `shared/`, which the desktop app uses too).
   The address step also shows a QR code that opens the TV's phone page
   (`/website` on the phone hub), so the address can be typed on a phone
   instead of with the remote. Once the TV has a code, the phone opens the
   website's confirm page with that code filled in.
2. The TV shows a short code, plus a QR code for the website's `/pair` page. The
   QR code is built from the website address that worked, falling back to the
   API's `FETCH_WEB_PUBLIC`. Signed in on the website, you scan it, or type the
   code under Settings → TVs & Devices.
3. The API issues that TV its own token, listed and revocable on the website. A
   revoked TV gets a bearer 401 on its next request and says it was signed out.

While paired, every domain above goes to the website: profiles (colour, avatar,
parental limit, kids rules), and each profile's collections, addons, Home rows and
settings. Parental limits and kids rules are written into the TV's own restriction
store, so they keep applying offline. Kids rules (autoplay cap, approved titles,
daily screen time, bedtime) can be changed on any TV or on the website, and the
change made last wins; the screen time used today is counted on each TV and never
leaves it.
The website does not filter titles by a profile's rating limit; the profile
editor says so. On the website a kids profile is kept out of Settings, its data
and signing in a TV, and the server refuses it anything that would loosen its
own limits (kids rules included) or add a profile.

The Stremio addon keeps its per-profile secret URL, which already works.

## Choosing a backend

Settings → Account starts with a **Sync** row with two choices:

| Choice | What syncs | Set up with |
| --- | --- | --- |
| **Local only** (default) | Nothing; everything stays on the TV | — |
| **Fetch website** | Everything in the table above | Pairing by code or QR (#91) |

Choosing local only unpairs the website and leaves everything on the TV as it
is. A TV that never made the choice uses the website while it is paired.

### The Supabase path: removed

Fetch used to keep a second backend, a self-hosted server speaking the Supabase
protocol inherited from NuvioTV, mainly for watch party. Release builds never
pointed it anywhere, nobody used it, and every sync change had to be made twice,
so it was removed with watch party, the Supabase sign-in and the Supabase and
Ktor dependencies (#427). A TV that had chosen it reads as local only.

## Plan

Each phase ships on its own and leaves both halves working.

1. **One repository** *(this change)*. Cataloggy moved into `web/` with its
   history, CI runs from the root, and the website calls itself Fetch. Its
   env vars, addon ID, catalog IDs, database name and storage keys were
   renamed from `cataloggy` to `fetch` before the website's first deployment.
2. ~~**Lists on the TV with no new code.**~~ *Dropped.* Installing the website's
   addon on the TV was the way to get lists there before native list sync; a
   paired TV now reads them through the **Fetch website** library source (#108),
   so the first-run Server step doesn't offer the addon.
3. **Device pairing** *(done, #91)*. `POST /devices/pair/start` and friends on
   the API, Settings → TVs & Devices on the website, the QR flow on the TV.
4. **A sync interface on the TV** *(done, #203)*. The per-domain services go
   through `SyncBackend`, and the backend is picked in Settings → Account.
5. **Fetch website backend, watch state first** *(done, #108)*. The website is a
   tracking provider on a paired TV, next to Trakt, Simkl and MDBList: scrobbles,
   resume points and watched marks go to it, and Continue Watching, watched
   badges and the Library read from it when it is the chosen source.
6. **Addons, settings and home rows** *(done, #92–#98, #150)*. API resources and
   website pages for each profile's addons, Home rows and TV settings. This is
   where the website became the place you manage the TV.
7. **Decide the Supabase path** *(done, #203, #427)*. Kept at first, then removed;
   see [above](#the-supabase-path-removed).

Phases 2 to 7 were tracked in #4, then #203; all are done or dropped.

## Working in `web/`

Run every `pnpm` command from `web/`. Its README covers setup, Docker Compose
and configuration; `web/docs/api.md` documents every endpoint. CI for it lives
in `.github/workflows/web-*.yml` and only runs when `web/` changes. Website
releases are tagged `web-vX.Y.Z`, separate from the APK's `vX.Y.Z`.
