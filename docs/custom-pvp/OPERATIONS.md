# Custom PvP Core — operations guide

Branch: `feature/custom-pvp-core`

## Current validation status

- CI build passed on Temurin JDK 27.
- Core and custom event scripts compiled successfully.
- XML/NPC checks passed, including unique NPC IDs 70013–70015.
- SQL installers and smoke tests passed against a clean MariaDB 11.4 database.
- A real game-server startup, the target database, and in-game lifecycle tests have **not** been verified. Use a separate test server first; do not deploy these modes directly to production.

## Database setup

1. Stop the game server and take a database backup.
2. Follow the exact installation order in `SQL_INSTALLATION.md`.
3. Verify the standard Mobius `characters` table has `charId` and `char_name` columns.
4. Do not run the migration scripts for `pvpPoints` or its index unless you have confirmed the column/index is missing.

## Global PvP settings

Configuration file: `dist/game/config/Custom/CustomPvp.ini`

- `RepeatKillCooldownSeconds = 30`: same killer-victim pair can award custom kill credit once per cooldown.
- `MaxPendingRecords = 10000`: maximum in-memory custom PvP persistence queue.
- `DatabaseRetryAttempts = 3`: bounded retry count for failed persistence.
- `RewardEventKills = False`: event kills do not award global custom kills/points by default.
- `RewardInstanceKills = False`: instance kills do not award global custom kills/points by default.

The persistence queue is in memory. A prolonged database outage can still result in records being dropped after retries or when the queue reaches its limit.

## Event administration

Use a GM/admin character and the server's event commands:

- `//event_start Arena1v1`
- `//event_stop Arena1v1`
- `//event_start Arena3v3`
- `//event_stop Arena3v3`
- `//event_start LastHero`
- `//event_stop LastHero`

The event names must match the loaded script names. If a command reports that the event is missing, check server startup logs for script loading errors before changing NPC IDs or templates.

## Player access

| Mode | Manager NPC | Instance template | Match rules |
|---|---:|---:|---|
| 1v1 Arena | 70013 | 3053 | FIFO pair queue; 5-minute timeout; 30-minute rated pair cooldown |
| 3v3 Arena | 70014 | 3051 | FIFO six-player queue; team wipe or forfeit; 10-minute timeout; 30-minute roster-pair cooldown |
| Last Hero | 70015 | 3052 | 4–10 players; 15-second start window; last living connected player wins; 10-minute timeout; 30-minute roster cooldown |

The Community Board PvP page is opened with `_bbspvp` and is linked from the custom navigation bar.

## Rating and rewards

- Ratings start at 1000 and use K=32 Elo-style adjustments.
- Repeat-pair/roster cooldowns suppress the rating and rated-stat transaction; they do not silently award a rated result.
- Timeout and no-winner outcomes do not change arena ratings.
- These modes do not grant items, combat bonuses, global PvP points, or global kill-streak announcements.
- PvP points from ordinary eligible player kills are separate from arena rating.

## Test-server checklist

Before enabling any mode on a live server, test:

- event start/stop and NPC Join/Leave/Status bypasses;
- successful instance creation and cleanup after a failed start;
- normal win, simultaneous death, timeout, logout, and teleport-out;
- stop/restart while a match or Last Hero countdown is active;
- rating and anti-farm cooldown behavior with repeated opponents/rosters;
- return-location recovery after disconnect and server restart;
- Community Board display in the target Interlude client.

Do not merge or deploy solely because CI is green; CI validates compilation and SQL smoke tests, not live game behavior.
