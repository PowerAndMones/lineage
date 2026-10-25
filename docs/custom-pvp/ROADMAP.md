# Custom PvP Core — initial design

This branch is for the custom x1000 Interlude PvP feature set. The upstream `main` branch remains unchanged.

## Implementation status

### Phase 1 — persistent PvP statistics (code added, build/runtime test pending)

- `Player.increasePvpKills()` now calls `CustomPvpStatsManager` only after the existing `AntiFeedManager` check succeeds.
- `CustomPvpStatsManager` atomically stores killer/victim totals, current streak, best streak, and kill timestamp in one database transaction.
- `dist/db_installer/sql/game/character_pvp_stats.sql` creates the required InnoDB table.
- This phase does not yet display the statistics in-game; Community Board and ranking UI are next.

### Next phases

1. Add configurable anti-farm rules before granting custom points or rewards: repeated-kill cooldown and exclusions for events/instances/PvP zones.
2. Expose stats through a Community Board page and add rankings/streak announcements.
3. Reuse existing TvT and CTF event scripts; add 1v1/3v3 and Last Hero after core tracking is tested.

## Design constraints

- No pay-to-win combat bonuses.
- Cosmetic rewards and convenience features only.
- Never reward fake-player kills.
- Keep existing PvP/PK counters and upstream behavior intact.
- Do not enable reward drops globally until anti-farm checks and tests are in place.

## Integration points inspected

- `java/org/l2jmobius/gameserver/entity/actor/Player.java`: `doDie(Creature)` and `increasePvpKills(Creature)`.
- `java/org/l2jmobius/gameserver/config/custom/PvpRewardItemConfig.java`: existing reward configuration.
- `dist/game/data/scripts/custom/events/TeamVsTeam/TvT.java` and `dist/game/data/scripts/custom/events/CaptureTheFlag/CtF.java`: existing event scripts.
- `dist/game/data/scripts/handlers/bypass/communityboard/HomeBoard.java`: Community Board integration.

The persistent-statistics code and SQL table have been added to this feature branch. Compilation and runtime behavior have not yet been verified.