# Integration - Scrollable Tooltips + Enchant Colours

Two features, both LEGIT (ship on both builds, no `isCheatOnly()` override), both default OFF.
Everything below is staged under `scratchpad/staging/tooltips/`; nothing under `src/` was touched.

Compiled clean with `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/javac @args-tooltips.txt @sources.txt`
(13 class files, zero errors/warnings). `args-tooltips.txt` was regenerated from `ap3/args-ap3.txt` with the
48 stale Fabric-API classpath entries that no longer exist on disk dropped.

---

## 1. Files to copy into `src/main/java`

| Staged path | Destination |
|---|---|
| `com/killer560/hub/tooltipscroll/TooltipScrollConfig.java` | `src/main/java/com/killer560/hub/tooltipscroll/` |
| `com/killer560/hub/tooltipscroll/TooltipScrollFeature.java` | same |
| `com/killer560/hub/tooltipscroll/mixin/TooltipScrollGraphicsMixin.java` | `src/main/java/com/killer560/hub/tooltipscroll/mixin/` |
| `com/killer560/hub/tooltipscroll/mixin/TooltipScrollContainerMixin.java` | same |
| `com/killer560/hub/enchantcolors/EnchantColorsConfig.java` | `src/main/java/com/killer560/hub/enchantcolors/` |
| `com/killer560/hub/enchantcolors/EnchantColorsDefaults.java` | same |
| `com/killer560/hub/enchantcolors/EnchantColorsFeature.java` | same |
| `com/killer560/hub/enchantcolors/LegacyText.java` | same |
| `com/killer560/hub/enchantcolors/mixin/EnchantColorsTooltipMixin.java` | `src/main/java/com/killer560/hub/enchantcolors/mixin/` |
| `com/killer560/hub/gui/tab/TooltipScrollTab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `com/killer560/hub/gui/tab/EnchantColorsTab.java` | same |
| `resources/killer560smod-tooltipscroll.mixins.json` | `src/main/resources/` |
| `resources/killer560smod-enchantcolors.mixins.json` | `src/main/resources/` |

---

## 2. `src/main/resources/fabric.mod.json` - MIXIN CONFIGS MUST BE REGISTERED

**This is the step that has already killed two features in this project.** A mixin config that is not listed
in `fabric.mod.json` is never loaded, the mixins never apply, and BOTH features silently do nothing with no
error in the log. Add both entries to the `"mixins"` array (after `"killer560smod-objecthider.mixins.json"`,
the current last entry):

```
    "killer560smod-objecthider.mixins.json",
    "killer560smod-tooltipscroll.mixins.json",
    "killer560smod-enchantcolors.mixins.json"
```

(remember the comma after `objecthider`).

---

## 3. `Killer560ModClient` - feature registration

Add next to the other `com.killer560.hub.*Feature.register();` lines (e.g. right after
`com.killer560.hub.itemrarity.ItemRarityFeature.register();`):

```java
        com.killer560.hub.tooltipscroll.TooltipScrollFeature.register();
        com.killer560.hub.enchantcolors.EnchantColorsFeature.register();
```

`TooltipScrollFeature.register()` installs a `ClientTickEvents.END_CLIENT_TICK` handler that clears the scroll
offset when the open screen is not an `AbstractContainerScreen`; `EnchantColorsFeature.register()` installs a
`ClientPlayConnectionEvents.DISCONNECT` handler that drops the tooltip cache. Neither feature works correctly
without its `register()` call (the scroll offset would leak between screens; the enchant cache would hold
stale stacks across server hops).

---

## 4. `gui/tab/NewTab.java` - tab registration

Both tabs go in the **New** tab per the repo convention (untested until killer560 confirms them in a real
run). Add to the `List.of(...)` in `buildTabs()`, alongside the other legit tabs (NOT inside the
`BuildVariant.CHEAT_FEATURES_ENABLED` block):

```java
                new TooltipScrollTab(),
                new EnchantColorsTab(),
```

---

## 5. `profiles/ProfileManager.reloadAllConfigs()` - profile support

Add to the `load()` method-reference list, keeping it alphabetical by package:

```java
                com.killer560.hub.enchantcolors.EnchantColorsConfig::load,
```
(between `com.killer560.hub.dungeonqueue...` / `com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig::load`
and `com.killer560.hub.experiments.ExperimentsConfig::load` - i.e. wherever `enchantcolors` sorts)

```java
                com.killer560.hub.tooltipscroll.TooltipScrollConfig::load,
```
(near `com.killer560.hub.terminals...` / `com.killer560.hub.trajectories...`)

Both config files are `killer560smod-*.json` in the config dir, so `ProfileManager.isProfileSettingFile`
already picks them up for save/apply/export - **no** change to `EXCLUDED_FILES` (neither holds a secret or a
cache; both are pure settings).

New config files created at runtime:
- `config/killer560smod-tooltipscroll.json`
- `config/killer560smod-enchantcolors.json`

---

## 6. `gui/SettingTooltipsData.java` - setting descriptions

Paste the lines from `tooltips.txt` into one of the `partN(Map<String, String> d)` methods (the class is split
across several methods to stay under the JVM method size limit - put them in whichever is smallest).

Note `d.put("new/bold", ...)`: "Bold" on its own is too generic to key globally, so it is scoped to the **New**
tab. `"filter enchants"` is deliberately not `"search"` - `"search"` is already taken by another feature, which
is why the filter box's label was changed from "Search" to "Filter Enchants".

---

## 7. `README.md` - Features list

Per the standing rule (update the Features section on every new feature), add:

- **Scrollable Tooltips** - mouse-wheel scrolling for item tooltips taller than the screen, with an optional
  hold-a-key modifier. Works everywhere, not just on Skyblock.
- **Enchant Colours** - per-enchantment colour overrides in item lore, with sensible defaults and a separate
  Ultimate colour. Skyblock only.

`TESTING.md` should get the same two entries.

---

## 8. Things to check on the first real run

1. Hover a maxed Hyperion / fully-enchanted chestplate in a chest GUI and scroll. The tooltip should window,
   clamp at both ends, and reset the moment you move to a different slot.
2. Hover a short tooltip and scroll - the wheel must behave exactly as it did before (nothing consumed).
3. Confirm the enchant block recolours and that **no other lore line** does - in particular any line of the
   shape `Word IX` (a "Tier IX"-style line would be shape-compatible; the NBT + name check should still leave
   it alone, but it is the one false-positive class worth eyeballing).
4. Check on **p3sim.net** as well as hypixel.net - p3sim items may not carry `ExtraAttributes.enchantments`,
   in which case Enchant Colours will (correctly, by design) do nothing there. Scrollable Tooltips is not
   Skyblock-gated and should work on both.
