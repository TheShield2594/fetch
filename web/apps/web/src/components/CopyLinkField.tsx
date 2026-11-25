import { Check, Copy } from "lucide-react";
import { useTransientFlag } from "../hooks/useTransientFlag";

/** A read-only link with a Copy button, for an address the user hands to something else. */
export function CopyLinkField({ label, url }: { label: string; url: string }) {
  const [copied, setCopied] = useTransientFlag();
  const [copyError, setCopyError] = useTransientFlag(3000);

  const copy = () => {
    navigator.clipboard.writeText(url).then(() => setCopied(true)).catch(() => setCopyError(true));
  };

  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2">
        <input
          readOnly
          value={url}
          aria-label={label}
          onFocus={(e) => e.currentTarget.select()}
          className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 font-mono text-xs field-control"
          style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-0)" }}
        />
        <button type="button" onClick={copy} className="btn-secondary flex-none" aria-label={`Copy ${label.toLowerCase()}`}>
          {copied ? <Check size={16} /> : <Copy size={16} />}
        </button>
      </div>
      {copyError && <p role="alert" className="text-xs text-danger">Couldn't copy. Select the link and copy it by hand.</p>}
    </div>
  );
}
