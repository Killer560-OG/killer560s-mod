# Armour Recolour — integration checklist

Staged in `scratchpad/staging/armourdye/`. Package-shaped: copy `com/` over `src/main/java/com/`,
and `resources/killer560smod-armourdye.mixins.json` into `src/main/resources/`.

Compiles clean with
`"C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/javac" @args-armourdye.txt $(find com -name '*.java')`
(exit 0, no warnings). Gradle has **not** been run.

## Files

| Staged path | Destination |
| --- | --- |
| `com/killer560/hub/armourdye/ArmourDye.java` | `src/main/java/com/killer560/hub/armourdye/` |
| `com/killer560/hub/armourdye/ArmourDyeConfig.java` | same |
| `com/killer560/hub/armourdye/ArmourDyeEntry.java` | same |
| `com/killer560/hub/armourdye/ArmourDyeFeature.java` | same |
| `com/killer560/hub/armourdye/ArmourSkin.java` | same |
| `com/killer560/hub/armourdye/ArmourTrims.java` | same |
| `com/killer560/hub/armourdye/mixin/ArmourDyeColorMixin.java` | `src/main/java/com/killer560/hub/armourdye/mixin/` |
| `com/killer560/hub/armourdye/mixin/ArmourDyeComponentMixin.java` | same |
| `com/killer560/hub/armourdye/mixin/CustomDataTagAccessor.java` | same |
| `com/killer560/hub/gui/tab/ArmourDyeTab.java` | `src/main/java/com/killer560/hub/gui/tab/` |
| `resources/killer560smod-armourdye.mixins.json` | `src/main/resources/` |

## 1. `src/main/resources/fabric.mod.json` — REQUIRED, the feature is dead without it

Add to the `"mixins"` array (after `"killer560smod-objecthider.mixins.json"`):

```
    "killer560smod-armourdye.mixins.json"
```

**An unregistered mixin config silently does nothing.** Both render hooks live in that config, so
without this line the tab still saves settings and nothing on screen ever changes colour.
Quick check after a boot test: `latest.log` should show the mixin config being loaded, and
`ArmourDyeComponentMixin` / `ArmourDyeColorMixin` applying. The config is `"required": true` with
`"defaultRequire": 1`, so a broken injector fails **loudly** at startup rather than quietly — that is
deliberate (see Judgement calls below), swap to `0` if you'd rather it degrade.

## 2. `src/main/java/com/killer560/hub/Killer560ModClient.java`

After `com.killer560.hub.objecthider.ObjectHiderFeature.register();` (currently line 208):

```java
        com.killer560.hub.armourdye.ArmourDyeFeature.register();
```

No HUD element, no keybind registration — the capture key is handled through `ScreenEvents`
inside the feature, same as Item Protect.

## 3. `src/main/java/com/killer560/hub/gui/tab/NewTab.java`

Add to the always-on `List.of(...)` block in `buildTabs()` (killer560 hasn't run this in a real
dungeon yet, so it starts in **New**, not in a category tab):

```java
                new ArmourDyeTab(),
```

## 4. `src/main/java/com/killer560/hub/profiles/ProfileManager.java`

In the `reloadAllConfigs()` list, alphabetically just before
`com.killer560.hub.autoclosechest.AutoCloseChestConfig::load` (or anywhere in the block):

```java
                com.killer560.hub.armourdye.ArmourDyeConfig::load,
```

`ArmourDyeConfig.load()` calls `ArmourDye.invalidate()` itself, so switching profiles repaints
immediately with no extra wiring.

## 5. `src/main/java/com/killer560/hub/gui/SettingTooltipsData.java`

Paste the 14 lines from `tooltips.txt` into the `register(...)` body. They are all
`"armour recolour/..."`-scoped so the generic `enabled` / `colour` / `remove` keys that already exist
for other tabs are not touched.

## 6. `README.md` — Features list

Per the standing rule that the README Features section is updated with every new feature. Add next to
`Item Protection` (currently line 83):

```
- Armour Recolour — client-side armour colours, skins and trims, keyed on the Skyblock item id so every copy of a piece looks the same. Recolour a Necron's Chestplate, paint it as diamond or netherite, or add a vanilla trim — on your body, in inventories, in your hand and on dropped items. Purely local: nothing is sent to the server, the real item is never touched, and other players see the real armour
```

## 7. `TESTING.md`

Suggested entry:

- Turn **Armour Recolour** on in the New tab, hit **Add Chest**, pick a colour → your chestplate
  recolours on your body and in the inventory immediately.
- Set **Skin: Netherite** on a leather Skyblock piece → it renders as netherite armour on the body and
  (with **Skin Inventory Icons** on) as a netherite chestplate icon.
- Set a **Trim** + **Pattern** → the trim overlay appears on the body and on the icon.
- Bind the **Capture Key**, open any chest/inventory, hover an armour piece, press it → chat confirms
  it was added.
- Alt+F4 and relaunch → every colour, skin, trim and the capture key are still set.
- Ask a friend to look at you → they see the **real** armour. This is the thing to actually confirm.

---

# What this is and what it borrows

killer560, 2026-09-16: *"add an option from skyblocker where you can custom recolor armor, using dyes
and armor skin client side."*

## What Skyblocker actually does (6.9.1+26.1.2, verified against the jar in the 26.1.2 (Dungeons) instance)

It is called **armour customization** (`/skyblocker custom`, `CustomizeScreen` → `ArmorTab`/`ItemTab`).
Six overrides, all client-side:

| Skyblocker feature | Hook | Here? |
| --- | --- | --- |
| Custom dye colour | `@Mixin(DyedItemColor)` `@ModifyReturnValue` on `getOrDefault` | **yes** |
| Custom armour trim | `@Mixin(DataComponentHolder)` `@ModifyReturnValue` on `get`, `DataComponents.TRIM` | **yes** |
| Armour skin (worn model) | `@Mixin(EquipmentLayerRenderer)` `@ModifyVariable` on the `ResourceKey<EquipmentAsset>` arg of `renderLayers` | **yes**, different hook |
| Item model override (inventory icon) | same `DataComponentHolder#get`, `DataComponents.ITEM_MODEL` | **yes** |
| Animated (keyframed, OkLab) dyes | same `DyedItemColor` hook | no |
| Player-head helmet textures / animated skulls | same `get` hook, `DataComponents.PROFILE` | no |
| Custom item names, custom glint | same `get` hook | no |

Skyblocker **keys every one of those on the per-item `ExtraAttributes.uuid`** (cached on a
`SkyblockerStack` duck on `ItemStack`), and refuses any item without one — so one physical chestplate,
not a piece type. Storage is plain `Object2*OpenHashMap<String, …>` fields on its single
`skyblocker.json`.

## Where this build diverges, and why

1. **Keyed on the Skyblock item id, not the uuid.** killer560 asked for "every copy of that piece you
   own looks the same", which is the opposite of Skyblocker's model. The key is
   `ItemIdentity.of(stack)` — the same id Auto Routes and Item Protect already read, with `STARRED_`,
   the reforge `modifier` and `upgrade_level` stripped, and a cleaned display-name fallback for
   id-less items. Side effect worth knowing: a piece with no Skyblock id at all falls back to its
   display name, so two differently-named leather helmets stay separate but two identically-named
   ones share an entry.
2. **The armour skin is applied by rewriting `minecraft:equippable`'s `assetId`**, not by a
   `@ModifyVariable` on `EquipmentLayerRenderer#renderLayers`. `HumanoidArmorLayer` reads the asset
   key off that component *before* it calls the renderer (verified in the 26.1.2 bytecode), so one
   hook does the job, we don't have to pin an 11-argument renderer descriptor that Mojang reshuffles
   most versions, and we don't need MixinExtras' `@Local` sugar (this repo doesn't use MixinExtras
   anywhere else). The rebuilt `Equippable` record copies every other field verbatim — slot, equip
   sound, allowed entities — so only the texture choice changes.
3. **No animated dyes, no helmet head-texture swaps, no custom names/glint.** Animated dyes are a
   whole second feature (keyframe timeline, OkLab interpolation, per-frame state trackers) and
   killer560 asked for a recolour. Head textures need the NEU item repo, which this mod doesn't ship.
4. **Presets instead of a raw identifier box.** Skyblocker makes you type an equipment-asset
   identifier. Here the eight vanilla armour sets are a cycle button, each of which also knows the
   matching inventory item model per slot, with a `Custom ID` option for anything else.

Skyblocker is credited in the class docs of `ArmourDye`, `ArmourSkin`, `ArmourTrims`,
`CustomDataTagAccessor` and both render mixins, the same way this repo credits QUOI, Odin,
NoammAddons and Devonian.

## Render surfaces covered

- **Worn armour, third person / other players' view of you / inventory doll** — colour (via
  `DyedItemColor.getOrDefault`, which `EquipmentLayerRenderer#renderLayers` calls), skin (via
  `equippable.assetId`), trim (via `DataComponents.TRIM`, which `renderLayers` reads off the stack).
- **Inventory / chest / hotbar / hand / dropped item entity** — colour (vanilla's
  `net.minecraft.client.color.item.Dye` tint source calls the same `getOrDefault`), icon model (via
  `DataComponents.ITEM_MODEL`, behind the **Skin Inventory Icons** toggle), trim (the item model reads
  `TRIM` too).
- **Not covered:** any third-party mod that renders armour from a cached copy of the stack's
  components taken before our hook runs. None are known; worth an eye during a Sodium/Iris run.

## Nothing leaves the client

Explicitly, the answer is **nothing**. Every override happens inside a component *getter* or a colour
*reader*, on the client's own `ItemStack`. There is no `ItemStack#set`, no NBT write, no packet, no
chat command, no HTTP. The server's copy of the item is byte-identical before and after, and other
players see the real armour. That is why it's `isCheatOnly() == false` and ships on both builds.

## Judgement calls for killer560 to confirm

1. **Item id, not per-item uuid** (divergence 1). If he actually wanted "this one chestplate", the key
   in `ArmourDyeEntry.itemId` swaps to `ExtraAttributes.uuid` and nothing else changes.
2. **`defaultRequire: 1` / `required: true`** — a broken injector crashes at startup instead of the
   feature quietly doing nothing. Chosen because silently-dead render features have cost this project
   two features already. If a cosmetic feature shouldn't be able to block a launch, set both to the
   permissive values.
3. **Trims are included.** He asked for "dyes and armor skin"; trims are the third leg of Skyblocker's
   armour tab and came almost free off the same mixin. Easy to drop (delete the two cycle buttons and
   the `TRIM` branch) if he doesn't want them.
4. **Picking a colour arms it.** Opening the wheel and choosing a colour sets `Use Colour: ON`
   automatically rather than making him hit a second toggle.
5. **`Skin Inventory Icons` defaults ON.** It's the only setting in the feature that defaults on,
   because a skinned piece that keeps its old inventory icon looks like a bug.
6. **Turtle Shell only skins helmets.** Vanilla has no turtle chestplate/leggings/boots, so the other
   three slots keep their real icon while the body still gets the turtle texture.
7. **Orange theme.** The tab uses the standard `SectionHeaders`/`SettingsButtonWidget` widgets, so it
   inherits whatever the rest of the menu does; the per-entry header line is hard-coded `§6` (orange)
   for an enabled entry and `§8` for a parked one.
8. **No chat command.** Skyblocker exposes `/skyblocker custom dyeColor <hex>`. Everything here is in
   the tab plus the capture key; say the word if a command is wanted too.

## Performance note

`DataComponentHolder#get` is one of the hottest methods in the game. The guard order in
`ArmourDyeComponentMixin` is deliberate: three reference compares against the component types, then
one volatile boolean, then `instanceof`, and only then anything touching NBT. `ArmourDye` also keeps a
one-slot memo (an immutable record behind a single volatile field, so an off-thread read can't tear)
because a single piece is asked for `equippable`, `trim` and its dye colour inside one render call,
and it reads `ExtraAttributes` through `CustomDataTagAccessor` rather than `CustomData#copyTag()`,
which is a full NBT deep copy. `ArmourDye` also reads components back through
`stack.getComponents().get(...)` — a `DataComponentMap` is a `DataComponentGetter`, not a holder, so
that path skips our own mixin; reading `EQUIPPABLE` the ordinary way from inside the `EQUIPPABLE`
branch would recurse until the stack overflowed.
