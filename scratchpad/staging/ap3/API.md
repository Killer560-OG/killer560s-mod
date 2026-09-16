# AP3 core — what was produced

Staging root: `scratchpad/staging/ap3/` (mirrors `src/main/java/` + `src/main/resources/`). Everything compiles
against the real repo sources + the 26.1.2 jar with the JDK 25 javac:

```
javac @scratchpad/staging/ap3/args-ap3.txt @scratchpad/staging/ap3/sources.txt          # the new classes
javac @scratchpad/staging/ap3/args-ap3-patched.txt <patched-src files> ClassOverrides.java   # the patched repo files
```
(`args-ap3.txt` = the Auto Routes UI args with `-sourcepath` = this staging dir + `build/generated/.../BuildVariant`
+ `src/main/java`; `args-ap3-patched.txt` puts `patches/patched-src` ahead of `src/main/java`.)

## Files

| File | Role |
|---|---|
| `com/killer560/hub/ap3/Ap3Node.java` | Node model. `enum Type {LINE, AXIS_LINE, WALK, RUN, LEAP, LEAP_DETECTOR, TERMINAL, WAIT, STOP, LOOK, BREAKER}` (+ `parse`, `label`, `isCorridor`, `isMover`, `isWaiting`), `enum LeapMode {DEFAULT, CLASS, IGN}`, `enum WallAxis {NONE, FRONT, LEFT, RIGHT}`. Public fields + accessors: snapped `x y z`, `yaw pitch`, `length width`, `waitMs`, `leapMode leapClass leapIgn`, `leapCount`, `breakerBlocks` (absolute `BlockPos`), `colour` (ARGB or null), `wallAxis wallDistance`. Clamped setters `setLength/setWidth/setWaitMs/setLeapCount`. `snapXZ()` / `snapY()`, `dir()`, `left()`, `lateralOffset(Vec3)`, `alongOffset(Vec3)`, `boundingBox(height)`, `describe()` (no index — caller prefixes the 1-based `#n`), `copy()`. |
| `Ap3Chain.java` | `section()` 1–5, `classFilter()` (null = any), `nodes()` (live list), `key()` `"S3"`/`"S3:MAGE"`, `label()` `"S3 (Mage)"`, `indexOf`, `isEmpty`. |
| `Ap3Store.java` | One shareable `config/killer560smod-ap3.json`. |
| `Ap3Config.java` | Settings in `config/killer560smod-ap3-settings.json`. |
| `Ap3Executor.java` | Runs a chain: movement, alignment, waiting, advancing, every abort path. |
| `Ap3Feature.java` | Registration, tick/render/chat wiring, BOSS-ONLY + P3 gate, edit mode, the public API. |
| `Ap3Renderer.java` | Markers, corridors (length × width), walk arrows, chain line, active highlight, 1-based labels, breaker-block highlight in edit mode. |
| `Ap3EditInput.java` | `UseBlockCallback` breaker block picking (same approach as `AutoRoutesEditInput`). |
| `mixin/Ap3InputMixin.java` | Analog `moveVector` drive on `KeyboardInput#tick` (TAIL). |
| `mixin/Ap3RotationSendMixin.java` | LOOK's "client-side only": holds rotation off the wire around `LocalPlayer#sendPosition`. |
| `resources/killer560smod-ap3.mixins.json` | **MUST be added to `fabric.mod.json`** — see `patches/fabric.mod.json.diff`. Both mixins are `require = 0` / `"required": false`; without the config the executor drives via key mappings (8-way) and LOOK sends rotation normally. |
| `com/killer560/hub/dungeonclass/ClassOverrides.java` | Mod-wide IGN → class override, `config/killer560smod-classoverrides.json`. |
| `patches/*.diff` | Unified diffs for the six existing files (below). `patches/patched-src/` holds the edited copies they were generated from. |

## Public API (names exactly as requested)

```java
Ap3Config.getInstance()                 // + save()
Ap3Store.getInstance()                  // chains(), forSection(int), save(); static reload(), directory()
Ap3Executor.isRunning()
Ap3Executor.start()                     // boolean; chats the reason on failure
Ap3Executor.stop(String reason)
Ap3Feature.addNode(Ap3Node.Type type)   // boolean; snapped position, current look, chats "Added #n ..."
Ap3Feature.addWaitNode(int millis)      // boolean
Ap3Feature.currentChainNodes()          // List<Ap3Node> (LIVE list), empty if none
Ap3Feature.deleteNode(int index)        // boolean, 0-based (chat shows index+1)
Ap3Feature.clearCurrentChain()          // boolean
Ap3Feature.setEditMode(boolean on)
Ap3Feature.isEditMode()
Ap3Feature.onEditRightClick(BlockPos pos, boolean shift)  // boolean, true if consumed
ClassOverrides.getInstance()            // + save()
ClassOverrides.classOf(String ign, DungeonClass detected)  // static
ClassOverrides.set(String ign, DungeonClass cls)           // static, boolean, SAVES
ClassOverrides.clear(String ign)                           // static, boolean, SAVES
ClassOverrides.all()                    // static Map<String, DungeonClass> (IGN as typed → class, unmodifiable copy)
```

Return types the request left open: `addNode`/`addWaitNode`/`deleteNode`/`clearCurrentChain` are `boolean`
(the feature sends its own chat on both success and failure, like `AutoRoutesFeature.deleteNode`).
`Ap3Store.forSection(int)` returns the **class-less** `Ap3Chain` for that section or null (same shape as
`RouteStore.forRoom`); `forSection(int, DungeonClass)` returns the chain that would actually run for that class;
`chainsFor(int)` returns every chain of the section; `exact(int, DungeonClass)`; `forSectionOrCreate(int, DungeonClass)`;
`remove(Ap3Chain)`; `markEdited()`; `lastLoadFailed()`; static `file()`.

### Extras the commands / tab will want

```java
Ap3Feature.addNode(Type, double length, double width)
Ap3Feature.addLeapNode(Ap3Node.LeapMode mode, DungeonClass clazz, String ign)   // "add leap [class x | ign y]"
Ap3Feature.addLeapDetectorNode(int count)
Ap3Feature.deleteLastNode()             // the deleteLast keybind
Ap3Feature.saveChains()                 // after editing node fields in place (stops a running chain)
Ap3Feature.describeCurrentChain()       // List<String> "#1 Line [len 3.0, width 1.0] @ x, y, z" for /ap3 list
Ap3Feature.currentChain()               // Ap3Chain or null
Ap3Feature.currentChainLabel()          // "S3", "S3 (Mage)" or "no section"
Ap3Feature.currentSectionNumber()       // 1-5, 0 when not in P3
Ap3Feature.isP3Live()                   // the gate
Ap3Feature.selfClass()                  // your class through ClassOverrides
Ap3Executor.start(Ap3Chain)             // run a specific chain (gates already passed)
Ap3Executor.wasStoppedByUser() / clearStoppedByUser() / stopReason()
Ap3Node.Type.parse("axisline"|"al"|"db"|...), Ap3Node.LeapMode.parse("class"|"ign"|"default")
ClassOverrides.has(ign), ClassOverrides.clearAll(), ClassOverrides.MAX_ENTRIES
```

"Current chain" = the section you are standing in (`Floor7Tracker.getStageAt()`, else `getStage()`) + the tab's
**edit class filter** (`Ap3Config.getEditClassFilter()` / `setEditClassFilter(DungeonClass)`, null = the
class-less chain, persisted). Adding a node creates that chain if needed. Nodes can only be placed while
`isP3Live()` (absolute coordinates — the arena is fixed).

### Ap3Config accessors

Master: `isEnabled()` (cheat-gated + `SkyblockGate`), `isEnabledRaw()`, `setEnabled`.
General: `isChatFeedback/setChatFeedback`, `isDiagonalWalk/setDiagonalWalk` (+ `Ap3Config.DIAGONAL_WALK_TOOLTIP`),
`isContinueIntoNextSection/set…`, `getEditClassFilter/setEditClassFilter(DungeonClass)`, `getDefaultWaitMs/set…`.
Rendering: `isUniformColor/set…`, `getUniformColorArgb/set…`, `getActiveColorArgb/set…`,
`getNodeColorArgb(Type)/setNodeColorArgb(Type,int)`, `colorFor(node)`, `getThickness/set…` (1–8),
`getHeight/set…` (0.1–1), `isShowLabels/set…`, `Ap3Config.defaultNodeColor(Type)`.
Executor: `getAlignTolerance/set…` (0.01–0.25 blocks), `getAlignTimeoutTicks/set…` (20–400),
`getMoveTimeoutTicks/set…` (20–600), `getLeapDetectRadius/set…` (2–12 blocks).
Keybinds: `getKeybind(String id)` / `setKeybind(String id, int glfwCode)` with ids in `Ap3Config.KEYBIND_IDS`
(`addLine addAxisLine addWalk addRun addLeap addLeapDetector addTerminal addWait addStop addLook addBreaker editDb
list deleteLast clear reload start stop`, constants `Ap3Config.KEY_*`) **and** typed pairs
`getAddLineKey()/setAddLineKey(int)` … `getStopKey()/setStopKey(int)`. All default `KeyUtil.NONE`.
MIN/MAX constants for every slider are public on the class.

## Node semantics as built (confirm with killer560 — see the report)

* **Snapping**: X/Z to the nearest 0.5 (block centre or seam), Y to the block floor; done once at placement and
  re-applied on load for hand-edited files.
* **LINE** (active node): perpendicular-only alignment to the node's centre line, sneaking for the last 0.3
  blocks, done when within `alignTolerance` and stopped. Refuses (stops the chain) when the player is outside
  `[-1, length+0.5]` along the line or more than `width/2 + 0.25` off it.
  **Also** a passive corridor for any WALK/RUN of the same chain pointing the same way (±10°): while the player is
  inside `[-0.5, length]` × `±width/2`, a lateral nudge is blended into the walk — never a push along the travel.
* **AXIS_LINE**: at placement the nearest collidable wall among front / left / right (within 8 blocks, chest height,
  `Level.clip`) is recorded (`wallAxis`, `wallDistance`). Alignment then holds that distance to the wall on that
  axis and centres on the node on the other axis (lateral for a front wall, along for a side wall). Same corridor
  behaviour as LINE.
* **WALK / RUN**: travel `length` blocks along the recorded yaw (measured from the node), camera untouched,
  stuck timeout `moveTimeoutTicks`. RUN sets the sprint key; vanilla only starts a sprint with a forward
  component, so a direction behind the camera walks.
* **LEAP**: DEFAULT = Fast Leap's P3 target for the chain's section (`LeapTarget.S1..S4`, Name / Class / Posmsg
  resolution mirrored from `FastLeapFeature.leapToConfigured`, which is package-private); S5 has no Fast Leap
  target → needs a class/IGN. Done when `LeapManager` clicked the menu and you moved 4+ blocks (or 1.5 s after the
  click for a short teleport); stops on a Fast Leap failure or after 10 s.
* **LEAP_DETECTOR**: no Hypixel chat line exists for "X leapt to you"; counts teammates whose position jumps
  8+ blocks in one tick (or who appear from unloaded) and land within `leapDetectRadius` of you. Waits indefinitely
  (left-click to skip).
* **TERMINAL**: advances only on Hypixel's `<you> (activated|completed) a (terminal|lever|device)! (n/m)` line
  (same regex as `Floor7Tracker.Stage`). A GUI close does nothing.
* **WAIT**: milliseconds. **STOP**: no input until the slide has died (≤ 2 s), then continues — it ends the chain
  only when it is the last node. **LOOK**: `RouteRotation.beginApproach` (delta-only writes), rotation held off
  the wire by the mixin until the chain ends or the player turns the mouse. **BREAKER**: as Auto Routes.
* **Manual left-click** (physical button via GLFW, polled per tick AND per frame, ignored while any screen is
  open) advances the current node, whatever it is.
* **Stops**: your movement keys (any node, mixin path or fallback path), your mouse while LOOK is turning, a
  screen the node did not ask for (TERMINAL may have any screen; LEAP may while `LeapManager` is busy), world
  change, death/spectator, leaving P3, `/ap3 stop`, edit mode, chain edits. Nothing auto-arms; a stop stays stopped.
  `continueIntoNextSection` (default OFF) starts the next section's chain only after a chain COMPLETED and only
  when you are in a different section.

## Integration checklist (main session)

1. Copy `com/killer560/hub/ap3/**` and `com/killer560/hub/dungeonclass/ClassOverrides.java` into `src/main/java`.
2. Copy `resources/killer560smod-ap3.mixins.json` into `src/main/resources` and apply
   `patches/fabric.mod.json.diff` — **an unregistered mixin config silently does nothing.**
3. Apply `patches/Killer560ModClient.diff` (registers `Ap3Feature` + `Ap3EditInput`; add the UI agent's
   `Ap3Commands.register()` / `Ap3Keybinds.register()` next to them).
4. Apply `patches/ProfileManager.diff` (`Ap3Config::load`, `Ap3Store::reload`, `ClassOverrides::load`).
5. Apply the class-lookup patches: `patches/PartyTracker.diff`, `patches/Teammates.diff`,
   `patches/ClassColors.diff`.
6. Add the AP3 tab (cheat-only block of the New tab, `isCheatOnly()` true) and the Class Overrides settings
   section; surface `ClassOverrides.all()` read-only in the AP3 tab.
7. README Features list + TESTING.md entries.
8. Boot test: look for `[AP3] Registered` and, in a P3 run, the absence of
   `[AP3] Input mixin did not apply` in the log.
