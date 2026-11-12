import { useEffect, useId, useRef, useState } from "react";
import {
  Check, Film, Play, Star, X,
} from "lucide-react";
import { Poster } from "../Poster";
import { parseSizedTmdbImage, tmdbImageAtWidth } from "../../poster-widths";
import { api, type CheckIn, OfflineWriteQueuedError, type SearchResult, type TrendingMeta, type WatchEvent, type WatchProviders } from "../../api";
import { WatchDateModal } from "./WatchDateModal";
import { CheckInModal } from "./CheckInModal";
import { ExternalRatings, StarRating } from "./RatingsSection";
import { MdbListRatings, useMdbListRatings } from "./MdbListRatings";
import { ListsSection } from "./ListsSection";
import { TagsSection } from "./TagsSection";
import { CastSection, type CastMember } from "./CastSection";
import { SeasonsSection, type SeasonInfo } from "./SeasonsSection";
import { ProvidersSection } from "./ProvidersSection";
import { CheckInBlock } from "./CheckInBlock";
import { WatchHistorySection } from "./WatchHistorySection";
import { MoreActionsMenu } from "./MoreActionsMenu";
import { RecommendationsSection } from "./RecommendationsSection";
import { SectionError } from "../SectionError";
import { buildMetaLine, formatRuntime, nextEpisodeUp, statusColor, type WatchLogTarget } from "./detailPanelUtils";
import { formatRating, ratingLabel, RATING_MAX } from "../../utils/rating";
import { useFocusTrap } from "../../hooks/useFocusTrap";
import { useScrollLock } from "../../hooks/useScrollLock";
import { useEscapeKey } from "../../hooks/useEscapeKey";
import { useExitAnimation } from "../../hooks/useExitAnimation";
import type { ShowToast } from "../../hooks/useToast";
import { relogWatchEvent } from "../../utils/watchEvents";
import { invalidateDetailBundle, type PanelDetail } from "./useDetailPanel";
import { PAGE_TITLE, KICKER, MICRO_LABEL } from "../typography";

/**
 * The backdrop at the size a page-width header needs.
 *
 * The API stores every TMDB image at w500, which a 672px card could get away
 * with and a 1440px header cannot. TMDB serves the same picture at any of its
 * widths, so the hero asks for the one that fits: 780 on a phone or the tablet
 * card, 1280 on a desktop. Anything that isn't a sized TMDB URL (another
 * artwork provider, a data URI) is used as it came.
 */
function heroBackdropSources(src: string): { src: string; srcSet?: string; sizes?: string } {
  const image = parseSizedTmdbImage(src);
  if (!image) return { src };
  return {
    src: tmdbImageAtWidth(image, 1280),
    srcSet: `${tmdbImageAtWidth(image, 780)} 780w, ${tmdbImageAtWidth(image, 1280)} 1280w`,
    sizes: "(min-width: 1024px) 100vw, (min-width: 640px) 672px, 100vw",
  };
}

/* ─── Detail Panel ────────────────────────────────────────── */

export function DetailPanel({
  item,
  history,
  historyLoading,
  detail,
  detailLoading,
  detailFailed = false,
  onRetryDetail,
  onClose,
  onShowToast,
  onHistoryChange,
  onSelectItem,
  staticScrim = false,
}: {
  item: SearchResult;
  history: WatchEvent[];
  historyLoading: boolean;
  /** Cast, providers, recommendations, seasons and dropped state, fetched as one bundle by `useDetailPanel`. */
  detail: PanelDetail | null;
  detailLoading: boolean;
  /** The bundle request failed — not "this title has no cast", but "we couldn't ask". */
  detailFailed?: boolean | undefined;
  onRetryDetail?: (() => void) | undefined;
  onClose: () => void;
  onShowToast: ShowToast;
  onHistoryChange: (events: WatchEvent[]) => void;
  onSelectItem: (item: SearchResult) => void;
  /**
   * The command palette opens this panel over a scrim it already has up, and
   * takes the scrim back when the panel closes. Both backdrops are the same
   * `.overlay-scrim`, so holding this one still — no fade in, no fade out —
   * is what makes the swap read as one surface replacing another rather than
   * the whole backdrop blinking.
   */
  staticScrim?: boolean;
}) {
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const trackingHeadingId = useId();
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);

  // Undo runs from a toast that outlives the interaction that raised it: the
  // panel can have moved on to a recommendation, or the history behind it can
  // have changed. Both handlers read current values rather than the ones closed
  // over at click time.
  const currentImdbIdRef = useRef(item.imdbId);
  currentImdbIdRef.current = item.imdbId;
  const historyRef = useRef(history);
  historyRef.current = history;

  useScrollLock();
  useEscapeKey(requestClose);

  // Following a recommendation swaps the `item` prop on this same instance, so
  // nothing about the panel resets: the scroll container keeps the offset it had,
  // and the recommendations rail is the last thing in it. Choosing a title from
  // the bottom of one panel dropped you at the bottom of the next one, past its
  // poster, its overview and every control on it.
  //
  // Handled here rather than with a `key` at the six call sites: remounting would
  // replay the entrance animation over a panel that is meant to read as one
  // surface replacing another, and would cycle useScrollLock's refcount through
  // zero, which restores the body's scroll position on the way past.
  const contentRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // Assigned rather than scrollTo(): the jump should be instant, and the panel
    // underneath may be mid-`scroll-behavior: smooth`.
    if (contentRef.current) contentRef.current.scrollTop = 0;
    // The trap's own initial focus runs on mount, which this isn't. Without
    // this, focus stays on the recommendation tile that has just been replaced
    // by a different title's content.
    dialogRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- dialogRef is a stable ref from useFocusTrap
  }, [item.imdbId, item.type]);

  // Cast, providers, recommendations and seasons all arrive together in `detail`
  // — one request made by `useDetailPanel`, rather than the five this component
  // used to fire on mount. They are read straight from the prop; only the
  // dropped flag needs local state, because the button below can change it.
  const cast: CastMember[] = detail?.cast ?? [];
  const director = detail?.director ?? null;
  const providers: WatchProviders | null = detail?.providers ?? null;
  const recommendations: TrendingMeta[] = detail?.recommendations ?? [];
  const seasons: SeasonInfo[] = detail?.seasons ?? [];
  const sectionsLoading = detailLoading;
  const mdbList = useMdbListRatings(item.imdbId, item.type);

  // The header's hierarchy — see the note on it below, and on `buildMetaLine`.
  const hasRating = item.rating != null && item.rating > 0;
  const hasRuntime = item.runtime != null && item.runtime > 0;
  /*
   * The facts under the title, and the two values that used to be chips above
   * them.
   *
   * The runtime was a pill with a clock in it and the score a pill with a star,
   * on their own row between the title and the metadata — two more boxes on a
   * header the last redesign had already spent a paragraph cutting boxes from.
   * A runtime is the same kind of fact as a year, so it joins the run; the
   * score is a judgement rather than a fact, so it stays in the row but keeps
   * its colour and its star. Neither loses its place as the thing the panel is
   * usually opened to check — the row is directly under the title.
   */
  const factLine = [
    ...buildMetaLine(item),
    ...(hasRuntime ? [formatRuntime(item.runtime!)] : []),
  ];
  // Key art if the record carries it, the poster cropped to the frame if not.
  // See the hero's own note.
  const heroArt = item.background ?? item.poster ?? null;
  /*
   * Whether the hero is showing a stand-in rather than key art.
   *
   * A still is meant to fill the frame — cropping one to 16:9 is what it was
   * shot for. A poster is not: `object-cover` on a 2:3 image in a landscape
   * frame keeps its middle third, which for most posters is an actor's torso
   * and no title. So the fallback is contained inside the frame at its own
   * ratio, with the blurred copy behind it filling the sides — while real key
   * art still goes edge to edge.
   */
  const heroArtIsPoster = !item.background && !!item.poster;

  // Dropped state (series only) — seeded from the bundle, then owned here, since
  // toggling it has to show immediately rather than wait for a refetch.
  //
  // `null` means "not known yet", which is not the same as "not dropped": a
  // bundle that failed leaves `detail` null with nothing loading, and defaulting
  // to false there would offer to drop a show that is already dropped. The
  // button stays hidden until there is an answer.
  const [isDropped, setIsDropped] = useState<boolean | null>(null);
  useEffect(() => {
    setIsDropped(detail ? detail.dropped : null);
  }, [detail, item.imdbId]);

  // Check-in. Stays a request of its own: it is about the user's current
  // session rather than about this title, and it is what tells the panel
  // whether the thing playing right now is the thing being looked at.
  const [activeCheckin, setActiveCheckin] = useState<CheckIn | null>(null);
  const [checkinLoading, setCheckinLoading] = useState(true);
  const [showCheckinModal, setShowCheckinModal] = useState(false);

  // Watch log modal
  const [watchTarget, setWatchTarget] = useState<WatchLogTarget | null>(null);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    setActiveCheckin(null);
    setCheckinLoading(true);

    void api.getCheckin(controller.signal).then((r) => {
      if (cancelled) return;
      const c = r.checkin;
      const isThisItem = c && (c.imdbId === item.imdbId || c.seriesImdbId === item.imdbId);
      setActiveCheckin(isThisItem ? c : null);
      setCheckinLoading(false);
    }).catch(() => { if (!cancelled) setCheckinLoading(false); });

    return () => { cancelled = true; controller.abort(); };
  }, [item.imdbId, item.type]);

  const handleUndoDeleteEvent = async (event: WatchEvent, imdbId: string) => {
    try {
      const restored = await relogWatchEvent(event);
      // The panel may have followed a recommendation since the toast went up;
      // the re-log is what matters then, and pushing the event into a different
      // title's history would only corrupt what's on screen. A re-log can also
      // fold into an event already listed, so the insert is keyed on id.
      if (currentImdbIdRef.current !== imdbId) return;
      onHistoryChange(
        historyRef.current.some((e) => e.id === restored.id)
          ? historyRef.current
          : [...historyRef.current, restored].sort(
              (a, b) => new Date(b.watchedAt).getTime() - new Date(a.watchedAt).getTime()
            )
      );
    } catch {
      onShowToast("Could not restore watch event", "error");
    }
  };

  const handleDeleteEvent = async (event: WatchEvent) => {
    const imdbId = item.imdbId;
    try {
      await api.deleteWatchEvent(event.id);
      onHistoryChange(history.filter((e) => e.id !== event.id));
      onShowToast("Watch removed", "info", {
        action: { label: "Undo", onAction: () => void handleUndoDeleteEvent(event, imdbId) },
      });
    } catch {
      onShowToast("Failed to remove watch event", "error");
    }
  };

  const handleUndrop = async (imdbId: string) => {
    try {
      await api.undropShow(imdbId);
      if (currentImdbIdRef.current === imdbId) setIsDropped(false);
      invalidateDetailBundle(imdbId);
    } catch {
      onShowToast("Failed to update drop status", "error");
    }
  };

  const handleToggleDrop = async () => {
    const imdbId = item.imdbId;
    try {
      if (isDropped) {
        await api.undropShow(imdbId);
        setIsDropped(false);
        onShowToast("Removed from dropped shows", "info");
      } else {
        await api.dropShow(imdbId);
        setIsDropped(true);
        onShowToast("Marked as dropped", "info", {
          action: { label: "Undo", onAction: () => void handleUndrop(imdbId) },
        });
      }
      // The cached bundle carries the old dropped flag, and it is served for a
      // minute — long enough to reopen this title and see the state you just
      // changed still showing the previous value.
      invalidateDetailBundle(imdbId);
    } catch {
      onShowToast("Failed to update drop status", "error");
    }
  };

  const handleCheckin = async (season?: number, episode?: number) => {
    const runtime = item.runtime ?? undefined;
    const payload = item.type === "movie"
      ? { type: "movie" as const, imdbId: item.imdbId, name: item.name, poster: item.poster ?? undefined, runtime }
      : { type: "episode" as const, imdbId: item.imdbId, seriesImdbId: item.imdbId, name: item.name, poster: item.poster ?? undefined, season, episode, runtime };
    const res = await api.startCheckin(payload);
    setActiveCheckin(res.checkin);
    onShowToast(`Checked in to ${item.name}`, "info");
  };

  const handleCheckout = async (logWatch: boolean) => {
    await api.endCheckin(logWatch);
    setActiveCheckin(null);
    if (logWatch) {
      onShowToast("Watch logged!", "success");
      try {
        const updated = await api.getWatchHistory(50, 0, { imdbId: item.imdbId });
        onHistoryChange(updated);
      } catch { /* best-effort */ }
    } else {
      onShowToast("Checked out", "info");
    }
  };

  const handleLog = async (dateIso: string, episodeInfo?: { season: number; episode: number }, dateUnknown?: boolean) => {
    if (!watchTarget) return;
    try {
      if (watchTarget.kind === "movie") {
        await api.logWatch({ type: "movie", imdbId: watchTarget.imdbId, watchedAt: dateIso, dateUnknown });
      } else {
        await api.logWatch({
          type: "episode",
          imdbId: watchTarget.seriesImdbId,
          seriesImdbId: watchTarget.seriesImdbId,
          season: episodeInfo?.season ?? watchTarget.season,
          episode: episodeInfo?.episode ?? watchTarget.episode,
          watchedAt: dateIso,
          dateUnknown,
        });
      }
    } catch (err) {
      // The service worker has it and will send it (see sw.js). Rethrowing here
      // would leave the modal open under a red "failed" — for a watch that is
      // saved, and that the row below cannot show yet only because the event has
      // no server-minted id until it lands.
      if (!(err instanceof OfflineWriteQueuedError)) throw err;
      onShowToast(err.message, "info");
      return;
    }
    onShowToast("Watch logged!", "success");
    // Refresh history via parent
    try {
      const updated = await api.getWatchHistory(50, 0, { imdbId: item.imdbId });
      onHistoryChange(updated);
    } catch { /* best-effort */ }
  };

  const handleSelectRecommendation = (rec: TrendingMeta) => {
    onSelectItem({
      imdbId: rec.id,
      type: rec.type,
      name: rec.name,
      year: rec.year ?? null,
      poster: rec.poster ?? null,
      description: rec.description ?? null,
      genres: rec.genres ?? [],
      rating: rec.rating ?? null,
      inWatchlist: false,
      inCollection: false,
      lists: [],
    });
  };

  /*
   * What the primary action means, computed once so the button's label and the
   * modal it opens cannot name two different episodes. See `nextEpisodeUp`.
   *
   * Gated on both of its inputs having arrived. `history` and `seasons` are
   * empty while their requests are in flight, and `nextEpisodeUp` cannot tell
   * "this show has never been watched" from "we have not been told yet" — so
   * for the first moment of every series the button read `Continue · S1 E1`,
   * and pressing it in that moment opened the log modal on episode one of a
   * show you are forty episodes into. A default that wrong is worse than a
   * disabled button, because the modal accepts it.
   *
   * A film has nothing to wait for: its target is the film.
   */
  const progressionReady = item.type === "movie" || (!historyLoading && !detailLoading);
  const nextUp = item.type === "series" && progressionReady ? nextEpisodeUp(history, seasons) : null;

  const openWatchModal = () => {
    if (!progressionReady) return;
    if (item.type === "movie" || !nextUp) {
      setWatchTarget({ kind: "movie", imdbId: item.imdbId, releaseDate: item.releaseDate });
    } else {
      setWatchTarget({ kind: "episode", seriesImdbId: item.imdbId, season: nextUp.season, episode: nextUp.episode });
    }
  };

  /*
   * Check-in, for the callers that cannot wait on it.
   *
   * `handleCheckin` deliberately does not catch: `CheckInModal` awaits it and
   * keeps itself open with its own error when it rejects. The two callers that
   * The button beside the primary action fires and forgets, and was dropping
   * that rejection on the floor with `void`, so a failed check-in was a button
   * that did nothing at all.
   */
  const checkInAndReport = (season?: number, episode?: number) => {
    void handleCheckin(season, episode).catch(() => onShowToast("Could not check in", "error"));
  };

  const backdrop = item.background ? heroBackdropSources(item.background) : null;

  return (
    <>
      <div
        className={`overlay-scrim fixed inset-0 z-50 flex items-center justify-center overflow-hidden sm:p-6 lg:p-0 ${staticScrim ? "" : "overlay-fade"} ${exiting ? "overlay-exit" : ""}`}
        onClick={requestClose}
      >
        {item.background && (
          <img
            src={item.background}
            alt=""
            aria-hidden="true"
            className="absolute inset-0 hidden h-full w-full scale-110 object-cover opacity-30 blur-2xl sm:block"
          />
        )}
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-label={item.name}
          tabIndex={-1}
          onClick={(e) => e.stopPropagation()}
          onAnimationEnd={onExitAnimationEnd}
          /*
           * Three sizes of one surface.
           *
           * A phone gets the full screen, and a tablet a centred card. From
           * `lg` the panel is the page: it takes the window's width and height,
           * because a 672px card on a 1440px window was the phone sheet floated
           * in the middle of a blurred copy of the page behind it, with every
           * section in one narrow scrolling column.
           */
          className={`glass-surface overlay-panel relative flex h-full w-full max-h-screen flex-col overflow-hidden shadow-e3 sm:h-auto sm:max-h-[88vh] sm:max-w-2xl sm:rounded-3xl sm:border lg:h-full lg:max-h-none lg:max-w-[110rem] lg:rounded-none lg:border-y-0 ${exiting ? "overlay-exit" : ""}`}
          style={{ background: "var(--bg-0)", borderColor: "var(--border)" }}
        >
          {/* Close — the 36px glass circle the platform floats over artwork,
              rather than a chip borrowed from the page behind it. Hovers to
              --text, not white: the light theme's circle is translucent, and
              white-on-it made the X disappear under the pointer on the one
              theme where it mattered most. */}
          <button
            type="button" onClick={requestClose}
            className="tap-target bar-glass absolute right-4 top-4 z-30 flex h-9 w-9 items-center justify-center rounded-full transition-colors hover:text-[var(--text)] lg:right-6 lg:top-6"
            style={{ color: "var(--text-dim)" }}
            aria-label="Close detail panel"
          >
            <X className="h-4 w-4" />
          </button>

          <div ref={contentRef} className="relative z-20 min-h-0 flex-1 overflow-y-auto">
          {/* `isolate`: on a desktop the artwork sits behind the header at a
              negative z-index, and this is the stacking context that keeps it
              in front of the panel's own background. */}
          <div className="relative isolate">
          {/*
            * One set of sections, laid out twice.
            *
            * Below `lg` this is a column, and `order` puts the sections in the
            * order a phone reads them: header, the primary action, the episode
            * list, then what the title is, then your tracking, then more like
            * it. From `lg` it is a three-column grid — poster, title and
            * action, and the overview and ratings beside them — over two
            * columns of sections: content on the left, your tracking on the
            * right.
            *
            * Reordered with CSS rather than rendered twice because the sections
            * fetch for themselves: two copies of the lists control would be two
            * requests and two sets of state that disagree after the first tap.
            */}
          <div className="flex flex-col gap-6 px-5 pb-10 sm:px-8 lg:mx-auto lg:grid lg:max-w-[86rem] lg:grid-cols-[13rem_minmax(0,1fr)_minmax(0,24rem)] lg:gap-x-10 lg:gap-y-0 lg:px-12 lg:pb-16 lg:pt-[14rem]">
          {/*
            * The hero.
            *
            * A full-bleed still with the title set over the bottom of it — the
            * picture is the top of the screen rather than an illustration on
            * it. Inside the scroller, not pinned above it: a hero that stays
            * put spends 42vh of every screen on a picture you have already
            * looked at.
            *
            * On a phone this box is the picture's frame. From `lg` it is
            * `contents`: the artwork becomes a band across the top of the whole
            * panel, and the title becomes a cell of the grid above.
            */}
          <div className="relative z-0 -mx-5 flex h-[42vh] max-h-[26rem] min-h-[15rem] flex-col justify-end overflow-hidden sm:-mx-8 sm:h-[22rem] lg:contents">
            {/* `vt-title-hero`: below `lg` this is where the tapped poster
                lands when the title opens — see utils/viewTransition.ts. */}
            <div
              aria-hidden="true"
              data-title-art=""
              className="vt-title-hero absolute inset-0 overflow-hidden lg:inset-x-0 lg:bottom-auto lg:-z-10 lg:h-[36rem]"
            >
              {/*
                * Key art if the record has it, the poster if not. A poster in a
                * 16:9 crop is its middle third, so it is contained at its own
                * ratio with a blurred copy filling the sides. On a desktop the
                * poster has a place of its own in the header, so there only
                * the blurred copy is drawn behind it.
                */}
              {heroArt ? (
                <>
                  <img
                    src={heroArt}
                    alt=""
                    className="absolute inset-0 h-full w-full scale-110 object-cover blur-2xl"
                  />
                  {backdrop ? (
                    <img
                      src={backdrop.src}
                      srcSet={backdrop.srcSet}
                      sizes={backdrop.sizes}
                      alt=""
                      className="relative h-full w-full object-cover lg:object-[center_20%]"
                    />
                  ) : (
                    heroArtIsPoster && (
                      <img
                        src={heroArt}
                        alt=""
                        className="relative mx-auto h-full w-auto max-w-full object-contain lg:hidden"
                      />
                    )
                  )}
                </>
              ) : (
                <div className="flex h-full w-full items-center justify-center" style={{ background: "linear-gradient(to bottom right, var(--bg-1), var(--bg-0))" }}>
                  <Film className="h-20 w-20 lg:hidden" style={{ color: "var(--border-strong)" }} />
                </div>
              )}
              {/* Fades to --bg-0 rather than to black, so the picture dissolves
                  into the page on every theme and the title can take --text.
                  See `.title-hero-scrim` in index.css. */}
              <div className="title-hero-scrim absolute inset-0" />
            </div>

            <div className="relative px-5 sm:px-8 lg:col-start-2 lg:row-start-1 lg:self-end lg:px-0">
              {/*
                * No "MOVIE"/"SERIES" chip over the artwork: the row under the
                * title says it quietly and the rest of the panel makes it
                * obvious. The status stays, in the fact row — "Returning" and
                * "Ended" are the one thing here a reader cannot infer.
                */}
              <h2 className={`${PAGE_TITLE} lg:text-[2.75rem] lg:leading-[1.05]`} style={{ color: "var(--text)" }}>{item.name}</h2>

              {/* One line of facts, and the certification as a badge at the end
                  of it — see `buildMetaLine` for why that one leaves the run. */}
              {(factLine.length > 0 || item.certification || hasRating || item.status) && (
                <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] lg:mt-2.5 lg:text-[0.9375rem]" style={{ color: "var(--text-dim)" }}>
                  {factLine.map((part, index) => (
                    <span key={part} className="flex items-center gap-2">
                      {index > 0 && <span aria-hidden="true" style={{ color: "var(--text-mute)" }}>·</span>}
                      {part}
                    </span>
                  ))}
                  {/* The score keeps its colour — it is the one value in the
                      row that is a judgement rather than a fact, and it is
                      half of what the panel gets opened to check. */}
                  {hasRating && (
                    <span
                      className="inline-flex items-center gap-1 font-semibold text-warning"
                      title={ratingLabel(item.rating!)}
                    >
                      <Star className="h-3 w-3 fill-warning" />
                      <span className="tabular-nums">{formatRating(item.rating!)}</span>
                      <span className="font-normal tabular-nums" style={{ color: "var(--text-mute)" }}>
                        /{RATING_MAX}
                      </span>
                    </span>
                  )}
                  {item.certification?.trim() && (
                    <span
                      className="rounded-[3px] px-1 text-2xs font-bold"
                      style={{ border: "1px solid var(--border-strong)", color: "var(--text-dim)" }}
                    >
                      {item.certification.trim()}
                    </span>
                  )}
                  {/* No `ring-1` on the chip: .status-chip draws its own
                      hairline from the same token as its text, and a utility
                      ring would outrank it. */}
                  {item.status && (
                    <span className={`rounded-full px-2 py-0.5 text-2xs font-semibold ${statusColor(item.status)}`}>
                      {item.status}
                    </span>
                  )}
                </div>
              )}

              {/* Its own line rather than another segment of the row above: a
                  person is not the same kind of fact as a year or a genre, and
                  it is the one line here that names someone. */}
              {director && (
                <p className="mt-1 truncate text-[0.8125rem] lg:text-[0.9375rem]" style={{ color: "var(--text-mute)" }}>
                  {item.type === "movie" ? "Directed by " : "Created by "}
                  <span style={{ color: "var(--text-dim)" }}>{director}</span>
                </p>
              )}
            </div>
          </div>

          {/* The poster, on a desktop only: the phone's hero is the picture,
              and a poster beside the title there is the layout this panel was
              redesigned away from. */}
          <div className="vt-title-poster hidden overflow-hidden rounded-xl shadow-e3 lg:col-start-1 lg:row-span-2 lg:row-start-1 lg:block lg:self-end">
            <Poster src={item.poster ?? undefined} alt={item.name} className="aspect-[2/3] w-full" sizes="208px" />
          </div>

          {/*
            * One primary action, and the two that go with it.
            *
            * A wide filled button and square secondaries is how the platform
            * says what a screen is for. Each of these is the only one of its
            * kind on the panel: logging a watch is the primary button and
            * nowhere else, and so is checking in.
            */}
          <div className="order-1 -mt-2 lg:col-start-2 lg:row-start-2 lg:mt-6">
            <div className="flex items-stretch gap-2.5 lg:max-w-md">
              <button
                type="button"
                onClick={openWatchModal}
                disabled={!progressionReady}
                className="btn-primary h-[3.125rem] min-w-0 flex-1 rounded-2xl text-[1.0625rem]"
              >
                <Play className="h-4 w-4 translate-x-[1px] fill-current" />
                {/* Named only once the episode is known — see `progressionReady`.
                    "Continue" alone is true while we are still finding out; a
                    number would not be. */}
                {item.type === "movie"
                  ? "Log a watch"
                  : nextUp
                    ? `Continue · S${nextUp.season} E${nextUp.episode}`
                    : "Continue"}
              </button>
              <button
                type="button"
                onClick={() => (item.type === "series" ? setShowCheckinModal(true) : checkInAndReport())}
                // A series waits for its history and seasons as the primary
                // action does: the dialog this opens defaults to the next
                // episode, which is not known until both have arrived.
                disabled={checkinLoading || !!activeCheckin || !progressionReady}
                // Named for what it does rather than for its glyph, and titled as
                // well — it is a 50px square with no label under it.
                aria-label={activeCheckin ? "Already checked in" : `Check in to ${item.name}`}
                title={activeCheckin ? "Already checked in" : "Check in"}
                className="btn-secondary h-[3.125rem] w-[3.125rem] flex-none rounded-2xl px-0"
              >
                <Check className="h-[1.125rem] w-[1.125rem]" />
              </button>
              {/* Series only, and only once the dropped state is known — which
                  covers both "still loading" and "the bundle failed". Offering
                  to drop a show that is already dropped is worse than waiting. */}
              {item.type === "series" && !sectionsLoading && isDropped !== null && (
                <MoreActionsMenu isDropped={isDropped} onToggleDrop={() => void handleToggleDrop()} />
              )}
            </div>
            {/* What is playing, when it is this title. Under the button that
                started it rather than in a section of its own further down. */}
            {activeCheckin && (
              <div className="mt-3 lg:max-w-md">
                <CheckInBlock activeCheckin={activeCheckin} onCheckout={(logWatch) => void handleCheckout(logWatch)} />
              </div>
            )}
          </div>

          {/* ─── What it is ───────────────────────────────────────
              The overview and the scores. Under the episode list on a phone
              (a show you are part-way through is opened for its next episode,
              not its premise); beside the title on a desktop. */}
          <div className="order-3 space-y-6 empty:hidden lg:col-start-3 lg:row-span-2 lg:row-start-1 lg:self-end">
            {item.description && (
              <div>
                <h3 className={`mb-2 ${KICKER}`} style={{ color: "var(--text-mute)" }}>Overview</h3>
                <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>{item.description}</p>
              </div>
            )}

            {/* External ratings: the profile's MDBList ratings when it has them
                on, else the scores that came with the title. The TMDB link is
                the last entry of whichever row is drawn. */}
            {mdbList.ratings.length > 0 ? (
              <MdbListRatings imdbId={item.imdbId} ratings={mdbList.ratings} tmdbId={item.tmdbId} type={item.type} />
            ) : (
              <ExternalRatings
                imdbId={item.imdbId}
                imdbRating={item.imdbRating}
                rtScore={item.rtScore}
                mcScore={item.mcScore}
                tmdbId={item.tmdbId}
                type={item.type}
                loading={sectionsLoading || mdbList.loading}
              />
            )}
          </div>

          {/* ─── Content: episodes, where to watch, cast, more like this ───
              A wrapper only on a desktop, where it is the wide column. Below
              that it is `contents`, and its sections take their place in the
              one column by `order`. */}
          <div className="contents lg:col-span-2 lg:row-start-3 lg:mt-12 lg:block lg:space-y-10">
            {/* Episodes, providers, cast and recommendations all come from the
                one bundle, so when it fails they fail together: one error in
                their place, rather than four sections that each quietly don't
                render and leave a short page that looks like a title with no
                data. */}
            {detailFailed && onRetryDetail && (
              <div className="order-2">
                <SectionError message="Couldn't load the details for this title." onRetry={onRetryDetail} />
              </div>
            )}
            {/* The episode list leads on a series: it is the most-used surface
                on the panel, and it used to sit below the ratings, the cast,
                the lists, the stars, the tags and the drop control. */}
            {item.type === "series" && (
              <div className="order-2 empty:hidden">
                <SeasonsSection
                  imdbId={item.imdbId}
                  seasons={seasons}
                  loading={sectionsLoading}
                  onError={(msg) => onShowToast(msg, "error")}
                  onToast={(msg, type) => onShowToast(msg, type)}
                />
              </div>
            )}

            <div className="order-4 empty:hidden">
              <ProvidersSection providers={providers} loading={sectionsLoading} />
            </div>

            <div className="order-5 empty:hidden">
              <CastSection cast={cast} loading={sectionsLoading} />
            </div>

            <div className="order-8 empty:hidden">
              <RecommendationsSection
                items={recommendations}
                loading={sectionsLoading}
                onSelect={handleSelectRecommendation}
              />
            </div>
          </div>

          {/* ─── Your tracking ─── the narrow column on a desktop. */}
          <div className="contents lg:col-start-3 lg:row-start-3 lg:mt-12 lg:block lg:space-y-10">
            {/* Lists, your rating and tags as one block: three controls that
                file the same title, where there used to be three sections
                under a divider. */}
            <section
              aria-labelledby={trackingHeadingId}
              className="order-6 space-y-4 rounded-2xl border p-4"
              style={{ borderColor: "var(--border)", background: "var(--surface)" }}
            >
              <h3 id={trackingHeadingId} className={MICRO_LABEL} style={{ color: "var(--text-mute)" }}>
                Your tracking
              </h3>
              <ListsSection
                imdbId={item.imdbId}
                type={item.type}
                name={item.name}
                initialListIds={item.lists}
                onError={(msg) => onShowToast(msg, "error")}
                onToast={(msg, type) => onShowToast(msg, type)}
              />
              <StarRating imdbId={item.imdbId} type={item.type} onError={(msg) => onShowToast(msg, "error")} />
              <TagsSection imdbId={item.imdbId} type={item.type} onError={(msg) => onShowToast(msg, "error")} />
            </section>

            <div className="order-7">
              <WatchHistorySection
                history={history}
                loading={historyLoading}
                onDeleteEvent={(event) => void handleDeleteEvent(event)}
              />
            </div>
          </div>

          </div>
          </div>
          </div>
        </div>
      </div>

      {/* Watch Date Modal */}
      {watchTarget && (
        <WatchDateModal
          target={watchTarget}
          seasons={seasons}
          onLog={handleLog}
          onClose={() => setWatchTarget(null)}
        />
      )}

      {/* Check-in Modal (series only). Opens on the same episode the primary
          action names, once that is known. */}
      {showCheckinModal && item.type === "series" && (
        <CheckInModal
          seriesName={item.name}
          seasons={seasons}
          defaultSeason={nextUp?.season ?? history.find((e) => e.season != null)?.season ?? 1}
          defaultEpisode={nextUp?.episode ?? (history.find((e) => e.episode != null)?.episode ?? 0) + 1}
          onCheckIn={async (season, episode) => { await handleCheckin(season, episode); }}
          onClose={() => setShowCheckinModal(false)}
        />
      )}
    </>
  );
}
