# Auto Routes UI - integration notes

Staging root: `C:\Users\Hunter\killer560s-mod\scratchpad\staging\autoroutes-ui\`

## Files to copy into the repo (paths are already package-shaped)

| Staging file | Repo destination |
|---|---|
| `src/main/java/com/killer560/hub/autoroutes/AutoRoutesCommands.java` | `src/main/java/com/killer560/hub/autoroutes/` |
| `src/main/java/com/killer560/hub/autoroutes/AutoRoutesKeybinds.java` | `src/main/java/com/killer560/hub/autoroutes/` |
| `src/main/java/com/killer560/hub/autoroutes/AutoRoutesEditInput.java` | `src/main/java/com/killer560/hub/autoroutes/` |
| `src/main/java/com/killer560/hub/gui/tab/AutoRoutesTab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `tooltips.txt` | paste the `d.put(...)` lines into `gui/SettingTooltipsData.java` (next to the Lever Aura block) |

**Do NOT copy `compile-stubs/`** - those are throwaway stand-ins for the core agent's classes, used only so
this half could be type-checked before the core existed. `args.txt` is the javac args file used for that check.

## No mixin

None needed. Edit-mode right-clicks go through Fabric's `UseBlockCallback` (already used by `KingRelicsFeature`).
Verified with javap against fabric-events-interaction-v0 5.2.6 and the 26.1.2 jar: returning `InteractionResult.FAIL`
from the client-side hook sends no `ServerboundUseItemOnPacket` and makes `Minecraft#startUseItem` return before the
item-use / off-hand passes. So there is **no `killer560smod-autoroutes.mixins.json` and nothing to add to
`fabric.mod.json`** from this half. (If the core agent ships a mixin config of its own, that one still has to be
registered there - unregistered mixin configs are silent.)

## Wiring the main session must do

### 1. `Killer560ModClient#onInitializeClient` - three register calls

Put them next to `com.killer560.hub.leveraura.LeverAuraFeature.register();`:

```java
        com.killer560.hub.autoroutes.AutoRoutesCommands.register();   // the /ar tree
        com.killer560.hub.autoroutes.AutoRoutesKeybinds.register();   // raw-polled keys (no-op on the legit jar)
        com.killer560.hub.autoroutes.AutoRoutesEditInput.register();  // right-click handling in /ar edit db mode (no-op on the legit jar)
```

Plus whatever the core agent's `AutoRoutesFeature.register()` needs (recorder/executor ticks, renderer). Order
between the four does not matter.

### 2. `gui/tab/NewTab.java` - cheat-only block

```java
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ...
            tabs.add(new TerminalTriggerbotTab());
            tabs.add(new AutoRoutesTab());
        }
```

### 3. `profiles/ProfileManager#reloadAllConfigs` - loaders array

```java
                com.killer560.hub.autoroutes.AutoRoutesConfig::load,
```

(Only the settings config. The routes file is not a `killer560smod-*.json` settings file in spirit - it is user
data meant to be shared - but it IS named `killer560smod-autoroutes.json`, so decide with the core agent whether
profiles should copy it. If not, add its name to `ProfileManager.EXCLUDED_FILES`.)

### 4. `gui/SettingTooltipsData.java`

Paste `tooltips.txt`. Keys are widget labels cut at the first `:` and lower-cased. Five keys collide with existing
entries and are therefore scoped `new/...` (`new/mode`, `new/style`, `new/height`, `new/clear route`, `new/delete`).

### 5. README Features section

Per the standing rule, add an Auto Routes bullet (cheat build only, not in the next release).

## Core API this half calls - MUST match exactly

Everything below was compiled against the stubs in `compile-stubs/`. The first block is the API the coordinator
declared; the second block is what this half additionally **assumed** for `AutoRoutesConfig`, because the brief only
said "getters/setters for every setting and keybind" without naming them. If the core agent chose different names,
either rename there or do a find/replace here - every call site is in `AutoRoutesTab`, `AutoRoutesKeybinds` and
`AutoRoutesCommands` only.

### Declared by the coordinator (used as given)

```java
AutoRoutesConfig.getInstance(); cfg.save(); AutoRoutesConfig.load()   // load() for ProfileManager
RouteStore.getInstance().routes()        // only .size() is used (Map or Collection both fine)
RouteStore.reload()                      // static
RouteStore.routesDirectory()             // static, java.nio.file.Path, folder holding the routes file
RouteRecorder.startRecording()           // String status for chat, null = failed
RouteRecorder.stopRecording()            // String status for chat (null tolerated)
RouteRecorder.isRecording()
RouteRecorder.addNode(RouteNode.Type)    // return value (if any) is ignored - see note below
RouteExecutor.isRunning()
RouteExecutor.stop(String reason)
AutoRoutesFeature.setEditMode(boolean) / isEditMode()
AutoRoutesFeature.onEditRightClick(BlockPos, boolean shift)   // boolean, return value currently not used
AutoRoutesFeature.currentRouteNodes()    // List<RouteNode>, 0-based
AutoRoutesFeature.deleteNode(int index)  // 0-based
AutoRoutesFeature.clearCurrentRoute()
RouteNode.Type { START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND }
```

### Assumed by this half (confirm or rename)

```java
// AutoRoutesConfig
boolean isEnabledRaw();  void setEnabled(boolean);            // LeverAuraConfig pattern; isEnabled() is the gated one
boolean isLegitMode();   void setLegitMode(boolean);          // "legit or obvious"
boolean isStartFromStartNodeOnly(); void setStartFromStartNodeOnly(boolean);  // effective value; core forces true in legit
boolean isUniformColor(); void setUniformColor(boolean);
int  getUniformColorArgb(); void setUniformColorArgb(int);
int  getActiveColorArgb();  void setActiveColorArgb(int);
int  getNodeColorArgb(RouteNode.Type); void setNodeColorArgb(RouteNode.Type, int);
AutoRoutesConfig.RenderStyle getRenderStyle(); void setRenderStyle(RenderStyle);
    // enum nested in AutoRoutesConfig; only values()/ordinal()/name() are used, so the constants can be anything
    // (QUOI's are Box / Filled box / Cylinder; the tab prettifies BOX -> "Box", FILLED_BOX -> "Filled Box")
double getThickness(); void setThickness(double);   // tab slides 1.0..8.0 in 0.5 steps (QUOI's range)
double getHeight();    void setHeight(double);      // tab slides 0.1..1.0 in 0.1 steps (QUOI's range)
int  getKeybind(String actionId); void setKeybind(String actionId, int glfwCode);   // KeyUtil.NONE (-1) = unbound

// RouteNode
RouteNode.Type type();   // record-style accessor - the ONLY RouteNode member used (AutoRoutesCommands.describe)
```

Keybind ids (the `actionId` strings, stable - they end up in config files):
`start_record, stop_record, add_ew, add_breaker, add_use, add_walk, add_boom, add_await, add_start, edit_db, clear,
list, delete, reload`. They are `AutoRoutesCommands.Action.id`; the core config can simply store a
`Map<String,Integer>` and default every missing id to `-1`.

Routes file name: `AutoRoutesCommands.ROUTES_FILE_NAME = "killer560smod-autoroutes.json"`, resolved inside
`RouteStore.routesDirectory()`. `/ar reload` JSON-parses that exact file before calling `RouteStore.reload()` so it
can name it in chat if it is broken - the constant must match the store's file name.

### Things to confirm with the core agent

- `RouteRecorder.addNode(type)`: `/ar add <x>` chats "Added <Type> node." unconditionally after calling it. If
  addNode can fail without chatting its own error (e.g. not in a room), make it return `boolean`/`String` and gate
  that line in `AutoRoutesCommands.add`.
- `AutoRoutesCommands` calls `AutoRoutesEditInput.reset()` (clears the hold-repeat debounce) whenever IT turns edit
  mode off. If the core turns edit mode off on its own (room change, route start), call `reset()` there too -
  harmless if skipped (350 ms window).
- Edit mode suppresses **every** right-click on a block while on, consumed or not (so a mis-aimed click can't fire
  an AOTV/sceptre). If the core wants pass-through when `onEditRightClick` returns false, flip the last `return`
  in `AutoRoutesEditInput` to `consumed ? FAIL : PASS`.
- Default per-type colours in `AutoRoutesTab.defaultColor` (the picker's reset value) should mirror the config's
  defaults: BOOM red `FF3333`, USE_ITEM bat-purple `8A5CBF`, ETHERWARP cyan, DUNGEON_BREAKER orange, START green,
  WALK white, AWAIT yellow, others mod-orange.
- `describe(RouteNode)` only shows the type. If `RouteNode` exposes a relative position, extend it there (one place)
  so `/ar list` and the tab rows show "#3 Etherwarp (12, 70, -4)".

## Behaviour summary (for TESTING.md)

- `/ar` alone prints the command list. Every action refuses with a chat line on the legit jar, outside
  Skyblock/p3sim, or while the master toggle is off.
- `/ar delete <n>` uses the 1-based number `/ar list` prints. The **Delete Last Node** keybind deletes the last node.
- `/ar clear`, `/ar delete`, `/ar reload` stop a running route first (`RouteExecutor.stop(reason)`).
- Keybinds: edge-triggered, never fire while any screen is open, reset when a screen opens.
- Tab: collapses to the master toggle when off; Legit greys out "Start From Start Node Only" as forced ON;
  Start/Stop Recording grey out by recorder state; "Stop Route" only shows while a route runs; node rows have
  Delete buttons; "Open Routes Folder" creates the folder before opening it; "Reload Routes" = `/ar reload`.
