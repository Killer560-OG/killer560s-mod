# Automation runtime lessons

Split out of [LESSONS.md](LESSONS.md). Same rules: problem, then fix; verified only. This file holds what every auto
shares (GrimAC, right clicks on Hypixel's 1.8 server, ticks and clocks, threads, chests) plus Auto Clear kill speed,
Key rooms, and Blink, Inventory Walk, Zero Ping Etherwarp and Zero Ping Chain (their GrimAC limits). The rest is split by feature:

- [LESSONS-ROUTES.md](LESSONS-ROUTES.md): Auto Routes - chain timing, the Interact Delay, node aims (recorded look, crypt,
  boom, breaker), refused and eaten warps, the click gap, awaits (start lookback, carry, levers, the Redstone Key, key
  spots, bats, Kill Mimic).
- [LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md): the Interactive Map's floor graph, planner thread and trips (corrections,
  "already there", arrival settle, `sendNow`, one item use per tick), Auto Secret (room order, last-node wait) and Auto
  Clear (chest rule, bats).
- [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md): the puzzle autos - FreeCam, TurnFirst, the heard look,
  `AutoPuzzleActivity.ended()`, reward chests, Blaze's VolleyWatch, Tic Tac Toe, Boulder, Three Weirdos, Quiz.

## GrimAC and packet order

- **Auto Routes on GrimAC (2026-10-05, testkit 62-argrim).** Obvious mode sent each use with a rotation the client never
  reported (BadPacketsJ on every etherwarp) - the bug Auto Puzzles fixed on 2026-09-27, and `ClearExecutor.doInteract`
  (every Interactive Map warp and Go To) had it too until 09e2c304 (62-argrim-imwarp/-goto). Digs must carry the face the
  eye sees (an always-UP face is PositionBreakA), an ABORT says DOWN as vanilla does (any other face is PositionBreakB on
  every later dig), and no input packet may say sneak while a container is open (MultiActionsD on its close). Both
  executors turn the body with the camera held through `util/BodyAim`, one instance each. Multi Break's six STARTs on one
  tick, aimed at the first, drew nothing from GrimAC with experimental checks OFF - and MultiBreak + RotationBreak with
  them on (next entry); Multi Break is gone.
- **GrimAC 2.3.74's experimental checks (gridexp-1102, 62-argrim/63-ap3 with `experimental-checks: true`).** Each flag and
  its packet pattern, from Grim's source at tag v2.3.74:
  MultiBreak: a second START_DESTROY_BLOCK on another block or face before the server's next tick packet - Multi Break's
  burst, and a breaker node's six. Fix: one dig a tick everywhere (Breaker Aura, breaker node, linger, re-dig); Multi Break
  came back the same day as "back to back, one a tick" (multibreak-1102, next entry).
  RotationBreak: a dig is traced along the NEXT movement packet's look only if that arrives within 15 ms
  (`handleQueuedBreaks`), else along the look before; after one miss every later dig is judged on arrival by the look
  before ("pre-flying"). Digs sent at START with the body turned in the same tick missed whenever the tick was slow. Fix:
  a dig (breaker, boom tap) goes at once when the look the server already heard strikes the block's cube from where it
  has him (`util/DigAim`); otherwise the body turns and the dig is queued for the head of that tick's `sendPosition`
  (`util/BeforeMove`), right ahead of the movement packet carrying the look - vanilla's own order (dig in handleKeybinds,
  then sendPosition). After each dig the next block's turn rides the same movement packet (Grim also tries the previous
  look), so a run of digs goes one a tick from the firing tick. Turning a tick AHEAD instead (first try) was clean too but
  cost the node its firing tick (96-ar-play's "DUNGEON_BREAKER acted 0 tick").
  PacketOrderE: a `set_carried_item` after a use / block click / interact / attack / release / player command inside one
  server tick (reset by a movement packet, or by `client_tick_end` when no movement packet went that tick; a teleport's
  echo is not a tick packet). Vanilla reads hotbar keys before use keys, so it never sends that order. Cases: Kill Mimic's
  Hyperion use then the slot handed back in the same tick; his lever click (outside the tick) then the breaker node's swap.
  Fix: `HotbarSelect.swapWouldFlag()` (fed by the send tap); the route holds a tick, Kill Mimic waits a tick before its
  swap and hands the slot back the next tick (state RESTORE). Not under a Blink charge (Blink replays the wire).
  DuplicateRotPlace: a `use_item_on` whose preceding yaw turn is over 2 degrees and equal to 0.0001 to the turn before the
  previous block click - automation snapping to 45-degree node yaws did it twice in a row. Fix: `util/PlaceRotationGuard`
  moves the yaw 0.01 for the one send when a turn would repeat (sendPosition head), never in a tick whose movement packet
  must report a `use_item`'s look (moving it there is BadPacketsJ - 11 flags in 62-argrim-1020-acbat on the first try).
  That left HIS right click unguarded (grimsame-1105): in the tick a feature turned, his own `use_item` went out first
  with the turned look, so sendPosition could not move it and the repeated turn reached the next block click
  (1105-same-lights-use, experimental, x2). `PlaceRotationGuard.beforeUse` (head of `MultiPlayerGameMode.useItem`) now
  moves the yaw before the use carries it and keeps it through that tick's sendPosition.
- **His own action in the same server tick as a feature's (grimsame-1105, testkit `SameTick`).** Test a feature with him
  clicking, not idle: an idle-after case misses every flag below. His left click after a world aura's interact / use in
  one tick is PacketOrderI (`type=attack, rightClicking=true`) plus MultiInteractA on a second entity (1105-same-termaura-
  attack, experimental). `ActionGate` now refuses WORLD actors while the attack key has a queued click
  (`KeyMapping.clickCount` > 0 at START): his click goes this tick, the aura next. His scroll / hotbar key between ticks
  is sent by `gameMode.tick` AFTER the START actors, so a raw `startPrediction` use_item (Auto Routes, Auto Clear, ZPEW
  Aimed Warps) went out with his OLD item and the slot after it: PacketOrderE (62-argrim-1105-same-chain-swap,
  experimental). Every raw use now calls `ensureHasSentCarriedItem` first, as vanilla `useItem` does. Auto Routes takes
  his left click mid-chain (held for the next await), so no hand dig of his reaches the wire then. Coverage table and open gaps:
  killer560s-mod-logs/grimsame-1105-audit.md. His swap / use during a 20/s route chain and during the boss auras is clean.
  grimgapC-1105 closed the container-click and hotbar/drop gaps with no mod change and no flag on either Grim config:
  Auto Sell and (Block Input off) Auto Terminals share server ticks with his own screen click; Auto Terminals with Block
  Input on and the Inventory Sorter swallow his clicks; Swap To Drill On Pigeon is set at END and sent by vanilla at the
  next tick's start, so his use shares that tick (slot first) while his END attack / scroll hold it a tick
  (`swapWouldFlag`); Auto Ult's START drop goes before his use, and `ActionGate` puts it a tick after his queued attack.
  Test traps: a stick's click on a treasure chest opens its lid and ends the claim; `ChatObserver` drops a repeated line
  inside 250 ms; the server never re-sends the slot after a raw DROP_ITEM, so count drops on the server; a harness END
  click can land on a terminal before its first frame, when the solver has not yet read it (Block Input then misses it;
  a real click comes after the draw).
  BadPacketsH: every START/STOP dig and use must carry the last sequence + 1; AP3's boom START went out with sequence 0
  (the constructor without one). Fix: it goes through `RouteExecutor.sendBoomTap` (vanilla's `startPrediction`).
  grimgapA-1105: AP3's USE / BLOCK / BOOM act sends its hotbar change at the head of `sendPosition`, AFTER his right click
  of that tick: PacketOrderE (+ PacketOrderN for a place), experimental. The act now waits for the next tick while
  `HotbarSelect.swapWouldFlag()` (bounded by SWAP_TIMEOUT, so a click EVERY tick fails the node: tested at 10 cps).
  Charge sent his scroll live while the tick's move was banked and the ping answers ran a second late, so a flying, his
  `set_carried_item`, then an answer reached GrimAC: Post "held item change" (GrimAC Post resets only on a valid answer,
  not on `client_tick_end`). It now waits in `preHeld` for the next move sent, like a sprint command. A tick with a use
  is never banked by design, so Charge cases click only around the shot. Testkit `SameTick.Act.USE` sends at START, BEFORE
  `gameMode.tick` sends a pending slot, which no real click does; `Act.KEYUSE` clicks the use key so `handleKeybinds` sends
  it (AP3 cases). His use / swap / teleport in AP3 nodes, Camera and Sent-Yaw aligns, Charge shots and ZPEW Aimed Warps
  are clean on both configs (1105-ap3-same-*, 1105-blink-charge-same-*, 1105-zpew-aimed-same-*). Two 26.2-only follow-ups:
  a held scroll sent after the shot's (or aimed use's) own fresh slot is a duplicate slot packet (BadPacketsA), so
  `preHeld` flushes as soon as our own sends pass (before them); and at charge off the held answers go out BEFORE the held
  scroll, or a flying + slot + answer is Post again.
  BadPacketsV (a position packet that did not move) in 63-ap3's Blink shot cases is Blink's, not fixed here.
- **His hand during a body-turning aura (grimgapB-1105).** GrimAC judges his attack by the look of the NEXT movement
  packet, so an attack of his while Breaker Aura (BreakerAim) or a TurnFirst aura (Secret Aura...) has the body turned is
  Hitboxes on the entity under his crosshair. Both now give the body back in the tick his attack click is queued
  (`ActionGate.hisClickQueued`) and never turn in it: a turned click needs a free tick to turn and one to click, so at 10
  CPS of his own clicking the turned picks / levers wait (testkit spaces his clicks 3 ticks). His held left click digging
  another block while the aura digs is MultiBreak: `DigWindow.handDigsThisTick` predicts his START / STOP from the
  destroy delay and progress, and the aura holds that tick. The Wither bot's attack before his right click in one tick
  is PacketOrderJ: `ActionGate.hisUseGoesThisTick` (queued keyUse, or held keyUse with `rightClickDelay` <= 1) holds a
  melee attack. A test of his use must be a real key press (SameTick `USE_KEY`): a raw `gameMode.useItem` at START LATE
  is invisible to the feature and is not what his click does. Testkit 1105-same-{breaker,autoss,goldor,witherbot,thorn}-* and 62-argrim-1105-same-secretaura-*.
  A turn is computed from the yaw the server last HEARD (`yRotLast`), never the live one: TurnFirst's hand-back and the
  next turn in one tick went 90 -> 253.8 from a heard -73.8 (AimModulo360, 422-boss-ss-autosolve-norotate-behind).
  The Auto SS cases had done nothing since 1a4e48f60 (the Auto Simon Says master switch): every Auto Solve test must turn on
  `AutoSimonSaysEnabled`.
- **One block per server tick is GrimAC's hard limit for digs, from any source (multibreak-1102).** MultiBreak (2.3.74,
  `checks/impl/breaking/MultiBreak.java` 37-65) flags a START or STOP (not ABORT) on another block or face until a tick
  packet clears it; the tick packet is a movement packet, or `client_tick_end` only when no movement packet went that tick
  (`GrimProcessor.isTickPacket` 76-96, `CheckManagerListener` 599-609/781), so exactly one per client tick. Nothing
  faster is clean: insta-break is not treated differently (only ABORT is skipped), an extra movement packet is Timer, and a
  second dig sent AFTER the tick's movement packet (END_CLIENT_TICK, a frame) is the next window but GrimAC Post once a
  transaction answer follows it (`Post.java` 77-102). Multi Break now means every block back to back, one a tick, no
  cooldown between them (6 picks in 6 server ticks, a node's 10 blocks in 10). And the limit is mod-wide: a breaker
  linger dig plus a node that fires, or Breaker Aura beside a route, in one tick is the same flag - `util/DigWindow`
  mirrors MultiBreak from the send tap and every automated dig asks `mayDig` first and waits a tick when the window is
  taken (a dig queued at `BeforeMove` reserves it). His own hand dig after automation in the same tick is not covered.
  Testkit `harness/MultiBreakWatch` replays the check on the packets.
- **A session's first turned dig flags RotationBreak on a fresh server (multibreak-1102).** The first dig a new test server
  processes takes it longer than GrimAC's 15 ms window, so a dig sent at the movement packet's head as the session's first
  draws RotationBreak post-flying and then pre-flying on every later dig (36 lines, 62-argrim-breaker run alone on 26.3);
  the same as RotationPlace's first place (testkit warmPlaces). Not the mod: warm the path with one plain dig first.
- **Breaker Aura's 200 ms re-dig dug air on a busy server (multibreak-1102).** It sent a block's START again 200 ms
  after the first if the client still showed it; with the server's block update ~700 ms late (nine test clients), the
  same snow block was dug twice more after the server had broken it - GrimAC AirLiquidBreak x2 (1102-breaker-aura-
  multibreak-off, 26.2). A re-dig now waits for the server's ack of the first dig's prediction sequence
  (`ChestWindows.acked`, from ClientboundBlockChangedAckPacket) or 1.5 s; 10/10 clean on both versions after.
- **A turned dig that rides the next movement packet's look misses GrimAC's 15 ms lend on a loaded server, and one miss
  poisons the session (rbcause-1102).** Grim lends a dig the next movement packet's look only when it PROCESSES that packet
  within 15 ms of the dig (`handleQueuedBreaks`, server currentTimeMillis); after one miss its buffer judges every later dig
  on arrival by the look before, so every turned dig flags until ten clean ones (64 lines in one 62-argrim run). Probes
  (testkit [testkit-dig] server, [digflush] client) under five parallel sessions: the client wrote the dig, its swing and
  the movement packet within 0.2-0.6 ms; sent as separate flushes the server processed them 28-168 ms apart, and even
  written as ONE flush (BeforeMove now holds the flush like Ap3SendTogetherMixin) the server processed a boom tap's START,
  ABORT and the movement packet 16, 14 and 0 ms apart - its netty thread starved between packets of one read. Not fixable
  from the client. A dig the look the server ALREADY processed strikes passes either way (Breaker Aura, ba-rotation-1102:
  0 flags in 8 loaded runs). killer560 (2026-11-02): "Spend the extra tick." Breaker nodes, boom taps, the linger, the
  re-dig, obsidian starts and Burst Multi Break now dig only on a look the server has processed: turn, dig next tick
  (none spent when the look is already on it; `RouteExecutor.aimTurns` gives up after 3 turns). BeforeMove's one-write
  batching is kept.
- **Burst Multi Break: as many digs a tick as RotationBreak allows (burstmax-1108).** GrimAC 2.3.74's RotationBreak passes a
  dig when the eye column (0.4..1.62 above the feet) stands in the block, or a ray along the heard look strikes it from ANY
  of the three eye heights it always tries (GrimPlayer 313-315, RotationBreak 77-122); default Grim has no per-tick dig
  limit at all. So `DigAim.strikesAnyEye` counts a pick ready on any of the three rays, and `BreakerAim.aimForMost` aims at
  the look that strikes the most picks left (a wall face-on is struck by three parallel rays). Before, the post-dig aim
  could pick a block already dug that tick: 20 boss-sim blocks took 22 ticks (at most 3 a tick); now 6 of 6 in one tick on
  default Grim with 0 lines, and the boss wall in 10 ticks (the 20 charges are the limit). Experimental Grim's MultiBreak
  CANCELS every dig after the first in a tick (MultiBreak.java 36-66), so a burst there only re-dug and left picks standing;
  after 2 refused extras Burst goes one a tick for 100 ticks (not for good: a pick refused for a miscounted charge looks
  the same). QUOI/RSA's whole-wall "zero tick" digs need no look at all: that is possible
  only on a server without RotationBreak. The "sticks around" report (Burst + Blink Charge): BlinkCharge.deferBlockUpdate
  holds the server's break until its ping answer goes (~1 s) while the break sound is not held; a pending pick is now drawn
  as air through the SectionCompiler hook (`MithrilStone.replace`, remeshed on add/remove, expires at 3 s and on world change).
- **Inventory Walk's held close went out with the route's sneak down (iwcause-1102, GrimAC MultiActionsD).** With Stop To
  Click on, Inventory Walk holds a container close until a still tick, but lets a held close out AHEAD of any use (a
  use may open the next menu). A chest the route's await counted opened and was closed between two ticks, so the route
  never saw a screen (`holdsSneak`/`screenBlocks` only drop the sneak under a visible screen) and kept the etherwarp's
  sneak; the close was held, and the warp's use flushed it with the server's input still sneaking (62-argrim invwalk-play,
  v5, once in ~15 runs). Fix: while `InventoryWalkFeature.heldNow() > 0` the route installs no keys and sends no use
  (etherwarp, USE_ITEM, sceptre); the close goes on its still tick, then the sneak and the use. Testkit
  62-argrim-1102-iwclose reproduces it every time: the route held on its mana hold with the sneak down, the chest window
  hidden on arrival, the close sent at an early END phase (after the tick's sneaking input, before the route acts). Old jar
  MultiActionsD 5/5 per version, fixed 5/5 clean; a close sent at START does not reproduce it, since Inventory Walk then
  makes that tick's input its still tick.
- **Breaker Aura aims without steering his walk (ba-rotation-1102).** It dug picks it never looked at: GrimAC RotationBreak
  (experimental) 87 lines in scenario 50 and 73 of 200 digs canceled. Now only a pick the HEARD look strikes is dug
  (util/DigAim; Grim also tries (lastYaw, lastPitch), so the next pick's aim can ride the same tick's movement packet), and
  the look changes only where it cannot move him: pitch freely (horizontal movement uses yaw alone; not in water, lava,
  gliding or riding), the body's yaw kept on his view's yaw every tick so his mouse still steers; yaw only while no
  movement key is down and he is not sprinting, snapped back to his view the tick a key goes down, before travel. Picks
  needing a yaw turn while he walks wait (`dungeonextras/BreakerAim`; since balate-1109 they turn in 45-degree steps with
  his keys turned to match, see the next entry). Scenario 50: 118 blocks cleared, 0 flags (old: 127
  cleared, 200 digs, 87 flags). Also: a vanilla-instant break must be predicted at once (as vanilla's startDestroyBlock and
  Grim's handleDigging do) - kept standing until the server's update, he walked into a block Grim had as air, Simulation
  x17-19 in 51 once the aura dug one a tick right in front of him.
- **Breaker Aura was late while he walked, and snapped his camera on S1 (balate-1109).** Picks off his look waited
  for him to stop (above: no yaw turn while a key is down), so on S1 a wall was reached 3-5 ticks late and a pick he
  walked past never broke; the census called those waits "gate gave the tick away" (now "aiming or waiting on a look").
  Fix: movement is his eight-way keys turned by the body yaw, so the body may turn k x 45 degrees off his view when the
  keys are turned k steps back at the end of `KeyboardInput.tick` (`mixin/BreakerAuraInputMixin`): the push is the same
  to the bit. Only combinations that keep W when his keys have it (sprint starts and stops on `hasForwardImpulse`,
  moveVector.y > 0), none on a sprint jump's take-off tick (the boost goes along the yaw), none while AP3 drives (its
  held walk gives its own allowed yaws, `TurnFirst.yawConstraint`), and never while AP3 owns the body
  (`TurnFirst.bodyOwned`). The camera snap: under AP3's own view freeze the body yaw is not the camera, and the aura held
  ViewFreeze with the BODY's yaw while AP3's mouse hook moved only AP3's view, so the camera fell onto the stale hold
  when AP3 let go. Hold the VIEW (`getViewYRot/XRot`), and AP3's mouse hook turns ViewFreeze's view too. Testkit
  1109-breaker-walkpast-* measure reach -> first dig per pick on the wire and the camera's per-tick step.
  But walk-keeping yaws alone stall on a pick his body clips (balatefix-1109, killer560 2026-11-09: "i still run into
  breaker blocks"): his AP3 run walked at yaw 72 and the picks at 54/55,*,141 sat ~7 degrees off it, so 72 and 72+-45
  missed, the aura waited, and the digs went on the contact tick (160 ticks of waiting with a manual walk). Now a pick
  that is URGENT (in his way: overlapping his body height and under 0.8 sideways ahead, or he is not moving, or it waited
  10 ticks) gets one look off his walk: a yaw within 30 degrees of the pick's center, the one nearest a whole 45-degree
  step off his walk, his keys turned those steps back (walk bent by the remainder for that one tick; 0.83 blocks drift at
  speed 0.6 with the center look, 0.12 with the nearest-step yaw). Never on a sprint-jump take-off or while AP3 drives
  with no held-walk yaws. A wait logs the pick, his feet, the view yaw and the yaws tried. Testkit
  1109-breaker-walkpast-into (speed 0.6 into two picks: 0 ticks against a block, off-walk looks > 0, drift <= 0.25).
- The sim's Dungeonbreaker refused every pick in the boss arena ("does not work in puzzle rooms", 1108-boss-breaker "only
  0 picks broke") when an earlier case's floor index named a puzzle cell nearest the arena: `SimItems.dungeonBreak` skips
  the room checks while `BossFight.running()` (balatefix-1109).
- **A vanilla client removes a block it instabreaks the moment it sends the dig, and GrimAC assumes it did**: on an instant
  START_DESTROY_BLOCK (`BlockBreakSpeed.getBlockDamage >= 1`) it sets the block to air in its own world at once
  (`CheckManagerListener.handleDigging`, 2.3.74). Breaker Aura with Zero Ping off kept standing on a picked floor block
  until the server's update came back, so any movement packet in that gap claimed ground over air: GroundSpoof + 0.0784
  Simulation when the 20-tick position reminder landed there (about half the runs), NoFall every time when turning
  (testkit 60). The block he stands on is always predicted (`BreakerAuraFeature.standsOn`); 0 flags in 84 runs.
- **Hotbar swaps go through `util/HotbarSelect.select`, never `setSelectedSlot`.** A swap sent by hand
  (`ServerboundSetCarriedItemPacket` after `setSelectedSlot`) leaves the game mode's `carriedIndex` stale, and its `tick()`
  then sends the same slot again: GrimAC BadPacketsA. Swap through `MultiPlayerGameModeInvoker.invokeEnsureHasSentCarriedItem`
  - **and only inside a tick** (grim-post-autopilot-1016): GrimAC Post flags a held-item change sent after the tick's
  `client_tick_end` (END_CLIENT_TICK, a task or packet handler between ticks, a render frame) whenever a transaction answer
  follows it before the next tick packet - so it flags in some runs only (62-argrim-1016-autosecret-key, trace
  `[client_tick_end, set_carried_item, pong]`: Auto Secret's `decide` runs at END and a precomputed first trip, `ClearExecutor.runPlanned`, swapped
  there). `HotbarSelect.select` outside a tick only sets the slot (vanilla's `gameMode.tick` sends it at the start of the
  next tick, before the movement packet, as for the scroll wheel). The testkit's `PacketTrace.sentOutsideTick()` catches
  the order without waiting for Grim's luck. Since hotbar-select-rest-1016 there is no other slot write in the mod (Breaker
  Aura, Auto i4, MaskSwapper's rod, Secret Triggerbot, Auto Debuff, `AutoPuzzleUtil.swapTo`, the pearl hopper, AP3's slot
  restores, RouteExecutor.select, ClearExecutor.swapById).
- **A click sent from a render frame is GrimAC Post, same as one from END_CLIENT_TICK**: the frame runs after the tick's
  movement packet. Goldor Triggerbot, Arrow Align's Trigger Bot and Aura, Simon Says' Trigger Bot and Rotate Mode click, and
  Auto i4's shots all clicked from `LevelRenderEvents` for millisecond timing (testkit 786-788 recorded one Post per
  click). They send at START_CLIENT_TICK now; only camera turns stay per frame. Delays are `ActionGate.delayTicks` (rounded
  UP to 50 ms). Testkit 786-788 and 831-835 require clean. Secret Triggerbot and the Auto Door Opener were already on the
  tick and still time their delay with `System.currentTimeMillis`, so on a slow client their delay is fewer ticks than
  ms/50 while the P3 trigger bots' is exactly that many ticks; the testkit judges each in its own unit (preexisting-1026).
  Arrow Align's Trigger Bot has had a CPS range instead of a delay since b4b6e44f (the first click waits one gap).
- **An aura's click is judged against the rotation the movement packets report, not the camera.** A block use the reported
  look misses is GrimAC RotationPlace (and dropped); an entity interact is Hitboxes. Turn the BODY first with
  `util/TurnFirst` (or BodyAim), camera held, and send only after a movement packet has carried the turn: an AP3 Term Aura
  click sent at the START right after an END turn preceded every packet facing the stand, and after an earlier flag GrimAC
  let none through (testkit 416). Digs are not judged this way (54: behind and through a wall, 0 lines).
- **GrimAC 2.3.74 never judges the look or reach of an interact on a NON-LIVING entity** (javap, aafast-1027): `Reach.
  checkReach` returns NONE unless the target is living or an end crystal, so an item frame (Arrow Align) draws neither
  Hitboxes nor Reach whatever the look. An armor stand IS living (Terminal Aura, 415/418). For a living target the interact
  is queued (`playerAttackQueue`, one entry per entity id) and judged at the NEXT update packet (flying, client_tick_end or
  pong) after that packet's look is applied, against (yaw, pitch), (lastYaw, pitch) and (lastYaw, lastPitch): so a body
  turn written at START before the click, carried by the same tick's movement packet, is the look it is judged by. That
  ordering is javap only; it was measured on item frames, where Grim does not look, so it is unproven for armor stands.
- **Interacts on two different entities in one tick are GrimAC MultiInteractA; several on ONE entity are not** (javap +
  testkit 1027-aafast). MultiInteractA flags a second entity id (or a sneak change) before the next tick packet;
  MultiInteractB flags interact-at packets in one tick with different hit vectors. Both are EXPERIMENTAL in 2.3.74 and only
  report with `experimental-checks: true` (the testkit's server has it off). A second "tick" inside a client tick needs an
  extra flying packet, and Grim's Timer counts every tick packet (client_tick_end too, 1.21.2+) with only 120 ms of
  fall-behind (`clockDrift`), so that cannot be banked. Arrow Align Aura therefore does one frame a tick, all its clicks
  (Clicks Per Tick up to 7), and RSA's and QUOI's auras do the same (one frame, every click, then `break`).
- **A use-on-block (block place) has no rotation of its own: GrimAC 2.3.74 lends it the NEXT movement packet's only when
  that packet arrives within 15 ms** (`CheckManagerListener.handleQueuedPlaces`, javap), else judges it by the rotation
  before - RotationPlace "post-flying", then "pre-flying" on every later place (the buffer never drains). AP3 placed at
  START_CLIENT_TICK and a slow tick opened the gap (63-ap3-block-*); its USE / BLOCK / BOOM go out at the head of
  `sendPosition` (`Ap3Executor.beforeSendPosition`). Any snap-aimed place should go out there; testkit
  1018-ap3-block-slow-tick proves it (START-sent: flagged 2 of 2; sendPosition-sent: clean).
  The 15 ms is SERVER time between the two reads, and it includes the server's own processing of the place: a freshly
  started test server took 16-162 ms over its first place under load (testkit `[testkit-gap]` probe, 2026-10-27), so the
  63-ap3 session's first AP3 place flagged on every jar until the session warmed the path with plain places first. Since
  ap3-blockuse-1027 the act's packets are written unflushed (`mixin/Ap3SendTogetherMixin`) and leave in ONE write with the
  movement packet, so no network or wakeup split can come between them (warm gaps 0 ms against 0-2 before). What no
  client can prevent is a slow server; only a look already sent in the packet BEFORE the place survives that, at the cost
  of one tick.
- **Never release a `ViewFreeze` hold you did not take.** It is one global lease; a second owner releasing it drops the
  camera onto a body another auto is still turning (93-solve-blazemiss-lower, -boulder-aura). `ViewFreeze.holdCount()` says
  whether anybody else held it since you did.

## Right clicks on Hypixel's 1.8 server (`util/RightClick`)

- **Hypixel's 1.8 server pairs a bare item use with the last unpaired right click on a block** (CraftBukkit 1.8
  `PlayerInteractManager.firedInteract/interactResult`, face-255 branch of `PlayerConnection.a(PacketPlayInBlockPlace)`). A
  block click stores its verdict; a use traces 4.5 blocks along the look the server LAST HEARD in a movement packet (a 1.8
  use carries no rotation; a 26.1 use's own rotation does not count for this trace), and if that ray meets a block while a
  verdict is stored, the use is taken as the click's second half: no ability, no chat line, verdict cleared. A modern
  client's chest/lever/skull click is one packet, so its verdict stays stored. Evidence (Mod Only Test 2026-10-14): all six
  "no landing after 61 ticks" warps came a tick after a chest or lever click with the AOTV held, and their retries landed;
  along the warps' OWN looks Red Blue #5 and #6 meet nothing within 4.5 (raytraced against the room capture), so the server
  traced the look the chest was clicked with - Auto Routes turned the body and sent the use in the same tick. The
  2026-10-15 fix that held every use 4 ticks after a same-item click (`BlockClickGap`) was wrong and is gone: it still sent
  with the old look. Sneak timing was ruled out. `util/RightClick`, for every item-use sender: `mustTurnFirst` (unpaired
  click AND the heard look's ray meets a block AND the use's look not yet heard -> turn the body, send next tick; Auto
  Routes turns on the firing tick while the sneak goes out, so it costs nothing) and `beforeUse`/`beforePlainUse` (the
  use's look meets a block within reach -> send vanilla's click on that block first, so the use pairs with it). A click
  with an empty hand counts as harmless (and replaces an earlier verdict): the one real warp a tick after a click made with
  another item (Grand Library, Dungeon Breaker) landed. The sim models the pairing (`SimAbilities.takenAsClickHalf`, heard
  look from `SimUseLookMixin`) and the testkit's `HypixelUseAfterClick` forces it on any jar (96-ar-1015-warpclick,
  -puzzleclick, 1015-sim-clickgap). Every use and block click logs one `[RightClick]` line (seq, item, slot, rotation,
  heard look, shift, screen, unpaired click, ray); a WARN line ending "takes this use as that click's second half" is the
  failure happening.
  Traps: the Interactive Map sends a hop while the last one is still in flight client-side, so its server ray is traced
  from the planned spot (`mustTurnFirst(.., serverFeet, ..)`), and a waited hop is re-aimed only once he stands within a
  block of its spot (re-aimed from the pre-landing position it flew 38 blocks off the plan); a path hop in flight already
  turns to the next hop's look while a click is unpaired, so the landing tick still sends it; `updateDelays` must not let go
  of the forced sneak while `pendingInteract` waits (Instant Transmission). The testkit pairs a right click's two halves by
  the client's tick-end packet, not by server tick or movement packet (a standing client sends no movement packet). Do not
  test a block click with a lever while sneaking: a sneaking AOTV use-on-block warps in the sim.
- **Vanilla's answer to a server teleport bypasses the send tap** (his Mod Only Test log, Flags #13/#15/#18).
  `ClientPacketListener.handleMovePlayer` sends the accept and a `PosRot` on `Connection.send` directly (javap, both
  versions), not through `ClientCommonPacketListenerImpl.send`, so `util/RightClick` kept tracing the server's ray from where
  he stood BEFORE a warp: a path hop a tick after the last landing went bare under an unpaired AOTV chest click, the server
  (tracing from the landing) paired and dropped it, "no landing after 62 ticks". `RightClickTeleportMixin` (RETURN of
  `handleMovePlayer`) records it. Separately, the sim fired a warp on a block half and then traced the use half from the
  landing (and from the 1.8 eye, 0.27 above the aim's), missed, and warped AGAIN: the use half of a click whose ability
  fired now always pairs with it - one right click, one ability. Testkit 96-ar-1015-pathland.
- **The server's pairing ray and the client's pick can disagree** (1015-sim-clickgap). The 1.8 server traces a bare use from
  the 1.54 sneaking eye, the client picks from 1.27, so a warp's server ray can meet a block within 4.5 that the client's
  look never reaches: no block half can be sent and the warp is dropped under an unpaired click. Never click a block the
  client cannot see; `RightClick.reaimIfUnpairable` aims at the same block along a look whose rays agree and the warp goes
  one tick later (ClearExecutor hops, Auto Routes warps). Testkit 96-ar-1015-reaim builds the mismatch.
- **A run-time etherwarp aim must HOLD, not just land** (killer560 2026-10-15: path nodes "still taking me to the wrong
  block"). The planner proves every saved hop within `EtherSearch.AIM_MARGIN`; the aims worked out while flying
  (`TeleportUtils.getEtherwarpDirection` for a hop fired 0.05 over its spot, `RightClick.reaimIfUnpairable`) only checked
  the exact float look. From a block-center stand, lines to face points on the tenths/half lattice often pass EXACTLY
  through a block's vertical edge, and the server, reading the running yaw a few bits off, takes the voxel tie the other
  way (his re-aim at -149, 261, -188 stopped in -149, 262, -189 in the sim's cast). Both prefer a look that lands for
  +-AIM_MARGIN, over off-lattice points too. "Etherwarp: landed" used to be any landing within 2 blocks of the planned feet;
  every landing is now judged by its block ("landed OFF its planned block", WARN). Second shape, same 0.05: when no sample
  point lands from the landing-tick eye, `stepOntoWarpSpot` read "back on its spot (0.00 off)" (horizontal check only) and
  fired the SAVED look - planned from the settled eye - 0.05 high; in 1015-sim-pathland it landed 5.4 blocks off
  (Overgrown) or was refused on a slab lip (Quartz Knight). The re-aim tries `exactEtherwarpDirection` (aimExact), and "on
  its spot" includes the height, so the saved look goes the tick he has dropped onto it.
- **Hypixel's etherwarp itself lands along the HEARD look, not the use packet's rotation** (icefill-1102, his Ice Fill log
  2026-11-02 17:06:08 and 17:06:14). Two manual sneak warps clicked mid-turn: use packets at yaw 61.66 and 18.18 (crosshair on
  the next tile), heard looks 49.30 and 25.10; both landings (the next use's "from") were the blocks the HEARD look's ray met,
  a tile off - "Don't move diagonally! Bad!". So anything judging where a warp will land (Ice Fill's Etherwarp Protection
  now does, via `EtherwarpOverlayFeature.resolveTargetAlong` + `RightClick.heardLook/heardFeet`) must use the heard look.
  NOT yet reconciled: `AutoPuzzleUtil.useItemRotated` turns and fires a warp in the same tick, which by this evidence would
  land along the previous look on Hypixel; and the sim's `SimAbilities.etherwarp` uses the use packet's rotation
  (`getLookAngle` after vanilla's snap), so the sim cannot show this. Testkit 1102-pz-icefill-flick checks the protection.
- **"warp to X not sent - X+1 is not the next spot" with X one above the ice is a pillar, not a height bug.** Ice Fill's
  obstacles stand at feet height on each floor (the identifier's must-be-solid blocks sit there too), so aiming at one
  resolves to standing on top of it; his 33 refusals at -121 71 -59 (2026-11-02) were that pillar, correctly refused.

## Ticks, timing and clocks

- **A ping does not cover the tick boundaries** (testkit 792): an attack sent on a client tick is acted on 0-2 server ticks
  later than the round trip alone predicts, at 1 ms and 200 ms alike - each end only acts on a packet at a tick.
- **A click cap holds at the client, not at the server.** Blood Camp's clicks go exactly the attack-speed cap apart (5 ticks
  at 100 Attack Speed), but the testkit server saw them 4 and 6 ticks apart, alternating (186), mean exactly 5. Assert the
  cap on the client's own click ticks, and at the server only the mean and "never more than a tick under".
- **An Attack Speed the client cannot see is the cap, not 0** (atkspeed-1103, killer560: "fire it as soon as possible").
  `AttackCooldown` read the tab's Attack Speed line, else summed item lore, which misses pets and read 0 (10 ticks): a blood
  mob appearing 5-9 ticks after a click waited for the 10th (nmflake-1103 offspot). Hypixel's window is the HIT mob's
  immunity, so a click too early only wastes itself on that mob; now tab line, else the last tab reading, else 100 (5
  ticks). His real camps (2026-11-02 runs 2, 3) already read 5 ticks from the tab. Testkit 1103-bloodcamp-atkspeed-*.
- **A click RATE is scheduled from the previous click's due time**, fractional, not from when it went out (else 16 CPS
  rounds to 10), **and a turned click counts from its TURN tick.** With "schedule from the previous due tick" alone, a click
  that first needs `TurnFirst` goes out a tick after it was due and the next gap starts from there: Lever Aura measured 4.48
  at 5 CPS and 7.89 at 10 (testkit 846); scheduled from the turn tick, 5.17 / 10.00. On different blocks every click needs a
  turn, so 20 CPS tops out at 10. Arrow Align Aura had the same bug (832: 4.57 / 8.00, now 5.00 / 10.00).
- **Pace a jittered click from when it is SENT, not when it is decided** (Auto E-Table, testkit 289). The solver stamped
  "last click" at the decision while Random Delay held the click back up to its max, so with Click Delay 400 and Random
  Delay 500 two Chronomatron clicks landed 3 ticks (150 ms) apart, and a bigger draw could swap two. Now: one puzzle click
  in flight at a time, stamped at the send; a Click Delay spacing check where every click is sent; Click Delay and First
  Click Delay floor at 150 ms in setter, loader and slider (they went down to 0 before; the old default was 200).
- **Time a trigger from the moment he SEES, not from a second clock.** Blood Camp's Trigger Bot aimed at Hit Lead 3 + the
  1-tick allowance + ping before the countdown's end, while the green box and Kill Sound fire at ping alone, so every click
  went out about four ticks early ("attacking them before the kill timer actually went off"). It clicks on the first tick
  with time left < ping ticks + Trigger Offset, so the delay is exactly -offset ticks (testkit 177, 792). **"The kill
  timer" in his Blood Camp reports is the Kill Popup, not a mob's box** (his log, 2026-10-18: a click "use at 0.8 ticks" on a
  mob's countdown in the same second the popup was armed 100 ticks before KILL). Every Blood Camp attack waits for
  `BloodCampMoveTimer.killTimerAllows(offset)` (no greeting: no wait), and the Trigger Bot also clicks any blood mob off its
  landing spot or past its 20-tick window the first tick his look meets it (1018-bloodtrigger-offset / -next-mob). The Kill
  Sound plays on that same hit tick, not at box green.
  EXCEPT under Never Miss (killer560, 2026-11-03: "if never miss is on then it should just attack the tick after it spawns"):
  there every attack reads `attackOpen = neverMiss || killTimerOpen` and `effectiveTriggerOffsetTicks` is 0, so neither the
  Kill Popup nor Trigger Offset holds a click (testkit 1102-bloodcamp-nevermiss-*).
- **"Clicked once per hit mob" left a live blood mob alone.** Owner camp 3 (2026-11-02 20:25:04): player #481791 was hit once,
  survived, and the floor pass logged "already hit" for six seconds while he looked at it (`FloorClick.hit` skipped it for
  good). Under Never Miss a hit mob still alive is clicked again at the weapon's pace (1102-bloodcamp-nevermiss-rehit).
- **A START handler registered late sees the previous tick's number.** `ClearExecutor` counted `clientTicks` inside its own
  START handler, registered from `LiveMapFeature` after Auto Routes and Auto Secret, so their START code in tick N read N-1.
  The count runs in its own Fabric phase ordered before the default (`ClearExecutor.TICK_COUNT_PHASE`).
- **A wait for the server's answer must not read a server stall as a failure** (killer560, 2026-10-15: "Warp 1 never landed
  - planning again from here" whenever Hypixel lagged). `util/ServerLag` measures stalls from the gaps between Hypixel's
  once-a-tick pings; every such wait takes `ServerLag.mark()` when it starts (or makes progress) and compares against
  `TIMEOUT + ServerLag.ticksSince(mark)` (`serverMsSince` for ms waits), 0 extra with no lag. The sim's server sent no
  per-tick pings until then (`roomsim/SimServerTicks` now does), so in the sim every `ServerTickClock` user had silently
  fallen back to client ticks (testkit 1460-sim-server-lag / 96-ar-1015-serverlag). Measured by wall clock between pings, a
  client that ticks slower than real time (the testkit's lockstep recorder, a hitch, a frame longer than a tick) read every
  tick as server lag and pushed tick-counted waits to the 600-tick cap (a 30 s stand outside Ice Fill, 1915). Tick-counted
  waits count only client ticks that ran with no ping; ms-timed waits keep the wall-clock measure.
- **A command the player triggers must not ask `ActionGate.tryAct`.** Its screen-transition settle fires on ANY screen
  change, the mod's own wheel included, so Pet Wheel's /pets waited 3-4 ticks after a quick flick (testkit 397: 4 ticks /
  200 ms on the old jar, 0 now). Send it at once and call `ActionGate.noteCommand`, which only books the tick for later
  automation. Screen-driven picks belong in the screen's own `keyReleased`/`mouseReleased`, not a tick poll.
- ActionGate's fixed settles (6 ticks after a >4-block jump, 3 after any screen change) held Breaker Aura's first dig after
  every etherwarp and leap (2026-10-22). An actor that re-picks its target each tick from his current position
  (`Actor.freshEachTick`, only BREAKER_AURA) waives both; the WORLD container rule still applies. Testkit 1022-ap3-breaker-*.

## Threads

- **Never read the integrated server's world from the render thread.** A `ServerLevel.getBlockState` in an unloaded chunk
  loads it synchronously on the CALLER's thread; the sim's `/goto` did that from its command and, in the testkit's lockstep,
  parked the render thread in `ServerChunkCache.getChunk` for good (jstack, 97-sim-goto-loop). Wrap the whole read-then-act
  in `server.execute`; hand results back with `client.execute`. `ModChat.send` is safe from any thread (it hops), but
  `client.player`/screens/the tab list are not - read `client.player` once into a local if a server task must have the UUID.

## Reach, chests and secrets

- **A left click turned into a right click is clean on both Grims when it replaces vanilla's attack at the HEAD of `Minecraft.startAttack` and calls `gameMode.useItemOn` there** (Left Click Opens Secrets, leftsecret-1105): that is the attack's own slot in handleKeybinds, after the hotbar keys, so no `set_carried_item` follows the use in the tick, the use carries the look the server already has, and it goes through `startPrediction`. `continueAttack` must be cancelled too while the button is held on the secret, or the held button sends a dig START the next tick. Testkit 1105-leftsecret-* (empty hand, sword, Dungeon Breaker): one use_item_on, 0 player_action, 0 GrimAC lines on default and experimental checks.
- **Dungeon key pickup range = Key Base Range + 5; the Magnetic Talisman does NOT apply to keys** (confirmed in game by
  killer560, 2026-10-06; the wiki x3 is for items only, keys are armor stands). Default 1.0 + 5 = 6 blocks. The talisman
  setting and the x3 were removed; an old `magneticTalisman` key in the config file is ignored.
- **QUOI's aura ranges are eye to block CENTER; ours are eye to block BOX.** A center distance is 0.5-0.87 longer than the
  box distance, so QUOI's 4.7 skull range is 3.8-4.2 to the box and was TIGHTER than our 4.5-to-the-box skull cap, though the
  number is bigger. Compare in one measure. Skull Range uses QUOI's center measure (testkit 842: 4.8 not clicked, 4.6
  clicked); the 20-tick world-change pause QUOI's AuraManager adds was already ActionGate's WORLD_SETTLE_TICKS (841).
- **Secret Aura needs no line of sight**: it picks by standing eye to the block's BOX within Aura Range (capped 4.5) and
  clicks through walls, as the server allows. Grab Secret's landing is chosen by that same rule; in the sim's Tic Tac Toe the
  chest is in a roofed pit no warp or walk reaches, and a landing on the board side of its wall lets the aura take it
  (93-solve-tictactoe-grab, killer560 2026-10-15: "it should aura the secret").
- **With Auto Close Chest on, a chest click never shows a screen** (his setting; "stuck on ice fill after grabbing the
  chest"). The mixin cancels the open-screen packet and answers with a close, so the lid is open a tick at most and
  `getOpenNess(0f)` (the previous tick's value) often reads 0. The reward-chest step read the screen and the lid only:
  three clicks, "did not open", then a ten-second "appear" wait under Autopilot (93-solve-*-chest-autoclose). Anything
  asking "did my chest click work" reads `util/ChestWindows` (fed at the packet hook's HEAD in every build): RewardChest,
  Secret Aura, Auto Boulder, Auto Tic Tac Toe. Do not deduplicate those packets by container id - a server numbers windows
  per player from 1, so every new sim world's first chest repeats the last world's id; the hook's double run (network
  thread, then render thread) is one packet object. Secret Aura re-clicked every such chest after 1 s and Auto Secret's
  wait ran to its limit until `AutoCloseChestFeature.autoClose` told the aura (`noteWindowOpened`, network thread).
- **A Shadow Assassin's leap refuses block clicks CLIENT-side** (killer560, 2026-10-20: a route froze on a chest). Hypixel's
  leap is a `ClientboundInitializeBorderPacket` that leaves him outside the client's border, and `useItemOn` returns FAIL
  before sending anything for a block outside it (javap, both versions; QUOI `fixInteract` and RSM `WorldBorderFix` bypass
  that check - this mod does not). Secret Aura booked each FAIL as an attempt and gave the chest up, so an await sat at 0
  for ever. It holds while `util/ShadowAssassinLeap.refuses` (clicks when the border lifts, 5 s ceiling), and an await
  lets go uncounted once a click was refused and none is coming (that route run then does not mark the room fully
  secreted). Sim: `roomsim/SimShadowAssassinLeap`; testkit 96-ar-1020-saleap / -letgo.

## Walking, fairy souls and other autos

- **SkyHanni's island graph positions are the FEET block, and the path points sit half a block above them** (his HUB.json:
  Village nodes at y 70, where a player stands). `AutoWalker` jumped whenever the next path point was 0.6 higher than his
  feet, so every gentle rise was a hop ("jumping the whole time"). Jumps come from the block in front (`WalkTerrain.rise`
  against his real jump height, Jump Boost included); testkit 746 counts 1 take-off for a course with one step. Straight
  under a path point (the foot of a ladder) atan2 of near-zero spun the camera; the walker holds its aim there (747).
- **A strict straight-walk check is not an off-route signal.** Planning a way back whenever no carrot passed
  `WalkTerrain.walkable` for 8 ticks fired at the end of testkit 746's wall (the shoulder check) and planned eight ways back
  in a second, then "no way out". Only fluids, a fall below the route, being pressed against a wall with nothing walkable
  ahead, or no progress (stuck) start one.
- **Auto Fairy Souls (fairy-souls-1017).** A ClearExecutor node that is not a `ClearNode.EtherNode` never gets its sneak:
  every "is this an etherwarp" test in ClearExecutor (forced sneak, eye height, item swap) is an `instanceof EtherNode`, and
  the `EtherwarpHopper` node was a plain `ClearNode`, so no hop ever went out ("the sneak never reached the server",
  1017-fairy-escape-ether, 14 tries, 0 server warps). `EtherNode` is extensible (`items()`, `range()`) and the hopper's node
  is one; 2 server warps, out of the pit, GrimAC clean.
- **`getDeltaMovement()` at START_CLIENT_TICK is the velocity AFTER last tick's friction**, so the next move is that plus this
  tick's push: at Skyblock 600 sprinting it read 0.92 while he moved 1.68 a tick. The walker's key choice plays each
  combination one move ahead from it (`pathfinding/WalkPhysics`); a tie between "too fast" and "too slow" must go to slow,
  or one 0.6-block push too many slid him onto the soul (rest 0.05 from it, aiming for 1.6).
- **Standing on a soul puts his eye inside its stand's box** (a 1.975-tall armor stand, eye 1.62): `AABB.clip` from inside
  returns nothing, so the click never went (1017-fairy-overshoot at 600: 700 ticks, 0 clicks). Inside the box is a hit.
- **The old walker's overshoot was the key rule, not the camera**: W was held at a carrot up to 8 blocks ahead whenever the
  body faced within 70 degrees of it, and released only when the runner said "in reach" (3.0). Measured on the L route
  (1017-fairy-overshoot, before -> after): past the corner at Skyblock 400 +2.28 -> -0.14/+0.08, at 600 +3.47 ->
  +0.10/+0.75, 600 with Jump Boost II +2.63 -> +0.27/+0.24; past the soul at 600 +0.70 -> -1.73 (stopping 1.7 short). Walk,
  sprint and Speed II were never past.
- **A soul's stand is not at its graph node** (doc-fairy-1101). NEU's `constants/fairy_souls.json` ("the soul itself") against
  SkyHanni's `fairy_soul` nodes: Hub offsets 0 to 3.6 blocks, often a block up or down (his 2026-11-01 picture: node
  90,68,-152, soul 87,68,-154). "Arrived" at the node with the stand's box 3.7 from his eye bounced WALKING/COLLECTING every
  tick and he stood there for good (testkit 1101-fairy-ledgesoul and every 1101-fairy-island run on the old jar: stuck on
  the first such soul). The runner now walks to a cell the stand is in reach and in sight from (`APPROACH`).
- **The walker's way back can fail for reasons that are not the terrain** (doc-fairy-1101, 1101-fairy-island-*): planned
  mid-fall (a cell he will never stand in: plan on landing), "0 goals" when the route's points are farther apart than the
  planner's 24-block box (goals now include points every 2 blocks along each segment), and a ladder whose top goes nowhere
  held W on it for minutes because "stuck" was ignored while on a ladder.
- **More walker traps found on the stand-in islands (doc-fairy-1101, 2026-11-02).** Stuck clocks in wall time call a client
  running at half speed stuck twice as fast: they count client ticks now. A way back measured against its END is "stuck"
  while it correctly walks away from the end first (round a wall, to a ladder): progress is a cell reached or getting closer
  to the next cell. A server teleport (warp, correction, the testkit's tp) left the stuck clock on the old distance and the
  walk was called stuck 2.5 s later: any teleport resets it. Standing beside a fence or wall post, the block holding his
  feet is the fence's, so a plan from it went straight through the fence line: plan from the open cell on his side of it.
  A fence or wall top is 1.5 high: no step up onto one his jump does not clear. Way-back goals must cover the stretch from
  him to the route's first point (the SkyHanni shortcut drops the node behind him, and the next one can be 35 blocks away).
- **GrimAC and water currents: Grim's, not ours (doc-fairy-1101, settled 2026-11-02).** On a ladder with flowing water
  touching his box, GrimAC 2.3.74 (experimental checks on) reports Simulation .004667/.0070 (= vanilla's current push .014/k)
  for the ticks he is on the ladder in the current, then decaying x0.8. With NO automation - the testkit holding plain W+A (or
  W+A+jump) at the spot - it flags the same way every run (26.3 8 of 8, 26.2 3 of 3; 1101-fairy-island-BAYOU_SPILL with
  -Pfairy.keys); W alone, W+jump, W+D never get him into that state and are clean. The walker flags only when it is in that same
  state. A ladder beside STILL water, or flowing water only over the ladder's top (1102-fairy-grim-flowladder-*, 12 runs per
  version, keys and walker), is clean. Grim's or not, it is a flag, so the walker must never be in that state:
  `AutoWalker.keepLadderOutOfWater` has the last word on the keys while he is on a ladder (water within two columns: no
  key toward it once where his speed carries him is within 0.12, the key away within 0.05), and the ladder centering
  predicts his rest point from his sideways speed (it overshot 0.25 into the pool). Releasing a key is not enough - his
  momentum carried him in at 0.03-0.09 a tick - and a guard that refuses every strafe toward a water side left him 0.31
  off center with his head caught under the next block (m12). Testkit 1102-fairy-spill-ladder asserts zero ticks on a
  ladder in water; m8 had 3-8 such ticks on 4 of 4 runs, m13 none in 36 (18 per version).
- **GrimAC SprintE is a consequence, read it with the Simulation line beside it (doc-fairy-1101 m14, 2026-11-02).** GrimAC
  2.3.74's SprintE (checks/impl/sprint/SprintE.java) flags a tick where the client is still sprinting, did not just start, and
  Grim's OWN prediction of the tick before had a hard horizontal collision. Grim judges hard against soft from its predicted
  vector (MovementTicker.isHorizontalCollisionSoft), so a tick it mispredicted (soul 3's stop on BAYOU_SPILL: Simulation .046
  then x0.546, ground friction) at a glancing wall touch reads "hard" while the client saw it as minor and kept sprinting.
  So the pair flagged BEFORE his stop, not on it, and no release order of W and sprint explains it. On 1.21.2+ Grim also forces
  forward input whenever it believes he sprints (PredictionEngine.loopVectors) and tracks the sprint speed attribute
  separately (MC-69459). The flag could not be reproduced on demand: 0 in 112 flat arrivals with a pillar corner and 0 in 54
  soul-3 runs on m13. So the fix removes the precondition instead: the last 8 blocks of a soul's route, and an approach
  walk, are walked. A running sprint is ended the vanilla way, with one tick with no key on the ground and with no wall
  contact (aiStep stops a sprint without forward impulse and sends STOP_SPRINTING before that move). Testkit
  1102-fairy-arrival asserts he is not sprinting on the last W tick: m13 failed that check in 5 of 7 arrivals, m14 in none.
- **Measure a planner rule on all five islands before keeping it.** "No step up from a ladder's top block" (2026-11-02) read right
  and fixed the one Gold Mines stall it was written for, but the full island run fell from 34/55 to 29/55 (Spider's Den 8 -> 5,
  more "in water, no way back") and it was reverted. Single-island runs also vary by a soul or two between identical jars.
- **A soul whose stand is out of reach must never be clicked anyway**: the pre-doc-fairy jar drew GrimAC Reach 3.02-3.26 on
  the stand-ins (it clicked once the node was within 3); the click now needs eye-to-box <= 2.85 and a clear line.
- **Hypixel's Jump Boost is VI, and a 6-block jump is not a bigger 1-block jump** (fairysouls-1103, his 2026-11-03 log: "Jump
  Boost 6 - jumps 6.13 blocks"). The planner already used the real jump (`getJumpBoostPower`, javap 26.3: 0.1f*(amp+1) on
  0.42); the execution did not: a running jump onto a step flew ~30 ticks and past it, and a way back was thrown away as
  "off the way back" three blocks up its own jump. A jump with >= 0.6 to spare is taken from a walk, never a sprint (a
  sprint jump adds 0.2), and steered in the air onto the cell with `WalkPhysics.ticksToLand` (vanilla y += v, v = (v-0.08)*0.98);
  a cell with a roof 2 up (a raised hole) is entered on the way up instead. While airborne only horizontal distance is "off".
- **A stand in reach mid-jump is not a place to click from, and its graph node is not where the soul is** (1103-fairy-boost6-pillar).
  With JB VI the stand came in reach for a tick on the way up; the runner stopped the walk there and he fell before the click.
  Then, landed on the pillar, COLLECTING measured "too far" from the soul's NODE (5 blocks below) and walked him back off,
  four times. The hand-off waits for the ground, and "too far" is decided by the stand's own reach.
- **Crouch cells**: `LocalPlanner.crouchCell` is a cell with no standing room but 1.5 over the feet (a top slab, an upper
  trapdoor); the walker holds sneak in and next to one (`AutoWalker.wantSneak`, the input mixin ORs it into `shift`) and the
  key model scales the push by SNEAKING_SPEED (`LocalPlayer.modifyInput` scales the input when `isMovingSlowly`). Reach from a
  crouch cell uses eye 1.27. Base jar: 1103-fairy-overhang never got under the slabs; GrimAC (experimental on) clean after.
  OPEN: not checked on Hypixel, whose server may not give a crouching modern client the 1.5-tall box (a setback there).
- **Simulated key presses (Fabric's `TestInput.pressKey`) never reach a key read with `KeyUtil.isKeyDown`** (GLFW's own
  state): the Start/Stop key poll did not see the testkit's K. The key is taken from `KeyboardHandler.keyPress`, the same
  event the "any key stops it" rule reads, so a press is either the toggle or a stop, never both.
- **The Chocolate Factory menu moved** (killer560 2026-10-11, "clicks the cookie but does nothing else"): per SkyHanni-REPO
  `constants/HoppityEggLocations.json` the Time Tower is 38 (39 is the Rabbit Shrine), the Coach 41, and 42/43/47-51/53 are
  menu buttons. `chocolatefactory/ChocolateFactoryMenu` is the one reader for the auto and the legit helper; check that
  file in his SkyHanni repo copy before changing a slot (testkit 754 puts traps on 39 and 42). At 1x the production lore
  has no "Total Multiplier" line (his 2026-10-18 log): the reader needed it, so no upgrade was a candidate and the helper
  highlighted nothing. A missing line means 1x when "per second" is there (`ChocolateFactoryMenu.read`).

## Testing automation

- Idle automation cost is invisible to 95-perf-features, which never switches automation on (`PerfBench.NEVER_ON`):
  measure it with testkit 95-perf-autoidle (fps-auto-1015). The biggest find there: Secret Aura's in-range cube asked the
  done-set first, and `done.contains(pos.asLong())` boxes a Long, so every block of the ~2,000-block cube cost 16 B every
  tick (32.9 KB/tick idle in the sim's Entrance) before any block was read. Read the block, drop what `classify` can never
  answer for, then box. The chunk-section palette prefilter alone did not help: a room's sections almost always hold a
  decorative skull.
- **The testkit finds Auto Routes events by log substrings, so a new log line must not reuse their wording.** The first
  `[AutoRoutes] trace` start line said "await secret 1", which is how 96-ar-crypt finds AwaitEvents' count line, and its
  order check failed on a logging-only change. The trace writes "1 secret"; grep the testkit before wording a line.
- **A test's "player click" must be a real click, or experimental Grim flags the test (brksec-1105).** 1018-breaker-secrets-on
  drew NoSwingBreak* and RotationBreak* post-flying on experimental GrimAC: the dig was the TEST calling
  `gameMode.startDestroyBlock` from a client task (after the tick's movement packet, no swing, the look off the block); the
  mod's breaker guard and Zero Ping hook send nothing. A case standing in for the attack key turns the camera onto the block,
  waits two ticks so the server hears the look, checks `mc.hitResult` is the block, then `pressKey(o -> o.keyAttack)`, so
  vanilla's startAttack digs and swings. Before hunting a flag in the mod, check who sent the packet.

## Auto Clear kill speed (autoclear-speed-1020)

Measured with testkit 1020-sim-ac-speed (whole sim floors, the sim's live mobs, Auto Clear alone with the Spirit Sceptre
and AOTV) and the `[AutoClear] room time:` / `trip time:` lines (`autoclear/RoomClock`), which every real run logs too.
- **A trip's price must come from trips.** The straight distance over the 56-block hop range is one warp for any in-room
  trip, so every trip was "~7 ticks (1 warp)"; Hypixel's took 12.7 at 2.1 warps (his study). `warpsTo` (one only when a
  sneaking look lands on the block) and `tripTicks` (this run's mean per warp count) price them now. Counting a trusted bat
  as its launch alone was wrong: the room's last bats are waited for whole (bat waits 404 vs 206).
- **Plan on the reach that is worth having, not the full reach.** Stand blocks a straight bat reaches from 24 blocks gave
  fewer trips but 20-block flights at 1.02 b/t, slower than a 6-tick trip and the shot down (firing 928, bat waits 532).
  `usefulBatRange()` is the distance a bat covers in a one-warp trip plus the shot: ~10 in the sim, ~17 on Hypixel's trips.
- **A bat aim must hold, and must hit something.** The sim's bats leave the look by up to 0.06 an axis, so a 20-block shot
  at a lone mob passes it and flies on; and an aim that meets nothing within 24 was called a burst "in the air at 24", where
  the sim's bat flies on to ~29.6. `batImpactOrAir` and `batAimHolds` (3 degrees either way) refuse both. A bat that missed
  (tag unchanged) is not "too weak": it no longer throws away the one-bat trust.
- **A fresh press is not bound by the held use's 4 ticks** (`handleKeybinds`: `consumeClick` -> `startUseItem` with no
  `rightClickDelay` check, javap 26.1.2). Presses every 2 ticks: GrimAC clean (62-argrim-1020-acbat, 259 uses in 600 ticks).
- Waits a player does not pay: the body-release tick before a trip, the swap tick after it, the planner for a block one
  look lands on (`ClearExecutor.etherDirectFast`: 3 ticks against 6-8), 30-tick roam looks, standing in a room until the map
  checks it after the last kill, and waiting for trusted bats in flight (standalone runs only). The turn tick before a use
  and the wait for the landing's answer stay.
- Sim floor 1 of `killer560s-mod-logs/acs/codes-stuck.txt` (Tomioka) leaves a stand block with no etherwarp out: the base
  jar gave up 10 rooms there ("no etherwarp path (4 tries)", every one from the same spot); the new plan does not stand there.

## Key rooms (autoclear-keys-1020)

The sim side (`SimKeyRoom`) is in [SIM-SECRETS.md](SIM-SECRETS.md).
- **The room in front of a shut wither door holds its key, and an insta clear loses it** (killer560). The tracker's
  evidence (insta-clear.json, 2026-10-20): etherwarp into an UNDISCOVERED room insta clears 13 of 15, UNOPENED 15 of 74,
  DISCOVERED 1 of 75; the five walk-in "insta" clears flipped 3.6-12.5 s later (kills), every real one in 150-900 ms. So
  `autoclear/KeyRooms` rooms are walked into, never warped into, until entered. Testkit 1020-sim-ac-keyroom (with a
  control that turns the rule off and must lose the key).
- A trip that moves him after a door trip finished must clear `doorTripDone`: a key that dropped after he reached the
  door sent him off for it, and back at the door `openDoor` read "arrived" and stopped "out of reach from where the map
  put you".
- In the sim a wither-door click with an ability item in hand went to `SimAbilities` (a Wither Impact into the door) before
  `SimDoors`; the door now takes the click in an earlier phase and the ability skips door blocks.

- Auto Fairy Souls on the REAL hub (fairyhub-1105, testkit 1105-fairy-hub on the owner's private capture): SkyHanni's HUB
  graph has edges no walk can take - a 7-block straight-up edge at 13,80,-64 with nothing to climb (soul 28), a 1.5-block
  step into the Rabbit cave's doorway at 68,62,-6 (soul 7), vines that start 2 blocks over the floor at -68,72,-70 and are
  reached only by jumping from the top slab beside them (soul 26). A failure ON the route (or beside the soul) now skips the
  soul at once: a /warp walked the same route and failed the same way, 30-40 s a soul. Only a fall off the route warps.
  Graph points sit 1.5 over the feet (`NavigationManager.centre` of a node one block above the floor), and a path planned at
  the foot of a climb starts at its top (SkyHanni's shortcut drops the first node): `distanceSqToRoute` counts that leg too.
- Clicking a soul from a ladder (soul 48's hut, 97,70-75,-28): with no key down he slid out of reach while the look turned,
  four approaches in a row, then gave up. The runner holds sneak on a ladder while it clicks (vanilla stops the slide).
- After a /warp the rescue budget starts over: the 8 ways back spent before it failed the walk back 3 s after landing.

## Blink, Inventory Walk, Zero Ping Etherwarp and Zero Ping Chain (blink-1025)
- GrimAC TimerLimit caps a packet hold at about 1.1 s: the timer balance may fall at most ping-abuse-limit-threshold
  (1000 ms) + TimerA drift (120 ms) behind real time, and every held tick the burst brings back is 50 ms. Measured: 22
  ticks clean every time (walking, standing, 100 ms), 23 = one TimerLimit every time. It is a cap, not a pool: the
  release gives it all back, back-to-back 22-tick holds are clean (testkit 1025-blink-sweep-*, 1025-blink-pulse-max).
  Grim TickTimer requires a tick-end before every flying packet, so dropping or reshaping tick-ends is no way round it.
- A sword hit is `ServerboundAttackPacket` since 26.1 (javap 26.1.2 and 26.2: `MultiPlayerGameMode.attack` sends it);
  `ServerboundInteractPacket` is only the entity right click. Code that looks for hits by Interact misses them all - Blink
  did; `autoroutes/mixin/RightClickSendMixin` still counts actions that way (not changed).
- What a held burst breaks on Grim: two hits in one burst = Hitboxes (the server applied the first hit, then judged the
  next); a dig start and finish together = FastBreak (diff), a held finish just before a live next start = FastBreak
  (delay); a bow draw start and release together fire nothing. Blink sends hits, every dig edge and charged-use edges live.
- GrimAC MultiActionsC flags a container click while the server has a movement key in his last input, a sprint or a
  sneak. Inventory Walk holds such a click until a still tick has reached the server (Stop To Click).
- GrimAC 2.3.74's classes that read a container click (javap, doc-invsort-1101): MultiActionsC, PacketOrderA and K,
  BadPacketsP, CrashD/F, Post. PacketOrderA (a pickup and a shift-click in one tick) has `isApplicable` = client version
  1.12.2 or older, so it never judges a 26.x client; none of them counts clicks per tick. The Inventory Sorter's Instant
  sends a whole plan in one tick for that reason, behind the same still-input wait as every click.
- MultiActionsD is the same check on the menu CLOSE packet (javap 2.3.74), and GrimAC 2.3.74 has no InventoryA-D: MultiActions
  C/D and PacketOrder are its inventory checks. Both cancel the packet they flag, so a flagged click is never applied and
  the item snaps back. Until campaign-invwalk-1027 every close made while walking with Inventory Walk drew D, a click the
  frame a menu opened (before the feature's tick saw the screen) and clicks + close in one frame drew C and D, and with a
  Blink hold running every click drew C: Blink sends menu clicks live, so the server judged them on the moving input it
  had before the hold (testkit 1027-iw-*, shipped jar 3011406a). Fix: closes and button clicks are held with the clicks,
  in order; a held packet forces an all-keys-up input that tick (`invwalk/mixin/InventoryWalkInputMixin`, priority 2000,
  after every automation's input mixin); "still" is judged on what LEFT the connection (`InventoryWalkFeature.noteLeft`
  from Blink's send hook); during a hold the clicks join the hold behind the still tick (`BlinkFeature.clicksJoinHold`).
  Test helpers that close a menu with `setScreen(null)` send no close packet at all - `onClose()` does, as Escape does.
- Hypixel's "Bad client inventory move." limbo is movement after a click in his OWN inventory (window 0) with no close of
  window 0 since, worst with an item on the server's cursor (invwalk-1105; his 26.3 log 2026-11-05 23:14:34: a held pick-up
  of slot 40 went out, he walked 3 ticks later carrying it; 23:15:54: two pick-ups and a swap sent standing, then walking with
  the inventory still open). GrimAC does not flag it, so only his log shows it. Fix (vanilla's own shape): in window 0 the
  pick-up stays unsent while he walks carrying the item; the put-down sends one keyless tick, the clicks, then
  `ServerboundContainerClosePacket(0)`, so the server never has a window-0 click unclosed while he moves. Server menus keep the
  plain still-tick hold and are never closed; the SkyBlock Menu star is excluded, and the craft grid must be empty. Testkit
  1105-iw-carry fails on f76d17ccc (the old jar stops him while carrying).
- Carry In Chests (iwbypass-1108, cheat, default OFF) gives server STORAGE menus the same carry: chest, large chest, ender
  chest, barrel by title, and Hypixel Ender Chest pages / backpacks below their top row (buttons). The clicks then take the
  full stop and no close (a close would shut the menu). Every other server menu is buttons and keeps the plain hold. Two
  faults the 1105 carry had: (a) only a CLOSE flushed the waiting pick-up, so a put-down that could not wait (into the craft
  grid, which then is not empty) reached the server AHEAD of its pick-up; now any other click, button or close sends the
  carried clicks first, in order. (b) If the client cursor goes empty while clicks wait (a server resync, a new or closed
  menu), the server has reset it, so the waiting clicks are dropped, never sent later. Vanilla applies a click locally before
  sending it, so `containerMenu.getCarried()` at send time is already the state after the click. Testkit 1108-iw-chestcarry.
  Same-tick (1108-iw-chestcarry-sametick, clean on default and experimental Grim at every latency): his container click and
  hotbar swap with the chest open, and his right click / attack after Escape, each share a server tick with the replayed clicks
  (his click made while the replay is held joins the batch behind it). A use or attack pushed through `gameMode` while a
  container screen is open is not a player action (the screen takes the mouse) and flags by itself (MultiActionsC; Post when
  sent after the move at END), so a screen feature's same-tick rows put his world clicks after the close through vanilla's
  keybinds (SameTick USE_KEY / ATTACK).
- Inventory Walk's Look Key turns the view through `MouseHandler.turnPlayer` from a HEAD inject on `onMove` (26.3
  `(JDDDD)V`, 26.2 `(JDD)V`: read x/y with MixinExtras `@Local(argsOnly = true, ordinal = 0/1)`, which both share), then
  warps the cursor back to `xpos/ypos` so the menu sees no move (26.3 is SDL: `SDL_WarpMouseInWindow`; 26.2 GLFW
  `glfwSetCursorPos`; both in `compat/McWindow.warpCursor`). Reading the cursor from GLFW/SDL instead would miss the
  testkit's synthetic moves. Testkit 1105-iw-look (same-tick shift-click, close, walking click).
- A turn applied at START_CLIENT_TICK must come before every use that tick (leapsame-1105, testkit 1105-leaplook-same-*). Leap Takes Target's Look ran after `LeapManager.onStartTick` in Fast Leap's tick, so a Fast Leap use in the turn tick left at the old look and the movement packet reported the new one: BadPacketsJ on default and experimental GrimAC, as did a use sent between ticks at the old look just before the turn tick. Fix: the turn runs first, and waits a tick when a use_item already went out since the last client_tick_end (`LeapRotation.useThisTick`, fed by the send tap). A container click, an attack and the teleport accept in the turn tick carry no look and are clean on default and experimental GrimAC; a hand right click is read in handleKeybinds after START, so it carries the turned look.
- Two item uses in one client tick with DIFFERENT looks are BadPacketsJ: each use must carry a rotation that tick's single
  movement packet reports (javap 2.3.74: a use whose yaw/pitch differs from the tick's previous use flags, and at the tick
  packet the uses' look must equal the movement packet's). Uses at the SAME look are clean in any number: a vanilla client
  sends them itself (`Minecraft.handleKeybinds`: `while (keyUse.consumeClick()) startUseItem()`, two clicks inside 50 ms).
  So differently aimed warps (Auto Routes' nodes) go one per client tick (or several per SERVER tick through Blink The
  Chain), while same-look warps can go N in one tick: ZPEW Warps Per Tick, testkit 1027-zpewmt-* (2, 3, 5, 10 and 20 in a
  tick, 0-300 ms, 26.1.2 and 26.2, GrimAC clean; the server casts each from the previous landing).
- Different looks in ONE real tick (owner 2026-10-27, testkit 1027-zpewlk-*, javap GrimAC 2.3.74): every look needs its own
  tick packet, and only a whole extra client tick will do. BadPacketsJ resets at each tick packet and compares the last
  use's look with that packet's UPDATED look (`canSkipTicks` is false for 1.21.2+ clients, so no lastYaw fallback): the
  order is use, then the tick packet reporting its look. Measured: RSA's shape (n uses at n looks, nothing between) =
  BadPacketsJ; look packet BEFORE each use (a 1.8 server's way) = BadPacketsJ + TickTimer; use then a look-only packet =
  TickTimer (a flying needs a tick-end before it) and Timer/TimerLimit (each tick packet is +50 ms on the timer balance,
  whose floor is the last answered ping - 120 ms, so the headroom is about a ping plus 120 ms and does not come back while
  the client ticks normally). The 1.17 duplicate-packet exemption is for 1.17-1.20 clients only. What works is Blink
  Charge's bank: `BlinkCharge.spendTick` runs a whole tick (use, his movement tick with the body at that look, tick-end)
  per extra look - Auto Routes' Charged Chain and ZPEW Aimed Warps. It needs the campaign-blink-1027 fixes (a teleport's
  confirm held behind its ping answer): on 3011406a the same burst drew AimDuplicateLook, GroundSpoof, Simulation and
  TickTimer at every landing. Spend only while `BlinkCharge.shotCovered()` (a burst right after a kept teleport drew
  TimerLimit), and turn the first look from the yaw that last went ON THE WIRE (Charge keeps turn-only ticks off it, so
  the running yaw can be a whole turn away; a look jump over 320 is AimModulo360). The test's first path, a square
  (+x, +z, -x, -z, +x), spun the client a full 360 between two ticks and drew AimModulo360 with or without the burst.
- A spent tick must put its look on the wire itself when vanilla will not (zpew263-1101, 2026-11-01). Vanilla's
  sendPosition reports a look only when it differs from the last one IT sent, and a banked tick's turn never reached the
  server, so a burst whose first look equals the client's last "sent" look sent USE then the tick-end alone, and GrimAC
  compared the use with the pre-turn look: one BadPacketsJ per burst (wire trace, 1027-zpewlk-0, 2 of 2 runs on 26.2,
  sometimes 26.3). `BlinkCharge.spendTick` now sends a Rot when the wire look (RETURN of Connection.send) differs from his
  look - never when it matches (AimDuplicateLook). On 26.3 the spent tick also needs `McCompat.sendPlayerChanges` (26.3
  sends his move from Minecraft.tick); without it every spent tick after the first flagged (flags = n - 2, W-2).
- RouteExecutor assumed one warp in flight (one `warpLanding`, one `teleportPacketSeen`). Zero Ping Chain keeps a queue
  of predicted landings (`inFlight`), reads `feet(player)` instead of `player.position()` while it is non-empty, and
  matches each server packet against the queue; a packet that matches an older entry confirms it and every older one.
  The in-flight cap must sit inside Blink's 22 ticks or a blinked chain stalls until Blink's own limit releases it.
- The testkit's PacketTrace hooks `Connection.send` as Blink does, so with Blink holding it counts a held packet when
  made and again on release; judge blinked runs by the mod's log and the server's counters.
- Grim's lag anchor (Timer/TimerLimit `lastMovementPlayerClock`, javap 2.3.74) is the send time of the last ping the
  client had answered when its next counted tick arrived. Answered live, any tick sent while banking (an action, or a
  normal one once the bank is full) moved it to the present and the bank was gone: Blink Charge's first run flagged
  Timer on every shot. Charge answers every ping a steady 1050 ms late, so the anchor is always a second old and
  TimerLimit's own floor (real time - 1120 ms) binds - a full bank whatever happens meanwhile. Answers must never be
  reordered against a teleport confirm (Grim then drops every move after it) or against a hold's queue
  (TransactionOrder): flush the waiting ones first. Grim counts one tick per client tick (the flying, else the
  tick-end); a flying confirming a teleport is not counted. Nothing else resets the balance (testkit 1025-blink-charge*).
- Rework (blink-rework-1025, 2026-10-26), measured on GrimAC 2.3.74 with nothing released early: one sword hit, two
  swings (only the first did damage: both reach the server in the same tick and the second lands in hurt time), one stone
  block mined start to finish, towering with cobblestone and five bow shots, each inside a 20-tick hold, were all CLEAN
  (testkit 1025-blink-hold-hit-1/-2, -hold-dig, -place, -bow; frozen dummy). The Hitboxes/FastBreak above were with the
  old 22-tick holds and a moving target; re-measure before relying on either.
- A banked Charge tick drops its move, and when that move was a turn of the camera vanilla never reports the look again
  (it counts it as sent). The next shot then ran every tick with the server on the old look: GrimAC Simulation 0.13-0.34
  on all 20 ticks after a 180 turn. `BlinkCharge` now owes the look and puts it on the next move sent (testkit 1025-blink-charge).
- Releasing a hold with its moves and tick-ends dropped (the old refusal and world-change path) leaves a held sprint
  command after his last flying with a ping answer behind it and no flying between: GrimAC Post "entity action" (Post
  flags a post packet followed by a transaction answer while a flying is the last tick packet). Release everything, in order.
- An in-place server teleport while charging (Hypixel's re-sync) keeps the bank by design (killer560, 2026-10-25), but on
  GrimAC its confirm then reaches the server before the answer to the ping GrimAC sent with the teleport (answers wait
  990 ms): GroundSpoof, BadPacketsN, AimDuplicateLook, TickTimer, and the next shot was set back (testkit
  1025-blink-charge-inplace). Answering early resets the lag anchor and the bank with it. OPEN: his call.
- A menu click while the server still has him sneaking or moving is MultiActionsC and GrimAC drops the click. During a
  hold the click goes out live but the input change (stopping the sneak) is held, so stop sneaking before the hold.
- Testkit: a Session class (`62-argrim-session`) runs only if a -Scenario part contains the session's own name; run
  single cases with `-Scenario '62-argrim-session+,<case>,<case>'`.
- Stress pass (blink-stress, 2026-10-26, testkit 1026-blink-*, GrimAC 2.3.74, 26.1.2 and 26.2): every Hold Ticks and Charge
  Ticks 1-20 (both Charge modes), key mashing, both keys at once, switching off mid-hold/charge/armed, chest/chat/pause/
  inventory screens, ice, soul sand, slabs, stairs, water, ladders, cobwebs, sneaking, edges, walls, Speed/Slowness/Jump
  Boost, pearls, AOTV, Hyperion, chained etherwarps, LCEW+ZPEW, eating, bow, place/dig/lever/chest, death, leave/kick and
  rejoin, dimension change, 0-300 ms, a 2.5 s frozen client, 250 back-to-back holds, 120 shots, 10 minutes charging: all
  clean once the fixes below went in.
- A released hold went back through Charge's send hook when Charge was on (holding off = Charge's turn), so its tick-ends
  could be banked and their moves dropped, and its ping answers queued behind newer traffic. The burst is sent by one
  thread with a marker that skips Charge (`BlinkFeature.releasingThread`). Likewise a banked turn's owed look must ride the
  next move whether or not a hold is on (`BlinkCharge.carryOwedLook`, called first in `BlinkFeature.onSend`).
- A Charge shot fired the moment the bank refilled after a moving teleport (~1.04 s after the confirm) drew Simulation
  0.10 decaying over 8 ticks (ground friction) and was set back; 60 ticks later it was clean, and a tp right after a shot is
  clean. Banking now waits the answer delay + 150 ms after a moving confirm. Answering pings live for 250 ms after the
  confirm instead did not fix it and drew Timer on back-to-back warps (reverted, 0c634e76).
- The harness's two-way LagSpike (1.5 s) draws GrimAC TimerLimit with Blink OFF (8 flags, 1026-blink-controls); with a hold
  14, while charging 29 and the next shot is set back. A frozen client (render thread asleep 2.5 s) is clean in all cases.
- A Hold key down when the world goes (leave, kick, death screen) must latch: the no-world tick path reset `keyWasDown`
  and the latch, so a rejoin with the key still down started a hold during the join (1026-blink-disconnect).
- A setback right after a shot is told from a warp by where it puts him: back within 0.75 of the shot's start is "SHOT SET
  BACK" (logged and reported); the watch is cleared on a world change, or the next world's join position reads as one.
- OWNER DECISION (killer560, 2026-10-27, final, not a setting): "the blink should always be saved through leaps and
  teleports and stuff like that"; "it should never drop blink if possible except if i release it". Charge keeps its bank
  through every server teleport (answers stay on their steady delay, no flush, no post-teleport pause - the 2026-10-26
  settle pause and the flush on a moving confirm are gone), respawns and dimension changes, configuration phases (answers
  sent first), a hold starting, the Skyblock gate, screens and AP3 stopping. Until campaign-blink-1027 GrimAC flagged and
  ignored everything after a kept teleport; now the confirm waits behind its ping's answer (next entry) and only a shot in
  the first ~1.5 s after it is partly set back; he accepted the flags either way.
  Do not revert. Impossible to keep: a hold's packets across a world change (old-world moves), anything across a disconnect.
- What keeping the bank cost on GrimAC until campaign-blink-1027 (blink-keep-1027, testkit 1026-blink-keep-warp,
  1026-ap3-blink-leap, 1027-blink-c-keep-delay): Simulation = the teleport's distance, GroundSpoof, TickTimer, BadPacketsN and
  AimDuplicateLook (one per kept teleport, 12 for Charged Chain's 12), and then GrimAC IGNORED EVERY MOVE until the next
  teleport - not just the next shot: a walk 20 or 100 ticks after a kept teleport and shots 2-200 ticks after it never moved
  him on the server (client and server 6 blocks apart), in place or moving. Cause (javap SetbackTeleportUtil.checkTeleportQueue,
  2.3.74): GrimAC takes a flying as the teleport only while its last received transaction is EXACTLY the one it sent before
  the teleport; earlier it waits, later it flags BadPacketsN and keeps a setback pending. Charge sent the confirm a second
  before that answer. Fix (campaign-blink-1027, `BlinkCharge.tpSegs`): the confirm and everything after it wait right behind
  that answer (the newest one waiting when the confirm is made; one segment per teleport, so a chain's confirms each follow
  their own answer), menu clicks still go live, and the bank is kept. Result: the teleport, a walk after it and Charged Chain
  (12 warps) are clean, and a shot 40+ ticks after a moving teleport (60+ after an in-place one) lands clean. Inside ~1.5 s
  the shot is partly set back with TimerLimit: the held second plus the bank exceed GrimAC's ~1.1 s lag allowance, and the
  ticks he moved in during the hold are backed again on the next still ticks (`unbacked`). Releasing the confirm behind the
  FIRST answer after it instead (v3 of the campaign) is BadPacketsN on every teleport. Dropping still ticks inside the hold
  (v6) made the early shots worse (Simulation 0.10 decaying, fully set back): reverted.
- A menu click or close during a teleport hold (campaign-blink-1027): live, it reached GrimAC with the moving or sneaking
  input from before the teleport (the new input waits in the hold) and MultiActionsC cancelled it (1025-blink-charge-rapid:
  the first click after three sneaking warps never arrived); held, it would be a second late. Now it goes at once when the
  server already has him still, else every waiting answer and everything held go first, in order (each confirm right behind
  its own answer), then the click; the bank is kept and backed again. Same rule as Inventory Walk's clicks in a Hold
  (campaign-invwalk-1027, `InventoryWalkFeature.serverHasStillInput`).
- A Hold key down across a world change started its new hold on the new world's first tick, holding the spawn teleport's
  confirm: GrimAC Simulation 0.03-0.065 on 19-20 ticks (1026-blink-death (a), on 3011406a too). The new hold now waits for
  the spawn teleport (`BlinkFeature.spawnWaitTicks`, at most 40 ticks) and then for him to stand on the ground: he spawns 0.1
  above it, and a hold over that drop drew Simulation 0.029 decaying in 1 run of 2 on 26.2 (`spawnSettle`, at most 60 ticks).
- A server teleport that lands while he is sprinting draws GrimAC Simulation 0.196 with Blink idle (1027-blink-c-tpsprint,
  Charge off, 26.1.2 3 of 6 rows, 26.2 4 of 6; the same rate with Charge on). GrimAC's, not Blink's; compare against that control.
- Charge's answer delay followed the cap (50 x Charge Ticks - 10 ms), so a bank kept when Charge Ticks was lowered (owner:
  the bank is kept) was bigger than what that delay lets GrimAC cover: with Charge Ticks 2 and 17 banked the delay was 90 ms
  and the shot drew Timer however long he stood (1027-blink-c-mixed). The delay now covers max(cap, banked).
- OWNER DECISION (killer560, 2026-10-27, after his p3sim field test "if I teleport to someone up there (a leap) that's when it
  tends to fail"): after a kept teleport fire the kept bank on the first tick the server will accept it, automatically,
  never dropping it. `BlinkCharge.shoot` defers a shot while the bank is not covered (a teleport confirm held behind its
  answer, a Blink hold running, or ticks sent since not backed again) and fires it the tick it is (`covered()`, logged; at
  most 3 s); AP3's Blink Release holds on its node meanwhile and finishes the tick the bank is covered, so the shot runs
  along the next node. Measured on GrimAC 2.3.74 (1027-ap3-blink-leap-now/-10): released at the leap the shot waited 1.95 s,
  10 ticks after it 1.51 s, both landed the full 5.39 blocks, clean; on c9521191 both were set back (TimerLimit). The wait
  needed him still. SUPERSEDED (blink-fastcover-1027): the floor is NOT the teleport's ping. GrimAC's Timer moves its floor
  (`lastMovementPlayerClock`) only on the FIRST transaction answer after a move packet (`Timer.onPrePredictionPacketReceive`,
  `hasGottenMovementAfterTransaction`), to the newest answered ping's send time at that moment. So at a teleport every waiting
  answer up to the teleport's ping goes back to back, the confirm right behind (GrimAC needs exactly that answer last:
  `SetbackTeleportUtil.checkTeleportQueue`), and the floor lands on the OLDEST withheld ping: the cover survives the leap and
  the shot waits one tick (40-54 ms, clean at 0-300 ms, 26.1.2 and 26.2). The cover kept is that oldest answer's age, so a
  second teleport right after the first (only young answers left) cannot keep it: that one takes the old hold. Backing the
  shortfall on still ticks right after the confirm drew Simulation on the shot (fc3). `TimerLimit.limitFallBehind` also caps
  the floor at now - 1000 ms (`limitAbuseOverPing`), which is why a bank is 20 ticks at most. A shot still uncovered after 10 s
  is not fired (bank kept, logged). Tried and reverted: skipping his physics through the hold so its ticks cost nothing (the first ticks
  after still drew Simulation 0.025 decaying, even with input changes carried to the next sent tick).
- p3sim runs GrimAC (it has /grimac; "/grim alerts" works for him) and sends about 4 pings a server tick, so "2 pings" is not
  a server tick there. His alerts on 2026-10-27 16:54 traced to three Charge causes, each reproduced or read in Grim 2.3.74:
  (1) Post "entity action": a sprint action sent in a BANKED tick sat after his last sent move, and the next answer made
  Post flag it (Post.onPacketReceive: a post packet after a flying, then a valid transaction). Actions now wait for the next
  move that is sent. Not reproduced on the testkit (it needs a tick with no due answer at its start). (2) TimerLimit on the
  post-leap shot: the batch's oldest answer was 907 ms, and 20 ticks plus the firing tick need over 930 ms with Grim's
  120 ms drift; answers now wait 1050 ms (50 B + 50). The margin is thin whatever the delay: TimerLimit never lets
  the floor go back more than 1000 ms + drift (1120 ms with Grim's default 120), and a full bank plus its firing tick
  is 1050 ms, so 70 ms of network jitter is all a 20-tick shot can absorb (1150 ms drew TimerLimit twice at 150 ms).
  (3) Simulation 2.07 decaying x4: a superboom's knockback while the answers were held - Grim predicts a push only once the
  ping it sent after the push is answered. That answer and all before it now go at once (BlinkCharge.onServerPush from
  handleExplosion / handleSetEntityMotion over 0.1 blocks a tick); testkit 1027-blink-c-p3sim (b)/(c): GroundSpoof +
  Simulation 0.5-0.75 before, clean after. (4) Simulation .001-.7 where Breaker Aura mined the wall
  he walked through inside a shot: GrimAC predicts air only for a VANILLA-instant dig (CheckManagerListener.handleDigging:
  BlockBreakSpeed.getBlockDamage >= 1); Hypixel's Dungeon Breaker on stone bricks is server-instant, so GrimAC applies the
  break at the ping after the server's block update - a second later with Charge holding answers. While Charge is on:
  no zero-ping air for a non-instant dig; block updates near him that change collision are held on the client until the
  answer to the ping GrimAC sent just BEFORE the block packet goes (BasePacketWorldReader.handleBlockChange: sendTransaction
  then addRealTimeTask(lastTransactionSent)); marking the ping after it left the client a move behind GrimAC (setback to the
  wall, Simulation .0077 decaying). The answer goes only at a tick start and the change is applied before his physics; a shot pauses
  at a wall Breaker Aura dug that is still there (about 1.3 s at 150 ms) and goes on covered; Breaker Aura never re-digs
  a held block (that drew AirLiquidBreak x108). Answering the block's ping early instead cost the bank its cover (Timer on
  the rest of the shot). The testkit's HxAbilities now emulates the Dungeon Breaker (1027-blink-breaker-walk/-leap/-stand).
- The same lag allowance is shared by a hold and the bank: a hold while charging sends the waiting answers (TransactionOrder)
  and its own in its burst, so after it GrimAC covers only ~2 ticks of the kept bank. Before campaign-blink-1027 the shot after
  a kept hold drew Timer/TimerLimit and was half set back however long he waited (the bank was full, so nothing re-banked:
  1027-blink-c-interleave 49/60 rows). Now the bank is kept and the next still ticks are withheld uncounted until it is covered
  again (0/60). A shot fired within the same second as the hold, or inside the hold, cannot be covered (interleave-now 12/16,
  order (e)); that is the allowance, not a bug.
- Charge's "a tick is open" flag must move only on the client's own sends. A tick that is not banked sends its kept move
  from inside the tick-end's hook; counted as the client's, that re-send re-opened the tick, so while he moved with Charge
  on no ping answer was ever sent at a tick start (AP3 blinkcharge 3, then running: 18 answers waiting, the oldest 1660 ms
  at a 140 ms delay; standing still hid it because a banked tick drops the move; at a 990 ms delay the oldest reached 2501 ms
  after a short run, and nothing bounded it). Testkit 1026-ap3-blink-* checks the oldest answer while he runs charged (2026-10-26).
- A feature drives Charge through `BlinkCharge.beginFor(owner, n)` / `shootFor(owner, n)` / `endFor` (AP3's Blink nodes):
  the key is ignored while an owner holds it, `cap()` is the owner's n, and a partial shot leaves the rest banked. The shot
  runs at Blink's START with the input a driver installed at the END before, which is how AP3's release fires along its walk.
- Zero Ping Etherwarp's Works For (2026-10-27) knows a warp's sender only by the marker set around its send:
  `ZpewSource prev = ZeroPingEtherwarp.sending(ZpewSource.X); try { send } finally { ZeroPingEtherwarp.done(prev); }`.
  A new feature that sends an etherwarp use without it is counted as Manual Etherwarps. The map's executor
  (`ClearExecutor.hopSource`) picks Pathfinding, Auto Secret, Auto Clear or Interactive Map from who is running.
- When a feature moves from the cheat build to both, grep the OTHER features for `CHEAT_FEATURES_ENABLED && <it>`: ZPEW's
  move (bc3a43d1) left two such gates, so in a legit jar Overlay From Prediction did nothing and Smooth Teleport glided
  every predicted warp from where he was - the camera jumped back up to 8 blocks and slid forward (fixed 41c51fc5;
  testkit 1027-zpewc-overlay, -smoothtp fail on the shipped 3011406a legit jar and pass after).
- ZPEW measured (zpew campaign 2026-10-27, testkit 1027-zpewc-*): packet for packet the same as a ZPEW-off run, and no
  packet ever sent from a zpew frame; against the testkit's etherwarp every shape it models the same way is exact (error
  0.000, 0/50/150/300 ms, tuners 0-4 to the last block in range and not one past it, falling, jumping, walking, chains
  of 5 and two uses on consecutive ticks - the campaign's "two uses in one tick" case pressed the key twice with
  `pressKey`, which holds each press a tick (ZPEW log: predictions at ticks 1991 and 1992); 1027-zpewmt-* has the real
  same-tick case). When the server just ignores a use (no teleport at all), the camera stays on the
  predicted landing for Give Up After (1000 ms default) and then snaps back; any position packet ends it at once.
- A shot replays banked ticks inside one real tick, so a feature that steers him per tick must run inside it: a 20-tick AP3
  release on the keys chosen at the END before ran him 18.75 blocks straight off S2's platform (Mod Only Test log
  2026-10-27 00:15:34). `BlinkCharge.addShotTickEndHook` runs after each shot tick's movement and tick-end, and
  `addShotGuard` can end a shot before a tick (the rest stays banked). An `endFor` from inside the loop is deferred
  (`pendingEnd`): a reset under the loop would null the connection the loop sends on.
- A banked tick passes no game time for the server, so a driver may skip his physics on exactly those ticks and keep his
  momentum (AP3's Blink Charge, `noteFrozenTick`): measured on GrimAC, sprinting and mid-jump, 10 ticks paused, velocity
  unchanged, and the run afterwards equal tick for tick (0.000000) to the same route without the charge; clean.
- The vanilla server's 10 blocks are per SERVER tick, measured from where that tick began, and include every move
  packet in it - the run before a shot as well as the shot. A server that cannot keep up takes several client ticks in
  one of its own, so a split shot's second part (sent the next client tick) and the run ahead of the first part all
  landed together: "Player0 moved too quickly! -6.66,0,-7.94" (2.2 of run + 8.2 of shot), a vanilla setback, and GrimAC
  saw the 2.17-block jump (Simulation 2.166, GroundSpoof, TickTimer; 1027-ap3-s2ee3-blink-0.5 on 26.2 under load,
  2026-10-27). BlinkCharge now notes his position at each server ping (GrimAC pings every tick): a part's budget is 9.5
  less what he moved since the ping before the latest (while pings come at tick rate), and a split's second part waits
  for two new pings (at most 300 ms). Lowering the cap alone (8.5) did not help. `grim consoledebug <player>` plus the
  server log's "moved too quickly" line told the two apart: GrimAC predicted every shot tick exactly.
- That budget is a MODERN vanilla rule (ServerGamePacketListenerImpl: more than 5 move packets in one server tick count
  as one, so 10 blocks per server tick). Hypixel runs a 1.8 server, whose NetHandlerPlayServer.processPlayer judges
  "moved too quickly" per packet from the last accepted position, with no per-tick count, so on Hypixel the split only
  cost him: his 26.3 log (2026-11-05, "it releases the blink in 3 packets sets") shows 20-tick Speed shots going out
  as 8+8+4, 5+9+6, 10+9+1 about 100 ms apart. blinkone-1105: `BlinkCharge.serverTickBudget` skips the budget on
  hypixel.net (and singleplayer) and keeps it elsewhere. Testkit 1105-blink-oneshot stands in for Hypixel with the
  gamerule `player_movement_check false` and the hook `tickBudgetForTest = FALSE`: one burst of 21 moves, server
  agreeing, GrimAC silent; with the rule on, the same shot still splits. Watchdog itself cannot be tested here.
- The landing box (`BlinkCharge.landingEstimate`) runs his last step through `Ap3RouteCollide.collide`, the exact copy
  of Entity.collide, for every banked tick plus the firing tick (gravity and drag after a vertical miss), once per
  client tick. The old box stopped at the first block in the way, so a half slab or a stair stopped it (killer560
  2026-11-05); testkit 1105-blink-predict-step shoots over a slab, a stair and a raised floor.
- His attack in the shot's tick was lost (blinkone-1106, 1105-blink-oneshot-same-attack "his ticks 0"): the Charge key is
  read before that tick's handleKeybinds, so the shot carried him 10+ blocks past what he clicked and vanilla's queued
  click hit nothing. A key shot now waits while `ActionGate.hisClickQueued(keyAttack)` (at most 250 ms): his click goes
  alone, the shot the tick after. Then his hits drew Hitboxes: GrimAC places an entity at the pings he has ANSWERED, and
  Charge holds answers ~1 s, so an entity moved under his crosshair was somewhere else to Grim. While answers are held,
  moves of entities within 20 blocks (move / teleport / position sync) wait in the held-block queue behind their ping's
  answer (`BlinkCharge.holdEntityMove`), so he sees them where GrimAC has them.

- State that is counted per tick (a wait after a world change, a cooldown) must be counted before a feature's "switched
  off" early return. Blink's spawn settle (5fa141f2) was counted only while Blink was on, so with Blink off at the world
  change it stayed pending, and a hold pressed later mid-jump waited for the landing (testkit 1025-blink-jump: 2.4 of 6.9
  blocks held; ship3-1027). The same shape in AP3: `Ap3Feature.onChat` returned before reading the leap line whenever no
  node was running, so an armed route never knew a landing was a leap (1027-ap3-live34-beside-leap).

- **Etherwarp passability: carpets are solid** (killer560, 2026-11-02, asked about the to-do Doc's "doesnt work right on
  carpets": the trouble was only on the sim). The modern passable tables (Odin, QUOI, ours in EtherwarpOverlayFeature and
  TeleportUtils) are the 1.8 "valid feet" id list carried over block by block with carpet (171) and lily pad (111) dropped;
  doc-routes 419fd24c added carpet back as passable, which made 62-argrim-1020-midas fail 3 of 3 and was reverted
  (65199074) before it shipped. A look at a carpet lands ON the carpet; a carpeted block is no landing. Testkit 1101-ew-carpet
  checks the prediction, 1102-sim-ew-carpet(-room) that the sim's own etherwarp lands where it says. What was wrong on the
  sim was the LOOK, not the table: it cast along `TeleportUtils.getLook` (Math.sin) while the overlay casts along vanilla
  `getLookAngle` (Mth's sine table, ~1e-4 rad apart). A ray at a carpet meets the full-cube carpet voxels a block or more
  before the carpet looked at, grazing voxel corners, so the sim landed one carpet off the box 2 of 23 times in real rooms.
  The sim now casts along `getLookAngle`. Any two etherwarp models must share the look formula, not only the block table.
- **Spring Boots charge is countable from sound alone**: `dungeonalerts.SpringBootsOverlay` (Odin's pitch table) counts
  `note_block.pling` notes while crouched in Spring Boots; AP3's Crystal node reads `chargedHeight()` and asks it to
  count with the HUD off (`setCountForAp3`). Its handler is behind the Skyblock gate (DungeonAlertsPackets.guard).
- **Zero Ping Etherwarp's own sends do not pass ActionGate** (Warps Per Tick's `flushExtraWarps` and Aimed Warps' `fireAimed`
  send through `MultiPlayerGameMode.startPrediction` directly), so a gate added to `ActionGate.tryActInternal` misses them:
  the Emergency Cancel hold and Streamer Mode's Disable cheats did until zpew2-1101. Any new ActionGate-wide refusal also
  goes in `ZeroPingEtherwarp.held` (testkit 1102-zpew-ecancel).
- **Blood Camp: a mob near two landing spots belongs to the trip whose timer ran out first** (owner 2026-11-03, "killed before
  the kill timer went off"). Two skulls landed 0.75 blocks apart; the first's stand went (-1.2 left) and its mob appeared, and the
  second trip (4.8 left) accepted it through `nearestMob` and `tagLanding` (radius 2, no ownership), so the click was logged
  at 4.8 ticks and attributed to the wrong stand. `owedElsewhere` now refuses a mob to a trip when another trip took it
  (`CLAIMS`, kept after the trip leaves `LANDED`: a tagged trip is dropped the same tick) or is unclaimed and more due.
  The mob then goes through the next-mob pass. Testkit 1103-bloodcamp-overlap-trips. A claim that lives only in `LANDED` is gone
  the tick it is made: the first version of the fix passed review and failed that case for exactly this.
- **The Watcher holds a shot only inside a projectile's margin** (owner's last mob 2026-11-03: held 10 ticks, first swing at
  6.8 blocks hit=false). `lineOfFireClear` used the 0.6 margin for the Watcher as for anything else; his zombie box grown by
  `WATCHER_LINE_MARGIN` 0.3 (a vanilla arrow's) is what a shot can meet. A melee attack goes by entity id, so only
  beam/arrow clicks (a swing without a melee target, a shortbow) are affected. The hit=false first click was a swing with no
  melee target at 6.8 blocks (a beam), not a melee miss; the log cannot show where the beam went. Testkit
  1103-bloodcamp-watcher-line (a decoy husk stands in for the Watcher; the test Watcher is a stand, which never blocks).
- Terminal Solver reads the SERVER's slots, but a real PICKUP click (Rubix; the other types send CLONE) predicts locally and leaves the slot EMPTY
  until the server answers. A server that rejects or drops the click (Hypixel does not always re-send) never refills it, so the solver saw no
  pane, the Custom GUI drew nothing and the terminal looked finished while open (killer560, 2026-11-03). `TerminalSolverFeature.predictedSlots`
  keeps the pre-click stack and puts it back after max(250 ms, 2 x ping + 150 ms) if still empty; the server's update wins if it arrives
  first. Hx's chest menu always re-sends after a click, which hid this: testkit `menu.terminal` `dropClicks` / `menu.terminal.drop` answer
  nothing, cases 298/299-menu-term-resync-*. His 19:22 log had no failing terminal in it (Rubix 1.68 s, Starts With 25 s, no resend lines).

### Lag spikes and TimerLimit (blinklag-1104)

Why a lag spike flags TimerLimit, read off GrimAC 2.3.74 (`TimerLimit.limitFallBehind`, javap of ac_grim_grimac_common):
when the newest ping answer GrimAC has is more than `ping-abuse-limit-threshold` (1000 ms) older than real time, the timer
balance is clamped and the catch-up burst after a stall is flagged tick by tick. A full Charge bank holds its answers 1050 ms
by design (`BlinkCharge.answerDelayMs`), so before the fix ANY stall crossed the limit: 6 flags at 250 ms, 23 at 1100.
Fix (owner, 2026-11-04: keep the whole bank, just do not charge while the server lags): `BlinkCharge.tick` calls the server
stalled when no ping answer was made for `STALL_MS` (150 ms; only once a ping was seen since Charge started, so a server that
never pings is never judged). It then sends the held answers at once (the stall, both ways, delays them like the vanilla
client's, so GrimAC's newest answer is the stall's own start, not 1 s older), pauses banking and re-backing, and the kept bank
is backed again on the next still ticks once pings return (`answersWentEarly`, same machinery as a hold). Nothing is dropped
or invented; the bank and every banked tick stay. Measured on 26.3 cheat with `killer560.test.lagMs` (1026-blink-lag, strict):
Charging rows clean at 250/650/900/1100; at 1500 ms 8 flags, the same as Blink OFF (1026-blink-controls: 8 at 1500, clean at
900 and 1100). The log line "charge: no server ping for N ms - lag; K answer(s) sent now, banking paused" proves it acted.
NOT fixed: a spike in the middle of a Hold (not Charge) still flags from 900 ms (2 flags; 6 at 1100, 14 at 1500, Blink OFF is
clean to 1100). Hold keeps every packet, pongs included, in order for the whole hold (Post/TransactionOrder), so its newest answer
is as old as the hold; sending the pongs ahead would show GrimAC the hold as a Timer burst, and releasing the hold on a stall is
an early release. Needs the owner's call.

### Position reminder and BadPacketsV (ship3fix2-1104)

GrimAC 2.3.74's experimental BadPacketsV (javap) counts tick-end packets since the last position packet and flags a still
position (no teleport, move under the threshold) that arrives fewer than 19 ticks after the previous one. Vanilla's
`LocalPlayer.positionReminder` sends a still player's position every 20 `sendPosition` calls, and a withheld (banked or
re-backed) Charge tick still counted, so the reminder reached Grim early: BadPacketsV on every Blink case once a checkout ran
`experimental-checks: true` (the ship3 gate's config came from tk-ship1103a; earlier blink passes ran with it off).
`BlinkCharge.dropStash` now takes each withheld tick back off the count (`blink/mixin/BlinkReminderAccessor`), and 14 blink
cases on experimental show no BadPacketsV. NOT fixed: under repeated server stalls Charge re-backs 22 still ticks after each
one, so a still player sends no position for 2 s+ and experimental BadPacketsR flags (`time > 2000 ms, positions=0` between two
transaction answers): 46 in 1026-blink-long-charge on a loaded PC (the pre-fix jar: 8 R, 48 V, 48 TimerLimit; default config:
clean). A position sooner would be BadPacketsV again, so this needs a design choice (the owner's).

BadPacketsR exactly (javap 2.3.74): at a valid transaction answer, when both the answered pings' clock and wall time have moved
over 2000 ms since the last such window, it flags if no position/pos-rot packet came in that window, then starts a new one.
The same 22-tick re-back after ONE 2.5 s frozen client (1026-blink-lag "frozen client charging") drew it in 2 of 3 runs ALONE
on experimental (`time=2049-2055ms, lst=3645ms, positions=0`, blinkload-1105): nothing goes while frozen, and right after it
the 22 still ticks are withheld while the re-held answers arrive 1050 ms later. Fix (blinkload-1105): `BlinkCharge.rebackKeepsPositions`
lets re-backing (and banking while unbacked) withhold a still tick only while the next 20-tick reminder still lands within
`POSITION_GAP_MAX_MS` (1800 ms) of the last position on the wire (`onWire`); otherwise the tick goes out, the reminder first,
and re-backing resumes after it. BadPacketsV stays clean (reminders still 20 SENT ticks apart); a re-back now takes about two
reminder cycles instead of 1.1 s, so a shot after a stall waits that much longer for cover. The "backed again" log line says how
many ticks went between ("N sent between them for a position in time").
