# Community Board Modernization — Interlude

## Goal
Replace the default Alt+B landing page with a compact, dark fantasy-themed dashboard while keeping the original Interlude client HTML constraints in mind. This work is isolated on `feature/community-board-modernization`, based on `feature/custom-pvp-core`; `main` is not modified.

## UI direction
- Fixed-width, table-based layout compatible with the legacy game client (no JavaScript, CSS grid/flex, remote fonts, or browser-only APIs).
- Dark panels, restrained gold accent, clear active section, concise labels.
- Character summary and server shortcuts at the top.
- Feature areas: Home, PvP rankings, Teleport, Buffer, Events, Services.
- Keep each rendered page comfortably below the Community Board 16,383-character HTML limit.

## Functional implementation plan
1. Confirm existing Community Board handlers and their actual bypass names in this source snapshot.
2. Implement the home page and register its handler through the project's existing handler bootstrap.
3. Wire each shortcut only after the target feature handler exists; do not expose dead buttons.
4. Add PvP/PK ranking queries with bounded result counts and parameterized SQL.
5. Add teleport/buffer integrations only by calling existing server-side managers; never trust client-provided prices, item IDs, or destinations.
6. Build the project and test every bypass, permissions, disabled-board behavior, and HTML packet size before opening a PR.

## Safety and compatibility requirements
- Never mutate `main` directly.
- Do not claim a feature is functional until its handler is registered and tested.
- Use server-side validation for all purchases, teleports, buffs, and character actions.
- Preserve the existing Community Board when the custom page is disabled or an unknown bypass is received.
