# Feature-specific lessons

Moved out of CLAUDE.md to keep it under its size limit. Same rules: problem, then fix; verified only.

- The gametest client runs as **java.exe**, not javaw.exe. `run-scenario.ps1` filtered on javaw only, so every
  safeguard in it was inert — the freeze watcher never saw an unresponsive client and the deadline cleanup
  killed nothing, while the script reported success. That is why "it still doesn't close out on freeze"
  survived two rounds of fixes to the watching logic.
- Instant Transmission is 8 blocks on Aspect of the End, Aspect of the Void AND the Etherwarp Conduit alike.
  What changes the range is the item's own `tuned_transmission` tag - a Transmission Tuner adds a block, four
  maximum - so a fully tuned one of any of them goes 12, and killer560 plays fully tuned ("nearly no one plays
  with less"). AOTV is NOT 12 by nature; assuming that got AOTE and AOTV wrongly split in the route matcher
  once already. `EtherwarpHopper` reads the same tag for the 57-block etherwarp.
- `ItemIdentity.of()` is shared by Auto Sell, the Inventory Sorter, Armour Dye and the mining profit tracker (shelved until after 2.0).
  Widening it to make two items equal makes "sell my Hyperion" sell an Astraea. Loose matching belongs in
  `matches()`, which only a route's USE_ITEM node reaches.
- **The live map's world scan must stay inside the floor's room grid.** Floor 1's boss arena stands in slots
  (4..5, 5) of the 6x6 footprint; scanning all 121 cells read its roof as two ROOM tiles, which widened the map's
  fit to 6x6 (his "doesn't rescale for other floors") and kept the boss latch off until he was deep in the arena.
  `scan()` and `insideGridFootprint` now use `MapPainter.currentFloorRooms()` (2026-10-07).
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
- The SkyBlockPV backend's `/authenticate` hands ANY caller a guest token (checked 2026-10-07: a made-up username and
  an unjoined server id got a JWT with `sub` all zeros), and its source (meowdding/skyblock-pv-backend) shows that is
  the "authentication disabled" branch. So `ProfileViewerApi.backendToken` no longer gives up when Mojang's
  `joinServer` fails; the backend decides. Hypixel's `fastest_time_s`/`_s_plus` are MILLISECONDS (263003 = 4:23).
- The SkyBlockPV backend rate-limits `/profiles`: 3-4 requests pass, then 429 "Retry-After: 10" (measured 2026-10-07 on 25
  real players: 4 answered, 19 x 429 inside two seconds). Party Finder stats cached each 429 as a 3-minute failure, so a
  full menu showed about four players. A 429 is now `ProfileViewerApi.RateLimitedException`; the PF worker waits out
  `backendRetryAtMs()` and requeues the name. Also: Hypixel records no S/S+ on Entrance (every real player's Entrance
  time is `fastest_time` only), so S/S+-only PBs never show there. The PF parser now also accepts "Floor: Entrance"
  (floor 0); that lore wording is assumed, not yet seen in a real menu.
- A Minecraft colour code can be a DIGIT (`§3` is dark aqua), so `([\d,]+)` with optional codes BEFORE it reads
  Hypixel's overflow mana `§3200ʬ` as 3200. Start the match where a number cannot continue and let the pattern take
  the codes itself: `(?<![§\d,])(?:§.)*([\d,]+)` (`PlayerStatsFeature.NUMBER_START`, 2026-10-04, found by a scratch
  run against a sample line). The health/mana/defence patterns did NOT escape it: with absorption Hypixel colours health
  GOLD, `§6`, and "§612,000/10,464" read as 612,000 - a nearly all-absorption bar (his "absorption breaks the health one",
  2026-10-07). Every stat pattern now starts with `NUMBER_START`; testkit 442 sends the gold line through the real path.
- `IslandDetector.graphIsland()` is null off any known island (sim, lobby, singleplayer), and `Set.of(...).contains(null)`
  throws. MiningProfitTracker (shelved since 2026-10-07) did that every tick once trackers went on by default; null-check before any `Set.of` lookup.
- Measure FPS work with the testkit's `95-fps-bench` (sim F7, ON/OFF alternated, frame and tick CPU time, JFR dumps;
  `tools/fps-jfr.py` attributes samples to mod code). Compare the ON-OFF DELTA within one run: absolute numbers
  drifted ~0.06 ms between identical runs, which is larger than most single fixes. 95 is a dungeon; a hub (80-entry
  tab list, ~180 entities, chat, an open chest, an Ender Chest page) is `403-perf-hub` (testkit docs/fps-bench.md).
- **Server-sent text can be memoised by object identity.** A tab entry's display name, a team's prefix/suffix and an
  entry's display are REPLACED with a new `Component` when the server updates them, never edited, so "same object (and
  same `Language`), same string" is exact. `util/TabText` and `DungeonState.readSidebarText` key on that (2026-10-07):
  a dozen tab readers had each re-flattened and regex-stripped all ~80 entries, and the sidebar was rebuilt every tick
  in every world. In a weak-keyed cache never store the key itself as its value (Name Changer stores `NO_MATCH`): a
  strong value that names its key keeps the entry alive forever.
- A cache that is never clean costs the whole save every time: `PlayerNameCache.put` treated a newer timestamp as a
  change, so the 500 ms tab-list scan made it dirty on every pass and the whole name file was rewritten every two
  seconds in any lobby (found 2026-10-07; a sighting now refreshes an unchanged name at most once per ten minutes).
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
  The graph's "proof of no way" is only about its own landings and aims: from Atlas's ledge it proved no way to a block
  three warps off, because the hop down past the ledge's lip clears it only between the 18 face aim points (testkit
  404-sim-planner-gaps, 2026-10-07). On any graph "no way" `EtherwarpPathfinder.finePlan` now searches every landing of
  his and the goal's rooms with the off-lattice points too (~30 ms, at most 200). Judge a "no way" against real casts
  (404's ground truth) before calling it a planner bug: 131's two "no way by a wall" spots were a one-block floor in the
  open seam between two rooms' walls, sealed except to the roof - correct answers (404-sim-planner-cracks).
- `ClearExecutor.isActive()` goes false two ticks after the last hop is SENT (`compDelay`), before its landing comes back,
  so anything asking "is the Interactive Map moving him?" at the landing must use `isBusy()` (true through the arrival
  sync), and read it when the position packet arrives. The insta-clear recorder used `isActive` on the next tick and
  logged every map path's final landing as "teleport by manual" (Mage, Hall, his live F7 runs 2026-10-06).
- An insta-clear observation must know whether he STOPPED in the room. A map path lands in rooms on the way for a tick
  each; the v1 recorder judged them like stops, so they closed NO_CLEAR (one failure blocks an entry for good), or NO_STARS
  when the room flipped ~200 ms after landing, before its mobs had loaded (Duncan, a real insta). Now: left within 3 s and
  no flip by 1.5 s after leaving is PASS_THROUGH; a flip with no starred mob in sight waits (up to 60 s) to see the mobs,
  which Hypixel leaves standing in an insta-cleared room. 98-sim-insta-clear-live replays both on a sim floor.
- A per-position "done" set must reset on a new RUN, not only a new world. Secret Aura, the Secret Triggerbot and Secret
  Waypoints' collected set cleared on world change / leaving the dungeon; a sim rebuild of the same room is neither and puts
  every secret back on the same blocks, so after run 1 the aura clicked nothing (his Museum, Map Logger, 2026-10-06). All
  three now also reset when `LiveMapFeature.resetGeneration()` changes (world change, dungeon entered, sim floor published).
  99-sim-aura-rebuild rebuilds Museum three times in one world and fails on 5008e4d6.

- Container automation must not treat a cursor stack mid-click as the player's. On 26.2 Auto Anvil's first build stopped with
  "something is on your cursor" in the first pair of testkit 292 and 293 (2026-10-07, not on 26.1.2, not on a rerun); the
  item was not captured. It now waits a cursor stack out mid-pair and gates each step on the menu's state id changing
  (`AbstractContainerMenu.getStateId`, which only the server's slot/content packets move), so a client prediction is never
  read as the server's answer. 292-297 then passed twice on 26.2.
- Blood Camp's prediction is NoammAddons' (killer560 asked for it, 2026-10-07), including a Kotlin quirk: Noamm sums
  `packet.xa / 4096` with a Short and an Int, which is INTEGER division, so a blood mob's sub-block step adds nothing and the
  trip starts at the skull's spot in the wall. Ours divided by 4096.0, started one packet out of the wall, and landed the
  predicted spot one step (0.215 blocks in test 392) too far along. Keep the integer division; it is the model, not a typo.
- A chat line dropped through Fabric's `ALLOW_GAME`/`ALLOW_CHAT` never reaches `ChatObserver` either: a cancel there skips both
  the GAME event and `ChatComponent.addMessage`, its two sources. To hide a line only from the WINDOW, cancel inside `addMessage`
  after the clicktranslate HEAD hook has dispatched it - Chat Tidy injects at that method's `Predicate.test` call (2026-10-07;
  testkit 87 checks every hidden line still reached `ChatObserver`). Chat Hider's other six hides (Object Hider's old
  Chat Replacements) still use `ALLOW_GAME`.

- **Moving the camera does not move the crosshair.** `GameRenderer.pick` -> `LocalPlayer.raycastHitResult` casts from
  `Entity.getEyePosition`, never from `Camera.position()` (javap 26.1.2 and 26.2), so a `Camera.setPosition` at the TAIL of
  `Camera.alignWithEntity` changes only where the frame is drawn from (chunks, culling, every renderer reading the camera).
  Smooth Teleport (`smoothtp/`, 2026-10-07) relies on it; testkit 396-sim-smooth-tp hits a wall 2.5 blocks past the landing on
  the first frame while the camera is still 14.5 blocks back.
- Vitality is NOT a Rift stat: it is a combat resource (healing abilities, Wither Shield, Creeper Veil - "Not enough vitality!
  Creeper Veil De-activated!" is in his own logs) shown on the action bar as `current/max` + U+E028 (hypixelskyblock wiki),
  which the generic Other readout caught until 2026-10-07. `PlayerStatsFeature.VITALITY_REGEX` reads it and Other skips U+E028.
- A migration that reads an old file must decide from the OLD file's keys, never from the new config's fields after the
  carry-over: those already hold the new file's own values wherever an old key is missing. Score Calculator's legacy
  alert migration tested its own `mimicAlertEnabled`/... and so switched `enabled` on when nothing legacy was on
  (testkit 310 caught it once another case had left Score Calculator on, 2026-10-07).
GUI, HUD and rendering lessons are in [LESSONS-GUI.md](LESSONS-GUI.md).
Compiling lessons (API names across versions, the cloud-session javac filter) are in [COMPILING.md](COMPILING.md).

- `api.docilelm.top` (Devonian's Party Finder stats) answers HTTP 200 `{"result":{}}` to every User-Agent except Devonian's own
  (`Mozilla/5.0 (Devonian)`, checked 2026-10-07), so an empty result is an access refusal, not "player not found". Do not
  impersonate Devonian; Party Finder uses SkyBlockPV (or his own Hypixel key) instead.

- javac folds `if (SomeClass.CONSTANT)` away but still writes a `CONSTANT_Class` entry naming `SomeClass` into the using
  class's constant pool (seen with javap -v, 2026-10-07). So gating normal code on a flag that lives in a package left out of
  a jar (`com.killer560.hub.testing`) still puts that package's name in every gated class; the testing build gates on
  `BuildVariant.TESTING` instead, and a byte grep of the normal jars for `hub/testing` comes back empty.

- **Hypixel's SkyBlock resource pack is required**: the 0.26 announcement (hypixel.net thread 6117801) says it is
  force-enabled and SkyBlock cannot be joined without loading it, and players who declined report being kicked with
  "failed to load resource pack". Noamm's PackDisabler gets round that by answering ACCEPTED + SUCCESSFULLY_LOADED and
  cancelling the push, i.e. reporting a pack it never applied. Our Pack Disabler leaves the push to vanilla (the pack
  really loads) and only swaps the model each SkyBlock item is drawn with, at `ItemModelResolver.appendItemLayers`
  (2026-10-07). Hypixel's pack overrides no vanilla item textures, so a vanilla look still comes from his own packs.
- NotEnoughUpdates moved items onto Hypixel's pack models in SEVERAL commits on 2026-07-09 (Auction House, Other,
  Bazaar Items, Recipe, More), so the parent of the Bazaar one (26169fe) already had 299 items on pack models. The
  pre-pack look comes from the newest snapshot where an item was not yet a pack model: 26169fe, then 0046933 (the
  parent of the first conversion), then 60e030e (April). With those, 95 of the 1,366 pack-model items have no old
  look anywhere (`tools/items/gen_item_looks.py`); they are the ones with our own textures.

- A parent `Style`'s `ClickEvent` is only inherited where the child sets none, so a whole-line click action wrapped round a
  Hypixel line (Copy Chat's old whole-message copy, via Click Translate's wrap) is dead on every name, link or invite in it,
  and past the end of the text vanilla finds no style at all. Find the clicked chat row from the layout instead
  (`ChatComponent.captureClickableText` with a recording collector) and take `GuiMessage.Line.parent()` (2026-10-08, testkit 561-563).
- A fixed RMS threshold is not a voice detector: room noise on an open mic sat above Voice To Text's 500, so no pause was
  ever seen and Open Mic never sent anything. `voicetotext/OpenMicSegmenter` compares against the quietest chunk of the last
  3 s; testkit 564 feeds noise at RMS 700 (2026-10-08).
