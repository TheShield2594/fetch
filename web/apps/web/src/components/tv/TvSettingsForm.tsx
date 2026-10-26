import { useEffect, useId, useState } from "react";
import { AlertCircle, Check, Loader2, RotateCcw } from "lucide-react";
import {
  STREAM_LANGUAGES,
  STREAM_RESOLUTIONS,
  TV_SETTING_GROUPS,
  VIDEO_CODECS,
  argbAlpha,
  argbToHex,
  hexToArgb,
  readField,
  readRankingRules,
  withField,
  withRankingRules,
  type RankingRules,
  type TvSettingField,
  type TvSettingsDocument,
} from "@fetch/shared/tv-settings";
import { api } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { SelectField } from "../SelectField";
import { errorMessage, inputClass, inputStyle, isVersionConflict, labelClass, labelStyle } from "./reorder";

const groupHeading = "text-xs font-semibold uppercase tracking-wide";
const switchRow =
  "flex items-center justify-between gap-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-3 cursor-pointer";

function FieldControl({
  field,
  document,
  onChange,
}: {
  field: TvSettingField;
  document: TvSettingsDocument;
  onChange: (value: string | boolean | number) => void;
}) {
  const id = useId();
  const value = readField(document, field);

  switch (field.kind) {
    case "select":
      return (
        <div>
          <label htmlFor={id} className={labelClass} style={labelStyle}>
            {field.label}
          </label>
          <SelectField
            id={id}
            value={value as string}
            onChange={(e) => onChange(e.target.value)}
            className="w-full rounded-xl px-4 py-2.5 text-sm"
            wrapperClassName="w-full"
            chevronSize={16}
          >
            {/* A value this build doesn't offer (set on a newer TV) stays selectable rather than silently changing. */}
            {!field.options.some((option) => option.value === value) && <option value={value as string}>{value as string}</option>}
            {field.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          {field.help && (
            <p className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
              {field.help}
            </p>
          )}
        </div>
      );
    case "toggle":
      return (
        <label htmlFor={id} className={switchRow}>
          <span className="text-sm" style={{ color: "var(--text)" }}>
            {field.label}
          </span>
          <input
            id={id}
            type="checkbox"
            role="switch"
            className="switch-control"
            checked={value as boolean}
            onChange={(e) => onChange(e.target.checked)}
          />
        </label>
      );
    case "number":
      return (
        <div>
          <label htmlFor={id} className={labelClass} style={labelStyle}>
            {field.label}
            {field.unit ? ` (${field.unit})` : ""}
          </label>
          <input
            id={id}
            type="number"
            min={field.min}
            max={field.max}
            step={field.step}
            value={value as number}
            onChange={(e) => {
              const parsed = Number(e.target.value);
              if (e.target.value !== "" && Number.isFinite(parsed)) {
                onChange(Math.min(field.max, Math.max(field.min, Math.round(parsed))));
              }
            }}
            className={inputClass}
            style={inputStyle}
          />
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
        </div>
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
        <input
          id={`${id}-size`}
          type="number"
          min={0.1}
          step={0.5}
          value={rules.maxSizeGb ?? ""}
          onChange={(e) => {
            const parsed = Number(e.target.value);
            set("maxSizeGb", e.target.value === "" || !(parsed > 0) ? null : parsed);
          }}
          className={inputClass}
          style={inputStyle}
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

/**
 * The TV's settings for one profile, as forms. Only settings that mean the same
 * on every TV are here — the ones the TV syncs — and only the ones the phone hub
 * also edits; everything else in the document is carried through a save as it
 * was, so a newer TV's settings survive an edit from here.
 */
export function TvSettingsForm() {
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

  // Inline rather than a shared `load()`, and setting state only once the
  // request settles, so mounting starts the request without an extra render;
  // `loading` starts true for the same reason.
  const [reloadToken, setReloadToken] = useState(0);
  useEffect(() => {
    if (!profileId) return;
    void (async () => {
      try {
        const result = await api.getTvSettings(profileId);
        setDocument(result.settings);
        setVersion(result.version);
        setDirty(false);
        // Remounts the keyword fields, which keep their own text, onto the new rules.
        setLoadedAt((n) => n + 1);
        setLoadError(null);
      } catch (err) {
        setLoadError(errorMessage(err, "Couldn't load the TV's settings."));
      } finally {
        setLoading(false);
      }
    })();
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
        <Loader2 size={16} className="animate-spin" /> Loading TV settings...
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
        Settings that follow {profile?.name ? `${profile.name}` : "this profile"} to every TV. Ones that depend on the
        TV itself, like refresh-rate matching and audio passthrough, stay on each TV.
        {version === 0 && " Nothing has been synced from a TV yet, so these show the TV's defaults."}
      </p>

      {TV_SETTING_GROUPS.map((group) => (
        <section key={group.id} className="space-y-3" aria-labelledby={`tv-settings-${group.id}`}>
          <h4 id={`tv-settings-${group.id}`} className={groupHeading} style={{ color: "var(--text-mute)" }}>
            {group.title}
          </h4>
          <div className="grid gap-3 sm:grid-cols-2">
            {group.fields.map((field) => (
              <FieldControl
                key={`${field.feature}.${field.key}`}
                field={field}
                document={document}
                onChange={(value) => change(withField(document, field, value))}
              />
            ))}
          </div>
        </section>
      ))}

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

      <div className="flex flex-wrap items-center gap-2">
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
