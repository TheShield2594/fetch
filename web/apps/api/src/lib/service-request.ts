import type { FastifyRequest } from "fastify";
import { deriveServiceToken, matchesServiceToken, SERVICE_TOKEN_HEADER } from "@fetch/shared";

// The service token singles out the add-on service. It is derived from
// `API_TOKEN` and never given to a browser, so a request carrying it is the
// add-on rather than any other holder of the shared token or a session.
//
// That distinction is what lets routes exist for the addon alone — reading the
// RPDB key, checking a signed add-on URL's key — without those routes also
// being reachable by every holder of the shared token or a session, an XSS'd
// tab included.
//
// Since #637 it is also all the add-on holds. `FETCH_ADDON_KEY` carries it, and
// sent without a bearer it authenticates the add-on principal on the routes in
// `lib/addon-scope.ts` (see `verifyToken`). An add-on still configured with
// `API_TOKEN` sends both, and is the operator as before.
//
// Derived on demand rather than at import so a test (or a rotation) that
// changes `API_TOKEN` is seen, and memoised so the HMAC is not recomputed per
// request.
let derived: { apiToken: string; serviceToken: string } | null = null;

const expectedServiceToken = (): string | null => {
  const apiToken = process.env.API_TOKEN;
  if (!apiToken) return null;
  if (derived?.apiToken !== apiToken) {
    derived = { apiToken, serviceToken: deriveServiceToken(apiToken) };
  }
  return derived.serviceToken;
};

/** Whether this request proves it comes from the addon service. */
export const isServiceRequest = (request: FastifyRequest): boolean =>
  matchesServiceToken(request.headers[SERVICE_TOKEN_HEADER], expectedServiceToken());
