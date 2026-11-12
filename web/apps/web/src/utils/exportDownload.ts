import { api } from "../api";

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
 * Downloads the active profile's export as `fetch-export-<profile>-<date>.json`:
 * its history, lists, ratings and progress. Shared by Settings → Data and the
 * profile page, so both hand over the same file.
 */
export async function downloadProfileExport(profileName?: string | undefined): Promise<void> {
  const payload = await api.exportData();
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  const slug = profileName ? slugify(profileName) : "";
  a.download = `fetch-export-${slug ? `${slug}-` : ""}${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  URL.revokeObjectURL(url);
}
