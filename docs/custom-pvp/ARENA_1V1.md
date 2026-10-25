# 1v1 Arena — implementation notes

## Initial feature

The first implementation adds a matchmaking queue using the server's existing native duel system.

- Players join or leave through the Arena Manager NPC.
- When two eligible players are waiting, they are paired and passed to `DuelManager`.
- The native duel system remains responsible for duel mechanics and ending the fight.
- No custom items, PvP points, ranking points, or global announcements are awarded by this initial version.
- Players cannot register while already registered on another event, in a duel, in Olympiad, in an instance, in a siege, flagged for PvP, fishing, flying, in combat, carrying karma, or using a cursed weapon.
- Queue start/stop and join/leave operations are synchronized to reduce lifecycle races; stale queued registrations are cleared on start/stop.
- Registration rejects players already marked as participating in another event.
- A logout event listener removes disconnected players from the waiting queue and clears their registration flag.
- Match eligibility is rechecked before pairing, including that a player is not already participating in another event.
- This remains a queue-to-native-duel prototype, not an isolated arena instance. The queue now confirms that the native duel actually starts, but it does not yet capture results or manage a dedicated match lifecycle. Isolated arena teleportation, winner/result capture, a dedicated ranking table, reward rules, disconnect recovery, and full lifecycle testing remain future work.

## Integration checks required during the planned final build

- Confirm the script loader discovers `custom.events.Arena1v1.Arena1v1`.
- Confirm NPC template `70013` exists in the datapack; if not, assign an existing dedicated custom NPC ID.
- Confirm the HTML bypass resolves to the script name `Arena1v1`.
- The queue now waits for the native duel countdown and checks that both players entered the same duel before reporting that the fight started. If that check fails, both players receive a failure message rather than a false success message.
- Verify matchmaking behavior when duel creation is rejected and when a player disconnects at the same moment a match is formed.
- Confirm the new synchronization, event-participation, and logout-listener code compiles against this exact Mobius revision.

Do not consider this feature production-ready until the shared build and runtime test pass.


## Isolated arena prototype added to source (validation pending)

- Added a dedicated PvP instance template at `dist/game/data/instances/custom/Arena1v1.xml` with template ID `3050`, separate from TvT's template ID `3049`.
- Matchmaking now creates a dynamic instance per pair, saves both players' return locations, adds both players to the instance allow-list, and teleports them to opposite Colosseum spawn points.
- The match uses the instance's PvP rules rather than Mobius' native DuelManager. This avoids the native duel's fixed arena teleport path, which would pull participants out of the custom instance.
- Death and logout are handled through event listeners. A one-time atomic finish guard prevents duplicate cleanup; online participants are revived if needed and returned to their saved locations. A disconnected participant's return location is held in memory for login restoration.
- Stopping the event attempts to close active matches, and partial startup failure attempts to return both participants before destroying the instance.
- No rating, custom PvP points, item rewards, or global announcements are added.

Known limitations before production use:
- Return locations for disconnected players are in memory only and will not survive a server process crash/restart.
- Match timeout, restart recovery, full disconnect/death ordering, and instance template/spawn suitability still require validation.
- The code has not been compiled or runtime-tested, as requested; do not treat the prototype as production-ready.

## Dedicated arena implementation plan

The isolated-match upgrade must be implemented as a separate phase rather than assuming the native duel API can run inside an instance.

### Reuse verified project patterns

- The existing TvT script creates dynamic worlds through `InstanceManager.getInstance().createDynamicInstance(INSTANCE_ID)`, wraps them in `InstanceWorld`, registers the world, adds participants with `addAllowed`, and teleports using `teleToLocation(location, instanceId, offset)`.
- TvT uses instance template `3049` and the Colosseum spawn coordinates `(147447, 46722, -3416)` and `(151536, 46722, -3416)`. These are reference coordinates only; suitability for a 1v1 arena must be confirmed before use.
- TvT demonstrates global `ON_CREATURE_DEATH` and `ON_PLAYER_LOGOUT` event handlers, plus `InstanceWorld.destroy()` for cleanup.

### Required match lifecycle

1. Reserve both players atomically and create one dynamic instance per match.
2. Save each player's original location and instance ID before teleporting.
3. Add both players to the instance allow-list and teleport them to opposite spawn points.
4. Decide on exactly one combat controller: either use native DuelManager if this Mobius revision supports instance duels safely, or use a dedicated 1v1 match state machine. Do not combine two independent result controllers.
5. Resolve winner/forfeit exactly once on death, logout, or timeout; guard against duplicate death/logout events.
6. Remove event flags/listeners, restore both players to a safe location, destroy the instance, and release the match reservation.
7. Handle partial startup failure by returning any player already teleported and destroying the partially created instance.

### Explicitly unresolved before source implementation

- Whether the native duel system supports duels inside dynamically created instances and exposes a reliable result callback in this exact revision.
- Which instance template and spawn coordinates should be dedicated to 1v1.
- The exact return-location behavior when a participant disconnects and later reconnects.
- Whether instance template `3049` is appropriate to share as a template for concurrent dynamic 1v1 instances.

No build or runtime validation has been performed. The isolated match should not be considered implemented until these integration points are handled in source and then validated in the planned final build.
