# killer560s-mod

A Fabric client mod for Hypixel Skyblock, mostly Catacombs dungeons: solvers, HUDs, timers, a dungeon map,
and (in one of the two build variants) automation. Personal toolkit that friends also use.

## Build and run

Java 25 and a JDK on `JAVA_HOME`. Gradle wrapper, Fabric Loom.

```
./gradlew build                     # legit variant  -> build/libs/killer560smod-1.1.0-legit.jar
./gradlew build -PcheatBuild=true   # cheat variant  -> build/libs/killer560smod-1.1.0-cheat.jar
./gradlew build -Prelease=true      # official release: compiles dev tooling OUT
```

Both variants come from the same source. `build.gradle` generates
`com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED` from `-PcheatBuild`, and `DEV_TOOLS` from
`-Prelease`. Because they are `static final boolean`, javac folds every `CHEAT_FEATURES_ENABLED && x` down
to `false` in a legit build and deletes the branch, which is the point.

Versions, all from `gradle.properties` (check there, not here, if something looks off): Minecraft 26.1.2,
Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Loom 1.17-SNAPSHOT, Mod Menu 18.0.0, mod version 1.1.0,
Netty 4.2.7.Final (bundled Jar-in-Jar for the Proxy Client feature; must match what MC 26.1.2 ships).
`fabric.mod.json` declares `minecraft ~26.1`, `java >=25`, entrypoints `client` and `modmenu`.

## Testing

Boot-test only in the Prism instance **26.1.2 (Mod Only Test)**, and close the game afterwards. The
instance **26.1.2 (Dungeons)** is him actually playing — never install a test jar there, never boot it, and
never draw a conclusion from its log.

Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` — kill
only the PID that was launched, and if the log shows a server join, he took the window, so leave it.

Deploying updates **Mod Only Test does not reach the instances he plays on.** Five instances run the cheat
variant — `26.1.2 (Dungeons)`, `26.1.2`, `26.1.2 ALT`, `Map Logger` and `26.1.2 (Mod Only Test)` — and
`26.1.2 (Legit Test)` runs a legit build. After a session's work lands, check every one of them by md5, and
promote or delete any leftover `.jar.pending`: on 2026-09-29 four instances were still two builds behind
with a stale `.pending` from the night before that nothing had ever promoted.

There is an anticheat harness at `C:\Users\Hunter\killer560s-mod-testkit` that runs features against a real
GrimAC on a real dedicated server. See its own `CLAUDE.md`.

## Layout

Source under `src/main/java/com/killer560/hub/`, one package per feature area (`ap3`, `dungeonextras`,
`secrets`, `livemap`, `autopuzzles`, `terminals`, `leveraura`, `gui/tab`, `util`, …). Mixins live in a
`mixin` subpackage of the feature that owns them. Docs: `docs/FEATURES.md` holds the full text for every
feature; `README.md` holds the name list.

Shared pieces worth knowing: `util/ActionGate` is the mod-wide one-automated-interaction-per-tick arbiter
with actor priority; `util/SkyblockGate` is the "Skyblock Only" gate; `secrets/DungeonState` owns floor and
boss-phase detection; `util/ViewFreeze` holds the camera still while something rotates the real player.

## Conventions

Every setting must survive a restart: add the field, load it, save it, and expose a getter and setter.
A new feature gets its name in the README list and its full text in `docs/FEATURES.md`, then the features
Google Doc is regenerated. Sharing and receiving settings default ON. The GUI is orange-themed. Never carry
his typos into a command, label or alias.

Never track a `HANDOFF.md` in the repo; handoff notes live only in
`C:\Users\Hunter\.claude\killer560s-mod-HANDOFF-PRIVATE.txt`. The Discord bot token lives at
`C:\Users\Hunter\.claude\secrets\killer560smod-discord-bot-token.txt` and is never printed or committed.
The features Google Doc **edit** link is never published; only the `/e/2PACX-…` published link is public.

## Safety rules for automation

These are his, they are about getting banned, and they are not negotiable. Never write position or velocity
directly (`setPos`, `setDeltaMovement`) and never use fractional movement input — discrete key presses only,
and abort on any server correction. Never clamp or wrap simulated yaw at 0-360; Hypixel treats an uncapped
running rotation value as the normal signal. Pitch is always kept within -90..+90. Anticheat *deception*
features (blink, inventory walk) were declined in September 2026 and stay declined.

## Quirks and lessons

- The dungeon map does NOT work in the sim by itself. `LiveMapFeature` fills its grid from the vanilla map
  ITEM and Hypixel's clay markers; a singleplayer world has neither, so the scan finds nothing and overwrites
  whatever else tried to fill it. The sim calls `LiveMapFeature.publishSimFloor` instead and the scan is
  skipped while `SimState.isActive()`. Anything else that reads Hypixel coordinates in the sim needs
  `SimAltitude.offset()` added to its y - `DungeonLayout.doorBlock`, Secret Waypoints' database y and its
  68..108 lever band all did.
- An L-shaped room is captured as its 2x2 bounding box, and nine of his eleven have a NEIGHBOURING room's
  geometry in the quarter they do not occupy. They are excluded from generated floors until the capture
  records which quadrant is real. Hand-drawn maps are not filtered.
- A reach check belongs at the one place the interaction is SENT, not in each caller. Simon Says had four
  callers and a check in one of them; three paths sent clicks from up to 30 blocks away for months.
- `"^(?:.*something.*|...)$"` is NOT anchored. The leading `^` buys nothing when the alternative starts with
  `.*`, and the Blood Key split read that way for months. Anchor on the real line shape, with a
  `[A-Za-z0-9_]{1,16}` name group, and check with `matches()`.

- A room's DOORWAYS are read off the captured blocks (`RoomDoors`), not declared anywhere. A Catacombs
  doorway is 3 wide and 4 high at a tile-edge midpoint, cut through the perimeter wall at `margin` blocks in.
  "Doorway" is not "air": a shut wither door is coal block and a blood door red terracotta, and checking for
  air alone reported Spikes, Staircase and Arrow Trap as sealed. Blood and Higher Blaze genuinely have none
  at floor height and are read as enterable from any side.
- `SimSecrets`' clay corner is ROTATION-DEPENDENT: NW at 0, NE at 90, SE at 180, SW at 270. It used NW
  always, which was invisible while the generator refused to rotate rooms and threw every secret out of the
  room the moment it did. It is the corner of the TILE area, not of the captured window - the wall margin is
  deliberately not subtracted.
- A comparator must not call the RNG. `SimFloorLayout` sorted candidates by a key containing
  `rng.nextDouble()`; TimSort noticed and threw "Comparison method violates its general contract!". Draw the
  jitter once per element and store it.
- Gradle does NOT always regenerate `BuildVariant` when only `-Prelease`/`-PcheatBuild` changes. After
  building a release jar, a plain `./gradlew build` left the legit jar still carrying `DEV_TOOLS=false`.
  Delete `build/generated/sources/buildVariant` between variants, and check the class hash:
  `c7d5d8f5`=cheat+dev, `1d821df5`=legit+dev, `f8fc20a1`=legit+release.

- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- Interaction features must tick on `ClientTickEvents.START_CLIENT_TICK`, not `END_CLIENT_TICK`: END runs
  after the player's own movement packet, and GrimAC flags every resulting interaction as `Post`. Measured
  2026-09-27 — Breaker Aura drew 808 violations on END and zero on START; Secret Triggerbot 17 and 17.
  Fixed for Breaker Aura in `825f319`. Audited properly 2026-09-29: of 111 END registrations, exactly
  **three** reach a block/item/container packet — `Ap3Feature:95`, `AutoRoutesFeature:87`, `FastLeapFeature:105`
  — and about 17 more send only chat or a server command. The "about twenty" figure counted those. Separately,
  three features click from a RENDER FRAME, which is also after the movement packet: Goldor Triggerbot, Arrow
  Align and Auto I4. `ActionGate` does not help — `tryAct` returns immediately and the caller sends
  synchronously, so it arbitrates who acts and never changes ordering.
- `RenderSystem.setShaderColor` does not exist in 26.1.2, so there is no global colour multiplier and items
  cannot be tinted per-item. The inventory HUD's Opacity now dims items with a translucent quad drawn over the
  panel after the item loop instead: 0 hides the panel outright, and the darkening is capped at 80% so no
  setting turns it into an unreadable black box.
- Forwarding a self-registered client command name to the server recurses through Fabric's command API and
  StackOverflows. Send below the dispatcher via `util/ServerCommands.toServer`.
- `DungeonState.toggleSimOverride()` (the `/killer560 sim` command) forces floor, F7 **and boss phase** on
  together, so it shuts the gate on any feature that requires *not* being in the boss. To get a dungeon that
  is not a boss, let floor detection run for real off a scoreboard sidebar line reading
  "The Catacombs (F7)".
- Several features gate on `getCurrentServer().ip` containing `hypixel.net` or `p3sim.net`, with no
  override anywhere in the codebase.
- An automated click must aim at a point on the block's real **surface**, from the eye, not at
  `Vec3.atCenterOf(pos)` with a fixed `Direction`. The centre is a point *inside* the block and no raycast
  produces it; GrimAC raised `PositionPlace` on every such click even at a distance the server accepted
  (2026-09-28). Use `util/BlockHits.surface`, and prefer skipping a tick to sending an impossible hit. Entity
  clicks are the same: aim at a point on the entity's box, which Arrow Align and Terminal Aura already do.
- Server interaction limits, measured on the sim: **4.5 blocks** to a block's box (past it the server refuses
  outright), **3.0 blocks** to an entity (past it the anticheat names the distance). `MEASURED_MAX_REACH` and
  `MEASURED_MAX_ENTITY_REACH` in `CheatUtilsConfig` are the single places those live. The entity figure was
  re-measured at 0.1 resolution on 2026-09-29 (scenario 85, player placed rather than walked): **3.00 draws no
  flag, 3.10 does** - so 3.0 is right and is exactly on the edge. Confirmed in the same run that 4.50 to the box
  is 5.08 to the CENTRE, which is why a centre-measured 4.5 limit silently refuses legitimate blocks.
  Audited 2026-09-29: 22 sites carried their own number, all inherited from QUOI and none tied to the
  measurement - 6.0 in the puzzle chest auras and Auto Croesus, 5.48 in Weirdos/Water/Tic Tac Toe/Auto Routes,
  4.0 for a terminal (an ENTITY click, so the limit is 3.0), 4.7 for essence skulls, and clamps at 5.5 and 6.0
  that let a saved config keep an unsafe value even after the default moved. All now derive from the constants.
- When sweeping for features that tick on the wrong event, resolve the **called classes**, not per-file: a
  feature is often ticked from a lambda in another class entirely (`CheatUtils` ticks Secret Aura, Auto Ult and
  Chocolate Factory; `PathfindingFeature` ticks the soul runner and pearl hopper). A per-file grep missed seven
  of them and the gap only surfaced as `Post` violations in a later test.
- Reach must be measured to the block's **box**, not its centre — the centre reads up to half a block
  further and makes a module look out of range when it is not.
- AP3's align planners solve the YAW freely, so two entries in an action set differ only by the SIZE of the
  push and what they leave for the next tick (sprint, crouch) - a key pointing elsewhere is the same action at
  another yaw. All eighteen real key combinations produce just five sizes: 0, 0.13377 (non-sprinting straight
  key), 0.13650 (non-sprinting diagonal), 0.17390 (W) and 0.17745 (W+A), and only `fw > 0` sprints. Searching a
  near-duplicate costs |ACTS| to the power of the press count for nothing.
- **Align nodes are designed for 550-600 speed** on the Hypixel scale (killer560, 2026-09-28: "they should
  still align at lower speeds but the time isn't important"). So tune and benchmark at 550-600, and treat low
  speed as a CORRECTNESS check only - it must still land, it may take as long as it likes. This matters because
  every push the planner prices comes off the movement-speed attribute: at 550-600 Fast Align lands 100% of
  cases in 3 or 4 ticks, while at 100-450 it lands 89% with a tail out to 7. A constant tuned at walking pace
  is not tuned. The sim can be set to any Hypixel speed with `TestMap.speed(550)`.
- The dungeon sim (`roomsim/`) is the ONE place this mod writes positions, and that is correct there: the
  no-direct-movement rule exists because Hypixel reconstructs your movement and lags you back, and in the sim the
  integrated server is ours. Everything sim-only gates on `SimState.canAct`, which requires a singleplayer world
  AND no connected server. If that gate is ever wrong, those files write positions on Hypixel - treat it as the
  single safety boundary of that package and do not add a second way in.
- Synthetic sim rooms live in `RoomLibrary`'s separate `TEST_ROOMS` map, are never saved, never counted and never
  listed as missing. A synthetic room in the real map would be written to disk by `saveAll()` and would end up in
  the shipped library looking exactly like a captured one.
- No public dungeon dataset ships room GEOMETRY (checked 2026-09-28). Dungeon Rooms Mod and its kind store secret
  coordinates plus room identification, which a waypoint mod needs and a sim cannot use. DRM is also GPL-3.0
  against this mod's MIT, so its code can never be used here - data only, credited, and only with his say-so.
- Align tick counts are bound by STOPPING, not by travel or by the solver. You must arrive under vanilla's 0.003
  zeroing line or the next tick slides you off the point, and friction alone takes ~8 ticks from top speed. A
  floor that charges the stop sits at 4.41 ticks against the planner's 4.52 (measured 2026-09-28), and 3 ticks
  is impossible for 72% of aligns at any tolerance. Tolerance is nearly free: Caleb's 3e-8 costs 0.09 of a tick
  over 1e-4. Do not accept a "make the align faster" task without re-deriving that floor first.
- A room's captured size is `tiles * 32 + 1` (`RoomLibrary.footprint`), and the ONLY inverse is
  `tiles = (size - 1) / 32`. Two places had their own: capture read a room's grid-cell span as a tile count
  (a 3-tile room captured 157 blocks long instead of 97, running 60 columns into the next room), and
  `SimFloorGen.cellFootprint` read the captured size as a tile span and halved it. Those two errors cancelled
  exactly, so fixing one alone produced a worse bug than either — a 2-tile room planned into one cell and
  pasted over its neighbour. `RoomLibrary.cellFootprint(name)` is now the single inverse; scenario 73 asserts
  every room covers exactly its captured footprint.
- Map-code room ids are per PLACEMENT, never per name. They were deduplicated by name, and `SimBuilder` pastes
  by flood-filling cells with the same id — so two placements of one room standing next to each other merged
  into a single smeared paste. Non-adjacent duplicates survived by luck, which is why it usually looked fine.
- The gametest client runs as **java.exe**, not javaw.exe. `run-scenario.ps1` filtered on javaw only, so every
  safeguard in it was inert — the freeze watcher never saw an unresponsive client and the deadline cleanup
  killed nothing, while the script reported success. That is why "it still doesn't close out on freeze"
  survived two rounds of fixes to the watching logic.
- `SimFloorGen.plan(...)` lays out a floor without touching the world, so a scenario can assert over a hundred
  floors instead of one. Built because the "does it ever place a multi-tile room" assertion passed and failed
  on alternate runs when it could only see a single floor — a test that flaps teaches you to ignore it.
- **A throw in a RAW chat listener disconnects him from Hypixel.** `ClientReceiveMessageEvents` runs on the
  packet path, and `ClientCommonPacketListenerImpl.onPacketError` logs "Failed to handle packet, disconnecting"
  and drops the connection. `util/ChatObserver` catches per listener; the raw events do not. On 2026-09-29 the
  Simon Says party tracker matched `SS (\d+)/(\d+)` with unbounded digits straight into `Integer.parseInt`, so
  ANY player typing `SS 99999999999/5` in party, guild or all chat ended his run - and that tracker is on by
  default. Reproduced and fixed, with scenario 86 as the control: on the broken jar it disconnects with
  "Network Protocol Error"; on the fixed one it survives. Bound every quantifier that parses another player's
  text, and wrap the parse anyway.
- A block scan over a room's volume is almost always a chunk-section scan in disguise. Secret Waypoints read
  44,649 blocks for a 1x1 room (173,000 for a 1x4) every second looking for levers; asking each section's
  palette first (`section.maybeHas(...)`, plus `hasOnlyAir()`) does the same job in 0.7% of the reads -
  10,240 against 1,527,209 over a whole floor, verified equivalent by scenario 77 which runs BOTH algorithms
  and requires identical results. Also hoist `isLoaded`/`getChunk` out of the y loop: they depend only on x,z.
- `Map.getOrDefault` EVALUATES its default eagerly. `HudConfig.getPosition` allocated a throwaway `int[2]` on
  every call even when a saved value existed - about 110 allocations a frame across the HUD.
- An early-out that reads "not enabled AND no key bound" is not an early-out when the key has a DEFAULT
  binding. Breaker Aura's render callback ran in every world, including the lobby, because its select key
  defaults to semicolon. Gate on the state the feature needs (in a dungeon), not on whether it is configured.
- **Anchor every chat pattern that gates an ACTION.** Other players' messages arrive on the same listeners as
  the server's. An unanchored `find()` on Maxor's opening line let anyone set the boss phase for a whole run
  (2026-09-29), and `contains("has obtained Wither Key")` let anyone make the client right-click a door. Even
  an anchored `^(.{1,16}) completed a terminal!` is forgeable, because `[VIP] Bob: a` is sixteen characters -
  a name group must be `[A-Za-z0-9_]{1,16}`, which no chat prefix can be.
- Verify Skyblock item ids against Hypixel's own list (`api.hypixel.net/v2/resources/skyblock/items`), not
  against the name or memory. Three were wrong at once (2026-09-28): the Spirit Sceptre is `BAT_WAND`, not
  `SPIRIT_SCEPTRE`, which broke both the sim item and the RNG meter's auction price lookup; `ClearNode` had
  `ASTREA` for `ASTRAEA`; and Auto Debuff's `equals` missed `STARRED_MIDAS_SWORD`. Exactly 30 items have a
  `STARRED_` form and the wither blades are not among them, so "strip STARRED_" and "treat the blades as one
  item" are separate fixes. `ItemIdentity.family()` is the one place that knows both.
- Instant Transmission is 8 blocks on Aspect of the End, Aspect of the Void AND the Etherwarp Conduit alike.
  What changes the range is the item's own `tuned_transmission` tag - a Transmission Tuner adds a block, four
  maximum - so a fully tuned one of any of them goes 12, and killer560 plays fully tuned ("nearly no one plays
  with less"). AOTV is NOT 12 by nature; assuming that got AOTE and AOTV wrongly split in the route matcher
  once already. `EtherwarpHopper` reads the same tag for the 57-block etherwarp.
- `ItemIdentity.of()` is shared by Auto Sell, the Inventory Sorter, Armour Dye and the mining profit tracker.
  Widening it to make two items equal makes "sell my Hyperion" sell an Astraea. Loose matching belongs in
  `matches()`, which only a route's USE_ITEM node reaches.
- `/f7`, `/m7` and the rest are THIS MOD'S client-side shortcuts, not Hypixel commands - they expand to
  `/joininstance catacombs_floor_seven` etc. in `CommandShortcutsFeature.Shortcut`. So automation must send the
  `joininstance` form: `ServerCommands.toServer` deliberately sends below the client dispatcher, so it handed
  Hypixel the literal "/f7" and the Room Recorder sat waiting for a dungeon that was never queued (2026-09-28).
  `/dh` and `/skyblock` ARE real Hypixel commands. Read the id off the enum rather than writing it out again.
- An "any key stops it" guard must ignore keys while `client.screen != null`. Otherwise the Return that submits
  the command starting the feature, and the Escape that closes the settings tab starting it, each stop it
  immediately - which reads as "I turn it on and it auto turns off". Name the key in the stop message too; "key
  pressed" cannot tell a walk from the feature killing itself.
- `DungeonLayout.name(room)` returns the literal `"Unknown"` for a room it has not identified yet - a
  placeholder, not a name. `RoomLibrary.capture` only rejected null/blank, so the first live scan (2026-09-28)
  wrote `Entrance.json` and `Unknown.json` identical in all 77841 block positions and reported "2 of 2 rooms
  complete" for one room seen twice. Every unidentified room shares that one placeholder, so the real damage
  was the next one overwriting it and producing a file holding half of two different rooms. Check for the
  placeholder, not just for blank, and distrust a room count that has not been diffed.
- The sim menu runs from the MAIN MENU, where there is no world yet. Every builder had a `server == null`
  branch that returned silently or opened an empty sim with a "run the command again" message, so nothing the
  menu offered ever built anything (found 2026-09-28: "no room ever loaded"). `SimWorld.open` now takes the
  build as a callback and runs it once the world exists, behind `SimLoadingScreen`. Any new entry point must
  go through that, not call a builder directly.
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default
  could never be restored once the setter ran. Fixed 2026-09-27. Worth checking other setters for the same
  mismatch between setter clamp and field default.
- A second Interactive Map goal cannot simply be issued over a running one. `ClearExecutor.etherPath` returns
  immediately while `pathPending`, and even when it does plan, it plans from the position you were at when you
  pressed - `ClearNode.inside` needs you within 0.32 blocks of the first hop, so once you have warped off that
  spot the new queue is inert and `isBusy()` never clears. A new goal must `cancel()` and then be issued from a
  later tick. `cancel()` also had to stop clearing everything *except* `syncDelay`, which kept `isBusy()` true
  for up to 49 more ticks with no completion callback left to run. Anything that holds a goal across those ticks
  must publish its own "still steering" flag: `isBusy()` is false for the whole wait by design, and Auto Routes'
  interlock 5 reads exactly that, so a node underfoot would arm in the gap and steer against the warp about to
  start. `InteractiveMapFeature.isSteering()` is that flag.
- `RouteExecutor.stop()` is already a real cancel - it drops the step machine, `releaseKeys()` zeroes the
  want-flags the input mixin reads, `RouteRotation.clear()` releases the camera, and every per-node buildup
  (breaker queue, boom snapshot, swap/await state) is rebuilt by `beginAction`. BOOM and BREAKER send their
  START and ABORT in the same tick, so nothing is left open server-side either. What it does NOT clear is
  `stoppedByUser`/`justFinished`, and both make `AutoRoutesFeature` latch instead of arming *while the player
  stands inside a node* - which is exactly where a map warp puts him. Clear them whenever something other than
  the player cancels a route.
