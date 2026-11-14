#!/bin/sh
set -e

API_BASE_JSON=$(VITE_API_BASE="${VITE_API_BASE:-}" node -e 'console.log(JSON.stringify(process.env.VITE_API_BASE))')
ADDON_BASE_JSON=$(VITE_ADDON_BASE="${VITE_ADDON_BASE:-}" node -e 'console.log(JSON.stringify(process.env.VITE_ADDON_BASE))')
SENTRY_DSN_JSON=$(SENTRY_DSN="${SENTRY_DSN:-}" node -e 'console.log(JSON.stringify(process.env.SENTRY_DSN))')
# The landing page at `/` for signed-out visitors; "off" sends them straight to
# the sign-in, as before it existed. Read again by render-serve-json.mjs, which
# only lets the page reach GitHub's API (for the latest release) while it's on.
case "${FETCH_LANDING_PAGE:-on}" in
  off|false|0) LANDING=false ;;
  *) LANDING=true ;;
esac
{
  printf 'window.__FETCH_API_BASE__ = %s;\n' "$API_BASE_JSON"
  printf 'window.__FETCH_ADDON_BASE__ = %s;\n' "$ADDON_BASE_JSON"
  printf 'window.__FETCH_SENTRY_DSN__ = %s;\n' "$SENTRY_DSN_JSON"
  printf 'window.__FETCH_LANDING__ = %s;\n' "$LANDING"
} > /app/dist/config.js

# Lets the TV and desktop apps find the API from the address people open the
# website at: they read this, then check the API's /health. It holds only the
# API base, which config.js already gives every browser that opens the site.
printf '{"service":"web","api":%s}\n' "$API_BASE_JSON" > /app/dist/fetch-server.json

# The CSP's connect-src names the API/addon/Sentry origins this container was
# configured with, so it is rendered here for the same reason config.js is —
# those values only exist at container start.
node /app/scripts/render-serve-json.mjs /app/serve.template.json /app/dist/serve.json

exec "$@"
