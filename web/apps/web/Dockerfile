FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build

WORKDIR /app
RUN npm install -g corepack@latest && corepack enable

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json eslint.config.mjs .npmrc ./
COPY apps/web/package.json ./apps/web/package.json
COPY apps/api/package.json ./apps/api/package.json
COPY apps/addon/package.json ./apps/addon/package.json
COPY packages/shared/package.json ./packages/shared/package.json

RUN pnpm install --frozen-lockfile

COPY apps/web ./apps/web
# The web app imports the API's request and response types from
# @fetch/shared/contracts, which resolves to the package's built dist/.
COPY packages/shared ./packages/shared

RUN pnpm --filter @fetch/shared build
RUN pnpm --filter @fetch/web build

FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS runtime

WORKDIR /app
# Alpine packages are upgraded past whatever the pinned base shipped with
# (musl, zlib, OpenSSL fixes land between node image rebuilds), and the
# package managers the base image bundles are removed: the services only run
# `node` (npm only until `serve` is installed), and npm's own vendored tar and brace-expansion are what image scanners
# flag in these containers.
RUN apk upgrade --no-cache && apk add --no-cache wget && npm install -g serve@14 \
 && npm cache clean --force \
 && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
      /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
      /usr/local/bin/yarn /usr/local/bin/yarnpkg /opt/yarn-*

COPY --from=build /app/apps/web/dist ./dist
# dist/serve.json is rendered from this template at container start — it carries
# a connect-src that depends on runtime env, so it cannot be baked in here.
COPY apps/web/serve.template.json ./serve.template.json
COPY apps/web/scripts/csp.mjs apps/web/scripts/render-serve-json.mjs apps/web/scripts/public-url-env.mjs ./scripts/
# The FETCH_PUBLIC_URL expansion the entrypoint runs: @fetch/shared's compiled
# public-url.ts, which imports nothing, so it runs here without node_modules.
# .mjs because nothing in /app declares "type": "module".
COPY --from=build /app/packages/shared/dist/public-url.js ./scripts/public-url.mjs
# csp.mjs reads the artwork-CDN list from here — the same list the service
# worker in dist/ was built against, so `connect-src` cannot fall behind what
# the worker fetches. Keep the path: the import is relative to scripts/.
COPY apps/web/src/image-cdn-hosts.mjs ./src/image-cdn-hosts.mjs
COPY apps/web/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app
USER app

EXPOSE 7002
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s CMD wget -q --spider http://127.0.0.1:7002/ || exit 1
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["serve", "-s", "dist", "-l", "7002"]
