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
- **Every landing goes through `roomsim/SimArrival`, and the loading bar reads only `SimWorld.progress()`** (simspawn-1104,
  killer560 2026-11-04: "put into walls or blocks ... the loading bar never updates"). The causes: the F7 boss arena was
  built by its own ticker with `setBlock` (60k a tick, every write sent to his client while he stood at world spawn
  inside the box), invisible to the bar, which read only `SimBuildQueue` and so showed 0 before the jobs were queued and
  again after they finished; a single room loaded from inside showed no screen at all; and every teleport reached the
  client before the chunks did, so he could fall through chunks it did not have yet. Now the arena is a queue job
  (`BossArena.ArenaJob`, `RoomPlacer.SectionWriter` unshifted, writes only changed cells: 120-670 ms), `SimArrival.clear`
  moves a landing that is not two free blocks over something solid, and the client keeps the screen up until the 3x3
  chunks round the spot are in, putting him back on it if he drifted. The bar is monotonic (0.9 x queue, then the arrival's
  chunk share) and `SimLoadingScreen.lastDrawn()` is what testkit 1104-sim-spawnload samples. "[SimPhase] build stages"
  logs each stage's time per build; a cold first world open (vanilla `createFreshLevel`, ~10 s) is now the biggest cost.
  On 26.2 a floor opened from the title after a boss world never got its landing chunks (0 of 9 for 15 s): the server
  counted them tracked, but the landing chunk sat at ticket level 33 (loaded, not ticking), and `ChunkHolder.getChunkToSend`
  sends only ticking chunks; a teleport hop and a re-queue did not help. `SimArrival.place` now adds a self-expiring PORTAL
  ticket (radius 2) at the landing and sends `ClientboundSetChunkCacheCenterPacket`; on 26.2 the holder STILL read level 33
  40 ticks later (2026-11-05 gate), so the ticket update never applied there: OPEN, 26.3 passes. Those worlds also opened with
  "Loading 210 persistent chunks": `BossFight.forget` never un-forced the arena; now SERVER_STOPPING un-forces it and
  every sim open releases any left (`BossFight.releaseForced`).
