import { createCipheriv, createDecipheriv, createHash, createHmac, hkdfSync, randomBytes } from "node:crypto";

// Envelope encryption for the credentials Fetch holds on your behalf: the
// Trakt access/refresh pair, per-channel notification tokens and URLs, addon
// manifest URLs, profile connection sign-ins, and the KV rows carrying the
// TMDB/OMDB/RPDB keys, the AI provider config (Authorization header and all)
// and the VAPID private key.
//
// The threat this addresses is a *copy of the database*: a dump on a NAS, the
// `pgdata` volume, a backup that outlived the machine it came from. Postgres is
// bound to loopback and every write path is behind `API_TOKEN`, so reading the
// database in the first place already takes host access — what this removes is
// the step from "has the file" to "has every credential in it".
//
// It is explicitly not protection from an attacker already running as the API
// process. The key derives from a secret that process must hold to serve a
// single request, so anything that can read the environment can read the
// secrets. Nothing here pretends otherwise; the property bought is that a dump
// on its own is inert.
//
// The key comes via HKDF-SHA256 from `FETCH_SECRET_KEY`, or from `API_TOKEN`
// when that is unset — which is every install from before it existed, so
// upgrading needs nothing (#638). With its own key, `API_TOKEN` (the admin
// bearer, which scripts hold copies of) can be rotated without touching a
// stored credential; and the encryption key itself can be rotated with
// `fetch rotate-key` (`lib/secret-rotation.ts`), which re-encrypts every row.
// Changing the key any other way makes every stored credential undecryptable:
// they have to be re-entered from Settings. The startup pass in
// `lib/secret-migration.ts` says so in the log rather than leaving it to be
// discovered at the next sync.

/**
 * Marks a value as ciphertext, and versions the construction: a future change
 * of cipher or KDF gets its own scheme string so old rows stay readable.
 */
const SCHEME = "cgy1";

const HKDF_SALT = "fetch-secret-box-v1";
const HKDF_INFO = "fetch-secret-box";
const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 16;

/**
 * Where a ciphertext is allowed to live, bound in as AES-GCM additional
 * authenticated data. A value lifted out of one column and pasted into another
 * fails to authenticate rather than decrypting into its new home.
 *
 * These strings are part of the on-disk format: changing one makes every value
 * stored under it undecryptable. Add, don't rename.
 */
export const SECRET_CONTEXT = {
  traktAccessToken: "traktToken.accessToken",
  traktRefreshToken: "traktToken.refreshToken",
  notificationChannelToken: "notificationChannel.token",
  profileConnectionCredentials: "profileConnection.credentials",
  // URLs that are themselves the credential: a Discord or generic webhook URL,
  // an ntfy topic, an addon manifest URL with a debrid key in its path.
  notificationChannelUrl: "notificationChannel.url",
  profileAddonManifestUrl: "profileAddon.manifestUrl",
  // An account's two-factor secret, on and being set up.
  accountTotpSecret: "account.totpSecret",
  accountTotpPendingSecret: "account.totpPendingSecret",
} as const;

/** The context a KV row's value is encrypted under — its own key. */
export const kvSecretContext = (key: string): string => `kv:${key}`;

/**
 * The secret every at-rest key derives from: `FETCH_SECRET_KEY`, or `API_TOKEN`
 * on an install that hasn't set one (#638). Null with neither (development).
 */
export const secretKeyRoot = (env: NodeJS.ProcessEnv = process.env): string | null =>
  env.FETCH_SECRET_KEY || env.API_TOKEN || null;

/** Which variable {@link secretKeyRoot} is reading, for messages to the operator. */
export const secretKeyRootName = (env: NodeJS.ProcessEnv = process.env): "FETCH_SECRET_KEY" | "API_TOKEN" =>
  env.FETCH_SECRET_KEY ? "FETCH_SECRET_KEY" : "API_TOKEN";

// A second key from the same root, for `secretLookupKey`: equality lookups
// over encrypted columns get their own key rather than reusing the cipher's.
const LOOKUP_HKDF_INFO = "fetch-secret-lookup";

type DerivedKeys = { cipher: Buffer; lookup: Buffer };

// Derivation is ~a hash, but it happens on every read of every credential, so
// the result is memoized against the root it came from — keyed rather than
// stored bare so a test, or `fetch rotate-key` deriving the old and the new
// side by side, doesn't keep encrypting under the wrong one.
const keyCache = new Map<string, DerivedKeys>();

const keysFor = (root: string): DerivedKeys => {
  const cached = keyCache.get(root);
  if (cached) return cached;
  const keys = {
    cipher: Buffer.from(hkdfSync("sha256", root, HKDF_SALT, HKDF_INFO, KEY_BYTES)),
    lookup: Buffer.from(hkdfSync("sha256", root, HKDF_SALT, LOOKUP_HKDF_INFO, KEY_BYTES)),
  };
  // Two roots at most in practice (current, and the other side of a rotation).
  if (keyCache.size >= 4) keyCache.clear();
  keyCache.set(root, keys);
  return keys;
};

const currentKeys = (): DerivedKeys | null => {
  const root = secretKeyRoot();
  return root ? keysFor(root) : null;
};

const lookupKeyUnder = (key: Buffer | null, context: string, value: string): string => {
  const input = `${context}\u0000${value}`;
  return key
    ? createHmac("sha256", key).update(input, "utf8").digest("hex")
    : createHash("sha256").update(input, "utf8").digest("hex");
};

/**
 * A deterministic stand-in for `value` that a unique index can hold where the
 * value itself is encrypted (and so different every time it is written):
 * HMAC-SHA256 under a key derived from {@link secretKeyRoot}, bound to
 * `context`. It reveals whether two rows hold the same value and nothing else,
 * and like the ciphertext it changes when that root does. Without one
 * (development only) it is a plain SHA-256.
 */
export const secretLookupKey = (context: string, value: string): string =>
  lookupKeyUnder(currentKeys()?.lookup ?? null, context, value);

/** {@link secretLookupKey} under an explicit root, for `fetch rotate-key`. */
export const secretLookupKeyUnder = (root: string, context: string, value: string): string =>
  lookupKeyUnder(keysFor(root).lookup, context, value);

/**
 * Whether this process can encrypt at all. False only without `API_TOKEN` or
 * `FETCH_SECRET_KEY`; production refuses to start without `API_TOKEN` — so in
 * practice this is "am I a bare `pnpm dev` with no .env".
 */
export const isSecretEncryptionAvailable = (): boolean => secretKeyRoot() !== null;

/**
 * Whether a stored value is one of ours. Structural only: a plaintext secret
 * that happened to look like this would fail authentication a moment later and
 * be reported as unreadable, which is the safe direction to be wrong in.
 */
export const isEncryptedSecret = (stored: string): boolean =>
  stored.startsWith(`${SCHEME}.`) && stored.split(".").length === 4;

/**
 * Ciphertext for `plaintext`, bound to `context`.
 *
 * Without `API_TOKEN` this returns the plaintext unchanged rather than throwing:
 * a development instance with no token still has to be able to save a TMDB key,
 * and `decryptSecret` reads such a value back. Production cannot reach this
 * branch — the startup guard exits before serving a request without a token.
 */
export const encryptSecret = (context: string, plaintext: string): string => {
  const key = currentKeys()?.cipher;
  if (!key) return plaintext;
  return encryptWith(key, context, plaintext);
};

/** {@link encryptSecret} under an explicit root, for `fetch rotate-key`. */
export const encryptSecretUnder = (root: string, context: string, plaintext: string): string =>
  encryptWith(keysFor(root).cipher, context, plaintext);

const encryptWith = (key: Buffer, context: string, plaintext: string): string => {
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(context, "utf8"));
  const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);

  return [
    SCHEME,
    iv.toString("base64url"),
    cipher.getAuthTag().toString("base64url"),
    ciphertext.toString("base64url"),
  ].join(".");
};

/**
 * The plaintext behind `stored`, or null when it cannot be recovered.
 *
 * A value that isn't ciphertext comes back verbatim: rows written before this
 * existed stay readable, so shipping the change needs no migration to be
 * correct (the startup pass encrypts them in place afterwards, for the rows a
 * dump would otherwise still expose).
 *
 * Null means the value is real ciphertext that this key cannot open — almost
 * always a changed `FETCH_SECRET_KEY` (or `API_TOKEN`, when that is the root). Callers treat that as "not configured" rather
 * than crashing, except where silently re-deriving would break something worse
 * (VAPID keys, whose loss unsubscribes every browser).
 */
export const decryptSecret = (context: string, stored: string): string | null => {
  if (!isEncryptedSecret(stored)) return stored;

  const key = currentKeys()?.cipher;
  if (!key) return null;
  return decryptWith(key, context, stored);
};

/** {@link decryptSecret} under an explicit root, for `fetch rotate-key`. */
export const decryptSecretUnder = (root: string, context: string, stored: string): string | null =>
  isEncryptedSecret(stored) ? decryptWith(keysFor(root).cipher, context, stored) : stored;

const decryptWith = (key: Buffer, context: string, stored: string): string | null => {
  const [, ivRaw, tagRaw, ciphertextRaw] = stored.split(".");
  // `isEncryptedSecret` above already required four parts; this is the same
  // claim in a form the three uses below can rely on.
  if (ivRaw === undefined || tagRaw === undefined || ciphertextRaw === undefined) return null;

  try {
    const iv = Buffer.from(ivRaw, "base64url");
    const tag = Buffer.from(tagRaw, "base64url");
    // createDecipheriv/setAuthTag throw on the wrong sizes; checking first keeps
    // a malformed row on the same "returns null" path as a wrong key.
    if (iv.length !== IV_BYTES || tag.length !== TAG_BYTES) return null;

    const decipher = createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAAD(Buffer.from(context, "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([
      decipher.update(Buffer.from(ciphertextRaw, "base64url")),
      decipher.final(),
    ]).toString("utf8");
  } catch {
    return null;
  }
};

/**
 * Re-encrypts `stored` under the current key if it is still plaintext, and
 * returns null when it is already ciphertext — so a caller walking rows can
 * write back exactly the ones that changed.
 */
export const encryptIfPlaintext = (context: string, stored: string): string | null =>
  isEncryptedSecret(stored) ? null : encryptSecret(context, stored);
