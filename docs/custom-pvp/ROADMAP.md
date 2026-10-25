# Custom PvP Core — initial design

This branch is for the custom x1000 Interlude PvP feature set. The upstream `main` branch remains unchanged.

## Implementation status

### Phase 1 — persistent PvP statistics (code added, build/runtime test pending)

- `Player.increasePvpKills()` calls `CustomPvpStatsManager` only after the existing `AntiFeedManager` check succeeds.
- `CustomPvpStatsManager` stores killer/victim totals, current streak, best streak, PvP points, and kill timestamp in one database transaction.
- `dist/db_installer/sql/game/character_pvp_stats.sql` creates the required InnoDB table, including `pvpPoints`.
- `dist/db_installer/sql/game/character_pvp_stats_add_points.sql` adds the points column to an existing table; run it only once on an existing installation.

### Phase 2 — Community Board PvP stats and ranking (code added, runtime test pending)

- Added `_bbspvp` and `_bbspvptop` commands to `HomeBoard.java`.
- Added `Custom/pvp.html` with personal stats and a top-10 leaderboard.
- Added a `PvP Ranking` navigation button.
- The page reads from `character_pvp_stats` and ranks players by PvP points, then kills and best streak. Install the SQL file before testing.

### Phase 3 — anti-farm and PvP points (code added, build/runtime test pending)

- The same killer-victim pair can award custom kill credit and one PvP point only once per configured cooldown.
- Repeated kills during the cooldown still count as victim deaths and reset the victim's current streak; only the killer's custom kill/point award is suppressed.
- The cooldown is configured in `config/Custom/CustomPvp.ini` (`RepeatKillCooldownSeconds`, default 30; `0` disables the cooldown). It is in memory and resets on server restart; it does not change the core PvP counter.
- The Community Board ranking is sorted by PvP points first.

### Phase 4 — asynchronous persistence, retry policy, and queue bounds (code added, build/runtime test pending)

- PvP kill records are queued and persisted by one background worker, preserving processing order for streak updates.
- Added queue size diagnostics and configurable backlog warnings.
- SQL writes retry up to `DatabaseRetryAttempts` times (default 3) with short exponential delays.
- `MaxPendingRecords` bounds the in-memory queue (default 10,000). If full, new custom-stat records are dropped with a severe log message until the backlog drains.
- A record still failing after all retries is logged and dropped. This does not guarantee lossless persistence during a prolonged database outage; a durable queue would be needed for that guarantee.

### Phase 5 — PvP achievements (code added, runtime test pending)

- Added achievement progress to the existing Community Board PvP page; achievements are quiet and never send global or chat announcements.
- Kill milestones: First Blood (1), PvP Veteran (100), Arena Legend (500), War Machine (1,000).
- Best-streak milestones: On a Roll (5), Unstoppable (10), Legendary Streak (20).
- PvP point milestones: Point Collector (100), Point Hunter (500), Point Master (1,000).
- Progress is derived from saved statistics, so no extra achievement table or migration is needed.

### Phase 6 — fake-player exclusion (code added, build/runtime test pending)

- Mobius fake players are represented as NPCs, while custom PvP stats are entered through the Player-to-Player kill path.
- Added an explicit defensive fake-player check in `CustomPvpStatsManager` so fake players cannot receive custom PvP kill credit or points if the call path changes later.
- Existing `AntiFeedManager` validation remains in front of the custom stats call.

### Phase 7 — existing event safety (code added, build/runtime test pending)

- TvT and CtF death handlers ignore deaths of characters who are not registered participants.
- Kill scoring checks that the killer is a registered participant with an initialized score entry before updating team/player scores.
- CtF handles deaths without a player killer without dereferencing a null attacker; flag return handling still runs for a registered carrier.

### Phase 8 — global PvP reward eligibility (code added, build/runtime test pending)

- Added `RewardEventKills` and `RewardInstanceKills` settings to `CustomPvp.ini`; both default to `False`.
- By default, kills where either player is marked as participating in an event, or where either player is in a non-zero instance, do not award global custom kills or PvP points.
- These kills still update the victim's custom death count and reset their current streak.
- Event/instance kills do not consume the open-world killer-victim anti-farm cooldown.
- Existing core PvP counters and event-specific scoring are left unchanged.

### Phase 9 — 1v1 Arena matchmaking prototype (code added, build/runtime test pending)

- Added an Arena Manager NPC interface with Join, Leave, and Status actions.
- Eligible players are queued and paired through Mobius' native `DuelManager`; the native duel flow remains responsible for the fight itself.
- Queue lifecycle operations are synchronized to reduce duplicate pairing and stop/start races.
- Starting the event clears stale queue registrations; stopping it clears queued players' event-registration flags.
- Players already participating in another event are rejected from registration.
- A logout listener removes disconnected players from the waiting queue; match eligibility is checked again before pairing.
- The queue waits for the native duel countdown and verifies both players entered the same duel before announcing that the fight started; failed starts produce an error message instead of a false success.
- No global announcements, custom rewards, PvP points, or ranking points are issued by this prototype.
- This is not yet an isolated arena instance and is not production-ready. NPC template `70013`, script discovery, duel rejection behavior, and disconnect races remain unverified until the planned final validation.

### Phase 10 — isolated 1v1 arena prototype (source added, build/runtime test pending)

- Added custom instance template `3050` for 1v1 Arena.
- Each matched pair gets a dynamic instance, saved return locations, allow-list registration, and opposite spawn points.
- Uses PvP instance combat rather than the native duel manager because native duels use their own fixed teleport path.
- Death/logout finalization is guarded against duplicate processing; stopping the event attempts to close active matches.
- Added a five-minute match timeout; timed-out matches close without a winner or reward.
- Return locations are persisted in `character_arena1v1_return` before participants enter the arena and restored on login after disconnect/restart.
- Added SQL installer `dist/db_installer/sql/game/character_arena1v1_return.sql`; it must be applied before runtime use.
- Spawn validation and full lifecycle testing remain outstanding.
- No custom rewards, rating, PvP points, or global announcements are issued.

## Next phases

1. Harden the isolated 1v1 prototype and rated result integration: validate instance/spawn behavior, database recovery, transaction locking, and death/logout/timeout race conditions.
2. Add Community Board display for 1v1 rating, wins/losses, and leaderboard after source integration is validated.
3. Harden and validate the 1v1, 3v3 and Last Hero prototypes before enabling them on a live server.
4. Add durable recovery for custom PvP-stat records that exceed retry attempts if lossless persistence is required through long database outages.
5. Prepare full build and runtime validation only after the planned source changes are complete.

## Design constraints

- No pay-to-win combat bonuses.
- Cosmetic rewards and convenience features only.
- Never reward fake-player kills.
- Keep existing PvP/PK counters and upstream behavior intact.
- Do not enable reward drops globally until anti-farm checks and tests are in place.

## Integration points inspected

- `java/org/l2jmobius/gameserver/entity/actor/Player.java`: `increasePvpKills()` and event/instance state accessors.
- `java/org/l2jmobius/gameserver/config/custom/PvpRewardItemConfig.java`: existing reward configuration.
- `dist/game/data/scripts/custom/events/TeamVsTeam/TvT.java` and `dist/game/data/scripts/custom/events/CaptureTheFlag/CtF.java`: existing event scripts.
- `dist/game/data/scripts/handlers/bypass/communityboard/HomeBoard.java`: Community Board integration.

The persistent-statistics code and SQL table have been added to this feature branch. Compilation and runtime behavior have not yet been verified.


### Phase 11 — dedicated 1v1 rating/statistics (source added, build/runtime test pending)

- Added independent 1v1 stats: matches, wins, losses, rating (starting at 1000), current win streak, best win streak, and last match time.
- Valid winner/loser outcomes update both characters in one database transaction using an Elo-style K-factor of 32.
- A persistent pair cooldown suppresses rating/stat updates for repeat opponents within 30 minutes; sorted character IDs make the cooldown direction-independent.
- Timeout and simultaneous-death endings have no winner and do not change rated statistics. No item rewards, global announcements, or general PvP points are granted.
- SQL installer: `dist/db_installer/sql/game/character_arena1v1_stats.sql`. Apply it before runtime use.
- The source has not been compiled or runtime-tested. Community Board rating display remains a follow-up phase.


### Phase 12 — Community Board 1v1 rating (source added, build/runtime test pending)

- The existing PvP Center now shows a separate 1v1 Arena record: rating, matches, wins, losses, current win streak, and best win streak.
- Added a top-10 1v1 leaderboard ordered by rating, then wins and match count.
- The general open-world PvP ranking remains separate and unchanged.
- The page requires `character_arena1v1_stats.sql` and the standard Mobius `characters` table.
- Source has not been compiled or runtime-tested. Verify the character table column names and HTML rendering on the target revision during final validation.


### Phase 13 — isolated 3v3 Arena prototype (source and SQL added; build/runtime validation pending)

- Added a 3v3 lifecycle and rating specification in `docs/custom-pvp/ARENA_3V3.md`.
- Added a separate statistics table for per-player 3v3 matches, wins, losses, Elo-style rating, streaks, and last match time.
- Added persistent roster-pair cooldown storage for anti-farm protection; roster hashes must be based on sorted character IDs.
- Added `Arena3v3.java`: FIFO queue reserves six eligible players, assigns the first three to Team A and next three to Team B, creates an isolated dynamic instance, and persists each return location before teleporting.\n- Added a separate PvP instance template (3051), manager HTML (NPC 70014), and persistent return-location table.\n- Team wipe, timeout, logout-forfeit, one-time finish guard, team-level Elo calculation, and 30-minute canonical roster-hash cooldown are implemented in the prototype.\n- The SQL schema does not enable the event by itself; the script must be discovered/registered by the target Mobius script loader and NPC template 70014 must exist.\n- Source has not been compiled or runtime-tested; lifecycle/API integration remains unverified.
- The implementation must reuse the isolated-instance/recovery principles from 1v1 but support six players and team-level result resolution.


### Phase 14 — Last Hero free-for-all prototype (source and SQL added; build/runtime validation pending)

- Added `LastHero.java` with FIFO queue, 4–10 players per match, individual spawn points, isolated dynamic instance, death-based survivor detection, timeout and event-stop cleanup.
- Last connected living participant wins; a match with no surviving player or a timeout has no winner and no rating changes.
- Added independent Last Hero rating/statistics, persistent order-independent full-roster anti-farm cooldown (30 minutes), and saved return locations.
- Added instance template `3052`, manager NPC interface `70015`, and SQL installer `character_lasthero_stats.sql`.
- No global announcements, item rewards, combat bonuses, or open-world PvP points are awarded.
- Prototype is not production-ready. Script discovery, NPC template, instance template, target API compatibility, coordinates, queue policy and rating math require validation. No build/runtime tests have been run.
