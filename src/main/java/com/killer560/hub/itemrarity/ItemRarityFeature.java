package com.killer560.hub.itemrarity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Item Rarity Backgrounds" - draws a translucent rarity-colored square / circle / outline behind every
 * Skyblock item in container slots (and optionally the hotbar), like Skyblocker's "Item Rarity Backgrounds"
 * and NoammAddons' "Item Rarity" feature. Purely visual.
 * <p>
 * Rarity detection, in order (same order Skyblocker's {@code ItemUtils#getItemRarity} uses):
 * <ol>
 *   <li>Pets (Skyblock id {@code PET}): the {@code petInfo} JSON's {@code tier}, one tier higher if the
 *       held item is {@code PET_ITEM_TIER_BOOST}.</li>
 *   <li>Lore, scanned bottom-up: the first line that STARTS with a rarity word, allowing the leading
 *       obfuscated recombobulator character ("a LEGENDARY DUNGEON CHESTPLATE a") and "SHINY ".</li>
 *   <li>Pet display names ("[Lvl 100] Golden Dragon" - menus without petInfo): the color of the name
 *       segment after the level bracket, as NoammAddons' {@code PET_PATTERN} does.</li>
 *   <li>A non-vanilla {@code tooltip_style} component whose path names a rarity (Skyblocker's fallback
 *       for reforge stones etc.).</li>
 * </ol>
 * Results are cached per {@link ItemStack} instance (weak keys - ItemStack uses identity equality) and the
 * cached entry is only trusted while the stack still holds the exact same lore/custom-data/name/tooltip
 * component instances, so lore is parsed once per stack rather than every frame.
 */
public final class ItemRarityFeature {

    private static final Pattern LORE_RARITY = Pattern.compile(
            "^(?:\\S{1,2} )?(?:SHINY )?(VERY SPECIAL|UNCOMMON|COMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|ULTIMATE|SUPREME|ADMIN)(?: |$)");

    private static final Map<ItemStack, CacheEntry> CACHE = new WeakHashMap<>();

    /** Circle style: per-row horizontal inset for a 16px disc, merged into runs of equal inset
     *  ({startRow, rowCount, inset}) so each slot is a handful of fills instead of 16. */
    private static final int[][] CIRCLE_RUNS = buildCircleRuns();

    /** Set from the (possibly off-render-thread) disconnect event; the render thread does the actual clear,
     *  since {@link WeakHashMap} isn't thread-safe. */
    private static volatile boolean clearRequested;

    private static ServerData lastServer;
    private static String lastServerIp;
    private static boolean lastServerIsSkyblock;

    private ItemRarityFeature() {
    }

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearRequested = true);
    }

    /** Drops every cached rarity (applied on the render thread at the next lookup). */
    public static void clearCache() {
        clearRequested = true;
    }

    // ---------------------------------------------------------------- rendering

    /** Called from the container-slot mixin (just before the item is drawn) and the hotbar mixin. */
    public static void drawBackground(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, boolean hotbar) {
        ItemRarityConfig cfg = ItemRarityConfig.getInstance();
        if (!cfg.isEnabled() || (hotbar && !cfg.isShowInHotbar())) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CacheEntry entry = lookup(stack);
        if (entry.rarity == null) {
            return;
        }
        if (cfg.isSkyblockOnly() && !entry.hasSkyblockId && !isOnSkyblockServer()) {
            return;
        }
        int alpha = Math.round(cfg.getOpacity() * 2.55f);
        int color = (alpha << 24) | entry.rarity.rgb;
        switch (cfg.getStyle()) {
            case OUTLINE -> {
                // Inset rings, one per pixel of width, so a thicker outline grows inward and never leaves the slot.
                int w = cfg.getOutlineWidth();
                for (int i = 0; i < w; i++) {
                    graphics.outline(x + i, y + i, 16 - 2 * i, 16 - 2 * i, color);
                }
            }
            case CIRCLE -> {
                for (int[] run : CIRCLE_RUNS) {
                    graphics.fill(x + run[2], y + run[0], x + 16 - run[2], y + run[0] + run[1], color);
                }
            }
            default -> graphics.fill(x, y, x + 16, y + 16, color);
        }
    }

    private static boolean isOnSkyblockServer() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null) {
            lastServer = null;
            return false;
        }
        if (server != lastServer || server.ip != lastServerIp) {
            lastServer = server;
            lastServerIp = server.ip;
            String ip = server.ip == null ? "" : server.ip.toLowerCase(Locale.ROOT);
            lastServerIsSkyblock = ip.contains("hypixel.net") || ip.contains("p3sim.net");
        }
        return lastServerIsSkyblock;
    }

    private static int[][] buildCircleRuns() {
        List<int[]> runs = new ArrayList<>();
        int prevInset = -1;
        for (int row = 0; row < 16; row++) {
            double dy = row + 0.5 - 8.0;
            double half = Math.sqrt(Math.max(0.0, 64.0 - dy * dy));
            int inset = (int) Math.round(8.0 - half);
            if (inset == prevInset) {
                runs.get(runs.size() - 1)[1]++;
            } else {
                runs.add(new int[]{row, 1, inset});
                prevInset = inset;
            }
        }
        return runs.toArray(new int[0][]);
    }

    // ---------------------------------------------------------------- parsing + cache

    /** Rarity of a stack, or null. Public so other overlays (e.g. Storage Overlay's own grid) can reuse it. */
    public static ItemRarity getRarity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return lookup(stack).rarity;
    }

    private static CacheEntry lookup(ItemStack stack) {
        if (clearRequested) {
            clearRequested = false;
            CACHE.clear();
            lastServer = null;
            lastServerIp = null;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        Identifier tooltipStyle = stack.get(DataComponents.TOOLTIP_STYLE);

        CacheEntry cached = CACHE.get(stack);
        if (cached != null && cached.lore == lore && cached.data == data && cached.name == name
                && cached.tooltipStyle == tooltipStyle) {
            return cached;
        }
        CacheEntry fresh;
        try {
            fresh = parse(stack, lore, data, name, tooltipStyle);
        } catch (Exception e) {
            fresh = new CacheEntry(lore, data, name, tooltipStyle, null, false);
        }
        CACHE.put(stack, fresh);
        return fresh;
    }

    private static CacheEntry parse(ItemStack stack, ItemLore lore, CustomData data, Component name, Identifier tooltipStyle) {
        String skyblockId = "";
        CompoundTag tag = null;
        if (data != null) {
            tag = data.copyTag();
            skyblockId = tag.getStringOr("id", "");
        }
        boolean hasId = !skyblockId.isEmpty();

        // 1. Pets: petInfo JSON
        if ("PET".equals(skyblockId) && tag != null) {
            ItemRarity pet = fromPetInfo(tag.getStringOr("petInfo", ""));
            if (pet != null) {
                return new CacheEntry(lore, data, name, tooltipStyle, pet, true);
            }
        }

        // 2. Lore, bottom-up
        if (lore != null) {
            List<Component> lines = lore.lines();
            for (int i = lines.size() - 1; i >= 0; i--) {
                ItemRarity r = fromLoreLine(lines.get(i).getString());
                if (r != null) {
                    return new CacheEntry(lore, data, name, tooltipStyle, r, hasId);
                }
            }
        }

        // 3. Pet display name color ("[Lvl 100] <colored name>")
        ItemRarity fromName = fromPetName(stack.getHoverName());
        if (fromName != null) {
            return new CacheEntry(lore, data, name, tooltipStyle, fromName, hasId);
        }

        // 4. Custom tooltip style
        if (tooltipStyle != null && !"minecraft".equals(tooltipStyle.getNamespace())) {
            String path = tooltipStyle.getPath();
            int slash = path.lastIndexOf('/');
            ItemRarity r = ItemRarity.byName(path.substring(slash + 1).replace('_', ' '));
            if (r != null) {
                return new CacheEntry(lore, data, name, tooltipStyle, r, hasId);
            }
        }
        return new CacheEntry(lore, data, name, tooltipStyle, null, hasId);
    }

    /** "LEGENDARY SWORD", "a EPIC DUNGEON HELMET a", "VERY SPECIAL", "SHINY MYTHIC ..." -&gt; rarity. */
    static ItemRarity fromLoreLine(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String line = raw.replace(' ', ' ').trim();
        if (line.isEmpty()) {
            return null;
        }
        Matcher m = LORE_RARITY.matcher(line);
        return m.find() ? ItemRarity.byName(m.group(1)) : null;
    }

    private static ItemRarity fromPetInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("tier")) {
                return null;
            }
            ItemRarity tier = ItemRarity.byName(obj.get("tier").getAsString());
            if (tier == null) {
                return null;
            }
            if (obj.has("heldItem") && "PET_ITEM_TIER_BOOST".equals(obj.get("heldItem").getAsString())) {
                tier = tier.next();
            }
            return tier;
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemRarity fromPetName(Component hoverName) {
        if (hoverName == null || !hoverName.getString().startsWith("[Lvl ")) {
            return null;
        }
        boolean[] pastLevel = {false};
        Optional<ItemRarity> found = hoverName.visit((Style style, String text) -> {
            String segment = text;
            if (!pastLevel[0]) {
                int close = text.indexOf(']');
                if (close < 0) {
                    return Optional.empty();
                }
                pastLevel[0] = true;
                segment = text.substring(close + 1);
            }
            if (segment.isBlank()) {
                return Optional.empty();
            }
            TextColor color = style.getColor();
            if (color == null) {
                return Optional.empty();
            }
            // Gray/dark-gray "[123✦]" skin/star brackets map to no rarity and are skipped naturally.
            ItemRarity r = ItemRarity.byColor(color.getValue());
            return r == null ? Optional.empty() : Optional.of(r);
        }, Style.EMPTY);
        return found.orElse(null);
    }

    private static final class CacheEntry {
        final ItemLore lore;
        final CustomData data;
        final Component name;
        final Identifier tooltipStyle;
        final ItemRarity rarity;
        final boolean hasSkyblockId;

        CacheEntry(ItemLore lore, CustomData data, Component name, Identifier tooltipStyle, ItemRarity rarity, boolean hasSkyblockId) {
            this.lore = lore;
            this.data = data;
            this.name = name;
            this.tooltipStyle = tooltipStyle;
            this.rarity = rarity;
            this.hasSkyblockId = hasSkyblockId;
        }
    }
}
