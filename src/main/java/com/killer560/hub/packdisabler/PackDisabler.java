package com.killer560.hub.packdisabler;

import com.killer560.hub.compat.McItems;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;

/**
 * Pack Disabler (killer560, 2026-10-07): every SkyBlock item drawn with the look it had before Hypixel's 2026 resource
 * pack, and our own original textures for the items that never had one.
 * <p>
 * <b>Hypixel's pack itself is left alone.</b> Hypixel's SkyBlock 0.26 announcement says the pack is force-enabled and
 * SkyBlock cannot be joined without loading it, so declining it is not an option, and reporting it loaded when it was
 * not (what Noamm's PackDisabler does: it answers ACCEPTED + SUCCESSFULLY_LOADED and cancels the push) is a lie this mod
 * will not tell. So the server's pack push goes through vanilla untouched, the pack really loads, the status vanilla
 * sends is true - and this feature changes only which MODEL each SkyBlock item is drawn with, at the moment the client
 * picks it ({@code ItemModelResolver.appendItemLayers}). Hypixel's announcement explicitly supports community packs
 * that revert textures to their old state, which is what this is. Hypixel's pack only touches the {@code minecraft}
 * namespace for fonts, text shaders, the title background and tooltip/HUD sprites, never vanilla item textures, so a
 * vanilla look resolved here is drawn by whatever resource pack he selected.
 * <p>
 * Only a model in Hypixel's own {@code hypixel_skyblock} namespace on a stack with a SkyBlock id is ever replaced, so
 * nothing changes off SkyBlock. If Noamm's PackDisabler is installed it does this job its own way and this one stands
 * back (one chat line per session says so). The dynamic models below (katanas, attuned daggers, Fungi Cutter, Carnival
 * Shovel, quiver arrows) follow Noamm's PackDisabler (github.com/Noamm9/PackDisabler, CC0-1.0), adapted.
 */
public final class PackDisabler {

    public static final String HYPIXEL_NAMESPACE = "hypixel_skyblock";
    private static boolean idleNoticeShown;

    private PackDisabler() {
    }

    public static void register() {
        // Read the 290 KB table off the client thread (scanboot-1104): every user of it checks isLoaded() or goes
        // through get()/idForModel(), which load it themselves, and load() is synchronized.
        Thread loader = new Thread(ItemLooks::ensureLoaded, "killer560smod-itemlooks");
        loader.setDaemon(true);
        loader.start();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!idleNoticeShown && PackDisablerConfig.getInstance().isEnabled() && noammInstalled()) {
                idleNoticeShown = true;
                ModChat.send("Pack Disabler", ModChat.text("is idle: Noamm's "), ModChat.value("PackDisabler"),
                        ModChat.text(" is installed and already reverts Hypixel's item textures."));
            }
        });
    }

    public static boolean noammInstalled() { return DetectedMods.isPackDisablerActive(); }

    /** The setting is on, Noamm's mod is not doing the job, and "Skyblock Only" allows it. */
    public static boolean isActive() {
        return PackDisablerConfig.getInstance().isEnabled() && !noammInstalled() && SkyblockGate.allows()
                && ItemLooks.isLoaded();
    }

    private static boolean isHypixelModel(Identifier model) {
        return model != null && HYPIXEL_NAMESPACE.equals(model.getNamespace());
    }

    private static final Identifier MAP_MODEL = Identifier.withDefaultNamespace("map");
    private static final Identifier FILLED_MAP_MODEL = Identifier.withDefaultNamespace("filled_map");
    private static final Identifier PAPER_MODEL = Identifier.withDefaultNamespace("paper");

    /** {@link #resolveModel(ItemStack, Identifier, ItemDisplayContext)} for a caller with no display context. */
    public static Identifier resolveModel(ItemStack stack, Identifier current) { return resolveModel(stack, current, null); }

    /**
     * The model {@code stack} should be drawn with. Called by the item-model mixin with the model the stack asked for;
     * returns it unchanged unless Pack Disabler is active and it is one of Hypixel's (or, on 26.3, a map menu button).
     * A stack with no SkyBlock id - a menu's picture of an item - is matched by its Hypixel model instead. Never
     * throws.
     */
    public static Identifier resolveModel(ItemStack stack, Identifier current, ItemDisplayContext context) {
        if (isMenuMap(stack, current, context)) return PAPER_MODEL;
        if (!isHypixelModel(current) || !isActive()) return current;
        try {
            CompoundTag tag = ItemNbt.view(stack);
            String id = tag == null ? null : tag.getStringOr("id", null);
            if (id == null) {
                if (tag != null && tag.contains("quiver_arrow")) return Identifier.withDefaultNamespace("arrow");
                // Menus show items without their SkyBlock id (Bazaar and AH pages, recipes, collections): the
                // Hypixel model alone names the item when exactly one table row uses it.
                id = ItemLooks.idForModel(current);
                if (id == null) return current;
            }
            Identifier model = ItemLooks.modelFor(id);
            if (model == null) return current;
            Identifier dynamic = tag == null ? null : dynamicModel(id, stack, tag, current);
            return dynamic != null ? dynamic : model;
        } catch (Exception e) {
            return current;
        }
    }

    /**
     * Minecraft 26.3 redrew the empty and the filled map as folded maps; before it both were a flat paper sheet, which
     * is how Hypixel's map buttons (Create Sell Offer, Create Buy Order, ...) always looked (killer560, 2026-11-03:
     * "the sell offer buttons look different now like maps instead of the old paper"). Hypixel's pack has no menu
     * models at all (pack 97: every item model is under item/), so this is the one menu change. Only a map drawn in a
     * GUI slot on its own vanilla model, with no SkyBlock id and no map data, is a button; a real map keeps its look.
     */
    static boolean isMenuMap(ItemStack stack, Identifier current, ItemDisplayContext context) {
        if (!McItems.MAPS_DRAWN_AS_MAPS || context != ItemDisplayContext.GUI || stack == null || stack.isEmpty()
                || !(MAP_MODEL.equals(current) || FILLED_MAP_MODEL.equals(current))) {
            return false;
        }
        if (!(stack.is(Items.MAP) || stack.is(Items.FILLED_MAP))) return false;
        // Create Buy Order is a filled map WITH a map number but no map data ("Unknown Map" in its tooltip; killer560,
        // 2026-11-03 screenshot). A map whose picture the client holds is a real map and keeps its look.
        if (stack.has(DataComponents.MAP_ID)) {
            try {
                net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
                if (level == null || level.getMapData(stack.get(DataComponents.MAP_ID)) != null) return false;
            } catch (Exception e) {
                return false;
            }
        }
        try {
            if (!PackDisablerConfig.getInstance().isPaperMenuButtons() || !isActive()) return false;
            CompoundTag tag = ItemNbt.view(stack);
            return tag == null || tag.getStringOr("id", null) == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The head skin for {@code stack}, called by the player-head mixin with the profile the stack carries. A real
     * player head keeps its own skin; anything else that we redirected to the player-head model gets its old skin.
     */
    public static ResolvableProfile resolveProfile(ItemStack stack, ResolvableProfile current) {
        if (stack == null || stack.isEmpty() || !isActive()) return current;
        try {
            if (current != null && stack.is(Items.PLAYER_HEAD)) return current;
            if (!isHypixelModel(stack.get(DataComponents.ITEM_MODEL))) return current;
            CompoundTag tag = ItemNbt.view(stack);
            String id = tag == null ? null : tag.getStringOr("id", null);
            if (id == null) {
                id = ItemLooks.idForModel(stack.get(DataComponents.ITEM_MODEL));
            }
            ResolvableProfile old = ItemLooks.profileFor(id);
            return old != null ? old : current;
        } catch (Exception e) {
            return current;
        }
    }

    // ---- items whose Hypixel model changes with their state (adapted from Noamm's PackDisabler, CC0-1.0) ----------

    private static Identifier dynamicModel(String id, ItemStack stack, CompoundTag tag, Identifier current) {
        switch (id) {
            case "CARNIVAL_SHOVEL": {
                String path = current.getPath();
                String last = path.substring(path.lastIndexOf('/') + 1);
                return switch (last) {
                    case "carnival_shovel_iron" -> Identifier.withDefaultNamespace("iron_shovel");
                    case "carnival_shovel_gold" -> Identifier.withDefaultNamespace("golden_shovel");
                    case "carnival_shovel_diamond" -> Identifier.withDefaultNamespace("diamond_shovel");
                    default -> null;
                };
            }
            case "FIREDUST_DAGGER", "BURSTFIRE_DAGGER", "HEARTFIRE_DAGGER":
                return tag.getIntOr("td_attune_mode", -1) == 1 ? Identifier.withDefaultNamespace("golden_sword") : null;
            case "MAWDUST_DAGGER", "BURSTMAW_DAGGER", "HEARTMAW_DAGGER":
                return tag.getIntOr("td_attune_mode", -1) == 3 ? Identifier.withDefaultNamespace("diamond_sword") : null;
            case "VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA": {
                Minecraft mc = Minecraft.getInstance();
                return mc.player != null && mc.player.getCooldowns().isOnCooldown(stack)
                        ? Identifier.withDefaultNamespace("golden_sword") : null;
            }
            case "FUNGI_CUTTER", "FUNGI_CUTTER_2", "FUNGI_CUTTER_3": {
                String mode = tag.getStringOr("fungi_cutter_mode", "");
                return "RED".equals(mode) ? Identifier.withDefaultNamespace("red_mushroom")
                        : "BROWN".equals(mode) ? Identifier.withDefaultNamespace("brown_mushroom") : null;
            }
            default:
                return null;
        }
    }
}
