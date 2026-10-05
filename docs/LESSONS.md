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
  XP reward tiles are recognised by NAME (`... Enchanting Exp`): the item varies, and cocoa beans were missed.
  Clicking a tile that is already uncovered does not use a click (killer560, 2026-10-01). A pair's first click
  can fail to land (tile still covered, others still read "Click any button!"); clicking the partner anyway lost
  Experiment the Fish, so the first tile is now re-clicked before its partner. **Superpairs is played in turns of
  two clicks**: a pair started while a tile is turned over matches against that tile and both pairs are lost, and
  a chance match that is not recognised as claimed gets re-queued and clicked as no-ops forever. The solver reads
  both off the board each decision (two face-up tiles of a kind = claimed, one = the turn's open tile; 2026-10-05,
  `ExperimentSolver.readBoard`). An offline port of the testkit's Hx table driving the solver class found both: in it,
  main's solver never finished 207 of 300 random boards (how often real Hypixel boards hit it is unmeasured).
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
- A hotbar swap sent by hand (`ServerboundSetCarriedItemPacket` after `setSelectedSlot`) leaves the game mode's
  `carriedIndex` stale, and its `tick()` then sends the same slot again: GrimAC BadPacketsA. Swap through
  `MultiPlayerGameModeInvoker.invokeEnsureHasSentCarriedItem` (RouteExecutor.select, ClearExecutor.swapById since 09e2c304).
- An await met by the chest CLICK (2026-10-05 rules) lets the route warp before the chest's window arrives, which is
  right - Hypixel credits the chest on the click - but the late window then stopped the route ("a screen opened"). A
  container screen within 2 s of our own chest click is now waited under (98b457be; 96-ar-play emulates the late window).
