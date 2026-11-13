# AniList and MyAnimeList

Fetch can keep an [AniList](https://anilist.co) or [MyAnimeList](https://myanimelist.net) list
up to date as you watch anime, and can copy either list into Fetch once. This page covers
switching the integrations on in a build, then signing in and using them on the TV.

Both are **scrobble targets**: they receive progress, but they are never the source of truth
for Continue Watching or watched marks (that stays Fetch Sync, Trakt, Simkl, MDBList or the
Fetch website, under Settings → Tracking → Watch progress).

## Part 1: switching them on in a build

Each integration is hidden until its client ID is in the build. You register an app with the
service once; every TV running that build then signs in with it.

### AniList

1. Go to [anilist.co/settings/developer](https://anilist.co/settings/developer) and choose
   **Create New Client**.
2. Give it a name (for example "Fetch").
3. Set the **Redirect URL** to exactly:

   ```
   https://anilist.co/api/v2/oauth/pin
   ```

   That is AniList's own page that shows the sign-in token for the viewer to copy. Fetch uses
   AniList's implicit sign-in, so it needs no client secret and no page of its own.
4. Save, and copy the client **ID** (a number).
5. Put it in `local.properties` for local builds:

   ```properties
   ANILIST_CLIENT_ID=12345
   ```

   or as the `ANILIST_CLIENT_ID` repository secret (Settings → Secrets and variables →
   Actions) for the Release workflow. An environment variable of the same name also works and
   takes precedence.

### MyAnimeList

MyAnimeList's sign-in redirects the browser to a page you host, with a one-time code. This
repository ships that page in [`site/mal-callback.html`](../site/mal-callback.html), and the
**Pages** workflow (`.github/workflows/pages.yml`) publishes it.

1. **Publish the callback page.** In the repository on GitHub, open Settings → Pages and set
   **Source** to **GitHub Actions**. The Pages workflow runs on the next push to `main` that
   touches `site/`, or run it by hand from the Actions tab (Pages → Run workflow). The page is
   then at:

   ```
   https://<owner>.github.io/<repo>/mal-callback.html
   ```

   For this repository that is `https://theshield2594.github.io/fetch/mal-callback.html`, the
   default `MAL_REDIRECT_URI`. Open it once in a browser: with no code in the address it should
   say "Sign-in didn't finish", which means it is published.
2. **Register the app.** Go to [myanimelist.net/apiconfig](https://myanimelist.net/apiconfig)
   and choose **Create ID**:
   - **App Type:** `other`. That gives a client ID with no secret, which is what Fetch expects.
     Don't choose `web`: it requires a secret Fetch never sends.
   - **App Redirect URL:** the callback page's address from step 1, exactly.
   - Fill in the remaining required fields (name, description, homepage) as you like.
3. Copy the **Client ID**.
4. Add it, and the redirect address if yours isn't the default, to `local.properties`:

   ```properties
   MAL_CLIENT_ID=0123456789abcdef0123456789abcdef
   # Only for a fork or another Pages address; must match the App Redirect URL exactly.
   MAL_REDIRECT_URI=https://example.github.io/fetch/mal-callback.html
   ```

   or as `MAL_CLIENT_ID` and `MAL_REDIRECT_URI` repository secrets. A blank
   `MAL_REDIRECT_URI` falls back to this repository's Pages address.

### Matching shows listed by IMDb ID

Titles from anime catalogs (`anilist:`, `mal:` or `kitsu:` IDs, as the Kitsu addon serves them)
are matched directly. A show listed by IMDb ID, as Cinemeta lists most anime, is matched through
Simkl's ID lookup, which finds the AniList or MyAnimeList entry that owns the season and that
entry's own episode number. That lookup uses the build's `SIMKL_CLIENT_ID` (no Simkl sign-in
needed). Without it, only anime-catalog titles update AniList and MyAnimeList.

### Checking a build

On the TV, Settings → Tracking → Accounts should list **AniList** and **MyAnimeList** under
Trakt, Simkl and MDBList. On the phone page (Settings → Configure from phone → Accounts), each
has its own section. A service missing from both was built without its client ID.

## Part 2: signing in and using them

### Signing in

Neither service can sign in on a TV directly, so both sign in from your phone. Make sure the TV
is on the profile you want to connect: every profile has its own AniList and MyAnimeList
account.

1. On the TV, open **Settings → Configure from phone** and scan the code with your phone.
2. On your phone, open **Accounts**.
3. Then:
   - **AniList:** tap **Sign in to AniList**, sign in and approve Fetch. AniList shows a long
     token. Copy all of it, go back to the Fetch page, paste it into **Token from AniList** and
     tap **Connect**.
   - **MyAnimeList:** tap **Sign in to MyAnimeList**, sign in and tap **Allow**. You land on a
     Fetch page showing a sign-in code with a **Copy code** button. Copy it, go back to the Fetch
     page, paste it into **Code from the sign-in page** and tap **Connect**. Do this within a
     few minutes; the code expires quickly.
4. The page says "Connected as *your name*", and the TV shows the account as connected under
   Settings → Tracking → Accounts.

To sign out, open the account on the TV (Settings → Tracking → Accounts → AniList or
MyAnimeList) and choose **Disconnect**.

### What happens as you watch

- When you finish an anime episode (80% watched, the same rule Fetch uses for Trakt), the
  matching entry's progress moves to that episode.
- Progress only ever moves forward: rewatching an earlier episode changes nothing.
- On the last episode the entry is marked **Completed**. An entry that is already completed is
  left alone unless it is marked as a rewatch on the service.
- An entry that was Planning, Paused or Dropped becomes Watching (Current) when you finish an
  episode of it.
- A title Fetch can't match to an entry is skipped; nothing is guessed.
- Updates go to the account of the profile that watched, even if someone switches profiles
  before the update is sent.

### Bringing your list into Fetch

Settings → Tracking → **Import from another source**, then choose **AniList** or
**MyAnimeList** (each appears once connected). This row is in the Sources card, so it shows in
Advanced mode.

- An entry at progress 7 marks episodes 1 to 7 as watched. A film you've completed is marked
  watched.
- Planning entries are skipped.
- Titles are filed under their MyAnimeList ID (`mal:…`), which is what anime catalogs use, or
  their AniList ID when there is none.
- Neither service keeps resume points, so none are imported. The result screen says so.
- Marks are only ever added. Running the import again is safe.

### How long a sign-in lasts

- **AniList:** about a year. AniList can't renew it, so after that the account shows as
  disconnected and you sign in again.
- **MyAnimeList:** the sign-in renews itself before it runs out. If MyAnimeList refuses the
  renewal (for example after you revoke Fetch on the MyAnimeList site), the account shows as
  disconnected and you sign in again.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| No AniList or MyAnimeList row on the TV or phone page | The build has no `ANILIST_CLIENT_ID` / `MAL_CLIENT_ID`. See Part 1. |
| AniList: "didn't accept that token" | Copy the whole token from AniList's page, with nothing before or after it, and paste it again. If it still fails, tap **Sign in to AniList** again for a new one. |
| MyAnimeList shows an error page instead of the sign-in, mentioning the redirect | The App Redirect URL on myanimelist.net/apiconfig doesn't exactly match `MAL_REDIRECT_URI`. |
| MyAnimeList: the callback page is a 404 | Pages isn't on, or the Pages workflow hasn't run yet. See Part 1, step 1. |
| MyAnimeList: "didn't accept that code" | The code expired or was already used. Tap **Sign in to MyAnimeList** again and paste the new code straight away. |
| Episodes of a show listed by IMDb ID never update the list | The build has no `SIMKL_CLIENT_ID`, or Simkl can't place that season. Titles from an anime catalog (the Kitsu addon) are matched without it. |
| Progress moved for the wrong season | Report it with the show and episode: the season was matched through Simkl's mapping, and the mapping is the likely cause. |

## Privacy

What each service receives is in [PRIVACY.md](../PRIVACY.md). In short: the sign-in token or
code is pasted on your phone and sent only to your TV; the TV talks to AniList and MyAnimeList
directly; MyAnimeList sends your browser to the callback page with the one-time code in its
address, so GitHub Pages, which serves the page, receives that code with the request, but the
page itself sends it nowhere else, and the code alone can't sign anyone in; and IMDb-listed
shows are looked up by IMDb ID with Simkl's public ID service.

## For developers

- Code: `app/src/main/java/com/fetch/tv/data/anilist/`, `data/mal/` and the shared
  `data/anime/` (entry resolver, progress rule, list types), plus
  `core/tracking/AnimeListImport.kt`.
- Phone sign-in: `core/server/PhoneHubController.kt` (`connectAniList`, `connectMal`, the MAL
  PKCE session) and the Accounts page in `core/server/PhoneHubWebPage.kt`.
- Tests: `app/src/test/java/com/fetch/tv/data/anilist/`, `data/mal/`, `data/anime/`.
