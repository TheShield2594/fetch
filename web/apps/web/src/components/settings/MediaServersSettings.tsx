import { type FormEvent, useEffect, useId, useState } from "react";
import { AlertCircle, ExternalLink, Loader2, RefreshCw, Trash2 } from "lucide-react";
import { api, type MediaServer, type MediaServerLibrary, type MediaServerSyncSummary, type PlexLink } from "../../api";

const PROVIDER_NAMES: Record<MediaServer["provider"], string> = { plex: "Plex", jellyfin: "Jellyfin", emby: "Emby" };
const LIBRARY_KINDS: Record<MediaServerLibrary["kind"], string> = { movies: "Films", shows: "Series", mixed: "Mixed" };

const INPUT_CLASS =
  "w-full rounded-xl border border-[var(--border)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] field-control";

export const mediaServerSummaryText = (summary: MediaServerSyncSummary): string => {
  const parts = [
    summary.movies > 0 ? `${summary.movies} film${summary.movies === 1 ? "" : "s"}` : null,
    summary.episodes > 0 ? `${summary.episodes} episode${summary.episodes === 1 ? "" : "s"}` : null,
    summary.resumePoints > 0 ? `${summary.resumePoints} to resume` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `Brought in ${parts.join(", ")}.` : "Nothing new on the server.";
};

function PasswordSignIn({ provider, onSignedIn }: { provider: "jellyfin" | "emby"; onSignedIn: () => void }) {
  const [address, setAddress] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const id = useId();
  const name = PROVIDER_NAMES[provider];

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.signInMediaServer(provider, address, username, password);
      setPassword("");
      onSignedIn();
    } catch (err) {
      setError(err instanceof Error ? err.message : `Couldn't sign in to ${name}.`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)} className="space-y-2" aria-label={`Sign in to ${name}`}>
      <p className="text-sm font-medium" style={{ color: "var(--text)" }}>{name}</p>
      <label htmlFor={`${id}-address`} className="sr-only">{name} server address</label>
      <input
        id={`${id}-address`}
        className={INPUT_CLASS}
        placeholder="Server address, e.g. https://jellyfin.example.com"
        value={address}
        onChange={(e) => setAddress(e.target.value)}
        autoComplete="url"
      />
      <label htmlFor={`${id}-user`} className="sr-only">{name} user name</label>
      <input
        id={`${id}-user`}
        className={INPUT_CLASS}
        placeholder="User name"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        autoComplete="username"
      />
      <label htmlFor={`${id}-password`} className="sr-only">{name} password</label>
      <input
        id={`${id}-password`}
        type="password"
        className={INPUT_CLASS}
        placeholder="Password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoComplete="current-password"
      />
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}
      <button type="submit" className="btn-secondary" disabled={busy || !address.trim() || !username.trim()}>
        {busy ? <Loader2 size={16} className="animate-spin" /> : null} Sign in to {name}
      </button>
    </form>
  );
}

function ServerRow({ server, onChange }: { server: MediaServer; onChange: () => Promise<void> }) {
  const [busy, setBusy] = useState<"sync" | "remove" | "toggle" | "libraries" | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const label = `${server.serverName} (${PROVIDER_NAMES[server.provider]})`;
  // An API from before libraries could be chosen leaves the field out.
  const libraries = server.libraries ?? null;

  const run = async (kind: "sync" | "remove" | "toggle" | "libraries", action: () => Promise<void>) => {
    setBusy(kind);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error ? err.message : "That didn't work.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <li className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--surface)" }}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium" style={{ color: "var(--text)" }}>{label}</p>
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>
            {server.userName ? `Signed in as ${server.userName} · ` : ""}
            {server.lastSyncedAt ? `Synced ${new Date(server.lastSyncedAt).toLocaleString()}` : "Not synced yet"}
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            className="btn-secondary"
            disabled={busy !== null}
            aria-label={`Sync ${label}`}
            onClick={() =>
              void run("sync", async () => {
                setResult(mediaServerSummaryText(await api.syncMediaServer(server.id)));
                await onChange();
              })
            }
          >
            {busy === "sync" ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />}
          </button>
          <button
            type="button"
            className="btn-secondary"
            disabled={busy !== null}
            aria-label={`Remove ${label}`}
            onClick={() =>
              void run("remove", async () => {
                await api.removeMediaServer(server.id);
                await onChange();
              })
            }
          >
            <Trash2 size={16} />
          </button>
        </div>
      </div>
      <label className="flex items-center gap-2 text-xs" style={{ color: "var(--text-dim)" }}>
        <input
          type="checkbox"
          role="switch"
          className="switch-control"
          checked={server.importWatchState}
          disabled={busy !== null}
          onChange={(e) =>
            void run("toggle", async () => {
              await api.setMediaServerImport(server.id, e.target.checked);
              await onChange();
            })
          }
        />
        Import watch progress (what you watched there, and where you stopped)
      </label>
      <fieldset className="space-y-1">
        <legend className="text-xs font-medium" style={{ color: "var(--text)" }}>Libraries to read</legend>
        {libraries === null ? (
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>All of them, until the server lists them.</p>
        ) : libraries.length === 0 ? (
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>The server has no film or series libraries.</p>
        ) : (
          libraries.map((library) => (
            <label key={library.id} className="flex items-center gap-2 text-xs" style={{ color: "var(--text-dim)" }}>
              <input
                type="checkbox"
                checked={library.selected}
                disabled={busy !== null}
                onChange={(e) =>
                  void run("libraries", async () => {
                    const selected = libraries
                      .filter((other) => (other.id === library.id ? e.target.checked : other.selected))
                      .map((other) => other.id);
                    await api.setMediaServerLibraries(server.id, selected);
                    await onChange();
                  })
                }
              />
              {library.name}
              <span style={{ color: "var(--text-mute)" }}>{LIBRARY_KINDS[library.kind]}</span>
            </label>
          ))
        )}
        <button
          type="button"
          className="text-xs underline"
          disabled={busy !== null}
          onClick={() =>
            void run("libraries", async () => {
              await api.refreshMediaServerLibraries(server.id);
              await onChange();
            })
          }
        >
          {busy === "libraries" ? "Checking..." : "Check the server for libraries"}
        </button>
      </fieldset>
      {result && <p role="status" className="text-xs">{result}</p>}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-xs text-danger">
          <AlertCircle size={14} /> {error}
        </p>
      )}
    </li>
  );
}

/**
 * This profile's Plex, Jellyfin and Emby sign-ins, as the TV has them: Plex by
 * a code entered at plex.tv/link, Jellyfin and Emby with the server's address
 * and a user name and password. Each signed-in server's watched state comes
 * into Fetch, and plays recorded here are marked played there.
 */
export function MediaServersSettings() {
  const [servers, setServers] = useState<MediaServer[] | null>(null);
  const [plexLink, setPlexLink] = useState<PlexLink | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);

  const reload = async () => {
    try {
      const loaded = await api.getMediaServers();
      setServers(loaded.servers);
      setPlexLink(loaded.plexLink);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load your media servers.");
    }
  };

  useEffect(() => {
    let cancelled = false;
    api
      .getMediaServers()
      .then((loaded) => {
        if (cancelled) return;
        setServers(loaded.servers);
        setPlexLink(loaded.plexLink);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Couldn't load your media servers.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // While a Plex code is waiting to be entered, check every few seconds.
  useEffect(() => {
    if (!plexLink) return;
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const answer = await api.pollPlexLink();
          if (answer.result === "pending") {
            setPlexLink({ ...plexLink });
            return;
          }
          setServers(answer.servers);
          setPlexLink(null);
          if (answer.result === "expired") setError("The Plex code expired before it was entered. Start again.");
        } catch (err) {
          setPlexLink(null);
          setError(err instanceof Error ? err.message : "Couldn't finish the Plex sign-in.");
        }
      })();
    }, plexLink.interval * 1000);
    return () => window.clearTimeout(timer);
  }, [plexLink]);

  const startPlex = async () => {
    setStarting(true);
    setError(null);
    try {
      setPlexLink(await api.startPlexLink());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't start the Plex sign-in.");
    } finally {
      setStarting(false);
    }
  };

  if (!servers && !error) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading...
      </div>
    );
  }

  return (
    <div className="space-y-4 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="leading-relaxed">
        Sign this profile in to your media servers, as on the TV. What you watch on them comes into Fetch, with
        where you stopped, and what you mark watched here is marked played there. This is separate from the
        TV's sign-ins. A server on your home network can only be used by the server admin's profiles.
      </p>

      {servers && servers.length > 0 && (
        <ul className="space-y-2" aria-label="Signed-in servers">
          {servers.map((server) => (
            <ServerRow key={server.id} server={server} onChange={reload} />
          ))}
        </ul>
      )}

      {error && (
        <p role="alert" className="flex items-center gap-2 text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}

      <div className="space-y-2">
        <p className="text-sm font-medium" style={{ color: "var(--text)" }}>Plex</p>
        {plexLink ? (
          <div className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
            <p>
              Go to{" "}
              <a href={plexLink.linkUrl} target="_blank" rel="noopener noreferrer" className="underline">
                plex.tv/link <ExternalLink size={12} className="inline" />
              </a>{" "}
              and enter this code:
            </p>
            <p className="font-mono text-2xl tracking-widest" style={{ color: "var(--text)" }} aria-label="Plex code">
              {plexLink.code}
            </p>
            <p className="flex items-center gap-2 text-xs" style={{ color: "var(--text-mute)" }}>
              <Loader2 size={12} className="animate-spin" /> Waiting for you to enter it...
            </p>
          </div>
        ) : (
          <button type="button" className="btn-secondary" disabled={starting} onClick={() => void startPlex()}>
            {starting ? <Loader2 size={16} className="animate-spin" /> : null} Sign in to Plex
          </button>
        )}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <PasswordSignIn provider="jellyfin" onSignedIn={() => void reload()} />
        <PasswordSignIn provider="emby" onSignedIn={() => void reload()} />
      </div>
    </div>
  );
}
