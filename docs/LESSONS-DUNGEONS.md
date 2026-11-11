# Dungeon feature lessons

Lessons from the dungeon features that are not automation runtime: the map, Blood Camp, Predictive Rooms, waypoints,
scoring, terminals, and the Interactive Map's planner as the features call it. Part of the feature lessons:
[LESSONS.md](LESSONS.md). Automation runtime is in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md); the sim in
[SIM.md](SIM.md).

## Leap Order and classes

- Leap Order newcomers landed in the wrong spot and pushed a stayer out (killer560, 2026-11-02; Reset All fixed it).
  `LeapRunMemory` found the departed's spot by laying the old party out with TODAY's classes, where the departed has none
  (PartyTracker drops tab-list classes on leaving a dungeon) and a stayer may have switched, so with a Tank and a Mage both
  wanting bottom-right a departed Tank "stood" top-left. It also pinned newcomers into vacated spots after a stayer changed
  class (his log 2026-10-31 23:55: a Tank joining for a Healer was pinned in the Healer's corner). Fix: lay out with the
  last run's classes and played class, place newcomers only while that layout holds (leaporder-1102).
- A class pick masks the tab list: comparing classes WITH picks never sees a picked teammate switch class, so their pick
  and pin outlived the role. Compare the tab list's own class per run (`lastRunTabClasses`) and drop stale picks/pins.
- Your own class: `PartyTracker.selfClass` let a pick on your own name beat the tab list, and nothing cleared one (rule 1
  skips you; the removed Class Selection Overlay could set it). The tab list wins now; outside a dungeon `playingClass`
  uses Hypixel's "You have selected the X Dungeon Class!" (and the pre-start, Party Finder and "stats are doubled" lines),
  persisted, before the editor's last class. RunSummary's stored class matched Hypixel's line in all 42 runs checked, so
  the end-of-run tab class was never the problem; the fallbacks were.

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
- A timer started by a chat line and checked in END_CLIENT_TICK reads a frame to a tick late for something already true
  at the line: the line is handled with the frame's packets (`Minecraft.runTick` processes packets every frame, then runs
  0..n client ticks), so the first END_CLIENT_TICK can be up to 50 ms later. Core Entry showed players standing in the
  core as 0.05s (killer560's F7 run, 2026-11-02, Ticks counting); it now records everyone inside at the line itself as 0.
  Do the first check in the line's handler. Testkit 1102-splitchat-core0(ticks).
- On Hypixel the Fairy door is usually coal until the run starts and opens 1-2 s after "Starting in 1 second." (Mod Only
  Test logs 2026-10-14..10-20: "fairy door N is a shut wither door" then "door N into Fairy" a second later), and is
  sometimes a real wither door that takes a key (2026-10-18 22:57:02, opened with the key). At the run start the map
  often cannot trace Entrance to Blood yet ("no path from Entrance" for up to a minute), which drew no Fairy door at
  all; FairyDoor now paths to the Fairy room itself then, keeps a door already found, and a shut one is drawn by the
  wither highlight in the Fairy color (testkit 678 checks the color).

## Blood Rush Helper

- Boxes "not loading in" (killer560, 2026-11-03): a room showed boxes only from spawn spots learned on earlier runs or
  from mobs already in view, and learning ran only during the rush (before the blood door) and only while the helper was
  on, so after a night of runs his file held 14 spots in 5 rooms. There is no public starred-mob spawn table (adm's is All
  Rights Reserved; the room captures' `mobSpawns` are 74 snapshot positions in 25 rooms, mostly bats). Fix (bloodrush2-1104):
  the jar ships `assets/killer560smod/bloodrush/spawn-spots.json` (rebuild with `python -I tools/bloodrush/build-spots.py`,
  which merges every Prism instance's learned file into it), spots are learned all run until the boss, and a spot is learned
  only after the mob stood on it half a second. Visibility: 2 px outlines and 12% faces were hard to see; Outline Width
  (default 4) and Filled Boxes (default on, 25%) were added, proven in pixels by testkit 1104-bloodrush-known.
- Where spawn spots could come from (surveyed 2026-11-04, so nobody repeats it): the 134 room captures' `mobSpawns` hold
  starred entries only for Archway (40: zombies, skeletons, 2 Fels), and Archway is a bad capture anchored a tile off
  (docs/SIM-ROOMS.md), so its frame cannot be trusted; the rest are bats. The sim places mobs at random unless a room has
  known spots (`SimLiveMobs.placeMobs`), so it is no source. His Hypixel logs name no starred mob positions with a room
  frame ([Bat], [AutoClear] key room, [InstaClear] lines carry none). The NoammAddons room data (rooms-modern.json), Hades'
  rooms.json (LGPL, room ids and scores only), Nebulune, Athen and QUOI hold no spawn positions; only adm does (ARR, not
  used). So the table grows only from learning: since bloodrush2-1104 Learn Spawn Spots also runs while the helper is off
  (its row is built in the tab either way and is in masterToggles, so the card reads ON while it learns), and the table's `sources` map
  names each room's origin.

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
- **Split Timers' lag is the stalls, not the net tick deficit** (tickfix-1104; killer560 2026-11-04: "run splits never
  shows lag now"). Odin (`SplitsManager.tickCounter`) and NoammAddons (`DualTime`) both show `wall - 50 ms * pings`, and
  so did `SplitLagClock` after timers2: on Hypixel that nets to 0, because the server runs the lost ticks afterwards (a burst
  of pings). His 2026-11-03 23:56 run printed "Storm took 45.15s (43.90s)" and then "Lag: 0.00s" for the run, and every
  other split equal to its lagless time to 0.01 s. `SplitLagClock` now follows that deficit from its lowest point and
  counts a rise of more than 150 ms in full, never paid back (a burst only lowers the low point); jitter counts nothing.
  Testkit 1104-logic-split-lag drives it at wall-clock pace. Each counted stall logs `[SplitLag] stall: +N ms`.
- **A repeating boss timer kept as a counter drifts; keep the server tick of one real event instead.** Goldor's "Tick:"
  was set to 60 on his line and decremented once per `ServerTickClock` event, which counts CLIENT ticks in any second
  with under 15 pings and was skipped whenever the sidebar did not read F7, so nothing ever pulled it back onto the
  server's 60-tick cycle (killer560, 2026-11-02: the mask "procced ... at like 1.5s instead of at 0"). `ticktimers/
  GoldorTick` keeps the `PerTickServerClock` tick of a hit and works the line out from it, and re-locks on observed hits
  (your health dropping, a mask/Phoenix proc line, two players hurt in one tick; a stray hit needs a second one a period
  later). Storm's pad cycle (`StormLine`) is done the same way. Testkit 1102-logic-goldor-tick, 1102-hx-goldor-tick
  (the testmod's `boss.timeline` damages the player at exact server ticks).
- **A chat line and a health drop in the same server tick read the clock one apart.** Chat is handled while the client
  tick runs its packets, BEFORE `PerTickServerClock`'s end-of-tick step; a polled health drop is seen after it. The first
  1102-hx-goldor-tick run read Goldor's first hit 2 ticks late (one of them this, the other the testmod's `damage` at
  END_SERVER_TICK sending the health packet a tick later; it now runs at START_SERVER_TICK, as a plugin scheduler would).
  Anything that compares a chat event with a polled one puts both on the end-of-tick reading (`GoldorTick.endOfTick`).
- **Off Hypixel the server-tick clock wobbles by several ticks.** Without per-tick pings (a GrimAC server: its transactions
  are not ticks) `PerTickServerClock` follows the client's game time plus whatever set-time packets come, which on the 26.x test
  server do not stop a drift: hits read 4-8 off, and on 26.2 drifted 4 ticks per 60 (1102-hx-goldor-tick), so GoldorTick's
  re-lock pair allows 6 ticks off a whole period. With
  per-tick pings (Hypixel; the testmod's `boss.pertickping` under -Pnogrim) hits read 0 +-1. GoldorTick snaps within 8.
- **On a trusted per-tick clock a locked Goldor phase must not be moved by single hits** (tickfix-1104; killer560
  2026-11-04: "still happens ... i mainly notice it during goldor"). The 8-tick snap sized for GrimAC let every stray
  health drop near the prediction walk the phase on Hypixel: his 2026-11-03 23:55:05/08/11 log has Spirit Mask, Phoenix and
  Bonzo procs exactly 3 s apart, the first locked the phase, and the Bonzo proc read 16 ticks off it. Odin and NoammAddons
  never resync at all (60 on Goldor's line, minus one per non-zero ping). Trusted, `GoldorTick` now confirms a hit within 3
  ticks and moves nothing, and re-locks only on hits a whole number of periods apart within 2 ticks (a pair with a mask proc
  or a multi-player hit in it, else three in a row). Testkit 1104-logic-goldor-trusted. Every hit and every predicted 0 logs a
  `[TickTimers] Goldor ...` line (ticks since Storm's death and Goldor's line, wall ms, pings, trust); every other tick timer
  logs a `[TickTimers] <name> proc:` line.
- **Measured F7 gaps, every 26.x/26.1.2 log of his since SkyBlock 0.27.2 (2026-10-06), 1 s resolution** (tickfix2-1104,
  `killer560s-mod-logs/tickfix2-1104/gaps.py`): Storm's death line to Goldor's line 3 s in 357 runs, 4 s in 125, 2 s in 9 -
  a steady ~3.26 s, 65 ticks of wall time with any lag in it, so Odin's 64; before 2026-10-06 it read 5 s / 6 s (Odin's old
  104). Maxor's opening line to "DON'T DISAPPOINT ME" 4 s in 403, 5 s in 118: ~4.23 s, MAXOR_START_TICKS 83 fits within lag.
  Necron's "congratulations." to "I'm afraid, your journey ends now." 2 s (403) / 3 s (67) and to "Goodbye." 4 s / 5 s: the
  60-tick drop sits between them and no chat line marks it, so it is not checked. Livid (4 runs), Spirit Bear, Terracotta,
  the purple pad and the mask cooldowns have no chat line at their end to measure against; their values stay the references'.
- **A stall reading and a tick conversion want different measures.** `PerTickServerClock.deficitTicksSince` is client
  ticks minus pings since a mark, which a catch-up burst pays back: right for turning a server-tick target into a client tick
  (Blood Camp's `killShownTick`). `stallTicksSince` counts every rise of that deficit past 2 ticks in full and keeps it after
  the burst (tickfix2-1104; until then it was the net, so Blood Camp Stats' camp lag and a pong's stall read ~0 after
  Hypixel caught up). Logic case 1104-logic-stall-burst.
- **GrimAC's transaction pings can pass the per-tick test for a few seconds** (shipH-rr-argrim-a-26.3.log: "is a per-tick
  pinger: 22 pings, 20 of 20" then "no longer" 3 s later). Grim's ids are `-(counter & 0x7FFF)`, non-positive and stepping
  down by one (javap `GrimPlayer.sendTransaction`, 2.3.74). Hypixel's ids are not known yet, so nothing filters by id;
  `ServerTickClock` logs "[ServerTickClock] first ping ids in this world: [...]" once a world, and his next Hypixel log
  says whether Grim's can be told apart. `ServerLag` follows the per-tick clock's trust (logic case 1104-logic-serverlag-grim).
- **Countdowns decremented inside a gated listener lose ticks.** Maxor Start, Goldor Start and Necron's drop were
  `x--` in the per-server-tick listener behind `DungeonState.isBossPhaseActive()`, so each tick the gate read false left
  them a tick behind for good. They keep their end tick now and are worked out from the clock every tick (tickfix-1104).
- **Real F7 gaps from his 2026-10-30 log (seconds resolution):** Storm's opening line to his lightning line 28 s twice
  (560 ticks fits, Odin's 520 does not); Storm's death line to Goldor's line 3-4 s (Odin's 64 fits, NoammAddons 1.2.9's 17
  does not; `GOLDOR_START_TICKS` is 64 since tickfix2-1104, see "Measured F7 gaps" below); the
  party's mask procs 3 and 6 s after Goldor's line, so his damage tick was taken as phased on the line. It is NOT at one
  fixed place after the line: his first mask proc re-locked the phase 42, 9, 2, 21 and 25 ticks (mod 60) after Goldor's
  line in five 2026-11-03/04 runs (26.3 log, "[TickTimers] Goldor tick re-locked by a mask proc (N ticks off ..., the first
  hit)"), on a clock whose ping count matched the wall time to 0.01 s over 62 s in the same runs. The start phase is a
  guess; the first hit is what places it. Whether Storm's death line places it is what the new log lines will tell.
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
- **Ten client ticks are not ten server ticks.** Testkit 186 on the run-3 jar: the Trigger Bot clicked exactly 10 client
  ticks apart, but after a client stall those 10 ran in 400 ms and the server saw 8- and 9-tick gaps (3 of 5 runs). The
  attack cap now also holds on `PerTickServerClock` when it is trusted at both clicks; the stall's cause was not found.
- **In the Blood Room Hypixel sends more than one ping per tick, so the ping clock runs ahead of game time** (bctrig-1104,
  his 2026-11-03/04 26.3 log, 25 camps). `PerTickServerClock` was trusted (sticky on Hypixel) and counted every ping (since tickfix3-1104 only Hypixel's per-tick id chain), and the
  Blood Room got 20-24 pings per 20 ticks: one camp read 417 clock ticks against 333 game ticks (`level.getGameTime()`,
  which matched the wall clock), a 14 s last wave read 16.45 s. Two faults came of it. The long-gap table (gap >= 18 s ->
  24-27 s) put KILL ~60 ticks after the move on fast Watchers ("the kill still comes up way too late"), and the gate opened
  early elsewhere. Measured: the first skull after the move left the wall 446-474 game ticks after the greeting whatever the
  gap (13.05-22.35 s), so the gap table is gone (the floor's time always, as NoammAddons 1.2.9), and `elapsedSinceGreeting`
  is the lesser of clock and game time. Testkit 1104-bloodcamp-gametime runs the ping clock 400 ticks ahead.
- **Never Miss waits for KILL** (killer560, 2026-11-04: "triggerbot still kills before the kill alert goes off"). It
  ignored the kill timer by design (61f5f083d), so walking in it hit mobs already on the floor (his 00:25:01 camp: a giant
  and a player-model mob 11 and 4 ticks before the gate). It still ignores Trigger Offset. Testkit 1104-bloodcamp-nevermiss-waits-kill.
- **Mob Ticks start at the first spawn after the kill timer, not at the timer** (killer560, same day): the mobs still on the
  floor when it goes off are not counted until a post-move trip's mob lands (`BloodCampStats` WAITING_SPAWN, `noteClaim` ->
  `onTripMobLanded`; a first-spawn trip never starts it). Testkit 1104-bloodcamp-stats-spawn.
- **A mob's claim names its trip, never a copy of the trip's landing spot** (uifail-1104): `endVector` is rebuilt on every
  move packet, so `CLAIMS` holding the vector seen at tagging made the trip's own mob read as another trip's a packet later
  and the floor path clicked it before the trip did (testkit 686 B, "first click was not the trip's"). `CLAIMS` keeps the
  `BloodMobState` plus its `startedAtTick` (restart reuses the object). An entity with no id yet goes through `idOrNone`.

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
- Stream Safe never showed in Termism (killer560, 2026-11-02): `TermismPracticeScreen` is a plain Screen with its OWN
  auto-click loop (Min/Max delays, no lookahead), and `FakeCursor` drew and hid the pointer only over
  `AbstractContainerScreen`. A feature added to the real terminal path does not reach Termism by itself; Termism now plays
  through `terminals/PracticeCursor` (the same hand, click order, settle and Melody skip rule) and implements
  `FakeCursor.Host`. Check Termism whenever Auto Terminals changes (testkit 1102-menu-termism-ss-*).
- Termism, like every mod screen, is laid out on Auto Scale's canvas and drawn under its factor: anything that aims or
  draws in window pixels over it multiplies canvas coordinates by `AutoScale.appliedFactor(screen)` (times the GUI scale)
  and draws from an identity pose. The first Stream Safe fix skipped that and put the cursor off the board on a scaled
  client while every probe agreed with itself (fixed ead35f86e; testkit's scaled pass at factor 1.125 checks it).
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
- Hypixel's 1.8 `random.click` reaches 26.x as `block.lever.click`, and it is also the dungeon start countdown's tick ("Starting in N seconds.", played at the player). Mute Lever Sounds dropped every one (killer560, 2026-11-04: "the sounds to the 5 4 3 2 1 ... dont come through"). A sound mute keyed on a vanilla id must also check its SOURCE: the chest/lever mutes now drop a sound only when a chest/lever block is where it played (testkit 1104-alerts-countdown-sound).
- A `DungeonLayout` room INDEX is only good for the snapshot it came from: `LiveMapFeature.rebuildGroups` numbers rooms by a HashMap walk, so a room joining the map renumbers the others. Wither Doors' Fairy Door kept Fairy's index for 10 ticks, and at run start (rooms arriving every tick) it named his Entrance, so "cleared: you entered Fairy" ended the highlight for the whole run (killer560 2026-11-04 "sometimes fairy door doesnt work"; logs 00:01:52, 00:05:34). Keep a cell (`tiles(r)[0]`) and map it back with `roomOfCell` each tick (fairydoor-1104, testkit 1104-sim-fairy-door-rooms-renumbered). THE RULE (roomidx-1104): never keep a room index (or a LiveMapFeature group id, the same numbering) past the tick, in a field, a set, a map key or a per-mob record, unless it is paired with the snapshot it came from. Keep a cell, or keep the snapshot and map everything over once when `layout.numbersLike(old)` is false (`sameRoom` / `sameRooms`, one reference compare a tick): Blood Rush Helper's next room, last room, rushed rooms, walked-in doors and every mob's room (a mob first seen at run start counted for another room, so its box never showed), and the sim's held-map opened rooms (`HeldMap.SIM_VISITED`) do that now; the map's double press keeps a cell (testkit 1104-sim-bloodrush-rooms-renumbered, 1104-sim-map-rooms-renumbered). A lambda that captures its own `layout` together with the index (Auto Clear's key-room walk) is safe.
- Hypixel sends a teleport's look ABSOLUTE (for a Spirit Leap: the leaped-to player's look), except a teleport within about 1 s of the one before (seen 221-997 ms), which comes RELATIVE 0/0 so you keep your look; a leap in that window does not take the target's look (his 26.3 log, 63 teleports, 2026-11-04). Fast Leap clicks without the leap menu ever becoming the screen, so detect an own leap by "You have teleported to Name!", not by the screen title at click time (Leap Takes Target's Look, fastleap/LeapRotation).
- Creeper Beams' opening pair and Secret Sound (dungsolve1108): a pick made before the creeper ENTITY has loaded used to fill `shownOrder` and lock the opening, so the creeper's pair never came first (it stays open until the creeper is found, 40 scans at most). Secret Sound kept ONE pending-chest timestamp, so a second chest inside the 500 ms wait played no sound, and `UseBlockCallback` never sees a click an earlier listener answered non-PASS; it now keeps a queue and also listens where the packet leaves (`RightClickSendMixin`, 50 ms same-position dedupe so callback plus packet is one click).
- Terminal Solver "last pane reappears and is not clicked again" (dungsolve1108, termlast1108, killer560 to-do Doc) is NOT reproduced on the hx server: testkit 1108-term-lastpane-{auto,stream,reopen,thrice,revert,revert-stream}-<type> cover all five non-Melody types (Rubix included) with the final click dropped once or three times, the board re-opened under a new container id, and the final pane answered then put back two ticks later; the base jar re-clicks and solves in all 30 (26.3 cheat). His real logs say the mod does re-click on Hypixel: on 2026-11-06 13:04 (Rubix, "Change all to same color!") slot 32 was clicked three times about 1 s apart and Hypixel answered none of them ("[AutoTerms] slot 32 still empty 930 ms after a click (server never answered)"); he closed the board at 13:04:17. Every such restore line in his logs is a Rubix click, the only type sent as a real PICKUP (the client predicts the pickup, so the pane vanishes, then the mod puts it back and it "reappears"); every other type sends CLONE and never shows it. Untested idea, needs his OK: send Rubix forward clicks as CLONE button 2 the way Athen does on Hypixel (ITerminalSolver.click: left = CLONE 2, right = PICKUP 1, no prediction), plus the same-tick Grim cases. Capture the log around the last click if it happens again.

- Auto Kick floor (pvkick-1109, killer560 11-09: joined an F5 party and Auto Kick used F7): Floor Source Detect kept the floor of HIS OWN last Party Finder queue (or the last run, F7) forever, so joining someone else's group (Hypixel: "You have joined [rank] Name's party!" or "Party Finder > <self> joined the dungeon group! (Class Level N)"; no chat line carries that group's floor) judged joiners on the stale floor. `AutoKickFeature.onLine` now calls `QueuedFloorDetector.reset()` and clears lastRunFloor on either line: the floor is that of the Party Finder listing he clicked within 10 s (PfJoinClickMixin on slotClicked, `QueuedFloorDetector.onSlotClick`, lore "Dungeon: The Catacombs" + "Floor: Floor V"); with no click (a /p join, an invite) no floor is known (joiners "no floor -> none", ModChat.log line) until he queues again. Testkit 1109-ui-autokick-joined-other-party.

- Party Tracker vs the dungeon tab (partyleft-1110): Hypixel keeps a player who left in the dungeon tab list until the run ends, and `readTabList` rebuilt MEMBERS from it every refresh, so "[MVP+] X has left the party." was undone within 10 ticks and Custom Scoreboard "Party (4)" kept X after the run. Names that left or were kicked go in `LEFT_NAMES` (bounded 64) and the tab read skips them; the set clears on a rejoin line, a new party, CLEARED and the end of the dungeon. Map pairing and the leap order read the tab directly, not MEMBERS. Testkit 1110-party-left-in-dungeon (menu session `200-menu-session:,1110-party-left`).
