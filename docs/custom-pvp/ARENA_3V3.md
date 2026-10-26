# 3v3 Arena — implementation specification

Status: core and custom-script compilation passed in CI; database and runtime validation pending.

## Rules

- Teams contain exactly three distinct eligible players.
- Queue matching is FIFO and must reserve all six players atomically before creating the match.
- Each match runs in its own dynamic PvP instance; do not reuse a live instance between matches.
- Team A and Team B spawn at opposite sides. Team membership is immutable after the match starts.
- A team wins when all three opponents are defeated, or when the opposing team forfeits after a disconnect.
- If all remaining players on both teams die before result resolution, record no winner.
- A match timeout ends without a winner and without rating/stat changes.
- Disconnect handling currently uses immediate team forfeit when any participant logs out. Reconnect grace is not implemented in this prototype.
- Results must be finalized exactly once and both teams' statistics must update in a single SQL transaction.
- No global announcements, combat stat bonuses, paid rating boosts, or item rewards. Cosmetic rewards may be considered only after anti-farm and runtime validation.

## Rating and anti-farm

- Each participant starts at 1000 rating.
- Calculate each team's rating from the average rating of its three members, then apply an Elo-style K-factor of 32 to each participant based on the match outcome.
- Record matches, wins, losses, rating, current win streak, best win streak, and last match time per participant.
- A repeated matchup between the same two three-player rosters must not change ratings within 30 minutes. Roster identity is order-independent: sort character IDs within each team, hash each roster, then sort the two roster hashes before lookup.
- The pair cooldown table stores the canonical roster-hash pair. The hash must be calculated from character IDs, not player names.
- The 3v3 rating is separate from 1v1 rating and open-world PvP points.

## Recovery and lifecycle

1. Validate all six players and reserve them under one synchronized matchmaking operation.
2. Persist each player's return location before any teleport.
3. Create the dynamic instance, add all six players to its allow-list, then teleport each player to the correct team spawn.
4. Confirm all six players entered the same instance before setting the match to started.
5. If any step fails, return all online participants, retain recovery records for offline participants, and destroy the partial instance.
6. On death, logout, timeout, or event stop, use one atomic finish guard.
7. Only a valid, unique winner result may update ratings. Simultaneous team wipe and timeout are unrated.
8. Remove event flags and match mappings, revive dead online players if needed, restore saved locations, and destroy the instance.

## SQL preparation

Install `dist/db_installer/sql/game/character_arena3v3_stats.sql` before enabling the prototype. It creates independent player statistics, persistent canonical roster-pair cooldown storage, and saved return locations. Applying this schema alone does not enable the event.

## Validation still required

- Confirm the correct character ID/name column schema and instance APIs for this exact Mobius revision.\n- Confirm script auto-discovery, NPC template 70014, instance template 3051, and bypass routing.\n- Consider a reconnect grace period and review team balancing before production use.
- Verify team spawn coordinates and instance template.
- Verify logout/reconnect behavior, simultaneous deaths, partial startup failure, event stop, and database transaction rollback.
- CI compile passed; runtime, SQL transaction, coordinate, and in-game tests have not been performed.
