import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { CheckCircle2 } from "lucide-react";
import { PairingCodeForm } from "../components/settings/DevicesSettings";
import { PageFrame, PageHeader } from "../components/PageFrame";

/**
 * Where a TV's QR code lands: `/pair?code=ABCD-EFGH`, the code already filled
 * in, one tap from signing that TV in.
 */
export function PairPage() {
  const [searchParams] = useSearchParams();
  const [pairedName, setPairedName] = useState<string | null>(null);

  return (
    <PageFrame width="reading" className="space-y-6">
      <PageHeader title="Sign in a TV" className="" />
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
    </PageFrame>
  );
}
