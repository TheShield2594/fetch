# Deploying the website to watchfetch.tv

The hosted Fetch website runs entirely on Cloudflare plus a managed Postgres:

| Piece | Where | What it is |
|---|---|---|
| Web app (PWA) | Workers static assets | `web/apps/web`, built at deploy time |
| API | One Cloudflare Container, always on, at `https://watchfetch.tv/api` | `web/apps/api`, from its usual Dockerfile |
| Database | Managed Postgres (Neon recommended) | The same schema and migrations as self-hosting |

The Worker that ties them together, its config and the build step are in
`web/apps/cloudflare`. GitHub Actions deploys it (`.github/workflows/web-deploy-cloudflare.yml`).

> **This is one private instance for now.** It's protected by a single access
> token, like a self-hosted install. Public sign-up needs user accounts, which
> are the next piece of work. The APK keeps shipping from GitHub releases and is
> not affected.

## One-time setup

### 1. Create the database

1. Sign up at [neon.com](https://neon.com) and create a project. Pick the
   region closest to you; Postgres 16 or later.
2. Open **Connect** and copy the connection string with **Connection pooling
   turned off** (the direct one, without `-pooler` in the host). It looks like
   `postgresql://user:password@ep-xxxx.region.aws.neon.tech/neondb?sslmode=require`.

On the free plan the database sleeps after 5 idle minutes and gets 100
compute-hours a month. That's enough while Trakt isn't connected: the deploy
turns the 5-minute Trakt poll off until a Trakt app is configured, because
that poll alone would keep the database awake around the clock. Once you use
Trakt, expect to need Neon's paid Launch plan (billed per hour of use, no
monthly minimum), or set `TRAKT_POLL_INTERVAL_SEC` higher.

Any other Postgres 16+ works too, as long as it's reachable from the internet
over TLS.

### 2. Upgrade Cloudflare to Workers Paid

Containers need the **Workers Paid** plan ($5/month). In the dashboard, go to
**Workers & Pages → Plans**.

Expected cost: $5/month for the plan, plus roughly $7/month for the API
container running all month (1 GiB of memory is billed for as long as it
runs; CPU only when used). Check **Workers & Pages → Containers** for actual
usage after the first week.

### 3. Free up the domain's apex

The Worker attaches to `watchfetch.tv` as a **Custom Domain**, which makes
Cloudflare create the DNS record and certificate itself. That fails if the
apex already has an `A`, `AAAA` or `CNAME` record. Delete any existing apex
record under **DNS → Records** first. Subdomains like `www` aren't touched.

### 4. Create a Cloudflare API token

1. **Manage account → Account API tokens → Create Token**.
2. Start from the **Edit Cloudflare Workers** template.
3. Scope it to your account, and the zone to `watchfetch.tv`.
4. Copy the token. Also copy your **Account ID** (shown on the account home page).

If the first deploy fails with a permissions error about containers, edit the
token and add **Containers: Edit**.

### 5. Add the GitHub secrets

In the repository, go to **Settings → Secrets and variables → Actions**.

Repository **secrets**:

| Secret | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | the token from step 4 |
| `CLOUDFLARE_ACCOUNT_ID` | your account ID |
| `DATABASE_URL` | the Neon connection string from step 1 |
| `API_TOKEN` | a long random string: `openssl rand -hex 32`. **Save it somewhere safe.** It's what you sign in with, and every stored key is encrypted with it, so changing it later means re-entering them. |

Optional secrets, each turning on one integration. Leave out any you don't use.

| Secret | Turns on |
|---|---|
| `TMDB_API_KEY` | Metadata and posters (can also be set later in the app under Settings → TMDB) |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | Trakt sync. Set the Trakt app's redirect URI to `https://watchfetch.tv/api/trakt/oauth/callback`. |
| `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET` | IGDB game search |
| `STEAM_API_KEY`, `STEAM_ID` | Steam library sync |
| `WEBHOOK_SECRET` | Plex, Jellyfin and Emby webhooks, at `https://watchfetch.tv/api/webhooks/<plex\|jellyfin\|emby>` |
| `SENTRY_DSN` | Error reporting |

Repository **variable** (the Variables tab): `CLOUDFLARE_DEPLOY` = `true`. Until it
is set, pushes to `main` don't try to deploy, so `main` stays green before the
secrets exist.

### 6. Deploy

Merge to `main` (the workflow has to be on the default branch before it can
run). With `CLOUDFLARE_DEPLOY` set, that push deploys. You can also run it any
time from **Actions → Web Deploy (Cloudflare) → Run workflow**.

Each deploy:

1. Runs the full CI suite (`web-verify.yml`), including every migration against a throwaway Postgres.
2. Builds the web app for `https://watchfetch.tv` and writes its `config.js` and security headers.
3. Applies pending migrations to your real database.
4. Builds the API image and deploys the Worker, the site and the container.

The first deploy takes a while because the image is built from scratch. The API
container can need a minute or two to start after a deploy. A cron trigger checks
on it every 5 minutes and starts it if it isn't running.

### 7. First visit

Open `https://watchfetch.tv`. The setup wizard asks for the access token: paste
the `API_TOKEN` from step 5. To check the API directly,
`https://watchfetch.tv/api/health/ready` should report the database as `ok`.

## What's not there yet

- **Sign-up and accounts.** Next. Until then, anyone with the `API_TOKEN` has full access.
- **The Stremio addon service.** `/addon/*` answers 503. Its install URL has no
  secret (see `docs/cloudflare-migration-audit.md` §10), so it stays off the
  internet until that's fixed. The API's own catalog-only addon does work. Its
  per-profile secret URL is returned as `stremioManifestUrl` by `GET /api/addon/config`.
- **Integrations on your home network** (a local AI server, ntfy, Gotify, Jellyseerr
  at a `192.168.x.x` address). Cloudflare can't reach them. Use public URLs for these.

## When something's wrong

- **Worker and API logs:** **Workers & Pages → fetch-web → Logs**. The container's
  output is under the same Worker's **Containers** tab.
- **Deploy failed at "Apply database migrations":** `DATABASE_URL` is wrong or
  the database isn't reachable. Use Neon's direct (non-pooled) connection string.
- **Deploy failed creating the custom domain:** something still exists at the
  `watchfetch.tv` apex in DNS (step 3), or the token isn't scoped to that zone.
- **The site loads but says it can't reach the API:** the container is still
  starting. Wait a couple of minutes and check `/api/health`.
