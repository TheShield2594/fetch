import { useEffect, useId, useState } from "react";
import { AlertCircle, Check, Loader2, RotateCcw } from "lucide-react";
import {
  EXPERIENCE_FEATURE,
  STREAM_LANGUAGES,
  STREAM_RESOLUTIONS,
  TV_SETTING_GROUPS,
  VIDEO_CODECS,
  argbAlpha,
  argbToHex,
  encodePalette,
  hexToArgb,
  isFieldVisible,
  readField,
  readPalette,
  readRankingRules,
  readTvValue,
  withField,
  withRankingRules,
  type RankingRules,
  type TvSettingField,
  type TvSettingSectionId,
  type TvSettingsDocument,
} from "@fetch/shared/tv-settings";
import { api } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { SelectField } from "../SelectField";
import { errorMessage, inputClass, inputStyle, isVersionConflict, labelClass, labelStyle } from "./reorder";

const groupHeading = "text-xs font-semibold uppercase tracking-wide";
const switchRow =
  "flex items-center justify-between gap-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-3 cursor-pointer";

/**
 * A number field that keeps what is being typed and only applies it on blur or
 * Enter. Applying every keystroke clamped "1" (on the way to "120") up to the
 * minimum and made a value below 1 impossible to type at all.
 */
function DraftNumberInput({
  id,
  value,
  min,
  max,
  step,
  integer = false,
  allowEmpty = false,
  onCommit,
}: {
  id: string;
  value: number | null;
  min?: number;
  max?: number;
  step: number;
  integer?: boolean;
  /** Empty means "no value" (null) rather than "put the old one back". */
  allowEmpty?: boolean;
  onCommit: (value: number | null) => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);

  const commit = () => {
    if (draft === null) return;
    setDraft(null);
    const trimmed = draft.trim();
    if (trimmed === "") {
      if (allowEmpty) onCommit(null);
      return;
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed)) return;
    let next = integer ? Math.round(parsed) : parsed;
    if (min !== undefined) next = Math.max(min, next);
    if (max !== undefined) next = Math.min(max, next);
    onCommit(next);
  };

  return (
    <input
      id={id}
      type="number"
      inputMode={integer ? "numeric" : "decimal"}
      min={min}
      max={max}
      step={step}
      value={draft ?? (value === null ? "" : String(value))}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") commit();
      }}
      className={inputClass}
      style={inputStyle}
    />
  );
}

function FieldHelp({ text }: { text?: string | undefined }) {
  if (!text) return null;
  return (
    <p className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
      {text}
    </p>
  );
}

function FieldControl({
  field,
  document,
  onChange,
}: {
  field: TvSettingField;
  document: TvSettingsDocument;
  onChange: (value: string | boolean | number | string[]) => void;
}) {
  const id = useId();
  const value = readField(document, field);

  switch (field.kind) {
    case "select":
    case "choice": {
      const current = String(value);
      const known = field.options.some((option) => String(option.value) === current);
      return (
        <div>
          <label htmlFor={id} className={labelClass} style={labelStyle}>
            {field.label}
          </label>
          <SelectField
            id={id}
            value={current}
            onChange={(e) => onChange(field.kind === "choice" ? Number(e.target.value) : e.target.value)}
            className="w-full rounded-xl px-4 py-2.5 text-sm"
            wrapperClassName="w-full"
            chevronSize={16}
          >
            {/* A value this build doesn't offer (set on a newer TV) stays selectable rather than silently changing. */}
            {!known && <option value={current}>{current}</option>}
            {field.options.map((option) => (
              <option key={String(option.value)} value={String(option.value)}>
                {option.label}
              </option>
            ))}
          </SelectField>
          <FieldHelp text={field.help} />
        </div>
      );
    }
    case "toggle":
      return (
        <div>
          <label htmlFor={id} className={`${switchRow} h-full`}>
            <span className="min-w-0">
              <span className="block text-sm" style={{ color: "var(--text)" }}>
                {field.label}
              </span>
              {field.help && (
                <span className="mt-0.5 block text-xs" style={{ color: "var(--text-mute)" }}>
                  {field.help}
                </span>
              )}
            </span>
            <input
              id={id}
              type="checkbox"
              role="switch"
              className="switch-control flex-none"
              checked={value as boolean}
              onChange={(e) => onChange(e.target.checked)}
            />
          </label>
        </div>
      );
    case "number":
      return (
        <div>
          <label htmlFor={id} className={labelClass} style={labelStyle}>
            {field.label}
            {field.unit ? ` (${field.unit})` : ""}
          </label>
          <DraftNumberInput
            id={id}
            value={value as number}
            min={field.min}
            max={field.max}
            step={field.step}
            integer={field.numberType !== "float"}
            onCommit={(next) => {
              if (next === null) return;
              // Floats land on the TV's steps (0.5 for the next-episode thresholds).
              onChange(field.numberType === "float" ? Math.round(next / field.step) * field.step : next);
            }}
          />
          <FieldHelp text={field.help} />
        </div>
      );
    case "color": {
      const argb = value as number;
      const transparent = argbAlpha(argb) === 0;
      return (
        <div>
          <span className={labelClass} style={labelStyle}>
            {field.label}
          </span>
          <div className="flex items-center gap-3">
            <input
              id={id}
              type="color"
              aria-label={field.label}
              value={argbToHex(argb)}
              onChange={(e) => onChange(hexToArgb(e.target.value, transparent ? 0xff : argbAlpha(argb)))}
              className="h-9 w-14 cursor-pointer rounded-lg border"
              style={{ borderColor: "var(--border-strong)", background: "var(--bg-1)" }}
            />
            <label className="flex items-center gap-2 text-xs" style={{ color: "var(--text-dim)" }}>
              <input
                type="checkbox"
                checked={transparent}
                onChange={(e) => onChange(hexToArgb(argbToHex(argb), e.target.checked ? 0 : 0xff))}
              />
              Transparent
            </label>
          </div>
          <FieldHelp text={field.help} />
        </div>
      );
    }
    case "multi": {
      const selected = value as string[];
      return (
        <fieldset className="sm:col-span-2">
          <legend className={labelClass} style={labelStyle}>
            {field.label}
          </legend>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {field.options.map((option) => (
              <label key={option.value} className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
                <input
                  type="checkbox"
                  checked={selected.includes(option.value)}
                  onChange={(e) =>
                    onChange(e.target.checked ? [...selected, option.value] : selected.filter((v) => v !== option.value))
                  }
                />
                {option.label}
              </label>
            ))}
          </div>
          <FieldHelp text={field.help} />
        </fieldset>
      );
    }
    case "text":
      return (
        <div className="sm:col-span-2">
          <label htmlFor={id} className={labelClass} style={labelStyle}>
            {field.label}
          </label>
          <input
            id={id}
            type="text"
            value={value as string}
            placeholder={field.placeholder}
            spellCheck={false}
            autoCapitalize="off"
            onChange={(e) => onChange(e.target.value)}
            className={`${inputClass} font-mono`}
            style={inputStyle}
          />
          <FieldHelp text={field.help} />
        </div>
      );
    case "palette": {
      const colors = readPalette(value as string, field.count);
      return (
        <fieldset>
          <legend className={labelClass} style={labelStyle}>
            {field.label}
          </legend>
          <div className="flex items-center gap-3">
            {colors.map((color, index) => (
              <input
                key={index}
                type="color"
                aria-label={`${field.label}: colour ${index + 1}`}
                value={color}
                onChange={(e) => onChange(encodePalette(colors.map((c, i) => (i === index ? e.target.value : c))))}
                className="h-9 w-14 cursor-pointer rounded-lg border"
                style={{ borderColor: "var(--border-strong)", background: "var(--bg-1)" }}
              />
            ))}
            <span
              aria-hidden="true"
              className="h-9 flex-1 rounded-lg"
              style={{ background: `linear-gradient(90deg, ${colors.join(", ")})` }}
            />
          </div>
          <FieldHelp text={field.help} />
        </fieldset>
      );
    }
  }
}

const parseKeywords = (text: string): string[] =>
  [...new Set(text.split(",").map((word) => word.trim()).filter(Boolean))];

function RankingRulesFields({ rules, onChange }: { rules: RankingRules; onChange: (rules: RankingRules) => void }) {
  const id = useId();
  const set = <K extends keyof RankingRules>(key: K, value: RankingRules[K]) => onChange({ ...rules, [key]: value });
  // Order matters to the TV — the first preferred entry wins ties — so a newly
  // ticked one goes to the end rather than into the list's display order.
  const toggleIn = (key: "preferredCodecs" | "preferredLanguages", value: string, on: boolean) =>
    set(key, on ? [...rules[key].filter((v) => v !== value), value] : rules[key].filter((v) => v !== value));

  // Kept as typed, so a trailing comma or space isn't swallowed mid-word, and
  // parsed into the rule on every change.
  const [preferredText, setPreferredText] = useState(rules.preferredKeywords.join(", "));
  const [excludedText, setExcludedText] = useState(rules.excludedKeywords.join(", "));

  const toggles: { key: "preferHdr" | "excludeDolbyVision" | "excludeCamAndTelesync" | "preferCachedDebrid"; label: string }[] = [
    { key: "preferHdr", label: "Prefer HDR" },
    { key: "excludeDolbyVision", label: "Never Dolby Vision" },
    { key: "excludeCamAndTelesync", label: "Never cam or telesync copies" },
    { key: "preferCachedDebrid", label: "Prefer cached debrid streams" },
  ];

  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-preferred`} className={labelClass} style={labelStyle}>
            Aim for
          </label>
          <SelectField
            id={`${id}-preferred`}
            value={rules.preferredResolution}
            onChange={(e) => set("preferredResolution", e.target.value)}
            className="w-full rounded-xl px-4 py-2.5 text-sm"
            wrapperClassName="w-full"
            chevronSize={16}
          >
            {STREAM_RESOLUTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </div>
        <div>
          <label htmlFor={`${id}-max`} className={labelClass} style={labelStyle}>
            Never above
          </label>
          <SelectField
            id={`${id}-max`}
            value={rules.maxResolution}
            onChange={(e) => set("maxResolution", e.target.value)}
            className="w-full rounded-xl px-4 py-2.5 text-sm"
            wrapperClassName="w-full"
            chevronSize={16}
          >
            {STREAM_RESOLUTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </div>
      </div>

      <div>
        <label htmlFor={`${id}-size`} className={labelClass} style={labelStyle}>
          Largest file (GB, empty for no limit)
        </label>
        <DraftNumberInput
          id={`${id}-size`}
          value={rules.maxSizeGb}
          min={0.1}
          step={0.5}
          allowEmpty
          onCommit={(next) => set("maxSizeGb", next)}
        />
      </div>

      <fieldset>
        <legend className={labelClass} style={labelStyle}>
          Preferred video codecs
        </legend>
        <div className="flex flex-wrap gap-3">
          {VIDEO_CODECS.map((codec) => (
            <label key={codec.value} className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
              <input
                type="checkbox"
                checked={rules.preferredCodecs.includes(codec.value)}
                onChange={(e) => toggleIn("preferredCodecs", codec.value, e.target.checked)}
              />
              {codec.label}
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend className={labelClass} style={labelStyle}>
          Preferred audio languages, in the order you tick them
        </legend>
        <div className="flex flex-wrap gap-3">
          {STREAM_LANGUAGES.map((language) => {
            const rank = rules.preferredLanguages.indexOf(language.value);
            return (
              <label key={language.value} className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
                <input
                  type="checkbox"
                  checked={rank !== -1}
                  onChange={(e) => toggleIn("preferredLanguages", language.value, e.target.checked)}
                />
                {language.label}
                {rank !== -1 && (
                  <span className="font-mono text-2xs" style={{ color: "var(--text-mute)" }}>
                    {rank + 1}
                  </span>
                )}
              </label>
            );
          })}
        </div>
      </fieldset>

      <div className="grid gap-2 sm:grid-cols-2">
        {toggles.map((toggle) => (
          <label key={toggle.key} className={switchRow}>
            <span className="text-sm" style={{ color: "var(--text)" }}>
              {toggle.label}
            </span>
            <input
              type="checkbox"
              role="switch"
              className="switch-control"
              checked={rules[toggle.key]}
              onChange={(e) => set(toggle.key, e.target.checked)}
            />
          </label>
        ))}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-prefer-words`} className={labelClass} style={labelStyle}>
            Prefer streams mentioning (comma-separated)
          </label>
          <input
            id={`${id}-prefer-words`}
            type="text"
            value={preferredText}
            onChange={(e) => {
              setPreferredText(e.target.value);
              set("preferredKeywords", parseKeywords(e.target.value));
            }}
            placeholder="REMUX, Atmos"
            className={inputClass}
            style={inputStyle}
          />
        </div>
        <div>
          <label htmlFor={`${id}-exclude-words`} className={labelClass} style={labelStyle}>
            Skip streams mentioning (comma-separated)
          </label>
          <input
            id={`${id}-exclude-words`}
            type="text"
            value={excludedText}
            onChange={(e) => {
              setExcludedText(e.target.value);
              set("excludedKeywords", parseKeywords(e.target.value));
            }}
            placeholder="HC, KORSUB"
            className={inputClass}
            style={inputStyle}
          />
        </div>
      </div>
    </div>
  );
}

/** What each page of TV settings says it covers, under its title. */
const SECTION_INTRO: Record<TvSettingSectionId, string> = {
  appearance: "The TV's colours, font and settings mode.",
  layout: "How Home, posters, Continue Watching and detail pages look on the TV.",
  playback: "Auto-play, the next episode, the player's controls and audio.",
  subtitles: "Which subtitles the TV picks, and how they look.",
  streams: "How the TV ranks streams and what the stream list shows.",
  content: "Where the TV gets extra details and recommendations from.",
};

/**
 * One page of the TV's settings for one profile, as forms. Only settings that
 * mean the same on every TV are here — the ones the TV syncs; the TV applies
 * them as soon as it next syncs, without a restart. Everything else in the
 * document is carried through a save as it was, so a newer TV's settings
 * survive an edit from here.
 *
 * Fields the TV only shows in Advanced mode are hidden here too until "Show
 * advanced settings" is on, which starts on when the profile's TV is in
 * Advanced mode; fields that only matter while another is set (pure-black
 * surfaces without AMOLED) appear with it.
 */
export function TvSettingsForm({ section = "playback" }: { section?: TvSettingSectionId }) {
  const { profile } = useProfile();
  const profileId = profile?.id ?? null;

  const [document, setDocument] = useState<TvSettingsDocument>({});
  const [version, setVersion] = useState(0);
  const [loadedAt, setLoadedAt] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState(false);
  /** Null until the person picks: then it follows the TV's mode. */
  const [showAdvancedChoice, setShowAdvancedChoice] = useState<boolean | null>(null);
  const advancedId = useId();

  // Inline rather than a shared `load()`, and setting state only once the
  // request settles, so mounting starts the request without an extra render;
  // `loading` starts true for the same reason.
  const [reloadToken, setReloadToken] = useState(0);
  useEffect(() => {
    if (!profileId) return;
    // A newer load (another profile, or a reload) supersedes this one.
    let cancelled = false;
    void (async () => {
      try {
        const result = await api.getTvSettings(profileId);
        if (cancelled) return;
        setDocument(result.settings);
        setVersion(result.version);
        setDirty(false);
        // Remounts the keyword fields, which keep their own text, onto the new rules.
        setLoadedAt((n) => n + 1);
        setLoadError(null);
      } catch (err) {
        if (cancelled) return;
        setLoadError(errorMessage(err, "Couldn't load the TV's settings."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [profileId, reloadToken]);

  /** Back to what is saved: after a conflict, or to throw away unsaved changes. */
  const reload = () => {
    setLoading(true);
    setConflict(false);
    setSaveError(null);
    setReloadToken((n) => n + 1);
  };

  const change = (next: TvSettingsDocument) => {
    setDocument(next);
    setDirty(true);
    setSaved(false);
  };

  const save = async () => {
    if (!profileId) return;
    setSaving(true);
    setSaveError(null);
    setConflict(false);
    try {
      const result = await api.saveTvSettings(profileId, document, version);
      setDocument(result.settings);
      setVersion(result.version);
      setDirty(false);
      setSaved(true);
    } catch (err) {
      if (isVersionConflict(err)) setConflict(true);
      else setSaveError(errorMessage(err, "Couldn't save the TV's settings."));
    } finally {
      setSaving(false);
    }
  };

  const tvMode = readTvValue(document, EXPERIENCE_FEATURE, "mode");
  const showAdvanced = showAdvancedChoice ?? tvMode === "ADVANCED";
  const sectionGroups = TV_SETTING_GROUPS.filter((group) => group.section === section);
  const hasAdvanced = sectionGroups.some((group) => group.fields.some((field) => field.advanced));
  const visibleByGroup = sectionGroups.map((group) => ({
    group,
    fields: group.fields.filter((field) => isFieldVisible(document, field)),
  }));
  const hiddenCount = showAdvanced
    ? 0
    : visibleByGroup.reduce((count, { fields }) => count + fields.filter((field) => field.advanced).length, 0);
  const groups = visibleByGroup.map(({ group, fields }) => ({
    group,
    fields: showAdvanced ? fields : fields.filter((field) => !field.advanced),
  }));

  if (!profileId) {
    return (
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Choose a profile to edit its TV settings.
      </p>
    );
  }

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading TV settings…
      </div>
    );
  }

  if (loadError) {
    return (
      <p role="alert" className="flex items-center gap-2 text-sm text-danger">
        <AlertCircle size={16} /> {loadError}
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        {SECTION_INTRO[section]} These follow {profile?.name ? `${profile.name}` : "this profile"} to every TV. Ones
        that depend on the TV itself, like refresh-rate matching and audio passthrough, stay on each TV.
        {version === 0 && " Nothing has been synced from a TV yet, so these show the TV's defaults."}
      </p>

      {hasAdvanced && (
        <label htmlFor={advancedId} className={switchRow}>
          <span className="min-w-0">
            <span className="block text-sm font-medium" style={{ color: "var(--text)" }}>
              Show advanced settings
            </span>
            <span className="mt-0.5 block text-xs" style={{ color: "var(--text-mute)" }}>
              {hiddenCount > 0
                ? `${hiddenCount} more ${hiddenCount === 1 ? "setting" : "settings"} the TV shows in Advanced mode.`
                : "Every setting on this page is showing."}
            </span>
          </span>
          <input
            id={advancedId}
            type="checkbox"
            role="switch"
            className="switch-control flex-none"
            checked={showAdvanced}
            onChange={(e) => setShowAdvancedChoice(e.target.checked)}
          />
        </label>
      )}

      {groups.map(({ group, fields }) =>
        fields.length === 0 ? null : (
          <section key={group.id} className="space-y-3" aria-labelledby={`tv-settings-${group.id}`}>
            <div>
              <h4 id={`tv-settings-${group.id}`} className={groupHeading} style={{ color: "var(--text-mute)" }}>
                {group.title}
              </h4>
              {group.help && (
                <p className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
                  {group.help}
                </p>
              )}
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              {fields.map((field) => (
                <FieldControl
                  key={`${field.feature}.${field.key}`}
                  field={field}
                  document={document}
                  onChange={(value) => change(withField(document, field, value))}
                />
              ))}
            </div>
          </section>
        )
      )}

      {section === "streams" && (
        <section className="space-y-3" aria-labelledby="tv-settings-ranking">
          <h4 id="tv-settings-ranking" className={groupHeading} style={{ color: "var(--text-mute)" }}>
            Best match ranking
          </h4>
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>
            How the TV picks a stream when auto-play is set to the best match.
          </p>
          <RankingRulesFields
            key={loadedAt}
            rules={readRankingRules(document)}
            onChange={(rules) => change(withRankingRules(document, rules))}
          />
        </section>
      )}

      <div
        className={`flex flex-wrap items-center gap-2 ${dirty ? "sticky bottom-3 z-10 rounded-2xl border p-3 shadow-e1" : ""}`}
        style={dirty ? { borderColor: "var(--border-strong)", background: "var(--surface-strong)" } : undefined}
      >
        <button type="button" onClick={() => void save()} disabled={saving || !dirty} className="btn-primary disabled:opacity-50">
          {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />} Save settings
        </button>
        {dirty && (
          <button type="button" onClick={reload} disabled={saving} className="btn-secondary disabled:opacity-50">
            <RotateCcw size={16} /> Discard changes
          </button>
        )}
        <span aria-live="polite" className="text-xs text-success">
          {saved && !dirty ? "Saved. The TV picks this up on its next sync." : ""}
        </span>
      </div>

      {conflict && (
        <p role="alert" className="flex flex-wrap items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> These settings were changed on another device since you opened them.
          <button type="button" onClick={reload} className="btn-secondary btn-sm">
            Load the latest
          </button>
        </p>
      )}
      {saveError && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {saveError}
        </p>
      )}
    </div>
  );
}
