# Dungeon feature lessons

Lessons from the dungeon features that are not automation runtime: the map, Blood Camp, Predictive Rooms, waypoints,
scoring, terminals, and the Interactive Map's planner as the features call it. Part of the feature lessons:
[LESSONS.md](LESSONS.md). Automation runtime is in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md); the sim in
[SIM.md](SIM.md).

## Dungeon Map and room data

- **The live map's world scan must stay inside the floor's room grid.** Floor 1's boss arena stands in slots
  (4..5, 5) of the 6x6 footprint; scanning all 121 cells read its roof as two ROOM tiles, which widened the map's
  fit to 6x6 (his "doesn't rescale for other floors") and kept the boss latch off until he was deep in the arena.
  `scan()` and `insideGridFootprint` now use `MapPainter.currentFloorRooms()`.
- The dungeon map item's start corner is fixed per floor; measuring it from the first 16-pixel run of color 30 (as
  NoammAddons does for F4+) read (16,5) on two of his M5 runs (2026-10-08 log) and shifted the whole map for the run.
  `DungeonMapScanner.knownCorner` holds every floor; the run is only a size check and the fallback for an unknown floor.
- On Hypixel the vanilla map draws the door into Fairy as a WITHER door while the doorway has no coal (killer560's
  2026-10-08_15.48.34 F1), so anything that finds wither doors by their coal never sees it. Fairy Door takes it from the
  map grid; only a door the scan really finds shut (coal) is left to the wither highlight (testkit 678).
- Blue terracotta at a tile corner is not always the clay marker. Hallway, Mossy, Pipes, Pit, Quartz Knight and Waterfall
  have a blue roof stripe crossing a tile join, so the per-tile fallback (room not fully on the map yet) took a join for the
  corner and put routes and waypoints a tile off; the map never re-checks a found rotation (2026-10-19). `RoomDatabase.
  findRotationAndCorner` now needs air one block outward on both axes (all 133 real markers in `rooms/` have it).

## Predictive Rooms

- **Predictive Rooms named almost nothing on Hypixel because it waited for 120 seen blocks a tile** (to-do item 22).
  A player glancing through doorways gives a tile 50-120, by which time every room but the right one is ruled out; 1726 never
  saw it because it stares from fifteen spots for 150 ticks and calls `settle()`. Measure a sight feature with the looks a
  player makes, not the best case. The thresholds went to 40 seen / 30 matches per standing placement (testkit
  1018-predict-hypixel: a sim floor walked like a player, 12 rooms named right on the fix, 2 on main), then to 20 / 15 (below).
  A `[Predict] tile x,z closed:` line says, per tile he looked into, what it named or why not.
- His in-game "about 20 s in a corridor" (Mod Only Test, 2026-10-18) was a main jar (f0a61ba4): the tile's first block at
  18:01:41, named at 18:01:50 at exactly 120 seen, one candidate build, so gathering blocks was the wait, not rebuilding or
  queueing. A doorway seen from a corridor is a slot a handful of the 336 grid rays reach; the sweep adds eight rays round
  each grid ray that met an unopened room (at most 336 more) and sweeps every 2 ticks instead of 4 while it sees one
  (1018-predict-hypixel-corridor: named at ticks 2-3; tick 35 without them; never on main, stuck at 73 seen).
- **Predictive Rooms never named Water Board on Hypixel: "every placement ruled out - 330 block(s) seen, 0 of 412"** (his
  Mod Only Test log, 2026-10-18). Glass has no visual shape, so a look into the room mostly meets the board behind the front
  glass, and Hypixel's board differs every run (four layouts, 27 piston gates the levers move); every Water Board capture
  there is (bundled, Mod Only Test's - the same rotated 90 degrees - and Map Logger's) is the same frame of board 0.
  `RoomPredictor.VOLATILE` leaves the board planes and the color columns out of the comparison. Write such boxes in
  ROOM-RELATIVE coordinates and turn them through each capture's own `RoomCaptureRotation`: the first version held capture
  boxes decoded off the bundled file, and his disk capture (which wins) is turned a quarter, so they covered the wrong
  wall. The sim's own board matched the capture, so it named the room on main too; testkit 1018-waterboard-predict-board
  mirrors the board planes first (main: 0 placements standing, as on Hypixel; `RoomPredictor.disagreements` lists what a
  placement disagreed on).
- **Predictive Rooms' speed was the sweep cadence and its one wrong name a soft match** (testkit 1020-predict-matrix: every
  doorway of 15 sim floors from 2-27 blocks back, the predictor ticked from a given eye through `RoomPredictor.tickFrom`,
  variants paired through `tune`). Sweeping every 2/1 ticks, a second focus round (1024 extra rays) and 20 seen / 15 matched
  took a name from 8.0 ticks mean to 2.2 and the furthest named from 14.0 to 19.0 blocks back. The sim's blocks ARE the
  captures, so a clean matrix never names wrong; `tune("TEST_NOISE", 0.05)` reads that share of seen blocks as random
  others, and it alone found Water Board named on Mines (4-6 glances a run, on main too): its board planes' palette matches
  any stone, so with Mines ruled out by noise they made up Water Board's matches. Soft matches no longer count toward
  MIN_MATCHES. The comparison budget charged every seen block in a candidate's span, ruled out or not, so more rays queued
  ticks of work that took a few (1018-predict-hypixel-fresh named at tick 20: 3506 placements, 1712 seen); it now charges
  a compared block 1, a block in another tile 1/16, and nothing after a candidate is ruled out.

## Secrets, crypts and waypoints

- Crypts and princes have no positions anywhere (room database and every installed mod's rooms.json: a count), and
  their undead do not exist until the tomb is blown, so `secretwaypoints/CryptScanner` finds them from blocks. The
  rule was fitted against the captures vs the database count (121/134 rooms exact since 2026-10-19: a crown needs air
  above, a slab staircase, a covered wall panel and a ledge 3+ blocks over the floor are not lids). Re-run
  killer560s-mod-logs/crypt-census/crypt_scan_port.py (a full port of CryptScanner; `--new` tries crypt_new_rule.py)
  before changing the rule; crypt_rule_b.py there is the stale 2026-10-05 version.
- An item pickup reaches the client a round trip after the server took it, by which time an etherwarp or a chest grab has
  carried the player away; Secret Waypoints missed pickups by checking a 3-block box around the player. Watch the item
  entity by id and count its REMOVAL from the level, not its leaving a box around the player.
- Secret Waypoints' lever waypoints never hid on a click: the click listener marked the lever in `COLLECTED`, but
  `scanLevers` builds its waypoints outside `addGroup` and never asked `COLLECTED`, so the next rebuild (<= 1 s) put it
  back. Any waypoint group added outside `addGroup` must apply the same collected filter.
- A per-position "done" set must reset on a new RUN, not only a new world. Secret Aura, the Secret Triggerbot and Secret
  Waypoints' collected set cleared on world change / leaving the dungeon; a sim rebuild of the same room is neither and puts
  every secret back on the same blocks, so after run 1 the aura clicked nothing (his Museum, Map Logger). All three now
  also reset when `LiveMapFeature.resetGeneration()` changes (world change, dungeon entered, sim floor published).
  99-sim-aura-rebuild rebuilds Museum three times in one world and fails on 5008e4d6.

## Score and run records

- **The Murkbat bat bonus is one per PLAYER, not one per run like the Prince.** The shard's Rekindle attribute says "+1
  Bonus Score", "Limit of +1 Score per run" (hypixelskyblock wiki, Murkbat_Shard), but the wiki's Dungeon Score page says
  it works out as one per player, up to 5, and Odin counts it that way (833e0533). Hypixel's line is `A Bat has been
  slain. +1 Bonus Score` (Odin `Mimic.kt`, Skyblocker `DungeonScore.java`; color codes unknown). `ScoreCalculatorFeature`
  keys each bat on a name (his own line, a mate's "Bat Killed!") and `ScoreCalculator.BAT_BONUS_CAP` is the one number to
  set to 1 if Hypixel makes it per run. The dungeon sim does not model attributes, so it never sends it.
- **Hypixel prints the score block ("Team Score: N" and "> EXTRA STATS <") when a run is WIPED too**, so those lines do not
  mean the run was finished. Run Summary stored a 21 s wipe (score 2, D) as the fastest F7 and announced a personal best
  (killer560, 2026-10-15). The only line that proves a boss kill is "☠ Defeated X in ..." (`RunRecord.hypixelTime`); a PB
  now needs that plus Hypixel's own score at or above the PB Minimum Score. Personal bests are derived from stored runs,
  so the check lives in `RunRecord.countsForPb` and every best reads through it. Testkit 107-hx-runsummary-pb.
- An insta-clear observation must know whether he STOPPED in the room. A map path lands in rooms on the way for a tick
  each; the v1 recorder judged them like stops, so they closed NO_CLEAR (one failure blocks an entry for good), or NO_STARS
  when the room flipped ~200 ms after landing, before its mobs had loaded (Duncan, a real insta). Now: left within 3 s and
  no flip by 1.5 s after leaving is PASS_THROUGH; a flip with no starred mob in sight waits (up to 60 s) to see the mobs,
  which Hypixel leaves standing in an insta-cleared room. 98-sim-insta-clear-live replays both on a sim floor.
- `ClearExecutor.isActive()` goes false two ticks after the last hop is SENT (`compDelay`), before its landing comes back,
  so anything asking "is the Interactive Map moving him?" at the landing must use `isBusy()` (true through the arrival
  sync), and read it when the position packet arrives. The insta-clear recorder used `isActive` on the next tick and
  logged every map path's final landing as "teleport by manual" (Mage, Hall, his live F7 runs 2026-10-06).

## Blood Camp

- Blood Camp's prediction is NoammAddons' (killer560 asked for it, 2026-10-07), including a Kotlin quirk: Noamm sums
  `packet.xa / 4096` with a Short and an Int, which is INTEGER division, so a blood mob's sub-block step adds nothing and the
  trip starts at the skull's spot in the wall. Ours divided by 4096.0, started one packet out of the wall, and landed the
  predicted spot one step (0.215 blocks in test 392) too far along. Keep the integer division; it is the model, not a typo.
- **A gap in an entity's move packets is not the end of its movement.** Blood Camp restarted a blood mob's trip after any
  10-tick gap, which is also exactly what a server or network stall looks like, so a lag spike threw the path away and
  re-fitted it from mid-air (killer560: "can break and not show the path if the server lags for a tick"). A gap now starts
  a new trip only when the stand had reached its predicted spot, the gap passes 5 s, or it reverses (testkit 552: a
  15-tick stall, path drawn on every tick, trip kept).
- Blood Camp's Trigger Bot tested "crosshair on the landing box" with the ATTACK reach (3 blocks), so from anywhere you
  actually camp blood it never clicked, and its mob search skipped every `Player` - the Watcher's summons are player-model
  NPCs (v2 UUID). Aim is line of sight (48 blocks); only the attack is reach-limited, else the click is a plain swing
  (testkit 686: 16 blocks out it now swings once, 2.7 out it attacks and the mob loses health).

## Terminals and solvers

- Melody's Custom GUI picked the moving piece as the one pane COLOR appearing exactly once; a board with a second
  lime pane (a finished row keeping its marker, which Odin handles with indexOfLast) has none, so after a row or an
  auto terminal's skip nothing was drawn as moving (killer560, 2026-10-05). Find a terminal's piece by position from
  the current slots (`findMelodyMovingSlot`), never by color counts; testkit 218 fails 5/16 on the old rule.
- Terminal and device SIZES are read off the board/device, never constants (SkyBlock 0.27.2: Melody 3 rows, Click in
  order 10, Simon Says 4 rounds; `terminals/TerminalLayouts`). The old Melody code was row-generic only by accident
  (`MELODY_CLAY_SLOTS[r-1] == r*9+7` for rows 1-4), so a 3-row board still auto-clicked; what broke was indexing past
  the last row - Melody Keys' key 4 pressed slot 43, the bottom marker row (testkit 290 fails on main 5813cb91).
- Tic Tac Toe's prediction skipped the computer's own square when testing "safe whatever it plays", so the square the
  computer was about to take always qualified and was what got shown. The prediction now takes every computer reply with
  its best outcome and shows his best move after them, never one of those squares (testkit 679, all 2,423 positions).

## Routes, the Interactive Map and puzzle autos (feature side)

- Chained Auto Routes etherwarps each logged "acted 1 tick(s) ... sneak went out in the firing tick's input packet"
  (his maplogger-latest2.log, 2026-10-04) because the executor kept sneak only for a next etherwarp in the SAME
  stack; the interact-delay settle ticks after a landing then sent shift up and the next warp re-sneaked. Held
  sneak is now decided after every node by `RouteExecutor.planSneak` from the node that actually fires next.
- `AutoRoutesFeature.warpToStartNode` called on its own (not through a map press) leaves the last route's
  `justFinished` latch set, so the next start node lands "latched" and never arms - Auto Secret did this for every room
  after the first (102-sim-autosecret). A map press clears it in `InteractiveMapFeature.queue` via
  `cancelForInteractiveMap`; anything that drives the start-node warp must call that first, as Auto Secret now does.
- **Dungeon Autopilot sim finds (2026-10-06).** `canPath` refuses Maze/Boulder by NAME and nothing walks: autopilot2 lifts it
  once the room is done (map/tab, or `permitLeave` after our step); a TRAP room always refuses and `ClearExecutor` fires no hop
  inside one (no ability works there - killer560). A doorway's connector cell reads as no room (`currentRoom` -1): decide
  from the last room. A dropped key leaves the CLIENT at ~100 blocks - gone is not picked up. `WitherDoorOpener` clicked a door
  with the AOTV in hand and the sim fired Instant Transmission; it now uses the key item or an empty slot.
- `ClearExecutor.etherPath` / `AutoPuzzleUtil.pathIfMapOn` want the block to LAND ON (solid, two air above), the same
  position `AutoReposition.start` takes - not `.above()`. Given the air the planner logs "is not etherwarpable ...
  Nothing searched" and the map says "Failed after 0ms". Auto Blaze and Auto Ice Path both passed `.above()` until
  2026-10-05.
- The Interactive Map's floor graph (2x2 buckets) misses ledge landings: asked for a block beside Higher Blaze's chest it
  landed up to five blocks off ("near"), or called him "already there", and from that ledge it "proved" no way back down
  the room the room-by-room planner had just walked him up. When the block itself matters, use
  `ClearExecutor.etherPathExact` (room by room before a near landing or a graph "no way"), and filter goals with
  `EtherwarpPathfinder.isEtherwarpable`: a carpet-topped spot he stood on is not one, and the planner searches nothing.
  The graph's "proof of no way" is only about its own landings and aims: from Atlas's ledge it proved no way to a block
  three warps off, because the hop down past the ledge's lip clears it only between the 18 face aim points (testkit
  404-sim-planner-gaps). On any graph "no way" `EtherwarpPathfinder.finePlan` now searches every landing of his and the
  goal's rooms with the off-lattice points too (~30 ms, at most 200). Judge a "no way" against real casts (404's ground
  truth) before calling it a planner bug: 131's two "no way by a wall" spots were a one-block floor in the open seam
  between two rooms' walls, sealed except to the roof - correct answers (404-sim-planner-cracks).
- An etherwarp aim verified at its exact float yaw is not verified (95-sim-map-warp): the use packet carries the RUNNING
  yaw (player yaw + wrapped turn, ~900 degrees here), which keeps fewer fraction bits, so the server read 173.65979 where
  173.65981 was planned and the ray caught a block corner the plan cleared by millionths of a block. Rays at lattice
  slopes (yaw 18.43495 = atan(1/3)) pass exactly through block corners, so this is common, and a replan from the same spot
  picks the same cached edge. `WarpGraph.plan` now runs every hop through `EtherSearch.holds` (lands for yaw/pitch +-0.01
  degrees), re-aims a fragile one with `aimFirm`, else drops that edge and plans again. The diagnosis that found it: log
  the server's refusal, then cast the server's exact ray through the client planner's grid - BLOCKED on both sides meant
  the worlds agreed and the aim was the fault.
- `MazeWalk` plans on ONE feet level, so it cannot take a staircase; Auto Boulder used to drop through a hole in the
  roof instead ("struggles going down the stairs"). `autopuzzles/BoulderPath` plans across heights (stairs climbed from
  their low side, ledges cost extra). And a walk planned on the tick of a Boulder button press plans round the box's OLD
  position (the move reaches the client ticks later: "no walk", 223 nodes) - wait ~400 ms after a press.
- A standalone sim puzzle builds IN PLACE of the room's floor (its floor sits at the player's feet minus one), so its
  reset must put back what was there, not air. The Teleport Maze reset aired its 52 floor blocks and the player fell
  into the void (testkit 78: "[Sim] back to the middle of the room", then "icepath: built nothing"; 26.1.2 and 26.2
  alike, only when he stood on the arena). It now records the prior states on the server and restores them. Tic Tac
  Toe's reset still leaves 1-3 blocks fewer than before (78's "arena after reset"; not traced).
