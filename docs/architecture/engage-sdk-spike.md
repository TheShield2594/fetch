# Watch Next after 2027: the Engage SDK spike (#662)

Google says the legacy Watch Next API (`TvContractCompat.WatchNextPrograms`, which
`core/recommendations/ProgramBuilder.kt` writes to) "will lose support in the 2nd half of 2027".
New Continue Watching integrations are meant to use the Engage SDK (formerly the Video Discovery
API). This note records what that would take for Fetch and what we do in the meantime.

Sources, read 2026-11-26:
- https://android-developers.googleblog.com/2026/05/increase-google-tv-app-discovery.html
- https://developer.android.com/guide/playcore/engage/faq
- https://developer.android.com/guide/playcore/engage/tv/getting-started
- https://developer.android.com/guide/playcore/engage/tv/continue-watching/client

## What Engage requires

- **Gated access.** Engage is "rolled out in phases" behind "an allowlist on the Engage side and a
  separate check within the Engage SDK". Developers are asked to express interest before
  integrating (the pages give `g.co/tv/engage` and `g.co/tv/vda`; contact
  `engage-developers@google.com`). Submission includes a screenshot from Google's verification app.
- **Distribution.** None of the pages says whether an app installed from outside Google Play can be
  allowlisted, or whether a Play Console listing is needed. That is the open question, and only
  Google can answer it.
- **Device support.** The SDK checks at runtime whether the service is available
  (`isServiceAvailable`). Nothing is said about devices without Google services; Fire TV, which
  doesn't show the Watch Next row today, should be assumed out.
- **Limits.** At most 5 entities in the Continuation cluster per app (publishes over the limit are
  rejected). Movies and TV episodes only; no show or season entities. Entities expire after 60
  days unless given a shorter expiry. Continuation data is for adult accounts only.
- **Accounts.** An `AccountProfile` with an account id is "technically required". Without real
  accounts the row stays on the current device. Cross-device sync is off by default, and the app
  has to offer a setting for it.
- **Artifacts.** `com.google.android.engage:engage-tv:1.1.0`; the `WRITE_EPG_DATA` permission and
  an `com.google.android.engage.service.ENV = PRODUCTION` meta-data tag for release builds.

## What it would mean for Fetch

Fetch is a GPL app distributed as APKs from this repository, not through Play. Joining Engage
depends on Google allowlisting an app like that, which nothing in the docs promises. If they do,
the work is moderate: a publisher that maps the launcher items `AndroidTvChannelSyncService`
already builds to `TvEpisodeEntity` / `MovieEntity` (capped at 5, profile id as the account id,
kids profiles excluded, the existing `LauncherExposureGate` rules kept), plus the cross-device
setting.

## Decision

1. **Ask.** The maintainer expresses interest through Google's form and asks whether a
   non-Play, open-source app can be allowlisted. Outcome to be recorded on #662.
2. **Until then, and if the answer is no:** keep the legacy Watch Next path working for as long as
   launchers show it (including the `WATCH_NEXT_TYPE_NEW` labels from #657). Accept that the
   Google TV launcher row may stop showing Fetch in the second half of 2027; the README says so.
3. **If the answer is yes:** add the Engage publisher next to the legacy path, not in place of it,
   since Engage is backward compatible only on devices that already support Watch Next and older
   Android TV launchers keep reading the provider.
