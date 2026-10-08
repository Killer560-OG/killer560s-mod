package com.killer560.hub.armourdye;

import com.killer560.hub.armourdye.mixin.CustomDataTagAccessor;
import com.killer560.hub.autoroutes.ItemIdentity;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.UUID;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom Items (Armour Recolour until 2026-10-08) - client-side colour, armour skin, trim, Look (item model) and
 * head-texture overrides for any item you hold. killer560 (2026-09-16): "add an option from skyblocker where you can
 * custom recolor armor, using dyes and armor skin client side"; 2026-10-08: "select an item in your inventory and you
 * can then apply a skin to it or recolor or something". Keyed per item UUID or per Skyblock id (see
 * {@link ArmourDyeEntry}).
 * <p>
 * Ported from Skyblocker's armour customization (github.com/SkyblockerMod/Skyblocker -
 * {@code skyblock/item/custom/CustomArmor{DyeColors,Trims}}, {@code mixins/{DyedItemColor,DataComponentHolder,
 * EquipmentLayerRenderer}Mixin}), credited the same way this mod credits QUOI, Odin, NoammAddons and Devonian.
 * Three deliberate divergences from Skyblocker, all of them killer560's call:
 * <ol>
 *   <li><b>Keyed on the Skyblock item id, not the item UUID.</b> Skyblocker stores one colour per physical item, so
 *       a second Necron's Chestplate is a second job. killer560 asked for "every copy of that piece you own looks
 *       the same", so the key is {@code ItemIdentity.of(stack)} - the id this mod already reads for Auto Routes and
 *       Item Protect, with {@code STARRED_}, reforges and stars stripped.</li>
 *   <li><b>The skin is applied by rewriting {@code minecraft:equippable}'s {@code assetId}</b> rather than by a
 *       {@code @ModifyVariable} on {@code EquipmentLayerRenderer#renderLayers}. {@code HumanoidArmorLayer} reads
 *       the asset key off that component before it ever calls the renderer, so one hook does the job and we don't
 *       have to pin a renderer method descriptor that Mojang reshuffles most versions.</li>
 *   <li><b>No animated dyes.</b> Skyblocker has keyframed OkLab dye animation; that is a whole second feature and
 *       killer560 asked for a recolour, so it isn't here.</li>
 * </ol>
 *
 * <h2>Nothing leaves the client</h2>
 * Every override happens inside a component <i>getter</i> on the client's own copy of the stack. No
 * {@code ItemStack#set}, no NBT edit, no packet, no command - the server's item is untouched and other players see
 * the real armour. This is why the feature is legit and ships on both builds.
 *
 * <h2>Never take down a frame</h2>
 * Both mixins funnel through here and every entry point is wrapped. After {@link #MAX_FAILURES} exceptions the
 * whole feature latches off for the session ({@link #failed}) and logs once, rather than throwing again every frame.
 */
public final class ArmourDye {

    private static final Logger LOGGER = ModLog.get("killer560smod-armourdye");
    private static final int MAX_FAILURES = 5;

    /** Volatile snapshot the render threads read; rebuilt by {@link #invalidate()} on every config change. */
    private static volatile Map<String, ArmourDyeEntry> snapshot = Map.of();
    private static volatile boolean active = false;
    private static volatile boolean failed = false;
    private static int failures = 0;

    /**
     * One-slot memo. A single armour piece is asked for {@code equippable}, then {@code trim}, then its dye colour
     * within the same render call, so remembering the last stack turns three id reads into one. Weak so a stack we
     * looked at once can't keep a whole inventory alive.
     * <p>
     * Stack and entry live in ONE immutable record behind ONE volatile field: {@code DataComponentHolder#get} is
     * also reached from off the render thread, and two separate fields could be read torn - a piece wearing another
     * piece's colour for a frame. One volatile read can't tear.
     */
    private record Memo(WeakReference<ItemStack> stack, ArmourDyeEntry entry) {
    }

    private static volatile Memo memo = null;

    private ArmourDye() {
    }

    /** Rebuilds the render-side snapshot. Called from {@link ArmourDyeConfig}'s load/save, so every GUI edit lands. */
    public static void invalidate() {
        try {
            ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
            Map<String, ArmourDyeEntry> map = new HashMap<>();
            for (ArmourDyeEntry entry : cfg.getEntries()) {
                if (entry.enabled && !entry.isEmpty()) {
                    map.put(entry.itemId, entry);
                }
            }
            snapshot = Map.copyOf(map);
            // isEnabledRaw, not isEnabled: the Skyblock gate is re-checked per frame in active() so leaving and
            // rejoining Skyblock puts the colours straight back without a config reload.
            active = !failed && cfg.isEnabledRaw() && !snapshot.isEmpty();
        } catch (Exception e) {
            snapshot = Map.of();
            active = false;
        } finally {
            memo = null;
        }
    }

    /** The cheap gate both mixins hit first: one volatile read in the common (feature-off) case. */
    /** Set the first time a render hook actually runs. The mixin config is {@code required:false} so a
     *  cosmetic feature can never stop the game booting - but that also means it could silently do nothing,
     *  which is exactly how Item Protect and Object Hider stayed dead for days. This is how the tab can say
     *  so out loud instead. */
    private static volatile boolean renderHookSeen;

    public static void markRenderHook() {
        renderHookSeen = true;
    }

    /** True once a render hook has fired at least once this session. */
    public static boolean renderHookSeen() {
        return renderHookSeen;
    }

    public static boolean active() {
        return active && ArmourDyeConfig.getInstance().isEnabled();
    }

    /** True only for the four components we rewrite - a reference compare, so it costs nothing on other reads. */
    public static boolean handles(DataComponentType<?> type) {
        return type == DataComponents.EQUIPPABLE || type == DataComponents.TRIM || type == DataComponents.ITEM_MODEL
                || type == DataComponents.PROFILE;
    }

    // --- the two override entry points, both called from mixins ---

    /**
     * Replacement for {@code DyedItemColor.getOrDefault}. Works even on a piece with no {@code dyed_color}
     * component at all, because it replaces the fallback the caller would otherwise have used.
     */
    public static int dyeColor(ItemStack stack, int original) {
        markRenderHook();
        try {
            ArmourDyeEntry entry = entryFor(stack);
            if (entry == null || !entry.colorEnabled) {
                return original;
            }
            // Opaque: vanilla's armour-layer tint treats a zero alpha as "invisible", and the picker lets you drag
            // alpha down. Same call Skyblocker makes (ARGB.opaque).
            return 0xFF000000 | (entry.color & 0x00FFFFFF);
        } catch (Exception e) {
            note(e);
            return original;
        }
    }

    /**
     * Replacement for {@code DataComponentHolder#get} on the three render components. {@code original} may be null.
     *
     * @return the value to return from {@code get}, or {@code original} when nothing is overridden.
     */
    public static Object component(ItemStack stack, DataComponentType<?> type, Object original) {
        markRenderHook();
        try {
            ArmourDyeEntry entry = entryFor(stack);
            if (entry == null) {
                return original;
            }
            if (type == DataComponents.EQUIPPABLE) {
                return skinnedEquippable(entry, original);
            }
            if (type == DataComponents.TRIM) {
                ArmorTrim trim = entry.hasTrim() ? ArmourTrims.resolve(entry.trimMaterial, entry.trimPattern) : null;
                return trim == null ? original : trim;
            }
            if (type == DataComponents.ITEM_MODEL) {
                Identifier model = iconModel(entry);
                return model == null ? original : model;
            }
            if (type == DataComponents.PROFILE) {
                ResolvableProfile head = headProfile(entry.headTexture);
                return head == null ? original : head;
            }
            return original;
        } catch (Exception e) {
            note(e);
            return original;
        }
    }

    // --- resolution ---

    private static Object skinnedEquippable(ArmourDyeEntry entry, Object original) {
        if (!(original instanceof Equippable equippable)) {
            return original;
        }
        ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> asset = assetFor(entry);
        if (asset == null) {
            return original;
        }
        // Rebuild the record with only assetId changed - equip sounds, slot, allowed entities and the rest of the
        // component stay exactly as the server sent them, so nothing but the texture choice is affected.
        return new Equippable(equippable.slot(), equippable.equipSound(), java.util.Optional.of(asset),
                equippable.cameraOverlay(), equippable.allowedEntities(), equippable.dispensable(),
                equippable.swappable(), equippable.damageOnHurt(), equippable.equipOnInteract(),
                equippable.canBeSheared(), equippable.shearingSound());
    }

    private static ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> assetFor(ArmourDyeEntry entry) {
        if (entry.skin == ArmourSkin.CUSTOM) {
            return ArmourSkin.assetFromId(entry.skinAsset);
        }
        return entry.skin.asset();
    }

    /** The Look: the entry's own model, or the player-head model when it carries a head texture. */
    private static Identifier iconModel(ArmourDyeEntry entry) {
        if (!entry.iconModel.isBlank()) {
            return Identifier.tryParse(entry.iconModel.trim());
        }
        if (!entry.headTexture.isBlank()) {
            return PLAYER_HEAD_MODEL;
        }
        return null;
    }

    private static final Identifier PLAYER_HEAD_MODEL = Identifier.withDefaultNamespace("player_head");

    /** One resolved head profile per texture, built once: a fresh UUID per call would make the skin cache miss
     *  every frame (see {@code SkyblockItemStackFactory}, the same lesson). */
    private static final Map<String, ResolvableProfile> HEADS = new java.util.concurrent.ConcurrentHashMap<>();

    public static ResolvableProfile headProfile(String texture) {
        if (texture == null || texture.isBlank()) {
            return null;
        }
        return HEADS.computeIfAbsent(texture.trim(), t -> {
            com.google.common.collect.Multimap<String, Property> backing = com.google.common.collect.HashMultimap.create();
            backing.put("textures", new Property("textures", t));
            GameProfile profile = new GameProfile(
                    UUID.nameUUIDFromBytes(t.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "CustomItem",
                    new PropertyMap(backing));
            return ResolvableProfile.createResolved(profile);
        });
    }

    /** The head texture an item carries (a player head's {@code textures} value), or null. Read raw, past our own
     *  override, so copying a look copies the real item's. */
    public static String headTextureOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResolvableProfile profile = raw(stack, DataComponents.PROFILE);
        if (profile == null || profile.partialProfile() == null) {
            return null;
        }
        java.util.Iterator<Property> it = profile.partialProfile().properties().get("textures").iterator();
        return it.hasNext() ? it.next().value() : null;
    }

    /** The item model an item really has (past our own override), or null. */
    public static Identifier itemModelOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : raw(stack, DataComponents.ITEM_MODEL);
    }

    /** The piece's real armour slot, read straight off the component map so we never re-enter our own {@code get}. */
    public static EquipmentSlot slotOf(ItemStack stack) {
        Equippable equippable = raw(stack, DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    /**
     * Raw component read that bypasses {@code DataComponentHolder#get} - {@code DataComponentMap} is a
     * {@code DataComponentGetter}, not a holder, so our mixin isn't on this path. Without this, asking for
     * {@code EQUIPPABLE} from inside the {@code EQUIPPABLE} override would recurse forever.
     */
    @SuppressWarnings("unchecked")
    private static <T> T raw(ItemStack stack, DataComponentType<T> type) {
        DataComponentMap map = stack.getComponents();
        return map == null ? null : (T) map.get(type);
    }

    /** @return the entry for this stack (its UUID key first, then its id), or null. */
    public static ArmourDyeEntry entryFor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || snapshot.isEmpty()) {
            return null;
        }
        Memo cached = memo;
        if (cached != null && cached.stack().get() == stack) {
            return cached.entry();
        }
        ArmourDyeEntry entry = lookup(snapshot, stack);
        // Only a hit is remembered: the item model of EVERY drawn stack is read through here while the feature is on,
        // and remembering misses evicted the memo and allocated for every item (FPS sweep, 2026-10-07).
        if (entry != null) {
            memo = new Memo(new WeakReference<>(stack), entry);
        }
        return entry;
    }

    private static ArmourDyeEntry lookup(Map<String, ArmourDyeEntry> map, ItemStack stack) {
        String uuid = uuidOf(stack);
        if (uuid != null) {
            ArmourDyeEntry byUuid = map.get(ArmourDyeConfig.uuidKey(uuid));
            if (byUuid != null) {
                return byUuid;
            }
        }
        String id = identityOf(stack);
        return id == null ? null : map.get(id);
    }

    /** The Skyblock {@code uuid}, read without copying the tag, or null. */
    public static String uuidOf(ItemStack stack) {
        CustomData data = raw(stack, DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = ((CustomDataTagAccessor) (Object) data).killer560smod$getTag();
        String uuid = tag == null ? null : tag.getStringOr("uuid", null);
        return uuid == null || uuid.isBlank() ? null : uuid;
    }

    /**
     * Same identity {@code ItemIdentity.of} produces, but reading {@code ExtraAttributes} without copying the tag -
     * this runs per armour piece per frame, and {@code CustomData#copyTag} is a full NBT deep copy. Falls back to
     * {@code ItemIdentity.of} (display-name path) only for a piece with no Skyblock id, which is rare enough that
     * the extra copy there doesn't matter.
     */
    public static String identityOf(ItemStack stack) {
        CustomData data = raw(stack, DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = ((CustomDataTagAccessor) (Object) data).killer560smod$getTag();
            String id = tag == null ? null : tag.getStringOr("id", null);
            if (id != null && !id.isBlank()) {
                id = id.trim().toUpperCase(Locale.ROOT);
                return id.startsWith("STARRED_") ? id.substring("STARRED_".length()) : id;
            }
        }
        return ItemIdentity.of(stack);
    }

    /** True for a helmet, chestplate, leggings or boots - the pieces an armour skin and a trim apply to. */
    public static boolean isArmour(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        EquipmentSlot slot = slotOf(stack);
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    /** The four armour pieces the player is wearing, helmet first; empty slots are {@link ItemStack#EMPTY}. */
    public static List<ItemStack> wornArmour(net.minecraft.world.entity.player.Player player) {
        if (player == null) {
            return List.of();
        }
        try {
            return List.of(
                    player.getItemBySlot(EquipmentSlot.HEAD),
                    player.getItemBySlot(EquipmentSlot.CHEST),
                    player.getItemBySlot(EquipmentSlot.LEGS),
                    player.getItemBySlot(EquipmentSlot.FEET));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void note(Exception e) {
        if (failed) {
            return;
        }
        if (++failures >= MAX_FAILURES) {
            failed = true;
            active = false;
            LOGGER.error("[ArmourDye] Disabled for this session after {} render failures - turn it off and on again "
                    + "in the settings menu once the cause is fixed.", MAX_FAILURES, e);
        } else {
            LOGGER.warn("[ArmourDye] Override failed ({}/{}): {}", failures, MAX_FAILURES, e.toString());
        }
    }

    /** Clears the session kill-switch - the settings tab calls this when the feature is turned back on. */
    public static void resetFailures() {
        failed = false;
        failures = 0;
    }

    public static boolean hasFailed() {
        return failed;
    }
}
