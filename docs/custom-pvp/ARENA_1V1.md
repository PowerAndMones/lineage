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
- This remains a queue-to-native-duel prototype, not an isolated arena instance. Teleportation, winner/result capture, a dedicated ranking table, reward rules, disconnect recovery, and full lifecycle testing remain future work.

## Integration checks required during the planned final build

- Confirm the script loader discovers `custom.events.Arena1v1.Arena1v1`.
- Confirm NPC template `70013` exists in the datapack; if not, assign an existing dedicated custom NPC ID.
- Confirm the HTML bypass resolves to the script name `Arena1v1`.
- Verify matchmaking behavior when duel creation is rejected and when a player disconnects at the same moment a match is formed.
- Confirm the new synchronization and event-participation checks compile against this exact Mobius revision.

Do not consider this feature production-ready until the shared build and runtime test pass.
