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

- **"Click, then warp" costs one tick, not seven.** Auto Water's warp after a lever went 7 ticks late: a 200 ms gate, the
  2-tick sneak wait, and `RightClick.mustTurnFirst`'s turn tick. `AutoReposition` in next-tick mode puts the sneak down AND
  turns the body in the tick it starts (that tick's input and movement packets carry both, after the click), and the use
  goes the next tick; on the landing it ends at once, re-waiving the gate's teleport settle (a Hypixel landing comes a round
  trip after the use, past the 3-tick waiver taken then). Clean on GrimAC (1015-grim-water-clickwarp).
- **Every bow puzzle's shot after a landing waited the gate's 6-tick teleport settle.** `AutoReposition` waived it for 3
  ticks at the USE, the sim lands 2 ticks after the use, and the landing starts its own 6-tick settle, so Blaze, Beams and
  Ice Path shot exactly 6 ticks after each landing (93-solve-*-timing). The landing re-waives the whole settle for bow warps
  too, and a trip a puzzle auto asks the Interactive Map for (`AutoPuzzleUtil.pathIfMapOn`) waives it for its landings.
  the 2-tick sneak wait and 2-tick wait after a landing are gone with the next-tick mode for every auto.
- **the `at(spot)` reads an etherwarp's landing tick as "not there yet".** He lands 0.05 over the block and gravity sets
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
- **A teleport has landed when the server sends a position, wherever it put him** (Creeper Beams Melee, creeperbeams-1118).
  Hypixel cuts an AOTV short with "There are blocks in the way!" (the line first, then the position, then tp_accept) at
  spots our dash model does not predict; the auto took only a landing within 1 block of the plan, so it let him fall and
  waited 20 ticks each time (134 of his 296 ticks on 11-18). Count server positions (`AutoPuzzleUtil.serverPositions`, fed
  by PuzzlePacketMixin) and judge reach from where he actually is. Hide Useless Messages had also dropped that line in
  ALLOW_GAME, so the refusal reader never ran: it is hidden in `ChatTidy.shouldHide` now. A dropped etherwarp used while
  falling is resent too (same x/z, no rise, no server position since the use). Testkit 1118-beams-melee-replay replays his
  room with `SimAbilities.setTestDashShort`.
- **A puzzle auto's dig waits a tick when his right click goes in that tick** (Creeper Beams Melee, creeperbeams-1118): a
  dig before his use_item in one tick is experimental GrimAC PacketOrderI (`type=place/use, digging=true`); `meleeClick`
  checks `ActionGate.hisUseGoesThisTick` like Breaker Aura. A same-tick test must press his key BEFORE calling the auto in
  START_CLIENT_TICK (his input lands between frames); pressing it after hid this flag from the guard and the test alike.
  Testkit 1018-beams-melee-grim rounds 7-10 (use, attack, swap, move) check the wire.

## Chests

- **Nothing ever opened Water Board, Ice Path or Ice Fill's reward chest.** Secret Aura skips Ice Path and Ice Fill
  outright (`SKIP_ROOMS`) and in Water Board waits for the tab list or the map to say the puzzle is over. The autos open it
  themselves (`autopuzzles/RewardChest`), Secret Aura or not; testkit 93-solve-*-claim run with Secret Aura off.
- **A chest is not a block to etherwarp onto.** Tic Tac Toe's chest trip asked the Interactive Map for the chest itself
  ("is not etherwarpable ... Nothing searched") and the secret was never taken. Trips to a block go to a standable block
  that has it in reach (`AutoPuzzleUtil.reachSpots`), as Auto Blaze's already did.
- Sim Ice Fill's two reward chests sat on coordinates read off one capture; Mod Only Test's holds gray wool there, so no
  chest stood until it searched for the torch-lit pedestals by the iron bars.
- **A held container close must die when the server opens a window** (termclose-1115, killer560 2026-11-15: "Why is it
  closing terminals sometimes", 7 of 66 terminals shut the second they opened). `util/StillHold` holds every
  `ServerboundContainerClosePacket` while the server has him moving, sprinting or sneaking, and sent it later with no
  check. Fast Leap's hidden-menu close went out mid-sprint, was held, and was released the tick the terminal screen
  released his keys; Hypixel closes whatever window is open on any close, so the terminal shut. Five of the seven had his
  own Fast Leap 2-5 s before. `StillHold.onServerOpen()` (render thread, HEAD of handleOpenScreen via AutoCloseChestMixin)
  now drops every held close. Testkit 1115-termclose-stillhold (boss session) fails on 03e527b54.
  That was not enough (termclose-1118, his 11-18 runs: 3 of 125 terminals and several /stats menus still shut): the held
  close often left as he STOPPED, before the terminal's open packet was handled, and the server had already closed that
  leap window itself after the teleport (97 of 99 StillHold releases that day). Hypixel answers a close for a window it
  already shut with a close of its own, and vanilla `handleContainerClose` shuts whatever screen is open, whatever the id.
  `StillHold.onServerClose(id)` (HEAD of handleContainerClose, InventoryWalkCloseMixin) drops a held close for the window
  the server just closed. Testkit 1118-termclose-serverclosed (boss session) fails on dbceac2df.

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
- **Three Weirdos: the auto never moved, and the reach is why it never had to** (killer560, 2026-10-15: "it cannot reach
  the right person"). The reference clicked every CLICK stand within 5.48 feet to feet, which from the room's middle covers all
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

## Terminals

- **Burst mode (termburstonly-1116).** `checkPendingClicks` used to drop any pending click whose slot was no longer
  highlighted, and Numbers highlights only the next 2-3 panes, so a whole-board Numbers burst lost track of its later clicks
  at once and the next burst re-sent them; a Numbers pending click now stays up to 1500 ms while its slot is unchanged, and
  Numbers bursts again only once every answer is in. A Rubix pane clicked n times in one burst is ONE pending entry with
  `left = n`, counted down on each slot change, so a later burst never clicks it again before all n answers land. Burst
  mode is gated before Stream Safe in `tickAutoClick`, and Stream Safe's hand then applies to Melody only (`streamSafeHand`).
  Bursts keep at least 75 ms apart even at Delay 0 (`MIN_BURST_GAP_MS`, termburstgap-1117): under load the client runs catch-up ticks 6-25 ms
  apart and two bursts landed in one server tick (Panes at 5 arrived as 9); even 40 ms and 50 ms floors still let a late server tick take two
  bursts (Panes at 1: 15 bursts on 14 server ticks). The proof is server-side, not a wall-clock gap: testkit 1116-term-bmode-* and
  1117-term-bmode-catchup-* deal the server's container.click events (stamped with the server tick) to the wire bursts and fail if any
  server tick carries clicks of two bursts.
  Test notes: Block Input While Auto Clicking (default ON) swallows his real screen clicks, so a same-tick screen-click
  case turns it off; a test attack must go at START_CLIENT_TICK like vanilla's, or it lands after the move and GrimAC Post
  flags the TEST. Whole-terminal bursts (size over 5) are clean on Grim but unproven on Hypixel.
- **Burst Finish (termburst-1111).** Up to 5 container clicks in one client tick, all under one `ActionGate` grant, are
  clean on GrimAC default and experimental for every terminal type, including a Rubix pane clicked twice in the same tick
  and his own click plus a hotbar swap in that tick (testkit 1111-menu-term-burst-*). The burst waits only for the server
  to answer the clicks already sent (no `pendingClicks` entry younger than the retry timeout), not for the per-click delay;
  a dropped click stays pending and goes again in the next burst. Numbers counts its red panes from the items, since
  `currentHighlights` holds only the next two or three. Any container click sent while movement input is held draws
  MultiActionsC on DEFAULT Grim and the server drops the click, burst or not; with a screen open he moves only through
  Inventory Walk, whose Stop To Click holds clicks until he stands still.
- The loaded gate below tracks ONLY the window its open packet named (clickorder-1116): it first followed any container id, so a
  window-0 inventory packet between the board's updates reset it, the board read "not loaded" until 3 s after the open, the
  highlights froze on pane 1 and Auto Terminals re-clicked pane 1 ("Wrong number!", Click in Order, his 11-16 log). The
  testkit hx terminal option `invSync` sends that window-0 update before every board change (1116-term-invsync-*).
- Terminal loaded gate, stall net and Burst Size (termplus-1112). A board counts as loaded only once every window slot has
  been described: `TerminalBoardLoadMixin` (TAIL of handleOpenScreen / handleContainerContent / handleContainerSetSlot)
  keeps a BitSet per container id; an open or contents packet that carries any item marks every slot, an all-empty one marks
  none, a slot packet marks its slot. A container no packet was seen for, or one still incomplete after 3 s, passes. The stall
  net (`stallNet`, after the First Click Delay gate) fires when 2.5 s pass with no click sent while clicks remain and drops
  `nextAutoClickAllowedAtMs`, `pendingClicks`, `predictedSlots`, the sticky slot and Melody's scheduled clicks. Burst Size 1-21 was
  swept at 5, 8, 10, 13, 16 and 21 on a 24-target Select board, with the player's use, swing, hotbar swap and container click in
  the burst tick, on default and experimental Grim: the wire shows exactly Burst Size clicks in one client tick (+1 with his own
  click) and the server answers every one. Testkit: hx `parts`/`partGap` open an EMPTY window and send the slots in parts
  (1112-term-parts fails on the termburst jar: a click at tick 275 before the last part at 301), `dropClicks` drops a reply
  (1112-term-stall-*; the case wedges the pacing so only the net can recover), 1112-term-burst-size-N[-sametick].
- Terminal "thought it was done" (termdone-1112, owner on real Hypixel F7, 2026-11-12: closed and reopened one): his 6 MB log at 13:42-13:54 holds NO stuck terminal (every terminal solved in 0.9-2.3 s, no "[AutoTerms]" warning, no "settled with nothing" line), so the cause is NOT proven. Ruled out by reading: predicted-pickup latch (only Rubix PICKUPs predict; restored by `resyncPredictedSlots`), stale Rubix target (reset in `beginTerminal`), `stickySlot`/`pendingClicks` (time out), `foreignClickFailed` (logs ERROR). Open suspects: a natively glinting board item (Starts With / Select skip every `hasFoil` item except Golden Apple) and a Hypixel window that never fills. `noteEmptySolve` now also fires when a stabilized board stays empty 2.5 s while open, with a board dump (item, count, foil, name): grep "[AutoTerms]" "nothing to click" in the next report and fix from that dump.
- **A mod chat line in flight as a terminal opens gets the terminal closed** (termclose-1111, killer560 2026-11-11: "something
  keeps closing my terminals"). His 26.3 logs of 11-11 (killer560s-mod-logs/termclose-1111-scan.py, -flight.py): 75 real F7
  terminal opens; every one Hypixel closed unsolved had one to four of our /pc lines (Leaping To, Cringe, Posmsg) sent and
  not yet echoed at the open, while all 41 opens with none in flight were solved. chatnow-1112 (2026-11-12) replaced the
  near/click/settle holds with his rule "nothing goes off in a terminal": `util/OutgoingChat` holds every mod-originated line
  while a terminal GUI is open (`HudVisibility.isTerminalMenu`) and sends it on the first tick none is open (dropped if over
  30 s old); his own commands never wait. The log says `[OutgoingChat] holding ...`. Testkit 1112-hx-chatnow-terminal.
- **Hypixel's terminal click limit (friendterms-1119).** A terminal takes at most 5 clicks in 10 server ticks and drops the
  rest with no answer (measured; `bosssim/BossTerminals.onClick` does the same). A friend's Min Delay 90 ms with Burst Finish
  lost 12 clicks on 4 sim boards (each a 600 ms retry; 3 Panes re-sends) while the owner's 100-130 ms stayed just under it.
  `TerminalSolverFeature.clickBudget` keeps the last 5 click times (550 ms window: 10 ticks plus one of jitter): paced, Stream
  Safe, Hover, Hold and Queue clicks wait for room and Burst Finish waits until its burst (up to 5) fits; Burst Mode and a
  Burst Size over 5 are the owner's and stay unpaced. Testkit 1119-boss-term-click-limit (`500-boss-session:,` prefix).
- **Sim terminals must send slot packets, as Hypixel does (friendterms2-1119).** Auto Terminals clicks through
  `screen.slotClicked`, so every other mod's slotClicked hook runs first. Odin's Terminal Solver (odtheking/Odin
  `AbstractContainerScreenMixin` HEAD of slotClicked, `TerminalSolver` GuiEvent.SlotClick) cancels EVERY click while a terminal
  is open and re-sends only the one its own solution allows; that solution is built only in `AbstractContainerMenu.setItem`
  (a slot packet), which a whole-window contents packet never calls (`initializeContents` uses `Slot.set`, javap 26.3).
  Hypixel opens an empty window and sends each slot as its own packet, so it works there; the sim sent contents packets only
  (`openMenu`, `broadcastFullState`), Odin's solution stayed empty and a friend's sim Numbers never got one click through.
  `BossTerminals.Menu.sendSlots` now sends every window slot as a slot packet after the open and after each click. A sim screen
  that another mod must read as Hypixel's describes its slots the same way. Testkit 1119-boss-term-friend-numbers.
  Why ONLY Click in Order failed for him (orderonly-1119, reproduced with test-side stand-ins of both filters, testkit
  `ForeignTermFilter`): Odin's filter would have blocked every type but Melody (Melody's redraw is the one sim path that
  sent slot packets), so his Odin Terminal Solver was off ("New terminal: NUMBERS" is Odin's TerminalUtils, which logs with
  the solver off). NoFrills' Terminal Solvers has a switch per type, all off by default; with only In Order on, it cancels
  every click on a "Click in order!" board while its slot-packet-built solution is missing, and leaves the other types
  alone. On the pre-fix sim that gave exactly his report (Numbers 0 clicks through, the rest solved); with `sendSlots`
  every type solves under both filters. Testkit 1119-boss-term-odin-filter, -nofrills-order, -nofrills-all.
- **A click another mod swallows must not wait for an answer (zeeks-simterm-1120).** After the slot-packet fix the friend's
  sim Rubix still sat 4 s unsolved (his 11-20 report). NoFrills' Colors solver cancels a click for 10 ping-ticks after the
  open, for 1 tick after a click it passed (so 4 of a 5-click burst), and on a pane at the board's most common color; each
  cancel left Auto Terminals waiting the 600 ms retry timeout for a click that never went out (stand-in: Rubix 3.6-6.0 s, 11-15
  cancels), and its sticky pane (a Rubix pane needing 2) could sit at the most common color and be refused for good. Now
  `TerminalClickSentMixin` counts `MultiPlayerGameMode.handleContainerInput` calls: a slotClicked that reaches none was
  swallowed, the caller treats it as never sent (next try after the normal delay, not counted in the 5-in-10-ticks budget),
  and the paced clicker skips that slot for 300 ms when another is free (Rubix 1.7-2.0 s, no stall). Odin's own re-send goes
  through handleContainerInput, so it counts as sent. The sim's terminal menus answered every click with a full contents
  packet (all slots); Hypixel sends only the changed slots, and Odin's Starts With drops every slot a packet names after a
  click attempt, so the full re-send stalled Starts With once clicks stopped waiting. `BossTerminals.Menu` now answers through
  vanilla's `broadcastChanges` only (`broadcastFullState` is routed there too). That solver also never moves a Rubix pane at
  the board's most common color, so a cheapest target that is not the most common color could stall for good (lane
  1119-boss-term-nofrills-all, 123 cancels): a refusal no timing gate explains (600 ms after the open, 150 ms after the last
  click out) on a pane whose color outnumbers the target switches the target to that color (`noteRefused`); Odin keeps our
  target, so the timing guard keeps its first-click protection from switching it. `List.of(...).indexOf(null)` throws: the
  first version crashed the client on an empty slot. Testkit 1120-boss-term-zeeks-seq(-nf, -odin): twelve boards one after
  another, each but Melody solved within 3.0 s (the stand-in's gates alone need 2.0-2.5 s for a 10-click Rubix); cheat only.
- **Auto Ice Fill on Hypixel (icefillbugs-1118, his 2026-11-18 run, table killer560s-mod-logs/icefillbugs-1118.md).** Hypixel
  judges where he stands ONCE PER SERVER TICK: two hops handled in one of its ticks are one two-tile move ("Don't move
  diagonally! Bad!", pos ids 11 and 12 in one tick). With Delay 1 / Adaptive off the old auto hopped every tick whatever he
  stood on, so any server stall bunched hops and broke the section; a hop now goes only from the point he stands on, after
  the last one LANDED (the stair step too), and the delay counts from the landing. The sim judged every landing and passed
  that run; it now judges once per server tick and fails a same-level diagonal (`SimIceFillPuzzle.judgeNow` is the
  testkit's immediate judge). Hypixel's etherwarp eye varies: the same look from the same spot landed on the start tile
  and, 4 s later, on its neighbor (fits 1.27 vs 1.54), so a recovery warp takes a look that lands on its block from 1.27,
  1.54 and 1.62 (`AutoIceFill.firmAim`; else the stair step, else the old aim) and a landing on another block ends the warp
  at once instead of after the 40-tick arrival wait. A point counts as stood on up to 0.06 over it (an etherwarp landing
  is 0.05 over), or the recovery fired a second straight-down warp. A give-up releases the free camera. Testkit
  93-solve-icefill-1118-bunched/-recovery/-sweep, 1118-same-icefill-* (Grim).
- Tic Tac Toe walkout (killer560, 2026-11-20: "you dont need ttt walkout since you can etherwarp inside of the ttt room"): the Walk Out When Done setting is gone for good; never bring a walkout back. 93-solve-tictactoe-resume failed once at 18 ticks (bound 5) in a loaded run and passed alone on this branch jar (remerge3a-1120): a load flake, so the bound stays.

- Puzzle autos under Auto Clear (acpuzzles-1121, measured 11-21 on 26.3 cheat). Ice Fill: a hop whose server position
  packet arrives after the 3-tick teleport waiver ran out sets the gate's 1-tick settle and denies the next hop, so a
  late landing cost about 3 ticks; the landing wait now renews `ActionGate.expectSelfTeleport(PUZZLE_WORLD, 2)` each
  tick. With 150 ms server stalls on every hop the case 1121-sim-acpuzzles-icefill solves in 462 ticks on origin and
  384 on this change (bound 420); with no stalls the sim lands a hop in 1 tick and both are the same, so the gain is
  only under real latency. Teleport Maze: an etherwarp onto the pad never landed in the sim (no landing 11 ticks after
  the use, then a timed-out reposition, then the walk anyway), about 50 ticks per hop, so that try was reverted and the
  walk stays at 186 ticks; mazes differ per run, so tick counts between two runs of that case are noisy. Ice Path
  (229-251 ticks in 93-solve-icepath*): the ticks are the silverfish slide (8 blocks about 12 ticks, 16 blocks about 27,
  one 16-block slide 61) plus about 4 per stop (2-tick etherwarp landing, the solver's rest check, one swap tick) and
  about 7 at the start; the shot goes the tick the solver reports rest, so no timer is left to remove. Beams and Lower
  Blaze are slide or latency paced the same way; no removable timer was found.
- Teleport Maze and Ice Fill ticks from his real run (acpuzzles2-1121, untested-latest-1121.log 11:07 and 11:09). The
  maze etherwarp did not land because the sim refuses every ability but Superboom in the maze (`SimAbilities.trapLocked`,
  his rule of 2026-10-01, Hypixel's "You cannot use abilities in this room!"), so walking is the only legal move. His 186
  ticks: 34 walking 14 blocks to the start pad, six hops of 13-19 ticks (5-7 blocks each; the next pad is picked the tick
  a teleport lands, no fixed wait; the count is exploring random pad links), 47 finishing (chest click about 10, end pad,
  walk out). The walk held forward only, at walking pace (his toggleSprint is off), so every maze leg now holds sprint
  with forward (`AutoTeleportMaze.forward`, discrete keys, as Auto Boulder): about 30% off the ~140 walking ticks. Testkit
  1121-sim-acpuzzles-teleportmaze fails unless most walking ticks sprint. Ice Fill's 239 ticks were 75 AOTV hops (223
  ticks: 26 at 2 ticks, 39 at 3, 9 at 5-7) plus about 13 claiming the two chests; it hands back the tick it is done. The
  "9 s walk with no use" was the landing-wait lines read as walking: no leg walked. A hop goes only after the last landed
  (icefillbugs-1118), so it is bound by the round trip (about 2.5 ticks a hop at his ping); the ~30 ticks of the ten
  "held back by the action gate" hops are the late-landing denials acpuzzles-1121's waiver renewal removes. The Quiz
  waited behind Ice Fill because Auto Clear's route finished Ice Fill first: that is the Auto Clear core, not a puzzle auto.
- Testkit 1118-same-icefill-move sent 0 hops whenever it ran right after the other same-tick Ice Fill cases (on the
  pre-waiver origin jar too, acpuzzles2-1121): each lap's tp starts ActionGate's 6-tick teleport settle and the case's hook
  retries only from the tile's middle, which the held walk leaves in one tick. A test that drives a feature from an exact
  spot while keys are held waits out the settle (and the item's slot update) before pressing them (tk 44741fb91).
- Auto Melody's Harp timing (harpmiss-1121): a note shows quartz in the click row for one step (250 ms in the testkit), so every tick before the click counts. Waiting a settle tick after EVERY step (to dodge a step whose click row arrives a tick after its rows) put the shipped jar's latest click 193-241 ms into the step and missed notes under load; the click row is now decided at once when its quartz equals the last board's row-3 wool, and only a mismatch waits. Early (row-ahead) clicks still wait the tick: TICK_LAG_MS was measured with it, and without it the 200 ms ping cases clicked a step too soon. HxHarp click events carry "ms" into their step to see the margin.
