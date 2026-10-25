# Sim server - gates, reach, abilities, mobs and world rules

How the sim (`roomsim/`) plays Hypixel's server: its gates, reach limits, abilities and weapons, mobs and minibosses,
damage, world rules, tab list and sidebar, and what it costs per tick.
Part of the sim docs: [SIM.md](SIM.md).

## Gates and reach

- `DungeonState.toggleSimOverride()` (the `/killer560 sim` command) forces floor, F7 **and boss phase** on together, so
  it shuts the gate on any feature that requires *not* being in the boss. For a dungeon that is not a boss, let floor
  detection run for real off a scoreboard sidebar line reading "The Catacombs (F7)".
- Several features gate on `getCurrentServer().ip` containing `hypixel.net` or `p3sim.net`, with no override anywhere.
- Server interaction limits, measured on the sim: **4.5 blocks** to a block's box (past it the server refuses outright),
  **3.0 blocks** to an entity (past it the anticheat names the distance). `MEASURED_MAX_REACH` and
  `MEASURED_MAX_ENTITY_REACH` in `CheatUtilsConfig` are the single places those live. The entity figure was re-measured
  at 0.1 resolution (scenario 85, player placed rather than walked): **3.00 draws no flag, 3.10 does**, so 3.0 is exactly
  on the edge. 4.50 to the box is 5.08 to the CENTER, which is why a center-measured 4.5 limit silently refuses
  legitimate blocks. The 2026-09-29 audit found 22 sites with their own number, all inherited from QUOI (6.0 in the
  puzzle chest auras and Auto Croesus, 5.48 in Weirdos/Water/Tic Tac Toe/Auto Routes, 4.0 for a terminal - an ENTITY
  click, so 3.0 - 4.7 for essence skulls, and clamps at 5.5 and 6.0 that let a saved config keep an unsafe value after
  the default moved). All now derive from the constants.

## The sim answers packets, like Hypixel's server

killer560: "If I make an auto route on sim it will still work the exact same on main, right? That is my entire reason
for creating the sim." Until 2026-10-04 `SimAbilities` and `SimItems` reacted to the CLIENT's `gameMode` calls, so a raw
packet (`ClearExecutor`'s `ServerboundUseItemPacket` hops through `startPrediction`) did nothing, and
`RouteExecutor`/`ClearExecutor` had grown sim-only branches (`etherwarpAlong` / `superboomAt` / `dungeonBreakAt`, a sim
landing height + 1.0, the planner's 60 blocks a hop against etherwarp's 57). If a feature behaves differently in the
sim, fix the sim.

- **Where the abilities live.** Fabric's `UseItemCallback`, `UseBlockCallback` and `AttackBlockCallback` are common
  events; their server copies fire inside `ServerPlayerGameMode.useItem` / `useItemOn` /
  `handleBlockBreakAction(START_DESTROY_BLOCK)` with the `ServerPlayer` (javap, fabric-events-interaction 5.2.2 and
  5.2.8; `handleUseItem` snaps the server player to the packet's rotation before `useItem`, 26.1.2 and 26.2). The sim's
  abilities run there, from the server player's position, rotation, `isShiftKeyDown` (set by
  `ServerboundPlayerInputPacket`) and held item, and teleport with `teleportTo(..., {Y_ROT, X_ROT} relative, 0, 0)` so
  the client gets a position packet and keeps its camera. No new mixins.
- **The client halves only stop vanilla prediction.** For an item the server handles, the client callback returns
  SUCCESS, which makes Fabric send the same packet vanilla would and skip the client-side use (no predicted TNT
  placement, no bow draw); left clicks in the sim return SUCCESS (START goes to the server, no local mining), except a
  Creeper Beams lantern, which returns FAIL so no START follows the connect.
- **Rules, checked against the server player:** trap rooms and the Teleport Maze (room at the server position), no
  etherwarp in Boulder, breaker locked before a generated floor starts, no breaker in puzzle rooms or on secrets, one
  charge per block. "One charge per press" is on the server: a START for the block just broken within 3 server ticks of
  the last START for it is the same press.
- **Charges are in the lore.** `SimBreakerState` keeps the Dungeon Breaker's "Charges: N/20" lore line current on the
  server, so Auto Routes (and the 0-ping breaker) read charges from the lore as on Hypixel.
- **Landing height is one value.** Etherwarp lands at the collision top + 1.05 (`SimAbilities.ETHERWARP_LANDING_OFFSET`),
  then falls; the planner always plans 1.05 (`EtherwarpPathfinder.STAND_OFFSET`). Instant Transmission lands on a whole
  y - the measured Hypixel behavior, which `ClearNode.AotvNode` (+ 1.0) predicts. The ray is the planner's
  (`TeleportUtils.traverseVoxels` with a `Level` argument, eye 1.27, `getLook`), run on the server level.
- **Weapons aimed by the packet.** Spirit Sceptre and Terminator right clicks are resolved on the server, which hands the
  client-side flight code the server player's eye and rotation through `SimAim`. Their LEFT clicks (Mage beam,
  Salvation, Terminator left shot) are still read off the client's attack key in `SimClass`: a left click in the air
  sends no packet but a swing, and nothing in the mod automates those.
- **Removed client branches:** `RouteExecutor` (sim etherwarp, BOOM, BREAKER, charges), `ClearExecutor` (sim range 57,
  landing 1.0, `etherwarpAlong` hop + result callback, the 40-tick "lost the path" cancel, wait-to-land after
  non-etherwarp hops), `ClearNode` (sim 1.0 / 57), `EtherwarpPathfinder` (the `offset` parameter),
  `DungeonBreakerFeature` (sim gate; dead anyway, since the sim's client callback cancels `startDestroyBlock` at HEAD
  before its TAIL hook).
- **Still different, and why:** `AutoTeleportMaze.afterChest` (the
  capture walls the center chamber off from the start pad) and the y offset everywhere (`SimAltitude`). Each is the
  sim's WORLD differing, not its server; fixing them means changing what the sim builds. (Secret Aura's essence test
  left this list on 2026-10-05, when the sim began placing Hypixel's essence skull; `TeleportUtils.underCover` /
  `EtherwarpPathfinder.coverTest` left it when the roof rule began to apply everywhere, 67ce488a, see
  [LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md).)
- **Auto Routes recording warning.** Starting a recording or `/ar add` in the sim in a room whose capture rotation is
  uncertain (`RoomCaptureRotation.uncertainForRecording`: no marker, ambiguous, or overruled) says once that the route
  may come out rotated on Hypixel until the room's capture is fixed.

## Listeners and commands

- **A sim listener that returns SUCCESS hides the click from every listener registered after it.** Fabric's
  `UseBlockCallback` stops at the first non-PASS result. `SimSecrets`' essence handler, registered before
  `SecretWaypointsFeature`, consumed the skull click, so the essence's waypoint never cleared; it now calls
  `SecretWaypointsFeature.markSimEssenceCollected` itself (nearest WITHER waypoint within 3 blocks, because a buried
  essence is placed up to two blocks above its database spot). The same trap from the other side: `SimAbilities`' client
  hook, registered BEFORE it, answered the skull click whenever an ability item was held, so Secret Aura (and he) never
  collected an essence with AOTV/Hyperion in hand. The essence is now a player_head with Hypixel's essence profile
  (black via a bundled skin patch), collected by a SERVER listener, and `SimAbilities.blockWins` lets it beat the held
  item. Scenario 99-sim-essence-aura.
- **A client command's `requires()` is evaluated when the command tree arrives on join**, not when typed - Fabric copies
  only the nodes that pass at that moment into the suggestion tree. `/start` required `isGeneratedFloor()`, which
  `SimBuilder.build` sets after the join, so it never tab-completed. The floor check lives in `SimRun.begin` only now.
  Any sim command gated on per-map state has the same trap.
- **An exception thrown inside `server.execute` is silent to the player**: it lands in the log as
  `Error executing task on Server` and the feature does nothing. Every sim feature that queues work there and reports
  success from the client side can report success for something that never ran. Check the log before writing down that
  something "works, invisibly".

## Trap rooms take your abilities

killer560: "in trap I cannot etherwarp or teleport or use any ability [...] This does not apply to dungeon breaker or
super boom." Checked at the moment of the click against the room he is STANDING in (the server position), so a warp that
starts outside and lands inside is allowed - the case he named. The Dungeon Breaker needs no exemption: it is a
LEFT-click tool on `AttackBlockCallback` and never reaches the use path. Superboom is the one name in the allow set. The
refusal returns `FAIL` rather than `PASS`, because an ender pearl is vanilla all the way down and `PASS` would let it
throw anyway. A trap room is identified by `RoomEntry.type`, not by its name containing "trap" - the name is only a
fallback for a room the database does not know.

## Teleports

- **A negative y is a valid y in the sim.** A bottom-aligned floor occupies y -63..17, so `landing = -1` as a "not
  found" sentinel made `SimBuilder.snapPlayerTo` throw away every spot it found and drop him in at `maxWorldY` onto the
  roof. Use a flag. `SimDoors.findFloor` was already right.
- A teleport that walks its bounding box along the look vector must SLIDE when only the vertical part is blocked.
  Standing on a floor, the box's bottom face is on the floor's top face, so any downward look made `SimAbilities.dash`'s
  first 0.25 step collide and refused the AOTV teleport outright (14 refusals in 10 seconds of play). Keep the
  horizontal part and carry on at the starting height.
- **Teleporting up.** `dashTarget` keeps the furthest point along the look vector the player still fits at. Its fallback
  for a step that did not fit was the same horizontal position at his STARTING height - right aiming down (the floor
  stops you and you slide along it, which his Hypixel log confirms), wrong aiming up, where it dropped him back to his
  own level. The fallback now walks from the candidate's own height TOWARD his, one block at a time, and takes the first
  that fits: under him that is the floor, over him the last block below the roof. A settle no further than the best so
  far is refused, so looking straight up at a low ceiling does nothing rather than teleporting him to where he is.

## Weapons and items

- **Verify Skyblock item ids against Hypixel's own list** (`api.hypixel.net/v2/resources/skyblock/items`), not against
  the name or memory. Three were wrong at once (2026-09-28): the Spirit Sceptre is `BAT_WAND`, not `SPIRIT_SCEPTRE`
  (broke both the sim item and the RNG meter's auction price lookup); `ClearNode` had `ASTREA` for `ASTRAEA`; Auto
  Debuff's `equals` missed `STARRED_MIDAS_SWORD`. Exactly 30 items have a `STARRED_` form and the wither blades are not
  among them, so "strip STARRED_" and "treat the blades as one item" are separate fixes. `ItemIdentity.family()` is the
  one place that knows both.
- **The Terminator fires real arrows.** A hitscan sim weapon fires nothing, as far as he can tell ("The terminator still
  does not shoot 3 arrows"). `SimTerminator.shoot` spawns real `net.minecraft.world.entity.projectile.arrow.Arrow`
  entities on the integrated server thread at `shoot(dx, dy, dz, 3.0f, 0.0f)` (3.0 is `BowItem`'s fully-drawn velocity),
  with the hitscan damage deleted, since both would double-hit invisibly on 1 HP mobs. Salvation's arming is on
  `ServerLivingEntityEvents.ALLOW_DAMAGE` (the hook `SimSurvival` uses), filtered on the damage's DIRECT entity being a
  private `TerminatorArrow` subclass; distinct-mobs-across-shots is unchanged. The subclass is the whole tagging
  mechanism (no UUID bookkeeping to leak); its entity type is still `EntityType.ARROW` because the four-argument `Arrow`
  constructor hard-codes it; it discards itself after 10 ticks in the ground so four shots a second do not carpet the
  floor. `Arrow`, `AbstractArrow`, `AbstractArrow.Pickup` and `Direction.Axis.choose(int,int,int)` are
  signature-identical in 26.1.2 and 26.2, so no compat facade is needed.
  For three days this was believed to fire "invisibly"; it had never fired one. `TerminatorArrow` passed
  `ItemStack.EMPTY` as the weapon, and 26.1.2's `AbstractArrow` constructor refuses it
  (`IllegalArgumentException: Invalid weapon firing an arrow` at `AbstractArrow.<init>(AbstractArrow.java:126)`), inside
  the `server.execute` lambda, so `arrowsFired` never incremented either. Fixed by passing `new ItemStack(Items.BOW)`,
  which is what fires these on Hypixel.
- **The Spirit Sceptre fired all along; it just could not be seen.** His log had six `[Sim] Spirit Sceptre bats fired`
  lines: `SimItems.tryUse` hit whatever was already inside an `AABB.ofSize(centre, 6, 3, 4)` - no bats, no particles, no
  sound - in a single-room sim with no mobs. The box was also axis aligned, so facing east it reached six blocks and
  spread two each side, facing south it reached two and spread three. `SimSpiritSceptre` replaces it with five bats that
  leave the hand two ticks apart, fly the look vector with a little spread, trail soul flame, and explode in a SPHERE on
  the first block or mob they meet. The damage is server-side and an approximation, not Hypixel's numbers.
- **The Dungeon Breaker is a LEFT-click tool.** It had a case on the right-click path too, so right-clicking anything
  while holding it broke the block instead of opening it - and `UseBlockCallback` consumed every click whose item merely
  had a Skyblock id, so the chest would not have opened even without that. Ask whether the id consumes a right-click
  before consuming it, and keep that question in a method: the callback is a COMMON event and the server side has to
  reach the same answer without running the ability a second time.
- **One held shot is twenty clicks.** Canceling a block break re-enters `AttackBlockCallback` every TICK rather than once
  per click; Creeper Beams consumes that callback, so one held shot picked a lantern, canceled it and picked it again
  twenty times a second - a lantern that mostly does not respond ("it wouldnt let my shots render the thing as hit").
  Three ticks of per-position cooldown, and the repeat is swallowed rather than passed on to a block break.

## Mobs and damage

- **Never iterate the level's entity list while removing from it.** `clearFloorDrops` did
  `for (var e : level.getAllEntities()) { e.discard(); }`, which mutates the level's own `Int2ObjectLinkedOpenHashMap`
  under its iterator; fastutil throws `ArrayIndexOutOfBoundsException` from inside `MapIterator.nextEntry` instead of
  `ConcurrentModificationException`. The exception killed the build task before it could hand over, so the loading
  screen stayed up at "1 room remaining". Collect into a list, then discard from the list, inside a try/catch so a
  tidy-up failure can never stop a build (as `clearFloorMobs` already did). Every scenario missed it because each builds
  into a BRAND NEW world, where the clear removes nothing. No test is kept: asserting "the build finished" passed on the
  broken jar, and asserting "no drops survive" failed on the fixed one (summoned drops in chunks the level is not
  ticking never enter `getAllEntities()`), and a test that cannot tell fixed from broken is worse than none.
- **Sim mobs are invulnerable to any damage with no entity behind it.** `SimMobs.environmental(source)` is
  `getEntity() == null && getDirectEntity() == null`, and every sim mob's `isInvulnerableTo` starts with it - deliberate,
  since a 1 HP practice target would otherwise suffocate on a low ceiling. `damageSources().magic()` has neither, so the
  mage beam (`SimClass.fire`) and the terminator (`SimTerminator.applyDamage`) landed and did nothing, with no error.
  Anything in `roomsim/` that damages a mob must name the player: resolve the `ServerPlayer` by UUID inside the
  `server.execute` and use `level.damageSources().playerAttack(sp)`.
- **No lava, fire or fall damage.** `SimWorld.freezeWorld` (sim world only) turns off `FIRE_DAMAGE` and `FALL_DAMAGE`;
  `Player.isInvulnerableTo` reads them for the `IS_FIRE` tag (which holds `minecraft:lava`) and `IS_FALL`. `SimSurvival`'s
  `ALLOW_DAMAGE` refuses both tags too (it only refused `DamageTypes.FALL` before), and the flames are put out each tick
  so lava does not paint the screen.

## Minibosses are real server-side players

- **A sim miniboss the Mob ESP recognizes has to be a real server-side PLAYER, and four separate things each silently
  refuse it.** `MobEspFeature.isMiniboss` wants a client `Player` with a **version-2 UUID** and a name from its five, so
  the only route is an AddEntity packet of type `PLAYER`. `ClientPacketListener.createEntityFromPacket` builds a
  `RemotePlayer` from `getPlayerInfo(uuid).getProfile()` and, with no `PlayerInfo`, logs "Server attempted to add player
  prior to sending player info" and drops the entity. So `SimMiniboss` sends a
  `ClientboundPlayerInfoUpdatePacket(ADD_PLAYER, boss)` to each real connection **before** `addFreshEntity` (ADD_PLAYER
  alone, so the client's `listed` stays false and it never reaches the tab list), and withdraws it with
  `ClientboundPlayerInfoRemovePacket` once the entity is gone. The other three, verified in the 26.1.2 bytecode: a bare
  `ServerPlayer` in a level **crashes**, because `ChunkMap.addEntity` calls `updatePlayerStatus(player, true)`, which
  reaches the static `ChunkMap.markChunkPendingToSend` reading `player.connection.chunkSender` with no null check - hence
  it extends Fabric's `FakePlayer`, whose constructor installs a `FakePlayerPacketListener` (a real
  `ServerGamePacketListenerImpl` over a channel-less `Connection` whose `send` is a no-op). `isInvulnerableTo` refuses
  **every** hit twice over: `FakePlayer`'s returns a flat `true`, and `ServerPlayer`'s returns true unless
  `connection.hasClientLoaded()`, which a fake listener never gets told. And `ServerPlayer.canHarmPlayer` returns false
  whenever `isPvpAllowed()` is false. All three are overridden in `SimMiniboss.Miniboss`.
- **`FakePlayer.tick()` is empty, and an empty tick makes a player immortal after ONE hit.** `invulnerableTime` is
  decremented in `ServerPlayer.tick` and nowhere else for a player (`LivingEntity.baseTick` guards its own decrement with
  `!(this instanceof ServerPlayer)`), so it sticks at 20 and `LivingEntity.hurtServer`'s `invulnerableTime > 10` branch
  refuses every later hit of the same size (`amount <= lastHurt`). A test that hits once cannot see this.
  `SimMiniboss.Miniboss.tick` decrements `invulnerableTime`/`hurtTime` and discards the entity once `isDeadOrDying()`,
  and `die()` is overridden to nothing so `ServerPlayer.die` never broadcasts a vanilla death message or leaves a corpse.
- **Singleplayer packets ARE serialized, so wire limits apply in the sim.** `Connection$3.initChannel` calls
  `configureInMemoryPipeline` -> `configureSerialization`, which installs a real `PacketEncoder`/`PacketDecoder` over the
  local channel. `ADD_PLAYER` encodes the profile name with `ByteBufCodecs.PLAYER_NAME` = `stringUtf8(16)`, and
  `Player.getName()` is the profile name and nothing else (`getfield gameProfile; GameProfile.name()`). "Frozen
  Adventurer" is 17 characters, so it can never be placed and `SimMiniboss.place` refuses it by name; the other four fit.
  `UUID.nameUUIDFromBytes` gives version 3 and `UUID.randomUUID` version 4, so the version nibble is written by hand
  (byte 6 `& 0x0F | 0x20`).
- **A placed miniboss lands in `ServerLevel.players()`.** `ServerLevel$EntityCallbacks.onTrackingStart` adds every
  `ServerPlayer` to that list, so anything in `roomsim` walking it sees NPCs: `SimMobs.wakeFels` would have had one wake a
  Fel, and `SimSecretItems` took `players().get(0)` as "the player". Both filter on `SimMiniboss.isPlaced`.
  `Kind.MINIBOSS` is the real thing; the old 20-HP starred zombie is only the automatic fallback when the player entity
  cannot be built (no player on the server yet, an unsendable name, or the `ServerPlayer` constructor throwing). There is
  no setting either way.

## Live mobs and the trap room counted cleared

Two sim options of the sim's own data (`roomsim/SimLiveMobs`), off unless asked for: `/simlive <per tile> [health]
[walk]`, `/simlive off`, `/simlive trap on|off`, or `SimLiveMobs.configure` / `setTrapCleared` from the testkit.

- **Live mobs**: at GO every mob room (database NORMAL, RARE, CHAMPION) gets N starred zombies and skeletons a tile on
  open floor, at spots seeded by the map code and the room's name (one floor gets the same mobs every run), each of the
  given health (a sim bat blast does 30, Wither Impact 10,000), on a random stroll with `walk` (no target, no attack). A
  CHAMPION room's first spot is a starred Lost Adventurer or Shadow Assassin (the real player-entity form) at three times
  that health. Each live mob's star tag follows it and shows its health as it drops (Hypixel's do), so Mob ESP and Auto
  Clear's one-tap call read what they would there. Clear-on-entry is off while it is on: a room clears when its last
  starred mob dies. Log: `[Sim] live mobs placed: N starred mob(s) in M mob room(s) ...`.
- **Trap cleared**: the trap room places no secrets, and at GO it is marked cleared (score and map, green) and credited
  with every secret the room database gives it (`SimScore.creditRoomSecrets`), as if Auto Trap had done it, so the score
  reads what it will once Auto Trap is hooked up. Log: `[Sim] trap room <name> counted cleared with its N secret(s)`.
- Testkit: `1020-sim-ac-speed` (Auto Clear's ticks per room on whole floors with live mobs, `-PacsHp`, `-PacsWalk`) and
  `1020-sim-300` (Dungeon Autopilot Solo to 300 with the trap counted). The sim's bats leave the look by up to `SPREAD`
  (0.06 an axis) and fly 1.02 a tick to ~29.6 blocks; what Auto Clear does about that, and the Tomioka stand block with
  no etherwarp out, are in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md) ("Auto Clear kill speed").

## World rules

- **`freezeWorld` runs for the sim world only**: it sat above the pending-code check in `SimWorld.onWorldLoaded` and so
  ran for every singleplayer world; it now runs after `SimState.enter`. It also turns off `MOB_DROPS` (loot and XP orbs,
  read in `LivingEntity.dropExperience`), `ENTITY_DROPS` and `BLOCK_DROPS`; the sim's own drops are spawned directly and
  unaffected.
- **Water stands still outside the Water Board.** killer560: "make it so if there is water it will not update its flow
  path outside of water board in sim." Hypixel's dungeon water is static; vanilla water drained, ran over floors and
  escaped through breaker holes. `roomsim/mixin/SimStaticWaterMixin` cancels
  `FlowingFluid.tick(ServerLevel, BlockPos, BlockState, FluidState)` at HEAD for water (`WaterFluid` does not override
  it; same descriptor in 26.1.2 and 26.2, javap) on the integrated server's overworld while `SimState.isActive()`, unless
  the column is inside a placed Water Board's paste box. `SimStaticWater` reads that box from `SimRoomIndex` (cached on
  its `version()`): `cellCenter - TILE/2 - margin`, the PasteJob anchor, by the room's size turned by its paste rotation,
  every y - so it holds whether or not the puzzle armed. A canceled tick is consumed; nothing reschedules it.
  `frozenTicks()`/`allowedTicks()` count both ways, and the first freeze on a floor logs "Sim water: holding water still
  outside the Water Board". Testkit 1410-sim-water-static.
- Anything the sim holds that names a `ServerLevel` must be dropped on EVERY unload, not only while `SimState` is still
  active: `SimBreakerState`'s regrowing blocks survived a leave that turned the sim off before the disconnect, and the
  next world's server tried to put them back into the dead level, so its loading screen never finished (96-ar).
  `SimWorld.onWorldUnloaded` resets it beside `SimBuildQueue.clear()`. Same shape for `SimRun`: a leave that turned the
  sim off first skipped `resetPerMapState`, so the last session's GO said a fresh floor's run had started (1721);
  `onWorldLoaded` resets it now.
- `Level.isLoaded(pos)` is false for any y outside the level's build height (Hypixel's dungeon world starts at y 0). Ask
  load questions at a y clamped into `level.getMinY()..getMaxY()`.

## Tab list and sidebar

- **The sim publishes a Hypixel-shaped tab list.** `DungeonInfoFeature`, `ScoreCalculatorFeature`, `RunSummaryFeature`,
  `PartyTracker` and others take secrets, crypts, rooms, deaths, puzzles and the team from Hypixel's TAB LIST display
  names. Since 2026-10-04 `SimTabList` sends one from the integrated server (80 fake player-info entries `!A-a`..`!D-t`
  plus a header/footer packet), replacing HUD branches that read `SimScore` under `SimState.isActive()`. The
  player-info packet has NO public constructor taking entries (every one takes `ServerPlayer`s, javap 26.1.2 and 26.2),
  so it is built empty and filled through the `@Mutable` accessor `SimPlayerInfoPacketAccessor`. `SimScore.roomCleared()`
  and `died()` were never called at the time, so "Completed Rooms" and "Team Deaths" read 0 (room clears since: see
  [SIM-SECRETS.md](SIM-SECRETS.md)).
- The sim's secret TOTAL was `SimMimic.candidateCount()`, the number of chests that could have been the mimic - a
  different quantity that ignores bats, essences, items and levers. `SimBuilder` now sums each placed room's
  `RoomEntry.secrets`, the same number the map prints beside a room's name.
- The sim's sidebar, the Custom Scoreboard (which shows the sim sidebar under a plain title instead of its Skyblock
  entries) and the vanilla tab list header/footer carry "killer560's personal testing sim" and `discord.gg/hkQMF5fE84`.
  The sidebar objective's title stays `SKYBLOCK`: `SkyblockGate` reads it, and every "Skyblock Only" feature in the sim
  depends on that.
- **The sidebar showed "k560l4"** because a score holder's name is drawn after its team prefix and the holders were plain
  `k560l0..15`. They are color-code-only names now (`§X§r`, Hypixel's trick), the old holders and teams are cleared from
  the saved world once, and the score numbers are blanked (`BlankFormat`). The lines are only the title line, secrets,
  room and the Discord link (time, keys/doors, the floor line and the "not Hypixel" line went; `DungeonState` gets the
  floor from `setRoomSim`). The Custom Scoreboard's sim branch is gated on `SimState`, so on Hypixel it still builds from
  the real sidebar.

## What the sim cost per tick, and why

fps-sim-1015, testkit 95-perf-sim. Most of the sim's own client time was rebuilding unchanged text: `SimTabList` composed
all eighty lines (two `String.format`s) and `SimSidebar` its seven every tick, and both asked `SimState.roomNameAt`, which
base64-decoded the whole floor's map code per call. Each now rebuilds only when an input it reads changed, and the decode
is kept per distinct code (121 -> 21 and 38 -> 6 us a tick, 10.5 KB and 5.9 KB -> ~100 B). The floor build parsed the
palette's block-state STRING for every block placed (~280 MB of garbage per F7 build); `PasteJob` now resolves each
palette entry once. A room's blocks are one byte each while its palette fits 255 entries (`Room.blockAt`/`setBlockAt`;
`room.blocks` is gone), 25.7 -> ~17 MB for the session.
