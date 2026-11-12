import { Link } from "react-router";
import { DotMatrixText, MarkGlyph } from "../components/BrandMark";
import { PageFrame } from "../components/PageFrame";
import { PAGE_TITLE } from "../components/typography";

/**
 * Nothing at this address.
 *
 * The number is set in the wordmark's dot matrix — the one place outside the
 * logo where the face is used at size — with the mark's dog beside it, which
 * is the 404's whole illustration. The copy says what happened and where to go,
 * and leaves the dog jokes alone.
 */
export function NotFoundPage() {
  return (
    <PageFrame width="reading" className="py-16">
      <div className="flex items-end gap-4" aria-hidden="true">
        <DotMatrixText text="404" className="h-14 w-auto sm:h-16" style={{ color: "var(--text-mute)" }} />
        <MarkGlyph className="h-10 w-auto sm:h-12" style={{ color: "var(--text-mute)", opacity: 0.7 }} />
      </div>
      <h1 className={`mt-6 ${PAGE_TITLE}`} style={{ color: "var(--text)" }}>
        Page not found
      </h1>
      <p className="mt-2 max-w-md text-[0.9375rem]" style={{ color: "var(--text-dim)" }}>
        Nothing lives at this address. The link may be old, or the page may have moved.
      </p>
      <div className="mt-6 flex flex-wrap gap-2">
        <Link to="/" className="btn-primary">
          Back to the Shelf
        </Link>
        <Link to="/search" className="btn-secondary">
          Search
        </Link>
      </div>
    </PageFrame>
  );
}
