package com.killer560.hub.craftcost;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.ContainerOverlayElement;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Craft Cost (killer560, 2026-11-26: "if i am in the recipe menu then it shows the buy order cost of all the items in the
 * menu to make the item currently ... it should be a hud"): over a SkyBlock recipe view ("<Item> Recipe") or the Craft Item
 * menu a HUD panel lists the 3x3 ingredients merged by item with each line's Bazaar buy order cost (top buy order + 0.1 coins),
 * the total, the Instant Buy total and what the recipe makes. An ingredient off the Bazaar falls back to its lowest BIN, else
 * reads "not on Bazaar" and the total gets a "+". Built only when a slot, the prices or the setting changed, never per frame.
 */
public final class CraftCost {

    private static final Logger LOGGER = ModLog.get("killer560smod-craftcost");
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int PAD = 4, LINE = 10, NAME_PX = 110;
    private static final List<String> SAMPLE = List.of("§lCraft Cost: §r§6120.5k", "§764x §fEnchanted Diamond  §6118k",
            "§71x §fHyperion  §62.5k", "§7Instant Buy: §6125k");

    private static ContainerOverlayElement element;
    private static List<String> lines = List.of();
    private static int panelWidth;
    private static String builtKey = "";
    private static long lastTouchMs;
    private static int computes;

    private CraftCost() {
    }

    public static void register() {
        element = ContainerOverlayElement.register("craft_cost_panel", "Craft Cost", 150, 40, 10, 30,
                () -> CraftCostConfig.getInstance().isEnabled(), CraftCost::drawPanel)
                .previewSize(() -> new int[]{panelWidth > 0 ? panelWidth : 150, panelHeight()}).clampOnScreen();
        ScreenEvents.AFTER_INIT.register(CraftCost::onScreenInit);
    }

    private static int panelHeight() {
        return (lines.isEmpty() ? SAMPLE : lines).size() * LINE + PAD * 2;
    }

    /** Slots whose stacks decide the layout: the filler around the arrow (14-16, 22-24) and the result (25). */
    private static final int[] FRAME = {14, 15, 16, 22, 23, 24, 25};

    /**
     * The result slot when the 6-row menu has a recipe view's layout, else -1. Hypixel titles the view by the item ("Terminator",
     * "Enchanted Diamond Recipe"), so the layout decides: a Skyblock item in 25, a marker item (no Skyblock id) in 23 and
     * filler (no Skyblock id) at 14-16 and 22 and 24, which no chest, backpack or "x" Recipes list full of items has. The Craft Item
     * menu keeps its title and its result in 23.
     */
    static int resultSlot(AbstractContainerMenu menu, String title) {
        if (menu.slots.size() != 90) {
            return -1;
        }
        if (title.equals("Craft Item")) {
            return 23;
        }
        if (id(menu.slots.get(25).getItem()) == null || menu.slots.get(23).getItem().isEmpty()) {
            return -1;
        }
        for (int i : FRAME) {
            if (i != 25 && id(menu.slots.get(i).getItem()) != null) {
                return -1;
            }
        }
        return 25;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container) || !(container.getMenu() instanceof ChestMenu)
                || container.getMenu().slots.size() != 90) {
            return;
        }
        builtKey = "";
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            try {
                render(client, container, g);
            } catch (RuntimeException e) {
                LOGGER.error("[CraftCost] render failed", e);
            }
        });
        ScreenEvents.remove(screen).register(s -> {
            lines = List.of();
            builtKey = "";
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!CraftCostConfig.getInstance().isEnabled() || !SkyblockGate.allows()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTouchMs > 10_000L) {
            lastTouchMs = now;
            PriceData.touchFromOtherFeature();
        }
        AbstractContainerMenu menu = screen.getMenu();
        long sig = 7;
        for (int i : GRID) {
            ItemStack s = menu.slots.get(i).getItem();
            sig = sig * 31 + System.identityHashCode(s) * 3L + s.getCount();
        }
        for (int i : FRAME) {
            sig = sig * 31 + System.identityHashCode(menu.slots.get(i).getItem());
        }
        String key = sig + "/" + menu.slots.get(25).getItem().getCount() + "/" + CraftCostConfig.version() + "/"
                + PriceData.generation() + "/" + System.identityHashCode(menu);
        if (!key.equals(builtKey)) {
            builtKey = key;
            int result = resultSlot(menu, screen.getTitle().getString());
            List<ItemStack> grid = new ArrayList<>(9);
            for (int i : GRID) {
                grid.add(menu.slots.get(i).getItem());
            }
            lines = result < 0 ? List.of() : build(grid, menu.slots.get(result).getItem(), client.font);
            int w = 0;
            for (String l : lines) {
                w = Math.max(w, client.font.width(l));
            }
            panelWidth = w + PAD * 2;
        }
        if (lines.isEmpty()) {
            return;
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int x = acc.killer560smod$getLeftPos() - panelWidth - 4;
        if (x < 0) {
            x = acc.killer560smod$getLeftPos() + 176 + 4;
        }
        g.nextStratum();
        element.draw(g, x, acc.killer560smod$getTopPos(), panelWidth, panelHeight(), CraftCost::drawPanel);
    }

    private static String id(ItemStack s) {
        CompoundTag tag = s.isEmpty() ? null : ItemNbt.view(s);
        return tag == null ? null : ItemKeys.id(tag);
    }

    /** The grid's ingredients merged by item id with their buy order and instant buy costs; empty when the grid has none. */
    static List<String> build(List<ItemStack> grid, ItemStack out, Font font) {
        computes++;
        record Acc(String name, int[] count) {
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (ItemStack s : grid) {
            String id = id(s);
            if (id != null) {
                merged.computeIfAbsent(id, k -> new Acc(s.getHoverName().getString(), new int[1])).count[0] += s.getCount();
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        double order = 0, instant = 0;
        boolean incomplete = false, anyBazaar = false;
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : merged.entrySet()) {
            Acc a = e.getValue();
            Double sell = PriceData.bazaarSell(e.getKey());
            Double buy = PriceData.bazaarBuy(e.getKey());
            double unit = sell != null && sell > 0 ? sell + 0.1 : buy != null && buy > 0 ? buy : 0;
            String cost;
            if (unit > 0) {
                order += unit * a.count[0];
                instant += (buy != null && buy > 0 ? buy : unit) * a.count[0];
                anyBazaar = true;
                cost = "§6" + coins(unit * a.count[0]);
            } else {
                Double bin = PriceData.lowestBin(e.getKey());
                if (bin != null && bin > 0) {
                    order += bin * a.count[0];
                    instant += bin * a.count[0];
                    cost = "§6" + coins(bin * a.count[0]) + " §8BIN";
                } else {
                    incomplete = true;
                    cost = "§8not on Bazaar";
                }
            }
            rows.add("§7" + a.count[0] + "x §f" + clip(font, a.name) + "  " + cost);
        }
        List<String> res = new ArrayList<>();
        res.add("§lCraft Cost: §r§6" + coins(order) + (incomplete ? "+" : ""));
        res.addAll(rows);
        if (anyBazaar) {
            res.add("§7Instant Buy: §6" + coins(instant) + (incomplete ? "+" : ""));
        }
        if (id(out) != null) {
            res.add("§7Makes: §f" + out.getCount() + "x " + clip(font, out.getHoverName().getString()));
        }
        return List.copyOf(res);
    }

    private static String coins(double v) {
        return DungeonChestValuer.formatCoins(Math.round(v));
    }

    private static String clip(Font font, String name) {
        return font.width(name) <= NAME_PX ? name : font.plainSubstrByWidth(name, NAME_PX - font.width("...")) + "...";
    }

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String> ls = lines.isEmpty() ? SAMPLE : lines;
        int w = panelWidth > 0 && !lines.isEmpty() ? panelWidth : 150;
        g.fill(x, y, x + w, y + ls.size() * LINE + PAD * 2, 0xC0101010);
        g.outline(x, y, w, ls.size() * LINE + PAD * 2, ThemeAccent.accent(0xFFCC6600));
        int title = ThemeAccent.onDark(ThemeAccent.light(0xFFFFAA00));
        for (int i = 0; i < ls.size(); i++) {
            g.text(font, ls.get(i), x + PAD, y + PAD + i * LINE, i == 0 ? title : 0xFFFFFFFF, true);
        }
    }

    // Testkit hooks.
    public static List<String> linesForTest() {
        return lines;
    }

    public static int computesForTest() {
        return computes;
    }
}
