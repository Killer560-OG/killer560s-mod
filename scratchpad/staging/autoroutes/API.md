# Auto Routes core — what was actually produced

Package `com.killer560.hub.autoroutes` (staging mirrors `src/main/java/com/killer560/hub/autoroutes/`).
Everything compiles against the real repo sources + MC 26.1.2 with the JDK 25 javac
(`scratchpad/args-autoroutes.txt`, `-sourcepath` = repo `src/main/java` + generated `BuildVariant` + this staging dir).

## Files

| File | Role |
|---|---|
| `RouteNode.java` | Node model. `enum Type {START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND}` with `Type.parse("ew"/"breaker"/"db"/...)` and `label()`. `enum AwaitCondition {SECRET, DELAY}`. Public fields + accessors (`type()`, `x()`, `y()`, `z()`, `yaw()`, `pitch()`, `pathIndex()`, `radius()`, `colour()`, `breakerBlocks()`, `item()`, `awaitCondition()`, `awaitAmount()`, `command()`), `describe()`, `boundingBox(realPos, height)`, `contains(...)`. |
| `Route.java` | `roomName()`, `nodes()` (live list), `path()`, `startNode()`, `indexOf(node)`, `nodesInPathOrder()`, `isEmpty()`. |
| `RoutePath.java` | Per-tick samples (`record Sample(x,y,z,yaw,pitch,keys,onGround)` + key bit helpers), `seek`, `nearest`, `lookahead`, `reached`, compact one-line `encode()` / defensive `decode(text, maxSamples, maxAbsCoord)`. |
| `RouteCoords.java` | `record Frame(roomName, clayX, clayZ, rotation)` with `Frame.current()` (Live Map). `toReal/toRelative` for `Vec3`/doubles (cell-preserving, matches `RoomDatabase` block transform), `toRealBlock/toRelativeBlock` (delegates to `RoomDatabase`), `toRealYaw/toRelativeYaw`. `RoomDatabase` untouched. |
| `AutoRoutesConfig.java` | Settings, `killer560smod-autoroutes-settings.json`. |
| `RouteStore.java` | All routes, one file `killer560smod-autoroutes.json`. |
| `RouteRotation.java` | Human-looking rotation controller (SimonSays model). Delta-only writes, never wraps the live yaw. |
| `RouteRecorder.java` | `/ar start record`, `/ar stop record`, `/ar add <type>`. |
| `RouteExecutor.java` | Playback (path seek, keys via mixin or key mappings, rotation, discrete actions with confirmation, safe abort). |
| `AutoRoutesFeature.java` | Registration, tick/render wiring, interlocks, edit mode, public helpers. |
| `AutoRoutesRenderer.java` | Node markers (Box/Filled/Cylinder), colours, active highlight, chain line, edit-mode labels/path/breaker-block highlight. |
| `ItemIdentity.java` | Stable item identity (Skyblock id minus `STARRED_`, or stripped display name). |
| `mixin/AutoRoutesInputMixin.java` + `resources/killer560smod-autoroutes.mixins.json` | Movement input rewrite (same technique as `PathWalkInputMixin`). `require = 0`, `"required": false`; falls back to key mappings if not registered. |

## Public API (exactly as requested)

```java
AutoRoutesConfig.getInstance()            // + save()
RouteStore.getInstance()                  // routes(), forRoom(String), save()
RouteRecorder.startRecording()            // String status for chat, null if it failed (reason already sent via ModChat)
RouteRecorder.stopRecording()             // String status, null if not recording
RouteRecorder.isRecording()
RouteRecorder.addNode(RouteNode.Type type) // String status (null on failure) — captures from look/position/held item
RouteExecutor.isRunning()
RouteExecutor.stop(String reason)
AutoRoutesFeature.setEditMode(boolean on)
AutoRoutesFeature.isEditMode()
AutoRoutesFeature.onEditRightClick(BlockPos pos, boolean shift)  // boolean, true if consumed
AutoRoutesFeature.currentRouteNodes()     // List<RouteNode> (the LIVE list), empty if none
AutoRoutesFeature.deleteNode(int index)   // boolean
AutoRoutesFeature.clearCurrentRoute()     // boolean
```

Added per the later spec changes:

```java
RouteStore.reload()          // static; re-reads the file, stops a running route / discards a recording first
RouteStore.routesDirectory() // static; java.nio.file.Path of the folder containing the routes file
RouteStore.routesFile()      // static; the file itself
```
Both are static, so `RouteStore.reload()` and `RouteStore.getInstance().reload()` compile.

Extra conveniences: `RouteRecorder.addNode(Type, String argument)` (command text / `"secret 2"` / `"delay 500"`),
`RouteRecorder.discard()`, `RouteStore.getInstance().all()`, `.roomNames()`, `.forRoomOrCreate(room)`, `.put(route)`,
`.remove(room)`, `.lastLoadFailed()`, `AutoRoutesFeature.onMapRoomClicked(DungeonLayout, int room)` (interlock 2 hook).

`routes()` returns `Map<String, Route>` (unmodifiable view keyed by room name).

## AutoRoutesConfig accessors (names as the UI agent assumed)

`isEnabled()` (cheat-gated + SkyblockGate) / `isEnabledRaw()` / `setEnabled(boolean)`
`isLegitMode()` / `setLegitMode(boolean)`
`isStartFromStartNodeOnly()` (forced true in legit mode) / `isStartFromStartNodeOnlyRaw()` / `setStartFromStartNodeOnly(boolean)`
`isUniformColor()` / `setUniformColor(boolean)`
`getUniformColorArgb()` / `setUniformColorArgb(int)`
`getActiveColorArgb()` / `setActiveColorArgb(int)`
`getNodeColorArgb(RouteNode.Type)` / `setNodeColorArgb(RouteNode.Type, int)` / static `defaultNodeColor(Type)` / `colorFor(RouteNode)`
`getRenderStyle()` / `setRenderStyle(RenderStyle)` — nested `enum RenderStyle {BOX, FILLED, CYLINDER}` with `label()`
`getThickness()` / `setThickness(float)` (1–8, 0.5 steps)   `getHeight()` / `setHeight(float)` (0.1–1.0)
`getInteractDelayTicks()` / `setInteractDelayTicks(int)` (0–6)
`getMaxDriftDistance()` / `setMaxDriftDistance(double)` (0.5–6)   `getReachTimeoutTicks()` / `setReachTimeoutTicks(int)` (10–400)
`isChatFeedback()` / `setChatFeedback(boolean)`
`getKeybind(String id)` / `setKeybind(String id, int code)` — `AutoRoutesConfig.KEYBIND_IDS` =
`startRecord, stopRecord, addEtherwarp, addBreaker, addUse, addWalk, addBoom, addAwait, addStart, editDb, clear, list, deleteLast, reload`
(ids matched case-insensitively; unknown ids are accepted and persisted too). All default `KeyUtil.NONE`.
Min/max constants are public (`MIN_THICKNESS`, ...).

## Integration checklist for the main session

1. Copy `com/killer560/hub/autoroutes/**` into `src/main/java/com/killer560/hub/autoroutes/`.
2. Copy `resources/killer560smod-autoroutes.mixins.json` into `src/main/resources/` and add it to `"mixins"` in `fabric.mod.json` (an unregistered config silently does nothing; the executor then falls back to key mappings).
3. `Killer560ModClient`: `com.killer560.hub.autoroutes.AutoRoutesFeature.register();`
4. `ProfileManager.reloadAllConfigs()`: add `com.killer560.hub.autoroutes.AutoRoutesConfig::load` and `com.killer560.hub.autoroutes.RouteStore::reload`.
5. Interlock 2 hook: in `InteractiveMapScreen.onClick`, in the `gid >= 0 && canTeleport` branch, before `AutoClearUtils.pathToRoom(layout, room, tile, 0)`:
   `if (com.killer560.hub.autoroutes.AutoRoutesFeature.onMapRoomClicked(layout, room)) return;`
6. UI agent's pieces: commands, keybinds, `NewTab` cheat-only block, `UseBlockCallback` -> `onEditRightClick`.

## Routes file layout

```json
{ "version": 1, "note": "...",
  "routes": {
    "Room Name": {
      "nodes": [ { "type": "ETHERWARP", "x": 12.5, "y": 69.0, "z": 4.5, "yaw": 90.0, "pitch": 45.0, "at": 37,
                   "landing": "20.500 70.050 4.500" },
                 { "type": "USE_ITEM", ..., "item": "SPIRIT_SCEPTRE" },
                 { "type": "DUNGEON_BREAKER", ..., "blocks": ["3 70 5", "3 71 5"] },
                 { "type": "AWAIT", ..., "await": "SECRET", "amount": 1 },
                 { "type": "COMMAND", ..., "command": "/pc hi" } ],
      "pathNote": "recorded movement - edit the nodes above, not this",
      "path": "x y z yaw pitch keys ground;..."
    } } }
```
Optional per node: `"radius"` (diameter, default 1.0), `"colour": "#AARRGGBB"`. `"at"` is the path sample index.
Caps on load: 500 routes, 200 nodes/route, 36 000 samples/route, 20 breaker blocks/node, |coord| <= 512, room name 100,
item 100, command 256 chars; NaN/inf dropped; a malformed node is skipped; an unparsable file is copied to
`killer560smod-autoroutes.broken-<time>.json` and never saved over (until the user explicitly changes routes).
