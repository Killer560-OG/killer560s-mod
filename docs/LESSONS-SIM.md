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
- Read a hole off the capture before cutting one: Hypixel's core floor is a 7x7 gold and bedrock plug at y 113 over a
  shaft already open to P4, while the plan's hole at 105-106 cut solid stone under it and nobody fell (bossfloor-1104).
- One capture file for every boss phase let a P3 capture overwrite his P1 room (2026-11-04 23:21), so the sim's
  fight start was built from a P3 world. Each capture now goes to its phase's file (K5BA v2 records the phase); the fight
  starts from P1's, and P3's start takes only the cells of P3's band where the P3 capture differs from the P1 one.
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
  sends only ticking chunks. Cause (simchunk-1105): a player's chunk TICKETS follow him only through `ChunkMap.move`, which
  vanilla calls when his client's movement packet arrives; `ChunkMap.tick` moves only the chunks he is tracked for. A client
  still in its world-load wait sends no movement (it waits for its own chunk, which is never sent), so a server teleport
  before it loaded left his tickets at the login spot 9 chunks away: a deadlock until the 15 s timeout (the old PORTAL
  ticket was only on `place`, never on a floor's `snapPlayerTo`). Every sim teleport goes through `SimArrival.teleport`,
  which calls `getChunkSource().move(sp)` after `teleportTo`; a stall still logs "landing chunk ... not on his client" with
  his ticket section and the chunk's level. 1104-sim-spawnload 63/63 on 26.3 cheat, 26.3 legit and 26.2 cheat. Those worlds also opened with
  "Loading 210 persistent chunks": `BossFight.forget` never un-forced the arena; now SERVER_STOPPING un-forces it and
  every sim open releases any left (`BossFight.releaseForced`).
- **A build's chunk loads froze because the light thread died** (simchunk-1105, testkit 97 single rooms). The light
  engine's `propagateLightSources` reads a chunk's light sources LIVE on the light thread; a source the build wrote into a
  section whose DataLayer was not made yet threw an NPE in `getStoredLevel`, which kills the light executor, and every later
  chunk load then waits on light forever (the server parks in a chunk load). `SimBuildQueue.FinishJob` now waits (polled
  once a tick, 200-tick cap with a WARN) for `waitForPendingTasks(cx, cz)` of every chunk it wrote before the build counts as
  done, and logs "light engine done with the build's N chunks after T tick(s)". 97 single rooms 133/133 on 26.3 and 26.2 cheat.
- **Blaze rooms land at the doorway's floor** (simchunk-1105): their spot was a column scan (Higher Blaze from the top), and
  on a generated floor with the room turned it stood him on a ledge 5 above the doorway (97 /goto, y259 vs 254). Their
  `Spawn` now carries the doorway offset (15), so `openingFloor` sets the height, with `doorwaySpot` as the fallback.
- **A reset that cancels pending work kills behavior built on it** (bossfix3-1105): the boss sim's Simon Says start press
  while running went back to IDLE and dropped the queued show, so Hypixel's skip (start, reset, start 3 ticks apart) never
  skipped. Hypixel keeps queued round tasks across a reset; `BossDevices` now runs SS on a task list that only a new device or
  its completion clears. Likewise the boss's "gate" was the plan's iron wall over a capture whose real gate is cracked stone
  bricks: the line came, the barriers went and the arch stood. Clear what the capture holds (`BossGates`), not what the plan built.
- **"Is this Hypixel?" gates must count the sim** (simgates-1105). `CheatUtils.isOnDungeonServer` (and the private copies in
  `SecretsFeature` and `I4SensorsFeature`) asked for a hypixel.net / p3sim.net address; a singleplayer world has none, so
  Lever Aura, Lights Device, Goldor Triggerbot, Auto i4, Auto Ult, Door Helpers, Secret Triggerbot and the Secrets hitboxes
  were silently off in the whole sim. A world with no address now counts while `SimState.canAct`. Two more sim-only facts the
  shared gates must carry: the boss fight's mode (`DungeonState.getFloor` is "F7" or "M7" per `simBossBegins(master)`, the
  clear stays "M7"), and a fight started mid-P3 hears Goldor's opening line, which on Hypixel always means S1:
  `Floor7Tracker` takes the section the player stands in while `DungeonState.isSimBoss()`. Per-feature list:
  killer560s-mod-logs/simgates-1105.md; testkit 1105-boss-simgates.
- **A tick-relative schedule never fires its k == 0 step when it is armed from inside the same tick's switch** (simgates-1105).
  Boss sim Simon Says' `beginRound(n, 0)` from ROUND_DONE set `ssShowTick = tick`, but SHOW first ran next tick (k = 1), so
  rounds 2-4 never lit their first lamp: the solver recorded a short sequence and Auto SS broke the device in round 2. Fixed
  as `beginRound(n, 1)`, then superseded in ship7 by bossfix3's task list, which lights a round's first lamp in the task itself. The testkit's own SS case read the sequence from the sim and never noticed; 1105-boss-simgates lets
  Auto Simon Says solve from what it sees, with only the start pressed.
- The boss sim's room captures sat in the one instance's config folder, so every other instance built the plain built-in plan
  ("didn't have the right world setup", bosssim1108); moving them to the shared store fixed only his PC (see coopnet-1110 below).

- Vanilla breaks in the sim (simbreak-1109): `PlayerBlockBreakEvents.BEFORE` fires on the sim's integrated SERVER, where `player` is a ServerPlayer and never `== client.player`, so the old `player != client.player` test made the "nothing breaks through vanilla" gate always pass. It now only checks `SimState.isActive()`. `Level.destroyBlock` (Superboom, SimItems breakIfBreakable) does not fire the event, so dungeonBreak and blasts are unaffected. Testkit 96-ar-vanillabreak calls `ServerPlayer.gameMode.destroyBlock` and asserts false + block still there.

- Sim miniboss kills (simmidasfix-1110): `SimMiniboss`'s ServerPlayer overrides `die()` without `super.die`, so Fabric's AFTER_DEATH never fired and `SimMobs.KILLED` never held it; a killed miniboss (King Midas, live CHAMPION rooms) was counted dead only after 200 ticks missing, so its room read uncleared and Auto Clear gave up "no starred mob is visible" (131-sim-midas-1020 failed; 1101-sim-mobfloor 921 s with 13 rooms open, now 65 s). `die()` now invokes `ServerLivingEntityEvents.AFTER_DEATH` itself. A sim mob that overrides death must fire that hook.
FIXED (simunreach-1111): `SimLiveMobs.reachable()` floods the room from its first tile (step up 1, drop up to 3, inside the room) and placeMobs skips any learned OR seeded spot outside that set; a walled-in learned spot logs "[Sim] X: learned spawn spot ... is walled in" (that line proves the fix acted). Redstone Crypt's lever-wall spots were the case. 131-sim-midas, 131-sim-auto-clear and 1101-sim-mobfloor skip on a legit jar; run the scenario as "131-sim-midas-1020,1101-sim-mobfloor" (bare 1101-sim-mobfloor runs 0 cases), proof is the "[1101-sim-mobfloor] PASS" line in the client log.
- A sim feature that reads a file only HIS PC has is broken for everyone else (coopnet-1110): the F7 arena captures sat in his shared store, so a friend's co-op host built the built-in plan and his guests saw "the very original" arena. His two captures (P1, P3, ~520 KB each, already gzip) now ship in the jar (`assets/killer560smod/sim-boss/`) and are ALWAYS used (killer560: "the one i have is the right one any other one is wrong ... with no way to overwrite it"): no file on disk is read, the plan is an ERROR fallback only. In co-op the host builds the world, so guests see it too. Testkit 1110-logic-boss-bundled (his sha256, strays ignored), 1108-boss-fresh.

- Blood room wall cases (simblood-1110): in the Blood capture the skull cases are cauldrons at y 75 and 79 (floor top y 69), on walls at dz ±12 with dx -4/0/4 and at dx ±12 with dz ±9. `SimBloodCamp` picks which of the two turns the world uses by counting cauldrons at both, never from the room's rotation guess. Starred-mob positions come from Blood Rush's spots (learned store + shipped `spawn-spots.json`); `SimMobLooks` (shared record `SIM_MOB_LOOKS`, written on Hypixel only) adds type, gear, skin and yaw, and a room with no recorded look spawns the dummy.
