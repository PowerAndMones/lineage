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
- Escaped character names before inserting them into HTML.
- Kept other server services unlinked until their handlers can be confirmed.

## Remaining verification and follow-up
1. Build the server against this source snapshot and resolve any compile issues.
2. Verify the characters table columns against the deployed database schema.
3. Test `_bbshome`, `_bbspvp`, `_bbspvptop`, disabled-board behavior, ranking SQL, and HTML packet size in an Interlude client.
4. Add teleport/buffer integrations only by calling existing server-side managers; never trust client-provided prices, item IDs, or destinations.

## Safety and compatibility requirements
- Never mutate `main` directly.
- Do not claim a feature is functional until its handler is registered and tested.
- Use server-side validation for all purchases, teleports, buffs, and character actions.
- Preserve the existing Community Board when the custom page is disabled or an unknown bypass is received.
