# Custom PvP SQL installation checklist

Branch: `feature/custom-pvp-core`

This guide lists the database tables used by the custom PvP features in this branch. Apply these scripts to the **game database** while the game server is stopped and after taking a database backup. Use the database credentials and tool already used for this server's Mobius game database.

## Fresh installation

Run these files in order:

1. `dist/db_installer/sql/game/character_pvp_stats.sql`
   - General custom PvP kills, deaths, streaks and PvP points.
2. `dist/db_installer/sql/game/character_arena1v1_stats.sql`
   - 1v1 rating/statistics and persistent opponent-pair cooldown.
3. `dist/db_installer/sql/game/character_arena1v1_return.sql`
   - 1v1 saved return locations used for disconnect/restart recovery.
4. `dist/db_installer/sql/game/character_arena3v3_stats.sql`
   - 3v3 rating/statistics, team-pair cooldown and saved return locations.
5. `dist/db_installer/sql/game/character_lasthero_stats.sql`
   - Last Hero rating/statistics, roster cooldown and saved return locations.

The scripts use `CREATE TABLE IF NOT EXISTS`, but that does not update an existing table's structure. Keep the backup even when installing on a database that already has some of these tables. The two `character_pvp_stats_add_*` migration files are now safe to run from the installer on a fresh schema: they check `INFORMATION_SCHEMA` and skip a column or index that already exists.

## Existing installation

- The add-points and add-index migration files can be rerun: each checks `INFORMATION_SCHEMA` and only alters the table when its target column or index is absent. Still back up the database before any installation or schema migration.
- If an installer reports an error while running either migration, update to this branch's latest SQL files and review the installer log; do not drop the statistics table to recover.
- If a custom table is already present, inspect its columns before assuming `CREATE TABLE IF NOT EXISTS` brought it up to date.
- Do not delete custom statistics tables as a troubleshooting step; that would erase player ratings and history.

## Tables created

| SQL file | Tables |
|---|---|
| `character_pvp_stats.sql` | `character_pvp_stats` |
| `character_arena1v1_stats.sql` | `character_arena1v1_stats`, `character_arena1v1_pair_cooldown` |
| `character_arena1v1_return.sql` | `character_arena1v1_return` |
| `character_arena3v3_stats.sql` | `character_arena3v3_stats`, `character_arena3v3_team_cooldown`, `character_arena3v3_return` |
| `character_lasthero_stats.sql` | `character_lasthero_stats`, `character_lasthero_roster_cooldown`, `character_lasthero_return` |

The PvP Center leaderboards also read the standard Mobius `characters` table and expect `charId` and `char_name`. Confirm those names against the exact Mobius revision before production use.

## Custom PvP reward configuration

The custom PvP reward rules are configured in `dist/game/config/Custom/CustomPvp.ini` and loaded by `CustomPvpConfig`:

- `PointsPerKill = 1`: points for an eligible open-world kill; `0` disables point income but retains kill/streak statistics.
- `MinimumRewardLevel = 1`: minimum victim level eligible for custom kill credit and points.
- `MaxRewardLevelDifference = 0`: optional maximum absolute killer/victim level gap; `0` disables this restriction.
- `RepeatKillCooldownSeconds = 30`: repeat-kill anti-farm cooldown for the same killer/victim pair.
- `RewardEventKills = False` and `RewardInstanceKills = False`: event/instance kills do not award global PvP credit by default.

The new reward-level settings affect only the custom PvP stats and points; core PvP/PK counters and event-specific scores are unchanged. These latest config additions are not yet included in the earlier CI validation snapshot and must be covered by the combined validation pass.

## TvT and Capture the Flag reward settings

The existing TvT and Capture the Flag winner rewards are now configurable in `dist/game/config/Custom/CustomPvp.ini`:

- `TvTRewardAdena = 100000`
- `CtFRewardAdena = 100000`

Each amount is paid to each winning participant, including a team that wins by forfeit. Set the corresponding amount to `0` to disable that event's Adena payout. These settings do not change event scoring or global custom PvP points. The latest source changes still need the planned combined compile and runtime validation.

## Queue startup behavior

1v1 Arena, 3v3 Arena, and Last Hero are designed as always-available matchmaking queues. Their scripts open the queues and spawn manager NPCs when loaded; they do not require an event schedule in `config.xml` or a separate NPC spawn row. If a manager NPC fails to spawn, the mode remains closed and writes a warning to the game log. Reloading scripts stops active matches and removes the old manager before the replacement starts.

The repository's `dist/db_installer/sql/game/characters.sql` confirms the expected leaderboard columns `charId` and `char_name`; verify the deployed server database is based on the same Mobius revision before production use.

## Pre-release integration checklist

- [x] Confirm script discovery: `Scripts.xml` does not exclude the custom event folders, `ScriptEngine` scans the script tree recursively, and `ScriptExecutor` invokes public static `main(String[])` methods. The manager HTML paths follow the existing TvT datapack convention.
- [x] Confirm custom NPC templates exist for NPC IDs 70013 (1v1), 70014 (3v3), and 70015 (Last Hero) in `dist/game/data/stats/npcs/custom/custom.xml`.
- [ ] Verify each queue opens and spawns exactly one manager NPC after script loading; all three constructors now call `eventStart(null)` and require runtime validation.
- [ ] Confirm instance templates 3053 (1v1), 3051 (3v3), and 3052 (Last Hero) load without errors and spawn/exit coordinates are valid on the target map; template 3050 is reserved for Capture the Flag.
- [x] Compile-verify the target Mobius APIs used by all three scripts, including instance allow-lists, event listeners, teleport methods, and scheduled tasks, against this repository revision; runtime behavior still requires test-server validation.
- [ ] Confirm the Community Board HTML is within client display limits and all placeholders are replaced.
- [x] Confirm SQL queries against `characters.charId` and `characters.char_name` match this repository's `characters.sql`; verify the deployed database uses the same revision.
- [ ] Test online/offline return recovery, simultaneous deaths, logout, event stop, timeouts, and duplicate-finalization protection.
- [ ] Test repeat-opponent/roster cooldowns and verify unrated outcomes do not change ratings.
- [x] Build the L2J Mobius core and compile the custom PvP, TvT, CtF, and Community Board scripts in CI (Temurin JDK 27).
- [ ] Apply SQL to a backed-up test database and verify schema/query compatibility.
- [ ] Perform controlled test-server lifecycle, rating, disconnect, and return-recovery tests.

## Current status

The source and SQL installers are prepared on `feature/custom-pvp-core`. GitHub Actions run `40223733497` passed on 2026-10-25 against the current PvP source and workflow: the L2J Mobius core/custom-script compile job and SQL smoke-test job both succeeded. The SQL job applied installers to clean MariaDB 11.4, verified expected tables/columns/index, exercised database-clock cooldown and streak SQL behavior, tested both existing-install migrations, and validated unique instance template IDs. A static review found and fixed a duplicate instance template ID shared by 1v1 Arena and Capture the Flag; 1v1 now uses unique template ID `3053`. The target server's database has not been accessed, and no live/test server or in-game validation has been performed. The arena modes must not be enabled on a live server until the remaining checklist is completed.
