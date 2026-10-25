# Custom PvP source review — CI compile passed; runtime validation pending

Branch: feature/custom-pvp-core

CI validation snapshot (2026-10-25): `ant -f build.xml compile` passed on Temurin JDK 27; custom PvP, TvT, CtF, and Community Board scripts compiled; XML/SQL checks, global uniqueness checks for NPC IDs 70013–70015, and whitespace checks passed. The SQL installers also executed successfully against a clean MariaDB 11.4 database; smoke tests verified all 10 tables, the `pvpPoints` column/index, current/best streak progression plus loss reset in all four stats systems, and all three database-clock cooldown queries under row locks. No live game-server, target-database, or in-game validation has been performed.

## Corrections made in this review

- Updated 1v1, 3v3, and Last Hero messages so a repeat-opponent/roster cooldown no longer implies that a match result was saved to rated statistics. In the current prototype, the transaction rolls back and the match does not change rating or rated match/win/loss/streak counters.

## Required checks during final validation

### Script and event lifecycle

- Confirm the target Mobius script loader invokes each custom Event script's `main` entry point exactly once and registers its annotated death/login/logout listeners.
- Confirm the event admin/start-stop mechanism exposes the expected script names and can start/stop each queue.
- Confirm eventBypass, onEvent, and NPC bypass strings are dispatched as expected by this exact Mobius revision.
- Confirm custom NPC IDs 70013–70015 from `dist/game/data/stats/npcs/custom/custom.xml` load successfully and are not duplicated by another datapack file.

### Instance handling

- Confirm instance templates 3050–3052 are loaded from the custom instance directory.
- Verify the target API's createDynamicInstance, addWorld, addAllowed, teleToLocation, and destroy behavior.
- Confirm missing/malformed instance templates (wrong template ID or missing exit point) trigger rollback before players enter the instance.
- Confirm a rejected dynamic instance is removed from `InstanceManager` and does not remain as an orphan.
- Confirm spawn points are inside the playable instance geometry and that return locations preserve the original instance where valid.
- Verify the three Giran manager coordinates are walkable and do not overlap existing NPCs; verify the fallback point is reachable and safe.
- Match finalizers in 1v1, 3v3, and Last Hero now use the configured fallback if a saved return location points to an instance that no longer exists.
- Test server shutdown/restart with participants inside an arena and confirm login recovery uses the saved return record without leaving a player stuck in an obsolete instance.
- Force a return teleport failure and verify the persistent recovery row is retained until the player reaches the saved location or fallback on a later login.

### Match and rating integrity

- Queue startup-failure paths now defer retries instead of repeatedly attempting a failed instance creation in the same matchmaking loop; verify retry behavior and queue flags during runtime validation.

- Test simultaneous death, logout during finalization, timeout, event stop, and failed instance startup.
- Test teleporting out of each arena: the leaver must be eliminated/forfeit, while the normal finalizer's return teleport must not trigger a second result.
- Verify Last Hero automatically schedules another round when four or more players remain queued after a match starts or eligibility filtering.
- Stop and restart Last Hero during its 15-second countdown; confirm a callback from the previous run is ignored.
- The 3v3 finalizer now rejects a winner team unless at least one member is both online and alive, including logout/timeout race paths.
- Verify each match can finalize once only, and offline players retain return-location rows until login recovery succeeds.
- Validate the 1v1 startup rollback path when a participant goes offline; the saved return row must remain until recovery on login.
- Verify the 1v1 pair cooldown is direction-independent and that 3v3 roster-pair and Last Hero complete-roster cooldowns behave as intended.
- Verify all three rating cooldown queries using the database clock (`TIMESTAMPDIFF`/`CURRENT_TIMESTAMP`) against the deployed MySQL or MariaDB version.
- Verify streak SQL assignment order: best streak must use the prior current streak plus one before current streak increments, and a loss must reset only the current streak.
- Verify each rated result conserves total rating points after integer rounding: 1v1/3v3 must apply equal-and-opposite deltas, and Last Hero must distribute the exact winner gain across all opponents.
- Verify repeat-match cooldown suppression leaves rating and rated counters unchanged; player messages now state that explicitly.
- Reviewed the 3v3 team Elo deltas: winner and loser changes use complementary expected scores. Corrected Last Hero's per-loser delta to distribute the winner's Elo gain across all opponents. Confirm both models with representative ratings during final validation.
- Review the rating formulas with representative ratings and confirm all SQL updates and cooldown changes commit atomically.

### Database and Community Board

- Load-test the custom PvP persistence queue with concurrent kill producers; the queue now uses an atomic pending counter and atomic capacity reservation rather than repeated linear-time queue-size scans.
- Verify queue overflow does not consume a killer-victim cooldown when the record is rejected before enqueueing.
- Inject unexpected runtime failures during persistence and verify bounded retries do not terminate the worker or strand later queued records; remember the queue is in-memory only.
- Load-test anti-farm cache cleanup with more than 10,000 unique killer-victim pairs; cleanup should be throttled and expired entries should eventually be removed.

- Back up the game database before applying the scripts listed in SQL_INSTALLATION.md.
- Confirm the Mobius characters table uses the expected charId and char_name columns.
- Confirm all leaderboard HTML placeholders are replaced and the final page fits the target client's Community Board display.
- Confirm custom-stat tables are installed before enabling the scripts; missing-table handling is intended to log or show a visible unavailable message, not to be treated as a successful installation.

## Release gate

Do not enable the modes on a live server until the source compiles against the target Mobius revision and the full lifecycle is tested on a separate test server. The current CI compile gate is green, but this is not a claim that the code is production-ready. This review is not a claim that the code is production-ready.
