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
- **Never gate on a distance equal to the thing measured.** A held hop was aimed again only when he stood `> 0.05` from its
  planned spot, and the settle after a landing IS 0.05: at block tops such as 69, -8 or -40 the double reads 0.0499999, so
  those hops kept the look planned 0.05 higher and landed a block off ("warp N put you 1.0 blocks from where it was aimed",
  1015-sim-clickgap on 26.2 2 runs of 3 on origin; not 26.2-only, 26.3's sim floors just sat at -18). Now `> 0.01`, Auto
  Routes' `REAIM_EPSILON` (clickgap-262-1125).
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
- The last-node wait must hold EVERY next step, not only a map trip (acleave-early-1125, killer560 11-25: "it keeps going
  out of rooms before it can finish the route grabbing all the secrets"). Autopilot's clear of the room he is in or one
  next door goes straight to `handToAutoClear`, and a running Auto Clear makes `AutoClearFeature.holdsSecretClicks()` true:
  Secret Aura stops clicking AND `takingHere` reads nothing close, so the wait ended "none close any more" a tick later
  and the clear walked him out (his Carpets, Slime, Slabs, Small Waterfall). Now the clear waits in `clearAfterWait` and
  starts from `tickLeaveWait` the tick the wait lets go. A new step that starts something which gates Secret Aura must
  check `leaveWaitRoom` the same way. Testkit 1125-sim-acleave-clearwait.
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

- Learned Room Costs (idea-roomcosts-1124): `RoomClock.end("cleared")` records the room's seconds into `RoomCosts` (shared record `room-costs.json`, last 9 per room name, median read at 3+ clears; 1 s to 300 s only; given-up and left rooms are never recorded). Readers: `estimateSeconds` before the run's first clear and Dungeons Autopilot's CLEAR candidate (its running mean is the fallback). Hop and landing code is untouched. Testkit 1124-logic-roomcosts.
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
- (apfix-1123) Giving such a route up put the room in `secreted` for the run: his 11-22 F7 Autopilot stopped at 289 with 13
  needed secrets in Atlas, Archway, Bridges and Market, whose routes all had a walk gap. Now the 10 still ticks end in a
  warp onto the waited node (`AutoRoutesFeature.warpOntoWalkNode`, Trip.WALK_NODE, fired on landing through the
  added-node path so Start From Start Node Only does not block it); finishPilot also runs goBackForMissed while
  `usefulSecrets > 0`. Testkit 1123-sim-ap-walkgap.
- (apfix-1123) A "no etherwarp path" trip failure is about the block he stands on, not the room: his Hall was tried 3x in
  2 s from one Dome block and skipped for the run, while Museum's trip from there worked. The Autopilot now leaves such
  targets out while he stands on that block (`AutoSecretFeature.noPathFromHere`, kept when nothing else is left). A failed
  trip logs `[Autopilot] trip failed: ...`, never `done`. Testkit 1123-sim-ap-nopath-hold.
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
  His movement, jump, attack or use key ends it until the next arm; a screen pauses it; the run start ends it. Testkit
  1122-sim-ac-greenstart(-keys) (switch off), 1122-hx-greenstart-same-*.
- Hypixel DOES answer an etherwarp in the green room before the start (autopilot-greenroom-warp-1126, logger sessions 11-23
  tick 68421 and 11-25 tick 207712: an AOTV sneak use there got its position packet 2 ticks later on the Entrance floor).
  So with Warp to Door Before Start on (off by default) and an etherwarp item in the hotbar GreenRoomStart warps once
  (ClearExecutor.etherDirectFast, no search) onto the first stand 1..4 blocks in from the door block that one sneaking look
  lands on, nearest the gate first; walking is the fallback. Do not route this through ClearExecutor.etherPath: before the
  start closedEntranceGate() makes EVERY plan a gate hold, so a trip inside the Entrance would wait for the gate.
  A direct hop that is not answered is re-planned by offPath (three tries, 2.6 s in hx 1126-hx-greenwarp-same-attack),
  and that re-plan is a planned path again: GreenRoomStart cancels the executor after 12 busy ticks and walks.
  Testkit SameTick at every=1 (his attack or swap EVERY tick) let no hop land at all; the cases use every=2.
  On Hypixel the gate (3 wide, 4 tall, one block in from the door block) turns into falling blocks 1 tick after Mort's line.
  The time lost at the start is NOT the standing spot: the first warp after the gate went 22-106 ticks late in his 11-26
  runs (decide + room-order search). Testkit 1126-sim-ac-greenwarp(-noether), 1126-hx-greenwarp-same-*.
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

## Room kill plan from spawn spots (ackilllogic-1122)

- A plan built from sightings came too late and planned against half a room; KillPlanner plans from the room's spawn
  boxes (BloodRushData.worldBoxes, the same spots the sim fills), off-thread, asked for where an old-path move will END
  so it is ready on landing. Sightings only take a spot (first sight inside a box inflated 1.5) or add an off-spot mob.
- "Never slower" needs the old path priced by the SAME cost model as the plan, and that model must be faithful: a greedy
  stand-in priced Museum at 65 ticks against a real 47 and let worse plans through. oldChoice copies the real order
  (cast while anything is in the blast, RepositionPlanner's cover tour re-planned after each death, the one-warp swap,
  then hop / chain cast / trip). The plan is taken only when it beats that by max(3, old/12).
- A plan taken but left at once costs a re-plan each time: k10's Golden Oasis left its plan three times 1.6-1.8 blocks
  from an etherwarp step's start (+17 ticks). An etherwarp step now accepts 3 blocks; a cast keeps 1.5, and the cast
  is checked again with nudged looks before it is sent (5 of 29 planned casts missed without the nudges).
- Ignoring the plan while a held in-place cast was on (useHeld && ATTACK) sent the following trip down the old path and
  left 59 of 65 plans (k7). The plan applies whenever it has a step; the guided bat is off while a plan stand is set.
- Room times are coupled: where one room ends changes the next room's time, so a per-room after table splits rooms
  where a plan was taken from the rest (planrooms.py); judge the plan by the first group.
- Measured (1020-sim-ac-speed, Hyperion, 6 floors / 91 rooms, second PC, deterministic base 3216 = 3216): plans 3216 ->
  3086 ticks (-4.0%), no floor slower (F1 -9, F2 -30, F3 -26.5, F4 -7, F5 -0.5, F6 -57; 6/Steps -45). The four rooms
  slower than the 4-tick base spread (2/Blue Skulls +9, 5/Banners +8, 5/Waterfall +7, 4/Overgrown +5) all ENTER from a
  different spot than base (the room before ended elsewhere, usually faster), and each nets <= +3 with that room.
  Carry-in, not a plan choice: compare entries (trip line + first decision) before blaming a room's own plan.
- Pricing must be honest on BOTH sides or neither: pricing the old hop at its measured cost (link+1, the next decision
  comes 4-5 ticks after a hop, not the link+fall 9-12 the model said) while plan casts kept link+fall, and plans waited
  to land, cut taken plans from ~36 to 16 and the total got worse (3181 vs 3086). Price plan casts the same measured
  way before changing the old hop's price.
- Grim (62-argrim-1122-same-plan-openclick): his container click while the path's etherwarp sneak is heard is
  MultiActionsC (GrimAC sets isSneaking from player_input at once). Inventory Walk sends a stand-up input just before
  his packet, and the path YIELDS its sneak through the next tick's input (ClearExecutor.menuStoodUp): re-sending it
  at once toggled the sneak every tick, two inputs in one client tick (BadPacketsZ, experimental) and one MultiActionsC
  left. Under an open container screen the path never sneaks; its warp waits for the sneak to be heard again.
  The path's hop goes at START, before handleKeybinds, so his queued attack went right after its use_item in one tick
  (PacketOrderI, experimental, 62-argrim-1122-same-plan-attack): the hop now waits a tick while his attack is queued.
  Same-tick rows for a yielding feature give his action every third tick, or the feature never gets a tick to act.
- Room order (acroomorder-1122, 1020-sim-ac-speed Hyperion, the six fixed floors, second PC): the same jar twice gave run
  totals 3408 and 3474 (room clocks swing with the entry side), so a run-tick delta under ~70 is noise; the entry-leg sum
  (first trip of each room, legs.py) repeated within 6 (758/764) and is the order metric to trust. A least-tile tour that
  keeps the off-path-first rule did not beat the old order (legs 739-770, run 3442-3454); the same tour without the rule
  gave legs 655/646 and run 3304 twice with no floor slower. He answered 2026-11-22 (Blood Rush Split off "should be able to clear whatever is fastest"): Full Clear now uses that tour with nothing forced first; Split ON keeps its rush order. The ONE_TOUR switch is gone. Testkit 131 case 6 asserts the tour costs no more than the plain order.
- Pricing the plan's casts at link+1 like the old hop (ackillprice-1122, with k14-pricing.patch) made the old order slower
  (room ticks 3173 vs 3048/3119, run 3544): not shipped; patch kept at C:\kmtest\jars\acroomorder-1122\pricing-P.patch.

## Quiz countdowns and Ice Fill First under the Autopilot (ap-quizfill-1122, his 11-22 F7 runs)

- A Quiz gap took only a route whose round trip plus the 20-tick spare fit; with none (and no clear or door) it went straight back and stood out
  the countdown: 83 and 73 ticks for answers 2 and 3 while Steps (79 of 94 left) and Duncan (95 of 86) were "(too long)". Now `quizGapPick`
  takes, after the clear and door options, the longest route that keeps `QUIZ_GAP_LOOSE_SPARE_TICKS` (10) in hand; never one
  over the countdown (an exact 95-of-95 fit came back 22 ticks late in 1017-sim-quiz-autosecret: the trip estimate runs short). Testkit
  1122-logic-quizgap-pick holds his numbers.
- `decidePilot` never called `decideIceFill`: Ice Fill First only ran inside the Quiz's first cooldown, so a floor with no Quiz, or an Ice
  Fill found later, waited for its turn in the tour (found 20:09:01, done 83 s later). It now runs right after the Quiz step, gated on Do
  Puzzles. Testkit 1122-sim-icefill-first-pilot.
- A Quiz-gap route whose way runs through an unentered key room is not timed by its round trip: `goSecret` clears the key room first
  (1121-sim-acquiz-return, 11-22: Purple Flags 93 of 138, Mossy cleared first, question 2 answered 73 ticks late). `quizGapRoom` now
  leaves such rooms out (`keyRoomFirst`, shared with goSecret); the key room can still come as a timed gap clear.
- Autopilot clear estimate (ap-clearest-1122, his 11-22 F7 runs): Auto Clear room times of 2-4x "estimated" (Stairs 6.3 s vs 2.5,
  Chambers 7.5 vs 4.0, Crypt 7.1 vs 3.1) were not corrections or waits: the `room time:` lines show the time follows the starred
  mob count (Chambers 10 kills 148 ticks, Wizard 9 kills 124, Crypt 13 kills 124; a room with nothing left 1 s), and the 77 clears
  split into about 1 s and 6-11 s. Corrections happened but Stairs had 0. The estimate was a rate-0.4 average of the last two or
  three rooms, so it swung 0.5..6.5 s; it is now a running mean (`Autopilot.learnClear`, weight 1/(n+2), floor 0.12). Testkit
  1123-logic-apclearest. Not fixed, kill logic (ackilllogic-1122): Stairs spent 60 of 122 ticks turning; Steps 214 ticks (87 roam
  trips, 40 looking); King Midas twice 128/63 ticks firing 34/18 uses for 0-1 kills; Mirror and Beams gave up "no starred mob is
  visible" after 9 roam trips.
- Chasing mobs and the first-seen home room (acreview-1123, his 11-23 F7 runs): `homeRooms` pinned a starred mob to the room
  Auto Clear FIRST saw it in, and mobs chase the player. Drop's Zombie Soldier and Super Tank Zombie followed him into
  Sarcophagus, already cleared; Auto Clear first saw them there, so Drop's clear roamed three times (84/63/76 ticks, one give-up,
  he pressed W twice) with both 1.4-1.5 blocks from him and Drop's mob list empty. Sloth's tank was first seen from the Quiz and
  pinned to a puzzle room. Only an uncleared mob room (`homeable`: NORMAL/RARE/CHAMPION, not cleared on the map) is a home now;
  otherwise the mob counts where it stands until it is seen in one, and a home that turns cleared is dropped. Testkit
  1123-sim-ac-chaser. Read the logger's acruns K lines (`m=` is the room's starred list) against the session recorder's
  `world entities` positions to find a mob next to him that the room did not list.
- A mob that wandered out before the clear began (wandered-mob-1126, his 11-26 4:00 PM F7): Leaves' Speedy Zombie Knight walked
  through the Quiz door while Auto Secret ran (no Auto Clear, so nothing homed it) and stood at -103,71,-182, two blocks past
  Leaves' edge in the Quiz, from 15:59:05 to the give-up at 15:59:23 (logger `world entities`); the run first saw it in the Quiz, a
  puzzle, so it counted there and Leaves' `m=` stayed empty. Now `adoptStrays`: with none of the room's mobs in sight, a seen mob
  within 16 blocks of one of the room's open doors in a neighbor that is cleared on the map or no mob room, with no home in another
  uncleared room and no other uncleared mob room's door into that neighbor nearer it, is homed to the room; an uncleared
  neighbor's own mobs are never taken. Door-side roam spots (4 inside each open door) come on top of the 4 quadrant spots, and the
  give-up line ends "a mob may have left the room". Testkit 1126-sim-ac-wandered (cleared / known / guard).
- Back at the Quiz early (same runs, 5 of 12 countdowns with 68-111 ticks left, 2.4-4.1 s standing): every one was "none fits"
  - no secret route fitted there + route + back, and a cut-short mob clear needs `QUIZ_GAP_PART_MIN_LEFT` (100) ticks left. At
  13:21:21 (68 left) Small Stairs next door cost 22 there + 18 back + the 20-tick spare, 8 ticks of work; at 12:59:55 (90 left)
  Prison Cell's route came to 100. The log does not say which mob rooms were weighed (quizGapMobClear logs nothing when it picks
  none), so whether a cut-short clear would have paid is unproven; filling such gaps means a shorter spare or arriving late,
  his call. Not changed.

- Quiz gap finishes what it started (acquizlate-1123, killer560 11-23 4:14 PM: "I would rather it get back a second late than a second early ... no time limit on how late it gets back just as soon as it finishes what it set out to do"). The acplan2-1121 cut (`quizGapCutDue`, cancel Auto Clear at countdown <= trip back + 20) is gone: it was why 5 of 12 returns in his 11-23 log were early. A gap clear or route runs to its end, then `decideQuiz` goes back at once (left <= 0 starts nothing new). Starting is unchanged (fits with the 20-tick spare, or up to 100 late, `QUIZ_GAP_PART_MIN_LEFT`). Side effect seen in sim: a far room (Dragon, 6 rooms away) cleared to its end can leave no etherwarp path back ("Room hop failed ... no warp chain") and Auto Secret stopped "nothing left it can reach" (1121-sim-acquiz-return-party, one floor of two); the case now fails on any "cutting the clear of" line.

- Stranded on a block with no warp chain out (acquizlate2-1123, found by acquizlate-1123's 1121-sim-acquiz-return-party on 26.3 and 26.2): a Quiz-gap clear in the Dragon room ended on -63,-22,-175, where the planner finds no chain to any door ("Room hop 1 of 6 failed"); every trip failed from that block, the rooms were dropped one by one and Autopilot stopped "nothing left it can reach" with 0 of 3 answers. Auto Clear itself got in by single hops (`tripTo`), which the planner does not reverse. Fix: `AutoClearFeature` keeps the last 8 blocks its trips left from (`noteStand`); on a no-path trip failure Auto Secret warps back one hop to the newest of them (onto the block UNDER the stand: the stands are feet blocks, and the first version aimed at the feet block itself, which the planner refuses as "not solid", so it could never act), then plans again; one recovery per block, so a failed one ends as before. `ClearExecutor.sendHop` notes each hop's origin, so a chain's middle landings are there too. FOUND 11-23 (acquizlate2-1123, probes in the pinned case): the Dragon pocket -63,-22,-175 cannot be left at all in the sim: no ShortWalk plan (stone-brick stair under barrier blocks on one side, solid walls, ceiling 12 up; a slit one block high in the south wall is all that joins it), and no warp chain even back to the hop origin -59,-23,-182 that put him there (the entry ray skims the slit low, the reverse ray from the 1.54 eye cannot). Nothing on foot or by warp helps there; only not landing there would. Before the cut was removed the cancel usually fired earlier, with him still outside such a pocket, but origin had no recovery either: a clear that ends on such a block (or a hand-placed player there) stopped it the same way (pre-existing, only more likely without the cut). Testkit: 1123-sim-acquiz-party-pinned replays the failing floor by map code and puts him on that block after answer 1 (the sim's timing differs run to run, so the floor alone does not repeat it); it fails on the acquizlate-1123 jar and still fails with the recovery (sealed pocket, see above). (Since acquizlate3-1123 it no longer puts him there by hand: it fails if the clear itself ends in the pocket.)
- Never land where no way leads back out (acquizlate3-1123): the 11-23 hop into the Dragon pocket was not a miss: two bats from -70,-24,-181 hit the cobblestone wall, the cover plan then picked -63,-23,-175 as the block reaching that Zombie and a 3-warp trip landed exactly on it. The Dragon capture holds 551 barrier blocks recorded from Hypixel, so the pocket is real. `AutoClearFeature.noExit` judges a block once per room (bounded 256, plan time only): a direct warp, a `ShortWalk.walkExists` walk or an `EtherwarpPathfinder.chainExists` chain (25 ms; out of time counts as a way out) back to the room's ANCHOR (the block of its first check), else the block goes in `noExitStands` and the cover plan, kill plan, sceptre stands, roam spots and Hyperion hops leave it out. Two holes found by the first fixed jar: judging the way back to where he stands NOW passed a block inside the pocket (a walk back to the pocket's corridor, which he was already in), and a roam-spot trip whose block "cannot be reached" was planned NEAR it and landed in the corridor, which only the asked block had been judged for. Hence the anchor and `ClearExecutor.etherPathFast(to, complete, nearVeto)`: a plan ending near its block is refused (the trip fails as no path) when its real stand fails the veto. Testkit 1123-sim-ac-pocket (Hyperion only, hops off, a starred zombie at -62,-22,-173 inside the pocket); the setups with bats reached the mob from outside and proved nothing.
- A sliced way-out check must hold the trip until it answers (acquiz-two-fails-1127): since acpocket-perf-1124 the chain search runs 2 ms a tick and a block counted as having a way out until its verdict, so under main-PC load (first slice unanswered) the mob trip onto -61,-23,-176 left at once and landed in the Dragon pocket 0.5 s in, while an idle PC answered in one slice and passed (same jar, 3 of 4 lane runs failed, 11-27). `AutoClearFeature.exitPending` now holds mob trips, roam spots and roam-to-known trips (Hyperion hops already were) and moves that block's search to the front; a verdict that lands while held replans as "found just now". The case forces 0.2 ms slices (`exitSliceNsForTest`) and asserts "waits for its way-out check", so it no longer passes by PC speed.
- A held trip must never wait for slices of a search that will run out of time (ptam-wayout-budget, his 11-27 Mage clear on the acquiz-two-fails-1127 preview): Mage's chain searches back to the room's anchor always used their whole 25 ms ("unknown (search out of time)", 12 slices), so each held trip stood 12 ticks for a verdict that let it go anyway, and searches queued one after another (47+29+12 idle ticks at 10:45:21). The search now runs its 25 ms on the `killer560smod-wayout` pool (2 daemon threads, own grid, like the planner's) and the verdict is read the next tick, or at once by `exitPending`; the log line says "answered N tick(s) after it was asked". Hook `exitDelayMsForTest` holds each search back so 1123-sim-ac-pocket still exercises the wait; testkit 1127-sim-ac-wayout-nowait (full speed, longest trip wait <= 2 ticks) fails on the sliced jar.
- The 96-tick stand before answer 3 (1121-sim-acquiz-return, 11-23) was not an early return: a route trip failed ("could not warp to its start node") and only a 116-tick round trip was left against 97 ticks, marked "(too long)". The case no longer bounds idle ticks; it fails only when a round trip NOT marked too long is passed over for "none fits, waiting at the Quiz". Mob-room clears that quizGapMobClear weighs are not logged, so they are not judged; whether to start a slightly-too-long task (owner: late over early) is still his call.

## A name tag that trails its mob (dropaudit-acstarred, Small Stairs 13:21 on 11-23)
- `MobEspFeature.resolveMob` tied a starred tag to its mob by id - 1, else by a box one block under the tag. A tag that is a NEW stand
  (no body at its id - 1) next to a fast mob (Speedy Skeleton Soldier, tag 1+ blocks off) matched nothing, so Auto Clear's mob list
  dropped to 0 while the starred mob stood beside him and it gave up "no starred mob is visible". Now a second, wider search (2 blocks
  sideways, 3 below) takes the nearest mob no other tag has claimed (`nearestFreeMob`). The real tag/body ids were not logged that day
  (the logger's TG lines came later), so the sim case 1123-sim-ac-taglag swaps a zombie's tag for a later-id stand 1.8 blocks aside; it
  gives up on origin and kills the zombie on the fix.
- That wider search also stole neighbors (highlight-random-mobs-1125, killer560 11-25: the highlight marks "random mobs"): a tag
  whose mapped mob was gone was matched AGAIN, so a plain mob within 2 blocks of a starred one that died under a lingering tag became
  starred, highlighted and kept in KNOWN_STARRED (and Auto Clear's list) until it died. Now a tag keeps its first mob for good (gone
  = no mob), and the search skips a mob whose id + 1 stand is a plain heart tag: his logger census (11-24/25, about 1,300 starred
  tags) put every starred tag at its mob's id + 1 (Withermancer + 3), so that stand is the mob's own unstarred Hypixel tag. Testkit
  1125-esp-starred-death-neighbor and -plain-tag-neighbor fail on origin. Whether a real Hypixel tag outlives its mob is not yet
  seen: the logger's `hl orphantag` line records it.
- Tombstone 13:02 (give-up with m=0 and the room not green) is NOT this: its starred mobs were dead and three unstarred "[Lv173] Undead
  Skeleton 360k" stands were in the room; whether those keep the room open is unproven (needs the TG/GS logger lines of a real visit).
- A "wrong Quiz answer" at 13:00:06 was his own click: Hypixel changed "How many Fairy Souls are there in Hub?" (options 83/81/85, ours
  says 80), so Auto Quiz pressed nothing. quiz-answers.json waits for the real number (83 or 81).

## Kill to first warp (acoptimal-next-1124, 2026-11-25)
- AutoClearFeature.tick runs BEFORE ClearExecutor.onTickStart in the same START (both default phase, Auto Clear registered
  first), so a trip Auto Clear starts is already seen by that tick's onTickStart; no extra "send now" hook is needed.
- Three things held a one-warp trip's warp a tick after the kill was seen: (1) the sneak was pressed only once the trip
  existed; (2) runPlanned's Hyperion->AOTV swap made `hasSwappedThisTick` true and onTickStart's sendNow slot refused a use on a
  swap tick (now allowed when the swap went out at once inside the tick, `swappedInTickOn`: slot then use, vanilla's order);
  (3) a sneak sent one tick before is "fresh" (ZeroPingEtherwarp.sneakFresh) and doInteract waits a tick unless one look lands
  from both eyes. Fix: primeWarpAfterCast holds the sneak from the in-place cast's own tick (its use leaves in handleKeybinds,
  ahead of that tick's input packet, so it still goes unsneaked). Testkit 1124-sim-ac-presneak: gap 0 trips 0 -> 11 of 16
  rooms, room ticks 510 -> 465. Diagnose such holds by logging `waitReason` right after onTickStart's doInteract.

## Held hops, edge looks and the menu unsneak (acclick3-1125, 2026-11-25)
- A hop held behind a block click goes from the SETTLED eye (+1.0), but graph edges are aimed from the +1.05 landing. An edge
  that clears leaves or grass by about 0.05 drops onto them once he settles, and replanning took the same edge again (stored
  trips too) more than 300 times. Fix: when the held re-aim finds no ray, `TripCache.dropSettledMiss` bans that stand->landing
  edge in every plan for the floor (`WarpGraph.SETTLED_MISSES`, capped at 64) and drops the stored trips that use it. This is
  geometric, so it happens on Hypixel at any height (1015-sim-clickgap).
- The mod's `look()` uses Math.sin, while the server (and 1.8 Hypixel) uses a sine table. They differ by about 1e-4 rad, and on a
  look through a block's exact edge or corner that decides whether the ray hits. `RightClick.serverRay` traces every look model,
  and a use that would pair with an unpaired click (`unpairable`) is nudged off the edge first (`RightClick.nudgeIfUnpairable`,
  used by Auto Clear's bat/aim-and-hold use).
- A menu click (setScreen releaseAll, Inventory Walk's menuStoodTick) sends player_input shift=false in that tick. An
  etherwarp use behind it is then a transmission: a real Instant Transmission on Hypixel, and a silent no-op at y 69 pitch down
  in the testkit ("never landed", 62-argrim-1122-same-plan-openclick). ClearExecutor.doInteract waits until the server has
  heard the sneak again.

## Searched trips a tick late (acoptimal-next2-1125, 2026-11-25)
- A trip whose path is SEARCHED (ClearExecutor.plan, the planner thread) handed its result back through `client.execute`, which
  runs only after the tick that started it has ended, and startQueue then left `sendNow` false: every multi-warp trip after a
  kill left a tick late even when the search took 1 ms (137 of 251 kill-to-warp gaps on 1020-sim-ac-speed's 6 floors; all
  gap-0 warps were "no search, direct" trips). Fix: plan keeps the Future and a run-once hand-back; onTickStart (after Auto
  Clear's START) waits up to SEARCH_WAIT_NANOS (4 ms) for it, runs the hand-back there and sends the queue as a cached trip
  does (sendFromThisTick). A slower search falls back to the old hand-back. Testkit 1125-sim-ac-searchtick.
- The remaining late searched trips mostly waited in the planner's QUEUE (5-10 ms behind a warm-up or TripCache slice), not
  in the search itself; the classify/show scripts in C:/kmtest/jars/acoptimal-next2-1125 split gaps by source.

## His menu click while Auto Clear walks (acoptimal-next2-1125b, 2026-11-25)
- After failed etherwarp trips Auto Clear walks (ShortWalk, sprinting), and his container click then went right behind the
  tick's sprint input: GrimAC MultiActionsC (lane1125-0321 on trip-openclick; origin draws it too once it walks). A raw
  STOP_SPRINTING after the tick's movement packet is GrimAC Post, so InventoryWalkFeature.holdForWalk holds his menu packets one
  tick: the walk yields that tick's keys and sprint (ShortWalk.YIELD_KEYLESS), vanilla tells the server before its movement
  packet, and the packets go at the next tick's start. Testkit 62-argrim-1125-walk-openclick (every trip priced out).
- The sneak stand-up's raw input after a tick's end was followed by a short walk's first input in the next tick: two inputs in
  one GrimAC tick (BadPacketsZ, experimental). standUpForMenu now has the walk repeat the input just sent (YIELD_REPEAT).

## Searched trips queued behind the warm-up (acoptimal-next3-1125, 2026-11-25)
- The full floor graph warms for 12-20 s of every sim floor (19,000 landings), in 10 ms slices on the planner thread that
  checked "a click is waiting" only between slices, so a searched trip queued 5-15 ms behind one (37 of 213 on 1020-sim-ac-speed's
  6 floors) and missed the 4 ms in-tick wait. Fix: `WarpGraph.warm(..., yieldTo)` (only the background tickWarm passes it; a
  click's own finishWarm never yields) also stops after the node in hand per worker and after the field in hand. Queued >= 5 ms
  45/223 and 39/208 -> 3/211 and 4/210, plan wait 63/57 -> 19/20 ticks, room ticks 2719/2676 -> 2604/2601 (-3.5%). Testkit 1125-sim-ac-warmyield reads the "queued X ms" of every [Path] running line.

## Sneak primed while any mob is left (acoptimal-next4-1125, 2026-11-25)
- After acoptimal-next3, 59 of the 67 one-tick-late warps on 1020-sim-ac-speed's 6 floors followed an in-place Wither Impact
  whose primeWarpAfterCast DECLINED: a mob the blast missed stood within a cast across (16 blocks), so the sneak went down only
  on the kill's tick and the warp waited at ClearNode.doTeleport (`getLastSentInput().shift()`) for it to be heard. Fix: prime
  whenever any unhit mob is left. ClearExecutor.primeSneak now holds only the input of the tick it is asked on (castPrimedTick;
  primeNextWarp's END-handler prime still lasts through the next tick), so on a kill tick that leads to a cast across, a hop or
  a walk nothing asks again and that input goes unsneaked.
- The hop then turned twice: on the kill tick the client still crouches (the pose follows the last input), and
  WeaponReach.hyperionHop aimed from `player.getEyeHeight()`, ~1.3 degrees off the standing look it got the next tick (12 hops
  2 ticks late instead of 1). Hyperion casts and hops are aimed from WeaponReach.STANDING_EYE (KillPlanner's aimed looks too).
  Classify warp gaps with C:/kmtest/jars/acoptimal-next4-1125/g1.py-g3.py (g3 reads a probe jar's prime verdicts).
- His right click in the tick Auto Clear TURNS for a cast (aimAndHold) went out in that tick's handleKeybinds with the new,
  unheard look: his own Wither Impact flew the cast's way and killed its mob first, so 62-argrim-1122-same-plan-use saw plan
  casts 0 whenever that mob was the plan's only cast (2 of 3 on the merged jar; the same ride shows on every jar before it,
  only by chance on a mob the plan needed). aimAndHold now puts the turn off a tick while hisUseClicksQueued > 0 (never two
  ticks running); the row asserts no Hyperion use goes on a look the server had not heard. Path warps' AOTV clicks still ride
  the path's look (his etherwarp lands on the planned block; Grim clean).

## Moved off the kill plan by the server (acoptimal-next4-1125-fix2, 2026-11-25)
- His own Hyperion click (or any correction) mid-room teleports him off the plan's next step; the plan was then left and a new one
  had to beat the old choice by the full margin again, so the plan often stopped acting (62-argrim-1122-same-plan-use 2 of 5 on
  default Grim, "left the kill plan at step 4 ... plan casts 0"). Now `leavePlan` checks `corrections` against the count when the
  plan was taken: a move the server made gets a plan from where he stands with no margin (`KillPlanner.Job.keep`, best = old + 1),
  and the old choice sends nothing for up to 2 ticks while it comes (`waitingForKeptPlan`), at most 3 times a room. A move during a
  plan etherwarp is the trip's own to plan again (Path), so it never reaches this. Testkit 62-argrim-1125-plan-moved shoves him on
  the plan etherwarp's landing.
- 62-argrim-1117-achype's "no Wither Impact across" (11-25) was the row, not the mod: since the kill plan (3453a2b86) takes that
  layout, its cast across logs "Wither Impact (room plan) ... 2 mob(s) in its blast" instead of "Wither Impact across"; the row
  counts both. No lane had run it since 11-19.
- 62-argrim-1122-planprobe run AFTER the plan rows in one session draws one Simulation (.089) after a 2-warp path landing on the
  merged base without this fix too (m0 exp-rows); alone it is clean (m0 and fix, def). Fixed in acnohype-1125, see below.
- (acnohype-1125) A Hyperion that vanished from the hotbar mid-clear (same-plan-use, default Grim) was GrimAC 2.3.74's answer to
  a block click it refused while a teleport was pending: CheckManagerListener resyncs the held slot from lastSlotSelected plus
  the item in hand read off the server thread, so a swap-and-turn in the same tick as his queued right click, with an etherwarp
  landing in flight, wrote the AOTV into the Hyperion's slot. Fix: aimForLanding does nothing while hisUseClicksQueued > 0, and
  aimAndHold waits up to WEAPON_WAIT_TICKS (10) for a missing weapon before it stops. Testkit 62-argrim-1125-hype-resync puts a
  stick (not an AOTV: the server still holds the Hyperion, so an AOTV picked for etherwarps casts Wither Impact server-side)
  in the slot until the mod reaches for the Hyperion; an origin jar stops "no Hyperion in the hotbar".
- (acnohype-1125) The planprobe Simulation 0.089 after walk-openclick was a walk whose first keys and turn went out in the very
  tick the client answered its own Wither Impact's teleport (accept_tp, then a sprint start to a new yaw); the later accept_tps
  were Grim's setbacks. ShortWalk now holds a walk's first keys for the tick a server teleport is answered (hook in
  LiveMapPacketListenerMixin, counter teleportHolds). Origin flagged 2 of 2 (def, exp); fixed clean 4 of 4 with 3-5 holds.
- **A refused plan says why, once** (la-warpmiss-1126, his 2026-11-25 15:34-15:40: five bare "Failed after Nms"): `EtherwarpPathfinder.lastRefusal` is set at each refusal (nothing to stand on, no warp chain from here, every door locked, block unreachable) and `ClearExecutor` puts it in the chat line and `lastFailReason()`; while Auto Secret runs the line is only logged and Auto Secret's own "Couldn't get to" carries the reason. A start-node or walk-node go-to with no path is a FAILED trip (recovery warp, retry count), never "arrived" because he is in the node's room: it used to wait 40 ticks and say "did not arm". Testkit 1126-im-failreason.

## The entrance gate's barrier window, overhung doorways and the Hyperion's own drop (autopilot-clears-1126, his 11-26 clears)

- Hypixel's gate does not go straight from bricks to air: the infested bricks turn to BARRIER one tick after Mort's
  line and to air 15-16 ticks later (every run of logger session 20261125-232944-36672). With the map in hand
  `ClearExecutor.closedEntranceGate` saw no bricks and called the gate open, so trips planned in that window met the
  barriers ("Room hop 2 of 5 failed") and the first warp went 20-90 ticks late, and the key room walk failed its 4
  tries in 4 ticks and gave the room up. Barriers in the doorway now count as the gate (`entranceGateShut()`), and the
  key room walk waits on a shut gate instead of spending a try. Testkit 1126-sim-ac-gatebarrier.
- An Entrance whose roof overhangs its doorway (roof 98 over the gap, 01:08 and 01:18) read as a connector in the world
  scan, so before the map the Entrance had no door and Dungeons Autopilot saw nothing to reach. The gate's bricks at
  y 69 make the gap a door whatever the roof (`LiveMapFeature.doorGap`); a door next to the ENTRANCE room counts as
  the entrance door even when the map types it as normal. Testkit 1126-logic-acclears-doorgap.
- An etherwarp onto a carpet lands 1.05 above its block; a Hyperion cast in place (pitch 90) while falling is moved to
  the floor by Wither Impact itself, 2 ticks after the use. A position packet within 6 ticks of Auto Clear's own use
  press and on its look is the cast's teleport, not a correction (`castOwnTeleport`). Testkit
  1126-logic-acclears-castteleport.
- Not pinned: a first warp judged 5-8 blocks off (01:01:29, 01:07:15). Once, uses 1 and 2 of a one-per-tick chain got
  no answer and use 3 was judged from the start; once a stray use with the server's old look went out the tick Secret
  Aura opened a chest. The logger's `req by=` named only the `@Invoker` frame; it now names the mod caller (logger
  c11678c), so the next such clear shows who sent it.

- A door's stand is never across the door (entrance-noroute-1126): Door Only's trip to Blood's door 41 on a sim F7 had
  its approach (-57,253,-139) in a chunk the client lacked while Blood's side was loaded, so nearestEtherwarpable took
  -57,253,-128 INSIDE Blood; the planner found no room route there ("No ROOM route from room 16 to room 17", every
  tick) and he never left the Entrance. Not the entrance gate change of c6d2fd790 (doorStand predates it). Fix: doorStand
  and both nearestEtherwarpable searches keep only the approach's side of the lock block (TeleportUtils.sameSide); the
  approach chunk missing on the client then falls to the planner's view, which finds the approach. Testkit
  1126-sim-ac-entrance-exit (4 floors; floor 2 is the one).

## A trip's item swap ahead of his own right click (greenroom-fix-1126, 2026-11-26)
ClearExecutor swapped to the warp item at START (a trip started from a START handler, `sendFromThisTick` / `swapById`) while
his right click sat queued for that tick's `handleKeybinds`; `doInteract` then held the hop for his click, but the slot
was already the AOTV, so his stick click went out as a 12-block Instant Transmission (1126-hx-greenwarp-same-use, about half
the runs). `swapById` now holds while `AutoClearFeature.hisUseClicksQueued` is above 0; `doInteract`'s own item check swaps
the next tick. Test his click with `SameTick.Act.USE_CLICK` (queued, visible at START like every real click), not `Act.USE`
(a bare use_item sent after every START handler, which no START actor can see and no player sends).
That hold alone made it worse (greenroom-fix2-1126, read off the wire with the case's WIRE dump): `ClearNode.doTeleport`
took swapById's `false` for "no item" and cancelled the trip, so every leg asked in a tick his click was queued ended with
no swap, no use and no word (11 of 12 legs "warp missed", then a walk). A held swap now sets `swapHeldThisTick()` and the
node waits; only a missing item cancels. And with the AOTV already in hand while a hop waits (sneak, turn), his click still
rode it (wire: t194 carried 2, t195 HIS use_item with the diamond shovel, t196 the hop): `doInteract`'s his-click hold now
gives his slot back first (`handBefore`, the slot before the queue's first trip-item swap; -1 when he held it himself), so
vanilla sends slot then his use, and the hop's own swap goes with the hop next tick. 1126-hx-greenwarp-same-use 6/6 on
default and experimental Grim after; it now also fails on any use with the AOTV in hand on his level look.
The gate hold had the same fault for its whole length (gatehold-aotv-click-1126): `tickGate` primed the held first warp as
Auto Secret's hold does (`primeFirstWarp`: AOTV swapped in, body turned), so every right click of his while the gate was
shut went as an AOTV use (1126-hx-firsttrip-gate-same-use, experimental Grim: 39 uses; origin's tickGate and primeFirstWarp
are the same code, so it predates the prerun work). The gate hold now keeps only the sneak (`primeFirstWarp(.., inHand=false)`); the
swap goes in the release tick's START from `ClearNode.doTeleport` (`swappedInTickOn`) and the turn and use follow it in the
same START (`doInteract`), so no tick is added. Only when a block click is unpaired at release does the turn cost the one
tick every hop pays then (`RightClick.mustTurnFirst`). Auto Secret's last-node hold (`tickHold`) still primes in hand.

## The green room spawn ledge hides the gate floor (greenroom-fix-1126, 2026-11-26)
In the sim Entrance he spawns on a ledge 3 blocks up, behind a stone brick slab at his feet level, and that slab is the first block
on every straight line from his eye to the floor by the gate, so a one-warp look onto any stand there does not exist. GreenRoomStart
then plans warps (`ClearExecutor.etherPathArriveInRoom`). That trip stays in the green room, so it must not get the shut-gate hold
every pre-start plan gets (`closedEntranceGate`); with the hold it waited for the start. Once the warp is out, GreenRoomStart gives
his hotbar slot back (never behind a use in one server tick), or his next right clicks ride the AOTV as Instant Transmissions.
## Settling onto a slab, and the turn before the deaths (acoptimal-1126, 2026-11-26)
- Every kill-to-warp gap of 3-4 ticks on 1020-sim-ac-speed's 6 floors (25 of ~270) was a FALL: an etherwarp lands 1.05 above
  its block, so onto a slab or carpet he still drops 0.5-1 block (3-4 ticks), and a Wither Impact in place there leaves him
  in the air too. AutoClearFeature.attack waited for `onGround` before any move. A TRIP now goes while he is coming down onto
  a block within `SETTLE_FALL` (1.25) below and not rising (`settlingOnto`): ClearExecutor.doInteract already aims a queue's
  first warp again from `RightClick.heardFeet`, and Hypixel casts airborne etherwarps. A hop or chain cast is aimed where it
  is chosen and turns a tick first, so those still wait to stand. Counter `settlingTrips`; testkit 1126-sim-ac-settletrip.
- A room plan's cast across after its cast in place turned on the deaths' tick and cast the tick after (24-27 a run): the
  deaths come back 2 ticks after the use (338 of 382 casts) and the use repeats only every FAST_REPEAT_TICKS (2), so the tick
  between carries nothing. `preTurnNextCast` turns there to the next CAST step from the same spot (no sneak prime that tick),
  and the cast goes on the deaths' own tick. Counter `preTurns`; testkit 1126-sim-ac-preturn.
- Diagnose kill-to-use gaps with C:/kmtest/jars/acoptimal-1126/k1.py (gap by kind: AOTV, planned cast, hop) and show.py (the
  lines between a death and the use, each with its tick offset; one `[digflush]` line is one tick).
- Trip retry limit (ac-retry-limit-1126, 2026-11-26): Door Only has no room, so giveUpRoom after MAX_PATH_FAILURES (4) did nothing and a trip that could not route was asked again every tick ("failed (90 of 4)", one "No ROOM route" line each, ~7 a second). Now giveUpTrip: with no room it records blockedDoor and the room he stands in, says why once and leaves that door until he is in another room or its lock changes (nothing else of Auto Clear stops); "No ROOM route" logs once per (rooms, locked doors). Testkit 1126-sim-ac-trip-retry-limit walls him in with stone (no warp can start) and counts 4 failed tries, one give-up line, still running.

## The first trip on the start's own tick (autopilot-prerun-1126, 2026-11-26)

- On the tick the run starts the held map is in hand while the gate's infested bricks still stand (the barriers come a tick
  later). closedEntranceGate ignored bricks once the map was in hand, so the first trip was planned in the shut world, found
  nothing ("fewest-warps graph found nothing ... Room hop 2 of 3 failed") and was searched again: first warp 11 ticks after
  the gate was air. Bricks in the doorway itself now count as the gate; the trip plans through it in 1-50 ms inside the
  15-tick barrier window and goes 1 tick after the client sees air (the harness floor, testkit 1126-sim-ac-firsttrip-prerun).
- Planning it before the start (TripCache entries through the gate) was built and dropped: the Autopilot's first goal is
  Auto Clear's stand block, which TripCache does not plan, and the wants that failed through the gate were searched ~2,300
  times in 100 ticks. Measure what the start's own plan costs before adding pre-start work.
- 1126-sim-ac-gatebarrier flaked on 26.2 from three test races, not the mod: the map's current room trails a fast chain, a
  slow search could run past the 15 barrier ticks, and a room's route start can be its doorway column (New Trap).

## Tiles seen during the visit, the hop before the deaths, and hop counts from the landing (acoptimal-1126b, 2026-11-26)
- The "looking" ticks after a room's last death (88 a run on 1020-sim-ac-speed's 6 floors, main PC) were nearly all one rule:
  `moveOnAtLastDeath` left only when EVERY tile center of the room was within LAST_DEATH_SIGHT (48) of him at the death, so in
  2x2 and long rooms (Mossy, Wizard, Waterfall, Crypt, Flags) he stood until the map's checkmark. A mob in a tile that came
  within sight at any tick of the visit was loaded then and is remembered (`seenMobs`, "lost" while out of view), so the room
  now ends when every tile has been in sight during the visit (`roomSightMask`, counter `sightSeenLeaves`). Each refusal to
  leave after a death now logs "not left at a death yet - <why>" once per reason, so the looking ticks name their cause.
  Testkit 1126-sim-ac-sightleave (after-last-death on its floor 7 -> 1 tick, no room gone back into).
- An old-choice Hyperion hop after a cast in place turned on the deaths' tick and went the tick after (13 a run).
  `preTurnForHop` turns, the tick between the cast and its deaths, to the one-hop hop toward the mob `pickTarget` takes once the
  blast's mobs are gone; `preTurnedHop` keeps that look on the deaths' tick while it still lands the mob in the blast. A cast
  on an etherwarp landing goes 0.05 above the block (not on the ground yet): the hop is worked out from the block top
  (`settledFeet`), or the turn never happened (first try: 0 turns). Counters `preHopTurns`/`preHopUses`; testkit 1126-sim-ac-prehop.
- Coming down onto a slab after a kill, `cachedHops` was counted from the AIR: the dash from there lands elsewhere, so a hop
  was chosen, the hop waited 3 ticks to stand, and on landing the count from the block was another and the trip went then
  (the 3-tick AOTV gaps, 5 a run). The count now comes from `settledFeet`; counter `settledHopCounts`; testkit 1126-sim-ac-settlehop.
- Speed runs on the main PC when the second PC is unreachable: baseline and after on the SAME PC, interleaved (base, fix,
  base, fix). A testkit class that throws on its first case skips the rest, so each origin-must-fail case runs alone.
## Going back for missed secrets, and the green room walk off its path (acrun-1126-noon, his F7 11-26)
- At "nothing left it can reach" `finishPilot` asks `goBackForMissed` BEFORE the wither door. On his run it sent him back to
  New Trap (its Just Cleared route had run; 2 secrets "missed", 9.5 s) and then to Ritual, whose route had stopped for a
  BAT_WAND he does not carry and stopped the same way again (3.5 s), before the door to 6 rooms. `worthGoingBack` now
  refuses a TRAP room always and a room whose route stopped "X is not in the hotbar" while X is still not there
  (`missingItem`, cleared at each start). Testkit 1126-sim-ap-noback-trap calls goBackForMissed with one room at a time.
- Autopilot logged "done SECRET New Trap" at 11:35:27 after the key-room detour without ever visiting the trap: the pick
  stays a candidate (it was visited at 11:36:31), only the log line misleads.
- GreenRoomStart's walk met a block 4 blocks in (logger hcoll at z -181.30) and ShortWalk's off-line check (2.5 from the
  current segment) ended it in its 7th tick; nothing planned again. OFF_ROUTE or STUCK now re-plans from where he stands,
  up to 3 times (his keys still end it). Testkit 1126-sim-ac-greenstart-replan: the server puts him back on the spawn mid-walk.
- Slow last mobs (Mural, Big Red Flag): with no remembered spot the first roam waits ROAM_AFTER_TICKS (40) + lag
  ("looking 42/45" in room time); finding in acslow-1121-state.md, for the in-run timing owner.
- Testkit 1122-sim-ac-greenstart counted warps from per-tick position samples (trainfix gatehold-aotv-click-1126, 26.2): the route's own first hop lands in the doorway and the next hop one tick later, and a sample falling between the two server teleports read the doorway hop as a start-spot warp "inside the Entrance" (failed 3 of 3 on the branch, passed on origin and once on the branch with a trace: same use_item packets, same landings). Warps are now the position packets after a teleport accept (wire), inside = landing short of the door plane, the doorway hop (0..1) allowed. Never judge a hop chain from per-tick samples.
- Insta Clear First's from-room (acplan-insta-warptile-fix-1126): the warp goes over the wall, so decideInstaClear takes a
  known entry from ANY reachable room on its warp tile (`entryKeyFor(layout, room, landing, fromRoomId)`, unnamed rooms count),
  never only a room 1-2 door steps off, and the TARGET room needs no door path (it is usually behind a closed door;
  `dist` had dropped it silently, no log line); a room is retried until an entry matches. Testkit 1121-sim-acplan-insta-warptile
  (seeds a target 8 door steps from its warp room) failed on origin for days because this fix (674df3c46, acplan-1121) never
  reached a ready line, and the case never turned Auto Routes on, which plain Auto Secret needs to start ("Turn Auto Routes on
  first"). A "pre-existing" sim failure may be a case whose partner commit was dropped: check `git branch --contains` first.

- A failed Auto Clear trip while the floor graph is still warming (GO just opened the gate; a loaded PC) is no proof: the planner ran out of time ("not a proof of no way ... timedOut true ... warm false"), and each retry also pauses the warm-up. Four such failures in 2 s gave the wither door up for good (acflakes-1126, entrance-exit floor 1). They are not counted (6 per run) and the next trip waits for EtherwarpPathfinder.searchReady() (the QUICK graph warm), 10 ticks at most; testkit 1126-sim-ac-coldtrip. The first version waited up to 40 ticks for fullWarm(): the full graph was never warm within 40 ticks of GO under load, so every cold failure cost the whole wait and he left the Entrance at tick 54-56 (1126-sim-ac-entrance-exit's limit is 40; lane1126-2333, acdrops-1127); the retry that then worked planned on the quick graph. Wait for the graph the next search will use, not the best one.
- Insta clear room value (autopilot-instaclear-roomcost-1126, killer560 2026-11-26: "insta-clearing is going to 100% of
  the time be faster than clearing a room itself ... don't just go based off of room size ... Slime is awful ... Cathedral
  is very simple"): the planner had one flat clear guess for every room, so a heavy room's insta clear was worth no more
  than a light one's and lost to small detours. `autoclear/RoomClearTable` (bundled `autopilot/room-clear-cost.json`, from
  `tools/autopilot/build-room-clear-cost.py`: Blood Rush pack boxes per room, the walk through them, height spread and
  miniboss, least-squares fitted to his shared room-costs.json) shapes the guess per room; a room's own learned median
  still wins. Fit 11-26: 0.64 + 0.06/pack + 0.40 per 10 blocks of walk + 0.25 per 10 blocks of height + 1.14 miniboss;
  top Crypt 8.3, Pit 7.6, Waterfall 6.8, Slime 5.5; Cathedral 1.8; bottom 0.8 (Scaffolding, Spikes). Rerun the script
  after br-data.json changes. Testkit 1126-logic-roomcost.
- Only normal and yellow (miniboss) rooms can be insta cleared (killer560 2026-11-26: "it is impossible to insta-clear
  trap or any puzzles. You can insta-clear normal rooms and yellow room"): `AutopilotPlanner.instaType` = NORMAL, RARE,
  CHAMPION; never PUZZLE, TRAP, FAIRY, BLOOD, ENTRANCE. Before this, CLEAR candidates already existed only for those
  three types (AutoClearTargets.isMobRoom), so nothing else was ever credited; the rule is now explicit in instaSavings.
- A trip shows every room on its door path and each of their neighbours, not only the target's neighbours (killer560
  2026-11-26: "I don't want it to jump through the room before quiz if that room is something that it should
  insta-clear"): `AutopilotPlanner.instaBefore(at, .., from)` checks the whole path; Quiz First's walk-in and Ice Fill
  First (Auto Secret's own trips, outside the planner) ask `Autopilot.instaOnTheWay` first, Blood First's rush pick goes
  through `instaNextDoor` too (rush rooms themselves are never credited). Testkit 1126-sim-instacost-quiz/-icefill/-bloodfirst.
- An "insta clear X first" swap must cost what its own trip shows (instaclear-pick-1127, killer560 2026-11-27: "it isnt
  choosing good rooms yet to insta clear"): `instaBefore`/`instaNextDoor` took a 1-star room whose way went THROUGH the
  credited 53-62 star pick (Slime), which was then only passed through and later killed in 4.8 s. `AutopilotPlanner.instaNet`
  now subtracts the credited rooms the swap trip goes THROUGH (and the pick when the swap room is next to it), never rooms
  merely next to that way (instapick-fix-1127: counting those too refused "Gravel first: the way to Flags would show it"
  on 1126-sim-instacost-all, Flags' visit showed Gravel, and 3-4 of 8 armed rooms insta cleared where origin did 6).
  Testkit lesson from the same job: 1118-sim-acbloodfirst draws a sidebar "Keys: ... 0x" line on the sim board; left there,
  every later case of the run read 0 keys once DungeonKeys' 2 s chat lead ran out and stalled at its next slow wither door
  ("no wither key"), which looked like a pick regression in 1127-sim-instapick-big and 1126-sim-instacost-quiz. A case that
  puts something on the shared sim board takes it off in its finally. InstaClearStore counts a KILLED/NO_CLEAR visit
  as a failed entry only when the room was not already shown (before unopened/discovered/failed = a kill visit, not an
  insta try; read time only, the saved file is untouched), and Auto Secret's known-entry warp skips rooms the map shows.
  What the fixed floor of 1126-sim-instacost-all can give (instapick-fix-1127, 11 runs on five jars): Gravel, Three
  Floors, Hallway and King Midas every run; Gold (first pick, next to the Entrance) only when the sim's 8-15 tick reveal
  draw is over about 12, since the start takes that long to land its first warp; Buttons and Overgrown never (a room next
  to the one he just insta cleared is shown before the next trip lands: the insta'd room's clear takes ~14 ticks to end
  and the map path search ~8 more). Origin's extra Multicolored came from a trip that hopped THROUGH Hallway: `SimKeyRoom`
  clears a room on any first teleport in, Hypixel does not clear one he only hops through (his Slime), so the sim is too
  kind there (open: make the sim clear only when he is still inside at the reveal). Judge a swap in a sim case by what
  happened to the bigger room (never insta cleared, or cleared before the swap room), never by the swap's order.
  Known entries never steer Autopilot (it credits by unshown map + clear cost). Testkit 1127-logic-instapick,
  1127-logic-insta-killvisit, 1127-sim-instapick-big.
- (141-autopilot-blooddoor-fix, 11-27) The blood door is LEFT-clicked now (WitherDoorOpener logs "left-clicked the blood door"; the wither door and old path still log "right-clicked"). Testkit 141-sim-autopilot matched only "right-clicked the blood door" and failed on origin with "clicked false, air true" while the Autopilot opened the door fine; it matches "-clicked the blood door" now. A test that greps a log line must follow the line's wording when the mod's action changes.
- A route entered at a late start node ran only to the last number (diagonal-short-1127, his Diagonal 11-27: start #12 of 14, #14 lands on #1's block, #1-#11 held 3 of 4 secrets): `RouteExecutor.start` built the run's order from path order with the start's place as the first index, so lower numbers were never reached, and the endgame go-back reran the same three nodes. A node's number means nothing (killer560: "the number for the node does not matter"): `Route.runOrder` wraps past the last node for a run from the route's own start node (or one that carries an earlier run across a walk-gap warp, `RouteExecutor.carryOnNextStart`), `stackWithSkipped` judges "skipped" by the run's order, a walk gap goes to the nearest unfired stretch head, and `goBackForMissed` skips a room whose every node already fired (`RouteRunMemory.everyNodeFired`): in his 11-27 log four of six whole-route go-backs (Pirate, Diagonal, Temple, Rails) found nothing new, and Rails had run all 19 nodes with every await met: rerunning a route that ran in full cannot find the secret it does not take. Testkit 1127-sim-ap-start-below / -start-gap-above.
- A go-back reran the WHOLE route for one missing secret (goback-partial-1127, killer560 11-27: "itll miss a secret and redo the whole room wasting a bunch of time"; Rails 8/9 reran all 19 nodes). `RouteRunMemory.missedNodes` names what can be redone: nodes never fired this run and fired nodes whose secret wait was never met; `AutoRoutesFeature.warpOntoMissed` warps onto the nearest of them and `RouteExecutor.carryAllBut` makes the run that starts there count every other node as fired, so only those run. Nothing named = no go-back (logged). Testkit 1127-sim-ap-goback-partial.
- A go-back was an endgame-only nearest-first pick, so a far room missing one secret was gone back to while cheaper score waited, or after the need was already covered elsewhere (goback-weigh-1127, killer560 11-27). It is now a planner candidate (`AutopilotPlanner.goBack`, `Candidate.goBack`): seconds = trip + 1 + per-node x the MISSED nodes (`AutoSecretFeature.goBackNodes`, the one eligibility check both paths use), gain = `secretGain` of the missing count, offered only while `usefulSecrets` > 0; `chooseFastest` drops go-backs once the need is covered (Party spares too) and `chooseOrdered` holds them until no fresh route is left. Order type `GO_BACK` runs `goMissed`. Auto Secret's own endgame picks the best secrets-per-second go-back, not the nearest. Testkit 1127-logic-goback-expensive/-cheap/-enough. Two base bugs found with it: a go-back from ANOTHER room never ran its node, because the landing's queued node (`addedToFire`, set by `finishArrivalEarly` on the landing tick) was cleared by the room-change branch seen that same tick (now kept when it belongs to the entered room's route); and Auto Secret's endgame without Auto Clear went to the last uncleared room (which hands back) before any go-back, so the go-back was unreachable (now first). 1126-sim-ap-noback-* give their rooms a route: a room with no route has no node to go back for.
- Green room start (greenroom-late-1127, his 11-27 F7 runs: "sometimes it doesnt warp to the door in green room either till after the run starts"): `GreenRoomStart.tick` ended its arm for good (done = true) while `DungeonState.isInDungeon()` was still false, so a key pressed in the join second (before the sidebar names the floor) never warped, and a key pressed in the last second (RunSummary's "Run started" is the "Starting in 1 second." line, Mort speaks a second later) began a warp inside the start. It now WAITS while the dungeon is unidentified (the arming feature ends on a world change), begins nothing once "Starting in 1 second." was said in this world, polls every 4 ticks (was 20) for the door, the ground and the warp runner, and logs each wait or end reason once (`[GreenRoomStart] not acting yet: ...`, `ended: ...`). Testkit 1127-sim-ac-greenlate-loading/-countdown/-lastsecond/-after.
- Ice Fill with the solver off (autopilot-icefill-solver-off-1127, 11-27): Auto Ice Fill only waits ("waiting: Ice Fill Solver is off") when the Ice Fill Solver is off, yet the Autopilot still planned the trip (Ice Fill first, the tour PUZZLE pick, the puzzle-room stand, 60 s + 73 s of waiting). `Autopilot.iceFillAuto()` (auto AND solver on) now gates all three, as `QuizPlan.wanted()` already did for the Quiz. A testkit case that wants the Ice Fill trip turns the solver on itself (it defaults OFF); 1126-sim-instacost-icefill and 1118-sim-acbloodfirst fail the same way on origin (11-27, second PC), unrelated.
- A searched trip whose result missed the START's 4 ms wait stood 3 ticks before its first warp (tl1127-trip-firstuse, his 11-27 ledger: 404 dead ticks before Auto Clear trips' first warps): the planner's hand-back ran between ticks, so the sneak went down only on the next tick, and with `sendNow` off the hop it then worked out waited for the START after. The sneak now goes down while an etherwarp search is pending (`forceSneakNow`, not for a search through the shut gate) and a late hand-back's queue is sent from the next START as an in-tick search is (`lateSearchStart`). Sim floor 0.80 -> 0.54 ticks a trip from start to first warp (testkit 1127-sim-ac-tripfirstuse, which tallies every such tick by the executor's wait state). What is left there is forced by Hypixel: the server must have heard the sneak before an etherwarp use, and in the first tick after it casts from the standing eye, so with no look that lands from both eyes the use waits one tick (`aimForBothEyes`); a swap asked between ticks or from an END handler goes with the next tick, and no use follows it in that tick. Trips after an unprimed Wither Impact in place, a hop or a walk still pay the sneak tick (2 ticks each).
- A late-search Grim case that tested nothing (tripfirstuse-lanefail-1127): 62-argrim-1127-same-latesearch-swap/tp/second failed in the ship lane with GrimAC silent and 0 late starts. Their arena's trips are all one straight warp (`etherDirectFast`, "no search, direct, one warp"), which never reaches the planner, so `testLateSearch` delayed nothing; the worker's green 1s were a stale `handedBackLate` from the case before. The cases now also turn on `testSearchEveryTrip` (every trip searched), and switching `testLateSearch` clears a hand-back not yet sent. A counter read as "the feature acted" must be reset per case, and a hook that slows one code path must be checked to reach that path in the case's arena.
- A landing nothing warps back out of strands the Autopilot (strand-cages-1126, 2026-11-27): the block at Cages' main
  tile center sits inside an iron-bar cage under a cobblestone lid. The planner flew a 5-17 warp trip onto it (how the
  last hop gets in was not traced; the server's own walk from inside lands only on the block itself), then every plan
  out failed (graph, fine search, room by room, recovery) until "nothing left it can reach". It was a real pocket, not a
  cold-graph miss. `TeleportUtils.hasWayOut` (a ray fan, coarse then every 3 degrees) now gates `etherwarpableInTile`
  (next nearest), the planner's tile region and exact goals (refused with `EtherwarpPathfinder.NO_WAY_OUT`), and Auto
  Secret leaves such a target out at once with no recovery warp. Testkit 1127-sim-strand-cages.
- Ice Fill with the solver off (autopilot-icefill-solver-off-1127, 11-27): Auto Ice Fill only waits ("waiting: Ice Fill Solver is off") when the Ice Fill Solver is off, yet the Autopilot still planned the trip (Ice Fill first, the tour PUZZLE pick, the puzzle-room stand, 60 s + 73 s of waiting). `Autopilot.iceFillAuto()` (auto AND solver on) now gates all three, as `QuizPlan.wanted()` already did for the Quiz. A testkit case that wants the Ice Fill trip turns the solver on itself (it defaults OFF); 1126-sim-instacost-icefill and 1118-sim-acbloodfirst fail the same way on origin (11-27, second PC), unrelated.

## The tick ledger, and Secret Aura's teleport settle (tickledger-1127, his 11-27 morning runs)

- `killer560s-mod-logs/tickledger-1127.py` puts every tick of an Autopilot run (from "[DungeonsAutopilot] on" to off) in one
  class: acting (a packet went out), travel, server-forced (teleport answer, sneak reaching the server, click-gap hold, door
  animation, start countdown), puzzle auto, dead by cause, or unknown. It reads the logger mod's acruns T lines and places the
  game log's tick-stamped lines (AutoRoutes, SecretAura, puzzles) by a per-run offset fitted to the clock. 11-27: 19,131 ticks,
  dead 26%, unknown 6.8%. Rerun it on any later log; the logger's rx= (route step), rc= (Room Clock bucket) and gd=
  (ActionGate denials) columns, added the same day, turn most of the unknown ticks into named ones.
- ActionGate's 6-tick teleport settle held Secret Aura on every route await after a landing (379 ticks in 9 runs, 2-3 a click:
  chosen 2025, refused 2025-2027, body turn 2028, click 2029 after a landing at 2023). The aura re-picks its secret, face and
  turn from each tick's eye, so it waives that settle like Breaker Aura and Door Tap, but keeps the quiet tick after a server
  position (its block click beside a teleport accept is RotationPlace). Testkit 1127-tl-secaura-tpjump (-use, -swap).
- 47 Auto Clear trips etherwarped although their own estimate put hops lower ("~4 ticks vs hops ~2"); handed to
  autoclear-hype-hops-more-1127 (tickledger-1127-hops.py).
