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

- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- Interaction features must tick on `ClientTickEvents.START_CLIENT_TICK`, not `END_CLIENT_TICK`: END runs
  after the player's own movement packet, and GrimAC flags every resulting interaction as `Post`. Measured
  2026-09-27 — Breaker Aura drew 808 violations on END and zero on START; Secret Triggerbot 17 and 17.
  Fixed for Breaker Aura in `825f319`. **About twenty other features still tick on END.**
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
  `MEASURED_MAX_ENTITY_REACH` in `CheatUtilsConfig` are the single places those live.
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
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default
  could never be restored once the setter ran. Fixed 2026-09-27. Worth checking other setters for the same
  mismatch between setter clamp and field default.
