# killer560s-mod

A Fabric client mod for Hypixel Skyblock, mostly Catacombs dungeons: solvers, HUDs, timers, a dungeon map,
and (in one of the two build variants) automation. Personal toolkit that friends also use.

## Build and run

Java 25 and a JDK on `JAVA_HOME`. Gradle wrapper, Fabric Loom.

```
./gradlew build                     # legit variant  -> build/libs/killer560smod-1.1.0-26.1.2-legit.jar
./gradlew build -PcheatBuild=true   # cheat variant  -> build/libs/killer560smod-1.1.0-26.1.2-cheat.jar
./gradlew build -Prelease=true      # official release: compiles dev tooling OUT
./gradlew build [-PcheatBuild=true] -PtestingBuild=true  # killer560's private jar, ...-26.1.2-<cheat|legit>-testing.jar: Untested category, Mark tested, /k560tested; lists in testing/; deploy ONLY with --testing-instance-cheat/--testing-instance-legit NAME
python deploy-to-instances.py [--dry-run] [--jars-dir DIR]   # install into every Prism instance; writes minecraft/killer560smod-no-auto-update in each (Auto Update off there)
# --auto-update-instance NAME: leave NAME to the mod's own Auto Update (no jar, marker removed); none uses it (2026-10-24: all 7 have the marker)
```

The Minecraft version is in every jar name, and one gradle run builds one version. The deploy script keeps
each instance on its MC version and installed variant, stages `.jar.pending` for a running one, and prints
each instance's BuildVariant md5; point `--jars-dir` at a folder holding the other version's jars.

**Releases.** Every jar carries `killer560smod-build.properties` (commit, minecraft, variant, testing, release) for Auto
Update. The Beta channel is dev-build.yml's "Latest Dev Build" (built `-Prelease=true`, with `SHA256SUMS.txt` and
`commit.txt`). A full release is made by hand: commit everything, build the four jars with `-Prelease=true` (clearing
`build/generated/sources/buildVariant` and `build/classes` between them, as dev-build.yml does) into one folder, run
`python tools/release-assets.py <folder>`, and attach the four jars plus `SHA256SUMS.txt` and `commit.txt` to the
release. Auto Update ignores a release without both files, and every release before 2026-10-11.

Both variants come from the same source. `build.gradle` generates
`com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED` from `-PcheatBuild`, and `DEV_TOOLS` from
`-Prelease`. Because they are `static final boolean`, javac folds every `CHEAT_FEATURES_ENABLED && x` down
to `false` in a legit build and deletes the branch, which is the point.

Versions, all from `gradle.properties` (check there, not here, if something looks off): Minecraft 26.1.2,
Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Loom 1.17-SNAPSHOT, Mod Menu 18.0.0, mod version 1.1.0,
Netty is not in `gradle.properties`: `build.gradle`'s `nettyByMinecraft` picks the Jar-in-Jar pair for the Proxy Client
(4.2.7.Final for 26.1, 4.2.15.Final for 26.2) and the build re-checks it against Minecraft's own.
`fabric.mod.json` declares `minecraft ~<major.minor>` (filled in from `minecraft_version`), `java >=25`, entrypoints `client` and `modmenu`.

## Testing

Boot-test only in the Prism instance **26.1.2 (Mod Only Test)**, and close the game afterwards. The
instance **26.1.2 (Dungeons)** is him actually playing — never install a test jar there, never boot it, and
never draw a conclusion from its log. A finished, tested batch does go there with every other instance
(`deploy-to-instances.py`, killer560 2026-09-27).

**Never use `prismlauncher.exe --launch`.** On 2026-09-30 it was given a folder a running Prism had not rescanned
and silently started one of his play instances instead. Boot-test through the testkit (it starts its own client), or
ask him. Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` - kill only
the PID you launched, and leave a client whose log shows a server join (he took the window).

**Instances** (since 2026-10-07; enumerate the directory, never trust this list - it has been wrong before):
`26.1.2 (Dungeons)` (his play instance, cheat), `26.1.2 (Mod Only Test)` (cheat), `26.1.2 (SIM recording)` (cheat, checked
2026-10-24), `26.1.2 Legit`, `26.2 Cheat`, and the
private `26.1.2 Untested Cheat` / `26.1.2 Untested Legit`, which take only `-PtestingBuild` jars. `Title Screen GUI` (the main-menu preview instance, marker `killer560smod-title-screen-gui`, docs/TESTING-HOOKS.md) was
retired on 2026-10-16; it and eight older retired instances were deleted on 2026-10-26 (PC cleanup). Every
`options.txt` is a copy of Dungeons'. Mod Only Test
does not reach the instance he plays on: after work lands, check every instance by md5 and promote or delete any
leftover `.jar.pending` (on 2026-09-29 four were two builds behind). A new instance is invisible to a running Prism
until it rescans. 26.2 instances get the 26.2 jar (killer560, 2026-09-30); every jar name carries its MC version, and
each jar declares its own `minecraft` range on purpose - not a bug to widen away.

There is an anticheat harness at `C:\Users\Hunter\killer560s-mod-testkit` that runs features against a real
GrimAC on a real dedicated server. See its own `CLAUDE.md`. Harness system properties (network fakes/offline, no OS opens) are in **[docs/TESTING-HOOKS.md](docs/TESTING-HOOKS.md)**.

## Layout

Source under `src/main/java/com/killer560/hub/`, one package per feature area (`ap3`, `dungeonextras`,
`secrets`, `livemap`, `autopuzzles`, `terminals`, `leveraura`, `gui/tab`, `util`, …). Mixins live in a
`mixin` subpackage of the feature that owns them. Docs: `docs/FEATURES.md` holds the full text for every
feature; `README.md` holds the name list. Mining is shelved until after 2.0 (never compiled): **[shelved/mining/README.md](shelved/mining/README.md)**.

Shared pieces worth knowing: `util/ActionGate` is the mod-wide one-automated-interaction-per-tick arbiter
with actor priority; `util/SkyblockGate` is the "Skyblock Only" gate; `secrets/DungeonState` owns floor and
boss-phase detection; `util/ViewFreeze` holds the camera still while something rotates the real player.
Main menu pictures: he (or a theme's designer) paints them and we only animate them (killer560, 2026-10-16) -
`mainmenu/PictureScene` is the pipeline (texture, patches, ripples, overlays); `TargetScene` and `MilkyWayTexScene` are specs.

## Conventions

Loggers come from `util/ModLog.get("killer560smod-…")`, never from `LoggerFactory` directly. In a dev or
cheat build that hands back the real SLF4J logger; in a release (`-Prelease=true`, `DEV_TOOLS == false`) it
hands back one that drops TRACE/DEBUG/INFO/WARN and forwards only ERROR, so a release jar is quiet without
a thousand call sites being guarded. `roomsim/` and `bazaarflip/` were the last two packages still calling
`LoggerFactory` directly, and so the last two still logging in a release; both went through `ModLog` on
2026-09-30, so a release jar is now quiet everywhere.

Automation chat (killer560, 2026-10-16): a running auto says only that it started or stopped, a server correction and
a real failure; every progress line goes through `ModChat.log` (same words, logger `killer560smod-chat`, testkit LogTap
tag "(chat)"). The sim says only what Hypixel says, at Hypixel's pace.

Every config path goes through `util/ModPaths.config("killer560smod-<name>")`, never `FabricLoader.getConfigDir()`
directly: files live in `config/killer560/<category>/<feature>/` under their unchanged names, and the folder is
picked from the prefix table in `ModPaths` (add a row for a new feature, or it lands in `other/`). Code that lists
setting files uses `ModPaths.settingFiles()`. The client entrypoint's `ModPaths.migrateAll()` moves old root files in. `ModPaths.config()` does NOT
create the folder (since 2026-10-26): a save creates its parent first (`Files.createDirectories(path.getParent())` or
`ModPaths.forWrite(path)`), and a folder lister treats a missing folder as empty. `migrateAll` also moves
`ModPaths.RETIRED_NAMES` (removed features' files) into `retired/` (never deleted, never in a profile), sweeps
`FORMER_FOLDERS` (now including `other/`) with a WARN for a stale copy it cannot move, and removes empty category and
feature folders. `MapLogger` keeps the newest 20 map logs.

Every setting must survive a restart: add the field, load it, save it, and expose a getter and setter. A new config
class's `load` also goes in `ProfileManager.reloadAllConfigs`, or applying a profile is undone by its next save (testkit
320 names the class; Pack Disabler missed it, 2026-10-07).
Any other file or folder a feature keeps under `config/killer560/` (named `killer560smod-*`) rides in profiles by default
(`profiles/ProfileData`, since 2026-10-13): if it is a secret, cache, download, log or history, add it to
`ProfileData.EXCLUDED_NAMES`; if the feature holds it in memory, add its re-read to `ProfileManager.refreshDerivedState`.
A new feature gets its name in the README list and its full text in `docs/FEATURES.md`, then the features
Google Doc is regenerated. Sharing and receiving settings default ON. The GUI is orange-themed. Never carry
his typos into a command, label or alias.
Spelling is AMERICAN in everything the mod shows or writes (labels, tooltips, chat, HUD, logs, testkit matches,
the Discord posts and the features Google Doc): color, center, gray, armor, defense, canceled, optimized.
Game and Hypixel text stays as the game writes it, and so do config keys, saved-file names and anything the code
matches (killer560, 2026-10-24: "should be american not british").
Cringe lines update with the mod (`cringe/CringeLines.java`): `cringe-lines.txt` carries `#k560` lines, a hash stamp of the
built-in list and the hash of every built-in line ever offered, so a new built-in line is added on the next load and one
the player deleted never comes back. Edit the built-in list freely; there is no counter to bump.
Croesus run memory (`croesus/CroesusRunMemory.java`) never guesses: a run it cannot place reads "not looked at", never
hidden and never another run's state. A run moves later only by as many joins as the time since it was seen allows, it
crosses into a later page only behind a join that page's own runs show, and a head at the front of page 1 that fits no
remembered run is a new run (testkit 888, 906-908).
A setting that steps through values is built with `SettingsButtonWidget.cycling(label, (btn, dir) -> ...)` and its
`step` helpers: left click forward, right click back, wrapping (killer560, 2026-10-11); testkit 806 finds them by `isCycling()`,
which since 2026-10-20 is true only for `cycling(...)`: a plain `builder(...).secondaryPress(...)` (Door Keys' colour rows,
right click = Auto) is not a cycler, and 806 treating it as one opened the colour picker and failed every row after it.
A tooltip key is its label, and labels are also built outside gui/ (config enum labels such as
`TerminalSolverConfig.OverlayColor`, `ScoreboardEditorScreen`, `InventoryCustomizeScreen`): rename both together, or the
hover vanishes (1c10b812 renamed the keys to British and missed those). Testkit 513 compares every hover with its fixture
plus `menu-layout-merged.json`, and 511 every tab: an intended change goes in that file the same day.

Menu placement (killer560, 2026-10-07): every feature is its own tab (a card in the Option A menu, 2026-10-12) in its
category - no combined folders (only Secrets and Puzzle Solvers group tabs, at his request). Cheat-only tabs go in one
block at the BOTTOM of their category (the menu draws it under a red "Cheat" header), never directly under the legit tab
they go with, and cheat-only settings go at the bottom of their tab under a red `SectionHeaders` header. The categories
are put together in `gui/tab/MenuCategories`; a new tab still goes in its old folder class (`DungeonTab`, `ItemsTab`...).
Order (killer560, 2026-10-14: "sort them alphabetically or something of your choosing"): inside a category the legit cards
are alphabetical by display name ignoring case, then the cheat block alphabetical too (`MenuCategories.cheatLast` does it,
so a new tab needs no position); Secrets and Puzzle Solvers sort their own sections the same way (Solver Highlights, once first, was removed 2026-10-18; each solver has its own highlight rows).
Inside a tab the feature's on/off switch is the first row. A tab that is really several features side by side (Dungeon Queue)
has no switch over the whole: each feature keeps its own, and the tab lists them in `BaseTab.masterToggles` so the card
(`gui/MasterSwitch`) is ON while any is on and OFF only when all are; never add a "X as a whole" row that gates independent
switches (the 2026-10-13 ones were removed 2026-10-14, files with the old key OFF load with every part OFF). Testkit 515-518.
Testkit 511-514 check it on cheat, legit and testing jars.

Never track a `HANDOFF.md` in the repo; handoff notes live only in
`C:\Users\Hunter\.claude\killer560s-mod-HANDOFF-PRIVATE.txt`. The Discord bot token lives at
`C:\Users\Hunter\.claude\secrets\killer560smod-discord-bot-token.txt` and is never printed or committed.
The features Google Doc **edit** link is never published; only the `/e/2PACX-…` published link is public.
A Discord update covers three places: #changelog (mod), #roadmap (edit the pinned message), and
#testkit-updates (1553951359810670692) for testkit changes - the last was missed for a week until 2026-10-05.

## Safety rules for automation

These are his, they are about getting banned, and they are not negotiable. Never write position or velocity
directly (`setPos`, `setDeltaMovement`) and never use fractional movement input — discrete key presses only.
A server correction is always honored (never fought) but never stops anything: see the first lesson below. Never clamp or wrap simulated yaw at 0-360; Hypixel treats an uncapped
running rotation value as the normal signal. Pitch is always kept within -90..+90. Anticheat *deception*
features: on 2026-10-25 he asked for Blink, Inventory Walk and Zero Ping Etherwarp and had them built and shipped
(cheat build only, off by default; blink-1025). Zero Ping Etherwarp stays CAMERA ONLY (his decision 2026-10-25; in both builds since 2026-10-26): the
character is never moved client-side ahead of the server, so the setPos rule above stands. Blink AP3 is not built.

(Added 2026-10-20: the working rules that now apply. Each is in the handoff file too, which is the fuller record.)

(Added 2026-10-26, killer560:)
- After-2.0 (staging) features are NEVER shipped - not to main, a jar, an instance or GitHub - until 2.0 is pushed.
  "Under no circumstances." Only dungeon/main work ships before 2.0.
- Up to 9 testkit clients may run at once (slot waits: fewer than 9 `fabric.dli.env=client` java.exe processes).
- A finished, tested main batch is deployed to EVERY instance, 26.1.2 (Dungeons) included (he said yes, 2026-10-26).
  Test or testing jars still never go into Dungeons, and it is never booted by us.

- Push only when no game is running (`(Get-Process javaw -ErrorAction SilentlyContinue | Measure-Object).Count` is 0).
  With a game running, commit but do not push; a jar that must reach a running instance is staged as `.jar.pending`.
  The testkit client runs as `java.exe`, not `javaw` (run-scenario.ps1), so count that too; Gradle daemons are `java.exe`
  with no window title and do not count.
- Parallel agents work in their own worktrees, commit by file name, and do not push. Cherry-picks onto `release-1019`
  conflict when the branch was based on an older commit: rebase the agent's branch first.
- Trap rooms (New Trap, Old Trap) are not functional yet; leave them out of tests until the owner says otherwise.
- The `/trades` menu is a container titled "Trades"; Auto Sell's default title pattern matches "sell" or "trade", and a
  saved old pattern is migrated on load.
- A Shadow Assassin leap blocks right-click on the main server; a route waiting on a chest click must retry on a real
  signal, never freeze.

Two topics have their own files, because they had grown to half this one:
**[docs/SIM.md](docs/SIM.md)** for the dungeon sim (`roomsim/`) - room captures, floor generation,
secret placement, doors and altitude - and **[docs/AP3.md](docs/AP3.md)** for AP3's nodes and align
physics. Read the relevant one before touching either area. Feature-specific lessons (Bazaar, HUD
elements, Superpairs, Instant Transmission, item identity, gametest, `AttackBlockCallback` per tick, slider clamps) are in
**[docs/LESSONS.md](docs/LESSONS.md)**; automation runtime ones (Auto Routes on GrimAC, chain timing, ViewFreeze, key
range) in **[docs/LESSONS-AUTOMATION.md](docs/LESSONS-AUTOMATION.md)**. GUI, HUD and rendering ones are in **[docs/LESSONS-GUI.md](docs/LESSONS-GUI.md)**. Sim lessons (the sim answers as the server, solver scans, heights, room answers, sim RNG) are in **[docs/LESSONS-SIM.md](docs/LESSONS-SIM.md)**. The Room Recorder was removed on 2026-10-04 and lives at git tag
`room-recorder-last`; the last section of docs/SIM.md says how to restore it.

- **Nothing in this mod ever stops, pauses or disables on a server correction** (killer560, 2026-10-06): it calls
  `util/ServerCorrections.report` (chat line + `ModSounds.playCorrectionAlarm()`) and carries on from the corrected position.
- `LOGGER.debug` never reaches his log. Minecraft's root log4j2 level is INFO, and a real client log
  (`26.1.2 (Dungeons)`, 29,596 lines, 2026-09-29) contains zero DEBUG lines. So a `.debug` call is not the
  spam and deleting one buys nothing; when hunting log noise, hunt `.info`. That is where it all was: the
  2026-09-29 trim cut INFO call sites from 783 to 273 and left WARN (276) and ERROR (86) alone.
- A reach check belongs at the one place the interaction is SENT, not in each caller. Simon Says had four
  callers and a check in one of them; three paths sent clicks from up to 30 blocks away for months.
- `"^(?:.*something.*|...)$"` is NOT anchored. The leading `^` buys nothing when the alternative starts with
  `.*`, and the Blood Key split read that way for months. Anchor on the real line shape, with a
  `[A-Za-z0-9_]{1,16}` name group, and check with `matches()`.
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
  `8e95469d`=cheat+dev, `31a271c1`=legit+dev, `9d814aa3`=legit+release, `8193ef73`/`84d195e7`=cheat/legit testing
  (since `BuildVariant.TESTING`, 2026-10-07; before it `c7d5d8f5`/`1d821df5`/`f8fc20a1`). `javap -constants` on that class is
  the unambiguous check if a hash ever disagrees - it prints the booleans directly.
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
- **Never write a Minecraft API call from memory - grep for a call site in this repo first.** Method names and some
  block/sound constants differ between 26.1.2 and 26.2 (`snapTo` not `moveTo`, `McBlocks.RED_WOOL`, holder vs plain
  `SoundEvent`); copy the shape of an existing use. Examples, and the cloud-session `javac` filter that catches
  structural errors without the Minecraft jar: [docs/COMPILING.md](docs/COMPILING.md).
- **A mixin that compiles on 26.2 can still crash 26.2 at startup.** Mixin descriptors are only checked when the
  game loads, so `@Inject(method = "extractRenderState")` on `Gui` with 26.1.2's `(GuiGraphicsExtractor, DeltaTracker)`
  built fine and crashed every 26.2 launch (26.2's is `(DeltaTracker, boolean, boolean)`). Seven overlays did it until
  2026-10-04; they are now Fabric HUD layers in `hud/GuiOverlays`. A 26.2 change is not done until a 26.2 boot ran:
  the testkit's `run-scenario.ps1 -Scenario smoke -Minecraft 26.2` does it unattended.
- A mixin class that is not listed in its `*.mixins.json` compiles, ships in the jar and never runs - no warning at all.
  On 2026-10-26 a Bash heredoc that should have added six FrameStats mixins to `killer560smod-lagdisplay.mixins.json` aborted
  on a quoting error and the jar measured nothing; testkit 1026-stutter-hooks caught it by asserting every hook fired.
  After adding a mixin, check the json in the built jar (`unzip -p <jar> <name>.mixins.json`) or a test that it acted.
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- Forwarding a self-registered client command name to the server recurses through Fabric's command API and
  StackOverflows. Send below the dispatcher via `util/ServerCommands.toServer`.
- When sweeping for features that tick on the wrong event, resolve the **called classes**, not per-file: a
  feature is often ticked from a lambda in another class entirely (`CheatUtils` ticks Secret Aura, Auto Ult and
  Chocolate Factory; `PathfindingFeature` ticks the soul runner and pearl hopper). A per-file grep missed seven
  of them and the gap only surfaced as `Post` violations in a later test.
- Reach must be measured to the block's **box**, not its center — the center reads up to half a block
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
- **A fix applied to one of a set is the thing to go looking for.** The 2026-09-30 audit found four, all of
  the same shape: the Wither Key line was anchored and the Blood Key line beside it left on `contains()`;
  `Floor7Tracker` and `LeverAura` got the `[A-Za-z0-9_]{1,16}` name group and `Ap3Feature`'s copy of the same
  line kept `(.{1,16})`; three reach CONSTANTS were tightened to 4.5/3.0 and the MEASURES under them left on
  center/feet (the javadoc describing the fault sat directly above the code still doing it); the cooldown
  setter above. When fixing one instance, grep the sibling call sites and fix the set, and update the comment
  that claims they match - two of these said "the same regex as X" after they had stopped being that.
- `RouteExecutor.stop()` is already a real cancel - it drops the step machine, `releaseKeys()` zeroes the
  want-flags the input mixin reads, `RouteRotation.clear()` releases the camera, and every per-node buildup
  (breaker queue, boom snapshot, swap/await state) is rebuilt by `beginAction`. BOOM and BREAKER send their
  START and ABORT in the same tick, so nothing is left open server-side either. What it does NOT clear is
  `stoppedByUser`/`justFinished`, and both make `AutoRoutesFeature` latch instead of arming *while the player
  stands inside a node* - which is exactly where a map warp puts him. Clear them whenever something other than
  the player cancels a route. Anything that fires a node on its own (the breaker re-fire) must honor `stoppedByUser`
  too: counting it as a mere latch restarted a breaker the tick after his strafe stopped it (96-ar-breakerwait, 2026-10-12).
- A movement key held when an Auto Routes node fires is the walk that got him there, not a takeover. The input
  mixin used to return before installing the route's input whenever any movement key was down, so walking onto
  an `/ar add ew start` node never got the etherwarp's sneak out and timed out (and a driven route stopped on its
  first tick) - traced in the code 2026-10-04. `RouteExecutor.onInputTick` now overrides keys held since the route took over and treats only a
  press after a release as "you moved" - AP3's align rule. (His sim etherwarps not firing was a different cause - the
  sim ignored raw use packets - and is fixed by the rule below, not by a branch.)
- Agent worktrees are cut from `main`, not from the branch checked out here. On 2026-10-04 six were started
  while work sat on a feature branch 26 commits ahead of `main`, and every one began on stale code. Get the
  work onto `main` (or tell each agent its base) before fanning out.
- An agent "started in" the parent's batch worktree must not branch or build there: on 2026-10-27 the etable agent ran
  `git checkout -b` in `worktrees/batch-1026` while the parent used it, so the parent's merge landed on the agent's branch
  and its build compiled the agent's edits. An agent makes its own `git worktree add` before its first edit.
- A run's output piped through `| tr -d '\r' > file` never ends: the Gradle daemon run-scenario starts inherits the pipe,
  so `tr` waits forever and the script never reaches its next line (2026-10-27). Redirect straight to the file instead.
  A menu case is selected with its session first: `-Scenario '200-menu-session:,2783-menu-...'`; without it 0 cases run.
- **Fabric's `ClientPlayConnectionEvents.DISCONNECT` runs on the NETTY thread** (`Connection.channelInactive`, both
  Fabric API versions), concurrently with the render thread's `clearLevel`, where Fabric walks every loaded chunk's
  block-entity map. The Chunk Cache released chunks from that listener and crashed 26.2 on leaving the sim (NPE "this.wrapped
  is null" in fastutil's iterator, about one full sim run in two). Anything a DISCONNECT listener does to the world or
  client state goes to the render thread (fixed 2026-10-05, 3f3fa8ad). But a `client.execute` task from DISCONNECT can be
  DROPPED: a pause-menu leave closes the channel, then `Minecraft.disconnect` begins with `dropAllTasks()` (javap 26.1.2).
  SimWorld leaves a flag its END_CLIENT_TICK drains (5a1afa62; 97-sim-leave-build lost 5 of 5 resets to the task alone).
- A spelling sweep can merge map keys: six British/American tooltip key pairs ("color", "border color", ...) became one key
  each and the later entry silently replaced the earlier (a92e0357). After renaming text, check keyed maps for duplicates.
- Merging branches that each edit `docs/FEATURES.md` left 11 features listed twice, each copy holding a change the other
  lacked (2026-10-24, fixed in 8dcac505). After such a merge, check FEATURES.md for a feature named twice before the Doc regen.
- Post-2.0 staging (2026-10-25): farming, mining, slayer, rift, fishing and island work lives on unpushed `staging-*` branches
  (`staging-combined` merges them). Automation is cheat-block only, default OFF. `ModPaths.settingFiles()` also lists `other/`,
  and import keeps the locked update and sharing keys (testkit 981).
- A Fabric event's first non-PASS listener wins. `/ar edit db`'s right clicks lost to the sim's ability hook (a held
  Superboom detonated, no block picked) until its `UseBlockCallback` moved to a phase ordered before the default. The sim's
  client-side essence listener lost the same way with any ability item held, so Secret Aura never collected essences
  (2026-10-05): sim secrets are answered on the SERVER now, and the essence is Hypixel's profiled player_head (99-sim-essence-aura).
- A reference mod's feature can already be here under another name: OdinClient's "Secret Hitboxes" is this mod's Full Block
  (Secrets folder, cheat build, testkit 62-full-block-reach / 1025-fullblock-*), though reference-mods-1018 said "no
  equivalent" and a duplicate was half built on 2026-10-25. Grep docs/FEATURES.md for the behavior, not the name, first.
- Menu tabs share `BaseTab.onOff`/`onOffButton` (cleanup-gui, 2026-10-25): a tab with its own `private static onOff` no longer
  compiles ("cannot override ... in BaseTab"); three tabs written on main beside the cleanup broke the merge that way. Use BaseTab's.
  The cleanup also dropped imports a tab no longer needed (`SettingsButtonWidget` in QuizSolverTab, `DungeonChestValuer` in
  CroesusTab), so an older branch adding a row there fails with "cannot find symbol" after a clean text merge (batch-1026,
  clickguards-1025). Write such rows with `onOffButton` and compile every variant after the merge.
- Docs are split by topic since docs-condense (2026-10-25): LESSONS.md, LESSONS-AUTOMATION.md, LESSONS-GUI.md and SIM.md are
  indexes to LESSONS-{AUTOCLEAR,ROUTES,AUTOMATION-PUZZLES,DUNGEONS,HUD,MENU,MINECRAFT,PERF}.md and SIM-*.md. Add a lesson to the
  topic file; a branch that appends to an old monolith conflicts on merge.
- Titles and subtitles have ONE hook: `terminals/mixin/TerminalQolTitleMixin` (Gui.setTitle/setSubtitle on 26.1.2, Hud on 26.2, one
  copy per version). Terminal QoL's Hide Completion and P3 Status both run there; add to it, never a second title mixin. A
  test reads the shown text from the private `title`/`subtitle` fields of Gui (26.1.2) / Hud (26.2) (testkit 2141).
  The dungeon-gaps survey called subtitles unhandled because it grepped for the packet class (2026-10-25).
- Blink's keys are read under a container menu (a terminal) since 2026-10-26; before, opening one mid-hold read as the
  key coming up and released the hold. Rework Grim findings (banked turns, Post on dropped releases, the in-place
  teleport conflict) are in docs/LESSONS-AUTOMATION.md, Blink section.
- Blink logs every event as one `[Blink]` INFO line through `blink/BlinkLog` (logger `killer560smod-blink`, last 40 kept
  for `/blink debug`). A release jar (`-Prelease=true`, the GitHub builds) drops INFO, so that logger is
  `ModLog.keptWhen(...)`: it logs in a release only while Blink's Debug Log setting is on (testkit 1026-blink-release-log).
  His own jars (deploy-to-instances.py, built without -Prelease) always log. Stress-pass lessons (burst skipping Charge,
  settling after a teleport, latches across a rejoin): docs/LESSONS-AUTOMATION.md, Blink section.
- `ModPaths.config()` made the parent folder on every lookup, and most features look up from a static initializer, so
  each boot made ~85 empty folders (cheat ones in legit instances). Since layout-fixes-1026 only a write makes a folder;
  a new writer must create its parent, and an "Open Folder" button gets it from `ExternalOpen.path` (testkit 1026-layout-empty).
