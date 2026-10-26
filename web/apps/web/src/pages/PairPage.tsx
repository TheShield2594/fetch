import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { CheckCircle2, Tv } from "lucide-react";
import { PairingCodeForm } from "../components/settings/DevicesSettings";
import { PAGE_TITLE } from "../components/typography";

/**
 * Where a TV's QR code lands: `/pair?code=ABCD-EFGH`, the code already filled
 * in, one tap from signing that TV in.
 */
export function PairPage() {
  const [searchParams] = useSearchParams();
  const [pairedName, setPairedName] = useState<string | null>(null);

  return (
    <div className="mx-auto max-w-xl space-y-6 py-6">
      <div className="flex items-center gap-3">
        <Tv size={28} style={{ color: "var(--text-mute)" }} />
        <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Sign in a TV</h1>
      </div>
      {pairedName ? (
        <div className="space-y-3">
          <p className="flex items-center gap-2 text-base" style={{ color: "var(--text)" }}>
            <CheckCircle2 size={20} className="text-success" /> {pairedName} is signed in.
          </p>
          <p className="text-sm" style={{ color: "var(--text-dim)" }}>
            The TV finishes on its own in a few seconds. You can sign it out at any time from Settings → TVs &amp; Devices.
          </p>
          <Link to="/settings" className="btn-secondary">Manage devices</Link>
        </div>
      ) : (
        <>
          <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
            Check that the code matches the one on the TV, then confirm. The TV gets its own sign-in, which you can
            revoke without affecting anything else.
          </p>
          <PairingCodeForm initialCode={searchParams.get("code") ?? ""} onPaired={setPairedName} />
        </>
      )}
    </div>
  );
}
