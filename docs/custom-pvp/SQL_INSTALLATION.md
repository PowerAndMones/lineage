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

The scripts use `CREATE TABLE IF NOT EXISTS`, but that does not update an existing table's structure. Keep the backup even when installing on a database that already has some of these tables.

## Existing installation

- If `character_pvp_stats` already exists and has the `pvpPoints` column, do **not** run the add-points migration.
- If `character_pvp_stats` exists but is missing `pvpPoints`, back up the database, confirm the column is absent, then run `dist/db_installer/sql/game/character_pvp_stats_add_points.sql` exactly once.
- If `character_pvp_stats` already has `pvpPoints` but lacks `idx_pvp_stats_points`, back up the database, confirm the index is absent, then run `dist/db_installer/sql/game/character_pvp_stats_add_points_index.sql` exactly once.
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

## Pre-release integration checklist

- [x] Confirm script discovery: `Scripts.xml` does not exclude the custom event folders, `ScriptEngine` scans the script tree recursively, and `ScriptExecutor` invokes public static `main(String[])` methods. The manager HTML paths follow the existing TvT datapack convention.
- [x] Confirm custom NPC templates exist for NPC IDs 70013 (1v1), 70014 (3v3), and 70015 (Last Hero) in `dist/game/data/stats/npcs/custom/custom.xml`.
- [ ] Verify each queue opens and spawns exactly one manager NPC after script loading; all three constructors now call `eventStart(null)` and require runtime validation.
- [ ] Confirm instance templates 3050, 3051, and 3052 load without errors and spawn/exit coordinates are valid on the target map.
- [ ] Confirm the target Mobius APIs used by all three scripts, including instance allow-lists, event listeners, teleport methods, and scheduled tasks.
- [ ] Confirm the Community Board HTML is within client display limits and all placeholders are replaced.
- [ ] Confirm SQL queries against `characters.charId` and `characters.char_name` match the target schema.
- [ ] Test online/offline return recovery, simultaneous deaths, logout, event stop, timeouts, and duplicate-finalization protection.
- [ ] Test repeat-opponent/roster cooldowns and verify unrated outcomes do not change ratings.
- [x] Build the L2J Mobius core and compile the custom PvP, TvT, CtF, and Community Board scripts in CI (Temurin JDK 27).
- [ ] Apply SQL to a backed-up test database and verify schema/query compatibility.
- [ ] Perform controlled test-server lifecycle, rating, disconnect, and return-recovery tests.

## Current status

The source and SQL installers are prepared on `feature/custom-pvp-core`. CI build and custom-script compilation passed on 2026-10-25 before the latest always-on startup edits. The latest CI run previously passed both the compile job and SQL smoke-test job; rerun compilation after source preparation is complete. The installers, database-clock cooldown queries, and streak SQL smoke tests passed against a clean MariaDB 11.4 database in CI. The latest CI run passed both the compile job and SQL smoke-test job. The target server's database has not been accessed, and no live/test server or in-game validation has been performed. The arena modes must not be enabled on a live server until the remaining checklist is completed.
