import { useRef, useState } from "react";
import { api, type ExportPayload } from "../../api";
import { useAccount } from "../../hooks/useAccount";
import { useProfile } from "../../hooks/useProfile";
import { Loader2, Check, AlertCircle, Download, Eraser, Upload } from "lucide-react";
import { SelectField } from "../SelectField";
import { ConfirmReview } from "../ConfirmReview";

/** The version of the Fetch export this site writes and the server reads back. */
const EXPORT_VERSION = 1;

type ExternalFormat = "letterboxd-diary" | "letterboxd-ratings" | "imdb-ratings" | "simkl";

const EXTERNAL_LABELS: Record<ExternalFormat, string> = {
  "letterboxd-diary": "Letterboxd diary export",
  "letterboxd-ratings": "Letterboxd ratings export",
  "imdb-ratings": "IMDb ratings export",
  simkl: "Simkl export",
};

const plural = (count: number, one: string, many: string) => `${count.toLocaleString()} ${count === 1 ? one : many}`;

/** "alex-s-profile" from "Alex's profile", for a download's file name. */
export const slugify = (name: string) =>
  name
    .normalize("NFKD")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, "-")
    .replace(/^-+|-+$/g, "");

/**
 * The records in a CSV file, quoted fields and line breaks inside them
 * respected, so the count a preview gives is the count the server parses.
 */
export function csvRecords(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        field += '"';
        i += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      record.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[i + 1] === "\n") i += 1;
      record.push(field);
      if (record.some((value) => value.trim())) records.push(record);
      record = [];
      field = "";
    } else {
      field += char;
    }
  }
  record.push(field);
  if (record.some((value) => value.trim())) records.push(record);
  return records;
}

/**
 * What a file will add, worked out before anything is sent, so importing is a
 * decision made with numbers in front of you rather than the moment a file is
 * picked.
 */
type ImportPreview =
  | { kind: "json"; fileName: string; payload: ExportPayload; parts: string[] }
  | { kind: "csv"; fileName: string; csv: string; rows: number }
  | { kind: "external"; fileName: string; csv: string; rows: number; format: ExternalFormat };

/** Reads a Fetch JSON export, or says why it isn't one. */
export function previewJsonExport(text: string): { payload: ExportPayload; parts: string[] } | { error: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { error: "That file isn't valid JSON. Choose a file exported from Fetch." };
  }
  if (!parsed || typeof parsed !== "object" || (parsed as { version?: unknown }).version !== EXPORT_VERSION) {
    return { error: "That file isn't a Fetch export this version can read." };
  }
  const payload = parsed as ExportPayload;
  const count = (value: unknown) => (Array.isArray(value) ? value.length : 0);
  const lists = Array.isArray(payload.lists) ? payload.lists : [];
  const items = lists.reduce((total, list) => total + count((list as { items?: unknown }).items), 0);
  const playback = count((payload as { playback?: unknown }).playback);
  const parts = [
    lists.length > 0 && `${plural(lists.length, "list", "lists")} (${plural(items, "title", "titles")})`,
    count(payload.watchEvents) > 0 && plural(count(payload.watchEvents), "watch event", "watch events"),
    count(payload.seriesProgress) > 0 && plural(count(payload.seriesProgress), "series progress entry", "series progress entries"),
    count(payload.ratings) > 0 && plural(count(payload.ratings), "rating", "ratings"),
    playback > 0 && plural(playback, "resume point", "resume points"),
  ].filter((part): part is string => Boolean(part));
  return { payload, parts };
}

const REQUIRED_CSV_COLUMNS = ["imdbid", "type", "watchedat"];

/** Reads a watch-history CSV, or says why the server would refuse it. */
export function previewHistoryCsv(text: string): { rows: number } | { error: string } {
  const [header, ...rows] = csvRecords(text);
  const columns = (header ?? []).map((column) => column.trim().toLowerCase());
  if (!REQUIRED_CSV_COLUMNS.every((column) => columns.includes(column))) {
    return { error: "The CSV's first line must name at least the columns imdbId, type and watchedAt." };
  }
  return { rows: rows.length };
}

export function DataSettings() {
  // Re-fetching every title's metadata is install-wide, and the server only
  // lets the admin do it. Export and import are per profile, for everyone.
  const { isAdmin } = useAccount();
  const { profile } = useProfile();
  const profileName = profile?.name ?? "this profile";
  const [syncing, setSyncing] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [importResult, setImportResult] = useState<string | null>(null);
  const [importError, setImportError] = useState<string | null>(null);

  const [externalFormat, setExternalFormat] = useState<ExternalFormat>("letterboxd-diary");
  const [externalResult, setExternalResult] = useState<string | null>(null);
  const [externalError, setExternalError] = useState<string | null>(null);

  const [clearing, setClearing] = useState<"history" | "ratings" | null>(null);
  const [clearBusy, setClearBusy] = useState(false);
  const [clearResult, setClearResult] = useState<string | null>(null);
  const [clearError, setClearError] = useState<string | null>(null);

  const jsonFileInputRef = useRef<HTMLInputElement>(null);
  const csvFileInputRef = useRef<HTMLInputElement>(null);
  const externalFileInputRef = useRef<HTMLInputElement>(null);

  const refreshAll = async () => {
    setSyncing(true);
    setResult(null);
    setError(null);
    try {
      const res = await api.refreshAllMetadata();
      setResult(
        res.processed === undefined
          ? `Refreshed ${res.refreshed} of ${res.total} items` // older API: total is the batch size
          : `Refreshed ${res.refreshed} of the ${res.processed} stalest items (${res.total} in library)`
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Metadata sync failed");
    } finally {
      setSyncing(false);
    }
  };

  const handleExport = async () => {
    setExporting(true);
    setImportError(null);
    try {
      const payload = await api.exportData();
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const slug = profile ? slugify(profile.name) : "";
      a.download = `fetch-export-${slug ? `${slug}-` : ""}${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setImportError(err instanceof Error ? err.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  const clearMessages = () => {
    setImportResult(null);
    setImportError(null);
    setExternalResult(null);
    setExternalError(null);
  };

  // Picking a file only reads it: nothing is sent until the preview is confirmed.
  const pickJsonFile = async (file: File) => {
    clearMessages();
    const read = previewJsonExport(await file.text());
    if ("error" in read) {
      setPreview(null);
      setImportError(read.error);
      return;
    }
    setPreview({ kind: "json", fileName: file.name, ...read });
  };

  const pickCsvFile = async (file: File) => {
    clearMessages();
    const csv = await file.text();
    const read = previewHistoryCsv(csv);
    if ("error" in read) {
      setPreview(null);
      setImportError(read.error);
      return;
    }
    setPreview({ kind: "csv", fileName: file.name, csv, rows: read.rows });
  };

  const pickExternalFile = async (file: File) => {
    clearMessages();
    const csv = await file.text();
    // Each tracker's columns are its own, and the server knows them; the
    // preview only needs to say how much is in the file.
    const rows = Math.max(0, csvRecords(csv).length - 1);
    setPreview({ kind: "external", fileName: file.name, csv, rows, format: externalFormat });
  };

  const runImport = async () => {
    if (!preview) return;
    setImporting(true);
    try {
      if (preview.kind === "json") {
        const s = (await api.importData(preview.payload)).summary;
        setImportResult(
          `Imported ${s.watchEvents} watch events, ${s.listItems} list items, ${s.seriesProgress} series progress entries, ${s.ratings} ratings` +
            (s.playback ? `, ${s.playback} resume points` : "")
        );
      } else if (preview.kind === "csv") {
        const res = await api.importCsv(preview.csv);
        setImportResult(`Imported ${res.summary.imported} watch events (${res.summary.skipped} rows skipped)`);
      } else {
        const res = await api.importExternal(preview.format, preview.csv);
        setExternalResult(
          `Imported ${res.summary.imported} watch events, ${res.summary.ratingsImported} ratings (${res.summary.skipped} rows skipped)`
        );
      }
      setPreview(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Import failed";
      if (preview.kind === "external") setExternalError(message);
      else setImportError(message);
      setPreview(null);
    } finally {
      setImporting(false);
    }
  };

  const clearData = async () => {
    if (!clearing) return;
    setClearBusy(true);
    setClearError(null);
    try {
      const { cleared } = await api.clearProfileData(clearing === "history" ? { history: true } : { ratings: true });
      setClearResult(
        clearing === "history"
          ? `Cleared ${plural(cleared.watchEvents, "watch event", "watch events")} and ${plural(cleared.playbackProgress, "resume point", "resume points")}.`
          : `Cleared ${plural(cleared.ratings, "rating", "ratings")}.`
      );
      setClearing(null);
    } catch (err) {
      setClearError(err instanceof Error ? err.message : "Could not clear the data.");
    } finally {
      setClearBusy(false);
    }
  };

  const previewMessage = (p: ImportPreview) => {
    const what =
      p.kind === "json"
        ? p.parts.length > 0
          ? p.parts.join(", ")
          : "nothing to import"
        : p.kind === "csv"
          ? plural(p.rows, "row of watch history", "rows of watch history")
          : `${plural(p.rows, "row", "rows")} from a ${EXTERNAL_LABELS[p.format]}`;
    return (
      <>
        <strong>{p.fileName}</strong> holds {what}. Importing adds it to <strong>{profileName}</strong>: it never deletes
        or replaces anything, and entries already here are skipped.
      </>
    );
  };

  const previewPanel = (where: "fetch" | "external") =>
    preview && (preview.kind === "external") === (where === "external") ? (
      <ConfirmReview
        label={`Import ${preview.fileName}?`}
        message={previewMessage(preview)}
        confirmLabel="Import"
        icon={<Upload size={14} />}
        tone="primary"
        onConfirm={() => void runImport()}
        onCancel={() => setPreview(null)}
        busy={importing}
        disabled={preview.kind === "json" ? preview.parts.length === 0 : preview.rows === 0}
        className="space-y-3 rounded-xl border p-3"
      />
    ) : null;

  return (
    <div className="space-y-6">
      {isAdmin && (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>Re-fetch metadata (posters, descriptions, etc.) for all tracked items from TMDB.</p>
        <button
          type="button"
          onClick={refreshAll}
          disabled={syncing}
          className="btn-primary"
        >
          {syncing ? <><Loader2 size={16} className="animate-spin" /> Syncing…</> : "Sync all metadata"}
        </button>
        {result && <p className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {result}</p>}
        {error && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>}
      </div>
      )}

      <div
        className={isAdmin ? "space-y-4 pt-5" : "space-y-4"}
        style={isAdmin ? { borderTop: "1px solid var(--border)" } : undefined}
      >
        <div>
          <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Export {profileName}</p>
          <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
            Download a JSON file with {profileName}&apos;s lists, watch history, series progress and ratings. It covers
            this profile only; switch profile to export another.
          </p>
        </div>
        <button
          type="button"
          onClick={handleExport}
          disabled={exporting}
          className="btn-secondary"
        >
          {exporting ? <><Loader2 size={16} className="animate-spin" /> Exporting…</> : <><Download size={16} /> Download export</>}
        </button>
      </div>

      <div className="space-y-4 pt-5" style={{ borderTop: "1px solid var(--border)" }}>
        <div>
          <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Import into {profileName}</p>
          <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
            Restore from a Fetch JSON export, or import watch history from a CSV file with columns <code>imdbId,type,season,episode,watchedAt</code>.
            You&apos;ll see what the file holds before anything is imported.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <input
            ref={jsonFileInputRef}
            type="file"
            accept="application/json"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void pickJsonFile(file);
              e.target.value = "";
            }}
          />
          <button
            type="button"
            onClick={() => jsonFileInputRef.current?.click()}
            disabled={importing}
            className="btn-secondary"
          >
            <Upload size={16} /> Import JSON export
          </button>

          <input
            ref={csvFileInputRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void pickCsvFile(file);
              e.target.value = "";
            }}
          />
          <button
            type="button"
            onClick={() => csvFileInputRef.current?.click()}
            disabled={importing}
            className="btn-secondary"
          >
            <Upload size={16} /> Import CSV history
          </button>
        </div>
        {previewPanel("fetch")}
        {importResult && <p className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {importResult}</p>}
        {importError && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {importError}</p>}
      </div>

      <div className="space-y-4 pt-5" style={{ borderTop: "1px solid var(--border)" }}>
        <div>
          <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Import from another tracker</p>
          <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
            Migrate watch history and ratings from a Letterboxd, IMDb, or Simkl export.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <SelectField
            aria-label="Import format"
            value={externalFormat}
            onChange={(e) => setExternalFormat(e.target.value as ExternalFormat)}
            className="rounded-xl px-4 py-2.5 text-sm font-medium"
          >
            {(Object.keys(EXTERNAL_LABELS) as ExternalFormat[]).map((format) => (
              <option key={format} value={format}>{EXTERNAL_LABELS[format]}</option>
            ))}
          </SelectField>
          <input
            ref={externalFileInputRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void pickExternalFile(file);
              e.target.value = "";
            }}
          />
          <button
            type="button"
            onClick={() => externalFileInputRef.current?.click()}
            disabled={importing}
            className="btn-secondary"
          >
            <Upload size={16} /> Import CSV
          </button>
        </div>
        {previewPanel("external")}
        {externalResult && <p className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {externalResult}</p>}
        {externalError && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {externalError}</p>}
      </div>

      {/* Short of deleting the profile, the only way to start a profile's
          history or ratings over. */}
      <div className="space-y-4 pt-5" style={{ borderTop: "1px solid var(--border)" }}>
        <div>
          <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Clear history or ratings</p>
          <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
            Start {profileName}&apos;s watch history or ratings over. Lists and settings stay.
          </p>
        </div>
        {clearing ? (
          <ConfirmReview
            label={clearing === "history" ? `Clear ${profileName}'s watch history?` : `Clear ${profileName}'s ratings?`}
            message={
              clearing === "history" ? (
                <>
                  Clear <strong>{profileName}</strong>&apos;s watch history? Every watched entry, the progress worked out
                  from it and its resume points go. Lists and settings stay. This can&apos;t be undone, and a connected
                  Trakt or Simkl may bring history back on its next sync.
                </>
              ) : (
                <>
                  Clear <strong>{profileName}</strong>&apos;s ratings? Every rating goes. Lists, history and settings
                  stay. This can&apos;t be undone, and a connected Trakt or Simkl may bring ratings back on its next sync.
                </>
              )
            }
            confirmLabel={clearing === "history" ? "Clear watch history" : "Clear ratings"}
            icon={<Eraser size={14} />}
            onConfirm={() => void clearData()}
            onCancel={() => setClearing(null)}
            busy={clearBusy}
            error={clearError}
            className="space-y-3 rounded-xl border p-3"
          />
        ) : (
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => {
                setClearResult(null);
                setClearError(null);
                setClearing("history");
              }}
              className="btn-secondary"
            >
              <Eraser size={16} /> Clear watch history
            </button>
            <button
              type="button"
              onClick={() => {
                setClearResult(null);
                setClearError(null);
                setClearing("ratings");
              }}
              className="btn-secondary"
            >
              <Eraser size={16} /> Clear ratings
            </button>
          </div>
        )}
        {clearResult && <p role="status" className="flex items-center gap-2 text-sm text-success"><Check size={16} /> {clearResult}</p>}
      </div>
    </div>
  );
}
