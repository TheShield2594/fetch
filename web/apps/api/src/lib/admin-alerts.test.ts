import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = { notificationChannel: { findMany: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));
vi.mock("./integration-owner.js", () => ({ outboundScopeForProfile: async () => "any" }));
const sendToChannel = vi.fn();
vi.mock("./notification-channels.js", () => ({ sendToChannel: (...a: unknown[]) => sendToChannel(...a) }));

const { JOB_ALERT_AFTER_FAILURES, jobAlertFor, sendAdminAlert, tvSyncAlert } = await import("./admin-alerts.js");

const streak = (failures: number, previousFailures: number) => ({
  job: "trakt-history-poll",
  failures,
  previousFailures,
  message: "Trakt: 401",
});

describe("jobAlertFor", () => {
  it("stays quiet for a blip", () => {
    expect(jobAlertFor(streak(1, 0))).toBeNull();
    expect(jobAlertFor(streak(2, 1))).toBeNull();
    expect(jobAlertFor(streak(0, 2))).toBeNull();
  });

  it("alerts once when the streak reaches the threshold, and not on every failure after", () => {
    const alert = jobAlertFor(streak(JOB_ALERT_AFTER_FAILURES, JOB_ALERT_AFTER_FAILURES - 1));

    expect(alert).toMatchObject({ event: "job-failing", severity: "warning" });
    expect(alert?.body).toContain("Trakt: 401");
    expect(jobAlertFor(streak(JOB_ALERT_AFTER_FAILURES + 1, JOB_ALERT_AFTER_FAILURES))).toBeNull();
  });

  it("says when a job that had alerted is working again", () => {
    expect(jobAlertFor(streak(0, 7))).toMatchObject({ event: "job-recovered", severity: "info" });
  });
});

describe("sendAdminAlert", () => {
  beforeEach(() => vi.clearAllMocks());

  it("only looks at enabled alert channels of an admin's profiles", async () => {
    prismaMock.notificationChannel.findMany.mockResolvedValue([]);

    await sendAdminAlert(tvSyncAlert({ name: "Living room" }, "timeout"));

    expect(prismaMock.notificationChannel.findMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { enabled: true, adminAlerts: true, profile: { account: { isAdmin: true } } },
      })
    );
  });

  it("carries on past a channel that fails, and never throws", async () => {
    prismaMock.notificationChannel.findMany.mockResolvedValue([
      { id: "c1", kind: "ntfy", name: "Dead", url: "x", token: null, profileId: "p1" },
      { id: "c2", kind: "ntfy", name: "Phone", url: "y", token: null, profileId: "p1" },
    ]);
    sendToChannel.mockRejectedValueOnce(new Error("unreachable")).mockResolvedValueOnce(undefined);
    const log = { warn: vi.fn() };

    expect(await sendAdminAlert(tvSyncAlert({ name: "Living room" }, "timeout"), log)).toBe(1);
    expect(log.warn).toHaveBeenCalledTimes(1);

    prismaMock.notificationChannel.findMany.mockRejectedValue(new Error("db down"));
    await expect(sendAdminAlert(tvSyncAlert({ name: "Living room" }, "timeout"), log)).resolves.toBe(0);
  });
});
