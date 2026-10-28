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
  tick, aimed at the first, drew nothing from GrimAC.
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

## Ticks, timing and clocks

- **A ping does not cover the tick boundaries** (testkit 792): an attack sent on a client tick is acted on 0-2 server ticks
  later than the round trip alone predicts, at 1 ms and 200 ms alike - each end only acts on a packet at a tick.
- **A click cap holds at the client, not at the server.** Blood Camp's clicks go exactly the attack-speed cap apart (5 ticks
  at 100 Attack Speed), but the testkit server saw them 4 and 6 ticks apart, alternating (186), mean exactly 5. Assert the
  cap on the client's own click ticks, and at the server only the mean and "never more than a tick under".
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

- State that is counted per tick (a wait after a world change, a cooldown) must be counted before a feature's "switched
  off" early return. Blink's spawn settle (5fa141f2) was counted only while Blink was on, so with Blink off at the world
  change it stayed pending, and a hold pressed later mid-jump waited for the landing (testkit 1025-blink-jump: 2.4 of 6.9
  blocks held; ship3-1027). The same shape in AP3: `Ap3Feature.onChat` returned before reading the leap line whenever no
  node was running, so an armed route never knew a landing was a leap (1027-ap3-live34-beside-leap).
