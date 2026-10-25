# Interactive Map trips, Auto Secret and Auto Clear lessons

The map's planner and executor (`ClearExecutor`) at run time, Auto Secret's room order and waits, and Auto Clear. Part
of the automation lessons: [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md) (Auto Clear kill speed and Key rooms are
there); Auto Routes is in [LESSONS-ROUTES.md](LESSONS-ROUTES.md); the sim's side of the path search is in
[SIM-MAP.md](SIM-MAP.md). Problem, then fix; verified only.

## The floor graph and the planner

- **The floor graph (`WarpGraph`) is used only from the Interactive Map's planner thread.** Auto Routes' path planner ran
  `findDungeonPath` on its own thread while the warm-up ran on `killer560smod-etherplanner`; two threads in
  `WarpGraph.node` pushed the node count past its 1024-long arrays without growing them, and every later node threw AIOOBE
  one index higher until the world changed (96-ar). Submit through `ClearExecutor.onPlanner`; `graphFor` throws if called
  elsewhere.
- **Anything else on the planner thread delays every trip.** Auto Secret's room-order trip searches ran in front of a real
  trip: on a cold graph one search took 1197 ms and the trip's first warp went 1865 ms after it was asked for (144).
  `RoomTour` skips its searches while a trip search waits (`ClearExecutor.tripSearchWaiting`) or the graph is not warm
  (`EtherwarpPathfinder.graphWarm`), and a trip whose path is being planned ahead waits for that search instead of
  queueing a second one behind it.
- **Hypixel's dungeon HAS a roof you can land on.** `TeleportUtils.underCover` and the floor graph's landing rule refused
  open-sky landings only in the sim, on the belief that a real floor is solid rock; an F5 Interactive Map path stood him at
  y 100 on the corner of four rooms. The rule applies everywhere since 67ce488a (testkit 363-logic-roof-cover).
- **The floor graph's warp count is an upper bound, not the fewest.** Its 2x2 buckets, 18 aim points and early-blocked
  refusals missed a route with fewer warps on 21 of 180 bench path pairs. Anything that must be minimal goes through
  `ExactWarpSearch` (via `EtherwarpPathfinder.findOptimalPath`), which proves it - see "Fewest warps, proved" in
  [SIM-MAP.md](SIM-MAP.md).
- **The floor graph's height band must reach under the floor** (his Altar #9 -> #10). Every planner search took landings
  only from floor - 20, and Altar's lower level is 26 below the floor, so the exact search's empty first backward layer read
  as "no route (proved)". The band starts at Hypixel's y 0 (floor - 69, `EtherwarpPathfinder.graphMinY`); testkit 941
  plans 2 warps and flies them.
- **"No route (proved)" was followed by a plan anyway** (testkit 634, Scaffolding #12/#17/#36). The goal sat in a 5x5 floor
  pocket no warp from outside reaches (testkit 947's server-side closure: 24 landings reach the goal, none reachable from
  the start); the exact search proved that, then `planFloor`'s fallbacks (the floor graph's NEAR landing, the room-by-room
  planner's "near") returned a chain ending on a stair 4 blocks up, which `RoutePathPlanner.save` stored as the pair. A
  path node's plan is now checked hop by hop to end ON the block (`planFloor`, `onBlock` only) and refused otherwise, with
  the reason in chat; the map's tile and near clicks are unchanged.
- **Background floor-graph work must use FloorGraphs' own "knows the floor" rule, not `warmedOnce`.** The sim's full graph
  warms once on the ~250 landings of the sealed entrance before GO. TripCache read "full graph warmed once and not warm" as
  "a search would finish a re-warm first" and waited for the whole floor's warm-up: phase 1 ready 7.9-12.2 s after the gate
  opened (147). A press in that state plans on the QUICK graph (`FloorGraphs.KNOWN_RATIO`), so
  `EtherwarpPathfinder.searchReady` asks the same: 2.4-3.7 s on a loaded machine, most of it the quick graph's warm-up.
- **A precomputed trip planned on the quick floor graph is not flown once the full graph is warm** (fps-fix-1016): a press
  in the second after the full graph warmed flew 15 warps where the full graph's fresh plan took 11 (147, 2 of 2 runs).
  `Entry.outgrown()` makes the press search (a few ms on the warm graph) and the cache replan those trips first, outside
  the quiet gate.
- **A check that reads the client's chunks disagrees with a planner that reads LevelEtherGrid** wherever the client has no
  chunk (the sim's server mirror fills those columns for the planner). Far trips at a floor's start hit it: the plan
  validator refused every Ice Fill plan and the recorded-spot check sent the trip to the wrong block (fullclear-1016 runs
  036/038). Both ask the planner's grid when the client has no answer (52d1e614). A trip that never names its `target`
  cannot give up: Ice Fill looped for 480 s on "trip to null" (9a151f5b).
- **A dungeon trip's length is not its straight distance.** Two rooms 45 blocks apart were 13 etherwarps apart by the floor
  graph (the walls send a trip round by the doors), and one 115 blocks away was 4 (145). `RoomTour` uses the planner's own
  warp counts, and its fallback estimate takes the larger of the straight line and 2 warps per room on the map's path.

## Trips

- **A server correction can land between a map path's search and its first warp** (131-sim-auto-clear
  "correction-plan"). The search takes well under a millisecond, so the teleport packet and the search result are drained
  in the same frame; `ClearExecutor` started the queue from the old spot and replanned as an ordinary failure - no chat
  line, no alarm, and a second one in a click would have STOPPED the path. Same for a packet during the arrival settle
  ("ended N blocks from the planned landing"). Both are corrections: reported, and the goal planned again from where he was
  put without counting against `MAX_REPLANS`. Auto Clear ignored every packet during its trips; it now adds
  `ClearExecutor.serverCorrections()` to its own.
- **Another feature's warp landing is not a correction of the map's trip** (88 alarms in one Ice Fill crossing). A trip
  with every landing answered sits in its arrival settle (or waits to plan again) for ~9 ticks; Auto Ice Fill hopped on the
  first tile inside that window, each 1-block hop read "the server moved you off the last landing", and the replan of the
  old goal dragged him back across the room. `ClearExecutor` counts its own uses against `RightClick.usesSent()`; a moving
  packet another feature's use (last ~2 s) accounts for is that feature's landing: in the settle the trip ends as arrived,
  while planning it is not reported. A move with no use behind it still reports (93-solve-icefill-maptrip asserts both).
- **"Already there" is within five blocks, not on the block.** The map planner calls an exact goal within five blocks
  "already there", so a caller that insists on closer asks again for ever: Auto Clear's wither door required 2.5 blocks
  and, standing 3.6 off, logged a trip and an "Already there" every tick without clicking (131); once a trip to the door
  has finished it clicks from where he stands, and says why if the door is out of reach. Auto Clear's fast trip landed 4.9
  off its stand block in Flags and Mines and asked again every other tick for 16 s: a repeat trip to the same block asks
  for the block itself (`etherPathArrive`), and when that ends off it too the mob is skipped (`AutoClearFeature.tripTo` /
  `tripsCannotReach`).
- **The Interactive Map's arrival settle held every route start ~9 ticks.** Auto Routes stays inert while `ClearExecutor`
  is busy (interlock 5), and a trip stayed busy for its whole post-landing settle, so a start node armed 11 ticks after the
  trip's last warp (146, every room). A start-node warp (`ClearExecutor.etherPathArrive`) ends on the tick the server's
  packet confirms its last landing (`finishArrivalEarly`, asked by Auto Routes at the top of its tick): 2 ticks in the sim.
  Two things the settle had hidden: he is still 0.05 above the block on that tick, so a planned-ahead first warp is aimed
  again from where he is ("Failed from ..."), and `canPath`'s on-ground rule held the next trip a tick or two
  (`canPath(layout, true)` accepts that settling). And the map's body turn must be handed back before the route turns the
  body for its first action that same tick: left to `ClearExecutor`'s own tick, later in START, it undid the route's turn
  and the warp drew GrimAC BadPacketsJ (62-argrim-imwarp-run).
- **A map trip that sends every hop on the tick it is worked out (`ClearExecutor.sendNow`) loses hops under an unpaired
  block click.** With 1015-sim-clickgap's click primer a hop went while the previous landing was still on its way,
  `RightClick` judged it from the planned spot, the server traced it from the spot before and paired it with the click:
  "warp 5 never landed". Every early-sending trip (planned ahead or precomputed) sends a hop early only while
  `RightClick.unpaired()` is false; otherwise it goes the next tick through `doInteract`'s turn-first check
  (1015-sim-clickgap 3 of 3 runs, 0 hops ignored). The other early senders (Auto Routes' landing-tick warp,
  AutoReposition) only send after the landing is known and already go through `RightClick.mustTurnFirst`.
- **One item use per tick.** `ClearExecutor.onTickStart` has two hop slots, the held hop's (`doInteract` first thing) and a
  precomputed/planned-ahead queue's `sendNow` slot after `handleQueue`. A first hop held behind an unpaired block click
  went from the first slot with its block half, which paired the click, so the `!unpaired()` guard on the second slot let
  the next hop out in the same tick: two `use_item` with two looks before one movement packet, GrimAC BadPacketsJ, and two
  teleports answered together (62-argrim-autopilot, about one run in seven). Bisected to 04dfc9c5 (the unpaired guard).
  `doInteract` holds a hop while `RightClick.itemUsesSent()` moved since the tick began. Testkit 62-argrim-1016-oneuse
  reproduces it every run. The "Simulation 5.99" in that session's console is the positive control's own flag.
- A trap route that ends at the trap room's edge is not out of it for the next warp. Auto Trap stopped the route the moment
  `roomAtWorld` left the trap, about a block and a half out; Auto Secret's planned-ahead warp went at once and the sim's
  server answered "No abilities in a trap room" (142's trap case). Auto Trap counts him out 2.5 blocks past the edge
  (`AutoTrap.LEAVE_MARGIN`); the route's walk carries him there.
- Auto Clear logged "etherwarp toward" before `startTrip`, which only turns the body back on that tick (Phase.RELEASE) and
  comes through again the next, so one trip was logged twice (131 case 9). It logs once the trip is really under way.

## Auto Secret

- **Auto Secret's Room Order** is Least Etherwarps (`TourPlanner.shortest`, exact branch and bound over the travel alone,
  then nearest-next improved by moving single rooms) or Largest To Smallest (`RoomTour.byWeight`), and only those two; with
  every room left at 3 unfound or fewer it is Least Etherwarps whatever is set (`RoomTour.effectiveOrder`, one place for the
  choice and the plan-ahead). The value-weighted `TourPlanner.plan` is Dungeon Autopilot's alone. The room on the near side
  of the blood door (`AutoSecretFeature.preBloodRoom`: the door cell's two neighbors, the one that is not the blood room) is
  held out of every order while any other room with secrets is left, open or behind a door; the endgame takes it just
  before it would wait on a teammate's door. Testkit 1018-logic-room-order, 1018-sim-autosecret-preblood.
- **The last-node wait** (killer560, 2026-10-15: "it will leave a room before a secret is grabbed and registered"; his Gold,
  Basement and Scaffolding cases). The wait holds the next trip as a queued `ClearExecutor` path, and Secret Aura stood down
  while `ClearExecutor.isBusy()`; the hold also kept the sneak down, and a sneaking right click with the AOTV in hand is the
  AOTV's (`SimAbilities.blockWins`, as Hypixel's 1.8 server). Now the aura acts while the trip is `heldAtStart`, the hold
  lets the sneak up only while an aura click is due (`SecretAuraFeature.clickDue`) and turns the body to the first warp's
  look once it has gone, and a secret the aura has in reach and has not seen taken (or a database item secret by his feet)
  holds the warp until it registers, bounded by `TAKE_TIMEOUT_TICKS` (testkit 147-sim-autosecret-lastsecret). His rule
  (2026-10-20): wait at most 20 ticks, only when a secret is close, else leave at once - the cap is 20 and the old flat
  6-tick wait is gone. That 6-tick wait had hidden that secret chests spawn in only after he has been near them a moment
  (like bats), so at a route's end the aura's scan can see air where the chest will be; `takingHere` also counts a database
  chest in the aura's reach that is not there yet (up to `ITEM_APPEAR_TICKS`, as an item).
- **Deciding the next trip inside the wait overwrote the wait's own count.** `onRouteDone` calls `decide`, which sets
  `routeStartClock`/`routeFoundAtStart` for the NEXT room, and the wait then counted the room it was leaving from that
  room's found count ("every secret counted after 1 tick" with the room still 4/5). The wait keeps the leaving room's own
  (`leaveStartClock`/`leaveFoundAtStart`).
- Auto Clear waits while ANY route runs. A route Auto Secret stopped for stalling re-armed on the node under him a tick
  later, and the clear it had just handed over waited 13 minutes. Every hand-over to Auto Clear goes through
  `AutoRoutesFeature.cancelForInteractiveMap` first, as a map trip does.
- The "every secret found" stop ended runs on the trip to the last uncleared room (the last secret tends to come in then)
  and, before that, at 100% with up to 17 rooms never cleared. It waits for a clear under way and for every mob room, and
  carries on while a puzzle is left (`unfinishedPuzzleLeft`). Older base-jar sim times on such floors are not comparable:
  they skipped the clears.
- Kill Mimic runs outside routes too (Secret Aura's trapped-chest click under Auto Clear), and Auto Secret warped away under
  it; it then fired 51 sceptre uses straight down in the next room. Auto Secret holds for it (`holdForMimic`), and it ends
  once he is 16 blocks from the chest, or when the chest's window has come and no new baby zombie is by it 4 ticks later
  (plus server lag) - Dueces' trapped chest opened as a plain chest and Kill Mimic had fired its whole 5 s timeout at nothing.
- Route data, not code (left as recorded): Redstone Key has no await node within click reach of its key spot (19,66,7)
  (#8 waits for 2: the item and the placing, and the key is never taken under the aura's await-only rule); Golden Oasis
  #1 (start) waits for 1 at the entrance with no secret in reach; Supertall #13 CRYPT sits 1.41 off #20's landing, a step
  he walks by hand. Quiz cadence (waiting at Oruo for the next question) is the 4-12 s "TRAVEL" still at floor start.

## Auto Clear

- Generated sim floors have no mobs and clear every room on entry, so an Auto Clear comparison there measures nothing.
  Testkit 1016-sim-fullclear `-PfullclearMobs=N` places starred mobs (seeded by map code) and turns clear-on-entry off;
  `-PfullclearNoTraps=true` keeps trap rooms out (his trap routes do not work yet).
- `AutoClearFeature.estimateClear` prices a clear by its cover count far more than by where he starts (the AOTV range makes
  most first trips one warp), so clear-first vs route-first is nearly always a tie: route first. The time Fastest Total
  saves is the re-entry trips and settles, about 12 s a floor in the sim (8 floors, 2026-10-20).
- **Chest rule** (killer560): `autoclear/ChestClearRule`, fed by `RouteRunMemory.gotSecret`. A mob room's chest grabbed
  under Auto Clear or Auto Secret counts it cleared 3 s on; not cleared on the map 30 s on, it is returned once (Auto Secret
  drops it from `clearsDone`). Testkit 1020-sim-chestclear.
- A held sceptre use sends a bat every 4 ticks, so a second bat is nearly always in the air when the first hits (sim
  flights 5-12 ticks). Counting only mobs that got exactly one bat learned nothing in 131e; a death before the second bat
  could land counts as a one-bat kill (`BatLog.secondLands`).
- The wiki's Guided Bat "follows your aim" (Spirit Sceptre page); the sim's bat flies straight. One-Tap Warp At Once turns
  away while the bat flies, so the sim cannot tell whether it misses on Hypixel; After Impact keeps the aim on the mob
  until the bat is due.
- Kill speed and key rooms: see "Auto Clear kill speed" and "Key rooms" in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md).
