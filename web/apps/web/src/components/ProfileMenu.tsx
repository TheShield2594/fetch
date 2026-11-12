import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router";
import { Download, LogOut, Palette, Users, UserCog } from "lucide-react";
import { api } from "../api";
import { useProfile } from "../hooks/useProfile";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { MANUAL_INSTALL_HINT, useInstallOffer } from "../hooks/useInstallOffer";
import { ProfileAvatar } from "./ProfileAvatar";
import { ThemePicker } from "./ThemePicker";

const ROW =
  "flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:bg-[var(--surface-strong)] focus-visible:ring-2 focus-visible:ring-focus";

/**
 * The profile, as one control: its avatar, which opens a menu to switch
 * profile, change the theme, install the app, manage profiles and sign out.
 *
 * It replaces three things that each answered part of "who is this, and how
 * do I change how it looks": a palette button and an install pair in the
 * header of every page, and two different drawings of the profile — a generic
 * person on an accent gradient in the rail, the initial on the same gradient
 * on a phone — neither of which was the avatar the person chose on the TV.
 *
 * `header` is the phone's top-bar circle; `rail` is the desktop rail's footer
 * row, whose name shows when the rail is open.
 */
export function ProfileMenu({ variant, expanded = true }: { variant: "header" | "rail"; expanded?: boolean }) {
  const { profile } = useProfile();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const name = profile?.name ?? "Profile";

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={profile ? `Profile menu, ${profile.name}` : "Profile menu"}
        title={variant === "rail" && !expanded ? name : undefined}
        onClick={() => setOpen((was) => !was)}
        className={
          variant === "header"
            ? "tap-target flex h-[2.125rem] w-[2.125rem] flex-none items-center justify-center rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
            : "flex w-full items-center gap-2.5 rounded-[0.4375rem] px-2 py-[0.3125rem] text-[0.8125rem] transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
        }
        style={variant === "rail" ? { color: "var(--text-dim)" } : undefined}
      >
        {profile ? (
          <ProfileAvatar profile={profile} size={variant === "header" ? "sm" : "xs"} />
        ) : (
          // A neutral circle while the profile is still in flight: a coloured
          // one would be somebody's colour.
          <span
            aria-hidden="true"
            className={`flex-none rounded-full ${variant === "header" ? "h-8 w-8" : "h-6 w-6"}`}
            style={{ background: "var(--surface-strong)" }}
          />
        )}
        {variant === "rail" && (
          <span className="truncate whitespace-nowrap" style={{ opacity: expanded ? 1 : 0 }}>
            {name}
          </span>
        )}
      </button>
      {open && <ProfileMenuPanel variant={variant} triggerRef={triggerRef} onClose={close} />}
    </>
  );
}

function ProfileMenuPanel({
  variant,
  triggerRef,
  onClose,
}: {
  variant: "header" | "rail";
  triggerRef: React.RefObject<HTMLButtonElement | null>;
  onClose: (refocus: boolean) => void;
}) {
  const { profile, openSwitcher } = useProfile();
  const { state: installState, install } = useInstallOffer();
  const [installHint, setInstallHint] = useState<string | null>(null);
  const [signingOut, setSigningOut] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<CSSProperties>({ visibility: "hidden" });

  // The latest close, read when an event needs it, so the listeners below are
  // added once — re-adding them on every render also re-ran the focus move.
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEscapeKey(() => onCloseRef.current(true));

  // Fixed to the trigger and portalled to <body>: the rail clips its own
  // overflow, so a menu opened inside it would be cut at the rail's edge.
  useLayoutEffect(() => {
    const place = () => {
      const rect = triggerRef.current?.getBoundingClientRect();
      if (!rect) return;
      setPosition(
        variant === "header"
          ? { top: rect.bottom + 8, right: Math.max(8, window.innerWidth - rect.right) }
          : { bottom: Math.max(8, window.innerHeight - rect.bottom), left: rect.right + 8 }
      );
    };
    place();
    window.addEventListener("resize", place);
    return () => window.removeEventListener("resize", place);
  }, [triggerRef, variant]);

  useEffect(() => {
    panelRef.current?.querySelector<HTMLElement>("button, a, input")?.focus();
  }, []);

  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return;
      onCloseRef.current(false);
    };
    // Tabbing out of the panel closes it rather than leaving it open behind
    // the focus.
    const panel = panelRef.current;
    const onFocusOut = (e: FocusEvent) => {
      const next = e.relatedTarget as Node | null;
      if (next && !panel?.contains(next) && !triggerRef.current?.contains(next)) onCloseRef.current(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    panel?.addEventListener("focusout", onFocusOut);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      panel?.removeEventListener("focusout", onFocusOut);
    };
  }, [triggerRef]);

  const signOut = async () => {
    setSigningOut(true);
    try {
      await api.logout();
    } catch {
      // Signed out on this device regardless; the server forgets the session
      // when it next expires.
    } finally {
      window.dispatchEvent(new Event("fetch:unauthorized"));
    }
  };

  const canInstall = installState === "prompt" || installState === "ios";

  return createPortal(
    <div
      ref={panelRef}
      role="dialog"
      aria-label="Profile"
      className="overlay-dialog fixed z-[60] w-[17rem] max-w-[calc(100vw-1rem)] rounded-2xl p-1.5 shadow-e2"
      style={{
        ...position,
        background: "var(--bg-1)",
        border: "1px solid var(--border-strong)",
        transformOrigin: variant === "header" ? "top right" : "bottom left",
      }}
    >
      {profile && (
        <div className="flex items-center gap-3 px-3 pb-2.5 pt-2">
          <ProfileAvatar profile={profile} size="md" />
          <p className="min-w-0 truncate text-[0.9375rem] font-semibold" style={{ color: "var(--text)" }}>
            {profile.name}
          </p>
        </div>
      )}

      <button
        type="button"
        className={ROW}
        style={{ color: "var(--text)" }}
        onClick={() => {
          onClose(false);
          openSwitcher();
        }}
      >
        <Users className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
        Switch profile
      </button>
      <Link
        to="/settings?section=profile"
        className={ROW}
        style={{ color: "var(--text)" }}
        onClick={() => onClose(false)}
      >
        <UserCog className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
        Manage profiles
      </Link>

      <div className="my-1.5 border-t" style={{ borderColor: "var(--border)" }} />

      <div className="px-3 pb-1 pt-1.5">
        <p className="mb-2 text-xs font-medium" style={{ color: "var(--text-dim)" }}>
          Theme
        </p>
        <ThemePicker layout="dots" />
      </div>
      <Link
        to="/settings?section=appearance"
        className={ROW}
        style={{ color: "var(--text)" }}
        onClick={() => onClose(false)}
      >
        <Palette className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
        Appearance
      </Link>
      {canInstall && (
        <button
          type="button"
          className={ROW}
          style={{ color: "var(--text)" }}
          onClick={() =>
            void install().then((prompted) => {
              if (prompted) onClose(false);
              else setInstallHint(installState === "ios" ? MANUAL_INSTALL_HINT.ios : MANUAL_INSTALL_HINT.other);
            })
          }
        >
          <Download className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
          {installState === "ios" ? "Add to Home Screen" : "Install app"}
        </button>
      )}
      {installHint && (
        <p role="status" className="px-3 pb-1.5 text-xs" style={{ color: "var(--text-dim)" }}>
          {installHint}
        </p>
      )}

      <div className="my-1.5 border-t" style={{ borderColor: "var(--border)" }} />

      <button
        type="button"
        className={ROW}
        style={{ color: "var(--text)" }}
        disabled={signingOut}
        onClick={() => void signOut()}
      >
        <LogOut className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
        Sign out
      </button>
    </div>,
    document.body
  );
}
