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
