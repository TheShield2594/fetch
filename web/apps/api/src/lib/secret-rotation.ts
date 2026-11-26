import { prisma } from "./prisma.js";
import {
  SECRET_CONTEXT,
  decryptSecretUnder,
  encryptSecretUnder,
  isEncryptedSecret,
  kvSecretContext,
  secretLookupKeyUnder,
} from "./secret-box.js";

// `fetch rotate-key`: moves every stored credential from one encryption root to
// another (#638). Before this, the only way to change the key was to lose every
// credential and re-enter it, so nobody did.
//
// It walks the same columns as the startup sweep (`secret-migration.ts`), plus
// every KV row that holds ciphertext, whatever its key: a KV credential added
// later is carried across without this list having to learn about it.
//
// All of it runs in one transaction, and nothing is written until every value
// has been opened under the old root. A wrong `--old`, or one credential that
// was already unreadable, leaves the database exactly as it was and names what
// stopped it; half the rows under one key and half under the other would be
// worse than either.

export class RotationError extends Error {
  constructor(
    message: string,
    readonly unreadable: string[] = []
  ) {
    super(message);
  }
}

export type RotationReport = { reencrypted: number };

type Write = () => Promise<unknown>;

/**
 * Re-encrypts every stored credential from `oldRoot` to `newRoot`. Plaintext
 * left over from before encryption at rest is encrypted under `newRoot` on the
 * way. Throws {@link RotationError}, having written nothing, when any value
 * can't be opened under `oldRoot`.
 */
export const rotateStoredSecrets = async (oldRoot: string, newRoot: string): Promise<RotationReport> =>
  prisma.$transaction(
    async (tx) => {
      const unreadable: string[] = [];
      const writes: Write[] = [];

      /** The value under the new root, or null (and noted) when the old one can't open it. */
      const reencrypt = (context: string, label: string, stored: string): string | null => {
        const plain = decryptSecretUnder(oldRoot, context, stored);
        if (plain === null) {
          unreadable.push(label);
          return null;
        }
        return encryptSecretUnder(newRoot, context, plain);
      };

      const kvRows = await tx.kV.findMany({ where: { value: { startsWith: "cgy1." } } });
      for (const row of kvRows) {
        if (!isEncryptedSecret(row.value)) continue;
        const value = reencrypt(kvSecretContext(row.key), row.key, row.value);
        if (value !== null) {
          writes.push(() => tx.kV.update({ where: { key: row.key }, data: { value, updatedAt: new Date() } }));
        }
      }

      for (const token of await tx.traktToken.findMany()) {
        const accessToken = reencrypt(SECRET_CONTEXT.traktAccessToken, "Trakt access token", token.accessToken);
        const refreshToken = reencrypt(SECRET_CONTEXT.traktRefreshToken, "Trakt refresh token", token.refreshToken);
        if (accessToken !== null && refreshToken !== null) {
          writes.push(() => tx.traktToken.update({ where: { id: token.id }, data: { accessToken, refreshToken } }));
        }
      }

      for (const channel of await tx.notificationChannel.findMany()) {
        const url = reencrypt(SECRET_CONTEXT.notificationChannelUrl, `Notification channel URL (${channel.name})`, channel.url);
        const token =
          channel.token === null
            ? null
            : reencrypt(SECRET_CONTEXT.notificationChannelToken, `Notification channel token (${channel.name})`, channel.token);
        if (url !== null && (channel.token === null || token !== null)) {
          writes.push(() => tx.notificationChannel.update({ where: { id: channel.id }, data: { url, token } }));
        }
      }

      // The lookup key is an HMAC under the root as well, so it moves with the URL.
      for (const addon of await tx.profileAddon.findMany()) {
        const context = SECRET_CONTEXT.profileAddonManifestUrl;
        const plain = decryptSecretUnder(oldRoot, context, addon.manifestUrl);
        if (plain === null) {
          unreadable.push(`Add-on URL (${addon.name})`);
          continue;
        }
        writes.push(() =>
          tx.profileAddon.update({
            where: { id: addon.id },
            data: {
              manifestUrl: encryptSecretUnder(newRoot, context, plain),
              manifestUrlKey: secretLookupKeyUnder(newRoot, context, plain),
            },
          })
        );
      }

      for (const connection of await tx.profileConnection.findMany()) {
        const credentials = reencrypt(
          SECRET_CONTEXT.profileConnectionCredentials,
          `${connection.provider} sign-in`,
          connection.credentials
        );
        if (credentials !== null) {
          writes.push(() => tx.profileConnection.update({ where: { id: connection.id }, data: { credentials } }));
        }
      }

      const accounts = await tx.account.findMany({
        where: { OR: [{ totpSecret: { not: null } }, { totpPendingSecret: { not: null } }] },
        select: { id: true, username: true, totpSecret: true, totpPendingSecret: true },
      });
      for (const account of accounts) {
        const totpSecret =
          account.totpSecret === null
            ? null
            : reencrypt(SECRET_CONTEXT.accountTotpSecret, `Two-factor secret (${account.username})`, account.totpSecret);
        const totpPendingSecret =
          account.totpPendingSecret === null
            ? null
            : reencrypt(
                SECRET_CONTEXT.accountTotpPendingSecret,
                `Two-factor setup in progress (${account.username})`,
                account.totpPendingSecret
              );
        if ((account.totpSecret === null || totpSecret !== null) && (account.totpPendingSecret === null || totpPendingSecret !== null)) {
          writes.push(() => tx.account.update({ where: { id: account.id }, data: { totpSecret, totpPendingSecret } }));
        }
      }

      if (unreadable.length > 0) {
        const nothingOpened = writes.length === 0;
        throw new RotationError(
          nothingOpened
            ? "None of the stored credentials could be decrypted with --old. It is not the key they were saved under: " +
                "that is FETCH_SECRET_KEY if it is set, otherwise API_TOKEN. Nothing was changed."
            : `${unreadable.length} stored credential(s) cannot be decrypted with --old, so nothing was changed. ` +
                "If --old is right, these were already unreadable: re-enter or remove them in Settings, then run this again.",
          unreadable
        );
      }

      for (const write of writes) await write();
      return { reencrypted: writes.length };
    },
    // A large install has a few hundred rows; the default 5 s is for request paths.
    { timeout: 120_000, maxWait: 10_000 }
  );
