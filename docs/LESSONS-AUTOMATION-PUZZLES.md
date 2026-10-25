# Auto Puzzles lessons

The puzzle autos at run time (camera, timing, leaving, each puzzle). Part of the automation lessons:
[LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md). The sim's side of each puzzle is in [SIM-PUZZLES.md](SIM-PUZZLES.md) and
[SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md). Problem, then fix; verified only.

## Camera and turns

- **A `ViewFreeze` lease taken per rotation is not a free camera.** It lapses 400 ms after the last hold, and a Blaze shot
  waits out the arrow's flight plus the cooldown, a reposition up to 2 s for the landing; each lapse showed the turned body
  and the next hold re-captured it, and Auto Blaze's every-tick room-center seed re-took every lapsed lease and threw his
  mouse-steered view back ("snaps my camera around everywhere"). Every auto puzzle that turns him holds an
  `autopuzzles/FreeCam` for the whole run (engage before the first aim, `keep` every tick, `release` turns the body under
  the view, shifting the hand sway by the same whole turns). 93-solve-*blaze's CameraWatch checks it every render frame.
- **`TurnFirst` reads an auto's OWN free camera as somebody else turning him, and waits 10 ticks.** Its `settle` holds a
  turn for up to `FOREIGN_WAIT` (10) ticks whenever `ViewFreeze` is held and it did not turn him itself, and `FreeCam` holds
  the view for the whole run, so every Auto Water click whose lever the reported look did not already meet went 11 ticks
  late (his Hypixel run 2026-10-15, "11 tick(s) late"; 93-solve-waterboard-timing). Auto Water turns its own body onto the
  next lever as soon as he stands where it is in reach. Since puzzles-nowait-1015 the puzzle autos' own `TurnFirst` takes a
  `FreeCam.anyEngaged()` hold as its own and never waits on it (1015-grim-puzzle-nowait: turned the tick after the landing,
  clicked the next, GrimAC clean); Secret Aura's `TurnFirst` still waits on it, so Tic Tac Toe hands its camera back before
  the aura's chest.
- **"Aimed" must mean the look the server HEARD, not the body's look this tick** (Auto Water, killer560 2026-10-18: "failed
  to flick a lever"). Two levers due as he landed were clicked the tick after the landing with the server still holding the
  warp's look; `prepare()` had turned the body earlier that tick, and `aimedAt`/`TurnFirst.readyBlock` read the body. The
  reward chest stayed locked. `AutoPuzzleUtil.gateWorldClick` (block and entity) and Auto Water's own check also require
  `RightClick.heardLook` to meet the target; the click goes the next tick. Both clicks were late (22 and 43 ticks) because
  the warps before them, sent a tick after a lever click, got no landing and `AutoReposition` waited its 40-tick timeout;
  why Hypixel dropped them is NOT found (the server ray met nothing, the same look landed 2 ticks after the resend). A use
  with no landing is sent again after twice the measured landing time plus 2 ticks (or `RightClick.eatenRetryTicks`,
  whichever is longer) plus any server stall, once per warp, while he has not moved. Testkit 1018-waterboard-lever and
  1018-waterboard-grim (the real auto on a pasted board, GrimAC).
- **A look aimed at a block's center can pass a neighbor's corner by a hair and miss in floats** (Auto Creeper Beams Melee).
  The exact eye-to-center ray met the lantern, the turned body's float yaw/pitch met the next block, and the auto turned
  every tick and never hit. `AutoBeams.visiblePoint` samples face points and keeps one only if looks 0.35 degrees either way
  meet the block too. Half the room's lanterns are set in walls, so center-only sight hid most spots.

## Timing and leaving

- **"Click, then warp" costs one tick, not seven.** Auto Water's warp after a lever went 7 ticks late: a 200 ms gate, QUOI's
  2-tick sneak wait, and `RightClick.mustTurnFirst`'s turn tick. `AutoReposition` in next-tick mode puts the sneak down AND
  turns the body in the tick it starts (that tick's input and movement packets carry both, after the click), and the use
  goes the next tick; on the landing it ends at once, re-waiving the gate's teleport settle (a Hypixel landing comes a round
  trip after the use, past the 3-tick waiver taken then). Clean on GrimAC (1015-grim-water-clickwarp).
- **Every bow puzzle's shot after a landing waited the gate's 6-tick teleport settle.** `AutoReposition` waived it for 3
  ticks at the USE, the sim lands 2 ticks after the use, and the landing starts its own 6-tick settle, so Blaze, Beams and
  Ice Path shot exactly 6 ticks after each landing (93-solve-*-timing). The landing re-waives the whole settle for bow warps
  too, and a trip a puzzle auto asks the Interactive Map for (`AutoPuzzleUtil.pathIfMapOn`) waives it for its landings.
  QUOI's 2-tick sneak wait and 2-tick wait after a landing are gone with the next-tick mode for every auto.
- **QUOI's `at(spot)` reads an etherwarp's landing tick as "not there yet".** He lands 0.05 over the block and gravity sets
  him on it the tick after; `ceil(y - 1)` is then the block above, so every reposition saw its own arrival a tick late.
  `AutoPuzzleUtil.at` accepts up to 0.08 over the top; a look aimed on that tick is aimed from the settled standing eye
  (`settledEye`), or a small target's edge is passed by once he drops 0.05.
- **An auto puzzle's last step must call `AutoPuzzleActivity.ended()`**, or Auto Secret, Autopilot and Auto Routes wait out
  the 40-tick grace (`AutoPuzzleActivity.isWorking` stays true 40 ticks after the last mark). What Auto Secret waits on after a puzzle is the activity spell, not the puzzle (killer560, 2026-10-16:
  "it registers them a lot later than it is actually done"): solve to resume was Creeper Beams 44, Three Weirdos 41, Quiz
  23, Tic Tac Toe 12 ticks (93-solve-*-resume); each now ends its spell on its own last step (the fourth pair, the right
  chest, Oruo's final-answer line, the game over): 3, 0, 0, 3. Boulder's finish and Blaze's done do too, and so must an auto
  that leaves its room as its last step (Auto Teleport Maze's end pad sends him out and the leave-reset never did: 2 s in
  the rerun, 64 s on one floor of acplan-1020, cause of the extra length not found). On Hypixel PUZZLE_WAIT (and Autopilot's
  `puzzleFinished`) also read the map or the tab list, a refresh behind; Hypixel's `PUZZLE SOLVED! <name> ... ! Good job!`
  line, anchored with a real name group, counts the tick it arrives (`AutoPuzzleActivity.solvedByHim`), and the sim sends it
  (`roomsim/SimPuzzleSolvedLine`; only Tic Tac Toe's wording, "tied Tic Tac Toe", is from a real log) and Oruo's
  final-answer line.
- **Leave a puzzle on its auto's `AutoPuzzleActivity.ended()`, not the map's check.** Hypixel's check for Higher Blaze came
  7 s after the auto ended. The sim sends its "PUZZLE SOLVED!" line with the solve, a tick before the auto ends, so testkit
  93-solve-higherblaze-leave leaves at 0 ticks on main too: it proves the fix leaves on the auto's own signal, not that main
  was slow. Holding the sim's check back from the test thread races the packet.
- `AutoPuzzleActivity`'s name was the LAST mark of a tick, and one tick of a chest step marks three ("Ice Path", "Ice Path
  chest", "Puzzles"), so every "say it once per change" reader said it every tick (Autopilot's "Waiting for ..." twenty
  times a second). The name holds for the working spell; Auto Secret says each reason once per room (102-sim-autosecret).
- **A fallback that hands over to another mode inherits that mode's preconditions** (Creeper Beams Melee). Melee ran out of
  on-foot pairs and handed the room to Shoot mode, whose first step is an etherwarp onto the center platform; from deep in
  the room there was no line onto it, AutoReposition refused once, and the auto sat there (1018-beams-melee-after). Melee
  reaches each lantern itself (spot, AOTV, shot).

## Chests

- **Nothing ever opened Water Board, Ice Path or Ice Fill's reward chest.** Secret Aura skips Ice Path and Ice Fill
  outright (`SKIP_ROOMS`) and in Water Board waits for the tab list or the map to say the puzzle is over. The autos open it
  themselves (`autopuzzles/RewardChest`), Secret Aura or not; testkit 93-solve-*-claim run with Secret Aura off.
- **A chest is not a block to etherwarp onto.** Tic Tac Toe's chest trip asked the Interactive Map for the chest itself
  ("is not etherwarpable ... Nothing searched") and the secret was never taken. Trips to a block go to a standable block
  that has it in reach (`AutoPuzzleUtil.reachSpots`), as Auto Blaze's already did.
- Sim Ice Fill's two reward chests sat on coordinates read off one capture; Mod Only Test's holds gray wool there, so no
  chest stood until it searched for the torch-lit pedestals by the iron bars.

## Per puzzle

- **Blaze: a miss waited a fixed "arrow flight + 4 ticks".** A volley that killed already ended the wait (the target's label
  stand removed), but a miss held the next volley the simulated flight plus 4 ticks (93-solve-blazemiss-*-timing: 5 to 6
  ticks after the arrows had ended; the chest after a missed final volley up to 16). `AutoBlaze.VolleyWatch` takes the
  volley's arrows as they appear by his eye and calls the result when none can still reach the target: removed by the
  server, stuck (`AbstractArrowInGroundAccessor`), or moving away from it (velocity against the direction to it - a
  Terminator's side arrows). A removed arrow waits for the server's next update unless the target's label already changed.
  The solver's list holds a blaze for a tick after its kill (it re-reads after the autos), so the order is filtered of
  removed entities - aiming at the dead one sends the volley through its spot at the next one up. Result: 0 or less in
  every volley; no kill out of order in 93 kills.
- **Blaze: "the list stayed empty 30 ticks" is not the only way to know the room is done.** When the list goes from one to
  none with the last target's stand gone and an arrow of the volley at it struck, Auto Blaze calls it done that tick (main:
  30-31 ticks after the last kill, then the grace). The volley's arrows must be picked up every tick of the wait, including
  under the secret trip and a warp, or the kill cannot be told from a chain rebuild.
- Auto Blaze started its own reposition while an Interactive Map trip it had asked for was still moving him; the warp never
  saw its landing and timed out 40 ticks later (blazemiss-lower). `cyclePosition` waits while `ClearExecutor.isBusy()`.
- **The sim's Terminator spawned its arrows from where the server player was when the spawn TASK ran, not when the use was
  answered**: an etherwarp sent the next tick was often handled first, and the volley flew from the landing with the old
  aim (93-solve-lowerblaze-speed: shots lost, a 170-warp ping-pong). Arrows start at the answered eye - 0.1, as vanilla's
  owner constructor does.
- **Tic Tac Toe: "no move for him" is not "the game is over".** Hypixel's computer answers a second or two after his click,
  and the board in between has no move for him either; Walk Out read that gap as finished and warped him out after the
  FIRST click, then walked him back in. The sim painted both marks in one server tick, so it never showed. The walk-out
  waits for `TicTacToeSolverFeature.isGameOver` (or the tab list), and the sim's computer replies
  `SimTicTacToePuzzle.REPLY_TICKS` later (an estimate: his real clicks came 3 s apart against the auto's 0.5 s gap).
- **The sim decided a Tic Tac Toe click on the CLIENT and took the square's button away in a server task queued before the
  click's own packet**, so the server saw air and fired an AOTV's Instant Transmission into the board (4.2 blocks,
  93-solve-tictactoe-grab). On Hypixel a board click never moves him; `SimAbilities.blockWins` lets a square win.
- **Boulder started at the doorway only on ClearExecutor's arrival counter**, which moves only when a map path ends within
  1.5 of its planned landing - never for his own etherwarp, nor a map trip that ended off its plan. Standing on the doorway
  spot (filed by the live map under the tile next door) it never started; walking in did. Any server teleport that leaves
  him there starts it (`ClearExecutor.positionPackets`); 93-solve-boulder-doorwarp (a real map press, -mapwarp, passes on
  both). Its walks also only sprinted on long straights.
- **Boulder: a path follower tuned at a vanilla sprint swings off the line at his sim speed** (killer560, 2026-10-16: "the
  boulder pathfinder broke going out"). At speed 600 a sprint covers ~1.7 blocks a tick; the 1.2-block pursuit carrot was
  under a tick ahead and each tick's turn an eased fraction of what was left, so the yaw swung -175, -192, -142, -246 on a
  straight 29-block roof run until "more than 1.6 off the segment" called it "pushed off the path"
  (93-solve-boulder-speed-aura: 4 of 4 runs, 2 gave up). Where open floor at his height reaches the way out, the exit is one
  straight run aimed at a point past the doorway (`AutoBoulder.runOut`). The button mode's walks at that speed slid past
  corners, fell off the one-wide stairs and pressed into their wall; letting go of W cannot brake them (one tick of W alone
  carries ~1.7 blocks), so a fast path walk is a sneaking walk (~0.4 a tick), corners are turned mostly standing, a stair
  point is only passed by standing on it, and the cell path is pulled tight first. A sprint-the-straights variant failed 2
  of 4 (a 2.5-block fall, a re-plan).
- **Three Weirdos: the auto never moved, and QUOI's reach is why it never had to** (killer560, 2026-10-15: "it cannot reach
  the right person"). QUOI clicked every CLICK stand within 5.48 feet to feet, which from the room's middle covers all
  three; ours is the measured 3.0 eye to box (an entity interaction), and from the Interactive Map's spot (relative 15, 68,
  22) the far weirdo is 3.26 off in every rotation (93-solve-weirdos-reach). `AutoWeirdos` moves onto the spot reaching the
  most weirdos left (then their chests) by its own next-tick etherwarp (a first hop, a walk or an Interactive Map trip when
  no line reaches one; from the doorway the weirdos are ~21 blocks off, past the 16-block scan it first had), and talks one
  a tick with the body turned onto the next in the same tick. Its "waiting" log alternated two reasons every tick (781
  lines in 20 s) because the talk and the solver each logged their own; one owner per tick now. The talked-to set follows
  the stands, not the RoomEntry: a rebuilt room or the sim's new round reuses the entry, and the second round found "all
  three talked to" and never spoke.
- **Quiz: a question can be asked while he is in another room** (Oruo's lines reach the whole dungeon), and Quiz Solver
  reset its answers on every room change, so a question asked while Auto Secret was doing another room was gone when he
  walked back in. While `QuizState.inProgress()` a room change only drops the pillar positions.
- **The Quiz starts when a player walks into the room, not on a click on Oruo** (killer560, 2026-10-18). His logs put the
  intro 0-1 s after his own room entry in all 47 runs where he walked in, so Auto Quiz no longer walks to a guessed "CLICK"
  stand to talk. The sim starts Oruo on the footprint, and Oruo's intro can reach the client a tick or two BEFORE the live
  map names the room, so anything pairing the two must allow either order ([QuizLog] does).
