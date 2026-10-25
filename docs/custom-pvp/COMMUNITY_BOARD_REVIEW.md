# Community Board PvP Center review

## Scope

The PvP Center combines open-world PvP statistics with separate 1v1 Arena, 3v3 Arena, and Last Hero ratings. Each rating table depends on its matching SQL installer and the standard Mobius `characters` table.

## HTML output safety

- Escape character names before inserting them into Community Board HTML.
- The shared rating leaderboard renderer escapes `char_name` values.
- The general PvP ranking and the dedicated 1v1 ranking must apply the same escaping helper; these two code paths should be checked before release.
- Keep leaderboard values sourced from SQL result columns and do not concatenate untrusted text into SQL.

## Release checks still required

- Confirm the target Mobius schema uses `characters.charId` and `characters.char_name`.
- Install the PvP statistics and arena SQL files before opening the page.
- Verify all six leaderboard/personal-stat placeholders are replaced and no raw `%placeholder%` remains.
- Open the Community Board with empty tables and with populated tables; verify it renders within the target client's HTML limits.
- Compile against the exact Mobius revision and test in-game before production use.

No compilation, SQL execution, or runtime testing has been performed as part of this review.
