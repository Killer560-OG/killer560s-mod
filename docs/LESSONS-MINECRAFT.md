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
- Filtering which chat messages show needs no ChatScreen or ChatComponent redirect: vanilla has
  `ChatComponent.setVisibleMessageFilter(Predicate<GuiMessage>)` (26.3 and 26.2, javap 2026-11-01), which stores the
  predicate and re-lays every message through it (`refreshTrimmedMessages`); vanilla sets it from the chat restrictions
  in `LocalPlayer.refreshChatAbilities`. Chat Tabs wraps whatever arrives with a `@ModifyVariable` on that argument and
  calls the setter again to re-filter, so other chat mods keep their hooks (doc-extras-1101, testkit 1101-extras-chattabs).
- A command the mod sends through `ServerCommands.toServer` never reaches Fabric's `ClientSendMessageEvents.ALLOW_COMMAND`
  (it leaves below the dispatcher). A rule over outgoing commands (Creation Cooldown's /joininstance block) needs both:
  ALLOW_COMMAND for typed ones and a check in `toServer` for the mod's own (the /f7 shortcuts, Party Commands).

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
- **Match a sound packet's sound by id, not by object.** A `ClientboundSoundPacket` whose holder was sent inline (a direct
  holder, which is what the testkit's Hx `sound` sends) decodes to a NEW `SoundEvent`, so `value() == SoundEvents.CHEST_OPEN`
  is false for it. Mute Chest/Lever Sounds compares `value().location()` (2026-10-25); Spring Boots already did.

## Input and window on 26.3 (SDL3)

- **26.3 replaced GLFW with SDL3; `org.lwjgl.glfw` is not on its classpath.** Key codes changed meaning, not just
  names: `KeyEvent.key()` (= `input()`), `KeyMapping` keyboard values and `InputConstants.KEY_*` are SDL SCANCODES
  (A=4, Escape=41, Return=40, LShift=225; javap `SDLEventHandler.handleKeyEvent` builds `KeyEvent(scancode, keycode,
  mod)`); `KeyEvent.keycode()` is the layout-dependent SDL keycode, used by vanilla only for Ctrl+A/C/V/X. Mouse buttons
  are SDL's 1-based numbers (left 1, middle 2, right 3, X1 4), and vanilla's own `AbstractContainerScreen.
  getContainerClickButton` turns 1/3 back into container buttons 0/1. Modifier bits changed too (`MOD_SHIFT` 3,
  `MOD_CONTROL` 192); use `event.hasShiftDown()` and friends, never raw bits. `InputConstants.Type.KEYSYM` is now
  `KEYBOARD`, `isKeyDown(Window,int)` is `isKeyDown(int)`, `grabOrReleaseMouse` is `grabMouse`/`releaseMouse`, and
  `KEY_1..KEY_9` are still contiguous (30..38) but `KEY_0` (39) comes after 9. Wrong numbers COMPILE: `event.button()
  == 1` meant right click on 26.2 and is LEFT click on 26.3.
- **Stored codes are GLFW numbering on every version** (2026-11-01, port-263-input-1101): every config file, profile
  and shared setting keeps GLFW key tokens (`util/Keys`) and `KeyUtil.MOUSE_CODE_BASE - <GLFW button>`. `compat/McInput`
  translates at the boundary (identity on 26.2; GLFW<->SDL scancode table on 26.3 built from LWJGL's `SDLScancode`
  names). Rules: a code from Minecraft is NATIVE (`event.key()`, a `KeyMapping` value, `InputConstants.KEY_*`), and
  is only compared with another native code; anything that is stored, compared with a config value, or handed to mod
  code goes through `McInput.key(event)`; every mouse button read is `McInput.button(event)` (0 left, 1 right, 2
  middle on both versions); a stored code is polled with `KeyUtil.isKeyDown/isBindDown`, a `KeyMapping` value with
  `McInput.isNativeKeyDown/isNativeButtonDown`; a stored code's name is `KeyUtil.keyName`/`bindDisplayName`.
  `KeyCaptureTab.onKeyCaptured` gets the stored code, so its Escape check is `Keys.ESCAPE`. A key with no GLFW twin
  (SDL-only media keys) reads as -1 on 26.3; GLFW `WORLD_2` and `F25` have no SDL scancode and never poll as down.
- **Window management on 26.3 goes through `compat/McWindow`**, which drives SDL3 the way 26.3's own `Window` does:
  `Window.handle()` is the `SDL_Window*`, the HWND is the window property `SDL.window.win32.hwnd`, displays are SDL
  display ids (`Monitor.id()`, not `Monitor.monitor()`), cursors are `SDL_HideCursor`/`SDL_ShowCursor` plus relative
  mouse mode. 26.3's Window has no `isFullscreen()`/`toggleFullScreen()`: F11 runs the private `Minecraft.
  toggleFullscreen()`, so `WindowFullscreenMixin` has one copy per version (26.2 hooks `Window.toggleFullScreen`, 26.3
  hooks `Minecraft.toggleFullscreen`), and `McWindow.isFullscreen` reads Window's private `fullscreen` field by
  reflection (26.3 is not obfuscated). SDL3 has no Win32 display device name, so Window Layout finds a saved monitor
  by name and index on 26.3. `Util.OS.openUri/openPath` moved to `com.mojang.blaze3d.Blaze3D.openUri(URI)/openPath`.

## Packets, swings and entities on 26.3 (port, 2026-11-01)

- **26.3 has no ServerboundSwingPacket and no `LivingEntity.swing(hand)`.** Vanilla's `Minecraft.startAttack` (javap 26.3)
  animates with `swing(MAIN_HAND, held.getAttackAnimation(), false)` and then sends `ServerboundPunchPacket.INSTANCE` (no
  hand); `startUseItem` only animates a successful use (`getInteractAnimation()`) and sends NO swing packet. So a mod swing
  must say which click it goes with: `McCompat.swingAttack` after a dig START/ABORT, an attack or an air click,
  `McCompat.swingUse` after useItemOn/useItem/interact. On 26.2 both are the old `swing(hand)` with its packet. Outbound
  taps that watched for the swing packet (`OutboundBreakMixin`, `RightClickSendMixin`) now see the punch packet on 26.3,
  which only a left click sends.
- **Most game packets became records**: `getHand()`/`getHitResult()`/`getSequence()`/`getX()` are `hand()`/`hitResult()`/
  `sequence()`/`x()`; the particle packet carries x/y/z max speeds (its old 11-argument constructor copies one value into
  all three); `ClientboundMoveEntityPacket` lost `getXa/Ya/Za` for a stepped `VecDelta` decoded against
  `entity.getPositionCodec()` (still 1/4096 block units). All behind `compat/McPackets`.
- **Methods that are now only inherited on LocalPlayer:** `getViewXRot(float)` (Entity) and the drop key's
  `drop(boolean)` (gone; the key runs `MultiPlayerGameMode.dropItem(LocalPlayer, boolean)`). Mixin injects into DECLARED
  methods only, so a `@Mixin(LocalPlayer.class)` on either silently did nothing on 26.3: AP3's pitch freeze now also
  hooks `Entity.getViewXRot` (`Ap3ViewPitchMixin`), and Item Protection's drop guard has a per-version copy.
- Entities: `setInvulnerable` -> `setPermanentlyInvulnerable`, the public `invulnerableTime` field -> private with
  `get/setInvulnerableTime`, `InterpolationHandler` is an interface whose `target().position()` replaces `position()`,
  `Player.drop(stack, bool)` gained a `Prediction` (server side: `SERVER_ONLY`). `SoundInstance.resolve` ->
  `getOrResolve` (it also stores `soundEvent`). authlib 10 dropped `YggdrasilAuthenticationService`: Minecraft builds its
  UserApiService with `MinecraftServicesDiscoveryService.create(proxy, true).createUserApiService(token)`, and
  `ProfileResult` moved to `com.mojang.authlib.services`.


## Mixins beside other mods

- Mixin conflict rules (2026-11-01, Lunar Client and the chat-history mods). Two `@Redirect`s on one call: the higher
  priority takes it and the other finds no target; two `@ModifyConstant`s on one constant: the first applied takes it and
  the later one is skipped ("@ModifyConstant conflict. Skipping ... already redirected by ..."). A skipped require-1
  injector is a boot crash. MixinExtras injectors run after every plain-Mixin injector and chain with each other, so a
  `@ModifyExpressionValue` on a CONSTANT still applies after another mod's `@ModifyConstant`, and a `@WrapOperation`
  wraps the call whatever else hooks it. The testkit shows which mod lost: grep the client log for "conflict. Skipping".

- Terrain color tint (Dark Stone, darkstone-1104): Fabric API renders terrain through its own renderer and writes with `MutableQuadView.buffer`, so vanilla `ModelBlockRenderer.tesselateBlock`'s `BlockQuadOutput` sees zero quads (measured: wrapper called 683 times, 0 quads). Set a ThreadLocal color multiplier around the block's draw in `MithrilStoneSectionMixin` and apply it in `DarkBufferMixin` (`@ModifyVariable` on `BufferBuilder.putRgba(JI)V`, same on 26.2). Assert it acted (`DarkStone.tintedCount()`).
