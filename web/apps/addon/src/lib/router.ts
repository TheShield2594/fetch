import type {
  FastifyInstance,
  RawReplyDefaultExpression,
  RawRequestDefaultExpression,
  RawServerDefault,
  RouteGenericInterface,
  RouteHandlerMethod,
} from "fastify";
import { PROFILE_ROUTE_PREFIX, SIGNED_PROFILE_ROUTE_PREFIX } from "./profile.js";

export type AddonHandler<R extends RouteGenericInterface> = RouteHandlerMethod<
  RawServerDefault,
  RawRequestDefaultExpression,
  RawReplyDefaultExpression,
  R
>;

/**
 * Registers a route three times: bare, under the per-profile prefix, and under
 * the signed per-profile prefix.
 *
 * Every Stremio-facing route is served all three ways, so old installs keep
 * working while new ones pin a profile with a key — see `lib/profile.ts`, which
 * also decides whether the first two are answered at all. A route module takes
 * one of these rather than the Fastify instance, so it cannot register a route
 * that exists at only some of the addresses.
 */
export type AddonRouter = {
  get: <R extends RouteGenericInterface>(path: string, handler: AddonHandler<R>) => void;
  post: <R extends RouteGenericInterface>(path: string, handler: AddonHandler<R>) => void;
};

export const addonRouter = (app: FastifyInstance): AddonRouter => ({
  get: (path, handler) => {
    app.get(path, handler);
    app.get(`${PROFILE_ROUTE_PREFIX}${path}`, handler);
    app.get(`${SIGNED_PROFILE_ROUTE_PREFIX}${path}`, handler);
  },
  post: (path, handler) => {
    app.post(path, handler);
    app.post(`${PROFILE_ROUTE_PREFIX}${path}`, handler);
    app.post(`${SIGNED_PROFILE_ROUTE_PREFIX}${path}`, handler);
  },
});
