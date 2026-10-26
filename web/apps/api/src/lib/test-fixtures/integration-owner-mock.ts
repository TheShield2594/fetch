import * as ownerKeys from "../owner-keys.js";

/** The account every request and profile belongs to under this mock. */
export const MOCK_OWNER = "owner-1";

/**
 * A stand-in for lib/integration-owner.ts in route tests with no database:
 * the real key helpers, and one account, the admin, behind every lookup.
 *
 *     vi.mock("../lib/integration-owner.js", async () =>
 *       (await import("../lib/test-fixtures/integration-owner-mock.js")).integrationOwnerMock);
 */
export const integrationOwnerMock = {
  ...ownerKeys,
  ownerForRequest: async () => MOCK_OWNER,
  ownerForProfile: async () => MOCK_OWNER,
  outboundScopeForOwner: async () => ({ allowPrivateNetwork: true }),
  outboundScopeForProfile: async () => ({ allowPrivateNetwork: true }),
};
