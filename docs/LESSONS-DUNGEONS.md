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
- **Before the run there is no map, and the predictor knew nothing was opened** (to-do Doc 2026-11-01, "PREDICTIVE MAP
  MODE": dig the green room's gate pre-run, peek into the first room, "once the run starts it bugs out the map entirely"; his
  picture shows "Grand Library" drawn where his head is and the green room gone). Hypixel hands the map over at Mort's first
  line, so `HeldMap.available()` is false pre-run; standing in the doorway gap (where he digs) his eye is in no tile, so the
  start room itself was a target and every placement could cover it, and nothing re-decided the predictions when the map
  arrived (`decide` ran only on new sightings). Fix: `RoomPredictor.startTile` (the tile he is in while there is no map)
  counts as opened, and any change in the opened tiles re-decides. The sim used to answer with its stand-in map during the
  countdown, so this state was never tested; it now has no map until GO (testkit 1101-map-prerun; on main the sim cannot
  reach the bug at all, the stand-in hides it).
- **Seeing through one room into another** (same Doc item): in the sim the middle room is named every time (1101-map-
  seethrough, every straight line on the floor). His Hypixel logs (2026-10-30..11-01) show 372 tiles named right, 1 unknown,
  and 15 "every placement ruled out" with 61-440 blocks seen - Default x3, Dino Site x3, Redstone Key, Ice Fill, Steps,
  Temple, Drop, Spikes, Prison Cell, Overgrown Chains, Sarcophagus - all of which have a bundled capture. So a gray middle
  room on Hypixel is that room's live blocks differing from its capture, not the geometry of a see-through; which blocks
  differ needs `RoomPredictor.disagreements` on a Hypixel run (not traced yet).

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
- The map's per-room secret count (Secrets HUD, Auto Secret) is bumped the moment HE takes a secret
  (`RouteRunMemory.gotSecret` -> `LiveMapFeature.noteSecretTakenAt`), before the action bar answers. Every chest click
  counted, so a LOCKED chest (Hypixel: "That chest is locked!") counted too, and every item pickup counted, so an item
  that came out of a chest he opened (or any non-secret item) read as an item secret (killer560, 2026-11-01). Since
  doc-dhud-1101 `AwaitEvents` takes a chest back on the locked line within 1.5 s, and counts an item only when its name
  is one of Noamm's dungeon secret drops and it was not made after his click within 4.5 blocks of that chest (entity id
  above the highest id his world held at the click). Testkit 1101-dhud-lockedchest / chestdrop.

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
- Core Entry Times measured the P4 band (45 < y <= 100, Necron's platform, UNDER the core) until 2026-11-01, so a
  player was "in the core" only after Goldor died and he dropped; Necron's first line ends the timing, so usually only the
  first to drop was timed ("4 mages and 1 arch and it only ever gave the arch's time"). The core is the closed room in the
  middle of the Goldor arena: NoammAddons LeapCounter's in-core box x 42..68, y 106..155, z 54..119 (its INCORE_BOX and our
  Posmsg "Recore" preset 54.5,115,58.5 sit inside). Testkit 1101-dhud-coreentry.
- On Hypixel the Fairy door is usually coal until the run starts and opens 1-2 s after "Starting in 1 second." (Mod Only
  Test logs 2026-10-14..10-20: "fairy door N is a shut wither door" then "door N into Fairy" a second later), and is
  sometimes a real wither door that takes a key (2026-10-18 22:57:02, opened with the key). At the run start the map
  often cannot trace Entrance to Blood yet ("no path from Entrance" for up to a minute), which drew no Fairy door at
  all; FairyDoor now paths to the Fairy room itself then, keeps a door already found, and a shut one is drawn by the
  wither highlight in the Fairy color (testkit 678 checks the color).

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
- **A blood name tag anywhere is not a blood mob.** "Fels", "Skull" and the rest are ordinary dungeon mob names too, so
  tagging by name tag across the whole floor made every Fel a blood mob for Blood Mobs ESP ("something is highlighting all
  fels", killer560 2026-11-01). Name tags count only inside the Watcher's room tile and never with a star. The tag-to-mob
  mapping (the entity one id below the stand) can land on a silverfish (his 2026-10-29 log); silverfish, the Watcher (by id
  or his skull) and Primal Fears are excluded in one place, `BloodCampFeature.excluded` (testkit 1101-bloodcamp-*).
- **An anticheat's transaction pings are not server ticks.** `ServerTickClock`/`ServerLag` call a server ping-driven at 15
  pings a second; GrimAC alone sends about that many, so on the testkit ServerLag read their gaps as stalls and a Blood Camp
  countdown built on it fell 11-15 ticks a second (177 failed "kill timer never went off"), and a rule of 18 pings a window
  still trusted Grim now and then (792: a trip 31 ticks slow). Blood Camp times on `util.PerTickServerClock`: trusted only
  after two 20-tick windows with a ping on at least 19 ticks and at most 24 pings, pings drive it while trusted, client
  ticks otherwise (logic case 1101-logic-bloodcamp-clock drives it by hand).
- **A player-model mob is still `isAlive()` on the client after the server says it died.** Vanilla's
  `LivingEntity.handleEntityEvent(3)` (the death event) sets health 0 and calls `die` only `if (!(this instanceof Player))`
  (javap 26.2 and 26.3), because a player's health comes from its own data. So a killed player-model blood mob counted as
  alive until Hypixel removed it, and Mob Ticks ran on for seconds after the kills (owner's F7 run, 2026-11-02, 233 ticks).
  Take event 3 as the death for any tracked mob (`BloodCampFeature.DEAD_IDS`, the hook in `BloodCampPacketMixin`); never
  read a Hypixel NPC's death off `isAlive()`. Testkit 1102-bloodcamp-npc-deaths checks vanilla still calls it alive.
- **Mobs Left comes from The Watcher's boss bar, not from the entities** (killer560, 2026-11-02: "that top bar is how many
  mobs are left"). The client cannot see the mobs still to come, so a count of the ones on the floor read "1+" with a fifth
  of the camp left. The bar's size is not settled: the wiki says 9 + floor (F7 16, a Hypixel staff quote), Odin and Noamm
  print the bar against 8 + floor. `bloodcamp/WatcherBar` starts from the wiki's and recalibrates from the bar's own
  settled steps (k of N is whole only for the right N), rounding up; it logs "[BloodCampStats] Watcher bar ..." each step,
  so the first real camp's log settles which size Hypixel uses.
- **A skull 20 blocks from the Watcher is not the edge of the Blood Room** (owner's F7 camp, 2026-11-02, "missed a wall
  spawn"). NoammAddons and Odin both track a skull's trip only within 20 blocks of the Watcher, but he flies to the case of
  the mob he picks (wiki) and the cases sit on walls 24 blocks apart, so a skull leaving the far wall while he was at the
  other had no trip at all (his log: a mob landing with no "trip tracked" line, 6 ticks after another wall's trip started;
  his morning run has the same). The gate is now his room tile or the old 20 blocks; a moving player-head stand with an
  unknown texture is logged once ("skull stand #N moved but its texture is not a blood mob's"), in case a skin is missing.
- **Hypixel removes a blood mob's skull stand around the tick the mob appears, often first.** Dropping the trip with its
  stand left the mob to a room scan that ran every 5 ticks, which is what the owner felt as "Trigger Bot clicks were very
  delayed" (9 of his 16 mobs were tagged only on a multiple of 5). A removed stand's trip now stays (`LANDED`) for the
  landing tag, and the room tile is scanned every tick. Testkit 1102-bloodcamp-tag-latency.
- **The Watchful Eyes do not wear the Watcher's skull.** Two zombies with ids right after his (#330208 -> #330210, #330212)
  circle the room; one passed a landing spot inside the tag window and became that trip's "blood mob" for ESP. Excluded by
  the "Watchful Eye" name (custom name or the stand one id above; see the name-tag lesson below) and as a non-player mob
  within 8 ids after the Watcher that joined the client world within 5 ticks of him. Testkit 1102-bloodcamp-watchful-eye.
- Every tagging path goes through `isAttackableMob` (alive, not a player summon, not excluded): the name-tag path did not,
  so his own Guided Sheep under a blood name tag became a blood mob, and a dead named NPC was re-tagged on every scan.
- **The Watcher leaves the client world mid-camp.** Owner's run 3 (2026-11-02): "Watcher removed by the server" for six
  seconds while he flew to a case and the player was still outside; every skull move in that time was dropped and the
  first wave's trip was picked up mid-flight (box 7 blocks off). Gate on his last known spot (`watcherAnchor`), not on his
  entity being there. Same run: 8 case stands had skins NoammAddons' list lacks; a sideways move from the case grid (x/z on
  a block's center, y N+0.75) is a trip whatever the skin. Testkit 1102-bloodcamp-missed-box.
- **A trip must not take a mob tagged before its window opened.** Trip #479208 silently took a giant name-tagged 120 ticks
  earlier as its mob, and its own giant was never tagged or clicked (1102-bloodcamp-trip-steal).
- **"Hit" is not "dead".** The Trigger Bot clicked a mob once and never again once it saw a hurt flash, so a mob that
  survived one hit stood there "already hit" for 121 ticks with him aiming at it ("it left one"). A hit mob that is still
  alive is clicked again once its hurtTime is back to 0, at the weapon's pace (1102-bloodcamp-hit-alive). This is outside
  Never Miss's landing-spot wait.
- A name tag near a mob is not its name for an exclusion: an orbiting Watchful Eye's tag inside a blood mob's box excluded
  that blood mob as an "Eye". Eyes are matched by their own custom name or the stand one id above, never for a player.

## Terminals and solvers

- Melody's Custom GUI picked the moving piece as the one pane COLOR appearing exactly once; a board with a second
  lime pane (a finished row keeping its marker, which Odin handles with indexOfLast) has none, so after a row or an
  auto terminal's skip nothing was drawn as moving (killer560, 2026-10-05). Find a terminal's piece by position from
  the current slots (`findMelodyMovingSlot`), never by color counts; testkit 218 fails 5/16 on the old rule.
- Terminal and device SIZES are read off the board/device, never constants (SkyBlock 0.27.2: Melody 3 rows, Click in
  order 10, Simon Says 4 rounds; `terminals/TerminalLayouts`). The old Melody code was row-generic only by accident
  (`MELODY_CLAY_SLOTS[r-1] == r*9+7` for rows 1-4), so a 3-row board still auto-clicked; what broke was indexing past
  the last row - Melody Keys' key 4 pressed slot 43, the bottom marker row (testkit 290 fails on main 5813cb91).
- p3sim.net still runs the OLD 5-round Simon Says device (every device in his Mod Only Test logs of 2026-10-22 and
  10-27 revealed a fifth round), Hypixel the 4-round one, and the mod's own dungeon sim builds no device at all (the
  testkit's `hx/boss/HxSimonSays` is the only emulated one). A "sim" Simon Says report from him is p3sim: check the
  log's "Connecting to" line. The round count is remembered per server (`TerminalLayouts`, p3sim known as 5 before its
  first device), Auto Solve's click total is worked out at every click from the attempt's start round (a fifth round
  found mid-attempt joins the plan), and a remembered 5 is still checked at round 4. Before 2026-10-27 the first p3sim
  device of every session was planned as 4 rounds and ran about 3 s past the Target (14.7 and 14.9 s at 11.8), and a
  p3sim session taught "5" to the Hypixel devices after it (round 4 never ended them; Auto Restart pressed start on a
  finished device). Testkit 1027-ss-*.
- Simon Says round transitions on Hypixel did not change with the 0.27.2 cut: a skip run's 2->3 plus 3->4 is about
  4600 ms on the 4-round device (twelve runs, 2026-10-23), the old device's 2148 + 2398. The reserve table had them at
  2000/2300; it is 2150/2450 now. The completion log line lists each transition ("transitions 2->3 2150, 3->4 2450"),
  so re-measure from that line, not from the sum.
- Tic Tac Toe's prediction skipped the computer's own square when testing "safe whatever it plays", so the square the
  computer was about to take always qualified and was what got shown. The prediction now takes every computer reply with
  its best outcome and shows his best move after them, never one of those squares (testkit 679, all 2,423 positions).
- "Hover terms doesn't work in melody" (killer560, 2026-10-26) was not the click logic: on the base jar, Hover Melody
  clicks each row once at the right column (testkit 271/272 pass on 97527a55). It was a second switch, Hover Melody,
  shipped OFF under the hover sliders: his config and Full Toggle profile had Hover Terminals' delay set to 50 and
  `hoverMelodyEnabled: false`. It now defaults ON and old files load ON (`hoverMelodyVersion`). A sub-feature behind its
  own default-off switch reads as broken; check the saved config before the code.
- `MouseHandler.isLeftPressed()` only changes while NO screen is open (javap 26.1.2 `onButton`: the write sits behind
  `screen == null`), so inside a terminal it never goes true. Hold To Click tracks the button with
  `ScreenMouseEvents.allowMouseClick/allowMouseRelease` on the container screen (testkit 273/274 timed out without it).
- The Fake Cursor aimed at vanilla slot coordinates, which under Custom GUI are hidden slots nowhere near the drawn
  panel (killer560: "doesn't match the terminal gui"). Anything that points at a terminal slot asks
  `TerminalSolverFeature.slotCenterGui`, which uses the panel layout the render and click hit test use.
- Melody Lookahead counted the proper click (1 and 0 both meant none); since 2026-10-26 N is the EXTRA rows, max 2,
  old files move by one on load (`melodyLookaheadVersion`). A lookahead click for a row the live match already clicked
  is dropped, and the live match skips a row with a lookahead queued, so no row is clicked twice (testkit 275-277).
- A Melody "double click" (ship gate 1102k, testkit 292 "34=2") was almost never two sends of one decision: it was a click
  that reached the server after its pane had stepped (a wrong click), and the row clicked again later. Causes found under
  load: the live path's only re-click guard was 250 ms; a click sent late in the pane's stay (Stream Safe's hand); a lookahead
  click sent after the pane was already seen off the target; a pane age measured from when it appeared on a new row (Hx keeps
  the column and the step clock across a row change); and a step seen late after a frame hitch. Now (`TerminalSolverFeature`):
  one click per row until the pane is seen MOVING on that row (a miss), a live or lookahead click goes only if age since the
  last step (dated to the scan before the one that saw it) + measured ping + 50 ms is under the board's step time, and a
  lookahead only while the pane is on the target. The Hx click event carries the server's lime/target/msSinceStep: judge a
  wrong Melody click by those, never by the client's log. A server catching up after "Can't keep up" burst-ticks steps; no
  client rule covers that.
- A migration marker key must end in `Version` (or match testkit 310's marker pattern: seeded, migrated, defaulted, V2,
  a year). 310 flips every other number in a saved file and reloads; it flipped terms-1026's `hoverMelodyFormat` 2 -> 1,
  the load re-ran the migration and `hoverMelodyEnabled` came back ON (found by another agent, 2026-10-27). The keys are
  now `hoverMelodyVersion` / `melodyLookaheadVersion`, and files saved with the old `...Format` keys still count.
- A Rubix click is a real PICKUP, which the client predicts by lifting the pane: the slot reads EMPTY until the server
  answers, so its highlight vanishes for that moment. Anything that keeps working a slot "while it is highlighted" lets
  go there and comes back after the answer - the Auto Terminals cursor did exactly that (killer560, 2026-10-27: "if
  rubix is a 2 it'll go off of it and come back"). Keep the slot while its click is unanswered (`HumanClickOrder.next`,
  `stickySlot`); testkit 1027-menu-term-human-rubix checks each pane's clicks come back to back.

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
