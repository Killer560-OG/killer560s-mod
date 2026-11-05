# Sim lessons

Moved out of CLAUDE.md on 2026-10-24 to keep it under 300 lines. Read with [SIM.md](SIM.md).

- A comparator must not call the RNG. `SimFloorLayout` sorted candidates by a key containing
  `rng.nextDouble()`; TimSort noticed and threw "Comparison method violates its general contract!". Draw the
  jitter once per element and store it.
- **One room must not have two answers.** A room's clay corner and its rotation are published once and read
  everywhere; if any second place computes them, they will disagree and the symptom will be "my solvers point at
  the wrong block". `SimBuilder` published the PASTE rotation beside a corner computed for the DATABASE rotation,
  which differ by the capture's own turn in 88 of 122 rooms, so nine of the eleven puzzle rooms' solvers were
  measuring through a rotation a quarter or half turn off. `SimRoomIndex` had always been right, which is why
  Secret Waypoints worked while every solver did not - **a feature that works beside one that does not, on the
  same data, is naming the bug.** The publish now reads `SimRoomIndex` and nothing else computes it.
- **A bare dungeon height in a comparison is a bug in the sim.** Relative y IS world y on Hypixel, so
  `player.getY() != 75.0`, `pos.y != 69.5`, `69.5..72.5` and `y 66` all look like facts and are all wrong the
  moment `SimAltitude` shifts the floor. Anything that TRANSLATES a coordinate goes through
  `PuzzleCoords`/`RouteCoords`; anything that COMPARES one adds `DungeonLayout.simYOffset()`. Better still, take
  the height from the solver's own output (`path.get(0).y`) so there is no second expression to keep in step.
  Five of these were found in one sweep in October 2026, one per auto, each failing silently.
- **To make the sim satisfy a solver, read the solver's SCAN, not just its coordinates.** Getting the position
  right is half of it. `BlazeSolverFeature` skips anything that is not an `ArmorStand`, so a name set on the blaze
  itself was invisible; `TicTacToeSolverFeature` reads map ITEM FRAMES at the cell, so a mark painted on the wall
  behind the button was invisible; `BeamsSolverFeature` reads prismarine as "burned on the wrong partner", so the
  sim marking a held lantern that way turned a correct shot red; `TeleportMazeSolverFeature` only accepts a
  teleport that lands on a half block. In every case the sim was placing the right thing somewhere the solver
  never looks, or the wrong thing where it does.
- **The sim emulates Hypixel's SERVER; client features never special-case it.** Abilities in `roomsim` answer the
  use / use-on / START_DESTROY_BLOCK packets on the integrated server (Fabric's common `UseItemCallback`,
  `UseBlockCallback`, `AttackBlockCallback`, whose server copies fire for the `ServerPlayer`), from the server
  player's position, packet rotation, sneak and held item, and teleport with a real position packet. Until
  2026-10-04 they reacted to the client's `gameMode` calls instead, and `RouteExecutor`/`ClearExecutor` grew sim
  branches to cope - the opposite of why the sim exists. If a feature behaves differently in the sim, fix the sim.
  The same goes for a puzzle's JUDGE: Ice Fill read `client.player` once a client tick and missed a tile whenever two
  teleports reached the client between ticks (2026-10-05); it now judges on the server, every landing.
  So did the mimic: its chest click was the CLIENT's callback queueing the spawn, and Secret Aura's click plus Kill Mimic's
  Hyperion could reach the server first (2026-10-11, 96-ar-644). A chest now counts only when the server accepts the click (in reach).
- A Fabric event's first non-PASS listener wins. `/ar edit db`'s right clicks lost to the sim's ability hook (a held
  Superboom detonated, no block picked) until its `UseBlockCallback` moved to a phase ordered before the default. The sim's
  client-side essence listener lost the same way with any ability item held, so Secret Aura never collected essences
  (2026-10-05): sim secrets are answered on the SERVER now, and the essence is Hypixel's profiled player_head (99-sim-essence-aura).
- A built-in plan laid "over" a capture overwrites the real room: BossArena re-laid its gates, holes, pillars and device
  walls on top of his F7 boss capture, so the sim showed iron walls, a gray pad, gray concrete lamps and a floating plate
  that Hypixel does not have (bossfloor-1104). Over a capture lay nothing; a fight mechanic changes only the blocks Hypixel
  changes (a gate clears its barriers, not its box), and a capture read off the client carries the mod's own client-side
  edits (I Hate Diorite's glass) - undo those when reading. Testkit 1104-boss-capture diffs the whole box; it read 342 cells off
  until fire ticks were canceled and the build placed with `UPDATE_SKIP_ON_PLACE` (hoppers' onPlace re-checked power).
