# AP3 UI half - integration notes

Staging root: `C:\Users\Hunter\killer560s-mod\scratchpad\staging\ap3-ui\`

## Files to copy into the repo (paths are already package-shaped)

| Staging file | Repo destination |
|---|---|
| `src/main/java/com/killer560/hub/ap3/Ap3Commands.java` | `src/main/java/com/killer560/hub/ap3/` |
| `src/main/java/com/killer560/hub/ap3/Ap3Keybinds.java` | `src/main/java/com/killer560/hub/ap3/` |
| `src/main/java/com/killer560/hub/gui/tab/Ap3Tab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `src/main/java/com/killer560/hub/gui/tab/ClassOverridesTab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `tooltips.txt` | paste the `d.put(...)` lines into `gui/SettingTooltipsData.java` (next to the Auto Routes block) |

**Do NOT copy `compile-stubs/`** - throwaway stand-ins for the core agent's classes so this half could be
type-checked before the core existed. `args.txt` is the javac args file used for that check (output went to the
scratchpad `gate-ap3` folder, nothing under `src/` was touched).

All four files compiled with JDK 25 javac against the real 26.1.2 jar + Fabric API + the stubs, zero warnings.

## No mixin from this half

Edit-mode right-clicks (`/ap3 edit db`) are the core's job - the spec says to reuse `AutoRoutesEditInput`'s
`UseBlockCallback` approach, which needs no mixin. This half only toggles `Ap3Feature.setEditMode`. The core's
**input mixin** (`killer560smod-ap3.mixins.json`) still has to go into `fabric.mod.json` - unregistered mixin
configs are silent.

## Wiring the main session must do

### 1. `Killer560ModClient#onInitializeClient` - two register calls

Next to the Auto Routes block (lines ~136-139):

```java
        com.killer560.hub.ap3.Ap3Commands.register();   // the /ap3 tree
        com.killer560.hub.ap3.Ap3Keybinds.register();   // raw-polled keys (no-op on the legit jar)
```

Plus whatever the core's `Ap3Feature.register()` (tick/render/edit-input) needs. Order does not matter.

### 2. Tabs

`gui/tab/NewTab.java`, cheat-only block:

```java
            tabs.add(new AutoRoutesTab());
            tabs.add(new Ap3Tab());
```

`gui/tab/DungeonTab.java`, the **legit** list, right after `new LeapMenuTab()` (it changes the normal leap menu,
so it is NOT cheat-only and must not go in the cheat block):

```java
                new LeapMenuTab(),
                new ClassOverridesTab(),
```

If killer560 would rather have it as an accordion inside `LeapMenuTab`, `ClassOverridesTab` is a plain `BaseTab`
with no state beyond two scratch fields - it can be nested wherever. The AP3 tab's read-only section says "Edit it
in Dungeon > Class Overrides", so update that string if it lands somewhere else.

### 3. `profiles/ProfileManager#reloadAllConfigs` - loaders array

```java
                com.killer560.hub.ap3.Ap3Config::load,
                () -> com.killer560.hub.ap3.Ap3Store.getInstance().reload(),
                com.killer560.hub.dungeonclass.ClassOverrides::load,
```

(`Ap3Store.reload()` is an instance method per the declared API; if the core made it static like `RouteStore`,
use `Ap3Store::reload`. Whether profiles should copy the chains file `killer560smod-ap3.json` is the same open
question Auto Routes left - if not, add it to `ProfileManager.EXCLUDED_FILES`.)

### 4. `gui/SettingTooltipsData.java`

Paste `tooltips.txt`. Every label in both tabs was checked against the existing keys; none collide, so nothing is
scoped `new/`. The node rows' "Delete" button deliberately reuses the existing `new/delete` entry.

### 5. README Features section

Per the standing rule, add an AP3 bullet (cheat build only, not in the next release) and a Class Overrides bullet
(legit).

## Core API this half calls - MUST match exactly

Everything below compiled against `compile-stubs/`. First block: the API the coordinator declared, used as given.
Second block: what this half additionally **assumed**, because the brief said "getters/setters per setting and
keybind" and "Ap3Node" without naming members. Every call site is in the four files above only.

### Declared by the coordinator (used as given)

```java
Ap3Config.getInstance(); cfg.save()
Ap3Store.getInstance().chains()     // only .size() is used (Map or Collection both fine)
Ap3Store.getInstance().reload()     // instance
Ap3Store.getInstance().directory()  // instance, java.nio.file.Path - folder holding killer560smod-ap3.json
    // forSection(int) and save() are NOT called by this half
Ap3Executor.isRunning() / start() / stop(String reason)   // static; start()'s return value (if any) is ignored
Ap3Feature.currentChainNodes()      // List<Ap3Node>, 0-based, live list of the current section's chain
Ap3Feature.clearCurrentChain()
Ap3Feature.setEditMode(boolean) / isEditMode()
ClassOverrides.getInstance().save()
ClassOverrides.set(String ign, DungeonClass) / clear(String ign) / all()   // static, all() -> Map<String, DungeonClass>
    // classOf(ign, detected) is NOT called by this half - the editor shows detected and override side by side on purpose
Ap3Node.Type { LINE, AXIS_LINE, WALK, RUN, LEAP, LEAP_DETECTOR, TERMINAL, WAIT, STOP, LOOK, BREAKER }
```

### Assumed by this half (confirm or rename)

```java
// Ap3Config - AutoRoutesConfig names
boolean isEnabledRaw(); void setEnabled(boolean);        // isEnabled() is the gated one; the tab reads Raw
boolean isUniformColor(); void setUniformColor(boolean);
int  getUniformColorArgb(); void setUniformColorArgb(int);
int  getActiveColorArgb();  void setActiveColorArgb(int);
int  getNodeColorArgb(Ap3Node.Type); void setNodeColorArgb(Ap3Node.Type, int);
int  getKeybind(String actionId); void setKeybind(String actionId, int glfwCode);   // KeyUtil.NONE (-1) = unbound
static void load();                                       // for ProfileManager

// Ap3Feature - return values
Ap3Node addNode(Ap3Node.Type);      // the node it placed, or null when refused (not in P3, no section...)
Ap3Node addWaitNode(int millis);    // same
boolean deleteNode(int index0);     // true when something was removed

// Ap3Node - record-style accessors, read ONLY inside Ap3Commands.describe()
Ap3Node.Type type();
double x(); double y(); double z();          // snapped world position
double length(); double width();             // LINE / AXIS_LINE
int    waitMillis();                         // WAIT
String leapModifier();                       // LEAP: null/blank = Fast Leap's target, else class name or IGN

// ClassOverrides
static void load();                                       // for ProfileManager
```

If the core used public fields instead (RouteNode style: `node.x`), `describe()` is the one method to edit.

### The "one chat line" contract

Auto Routes double-printed because `deleteNode`/`setEditMode` and the command both talked, and the inner one used
0-based numbers. Here **`Ap3Commands` owns all success feedback**:

- It range-checks `delete` itself, so the core's "no node #n" path never runs from a command or the tab.
- On `addNode`/`addWaitNode`, it prints "Added #n <desc> to S2" only when the returned node is non-null. The core
  should print nothing on success and may explain a refusal (returning null) - then the command prints nothing.
- `setEditMode(on)`: the command prints ON/OFF only if `isEditMode()` actually changed to `on`. The core may add
  its own line saying WHICH breaker node is being edited (Auto Routes does) - that is information the command
  can't know, and it's the one deliberate exception. If the core also prints "edit mode on", drop one.
- `start()`: the command pre-checks running / in P3 / non-empty chain and prints "Started S2 chain - n nodes"
  only if `isRunning()` is true afterwards. The core should not print on a successful start.
- `stop(reason)`: the command prints "Stopped." itself. If `Ap3Executor` echoes its stop reason in chat the way
  `RouteExecutor` does on its next tick, the user will see two lines for `/ap3 stop` - either make the executor
  skip the echo for the `"/ap3 stop"` reason string, or delete the `ModChat.send(... "Stopped.")` in
  `Ap3Commands.stopChain()`. One-line fix either way.

Numbers the player sees are 1-based everywhere: `/ap3 list`, `/ap3 delete <n>`, the tab rows (`describeNumbered`)
and every "Added #n"/"Deleted #n" line all come from the same two formatters in `Ap3Commands`. The core's own world
labels should print `index + 1` to agree.

### Keybind ids (stable - they end up in config files)

`add_line, add_axisline, add_walk, add_run, add_leap, add_leapdetector, add_terminal, add_wait, add_stop, add_look,
add_breaker, edit_db, list, delete, clear, reload, start, stop`

`Ap3Config.getKeybind/setKeybind` must accept these strings (AutoRoutesConfig's `canonical()` accepts unknown ids
and stores them under their own name - same thing works here). All default `KeyUtil.NONE`.

### Behaviour notes the core should know

- **STOP bypasses every gate.** `Ap3Commands.run(Action.STOP)` skips `ready()` (only the cheat-jar check stays)
  and `Ap3Keybinds` polls the STOP key even while the master toggle is off - "the tab's own stop button must
  work at any time". The tab's Stop Chain button is never greyed for the same reason.
- `ready()` does NOT require P3. Listing / clearing / reloading / deleting a chain placed earlier shouldn't need
  the player to stand in the boss room; the core decides what needs a section (`addNode` returning null with a
  message is the expected way to refuse placement outside P3).
- `/ap3 add wait <ms>` (1..120000) sets `Ap3Commands.setPendingWaitMillis` and adds. The keybind and the tab's
  "Add Wait Node" button add with the pending value; the tab's "Wait ms" box edits it. Session-only scratch - the
  real value lives on the node.
- `ADD_WAIT` and `DELETE_LAST` are the two actions whose command form takes an argument the key can't.
- Section name for chat/tab is `Floor7Tracker.getStage().name()` when `number` is 1..5, else "this section".
- `Ap3Tab` colour defaults (picker reset) are in `Ap3Tab.defaultColor(type)` - mirror them in the core config.

## What killer560 should confirm

1. **Where Class Overrides lives** - this half puts it in Dungeon next to Leap Menu as its own tab. Fine, or nested
   inside the Leap Menu tab?
2. **Override cycling order** - None > Mage > Tank > Healer > Archer > Berserker > None (enum order), one button
   per party member. A "Remove Override" button sits beside it for people who don't want to cycle round.
3. **Leap node modifier entry** - there is no `/ap3 add leap <class|ign>` form; per the spec's command list the
   modifier is set by editing the node in `killer560smod-ap3.json` (or the core can add an optional argument -
   `Ap3Commands.register()` is where it would go). Say if a command form is wanted.
4. **Delete key deletes the LAST node** (same as Auto Routes) - the command form takes a number.
5. The AP3 tab's labels avoid Auto Routes' wording so tooltips can't cross-match: "Add Walk (No Turn) Node",
   "Breaker Edit Mode", "List Chain In Chat", "Reload AP3 Chains", "AP3 Keybinds". If he'd rather they matched
   Auto Routes' exactly, the tooltip keys need `new/` scoping instead.
