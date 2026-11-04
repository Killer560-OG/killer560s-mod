# Compiling

Moved out of CLAUDE.md (2026-10-05), then out of LESSONS.md (2026-10-07). Problem, then fix; verified only.

- **Never write a Minecraft API call from memory - grep for a call site in this repo first.** A cloud session
  cannot compile (the network policy blocks `maven.fabricmc.net` and Mojang's hosts), so a wrong method name is
  not caught until killer560 runs the build, and it costs him a whole round trip. Three in one batch on
  2026-10-01: `Entity.moveTo` is `snapTo` in 26.1.2, `EntityType.BAT` belongs behind `McEntities.BAT` because it
  is one of the names that moved in 26.2, and `BlockState.isCollisionShapeFullBlock` was a guess at a predicate
  that could have been several things. Every one of them had a working equivalent already in the tree -
  `SimMiniboss.snapTo`, `SimMobs`' bat spawn, `TeleportUtils`' `getCollisionShape(...).max(...)`. The rule is
  mechanical: before using a vanilla method or constant that does not already appear in `src/`, either find it
  there or pick something that does. METHOD names are what move between versions - and so do some block
  constants: a colored block (`Blocks.RED_WOOL`) does not exist in 26.2 and must be `McBlocks.RED_WOOL`, which
  broke only the 26.2 build of `a4e563a`. The same commit also broke 26.1.2 with
  `SoundEvents.ELDER_GUARDIAN_HURT.value()`: only some `SoundEvents` are holders (`NOTE_BLOCK_PLING`,
  `GENERIC_EXPLODE`); mob sounds like `BLAZE_HURT` are plain `SoundEvent`s. Copy the shape of an existing use.
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
  the rule below about API names - an unresolved method is indistinguishable from a misspelled one here.

## Two versions: 26.3 (main) and 26.2 (since 2026-11-01)

- **Build both, every time.** 26.3: `./gradlew build`. 26.2: `./gradlew build -Pminecraft_version=26.2
  -Pfabric_api_version=0.160.0+26.2 -Pmodmenu_version=20.0.2`. Between versions or variants delete
  `build/generated/sources/buildVariant` and `build/classes`. 26.1.2 is dropped; there is no mc26_1 any more.
- **src/main/java speaks 26.3.** Write 26.3's class names. A class that only moved or was renamed between the versions
  goes in `src/mc26_2/remap.txt` as `26.3 name = 26.2 name`; the 26.2 build compiles a rewritten copy under
  `build/generated/sources/remapped/mc26_2/`, so a 26.2-only compile error is reported THERE (same relative path and line
  as the real file under src/main/java - fix the real file). The rewrite also renames the simple name in a file that
  imports the class or its package, comments included, never string literals unless they hold the bare simple name.
- **What remap.txt cannot do:** a changed METHOD (rename, new argument, record accessor) needs a facade in the compat
  classes, written in both `src/mc26_3/java` and `src/mc26_2/java` with identical public signatures; and mixin STRINGS
  (slash descriptors like `Lcom/mojang/blaze3d/pipeline/RenderPipeline;`) are never rewritten, so a descriptor naming a
  moved class lists both versions' forms or lives in a per-version mixin copy.
- **Where the 26.3 differences live** (2026-11-01 port): packet getters that became record accessors (`getHand()` ->
  `hand()`, particle/chunk/remove-entities packets, the move-entity packet's stepped VecDelta) in `McPackets`; arm swings in
  `McCompat.swingAttack` (left-click actions: animation + `ServerboundPunchPacket`, what vanilla's startAttack sends) and
  `McCompat.swingUse` (right-click actions: animation only on 26.3, the swing packet on 26.2 - both as each version's
  vanilla does); keys, mouse buttons and the window (SDL3 replaced GLFW) in `McInput`/`McWindow` with configs keeping GLFW
  numbers (`util/Keys`); `PoseStack.mulPose(quaternion)` -> `McRender.rotate`; entity renames (`setPermanentlyInvulnerable`,
  `invulnerableTime` behind a getter, `InterpolationHandler.target()`, `drop(..., Prediction)`) in `McEntities`;
  `SoundInstance.resolve` -> `getOrResolve` behind `McSoundInstance`; authlib 10's `MinecraftServicesDiscoveryService` in
  `McCompat.createUserApiService`; first-person hands and the GPU API in `McRender` plus per-version mixins
  (docs/LESSONS-GUI.md).
- **Checking mixins on a version:** `tools/mixin-target-survey.py` (names), `tools/mixin-descriptor-check.py` (full
  descriptors in mixin strings) and `tools/mixin-handler-check.py` (handler arguments against the target). Pass the mapped
  jar from `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/<version>/` as a Windows path (`cygpath -m`);
  a `/c/...` path makes javap find nothing and every target reads as missing. Expected noise: alternatives listed for the
  other version, Fabric impl classes (not in the Minecraft jar), ModMenu's ModListWidget, generic erasure (`Packet<?>`,
  `E extends Entity`, `AvatarlikeEntity`), and an INVOKE owner that only inherits the method (javac writes the receiver's
  static type as the owner, so check the bytecode with `javap -c` before "fixing" it).

Automation runtime lessons (Auto Routes on GrimAC, chain timing, ViewFreeze, held camera, key range) moved to
[LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md) on 2026-10-06 to keep this file under its size limit.

## Static bug scans: SpotBugs and Error Prone (scanbugs-1104)

Both run OUTSIDE the normal build (nothing in build.gradle; the owner's builds are no slower). Build the variant first
(`./gradlew build [-PcheatBuild=true]`), then from the repo root, through Bash, with JDK 25 on JAVA_HOME:

    bash tools/spotbugs/run-spotbugs.sh [-PcheatBuild=true]     # ~2.5 min -> build/spotbugs/report.xml + report.txt
    bash tools/errorprone/run-errorprone.sh [-PcheatBuild=true] # ~3 min   -> build/errorprone/report.txt

- Both get the compile classpath from the init script `tools/spotbugs/classpath.gradle` (task `writeSpotbugsClasspath`,
  writes build/spotbugs/aux-classpath.txt), so Loom's mapped Minecraft jar is on it.
- SpotBugs 4.10.5 (reads Java 25 class files): unzip the release to `C:/Users/Hunter/tools/spotbugs-4.10.5` or set
  `SPOTBUGS_HOME`. It scans build/classes/java/main at `-effort:max -medium`; `tools/spotbugs/exclude.xml` drops
  MALICIOUS_CODE, serialization, I18N and the unwritten/unused-field patterns in `.mixin.` classes (Mixin fills them).
- Error Prone 2.50.0: a separate javac pass with `-Xplugin:ErrorProne` over src/main/java, src/mc26_3/java and the
  generated BuildVariant; its class output (build/errorprone/classes) is thrown away. Put
  `error_prone_core-2.50.0-with-dependencies.jar` and `dataflow-errorprone-3.41.0-eisop1.jar` (Maven Central) in
  `C:/Users/Hunter/tools/errorprone-2.50.0` or set `ERRORPRONE_HOME`. Style checks are switched off in the script. It
  exits 1 when an ERROR-level check fires; the report is still complete.
- Triage helpers in tools/spotbugs: `summarize.py` (one line per finding, `--priority`), `lines.py` (source line per
  finding of a pattern), `np_called.py` (null-return findings grouped by called method), `guard_check.py` (null guards
  above each site). Run them with `python -I`.
- Known false-positive families (do not re-triage): BC_IMPOSSIBLE_CAST on mixin `(Object) this`, IM_BAD_CHECK_FOR_ODD on
  nonnegative grid indices, ES_COMPARING_STRINGS on identity caches, NP on `Path.getParent()` of absolute config paths,
  AT_*/ST_WRITE_TO_STATIC (client-thread statics). The 2026-11-04 triage is killer560s-mod-logs/scan-scanbugs-1104.md.
