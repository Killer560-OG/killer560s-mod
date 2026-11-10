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
# every build (not only -Prelease, since jarsize-1104) drops JNA natives for platforms Java 25 cannot run on and rewrites the jar at deflate 9; -PnoJarTrim=true skips it
# PNG recompression: oxipng -o max --zopfli --nc --nb --np (without --nc/--nb/--np it turned cape/light.png grayscale and 70 others into palette PNGs; jarsize-1104). Report: killer560s-mod-logs/jarsize-1104.md
# The 4 main menu pictures are lossless WebP (Pillow lossless, method 6, exact), read by mainmenu/WebpPictures with the bundled TwelveMonkeys reader OFF the render thread and drawn once ready (decoding on it took 11-35 s on a loaded PC); a new painted picture goes the same way. GIFs: gifsicle -O3 (C:\Users\Hunter\tools-dl\gifsicle)
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
Records (profit, run history, stats) are in the shared store, not in any instance. Of the old instances only
`26.1.2 (Dungeons)` and `26.1.2 (Mod Only Test)` (retired, 26.1.2 jar 67b77105 - never deploy to or boot them) and `26.2 Cheat`
(deployed like the others) still exist (checked 2026-11-02); delete the 26.1.2 ones only with his OK. Their mod config is
backed up in `killer560s-mod-logs/instance-backups-1101/`. The handoff file lists every instance with its deployed commit. `Title Screen GUI` (the main-menu preview instance, marker `killer560smod-title-screen-gui`, docs/TESTING-HOOKS.md) was
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
feature; `README.md` holds the name list. Mining ships on main (killer560, 2026-11-04): `miningqol/`, `powdermining/` and `mining/` (pulled from staging-mining-3; its macros Auto Nucleus Run and Lobby Swapper stay there), all in the menu's Mining category (`MiningQolTab` groups); docs/CRYSTAL-HOLLOWS.md and docs/MINING-PLAN.md came back from the old shelf. The rest of
the post-2.0 staging work stays unpushed.

Shared pieces worth knowing: `util/ActionGate` is the mod-wide one-automated-interaction-per-tick arbiter
with actor priority; `util/SkyblockGate` is the "Skyblock Only" gate; `secrets/DungeonState` owns floor and
boss-phase detection; `util/ViewFreeze` holds the camera still while something rotates the real player.
Duplicate helpers (unify-1108): a HUD element with a constant id, name and default position extends `hud/BasicHud` (`super(id, name, x, y)`), not `implements HudElement` with four one-line methods; `bazaarflip/autopilot/FlipAction` has default `productId()`, `name()` and `order()`, so a record that carries no order overrides nothing. A config's plain on/off switches go in a `util/BoolFields` table, its file reads and writes through `ConfigJson.read/write` (never `JsonParser.parseString(Files.readString(..))` or a private pretty Gson), and box/line vertices through `util/BoxVerts` (`lineBox`, `filledBox`, `lineStrip`).
Main menu pictures: he (or a theme's designer) paints them and we only animate them (killer560, 2026-10-16) -
`mainmenu/PictureScene` is the pipeline (texture, patches, ripples, overlays); `TargetScene` and `MilkyWayTexScene` are specs.
Theme assets on demand (themedl-1104): the full main menu pictures and the theme music loops live in `theme-assets/` at the
repo root, never in the jar. `python tools/theme-assets.py` writes the in-jar previews (a quarter size; Amber's full size at
medium quality) and the manifest `assets/killer560smod/theme-assets.json` (name, sha256, size) and copies the files to
`build/theme-assets/`; after changing a file, bump its `RELEASE` and upload those files to that GitHub release as a
PRE-RELEASE (never marked latest, or Auto Update's /releases/latest stops finding the mod). `mainmenu/ThemeAssets`
downloads through ModNet service `theme-assets` into the shared store's `theme-assets/`, never with the custom title
screen off, and never deletes on its own.

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

SHARED FOLDERS (univfolders-1104, killer560 2026-11-04): imported menu backgrounds (`ThemeBackgrounds.folder()`) and the GIF / audio folder (`GifPlayerFeature.folder()`) are ONE folder for every instance, `shared/backgrounds/` and `shared/gifs/` under the record store root (a testkit client: its sandbox), through `shared/SharedFolders.folder(name, legacyConfigFolder)`; the first use moves the old per-instance folder in (same bytes once, different bytes kept as `name-2`). They are user files, not records, and are in `ProfileData.EXCLUDED_NAMES` ("gifs", "menu-themebackgrounds"): a profile keeps which GIFs are on, never the files. The converted-audio cache stays per instance (`GifPlayerFeature.CACHE_FOLDER`). Testkit 1104-ui-univfolders.

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
Google Doc is regenerated. Sharing and receiving settings default ON. The GUI is orange-themed by default (Amber), but EVERY part of the mod follows the chosen menu theme (killer560, 2026-11-04: "all parts of my mod should match the theme of the mod. so if someone has the green theme and has my crystal hollows map the border shouldnt be orange it should be green"): screens, HUDs, maps and borders take their accent/border colors from the active theme, never a hardcoded orange (a color the player set for that one element stays); fixed amber chrome goes through `gui/ThemeAccent` (docs/LESSONS-MENU.md, testkit 1104-ui-themematch-audit). Never carry
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
Menu search (killer560, 2026-11-02: "i keep searching for things we have and I cant find them"): `gui/MenuSearch` reads each tab
once per search through `BaseTab.searchScanWidgets()` with `BaseTab.isSearchScan()` true. A tab that leaves its rows out while
its switch is off writes the gate as `if (!cfg.isEnabled() && !isSearchScan()) { return widgets; }` (96 gates did on
2026-11-02), or its settings cannot be found while it is off; a tab with collapsed sections builds them open in the scan (Ap3Tab's
pattern). Never put a network call or a side effect in buildWidgets: the search builds every tab. Common words a player types go in
`MenuSearch.SYNONYMS`, phrases in `SearchAliases`; testkit 1102-ui-search-queries holds 80 real queries. A new client command
gets its owner card in `gui/SearchCommands` (by name, by the handler's class or package) or a reason in `NO_CARD`: testkit
1102-ui-search-commands walks the live dispatcher and fails on any mod command with neither (killer560, 2026-11-02: "i search
invsort and the inventory sorter doesnt show up even tho it is the command"). A README name that is not a card name gets a
`SearchAliases` entry (1102-ui-search-readme searches every one).
Inside a tab the feature's on/off switch is the first row. A tab that is really several features side by side (Dungeon Queue)
has no switch over the whole: each feature keeps its own, and the tab lists them in `BaseTab.masterToggles` so the card
(`gui/MasterSwitch`) is ON while any is on and OFF only when all are; never add a "X as a whole" row that gates independent
switches (the 2026-10-13 ones were removed 2026-10-14, files with the old key OFF load with every part OFF). Testkit 515-518.
Testkit 511-514 check it on cheat, legit and testing jars.
A feature that can be off has a real ON/OFF switch (`onOffButton`), never an Off/None value in a selector (killer560,
2026-11-02, Chat History's card had no switch: "this needs an actual toggle"): the switch is the first row, the selector
after it has no Off value and shows only while ON, and the value is kept while OFF so OFF then ON restores it; a saved file
from the selector era migrates Off to the switch off. Chat History and the Cape section were fixed (realswitch-1102);
Menu Theme is a pure settings page (no switch). Testkit 530 steps every selector on every card without a switch.
A feature folded into another tab (Thorn Triggerbot into Thorn, 2026-11-02) keeps its own toggle key through
`ModuleKeybinds` EXTRAS under its old card id, so a saved bind carries over; its row sits in the host tab (testkit 527).
A row whose feature runs on its own flag (not gated by the tab's first switch) is an independent feature and goes in
`masterToggles`, and it must be built even while the first switch is off (an early `return` hides it from `switchRows`);
otherwise the card reads OFF while it runs (Architect's First Draft and 11 more, menufix-1102). A row the tab only shows
under a parent switch must not run without it (Simon Says Party Tracker, Send Melody Progress now follow theirs). Testkit
525 switches every listed row on alone on every card and pins the 1102 tabs' lists.

Never track a `HANDOFF.md` in the repo; handoff notes live only in
`C:\Users\Hunter\.claude\killer560s-mod-HANDOFF-PRIVATE.txt`. The Discord bot token lives at
`C:\Users\Hunter\.claude\secrets\killer560smod-discord-bot-token.txt` and is never printed or committed.
The features Google Doc **edit** link is never published; only the `/e/2PACX-…` published link is public.
A Discord update covers three places: #changelog (mod), #roadmap (edit the pinned message), and
#testkit-updates (1553951359810670692) for testkit changes - the last was missed for a week until 2026-10-05.
@Claude_Bot answers only Staff-or-higher members (and the owner), in any channel of killer560's server; it answers mod questions, runs mute/unmute/purge/warn itself after its own rank checks, answers a staff reply's original message, and logs work requests to killer560s-mod-logs/DISCORD-INBOX.md (owner-triggered = "owner-approved"), looks up live Skyblock stats (networth estimate, skills, cata, slayers, money, status, auctions) and Bazaar/BIN/firesale data itself through sblookup.py (the Hypixel key never leaves it), and saves the crash reports the relay posts as it into killer560s-mod-logs/crash-inbox/ with a "crash" inbox entry, never acting on them (devbot-1105, C:\Users\Hunter\killer560s-devbot; start.sh/stop.sh, status file status.json, last_alive_epoch).

## Safety rules for automation

These are his, they are about getting banned, and they are not negotiable. Never write position or velocity
directly (`setPos`, `setDeltaMovement`) and never use fractional movement input — discrete key presses only.
A server correction is always honored (never fought) but never stops anything: see the first lesson below. Never clamp or wrap simulated yaw at 0-360; Hypixel treats an uncapped
running rotation value as the normal signal. Pitch is always kept within -90..+90. Anticheat *deception*
features: on 2026-10-25 he asked for Blink, Inventory Walk and Zero Ping Etherwarp and had them built and shipped
(cheat build only, off by default; blink-1025). Zero Ping Etherwarp stays CAMERA ONLY (his decision 2026-10-25; in both builds 2026-10-26 to 2026-11-02, cheat-only again since: "all zero ping etherwarp stuff should be cheat only nothing in legit"): the
character is never moved client-side ahead of the server, so the setPos rule above stands. Blink AP3 is not built.

SAME-TICK GRIM CASES (killer560, 2026-11-05: "that is unacceptable for something like that to have not been thought of
and tested", after Leap Takes Target's Look was shipped tested only with an idle tick after its turn). Any feature that
turns the look, swaps the hotbar, sends a click/use/attack or answers a teleport is tested on default AND experimental
Grim with the player's own likely actions landing IN THE SAME TICK as the feature's: a right-click use (use_item carries a
look), a container click, an attack, a hotbar swap, a teleport accept, and a second run of the feature itself. Assert on
the wire (WireCapture) that both went out in that tick before trusting a clean Grim result. An idle-after test is not a
Grim test.

A setting that flags is not shipped with that value (killer560, 2026-10-27: "if a setting is flagging then it shouldnt be
pushed with that setting. SImple as that."). Test every new cheat setting at its HIGHEST value on GrimAC's default config
AND with `experimental-checks: true` (testkit: a checkout whose `run/testserver/config/GrimAC/config.yml` says so); a value
that flags is capped below or the setting removed (Arrow Align Frames Per Tick was dropped for MultiInteractA/B,
2026-10-27). Kept-teleport Blink flags are his accepted exception. A lag spike of 900 ms or more mid-Hold that draws TimerLimit is accepted too (killer560, 2026-11-04: "Keep holding"); Charge never flags under lag (it sends held answers after 150 ms without a ping, keeping the bank). Long Charge under a server that keeps stalling sends no position for over 2 s and draws experimental BadPacketsR: accepted too (killer560, 2026-11-04: "Accept it, like TimerLimit"; a sooner position brings BadPacketsV back). Burst Multi Break is an owner exception too
(killer560, 2026-11-02): Breaker Aura's "Burst Multi Break (flags newest Grim)" switch (default OFF, only with Multi Break on)
brings back the pre-d2da5856 burst that GrimAC's experimental MultiBreak flags; its label says it flags, and tests accept
MultiBreak* only while it is on.

(Added 2026-10-20: the working rules that now apply. Each is in the handoff file too, which is the fuller record.)

(Added 2026-10-26, killer560:)
- After-2.0 (staging) features are NEVER shipped - not to main, a jar, an instance or GitHub - until 2.0 is pushed.
  "Under no circumstances." Only dungeon/main work ships before 2.0.
- Testkit client gate (killer560, 2026-11-04, after "no limit" filled all 61.7 GB of RAM and froze runs): every run first calls `bash /c/Users/Hunter/km-scripts/clientslot.sh <clients it starts> <expected minutes>`. It starts clients while free RAM stays >= 6 GB (1.5 GB counted for each client started in the last 2 min) and at most 12 run; waiting runs go shortest first (waiting time ages them), and a run of 45+ min goes first and gets its own room ("if something is really long let it have a dedicated one"). `KM_CLIENT_CAP=1` asks to run alone (timing cases). It counts only Minecraft client windows. v2 counted every java.exe over 1 GB, including Gradle daemons and test servers, so a run-alone waiter at the head never fit, and from 23:52 to about 00:30 on 11-04/05 nothing was granted. Never kill another run's waiter to get past the gate. v4 (2026-11-05) backfills: every grant is recorded in `killer560s-mod-logs/clientslot.running` with its caller pid and expected end, and while the head waits only because running clients fill its cap, a later ticket may start if it ends no later than the latest recorded run (no window if a run is overdue or a client has no record); decisions in `clientslot.backfill.log`, and a run alive at 150% of its stated minutes draws a WARN, so pass honest minutes. A grant is tied to the pid that asked: call clientslot.sh from each run's own subshell, never once from a long-lived script, or the gate counts finished runs as still holding slots (spotify1108, 2026-11-08). A KM_CLIENT_CAP below the clients a ticket asks for could never pass, and a ship-priority head then blocked every ticket (02:07-02:35 on 11-10, coopsim asked 2 clients at cap 1); since 1110 the cap is raised to the asked count. KM_SHIP is for the release ship gate and the runs a pending push waits on (killer560 2026-11-10: "Any push should always have the highest priority for testing"), nothing else: the coopsim session's reruns posted every run as a run-alone SHIP ticket, and short tickets of other agents waited 4+ hours behind them (11-10). Test servers had no -Xmx (default 1/4 RAM, up to 7.5 GB each) and held free RAM at the 6 GB floor; testkit srvheap-1110 caps them at 4G. A caller that asks for a new slot has finished its previous run, so the gate now drops that caller's record and run-alone hold when it sees it waiting again (darkall-run3.sh's own overdue grant blocked every backfill 06:40-07:08 on 11-10). With 27 waiters each running a 6 s locked pass every 15 s the lock was busy 92% of the time and a head that fit waited 23 min for its turn (07:10-07:33 on 11-10): since then the client count (tasklist) is cached 8 s, only the head polls (every 3 s), others pass at most every 20 s, and the head writes why it waits to `clientslot.headwhy`. Waiters queued before a reinstall of clientslot.sh never took their turn (08:00 the same day), so a head ticket older than the installed script is drained like a v2 waiter (granted for it, waiter ended so its script goes on); `km-scripts/drain-old-1110.sh` pushes such a queue through after a reinstall. On a loaded PC (10 search JVMs) one pass took ~25 s, the lock was held 95% of the time and the head, sleeping a full round after each failed mkdir, waited 45 min (09:12-09:58 on 11-10): the head now retries the lock every 1 s and other waiters pass at most every 60 s. That was still too often with 30 waiters (lock busy 19 of 20 s at 11:27 the same day, a head that fit waited 20 min): while a live head waits, only ONE non-head pass runs per 300 s in all (clientslot.lastnh), and a dead or empty head is replaced at once. Never rewrite a waiting ticket to change its priority: appending "ship" to three old-text tickets at 10:05 moved their mtime past the script's, drain-old stopped seeing them as old, and the old-text head never granted itself (no grants 10:11-10:30, 0 clients, 26 GB free); keep the mtime (`touch -r` the old file) if a ticket must be edited.
- BUILD STANDARDS for every feature and fix (killer560, 2026-11-04: what the size/FPS/scan passes of 11-04 enforced, now
  the default for all new code). FPS: nothing per frame or per tick that can be cached, event-driven or gated on the state
  it needs (in a dungeon, screen open); no allocation, stream or regex compile in render/tick paths; block scans ask section
  palettes first; disk, network and big parsing never on the render thread (saves on release or throttled, shared-store
  writes on `shared/SharedWriter`). Boot: nothing heavy in static init or the client entrypoint; load lazily. Size: fewest
  lines that stay clear; grep for an existing helper before writing one and never add a second copy (the dup passes merged
  dozens); delete dead code you touch; no large asset in the jar (big theme assets download on demand, previews only).
  Leaks: every listener, cache and map is bounded or cleared on world change. Mixins: chaining forms only (see below),
  targets verified. Render or tick changes run the perf gate; docs/LESSONS-PERF.md has the measured rules.
- Every failure found gets fixed, even in the middle of a size, perf or other pass (killer560, 2026-11-04: "If you find a
  failure fix it even during these changes/optimizations"). "Pre-existing" is a finding, not a reason to leave it: start a
  fix job (mod or test, whichever is wrong) and put it in WORKQUEUE.
- A finished, tested main batch is deployed to EVERY instance, his play instance included (he said yes, 2026-10-26; that
  is `26.3` since 2026-11-01). Test or testing jars still never go into it, and it is never booted by us.

- Push only when no game is running (`(Get-Process javaw -ErrorAction SilentlyContinue | Measure-Object).Count` is 0).
  With a game running, commit but do not push; a jar that must reach a running instance is staged as `.jar.pending`.
  Testkit clients (`java.exe` with `-Dfabric.addMods=` pointing at a test jar folder) and Gradle daemons do NOT block a
  push (killer560, 2026-11-04: "There shouldn't be any games running besides testing stuff. It should be able to push");
  only a Prism-launched game (javaw) does.
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

- **Code lessons live in `.claude/rules/code-lessons.md`** (split out 2026-11-03 to save tokens): they load
  automatically when a file under `src/` is read. Before editing source without having read one, read that file.
- **Nothing in this mod ever stops, pauses or disables on a server correction** (killer560, 2026-10-06): it calls
  `util/ServerCorrections.report` (chat line + `ModSounds.playCorrectionAlarm()`) and carries on from the corrected position.
- `LOGGER.debug` never reaches his log. Minecraft's root log4j2 level is INFO, and a real client log
  (`26.1.2 (Dungeons)`, 29,596 lines, 2026-09-29) contains zero DEBUG lines. So a `.debug` call is not the
  spam and deleting one buys nothing; when hunting log noise, hunt `.info`. That is where it all was: the
  2026-09-29 trim cut INFO call sites from 783 to 273 and left WARN (276) and ERROR (86) alone.
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
- The testkit's synthetic clicks must use `InputConstants.MOUSE_BUTTON_LEFT` (1 on 26.3, 0 on 26.2), never a literal 0:
  with GLFW's 0, 26.3 smoke 303/304 failed "presses not landing" while the mod was fine (2026-11-01).
- Run gradle through Bash, not PowerShell: PowerShell wraps native stderr in ErrorRecords and splits compiler
  messages mid-line, so error counts and file paths become unreadable. Gradle also prints compiler output
  TWICE, so `grep -c "error:"` is double - the `N errors` line javac prints is the authoritative number.
- Agent worktrees are cut from `main`, not from the branch checked out here. On 2026-10-04 six were started
  while work sat on a feature branch 26 commits ahead of `main`, and every one began on stale code. Get the
  work onto `main` (or tell each agent its base) before fanning out.
- An agent "started in" the parent's batch worktree must not branch or build there: on 2026-10-27 the etable agent ran
  `git checkout -b` in `worktrees/batch-1026` while the parent used it, so the parent's merge landed on the agent's branch
  and its build compiled the agent's edits. An agent makes its own `git worktree add` before its first edit.
- A run's output piped through `| tr -d '\r' > file` never ends: the Gradle daemon run-scenario starts inherits the pipe,
  so `tr` waits forever and the script never reaches its next line (2026-10-27). Redirect straight to the file instead.
  A menu case is selected with its session first: `-Scenario '200-menu-session:,2783-menu-...'`; without it 0 cases run.
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
  (a reviewed false positive goes in `.gitleaksignore` by fingerprint). A ship runs the same scan locally before pushing:
  `C:/Users/Hunter/tools-dl/gitleaks/gitleaks.exe git --config .gitleaks.toml --redact --no-banner --exit-code 1 --log-opts="<origin tip>..HEAD" .`
  (ship 7's CI scan failed on the folder name "leftclicksecrets" in ModPaths, 2026-11-05; fixed in 4e54b164b). Native libraries inside Jar-in-Jar jars carry their
  own builders' paths (JNA): reported as warnings only.
- A testkit `-PseedConfig` copied from Mod Only Test carries his Proxy Client config (`system/proxy/proxyclient.json`, SOCKS5,
  enabled): the test client then dials the local server through his real proxy, which refuses it ("socks5 ... FORBIDDEN",
  00-smoke fails, 2026-11-01). Leave that file out of a seed.
- A `try { ... } catch { }` around a PowerShell helper step hides a binding failure for good: the Spotify cover never showed
  because `AsStreamForRead` could not take a WinRT COM object (2026-11-01, docs/LESSONS.md). Run a helper script's step outside
  the game against a real or file-backed input before trusting it.
- `.gitignore`'s `secrets/` line also matches the source package `src/main/java/com/killer560/hub/secrets/`, so
  `git add <path>/secrets/X.java` of a tracked file (DungeonState, ...) aborts the WHOLE add with "paths are ignored"
  (2026-11-01). Stage it with `git add -u -- <paths>` or `git add -f` on the tracked file.
- GrimAC 2.3.74's EXPERIMENTAL checks (gridexp-1102; the owner's rule counts them): never two digs on one tick
  (MultiBreak - Multi Break was removed for it), a dig goes at once only when the look the server already HEARD strikes the
  block (`util/DigAim`), else the body turns and the dig goes at the head of that tick's `sendPosition` (`util/BeforeMove`;
  RotationBreak lends the next movement packet's look only within 15 ms), no `set_carried_item` after a
  use / click / attack / command inside one server tick (`HotbarSelect.swapWouldFlag()`, PacketOrderE), no yaw turn over
  2 degrees that repeats the turn before the previous block click (`util/PlaceRotationGuard`, DuplicateRotPlace - but never
  move the look in a tick that carries a `use_item`, that is BadPacketsJ), and every dig START / use through vanilla's
  `startPrediction` (BadPacketsH). Details per check: docs/LESSONS-AUTOMATION.md.
- Before building ANY non-dungeon feature, look for it on the staging-* branches (staging-mining-3 holds most mining) and in shelved/: on 2026-11-03 powder chest/tracker/loot-filter features were rebuilt from scratch while staging-mining-3 already had them (killer560: "some of this is already built and you just need to pull it"). git ls-tree -r --name-only staging-<x> -- src/main/java | grep -i <topic>.
- Post-2.0 staging (2026-10-25): farming, mining, slayer, rift, fishing and island work lives on unpushed `staging-*` branches
  (`staging-combined` merges them). Automation is cheat-block only, default OFF. `ModPaths.settingFiles()` also lists `other/`,
  and import keeps the locked update and sharing keys (testkit 981).
- Docs are split by topic since docs-condense (2026-10-25): LESSONS.md, LESSONS-AUTOMATION.md, LESSONS-GUI.md and SIM.md are
  indexes to LESSONS-{AUTOCLEAR,ROUTES,AUTOMATION-PUZZLES,DUNGEONS,HUD,MENU,MINECRAFT,PERF}.md and SIM-*.md. Add a lesson to the
  topic file; a branch that appends to an old monolith conflicts on merge.
- A failure that "fails the same on merge-train" is not proof the mod is right: of the 2026-10-26 pre-existing list, two
  were real mod bugs (Profile Viewer name box 94 px for a 96 px name; a Custom Scoreboard row with no tooltip) and the
  96-ar run turned up a third (a path arrival ranked as an etherwarp in a stack); the rest were tests left behind by the
  owner's redesigns or measuring under load. Two testkit fixes (91af48ec, 7d854287) had sat unmerged on old branches for a
  week. Report: killer560s-mod-logs/preexisting-1026-report.md.
- Crypt census (CryptScanner rules against all 134 captures): use `killer560s-mod-logs/doc-secrets-1101/crypt_scan_port.py
  --new --rule crypt_1101_rule.py` (paths fixed for 26.3 Mod Only Test); the older copy in `crypt-census/` points at a
  deleted worktree and the wiped 26.1.2 instance. Run `python -I` on a file, never `python -` with stdin redirected.
- Chat Hider display-time hides (chattidy-1104): a line a mod feature must READ and the owner wants gone from chat (Breaking Power reminder, [Sacks] lines, "You uncovered a treasure chest!") is hidden in `ChatTidy.shouldHide` (ChatTidyMixin, after ChatObserver dispatched it), never in ObjectHiderFeature's ALLOW_GAME, which would starve the readers. Testkit 1104-chattidy-* (hx session: `-Scenario "100-hx-session:,1104-chattidy"`; a bare case name runs nothing of the session).
- Inventory Sorter layouts carry an optional Hypixel loadout (invloadout-1104, killer560 2026-11-04): `InventoryLayout.loadout()` 0 (none) or 1-12 (/loadouts page 1 only), saved as `"loadout"` in the layout file. Applying selects it first through `LoadoutSwapper.requestLoadout` (Open, Swap, Close), then storage/sort plan from the inventory it leaves; a failed swap stops the run. Testkit 1104-invsort-loadout-save/-apply.
- A cheat-only feature's README name goes in the `**Cheat Build Only**` line, never a category line with "(cheat)": testkit 1102-ui-search-readme skips only that line on a legit jar, so Swap To Drill On Pigeon failed it on legit (ship-1104b, 2026-11-04). Its new tab goes in 511's `newCheatLeaves`.
- A quiet GrimAC server pings about once a tick, so `PerTickServerClock` trusted it (20 pings, 19 of 20 ticks), and each hit
  then added transactions and ran the clock ahead: 1102-hx-goldor-tick read Goldor's hits 5, 3, 7, 11 late on 26.3 legit
  (gate-1104b/c; Tick Timers logged "48 pings, 2129 ms (42.6 ticks of wall time)"). goldfix-1104: pings are paced against the
  WALL clock (a server cannot tick faster than 20/s; client ticks are no measure, a frame freeze drops them), and once they run
  more than 6 ahead the server loses trust until the next world. That rule untrusted REAL Hypixel (its random-id pings make
  ~1.13 a tick), so it is off-Hypixel only since tickfix3-1104 (code-lessons.md, "Tick timers and split timers"). Read a timer
  fault off its pings-vs-wall-time line and the once-a-world "ping census" line first. Testkit `pingForTest` is unpaced (cases push the clock 400 ahead); `pingForTestTimed` is paced.
- Defaults-off pass (defoff-1104, killer560 2026-11-04): ~46 first-row switches (Croesus, Blood Rush learn spots, Custom Party Finder, Posmsg, RNG Meter, Team Melody share, Etherwarp Waypoints, Auto SS, mining profit/qol/powder rows, GIF Player, DVD, Object Hider, emotes, click translate, chat keybinds, image preview, Spotify lyrics, Armor HUD, AH/BZ, BZ Order Helper, Hide Potion Effects, Experiments, Legit GFS) start OFF; field initializer and `getBool` default must agree. Sim keybinds merged into SimSettingsTab. Testkit `DefaultsOff1104Cases.allOn` turns them on around the 511/513/412 walks (rows hidden while OFF); 1104-ui-defaults-off checks the no-file defaults. Add a new default-off config to its TABLE.
- Structure Scanner folded into the Crystal Hollows Map tab (scanfold-1105, killer560 2026-11-05): cheat-only rows at the bottom under a red Cheat header, its switch in `masterToggles`, toggle key kept as `ModuleKeybinds` EXTRAS id "StructureScannerTab"; scanner rows show only while its switch is ON (or in a search scan). `/chmap` opens the full-screen map. Map Scale and the HUD switch are gone (feature ON = HUD ON; "HUD Name Size" slider). A config `setEnabled` read by `FeatureBits.register` must call `FeatureBits.changed()` or 1104-ui-mining fails on the FeatureBits ERROR line. Testkit 1105-ui-chmap-tab, 1104-chscan-scan (center and zone color).
- Multi-branch ship (ship12-1108, 17 mod + 17 testkit branches): the merges conflicted only in append-only docs (code-lessons.md, FEATURES.md; keep both, then grep for duplicate headings), but two testkit checks went stale at once and failed the gate on all three builds: `350-logic-catalog` (regenerate: `python -X utf8 tools/extract-patterns.py --mod-source <ship worktree>`) and `1104-ui-themematch-audit` (a branch that adds raw color constants needs its ThemeMatchCases allowance, ExtraPages 13 = Minecraft gold data colors). Run both before the ship gate. A bare case name in a gate scenario runs nothing for a session case (1102-hx-goldor-tick: MISSING); write `100-hx-session:,<case>`.
- Testkit merges leave conflict markers in files you did not look at (ship13-1108): after merging testkit branches, `grep -rlE "^(<<<<<<<|>>>>>>>)" src` across the whole tree (ThemeMatchCases carried markers into a commit and broke the gametest compile; the first gate start died on it). A `100-hx-session:,` prefix is needed for 1102-hx-goldor-tick, and the gate's `smoke` entry always reads MISSING (cases are named 30x-ui-smoke-*).
- Ship base for the testkit is ORIGIN master, never the local `master` checkout (ship14-1108: local master was 267 commits behind origin and carried uncommitted changes; the first gate ran without ship13's testkit cases - Blink 11 cases, yt-lyrics, themematch - and had to be redone). Start `git worktree add ... origin/master`, check `git merge-base --is-ancestor <origin tip> HEAD`. Gate flakes under load: 1025-boss-relic-trigger, 1026-blink-items-b (26.2) and 1105-blink-oneshot-same-click each failed once in a full gate and passed on rerun alone; rerun a failure alone (clientslot KM_CLIENT_CAP=1) before reading it as a bug.
- Ship15 (ship15-1109, 4 feature branches + one cherry-pick): new test cases that run on a legit jar by prefix (`1108-` in every session) must skip there (`1108-same-burst-*` failed "isBreakerAuraEnabled() still false" on legit until it skipped); a new client command root needs a deny-commands.json entry (306: `rp`, `reparty` parseOnly); a card blurb must fit the 2-line budget of a card whose name wraps (1060: "Watcher Helper"). A branch whose test file grew in several commits (invwalkterm-1108's 1108-iw-termwalk) must be taken at its final version, never the first commit: the first version's flying rows failed "did not act"; take the final file and drop only the Hide Keys rows. A wait of 175 s or more in the foreground is auto-moved to the background, use at most ~168 s per waitfor call.
- Ship 16 (ship16-1109, featD/E/F): a card blurb is cut (1060 "only fit because the menu cut their text") when the card name wraps to two lines: keep it to about 22 characters (Watcher Helper "Rates Watcher speed." fits, "Dungeon milestone count HUD." did not). 1104-ui-defaults-off reads each config's path through a static field named `CONFIG_PATH`; a config calling it `PATH` fails with "no field CONFIG_PATH". Scenario `1101-wp` runs only 1101-named cases; 1108-dwp-place runs under the `1108-` prefix. A whole testkit checkout (tk-ship16b) answered every run with "Anticheat test server exited with code 0 before it was ready" while a fresh worktree worked: after two such fails move the run to another checkout. The 1105-blink-oneshot-* charge-shot cases and 1105-same-breaker-attack ("crosshair MISS") flake under load, a different case each time; each passed alone (KM_CLIENT_CAP=1). A new client command needs its root in the testkit's deny-commands.json (dwp).
- Ship 17 (ship17-1109, featG/featH): a case that needs the map HUD must first leave the F7 boss box (`tp @a -419.5 151 -419.5`): earlier cases of a full hx session leave him at 56,30,81, LiveMap latches "Boss room entered" and hides the HUD, so 1109-roompreview-hover failed in the full session on all builds but passed alone (testkit fefc05c8). Cheat-only packages (ghostblock, terminatorclicker, like blink and invwalk) stay in the legit jar as unregistered classes; no build step strips them, so that is the norm, not a leak. Reruns of a hx session with `1109-` in it need `100-hx-session:,` in front. Perf gate S1/B1/S2/B2 all 0 OVER.
- Ship 18 (ship18-1109, invwalkclean): a gate script started with `setsid ... &` silently never ran here (setsid is missing in Git Bash; the waitfors then just timed out): start a gate with the tool's run_in_background. Gate flakes under load, each passed on rerun alone: 1101-bloodcamp-stats (first kill at 31-41 ticks, on 26.3 and 26.2), 1104-menu-pvstyle (NEU Museum pixel look, failed twice in the gate and once alone, then passed 3 of 3 on the new and old jar), 1105-blink-oneshot-same-click. Inventory Walk "Stand Still In Server Menus" replaces Hide Keys; 1108-iw-termwalk shows FLAGGED "flags expected; 0 lines" in the summary, that is its label, totals say passed. 95-perf-gate has no verdict row: it passed when it ran without throwing.
- Ship 19 (ship19-1109, cmdprefix): a first full gate on three builds still showed load failures that all passed on rerun: "Anticheat test server exited with code 0 before it was ready" in whole runs (G2, G3, G5 on tk-ship19/tk-ship19b, 21 cases), one 26.2 G4 hang at 1200 s, a 26.2 1101-wp with no summary, 1105-blink-oneshot-same-click, and 95-perf-gate over budget on defaults and every feature (about 1.6x). Rerun those groups on other checkouts (tk-ship19b/c/d) and the perf gate and same-click alone (KM_CLIENT_CAP=1): all green. ship19-rerun.sh is ship19-gate.sh cut after its helpers; the gate was ~2 h. A waitfor call over 175 s is moved to the background: pass 165 as its third argument.
- Ghost mobs (ghostmob-1109, killer560 2026-11-09): a server sends every entity it still tracks for you a move packet at least every 60 server ticks even standing still (26.3 `ServerEntity.sendChanges` `tickCount % 60`, javap; 1.8 tracker `m % 60`), so `mobesp/GhostMobs` (Hide Ghost Mobs, Dungeon ESP tab, default OFF) hides a dungeon mob or ❤ name tag with no packet for 200 ticks while others were heard within 80; `GhostMobs.hidden(e)` is the filter for any new mob consumer (ESP, starredMobs/Auto Clear, CryptMobs, the ObjectHider render hook use it). On Hypixel it is unproven: read the `[GhostMobs] census` line (killer560smod-ghostmobs, once per dungeon world) in his log: "heard again (false)" above 0 means a real mob was hidden and the threshold is wrong. Testkit 1109-ghostmob-hide (hx session).
- Untested pass (untestbugs-1109, mod km-untestbugs / testkit tk-untestbugs): Chat Tabs draw from a HEAD inject on ChatScreen.extractRenderState (ChatTabsScreenMixin) so vanilla's command suggestions draw over them (an afterExtract draw landed on top); the suggestion box rect is read by reflection (private in 26.x). Dark Mode tints the chat screen and HUD editor through DarkModeFeature.tintScreen (AFTER_INIT listener registered at CLIENT_STARTED so it runs last); it tints every screen the mod skins (chat, HUD editor, any com.killer560.hub screen, the AH and Bazaar reskins); vanilla and other mods' screens stay untinted by design. Auto Leap: each leap has its own Off/Fast/Auto and its own Target Mode on its Universal / Set Class Targets row (FastLeapConfig TargetSettings.mode, an old global targetMode becomes every leap's mode), leaps go by class only (saved names are turned into classes or emptied on load, logged once), and the main page has no leap list. A tooltip key is the label before the colon, so a button whose label changes (Start/Stop Autopilot) needs a key per label. Secret Sound waits only the connection's round trip for a chest (clamp(latency*2+50,150,500) ms); a locked-chest line inside it still silences the sound. Secret Aura and Secret Triggerbot are one card (SecretAuraTab, toggle key via ModuleKeybinds EXTRAS 'SecretTriggerbotTab'); Ghost Block and Dungeon Waypoints cards are gone (config files in RETIRED_NAMES). Auto Dialogue has no NPC filter (old key ignored). Status rows (Auto Croesus Idle, Secret Aura Tracked) are removed; Autopilot/Auto Secret buttons read Start/Stop. No Rotate (NoRotateTab) is the old Leap Takes Target's Look, same FastLeapConfig key. Auto Ult has an Ult When section. Reroll on floors defaults F7 and M7 only.
- Ship 21 (ship21-1109, 20 branches + balatefix): a test that waits ticks for a feature that runs on the real clock (Reparty delay/accept window, Collection Tracker Only Recent's 10 s) fails in a loaded session and passes alone: wait on System.currentTimeMillis (testkit 4e283e59, 6ae4e132). A big hx session leaves items on the floor (get(0) of an item list is not your Decoy: kill @e[type=item] first) and a block selected in the hotbar (an AP3 BLOCK node places the HELD block first; hold the empty slot). 63-ap3-session needs more than 110 lanes when run as one group (now 170). A method inherited from BasicHud is not found by ModUnderTest.call (getDeclaredMethod): use getMethod; the stale 63-ap3-stopwatch-hud failure left the HUD editor open and broke every later AP3 walk. The `63-ap3-,1101-ap3-,1105-ap3-` group fails 1105-ap3-same-block-swap/-tp the same on origin/26.3 jars and passes alone 5 of 5 on both (group-order state, open). Two parallel cheat boss sessions (500-boss-session G4) both hung at 1200 s while the legit one passed; alone it passes (87 s of boss cases).
- Look Key pointer freeze (invlook-1110): `InventoryWalkLookMixin` makes `MouseHandler.xpos()/ypos()` and `getScaled*Pos` return the menu's saved spot while looking, but 26.2's `onMove` takes its deltas from the FIELD, so a test that moves the mouse as `xpos() + dx` turns once and then nothing (1105-iw-look "look feel" 0.023 deg/count on 26.2 only); testkit moves from the raw `xpos`/`ypos` fields (`InvWalkCarryTests.rawPos`). Ship gate rule: a 26.2 compile alone does not show a mixin acts, run the case on 26.2 too.
- Ship 23 (nobosspearl-1110, Block Ender Pearls In Boss): `412-ui-no-new-tab` failed on origin master and on the baseline jar alike ("tooltip changed for '(unscoped)' row 'auto close chest' / 'wither dragons' ... now none"): a mod tooltip rewrite (autoclose-1109, dragons1108) needs an `(unscoped)` entry in the testkit's menu-layout-merged.json next to its owner-scoped one, or 412 fails; fixed in tk 3997745a. A case name can ride in an hx scenario after the session (`100-hx-session:,1110-bosspearl,412-ui-no-new-tab` ran both). A ship-priority head at the clientslot gate held the queue for over 2 h (14:00-15:40 on 11-10), so a short run waits, it is not stuck. To keep a play instance on a private jar through `deploy-to-instances.py` (it has no skip flag), copy that jar aside and back after the deploy.
- Ship 22 (ship22-1110, coopsim + darkall + ap3 lever + ...): a gate group of a session suite needs its session prefix: `63-ap3-session:,` for the AP3 groups and `500-boss-session:,` for boss cases; without it the run reports 0 cases (the first ship22b gate ran 0 AP3 cases, and the boss group G13 `1101-boss-,...` showed "0 passed, 0 failed, 23 other"). When Lever Aura's setting changed from CPS to a cooldown in ms (cheatbugs-1109), the EE3 route cases (setCps / getCps) and 1109-boss-lights failed with "could not call LeverAuraConfig.setCps" until the tests used CooldownMs (testkit 34db5220); LightsDeviceConfig still has Cps. `1109-coop-two` fails on 26.2 (guest `DungeonState.isSimBoss` false) the same way on the ship21 jar (7e216e3c) and on ship22, 3 of 3 runs, while 26.3 cheat and legit pass: an open 26.2 co-op bug, not a ship22 regression. The cheat boss session G4 (`500-boss-session:,1108-,1109-,1104-pz-beams-opening,1025-boss-relic`) hung at 1200 s on 26.3 and 26.2 in the full group but its parts (1108-, the pz/relic pair, 1109- via G11) all passed alone: run it split. A test run killed with "NTSTATUS 0xFFFFFFFF / NOT RUN" (the PC freeze and restart) is rerun whole. 350-logic-catalog needs the catalog regenerated again after each merge that adds a regex (Mask Invincibility pet lines). Other agents push to origin while a ship gates (ship23 landed during this one): fetch and merge before the push, rebuild, and rerun only G1 plus the cases of the merged features.
