# Automation runtime lessons

Split out of [LESSONS.md](LESSONS.md). Same rules: problem, then fix; verified only.

- **Auto Routes on GrimAC (2026-10-05, testkit 62-argrim).** Obvious mode sent each use with a rotation the client never
  reported (BadPacketsJ on every etherwarp) - the bug Auto Puzzles fixed on 2026-09-27, and `ClearExecutor.doInteract`
  (every Interactive Map warp and Go To) had it too until 09e2c304 (62-argrim-imwarp/-goto). Digs must carry the face the
  eye sees (an always-UP face is PositionBreakA), an ABORT says DOWN as vanilla does (any other face is PositionBreakB on
  every later dig), and no input packet may say sneak while a container is open (MultiActionsD on its close). Both
  executors now turn the body with the camera held through `util/BodyAim`, one instance each. Multi Break's six STARTs on
  one tick, aimed at the first, drew nothing from GrimAC.
- **A vanilla client removes a block it instabreaks the moment it sends the dig, and GrimAC assumes it did**: on an instant
  START_DESTROY_BLOCK (`BlockBreakSpeed.getBlockDamage >= 1`) it sets the block to air in its own world at once
  (`CheckManagerListener.handleDigging`, 2.3.74). Breaker Aura with Zero Ping off kept standing on a picked floor block
  until the server's update came back, so any movement packet in that gap claimed ground over air: GroundSpoof + 0.0784
  Simulation when the 20-tick position reminder landed there (about half the runs), NoFall every time when turning
  (testkit 60). The block he stands on is now always predicted (`BreakerAuraFeature.standsOn`); 0 flags in 84 runs.
- A hotbar swap sent by hand (`ServerboundSetCarriedItemPacket` after `setSelectedSlot`) leaves the game mode's
  `carriedIndex` stale, and its `tick()` then sends the same slot again: GrimAC BadPacketsA. Swap through
  `MultiPlayerGameModeInvoker.invokeEnsureHasSentCarriedItem` (RouteExecutor.select, ClearExecutor.swapById since 09e2c304).
- An await met by the chest CLICK (2026-10-05 rules) lets the route warp before the chest's window arrives, which is
  right - Hypixel credits the chest on the click - but the late window then stopped the route ("a screen opened"). A
  container screen within 2 s of our own chest click is now waited under (98b457be; 96-ar-play emulates the late window).
- **Never read the integrated server's world from the render thread.** A `ServerLevel.getBlockState` in an unloaded chunk
  loads it synchronously on the CALLER's thread; the sim's `/goto` did that from its command and, in the testkit's lockstep,
  parked the render thread in `ServerChunkCache.getChunk` for good (jstack, 97-sim-goto-loop, 2026-10-06). Wrap the whole
  read-then-act in `server.execute`; hand results back with `client.execute`. `ModChat.send` is safe from any thread since
  then (it hops), but `client.player`/screens/the tab list are not - read `client.player` once into a local if a server
  task must have the UUID.
- **Auto Routes chain timing (2026-10-06, testkit 62-argrim-chain / 96-ar-chain).** An etherwarp chain ran at 4 ticks per
  warp on the dedicated server (5 in the sim): landing seen, then the interact-delay settle, then a tick for the walk step to
  re-fire. After a server-confirmed landing an etherwarp/path node that fires next now goes on that same tick: 1.00 tick per
  warp (Grim clean), 2.00 in the sim, whose integrated server answers a tick later. Only warp-into-warp skips the settle: a
  breaker stacked on the landing tile and fired on the landing tick sent its digs and the dedicated server broke nothing
  (62-argrim-play, not traced). A warp fired on the landing tick stands 0.05 above the floor its look was recorded from, which
  put a 16-block shallow warp one block long (96-ar-rotate), so a node fired off its own height re-aims from the eye with
  `TeleportUtils.getEtherwarpDirection`, as path hops do.
- **A `ViewFreeze` lease taken per rotation is not a free camera.** It lapses 400 ms after the last hold, and a Blaze shot
  waits out the arrow's flight plus the cooldown, a reposition up to 2 s for the landing; each lapse showed the turned body
  and the next hold re-captured it, and Auto Blaze's every-tick room-centre seed re-took every lapsed lease and threw his
  mouse-steered view back (2026-10-06, "snaps my camera around everywhere"). Every auto puzzle that turns him now holds an
  `autopuzzles/FreeCam` for the whole run (engage before the first aim, `keep` every tick, `release` turns the body under
  the view, shifting the hand sway by the same whole turns). 93-solve-*blaze's CameraWatch checks it every render frame.
- **In obvious mode `Minecraft.pick` uses the HELD camera, not the body.** `Ap3ViewYawMixin` makes `getViewYRot/XRot`
  return `ViewFreeze`'s view, and the crosshair pick reads those, so a vanilla key press while the body is turned acts on the
  block the CAMERA looks at. The crypt node holds the real use key (vanilla `handleKeybinds`: held + `rightClickDelay == 0` ->
  `startUseItem`, every 4 ticks, javap 26.1.2 and 26.2) and `autoroutes/mixin/HeldUsePickMixin` re-picks along the body at
  the end of `pick` while it holds. Vanilla's held use with a sword on a floor sends `use_item_on`, `use_item`, `use_item_on`
  (both hands), which 62-argrim-crypt compares against a harness-held key.
- **Dungeon key pickup range = Key Base Range + 5; the Magnetic Talisman does NOT apply to keys** (confirmed in game by killer560, 2026-10-06; the wiki x3 is for items only, keys are armour stands). Default 1.0 + 5 = 6 blocks. The talisman setting and the x3 were removed; an old `magneticTalisman` key in the config file is ignored.

- **Hypixel's dungeon HAS a roof you can land on.** `TeleportUtils.underCover` and the floor graph's landing rule refused
  open-sky landings only in the sim, on the belief that a real floor is solid rock; on 2026-10-06 an F5 Interactive Map path
  stood him at y 100 on the corner of four rooms. The rule applies everywhere since 67ce488a (testkit 363-logic-roof-cover).
- **The floor graph (`WarpGraph`) is used only from the Interactive Map's planner thread.** Auto Routes' path planner ran
  `findDungeonPath` on its own thread while the warm-up ran on `killer560smod-etherplanner`; two threads in `WarpGraph.node`
  pushed the node count past its 1024-long arrays without growing them, and every later node threw AIOOBE one index higher
  until the world changed (96-ar, 2026-10-06). Submit through `ClearExecutor.onPlanner`; `graphFor` now throws if called elsewhere.
- **A command the player triggers must not ask `ActionGate.tryAct`.** Its screen-transition settle fires on ANY screen
  change, the mod's own wheel included, so Pet Wheel's /pets waited 3-4 ticks after a quick flick and a tick after any claim
  (testkit 397, 2026-10-07: 4 ticks / 200 ms on the old jar, 0 now). Send it at once and call `ActionGate.noteCommand`, which
  only books the tick for later automation. Screen-driven picks belong in the screen's own `keyReleased`/`mouseReleased`, not a tick poll.

- **An etherwarp node's recorded look is right from the node's own spot and nowhere else** (2026-10-07, his Museum #10 armed
  0.6 off centre and 0.27 up mid-jump, fired the recorded look, landed a block wide of #11 and the route stood there). The
  ring is up to 0.8 wide either side, and only a height difference used to trigger the re-aim. `RouteExecutor.reaimIfMoved`
  now re-aims at the recorded landing block from wherever he is, by any axis, at set-up AND on the tick the use goes out
  (sent at the start of a tick, so the server has exactly the client's current position). Where no ray from his spot
  reaches the block (a pillar hides it from one corner of #10's ring), `stepOntoWarpSpot` walks him back toward the node
  with sneak-held discrete keys until one does, or onto the spot, then fires. Testkit 96-ar-398-offnode (old jar lands
  at his exact (-79.5, -105.5)).
- **Secret Aura and Auto Routes are released on the same tick after an Interactive Map warp, and the aura ticks first**
  (CheatUtils registers before Auto Routes). Its click on the start node's secret came one tick before `AwaitEvents.begin`,
  so #1's await sat at 0/1 until he skipped it (Museum, three runs of four, 2026-10-07). `begin` now counts his own secret
  clicks and pickups from the last `START_LOOKBACK` (10) ticks. Testkit 96-ar-398-startawait.
- **A server correction can land between a map path's search and its first warp** (2026-10-07, testkit 131-sim-auto-clear
  "correction-plan"). The search takes well under a millisecond, so the teleport packet and the search result are drained
  in the same frame; `ClearExecutor` started the queue from the old spot, called it "not on the path's first spot" and
  replanned as an ordinary failure - no chat line, no alarm, and a second one in a click would have STOPPED the path. Same
  for a packet during the arrival settle ("ended N blocks from the planned landing"). Both are corrections now: reported,
  and the goal planned again from where he was put without counting against `MAX_REPLANS`. Auto Clear ignored every packet
  during its trips and so never counted a trip's correction; it now adds `ClearExecutor.serverCorrections()` to its own.
- **A held key does nothing under a screen.** `Minecraft.tick` runs `handleKeybinds` only while `overlay == null && screen
  == null` (javap 26.1.2 and 26.2), so the crypt node's held use key sent no use at all with the Interactive Map open (Run
  While Map Open), and timed out (2026-10-07, testkit 96-ar-405-crypt-mapopen: 0 uses / 101 ticks against 3 uses / 9 ticks).
  `HeldUsePickMixin` now runs vanilla's held-use step itself after the tick's `pick` while `RouteExecutor.heldUseUnderScreen`
  (same rule: key down, `rightClickDelay == 0`, not using; the delay counts down whatever the screen). Every other node sends
  its packets directly or through the input mixin, which run under screens.
- **`tickAwait` uses the same `step` field as the action**: an etherwarp node waiting on its await is in `Step.CONFIRM` before
  its PREP has set `warpLanding`. `checkCorrections` read that as the warp's confirm and measured a 1-block server move from a
  stale landing (his #6 of a stopped run, "47.4 from its landing"), let the start node go, and the route stood still (his 1 in 5
  "map warp put me off the node", 2026-10-07). It now requires `awaitPhaseDone`, and `beginAction` clears the warp fields.
  What moved him that block in his sim is NOT found: 20 map warps with his mimic, slot, speed 600 and the map open never moved
  him (96-ar-405-startwarp), so the case injects the move.
- **The map planner calls an exact goal within five blocks "already there"**, so a caller that insists on being closer
  asks again for ever: Auto Clear's wither door required 2.5 blocks from the approach spot and, standing 3.6 off, logged a
  "trip to wither door" and an "Already there" every tick without ever clicking (2026-10-07, testkit 131). Once a trip to
  the door has finished it now clicks from where he stands, and stops saying why if the door is out of reach.
- **An aura's click is judged against the rotation the movement packets report, not the camera.** A block use the reported
  look misses is GrimAC RotationPlace (and dropped); an entity interact is Hitboxes. Turn the BODY first with
  `util/TurnFirst` (or BodyAim), camera held, and send only after a movement packet has carried the turn: an AP3 Term Aura
  click sent at the START right after an END turn preceded every packet facing the stand, and after an earlier flag GrimAC
  let none through (testkit 416, 2026-10-07). Digs are not judged this way (54: behind and through a wall, 0 lines).
- **Never release a `ViewFreeze` hold you did not take.** It is one global lease; a second owner releasing it drops the
  camera onto a body another auto is still turning (93-solve-blazemiss-lower, -boulder-aura). `ViewFreeze.holdCount()` says
  whether anybody else held it since you did.
- **`/ar edit db` measured reach from the NODE, not from him** (killer560, 2026-10-09: "it says they are too far for my
  breaker"): the pick was refused whenever he stood off the node, though his crosshair had just picked it. Picks are
  measured eye-to-box at 4.5 now (a plain dig - the wiki gives the Dungeon Breaker no range), and a pick far from the node
  is only noted in chat, since playback digs from wherever the route has him (testkit 96-ar-658-dbreach).
- **The floor graph's warp count is an upper bound, not the fewest.** Its 2x2 buckets, 18 aim points and early-blocked
  refusals missed a route with fewer warps on 21 of 180 bench path pairs (2026-10-11). Anything that must be minimal goes
  through `ExactWarpSearch` (via `EtherwarpPathfinder.findOptimalPath`), which proves it - see docs/SIM.md "Fewest warps,
  proved".

- **A use-on-block packet carries no rotation** - the server uses whatever the last movement packet reported, and vanilla
  sends a tick's held use BEFORE that tick's movement packet. A crypt node turning straight down and holding use the same
  tick sent its first use at the look he armed with (30.0, or 89.85-89.96 in legit; testkit 96-ar-653). It now holds use
  only after two ticks reading pitch 90.0. RouteRotation's ease also never reached its target exactly (a decaying arc
  tail kept it thousandths off), so its last 0.01 degree now lands exactly and the arc stops there (2026-10-11, hype-down).
- **SkyHanni's island graph positions are the FEET block, and the path points sit half a block above them** (verified on
  his HUB.json: Village nodes at y 70, where a player stands). `AutoWalker` jumped whenever the next path point was 0.6
  higher than his feet, so every gentle rise was a hop ("jumping the whole time", killer560 2026-10-11). Jumps now come from
  the block in front (`WalkTerrain.rise` against his real jump height, Jump Boost included); testkit 746 counts 1 take-off
  for a course with one step. Straight under a path point (the foot of a ladder) atan2 of near-zero spun the camera; the
  walker now holds its aim there (testkit 747).
- **The Chocolate Factory menu moved** (killer560 2026-10-11, "clicks the cookie but does nothing else"): per SkyHanni-REPO
  `constants/HoppityEggLocations.json` the Time Tower is 38 (39 is the Rabbit Shrine), the Coach 41, and 42/43/47-51/53 are
  menu buttons. `chocolatefactory/ChocolateFactoryMenu` is the one reader for the auto and the legit helper; check that
  file in his SkyHanni repo copy before changing a slot (testkit 754 puts traps on 39 and 42).
- **A click sent from a render frame is GrimAC Post, same as one from END_CLIENT_TICK** (2026-10-11): the frame runs after
  the tick's movement packet. Goldor Triggerbot, Arrow Align's Trigger Bot and Aura, Simon Says' Trigger Bot and Rotate
  Mode click, and Auto i4's shots all clicked from `LevelRenderEvents` for millisecond timing (testkit 786-788 recorded one
  Post per click). They send at START_CLIENT_TICK now; only camera turns stay per frame. Delays are
  `ActionGate.delayTicks` (rounded UP to 50 ms), and a click RATE is kept by scheduling each click from the previous one's
  due time, fractional, not from when it went out (else 16 CPS rounds to 10). Testkit 786-788 and 831-835 require clean.
- **A ping does not cover the tick boundaries** (testkit 792, 2026-10-11): an attack sent on a client tick is acted on 0-2
  server ticks later than the round trip alone predicts, at 1 ms and 200 ms alike - each end only acts on a packet at a tick.
- **Time a trigger from the moment he SEES, not from a second clock.** Blood Camp's Trigger Bot aimed at Hit Lead 3 + that
  1-tick allowance + ping before the countdown's end, while the green box and Kill Sound fire at ping alone, so every click
  went out about four ticks before the "kill" he was watching for ("attacking them before the kill timer actually went
  off", 2026-10-15). It now clicks on the first tick with time left < ping ticks + Trigger Offset - the kill timer's own
  test shifted by the offset - so the delay is exactly -offset ticks (testkit 177, 792).
- **A click cap holds at the client, not at the server.** Blood Camp's clicks go exactly the attack-speed cap apart
  (5 ticks at 100 Attack Speed), but the testkit server saw them 4 and 6 ticks apart, alternating (186, 2026-10-15): each
  end acts on a packet only at its own tick, so one landing a tick late and the next on time is a 4. The mean was exactly
  5. Assert the cap on the client's own click ticks, and at the server only the mean and "never more than a tick under".
- **QUOI's aura ranges are eye to block CENTRE; ours are eye to block BOX.** A centre distance is 0.5-0.87 longer than the box
  distance, so QUOI's 4.7 skull range is 3.8-4.2 to the box and was TIGHTER than our 4.5-to-the-box skull cap, though the
  number is bigger (2026-10-12). Compare in one measure. Skull Range now uses QUOI's centre measure (testkit 842: 4.8 not
  clicked, 4.6 clicked); the 20-tick world-change pause QUOI's AuraManager adds was already ActionGate's WORLD_SETTLE_TICKS (841).
- **A CPS schedule must count a turned click from its TURN tick.** With "schedule from the previous due tick" alone, a click
  that first needs `TurnFirst` goes out a tick after it was due and the next gap starts from there, so every turn tick was
  added to the gap: Lever Aura measured 4.48 at 5 CPS and 7.89 at 10 (testkit 846, 2026-10-12). It now schedules from the
  tick the turn took (5.17 / 10.00). On different blocks every click needs a turn, so 20 CPS tops out at 10. Arrow Align
  Aura had the same bug (832 sweep 4.57 / 8.00 at 5 / 10 CPS, now 5.00 / 10.00, arrow-turn).
- **The floor graph's height band must reach under the floor** (killer560's Altar #9 -> #10, 2026-10-12). Every planner search
  took landings only from floor - 20, and Altar's lower level is 26 below the floor, so no landing could see the goal: the
  exact search's empty first backward layer read as "no route (proved)". The band now starts at Hypixel's y 0 (floor - 69,
  `EtherwarpPathfinder.graphMinY`); testkit 941 plans 2 warps and flies them (main: not planned).
- **"No route (proved)" was followed by a plan anyway** (testkit 634, Scaffolding #12/#17/#36, 2026-10-12). The planner and
  the server agreed on every ray; the goal sat in a 5x5 floor pocket that no warp from outside reaches (testkit 947's
  server-side closure: 24 landings reach the goal, none of them reachable from the start). The exact search proved that,
  and then `planFloor`'s fallbacks (the floor graph's NEAR landing, the room-by-room planner's "near") returned a chain
  ending on a stair 4 blocks up, which `RoutePathPlanner.save` stored as the pair. A path node's plan is now checked hop by
  hop to end ON the block (`planFloor`, `onBlock` only) and refused otherwise, with the reason in chat; the map's tile and
  near clicks are unchanged.
- **A jump takes him out of every node's ring** (0.1 high by default), and standing in no node clears the latch,
  `justFinished` and `stoppedByUser`, so a finished JUMP node armed again the moment he landed back on it ("falls right back
  onto the node", killer560 2026-10-14). A finished jump node now holds its tile for 3 s of real time
  (`RouteExecutor.jumpCooldownLeft`, gate line "jump cooldown N.Ns left"), then the latch rules apply as before; testkit
  96-ar-1014-jumpcd / 62-argrim-1014-jumpcd measure 3.04-3.10 s between fires, and a jump -> etherwarp route goes on at once.
- **On Hypixel the item he warps onto is taken in the same batch as the landing** (killer560, 2026-10-14: "it never detects
  secrets... it picks them up faster than it knows it grabs them"). His Mod Only Test log of a real Red Blue run logs "await
  secret 1: item picked up by you" while #3's warp is still the active node, then "landed", then #4 sitting at 0/1: the take
  packet is handled before the route's tick sees the landing, so it was counted in #3's window and wiped by `window()`. An
  integrated server picks up a server tick later, so the sim never showed it. A secret got while the previous node was
  acting, in its last `AwaitEvents.CARRY_TICKS` (10), within 6 blocks and unclaimed, now counts for the next node; the sim
  picks up on the teleport step (`SimSecretItems.pickupOnLanding`), and the testkit's `HypixelOrder` forces that order on
  any jar (96-ar-1015-landpickup).
- **A left click only skips while the node is ALREADY waiting** - one made while the warp onto it was in flight went to
  vanilla as a swing a tick before the await opened, and one while Kill Mimic held the hands was never read (that branch
  of `tick` returned before `tickAction`). Both are taken now: held `EARLY_SKIP_TICKS` (10) for the next await, and Kill
  Mimic is stopped by the skip (96-ar-1015-earlyskip, -mimicskip, 2026-10-15).
- **Hypixel's 1.8 server pairs a bare item use with the last unpaired right click on a block** (CraftBukkit 1.8
  `PlayerInteractManager.firedInteract/interactResult`, face-255 branch of `PlayerConnection.a(PacketPlayInBlockPlace)`). A
  block click stores its verdict; a use traces 4.5 blocks along the look the server LAST HEARD in a movement packet (a 1.8
  use carries no rotation; a 26.1 use's own rotation does not count for this trace), and if that ray meets a block while a
  verdict is stored, the use is taken as the click's second half: no ability, no chat line, verdict cleared. A modern
  client's chest/lever/skull click is one packet, so its verdict stays stored. Evidence, Mod Only Test 2026-10-14
  17:22-17:29: all six "no landing after 61 ticks" warps came a tick after a chest or lever click with the AOTV held, and
  their retries landed (verdict gone); along the warps' OWN looks Red Blue #5 and #6 meet nothing within 4.5 (raytraced
  against the room capture), so the server traced the look the chest was clicked with - Auto Routes turned the body and sent
  the use in the same tick, so the movement packet with the warp's look went after it. The 2026-10-15 fix that held every
  use 4 ticks after a same-item click (`BlockClickGap`) was wrong and is gone: it still sent with the old look. Sneak timing
  was ruled out (four warps with sneak in the firing tick and no click before them landed). `util/RightClick` now, for every
  item-use sender: `mustTurnFirst` (unpaired click AND the heard look's ray meets a block AND the use's look not yet heard ->
  turn the body, send next tick; Auto Routes turns on the firing tick while the sneak goes out, so it costs nothing) and
  `beforeUse`/`beforePlainUse` (the use's look meets a block within reach -> send vanilla's click on that block first, so the
  use pairs with it). A click with an empty hand counts as harmless (and replaces an earlier verdict): the one real warp
  a tick after a click made with another item (Grand Library 17:29:35, Dungeon Breaker) landed. The sim models the pairing (`SimAbilities.takenAsClickHalf`, heard look from `SimUseLookMixin`) and the
  testkit's `HypixelUseAfterClick` forces it on any jar (96-ar-1015-warpclick, -puzzleclick, 1015-sim-clickgap). Every use
  and block click logs one `[RightClick]` line (seq, item, slot, rotation, heard look, shift, screen, unpaired click, ray);
  a WARN line ending "takes this use as that click's second half" is the failure happening.
  Traps: the Interactive Map sends a hop while the last one is still in flight client-side, so its server ray is traced from
  the planned spot (`mustTurnFirst(.., serverFeet, ..)`), and a waited hop is re-aimed only once he stands within a block of
  its spot (re-aimed from the pre-landing position it flew 38 blocks off the plan, 1015-sim-clickgap); a path hop in flight
  already turns to the next hop's look while a click is unpaired, so the landing tick still sends it; `updateDelays` must not
  let go of the forced sneak while `pendingInteract` waits (Instant Transmission). The testkit pairs a right click's two
  halves by the client's tick-end packet, not by server tick or movement packet (a standing client sends no movement packet).
  Do not test a block click with a lever while sneaking: a sneaking AOTV use-on-block warps in the sim.

- **"No move for him" is not "the game is over" in Tic Tac Toe.** Hypixel's computer answers a second or two after his
  click, and the board in between has no move for him either; Auto Tic Tac Toe's Walk Out read that gap as finished and
  warped him out after the FIRST click, then walked him back in (Mod Only Test 2026-10-14 17:26:00-17:26:04). The sim
  painted both marks in one server tick, so it never showed. The walk-out now waits for `TicTacToeSolverFeature.isGameOver`
  (or the tab list), and the sim's computer replies `SimTicTacToePuzzle.REPLY_TICKS` later (an estimate: his real clicks
  came 3 s apart against the auto's 0.5 s gap).
- **Secret Aura needs no line of sight**: it picks by standing eye to the block's BOX within Aura Range (capped 4.5) and
  clicks through walls, as the server allows. Grab Secret's landing is chosen by that same rule; in the sim's Tic Tac Toe the
  chest is in a roofed pit no warp or walk reaches, and a landing on the board side of its wall lets the aura take it
  (testkit 93-solve-tictactoe-grab, killer560 2026-10-15: "it should aura the secret").
- **A chest is not a block to etherwarp onto.** Tic Tac Toe's chest trip asked the Interactive Map for the chest itself:
  "is not etherwarpable ... Nothing searched" three times and the secret was never taken (same log, 17:26:00). Trips to a
  block go to a standable block that has it in reach (`AutoPuzzleUtil.reachSpots`), as Auto Blaze's already did.
- **Nothing ever opened Water Board, Ice Path or Ice Fill's reward chest.** Secret Aura skips Ice Path and Ice Fill outright
  (`SKIP_ROOMS`) and in Water Board waits for the tab list or the map to say the puzzle is over (his 2026-10-14 runs: each
  auto finished and he stood beside the chest). The autos open it themselves (`autopuzzles/RewardChest`), Secret Aura or
  not; testkit 93-solve-*-claim run with Secret Aura off.

- **Auto Boulder started at the doorway only on ClearExecutor's arrival counter**, which moves only when a map path ends
  within 1.5 of its planned landing - never for his own etherwarp, nor a map trip that ended off its plan. Standing on
  the doorway spot (filed by the live map under the tile next door) it then never started; walking in did. Any server
  teleport that leaves him there starts it now (`ClearExecutor.positionPackets`); testkit 93-solve-boulder-doorwarp
  (main: never starts; a real map press, -mapwarp, passes on both). Its walks also only sprinted on long straights.
- **The sim decided a Tic Tac Toe click on the CLIENT and took the square's button away in a server task queued before
  the click's own packet**, so the server saw air and fired an AOTV's Instant Transmission into the board (4.2 blocks,
  93-solve-tictactoe-grab). On Hypixel a board click never moves him (his 2026-10-14 log); `SimAbilities.blockWins` now
  lets a square win. Sim Ice Fill's two reward chests sit on coordinates read off one capture; Mod Only Test's holds
  gray wool there, so no chest stood at all until it searched for the torch-lit pedestals by the iron bars.

## Moved from CLAUDE.md (2026-10-15, to keep it under 300 lines)

- Auto Routes awaits no longer read the "x/y Secrets" bar at all (2026-10-05): it is the ROOM's count, so a teammate's
  secret raised it, and levers never touch it. `autoroutes/AwaitEvents` counts only his own clicks/pickups/nearby bat
  spawns/mimic kill, from the previous node's finish. The sim still sends the bar (`roomsim/SimActionBar`) for the map.
- A Superboom does not break the block it lands on: it blows the crypt or wall NEAR it. Auto Routes' boom node watched only
  the hit block and its six neighbours, so a boom that opened a crypt a block over read as "superboom didn't break anything"
  and stopped the route (his Museum stack, 2026-10-06). It now watches a radius-3 cube, and a boom that changes nothing
  within a round trip completes (nothing left to blow) instead of stopping; 96-ar-museum plays his exact route.

- **A sceptre BAT node counts its uses from the OUTGOING use packets** (`BlockClickGapSendMixin`: `ServerboundUseItemOnPacket` and
  `ServerboundUseItemPacket`), one per tick. The game-mode calls (`useItem` HEAD, `useItemOn` RETURN) are not seen in the sim:
  Fabric's UseBlockCallback answers an ability item at the head, so the first version never left its two downward uses
  (96-ar-1015-batseek, 2026-10-15). Past two it lets go of the held key and sends plain item uses at the nearest secret bat. Run
  memory also carries between cases: call `forgetRunMemory` first or the node says "its bat was already killed".
- No real Hypixel BAT-node run exists in his logs yet (every `Node #N BAT` line in Mod Only Test is a sim run), so "a bat already
  alive on arrival is never killed" is NOT reproduced: the sim kills and counts one (96-ar-1015-batpresent passes on main).
- A one-node route starts and completes inside `RouteExecutor.start()`/`complete()` before any executor tick runs, so anything
  that wants a route's progress (the Auto Routes Progress HUD, `RouteRunMemory.noteProgress`) must be told at start and at
  completion as well as each tick, or a short route is never seen (102-sim-autosecret, 2026-10-15). The planners' queue for
  that HUD is `autoroutes/RunPlan`, published by each planner at its own pick, never recomputed by the HUD.
- **A wait for the server's answer must not read a server stall as a failure** (killer560, 2026-10-15: the Interactive Map
  said "Warp 1 never landed - planning again from here" whenever Hypixel lagged). `util/ServerLag` measures stalls from the
  gaps between Hypixel's once-a-tick pings; every such wait takes `ServerLag.mark()` when it starts (or makes progress) and
  compares against `TIMEOUT + ServerLag.ticksSince(mark)` (`serverMsSince` for ms waits), which is 0 extra with no lag.
  The sim's server sent no per-tick pings until then (`roomsim/SimServerTicks` now does), so in the sim every
  `ServerTickClock` user had silently fallen back to client ticks. Testkit 1460-sim-server-lag / 96-ar-1015-serverlag.
- **Anything else on the Interactive Map's planner thread delays every trip.** The floor graph may only be searched from
  `ClearExecutor`'s single planner thread, so Auto Secret's room-order trip searches ran in front of a real trip: on a cold
  graph one search took 1197 ms and the trip's first warp went 1865 ms after it was asked for (144's first run, 2026-10-15).
  `RoomTour` now skips its searches while a trip search waits (`ClearExecutor.tripSearchWaiting`) or the graph is not warm
  (`EtherwarpPathfinder.graphWarm`), and a trip whose path is being planned ahead waits for that search instead of queueing
  a second one behind it.
- **A START handler registered late sees the previous tick's number.** `ClearExecutor` counted `clientTicks` inside its own
  START handler, registered from `LiveMapFeature` after Auto Routes and Auto Secret, so their START code in tick N read N-1
  and a route-end-to-warp gap measured one tick long. The count now runs in its own Fabric phase ordered before the
  default (`ClearExecutor.TICK_COUNT_PHASE`); every START handler reads the same tick.
- A route used to complete only after the interact delay and a walk-step tick past its last node, though that delay is a wait
  before a NEXT node; `RouteExecutor.finishAction` now completes it the tick the last node finishes. A one-node route then
  completes on its arming tick, inside `RouteExecutor.start`, so nothing ever sees it running: Auto Secret watches
  `RouteExecutor.completions()` / `lastCompletedRoom()` instead of `isRunning()`.
- **A dungeon trip's length is not its straight distance.** Two rooms 45 blocks apart were 13 etherwarps apart by the floor
  graph (the walls send a trip round by the doors), and one 115 blocks away was 4 (145's floors, 2026-10-15). Room order
  that guessed from straight lines put the wrong room next; `RoomTour` uses the planner's own warp counts, and its fallback
  estimate takes the larger of the straight line and 2 warps per room on the map's path.
- Auto Clear logged "etherwarp toward" before `startTrip`, which only turns the body back on that tick (Phase.RELEASE) and
  comes through again the next, so one trip was logged twice (131 case 9: 5 lines for 3 trips). It logs once the trip is
  really under way now.
- A trap route that ends at the trap room's edge is not out of it for the next warp. Auto Trap stopped the route the moment
  `roomAtWorld` left the trap, about a block and a half out; Auto Secret's planned-ahead warp then went at once and the sim's
  server answered "No abilities in a trap room" and had him back inside, so the run stopped there (142's trap case,
  2026-10-15; before, a search's few ticks of delay let him slide clear). Auto Trap now counts him out 2.5 blocks past the
  edge (`AutoTrap.LEAVE_MARGIN`); the route's walk carries him there.
- **The Interactive Map's arrival settle held every route start ~9 ticks.** Auto Routes stays inert while `ClearExecutor`
  is busy (interlock 5), and a trip stayed busy for its whole post-landing settle, so a start node armed 11 ticks after the
  trip's last warp (146, main, every room). A start-node warp (`ClearExecutor.etherPathArrive`) now ends on the tick the
  server's packet confirms its last landing (`finishArrivalEarly`, asked by Auto Routes at the top of its tick): 2 ticks in
  the sim. Two things the settle had hidden: he is still 0.05 above the block on that tick, so a planned-ahead first warp
  is aimed again from where he is (a look planned from the block's height missed: "Failed from ..."), and `canPath`'s
  on-ground rule held the next trip a tick or two (`canPath(layout, true)` accepts that settling). And the map's body turn must be handed back before the route turns the body for its first action that same tick: left to `ClearExecutor`'s own tick, later in START, it undid the route's turn and the warp drew GrimAC BadPacketsJ (62-argrim-imwarp-run).
- **`TurnFirst` reads an auto's OWN free camera as somebody else turning him, and waits 10 ticks.** Its `settle` holds a
  turn for up to `FOREIGN_WAIT` (10) ticks whenever `ViewFreeze` is held and it did not turn him itself, and an auto
  puzzle's `FreeCam` holds the view for its whole run. So every Auto Water click whose lever the reported look did not
  already meet went 11 ticks after it was due (his Hypixel run 2026-10-15 16:11:52/54, "11 tick(s) late"; the sim, testkit
  93-solve-waterboard-timing, main 12 ticks after the solver's CLICK ME, the branch 1). Auto Water now turns its own body
  onto the next lever as soon as he stands where it is in reach, so the click needs no turn. Since puzzles-nowait-1015 the
  puzzle autos' own `TurnFirst` takes a `FreeCam.anyEngaged()` hold as its own and never waits on it (testkit
  1015-grim-puzzle-nowait's turn rounds: turned the tick after the landing, clicked the next, GrimAC clean); Secret Aura's
  `TurnFirst` still waits on it, so Tic Tac Toe hands its camera back before the aura's chest.
- **"Click, then warp" costs one tick, not seven.** Auto Water's warp after a lever went 7 ticks late: a 200 ms gate, QUOI's
  2-tick sneak wait, and `RightClick.mustTurnFirst`'s turn tick (the heard look still met the lever). `AutoReposition` in
  next-tick mode puts the sneak down AND turns the body in the tick it starts (that tick's input and movement packets carry
  both, after the click), and the use goes the next tick; on the landing it ends at once, re-waiving the gate's teleport
  settle (a Hypixel landing comes a round trip after the use, past the 3-tick waiver taken then). Clean on GrimAC
  (1015-grim-water-clickwarp: click, turn and sneak the same tick, use the next).
- **Vanilla's answer to a server teleport bypasses the send tap** (2026-10-15, his Mod Only Test log 15:55, Flags #13/#15/#18).
  `ClientPacketListener.handleMovePlayer` sends the accept and a `PosRot` on `Connection.send` directly (javap, both
  versions), not through `ClientCommonPacketListenerImpl.send`, so `util/RightClick` kept tracing the server's ray from where
  he stood BEFORE a warp: a path hop a tick after the last landing went bare under an unpaired AOTV chest click, the server
  (tracing from the landing) paired and dropped it, "no landing after 62 ticks". `RightClickTeleportMixin` (RETURN of
  `handleMovePlayer`) records it. Separately, the sim fired a warp on a block half and then traced the use half from the
  landing (and from the 1.8 eye, 0.27 above the aim's), missed, and warped AGAIN (11.2 blocks on): the use half of a click
  whose ability fired now always pairs with it - one right click, one ability.
  Testkit 96-ar-1015-pathland.
- **Another feature's warp landing is not a correction of the map's trip** (2026-10-15, 88 alarms in one Ice Fill crossing).
  A trip with every landing answered sits in its arrival settle (or waits to plan again) for ~9 ticks; Auto Ice Fill started
  hopping on the first tile inside that window and each 1-block hop read "the server moved you off the last landing", then
  "while waiting to plan again", and the replan of the old goal dragged him back across the room against the chest claim.
  `ClearExecutor` counts its own uses against `RightClick.usesSent()`; a moving packet another feature's use (last ~2 s)
  accounts for is that feature's landing: in the settle the trip ends as arrived, while planning it is not reported. A move
  with no use behind it still reports (testkit 93-solve-icefill-maptrip asserts both).
- **The server's pairing ray and the client's pick can disagree** (2026-10-15, 1015-sim-clickgap). The 1.8 server traces a bare
  use from the 1.54 sneaking eye, the client picks from 1.27, so a warp's server ray can meet a block within 4.5 that the
  client's look never reaches: no block half can be sent and the warp is dropped under an unpaired click. Never click a
  block the client cannot see; `RightClick.reaimIfUnpairable` aims at the same block along a look whose rays agree and the
  warp goes one tick later (ClearExecutor hops, Auto Routes warps). Testkit 96-ar-1015-reaim builds the mismatch.
- **A run-time etherwarp aim must HOLD, not just land** (killer560 2026-10-15: path nodes "still taking me to the wrong
  block"). The planner proves every saved hop within `EtherSearch.AIM_MARGIN`; the aims worked out while flying
  (`TeleportUtils.getEtherwarpDirection` for a hop fired 0.05 over its spot, `RightClick.reaimIfUnpairable`) only checked
  the exact float look. From a block-centre stand, lines to face points on the tenths/half lattice often pass EXACTLY through
  a block's vertical edge, and the server, reading the running yaw a few bits off, takes the voxel tie the other way: his
  Mod Only Test 22:47:07 run re-aimed at -149, 261, -188 (eye -150.5, -191.5 to face point 0.3/0.7 crosses x -149, z -188
  at one t) and the sim's cast stopped in -149, 262, -189. Both now prefer a look that lands for +-AIM_MARGIN, over
  off-lattice points too. Also: "Etherwarp: landed" is any landing within 2 blocks of the planned feet, so a hop on the
  neighbouring block counted as landed; every landing is now judged by its block ("landed OFF its planned block", WARN).
  Second shape, same 0.05: when no sample point lands from the landing-tick eye, `stepOntoWarpSpot` read "back on its spot
  (0.00 off) after 0 tick(s)" (horizontal check only) and fired the SAVED look - planned from the settled eye - 0.05 high
  (his 22:47:32 Diagonal #8 hop 1 did exactly this, and happened to land). In 1015-sim-pathland it landed 5.4 blocks off
  (Overgrown) or was refused on a slab lip (Quartz Knight). The re-aim now tries `exactEtherwarpDirection` (aimExact), and
  "on its spot" includes the height, so the saved look goes the tick he has dropped onto it. Testkit 1015-sim-pathland.
- **Every bow puzzle's shot after a landing waited the gate's 6-tick teleport settle.** `AutoReposition` waived it for 3
  ticks at the USE, the sim lands 2 ticks after the use, and the landing starts its own 6-tick settle, so Blaze, Beams and
  Ice Path shot exactly 6 ticks after each landing (93-solve-*-timing on main, 2026-10-15; on Hypixel the round trip is
  longer still). The landing now re-waives the whole settle for bow warps too, and a trip a puzzle auto asks the
  Interactive Map for (`AutoPuzzleUtil.pathIfMapOn`) waives it for its landings (Lower Blaze's far-spot walks: 6 ticks
  after each). Also gone with the next-tick mode for every auto: QUOI's 2-tick sneak wait and 2-tick wait after a landing.
- **QUOI's `at(spot)` reads an etherwarp's landing tick as "not there yet".** He lands 0.05 over the block and gravity sets
  him on it the tick after; `ceil(y - 1)` is then the block above, so every reposition saw its own arrival a tick late (main:
  landing to the sneak let go 2 ticks). `AutoPuzzleUtil.at` also accepts up to 0.08 over the top; a look aimed on that tick
  is aimed from the settled standing eye (`settledEye`), or a small target's edge is passed by once he drops 0.05.
- **Auto Blaze's "arrow flight + 4 ticks" was a fixed margin on every miss.** A volley that killed already ended the wait
  (the target's label stand removed), but a miss - a wall, a refused hit - held the next volley the simulated flight plus
  4 ticks (93-solve-blazemiss-*-timing on main: 5 to 6 ticks after the volley's arrows had ended; the chest after a
  missed final volley up to 16). `AutoBlaze.VolleyWatch` takes the volley's arrows as they appear by his eye and calls the
  result in when none can still reach the target: removed by the server, stuck (`AbstractArrowInGroundAccessor`), or
  moving away from it (velocity against the direction to it - a Terminator's side arrows). A removed arrow waits for the
  server's next update unless the target's label already changed, so a kill reported a tick late is still seen. And the
  solver's list holds a blaze for a tick after its kill (it re-reads after the autos), so the order is filtered of
  removed entities - aiming at the dead one would send the volley through its spot at the next one up. Branch: 0 or less
  in every volley; no kill out of order in 93 kills.
- Auto Blaze started its own reposition while an Interactive Map trip it had asked for was still moving him; the warp
  never saw its landing and timed out 40 ticks later (blazemiss-lower, main and branch). `cyclePosition` waits while
  `ClearExecutor.isBusy()`.
