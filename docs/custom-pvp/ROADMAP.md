# Custom PvP Core — initial design

This branch is for the custom x1000 Interlude PvP feature set. The upstream `main` branch remains unchanged.

## First implementation phase

1. Keep the existing L2J Mobius PvP kill path and its `AntiFeedManager` check as the authority for valid PvP kills.
2. Add persistent per-character PvP statistics: total valid PvP kills, deaths, kill streak, best streak, and last PvP activity.
3. Add configurable anti-farm rules before granting any custom points or rewards: same-account/dualbox checks where available, repeated-kill cooldown, and exclusions for events/instances/PvP zones as configured.
4. Expose stats through a Community Board page, then add rankings and streak announcements.
5. Reuse existing TvT and CTF event scripts; add 1v1/3v3 and Last Hero only after the core tracking is tested.

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

This document is a plan, not a claim that the features above are implemented.