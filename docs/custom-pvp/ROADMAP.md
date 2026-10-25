# Custom PvP Core — roadmap and validation status

This branch is for the custom x1000 Interlude PvP feature set. The upstream `main` branch remains unchanged.

## Current validation snapshot — 2026-10-25

- GitHub Actions passed: L2J Mobius core compilation on Temurin JDK 27.
- GitHub Actions passed: compilation of 1v1 Arena, 3v3 Arena, Last Hero, TvT, CtF, and Community Board scripts against the current core/libraries.
- GitHub Actions passed: XML parsing, expected instance-template ID/exit-point checks, global uniqueness checks for NPC IDs 70013–70015, SQL installer file-presence checks, and whitespace validation.
- The SQL installers executed against a clean MariaDB 11.4 database; the smoke tests verified the expected 10 tables, `pvpPoints` column/index, and win-streak progression/reset logic.
- The CI workflow also passed the three database-clock cooldown query checks under transactions/row locks for 1v1, 3v3, and Last Hero.
- Not yet done: execute SQL against the target server database, start the game server, test event/NPC loading, or run in-game match and recovery scenarios.
- The phase entries below are a chronological implementation record; earlier notes saying “build pending” describe the status at that historical point. This snapshot supersedes those earlier build-status notes.


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

## Remaining work before production

1. Apply the documented SQL installers to a backed-up test database and verify every query and transaction.
2. Start the server and confirm script discovery, event admin start/stop, NPC spawn and bypass routing.
3. Test 1v1, 3v3, and Last Hero with real players: wins, simultaneous deaths, disconnects, teleport-out, timeouts, stop/restart, and failed instance startup.
4. Validate spawn/fallback coordinates and verify return-location recovery after disconnect and server restart.
5. Review Community Board rendering and leaderboards on the target Interlude client.
6. Keep the features disabled on production until all runtime and database checks pass.

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


### Phase 15 — Unified PvP Center (source and HTML added; runtime validation pending)

- Extended the existing Community Board PvP Center with personal statistics and top-10 leaderboards for 3v3 Arena and Last Hero.
- The page now keeps open-world PvP ranking, 1v1 Arena, 3v3 Arena, and Last Hero ratings in separate sections.
- Added shared leaderboard rendering and escaped character names before inserting them into HTML.
- The 3v3 and Last Hero sections require their respective SQL installers, as well as the standard Mobius `characters` table for character names.
- Updated `dist/game/data/html/CommunityBoard/Custom/pvp.html` and `HomeBoard.java`.
- No build or runtime tests have been performed. Verify Community Board rendering, database column names, and HTML size/scrolling on the target client.


### Phase 16 — SQL installation and integration checklist (documentation added; validation pending)

- Added `docs/custom-pvp/SQL_INSTALLATION.md` with the exact SQL files for fresh installs and the conditional migration for older `character_pvp_stats` tables.
- Documented which SQL files create the 1v1, 3v3 and Last Hero statistics, cooldown and return-location tables.
- Added a pre-build integration checklist for script discovery, NPC IDs, instance templates, Mobius API compatibility, Community Board SQL schema and match lifecycle scenarios.
- This phase is documentation only. No SQL was executed, no build was run, and no runtime tests were performed.


### Phase 17 — pre-build source review (documentation and message corrections added; validation pending)

- Added `docs/custom-pvp/SOURCE_REVIEW.md` with a focused checklist for script discovery, event lifecycle, instances, recovery, rating integrity, database schema, and Community Board rendering.
- Corrected the 1v1, 3v3, and Last Hero repeat-match cooldown messages to state that rating and rated counters remain unchanged; the cooldown transaction rolls back rather than recording a rated result.
- This is still source-level review only. No compilation, database execution, or in-game testing has been performed.

### Phase 18 — Community Board leaderboard output hardening (source updated; validation pending)

- Escaped character names in the general PvP leaderboard and the 1v1 Arena leaderboard before inserting them into Community Board HTML.
- These paths now use the same shared escaping helper already used by the 3v3 and Last Hero rating leaderboards.
- Added `docs/custom-pvp/COMMUNITY_BOARD_REVIEW.md` with HTML-safety and release checks.
- No compilation, SQL execution, or runtime tests have been performed.

### Phase 19 — arena return-location safety (source updated; validation pending)

- Hardened match finalization in 1v1 Arena, 3v3 Arena, and Last Hero: if a participant's saved return location points to an instance that no longer exists, the finalizer sends them to the configured safe fallback instead of attempting to return them to the stale instance.
- The existing login-recovery fallback remains in place for players who disconnect or reconnect after an instance has been destroyed.
- Updated `docs/custom-pvp/SOURCE_REVIEW.md` with the corresponding release check.
- This is source-level hardening only. No build, SQL execution, or runtime test was run; exact Mobius API compatibility and in-game teleport behavior remain unverified.

### Phase 20 — 3v3 winner validation (source updated; validation pending)

- Tightened the 3v3 finalizer so it only records a winning team when at least one member is both connected and alive.
- The previous check could skip this requirement when no team member was marked dead, even if the team had no connected survivor during a logout/finalization race.
- Updated the source review checklist to cover this race path.
- No compilation or runtime tests were run; validate concurrent logout/death scenarios on a test server.


### Phase 21 — rating formula correction (source updated; validation pending)

- Reviewed the 3v3 team Elo formula and restored the complementary expected-score loss calculation.
- Corrected Last Hero's loser delta to distribute the winner's rating gain across all opponents rather than scaling losses by the winner's expected score.
- These changes are source-level only. No compilation, database execution, or runtime tests have been performed; verify rating changes for underdog and favorite scenarios before launch.


### Phase 22 — matchmaking failure recovery (source updated; validation pending)

- Prevented 1v1 instance-start failures from immediately looping through the same queue entries on the matchmaking thread; retries now run after a delay.
- Hardened 3v3 queue processing when ineligible players are removed and more than six queued players remain; match-start retries are delayed to avoid a tight loop if the instance cannot be created.
- Last Hero now schedules another registration window after a failed match startup when enough eligible players were returned to the queue.
- These are source-level lifecycle fixes only. No compilation, SQL execution, or runtime tests have been performed.


### Phase 23 — PvP persistence queue contention (source updated; validation pending)

- Replaced repeated `ConcurrentLinkedQueue.size()` calls in the custom PvP kill path with an `AtomicInteger` pending counter, avoiding linear-time queue scans during mass PvP.
- Queue slot reservation is now atomic, so concurrent kill events cannot exceed `MaxPendingRecords` through a check-then-add race.
- The counter tracks queued records and is decremented when the worker removes a record for processing. Database retry/drop behavior remains unchanged.
- No compilation, load testing, or runtime tests have been performed; verify counter consistency under concurrent producers and worker shutdown/restart conditions.


### Phase 24 — event queue reward eligibility (source updated; validation pending)

- Custom PvP reward eligibility now checks both active event participation and event registration, so queued players do not earn global kill credit or points while waiting.
- Victim death and streak-reset tracking remains governed by the existing policy when killer rewards are suppressed.
- No build or runtime tests have been run. Confirm the target Mobius `isRegisteredOnEvent()` semantics before release.


### Phase 25 — database-clock cooldown checks (source updated; validation pending)

- The 1v1, 3v3, and Last Hero rating transactions now calculate cooldown age with `TIMESTAMPDIFF` and the database's `CURRENT_TIMESTAMP`, instead of comparing SQL `DATETIME` values to the game JVM clock.
- This avoids cooldown decisions changing when the database and game server use different time zones or clock settings.
- No SQL execution or runtime tests have been performed; verify the query syntax against the exact MySQL/MariaDB version used by the target server.


### Phase 26 — best-streak SQL assignment order (source updated; validation pending)

- Corrected the general PvP, 3v3, and Last Hero SQL update order so `bestStreak` / `bestWinStreak` is calculated from the previous streak plus one before the current streak is incremented.
- MySQL single-table updates can evaluate assignments left-to-right, so calculating the best streak after updating the current streak could overcount a winning streak by one. The 1v1 query already used the safe order.
- No database execution or runtime tests have been performed; verify streak progression with sequences such as 1, 2, 3 consecutive wins and a loss/reset.


### Phase 27 — arena instance ID guard (source updated; validation pending)

- Added explicit validation that the dynamic instance ID is positive before registering an arena world or teleporting participants in 1v1, 3v3, and Last Hero.
- A failed or invalid instance creation now follows the existing rollback path instead of potentially treating instance ID zero as a successful match.
- No build or runtime tests have been performed; confirm the target Mobius `createDynamicInstance` failure behavior and `InstanceWorld.destroy()` cleanup during final validation.


### Phase 28 — PvP leaderboard index (SQL updated; validation pending)

- Added a composite index on `pvpPoints`, `totalKills`, and `bestStreak` to support the Community Board's top-10 global PvP ranking.
- Added `character_pvp_stats_add_points_index.sql` for existing installations where the column exists but the index is absent; the migration must be run only after confirming the index does not already exist.
- No SQL execution or performance benchmark has been performed. Confirm the index with `SHOW INDEX` and compare the leaderboard query plan on representative data.


### Phase 29 — event script entry points (source updated; validation pending)

- Added `public static void main(String[] args)` entry points to 1v1 Arena, 3v3 Arena, and Last Hero. Each entry point instantiates the script so its constructor can register NPC interactions and event listeners, matching the pattern used by the existing Mobius TvT event script.
- This addresses a script-discovery risk that would otherwise leave the custom event classes uninstantiated by loaders that invoke each script's `main` method.
- No script-loader or runtime test has been performed; confirm the exact loader's entry-point behavior and that each class is instantiated once.


### Phase 30 — custom arena manager NPC templates (data updated; validation pending)

- Added NPC templates 70013, 70014, and 70015 to `dist/game/data/stats/npcs/custom/custom.xml` for 1v1 Arena, 3v3 Arena, and Last Hero managers.
- The templates use the existing Race Manager display model already present in this custom NPC file and server-side names/titles; the event scripts can now spawn defined NPC IDs rather than relying on undocumented pre-existing templates.
- No datapack load or in-game visual test has been performed; validate XML schema loading, model appearance, collision, and script talk/bypass dispatch.


### Phase 31 — separate arena manager locations (source updated; validation pending)

- Moved the 1v1, 3v3, and Last Hero manager NPCs to separate nearby Giran coordinates instead of spawning all three at the same point.
- The saved-return fallback remains separate from the three manager positions.
- Coordinates are provisional and must be checked in-game for walkability, ground height, collision, and proximity to existing NPCs before release.


### Phase 32 — avoid consuming anti-farm cooldown on queue overflow (source updated; validation pending)

- PvP persistence queue capacity is now reserved before mutating the in-memory killer-victim cooldown map.
- When the queue is full, the kill record is dropped without consuming that pair's anti-farm cooldown, so a later eligible kill is not suppressed because of a record that never entered the queue.
- No load test or runtime validation has been performed; verify capacity accounting and pair cooldown behavior under concurrent producers.


### Phase 33 — preserve offline recovery after 1v1 startup failure (source updated; validation pending)

- The 1v1 startup rollback now deletes a participant's saved return row only when that participant is still online and has been sent back. Offline participants keep the persisted recovery row for login.
- This aligns 1v1 rollback with the offline recovery behavior already used by 3v3 and Last Hero.
- No runtime test has been performed; disconnect one participant at each stage of match startup on a test server before release.


### Phase 34 — Last Hero queue progression (source updated; validation pending)

- Last Hero now schedules another registration window when enough players remain in the waiting queue after a match starts, allowing queued players to enter subsequent rounds without requiring an additional person to join.
- The same rescheduling occurs when eligibility checks leave fewer than four selected players but enough other players remain queued.
- No runtime test has been performed; verify multiple isolated Last Hero matches can coexist and that queue registrations remain unique.


### Phase 35 — stale Last Hero countdown protection (source updated; validation pending)

- Added a generation token to invalidate delayed Last Hero countdown callbacks when the event is stopped or restarted.
- A countdown scheduled by a previous event run can no longer start a match early in a newly started run.
- No runtime test has been performed; stop/restart the event while a countdown is pending and verify the stale task is ignored.


### Phase 36 — avoid duplicate persistence retries after commit (source updated; validation pending)

- The asynchronous PvP persistence worker now treats a successful SQL commit as success even if restoring the connection's auto-commit setting subsequently fails; the restore problem is logged separately.
- This avoids retrying a transaction that may already have committed and thereby incrementing kills, deaths, streaks, or points twice.
- No database-failure injection or runtime test has been performed; validate connection-pool behavior and transaction commit/reset failures before release.


### Phase 37 — throttle anti-farm cache cleanup (source updated; validation pending)

- Throttled the in-memory killer-victim cooldown-cache cleanup so a cache larger than 10,000 entries is not fully scanned on every PvP kill during a large, active mass battle.
- Cleanup now runs at most once per configured cooldown interval, with a 10-second minimum interval when the cooldown is disabled or very short.
- No load test has been performed; measure CPU and memory under high pair diversity and verify expired entries are still reclaimed.


### Phase 38 — null instance creation rollback (source updated; validation pending)

- The arena startup paths now validate the `Instance` returned by `createDynamicInstance` before assigning it to an `InstanceWorld`. A null or invalid instance is rejected before the world is registered, so rollback does not try to destroy a world whose internal instance reference is null.
- This complements the earlier positive-ID guard for all three match types.
- No build or runtime test has been performed; verify the exact Mobius instance manager's failure behavior and cleanup path.


### Phase 39 — reject unloaded arena templates (source updated; validation pending)

- Match startup now verifies the returned dynamic instance has the expected template ID and a non-null exit location, in addition to a valid instance ID.
- This catches missing or malformed instance XML before players are teleported into an empty/unconfigured instance.
- No runtime test has been performed; temporarily remove or corrupt a template on a test server and confirm startup rolls back cleanly without leaving players in an instance.


### Phase 40 — clean up rejected dynamic instances (source updated; validation pending)

- If an arena template fails validation after `createDynamicInstance` has already registered the dynamic instance, the startup path now explicitly removes that invalid instance before aborting.
- This prevents invalid or missing template data from leaving orphaned dynamic instances in `InstanceManager`.
- No runtime test has been performed; force a missing/malformed template and verify the instance registry is clean after startup rollback.


### Phase 41 — persistence worker scheduling failure (source updated; validation pending)

- The custom PvP statistics path now catches runtime failures when scheduling its background worker, resets the running flag, and logs the issue instead of propagating the scheduler exception into the combat path.
- Queued records remain in memory for a later scheduling attempt; this does not make the queue durable across process shutdown.
- No thread-pool shutdown test has been performed; confirm the worker does not block combat handling when the server is stopping.


### Phase 42 — arena exit handling (source updated; validation pending)

- Added creature-teleport listeners to the 1v1, 3v3, and Last Hero modes. A participant who leaves the match instance is treated as a forfeit or elimination.
- Winner and survivor checks now require the player to remain in that match's dynamic instance.
- No in-game validation has been performed; verify normal return teleports are not mistaken for exits and that escape attempts cannot keep a match alive.


### Phase 43 — remove undefined arena door references (XML updated; validation pending)

- Removed the four placeholder door IDs from all three custom instance templates. No matching door definitions were found in the branch, and these arenas do not require doors to run their core matchmaking flow.
- This avoids loading instance templates with references to undefined doors. If physical arena gates are desired later, add actual door definitions first and then reference those IDs from the instance templates.
- XML parsing and instance-loader validation have not yet been run against the server.


### Phase 44 — persistence worker resilience (source updated; validation pending)

- Added a per-attempt runtime-exception guard around custom PvP persistence. Unexpected runtime failures are logged and retried under the existing bounded retry policy instead of terminating the worker before later queued records are processed.
- The current record can still be dropped after all retries fail; this queue is in-memory and is not a durable outbox.
- No fault-injection or runtime tests have been performed. Simulate connection acquisition failures and scheduler shutdown to verify the worker resets its running flag and subsequent records can restart processing.


### Phase 45 — Last Hero disconnect elimination (source updated; validation pending)

- Last Hero now records a participant as eliminated when a logout event fires, rather than relying only on `isOnlineInt()` after a short delay. This closes a race where the logout callback can arrive before the online flag changes and incorrectly leave the disconnected player counted as alive.
- Leaving the match instance also marks the participant eliminated explicitly; the winner resolver ignores eliminated object IDs.
- No runtime test has been performed; disconnect the last alive player and disconnect during a multi-player round to verify winner resolution and no-result behavior.


### Phase 46 — verified return-location restoration (source updated; validation pending)

- Arena login recovery and match finalizers now verify that a player reached the intended return area (instance ID and coordinate tolerance) before deleting the persistent recovery row.
- If teleportation fails, the record is retained and a warning is logged so the next login can retry instead of losing the only recovery point.
- No in-game teleport-failure test has been performed; verify normal return, fallback-to-town, and failed-teleport recovery for online and offline participants.
