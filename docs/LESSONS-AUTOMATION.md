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
