import { randomInt } from "node:crypto";
import { prisma } from "./prisma.js";
import { hashPin } from "./pin-hash.js";
import { clearAttempts } from "./attempt-lockout.js";
import { invalidateDeviceCache } from "./device-token.js";
import { invalidateProfileCache } from "./profile.js";
import { deleteOwnerIntegrations } from "./integration-owner.js";
import { deleteProfileConnectionState } from "./profile-connections.js";
import { resetTraktClient } from "./trakt-client.js";

/**
 * Account operations shared by the HTTP routes and the `fetch` recovery
 * command (src/cli.ts), so a password reset or a cleared PIN does the same
 * thing whether the admin did it from Settings or the operator from a shell.
 */

export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 256;
export const PASSWORD_ERROR = `Password must be between ${MIN_PASSWORD_LENGTH} and ${MAX_PASSWORD_LENGTH} characters`;

export const isValidPassword = (value: unknown): value is string =>
  typeof value === "string" && value.length >= MIN_PASSWORD_LENGTH && value.length <= MAX_PASSWORD_LENGTH;

/**
 * A temporary password for someone to type once and then replace: 16
 * characters from an alphabet without look-alikes, about 90 bits.
 */
const TEMP_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const generateTemporaryPassword = (): string =>
  Array.from({ length: 16 }, () => TEMP_ALPHABET[randomInt(TEMP_ALPHABET.length)]).join("");

/**
 * Puts a password someone else chose on an account: the account is signed out
 * everywhere and asked for a new password at its next sign-in. Its TVs stay
 * signed in, because the person who forgot a password still owns those.
 */
export const setTemporaryPassword = async (accountId: string, password: string): Promise<void> => {
  const passwordHash = await hashPin(password);
  await prisma.$transaction([
    prisma.account.update({ where: { id: accountId }, data: { passwordHash, mustChangePassword: true } }),
    prisma.session.deleteMany({ where: { accountId } }),
  ]);
};

/**
 * Turns two-factor sign-in off for an account: its secret, any half-finished
 * setup and its recovery codes all go. For the account itself (with its
 * password and a code), the admin, and `fetch reset-2fa`.
 */
export const removeTotp = async (accountId: string): Promise<void> => {
  await prisma.account.update({
    where: { id: accountId },
    data: {
      totpSecret: null,
      totpPendingSecret: null,
      totpEnabledAt: null,
      totpLastUsedStep: null,
      totpRecoveryCodes: [],
    },
  });
};

/** Removes a profile's PIN, and with it any lockout its wrong guesses earned. */
export const clearProfilePin = async (profileId: string): Promise<void> => {
  await prisma.profile.update({ where: { id: profileId }, data: { pinHash: null } });
  invalidateProfileCache();
  await clearAttempts(`pinattempts:${profileId}`);
};

/** Deletes an account and everything its profiles own, TVs included. */
export const deleteAccountAndData = async (accountId: string): Promise<void> => {
  const profileIds = await prisma.$transaction(async (tx) => {
    // Locking the account row holds off a profile being created for it until
    // this commits (the insert's foreign-key check waits on the lock), so the
    // profiles read next are every profile the delete below takes with it.
    await tx.$executeRaw`SELECT id FROM "Account" WHERE id = ${accountId}::uuid FOR UPDATE`;
    const ids = (await tx.profile.findMany({ where: { accountId }, select: { id: true } })).map((profile) => profile.id);
    // Kept in KV, keyed by id rather than tied to the rows by a foreign key.
    await deleteOwnerIntegrations(tx, accountId);
    await deleteProfileConnectionState(tx, ids);
    await tx.account.delete({ where: { id: accountId } });
    return ids;
  });
  for (const profileId of profileIds) resetTraktClient(profileId);
  invalidateProfileCache();
  // Its TVs went with it; their tokens must stop working now, not when the
  // lookup cache next expires.
  invalidateDeviceCache();
};
