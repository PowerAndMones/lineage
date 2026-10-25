# 1v1 Arena — implementation and validation status

Status: core and custom-script compilation passed in CI; database and in-game validation pending.

## Current behavior

- Event script: `dist/game/data/scripts/custom/events/Arena1v1/Arena1v1.java`; manager NPC ID 70013.
- Eligible players join a FIFO queue through the NPC's Join, Leave, and Status actions.
- Each match gets a separate dynamic PvP instance using template 3050, with opposite Colosseum spawn points.
- The script saves both return locations before teleporting players and verifies that each participant entered the expected instance.
- Death, logout, leaving the arena, timeout, and event stop converge on an atomic finalizer. A match can be finalized only once.
- Match timeout is five minutes and produces no winner or rating change.
- Rated wins use an Elo-style K-factor of 32. Initial rating is 1000.
- The same character pair cannot produce another rated result within 30 minutes. The pair key is direction-independent.
- A repeated-pair cooldown suppresses the rating and rated statistics for that match; the player message states that no rated result was saved.
- Return locations are persisted in `character_arena1v1_return`. Login recovery uses the saved location or Giran fallback if the saved instance no longer exists. Recovery records are retained if a return teleport does not reach the expected location.
- No item rewards, combat bonuses, global kill-streak announcements, or open-world PvP points are awarded by the arena.

## Required SQL

Apply these scripts to the game database while the server is stopped and after taking a backup:

1. `dist/db_installer/sql/game/character_arena1v1_stats.sql`
2. `dist/db_installer/sql/game/character_arena1v1_return.sql`

See `SQL_INSTALLATION.md` for the full installation order and existing-database migrations.

## CI verification completed

- L2J Mobius core compiled with Temurin JDK 27.
- The 1v1 script compiled against the current branch's core and libraries.
- Custom PvP XML and SQL file-presence checks passed.

## Still required before production

- Apply the SQL and verify the rating/cooldown transaction against the deployed MySQL/MariaDB version.
- Start and stop the event through the server's admin event mechanism; verify script loading and NPC interaction.
- Test normal win, simultaneous death, logout, teleport-out, timeout, stop/restart, and failed instance startup.
- Verify Colosseum spawn points, manager/fallback coordinates, and Community Board display on the target client.
- Test disconnect and server-restart recovery with a real database.

Do not enable this arena on a live server until the remaining database and runtime checks pass.
