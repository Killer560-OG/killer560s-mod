# Feature-specific lessons

Moved out of CLAUDE.md to keep it under its size limit. Same rules: problem, then fix; verified only.

- `RenderSystem.setShaderColor` does not exist in 26.1.2, so there is no global colour multiplier and items
  cannot be tinted per-item. The inventory HUD's Opacity now dims items with a translucent quad drawn over the
  panel after the item loop instead: 0 hides the panel outright, and the darkening is capped at 80% so no
  setting turns it into an unreadable black box.
- The gametest client runs as **java.exe**, not javaw.exe. `run-scenario.ps1` filtered on javaw only, so every
  safeguard in it was inert — the freeze watcher never saw an unresponsive client and the deadline cleanup
  killed nothing, while the script reported success. That is why "it still doesn't close out on freeze"
  survived two rounds of fixes to the watching logic.
- **A `HudElement`'s `render()` is not always where it draws.** For `croesus_start_button`,
  `experiments_start_button`, `rng_meter_ranking`, `storage_overlay`, `inventory_hud` and `custom_scoreboard`,
  `render()` is ONLY the HUD editor's preview and the real pixels come from a container-screen or Fabric HUD
  layer elsewhere in the feature; `etherwarp_waypoints` never draws at all (`isVisible()` is hardcoded false).
  So anything that needs to know "was this on screen" must be placed at each feature's own draw site, not on
  the interface method. That is what `hud/HudSeen` does, and why `isRelevantNow` was split into
  `isEnabledInSettings()` (the toggle) plus the draw stamp on 2026-09-30 - the old single predicate had
  already drifted from the render path it mirrored in four places (Split Timers' tested `isInDungeon()` and
  its `render` did not).
- `Map.getOrDefault` EVALUATES its default eagerly. `HudConfig.getPosition` allocated a throwaway `int[2]` on
  every call even when a saved value existed - about 110 allocations a frame across the HUD.
- Instant Transmission is 8 blocks on Aspect of the End, Aspect of the Void AND the Etherwarp Conduit alike.
  What changes the range is the item's own `tuned_transmission` tag - a Transmission Tuner adds a block, four
  maximum - so a fully tuned one of any of them goes 12, and killer560 plays fully tuned ("nearly no one plays
  with less"). AOTV is NOT 12 by nature; assuming that got AOTE and AOTV wrongly split in the route matcher
  once already. `EtherwarpHopper` reads the same tag for the 57-block etherwarp.
- `ItemIdentity.of()` is shared by Auto Sell, the Inventory Sorter, Armour Dye and the mining profit tracker.
  Widening it to make two items equal makes "sell my Hyperion" sell an Astraea. Loose matching belongs in
  `matches()`, which only a route's USE_ITEM node reaches.
- **A HUD element's `width()` must be in the registry's unit, and the HUD editor saves on a zero-pixel
  click.** `HudElementRegistry` defines an element's on-screen size as `width() * HudConfig` scale, but the
  Storage Overlay's `defaultX()` centred against `width() * its own slider scale` and its render pose used a
  third combination, so the clamp, the editor box and the drawn panel measured three different panels
  (2026-09-30). A clamp also cannot rescue a panel *wider* than the screen - it only picks which columns to
  hide - so `gridWidthLocal()` now drops columns until the grid fits. Separately, `HudEditorScreen.
  mouseReleased` persists a position for any press-release on a box, drag or not: one click in the editor
  while the window was briefly 854x480 froze `storage_overlay` at the clamped `x:0` and it stayed there at
  2560x1441, which is what "the storage overlay is no longer centered" turned out to be. A saved position is
  never re-clamped, so the cure is deleting the element's entry from `killer560smod-hud.json`.
- **Superpairs powerups come in two kinds, told apart by lore.** "Instant powerup!" (the `+479,095 XP` lapis
  block, "Gained +3 Clicks") applies on the spot; only "Powerup for next click!" (Instant Find) matches the next
  click. Arming on both spent a lone click on an Enchanted Book and reserved it, blocking its pair (2026-10-01).
  Turning a powerup over is all it takes (killer560, 2026-10-05): it never uses a turn's click slot (the open tile
  stays up), Instant Finds stack ("insta find into an insta find then your next two clicks are insta finds"), and
  there is no activation click - the solver's old second click on the face-up tile did nothing but wait out 1s.
  XP reward tiles are recognised by NAME (`... Enchanting Exp`): the item varies, and cocoa beans were missed.
  Clicking a tile that is already uncovered does not use a click (killer560, 2026-10-01). A pair's first click
  can fail to land (tile still covered, others still read "Click any button!"); clicking the partner anyway lost
  Experiment the Fish, so the first tile is now re-clicked before its partner. **Superpairs is played in turns of
  two clicks**: a pair started while a tile is turned over matches against that tile and both pairs are lost, and
  a chance match that is not recognised as claimed gets re-queued and clicked as no-ops forever. The solver reads
  both off the board each decision (two face-up tiles of a kind = claimed, one = the turn's open tile; 2026-10-05,
  `ExperimentSolver.readBoard`). An offline port of the testkit's Hx table driving the solver class found both: in it,
  main's solver never finished 207 of 300 random boards (how often real Hypixel boards hit it is unmeasured).
- `ServerTickClock.now()` counts ticks since client LAUNCH, so `now() % N` has no relation to any server cycle. Tick
  Timers' clear "Death Tick" was `20 - now() % 20` and so never lined up with anything; removed 2026-10-06. A phase needs
  a server anchor (NoammAddons used `ClientboundSetTimePacket.gameTime`).
- `ServerTickClock`'s subscribers cannot measure a lag spike: a real stall drops the ping rate under 15/s,
  the clock flips to client-tick fallback, and what it fires is the client's own ticks. Anything measuring
  server stalls subscribes to `subscribeRawPing` instead (as `experiments/ServerLagSensor` does).
- **Hypixel's Bazaar summaries are named the opposite of how they read.** In
  `api.hypixel.net/v2/skyblock/bazaar`, `buy_summary` is the book you INSTANT-BUY OUT OF and `sell_summary` is
  the one you instant-sell into. Verified on `VIBRANT_CORAL` (2026-09-29): `quick_status.buyPrice` 3324220.9
  matches `buy_summary[0].pricePerUnit` and `sellPrice` 221605.8 matches `sell_summary[0]`. Reading them the
  other way round produced a fake 1.6-billion-coin flip. `quick_status.buyPrice` is also a weighted AVERAGE,
  matching the exact top of book on only 628 of 1833 products, so anything sizing a real purchase must walk the
  levels. Scale check for the Bazaar-to-NPC flipper: of 819 products with an `npc_sell_price` only ~46 profit at
  all and the worthwhile margins are 0.4%-1.5% - a result far outside that band means the book is backwards.
- A hotbar swap sent as `setSelectedSlot` + a hand-made `ServerboundSetCarriedItemPacket` goes out TWICE:
  `MultiPlayerGameMode.tick()` calls `ensureHasSentCarriedItem()`, which compares against its own `carriedIndex`
  (never updated by the hand-made packet) and sends the same slot again (javap, 26.1.2 and 26.2). Swap through
  `MultiPlayerGameModeInvoker.killer560smod$invokeEnsureHasSentCarriedItem()` instead, as `RouteExecutor.select`
  does since 2026-10-04. AutoI4, Breaker Aura, ClearExecutor, AP3, Secret Triggerbot, MaskSwapper and Auto Debuff
  still use the hand-made form.
- Packet order inside one client tick (javap, 26.1.2): `START_CLIENT_TICK`, `gameMode.tick()`, `handleKeybinds`
  (vanilla clicks), the player tick (`ServerboundPlayerInputPacket` if the input changed, then `sendPosition`),
  `ServerboundClientTickEndPacket`, then `END_CLIENT_TICK`. The server applies the input packet's shift on arrival
  and `handleUseItem` snaps to the use packet's own yaw/pitch before using, so a sneak installed by the input mixin
  is in force for a use sent at the next START - no round trip to wait for.
- The mod's own widget labels are not drawn through `GuiGraphicsExtractor.text`; they go through
  `GuiGraphicsExtractor$RenderingTextCollector.accept`. A text hook that only targets `text` misses every
  button and label in the settings GUI (found when keeping Name Changer out of the mod's own menus, 2026-10-04).
- Chained Auto Routes etherwarps each logged "acted 1 tick(s) ... sneak went out in the firing tick's input packet"
  (his maplogger-latest2.log, 2026-10-04) because the executor kept sneak only for a next etherwarp in the SAME
  stack; the interact-delay settle ticks after a landing then sent shift up and the next warp re-sneaked. Held
  sneak is now decided after every node by `RouteExecutor.planSneak` from the node that actually fires next.
- A test script that pipes its runner into `grep` exits with grep's status, so it passes when the runner cannot even
  compile. `tools/bench/regress.sh` did exactly that after the 2026-10-04 path-to-blood merge (two javac errors,
  exit 0). Write the run to a file, check its exit status, and require a line only a finished run prints.
- A client `UseItemCallback` that returns SUCCESS skips vanilla's `ensureHasSentCarriedItem`: Fabric's hook sits on
  `MultiPlayerGameMode.useItem` before that call and cancels there (fabric-events-interaction 5.2.2, javap). A slot
  change made in the same tick then reaches the server AFTER the use packet, so the server uses the previous item.
  Send the slot first (`MultiPlayerGameModeInvoker.killer560smod$invokeEnsureHasSentCarriedItem`) before answering
  SUCCESS - `SimAbilities.sendHeldSlotFirst` does (2026-10-04).
- An entity of a mod SUBCLASS is saved under its vanilla type and comes back as the vanilla class when its chunk
  reloads, losing every override. The sim's blazes and silverfish override `checkDespawn` to survive PEACEFUL; reloaded,
  they are discarded on their first tick. Anything the sim spawns that matters must be put back when it goes missing
  from a section the server is showing (`isPositionEntityTicking`), and "missing" must never be read as "killed".
- Fabric's `MODIFY_GAME` chains: each listener gets the previous one's return, and `GAME` plus
  `Gui.setOverlayMessage` get the final result (javap, fabric-message-api-v1 7.0.5 and 7.0.8). Stat Bars returned
  `Component.empty()` for the HP/mana action bar, which blanked it for Ability Cooldown, the live map, Auto Routes,
  interop room secrets and the Custom Scoreboard's "x/y Secrets". Hide a line at `setOverlayMessage`
  (`CustomScoreboardGuiMixin` now cancels via `PlayerStatsFeature.shouldHideActionBar`), never by blanking it there.
- Hypixel SkyBlock has no command that summons a named pet. `/pets`, `/pet`, `/petmenu`, `/petsmenu`, `/viewpets`
  and `/viewpetsmenu` are all argument-less aliases that open the Pets menu (hypixelskyblock.minecraft.wiki
  Command page, checked 2026-10-04), and Autopet rules fire only on game events, never on demand. A pet summon
  must go through the menu, which is why Pet Wheel opens it (headless when Hide Pets Menu is on).
- **Anything drawn from inside `AbstractContainerScreen.extractContents` is already translated by leftPos/topPos**
  (labels, `extractSlotHighlightBack/Front`, `extractSlots`/`extractSlot`; javap, 26.1.2 and 26.2), so it draws at
  plain `slot.x, slot.y`. `extractBackground` and the `extractRenderState` TAIL / `ScreenEvents.afterExtract` are
  outside that pose and DO add leftPos. The Inventory Theme added it in both places and drew every slot square at
  twice the panel's offset - "an empty orange inventory grid at the bottom right"; the 2026-09-27 recipe
  book "fix" for that symptom was a different bug. A `SkinManager.createLookup` on a bare `new GameProfile(uuid,
  name)` is always the default skin: SkinManager only unpacks the profile's own textures property and never fetches
  one, so fetch the textured profile first (`ProfileViewerApi.fetchSkinProfile`) or use the tab list's `PlayerInfo`.
- HUD scale has one source: `HudElementRegistry.resolveScale` = the element's own `HudConfig` scale times the global
  HUD Scale (Home tab, `globalScale` in `killer560smod-hud.json`). The HUD editor scrolls and saves the OWN scale and
  draws at the product. Until 2026-10-04 three Gui mixins (Ability Timers, Dungeon Info, Etherwarp Waypoints) drew at
  `resolvePosition` with no scale at all, so resizing them in the editor never showed in game - a draw site that skips
  `resolveScale` silently opts out of both scales. Since 2026-10-05 the product also includes Auto Scale
  (`hud/AutoScale`, via `HudConfig.getEffectiveGlobalScale`), and SAVED positions are baseline units drawn at
  `saved * factor` - so a draw site that reads `HudConfig.getPosition` directly instead of `resolvePosition`, or saves a
  dragged position without `HudElementRegistry.toSaved`, lands in the wrong place on any monitor but 2560x1440 / GUI 3.
- The mod's own screens are laid out at `guiSize / factor` and drawn under a pose scale (`hud/mixin/AutoScaleScreenMixin`);
  every GUI mouse coordinate, drag deltas and the render mouseX/Y included, passes through the static
  `MouseHandler.getScaledXPos/YPos(Window, double)` (javap 26.1.2 and 26.2), which `AutoScaleMouseMixin` divides. So a mod
  screen must take its size from `this.width/height`, never from `getWindow().getGuiScaledWidth()`, and must read the mouse
  from its event arguments, never from `mouseHandler.xpos()`.
- A `FolderTab` section that is pinned (always open, no header) is never in `expanded`, so
  `findListeningKeyCaptureTab` did not ask it: Home's "Edit HUD Keybind" sat on "Press any key..." forever. Pinned
  sections are now checked first.
  (`CustomScoreboardGuiMixin` now strips the stat segments via `PlayerStatsFeature.actionBarReplacement` and re-sends the
  rest under a re-entry guard), never by blanking it there.
- A Minecraft colour code can be a DIGIT (`§3` is dark aqua), so `([\d,]+)` with optional codes BEFORE it reads
  Hypixel's overflow mana `§3200ʬ` as 3200. Start the match where a number cannot continue and let the pattern take
  the codes itself: `(?<![§\d,])(?:§.)*([\d,]+)` (`PlayerStatsFeature.NUMBER_START`, 2026-10-04, found by a scratch
  run against a sample line). The older health/mana/defence patterns only escape it because their codes are letters.
- `IslandDetector.graphIsland()` is null off any known island (sim, lobby, singleplayer), and `Set.of(...).contains(null)`
  throws. MiningProfitTracker did that every tick once trackers went on by default; null-check before any `Set.of` lookup.
- **Every GUI `fill` costs more than its pixels.** Each one becomes a render-state element with its own matrix copy and
  screen rectangle, and vanilla's `GuiRenderState.hasIntersection` scans the node's existing elements for each
  one added, so a HUD that draws a shape a pixel row at a time is quadratic in rows. The Custom Scoreboard border
  (two fills per pixel row) was 6% of the render thread and most of its allocation; merged into runs of equal rows
  it is a handful of fills with identical pixels (2026-10-05). Draw runs, not rows - and when merging, keep the
  rects non-overlapping or a translucent colour blends twice.
- Measure FPS work with the testkit's `95-fps-bench` (sim F7, ON/OFF alternated, frame and tick CPU time, JFR dumps;
  `tools/fps-jfr.py` attributes samples to mod code). Compare the ON-OFF DELTA within one run: absolute numbers
  drifted ~0.06 ms between identical runs, which is larger than most single fixes.
- **26.2 sorts QUADS only.** `StagedVertexBuffer.appendDraw` throws "Cannot sort draw with LINES" for any non-QUADS
  topology given a sorting (javap 26.2), so a `RenderType` built with `.sortOnUpload()` on `LINES_SNIPPET` crashes the
  first frame it draws. 26.1.2 accepted it. Solver ESP's through-walls lines did this; sort lines through
  `McRender.sortLinesOnUpload` (2026-10-05). `DEBUG_FILLED_SNIPPET` is QUADS on 26.2, so the filled types are fine.
- **On 26.2 a PEACEFUL level hides every hostile mob from the client.** `ClientPacketListener.handleAddEntity` goes
  through `EntityType.create` -> `canSpawn`, which refuses a type not `isAllowedInPeaceful` while the level reads
  PEACEFUL ("Skipping Entity with id entity.minecraft.silverfish"; javap 26.2). The server has the mob, the client never
  does. The sim world is EASY since 2026-10-05 for this; it keeps him fed itself (`SimSurvival`).
- `ClearExecutor.etherPath` / `AutoPuzzleUtil.pathIfMapOn` want the block to LAND ON (solid, two air above), the same
  position `AutoReposition.start` takes - not `.above()`. Given the air the planner logs "is not etherwarpable ...
  Nothing searched" and the map says "Failed after 0ms". Auto Blaze and Auto Ice Path both passed `.above()` until
  2026-10-05.
- A step that closes a menu and sends a command must time out if the screen it waits for never opens. Auto E-Table's
  Guardian swap closed the table, sent /pets, and waited forever when nothing opened; it now gives up after 10 s.
- Melody's Custom GUI picked the moving piece as the one pane COLOUR appearing exactly once; a board with a second
  lime pane (a finished row keeping its marker, which Odin handles with indexOfLast) has none, so after a row or an
  auto terminal's skip nothing was drawn as moving (killer560, 2026-10-05). Find a terminal's piece by position from
  the current slots (`findMelodyMovingSlot`), never by colour counts; testkit 218 fails 5/16 on the old rule.
- Terminal and device SIZES are read off the board/device, never constants (SkyBlock 0.27.2: Melody 3 rows, Click in
  order 10, Simon Says 4 rounds; `terminals/TerminalLayouts`). The old Melody code was row-generic only by accident
  (`MELODY_CLAY_SLOTS[r-1] == r*9+7` for rows 1-4), so a 3-row board still auto-clicked; what broke was indexing past
  the last row - Melody Keys' key 4 pressed slot 43, the bottom marker row (testkit 290 fails on main 5813cb91).
- On 26.2 `McRender.inCameraSpace` runs its callback LATER in the frame (`submitCustomGeometry`, built in
  `CustomFeatureRenderer.buildGroup`), after the tick may have rebuilt whatever list the callback reads. Secret
  Waypoints indexed its live list there and crashed the client ("Index 6 out of bounds for length 6", Render Frame)
  when the list shrank - testkit 98 on 26.2, 2026-10-05. Copy what the callback draws into a local array before
  calling `inCameraSpace`. Audited 2026-10-05: also fixed `WitherDoorsRenderer` (live `CACHED` list),
  `StorageSearchEsp` (live `TARGETS`, mutated by clicks) and both `renderLineStrip`s (`WorldRenderUtils`,
  `SolverEspRender`: callers pass live path lists). Safe because the lambda captures only an immutable `AABB`/`Vec3`
  and a fresh local `float[]`: DoorKeys, MageBeam, EtherwarpWaypoints (fresh per-frame entry list), mobesp
  `EspRenderer`, `P3NavRenderer`, Teammates/Thorn ESP, the box methods of `SolverEspRender`/`WorldRenderUtils`.
- Crypts and princes have no positions anywhere (room database and every installed mod's rooms.json: a count), and
  their undead do not exist until the tomb is blown, so `secretwaypoints/CryptScanner` finds them from blocks. The
  rule was fitted against the captures vs the database count (112/134 rooms exact); the census scripts and misses
  are in killer560s-mod-logs/crypt-waypoints.md. Re-run that census before changing the rule.
- **Two mod-menu rows on one rectangle look like the top one and act as the bottom one.** ModScreen's content pane draws
  children in order (last on top) but gives a press to the FIRST under the cursor. Breaker Aura's `y += 20 ... y -= 20`
  side-step left Cooldown on Side Reach's rect and Auto Swap on Multi Break's, so dragging "Cooldown" moved Side Reach
  (2026-10-05). Testkit `386-ui-sliders` checks every tab, toggles flipped, for overlapping rows.
- **Cancelling a block break makes `AttackBlockCallback` fire every TICK, not once per click.**
  `MultiPlayerGameMode.continueDestroyBlock` only continues an existing break when `isDestroying` is set, and
  that field is set inside `startDestroyBlock` - which is where the callback lives and which a cancel returns
  from first. So holding the button re-enters the callback twenty times a second (verified by `javap -c`:
  `continueDestroyBlock` calls `startDestroyBlock` on its fallback path). The sim's Dungeon Breaker spent its
  whole twenty-charge bar in one second this way. Anything that consumes that callback needs its own
  edge-detection - track the block and clear it when `keyAttack` comes up.
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default
  could never be restored once the setter ran. Fixed 2026-09-27; the SLIDER driving it still mapped onto
  1-20 and was fixed 2026-09-30. A clamp has two halves - the setter and whatever widget feeds it - and a
  slider whose start position computes negative (`(0 - 1) / 19`) is the tell. Every other numeric setter in
  the repo was swept on 2026-09-30 and has its field default inside its clamp.
- `MazeWalk` plans on ONE feet level, so it cannot take a staircase; Auto Boulder used to drop through a hole in the
  roof instead ("struggles going down the stairs", 2026-10-06). `autopuzzles/BoulderPath` plans across heights (stairs
  climbed from their low side, ledges cost extra). And a walk planned on the tick of a Boulder button press plans round
  the box's OLD position (the move reaches the client ticks later: "no walk", 223 nodes) - wait ~400 ms after a press.
- An etherwarp aim verified at its exact float yaw is not verified (2026-10-06, 95-sim-map-warp): the use packet carries
  the RUNNING yaw (player yaw + wrapped turn, ~900 degrees here), which keeps fewer fraction bits, so the server read
  173.65979 where 173.65981 was planned and the ray caught a block corner the plan cleared by millionths of a block.
  Rays at lattice slopes (yaw 18.43495 = atan(1/3)) pass exactly through block corners, so this is common, and a replan
  from the same spot picks the same cached edge. `WarpGraph.plan` now runs every hop through `EtherSearch.holds`
  (lands for yaw/pitch +-0.01 degrees), re-aims a fragile one with `aimFirm`, else drops that edge and plans again.
  The diagnosis that found it: log the server's refusal, then cast the server's exact ray through the client planner's
  grid - BLOCKED on both sides meant the worlds agreed and the aim was the fault.

- To replace a vanilla HUD piece, wrap its Fabric layer instead of mixing into `Gui`: `HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> ...)` (identical in Fabric API 0.155.2+26.1.2 and 0.160.0+26.2, javap) calls the
  vanilla layer only when you do, so there is no 26.2 descriptor to break. The Custom Crosshair does this (2026-10-06); F1 is
  `McCompat.hudHidden`, because `options.hideGui` is gone on 26.2. To draw in SCREEN pixels from any pose, `pose().identity()`
  then `scale(1f / guiScale)` - an int `fill` is then one framebuffer pixel.

- A standalone sim puzzle builds IN PLACE of the room's floor (its floor sits at the player's feet minus one), so its
  reset must put back what was there, not air. The Teleport Maze reset aired its 52 floor blocks and the player fell
  into the void (testkit 78: "[Sim] back to the middle of the room", then "icepath: built nothing"; 26.1.2 and 26.2
  alike, only when he stood on the arena). It now records the prior states on the server and restores them
  (2026-10-06). Tic Tac Toe's reset still leaves 1-3 blocks fewer than before (78's "arena after reset"; not traced).

- `AutoRoutesFeature.warpToStartNode` called on its own (not through a map press) leaves the last route's
  `justFinished` latch set, so the next start node lands "latched" and never arms - Auto Secret did this for every room
  after the first (102-sim-autosecret, 2026-10-06). A map press clears it in `InteractiveMapFeature.queue` via
  `cancelForInteractiveMap`; anything that drives the start-node warp must call that first, as Auto Secret now does.

- **Dungeon Autopilot sim finds (2026-10-06).** `canPath` refuses Maze/Boulder by NAME and nothing walks: autopilot2 lifts it
  once the room is done (map/tab, or `permitLeave` after our step); a TRAP room always refuses and `ClearExecutor` fires no hop
  inside one (no ability works there - killer560). A doorway's connector cell reads as no room (`currentRoom` -1): decide
  from the last room. A dropped key leaves the CLIENT at ~100 blocks - gone is not picked up. `WitherDoorOpener` clicked a door
  with the AOTV in hand and the sim fired Instant Transmission; it now uses the key item or an empty slot.

- Secret Waypoints' lever waypoints never hid on a click (2026-10-06): the click listener marked the lever in `COLLECTED`,
  but `scanLevers` builds its waypoints outside `addGroup` and never asked `COLLECTED`, so the next rebuild (<= 1 s) put it
  back. Any waypoint group added outside `addGroup` must apply the same collected filter.
- To swallow his left click before vanilla acts on it, consume it at START_CLIENT_TICK: `while (keyAttack.consumeClick())`
  then `keyAttack.setDown(false)`. `handleKeybinds` runs later in the same tick and only calls `startAttack` per queued
  click and `continueAttack(true)` while the key reads down (javap 26.1.2 and 26.2), so nothing is swung or dug
  (`RouteExecutor.takeSkipClick`, 96-ar-awaitskip / 62-argrim-awaitskip).
- The Interactive Map's floor graph (2x2 buckets) misses ledge landings: asked for a block beside Higher Blaze's chest it
  landed up to five blocks off ("near"), or called him "already there", and from that ledge it "proved" no way back down
  the room the room-by-room planner had just walked him up (2026-10-06). When the block itself matters, use
  `ClearExecutor.etherPathExact` (room by room before a near landing or a graph "no way"), and filter goals with
  `EtherwarpPathfinder.isEtherwarpable`: a carpet-topped spot he stood on is not one, and the planner searches nothing.

## Compiling (moved from CLAUDE.md 2026-10-05 to keep it under 300 lines)

- **Never write a Minecraft API call from memory - grep for a call site in this repo first.** A cloud session
  cannot compile (the network policy blocks `maven.fabricmc.net` and Mojang's hosts), so a wrong method name is
  not caught until killer560 runs the build, and it costs him a whole round trip. Three in one batch on
  2026-10-01: `Entity.moveTo` is `snapTo` in 26.1.2, `EntityType.BAT` belongs behind `McEntities.BAT` because it
  is one of the names that moved in 26.2, and `BlockState.isCollisionShapeFullBlock` was a guess at a predicate
  that could have been several things. Every one of them had a working equivalent already in the tree -
  `SimMiniboss.snapTo`, `SimMobs`' bat spawn, `TeleportUtils`' `getCollisionShape(...).max(...)`. The rule is
  mechanical: before using a vanilla method or constant that does not already appear in `src/`, either find it
  there or pick something that does. METHOD names are what move between versions - and so do some block
  constants: a coloured block (`Blocks.RED_WOOL`) does not exist in 26.2 and must be `McBlocks.RED_WOOL`, which
  broke only the 26.2 build of `a4e563a`. The same commit also broke 26.1.2 with
  `SoundEvents.ELDER_GUARDIAN_HURT.value()`: only some `SoundEvents` are holders (`NOTE_BLOCK_PLING`,
  `GENERIC_EXPLODE`); mob sounds like `BLAZE_HURT` are plain `SoundEvent`s. Copy the shape of an existing use.
- **A cloud session CAN check far more than it parses.** `javac -XDshould-stop.ifNoError=PARSE` only checks
  syntax, which is why `List<Integer> pool = live;` shipped into a method whose own parameter was already called
  `pool` and broke the build. Run the FULL compile on each changed file and filter the noise instead - without
  the Minecraft jar every type is unresolved, but everything structural is still reported:
  ```
  javac -proc:none -nowarn -Xmaxerrs 2000 -d /tmp/out F.java 2>&1 | grep "error:" \
    | grep -vE "cannot find symbol|package .* does not exist|cannot access|incompatible types|method does not override|no suitable method|cannot be applied|is not abstract|bad operand|cannot be dereferenced|array required|unexpected type|not a statement|cannot infer type"
  ```
  What survives that filter is real: "already defined", "missing return statement", "unreachable statement",
  "cannot assign a value to final variable", "might not have been initialized", duplicate methods. Verified by
  reintroducing the `pool` collision into a scratch copy and watching the filter print it. This does NOT replace
  the rule below about API names - an unresolved method is indistinguishable from a misspelt one here.

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
