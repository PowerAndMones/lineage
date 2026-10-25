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

## Next phases

1. Add durable recovery for records that exceed retry attempts if lossless statistics are required through long database outages.
2. Add 1v1/3v3 and Last Hero using established registration, teleport, cleanup, and AntiFeed patterns.
3. Prepare full build and runtime validation after the planned features are complete.

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
