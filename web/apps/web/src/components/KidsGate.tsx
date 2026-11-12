import { type ReactNode, useContext } from "react";
import { Loader2, RotateCw, Users } from "lucide-react";
import { ProfileContext, useIsKidsProfile, useProfile } from "../hooks/useProfile";
import { EmptyState } from "./EmptyState";
import { PageFrame } from "./PageFrame";

/**
 * What a kids profile meets instead of the account's settings, its data and
 * device pairing: a page that says these are a grown-up's, and the way to
 * switch to one.
 *
 * The server already refuses a kids profile the changes that matter
 * (loosening its own limits, adding a profile); this keeps it out of the rest
 * on the website, which is where a child on a tablet with the app installed
 * would find them. A grown-up's profile with a PIN is what makes it a
 * boundary: an unlocked one is a switch away.
 */
export function KidsGate({
  title,
  reason,
  children,
}: {
  /** The page's name, kept as its (hidden) heading. */
  title: string;
  /** "Settings are", "Signing in a TV is": what is kept back, as the start of a sentence. */
  reason: string;
  children: ReactNode;
}) {
  const isKids = useIsKidsProfile();
  const { profile, openSwitcher } = useProfile();
  const ctx = useContext(ProfileContext);
  // Closed until the active profile is known: `null` is "not read yet" (or
  // "couldn't be read"), not "a grown-up's".
  if (!profile) {
    return (
      <PageFrame width="reading">
        <h1 className="sr-only">{title}</h1>
        {ctx?.profileLoadFailed ? (
          <EmptyState
            title="Couldn't check this profile"
            action={
              <button type="button" onClick={() => ctx.retryProfileLoad?.()} className="btn-secondary">
                <RotateCw size={16} /> Try again
              </button>
            }
          >
            {title} opens once Fetch knows which profile is in use.
          </EmptyState>
        ) : (
          <p role="status" className="flex items-center justify-center gap-2 py-16 text-sm" style={{ color: "var(--text-mute)" }}>
            <Loader2 size={16} className="animate-spin" /> Loading…
          </p>
        )}
      </PageFrame>
    );
  }
  if (!isKids) return <>{children}</>;
  return (
    <PageFrame width="reading">
      <h1 className="sr-only">{title}</h1>
      <EmptyState
        title="Ask a grown-up"
        action={
          <button type="button" onClick={openSwitcher} className="btn-secondary">
            <Users size={16} /> Switch profile
          </button>
        }
      >
        {reason} for a grown-up&apos;s profile. {profile?.name ?? "This profile"} is a kids
        profile, so switch to a grown-up&apos;s to carry on.
      </EmptyState>
    </PageFrame>
  );
}
