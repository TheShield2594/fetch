import { prisma } from "./prisma.js";
import { outboundScopeForProfile } from "./integration-owner.js";
import { type NotificationEvent, sendToChannel } from "./notification-channels.js";

/**
 * Alerts for whoever runs the server: a background job that keeps failing, a
 * TV whose sync has started to.
 *
 * Both were already recorded — in Settings → Sync Status and on the device
 * list — but only for someone who went and looked, and a sync that broke
 * quietly is exactly the thing nobody goes to look at. These go out over the
 * notification channels the admin has marked for it (`adminAlerts`), which is
 * off for every channel until they do: an episode reminder and "the Trakt poll
 * has been failing since Tuesday" are not the same subscription.
 *
 * Best-effort throughout. Nothing here may throw into the job or the request
 * that noticed the problem.
 */

type AlertLogger = { warn: (obj: object, msg: string) => void };

/** Sends `event` to every enabled admin-alert channel of an admin's profile. Returns how many took it. */
export const sendAdminAlert = async (event: NotificationEvent, log?: AlertLogger): Promise<number> => {
  let delivered = 0;
  try {
    const channels = await prisma.notificationChannel.findMany({
      // The flag can only be set by the admin, but an account can stop being
      // the admin afterwards: checked here as well, where it matters.
      where: { enabled: true, adminAlerts: true, profile: { account: { isAdmin: true } } },
      select: { id: true, kind: true, name: true, url: true, token: true, profileId: true },
      orderBy: { createdAt: "asc" },
    });
    for (const channel of channels) {
      try {
        await sendToChannel(channel, { ...event, severity: event.severity ?? "warning" }, await outboundScopeForProfile(channel.profileId));
        delivered += 1;
      } catch (error) {
        log?.warn({ error, channel: channel.name, event: event.event }, "Admin alert could not be sent");
      }
    }
  } catch (error) {
    log?.warn({ error, event: event.event }, "Admin alerts could not be looked up");
  }
  return delivered;
};

/**
 * A job alerts once it has failed this many times in a row. One failure is a
 * blip — Trakt answering 502, a TMDB timeout — and most clear themselves on the
 * next run; three in a row is something that needs a person.
 */
export const JOB_ALERT_AFTER_FAILURES = 3;

/** What `job-status` knows after recording a run: how long the failing streak now is, and how long it was. */
export type JobStreak = { job: string; failures: number; previousFailures: number; message: string | null };

/**
 * The alert a just-recorded run calls for, if any: one when a streak reaches
 * the threshold — not again on every failure after it — and one when a job
 * that had alerted runs clean again.
 */
export const jobAlertFor = (streak: JobStreak): NotificationEvent | null => {
  if (streak.failures === JOB_ALERT_AFTER_FAILURES) {
    return {
      event: "job-failing",
      title: `Fetch: ${streak.job} keeps failing`,
      body: `It has failed ${streak.failures} times in a row. ${streak.message ?? ""}`.trim().slice(0, 500),
      path: "/settings?section=job-status",
      severity: "warning",
      data: { job: streak.job, failures: streak.failures, message: streak.message },
    };
  }
  if (streak.failures === 0 && streak.previousFailures >= JOB_ALERT_AFTER_FAILURES) {
    return {
      event: "job-recovered",
      title: `Fetch: ${streak.job} is working again`,
      body: `It ran successfully after failing ${streak.previousFailures} times in a row.`,
      path: "/settings?section=job-status",
      severity: "info",
      data: { job: streak.job, previousFailures: streak.previousFailures },
    };
  }
  return null;
};

/** The alert for a TV whose sync has just gone from working to failing. */
export const tvSyncAlert = (device: { name: string }, error: string): NotificationEvent => ({
  event: "tv-sync-failing",
  title: `Fetch: ${device.name} can't sync`,
  body: `Its last sync with this server failed. ${error}`.slice(0, 500),
  path: "/settings?section=account",
  severity: "warning",
  data: { device: device.name, error },
});

/**
 * The alert for credentials the startup sweep could not decrypt: API_TOKEN has
 * changed since they were saved. A channel whose own URL is among them can't
 * carry it, so this reaches only the channels saved since; the startup log
 * names them either way.
 */
export const credentialsUnreadableAlert = (labels: string[]): NotificationEvent => ({
  event: "credentials-unreadable",
  title: `Fetch: ${labels.length} stored credential${labels.length === 1 ? "" : "s"} can't be read`,
  body: (
    `API_TOKEN has changed since ${labels.length === 1 ? "it was" : "they were"} saved: ${labels.join(", ")}. ` +
    "Restore the previous API_TOKEN, or enter them again in Settings."
  ).slice(0, 500),
  path: "/settings",
  severity: "warning",
  data: { credentials: labels },
});

/** The alert for a profile whose Trakt sign-in can no longer be refreshed. */
export const traktSignInAlert = (profileName: string): NotificationEvent => ({
  event: "trakt-reconnect-needed",
  title: `Fetch: Trakt needs signing in again for ${profileName}`,
  body: "Trakt refused to renew the sign-in, so history, watchlist and scrobbles for this profile have stopped syncing. Reconnect Trakt from Settings.",
  path: "/settings?section=trakt",
  severity: "warning",
  data: { profile: profileName },
});
