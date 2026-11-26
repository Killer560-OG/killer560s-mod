package com.killer560.hub.craftcost;

import com.killer560.hub.compat.McInput;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.ContainerOverlayElement;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Craft Cost (killer560, 2026-11-26: "if i am in the recipe menu then it shows the buy order cost of all the items in the
 * menu to make the item currently ... it should be a hud"): over a SkyBlock recipe view ("<Item> Recipe") or the Craft Item
 * menu a HUD panel lists the 3x3 ingredients merged by item, one aligned row each (quantity, full name, Bazaar buy order cost
 * = top buy order + 0.1 coins, "BIN" marker), then the total and the Instant Buy total in the same cost column. An ingredient
 * off the Bazaar falls back to its lowest BIN, else reads "not on Bazaar" and the total gets a "+". A left click on a priced
 * row sends "/bz name" (Bazaar) or "/ahs name" (BIN). While the recipe menu's Supercraft item is hovered, quantities, costs, the
 * total and Instant Buy also read the N-craft amount in parentheses, N and the per-ingredient totals read from that item's own
 * lore ("(8x crafts)", "0/256 Name"; killer560, 11-26). Built only when a slot, the prices or the setting changed, never per frame.
 */
public final class CraftCost {

    private static final Logger LOGGER = ModLog.get("killer560smod-craftcost");
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final Pattern CRAFTS = Pattern.compile("\\((\\d+)x crafts?\\)");
    private static final Pattern NEED = Pattern.compile("^\\d+/(\\d+) (.+)$");
    private static final int PAD = 4, LINE = 11, GAP = 4, COL = 8;
    private static final List<String> SAMPLE = List.of("§lCraft Cost: §r§6120.5k", "§764x §fEnchanted Diamond  §6118k",
            "§71x §fHyperion  §62.5k", "§7Instant Buy: §6125k");

    /** One panel line: each piece with its x, the cost right-aligned to {@code costEnd}, and the command a click sends (null: none). */
    record Row(String qty, int qtyX, String name, int nameX, String cost, int costX, int costEnd, String bin, int y, String cmd) {
    }

    /** The built panel: rows plus the size, fixed until the recipe or the prices change. */
    record Layout(List<Row> rows, int width, int height, int binX) {
        static final Layout EMPTY = new Layout(List.of(), 0, 0, 0);
    }

    /** The hovered Supercraft item's lore: N crafts and each ingredient's total need by plain name. */
    record Multi(int n, Map<String, Long> need) {
    }

    private static ContainerOverlayElement element;
    private static Layout layout = Layout.EMPTY;
    private static float fit = 1f;
    private static int hoverRow = -1;
    private static String builtKey = "";
    private static long lastTouchMs;
    private static int computes;

    private CraftCost() {
    }

    public static void register() {
        element = ContainerOverlayElement.register("craft_cost_panel", "Craft Cost", 150, 40, 10, 30,
                () -> CraftCostConfig.getInstance().isEnabled(), CraftCost::drawPanel)
                .previewSize(() -> new int[]{layout.width() > 0 ? layout.width() : 150, panelHeight()}).clampOnScreen();
        ScreenEvents.AFTER_INIT.register(CraftCost::onScreenInit);
    }

    private static int panelHeight() {
        return layout.rows().isEmpty() ? SAMPLE.size() * LINE + PAD * 2 : layout.height();
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
        // Only a left click on a priced row is taken; every slot click (and every other click) stays Hypixel's.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                McInput.button(event) != 0 || !onClick(event.x(), event.y()));
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            try {
                render(client, container, g, mouseX, mouseY);
            } catch (RuntimeException e) {
                LOGGER.error("[CraftCost] render failed", e);
            }
        });
        ScreenEvents.remove(screen).register(s -> {
            layout = Layout.EMPTY;
            hoverRow = -1;
            builtKey = "";
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
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
        Slot hov = ((com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
        ItemStack sup = hov != null && hov.hasItem() && isSupercraft(hov.getItem()) ? hov.getItem() : null;
        ItemLore supLore = sup == null ? null : sup.get(DataComponents.LORE);
        String key = sig + "/" + System.identityHashCode(sup) + "/" + System.identityHashCode(supLore) + "/" + menu.slots.get(25).getItem().getCount() + "/" + CraftCostConfig.version() + "/"
                + PriceData.generation() + "/" + System.identityHashCode(menu);
        if (!key.equals(builtKey)) {
            builtKey = key;
            int result = resultSlot(menu, screen.getTitle().getString());
            List<ItemStack> grid = new ArrayList<>(9);
            for (int i : GRID) {
                grid.add(menu.slots.get(i).getItem());
            }
            layout = result < 0 ? Layout.EMPTY : build(grid, client.font, sup == null ? null : parse(sup));
        }
        if (layout.rows().isEmpty()) {
            return;
        }
        // Beside the container on the roomier side; scaled down only when neither side holds the whole panel (never cut).
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos(), w = layout.width();
        int roomLeft = left - 8, roomRight = screen.width - (left + 176 + 8);
        boolean onLeft = w <= roomLeft || w > roomRight && roomLeft >= roomRight;
        fit = Math.max(0.4f, Math.min(1f, (float) (onLeft ? roomLeft : roomRight) / w));
        int pw = Math.round(w * fit);
        hoverRow = rowAt(mouseX, mouseY);
        g.nextStratum();
        element.draw(g, onLeft ? left - pw - 4 : left + 176 + 4, acc.killer560smod$getTopPos(), pw,
                Math.round(layout.height() * fit), CraftCost::drawPanel);
    }

    /** The clickable row under (mx, my) as last drawn, else -1. */
    private static int rowAt(double mx, double my) {
        if (!element.hits(mx, my)) {
            return -1;
        }
        double ly = (my - element.drawnY()) / (element.drawnScale() * fit);
        List<Row> rows = layout.rows();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.cmd() != null && ly >= r.y() - 1 && ly < r.y() + LINE - 1) {
                return i;
            }
        }
        return -1;
    }

    /** A left click on an ingredient row opens its market page ("/bz name" or "/ahs name"): one command, never queued. */
    static boolean onClick(double mx, double my) {
        if (!CraftCostConfig.getInstance().isEnabled()) {
            return false;
        }
        int i = rowAt(mx, my);
        return i >= 0 && ServerCommands.toServer(layout.rows().get(i).cmd());
    }

    private static String id(ItemStack s) {
        CompoundTag tag = s.isEmpty() ? null : ItemNbt.view(s);
        return tag == null ? null : ItemKeys.id(tag);
    }

    private static boolean isSupercraft(ItemStack s) {
        return s.getHoverName().getString().startsWith("Supercraft");
    }

    /** N and the totals from a Supercraft item's name and lore (codes and commas tolerated), null when either is missing. */
    static Multi parse(ItemStack s) {
        int n = 0;
        Map<String, Long> need = new LinkedHashMap<>();
        ItemLore lore = s.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        lines.add(s.getHoverName().getString());
        if (lore != null) {
            lore.lines().forEach(l -> lines.add(l.getString()));
        }
        for (String raw : lines) {
            String line = ChatObserver.stripCodes(raw).replace(",", "").trim();
            Matcher m = CRAFTS.matcher(line);
            if (n == 0 && m.find()) {
                n = Integer.parseInt(m.group(1));
            } else if ((m = NEED.matcher(line)).matches()) {
                need.put(m.group(2).trim(), Long.parseLong(m.group(1)));
            }
        }
        return n > 0 && !need.isEmpty() ? new Multi(n, need) : null;
    }

    /** The grid's ingredients merged by item id with their buy order and instant buy costs; empty when the grid has none. */
    static Layout build(List<ItemStack> grid, Font font, Multi multi) {
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
            return Layout.EMPTY;
        }
        double order = 0, instant = 0, order2 = 0, instant2 = 0;
        boolean incomplete = false, anyBazaar = false, anyBin = false;
        // {qty, name, cost, bin, command} per line; the title and the totals use the same columns.
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : merged.entrySet()) {
            Acc a = e.getValue();
            Double sell = PriceData.bazaarSell(e.getKey());
            Double buy = PriceData.bazaarBuy(e.getKey());
            double unit = sell != null && sell > 0 ? sell + 0.1 : buy != null && buy > 0 ? buy : 0;
            String cost, bin = "", cmd = null;
            long tot = multi == null ? 0 : multi.need().getOrDefault(a.name, (long) a.count[0] * multi.n());
            String qty2 = multi == null ? "" : " §8(" + tot + "x)", cost2 = "";
            if (unit > 0) {
                order += unit * a.count[0];
                instant += (buy != null && buy > 0 ? buy : unit) * a.count[0];
                anyBazaar = true;
                cost = "§6" + coins(unit * a.count[0]);
                order2 += unit * tot;
                instant2 += (buy != null && buy > 0 ? buy : unit) * tot;
                cost2 = multi == null ? "" : " §8(" + coins(unit * tot) + ")";
                cmd = "bz " + a.name;
            } else {
                Double lowest = PriceData.lowestBin(e.getKey());
                if (lowest != null && lowest > 0) {
                    order += lowest * a.count[0];
                    instant += lowest * a.count[0];
                    order2 += lowest * tot;
                    instant2 += lowest * tot;
                    cost2 = multi == null ? "" : " §8(" + coins(lowest * tot) + ")";
                    cost = "§6" + coins(lowest * a.count[0]);
                    bin = "§8BIN";
                    anyBin = true;
                    cmd = "ahs " + a.name;
                } else {
                    incomplete = true;
                    cost = "§8not on Bazaar";
                }
            }
            rows.add(new String[]{"§7" + a.count[0] + "x" + qty2, "§f" + a.name, cost + cost2, bin, cmd});
        }
        String plus = incomplete ? "+" : "";
        List<String[]> all = new ArrayList<>();
        all.add(new String[]{"", "§lCraft Cost", "§6" + coins(order) + (multi == null ? "" : " §8(" + coins(order2) + ")") + plus, "", null});
        all.addAll(rows);
        if (anyBazaar) {
            all.add(new String[]{"", "§7Instant Buy", "§6" + coins(instant) + (multi == null ? "" : " §8(" + coins(instant2) + ")") + plus, "", null});
        }
        int qtyW = 0, nameW = 0, costW = 0;
        for (String[] r : all) {
            qtyW = Math.max(qtyW, font.width(r[0]));
            nameW = Math.max(nameW, font.width(r[1]));
            costW = Math.max(costW, font.width(r[2]));
        }
        int nameX = PAD + qtyW + (qtyW > 0 ? COL : 0), costEnd = nameX + nameW + COL + costW;
        int binX = costEnd + 3, width = (anyBin ? binX + font.width("BIN") : costEnd) + PAD;
        List<Row> out = new ArrayList<>(all.size());
        int y = PAD;
        for (int i = 0; i < all.size(); i++) {
            String[] r = all.get(i);
            if (i == 1 || i == all.size() - 1 && anyBazaar) {
                y += GAP;
            }
            out.add(new Row(r[0], PAD + qtyW - font.width(r[0]), r[1], nameX, r[2], costEnd - font.width(r[2]), costEnd, r[3], y, r[4]));
            y += LINE;
        }
        return new Layout(List.copyOf(out), width, y + PAD - 1, binX);
    }

    private static String coins(double v) {
        return DungeonChestValuer.formatCoins(Math.round(v));
    }

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<Row> rows = layout.rows();
        int w = rows.isEmpty() ? 150 : layout.width(), h = panelHeight();
        g.pose().pushMatrix();
        g.pose().scale(fit, fit);
        g.fill(x, y, x + w, y + h, 0xC0101010);
        g.outline(x, y, w, h, ThemeAccent.accent(0xFFCC6600));
        int title = ThemeAccent.onDark(ThemeAccent.light(0xFFFFAA00));
        if (rows.isEmpty()) {
            for (int i = 0; i < SAMPLE.size(); i++) {
                g.text(font, SAMPLE.get(i), x + PAD, y + PAD + i * LINE, i == 0 ? title : 0xFFFFFFFF, true);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i == hoverRow) {
                g.fill(x + 2, y + r.y() - 1, x + w - 2, y + r.y() + LINE - 1, ThemeAccent.fill(0xFF4A2A08));
            }
            int c = i == 0 ? title : 0xFFFFFFFF;
            g.text(font, r.qty(), x + r.qtyX(), y + r.y(), c, true);
            g.text(font, r.name(), x + r.nameX(), y + r.y(), c, true);
            g.text(font, r.cost(), x + r.costX(), y + r.y(), c, true);
            if (!r.bin().isEmpty()) {
                g.text(font, r.bin(), x + layout.binX(), y + r.y(), c, true);
            }
        }
        g.pose().popMatrix();
    }

    // Testkit hooks.
    public static List<String> linesForTest() {
        return layout.rows().stream().map(r -> (r.qty() + " " + r.name() + "  " + r.cost() + " " + r.bin()).trim()).toList();
    }

    /** Per row {screen x of the cost's right end, screen y of the row's middle, screen x of the name, 1 if a click acts}. */
    public static List<int[]> rowsForTest() {
        double s = element.drawnScale() * fit;
        return layout.rows().stream().map(r -> new int[]{(int) Math.round(element.drawnX() + r.costEnd() * s),
                (int) Math.round(element.drawnY() + (r.y() + 4) * s), (int) Math.round(element.drawnX() + r.nameX() * s),
                r.cmd() == null ? 0 : 1}).toList();
    }

    public static boolean clickForTest(double x, double y) {
        return onClick(x, y);
    }

    public static int computesForTest() {
        return computes;
    }
}
