# Last Hero — implementation specification

Status: prototype source and SQL added; build/runtime validation pending.

## Rules
- Free-for-all mode with 4–10 players per match.
- Queue is FIFO; when at least four eligible players are waiting, the event starts with up to ten queued players.
- Every match uses its own dynamic PvP instance. Each participant receives an individual spawn.
- Last connected, living player wins. If nobody remains alive, the match ends without a winner.
- Ten-minute timeout ends the match without a winner and without rating/stat updates.
- Logout immediately removes that participant from the survivor count; there is no reconnect grace period.
- No global announcements, item rewards, combat bonuses, or open-world PvP points.

## Rating and anti-farm
- Independent character rating starts at 1000.
- Winner receives an Elo-style rating adjustment using K=32 and average rating of other participants; other players receive a reduced loss adjustment.
- Rated results update participant match/win/loss totals and streaks in one SQL transaction.
- The same complete roster, order-independent by character ID, cannot generate another rated result within 30 minutes.

## Recovery
- Return location is saved for each participant before teleporting.
- Dynamic instance is allow-listed for all participants; match startup verifies each player entered the same instance.
- Startup failures return online players and retain recovery records for offline players.
- Death, logout, timeout and event stop converge on a single atomic finalizer.
- Login recovery uses the saved return location or Giran fallback if the old instance no longer exists.

## Files
- Source: `dist/game/data/scripts/custom/events/LastHero/LastHero.java`
- Instance: `dist/game/data/instances/custom/LastHero.xml` (template 3052)
- NPC interface: `dist/game/data/scripts/custom/events/LastHero/manager.html` (NPC 70015)
- SQL: `dist/db_installer/sql/game/character_lasthero_stats.sql`

## Validation still required
- Confirm script auto-discovery, NPC template 70015, instance template 3052, bypass routing and the target Mobius API.
- Validate coordinates, rating formula and transaction behavior.
- Review queue start policy and consider a reconnect grace period.
- No build or runtime tests have been performed.
