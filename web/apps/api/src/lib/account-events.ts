import type { FastifyRequest } from "fastify";
import { prisma } from "./prisma.js";

/**
 * The account activity log: what Settings → Account → Recent activity shows.
 *
 * Writes are best-effort. A log that can't be written must never turn a
 * sign-in or a password change into a failure, so `recordAccountEvent` swallows
 * its own errors (and logs them where it can).
 */

export type AccountEventKind =
  | "sign_in"
  | "sign_in_failed"
  | "password_changed"
  | "password_reset"
  | "pin_changed"
  | "pin_removed"
  | "pin_cleared"
  | "tv_paired"
  | "tv_signed_out"
  | "session_revoked"
  | "signed_out_everywhere"
  | "username_changed"
  | "admin_granted"
  | "admin_transferred"
  | "addon_url_rotated"
  | "totp_enabled"
  | "totp_disabled"
  | "totp_failed"
  | "recovery_code_used";

/** How long an event is kept. */
export const ACCOUNT_EVENT_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
/** The most a reader is shown at once. */
export const ACCOUNT_EVENT_PAGE = 50;

const MAX_USER_AGENT_LENGTH = 300;
const MAX_DETAIL_LENGTH = 200;

/** Where a request came from, as a session or event records it. */
export type RequestOrigin = { userAgent: string | null; ipAddress: string | null };

export const requestOrigin = (request: FastifyRequest | undefined): RequestOrigin => {
  if (!request) return { userAgent: null, ipAddress: null };
  const raw = request.headers["user-agent"];
  const userAgent = (Array.isArray(raw) ? raw[0] : raw)?.trim().slice(0, MAX_USER_AGENT_LENGTH) || null;
  return { userAgent, ipAddress: request.ip || null };
};

export const recordAccountEvent = async (
  accountId: string,
  kind: AccountEventKind,
  options: { request?: FastifyRequest; detail?: string | null } = {}
): Promise<void> => {
  try {
    const origin = requestOrigin(options.request);
    await prisma.accountEvent.create({
      data: {
        accountId,
        kind,
        detail: options.detail ? options.detail.slice(0, MAX_DETAIL_LENGTH) : null,
        ...origin,
      },
    });
  } catch (error) {
    options.request?.log.warn({ err: error, kind }, "Could not record an account event");
  }
};

export const listAccountEvents = async (accountId: string) => {
  const events = await prisma.accountEvent.findMany({
    where: { accountId },
    orderBy: { createdAt: "desc" },
    take: ACCOUNT_EVENT_PAGE,
  });
  return events.map((event) => ({
    id: event.id,
    kind: event.kind,
    detail: event.detail,
    ipAddress: event.ipAddress,
    userAgent: event.userAgent,
    createdAt: event.createdAt.toISOString(),
  }));
};

/** Deletes events past the retention window. Run with the other auth clean-up. */
export const sweepOldAccountEvents = async (now: Date = new Date()): Promise<number> => {
  const { count } = await prisma.accountEvent.deleteMany({
    where: { createdAt: { lte: new Date(now.getTime() - ACCOUNT_EVENT_RETENTION_MS) } },
  });
  return count;
};
