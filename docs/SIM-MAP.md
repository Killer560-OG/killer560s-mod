# Sim map - the live map and the Interactive Map's etherwarp

The dungeon map in the sim, and the Interactive Map's etherwarp planner and executor (`EtherSearch`, `WarpGraph`,
`ExactWarpSearch`, `ClearExecutor`), with the bench numbers they were tuned on.
Part of the sim docs: [SIM.md](SIM.md).

## The live map in the sim

- The dungeon map does NOT work in the sim by itself. `LiveMapFeature` fills its grid from the vanilla map ITEM and
  Hypixel's clay markers; a singleplayer world has neither, so the scan finds nothing and overwrites whatever else tried
  to fill it. The sim calls `LiveMapFeature.publishSimFloor` instead and the scan is skipped while `SimState.isActive()`.
  Anything else that reads Hypixel coordinates in the sim needs `SimAltitude.offset()` added to its y -
  `DungeonLayout.doorBlock`, Secret Waypoints' database y and its 68..108 lever band all did. So did Wither Doors (until
  2026-10-08): its gate refused the sim (no server address), and `WitherDoorScanner` read roof heights 73/74/81/82 and the
  anchor at bare y 69 inside a 0..255 band, so with the floor near y -60 it found no door. It now shifts all of them by
  `DungeonLayout.simYOffset()`; testkit 538 checks a sim wither door's box.
- **The map is a fixed square with the floor's grid inside it.** Cutting the HUD panel to the bounding box of the rooms
  revealed so far (`MapPainter.panelUnits`/`autoFit`) made every room shrink each time a far room came into view
  (killer560, 2026-10-04: "It shouldn't do this weird fill in and shrink in real dungeons"); both are gone. The outline is
  always `MAP_UNITS` square at the Room Size setting, and `MapPainter.floorFit` scales and centers the floor's whole grid
  inside it from `DungeonState.getFloor()` alone (`MapPainter.floorRooms`: E 4x4, F1 4x5, F2/F3 5x5, F4 6x5, rest 6x6, M
  floors as their number - from `DungeonMapScanner.calibrate`'s start corners and the FloorSizeLog samples). The sim
  reports M7 and always lays out on the 6x6. A drawn room outside the floor's grid widens it rather than overflowing (only
  if the table is wrong). The Interactive Map's `ppu()`/`originX()`/`originY()` use the same fit, and `cellAt` goes through
  them, so clicks still map correctly.
- **The green room's open faces.** The Entrance (green on the map) has a complete capture (all 1,089 columns seen) and
  still shows the void through its back wall and window bays: on Hypixel that is scenery outside the 31x31 tile.
  `SimBuilder.closeGreenRoomShell` fills air in the room's own outermost layer with stone (the "only where the ring is
  already air" rule of `SimBuildQueue.submitSeal`, stone rather than diamond so it disappears). It runs from `doorWork`
  BEFORE the doorways are carved, so the one real door is reopened afterward; after would brick up the way out.
- **An L room on the map is three tiles, not its 2x2 box.** `publishSingleRoomMap` marked the whole box;
  `LiveMapFeature.union` refuses a fourth tile for shape "L", so the box split into two groups both named for the room (his
  "Altar 0/6" twice). Every L did it (Altar, Chambers, Dino Site, Layers, Market, Melon, Pirate, Spider, Well,
  Withermancer). `SimBuilder.singleRoomCells` drops the capture's empty quarter (no block in y 66..99, RoomTileAudit's test;
  emptiest tile otherwise, with a warning) and its connectors. Market is the one L with no empty quarter (its emptiest,
  tile 0,1, still holds 18,311 blocks); whether that is its real missing quarter is not verified.

## Unreproduced: "this room is not part of supertall it is its on 1x1"

His 2026-09-30 screenshot showed a 1x1 drawn as part of the neighboring 2x2 Supertall. Measured, it did not happen:
scenario 90 plans a floor, publishes it through `LiveMapFeature.publishSimFloor`, captures it through
`DungeonLayout.capture` and compares which placement owns each tile against which map room owns it. Over **240 planned
F7s, 160 of them holding Supertall, 8,639 room tiles across 5,263 map rooms: zero merges and zero splits.** Scenario 79
checks the one floor it really builds. The three ways two rooms could merge are each sound: `SimFloorGen.markRoomCells`
fills a rectangle of reserved cells and never a neighbor's; a connector between two rooms is left `NO_ROOM` so it is never
`Tile.ROOM`; and `rebuildGroups`' union-by-name is capped at the room's own `shapeTileCount`. Supertall's capture is 65x65
and the database says `2x2`, so no footprint mismatch either. Likeliest reading: the STALE map from the single-room bug
(fixed, see [SIM-FLOORS.md](SIM-FLOORS.md)). To go further, get that floor's map code (`SimState.mapCode()`), not a guess.

## The Interactive Map's executor

- **A second goal cannot simply be issued over a running one.** `ClearExecutor.etherPath` returns immediately while
  `pathPending`, and a plan starts from where you were when you pressed - `ClearNode.inside` needs you within 0.32 blocks
  of the first hop, so once you have warped off that spot the new queue is inert and `isBusy()` never clears. A new goal
  must `cancel()` and then be issued from a later tick. `cancel()` also had to stop sparing `syncDelay`, which kept
  `isBusy()` true for up to 49 more ticks with no completion callback left to run. Anything that holds a goal across those
  ticks must publish its own "still steering" flag: `isBusy()` is false for the whole wait by design, and Auto Routes'
  interlock 5 reads exactly that, so a node underfoot would arm in the gap and steer against the warp about to start.
  `InteractiveMapFeature.isSteering()` is that flag.
- **Hops chain one a tick from the predicted landing** (QUOI's design; a 20-warp path takes one second). In the sim each
  etherwarp hop goes through `SimAbilities.etherwarpAlong`, which resolves on the integrated server from the server's own
  copy of him with the planner's ray (`TeleportUtils.getLook` + `traverseVoxels`, sneaking eye 1.27), as Hypixel does.
  (Earlier the sim resolved from the CLIENT player, who had not moved yet - "no etherwarp target there" in bursts - and the
  fix of dropping the cached position after each hop made every hop wait to land.)
- **The executor checks its landings** (fix-map3, 2026-10-04). It used to trust the prediction completely: a refused hop
  was "done" anyway and every later hop was cast from the wrong spot (nine `[Sim] no etherwarp target there` in one
  second), and a path whose first spot he had left sat in the queue for ever (`isBusy()` true, Auto Routes inert, every
  later click retargeting onto the dead queue). Now:
  - Every hop's expected landing is recorded and each server position packet is matched against them (0.6 horizontally,
    1.3 vertically, so the first ticks of the fall still match). A packet leaving him where he was is ignored
    (rotation-only correction); one that puts him anywhere else drops the rest of the hops and the same goal is planned
    again from where he really is, at most twice a click, with a chat line ("Warp 4 put you 12.3 blocks from where it was
    aimed - planning again from here (1/2)").
  - At most 6 hops in flight unanswered; 20 ticks with no progress gives up with the reason (`warp N never landed`, `the
    sneak never reached the server`, `not on the path's first spot (x blocks off)` - that one after 5 ticks on the
    ground). The arrival sync waits for every hop to be answered, and "arrived" runs only within 1.5 blocks of the last
    landing. A replan waits up to 3 s for the ground and a room a path may start from (`canPath`), then stops with a
    message.
  - The planner thread's result is handed back even on an `Error` (catching only `RuntimeException` left `pathPending`
    set for good); a search that does not answer in 10 s is dropped with a message.
  - Log lines: `[Path] running N warp(s) from ... to ...` and `[Path] off the plan: ...`. Every wait names itself.
  - **On Hypixel** the same: an etherwarp is answered by one position packet to the landing (what QUOI's executor syncs
    on), so a correct path confirms hop by hop, and a lagback or refused warp stops the chain within a tick or a second.
- `AutoClearUtils.canPath` must not test a room NAME for "Trap" once `isTrap` has read the database: Arrow Trap is NORMAL,
  and the leftover QUOI rule made every map press from its back half silently do nothing (99-sim-arrowtrap, 2026-10-14).
  Nodes in a trap, a maze or Boulder may be landed on but never warped from (`canPath`'s rooms).

## Etherwarp onto the roof, and what the interactive map actually needs

"somehow my etherwarp pathfound onto the roof of the dungeon while using interactive map." The sim pastes each room as its
own captured column with rock above the ceiling, and the cells between rooms are empty air, so the sim's map is a cluster
of rock towers with open sky between; a planner whose moves are "etherwarp at anything you can see" will climb one.
`TeleportUtils.underCover` refuses a landing with nothing above it, skipped within six blocks of floor height (nearly
every landing on a clear), so the normal path costs nothing. It was written as sim-only on the belief that Hypixel's
dungeon is solid rock; Hypixel's HAS a roof you can land on, and the rule applies everywhere since 67ce488a (see
[LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md)).

A room click goes to the room, not the exact block: "it just needs to go to that room, more specifically if that room is a
1x2 then it should go into that rooms quadrant that I clicked. It can choose anywhere in that room whatever is fastest."
The old fallback, `nearestEtherwarpable` on the tile center (a 25-block sphere by distance from the center), could leave
the tile, pick a block 20 up or land on a wall. `etherwarpableInTile` stays inside the clicked tile's footprint at its
floor height; since the floor graph, a room click goes to ANY landing in that first band, whichever is fastest. A room
override that is not standable falls through to it. An exact block nothing can aim at falls back to the nearest landing
within 5 blocks in its room.

## Aims and the landing height ("off by one")

Two causes. The planner stood him at block top + 1.05 (QUOI's Hypixel number) while the sim stood him at + 1.0, so every
sim hop was aimed from an eye 0.05 too high; the sim now lands at + 1.05 too and the planner uses that one value. And
QUOI's aim points are mostly 0.001 from a block's edge, with its top-center point exactly ON the top face, which the voxel
walk files under the air block above, so a ray grazing an edge flips to the neighbor on any rounding.
`getEtherwarpDirection` aims at face centers first and returns an aim only after casting the real float yaw/pitch for
exactly the hop range and seeing it land on the block. Hop range is the held item's 57 + tuners, minus one block, at most
60 (`ClearExecutor.hopRange`); it was 60 for everyone, which an item with fewer than three tuners cannot do.

## Path search speed (`EtherSearch`, `LevelEtherGrid`)

The search runs over a byte-per-block grid (`LevelEtherGrid`): flags read once per chunk section straight from the
`LevelChunkSection`, kept across searches and dropped by packet hooks (`LiveMapPacketListenerMixin`, at RETURN of the
block, section and chunk handlers) or after 15 s; a primitive heap and open-addressed node map instead of
`PriorityQueue`/`HashMap`; a leg ends the moment any landing satisfies it. The search has no Minecraft imports, so
`tools/bench/run.sh` times it outside the game. Before the rewrite (his Map Logger log, 2026-10-01): median 6 ms, worst
153 ms, eight "Failed after ~675ms" timeouts.

- **The bench was reading most of every room as air.** `EtherSearchBench` cut each palette at its first `]` - the one
  inside `chest[facing=north]` - so every later state was air, and the first table measured on it was too optimistic.
  On the fixed bench (2,000 clicks, sections cached): mean 1.96 ms, median 0.76, p99 13, max 20, 100% found.
- Bench limits: its flags come from palette NAMES, not `TeleportUtils`' instanceof rules, and a "fresh" fill copies an
  array, cheaper than reading a real section. The `[Path] ... total N ms` log line is the in-game number.
- **"Room hop N failed" right after a build, fine after teleporting there**: the sim writes a floor without telling
  clients, and with Keep Chunks Loaded / the Chunk Cache his client keeps every chunk it ever had, so every chunk not
  re-sent since the rebuild held the PREVIOUS floor. `LevelEtherGrid.mirror` snapshots the built floor from the server
  level at the end of the build; a column reads from it until his client receives that chunk or a block change in it.
  Sim only; dropped on leaving and on a single-room load. Not reproduced offline - the reasoning is the log pattern plus
  `ChunkCacheManager`'s own "Known limitation".
- **"No single-room path ... within 57.0 blocks a hop", 670 ms, ten times**: the target was Tic Tac Toe's chest, which in
  his capture sits in a walled alcove - "etherwarpable" and unreachable (rebuilt in `tools/bench`, `-Dttt=`: the room
  alone is exhausted after 295 nodes). A same-room target is searched inside the room, exact first and then any landing
  within 5 blocks, and the last leg of a multi-room path gets the same near fallback; the unbounded search still runs if
  both fail.
- The fan (6 x 7 degrees) can miss a single goal block from every node; the A* also tries a verified `aim` at the goal
  from every node it expands.

## The map's etherwarp plans the whole floor at once, by warps (`WarpGraph`)

killer560: "Get it to the point where it is only a few ms every time and prioritize using as few warps as physically
possible" (his log: "11 leg(s) ... total 94.02 ms, 40 warp(s)"). It took so many warps because the planner went room by
room (one leg per doorway, bounded to the two rooms it joins, so it could never skip a room) and every move was a ray of
the 6 x 7 degree fan: from an eye 2.32 above the floor, pitch -6 lands about 22 blocks out and pitch -13 about 10, and
nothing between 22 and 57 blocks along a floor is ever hit.

Now `WarpGraph`, wired in `EtherwarpPathfinder.findDungeonPath` / `findDungeonPathToTile`:
- Nodes are the floor's landings thinned to one per bucket of columns and height (the one nearest the square's center;
  bucket 3, then 2 - below), except along every tile's center lines and in every doorway box, where each landing is its
  own node (every door sits on a seam at a center line). Without those the graph took 12% more warps than every-landing;
  with them under 5%.
- An edge is a verified aim at a node within reach (the real float yaw/pitch cast for exactly the hop range must land on
  the block). A line to the block's top center stopped in its first part (`partialFrom`) is refused without trying the
  other 17 aim points; stopped later, the full aim runs. That cheap refusal misses 1.4-4% of the pairs the full aim
  accepts (bench `-Daimcheck`).
- A click is A* on (warps, distance). Once the floor is warm the heuristic is exact: a room click reads a per-tile
  distance field (breadth first backward from the tile's floor band, built when warming completes); an exact block gets a
  backward breadth-first search from the nodes that can aim at it, stopped at his own landings.
- A node's edges are kept. Every section its rays read is recorded; `LevelEtherGrid` sets a touched section's old flags
  aside and the planner thread reports it to the graph only if the FLAGS changed (`processChanges`), so a lever or a chunk
  re-sent unchanged costs nothing. Air sections are cached too, so a chunk arriving can be compared.
- `EtherwarpPathfinder.tickWarm` (from `ClearExecutor`'s tick, Interactive Map on, in a dungeon) keeps warm-up slices
  running on the planner thread, the rays on up to six low-priority worker threads (a quarter of the cores), yielding to
  any click. The old room-by-room planner is kept as the fallback whenever the graph finds nothing. Without warm-up (a
  fresh graph each click) the graph search is useless - 21% found in 670 ms - which is why warm-up exists.
- `WarpGraph` is used only from the Interactive Map's planner thread (see [LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md)).

**Measured** with `tools/bench/floor.sh` (`FloorBench`, built as described in [SIM-FLOORS.md](SIM-FLOORS.md)): whole
floors, random clicks from walkable spots, half tile clicks and half exact blocks, every returned path replayed ray by ray.
First run, 5 floors x 300 clicks, seed 560: old room by room 93.9% found, 13.56 warps mean, 91 ms mean, p99 670 ms; new
warm 99.9% found, 7.62 warps, 1.33 ms mean, median 0.76, p99 5.3 (tile clicks median 0.38 ms, p99 1.4; exact blocks
median 1.9, the backward search most of it). No path was invalid. Warm-up: about 15,000 nodes and
3 s a floor on the bench with 6 threads; in game 4.6-4.9 s for 21-22k nodes.

**Minimality.** Within its graph the search is exact (re-planning 200 clicks with only the geometric bound gave the same
warp count every time, `SELF-CHECK`). Against a reference graph of EVERY landing with the full 18-point aim (900 clicks on
3x2-tile windows): reference 3.48 warps, graph 3.60, 728 identical, 95 one more, 3 two more; the old planner 5.24, with 11
paths that did not replay. The reference is not "physically" minimal either: it is a fixed set of 18 aim points per block
(see the exact search below).

**Regression test:** `tools/bench/regress.sh` (thresholds in `tools/bench/floorbench-expect.txt`) fails on more warps, a
lower found rate, any invalid hop, any click the old planner finds and the new one does not, or exact heuristics changing a
warp count. It once printed two javac errors and exited 0 because it piped the bench into `grep`; it now checks the bench's
exit status and requires its `SELF-CHECK` line (the general lesson is in [LESSONS.md](LESSONS.md)). After the path-to-blood
generator it failed on main because the bench was not building the game's floors (not the planner; fixed and re-baselined,
see "The floor bench" in [SIM-FLOORS.md](SIM-FLOORS.md)).

## Block changes, cheaper aims, a denser graph (night-path, 2026-10-05)

- **A block change dropped a third of the floor graph.** One door change dropped 2,000-5,900 of ~15,000 nodes (a node was
  thrown away if any ray read any of the changed SECTION's 4,096 blocks), re-ran warm-up, and a click in that window fell
  back room by room with 39-40 warps where the graph gave 17-22. Now `LevelEtherGrid` reports the box of the blocks whose
  flags changed and `WarpGraph.blocksChanged` re-checks only the aims whose cone from the eye into the target passes near
  it, aims at landings that appeared and drops the ones that went, on the warm-up workers. `FloorBench -Dchangecheck`
  (every node re-expanded from scratch after each change): 0 mismatches. A bucket's landings are listed under the column
  its FIRST block is in, which a column check misses at chunk edges. A click on a floor that was warm finishes the re-warm
  first, never gets the cold budget, and skips room by room when the warm graph proves there is no way (a closed door) -
  `[Path] not a proof of no way because: ...` when it cannot prove it. `-Dchanges=8`: fell back 98 of 192 -> 0, warps
  14.17 -> 8.44, median 43 -> 1.3 ms.
- **Aims.** Failing 18-point aims were ~70% of warm-up's rays. `EtherSearch.aimPast` skips an aim point whose line passes
  through the inside of a block that already stopped a line to the same target: same edge counts, 44-71% fewer rays.
- **Density.** Bucket 3 -> 2 and partial aims from 0.3 of the way instead of 0.6: whole floors 8.26 -> 8.04 warps (the
  every-landing reference 7.81). Bucket 1 reaches the reference (7.89 vs 7.88) but takes 11-14 ms at p95 and holds 12M
  edges, so it is not used.
- **Clicks.** Exact goals stop their backward search after 3,000 nodes and bound the rest through per-cell fields (fewest
  warps into each 32 x 32 cell's column, built with the tile fields); ties among equal bounds go deep first. Whole floors,
  warm: median 0.65 ms, p95 3.6, p99 7.2.
- **In game** (testkit `95-sim-map-warp`, generated F7s): 17 presses planned in 0.2-5.3 ms, 1-17 warps, every one moved
  him 23-160 blocks into the clicked room with the arrival confirmed; a press right after stone was placed beside him
  planned in 0.7-0.8 ms. On Hypixel a door opening is a block change like any other.

## A click during the first warm-up: the quick floor graph (night-warm, 2026-10-05)

Before the full graph's first warm-up completes (about 5 s of planner time on six threads) a click got 40 ms on the
half-built graph and then room by room: 13-14 warps where the warm graph takes 9, up to 711 ms (`FloorBench
-Dearly=0.5,1,2,3`). Searching the half-built graph harder cannot fix it: an unexpanded node costs about 1.5 ms of rays a
thread, and a cold tile click's A* closes about 6,170 nodes (its bound is just "one more warp"); a door-following
heuristic still needs 940-2,600 nodes.

**`FloorGraphs`** pairs the full graph with a QUICK graph of the same floor: bucket 5, every landing kept only on each
doorway's own center line (7 columns per door), no partial-occlusion aims. A tenth of the rays: warm in ~0.3-0.5 s,
6.4-7.8k nodes. It is warmed first, follows the full graph's block changes (`WarpGraph.follower`), and until the full
graph is warm a click is planned on it - exact on its own landings, every hop a verified aim (0 invalid on replay). A click
before it is warm finishes it (at most 600 ms). Chosen by sweep: bucket 4 is ~0.2 warps better but a click at t=0 waits
~150 ms longer; door boxes instead of lines buy nothing; a 25 ms look on the half-warm full graph bought 0.0-0.3 warps and
was dropped. Memory: 10-13.5 MB beside the full graph's 51-70 MB (`-Dmemcheck`), kept for the floor.

"Has been warm" is not "knows the floor": the sim warms 288 nodes in the sealed entrance before GO, and a real log has a
279-node warm before the real 13,454. A re-warming full graph counts as knowing the floor only if its last finished warm
had at least 1.3x the quick graph's nodes (the whole floor is ~2.6x). A click must apply the queued changes before reading
`warmDone()`, or the click right after a door opened searches stale fields (`-Dearlygate`).

Measured (3 floors x 10 clicks per t; warm graph in brackets): at 0.5 s 14.07 warps / 20 of 30 fell back / max 711 ms
before, 9.47 (8.63) / 0 / max 232 after; at 3 s 13.17 / 8 / 65 before, 11.13 (9.90) / 0 / 1.7 after. Entrance gate
opening (`-Dearlygate`): 12.0-12.8 warps before, 8.9-9.7 after, none fell back. `regress.sh` runs both early modes
against `early.*` in `floorbench-expect.txt`. In game (`95-sim-map-warp`, a press three ticks after GO): 11 warps in
475 ms vs 8 warm (26.1.2), 15 in 246 ms vs 13 (26.2); the old jar fell back, 19 vs 10.

## Fewest warps, proved: the exact search (path-optimal, 2026-10-11)

killer560 (2026-10-09): "I will line up where it is only 1 etherwarp and it will take 2 for some reason [...] Make sure
that it is 100% perfect as to the most optimal route." The floor graph misses routes because it thins landings to
buckets, tries 18 aim points a block, refuses a line blocked early (`partialFrom`; `startPartialFrom = 1` for his own
position), and lets a non-exact goal end on a "near" landing. The 1-vs-2 case reproduces as `tools/bench/exact.sh
-Dlip=true`: a goal whose top is seen from a pillar only along its far tenth.

`ExactWarpSearch` (no Minecraft imports): nodes are EVERY landing plus his exact position and the goal block; an edge is
`EtherSearch.aimExact` - the 26 fixed points, then every face turned to the eye split into quarters down to 1/16 of a
block, a cell skipped only when its four corners are all behind the inside of one block already seen to stop a line
(convexity makes that a proof), each point kept only if the real float hop lands and `holds` at +-0.01 degrees. Breadth
first from both ends: a route of a + b + 1 warps exists exactly when some node of F_a reaches some node of B_b, rounds try
1, 2, 3... and the smaller side grows a whole layer between rounds, so the first round that finds a pair is the minimum.
Ties: shortest total hop length (a tile: nearest its center first). The graph's plan is the upper bound
(`maxWarps = graph - 1`) and the fallback. Every outcome logs `[Path] exact: ...` (proved / NOT proved).

Measured (`tools/bench/exact.sh`): path-node pairs, 3 floors x 60: the graph took more warps than the minimum on 21 of 180
(one a single warp it took 2 for); exact search median 9.6 ms, p90 125 ms, max 2.6 s. `-Dbrute=5`: 15 pairs by brute force,
0 mismatches. Whole-floor presses (`-Dfar`): fewer warps on 12 of 25 (17 -> 15 at most), but 0.02-23 s to prove.

- **Path nodes are ALWAYS proved** (`EtherwarpPathfinder.findOptimalPath`, from `RoutePathPlanner`): 1.5 s on the planner
  thread, then `EtherwarpPathfinder.lastProver` carries the same search on with no deadline on the
  `killer560smod-pathproof` thread; RouteExecutor holds the pair until it is in; a world change cancels it.
- **The map does not search before its first warp** (`MAP_EXACT_NANOS = 0`): it flies the graph's plan and
  `ClearExecutor.improveInBackground` runs `remainderProver` from a warp not yet made (1.5 s slices, moving further along
  each time) and splices a shorter rest into the queue.
- **Full top faces only** (killer560: "never teleport onto a half slab on the bottom of a block [...] if I place a node
  specifically on a bottom half slab then it can go there"). On the way, a landing must have a completely flat, full top
  face: `LevelEtherGrid.fullTop` is `Block.isFaceFull(collision shape, UP)`, so bottom slabs, chests, soul sand, farmland,
  paths, the low half of stairs, enchanting tables, panes, bars, carpets, pressure plates and the rest are off limits, on
  top of the old blacklist (walls, fences, gates, hoppers, cauldrons, banners). (An interim rule, `LevelEtherGrid.lowTop`,
  refused collision tops under 0.75; it is gone.) Snow layers are passable to the ray. A goal he chose (a path node) is
  never refused for it; goals the mod picks itself (`TeleportUtils.landable`: `etherwarpableInTile`,
  `nearestEtherwarpable`, a room override spot, Auto Blaze's spot via `isEtherwarpable`) never are one.
- **Breaker Aura picks plan as air** for path nodes (`AirOverride` over the exact search and the fine room search, never
  the shared floor graph, whose plan is only kept if it still lands in that world; `BreakerAuraFeature.plannerTreatsAsAir`
  decides, so only while the aura is on).
- `/ar replan` (`RoutePathPlanner.invalidateAll`) clears every saved chain in every room and re-plans this room's at once.
  Testkit 631-638 (`SimPathOptimalTests`).

## The map and the puzzle autos (fix-map3, 2026-10-04)

- **Tic Tac Toe's chest trip.** With "aura the chest" on, the first placed move sends him to the room's secret chest
  (click, chest, back, carry on). A path that could only land NEAR the chest led to about a hundred `already there`
  searches in four seconds: the leg re-asked the map every tick and only ended on "standing on it" or "in reach". Each leg
  of the trip now asks for ONE path and is over when that path is; the aura stage moves on after three tries; every stage
  change is an INFO line; the executor says "Already there" once per goal per 2 s. Trips to a block now go to a standable
  block that has it in reach (see [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md), "A chest is not a block to etherwarp
  onto"; the auto's sim side is in [SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md)).
- **Teleport Maze: the map hands over.** The map's spot for the room is the doorway, relative (15,68,-2).
  `ClearExecutor.arrivalSeq` moves when a path ends where it planned; within 3 s of one, standing within 3 blocks of the
  arrival and at the doorway spot or inside the maze, before any maze teleport, Auto Teleport Maze walks onto the start pad
  (15,69,12) and runs from its teleport. Walking in yourself does not trigger it. Same on Hypixel.
- **Teleport Maze: round the walls.** The capture has a cobblestone wall (collision 1.5) in the middle of two sides of
  every chamber, between the two pads on that side, so a straight walk to the pad beside you timed out.
  `autopuzzles/MazeWalk` is a grid Dijkstra at the pad's feet level (8 neighbors, no corner cutting, 0.6 step-up, nothing
  solid in the body's 1.8), thinned to straight legs, steered with the camera and the forward key only; it re-plans if
  pushed 1.6 blocks off its line, falls back to the straight walk if it finds no way, and its timeout grows with the
  planned length. `pathfinding.GraphPathfinder` needs recorded island graphs, which a dungeon room has none of.
  `tools/mazecheck/walkcheck.py`: 84 pad-to-pad walks, 28 blocked going straight (the same-side pairs), 0 without a way round.
- **Teleport Maze: one closed loop** ("make sure tp maze only has one closed loop, not multiple"). Seven chambers of four
  corner pads, every pad a two-way link to a pad in another chamber, the start linked to one, one exit to the center. The
  wiki's way through is to take the pad diagonal to the one you arrived on. Random pairing split the fourteen diagonals
  into several loops, and a walk entering one off the start-to-exit line could go round it for ever.
  `TeleportMazeLinks.draw` lays all fourteen diagonals in one chain (start, d1..d14, exit), no two neighbors in the same
  chamber, each entered by a random one of its two pads; the center pad returns him beside the start, so
  start-chain-center-start is the one loop. `tools/mazecheck/run.sh`, 100,000 draws: old 39.4% / 42.1% / 15.7% / 2.7% with
  1 / 2 / 3 / 4+ loops and 26.6% of landings never reaching the center by diagonals; one chain 100% one loop, 0%. From the
  start, diagonals reach the center in 15 pads (11.3 before). Sim only; on Hypixel the pairing is the server's own.
