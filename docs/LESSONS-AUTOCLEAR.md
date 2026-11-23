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
- A start node on a block no etherwarp lands on exactly is skipped ("landed, but its start node did not arm"): the
  start-node warp asks for the block itself, the planner logs "the block itself cannot be reached" and flies its NEAR
  landing (up to ~5 blocks off), and the node arms only inside its ring. Seen in testkit 147 when its stand was under a sim
  chest that had not spawned yet (2026-10-26); a recorded route's start, etherwarped onto, does not hit it. Not changed in
  the mod: walking from a near landing onto the node would be a new behavior for him to decide.
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
- A room Auto Clear leaves at its last death (`pendingClears`) is out of the next pick's order until the map's clear
  comes in, so the Progress HUD header must count it as done: counting it nowhere read "0% (0/1)" after the first of two
  rooms (testkit 131 hud, 2026-10-25). `publishPlan` adds the pending rooms not still in the order.
- Kill speed and key rooms: see "Auto Clear kill speed" and "Key rooms" in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md).
- A sleeping Fel is a starred tag over an INVISIBLE enderman (Hypixel's, per recorded notes; the sim's `SimMobFloor` floors
  copy it): `MobEspFeature.starredMobs` resolves it like any mob, and shooting it does nothing until a player is within 9
  blocks. `AutoClearFeature.approachFel` (2026-11-01) leaves sleepers out of the targets while awake mobs remain, then
  etherwarps inside 7.5 blocks of the nearest and waits for it to show (testkit 1101-sim-mobfloor).
- Waiting at a shut wither door with no key, Auto Clear only looked for keys within 40 blocks (`KEY_SEARCH_RANGE`): the
  key it needed dropped 50 blocks away the tick it set off for the door, and it waited 12 minutes. `keyAnyRange` (2026-11-01)
  takes a Wither Key anywhere in view while a door waits on one (testkit 1101-sim-mobfloor).
- Pockets the etherwarp graph cannot land in or leave (Banners' ledge in the sim, 2026-11-01): a key or a sleeping Fel there
  was left for good after one failed trip, and from inside it the door trip failed four times. Now: up to 3 stands for a key,
  then one forced walk (`walkInsteadOfTrip(..., force)`, up to 32 blocks, whatever the etherwarp estimate); a Fel trip that
  ended out of range walks; a door after a failed trip walks; and at a door with no key a key left earlier gets one more go.
- A key room's single-room clear ends on "room cleared" in the tick its last mob dies, before the key's stand reaches the
  client, so Auto Clear hands back with the key lying there (simmobs2-1101, 1016-sim-fullclear on a mob floor: Multicolored's
  key, then Auto Secret waited at the door it opens for 12 minutes). Auto Secret now picks up a reachable lying Wither Key
  before it waits at a shut wither door, and when none lies anywhere and the door's key room counted as cleared only for its
  chest (ChestClearRule), the come-back falls due at once (`ChestClearRule.returnNow`) so the room is cleared for its key.
- On the Grim arena (62-argrim) the Auto Clear cases leave starred mobs alive (acbat's: the server has no bat). A later case
  that counts on its own mobs only must kill the leftovers first: 62-argrim-1102-acfel saw 5 starred mobs and its Fel was
  woken by a hop kill beside it, so no Fel trip ran (2026-11-02).
- Insta Clear Warning's key rooms are KeyRooms.guards: the room BESIDE a shut door. The sim keys the room before Fairy for
  the wither door after Fairy (Museum > Fairy > Mines), which the map rule never calls a key room, so its insta clear is not
  warned (1102-sim-instaclear, 2026-11-02). Which room holds that key on Hypixel is unknown; left for the owner.
- The sim reports the floor as M7 (`DungeonState.getFloor`, on purpose), so Insta Clear Warning's requeue in the sim is
  /joininstance master_catacombs_floor_seven.
- Dungeon Mob Highlight's Fel Skulls took only a player head or a plain skull; the sim's sleeping Fels wear a wither skeleton skull
  (his "black fel skulls"), so none was drawn. It takes all three now (1102-sim-felskulls).
- Hyperion casts across (achype-1117, killer560: "it only hyped straight down never across to other boxes"): a Wither Impact is planned once per plan key over a fan of looks (`WeaponReach.hyperionBest`, 24 yaws x 6 pitches, each landing predicted like the sim's dash, blast radius 6 less a 1 block margin) and the landing that reaches the target and the most other live mobs wins; unseen known spawn spots (`BloodRushData.worldBoxes`, each box's bottom center; the shared table, else the jar's fallback boxes) only break ties. An across cast must be held as Phase.HOP (ATTACK presses again every 2 ticks and would dash twice). Where a straight-down cast already reaches the target, the across one is used only when its mob set is a superset with strictly more mobs, so it never costs a kill the old plan had. A chain cast replaces only a TRIP (swap, etherwarp, swap): the old hop still goes first where it keeps the plan, a chain link must land in reach of the target with every live mob of the plan's block for it in the blast, and it is taken only when its measured time beats the trip: 1 turn tick + the cast as timed on this run (use tick to standing again, less the predicted fall; EMA, prior 3) + the fall (`WeaponReach.fallTicks`, vanilla gravity under the box corners) <= trip + 2 (a tie goes to the chain: the trip price leaves out its swap ticks, and a tied 1-warp trip cleared at last death 9 where the chain did at 7). A flat ~2 tick link price (new5) ignored the turn, the cast and the fall and lost 1124 -> 1283 ticks; between casts reaching the same mobs, the one that falls least wins. Measured on two replayed floors (31 rooms, -PacsCodes): chains placed before hops, or with no plan rule, left the tour (Museum 6 -> 11 warp uses, Crypt 65 -> 90 ticks, the floor 1124 -> 1206 ticks); one per-room tick difference of under ~10% is load noise (Bridges 81 -> 91 with every decision line identical). The final rule, A/B alternating on one checkout under load: origin 1077 and 1079 ticks, new 1142 and 1045 (the 1142 run made every decision the origin did; its trips alone ran slower), 0 chains on those floors, every room cleared. In the owner's layout (1117-ac-hype-chain) origin casts, swaps, etherwarps, swaps, casts; the new rule casts twice with no hotbar change. A test that counts hotbar changes "between casts" must count one only once a later cast lands (the swap back after the room is not between casts). A room with neither shared nor fallback boxes (the mob rooms Buttons, Cathedral, Museum, Well and others, 2026-11-17) has no spot weighting: its casts plan on live mobs only.
- Auto Clear alone (Quiz First, no Auto Secret) used to stand at the Quiz through all three locks (220, then 100 ticks). acquiz-1117: `quizGapPick` runs at each pick and once per countdown at the Quiz (keyed on `QuizState.answerAtTick()`), never per tick; it prices a room with `estimateClear` (mobs in sight only, its trips included) plus `etherTicks` back onto Auto Quiz's answer spot. The owner allows a late return while work remains ("It doesn't need to be back tick perfect"), so a room up to `QUIZ_GAP_LATE_TICKS` (100) late is taken when none fits
(SUPERSEDED 11-21 ~11:00 PM, his newest words: back "the second that it's ready for me to come back", never LATE; a late
pick is now cut when only the trip back is left, see "Quiz return" below); it stops starting gaps once the trip back alone fills what is left. Testkit 1117-sim-acquiz-gap / -wait (a mob F7 with the Quiz pinned; -PmobfloorCode replays it).
- Landing pre-aim and stale trip state (achype6-1117): while the last etherwarp of a trip is in flight Auto Clear already swaps to the Hyperion and looks down (`aimForLanding`, "Hyperion ready for the landing"), so the first cast goes the tick he stands. Two statics survived from one clear to the next and made rows order-dependent: `lastTripGoal`/`sameGoalTrips` (a goal the last clear reached counted as a repeat, so the next clear skipped a starred mob "no trip lands on" and left it alive; now cleared in `begin` and when a trip lands on its goal) and the learned chain link time `linkMean` (kept on purpose; the testkit puts it back to 3.0 per case). On the local test server the last landing confirms within one tick of the last warp, which leaves the pre-aim no tick: same-tick rows run with 100 ms latency. The room-time "kills" count and the run total count only the targeted mob and doomed bat flights, not splash/chain deaths: compare runs on the "every starred mob seen here is dead (N deaths)" lines (killer560s-mod-logs/achype/a6-deaths.py), which matched per room on origin and branch.
- acquiz2-1118 (his 11-18 Hypixel run, Untested Cheat): the Quiz gap above is Auto Clear ALONE. With Auto Secret driving (Auto Clear Rooms) `AutoSecretFeature.decideQuiz` fills a countdown only with SECRET rooms whose round trip fits; with none (no route, or too long: 176/138/138 ticks left each time) it went back and stood 405 ticks while Multicolored (a 25-tick clear next door) and Chains were uncleared. Real countdowns: question 1 answerable 220 ticks after Oruo's intro, 2 and 3 exactly 100 after the previous question line (about 140 after the answer click), so the sim's 220/100 is right. Both gap pickers priced a room only from starred mobs IN SIGHT (`estimateClear`, null otherwise), so a room not seen yet was never a candidate: `estimateClearOrGuess` prices it as the trip to its middle plus `UNSEEN_CLEAR_TICKS` (60). New `quizGapMobClear` in Auto Secret chains mob-only rooms the same way (longest that is back in time, else least late within 100). Testkit 1118-sim-acquiz-secret.
- Blood First detoured after every key room (acbloodfirst-1118, his 11-18 runs): Hypixel redraws the sidebar Keys line about once
  a second, 0.05-1.0 s AFTER the "has obtained Wither Key!" / "opened a WITHER door!" chat line, and `DungeonKeys.witherKeys()`
  preferred the sidebar, so the decision taken the same tick read 0 keys and went to a puzzle or room (Ice Path 14 s, Tic Tac
  Toe 11 s) before the door. A chat change now overrides the sidebar value it was seen on until that value changes or 2 s
  pass (blood key too). The logger mod's "in team team_7" lines show the redraw times. The sim has no Keys line (his 10-04
  wish), so testkit 1118-sim-acbloodfirst draws one every 20 ticks. Same job: under Blood First no puzzle (Quiz First, Ice Fill
  First, Autopilot PUZZLE picks, endgame puzzles) starts before the blood door is open (`AutoSecretFeature.bloodFirstHoldsPuzzles`).
  A second cause showed in the sim: the room is "done" when its last mob dies, but its key reaches the team 0.3-0.6 s later, so
  the decision of that tick planned another room (Gold, Buttons, Overgrown). After a clear, while the next door wants a key it
  lacks, `Autopilot.awaitKeyAfterClear` now waits up to 1.5 s (SETTLE) for it. A testkit case that reads a long run's log must
  collect it as it goes (`LogTap.between`): the 4000-line ring had dropped the early keys and the case judged only the tail.
- Flaky sim door cases (acdoorflake-1121): 1115-ac-dooronly-sim failed on some random floors with "no block to stand on at the wither door" (or an endless "not etherwarpable" trip). The testkit client's view distance is 5 chunks (80 blocks); the Blood door's approach can be 160 blocks from the first door, its chunk is not loaded, the client reads air there, and TeleportUtils.landable / nearestEtherwarpable find nothing: the case was wrong, not the mod. Any sim case that sends Auto Clear to a far door sets renderDistance 16 first (as 1118/1117 do) and restores it; the case logs its floor seed (`-PdoorPin=SEED:RD` pins it, RD 0 = old distance). Auto Clear is cheat-only, so 1020-sim-ac-keyroom is in Scenario.CHEAT_ONLY (it failed "no use-item packet" on every legit jar: nothing automated to watch).

- Insta Clear's `testUseFile` must drain SharedWriter AFTER `ensureLoaded()` too: loading a v1 file migrates it and saves on SharedWriter asynchronously, so testkit 361-logic-insta-clear-v2 read the file before the v2 write landed ("the file itself is now version 2") on a loaded PC, in the run and the alone recheck (acpuzzles2-1121 lane, 2026-11-21; origin passed only by timing).
- A run held for a rejoin (secrets.DungeonRun.holding) keeps LiveMap's grid AND the calibrated map item past the world
  change, so in the next dungeon `WitherDoorsFeature.runStarted()` read the OLD map as "the run has started" until
  DungeonRun dropped the hold and LiveMap reset (his 26.3 log 2026-11-22 09:04:14: Dungeons Autopilot armed, then started
  ~7 s before the gate). Fixed at the source (acstart-1122): `DungeonMapScanner` stamps the world it calibrated in and
  `LiveMapReads.mapItemCalibrated()` counts it only in that world or on `DungeonRun.resumed()`; Mort's line is a world stamp
  too (`mortLevel`), so no tick order can read a stale flag. Testkit 1122-hx-runstart-heldmap.
- Door Only at GO from the Entrance spawn of a seeded sim F7 logged "only a wither door (67) stands before Blood" and then
  stood still: the door was 157 blocks off, its chunks were not loaded, `nearestEtherwarpable(approach)` read air, and
  openDoor waited silently ("no block to stand on") for chunks that only come once he moves. Now the stand is read
  through the planner's view (`TeleportUtils.nearestEtherwarpablePlanned`, the sim's server mirror), else a trip goes into
  the farthest room on the map's way there whose chunk he has (its door is planned again on landing), and every openDoor
  wait is logged once (`doorWait`). Testkit 1122-sim-ac-dooronly-start.


## Slowdowns from his 11-21 Untested runs (acslow-1121)

- A wither/blood door is open when its center column (y+0..2) is air, and the client sees that the tick the server sends
  the block change: `DungeonLayout.doorShut(idx, blood)` reads the blocks (map lock only for an unloaded chunk). Waiting for
  the map tile to flip plus a fixed 10-tick SETTLE cost about 1 s a door; DOOR now leaves straight to DECIDE.
- A CLEAR trip to a room next door walked to the door before handing off to Auto Clear; a room at most one step away (or
  the current one, or a key room on the way) is handed off at once, so Auto Clear starts on the mobs it can already see.
- Auto Clear treats Blood Rush spawn boxes (`BloodRushData`, the starred mobs' default spots) as known mobs ("spawn spot",
  lostId -2), so with nothing seen it roams after 5 ticks instead of 40 and visits real sightings first, then the spots;
  a visited spot goes into `spotsSeen`.
- Started in the green room, Auto Clear, Auto Secret and Autopilot only ARM (`autosecret/ArmedStart`, one slot): nothing
  plans or gives up on a room before `WitherDoorsFeature.runStarted()`; the same key, Escape or the Emergency Cancel Key
  disarm it, a world change drops it. Testkit 1121-sim-acslow-{green-queue,door,firstmob,unseen} (one Autopilot run on a
  fixed floor; bounds from the origin vs branch run, see killer560s-mod-logs/acslow-1121-state.md).

## Remaining idle gaps (acgaps2-1121)

- Hypixel takes a Wither Essence 1093-1247 ms after the click (all four aura clicks in his 11-21 log), so the end-of-route
  wait for "secrets still being taken" (20 ticks) held for every clicked essence and gained nothing: it now skips a
  clicked essence (`SecretAuraFeature.takingBeforeLeaving`). The sim took essences the same tick and hid this; it now
  takes them `SimSecrets.ESSENCE_TAKE_TICKS` (22) after the click.
- Blood First waited up to 1500 ms after every CLEAR for "the cleared room's key", also in rooms with no key (King Midas):
  it waits only when the room is a key room (`KeyRooms.guardOf`), the same rule the sim uses to place keys.
- A room handed over to Auto Clear finished only when the map's checkmark came (~4 ticks after the last death); it now
  finishes on the last death when no known mob (spawn spots included) is left, as standalone runs already did. A mob not
  yet seen comes back: a room still not cleared on the map 60 ticks (+ server lag) later goes back into Auto Secret's to-do
  list (`AutoClearFeature.drainHandBackReturns`). Key rooms and King Midas still wait for the checkmark.
- A path warp Hypixel never answers (door 81: use seq 15, no teleport) idled the full 20-tick stall deadline before the
  replan. Now a hop in flight with no answer is judged like Auto Routes' refused warp (`RouteExecutor.refusedRetryTicks()`,
  fed by Auto Clear's own landing times through `noteLandingTicks`), plus server lag; testkit 1121-sim-acgaps2-warp-lost
  drops one use on the sim server. Not code faults: a server stall (door 71, PerTickServerClock 15 behind; the deadline
  grows by ServerLag, so the retry went 0-2 ticks after recovery) and puzzle work before the blood door (door 109); the
  room order runs on the etherplanner thread in slices. Testkit 1121-sim-acgaps2-{lastdeath,keywait,essence}.
  That deadline (2 x slowest landing + 2, then the eaten-use floor) still read 14 ticks after a 2-tick flight: with no
  ping measured `RightClick.eatenRetryTicks` guesses 300 ms (11 ticks). `RouteExecutor.lostWarpTicks()` (Auto Clear only)
  is now the slowest of the last 5 landings + 3 ticks: one round trip has passed, so no double warp (origin 23, now 10).
- A door that used up its 12 clicks while the key count read -1 (unknown, so the clicks were also the probe) waited there
  until the 360 s cap once the key came (acslow-door's 99-tick rows, origin too): `doorClicks` survives `beginDoor`. A key
  count above the one at the last click now starts a new round (`doorClickKeys`). Testkit 1121-sim-acslow-door.
- A mid-run rejoin hears no Mort line, so ArmedStart armed Auto Clear / Auto Secret until the held map was read:
  `WitherDoorsFeature.runStarted()` also counts `DungeonRun.resumed()`. Testkit 1121-hx-rejoin-runstarted.
- Solo 300 planning (acplan-1121): Dungeon Autopilot planned clears and secrets as two passes, so a room was often
  visited twice (once to clear, once for secrets). `AutopilotPlanner.chooseFastest` picks per room the one visit the
  score still needs, least travel first, priced with the persisted per-room times (S1). `clearsLeft` counts CLEAR only;
  the clears phase takes CLEAR+KEY candidates. `clearsFirst() = isClearFirst() || getMode() == CLEAR_THEN_SECRET` is the
  one gate for the planner's clears phase (mob clears only; puzzles keep their own toggles). Testkit 1121-logic-acplan-fastest,
  1121-sim-acplan-*.
- Quiz gap filler (acplan-1121 S3): `quizGapMobClear` refused every route room, so with no whole route fitting in the
  Quiz countdown the gap was idle. A route room's clear is allowed when no whole route fits (solo, or Clear Room Before
  Secreting in a party). Testkit 1121-sim-acquiz-routes, 1118-sim-acquiz-secret.
- Quiz return (acplan2-1121, his 11-21 "come back ... the second that it's ready for me to come back, not a minute
  earlier"): `tickQuiz` decided again only on an answer, so a wait begun on ESTIMATES (the RoomTour searches it had just
  asked for land a second later) stood out the whole countdown. It now re-decides every 40 ticks, or 10 ticks after
  `RoomTour.finished()` changes, while more than 48 ticks are left. A gap route needs the same 20-tick spare as a gap
  clear. Clear estimates miss by seconds both ways (sim: a clear priced "back in time" came back 120 ticks late; refusing
  late picks left 95 idle ticks a countdown), so a Quiz-gap clear is CUT when only the trip back (+10) is left
  (`quizGapCutDue` in the AUTO_CLEAR phase, `runId++` so the cut clear's callbacks are ignored, `clearsDone` dropped so it
  is planned again); a clear that does not fit whole may start while 100+ ticks are left.
  Testkit 1121-sim-acquiz-return / -return-party (per-countdown idle from `quizIdleByQuestion()`).
- A trip search that found no way was cached as 2000 ticks for the whole run (audit S2), which ruled out every room whose
  trip back to the Quiz spot failed once. `RoomTour` now stores the estimate for it and searches it again on the next
  requests, up to 3 tries (`FAILS`); `UNREACHABLE_TICKS` is gone.
- Audit items that were not planner bugs (acplan2-1121): S5 "Gravel cleared twice" in 224231 was one TRAVEL/CLEAR stuck
  175 ticks at -191.5,69.3,-115.5 in Three Weirdos (never reached Gravel; trip failed, `clearsDone` dropped it, cleared
  later) - a travel problem. S8: Solo already values secrets past the S+ need at 0 (`usefulSecrets`) and drops zero-gain
  candidates, so it stops secreting once the need is met. S7 (blood last): the documented design; Blood First opens it early.
- Clear First touring for clears without secrets (acplan2-1121 round 3): with every reachable mob room cleared but more
  behind a closed wither door, `clearsLeft` went false and Clear First / Clear Then Secret secreted this side first; Party
  with no route left on this side cleared rooms while routes waited behind a door. `Autopilot.wantsBehindDoor()` now says
  which work the decide still wants, and decidePilot opens a door with that work behind it (`decideWitherDoor(.., want)`)
  before the other phase, with no key in hand needed (round 4: that guard let the key room's key, dropping just after its
  clear, slip by, and it secreted first), and drops the set-aside order (`Autopilot.dropCurrent`), or it was counted done.
  Round 5: Party picked a clear because of Clear Room Before Secreting (secrets still on this side) and was sent to a
  keyless door, re-picking forever; Party now goes for a door only with no secret candidate left, and with no key in hand
  or lying, a key room still standing on this side is cleared first (its key opens that door). The party-secrets case
  allows a wither key room's clear before routes (no teammate opens the door in the sim).
  Round 7: diverting AFTER Autopilot.decide left a "pick SECRET X" line for a pick never done (the case counted it, the
  sim credited its secrets). The pick is now held back unbegun (Order type DOOR_FIRST, `Autopilot.beginDeferred()` when no
  door goes first), and with no key a held-back CLEAR runs (its room may drop the key; Party looped 83 picks at a keyless
  door while the pick was the key room's clear).
  Round 8: a keyless held-back CLEAR now logs "no Wither Key for the door to the ... left - <pick> first"; the
  party-secrets case allows exactly those clears before routes (the key room by the map heuristic, Gold, is not always
  the sim's listed key room).

## Wait cuts (acwaits-1121)

- A wither or blood door opens on a LEFT click with any held item, not only the right click with the key (killer560
  2026-11-21). `WitherDoorOpener` taps it (START through the block prediction, ABORT, `McCompat.swingAttack`, gated by
  `DigWindow`) with no hotbar swap, in the tick Auto Secret reaches the door when the heard look already strikes it, else
  turn then click the next tick (was click at door start + 3). The sim answers an attack on a door like a use
  (`SimDoors`, AttackBlockCallback). Testkit 1121-sim-acwaits-doorclick, 1121-hx-acdoor-left-same-* (default and
  experimental Grim).
- AWAIT_ROUTE's 41 ticks were the route arming and stopping in the same tick (e.g. "BAT_WAND is not in the hotbar"), so
  the 40-tick arm timeout ran out: `RouteExecutor.starts()` tells it a route started, and it moves on at once.
- The 112-tick START_NODE stall was a path-less route waiting for him to walk to its next etherwarp node: Auto Secret now
  stops it after 10 still, grounded ticks (`RouteExecutor.awaitingWalk()`).
- A finished pilot puzzle went through a 10-tick SETTLE; it goes to DECIDE now.
- Kept, with cause: DOOR to open 20 t (blood 42 t) is the server's open delay (sim `SimDoors.OPEN_DELAY_TICKS`); QUIZ 100 t
  is Oruo's cooldowns; SETTLE after a failed START_NODE or CLEAR is the backoff; long ROUTE stands were his own keys or a
  puzzle auto's run time.

## Standing after landings (acwaits2-1121)

- Every Auto Secret door, room and secret-spot trip stood ~8 ticks after its last landing (his 11-21 alpha recording
  224531: landing 19791, DOOR 19799; landing 19837, AUTO_CLEAR 19845): ClearExecutor's arrival settle (`syncDelay` 2..10
  in `onTickEnd`), which only the fast goals (`etherPathArrive`/`Fast`) skip through `finishArrivalEarly`. Auto Secret's
  `beginTravel` now calls `ClearExecutor.endOnLanding()` and `tickTravel` asks `finishArrivalEarly` each tick, so the trip
  ends on the server's confirmed landing. `AutoClearUtils.pathToDoor/pathToRoom` are shared with the map's own clicks and
  stay unchanged. Testkit 1121-sim-acwaits2-settle (median from last landing to next step <= 2, was ~9).
- Cutting that settle uncovered the next hold: ActionGate's 6-tick teleport settle (`TELEPORT_SETTLE_TICKS`) held the
  door's left click (median 6 ticks from reaching the door, 1121-sim-acwaits-doorclick). The left click now goes through
  `Actor.DOOR_TAP` (freshEachTick, like Breaker Aura: it re-reads the door from his eye on the tick it clicks); the right
  click keeps `DOOR_OPENER` and its quiet tick after a server position. A wait you remove can unmask the one behind it:
  rerun every timing case of the flow, not only the new one.
- His 11-21 "sim" recordings (223131, 223204, 224231, 224531) were made on alpha.hypixel.net (latest.log "Connecting to
  alpha.hypixel.net"), not the sim: read their stands as real server behavior.
- Open: on Hypixel a Charged Chain burst of a map path often lands its first warp somewhere else ("off the plan: warp 1
  put you N blocks", 52 of 75 off-plan lines that night): at 22:32:43 the first landing matched the THIRD hop's look from
  the start spot, so the server judged that use with a later look. Each correction then replans behind the planner thread
  (queued 300-800 ms behind RoomTour's room-order search). Not fixed here (Charged Chain / Blink and planner code).

## Green room start (acgreenstart-1122)

- From the Entrance spawn every first trip spent two etherwarps reaching the spot 4 blocks in from the Entrance door (his
  11-22 runs, spawn -56.5,72,-188.5: warp 1 to -57.5,73.1,-183.5, warp 2 to -56.5,69.1,-172.5, warp 3 through). The
  Entrance has ONE door, so that spot is right for every first trip and is known from the world scan before the map.
  `autosecret/GreenRoomStart` walks there (ShortWalk) while Dungeons Autopilot or an armed Auto Clear / Auto Secret waits.
  It walks, not warps: no log shows an etherwarp working before Mort's line. His movement, jump, attack or use key ends it
  until the next arm; a screen pauses it; the run start ends it. Testkit 1122-sim-ac-greenstart(-keys), 1122-hx-greenstart-same-*.
- The pre-start window on Hypixel is "X is now ready!" then "Starting in 4..1 seconds"; Mort's line, the map and the gate
  opening come together at the end. runStarted() once read true at entry from the previous run's calibrated map item
  (09:04:14, before "map scanner reset"), so the autopilot started 7 s early; ClearExecutor's gate hold covered it.
- A Charged Chain burst of a map path landed its first warp off the plan on Hypixel ("off the plan: warp 1 put you N
  blocks", 52 of 75 off-plan lines on 11-21). The cause was the sneak, not the look: the burst sent the sneak in the same
  flush as its uses, so the server had not heard it when use 1 was judged. Use 1 became an Instant Transmission, and every
  landing after it slid one hop (so landing 1 looked like hop 3's look). Fix (acwaits3-1122): while a bank waits, `chainInOneTick` sets
  `active` so the sneak goes down in ticks of its own (no use), holds the hop, and fires the burst only once the sent input
  has carried the sneak for 3 ticks (`SNEAK_HEARD_TICKS`). A one-tick gate failed the sneak-lag case about 1 run in 2 on
  default Grim; holding the hop without pressing the sneak deadlocked the path (the sneak was pressed only by a pending
  hop, in `handleQueue`). The testkit's `dungeon.sneakLag` knob counts a
  sneak only once it was seen at a server tick end (case 62-argrim-1122-mapchain-sneaklag).
- A trip that went wrong from a spot was handed out again from that spot (five times in one room), because the planner
  stored the same trip again after each `TripCache.reject`. TripCache now keeps a REFUSED goal|from set (at most 256,
  cleared on world change) that `find` skips (logic case 1122-logic-tripcache-refused). Open: a fresh plan may still pick
  the same failing first hop, and each correction's replan still waits 300-800 ms behind RoomTour's room-order search on
  the planner thread (B).

## Correction replans and refused hops (acwaits4-1122)

- A correction's replan queued 300-900 ms on the one planner thread behind RoomTour's room-order search: a single graph
  search on a re-warming graph ran 300-430 ms (the 670 ms timeout), then the fine and room-by-room fallbacks, and the
  tour only yields between searches. RoomTour now searches through `EtherwarpPathfinder.tourWarps` (floor graph only,
  PRECOMPUTE mode, 25 ms budget; an unknown answer is an estimate, never stored) and plans its next trip with
  `planForCache` in the same budget, so a replan waits at most one short search (sim case 1122-sim-tour-replan, queued
  <= 40 ms). `tripSearchWaiting` reads `ClearExecutor.tripQueued` (submit to search end); reading `pathPending`, which
  stays true until the client thread takes the plan, made the tour spin 17,216 empty slices.
- A warp that never landed is a refused HOP (spot + landing block, its ray kept) in `TripCache.REFUSED_HOPS` (256 max,
  cleared on world change, dropped when `blocksChanged` touches its ray). `WarpGraph.Goal.refusedFirst` keeps it off a
  plan's first hop; `find` and `runPlanned` skip trips that start with it (logic case 1122-logic-refused-hop). The
  fine and room-by-room fallbacks do not check it yet.

## Warm-up slices (acwaits5-1122)

- A correction's replan still queued 656 ms (acwaits4 fixsim6 trial 2) and ran right after "floor graph warm": tickWarm
  checks clickWaiting only between 10 ms slices, but a slice was not 10 ms. `WarpGraph.warm` ran its last step, the
  field build (reverse edges + 72 backward BFS over ~1.9M edges), in one go, and a worker batch (8 nodes per worker)
  ran to its end. Now both stop at the slice's end (at least one node or field per worker; the rest are re-queued or
  built next call). The fields, reverse edges and `warmComplete` are published together, so a search between steps sees
  a not-warm graph, never half-built fields; a block change or `reseed` drops a pending build. tickWarm's clickWaiting
  also reads `tripQueued`. Logic case 1122-logic-warm-yield (field build 323 ms, longest trip queue 14 ms on the fix; a
  trip waits 308 ms on acwaits4 aa3dacc3e). 1122-sim-tour-replan once ran 0 of 5 trials (the sim refused the first warp
  from the spot, so the set-back moved nothing) and failed only on "fewer than 2 measured": it now sets him back only
  after the first warp landed, tries the next room when it did not, and needs all 5 trials measured.

## Last death in a long room, refused direct trips, off reasons (acrev-clear-1122, his 11-22 live clears)

- Long Hall (four tiles) was left at its last death with a starred mob unseen at the far end (5.7 s plus an uncleared room);
  the room has more mobs than the 2 spawn boxes the table holds, and a mob beyond the client's entity range says nothing by
  being absent. `moveOnAtLastDeath` now leaves only when every tile center of the room is within
  `LAST_DEATH_SIGHT` (48) of him (`roomInSight`); otherwise only the map's clear (or the roam/give-up ladder) ends the room.
  The 48 is a judgment (edge = 64 blocks), not a measured entity range. Logic case 1122-logic-acrev-lastdeath.
- The direct one-warp trip already met `refusesFirst` in `runPlanned`, after `etherDirectFast` had changed the goal state;
  `ClearExecutor.directRefused` now refuses it first, so the planner (which plans around the refused hop) takes the trip.
  Logic case 1122-logic-acrev-refused-direct.
- Dungeon Autopilot's "off:" line used to say "the secrets are done" for every Auto Secret stop; it now logs
  `AutoSecretFeature.lastStopReason()` (no key, his keys, trap stop). Logic case 1122-logic-acrev-offreason.
## Wither doors into a room the map does not show yet (ap-witherkey-1122)

- Problem (his 26.3 run, 11-22 9:00-9:12 AM): keys lost on the way to Blood. Auto Secret etherwarped to a wither door and
  clicked it ~110 ms after landing, but Hypixel puts the room he stands in on the map 392-756 ms late, so the room BEHIND
  the door was still undiscovered; a door opened into an undiscovered room makes Hypixel count it cleared at once (map
  lines `undiscovered->cleared` at five door opens, sidebar +3/+4 tiles at the click), its mobs stand and its key never
  drops. Every good open showed `unopened->discovered`. Fix: `KeyRooms.unseenBehind(door)` - Auto Secret's `tickDoor` and
  Auto Clear's `openDoor` hold the first click on a wither door while a side room reads UNDISCOVERED, at most
  `UNSEEN_WAIT_TICKS` (40), then click anyway. In the sim (no map item) `SimKeyRoom.unseen` answers: a key room armed with
  its door in counts as shown after `SEEN_TICKS` (8) server ticks in the room before it, and that door opening sooner
  insta clears it. Testkit 1122-sim-ac-witherkey-unseen / 1122-sim-ap-witherkey-unseen.
- Key race (ap-keyrace-1122, 11-22): a key room's last mob dies and its key stand reaches the client 1-2 ticks later, so a
  "nothing left" (FINISH) decision in that tick went to the blood door and left the key (Autopilot.holdFinishForKey now holds it
  at most 500 ms, ended early by `finishHoldOver` when a stand shows). A blood door wait also decides again when a BLOOD key
  appears (the at-door check compares the stand's kind with the door's kind). Testkit cases 1122-sim-ap-keyrace and 1122-sim-ap-bloodkey-late.


## Standing at an opening wither door (acrev-door-1122)

- Problem (his 26.3 runs, 11-22 morning: 22 doors at 18-23 ticks each): the time at a door is mostly Hypixel's. From his
  logger session (Skull door, client ticks): our left click t2117, "opened a WITHER door!" and every door block turned
  to BARRIER t2120 (the 3-tick round trip), the barriers turned to AIR t2134 (Hypixel's 14-tick opening, the same on the
  blood door: 7555 / 7558 / 7572). Those 17 ticks cannot be saved: barriers are solid to walking and to an etherwarp's
  ray. What was ours: `tickDoor` saw the air at t2135, set DECIDE, decided on t2136, then the trip spent t2137 on the
  sneak and the AOTV swap and sent the first use at t2138; a key still read as held could also click the door again
  while it stood as barriers. Fix: barrier on the lock block after our click = Hypixel took it: no second click, and
  `ClearExecutor.primeNextWarp()` every tick (AOTV in hand, sneak down; not under a screen or with a Secret Aura click
  due); the tick the blocks read air, `decide()` runs at once. The first warp then goes the next tick (the plan is
  searched on the planner thread, so one tick stays). Its floors are random, so the first use's time is the planner's
  (branch 2, 3 and 6 ticks after the air, origin 4; the 6 was Auto Clear's first warp queued behind a 291 ms room-order
  search): testkit 1122-sim-ap-door-go judges the door part instead, the trip decided in the tick the door reads open
  (origin: the tick after) and the sneak already heard as the blocks go, and
  1122-hx-doorprime-same-* run the prime with his own action in its ticks on GrimAC. The primed sneak is never held
  under an open screen: his container click while the server hears sneaking is GrimAC MultiActionsC (56 flags in the
  first run of the click row).
