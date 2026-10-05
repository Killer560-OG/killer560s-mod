# killer560s-mod

A Fabric client mod for Hypixel Skyblock, mostly Catacombs dungeons: solvers, HUDs, timers, a dungeon map,
and (in one of the two build variants) automation. Personal toolkit that friends also use.

## Build and run

Java 25 and a JDK on `JAVA_HOME`. Gradle wrapper, Fabric Loom.

```
./gradlew build                     # legit variant  -> build/libs/killer560smod-1.1.0-26.1.2-legit.jar
./gradlew build -PcheatBuild=true   # cheat variant  -> build/libs/killer560smod-1.1.0-26.1.2-cheat.jar
./gradlew build -Prelease=true      # official release: compiles dev tooling OUT
python deploy-to-instances.py [--dry-run] [--jars-dir DIR]   # install into every Prism instance
```

The Minecraft version is in every jar name, and one gradle run builds one version. The deploy script keeps
each instance on its MC version and installed variant, stages `.jar.pending` for a running one, and prints
each instance's BuildVariant md5; point `--jars-dir` at a folder holding the other version's jars.

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

**Never use `prismlauncher.exe --launch`.** On 2026-09-30 `--launch "26.2 mod only"` was given to an
already-running Prism that did not yet know that folder, and instead of failing it started **`26.2`, one of
his play instances** (17:35:35 to 17:39:54 in its own log). A new instance folder is invisible to a running
Prism until it rescans, and `--launch` does not report a miss. Boot-test through the gametest harness, which
starts its own client and needs no launcher, or ask him to launch it. killer560, the same day: "make sure you
are only ever boot testing on the mod only variant."

Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` — kill
only the PID that was launched, and if the log shows a server join, he took the window, so leave it.

Deploying updates **Mod Only Test does not reach the instances he plays on.** As of 2026-09-30 SIX
instances run the cheat variant - `26.1.2 (Dungeons)`, `26.1.2`, `26.1.2 ALT`, `Map Logger`,
`Ashfall rooms` and `26.1.2 (Mod Only Test)` - and `26.1.2 (Legit Test)` runs a legit build. After a session's work lands, check
every one by md5 and promote or delete any leftover `.jar.pending`: on 2026-09-29 four instances were two
builds behind with a stale `.pending` nothing had ever promoted.

**Enumerate the instances directory, never trust this list.** It has been wrong twice in one day:
`AP3 Competition Instance` was missing from it, then that instance disappeared and two new ones showed up.

**There is now a `26.2 mod only` instance**, created 2026-09-30 as the 26.2 twin of `26.1.2 (Mod Only Test)`:
MC 26.2, Fabric Loader 0.19.5, Fabric API 0.160.0+26.2, Mod Menu 20.0.2, Kotlin, and the mod. It is the only
place a 26.2 build may be booted. A newly created instance is invisible to a running Prism until it rescans,
so restart the launcher before looking for it.

**`26.2` and `26.2 ALT` get the 26.2 build, not the 26.1.2 one.** killer560 lifted the old do-not-deploy rule on 2026-09-30 ("you can deploy the proper version of the mod to the proper instance") when 26.2 became a supported target: 26.1.2 stays the main release because most people play it, and a good few play 26.2. Four jars now ship - legit and cheat for each - and the Minecraft version is in every jar's name so `*-legit.jar` cannot match two different builds. The 26.1.2 jar still will not load on 26.2 and vice versa, because each declares its own `minecraft` range; that is the point, not a bug to widen away.

There is an anticheat harness at `C:\Users\Hunter\killer560s-mod-testkit` that runs features against a real
GrimAC on a real dedicated server. See its own `CLAUDE.md`. Harness system properties (network fakes/offline, no OS opens) are in **[docs/TESTING-HOOKS.md](docs/TESTING-HOOKS.md)**.

## Layout

Source under `src/main/java/com/killer560/hub/`, one package per feature area (`ap3`, `dungeonextras`,
`secrets`, `livemap`, `autopuzzles`, `terminals`, `leveraura`, `gui/tab`, `util`, …). Mixins live in a
`mixin` subpackage of the feature that owns them. Docs: `docs/FEATURES.md` holds the full text for every
feature; `README.md` holds the name list.

Shared pieces worth knowing: `util/ActionGate` is the mod-wide one-automated-interaction-per-tick arbiter
with actor priority; `util/SkyblockGate` is the "Skyblock Only" gate; `secrets/DungeonState` owns floor and
boss-phase detection; `util/ViewFreeze` holds the camera still while something rotates the real player.

## Conventions

Loggers come from `util/ModLog.get("killer560smod-…")`, never from `LoggerFactory` directly. In a dev or
cheat build that hands back the real SLF4J logger; in a release (`-Prelease=true`, `DEV_TOOLS == false`) it
hands back one that drops TRACE/DEBUG/INFO/WARN and forwards only ERROR, so a release jar is quiet without
a thousand call sites being guarded. `roomsim/` and `bazaarflip/` were the last two packages still calling
`LoggerFactory` directly, and so the last two still logging in a release; both went through `ModLog` on
2026-09-30, so a release jar is now quiet everywhere.

Every config path goes through `util/ModPaths.config("killer560smod-<name>")`, never `FabricLoader.getConfigDir()`
directly: files live in `config/killer560/<category>/<feature>/` under their unchanged names, and the folder is
picked from the prefix table in `ModPaths` (add a row for a new feature, or it lands in `other/`). Code that lists
setting files uses `ModPaths.settingFiles()`. The client entrypoint's `ModPaths.migrateAll()` moves old root files in.

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

Two topics have their own files, because they had grown to half this one:
**[docs/SIM.md](docs/SIM.md)** for the dungeon sim (`roomsim/`) - room captures, floor generation,
secret placement, doors and altitude - and **[docs/AP3.md](docs/AP3.md)** for AP3's nodes and align
physics. Read the relevant one before touching either area. Feature-specific lessons (Bazaar, HUD
elements, Superpairs, Instant Transmission, item identity, gametest) are in
**[docs/LESSONS.md](docs/LESSONS.md)**. The Room Recorder was removed on 2026-10-04 and lives at git tag
`room-recorder-last`; the last section of docs/SIM.md says how to restore it.

- `LOGGER.debug` never reaches his log. Minecraft's root log4j2 level is INFO, and a real client log
  (`26.1.2 (Dungeons)`, 29,596 lines, 2026-09-29) contains zero DEBUG lines. So a `.debug` call is not the
  spam and deleting one buys nothing; when hunting log noise, hunt `.info`. That is where it all was: the
  2026-09-29 trim cut INFO call sites from 783 to 273 and left WARN (276) and ERROR (86) alone.
- A reach check belongs at the one place the interaction is SENT, not in each caller. Simon Says had four
  callers and a check in one of them; three paths sent clicks from up to 30 blocks away for months.
- `"^(?:.*something.*|...)$"` is NOT anchored. The leading `^` buys nothing when the alternative starts with
  `.*`, and the Blood Key split read that way for months. Anchor on the real line shape, with a
  `[A-Za-z0-9_]{1,16}` name group, and check with `matches()`.
- A comparator must not call the RNG. `SimFloorLayout` sorted candidates by a key containing
  `rng.nextDouble()`; TimSort noticed and threw "Comparison method violates its general contract!". Draw the
  jitter once per element and store it.
- Switching variants needs `build/classes` cleared too, not just the generated `BuildVariant` source. After a
  cheat compile, deleting only `build/generated/sources/buildVariant` and compiling legit produced a wall of
  `error: cannot access Ap3FreezeState` / `Ap3EditScreen` / `Ap3RouteCache` - stale class files, not a real
  break. The same source compiled clean once `build/classes` went too (2026-09-29). A "cannot access <a class
  in this repo>" error is almost always this, not a missing dependency.
- Gradle does NOT always regenerate `BuildVariant` when only `-Prelease`/`-PcheatBuild` changes. After
  building a release jar, a plain `./gradlew build` left the legit jar still carrying `DEV_TOOLS=false`.
  Delete `build/generated/sources/buildVariant` between variants, and check the class hash - **`md5sum` of
  `com/killer560/hub/BuildVariant.class` unzipped from the jar, first 8 hex characters**, which a 2026-09-30
  sweep wasted time on by reaching for CRC32 and finding it did not reproduce:
  `c7d5d8f5`=cheat+dev, `1d821df5`=legit+dev, `f8fc20a1`=legit+release. `javap -constants` on that class is
  the unambiguous check if a hash ever disagrees - it prints the two booleans directly.
- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
- **Two Minecraft versions, one source tree.** `build.gradle` derives a compat directory from
  `minecraft_version` and puts exactly ONE of `src/mc26_1/java` / `src/mc26_2/java` on the source path (it
  prints "compatibility layer mcXX_Y" every build and throws for a version with no directory). Version-specific
  API lives in `com.killer560.hub.compat` - `McCompat`, `McBlocks`, `McItems`, `McEntities`, `McRender` - and
  **every version's copy must have identical public signatures**, or one version stops compiling. Nothing under
  `src/main/java` may touch an API that differs between versions. Build 26.2 with
  `-Pminecraft_version=26.2 -Pfabric_api_version=0.160.0+26.2 -Pmodmenu_version=20.0.2`.
  A mixin is the one thing the facade cannot cover, because `@Mixin(X.class)` is an annotation constant: a
  mixin whose target **class** moved gets one copy per version directory under the same name (eight do), while
  one where only the **method** name changed just lists both - `method = {"renderFire", "submitFire"}`. Mixin
  accepts a handler taking only `CallbackInfo` for any target (checked in `CallbackInjector$Callback.
  checkDescriptor`: the full descriptor OR `(LCallbackInfo;)V`, all-or-nothing, never a prefix), which is how a
  single mixin survives a target whose argument TYPES changed.
- Run gradle through Bash, not PowerShell: PowerShell wraps native stderr in ErrorRecords and splits compiler
  messages mid-line, so error counts and file paths become unreadable. Gradle also prints compiler output
  TWICE, so `grep -c "error:"` is double - the `N errors` line javac prints is the authoritative number.
- **Cancelling a block break makes `AttackBlockCallback` fire every TICK, not once per click.**
  `MultiPlayerGameMode.continueDestroyBlock` only continues an existing break when `isDestroying` is set, and
  that field is set inside `startDestroyBlock` - which is where the callback lives and which a cancel returns
  from first. So holding the button re-enters the callback twenty times a second (verified by `javap -c`:
  `continueDestroyBlock` calls `startDestroyBlock` on its fallback path). The sim's Dungeon Breaker spent its
  whole twenty-charge bar in one second this way. Anything that consumes that callback needs its own
  edge-detection - track the block and clear it when `keyAttack` comes up.
- **One room must not have two answers.** A room's clay corner and its rotation are published once and read
  everywhere; if any second place computes them, they will disagree and the symptom will be "my solvers point at
  the wrong block". `SimBuilder` published the PASTE rotation beside a corner computed for the DATABASE rotation,
  which differ by the capture's own turn in 88 of 122 rooms, so nine of the eleven puzzle rooms' solvers were
  measuring through a rotation a quarter or half turn off. `SimRoomIndex` had always been right, which is why
  Secret Waypoints worked while every solver did not - **a feature that works beside one that does not, on the
  same data, is naming the bug.** The publish now reads `SimRoomIndex` and nothing else computes it.
- **A bare dungeon height in a comparison is a bug in the sim.** Relative y IS world y on Hypixel, so
  `player.getY() != 75.0`, `pos.y != 69.5`, `69.5..72.5` and `y 66` all look like facts and are all wrong the
  moment `SimAltitude` shifts the floor. Anything that TRANSLATES a coordinate goes through
  `PuzzleCoords`/`RouteCoords`; anything that COMPARES one adds `DungeonLayout.simYOffset()`. Better still, take
  the height from the solver's own output (`path.get(0).y`) so there is no second expression to keep in step.
  Five of these were found in one sweep in October 2026, one per auto, each failing silently.
- **To make the sim satisfy a solver, read the solver's SCAN, not just its coordinates.** Getting the position
  right is half of it. `BlazeSolverFeature` skips anything that is not an `ArmorStand`, so a name set on the blaze
  itself was invisible; `TicTacToeSolverFeature` reads map ITEM FRAMES at the cell, so a mark painted on the wall
  behind the button was invisible; `BeamsSolverFeature` reads prismarine as "burned on the wrong partner", so the
  sim marking a held lantern that way turned a correct shot red; `TeleportMazeSolverFeature` only accepts a
  teleport that lands on a half block. In every case the sim was placing the right thing somewhere the solver
  never looks, or the wrong thing where it does.
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
- **A mixin that compiles on 26.2 can still crash 26.2 at startup.** Mixin descriptors are only checked when the
  game loads, so `@Inject(method = "extractRenderState")` on `Gui` with 26.1.2's `(GuiGraphicsExtractor, DeltaTracker)`
  built fine and crashed every 26.2 launch (26.2's is `(DeltaTracker, boolean, boolean)`). Seven overlays did it until
  2026-10-04; they are now Fabric HUD layers in `hud/GuiOverlays`. A 26.2 change is not done until a 26.2 boot ran:
  the testkit's `run-scenario.ps1 -Scenario smoke -Minecraft 26.2` does it unattended.
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
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- Forwarding a self-registered client command name to the server recurses through Fabric's command API and
  StackOverflows. Send below the dispatcher via `util/ServerCommands.toServer`.
- When sweeping for features that tick on the wrong event, resolve the **called classes**, not per-file: a
  feature is often ticked from a lambda in another class entirely (`CheatUtils` ticks Secret Aura, Auto Ult and
  Chocolate Factory; `PathfindingFeature` ticks the soul runner and pearl hopper). A per-file grep missed seven
  of them and the gap only surfaced as `Post` violations in a later test.
- Reach must be measured to the block's **box**, not its centre — the centre reads up to half a block
  further and makes a module look out of range when it is not.
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
- An early-out that reads "not enabled AND no key bound" is not an early-out when the key has a DEFAULT
  binding. Breaker Aura's render callback ran in every world, including the lobby, because its select key
  defaults to semicolon. Gate on the state the feature needs (in a dungeon), not on whether it is configured.
- **Anchor every chat pattern that gates an ACTION.** Other players' messages arrive on the same listeners as
  the server's. An unanchored `find()` on Maxor's opening line let anyone set the boss phase for a whole run
  (2026-09-29), and `contains("has obtained Wither Key")` let anyone make the client right-click a door. Even
  an anchored `^(.{1,16}) completed a terminal!` is forgeable, because `[VIP] Bob: a` is sixteen characters -
  a name group must be `[A-Za-z0-9_]{1,16}`, which no chat prefix can be.
- `/f7`, `/m7` and the rest are THIS MOD'S client-side shortcuts, not Hypixel commands - they expand to
  `/joininstance catacombs_floor_seven` etc. in `CommandShortcutsFeature.Shortcut`. So automation must send the
  `joininstance` form: `ServerCommands.toServer` deliberately sends below the client dispatcher, so it hands
  Hypixel the literal "/f7" and nothing is queued (found 2026-09-28).
  `/dh` and `/skyblock` ARE real Hypixel commands. Read the id off the enum rather than writing it out again.
- An "any key stops it" guard must ignore keys while `client.screen != null`. Otherwise the Return that submits
  the command starting the feature, and the Escape that closes the settings tab starting it, each stop it
  immediately - which reads as "I turn it on and it auto turns off". Name the key in the stop message too; "key
  pressed" cannot tell a walk from the feature killing itself.
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default
  could never be restored once the setter ran. Fixed 2026-09-27; the SLIDER driving it still mapped onto
  1-20 and was fixed 2026-09-30. A clamp has two halves - the setter and whatever widget feeds it - and a
  slider whose start position computes negative (`(0 - 1) / 19`) is the tell. Every other numeric setter in
  the repo was swept on 2026-09-30 and has its field default inside its clamp.
- **A fix applied to one of a set is the thing to go looking for.** The 2026-09-30 audit found four, all of
  the same shape: the Wither Key line was anchored and the Blood Key line beside it left on `contains()`;
  `Floor7Tracker` and `LeverAura` got the `[A-Za-z0-9_]{1,16}` name group and `Ap3Feature`'s copy of the same
  line kept `(.{1,16})`; three reach CONSTANTS were tightened to 4.5/3.0 and the MEASURES under them left on
  centre/feet (the javadoc describing the fault sat directly above the code still doing it); the cooldown
  setter above. When fixing one instance, grep the sibling call sites and fix the set, and update the comment
  that claims they match - two of these said "the same regex as X" after they had stopped being that.
- `RouteExecutor.stop()` is already a real cancel - it drops the step machine, `releaseKeys()` zeroes the
  want-flags the input mixin reads, `RouteRotation.clear()` releases the camera, and every per-node buildup
  (breaker queue, boom snapshot, swap/await state) is rebuilt by `beginAction`. BOOM and BREAKER send their
  START and ABORT in the same tick, so nothing is left open server-side either. What it does NOT clear is
  `stoppedByUser`/`justFinished`, and both make `AutoRoutesFeature` latch instead of arming *while the player
  stands inside a node* - which is exactly where a map warp puts him. Clear them whenever something other than
  the player cancels a route.
- A movement key held when an Auto Routes node fires is the walk that got him there, not a takeover. The input
  mixin used to return before installing the route's input whenever any movement key was down, so walking onto
  an `/ar add ew start` node never got the etherwarp's sneak out and timed out (and a driven route stopped on its
  first tick) - traced in the code 2026-10-04. `RouteExecutor.onInputTick` now overrides keys held since the route took over and treats only a
  press after a release as "you moved" - AP3's align rule. (His sim etherwarps not firing was a different cause - the
  sim ignored raw use packets - and is fixed by the rule below, not by a branch.)
- **The sim emulates Hypixel's SERVER; client features never special-case it.** Abilities in `roomsim` answer the
  use / use-on / START_DESTROY_BLOCK packets on the integrated server (Fabric's common `UseItemCallback`,
  `UseBlockCallback`, `AttackBlockCallback`, whose server copies fire for the `ServerPlayer`), from the server
  player's position, packet rotation, sneak and held item, and teleport with a real position packet. Until
  2026-10-04 they reacted to the client's `gameMode` calls instead, and `RouteExecutor`/`ClearExecutor` grew sim
  branches to cope - the opposite of why the sim exists. If a feature behaves differently in the sim, fix the sim.
- **Moving a setting to a different sub-tab silently orphans its scoped tooltip.** `SettingTooltips.describe`
  looks up `"<sub-tab name>/<label>"` first and falls back to the bare label, so a `d.put("experiments/set", ...)`
  entry stops being found the moment that button is built by a different tab - no error, the hover text just
  changes or disappears. Re-key the entry in `SettingTooltipsData` in the same session as the move.
- Agent worktrees are cut from `main`, not from the branch checked out here. On 2026-10-04 six were started
  while work sat on a feature branch 26 commits ahead of `main`, and every one began on stale code. Get the
  work onto `main` (or tell each agent its base) before fanning out.
- **Fabric's `ClientPlayConnectionEvents.DISCONNECT` runs on the NETTY thread** (`Connection.channelInactive`, both
  Fabric API versions), concurrently with the render thread's `clearLevel`, where Fabric walks every loaded chunk's
  block-entity map. The Chunk Cache released chunks from that listener and crashed 26.2 on leaving the sim (NPE "this.wrapped
  is null" in fastutil's iterator, about one full sim run in two). Anything a DISCONNECT listener does to the world or
  client state goes through `client.execute(...)` (fixed 2026-10-05, 3f3fa8ad).
- `Level.isLoaded(pos)` is false for any y outside the level's build height (Hypixel's dungeon world starts at
  y 0). Ask load questions at a y clamped into `level.getMinY()..getMaxY()`.
- The room database (`RoomDatabase`) only loads when something calls `ensureLoading()` - the live map does so
  only inside a real dungeon. Anything that names rooms or votes on rotation outside one (Ashfall solo rooms, the
  sim) must start the load itself and must not judge rooms until `isReady()` - before it, the tile audit and the
  rotation vote judge good rooms broken.
