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

The TV talks to the API directly for anything it writes (progress, watched
state, settings). Lists can reach the TV two ways: through the sync client, or,
working today with no TV changes, by installing the website's Stremio addon
on the TV, which exposes each list as a catalog row.

## What the TV syncs, and what the website already has

The TV's sync layer (`app/src/main/java/com/fetch/tv/core/sync/`) is split by
domain and currently speaks the Supabase protocol inherited from NuvioTV. Each
domain maps onto the website like this:

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
| Watch party | — | Not planned; stays Supabase-only or is removed (#4) |

## Auth

The website uses one shared `API_TOKEN` plus per-profile PIN tokens. That is
fine for a browser on the LAN but not for a TV: a token typed or scanned into
every device can't be revoked one device at a time. A TV signs in by **device
pairing** instead (#91):

1. Settings → Account → Fetch website on the TV asks for the API address and
   shows a short code, plus a QR code for the website's `/pair` page when the API
   knows the website's address (`FETCH_WEB_PUBLIC`).
2. Signed in on the website, you scan it or type the code under Settings → TVs & Devices.
3. The API issues that TV its own token, listed and revocable on the website. A
   revoked TV gets a bearer 401 on its next request and says it was signed out.

While paired, the TV's profile and collection sync go to the website instead of
the Supabase sync server: profiles (colour, avatar, parental limit) and each
profile's collections. Parental limits are written into the TV's own restriction
store, so they keep applying offline.

The Stremio addon keeps its per-profile secret URL, which already works.

## Plan

Each phase ships on its own and leaves both halves working.

1. **One repository** *(this change)*. Cataloggy moved into `web/` with its
   history, CI runs from the root, and the website calls itself Fetch. Its
   env vars, addon ID, catalog IDs, database name and storage keys were
   renamed from `cataloggy` to `fetch` before the website's first deployment.
2. **Lists on the TV with no new code.** Document installing the website's addon
   on the TV, and have the TV's first-run Server step offer it.
3. **Device pairing** *(done, #91)*. `POST /devices/pair/start` and friends on
   the API, Settings → TVs & Devices on the website, the QR flow on the TV.
4. **A sync interface on the TV.** Put the per-domain services behind a backend
   interface, with Supabase as one implementation (#4: "Define a small sync
   interface"), and pick the backend in Settings → Account.
5. **Fetch website backend, watch state first** *(done, #108)*. The website is a
   tracking provider on a paired TV, next to Trakt, Simkl and MDBList: scrobbles,
   resume points and watched marks go to it, and Continue Watching, watched
   badges and the Library read from it when it is the chosen source.
6. **Addons, settings and home rows.** New API resources for the gaps above, and
   website pages to edit them. This is where the website becomes the place you
   manage the TV.
7. **Decide the Supabase path.** Keep it as a second backend or remove it, once
   profile PINs and everything else it carries work against the website.

Phases 2 to 7 are tracked in #4.

## Working in `web/`

Run every `pnpm` command from `web/`. Its README covers setup, Docker Compose
and configuration; `web/docs/api.md` documents every endpoint. CI for it lives
in `.github/workflows/web-*.yml` and only runs when `web/` changes. Website
releases are tagged `web-vX.Y.Z`, separate from the APK's `vX.Y.Z`.
