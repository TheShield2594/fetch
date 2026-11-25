# TV app and server compatibility

The TV app and the server are released separately and on different version
lines: the server is `0.x` (`FETCH_IMAGE_TAG`), the TV app `1.x` and later.
Neither version says anything about the other, so they agree on two numbers
instead:

- **`apiVersion`**, an integer the server reports at `GET /version`. It goes up
  when a route the TV calls changes in a way an older TV can't handle. The TV
  app carries the lowest `apiVersion` it needs, and stops syncing with "Update
  your Fetch server" when the server is below it. A server without
  `GET /version` (anything before 0.7.0) counts as `apiVersion` 0.
- **`minTvApp`**, the oldest TV app the server still serves. The TV sends its
  version in `X-Fetch-App-Version` on every request; a TV below `minTvApp` gets
  `426` with `code: "tv_app_too_old"` and shows "Update Fetch on this TV". The
  TV's sync report still goes through, so the website's TVs page can flag it.

The server also checks its own image against its database at startup, and
refuses to run on a database a newer image migrated (see
[troubleshooting](troubleshooting.md#images-are-on-mismatched-versions)).

## Versions

| Server (`FETCH_IMAGE_TAG`) | `apiVersion` | Oldest TV app it serves | TV app | Needs `apiVersion` |
| --- | --- | --- | --- | --- |
| 0.6.x and older | — (no `/version`) | any | 1.9.x and older | — (no check) |
| 0.7.0 and newer (2.0) | 1 | 1.9.0 | 2.0.0 and newer | 1 |

Keep this table current: add a row whenever `API_VERSION` or `MIN_TV_APP`
changes in `apps/api/src/lib/version.ts`, or the TV's
`FetchWebsiteCompatibility.MIN_SERVER_API_VERSION` changes.

## Raising the numbers

- Raise **`API_VERSION`** when a TV-facing route is removed, renamed or changes
  shape incompatibly. Adding a route or an optional field doesn't need it; add
  an entry to `FEATURES` instead so a TV can test for it.
- Raise **`MIN_TV_APP`** only when the server can no longer answer an older TV
  correctly. Everyone on that older TV loses sync until they update, so name it
  in the changelog.
- Raise the TV's **`MIN_SERVER_API_VERSION`** when the TV starts depending on a
  route only the newer `apiVersion` has.
