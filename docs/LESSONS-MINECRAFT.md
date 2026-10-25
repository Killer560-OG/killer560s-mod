# Minecraft, Fabric and Java lessons

How vanilla, Fabric and the JVM actually behave where the mod got it wrong, each checked with javap or a test. Part of the
feature lessons: [LESSONS.md](LESSONS.md). Rendering and screen behavior is in [LESSONS-GUI.md](LESSONS-GUI.md);
API names that differ between 26.1.2 and 26.2 are in [COMPILING.md](COMPILING.md).

## Packets, ticks and input

- Packet order inside one client tick (javap, 26.1.2): `START_CLIENT_TICK`, `gameMode.tick()`, `handleKeybinds`
  (vanilla clicks), the player tick (`ServerboundPlayerInputPacket` if the input changed, then `sendPosition`),
  `ServerboundClientTickEndPacket`, then `END_CLIENT_TICK`. The server applies the input packet's shift on arrival
  and `handleUseItem` snaps to the use packet's own yaw/pitch before using, so a sneak installed by the input mixin
  is in force for a use sent at the next START - no round trip to wait for.
- A hotbar swap sent as `setSelectedSlot` + a hand-made `ServerboundSetCarriedItemPacket` goes out TWICE:
  `MultiPlayerGameMode.tick()` calls `ensureHasSentCarriedItem()`, which compares against its own `carriedIndex`
  (never updated by the hand-made packet) and sends the same slot again (javap, 26.1.2 and 26.2). Swap through
  `MultiPlayerGameModeInvoker.killer560smod$invokeEnsureHasSentCarriedItem()` instead, as `RouteExecutor.select`
  does since 2026-10-04. AutoI4, Breaker Aura, ClearExecutor, AP3, Secret Triggerbot, MaskSwapper and Auto Debuff
  still use the hand-made form.
- A client `UseItemCallback` that returns SUCCESS skips vanilla's `ensureHasSentCarriedItem`: Fabric's hook sits on
  `MultiPlayerGameMode.useItem` before that call and cancels there (fabric-events-interaction 5.2.2, javap). A slot
  change made in the same tick then reaches the server AFTER the use packet, so the server uses the previous item.
  Send the slot first (the same invoker) before answering SUCCESS - `SimAbilities.sendHeldSlotFirst` does.
- **Canceling a block break makes `AttackBlockCallback` fire every TICK, not once per click.**
  `MultiPlayerGameMode.continueDestroyBlock` only continues an existing break when `isDestroying` is set, and
  that field is set inside `startDestroyBlock` - which is where the callback lives and which a cancel returns
  from first. So holding the button re-enters the callback twenty times a second (`javap -c`: `continueDestroyBlock`
  calls `startDestroyBlock` on its fallback path). The sim's Dungeon Breaker spent its whole twenty-charge bar in one
  second this way. Anything that consumes that callback needs its own edge-detection - track the block and clear it
  when `keyAttack` comes up.
- To swallow his left click before vanilla acts on it, consume it at START_CLIENT_TICK: `while (keyAttack.consumeClick())`
  then `keyAttack.setDown(false)`. `handleKeybinds` runs later in the same tick and only calls `startAttack` per queued
  click and `continueAttack(true)` while the key reads down (javap 26.1.2 and 26.2), so nothing is swung or dug
  (`RouteExecutor.takeSkipClick`, 96-ar-awaitskip / 62-argrim-awaitskip).
- `getUseItemRemainingTicks()` is 0 when NOTHING is being used, so a draw read as `72000 - remaining` calls an idle bow fully
  drawn: Trajectories drew a full-power arc for any bow merely held (testkit 1082 on main: 885 arc px). Gate on
  `isUsingItem()` in the main hand first. Shortbows (lore "Shortbow: Instantly shoots!") are `BowItem`s too; test them first.
- A `@Inject` at HEAD of a `ClientPacketListener.handleX` runs TWICE per packet: first on the Netty thread, where
  `PacketUtils.ensureRunningOnSameThread` re-queues the packet and throws, then on the render thread (javap, 26.1.2 and 26.2).
  The Egglocator trail recorded every particle twice, half off-thread (2026-10-19). Inject `AFTER` that invoke, as SkyHanni does.
- **A client sees an entity's server position only in bursts, and `InterpolationHandler.position()` is not it between them.** It
  returns the DRAWN position once the lerp steps run out (javap 26.1.2), and a vanilla bat flaps on the client in between, so
  BatLog's first sim run summed those steps and read 0.53-0.84 b/t for a bat flying 1.02, and "curved" for straight shots
  (2026-10-20). Keep the last target yourself, sample only new positions, and fit speed over them (`autoclear/BatLog`).
- **Moving the camera does not move the crosshair.** `GameRenderer.pick` -> `LocalPlayer.raycastHitResult` casts from
  `Entity.getEyePosition`, never from `Camera.position()` (javap 26.1.2 and 26.2), so a `Camera.setPosition` at the TAIL of
  `Camera.alignWithEntity` changes only where the frame is drawn from (chunks, culling, every renderer reading the camera).
  Smooth Teleport (`smoothtp/`) relies on it; testkit 396-sim-smooth-tp hits a wall 2.5 blocks past the landing on the
  first frame while the camera is still 14.5 blocks back.

## World and entities

- **`ClientLevel.hasChunk(x, z)` is hard-wired to `true`** (javap 26.1.2 and 26.2: `iconst_1; ireturn`), and so is
  `LevelReader.hasChunkAt` on it; a chunk the client does not have reads as an empty chunk, all air. (`Level.isLoaded(BlockPos)`
  is fine: it asks the chunk source, `level.getChunkSource().hasChunk(x, z)`, vanilla's answer even with the Chunk Cache on,
  `ChunkCacheChunkSourceMixin`.) Ask `util/ClientChunks.isLoaded` (a block read here returns real blocks: live, or held
  by the Chunk Cache) or `isLive` (vanilla's answer, never a cached snapshot - `/mage`), once per chunk, and make a missing
  chunk mean UNKNOWN, not absent. `/mage`'s first version asked the level and captured a forgotten chunk as a hole, which
  its own tile check then called "matches no room" (testkit 1490); chunk-loaded-1015 found it reading a shut wither door as
  opened, a crypt as opened (then done for the run), half a lid as a crypt, a box with no chest, and a wall the etherwarp
  ray flew through (testkit 1491). As of 2026-10-15 `DoorScanner`, `SecretWaypointsFeature`, `CryptScanner` and
  `AutoPuzzleUtil` still asked `level.hasChunk` on the client.
- An entity of a mod SUBCLASS is saved under its vanilla type and comes back as the vanilla class when its chunk
  reloads, losing every override. The sim's blazes and silverfish override `checkDespawn` to survive PEACEFUL; reloaded,
  they are discarded on their first tick. Anything the sim spawns that matters must be put back when it goes missing
  from a section the server is showing (`isPositionEntityTicking`), and "missing" must never be read as "killed".
- **On 26.2 a PEACEFUL level hides every hostile mob from the client.** `ClientPacketListener.handleAddEntity` goes
  through `EntityType.create` -> `canSpawn`, which refuses a type not `isAllowedInPeaceful` while the level reads
  PEACEFUL ("Skipping Entity with id entity.minecraft.silverfish"; javap 26.2). The server has the mob, the client never
  does. The sim world is EASY since 2026-10-05 for this; it keeps him fed itself (`SimSurvival`).
- `ClientPacketListener.getListedOnlinePlayers()` is a `ReferenceOpenHashSet` (javap, 26.1.2 and 26.2): its order is
  identity-hash order, not tab order. `PartyTracker` built the party from it, so the Dungeon Map paired far teammates'
  map markers with names in a random order (testkit 1015-noamm-map: party read `[Bravo, Charlie, Delta, Alpha]`, 0 of 4
  on their own marker). Anything that relies on the tab's order reads `util/TabOrder.listed`, the overlay's comparator.
- **The instance `SoundManager.play` is handed is not resolved yet, so its `getPitch()` and `getVolume()` throw** (null
  `Sound`, javap 26.1.2). Sound Effects read `at.getPitch()` there, the catch handed back the game's sound, and no effect ever
  played (2026-10-20). Call `resolve(soundManager)` on it first, or read neither. Proof that a swap reached the engine: a
  `SoundEventListener` (testkit 99-sim-sound-effects) is told before the muted client's zero-volume skip.

## Chat and commands

- Fabric's `MODIFY_GAME` chains: each listener gets the previous one's return, and `GAME` plus
  `Gui.setOverlayMessage` get the final result (javap, fabric-message-api-v1 7.0.5 and 7.0.8). Stat Bars returned
  `Component.empty()` for the HP/mana action bar, which blanked it for Ability Cooldown, the live map, Auto Routes,
  interop room secrets and the Custom Scoreboard's "x/y Secrets". Hide a line at `setOverlayMessage`
  (`CustomScoreboardGuiMixin` now cancels via `PlayerStatsFeature.shouldHideActionBar`), never by blanking it there.
- A chat line dropped through Fabric's `ALLOW_GAME`/`ALLOW_CHAT` never reaches `ChatObserver` either: a cancel there skips both
  the GAME event and `ChatComponent.addMessage`, its two sources. To hide a line only from the WINDOW, cancel inside `addMessage`
  after the clicktranslate HEAD hook has dispatched it - Chat Tidy injects at that method's `Predicate.test` call (testkit 87
  checks every hidden line still reached `ChatObserver`). Chat Hider's other six hides (Object Hider's old Chat Replacements)
  still use `ALLOW_GAME`.
- A parent `Style`'s `ClickEvent` is only inherited where the child sets none, so a whole-line click action wrapped round a
  Hypixel line (Copy Chat's old whole-message copy, via Click Translate's wrap) is dead on every name, link or invite in it,
  and past the end of the text vanilla finds no style at all. Find the clicked chat row from the layout instead
  (`ChatComponent.captureClickableText` with a recording collector) and take `GuiMessage.Line.parent()` (testkit 561-563).
- A Minecraft color code can be a DIGIT (`§3` is dark aqua), so `([\d,]+)` with optional codes BEFORE it reads
  Hypixel's overflow mana `§3200ʬ` as 3200. Start the match where a number cannot continue and let the pattern take
  the codes itself: `(?<![§\d,])(?:§.)*([\d,]+)` (`PlayerStatsFeature.NUMBER_START`). With absorption Hypixel colors
  health GOLD, `§6`, and "§612,000/10,464" read as 612,000 - a nearly all-absorption bar (his "absorption breaks the
  health one", 2026-10-07). Every stat pattern now starts with `NUMBER_START`; testkit 442 sends the gold line through
  the real path.
- Brigadier can add a root to a live dispatcher but cannot remove one, and Fabric fires `ClientCommandRegistrationCallback`
  only on join, into a fresh dispatcher (`ClientPacketListenerMixin.onGameJoin`, fabric-command-api-v2 3.0.5 javap). A
  node whose `requires()` fails does not parse, which Fabric reads as "unknown command" and hands the line to the server
  (`ClientCommandInternals.isIgnoredException`). So live-editable client commands (custom Command Shortcuts) are added to
  `getActiveDispatcher()` behind `requires(isLive(name))`: removing or renaming one is instant, adding one registers it a
  moment after typing stops, and only its tab completion lingers until the next join.

## Java, javac and Windows

- javac folds `if (SomeClass.CONSTANT)` away but still writes a `CONSTANT_Class` entry naming `SomeClass` into the using
  class's constant pool (javap -v). So gating normal code on a flag that lives in a package left out of a jar
  (`com.killer560.hub.testing`) still puts that package's name in every gated class; the testing build gates on
  `BuildVariant.TESTING` instead, and a byte grep of the normal jars for `hub/testing` comes back empty.
- **On Windows a tmp-then-`Files.move(REPLACE_EXISTING)` save fails while anything else has the target open**
  (FileAlreadyExistsException), and a writer that only logs that has lost the save. The Croesus run file lost pages 2 and
  3 across a restart that way (testkit 890, 2026-10-22). `CroesusRunMemory.replace` writes the file in place when the
  replace is refused; other savers with the same pattern (RunHistoryStore, BestFriendsStore, GraphRepository...) still drop it.
- `ServerTickClock.now()` counts ticks since client LAUNCH, so `now() % N` has no relation to any server cycle. Tick
  Timers' clear "Death Tick" was `20 - now() % 20` and so never lined up with anything; removed 2026-10-06. A phase needs
  a server anchor (NoammAddons used `ClientboundSetTimePacket.gameTime`).
- `ServerTickClock`'s subscribers cannot measure a lag spike: a real stall drops the ping rate under 15/s,
  the clock flips to client-tick fallback, and what it fires is the client's own ticks. Anything measuring
  server stalls subscribes to `subscribeRawPing` instead (as `util/ServerLag` does).
- `IslandDetector.graphIsland()` is null off any known island (sim, lobby, singleplayer), and `Set.of(...).contains(null)`
  throws. MiningProfitTracker (shelved since 2026-10-07) did that every tick once trackers went on by default; null-check
  before any `Set.of` lookup.
