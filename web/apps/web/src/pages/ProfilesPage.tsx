import { ProfileSettings } from "../components/settings/ProfileSettings";
import { PageFrame, PageHeader } from "../components/PageFrame";

/**
 * The household, on a page of its own: add, rename, recolour and pick a
 * picture for each profile, set its PIN and parental limit, and delete it.
 * The same controls as Settings → Profile, reachable directly from the rail.
 */
export function ProfilesPage() {
  return (
    <PageFrame width="reading" className="space-y-6">
      <PageHeader title="Profiles" className="" />
      <ProfileSettings />
    </PageFrame>
  );
}
