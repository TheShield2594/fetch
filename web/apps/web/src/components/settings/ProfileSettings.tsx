import { useEffect, useId, useState } from "react";
import { Link } from "react-router";
import { AlertCircle, Loader2, Pencil, Plus, Users } from "lucide-react";
import { api, type Profile } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { useToast } from "../../hooks/useToast";
import { ProfileAvatar } from "../ProfileAvatar";
import { ProfileBadges } from "../profile/ProfileBadges";
import { ProfileSheet } from "../profile/ProfileSheet";
import { CreateProfileFlow } from "../profile/CreateProfileFlow";
import { SelectField } from "../SelectField";
import { IDLE_LOCK_CHOICES, setIdleLockMinutes, useIdleLockMinutes } from "../../hooks/useIdleLock";

export { describeProfileContents } from "../profile/ProfileSheet";

/**
 * One profile: its picture large, its name, what it is (in use, primary,
 * kids, PIN) and one labelled way in. Everything else is in the sheet.
 */
function ProfileCard({
  profile,
  isActive,
  isPrimary,
  onEdit,
}: {
  profile: Profile;
  isActive: boolean;
  isPrimary: boolean;
  onEdit: () => void;
}) {
  return (
    <li
      className="flex items-center gap-4 rounded-2xl border p-4"
      style={{ borderColor: isActive ? "rgb(var(--accent-rgb) / 0.45)" : "var(--border)", background: "var(--surface)" }}
    >
      <ProfileAvatar profile={profile} size="lg" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-base font-semibold" style={{ color: "var(--text)" }}>{profile.name}</p>
        <ProfileBadges profile={profile} isActive={isActive} isPrimary={isPrimary} className="mt-1.5" />
      </div>
      <button type="button" onClick={onEdit} className="btn-secondary btn-sm flex-none" aria-label={`Edit ${profile.name}`}>
        <Pencil size={14} aria-hidden="true" /> Edit
      </button>
    </li>
  );
}

/**
 * How long a PIN profile stays open on this browser without use. Per browser,
 * not per profile: it is about whether this computer is shared.
 */
function IdleLockSetting() {
  const minutes = useIdleLockMinutes();
  const id = useId();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <label htmlFor={id} className="text-sm" style={{ color: "var(--text-dim)" }}>
        Lock PIN profiles on this browser after
      </label>
      <SelectField
        id={id}
        value={String(minutes)}
        onChange={(e) => setIdleLockMinutes(Number(e.target.value))}
        className="rounded-xl px-3 py-2 text-sm font-medium"
      >
        {IDLE_LOCK_CHOICES.map((choice) => (
          <option key={choice} value={choice}>
            {choice === 0 ? "Never (stay open 12 hours)" : `${choice} minutes of inactivity`}
          </option>
        ))}
      </SelectField>
    </div>
  );
}

export function ProfileSettings() {
  const { profile: activeProfile, setProfile: setActiveProfile, openSwitcher } = useProfile();
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const { showToast } = useToast();

  useEffect(() => {
    void (async () => {
      try {
        const { profiles: fetched } = await api.getProfiles();
        setProfiles(fetched);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load profiles");
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const handleUpdated = (updated: Profile) => {
    setProfiles((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
    if (activeProfile?.id === updated.id) setActiveProfile(updated);
  };

  const handleDeleted = (id: string) => {
    setProfiles((prev) => prev.filter((p) => p.id !== id));
    setEditing(null);
    showToast("Profile deleted");
    if (activeProfile?.id === id) {
      // The profile we were using is gone — force re-selection instead of
      // leaving every subsequent API call scoped to a now-nonexistent profile.
      openSwitcher();
    }
  };

  const editingIndex = profiles.findIndex((p) => p.id === editing);
  const editingProfile = editingIndex >= 0 ? profiles[editingIndex] : undefined;

  return (
    <div className="space-y-5">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        {activeProfile ? <>You&apos;re watching as <strong>{activeProfile.name}</strong>. </> : null}
        Each profile keeps its own watch history, lists and stats. Paired TVs pick up changes here on their next sync.{" "}
        {activeProfile && (
          <Link to="/profile" className="font-medium text-accent-text underline-offset-2 hover:underline">
            See {activeProfile.name}&apos;s profile
          </Link>
        )}
      </p>

      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={openSwitcher} className="btn-secondary">
          <Users size={16} /> Switch profile
        </button>
        {/* Only once the list is in: a profile made before it arrived would be
            overwritten by the older list, or hidden behind its error. */}
        {!adding && !loading && !error && (
          <button type="button" onClick={() => setAdding(true)} className="btn-secondary">
            <Plus size={16} /> Add profile
          </button>
        )}
      </div>

      {profiles.some((p) => p.hasPin) && <IdleLockSetting />}

      {adding && (
        <div className="rounded-2xl border p-5" style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}>
          <CreateProfileFlow
            title="Add a profile"
            subtitle="Its own history, lists and stats, here and on every paired TV."
            headingTag="h3"
            currentProfileName={activeProfile?.name}
            onCreated={(created) => {
              setProfiles((prev) => [...prev, created]);
              showToast(`Added ${created.name}`);
            }}
            onEntered={(created) => {
              setAdding(false);
              setActiveProfile(created);
            }}
            onDone={() => setAdding(false)}
            onCancel={() => setAdding(false)}
          />
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading profiles…
        </div>
      ) : error ? (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>
      ) : (
        <ul aria-label="Profiles" className="grid gap-3 xl:grid-cols-2">
          {profiles.map((profile, index) => (
            <ProfileCard
              key={profile.id}
              profile={profile}
              isActive={activeProfile?.id === profile.id}
              // The oldest profile is the primary one, as on the TV.
              isPrimary={index === 0}
              onEdit={() => setEditing(profile.id)}
            />
          ))}
        </ul>
      )}

      {editingProfile && (
        <ProfileSheet
          key={editingProfile.id}
          profile={editingProfile}
          isActive={activeProfile?.id === editingProfile.id}
          isPrimary={editingIndex === 0}
          isOnlyProfile={profiles.length === 1}
          onUpdated={handleUpdated}
          onDeleted={handleDeleted}
          onSwitch={openSwitcher}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}
