import { ProfileSettings } from "../components/settings/ProfileSettings";
import { PAGE_TITLE } from "../components/typography";

/**
 * The household, on a page of its own: add, rename, recolour and pick a
 * picture for each profile, set its PIN and parental limit, and delete it.
 * The same controls as Settings → Profile, reachable directly from the rail.
 */
export function ProfilesPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6 py-2">
      <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Profiles</h1>
      <ProfileSettings />
    </div>
  );
}
