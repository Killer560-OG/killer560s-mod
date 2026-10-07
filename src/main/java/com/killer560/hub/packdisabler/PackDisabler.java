package com.killer560.hub.packdisabler;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-packdisabler");
    public static final String HYPIXEL_NAMESPACE = "hypixel_skyblock";
    private static final Path MISSING_FILE = ModPaths.config("killer560smod-packdisabler-missing.txt");
    private static final int MAX_MISSING = 5000;

    /** SkyBlock id -> display name, for every item drawn on a Hypixel model this session that has no look of ours. */
    private static final Map<String, String> MISSING = new ConcurrentHashMap<>();
    private static boolean idleNoticeShown;

    private PackDisabler() {
    }

    public static void register() {
        ItemLooks.ensureLoaded();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!idleNoticeShown && PackDisablerConfig.getInstance().isEnabled() && noammInstalled()) {
                idleNoticeShown = true;
                ModChat.send("Pack Disabler", ModChat.text("is idle: Noamm's "), ModChat.value("PackDisabler"),
                        ModChat.text(" is installed and already reverts Hypixel's item textures."));
            }
        });
        if (BuildVariant.DEV_TOOLS) {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                    ClientCommands.literal("k560textures").then(ClientCommands.literal("missing").executes(ctx -> {
                        reportMissing();
                        return 1;
                    }))));
        }
    }

    public static boolean noammInstalled() {
        return DetectedMods.isPackDisablerActive();
    }

    /** The setting is on, Noamm's mod is not doing the job, and "Skyblock Only" allows it. */
    public static boolean isActive() {
        return PackDisablerConfig.getInstance().isEnabled() && !noammInstalled() && SkyblockGate.allows()
                && ItemLooks.isLoaded();
    }

    private static boolean isHypixelModel(Identifier model) {
        return model != null && HYPIXEL_NAMESPACE.equals(model.getNamespace());
    }

    /**
     * The model {@code stack} should be drawn with. Called by the item-model mixin with the model the stack asked for;
     * returns it unchanged unless Pack Disabler is active and it is one of Hypixel's. Never throws.
     */
    public static Identifier resolveModel(ItemStack stack, Identifier current) {
        if (!isHypixelModel(current) || !isActive()) {
            return current;
        }
        try {
            CompoundTag tag = ItemNbt.view(stack);
            if (tag == null) {
                return current;
            }
            String id = tag.getStringOr("id", null);
            if (id == null) {
                return tag.contains("quiver_arrow") ? Identifier.withDefaultNamespace("arrow") : current;
            }
            Identifier model = ItemLooks.modelFor(id);
            if (model == null) {
                noteMissing(id, stack);
                return current;
            }
            Identifier dynamic = dynamicModel(id, stack, tag, current);
            return dynamic != null ? dynamic : model;
        } catch (Exception e) {
            return current;
        }
    }

    /**
     * The head skin for {@code stack}, called by the player-head mixin with the profile the stack carries. A real
     * player head keeps its own skin; anything else that we redirected to the player-head model gets its old skin.
     */
    public static ResolvableProfile resolveProfile(ItemStack stack, ResolvableProfile current) {
        if (stack == null || stack.isEmpty() || !isActive()) {
            return current;
        }
        try {
            if (current != null && stack.is(Items.PLAYER_HEAD)) {
                return current;
            }
            if (!isHypixelModel(stack.get(DataComponents.ITEM_MODEL))) {
                return current;
            }
            CompoundTag tag = ItemNbt.view(stack);
            String id = tag == null ? null : tag.getStringOr("id", null);
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

    // ---- the missing-texture list (dev tool: hand it over after a Hypixel update) -------------------------------------

    private static void noteMissing(String id, ItemStack stack) {
        if (MISSING.size() >= MAX_MISSING || MISSING.containsKey(id)) {
            return;
        }
        String name;
        try {
            name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        } catch (Exception e) {
            name = id;
        }
        MISSING.put(id, name == null ? id : name);
    }

    /** Test hook and the command's body: every id seen without a look, plus live Bazaar products without one. */
    public static Map<String, String> missingSnapshot() {
        Map<String, String> out = new TreeMap<>(MISSING);
        try {
            for (BazaarProduct p : BazaarApi.getProducts()) {
                ItemLooks.Look look = ItemLooks.get(p.productId());
                if (look == null || !look.hasAnyLook()) {
                    out.putIfAbsent(p.productId(), "(Bazaar product)");
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Forget this session's sightings (test hook). */
    public static void clearMissing() {
        MISSING.clear();
    }

    /** Writes the list to config and returns its path. */
    public static Path writeMissing() throws Exception {
        Map<String, String> missing = missingSnapshot();
        List<String> lines = new ArrayList<>();
        lines.add("# Pack Disabler: SkyBlock items seen with no pre-pack look and no texture of ours.");
        lines.add("# id<TAB>name. Hand this list over and textures get drawn (tools/textures).");
        for (Map.Entry<String, String> e : missing.entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue());
        }
        Files.createDirectories(MISSING_FILE.getParent());
        Files.write(MISSING_FILE, lines, StandardCharsets.UTF_8);
        return MISSING_FILE;
    }

    private static void reportMissing() {
        try {
            Map<String, String> missing = missingSnapshot();
            Path file = writeMissing();
            ModChat.send("Pack Disabler", ModChat.value(String.valueOf(missing.size())),
                    ModChat.text(" item(s) with no texture this session"
                            + (isActive() ? "" : " (Pack Disabler is not active, so nothing new is being noted)")
                            + ". Written to "), ModChat.dim(file.toString()));
            int shown = 0;
            for (Map.Entry<String, String> e : missing.entrySet()) {
                if (shown++ >= 15) {
                    ModChat.send("Pack Disabler", ModChat.dim("... and " + (missing.size() - 15) + " more in the file"));
                    break;
                }
                ModChat.send("Pack Disabler", ModChat.value(e.getKey()), ModChat.dim("  " + e.getValue()));
            }
        } catch (Exception e) {
            LOGGER.warn("[Pack Disabler] could not write the missing list", e);
            ModChat.send("Pack Disabler", ModChat.bad("Could not write the list: " + e.getMessage()));
        }
    }
}
