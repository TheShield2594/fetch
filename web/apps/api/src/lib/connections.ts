import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma.js";
import { SECRET_CONTEXT, decryptSecret, encryptSecret } from "./secret-box.js";

// A profile's sign-ins to Simkl, MDBList, Plex, Jellyfin and Emby, kept in
// ProfileConnection, and its MDBList API key for ratings (`mdblist-key`, a
// provider of its own so the tracker's sync and disconnect never touch it). Each is the profile's own, as in the TV app: the
// website's are separate from the TV's, and both record into the same history.

export type ConnectionProvider = "simkl" | "mdblist" | "mdblist-key" | "plex" | "jellyfin" | "emby";

export type Connection<C, D, S = Record<string, unknown>> = {
  id: string;
  profileId: string;
  provider: ConnectionProvider;
  externalKey: string;
  /** Null when the stored credentials can't be decrypted (API_TOKEN changed). */
  credentials: C | null;
  details: D;
  state: S;
  createdAt: Date;
  updatedAt: Date;
};

type Row = {
  id: string;
  profileId: string;
  provider: string;
  externalKey: string;
  credentials: string;
  details: Prisma.JsonValue;
  state: Prisma.JsonValue;
  createdAt: Date;
  updatedAt: Date;
};

const fromRow = <C, D, S = Record<string, unknown>>(row: Row): Connection<C, D, S> => {
  const plain = decryptSecret(SECRET_CONTEXT.profileConnectionCredentials, row.credentials);
  let credentials: C | null = null;
  if (plain !== null) {
    try {
      credentials = JSON.parse(plain) as C;
    } catch {
      credentials = null;
    }
  }
  return {
    id: row.id,
    profileId: row.profileId,
    provider: row.provider as ConnectionProvider,
    externalKey: row.externalKey,
    credentials,
    details: (row.details ?? {}) as D,
    state: (row.state ?? {}) as S,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
};

export const getConnection = async <C, D>(
  profileId: string,
  provider: ConnectionProvider,
  externalKey = ""
): Promise<Connection<C, D> | null> => {
  const row = await prisma.profileConnection.findUnique({
    where: { profileId_provider_externalKey: { profileId, provider, externalKey } },
  });
  return row ? fromRow<C, D>(row) : null;
};

/** One profile's connections to a provider (several for a media server provider). */
export const listProfileConnections = async <C, D>(
  profileId: string,
  provider: ConnectionProvider
): Promise<Connection<C, D>[]> =>
  (await prisma.profileConnection.findMany({ where: { profileId, provider }, orderBy: { createdAt: "asc" } })).map(
    (row) => fromRow<C, D>(row)
  );

/** Every profile's connections to a provider, for a scheduled sync. */
export const listConnections = async <C, D>(provider: ConnectionProvider): Promise<Connection<C, D>[]> =>
  (await prisma.profileConnection.findMany({ where: { provider }, orderBy: { updatedAt: "asc" } })).map((row) =>
    fromRow<C, D>(row)
  );

export const saveConnection = async <C, D>(input: {
  profileId: string;
  provider: ConnectionProvider;
  externalKey?: string;
  credentials: C;
  details: D;
}): Promise<void> => {
  const externalKey = input.externalKey ?? "";
  const credentials = encryptSecret(SECRET_CONTEXT.profileConnectionCredentials, JSON.stringify(input.credentials));
  const details = input.details as Prisma.InputJsonValue;
  await prisma.profileConnection.upsert({
    where: { profileId_provider_externalKey: { profileId: input.profileId, provider: input.provider, externalKey } },
    create: { profileId: input.profileId, provider: input.provider, externalKey, credentials, details },
    update: { credentials, details },
  });
};

/** Changes what Settings shows about a connection, leaving its credentials alone. */
export const updateConnectionDetails = async <D>(id: string, details: D): Promise<void> => {
  await prisma.profileConnection.updateMany({ where: { id }, data: { details: details as Prisma.InputJsonValue } });
};

/**
 * Rewrites a connection's details from what is stored now, with the row locked,
 * so a sync finishing and a Settings change made meanwhile don't overwrite each
 * other. `change` returns null to leave them as they are. Returns what was
 * written (or what is stored, on null), or null when the connection is gone.
 */
export const changeConnectionDetails = async <D>(id: string, change: (current: D) => D | null): Promise<D | null> =>
  prisma.$transaction(async (tx) => {
    await tx.$executeRaw`SELECT id FROM "ProfileConnection" WHERE id = ${id}::uuid FOR UPDATE`;
    const row = await tx.profileConnection.findUnique({ where: { id }, select: { details: true } });
    if (!row) return null;
    const current = (row.details ?? {}) as D;
    const next = change(current);
    if (next === null) return current;
    await tx.profileConnection.update({ where: { id }, data: { details: next as Prisma.InputJsonValue } });
    return next;
  });

export const deleteConnection = async (
  profileId: string,
  provider: ConnectionProvider,
  externalKey?: string
): Promise<number> => {
  const { count } = await prisma.profileConnection.deleteMany({
    where: { profileId, provider, ...(externalKey === undefined ? {} : { externalKey }) },
  });
  return count;
};

/** A connection by its id, if it is the profile's. */
export const getProfileConnectionById = async <C, D, S = Record<string, unknown>>(
  profileId: string,
  id: string
): Promise<Connection<C, D, S> | null> => {
  const row = await prisma.profileConnection.findFirst({ where: { id, profileId } });
  return row ? fromRow<C, D, S>(row) : null;
};

/** Replaces a connection's sync state. */
export const updateConnectionState = async <S>(id: string, state: S): Promise<void> => {
  await prisma.profileConnection.updateMany({ where: { id }, data: { state: state as Prisma.InputJsonValue } });
};

export const deleteConnectionById = async (profileId: string, id: string): Promise<number> =>
  (await prisma.profileConnection.deleteMany({ where: { id, profileId } })).count;
