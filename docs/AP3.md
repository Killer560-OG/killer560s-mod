# AP3 - quirks and lessons

Route nodes, the align planners and their measured physics. Split out of the project `CLAUDE.md` on
2026-09-29 when it passed 300 lines.

- Interaction features must tick on `ClientTickEvents.START_CLIENT_TICK`, not `END_CLIENT_TICK`: END runs
  after the player's own movement packet, and GrimAC flags every resulting interaction as `Post`. Measured
  2026-09-27 — Breaker Aura drew 808 violations on END and zero on START; Secret Triggerbot 17 and 17.
  Fixed for Breaker Aura in `825f319`. Audited properly 2026-09-29: of 111 END registrations, exactly
  **three** reach a block/item/container packet — `Ap3Feature:95`, `AutoRoutesFeature:87` (moved to START
  2026-10-04), `FastLeapFeature:105`
  — and about 17 more send only chat or a server command. The "about twenty" figure counted those. Separately,
  three features click from a RENDER FRAME, which is also after the movement packet: Goldor Triggerbot, Arrow
  Align and Auto I4. `ActionGate` does not help — `tryAct` returns immediately and the caller sends
  synchronously, so it arbitrates who acts and never changes ordering.
- An automated click must aim at a point on the block's real **surface**, from the eye, not at
  `Vec3.atCenterOf(pos)` with a fixed `Direction`. The center is a point *inside* the block and no raycast
  produces it; GrimAC raised `PositionPlace` on every such click even at a distance the server accepted
  (2026-09-28). Use `util/BlockHits.surface`, and prefer skipping a tick to sending an impossible hit. Entity
  clicks are the same: aim at a point on the entity's box, which Arrow Align and Terminal Aura already do.
- AP3's align planners solve the YAW freely, so two entries in an action set differ only by the SIZE of the
  push and what they leave for the next tick (sprint, crouch) - a key pointing elsewhere is the same action at
  another yaw. All eighteen real key combinations produce just five sizes: 0, 0.13377 (non-sprinting straight
  key), 0.13650 (non-sprinting diagonal), 0.17390 (W) and 0.17745 (W+A), and only `fw > 0` sprints. Searching a
  near-duplicate costs |ACTS| to the power of the press count for nothing.
- **Align nodes are designed for 550-600 speed** on the Hypixel scale (killer560, 2026-09-28: "they should
  still align at lower speeds but the time isn't important"). So tune and benchmark at 550-600, and treat low
  speed as a CORRECTNESS check only - it must still land, it may take as long as it likes. This matters because
  every push the planner prices comes off the movement-speed attribute: at 550-600 Fast Align lands 100% of
  cases in 3 or 4 ticks, while at 100-450 it lands 89% with a tail out to 7. A constant tuned at walking pace
  is not tuned. The sim can be set to any Hypixel speed with `TestMap.speed(550)`.
- Align tick counts are bound by STOPPING, not by travel or by the solver. You must arrive under vanilla's 0.003
  zeroing line or the next tick slides you off the point, and friction alone takes ~8 ticks from top speed. A
  floor that charges the stop sits at 4.41 ticks against the planner's 4.52 (measured 2026-09-28), and 3 ticks
  is impossible for 72% of aligns at any tolerance. Tolerance is nearly free: Caleb's 3e-8 costs 0.09 of a tick
  over 1e-4. Do not accept a "make the align faster" task without re-deriving that floor first.
- **A node's angle was being quantized on every save.** `Ap3Store.writeNode` wrote yaw and pitch through
  `round(v, 1)`, so a restart moved every node's angle to the nearest tenth of a degree and the loss compounded
  (killer560, 2026-09-29: "it feels like the angles and stuff gets truncated whenever I close and restart").
  `Ap3EditScreen.fmt` did the same at two decimals, and it is read back on Save, so opening an editor and
  saving damaged the node - as did "Look from me". Store at 5-6 decimals, and print fields at full precision
  with the zeros trimmed.
- `Ap3Store` wrote `useItemId` inside `case LEAP`, so a USE node's recorded item was NEVER saved while the load
  path read it unconditionally. It worked until the game closed and then became "use whatever is in my hand".
  A field shared by several node types belongs outside the type switch.
- A step machine whose every phase sets the next step and RETURNS costs one client tick per phase whether or
  not it had anything to wait for. AP3's USE node spent four ticks before the click, six or seven with a swap
  ("my use item nodes come out like half a second late"). Only two waits are real: the server must see the new
  rotation before the use, and it must have acknowledged a hotbar change. Let the rest fall through in one tick.
- `Ap3Node.copy()` does NOT copy `useItemId` or any Path field (`pathIndex`, start/end, speed window, dir, term).
  It is fine for `replaceNode`'s position probe, but anything that must restore a node whole (undo/redo) uses
  `Ap3Node.snapshot()` / `copyFrom()`, added 2026-10-04 with every field.
- **Anything that turns the camera must turn the VIEW while a view freeze is up.** With the defaults (Camera Planner +
  Freeze View) a held walk freezes the view and `applyHoldRealYaw` snaps the REAL yaw onto the walk every tick, so a
  LOOK that set the real yaw during a run was invisible: testkit 63-ap3-look-walk on main 59412f59 read the view at
  yaw -90 (the walk's) with only the pitch moved. `doLook` now turns `viewYaw`/`viewPitch` in that case, and `tickView`
  holds the freeze for any aim (a USE's aim was glided back under the view before its movement packet went out).
- **A USE must not let go of its aim before the use goes out.** `endAim` ran before `useItem`, so the use packet
  carried the pitch he had before the node (63-ap3-use-walk: node -35, packet 0). GrimAC's BadPacketsJ compares a
  use's yaw/pitch with the NEXT movement packet's rotation (javap, common-2.3.74), so the order that passes is: aim at
  END t, use at START t+1 (`Ap3Executor.tickStart`), aim released at END t+1. AP3 still ticks on END; USE, BLOCK and
  BOOM send from START (BLOCK / BOOM until 2026-10-06 sent at END with a hand-made slot packet: GrimAC Post on held item
  change, block placement and digging, plus BadPacketsA). The old one-tick-early pre-aim is gone - it only existed to
  place at END of the entry tick, which is the Post position. A Block's swap back is just `setSelectedSlot`; the game
  mode's own tick sends it at the next START, once.
- A node's yaw and pitch are FLOATS, so the file's sixth decimal is past what they hold for any angle above ~10 degrees:
  `33.123456` loads as 33.12345504... and is written back `33.123455` (2026-10-06, testkit 387). The angle the game uses
  does not change, so compare angles as floats, never as the file's decimal text.
- Since 2026-10-06 a type saves only what it reads (`Ap3Node.Type.usesYaw/usesPitch/usesItem/usesName`); yaw also laid the
  trigger box out (`boxYaw`), so `clearUnusedFields` swaps width and length for an east/west yaw when it drops the yaw.
  The route planner had always read a Path box as X = width, Z = length whatever its yaw, so a non-square Path box at an
  east/west yaw was planned 90 degrees off its drawn box until then.
- The route planner runs headless: `Ap3RoutePlanner`, `Ap3RouteMath`, `Ap3RouteCollide`, `Ap3DiscretePlanner` and
  `Ap3AlignMath` import nothing from Minecraft and compile with plain `javac`. Only the world snapshot
  (`Ap3RouteRunner.Snap`) needs a `ClientLevel`; a port of it over a room capture's blocks (capture index = route-relative
  + margin 1 for New Trap, captured unturned) replayed the New Trap path pair on 2026-10-20: 15 ticks at speed 400 and 600,
  and a tick-by-tick search of the same model found no walk in 14 (and found 15 when allowed, the control).
- **A turn-first aura moves an AP3 walk along ITS yaw.** `util/TurnFirst` turns the body at START, AP3 picks a held walk's
  keys at the END before, and the move uses the turned yaw: killer560's "nodes 16/17 make me run backwards" (2026-10-26,
  Mod Only Test log 21:00:52 and 21:02:22-30) was Lights Device pre-flicking levers 58,133,142 beside those nodes - the
  measured push pointed at about yaw -30, the lever's yaw (~-75) plus W+D's 45. AP3 then snapped the body back at END, so
  the aura turned it again every tick and only flicked once AP3 stopped. Now: an align or an aimed Boom / Use / Block /
  Look registers as the body's owner (`TurnFirst.registerBodyOwner`) and auras wait; a held walk leaves an aura's turn
  alone and re-picks its keys at the live yaw in the input hook (`Ap3Executor.beforeInputInstall`). The same aura turns
  mid-align were what walked him off the ledge at align #13 (21:09:35, 21:43:23, flicks interleaved with the align trace).
- **Ledge guard (aligns).** Each align press's predicted position (the planner's own one-step model) needs a collision box
  within 0.5 under the footprint; if not, Sneak goes down with the same keys and vanilla's `maybeBackOffFromEdge` (it
  reads `isShiftKeyDown`, i.e. this tick's input, javap 26.1.2) clips the move at the edge. The edge clip stops the move,
  not the velocity, so a point past the drop settles by holding sneak with no keys until the speed is gone.
- **A Boom aims at its block, not at its angle.** Its block is what the node's angle meets from where the node was made
  (point + eye height); from the entry eye (box edge at sprint, or up to 1.5 above while falling in - `BOX_Y_TOLERANCE`)
  the same angle can meet nothing, which failed the node on the spot ("boom #31 isn't looking at a block", five times in
  his 2026-10-26 log). It now turns to the block from wherever he is, waits up to 20 ticks for it to come into reach,
  and re-aims instead of failing when the look at START meets nothing. A LOOK waits while a boom aims.
- **Float angles print through `Float.toString`.** Widening a float yaw to double and printing six decimals turned
  167.9 into 167.899994 (and wrote it to the file). `Ap3Node.savedAngle` gives the shortest decimal that reads back as the
  same float; files holding the noisy value load as the same float and save clean.
- **leapcounter: is a suffix and a gate.** The node is taken off the queue when its turn comes and held (`leapGate`);
  meanwhile nothing fires on entry and only aligns begin; on the count it goes back to the front of the queue. The old
  LEAP_COUNTER type stays in the enum only so old files and the old command load: both become a Stop with the suffix.
  The testkit's fake players have version-3 UUIDs, which the counter skips as NPCs; `setLeapCountAnyPlayerForTest` lifts
  that for 1026-ap3-leapgate.
- **The ledge guard measures from the align's start, inclusive, and does not rely on sneak.** The first cut (2026-10-26)
  probed (feet-0.5, feet) per tick and held Sneak when it found nothing; vanilla's edge back-off
  (`Player.maybeBackOffFromEdge` / `canFallAtLeast`, javap 26.1.2) only clips a drop of `maxUpStep` = 0.6 or more, so a
  half step 0.5 down was "no floor" to the guard and "ground" to vanilla - the move was not clipped (his 23:15:14 and
  23:17:06 Fast Align #34: the sneak-held move landed exactly on the unclipped prediction, then he was airborne and slid
  off the far side of the step, 3 blocks down). Now every predicted position for this press and the coast after it
  (`LEDGE_LOOKAHEAD`) must have a collision box reaching up to at least start - 0.5 (bottom inclusive); a press that
  fails is replaced by no keys + sneak, then the key against his velocity (+ sneak, then without), so a staircase of half
  steps cannot add up past 0.5.
- **Align planning has a wall-clock budget.** Off the ground nothing lands, so Fast Align's solve walks its whole schedule
  space and the coarse look-ahead coasts every leaf: a headless timing of the planner on his 23:15 airborne state took
  61-78 ms a tick against 4-7 ms on the ground (killer560s-mod-logs/ap3-stairs/bench), and his stutter log shows 60-180 ms
  ticks, ten per frame, while an align fought a lava bounce. `Ap3DiscretePlanner.startBudget` gives a tick 10 ms; the
  searches keep the best found; 20 spent ticks in a row end the align. Standing in lava or a hazard ends the align at once
  (logged once); presses into lava, fire, cactus, berry bush, powder snow or onto magma are refused like a drop.

- **Blink Charge / Blink Release nodes (2026-10-26).** AP3 drives Blink Charge through `BlinkCharge.beginFor(owner, n)` /
  `shootFor(owner, n)` / `endFor`, never the Charge key: while an owner is set the key is ignored (logged), `cap()` is the
  node's n, and a shot of k < bank runs k ticks and leaves the rest banked. The shot runs at Blink's START from AP3's input
  of the END before (the mixin installs AP3's keys in every shot tick), so a release fires along the held walk. A shot moves
  him up to 9.5 blocks in a tick, past AP3's 8-block "you leapt" line: `BlinkCharge.shotThisTick()` exempts it, or the
  first long shot would drop the walk and the queue. AP3 stopping hands a charge AP3 started back to the Charge key with
  its bank and AP3's cap (`BlinkCharge.handBack`, blink-keep 2026-10-27); only "AP3 turned off" ends it (`endFor`). A stop
  inside a shot tick ends the shot at its next tick (`shotAbort`), the rest is re-banked, and the hand-back keeps it. A
  release that spends the bank ends the charge, so ping answers go back to live. Testkit 1026-ap3-blink-*, 1026-ui-ap3-blink-nodes.
- **A Blink shot's ticks are AP3 ticks (2026-10-27).** The first release design ran every shot tick on the keys AP3 chose at
  the END before the shot, so a 20-tick release replayed one frozen input: killer560's S2 blinkrelease #39 (Mod Only Test
  log 00:15:34) went 18.75 blocks straight off the platform (y 132 -> 115, a server correction) because the turns and nodes
  inside the shot never ran. Now `BlinkCharge.addShotTickEndHook(Ap3Feature::tick)` runs AP3's whole END work after each
  shot tick's movement and tick-end, and `addShotGuard(Ap3Executor::shotStopReason)` may end the shot before a tick (the
  rest stays banked). The release node finishes as soon as it asks, so nodes reached inside the shot can begin; its result
  is logged by `tickReleaseWatch`. The 8-block leap line needs no exemption any more: no shot tick moves him 8 blocks.
- **Momentum-keeping charge.** A banked tick sends no move and no tick-end, so for the server no game time passes for him;
  `Ap3FreezeMixin` skips `LocalPlayer.aiStep` on exactly those ticks (`Ap3Executor.takeBlinkFreeze`, only when the tick's
  tick-end will bank - `BlinkCharge.canBankNow` - and `noteFrozenTick` makes it bank in mid-air too). Nothing is written:
  velocity, sprint and ground state simply are not ticked. A skipped tick that went out unbanked (an action in it) would
  tell the server he stood still with his velocity unspent; `BlinkCharge.frozenTicksSent` counts those. The node keeps the
  held walk (`Ap3Node.keepsHoldNow`); `stop` restores the old plain stop.
- **An aura may turn him during an AP3 walk only to a walk-keyed yaw (2026-10-27).** GrimAC 2.3.74's RotationPlace
  (javap: `didRayTraceHit`, a ray from the reported yaw/pitch - and lastYaw - against the clicked block's FULL block box)
  covers lever clicks too: measured in testkit 1027-ap3-lever-noturn-*, a no-turn click 0 or 10 degrees off is clean and
  30, 60, 90 or 180 degrees off is one RotationPlace flag and the lever does not toggle. A click-only look cannot exist:
  one rotation per movement packet, and Grim judges the click and the movement by the same one. Movement depends only on
  the yaw, and at the yaws walkYaw + 45 / walkYaw - 45 / walkYaw the keys W+D / W+A / W walk exactly along the walk with
  the sprint kept, so `TurnFirst.registerYawConstraint` (AP3: `Ap3Executor.walkKeyedYaws`) lets an aura turn only to one
  of those, with any pitch whose ray meets the target from the eye after this tick's move; none this tick = no turn, the
  click waits. A/D/back yaws were allowed first and dropped the sprint, which bent the path after the next node by 0.65.
- **#34 on main 3011406a (2026-10-27).** (1) The "no stepping down unless the point is lower" rule stranded an align that
  had to cross a stair's low half: his 09:25:55 Fast Align #34 started on the low half, stepped up, then refused every
  press for 50 ticks at rest 0.78 from the point. A press may now cross a lower floor down to start - 0.5 when the fall
  onto it (3 air ticks, velocity x 0.91, no push) stays over floor; at rest with the plan refused, a sneak-held step toward
  the point goes first. (2) RUN #33's line passes 1.4-1.9 blocks beside #34's 1x1 box and never enters it, so the run
  went on to the floor's edge (testkit 1027-ap3-s2ee3-run-* on the shipped jar: every entry fell 56 blocks). A held walk
  that passes within 2.5 of the route's next place (the next walk/align/stop/path/leap/terminal, skipping looks, uses,
  booms and blink nodes) and is getting farther away begins that align; and a held walk stops at an edge more than 0.5
  below its start unless that next place is lower, then brakes (sneak + key against velocity) until still - AP3 counts as
  running while it brakes, or the input hook would not install the sneak.
- **The `close` suffix (2026-10-27).** Any node; saved as "termClose" (the old "close" key of the 2026-10-06-removed gate
  stays unread so stale flags do not wake up). Held by the leapcounter: gate (only aligns run, a left click skips) until a
  container whose title `terminals/TerminalType.match` accepts goes from open to closed after the node was reached - a
  terminal finished while he stood in the box before the node's turn counts. While it holds, a terminal screen does not
  stop AP3 (screenAllowed).
- **`sectiondone:<n>` (2026-10-27).** Any node; saved as "sectionDone" (1-4). Held by the leapcounter: gate until
  `leveraura.P3SectionTracker.sectionDone(n)`: the tracker's section is past n, or it is n and its last "(n/m)" line read
  n == m. No position check (his "any section not just the one I am actively standing in"). The tracker clears its count
  on every section change, so a finished S1 count cannot answer for S2; it resets on Goldor's line and a world change, and
  reads only F7/M7 lines whose name group is `[A-Za-z0-9_]{1,16}` (a forged "[VIP] Bob: a activated a terminal! (7/7)"
  does nothing - testkit 1027-ap3-section-s1..s4).
- **Left click on a wait (2026-10-27).** His 09:22:39 log: leapcounter #37 held, he opened chat, "dropped (a screen
  opened)", and the node - he was standing in its box - could not fire again until he walked off and back on; a click had
  no gate to skip. A screen no longer stops AP3 while the only thing running is a gate (gateOnlyWaiting), and a stop that
  does drop a gate remembers it: a left click while he still stands in its box fires it (takeDroppedGateClick). The click
  is taken at START by consumeClick, so no swing or dig goes out (testkit 1027-ap3-gate-click-repeat).
- **Held walk edge stop needs the slide (2026-10-27).** Checking only pos + v + 0.35 stopped a 0.5-speed run with its
  feet already over the edge, and he fell 28 blocks under sneak and brake (1027-ap3-s2ee3-run-0.5-+0.45 on jar c). The
  probe is pos + 2.2 v + 0.35 (ground friction 0.546 slides 1.2 v after the keys let go). A walk with a jump / edge
  modifier, or a JUMP / EDGE node before the next place, is exempt: that walk leaves the floor on purpose.
- **Charge, release and run in one block (2026-10-27).** Co-located nodes are ordered by type priority (charge 4, run 6,
  release 7), not by the order they were added, so all six orders behave the same (testkit 1027-ap3-blink-combo-*). The
  charge freezes him the tick after his first END in the box. But it finishes inside tickNode, after that END's
  fireInstant and begin, so the Run and the Release used to wait a whole real tick (he walked 0.28 between the full bank
  and the shot). A charge that finishes now gives the queue a second pass in the same END (chargeDoneThisTick): the Run
  sets the hold, the Release asks for the shot along it, and the shot fires at the next tick start.

