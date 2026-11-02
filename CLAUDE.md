# killer560s-mod

A Fabric client mod for Hypixel Skyblock, mostly Catacombs dungeons: solvers, HUDs, timers, a dungeon map,
and (in one of the two build variants) automation. Personal toolkit that friends also use.

## Build and run

Java 25 and a JDK on `JAVA_HOME`. Gradle wrapper, Fabric Loom.

```
./gradlew build                     # legit variant  -> build/libs/killer560smod-1.1.0-26.3-legit.jar
./gradlew build -PcheatBuild=true   # cheat variant  -> build/libs/killer560smod-1.1.0-26.3-cheat.jar
./gradlew build -Prelease=true      # official release: compiles dev tooling OUT
./gradlew build [-PcheatBuild=true] -PtestingBuild=true  # killer560's private jar, ...-26.3-<cheat|legit>-testing.jar: Untested and Bugged categories, Mark tested / Mark bugged, /k560tested; lists in testing/; deploy ONLY with --testing-instance-cheat/--testing-instance-legit NAME
python deploy-to-instances.py [--dry-run] [--jars-dir DIR]   # install into every Prism instance; writes minecraft/killer560smod-no-auto-update in each (Auto Update off there)
python tools/verify-artifact.py JARS_OR_DIRS [--git REPO]    # secret scan; deploy-to-instances.py, release-assets.py, dev-build.yml and killer560s-mod-logs/verify_gate.py all run it and stop on exit 1
# --auto-update-instance NAME: leave NAME to the mod's own Auto Update (no jar, marker removed); none uses it (2026-10-24: all 7 have the marker)
```

**Minecraft versions (killer560, 2026-11-01): 26.3 is the main version, 26.2 the second, 26.1.2 is dropped** (no more builds;
`src/mc26_1` is gone). Every change must build and run on both. The two exact command lines, with any of the flags above added:

```
./gradlew build                                                                                       # 26.3 (gradle.properties)
./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.160.0+26.2 -Pmodmenu_version=20.0.2  # 26.2
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

Versions, all from `gradle.properties` (check there, not here, if something looks off): Minecraft 26.3,
Fabric Loader 0.20.1 (the stable one Fabric meta lists for 26.3; the jar itself still declares `fabricloader >=0.19.3`, which is
also what Fabric API 0.165.0+26.3 needs), Fabric API 0.165.0+26.3, Loom 1.17-SNAPSHOT (resolves 1.17.21, builds 26.3 fine),
Mod Menu 21.0.0 (declares minecraft >=26.3-; 20.0.x stops below 26.3), mod version 1.1.0. 26.2 keeps Fabric API
0.160.0+26.2 and Mod Menu 20.0.2 through the -P overrides above.
Netty is not in `gradle.properties`: `build.gradle`'s `nettyByMinecraft` picks the Jar-in-Jar pair for the Proxy Client
(4.2.16.Final for 26.3, 4.2.15.Final for 26.2, both read off Mojang's version manifests) and the build re-checks it against
Minecraft's own.
`fabric.mod.json` declares `minecraft ~<major.minor>` (filled in from `minecraft_version`: `~26.3` / `~26.2`), `java >=25`, entrypoints `client` and `modmenu`.

## Testing

Boot-test only in the Prism instance **26.3 Mod Only Test** (26.1.2 (Mod Only Test) until 2026-11-01), and close the game afterwards. The
instance **26.3** (26.1.2 (Dungeons) until 2026-11-01) is him actually playing — never install a test jar there, never boot it, and
never draw a conclusion from its log. A finished, tested batch does go there with every other instance
(`deploy-to-instances.py`, killer560 2026-09-27).

**Never use `prismlauncher.exe --launch`.** On 2026-09-30 it was given a folder a running Prism had not rescanned
and silently started one of his play instances instead. Boot-test through the testkit (it starts its own client), or
ask him. Never swap a jar over a running game; stage it as `.jar.pending`. Never blanket `taskkill javaw` - kill only
the PID you launched, and leave a client whose log shows a server join (he took the window).

**Instances** (remade 2026-11-01 when 26.3 became the main version; enumerate the directory, never trust this list - it
has been wrong before). Only our mod and Fabric API in each, no other mods (killer560, 2026-11-01). `26.3` (his play
instance, cheat: Minecraft options only, no mod settings), `26.3 Default` (cheat, Minecraft options only and no mod config,
to see what the mod turns on from first download), `26.3 Mod Only Test` / `26.2 Mod Only Test` (cheat; 26.3's carries all of
26.1.2 Mod Only Test's mod config, 26.2's that of `26.2 Cheat`), `26.3 Legit` / `26.2 Legit` (settings from 26.1.2 Legit), and
the private `26.3 Untested Cheat` / `26.3 Untested Legit`, which take only `-PtestingBuild` jars. No SIM recording instance.
Records (profit, run history, stats) are in the shared store, not in any instance. The 26.1.2 instances (Dungeons, Mod
Only Test, SIM recording, Legit, Untested Cheat/Legit) and `26.2 Cheat` stay until he has checked the new ones; their
mod config is backed up in `killer560s-mod-logs/instance-backups-1101/`. `Title Screen GUI` (the main-menu preview instance, marker `killer560smod-title-screen-gui`, docs/TESTING-HOOKS.md) was
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
feature; `README.md` holds the name list. Mining is shelved until after 2.0 (never compiled): **[shelved/mining/README.md](shelved/mining/README.md)**,
except the seven pre-2.0 mining features he asked for (to-do Doc, 2026-11-01) in `miningqol/` and the menu's Mining category.

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
2026-09-30, so a release jar is now quiet everywhere. Since 2026-11-01 every `ModLog` logger is wrapped (`RecordingLogger`) so Auto Report Bans'
`util/SessionLogTap` can keep the session's INFO+ lines in memory while that opt-in switch is on (also in a release); a logger
whose lines quote other players (chat, Mod Chat, party, friends) must be added to `SessionLogTap.SKIP`.

Automation chat (killer560, 2026-10-16): a running auto says only that it started or stopped, a server correction and
a real failure; every progress line goes through `ModChat.log` (same words, logger `killer560smod-chat`, testkit LogTap
tag "(chat)"). The sim says only what Hypixel says, at Hypixel's pace.

RECORDS are not settings (shared-stats-1101, killer560 2026-11-01: profit and stats "combinable" across instances): the
Croesus claim log and run memory, RNG Meter item log, Experiments and Bazaar Flip profit, NPC sale count, run history,
best friends and egg hunts live in ONE store for every instance on the PC, `%USERPROFILE%\.killer560smod\shared\`
(`accounts/<uuid>/` for one player's own), listed in `shared/RecordCatalog`. Read and write a record only through
`SharedRecords.file(kind)` + `SharedRecords.load/save`: a save takes an OS file lock and three-way merges onto the disk
(counters add, keyed entries union, what this instance removed stays removed), so two games never overwrite each other. A
new record type gets a `RecordCatalog.Kind` with its merge policy (which numbers are COUNTERs, how array entries are keyed).
An instance's old file is folded in on first use (a byte-identical copy never twice) and moved to
`<game dir>/killer560smod-records-moved/`. A testkit client gets a sandbox store in its game folder, emptied at start.
`tools/shared-records/run-test.sh` tests the merge and two JVMs at once; `combine.sh` folds Prism instances in by hand.

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
320 names the class; Pack Disabler missed it, 2026-10-07). That includes a class that is not a `*Config` singleton: one that
reads its file once behind a `loaded` flag needs a public `reload()` that puts the defaults back first and then reads (a
profile without the file deletes it). Streamer Mode, Legit GFS, the sim loadout, Cape and the sim filters were missing until
2026-11-02; 320's name audit now reads every class in the jar and fails on any that names a profile setting file without
ProfileManager naming the class (`ProfileReloadCases.NAME_ALLOWED` holds the few reasoned exceptions).
Any other file or folder a feature keeps under `config/killer560/` (named `killer560smod-*`) rides in profiles by default
(`profiles/ProfileData`, since 2026-10-13): if it is a secret, cache, download, log or history, add it to
`ProfileData.EXCLUDED_NAMES`; if the feature holds it in memory, add its re-read to `ProfileManager.refreshDerivedState`.
A plain `killer560smod-*.json` file is a SETTING file to the profile code whatever it holds, so a data `.json` (orders, caches,
records) goes in `ProfileManager.EXCLUDED_FILES`, or a profile apply copies an old copy in or deletes it; until 2026-11-02 the
`.json` names in `ProfileData.EXCLUDED_NAMES` did nothing (ProfileData only judges data entries; `isProfileSettingFile` honors them now).
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
Every mod text box is a `gui/CommitEditBox` (`ThemedEditBox` extends it), never a bare `EditBox` (killer560, 2026-11-01:
"any text box I type into if i press enter should save that value ... If it doesnt have a set button add it as well"):
wire Enter with `pressOnEnter(<its Set/Save/Add button>)`, `onEnter(<the save method>)` or `enterRunsResponder()`, and a
box with no commit button gets one from `CommitEditBox.withSet(widgets, box)`. Search boxes / live filters, chat inputs and
sign screens stay unwired. Testkit 1101-ui-textbox-enter types into every reachable box, presses Enter, checks the disk.
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

A setting that flags is not shipped with that value (killer560, 2026-10-27: "if a setting is flagging then it shouldnt be
pushed with that setting. SImple as that."). Test every new cheat setting at its HIGHEST value on GrimAC's default config
AND with `experimental-checks: true` (testkit: a checkout whose `run/testserver/config/GrimAC/config.yml` says so); a value
that flags is capped below or the setting removed (Arrow Align Frames Per Tick was dropped for MultiInteractA/B,
2026-10-27). Kept-teleport Blink flags are his one accepted exception.

(Added 2026-10-20: the working rules that now apply. Each is in the handoff file too, which is the fuller record.)

(Added 2026-10-26, killer560:)
- After-2.0 (staging) features are NEVER shipped - not to main, a jar, an instance or GitHub - until 2.0 is pushed.
  "Under no circumstances." Only dungeon/main work ships before 2.0.
- Up to 9 testkit clients may run at once (slot waits: fewer than 9 `fabric.dli.env=client` java.exe processes).
- A finished, tested main batch is deployed to EVERY instance, his play instance included (he said yes, 2026-10-26; that
  is `26.3` since 2026-11-01). Test or testing jars still never go into it, and it is never booted by us.

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
  `8e95469d`=cheat+dev, `31a271c1`=legit+dev, `9d814aa3`=legit+release, `3a71edd5`=cheat+release (same on 26.3 and 26.2, checked 2026-11-01), `8193ef73`/`84d195e7`=cheat/legit testing
  (since `BuildVariant.TESTING`, 2026-10-07; before it `c7d5d8f5`/`1d821df5`/`f8fc20a1`). `javap -constants` on that class is
  the unambiguous check if a hash ever disagrees - it prints the booleans directly.
- Mixin config uses `defaultRequire: 0`, so a wrong target signature fails **silently** and the feature
  just never runs. Verify targets with `javap` against the mapped jar in `.gradle/loom-cache/` before
  trusting a new mixin. A probe that silently counts nothing reports zeroes that read as findings.
- **Two Minecraft versions, one source tree.** `build.gradle` derives a compat directory from
  `minecraft_version` and puts exactly ONE of `src/mc26_3/java` / `src/mc26_2/java` on the source path (it
  prints "compatibility layer mcXX_Y" every build and throws for a version with no directory). 26.3 is the main version
  and 26.1.2 is dropped (2026-11-01). `src/main/java` is written in 26.3's CLASS names; a class 26.3 moved or renamed
  (the GPU API's `com.mojang.renderpearl.api.*`, `Enderman`, `RedstoneWireBlock`, `ServerboundPunchPacket`, authlib's
  `services.ProfileResult`) is listed in `src/mc26_2/remap.txt`, and a 26.2 build compiles a copy of src/main/java with
  those names rewritten (`remapMainSources`; its errors point into build/generated/sources/remapped/mc26_2/, same path and
  line). Only class names go there; a changed METHOD still goes behind a facade. Version-specific
  API lives in `com.killer560.hub.compat` - `McCompat`, `McBlocks`, `McItems`, `McEntities`, `McRender`, `McPackets`,
  `McInput`, `McWindow`, `McSoundInstance` - and
  **every version's copy must have identical public signatures**, or one version stops compiling. Nothing under
  `src/main/java` may touch an API that differs between versions. Build 26.2 with
  `-Pminecraft_version=26.2 -Pfabric_api_version=0.160.0+26.2 -Pmodmenu_version=20.0.2`.
  A mixin is the one thing the facade cannot cover, because `@Mixin(X.class)` is an annotation constant: a
  mixin whose target **class** moved gets one copy per version directory under the same name (eight do), while
  one where only the **method** name changed just lists both - `method = {"renderFire", "submitFire"}`. Mixin
  accepts a handler taking only `CallbackInfo` for any target (checked in `CallbackInjector$Callback.
  checkDescriptor`: the full descriptor OR `(LCallbackInfo;)V`, all-or-nothing, never a prefix), which is how a
  single mixin survives a target whose argument TYPES changed.
- **26.3 shaders need SPIR-V-ready GLSL** (2026-11-01): explicit `layout(location)` on every in/out, the separate-shader
  extension, `#include` for `#moj_import`, and DynamicTransforms in its new order; one bad shader takes down the whole
  resource reload and the client crashes at the first particle. Shaders live per version in `src/mc26_X/resources`
  (docs/LESSONS-GUI.md "The mod's GLSL shaders on 26.3").
- The testkit's synthetic clicks must use `InputConstants.MOUSE_BUTTON_LEFT` (1 on 26.3, 0 on 26.2), never a literal 0:
  with GLFW's 0, 26.3 smoke 303/304 failed "presses not landing" while the mod was fine (2026-11-01).
- **26.3 split the arm swing** (2026-11-01): no ServerboundSwingPacket; a left click sends `ServerboundPunchPacket` after the
  animation, a right click sends no swing at all. Swing through `McCompat.swingAttack` (left-click actions) or
  `McCompat.swingUse` (right-click actions), never `player.swing`. Packet getters became record accessors (`McPackets`).
  Details: docs/LESSONS-MINECRAFT.md "Packets, swings and entities on 26.3".
- **26.3 key and mouse codes are SDL, stored codes stay GLFW** (2026-11-01). On 26.3 `KeyEvent.key()`, `KeyMapping`
  values and `InputConstants.KEY_*` are SDL scancodes and mouse buttons are 1-based (left 1, right 3), and wrong numbers
  still compile. Configs keep GLFW numbers (`util/Keys`); read keys with `McInput.key(event)`, buttons with
  `McInput.button(event)`, poll stored codes with `KeyUtil`, window/cursor/display work through `McWindow`. A key capture
  stores `McInput.key(event)` too (Inventory Sorter's stored the scancode, saved as "none", UI 584). Full rules
  in docs/LESSONS-MINECRAFT.md "Input and window on 26.3".
- Run gradle through Bash, not PowerShell: PowerShell wraps native stderr in ErrorRecords and splits compiler
  messages mid-line, so error counts and file paths become unreadable. Gradle also prints compiler output
  TWICE, so `grep -c "error:"` is double - the `N errors` line javac prints is the authoritative number.
- **Never write a Minecraft API call from memory - grep for a call site in this repo first.** Method names and some
  block/sound constants differ between 26.1.2 and 26.2 (`snapTo` not `moveTo`, `McBlocks.RED_WOOL`, holder vs plain
  `SoundEvent`); copy the shape of an existing use. Examples, and the cloud-session `javac` filter that catches
  structural errors without the Minecraft jar: [docs/COMPILING.md](docs/COMPILING.md).
- **26.3 moved the player's packets out of `LocalPlayer.tick`.** On 26.2 the tick sends the input, sprint and move packets
  (sendPosition); on 26.3 `Minecraft.tick` calls the public `LocalPlayer.sendChanges()` after `ClientLevel.tickEntities` (javap
  26.3). Code that runs his tick by hand (Blink's shot, BlinkCharge's spent ticks) must call `McCompat.sendPlayerChanges(player)`
  right after `player.tick()`; without it every Blink shot on 26.3 moved him on the client only and the server set it back
  (63-ap3 26.3 76/21 -> 87/2, same 2 as 26.2; 2026-11-01). It also fixed ZPEW Aimed Warps n>=3 and Auto Routes' Charged
  Chain on 26.3 (BadPacketsJ: the spent ticks' tick-ends went with no move to report the use's look).
- **Blink Charge's banked turns leave vanilla's "last sent look" wrong.** A hand-run tick whose look equals it sends no
  move, so the server keeps the pre-turn look; `BlinkCharge.spendTick` sends the look itself when the wire one differs
  (zpew263-1101: one BadPacketsJ per aimed burst at 0 ms on 26.2 and 26.3 before it). Find such faults with the testkit's
  `WireCapture` (encoder-level, after Blink's holds), not `PacketTrace` (hand-off level, before them).
- **A mixin that compiles on 26.2 can still crash 26.2 at startup.** Mixin descriptors are only checked when the
  game loads, so `@Inject(method = "extractRenderState")` on `Gui` with 26.1.2's `(GuiGraphicsExtractor, DeltaTracker)`
  built fine and crashed every 26.2 launch (26.2's is `(DeltaTracker, boolean, boolean)`). Seven overlays did it until
  2026-10-04; they are now Fabric HUD layers in `hud/GuiOverlays`. A 26.2 change is not done until a 26.2 boot ran:
  the testkit's `run-scenario.ps1 -Scenario smoke -Minecraft 26.2` does it unattended.
- 26.3 removed `ItemInHandRenderer` (now `FirstPersonHandsAndItemsRenderer` + `FirstPersonHandsAndItems`), the
  `RenderSystem.output*TextureOverride` fields and `precompilePipeline`; the hand mixins have one copy per version dir
  and the GPU differences sit behind `McRender` (drawHandMask, pipelineCompiles, swingProgress, copySwing). Details and
  the replacement call sequences: docs/LESSONS-GUI.md "First-person hands and the GPU API on 26.3".
- remap.txt does not touch mixin STRINGS: a slash descriptor naming a moved class (RenderPipeline, GpuSurface) needs both
  versions' forms listed, and a handler whose arguments changed crashes at load whatever `require` says. Run
  `tools/mixin-descriptor-check.py` and `tools/mixin-handler-check.py` against each version after a port (LESSONS-GUI).
- A mixin class that is not listed in its `*.mixins.json` compiles, ships in the jar and never runs - no warning at all.
  On 2026-10-26 a Bash heredoc that should have added six FrameStats mixins to `killer560smod-lagdisplay.mixins.json` aborted
  on a quoting error and the jar measured nothing; testkit 1026-stutter-hooks caught it by asserting every hook fired.
  After adding a mixin, check the json in the built jar (`unzip -p <jar> <name>.mixins.json`) or a test that it acted.
- A class placed inside a mixin-owned package throws `IllegalClassLoadError` and crashes the game at boot.
  Keep helper classes out of `mixin` packages.
- **No `@Redirect`, `@Overwrite` or `@ModifyConstant` in this mod unless nothing else can do it** (killer560, 2026-11-01:
  "fix this cause it seems like a universal problem"). They take a call, method or constant for ONE mod: a second mod
  hooking the same spot either loses silently or, if its injector is require 1, crashes the game at boot. Use the forms
  that chain: `@WrapOperation` (call `original.call(...)` to keep the vanilla call), `@ModifyExpressionValue` (a call's
  result or a CONSTANT), `@ModifyReturnValue`, `@WrapWithCondition`, plain `@Inject`. Seen: Lunar Client's own chat mod
  `@Redirect`s ChatScreen's input-bar fill (require 1, priority 200) and our Peek Chat redirect won, so Lunar aborted with
  "Lunar Client Startup Error" (`Critical injection failure: Redirector lunar$chatMod$render$fill`; reproduced on Lunar 26.3
  as Guest, fixed by WrapOperation); Skysoft, Unlimited Chat History and Better Chat History hook ChatComponent's history
  constant 100 with require-1 `@ModifyConstant`s, and our `@ModifyConstant` there was the "chat history crash" (the
  first-applied one takes the constant, the later one is skipped: "@ModifyConstant conflict. Skipping ..."). All 32 were
  converted on branch mixincompat-1101. Lunar specifics: real Fabric Loader 0.19.5 inside Lunar's "Genesis/Ichor" launcher;
  `tools/lunar/lunar-mixin-scan.py` + `lunar-redirect-collide.py` list Lunar's redirects that collide with ours (jars in
  `%USERPROFILE%\.lunarclient\offline\multiver`). Lunar game log: `%USERPROFILE%\.lunarclient\profiles\<major>\logs\latest.log`
  and `ichor-boot.log`; launcher log `%USERPROFILE%\.lunarclient\logs\launcher\main.log`. Lunar boots as Guest (no login).
- A rule over OUTGOING commands needs two hooks: Fabric's `ClientSendMessageEvents.ALLOW_COMMAND` sees only typed ones,
  and the mod's own sends (`ServerCommands.toServer`: /f7 shortcuts, Party Commands, requeues) leave below it. Creation
  Cooldown's /joininstance block checks in both (doc-extras-1101). Chat filtering goes through vanilla's
  `ChatComponent.setVisibleMessageFilter` (Chat Tabs), not a ChatScreen redirect: docs/LESSONS-MINECRAFT.md "Chat and commands".
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
- Nickhider renames by WORD wherever text is drawn (`NameReplacer`), so any name added to its seen set is replaced in
  every string on screen. `namechanger/ChatNames` (2026-11-01) feeds it names from chat (friend/guild joins, party/guild
  chat, PMs, notices); each of its patterns is a whole anchored Hypixel line with a `[A-Za-z0-9_]{3,16}` group, because a
  loose one would turn an ordinary word ("Party", "Maxor") into a fake name everywhere. Testkit 1101-ds-nickhider.
- `/f7`, `/m7` and the rest are THIS MOD'S client-side shortcuts, not Hypixel commands - they expand to
  `/joininstance catacombs_floor_seven` etc. in `CommandShortcutsFeature.Shortcut`. So automation must send the
  `joininstance` form: `ServerCommands.toServer` deliberately sends below the client dispatcher, so it hands
  Hypixel the literal "/f7" and nothing is queued (found 2026-09-28).
  `/dh` and `/skyblock` ARE real Hypixel commands. Read the id off the enum rather than writing it out again.
- An "any key stops it" guard must ignore keys while `client.screen != null`. Otherwise the Return that submits
  the command starting the feature, and the Escape that closes the settings tab starting it, each stop it
  immediately - which reads as "I turn it on and it auto turns off". Name the key in the stop message too; "key
  pressed" cannot tell a walk from the feature killing itself.
  Since 2026-11-01 (killer560) no macro stops on "any key": every macro registers with `macrostop/MacroStop` (click
  macros: Escape or the Emergency Cancel Key; movement macros: also W/A/S/D/jump or the chat key, no screen open), which
  owns the one raw key/mouse hook. A new macro registers there; it does not get a key mixin of its own. The Emergency
  Cancel Key stops EVERYTHING automatic (killer560, 2026-11-01): automation with its own stop rules registers as
  `Kind.EMERGENCY`, and anything that acts by itself is held by `MacroStop.emergencyHeld()` (checked in `ActionGate`,
  the puzzle dispatcher, Auto Routes/AP3 starts, Auto I4). New automation must honor one of the two.
- **A fix applied to one of a set is the thing to go looking for.** The 2026-09-30 audit found four, all of
  the same shape: the Wither Key line was anchored and the Blood Key line beside it left on `contains()`;
  `Floor7Tracker` and `LeverAura` got the `[A-Za-z0-9_]{1,16}` name group and `Ap3Feature`'s copy of the same
  line kept `(.{1,16})`; three reach CONSTANTS were tightened to 4.5/3.0 and the MEASURES under them left on
  center/feet (the javadoc describing the fault sat directly above the code still doing it); the cooldown
  setter above. When fixing one instance, grep the sibling call sites and fix the set, and update the comment
  that claims they match - two of these said "the same regex as X" after they had stopped being that.
  Again 2026-11-01: the terminal Custom GUI's "hidden slots never hover" fix was never given to the Storage Overlay, so
  a click on blank overlay space picked his Spirit Sceptre out of the hidden slot under it. Any GUI drawn over a
  container now registers with `containercover.ContainerCover` (full screen or a per-frame panel rect); docs/LESSONS-GUI.md.
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
- On 26.3 the world render events (AFTER_TRANSLUCENT_*) run inside an open render pass, so a first-time glyph bake from
  `font.width` there throws and FeatureGuard disables the feature (Auto Routes, AP3, 2026-11-01). Measure world text with
  `McRender.textWidth`, never `font.width`; no texture uploads in those events. docs/LESSONS-GUI.md has the detail.
- 26.3's `RenderPipelines.LINES_SNIPPET` has no color target, so a mod pipeline built from it crashed the game on its first
  draw (Secret Waypoints, 2026-11-01). Build it as `McRender.linesColorTarget(RenderPipeline.builder(LINES_SNIPPET))`.
- A new movable HUD element draws in game only if its id is in `HudInGameRenderer.UNDRAWN_ELEMENT_IDS`; registering it only
  lists it in the HUD editor (missed for Advanced Position, Blink and the mining HUDs; docs/LESSONS-HUD.md).
- A spelling sweep can merge map keys: six British/American tooltip key pairs ("color", "border color", ...) became one key
  each and the later entry silently replaced the earlier (a92e0357). After renaming text, check keyed maps for duplicates.
  A branch can do it too: aafast-1027 added a new "arrow align/aura clicks per tick" tooltip (1-7) above the old one (1-4),
  and the old one, later in `SettingTooltipsData`, won (2026-10-27). After a merge: `grep -o 'd\.put("[^"]*"' | sort | uniq -d`.
- Merging branches that each edit `docs/FEATURES.md` left 11 features listed twice, each copy holding a change the other
  lacked (2026-10-24, fixed in 8dcac505). After such a merge, check FEATURES.md for a feature named twice before the Doc regen.
- **Never open or sign into another mod's own server unless its owner is fine with it.** NoammAddons' owner asked us to
  stop authenticating into `ws.noamm.org` (2026-11-01); the NoammAddons bridge was removed and `util/ModNet.forbidden` refuses
  `ws.noamm.org` and `api.noamm.org/hypixel/*` (testkit 1101-noamm-never). Read a co-installed mod's state in-process
  (`interop/LocalModBridge`) instead. The room database download from `api.noamm.org/na/data` (public, no auth) stays.
- **No secret ever goes in this repo or a jar** (security audit 2026-10-27, `killer560s-mod-logs/security-audit-1027.md`).
  v1.0.0/v1.1.0 shipped a Hypixel key XOR'd in `HypixelApiKeyProvider` - obfuscation is not protection, and it is public
  forever in those jars and in history. A key the mod needs goes in the relay as a Worker secret behind an endpoint, or is
  the player's own, stored in its own non-`.json` file listed in `ProfileData.EXCLUDED_NAMES` (or a `SECRET_FIELDS` field).
  `tools/verify-artifact.py` (shapes, the owner's secrets by sha256 from `~/.claude/secrets` + `~/.claude/secret-fingerprints.json`,
  embedded-key code, user paths) gates every deploy and release; CI also runs gitleaks with `.gitleaks.toml` and fails on a hit
  (a reviewed false positive goes in `.gitleaksignore` by fingerprint). Native libraries inside Jar-in-Jar jars carry their
  own builders' paths (JNA): reported as warnings only.
- A relay challenge and a relay bearer token must never verify with the same key: they did until 2026-10-27, and
  `GET /challenge?uuid=<anyone>` was a two-minute token for that player (relay `security-1027`, test/token-domain.test.mjs).
- The shared record store's account must be read on the client thread: testkit 75 asked from its test thread and got
  no account, so its seed went to `accounts/unknown` and the run list stayed empty (2026-11-01). `SharedRecords.accountId`
  is read at client init and remembered; a testkit case that needs a record's path asks for it in `computeOnClient`.
- The shared store was first at `%APPDATA%\killer560smod\shared`, and the combine run from the Claude desktop app wrote it
  there - but Windows virtualizes a packaged app's writes to a NEW %APPDATA% folder into
  `AppData\Local\Packages\Claude_*\LocalCache\Roaming`, so every tool here saw the store and the real game saw nothing:
  /profit was empty in 26.3 Mod Only Test (2026-11-01). The store moved to `%USERPROFILE%\.killer560smod\shared` (not
  virtualized); the mod folds a real %APPDATA% store into it once. Never put data a game must read under a new %APPDATA% folder
  from an agent tool; existing folders (PrismLauncher) are written in place.
- hypixelskyblock.minecraft.wiki answers `/w/<Page>?action=raw` with a "Please wait" bot-check page, but
  `index.php?title=<Page>&action=raw` (curl) returns the wikitext, and its slayer/loot tables are structured templates
  (2026-11-01). How Lucky Are You's drop table (`assets/killer560smod/rngtracker/drop-table.json`) is built from that by
  `tools/rng-table/fetch.sh`; rebuild it there, never hand-edit chances. Dungeon chest odds are the `/Loot` and
  `/Master_Mode_Loot` subpages' per-chest averages; Revenant's page has weights only (chance = weight/(token+main)).
- How Lucky Are You never counts a dungeon chest itself: chest attempts and drops are derived from the Croesus claim log
  (`CroesusProfitLog.allClaims`, resets ignored), so a new chest-counting hook would double count. Its own record
  (`RecordCatalog.LUCK`, `luck.json`) holds slayer and mob kills, Kuudra / Nucleus / Superpairs claims, drop lines, dye
  pickups, and per profile the slayer levels and slayer RNG Meters it read (luck2-1101). A claim with floor "Unknown" is
  given the one floor its items fit (`LuckStore.inferFloor`), else counted under `chest:Unknown:<chest>` with no odds.
- `tools/rng-table/fetch_mobs.py` gets every page using a wiki template in one request per 50 pages: `api.php?action=query&
  generator=embeddedin&geititle=Template:Mob_Drops_Table&prop=revisions&rvprop=content&rvslots=main` (590 mob and sea
  creature pages, 2026-11-02; api.php has no bot check either). Mob drop odds: Looting x(1+0.15L) first, then Magic Find
  only if the looted chance is under 5% (wiki Looting and Magic Find pages); Looting never applies to slayers or pets.
- ChatObserver drops a line equal to (or a 12+ character prefix of) one seen in the last 250 ms, so a Hypixel message that
  opens and closes with the same ▬ bar never hands its closing bar to subscribers. How Lucky Are You's Nucleus reader also
  ends the block 1.5 s after its header (luck2-1101, testkit 1102-luck-nucleus); any block parser keyed on a repeated
  closing line needs the same.
- A testkit `-PseedConfig` copied from Mod Only Test carries his Proxy Client config (`system/proxy/proxyclient.json`, SOCKS5,
  enabled): the test client then dials the local server through his real proxy, which refuses it ("socks5 ... FORBIDDEN",
  00-smoke fails, 2026-11-01). Leave that file out of a seed.
- Whether the relay connects, and to which room, is decided in ONE place, `ModChatFeature.onTick`. A feature that sends or
  reads relay `data` must be named there, or nothing connects for it: Song Tag never was, so no song was ever shared with
  Mod Chat off and no party (fixed 2026-11-01, lobby room; testkit 2866/2867). Testing the receiver by feeding it packets
  does not prove the socket opens.
- A `try { ... } catch { }` around a PowerShell helper step hides a binding failure for good: the Spotify cover never showed
  because `AsStreamForRead` could not take a WinRT COM object (2026-11-01, docs/LESSONS.md). Run a helper script's step outside
  the game against a real or file-backed input before trusting it.
- `ServerLag`/`ServerTickClock` take ~15 pings a second as "the server pings every tick", which GrimAC's transactions alone
  reach: on a Grim server they read jitter as stalls. Anything that must count exact server ticks uses `util.PerTickServerClock`
  (19 of 20 ticks with a ping, two windows in a row) (doc-blood-1101, docs/LESSONS-DUNGEONS.md).
- `.gitignore`'s `secrets/` line also matches `src/main/java/com/killer560/hub/secrets/`, so `git add` of a tracked file
  there (DungeonState, ...) fails with "paths are ignored" and the rest of that add is not staged; use `git add -f` on the
  tracked file (2026-11-01).
- The sim's F7 boss (`bosssim/`, docs/SIM-BOSS.md): reference mod ADM is All-Rights-Reserved - facts only, never its code
  or its arena.bin capture.
- GrimAC 2.3.74's EXPERIMENTAL checks (gridexp-1102; the owner's rule counts them): never two digs on one tick
  (MultiBreak - Multi Break was removed for it), a dig goes at once only when the look the server already HEARD strikes the
  block (`util/DigAim`), else the body turns and the dig goes at the head of that tick's `sendPosition` (`util/BeforeMove`;
  RotationBreak lends the next movement packet's look only within 15 ms), no `set_carried_item` after a
  use / click / attack / command inside one server tick (`HotbarSelect.swapWouldFlag()`, PacketOrderE), no yaw turn over
  2 degrees that repeats the turn before the previous block click (`util/PlaceRotationGuard`, DuplicateRotPlace - but never
  move the look in a tick that carries a `use_item`, that is BadPacketsJ), and every dig START / use through vanilla's
  `startPrediction` (BadPacketsH). Details per check: docs/LESSONS-AUTOMATION.md.
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
  for `BlinkCommands.debugLines`, the testkit's readout; /blink itself was removed 2026-11-01). A release jar (`-Prelease=true`, the GitHub builds) drops INFO, so that logger is
  `ModLog.keptWhen(...)`: it logs in a release only while Blink's Debug Log setting is on (testkit 1026-blink-release-log).
  His own jars (deploy-to-instances.py, built without -Prelease) always log. Stress-pass lessons (burst skipping Charge,
  settling after a teleport, latches across a rejoin): docs/LESSONS-AUTOMATION.md, Blink section.
- `ModPaths.config()` made the parent folder on every lookup, and most features look up from a static initializer, so
  each boot made ~85 empty folders (cheat ones in legit instances). Since layout-fixes-1026 only a write makes a folder;
  a new writer must create its parent, and an "Open Folder" button gets it from `ExternalOpen.path` (testkit 1026-layout-empty).
- A failure that "fails the same on merge-train" is not proof the mod is right: of the 2026-10-26 pre-existing list, two
  were real mod bugs (Profile Viewer name box 94 px for a 96 px name; a Custom Scoreboard row with no tooltip) and the
  96-ar run turned up a third (a path arrival ranked as an etherwarp in a stack); the rest were tests left behind by the
  owner's redesigns or measuring under load. Two testkit fixes (91af48ec, 7d854287) had sat unmerged on old branches for a
  week. Report: killer560s-mod-logs/preexisting-1026-report.md.
- Blink Charge keeps its bank through every teleport by the owner's decision (2026-10-27); do not revert. Since
  campaign-blink-1027 a teleport's confirm waits behind the answer to the ping GrimAC sent with it (else GrimAC ignored every
  move after it), and a shot GrimAC would not cover yet (after a teleport, during or after a hold) waits for cover on its own (~2 s after a
  leap standing still) - the owner's choice, 2026-10-27. Wider (same day): "it should never drop blink if possible except if i release it" - Charge also survives
  respawns, dimension changes, a configuration phase, a hold starting, the Skyblock gate flipping, screens (keys keep their
  state under chat/pause/death/mod screens) and AP3 stopping (handed back to his Charge key with AP3's cap). Ends left: his
  release, Hold Ticks, a disconnect, Blink switched off, a server refusal. Testkit expects the flags (runExpectingFlags,
  `Rows.addKept`).
- Sim mobs burned in daylight ("onFire, by nothing") and cleared key rooms at GO with nobody near, despite their
  `isInvulnerableTo`; `SimMobs` refuses environmental damage in Fabric ALLOW_DAMAGE (2026-11-01, docs/SIM-SERVER.md).
- `.gitignore`'s `secrets/` matches the source package `hub/secrets/`, so `git add <path>/secrets/X.java` aborts the WHOLE
  add with "paths are ignored" although the file is tracked (2026-11-01). Stage it with `git add -u -- <paths>`.
- Crypt census (CryptScanner rules against all 134 captures): use `killer560s-mod-logs/doc-secrets-1101/crypt_scan_port.py
  --new --rule crypt_1101_rule.py` (paths fixed for 26.3 Mod Only Test); the older copy in `crypt-census/` points at a
  deleted worktree and the wiped 26.1.2 instance. Run `python -I` on a file, never `python -` with stdin redirected.
- `DisconnectedScreen`'s constructors chain into each other, so a mixin on each of them (accounts/DisconnectedScreenMixin)
  runs up to three times for ONE screen (testkit 4101, 2026-11-01). Anything that must act once per screen keys on the
  screen object (`BanReportFeature.onDisconnectScreen(this, ...)`), not on the call.
- 26.3's move-entity packet can carry a position with a zero step (`McPackets.moveEntityMoves` is only `hasPosition()`
  there; 26.2's checks xa/ya/za). Blood Camp took those as moves and restarted trips (UI 392/552); it now drops a zero
  `moveEntityDelta`. Any other move-packet reader must do the same.
- The sim has NO held map during a run's countdown (`HeldMap.emulated()` is false until `SimRun` reaches GO, since doc-map-1101),
  as Hypixel gives the map only at Mort's first line. A feature that reads `HeldMap` must cope with `available() == false` in
  the green room; a sim test that needs the stand-in waits for `SimRun.hasStarted()` (or re-arms with `SimRun.begin` to stay pre-run).
- Inventory Sorter clicks come from `invsort/InvSortPlanner`, which is proven minimal (docs/INVSORT-OPTIMALITY.md). After any
  change to it, rerun `tools/invsort-optimality` (javac both files, ~5 min, every instance must print "exactly minimal"); its
  first run found a case the hand argument had missed (a hotbar slot already holding a matching copy is a free swap pivot).
- Five features cancel `ContainerScreen.extractBackground` at HEAD (Inventory Theme, Storage Overlay, AH/Bazaar reskins,
  Spirit Leap), so nothing may read per-frame state from a hook there: the Terminal Solver did, and with Inventory Theme on
  no terminal was ever tracked (2026-11-01, doc-terms-1101). Use the HEAD of `Screen.extractRenderStateWithTooltipAndSubtitles`.
- The Modrinth App starts the game through `com.modrinth.theseus.MinecraftLaunch` (so `sun.java.command` names it) and keeps
  its signed-in accounts in `%APPDATA%\ModrinthApp\app.db`, SQLite in WAL mode: table `minecraft_users` (uuid, active,
  username, access_token, refresh_token, expires in Unix seconds), recent rows often only in `app.db-wal`. Its refresh token
  is bound to the app's Xbox device key, so the mod uses the app's current token (`accounts/core/LauncherAccounts`,
  `SqliteReader`, read only; 2026-11-01). Inspect a COPY of app.db, never the live file, and never print a token.
- The first-launch tour (`intro/`, 2026-11-01) opens over the title screen once per instance (fresh or updated; `seen` in killer560smod-intro.json, saved as it opens); in a client gametest
  run it never opens unless `-Dkiller560.test.intro=show` (docs/TESTING-HOOKS.md), so title-screen cases are not
  interrupted. A GUI picture-in-picture (`g.skin`, entity, item models) ignores the pose: transform its box yourself
  under Auto Scale (docs/LESSONS-GUI.md).
- Etherwarp passability (2026-11-01): carpets are passable in BOTH tables (EtherwarpOverlayFeature, TeleportUtils), as in
  Hypixel's 1.8 feet list; change the two together or the overlay, the sim and Auto Routes/ZPEW disagree (LESSONS-AUTOMATION).
  Trap-room Auto Routes rules are tested without a trap capture through `AutoTrap.testTrapRoom` (testkit 62-argrim-1101-trap*).
- A cheat setting goes under the legit ones even when the owner asks for a legit block "at the bottom" (testkit 512 checks
  every tab): the Etherwarp tab puts Works For last among legit rows and its cheat rows under it (2026-11-01).
- Anything that BUYS sizes by Hypixel's stacking, never the vanilla material's (2026-11-01: Kaleidoscopic Mineral is PAPER,
  stacks to 1, and the Bazaar Flipper typed 254): `bazaarflip/BazaarStackSizes` (catalog `unstackable`/`has_uuid`, reforge
  stones, books, the live "Fill my inventory!"); docs/LESSONS.md "Bazaar and Croesus".
- A sim change to the world's SHAPE can silently blind a scanner: the 2026-10-12 seam fill walled the door's middle seam
  column up to the roofs, so every QUOI-rule door scanner (top block 73/74/81/82) found no doors in the sim, and the 538/678
  failures were later blamed on 26.3 (doors263-1101: they had failed on 26.1.2 since 2026-10-15). Before blaming a port,
  find the last PASS of the case in killer560s-mod-logs and bisect from it. docs/SIM-DOORS.md has the seam rule.
- A Brigadier node with children and no `.executes` answers the bare command with "Unknown or incomplete command", which
  reads to him as "unknown command": `/killer560 ew` and `/killer560 profile` were registered fine, they only needed a
  word after them (2026-11-01, both removed). A command meant to be typed bare needs its own `.executes`.
- Command removals (killer560, 2026-11-01, mod cmdclean-1101): no `/class`, `/log`, `/k560search`, `/lag`, `/k560textures`,
  `/autoclear`, `/autosecret`, `/blink`, `/freezestate`, `/rewind`, `/test`, and no `/killer560 class|ew|mapdump|profile|sim`.
  What they reached stays reachable from a key or the menu: Auto Clear's Clear Toggle Key, Auto Secret's key, the Freeze
  State keys, Blink's keys, the Profiles tab, `/runlog`, `/search`, `/ew`. The Bat Test (/test) was dropped entirely and
  the Insta Clear readout/mark/threshold/window have no UI (his call: "why do i need a bat test? autosecret had its own
  keybind already"); do not add menu rows for them. Testkit 1101-cmdclean-gone fails if any command comes back.
