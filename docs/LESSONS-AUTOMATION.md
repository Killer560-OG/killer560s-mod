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
  **And only inside a tick** (grim-post-autopilot-1016): GrimAC Post flags a held-item change sent after the tick's
  `client_tick_end` (END_CLIENT_TICK, a task or packet handler between ticks, a render frame) whenever a transaction answer
  follows it before the next tick packet - so it flags in some runs only. Auto Secret's `decide` runs at END (his toggle
  key too), and a first trip taken precomputed (`ClearExecutor.runPlanned`) swapped to the AOTV right there: testkit
  62-argrim-1016-autosecret-key, Post on main, its trace `[client_tick_end, set_carried_item, pong]`. Every automation swap
  now goes through `util/HotbarSelect.select`, which outside a tick only sets the slot (vanilla's `gameMode.tick` sends it
  at the start of the next tick, before the movement packet, as for the scroll wheel). The testkit's
  `PacketTrace.sentOutsideTick()` catches the order without waiting for Grim's luck. Since hotbar-select-rest-1016 there
  is no other slot write in the mod: Breaker Aura, Auto i4, MaskSwapper's rod, Secret Triggerbot, Auto Debuff, the auto
  puzzles' `AutoPuzzleUtil.swapTo`, the pearl hopper and AP3's slot restores went over too (all START_CLIENT_TICK but
  AP3's, which is END and was already slot-only); a new swap goes through `HotbarSelect.select`, never `setSelectedSlot`.
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
  and the next hold re-captured it, and Auto Blaze's every-tick room-center seed re-took every lapsed lease and threw his
  mouse-steered view back (2026-10-06, "snaps my camera around everywhere"). Every auto puzzle that turns him now holds an
  `autopuzzles/FreeCam` for the whole run (engage before the first aim, `keep` every tick, `release` turns the body under
  the view, shifting the hand sway by the same whole turns). 93-solve-*blaze's CameraWatch checks it every render frame.
- **In obvious mode `Minecraft.pick` uses the HELD camera, not the body.** `Ap3ViewYawMixin` makes `getViewYRot/XRot`
  return `ViewFreeze`'s view, and the crosshair pick reads those, so a vanilla key press while the body is turned acts on the
  block the CAMERA looks at. The crypt node holds the real use key (vanilla `handleKeybinds`: held + `rightClickDelay == 0` ->
  `startUseItem`, every 4 ticks, javap 26.1.2 and 26.2) and `autoroutes/mixin/HeldUsePickMixin` re-picks along the body at
  the end of `pick` while it holds. Vanilla's held use with a sword on a floor sends `use_item_on`, `use_item`, `use_item_on`
  (both hands), which 62-argrim-crypt compares against a harness-held key.
- **Dungeon key pickup range = Key Base Range + 5; the Magnetic Talisman does NOT apply to keys** (confirmed in game by killer560, 2026-10-06; the wiki x3 is for items only, keys are armor stands). Default 1.0 + 5 = 6 blocks. The talisman setting and the x3 were removed; an old `magneticTalisman` key in the config file is ignored.

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
  0.6 off center and 0.27 up mid-jump, fired the recorded look, landed a block wide of #11 and the route stood there). The
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
- **A rule that fires on "blocks standing" cannot tell a regrow from an edit.** The breaker re-fire (2026-10-12) breaks a
  latched breaker node's standing blocks while he stands on it; `/ar edit db` picks add standing blocks to exactly that
  node, so the tick edit mode closed it broke both picks (96-ar-add, failing on main and the 1015 jars: 2 digs). The picks
  themselves never sent a packet. Closing edit mode now puts the room's breaker nodes in the re-fire's `refired` set.
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
- **At 1x the factory's production lore has no "Total Multiplier" line** (2026-10-18 log, 22:49 vs 22:35): the reader needed
  it, so no upgrade was ever a candidate and the helper highlighted nothing. A missing line now means 1x when "per second" is
  there (`ChocolateFactoryMenu.read`).
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
- **"The kill timer" in his Blood Camp reports is the Kill Popup, not a mob's box.** On 2026-10-18 his own log (Mod Only
  Test, 19:19:53) had the Trigger Bot click "use at 0.8 ticks" on a mob's countdown in the same second the popup was armed
  100 ticks before KILL. Every Blood Camp attack now waits for `BloodCampMoveTimer.killTimerAllows(offset)` (no greeting:
  no wait), and the Trigger Bot also clicks any blood mob off its landing spot or past its 20-tick window the first tick
  his look meets it (1018-bloodtrigger-offset / -next-mob). The Kill Sound plays on that same hit tick, not at box green.
- **A click cap holds at the client, not at the server.** Blood Camp's clicks go exactly the attack-speed cap apart
  (5 ticks at 100 Attack Speed), but the testkit server saw them 4 and 6 ticks apart, alternating (186, 2026-10-15): each
  end acts on a packet only at its own tick, so one landing a tick late and the next on time is a 4. The mean was exactly
  5. Assert the cap on the client's own click ticks, and at the server only the mean and "never more than a tick under".
- **QUOI's aura ranges are eye to block CENTER; ours are eye to block BOX.** A center distance is 0.5-0.87 longer than the box
  distance, so QUOI's 4.7 skull range is 3.8-4.2 to the box and was TIGHTER than our 4.5-to-the-box skull cap, though the
  number is bigger (2026-10-12). Compare in one measure. Skull Range now uses QUOI's center measure (testkit 842: 4.8 not
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
- **Hypixel acks block clicks but not a bare item use** (Mod Only Test log 2026-10-19 15:06-15:08): the refused-warp retry
  gated on `ChestWindows.acked(warpUseSeq)` fired only when one of HIS later block clicks brought an ack in, and never in
  six Slime #9 refusals, each of which sat the 60-tick timeout and then re-fired #5's whole stack from the same spot. The
  retry now also counts a use as handled after `NO_ACK_ROUND_TRIPS`; an unlanded warp from an unmoved spot holds its node
  (`refireHoldAt`) until he left-clicks or moves; and a resend re-selects the etherwarp item (two resends went out with
  BAT_WAND / ITEM_SPIRIT_BOW after he changed slots while it waited).
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
  the hit block and its six neighbors, so a boom that opened a crypt a block over read as "superboom didn't break anything"
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
  the exact float look. From a block-center stand, lines to face points on the tenths/half lattice often pass EXACTLY through
  a block's vertical edge, and the server, reading the running yaw a few bits off, takes the voxel tie the other way: his
  Mod Only Test 22:47:07 run re-aimed at -149, 261, -188 (eye -150.5, -191.5 to face point 0.3/0.7 crosses x -149, z -188
  at one t) and the sim's cast stopped in -149, 262, -189. Both now prefer a look that lands for +-AIM_MARGIN, over
  off-lattice points too. Also: "Etherwarp: landed" is any landing within 2 blocks of the planned feet, so a hop on the
  neighboring block counted as landed; every landing is now judged by its block ("landed OFF its planned block", WARN).
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
- **Pace a jittered click from when it is SENT, not when it is decided** (Auto E-Table, 2026-10-15, testkit 289). The
  solver stamped "last click" at the decision while Random Delay held the click back up to its max, so with Click Delay
  400 and Random Delay 500 two Chronomatron clicks landed 3 ticks (150 ms) apart, and a bigger draw could swap two. Also
  Click Delay and First Click Delay went down to 0 (one click every tick), and the old default was 200. Now: one puzzle
  click in flight at a time, stamped at the send; a Click Delay spacing check where every click is sent; both delays
  floor at 150 ms in setter, loader and slider.
- **The sim's ability hook ended vanilla's right click after its block half** (2026-10-15, 96-ar-chain after 96-ar-awaitskip:
  every corner warp waited a tick, `[0, 0, 1, ...]`, on the deployed jars too - order, not a merge). `SimAbilities.onClientUse`
  answers a block click with an ability item SUCCESS (no prediction for a reskinned block item), and `Minecraft.startUseItem`
  stops at a Success; on Hypixel the same click PASSes (stone with a sword or shovel) and the use half follows in the tick,
  pairing it. So `util/RightClick` held a crypt node's Hyperion floor click unpaired for good. `roomsim/mixin/SimUseHalfMixin`
  turns that Success into PASS inside `startUseItem` only, so vanilla sends the use half; the sim's server pairs it.
  Testkit 96-ar-awaitskip asserts nothing is left unpaired.
- **In the sim a clicked chest's window races the warp its await fires.** It arrives 1-2 ticks after the click, so whether it
  lands before the use is the client/server tick phase, which drifts through a session; when it does, the action starts over
  under it ("window arrived after the await was met", logged since) and acts a tick or two later. 96-ar-1015-reaim's control
  read 2 for 1 in session runs on the rightclick-reaim tip as much as on merged main; it reruns a side the window landed in.
- **A sceptre crypt node's reach check must not stop at its own bat.** `WeaponReach.batImpact` ends at the first living
  entity on the line, and the node's sceptre bat in flight is one, so the mob it had just shot read "out of reach" for a
  tick and the node turned to the next mob between a mob's two uses (96-ar-1015-cryptaim "2 mobs": 14, 13, 14 - read at
  first as the mob dying early). `cryptMobOwed` traces blocks only (crypt-aim-2-1016): A, A, B, B, C, C. Crypt mobs are
  told by Hypixel's whole name tag (`autoroutes/CryptMobs.TAG`, "Crypt Undead" or "Prince" with its health), never by
  "appeared near me", since entering a room brings every mob into tracking range at once.
- **With Auto Close Chest on, a chest click never shows a screen** (his setting; 2026-10-15, "stuck on ice fill after
  grabbing the chest", his log: the sim server opened the chest on click 1). The mixin cancels the open-screen packet and
  answers with a close, so the lid is open a tick at most and `getOpenNess(0f)` (the previous tick's value) often reads 0.
  The reward-chest step read the screen and the lid only: three clicks, "did not open", then a ten-second "appear" wait
  under Autopilot (93-solve-*-chest-autoclose on main). Anything asking "did my chest click work" reads
  `util/ChestWindows` (fed at the packet hook's HEAD in every build): RewardChest, Secret Aura, Auto Boulder, Auto Tic Tac Toe.
  Do not deduplicate those packets by container id - a server numbers windows per player from 1, so every new sim world's
  first chest repeats the last world's id; the hook's double run (network thread, then render thread) is one packet object.
- `AutoPuzzleActivity`'s name was the LAST mark of a tick, and one tick of a chest step marks three ("Ice Path", "Ice Path
  chest", "Puzzles"), so every "say it once per change" reader said it every tick (his chat: Autopilot's "Waiting for ..."
  twenty times a second; Auto Routes' "Arming: inert"). The name now holds for the working spell; Auto Secret says each
  reason once per room (102-sim-autosecret).
- **Auto Secret's last-node wait could never be met by Secret Aura** (killer560, 2026-10-15: "it will leave a room before a
  secret is grabbed and registered"; his Mod Only Test 22:42:48 Gold, 22:42:56 Basement - a one-node DUNGEON_BREAKER route
  with no blocks beside the room's only wither essence - and 22:42:49 Scaffolding's chest by its last landing: "0/1
  secret(s) counted at the route's end", "the 6-tick cap", no "Wither essence collected"). The wait holds the next trip as
  a queued `ClearExecutor` path, and Secret Aura stands down while `ClearExecutor.isBusy()`; the hold also kept the sneak
  down, and a sneaking right click with the AOTV in hand is the AOTV's (`SimAbilities.blockWins`, as Hypixel's 1.8
  server); and the landing's 6-tick teleport settle alone is the whole cap. Now the aura acts while the trip is
  `heldAtStart`, the hold lets the sneak up only while an aura click is due (`SecretAuraFeature.clickDue`) and turns the
  body to the first warp's look once it has gone, and a secret the aura has in reach and has not seen taken (or a
  database item secret by his feet) holds the warp until it registers, bounded by `TAKE_TIMEOUT_TICKS` (50 + server lag).
  Testkit 147-sim-autosecret-lastsecret. Since 2026-10-20 (his rule: wait at most 20 ticks, only when a secret is close,
  else leave at once) the cap is 20 and the flat 6-tick wait is gone - and the 6 ticks had been hiding something: secret
  chests spawn in only after he has been near them a moment (like bats, sim-secrets-1018), so at a route's end the aura's
  scan can see air where the chest will be. Leaving at once left 2 of 5 such chests in 147 until `takingHere` also counts a
  database chest in the aura's reach that is not there yet (up to `ITEM_APPEAR_TICKS`, as an item).
- **Deciding the next trip inside the wait overwrote the wait's own count.** `onRouteDone` calls `decide`, which sets
  `routeStartClock`/`routeFoundAtStart` for the NEXT room, and the wait then counted the room it was leaving from that
  room's found count: "every secret counted after 1 tick" with the room still 4/5 (147 on main, every floor). The wait keeps
  the leaving room's own (`leaveStartClock`/`leaveFoundAtStart`).
- **With Auto Close Chest on, a clicked chest never looks opened**: its window packet is canceled before any screen, and
  the lid can be shut again before a tick reads it. Secret Aura re-clicked every such chest after 1 s and Auto Secret's
  wait ran to its limit; `AutoCloseChestFeature.autoClose` now tells the aura (`noteWindowOpened`, network thread).
- **Auto Three Weirdos never moved, and QUOI's reach is why it never had to** (killer560, 2026-10-15, sim: "it cannot reach
  the right person"). QUOI clicked every CLICK stand within 5.48 feet to feet, which from the room's middle covers all three;
  ours is the measured 3.0 eye to box (an entity interaction), and from the Interactive Map's spot (relative 15, 68, 22)
  the far weirdo is 3.26 off in every rotation - the exact figure from his log, reproduced by testkit 93-solve-weirdos-reach.
  The sim's weirdos sit where the solver looks (one database block of -x from each chest, chests two apart), so it was
  the auto, not the sim. `AutoWeirdos` now moves onto the spot reaching the most weirdos left (then their chests) by its own
  next-tick etherwarp (a first hop, a walk or an Interactive Map trip when no line reaches one; from the doorway the
  weirdos are ~21 blocks off, past the 16-block scan it first had), and talks one a tick with the body turned onto the next in the same tick (main: a 200 ms
  gap). Its "waiting" log alternated two reasons every tick (781 lines in 20 s) because the talk and the solver each logged
  their own; one owner per tick now. The talked-to set also has to follow the stands, not the RoomEntry: a rebuilt room or
  the sim's new round reuses the entry, and the second round found "all three talked to" and never spoke.
- **Background floor-graph work must use FloorGraphs' own "knows the floor" rule, not `warmedOnce`.** The sim's full graph
  warms once on the ~250 landings of the sealed entrance before GO. TripCache first read "full graph warmed once and not
  warm" as "a search would finish a re-warm first" and waited for the whole floor's full warm-up: phase 1 ready 7.9-12.2 s
  after the gate opened (testkit 147, first run). A press in that state plans on the QUICK graph
  (`FloorGraphs.KNOWN_RATIO`), so `EtherwarpPathfinder.searchReady` now asks the same: 2.4-3.7 s on a loaded machine,
  most of it the quick graph's own warm-up wall time.
- **A map trip that sends every hop on the tick it is worked out (`ClearExecutor.sendNow`) loses hops under an unpaired
  block click.** Precomputed trips first ran with the planned-ahead path's every-hop `sendNow`; with testkit
  1015-sim-clickgap's click primer a hop went while the previous landing was still on its way, `RightClick` judged it from
  the planned spot, the server traced it from the spot before and paired it with the click: "warp 5 never landed" (twice in
  two runs; main's searched presses never send that way). Since autosecret-early-1016 every early-sending trip (planned
  ahead or precomputed) sends a hop early only while `RightClick.unpaired()` is false; otherwise it goes the next tick
  through `doInteract`'s turn-first check. 1015-sim-clickgap 3 of 3 runs, 0 hops ignored; 144/146 timing unchanged. The
  other early senders (Auto Routes' landing-tick warp, AutoReposition) only send after the landing is known and already
  go through `RightClick.mustTurnFirst`.
- Idle automation cost is invisible to 95-perf-features, which never switches automation on (`PerfBench.NEVER_ON`):
  measure it with testkit 95-perf-autoidle (fps-auto-1015). The biggest find there: Secret Aura's in-range cube asked the
  done-set first, and `done.contains(pos.asLong())` boxes a Long, so every block of the ~2,000-block cube cost 16 B every
  tick (32.9 KB/tick idle in the sim's Entrance) before any block was read. Read the block, drop what `classify` can never
  answer for, then box. The chunk-section palette prefilter alone did not help: a room's sections almost always hold a
  decorative skull.
- **The Redstone Key's secret is the PLACING, and the await counted the pickup** (aura-skull-place-1016). `AwaitEvents`
  took every player-head click as a secret, so the key's pickup met an await and fed `RouteRunMemory` (Auto Secret read the
  room as one secret further on than Hypixel), while the node click Hypixel credits ("rewards 1 secret", wiki) was "not a
  secret block". Now the pickup counts nothing and the node click counts (at once with a key known found on an open face,
  else on the server's answer); Secret Aura has always placed it (no hotbar swap: not an item since 0.18.4). Testkit
  1415, 96-ar-1016-key*, 848.
- **A precomputed trip planned on the quick floor graph is not flown once the full graph is warm** (fps-fix-1016). Such a
  trip was only "to be planned again", so a press in the second after the full graph warmed flew it: 15 warps where the
  full graph's fresh plan took 11 (testkit 147 on the FPS build, 2 of 2 runs; the pre-FPS logs show the same timing, it
  passed only because those floors' quick and full plans happened to be equal). Not an FPS cache: `Entry.outgrown()` now
  makes the press search (a few ms on the warm graph) and the cache replan those trips first, outside the quiet gate.
- **A warp the server will take as an unpaired click's second half is sent again, not waited out** (fullclear-1016; his
  sim run 2026-10-16 Cathedral #15 and Crypt #5, every time a chest opened with the AOTV by the hop's spot). The 1.8
  server's ray (1.54 eye) met oak leaves the client's 1.27 look never reaches, `RightClick.reaimIfUnpairable` found no
  look with agreeing rays, and the use was dropped: Auto Routes then sat out its 61-tick landing timeout off every node's
  ring until Route Stall stopped the route; the Interactive Map chained four more hops from the landing that never came,
  all refused. The dropped use clears the server's stored click whatever happens, so when `barePairedUses()` rose on a
  warp both executors send it again after `RightClick.eatenRetryTicks()` (1.5x the measured round trip + 2 ticks, at least
  3; 300 ms before the first pong) with no landing, and ClearExecutor queues nothing behind it meanwhile. If the
  judgment was wrong the landing arrives inside that and nothing is resent. Testkit 96-ar-1016-eaten (main: timed out;
  fixed: resent 4 ticks after, landed).
- **One item use per tick.** `ClearExecutor.onTickStart` has two hop slots, the held hop's (`doInteract` first thing) and a
  precomputed/planned-ahead queue's `sendNow` slot after `handleQueue`. A first hop held behind an unpaired block click went
  from the first slot with its block half, which paired the click, so the `!unpaired()` guard on the second slot let the
  next hop out in the same tick: two `use_item` with two looks before one movement packet, GrimAC BadPacketsJ, and two
  teleports answered together (62-argrim-autopilot, about one run in seven: an earlier case's aura click left the click,
  and only some explore trips start beside a wall). Not the eaten-warp resend: 04dfc9c5 (the unpaired guard, 2026-10-16
  01:35) brought it in, bisected with the case below (its parent passes 2 of 2, it and every later jar fail). `doInteract` now holds a hop while `RightClick.itemUsesSent()` moved since the tick
  began. Testkit 62-argrim-1016-oneuse reproduces it every run (main 098b6c92: fails; fixed: the second hop a tick later).
  The "Simulation 5.99" in that session's console is the positive control's own flag, not the case's.
- **A secret got under a node with no await belongs to the next await, however many await-less nodes come between**
  (fullclear-1016 run 004, his Double Diamond #4 PATH / #5 breaker / #6 etherwarp stack, #7 awaiting the item the breaker
  opened): the Magnetic Talisman range picked it up while #4/#5 acted, #6 opened its own window and closed it unused,
  and #7 waited 20 s for a secret already taken until Route Stall stopped the route. `finishAction` now keeps the carry a
  node began with when it has no await (`nodeCarry`). Testkit 96-ar-1016-carry (main: #3 never warps).
- **A lever is not one of a room's secrets.** Auto Routes' awaits count his lever flips (a route records one to wait on),
  and Auto Secret's room count reused that memory, so "Museum: every secret counted (5/5: map 4/5)" with the lever as the
  fifth and the room left a secret short - three runs of three (fullclear-1016 runs 002-004; 5/5 in 005-007 with the fix).
  `RouteRunMemory.secretsSince` / `secretsWhatSince` leave levers out; awaits still count them.
- **The start node's lookback must be near the start node.** `AwaitEvents.START_LOOKBACK` counts his secrets from the 10
  ticks before a route starts, for the Secret Aura click that races the start node's arming (2026-10-07). Secret Aura also
  takes the LAST room's secret as the map's warp leaves it, inside those 10 ticks, and that met Museum #1's await from 50
  blocks away; the route left #1's chest behind (fullclear-1016 run 010). A pre-start secret now counts only within
  `RouteRunMemory.NEAR_NODE` of where he stands when the route starts. Testkit 96-ar-1016-farstart (main fails).
- **A path follower tuned at a vanilla sprint swings off the line at his sim speed** (killer560, 2026-10-16: "the boulder
  pathfinder broke going out"). At speed 600 a sprint covers ~1.7 blocks a tick; Auto Boulder's 1.2-block pursuit carrot was
  under a tick ahead and each tick's turn an eased fraction of what was left, so the yaw swung -175, -192, -142, -246 on a
  straight 29-block roof run until "more than 1.6 off the segment" called it "pushed off the path" (testkit
  93-solve-boulder-speed-aura on main: 4 of 4 runs, 2 gave up; nothing pushed him). Where open floor at his height reaches
  the way out, the exit is now one straight run aimed at a point past the doorway (`AutoBoulder.runOut`), as the run in
  always was. The button mode's walks at that speed slid past corners, fell off the side of the one-wide stairs and pressed
  into their wall; letting go of W cannot brake them (one tick of W alone carries ~1.7 blocks), so a fast path walk is a
  sneaking walk (~0.4 a tick), corners are turned mostly standing, a stair point is only passed by standing on it, and the
  cell path is pulled tight first. A sprint-the-straights variant failed 2 of 4 (a 2.5-block fall, a re-plan).
- **The sim's Terminator spawned its arrows from where the server player was when the spawn TASK ran, not when the use was
  answered**: an etherwarp sent the next tick was often handled first, and the volley flew from the landing with the old
  aim (Auto Blaze's warp while the volley flies, 93-solve-lowerblaze-speed, 2026-10-16: shots lost, a 170-warp ping-pong).
  Arrows now start at the answered eye - 0.1, as vanilla's owner constructor does.
- **"The list stayed empty 30 ticks" is not the only way to know a blaze room is done.** When the list goes from one to none
  with the last target's stand gone and an arrow of the volley at it struck, that is the finish; Auto Blaze calls it done
  that tick (main: 30-31 ticks after the last kill, then AutoPuzzleActivity's grace). The volley's arrows must be picked up
  every tick of the wait, including under the secret trip and a warp, or the kill cannot be told from a chain rebuild.
- An auto puzzle's last step must call `AutoPuzzleActivity.ended()`, or Auto Secret, Autopilot and Auto Routes wait out the
  40-tick grace: Boulder's finish (his 2026-10-16 log, "carrying on" 2 s after "finished") and Blaze's done now do.
- **What Auto Secret waits on after a puzzle is the activity spell, not the puzzle** (killer560, 2026-10-16: "it registers
  them a lot later than it is actually done"). The sim's map checks a puzzle room from his entry, so PUZZLE_WAIT ended the
  tick `AutoPuzzleActivity.isWorking` went false - 40 ticks after the auto's last mark unless it called `ended()`. Testkit
  93-solve-*-resume, solve to resume on main: Creeper Beams 44, Three Weirdos 41, Quiz 23, Tic Tac Toe 12 ticks. Each now
  ends its spell on its own last step (the fourth pair, the right chest, Oruo's final-answer line, the game over): 3, 0, 0, 3.
  On Hypixel PUZZLE_WAIT (and Autopilot's `puzzleFinished`) also read the map or the tab list, a refresh behind; Hypixel's
  `PUZZLE SOLVED! <name> ... ! Good job!` line, anchored with a real name group, now counts the tick it arrives
  (`AutoPuzzleActivity.solvedByHim`), and the sim sends it (`roomsim/SimPuzzleSolvedLine`; only Tic Tac Toe's wording,
  "tied Tic Tac Toe", is from a real log) and Oruo's final-answer line, which it never did.
- **Secret Aura clicks nothing while a route runs unless the node waits for secrets** (killer560, 2026-10-16). On main a
  WALK route sprinting past a lever, a chest and an essence skull had the aura flip the lever mid-walk, and the walk then
  missed the chest by 5.1 blocks instead of 2.3 and never reached its end node (testkit 96-ar-1016-auranoawait, fcba4045
  jar). The one gate is `RouteExecutor.secretAuraMayClick()`, asked in `SecretAuraFeature.tick` before the body turn, the
  ActionGate and the attempt booking, and in `scanTaking`, so a held-back secret is just picked again later. It is true
  with no route running, and in a run only while the active node waits on a SECRET await it has not met. A warp in
  flight, the settle between nodes, a DELAY await, a crypt/bat node and an await already met all say no. So a secret
  passed mid-route is now taken only if an await node has it in reach (Kill Mimic's trapped chest and the Redstone Key's
  pickup and placing too); item pickups are untouched (96-ar-1016-auraawait, 62-argrim-1016-auraawait).
- **An unpaired click outlives the warp after it and eats a later one** (killer560's Bridges, Mod Only Test 2026-10-17
  22:34-22:36: "after the second dungeon breaker I cannot get it to make that etherwarp ... if it stalls out then it
  works"). Secret Aura flipped #1's lever with the AOTV in hand; #1's warp was a right click in the air, so the lever's
  verdict stayed stored through #2's breaker. The warp down the hole the breaker opened (155.3 / 56.7 from the node) passes
  under the rim from the 1.27 eye while the 1.54 ray meets Stone Bricks at -83, -51, -118 2.8 out, and no other look onto
  the landing had rays that agree: the use paired with the 10-tick-old lever click and was dropped, every run. The stall
  "worked" because the dropped use had paired the click. The 2026-10-16 resend made it a round trip (5 ticks in the sim);
  now `RouteExecutor.spendStaleClickFirst` pairs the click with a bare Dungeon Breaker use at the heard look the tick before
  (`RightClick.spendStoredClick`: only when `unpairable` holds, i.e. the client's look reaches no block, so one bare use
  is what a vanilla right click sends) and the warp goes the next tick. Testkit 96-ar-1017-bridges (his route, exact: main
  drops 1 warp and resends; branch 0 dropped, route complete with every routed secret), 62-argrim-1017-breakerwarp.
- **A Spirit Sceptre fired straight down never reaches a mimic six blocks up** (his Chains, same log 22:32:46: the trapped
  chest at y -43 over his feet at -49, 51 uses at pitch 90.0, then "did not die in 5 s"). The blast is 6 from where the
  bat hits the floor. Kill Mimic now sends two uses down and aims the rest at the mimic (a new baby zombie by the chest,
  `AwaitEvents.liveMimic`) while `RouteExecutor.sceptreReaches` says its bat gets there, in every room (killer560,
  2026-10-17); obvious mode uses only once the server has heard the look. Testkit 96-ar-1017-mimicaim (main: 51 down, no
  kill; branch: 2 down, 3 aimed, killed), 62-argrim-1017-mimicaim.
- **His routes count a secret bat's APPEARANCE toward the node waiting when it appears, BAT node or not** (QUOI's
  rule). Deathmite #11 breaker awaits 2, a chest and the bat that #12 BAT then kills, on one tile; taking the appearance
  away from #11 while a BAT node was ahead stalled it 20 s (fullclear-1016 run 034), so that change was reverted. The
  same rule is why his Doors fails under aura-await-only: #7 awaits 2, the bat appears 8.6 blocks off and meets one, the
  second lever was left to Secret Aura mid-route (runs 013/015 before the rule), and the way it opens stays shut, so
  #13's hop 3 is refused at that jungle log (runs 019, 026). Route data ("needs await node"), not code.
- **A check that reads the client's chunks disagrees with a planner that reads LevelEtherGrid** wherever the client has
  no chunk (the sim's server mirror fills those columns for the planner). Far trips at a floor's start hit it: the plan
  validator refused every Ice Fill plan and the recorded-spot check sent the trip to the wrong block (fullclear-1016
  runs 036/038). Both now ask the planner's grid when the client has no answer (52d1e614). Also, a trip that never
  names its `target` cannot give up: Ice Fill first looped for 480 s on "trip to null" (9a151f5b).
- **A warp the server refused is sent once more, not waited out** (fullclear-1016 run 019, his Doors #13 hop 3): the
  server's ray stopped at a jungle log, no landing came, and the route sat out the 61-tick timeout and then Route Stall.
  (That log is the shut way of the entry above, which the client sees too; the retry is a guard, not Doors' fix.)
  With no landing a round trip on (`RightClick.eatenRetryTicks`) and him not moved, `RouteExecutor.retryAim` aims at the
  same block with a look that holds within `EtherSearch.AIM_MARGIN` and the warp goes once more; a saved hop fired from
  its own spot is re-aimed first when its look only lands for its exact floats (`TeleportUtils.holdsOn`). Testkit
  96-ar-1016-refused. Risk on Hypixel: a landing slower than 1.5 round trips + 2 ticks (plus measured server lag) would be
  followed by a second warp; ServerLag covers stalls, not a single late packet.
  Since 2026-10-19 (his Mod Only Test log 16:17-16:18): on Hypixel the first warp a tick after a Secret Aura lever or chest
  click went unanswered in 6 of 8 cases (cause still NOT found: server ray met nothing, the same look landed on the resend),
  and the retry, gated on 3 round trips because Hypixel never acks a bare use, came 13-22 ticks later while every landing
  took 2-4. It now goes past `RouteExecutor.refusedRetryTicks()` (2x the slowest of the last 5 landings + 2, or
  `eatenRetryTicks`), up to twice per warp, and `retryAim` resends the same look when no other holds (it used to send
  nothing: Pedestal #3, 16:15:55, waited for his left click). Each secret await logs "secret gap: ..." when the next node fires.
- **On Hypixel a use one client tick after a block click made with the SAME item gets no teleport about two times in
  three** (ar-1019, every AOTV warp with an unpaired AOTV click before it in his 2026-10-19 Mod Only Test and Untested
  Cheat logs): 1 tick after, 45 of 69 refused; 2 ticks, 1 of 7; 3+, 0 of ~250; after a Dungeon Breaker or Superboom click,
  3 of 3 landed one tick on. Every K=1 case had a fresh sneak, but 19 of 19 fresh-sneak warps with no click landed, and the
  server ray met nothing in every refusal, so it is neither the sneak nor the 1.8 pairing above; the cause is NOT known.
  QUOI (`interactDelay = 2`) and RSA (`lastBlockC08 = 2`) both resume at N+2. `RouteExecutor.clickGapHolds` sends the
  warp two client tick ends after such a click (counted from `client_tick_end` packets, the server's own clock); the
  testkit's `HypixelClickThenUse` plays the rule (96-ar-1019-clickgap: main ignored the warp and retried, 7 ticks; fixed 3).
  `ar1019-refusals.py` in killer560s-mod-logs rebuilds the table from any log.
- **The Interact Delay was a fixed wait on every node transition but warp-into-warp** (494 times, 988 ticks, in his
  2026-10-19 logs). Obvious mode now has none: the next node goes the same tick when nothing acting on the world left this
  tick (`RouteExecutor.quietThisTick`, fed by `RightClickSendMixin`), else the next. What it guarded is now checked against
  the server: the breaker digs again whatever still stands a round trip on and never moves on past a standing block, and a
  boom sent within 2 ticks of a landing that changed nothing goes once more. 96-ar-1019-nosettle: landing to breaker dig
  3 -> 0 ticks, breaker to boom 5 -> 2, boom to warp 5 -> 2. Legit mode keeps the delay.
- **62-argrim-chain's 20/s pace is the server's answer time.** The same jar read 1.00 and 1.18-1.55 ticks per warp an hour
  apart on a busy machine, base fcba4045 included, and every slow warp still went on its landing's tick; there was no
  regressing commit. The case judges 20/s against the round trip it measures (testkit fullclear-1016).
- **A Quiz question can be asked while he is in another room** (Oruo's lines reach the whole dungeon), and Quiz Solver
  reset its answers on every room change, so a question asked while Auto Secret was doing a room between answers was gone
  when he walked back in. While `QuizState.inProgress()` a room change now only drops the pillar positions (2026-10-17).

## Auto Fairy Souls (fairy-souls-1017)

- **A ClearExecutor node that is not a `ClearNode.EtherNode` never gets its sneak.** Every "is this an etherwarp" test in
  ClearExecutor (forced sneak, eye height, item swap) is an `instanceof EtherNode`, and Auto Fairy Souls'
  `EtherwarpHopper` node was a plain `ClearNode`: no hop of it ever went out ("[Path] off the plan: the sneak never reached
  the server", testkit 1017-fairy-escape-ether, 14 tries, 0 server warps). `EtherNode` is extensible now (`items()`,
  `range()`) and the hopper's node is one; 2 server warps, out of the pit, GrimAC clean.
- **`getDeltaMovement()` at START_CLIENT_TICK is the velocity AFTER last tick's friction**, so the next move is that plus
  this tick's push: at Skyblock 600 sprinting it read 0.92 while he moved 1.68 a tick. The walker's key choice plays each
  combination one move ahead from it (`pathfinding/WalkPhysics`); a tie between "too fast" and "too slow" must go to slow,
  or one 0.6-block push too many slid him onto the soul (rest 0.05 from it, aiming for 1.6).
- **Standing on a soul puts his eye inside its stand's box** (a 1.975-tall armor stand, eye 1.62): `AABB.clip` from inside
  returns nothing, so the click never went (testkit 1017-fairy-overshoot at 600: 700 ticks, 0 clicks). Inside the box is a hit.
- **The old walker's overshoot was the key rule, not the camera**: W was held at a carrot up to 8 blocks ahead whenever the
  body faced within 70 degrees of it, and released only when the runner said "in reach" (3.0). Measured on the L route
  (testkit 1017-fairy-overshoot, before -> after, two runs): past the corner at Skyblock 400 +2.28 -> -0.14/+0.08, at 600 +3.47 -> +0.10/+0.75,
  600 with Jump Boost II +2.63 -> +0.27/+0.24; past the soul at 600 +0.70 -> -1.73 (stopping 1.7 short). Walk, sprint and Speed II
  were never past.
- **Simulated key presses (Fabric's `TestInput.pressKey`) never reach a key read with `KeyUtil.isKeyDown`** (GLFW's own
  state): the Start/Stop key poll did not see the testkit's K. The key is taken from `KeyboardHandler.keyPress` now, the
  same event the "any key stops it" rule reads, so a press is either the toggle or a stop, never both.
- **A strict straight-walk check is not an off-route signal.** Planning a way back whenever no carrot passed
  `WalkTerrain.walkable` for 8 ticks fired at the end of testkit 746's wall (the shoulder check) and planned eight ways back
  in a second, then "no way out". Only fluids, a fall below the route, being pressed against a wall with nothing walkable
  ahead, or no progress (stuck) start one.
- **The Quiz starts when a player walks into the room, not on a click on Oruo** (killer560, 2026-10-18). The 2026-10-17
  work had Auto Quiz walk to a guessed "CLICK" stand and talk; his logs put the intro 0-1 s after his own room entry in
  all 47 runs where he walked in. The sim starts Oruo on the footprint, and Oruo's intro can reach the client a tick or two
  BEFORE the live map names the room, so anything pairing the two must allow either order ([QuizLog] does).

## Redstone Key spots and Auto Secret's room order (sim-secrets-1018)

- **An await beside a Redstone Key spot the key never spawned at starts one secret on** (killer560, 2026-10-18: a room
  with two key spots needs an await at each, and the empty one waited until he clicked). `autoroutes/KeySpots` reads the
  room's key spots from the room database; a node with a SECRET await whose eye (feet + 1.62) is within 4.5 of a spot's
  block box gets one remembered secret per spot that is loaded, holds no player head, and never held a key this run (each
  spot is looked at every tick in the room, and his click on a key marks it), only in a room with two or more spots
  (Redstone Key). Any player head counts as not empty, so a head whose profile has not reached the client yet is never
  read as "no key". The key's pickup still counts nothing (aura-skull-place-1016), so the await at the spot that DID get
  the key waits for something else of the route's; his Redstone Key route awaits 2 at the key-2 spot. Testkit 1018-ar-keyspot-empty.
- **Auto Secret's Room Order** is Least Etherwarps (`TourPlanner.shortest`, exact branch and bound over the travel alone, then
  nearest-next improved by moving single rooms) or Largest To Smallest (`RoomTour.byWeight`), and only those two; with every
  room left at 3 unfound or fewer it is Least Etherwarps whatever is set (`RoomTour.effectiveOrder`, one place for the choice
  now and the plan-ahead). The value-weighted `TourPlanner.plan` is Dungeon Autopilot's alone. The room on the near side of the blood door
  (`AutoSecretFeature.preBloodRoom`: the door cell's two neighbors, the one that is not the blood room) is held out of
  every order while any other room with secrets is left, open or behind a door; the endgame takes it just before it would
  wait on a teammate's door. Testkit 1018-logic-room-order, 1018-sim-autosecret-preblood.
- **A use-on-block (block place) has no rotation of its own: GrimAC 2.3.74 lends it the NEXT movement packet's only when
  that packet arrives within 15 ms** (`CheckManagerListener.handleQueuedPlaces`, javap), else judges it by the rotation
  before - RotationPlace "post-flying", then "pre-flying" on every later place (the buffer never drains). AP3 placed at
  START_CLIENT_TICK and a slow tick opened the gap (63-ap3-block-*, 2026-10-18); its USE / BLOCK / BOOM now go out at the
  head of `sendPosition` (`Ap3Executor.beforeSendPosition`). Any snap-aimed place should go out there; testkit
  1018-ap3-block-slow-tick proves it (START-sent: flagged 2 of 2; sendPosition-sent: clean).
- **A look aimed at a block's center can pass a neighbor's corner by a hair and miss in floats** (Auto Creeper Beams
  Melee, 2026-10-18). The exact eye-to-center ray met the lantern, the turned body's float yaw/pitch met the next block,
  and the auto turned every tick and never hit. `AutoBeams.visiblePoint` samples face points and keeps one only if looks
  0.35 degrees either way meet the block too. Half the room's lanterns are set in walls, so center-only sight hid most spots.
- **The testkit finds Auto Routes events by log substrings, so a new log line must not reuse their wording** (2026-10-18).
  The first `[AutoRoutes] trace` start line said "await secret 1", which is how 96-ar-crypt finds AwaitEvents' count line,
  and its order check failed on a logging-only change. The trace now writes "1 secret"; grep the testkit before wording a line.
- **A fallback that hands over to another mode inherits that mode's preconditions** (Creeper Beams Melee, 2026-10-18). Melee
  ran out of on-foot pairs and handed the room to Shoot mode, whose first step is an etherwarp onto the center platform;
  from deep in the room there was no line onto it, AutoReposition refused once, and the auto sat there until he moved
  (his Mod Only Test log, reproduced by 1018-beams-melee-after). Melee now reaches each lantern itself (spot, AOTV, shot).
- **"Aimed" must mean the look the server HEARD, not the body's look this tick** (Auto Water, killer560 2026-10-18: "failed
  to flick a lever"). His Hypixel log 19:25:17 / 19:25:19: two levers due as he landed were clicked on the tick after the
  landing with the server still holding the warp's look (0.00 and -180.00 yaw, 14.57 down, three blocks off the lever);
  `prepare()` had turned the body earlier in that same tick, and `aimedAt`/`TurnFirst.readyBlock` read the body. The reward
  chest stayed locked. `AutoPuzzleUtil.gateWorldClick` (block and entity) and Auto Water's own check now also require
  `RightClick.heardLook` to meet the target; the click goes the next tick. Both clicks were late (22 and 43 ticks) because
  the warps before them, sent a tick after a lever click, got no landing and `AutoReposition` waited its 40-tick timeout
  before a fresh start; why Hypixel dropped them is NOT found (the server ray met nothing, the same look landed 2 ticks
  after the resend). A use with no landing is now sent again after twice the measured landing time plus 2 ticks (or
  `RightClick.eatenRetryTicks`, whichever is longer) plus any server stall, once per warp, while he has not moved. Testkit
  1018-waterboard-lever (a server that refuses a lever click the heard look misses and drops that warp) and
  1018-waterboard-grim (the real auto on a pasted board, GrimAC).

## Auto Secret and Auto Clear in one visit (acplan-1020)

- Generated sim floors have no mobs and clear every room on entry, so an Auto Clear comparison there measures nothing.
  Testkit 1016-sim-fullclear `-PfullclearMobs=N` places starred mobs (seeded by map code) and turns clear-on-entry off;
  `-PfullclearNoTraps=true` keeps trap rooms out (his trap routes do not work yet, 2026-10-20).
- Auto Clear waits while ANY route runs. A route Auto Secret stopped for stalling re-armed on the node under him a tick
  later, and the clear it had just handed over waited 13 minutes. Every hand-over to Auto Clear goes through
  `AutoRoutesFeature.cancelForInteractiveMap` first, as a map trip does.
- The "every secret found" stop ended runs on the trip to the last uncleared room (the last secret tends to come in
  then) and, before that, at 100% with up to 17 rooms never cleared. It now waits for a clear under way and for every
  mob room. The base jar's sim times on such floors are not comparable: they skipped the clears.
- `AutoClearFeature.estimateClear` prices a clear by its cover count far more than by where he starts (the AOTV range
  makes most first trips one warp), so clear-first vs route-first is nearly always a tie: route first. The time Fastest
  Total saves is the re-entry trips and settles, about 12 s a floor in the sim (8 floors, 2026-10-20).

## Booms, puzzle leave and the chest rule (2026-10-20)

- **A boom node stores only a look, and the look was cast from the live eye.** With the previous warp's sneak still held
  (0.35 lower) a shallow look met the floor a block short of the block it was recorded on, and Hypixel blows only what is
  within 3 of the dug block: his Museum #11 (27 degrees down) dug -167, 68, -163 and changed nothing, fired standing it dug
  -166 and blew 30. `RouteExecutor.planBoom` casts the look from the node's standing eye once (`/ar add` is typed standing)
  and every send aims at that block from wherever the eye is. Testkit 96-ar-1020-boomsneak (main digs a block short in
  Museum) and 62-argrim-1020-boomsneak (main blows nothing, fixed blows both, GrimAC clean).
- A boom that blew this run is remembered (`RouteRunMemory.boomed`, keyed with its look: a stack's booms share a tile) and a
  re-run of its stack completes it at once; main re-sent it and waited 13 ticks per re-run of a two-boom stack.
- **Leave a puzzle on its auto's `AutoPuzzleActivity.ended()`, not the map's check.** Hypixel's check for Higher Blaze came
  7 s after the auto ended (Mod Only Test 09:55:27 -> 09:55:34). The sim sends its "PUZZLE SOLVED!" line with the solve, a
  tick before the auto ends, so testkit 93-solve-higherblaze-leave leaves at 0 ticks on main too: it proves the fix leaves
  on the auto's own signal, not that main was slow. Holding the sim's check back from the test thread races the packet.
- **Chest rule** (killer560): `autoclear/ChestClearRule`, fed by `RouteRunMemory.gotSecret`. A mob room's chest grabbed under
  Auto Clear or Auto Secret counts it cleared 3 s on; not cleared on the map 30 s on, it is returned once (Auto Secret drops
  it from `clearsDone`). Testkit 1020-sim-chestclear.
- **A Shadow Assassin's leap refuses block clicks CLIENT-side** (killer560, 2026-10-20: a route froze on a chest). Hypixel's
  leap is a `ClientboundInitializeBorderPacket` that leaves him outside the client's border, and `useItemOn` returns FAIL
  before sending anything for a block outside it (javap, both versions; QUOI `fixInteract` and RSM `WorldBorderFix` bypass
  that check - this mod does not). Secret Aura booked each FAIL as an attempt and gave the chest up, so an await sat at 0
  for ever. It now holds while `util/ShadowAssassinLeap.refuses` (clicks when the border lifts, 5 s ceiling), and an
  await lets go uncounted once a click was refused and none is coming (that route run then does not mark the room fully
  secreted). Sim: `roomsim/SimShadowAssassinLeap`; testkit 96-ar-1020-saleap / -letgo.

## Auto Clear edge mobs, bats and doors (ac-1020)

- A held sceptre use sends a bat every 4 ticks, so a second bat is nearly always in the air when the first hits (sim
  flights 5-12 ticks). Counting only mobs that got exactly one bat learned nothing in 131e; a death before the second
  bat could land counts as a one-bat kill (`BatLog.secondLands`).
- The wiki's Guided Bat "follows your aim" (Spirit Sceptre page, 2026-10-20); the sim's bat flies straight. One-Tap
  Warp At Once turns away while the bat flies, so the sim cannot tell whether it misses on Hypixel; After Impact keeps
  the aim on the mob until the bat is due.

## Route stalls in the sim (stall-1020)

- **His routes count the Redstone Key's PICKUP as an await event.** ce2cc26b (2026-10-16) made only the placing count,
  and his Redstone Crypt #1 (await 2: its chest and the key) stood 20 s on every floor that has it. The pickup counts
  again once the skull goes (`AwaitEvents.countPickup`); the run memory files it like a lever, so Auto Secret's room
  count still has only Hypixel's secrets. Testkit 96-ar-1016-keyawait/-keyaura expect it now.
- **A breaker node fired with the sneak still at the server measures from the 1.27 eye.** His Wizard #1 (start node, right
  after the map's sneaking warp) left its third block at 4.71 to the linger; the stacked #2 etherwarp put the sneak back,
  the block never came in reach and #2's landing stayed shut. A non-sneaking breaker node now waits (one tick in the sim)
  while a block is in reach only from the standing eye (`RouteExecutor.breakerStandTicks`; testkit 62-argrim-1020-breakerstand).
- **The floor graph's "already there" (within five blocks) is not on the block.** Auto Clear's fast trip landed 4.9 off
  its stand block in Flags and Mines; every later trip ended at once where he stood and the clear asked again every other
  tick for 16 s. A repeat trip to the same block now asks for the block itself (`etherPathArrive`), and when that ends
  off it too the mob is skipped (`AutoClearFeature.tripTo` / `tripsCannotReach`).
- An auto that leaves its room as its last step must call `AutoPuzzleActivity.ended()`: Auto Teleport Maze's end pad
  sends him out and the leave-reset never did, so Auto Secret waited out the grace (2 s in the rerun; 64 s on one floor
  of acplan-1020, cause of the extra length not found).
- Kill Mimic runs outside routes too (Secret Aura's trapped-chest click under Auto Clear), and Auto Secret warped away
  under it; it then fired 51 sceptre uses straight down in the next room. Auto Secret now holds for it
  (`holdForMimic`), and it ends once he is 16 blocks from the chest. That hold then showed the real waste: Dueces'
  trapped chest opened as a plain chest (no mimic in it), and Kill Mimic fired its whole 5 s timeout at nothing. It now
  ends when the chest's window has come and no new baby zombie is by it 4 ticks later (plus server lag).
- At 100% secrets Auto Secret stopped with the endgame's puzzles undone once routes stopped stalling; it carries on
  while a puzzle is left (`unfinishedPuzzleLeft`).
- Route data, not code (left as recorded): Redstone Key has no await node within click reach of its key spot (19,66,7)
  (#8 waits for 2: the item and the placing, and the key is never taken under the aura's await-only rule); Golden Oasis
  #1 (start) waits for 1 at the entrance with no secret in reach; Supertall #13 CRYPT sits 1.41 off #20's landing, a
  step he walks by hand. Quiz cadence (waiting at Oruo for the next question) is the 4-12 s "TRAVEL" still at floor start.
- ServerLag measured stalls by wall clock between pings, so a client that ticks slower than real time (the testkit's
  lockstep recorder, a hitch, a frame longer than a tick) read every tick as server lag and pushed tick-counted waits to
  the 600-tick cap (a 30 s stand outside Ice Fill, 1915 2026-10-20). Tick-counted waits now count only client ticks that
  ran with no ping; ms-timed waits keep the wall-clock measure.

## Auto Clear kill speed (autoclear-speed-1020)

Measured with testkit 1020-sim-ac-speed (whole sim floors, the sim's live mobs, Auto Clear alone with the Spirit Sceptre
and AOTV) and the `[AutoClear] room time:` / `trip time:` lines (`autoclear/RoomClock`), which every real run now logs too.
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
- ActionGate's fixed settles (6 ticks after a >4-block jump, 3 after any screen change) held Breaker Aura's first dig after
  every etherwarp and leap (2026-10-22). An actor that re-picks its target each tick from his current position
  (`Actor.freshEachTick`, only BREAKER_AURA) waives both; the WORLD container rule still applies. Testkit 1022-ap3-breaker-*.

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
- Two item uses in one client tick are BadPacketsJ: each use must carry a rotation that tick's single movement packet
  reports. "Several etherwarps per tick" is several per SERVER tick: one per client tick, held by Blink and released
  together (Blink The Chain).
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
