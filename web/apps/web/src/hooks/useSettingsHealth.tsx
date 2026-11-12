import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api";
import { useAccount } from "./useAccount";
import { ProfileContext } from "./useProfile";
import { timeAgo } from "../utils/timeAgo";
import {
  jobsHealth,
  mediaServersHealth,
  optionalKeyHealth,
  trackerHealth,
  webhookHealth,
  tmdbHealth,
  traktHealth,
  type JobStatus,
  type SectionHealth,
} from "../components/settings/health";

/**
 * The state of every integration the app can report on, keyed by the Settings
 * section id it belongs to.
 *
 * Five requests, all of them a single row or a config read. That is more than
 * the Settings page used to make, and the trade is deliberate: only one section
 * is expanded on a first visit, so the panels that would otherwise answer these
 * questions never run — which is exactly why the answers weren't on screen.
 *
 * Each one is allowed to fail alone, and **a request that failed contributes no
 * entry at all**. This is the difference between "we asked and TMDB has no key"
 * and "we could not ask": both would otherwise render as `No key — artwork is
 * off`, and telling someone their key is missing because a request timed out is
 * worse than telling them nothing. A section with no entry shows no dot, and if
 * every request fails the summary line has nothing to count and says nothing.
 *
 * ── Why this is a provider ──
 *
 * It used to be a plain hook, called once, by the Settings page. The rail now
 * puts the same dots against its Sources rows, and two independent callers on
 * one screen would mean ten requests for five answers — on the Settings
 * route, where both are mounted at once.
 *
 * So one fetch lives above both of them, in the shell, and both read it.
 *
 * The result is still not written to the shared data cache. This is health: a
 * value from the last visit is worse than no value at all, because the whole
 * point of the row is to say what is true right now. Held in memory for the
 * life of the tab instead, and `refresh()` re-asks — which the Settings page
 * calls on mount, so the status board a reader has deliberately navigated to is
 * always current, while the rail's at-a-glance dots ride along on whatever the
 * last read said.
 */
export type SettingsHealth = {
  sections: Record<string, SectionHealth>;
  refresh: () => void;
};

const SettingsHealthContext = createContext<SettingsHealth | null>(null);

export function SettingsHealthProvider({ children }: { children: ReactNode }) {
  const [sections, setSections] = useState<Record<string, SectionHealth>>({});
  // Bumping this re-runs the effect below. A counter rather than a callback
  // that fetches, so a refresh that arrives while one is in flight cancels it
  // through the same `cancelled` flag as an unmount.
  const [epoch, setEpoch] = useState(0);
  // Whether a load has ever landed. Gates `refresh` — see below.
  const settledRef = useRef(false);

  const refresh = useCallback(() => {
    /*
     * Folded into the first load rather than queued behind it.
     *
     * Opening /settings directly mounts the page *inside* this provider, and
     * React runs a child's effect before its parent's — so the page asks for a
     * refresh before the provider has started its own first load. Honouring
     * that sent five requests, then five more, for the same five answers; nothing
     * aborts the first set, so both were on the wire together.
     *
     * Until a load has landed there is nothing to refresh: the one already
     * running will deliver an answer exactly as current as a re-ask would.
     * After that, every refresh is real — which is the case that matters, a
     * reader coming back to the status board later in the session.
     */
    if (!settledRef.current) return;
    setEpoch((n) => n + 1);
  }, []);

  // The install-wide integrations only the admin can set up, whose status
  // requests would fail for anyone else, are asked only for the admin. The
  // profile's own sign-ins (Simkl, MDBList, media servers) are everyone's, and
  // are asked again when the profile changes: they are that profile's answer.
  const { isAdmin } = useAccount();
  const profileId = useContext(ProfileContext)?.profile?.id ?? null;

  useEffect(() => {
    let cancelled = false;

    // A request that throws — or a client method that isn't there — is the
    // same "could not ask", and becomes `null` rather than failing the rest.
    const ask = <T,>(read: () => Promise<T>): Promise<T | null> => Promise.resolve().then(read).catch(() => null);

    void (async () => {
      // `null` here means "the request failed", which is why every mapper below
      // is guarded rather than being handed the null. `undefined` never occurs.
      const [jobs, trakt, tmdb, omdb, rpdb, simkl, mdblist, mediaServers, webhook] = await Promise.all([
        isAdmin ? ask(() => api.getJobStatus() as Promise<JobStatus>) : null,
        isAdmin ? ask(() => api.getTraktStatus()) : null,
        isAdmin ? ask(() => api.getTmdbStatus()) : null,
        isAdmin ? ask(() => api.getOmdbStatus()) : null,
        isAdmin ? ask(() => api.getRpdbStatus()) : null,
        ask(() => api.getSimklStatus()),
        ask(() => api.getMdbListStatus()),
        ask(() => api.getMediaServers()),
        ask(() => api.getWebhookKey()),
      ]);
      if (cancelled) return;

      const next: Record<string, SectionHealth> = {};
      if (tmdb) next.tmdb = tmdbHealth(tmdb);
      if (trakt) next.trakt = traktHealth(trakt, jobs, timeAgo);
      if (omdb) next.omdb = optionalKeyHealth(omdb.configured);
      if (rpdb) next.rpdb = optionalKeyHealth(rpdb.configured);
      // The Trakt row above still reads `jobs` for its timestamp, and copes
      // with it being null — a missing "synced 4m ago" is a smaller loss
      // than a wrong one. Only the row that is *about* the jobs needs them.
      if (jobs) next["job-status"] = jobsHealth(jobs);
      if (simkl) next.simkl = trackerHealth(simkl, timeAgo);
      if (mdblist) next.mdblist = trackerHealth(mdblist, timeAgo);
      if (mediaServers) next["media-servers"] = mediaServersHealth(mediaServers.servers, timeAgo);
      if (webhook) next["media-server-webhooks"] = webhookHealth(webhook);

      settledRef.current = true;
      setSections(next);
    })();

    return () => { cancelled = true; };
  }, [epoch, isAdmin, profileId]);

  const value = useMemo(() => ({ sections, refresh }), [sections, refresh]);
  return <SettingsHealthContext.Provider value={value}>{children}</SettingsHealthContext.Provider>;
}

/**
 * Every integration's state, and a way to re-ask.
 *
 * Falls back to an empty report outside a provider rather than throwing, unlike
 * `useProfile`: a missing profile makes a component wrong, whereas a missing
 * health report makes it quiet — no dots, no summary — which is exactly what
 * this hook already renders while the first five requests are in flight, and
 * what it renders forever if they all fail. A test that mounts one panel should
 * not have to stand up a provider to say nothing.
 */
export function useSettingsHealth(): SettingsHealth {
  return useContext(SettingsHealthContext) ?? EMPTY;
}

const NO_SECTIONS: Record<string, SectionHealth> = {};
const EMPTY: SettingsHealth = { sections: NO_SECTIONS, refresh: () => {} };
