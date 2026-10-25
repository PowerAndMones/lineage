# Community Board Modernization — Interlude

## Goal
Replace the default Alt+B landing page with a compact, dark fantasy-themed dashboard while keeping the original Interlude client HTML constraints in mind. This work is isolated on `feature/community-board-modernization`, based on `feature/custom-pvp-core`; `main` is not modified.

## UI direction
- Fixed-width, table-based layout compatible with the legacy game client (no JavaScript, CSS grid/flex, remote fonts, or browser-only APIs).
- Dark panels, restrained gold accent, clear active section, concise labels.
- Character summary and server shortcuts at the top.
- Feature areas: Home, PvP rankings, Teleport, Buffer, Events, Services.
- Keep each rendered page comfortably below the Community Board 16,383-character HTML limit.

## Implemented in this branch
- Intercepted `_bbshome` and `_bbspvp` in the existing Community Board dispatcher.
- Added a dark, table-based home dashboard with player name and level.
- Added a personal PvP/PK statistics page.
- Added `_bbspvptop`, a Top 10 PvP ranking using a fixed SQL query and a bounded result set.
- Highlighted the current character in Top PvP when present and added empty/non-top-ten states.
- Added `_bbsbuffer`, showing the player's saved buffer schemes and configured buffer categories from `SchemeBufferTable`.
- Added a read-only detail page for each saved scheme, listing configured buff descriptions and levels; the page validates the numeric scheme index server-side.
- Added a per-skill and estimated total cost preview using the configured static cost or each buff's configured price, and displayed the configured cost item ID; negative configured prices are clamped to zero in the display and total. This is informational only and does not charge items or apply buffs.
- Reused one ordered snapshot of scheme entries when rendering the list and resolving the selected scheme, avoiding repeated list construction and keeping the displayed indexes aligned with the detail view.
- Fixed the buffer page's return-to-home bypass quoting and avoided querying the scheme map twice.
- Escaped character names before inserting them into HTML.
- Removed a malformed leftover fragment from the buffer detail method and made the HTML escaping helper null-safe.
- Kept other server services unlinked until their handlers can be confirmed.

## Remaining verification and follow-up
1. Build the server against this source snapshot and resolve any compile issues.
2. Verify the characters table columns against the deployed database schema.
3. Test `_bbshome`, `_bbspvp`, `_bbspvptop`, `_bbsbuffer`, disabled-board behavior, ranking SQL, and HTML packet size in an Interlude client.
4. Implement teleport destinations and actual buffer application only after confirming the existing server-side APIs and configured rules; never trust client-provided prices, item IDs, or destinations.

## Safety and compatibility requirements
- Never mutate `main` directly.
- Do not claim a feature is functional until its handler is registered and tested.
- Use server-side validation for all purchases, teleports, buffs, and character actions.
- Preserve the existing Community Board when the custom page is disabled or an unknown bypass is received.
