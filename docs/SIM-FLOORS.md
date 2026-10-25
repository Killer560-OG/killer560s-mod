# Sim floors - generation, builds, the Map Designer and landings

How the sim lays out a floor (`SimFloorLayout`, `SimFloorGen`), builds it (`SimBuilder`, `SimBuildQueue`), the Map
Designer and its filters, single-room loads, the room cycle, `/goto`, `/reset` and run replays.
Part of the sim docs: [SIM.md](SIM.md). Doorways and the carve: [SIM-DOORS.md](SIM-DOORS.md).

## Footprints and room ids

- A room's captured size is `tiles * 32 + 1` (`RoomLibrary.footprint`), and the ONLY inverse is `tiles = (size - 1) / 32`,
  `RoomLibrary.cellFootprint(name)`. Two places once had their own: capture read a grid-cell span as a tile count (a
  3-tile room captured 157 blocks long instead of 97) and `SimFloorGen.cellFootprint` read the captured size as a tile span
  and halved it. The two errors canceled exactly, so fixing one alone was worse than either (a 2-tile room planned into one
  cell and pasted over its neighbor). Scenario 73 asserts every room covers exactly its captured footprint.
- Map-code room ids are per PLACEMENT, never per name. Deduplicated by name, two adjacent placements of one room merged
  into a single smeared paste (`SimBuilder` pastes by flood-filling cells with the same id).
- **Shape strings vs capture footprints, and the Archway warning.** A 65x33 capture needs 2x1 cells at rotation 0 and 1x2
  at 90; the layout's flood fill and the capture's size come from different places, so the build compares them and says
  so when they differ ("generated halfway and broke [...] rotated 90 degrees" was this disagreement).

## Laying out a floor (`SimFloorLayout`)

- `SimFloorGen.plan(...)` lays out a floor without touching the world, and needs no Minecraft classes below it
  (`RoomDoors` reads only `Room.blocks`/`palette`/`margin`), so the generator runs outside the game against data-loading
  stubs: `tools/layoutsim/run.sh`, `-Dsweep=true` (every floor size x slider 2..8 x puzzles 2..5), `-Dbounded=true`,
  `-Dmatrix=true`, `-Dclamp=true`, `-Dkeepshapes=1x1`, `-Dpinroom=Quiz -Dpincell=14 -Donly=F7 -Dslider=5 -Dpuzzles=3`.
  Measure a distribution over hundreds of floors; a one-floor assertion flaps. `tools/layoutsim/rotation.sh` and
  `floor.sh` both needed `SimWitherDoors` on their compile line.
- **A ROOM COUNT IS NOT A CELL COUNT; the target is cells.** Stopping at `wantRooms` left 21 rooms on 25-35 cells (median
  30); his Map Logger scans of 40 real floors put a fully-walked one at 34-36 (12 of 40 at exactly 36). `Floor.cells` is
  the target and `Floor.rooms` only a minimum (500 F7s after: 36 of 36 cells, 21-26 rooms). It needed: the attempt score
  ranks the room MINIMUM above cells; a room's footprint is capped at `cellsLeft - (roomsStillOwed - 1)`; and `score`
  costs a placement for each free cell it strands (every neighbor a blank wall).
- Preferring small rooms is not free: `choose` sorted by `area * 0.45 - doors * 0.9` and each doorway facing a blank wall
  costs -3 in `score`, so big rooms were nearly always refused. The size term now flips sign while the floor is behind a
  real floor's cells-per-room, the "good enough" threshold moves with the extra credit, and multi-tile footprints get
  credit for their cells. No fixed weighting can do this.
- `wantBlood`/`wantFairy` recorded the REQUEST, not the room placed: a failed blood placement fell back to an ordinary room
  recorded as blood (certain once blood is PINNED). Check the placed room's type.
- `commit(entrance, entranceRotation, ...)` passes DEGREES; `entranceRotation` was 0..3, so Entrance was always committed
  unrotated (its doorways never turned). Fixed 2026-09-29.
- **Blood and fairy never touch, in both directions, and the fairy is never linked to the entrance.** `touchesFairy` only
  stopped blood landing next to fairy, and the extra-door pass opened a door wherever two doorways faced, including
  fairy-to-entrance. `SimFloorLayout.forbiddenPair` refuses those links in the growth and the pin-waking pass.
- **Every generated floor has a trap.** Trap rooms (type `TRAP`, or Old Trap / New Trap before the database loads; Arrow
  Trap is an ordinary room) are held out of the pool: a 30% roll once a stub is a doorway in, every stub once the floor is
  within eight cells or five rooms of done. "Has a trap" ranks just under "has blood"; `SimFloorLayout.ensureTrap` swaps a
  trap in for an unpinned 1x1 NORMAL room whose links its doorways cover, bricking extra doorways. With a 1x1-only pool
  every F7 had no trap (1,119 of 4,480 floors), because `fillGaps` covered all 36 cells first; `runOnce` now tries the
  trap-into-an-empty-cell step BEFORE the fill too (0 failing after). Traps no longer get second and third doors.
- **Room variety.** `SimFloorGen.RNG` is an unseeded `new Random()`; the RNG was never the problem. `SimFloorLayout.RECENT`
  costs a room for recent floors (halved each floor) and is saved by `SimRecencyStore` to
  `config/killer560/dungeons/sim/killer560smod-sim-recent.json` (kept out of `SimFloorLayout` so the generator runs outside
  the game). Decay 0.6, key weight 3.0, die 0..4.
- **Blank cells were the recency change.** Subtracting `recency * 2.0` from the PLACEMENT score (as much as two stranded
  cells) let a fresh room that walled a cell in beat a recent one that fitted: from his saved recency, 1000 F7s gave 63% with
  an empty cell and 16% failing `LayoutSim`'s structure check (one blood, one trap, no overlap, all reachable), against 18%
  and 1.4% with recency off; consecutive floors shared 0.6 rooms (3.5 by chance). Now recency only orders the shortlist
  (`RECENCY_ORDER_WEIGHT` 1.5, table in its javadoc), the sixty-room shortlist cap only ends a search that found something,
  and `fillGaps` fills what growth leaves: an unused room meeting an open doorway; else a 1x1 neighbor turned or swapped so
  a measured doorway faces the hole; else a 1x1 whose door is carved through the neighbor's wall. One WARN per floor that
  needed it, another if a cell is still empty. `ensureBlood` swaps blood in for the deepest 1x1 dead end. 2000 floors after:
  0 empty cells, 1 without blood, 5.0 rooms shared. Most holes are corner cells walled in by 2x2s; about a quarter of floors
  get one carved door.
- **The fill's carve must land on walkable floor.** 54 of 88 failed bench clicks were into or out of rooms `fillGaps` put
  in through a carved wall (Criss Cross through the Entrance's platform, Rail Track through Three Floors...); 5 of 27 such
  doors could not be walked. `RoomDoors.carvable(name)` measures, per perimeter tile edge in the capture's frame, whether a
  cut at y69 lands on floor walkable from 3 to 6 blocks in (stepping at most one block). `carveOnce` uses only those; the
  unchecked carve stays as last resort and says "(NOT a walkable cut - nothing else fitted)" (124 of 7,347 carves, 0.55% of
  floors; a purely checked carve left 0.57% with an empty cell). See also "No door is ever carved through a wall with
  nothing walkable behind it" in [SIM-DOORS.md](SIM-DOORS.md).
- **Exact floors (73 "only 20 room(s), wanted 21").** `fillGaps`' strict step put multi-tile rooms into the last cells while
  rooms were owed. `Filler.maxArea` is the growth's cap; `run` lays out up to 12 whole floors (4 with pins) until
  `shortfalls` is empty - room minimum, cells, puzzles, trap, blood, and the Entrance-to-Blood path exactly the slider with
  the fairy on it - and only the kept floor goes into recency. A retry fires about once in 1,000-4,000 floors. 0 failing of
  190,400 sweep floors; 2.4-3.5 ms a floor. `RETRY_BUDGET_NANOS` (400 ms) keeps the best floor found by then: an Entrance
  with Rooms to blood 8 costs ~190 ms a floor and twelve misses were a 2.3 s freeze.
- **Every floor fills its real rectangle.** `SimFloorLayout.generate(..., across, down)` keeps every bounds check, the
  entrance seat, the fill and the cell target inside the floor's rectangle at the grid's north-west corner (a per-thread
  bound; cell indices stay `z * 6 + x`). `Floor.across/down/cells` come from `MapPainter.floorRooms`, the live map's table,
  so sim and map cannot disagree. Before, an F1 was 22 cells scattered over the 6x6. Bounded sweep: 0 outside, 0 short, 0
  under the minimum. `Spine.slack` caps the path's cells beyond one a room at the floor's cells less its minimum (the first
  bounded build let the spine take two 2x2s on an Entrance: testkit 73 "ENTRANCE #16: only 10 room(s), wanted 11").
- **Slider tops.** `SimFloorLayout.maxPuzzles(across, down, roomsToBlood)` / `maxRoomsToBlood` hold the measured table,
  read by `SimFloorGen.Floor.maxPuzzles/maxRoomsToBlood`: Entrance holds 6 to blood and 4/4/3/3/2 puzzles at 2..6, Floor 1
  5 puzzles up to 4 to blood then 4/4/3/2, Floors 2-3 4 puzzles at 8, Floors 4-7 everything. Clamped, 3 seeds x 11,880
  floors gave 0 misses. `planWith` holds every caller to them. If a floor still misses, the designer's status line leads
  with "short of the sliders".
- A pinned room's `Candidate` is a separate object from the pool's (`resolvePins` builds its own through `candidateOf`), so
  `candidate == fairyRoom` is false for a pinned Fairy. Compare given rooms by name.

## The path to blood is laid first

"Rooms to blood" counts the rooms on the Entrance-to-Blood path NOT counting Entrance, Fairy or Blood (killer560,
2026-09-28: "Do not count blood, green room, or fairy those are given"). So the path is Entrance, the slider's N rooms with
the Fairy among them, Blood: N + 3 rooms, N + 2 doors, measured through the doors the build writes (what `SimWitherDoors`
walks).

- What was wrong (4,480 floors before: length wrong on 84.8%, fairy off the path 64.1%, puzzles off 40.2%): blood went in at
  the first stub with `stub.depth + 1 >= bloodDepth` (a lower bound); `SimFloorGen.plan` passed slider + 1 as the depth,
  leaving no room for the fairy; and the door pass took entrance/blood links first, so a loop link became a door and the
  door path was not the one counted. Earlier, the fairy went in on a 1-in-4 roll and blood was only biased into its
  subtree, which never made it reliable.
- `SimFloorLayout.growOnce` now plans the path before anything grows (`planSpine`): a bounded depth-first search with
  backtracking, ordinary rooms with at least two doorways, the fairy at a drawn position 2..N (never next to entrance or
  blood), blood last and not touching the fairy, each joined through a measured doorway in both rooms. `Floor` carries the
  path's links (`spine`); `SimFloorLayout.doorLinks` is the ONE place the door tree is decided (`SimFloorGen.plan` and
  `tools/layoutsim`), takes the spine first, refuses any other link between path rooms and any other link to blood. Path
  rooms are never what `ensureTrap` or `ensurePuzzles` swap (a trap still counts as one of the slider's rooms). A pinned
  Fairy or Blood is the path's goal at its own cell. If no attempt can lay the path the old growth runs as a fallback with
  a WARN, and `SimFloorGen.plan` WARNs the length got against the one asked; the designer shows the measured count.
- Puzzles: the growth asks for a puzzle on every stub once `cellsLeft <= puzzlesLeft * 2 + 2`, the fill uses an owed puzzle
  first, `ensurePuzzles` swaps owed puzzles in for 1x1 dead ends off the path, last into an empty cell past the cell target.
  The fill keeps going past the cell target while short of the room minimum; a trap `ensureTrap` cannot swap goes into an
  empty cell.
- After (22,400 floors): path, fairy and `SimWitherDoors` 100%, puzzles 99.99%, room minimum missed 0.12% (all full 36-cell
  F5/F7 grids, since fixed by `Filler.maxArea`). Consecutive F7s share 3.6 rooms; 3.7 ms a floor (99 ms pinned fairy,
  260 ms pinned blood). Only pins can make a slider value impossible (Blood pinned next to a pinned Entrance).

## Pinned rooms

- `SimFloorLayout.generate(..., Map<Integer,String> pinned, ...)` puts each pin on the grid first, registers its doorways
  as stubs, and marks it *dormant* (grown INTO, not out of). The wake has to happen in the growth's "doorway meets a
  doorway facing back" block, which is what consumes the pin's stub; a separate fixpoint pass alone honored 63% of single
  pins and left 67 links to empty cells per 200 floors. The fixpoint pass stays for a pin next to the seed and two pins side
  by side.
- A pin no attempt can connect causes a SECOND layout without it (its cells counted toward the target, so pruning left a
  hole: median F7 36 -> 34 cells); `run` still reports it.
- **Dropped-pin weight.** "Every pin kept" above the cell count let a two-room floor holding his rooms win; a five-cell
  charge then let a full floor without his room win ("a hand-placed room was replaced by Generate", testkit 83: Quiz
  pinned mid-F7 dropped 19 of 200). Now a dropped pin ranks above the cell count but below blood, trap and the room
  minimum; the fill tops up cells (Quiz@14: 0 of 1,000). A pinned PUZZLE counts as one of the slider's puzzles in the growth
  too. A pinned Fairy or Blood no path of his length can reach is laid out again without it.
- A pinned room is NOT filtered (L shape, Higher/Lower Blaze pair): he put it there. The generator still never ADDS the
  other blaze half (0 of 800).

## Builds and the build queue

- **`isBusy()` is not a completion signal.** `SimBuildQueue.isBusy()` and `progress()` read the same before a build starts
  and after it ends, and `generate()` returns before rooms are queued. Snapshot `buildsFinished()`, ask, wait for it to
  change. Eleven testkit scenarios had this bug ("0 secret chests", "sim mobs never spawn" read for days as mod defects).
- **The generated floor's doors are written in plan(), not linkDoors().** `linkDoors` serves the designer's explicitly
  drawn floor; generated floors take `laid.links()` inside `plan(floor, puzzles, blood, pinned)`. Blood and entrance links
  go first and are never refused; a link closing a cycle is dropped. A connected floor is a tree exactly when inter-room
  doors equal rooms minus one. (A no-loops union-find added to `linkDoors` alone changed nothing
  measurable, because scenario 73 plans through `plan()`: fix the pass that writes the doors you test.)
- **A flag set before `SimWorld.open` does not survive it**: the unload calls `SimState.leave()`, clearing
  `generatedFloor`. Per-map state belongs in `SimBuilder.build`, which all five floor paths go through (`SimFloorGen.
  generate`, `SimGenerator`'s two map-code entries, the map editor, `SimRunHistory`'s rebuild).
- A map-code build (`SimBuilder.build`) is a "generated floor": the sim's Dungeon Breaker refuses every block until
  `SimRun` has started. A test that breaks blocks must `SimRun.begin` first.
- **A rebuild from INSIDE the sim must hand `SimState` the new map code.** `SimWorld.open` skips the world reload in the
  sim, and only that path called `SimState.enter(code)`, so `SimState.roomNameAt` (trap-ability rule, sidebar Room line,
  tab list) decoded the FIRST floor. The shortcut calls `SimState.replaceMapCode`; scenario 99-sim-im builds A then B.
- **A new floor carried the last session's starred mobs.** `SimMobs.forget()` kept STARRED, Fel markers and star tags
  (89-sim-starred-mobs read "1 spawned, 5 starred"); it now does everything `forgetAll()` does. Sim mobs are persistent and
  loaded back from disk after `SimBuilder.clearFloorMobs` swept, so `SimWorld.open` deletes the overworld's `entities/`
  region files before reopening for a build. A rebuild INSIDE an open world still relies on the sweep, which cannot see
  mobs in unloaded chunks; not seen to matter yet.
- **"Sim build took N ms" measured from the PREVIOUS build** when a single-room load queued its clear first
  (`startedAtMs` was set only in `submit`); every entry point marks the start now. Do not trust a build time from a log
  older than 2026-09-30. `ClearJob` walks chunk sections like `RoomPlacer.SectionWriter`, skipping all-air sections with
  `hasOnlyAir()` (1.79M blocks cleared and pasted in 702 ms), and clears the whole world height.
- **The wipe's box.** `SimSecrets.place` runs from the completion callback after the queue moved the build's chunks into
  `lastBuiltBounds`, and calls `SimBuildQueue.touched` for its chests, so `touchedBounds()` handed the next wipe ONLY the
  secret chunks and an old room stood under the next one. `touchedBounds` is the union of both now; 97-sim-roomspawn fails
  on any block outside a room's capture band.
- Testkit 88 "the world CHANGED" was the test server's random tick (one grass_block turned dirt); the arena compare
  ignores a grass/dirt swap.

## Single-room loads

`SimBuilder.buildSingleRoom` must do everything a floor build does; each of these was missing once.
- **It never published its own map**: `LiveMapFeature.publishSimFloor` was only in `build()`, so the map kept the last
  floor's 22 rooms. Scenario 91 requires the map to go from 22 rooms to exactly 1.
- **A single-room load never armed anything**: no `SimRoomPuzzles.armFloor`, `SimPrince.scan` or `SimRoomLevers.armFloor`,
  so a room loaded alone was scenery (no blazes, buttons, beams, pads, silverfish, so no solvers or autos). When a sim
  puzzle "does nothing", first ask which path built it.
- **It had no map code**, so `SimState.roomNameAt` answered from the previous one. `buildSingleRoom` sets one, and
  `roomNameAt` maps by tile, `2 * floorDiv(x - origin + 16, 32)`, not to the NEAREST cell (which left the outer half of
  every 1x1 in a connector cell).
- **It kept the last run's Dungeon Breaker regrows**: it never reset `SimBreakerState` (every other rebuild does, via
  `SimWorld.resetPerMapState`), so a run's restores, due ten seconds later, landed in the next build. It resets now, and a
  newer break of a block drops any older restore waiting for it. Testkit 96-ar-398-regrow.
- **`/reset`** is `SimBuilder.resetSingleRoom`: `buildSingleRoom` through `SimWorld.open`'s in-world shortcut so
  `resetPerMapState` runs first, at the load's rotation and the yaw/pitch the spawn teleport recorded (`soloFacing`). A
  floor build or the flat room clears `soloLoad`. `SimMimic.chooseForMap` re-rolled the trapped chest on every build, so a
  reset passes the load's chest as `prefer` (testkit 1771 block diff: the only 2 of 1,478,400 that differed; anything else
  random would show there too).
- **Client command suggestions are a snapshot.** Fabric's `ClientCommandInternals.addCommands` copies only client nodes
  whose `requires` passes WHEN the commands packet arrives (javap, command-api 3.0.5 and 3.1.0), so a
  `requires(SimState::canAct)` command depends on packet order. `SimResetCommand` re-applies the last packet
  (`ClientCommands.refreshCommandCompletions`) whenever `canAct` changes. A failed `requires` sends the line to the server
  unchanged (1775).

## Landings, spawns and /goto

- **Landing = the room's main floor, by a doorway.** `snapPlayerTo` (single rooms, room cycle, `/goto`): `doorwaySpot`
  measures each mask doorway's floor in the world (RoomDoors' 3x4 window rule, >= 11 of 12 open or a shut door, in y 64..82
  + `SimAltitude.offset()`), takes the height most doorways share (lowest on a tie; the walk-in side first for a 1x1 on a
  generated floor), stands two in (then 1, 3, 4, +-1 sideways) on solid with feet and head clear of blocks and fluid, facing
  in. Testkit `97-sim-roomspawn`: before, 67 of 133 single rooms landed off the doorway floor and 11 of 22 `/goto` on an F7;
  after, 133/133 and 23/23. Search a doorway's floor in the doorway BAND: the lowest 3x3 gap was a roof window wherever the
  capture held a shut door (Blood, 46 blocks up). Negative y is valid in the sim, so no `-1` sentinel (see
  [SIM-SERVER.md](SIM-SERVER.md)).
- **Special landings** (traps, Teleport Maze, Boulder, Ice Fill) use `openingFloor` (the band rule) and fall back to
  `doorwaySpot` when that side has no doorway or the spot is unsafe. They used to scan UP FROM THE BOTTOM OF THE WORLD
  (`/goto New Trap` stood him at y -56 under a doorway at -9). Trap rooms and Teleport Maze land one block in from the
  entrance doorway (offset 14, the blaze rooms' number), facing in; "the entrance" is the doorway a BFS from the Entrance
  over `SimBuilder.floorPlan` (the room's own cells excluded) reaches first, or the first measured doorway on a single room.
- **Rooms that want a different spawn than their middle**: `SimBuilder.spawnFor` is the table (x/z offset plus a flag that
  scans DOWN from the top, which only Higher Blaze needs). The blaze doorway side comes from the room's measured `RoomDoors`
  mask; offsets are turned by the paste rotation. `/goto` uses the same table. A downward scan only accepts a spot with
  something over it (`/goto Higher Blaze` found the roof).
- **Every landing needs cover overhead** in both scan directions; a column without one gives way to the nearest covered
  column in the same tile (rings out to 15), else the old uncovered scan. `/goto museum` was on the roof: Museum's four tile
  centers are solid pillars y58 to the roof at y119.

## The Map Designer, filters and history

- **A filter that matches a room's NAME cannot find a room TYPE.** Ask `SimFloorGen.typeOf`. The room database has 140
  entries: NORMAL 108, RARE 12, PUZZLE 11, CHAMPION 4, TRAP 2, BLOOD 1, ENTRANCE 1, FAIRY 1, all upper case. It loads on a
  background thread and `typeOf` answers NORMAL until ready, so a screen filtering by type must rebuild when it arrives.
- **One Filters panel.** `SimRoomFilter` is the one model (rows, save/load, `matches`), `SimRoomFilterScreen` the one panel
  (`Chip` widgets), and `SimRoomFilters` holds `DESIGNER`, `PICKER`, `CYCLE`, each in its own file (designer:
  `killer560smod-sim-designer-filters.json`). Per place only the `Use` differs: the designer exempts puzzles from every row
  but Kind and Puzzles; All Rooms' DEFAULT (no Kind: no puzzle/Blood/Entrance/Fairy; no Kind and Secrets Any: no 0-secret
  room) equals `SimRoomRoutes.isEligible`. Multi-select rows store what is CHOSEN, empty meaning any (the first build's
  hidden-sets file reads as no filter). Every row is database data: Kind is `typeOf` with RARE as Normal and Old/New Trap as
  Trap; Rare room is RARE (11 in `rooms-modern.json`); Champion is CHAMPION (Default, Dragon, King Midas, Shadow Assassin,
  all 0 secrets); Fairy is FAIRY.
- Generate passes `SimRoomFilters::generatorAllows` to `SimFloorGen.plan(..., allow)`, which never filters Entrance, Blood,
  Fairy, a trap or a pinned room, and lays the floor out again from every room on any shortfall (`PinnedFloor.missed` /
  `Planned.missed`), restoring the recency memory first.
- **`ModChat.send` says nothing from the main menu** (it drops the line when `client.player` is null), so the designer's
  status line is the only feedback there; the filter note leads it ("filters too strict - used every room · ...").
- The designer draws only the floor's rectangle, scaled into the 6x6 square; the floor button regenerates a generated floor
  at once and drops pins that no longer fit. Play sets `SimState.setFloorLabel` (designer floors had played as F7). The
  sliders stop at the floor's tops; Puzzles' top follows Rooms to blood as it is dragged (`SimSlider.setMax` clamps).
- **Save is gone; Play records.** `SimMapHistory` (`killer560smod-sim-map-history.json`, newest first, 200 kept, in
  `ProfileData.EXCLUDED_NAMES`) keeps every floor played from the designer, a generated one by its map code (rotations and
  doors - never replay a generated floor from the grid), a drawn one by its cells, no repeats. `SimMapPresets` went; old
  designs came in once as "Saved design" (file mtime, F7, puzzles -1), a `migratedPresets` flag keeps a deleted one
  deleted, and `killer560smod-simmaps.json` is never read or written again.
- Testkit: `75-sim-designer-sliders`, `75-sim-designer-floors`, `75-sim-designer-load`, `75-sim-designer-count`,
  `75-sim-map-editor-filters`, `386-ui-sliders` (`ui/FilterPanelCases`).

## The room picker, the room cycle (All Rooms) and the pause button

- The sim menu runs from the main menu; see [SIM.md](SIM.md) for `SimWorld.open` taking the build as a callback.
- Route eligibility (`SimRoomRoutes`): database entry exists, type not PUZZLE/BLOOD/ENTRANCE/FAIRY, `secrets > 0` (113 of
  135; every CHAMPION room has 0). "Has routes" = `RouteStore.forRoom(name)` has a node; library and database names match
  for all 135 and routes are room-relative, so no rotation key. The picker's Routes filter is the Filters panel's "Your
  routes" row.
- **All Rooms** is `SimRoomCycle` (replaced `SimGenerator.generateAllRooms`). Sets come from `SimRoomRoutes.isEligible` plus
  `matches(NONE/HAS)`, ordered by `RoomLibrary.names()`, FROZEN when picked. Each step is a `buildSingleRoom` (one room at
  a time). `/next`/`/back` stop at the ends, act only while `SimRoomCycle.isActive()`, refuse while
  `SimBuildQueue.isBusy()`, and are `requires(SimState.canAct)`. No installed mod or vanilla registers top-level `/next` or
  `/back` (constant-pool scan, 2026-10-06). Keys are raw-polled (`killer560smod-sim-roomcycle-keys.txt`), shared with the Sim
  Keybinds tab, checked only on a press (so `currentSoloRoom` is not read every tick).
- "Single room" means `SimRoomCycle.isActive()` is false (`buildSingleRoom(client, name, fromCycle)` sets it, AFTER the
  no-world branch so the main-menu re-entry keeps the flag). Then `/next`, `/back` and `/simbuild noroutes` say "/next and
  /back only work in All Rooms", the keys do nothing and the pause button (`Next room (All Rooms)`, `step(+1)`) is not
  added; a picker load ends the cycle. The All Rooms submenu stays visible (it starts a cycle).
- The pause screen's Change Room button is a vanilla `Button` 4 px under the lowest center-column button, read from the
  screen's widgets (not pinned to `height - 46`); `belowVanillaColumn` must skip the next-room button or a resize stacks
  Change Room under it.
- Testkit `97-sim-roomcycle`, `97-sim-roompick`.

## Run replays

- **A replayed run's rooms faced rotation 0 because the run never stored one.** Each `MapRoom` in `RunRecord.MapSnapshot`
  now stores the DATABASE rotation the live map read (`DungeonLayout.clayRotation`); `SimRunHistory` pastes at that minus
  `RoomCaptureRotation` (the inverse of `SimRoomIndex.add`); older runs pick the turn whose `RoomDoors` doorways meet the
  map's doors. Testkit 97-sim-savedrun (old jar: 19 of 24 turned, 13 doors unbacked).

## The floor bench (`FloorBench`)

The bench must build the game's floors, or it measures something else. It once never loaded the room database (no
puzzles, traps as ordinary rooms - `SimFloorGen.typeOf` said NORMAL and `shapeOf` null - and the eleven L rooms a generated
floor never gets, which `SimFloorLayout.candidates` leaves out because their 2x2 box has a void or a neighbor's quarter)
and carved every `links()` adjacency instead of the `doorLinks` tree. Now it loads
`tools/bench/roomtypes.json` (cut from `rooms-modern.json`; override `-Droomdata=`), caps champions
(`LayoutSim.capChampions`), carves exactly `doorLinks` and seals every other measured doorway; `-Ddiag=true` checks each
carved door and attributes failed clicks per floor. Seed 560 (`regress.sh`) after: whole floors 100% / 8.26 warps (old
planner 92.4% / 15.84), small floors 100%, 3.43 warps against the every-landing reference's 3.29 (x1.043, limit 1.045).
The whole-floor warp limit was re-baselined to 8.28 in `floorbench-expect.txt` (reason written there) and
`small.minFoundPercent` raised to 100. Remaining failures (21 of 6,000) start or end in Lower/Higher Blaze, Balcony (box a
tile off), Three Floors, and one Waterfall>Fairy; not investigated.
