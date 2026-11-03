package com.killer560.hub.armorhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.storagesearch.StorageSearchNbtCodec;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Armor HUD (killer560, 2026-11-03: "make an armor hud thing that shows my helmet cp legs and boots with an option for
 * equipment as well"). A movable HUD element with the four worn armor pieces, read from the player's real armor slots,
 * and with Show Equipment the four Skyblock equipment pieces (necklace, cloak, belt, gloves/bracelet).
 * <p>
 * Equipment is not in the vanilla inventory: it is read while Hypixel's equipment menu is open and cached to
 * {@code killer560smod-armorhud-equipment.json} (a cache, kept out of profiles). The menu's title and slots are from
 * hypixelskyblock.minecraft.wiki's Equipment_Menu/UI page (checked 2026-11-03): title "Your Equipment and Stats", six
 * rows; necklace, cloak, belt and gloves/bracelet in column 2 of rows 2-5 (container slots 10, 19, 28, 37), armor in
 * column 3 beside them. A slot holding no Skyblock item (Hypixel's empty-slot placeholder) is cached as seen-empty; a
 * slot never seen draws nothing.
 * <p>
 * Drawn in game by {@code HudInGameRenderer} (its id is in UNDRAWN_ELEMENT_IDS), which applies the Skyblock Only gate,
 * the hidden-HUD gate and the open-menu gate; {@link Element#render} is both that draw and the HUD editor's preview.
 */
public final class ArmorHudFeature {

    public static final String ELEMENT_ID = "armor_hud";
    public static final String EQUIPMENT_TITLE = "Your Equipment and Stats";
    /** Necklace, cloak, belt, gloves/bracelet: container slots of the 6-row equipment menu (wiki, see class doc). */
    static final int[] EQUIPMENT_SLOTS = {10, 19, 28, 37};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private static final Logger LOGGER = ModLog.get("killer560smod-armorhud");
    private static final Path CACHE_PATH = ModPaths.config("killer560smod-armorhud-equipment.json");

    private static final int CELL = 18;
    private static final int PAD = 2;
    private static final int PANEL_BG = 0x90101010;
    private static final int PANEL_BORDER = 0xCCCC6600;

    /** Cached equipment: null = never seen; EMPTY = seen empty. */
    private static final ItemStack[] equipment = new ItemStack[4];
    /** The saved blobs not yet decoded (decoding needs a registry, so it waits for a world); null = nothing pending. */
    private static String[] pendingBlobs;
    private static boolean loaded = false;

    // Testkit hooks.
    private static int framesDrawn = 0;
    private static int iconsLastFrame = 0;
    private static int captures = 0;

    private ArmorHudFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ArmorHudFeature.tick", ArmorHudFeature::tick));
    }

    private static void tick(Minecraft client) {
        if (!loaded) {
            loadCache();
        }
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) || !isEquipmentMenu(screen)) {
            return;
        }
        capture(screen);
    }

    static boolean isEquipmentMenu(AbstractContainerScreen<?> screen) {
        String t = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return t != null && t.trim().equals(EQUIPMENT_TITLE);
    }

    /** Reads the four equipment slots of the open menu into the cache; saves when anything changed. */
    private static void capture(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = slots.size() - 36;
        if (containerSlots < 54) {
            return;
        }
        // Until the contents packet arrives every slot is empty; the menu's own filler panes say it has arrived.
        boolean any = false;
        for (int i = 0; i < containerSlots && !any; i++) {
            any = !slots.get(i).getItem().isEmpty();
        }
        if (!any) {
            return;
        }
        decodePending();
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            ItemStack in = slots.get(EQUIPMENT_SLOTS[i]).getItem();
            ItemStack now = isSkyblockItem(in) ? in.copy() : ItemStack.EMPTY;
            ItemStack was = equipment[i];
            if (was == null || !ItemStack.isSameItemSameComponents(was, now) || was.getCount() != now.getCount()) {
                equipment[i] = now;
                changed = true;
            }
        }
        if (changed) {
            captures++;
            saveCache();
        }
    }

    private static boolean isSkyblockItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && com.killer560.hub.autoroutes.ItemIdentity.skyblockId(stack) != null;
    }

    // ---- cache file ---------------------------------------------------------------------------------------------------

    private static void loadCache() {
        loaded = true;
        if (!Files.exists(CACHE_PATH)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(CACHE_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("equipment");
            String[] blobs = new String[4];
            for (int i = 0; i < 4 && arr != null && i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                blobs[i] = e == null || e.isJsonNull() ? null : e.getAsString();
            }
            pendingBlobs = blobs;
        } catch (Exception e) {
            LOGGER.warn("[ArmorHud] equipment cache unreadable: {}", e.toString());
        }
    }

    /** Decodes the saved blobs once a registry exists (a world or a connection). */
    private static void decodePending() {
        String[] blobs = pendingBlobs;
        if (blobs == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null && mc.getConnection() == null) {
            return;
        }
        pendingBlobs = null;
        for (int i = 0; i < 4; i++) {
            if (blobs[i] == null) {
                continue;
            }
            if (blobs[i].isEmpty()) {
                equipment[i] = ItemStack.EMPTY;
                continue;
            }
            List<ItemStack> d = StorageSearchNbtCodec.decode(blobs[i]);
            equipment[i] = d == null || d.isEmpty() ? null : d.get(0);
        }
    }

    private static void saveCache() {
        try {
            JsonArray arr = new JsonArray();
            for (ItemStack s : equipment) {
                if (s == null) {
                    arr.add(JsonNull.INSTANCE);
                } else if (s.isEmpty()) {
                    arr.add("");
                } else {
                    String blob = StorageSearchNbtCodec.encode(List.of(s));
                    if (blob == null) {
                        arr.add(JsonNull.INSTANCE);
                    } else {
                        arr.add(blob);
                    }
                }
            }
            JsonObject o = new JsonObject();
            o.add("equipment", arr);
            Files.createDirectories(CACHE_PATH.getParent());
            Files.writeString(CACHE_PATH, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[ArmorHud] equipment cache not saved: {}", e.toString());
        }
    }

    // ---- layout and drawing -------------------------------------------------------------------------------------------

    private static int lanes(ArmorHudConfig cfg) {
        return cfg.isShowEquipment() ? 2 : 1;
    }

    static int panelWidth(ArmorHudConfig cfg) {
        return (cfg.isVertical() ? lanes(cfg) : 4) * CELL + PAD * 2;
    }

    static int panelHeight(ArmorHudConfig cfg) {
        return (cfg.isVertical() ? 4 : lanes(cfg)) * CELL + PAD * 2;
    }

    /** The armor piece at {@code i} (0 helmet .. 3 boots) the player wears, EMPTY when none. */
    static ItemStack armor(Player player, int i) {
        return player == null ? ItemStack.EMPTY : player.getItemBySlot(ARMOR[i]);
    }

    /** The cached equipment piece at {@code i} (0 necklace .. 3 gloves), null when never seen. */
    static ItemStack equipmentAt(int i) {
        decodePending();
        return equipment[i];
    }

    /** Draws the panel at (x, y); returns how many item icons were drawn. */
    static int drawPanel(GuiGraphicsExtractor graphics, int x, int y, Player player, ArmorHudConfig cfg) {
        int w = panelWidth(cfg);
        int h = panelHeight(cfg);
        if (cfg.isBackground()) {
            GuiRects rects = GuiRects.begin(graphics);
            rects.fill(x, y, x + w, y + h, PANEL_BG);
            rects.outline(x, y, w, h, PANEL_BORDER);
            rects.submit();
        }
        var font = Minecraft.getInstance().font;
        int icons = 0;
        for (int lane = 0; lane < lanes(cfg); lane++) {
            for (int i = 0; i < 4; i++) {
                ItemStack stack = lane == 0 ? armor(player, i) : equipmentAt(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                int col = cfg.isVertical() ? lane : i;
                int row = cfg.isVertical() ? i : lane;
                int ix = x + PAD + col * CELL + 1;
                int iy = y + PAD + row * CELL + 1;
                graphics.item(stack, ix, iy);
                if (cfg.isShowDurability()) {
                    graphics.itemDecorations(font, stack, ix, iy, "");
                }
                icons++;
            }
        }
        return icons;
    }

    // ---- testkit hooks ------------------------------------------------------------------------------------------------

    public static int framesDrawn() {
        return framesDrawn;
    }

    public static int iconsLastFrame() {
        return iconsLastFrame;
    }

    public static int captures() {
        return captures;
    }

    /** The cached equipment's Skyblock ids, "" for seen-empty and null for never seen. */
    public static List<String> equipmentIds() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ItemStack s = equipmentAt(i);
            out.add(s == null ? null : s.isEmpty() ? "" : com.killer560.hub.autoroutes.ItemIdentity.skyblockId(s));
        }
        return out;
    }

    /** Forgets the in-memory cache and reads the file again (a restart, for the testkit). */
    public static void reloadCacheForTest() {
        for (int i = 0; i < 4; i++) {
            equipment[i] = null;
        }
        pendingBlobs = null;
        loadCache();
    }

    /** Clears the cache in memory and on disk (testkit). */
    public static void clearCacheForTest() {
        for (int i = 0; i < 4; i++) {
            equipment[i] = null;
        }
        pendingBlobs = null;
        loaded = true;
        try {
            Files.deleteIfExists(CACHE_PATH);
        } catch (Exception ignored) {
            // nothing to clear
        }
    }

    /** HUD-editor and in-game element (movable and scalable; position in {@code HudConfig}). */
    public static final class Element implements HudElement {

        public static final Element INSTANCE = new Element();

        @Override
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Armor HUD";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            var window = Minecraft.getInstance().getWindow();
            return window == null ? 120 : Math.max(0, window.getGuiScaledHeight() / 2 - height() / 2);
        }

        @Override
        public int width() {
            return panelWidth(ArmorHudConfig.getInstance());
        }

        @Override
        public int height() {
            return panelHeight(ArmorHudConfig.getInstance());
        }

        @Override
        public boolean isEnabledInSettings() {
            return ArmorHudConfig.getInstance().isEnabledRaw();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            ArmorHudConfig cfg = ArmorHudConfig.getInstance();
            boolean editor = HudVisibility.editorOpen();
            if (!editor && (!cfg.isEnabled() || HudVisibility.hidesHud())) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            try {
                int icons = drawPanel(graphics, x, y, client.player, cfg);
                if (!editor) {
                    framesDrawn++;
                    iconsLastFrame = icons;
                    HudSeen.markDrawn(ELEMENT_ID);
                }
            } catch (RuntimeException e) {
                // A bad item render must never take down the rest of the HUD.
            }
        }
    }
}
