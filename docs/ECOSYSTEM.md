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
(`core/sync/backend/`), with two implementations: `FetchWebsiteSyncBackend` for
the website and `SupabaseSyncBackend` for the Supabase protocol inherited from
NuvioTV. `SyncBackends` says which one is in use, from the choice in Settings →
Account (see [Choosing a backend](#choosing-a-backend)). Watch progress, watched
items and the library are the exception: the website carries them as a tracking
provider (`data/website/FetchWebsiteTracking.kt`), while Supabase has its own
delta sync, so the interface names the route (`features.watchStateProvider`)
rather than moving them. Each domain maps onto the website like this:

| TV sync service | Website today | Gap |
| --- | --- | --- |
| `ProfileSyncService` | `/profiles` with colour, avatar and parental limits; `/devices/me/profiles` maps them to the TV's slots; PINs checked by `/profiles/:id/verify` | None — the TV uses it when paired (#96) |
| `WatchProgressSyncService` | `/playback`, `/scrobble/*` (resume points) | None — while paired the website is a tracking provider (`data/website/FetchWebsiteTracking.kt`): scrobbles and resume points go to it, and Continue Watching reads from it when chosen as the watch progress source |
| `WatchedItemsSyncService` | `/watch`, `/watch/state`, `/series/progress` | None — watched marks go to the website, and badges and Next Up read `/watch/state` |
| `LibrarySyncService` | `/lists` (watchlist, collection, custom) | None — the **Fetch website** library source shows and edits the website's lists |
| `AddonSyncService` | `/profiles/:id/addons`, Settings → TV → TV Addons | None — the TV uses it when paired |
| `ProfileSettingsSyncService` | `/profiles/:id/settings/tv`, Settings → TV → TV Playback Settings | None — the TV uses it when paired |
| `HomeCatalogSettingsSyncService` | `/profiles/:id/home-layout`, Settings → TV → Home Rows | None — the TV uses it when paired |
| `CollectionSyncService` | `/collections`, plus a `fetch` source for the website's own lists | None — the TV uses it when paired (#95) |
| `ProviderCredentialSyncService` | Trakt, TMDB, OMDb, RPDB keys | Decide what should leave the TV at all; MDBList and Simkl stay on the device for now |
| Episode queue (#48) | Rides in the settings blob (`EpisodeQueueStore.FEATURE`), so `/profiles/:id/settings/tv` | None |
| Watch party | — | Supabase only. Not planned on the website; the Supabase backend stays for it (see below) |

## Auth

The website uses one shared `API_TOKEN` plus per-profile PIN tokens. That is
fine for a browser on the LAN but not for a TV: a token typed or scanned into
every device can't be revoked one device at a time. A TV signs in by **device
pairing** instead (#91):

1. Settings → Account → Sync → Fetch website on the TV (or the first-run Server
   step) asks for the API address and shows a short code, plus a QR code for the
   website's `/pair` page when the API knows the website's address
   (`FETCH_WEB_PUBLIC`).
2. Signed in on the website, you scan it or type the code under Settings → TVs & Devices.
3. The API issues that TV its own token, listed and revocable on the website. A
   revoked TV gets a bearer 401 on its next request and says it was signed out.

While paired, every domain above goes to the website: profiles (colour, avatar,
parental limit), and each profile's collections, addons, Home rows and settings.
Parental limits are written into the TV's own restriction store, so they keep
applying offline.

The Stremio addon keeps its per-profile secret URL, which already works.

## Choosing a backend

Settings → Account starts with a **Sync** row with three choices:

| Choice | What syncs | Set up with |
| --- | --- | --- |
| **Local only** (default) | Nothing; everything stays on the TV | — |
| **Fetch website** | Everything in the table above | Pairing by code or QR (#91) |
| **Supabase-compatible server** | The same domains, plus watch party, provider credentials and media servers | A server baked into the build, or one connected from the server options |

Only one backend is connected at a time: switching signs the TV out of the
other one, after asking, and leaves everything on the TV as it is. A TV that
never made the choice keeps working the way it did before there was one: a
paired website first, then a Supabase sign-in, otherwise local only.

### The Supabase path: kept, as an opt-in second backend

Release builds ship with a blank `SUPABASE_URL`, which resolves to
`https://sync.invalid`, and CI doesn't inject `FETCH_SUPABASE_*`, so a shipped
APK never contacts a Supabase server unless a builder bakes one in or someone
connects their own (`docs/cloudflare-migration-audit.md` §3.1). The website now
carries everything the TV syncs except watch party, so the Supabase path costs
nothing for people who don't use it. It stays, behind the same interface,
because:

- **Watch party** (`core/party/`) has no website equivalent and none is planned.
- **People who self-host the NuvioTV-style server** keep working without
  migrating.
- **Profile PINs** already work against both: the website checks its own
  (`FetchWebsiteProfilePins`) and Supabase checks its own
  (`ProfileSyncService.setProfilePin` and friends).

Removing it later means deleting `SupabaseSyncBackend`, the Supabase-only
services it names, and the option in the picker. No sync service has to change.

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
   through `SyncBackend`, with the website and Supabase as its two
   implementations, and the backend is picked in Settings → Account.
5. **Fetch website backend, watch state first** *(done, #108)*. The website is a
   tracking provider on a paired TV, next to Trakt, Simkl and MDBList: scrobbles,
   resume points and watched marks go to it, and Continue Watching, watched
   badges and the Library read from it when it is the chosen source.
6. **Addons, settings and home rows** *(done, #92–#98, #150)*. API resources and
   website pages for each profile's addons, Home rows and TV settings. This is
   where the website became the place you manage the TV.
7. **Decide the Supabase path** *(done, #203)*. Kept as an opt-in second backend;
   see [above](#the-supabase-path-kept-as-an-opt-in-second-backend).

Phases 2 to 7 were tracked in #4, then #203; all are done or dropped.

## Working in `web/`

Run every `pnpm` command from `web/`. Its README covers setup, Docker Compose
and configuration; `web/docs/api.md` documents every endpoint. CI for it lives
in `.github/workflows/web-*.yml` and only runs when `web/` changes. Website
releases are tagged `web-vX.Y.Z`, separate from the APK's `vX.Y.Z`.
