package com.killer560.hub.reforge;

import com.killer560.hub.compat.McInput;
import com.killer560.hub.containercover.ContainerCover;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.BelowTooltip;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reforge Helper (SkyHanni's {@code ReforgeHelper}, killer560 2026-11-04: "a seprate menu on the left side you click with
 * all the reforges you can get and it wont let you reroll over it"). In the Blacksmith's "Reforge Item" menu: a panel left
 * of the menu listing every reforge the item can roll for its type and rarity, with that rarity's stats, the current one
 * marked; a click on a row adds it to (or takes it off) the Wanted Reforges. The item gets an outline once it has a wanted
 * one, its current reforge is written over it, and (Block When Wanted, on by default) the player's click on the Reforge
 * button is cancelled before anything is sent while it has one. It never clicks for you.
 * <p>
 * The rows are built once per item and wanted-list change ({@link #rebuild}); a frame only draws the cached strings.
 */
public final class ReforgeHelperFeature {

    static final String COVER = "reforge.helper";
    private static final int SLOT_SIZE = 16;
    private static final int ROW_H = 11;
    private static final int PAD = 4;
    private static final int HEAD_H = 24;
    private static final int PANEL_BG = 0xD0141008;
    private static final int PANEL_BORDER = 0xFF663D1A;
    private static final int TITLE_COLOR = 0xFFFF9A2E;
    private static final int HOVER_BG = 0x40FF9A2E;

    /** Hypixel's stat icons and colors, and the names the tooltip uses, by NEU stat key. */
    private static final Map<String, String[]> STATS = Map.ofEntries(
            Map.entry("strength", new String[]{"§c", "❁", "Strength"}),
            Map.entry("crit_chance", new String[]{"§9", "☣", "Crit Chance"}),
            Map.entry("crit_damage", new String[]{"§9", "☠", "Crit Damage"}),
            Map.entry("defense", new String[]{"§a", "❈", "Defense"}),
            Map.entry("health", new String[]{"§c", "❤", "Health"}),
            Map.entry("intelligence", new String[]{"§b", "✎", "Intelligence"}),
            Map.entry("speed", new String[]{"§f", "✦", "Speed"}),
            Map.entry("attack_speed", new String[]{"§e", "⚔", "Bonus Attack Speed"}),
            Map.entry("mining_speed", new String[]{"§6", "⸕", "Mining Speed"}),
            Map.entry("mining_fortune", new String[]{"§6", "☘", "Mining Fortune"}),
            Map.entry("farming_fortune", new String[]{"§6", "☘", "Farming Fortune"}),
            Map.entry("foraging_fortune", new String[]{"§6", "☘", "Foraging Fortune"}));

    private record Row(String name, String modifier, String label, String stats, List<Component> tip) {
    }

    private static int blocked;
    private static long lastBlockMs;

    // The panel as last built and drawn (render thread only).
    private static ItemStack builtFor;
    private static int builtVersion = -1;
    private static List<Row> rows = List.of();
    private static String header = "";
    private static String slotText = "";
    private static boolean itemWanted;
    private static int nameW;
    /** The panel's width with the stat column, and with names only (stats then only in the hover tooltip). */
    private static int fullW;
    private static int namesW;
    private static int panelW;
    private static boolean showStats;
    private static int panelH;
    private static int panelX = Integer.MIN_VALUE;
    private static int panelY;

    private ReforgeHelperFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ReforgeHelperFeature::onScreenInit);
    }

    static boolean active() {
        return ReforgeHelperConfig.getInstance().isEnabled();
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container) || !ReforgeMenu.isTitle(container.getTitle().getString())) {
            return;
        }
        builtFor = null;
        panelX = Integer.MIN_VALUE;
        // Before the tooltip pass, so a row's hover tooltip shows (ScreenEvents.afterExtract runs after it).
        BelowTooltip.register(screen, (s, graphics, mouseX, mouseY, partialTick) -> {
            try {
                render(container, graphics, mouseX, mouseY);
            } catch (RuntimeException e) {
                ModLog.get("killer560smod-reforge").error("[Reforge Helper] render failed", e);
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!active() || McInput.button(event) != 0) {
                return true;
            }
            Row row = rowAt(event.x(), event.y());
            if (row == null) {
                return true;
            }
            ReforgeHelperConfig cfg = ReforgeHelperConfig.getInstance();
            cfg.toggleWanted(row.name, row.modifier);
            cfg.save();
            return false;
        });
    }

    private static Row rowAt(double x, double y) {
        if (panelX == Integer.MIN_VALUE || x < panelX || x >= panelX + panelW) {
            return null;
        }
        int i = (int) Math.floor((y - panelY - HEAD_H) / ROW_H);
        return y >= panelY + HEAD_H && i < rows.size() ? rows.get(i) : null;
    }

    /** Builds the rows, header and slot label for {@code item} (once per item and wanted-list change). */
    private static void rebuild(ItemStack item, ReforgeHelperConfig cfg) {
        builtFor = item;
        builtVersion = cfg.version();
        Set<String> wanted = cfg.wantedKeys();
        String current = ReforgeMenu.reforge(item);
        ReforgeTable.Reforge known = current.isEmpty() ? null : ReforgeTable.byKey(current);
        itemWanted = ReforgeMenu.hasWanted(item, wanted);
        String shown = known != null ? known.name()
                : current.isEmpty() ? "" : Character.toUpperCase(current.charAt(0)) + current.substring(1).replace('_', ' ');
        slotText = shown.isEmpty() ? "" : (itemWanted ? "§a" : "§e") + shown;
        Font font = Minecraft.getInstance().font;
        List<Row> out = new ArrayList<>();
        String[] rt = item.isEmpty() ? null : ReforgeMenu.rarityAndType(item);
        if (item.isEmpty()) {
            header = "§7Put an item in";
        } else if (rt == null || ReforgeTable.typesFor(rt[1]).isEmpty()) {
            header = "§7No reforges for it";
        } else {
            header = "§7" + title(rt[0]) + " " + title(rt[1]);
            for (ReforgeTable.Reforge r : ReforgeTable.forCategory(rt[1])) {
                boolean isCurrent = r == known;
                boolean isWanted = wanted.contains(r.modifier()) || wanted.contains(ReforgeHelperConfig.key(r.name()));
                String label = (isCurrent ? "§6▸ " : "  ") + (isWanted ? "§a✔ " : isCurrent ? "§6" : "§7") + r.name();
                StringBuilder stats = new StringBuilder();
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("§6" + r.name() + (isCurrent ? " §7(current)" : "")));
                Map<String, Double> s = r.stats().getOrDefault(rt[0], Map.of());
                for (Map.Entry<String, Double> e : s.entrySet()) {
                    String[] st = STATS.getOrDefault(e.getKey(), new String[]{"§7", "", title(e.getKey())});
                    String v = number(e.getValue());
                    stats.append(st[0]).append(v).append(st[1]).append(' ');
                    tip.add(Component.literal(st[0] + v + " " + st[2]));
                }
                if (s.isEmpty()) {
                    stats.append("§8no stats at this rarity");
                }
                tip.add(Component.literal(isWanted ? "§eClick to stop wanting it" : "§eClick to want it"));
                out.add(new Row(r.name(), r.modifier(), label, stats.toString().trim(), List.copyOf(tip)));
            }
        }
        rows = List.copyOf(out);
        nameW = 0;
        int statW = 0;
        for (Row r : rows) {
            nameW = Math.max(nameW, font.width(r.label));
            statW = Math.max(statW, font.width(r.stats));
        }
        int headW = Math.max(font.width("Reforges"), font.width(header));
        fullW = Math.max(headW, nameW + 8 + statW) + 2 * PAD;
        namesW = Math.max(headW, nameW) + 2 * PAD;
        panelH = HEAD_H + rows.size() * ROW_H + PAD;
    }

    private static String title(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String w : upper.toLowerCase(Locale.ROOT).split("[_ ]")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String number(double v) {
        String n = v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
        return v >= 0 ? "+" + n : n;
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeHelperConfig cfg = ReforgeHelperConfig.getInstance();
        if (!active()) {
            panelX = Integer.MIN_VALUE;
            return;
        }
        ItemStack item = ReforgeMenu.item(screen.getMenu());
        if (item != builtFor || cfg.version() != builtVersion) {
            rebuild(item, cfg);
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos();
        int top = acc.killer560smod$getTopPos();
        Font font = Minecraft.getInstance().font;
        graphics.nextStratum();
        if (!item.isEmpty()) {
            Slot slot = screen.getMenu().slots.get(ReforgeMenu.ITEM_SLOT);
            if (itemWanted) {
                graphics.outline(left + slot.x - 1, top + slot.y - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, cfg.getColor() | 0xFF000000);
            }
            if (!slotText.isEmpty()) {
                graphics.text(font, slotText, left + slot.x + 8 - font.width(slotText) / 2, top + slot.y - 10, 0xFFFFFFFF, true);
            }
        }
        // Left of the menu; on a narrow screen (GUI scale 4 at 1080p) the stats move into the hover tooltip so the
        // panel still fits beside it.
        showStats = left - 8 >= fullW;
        panelW = showStats ? fullW : namesW;
        int x = Math.max(2, left - panelW - 6);
        int y = Math.max(2, top);
        panelX = x;
        panelY = y;
        ContainerCover.coverRect(screen, COVER, x, y, x + panelW, y + panelH);
        graphics.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        graphics.outline(x, y, panelW, panelH, PANEL_BORDER);
        graphics.text(font, "Reforges", x + PAD, y + PAD, TITLE_COLOR, true);
        graphics.text(font, header, x + PAD, y + PAD + 10, 0xFFFFFFFF, true);
        Row hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int ry = y + HEAD_H + i * ROW_H;
            if (r == hovered) {
                graphics.fill(x + 1, ry - 1, x + panelW - 1, ry + ROW_H - 1, HOVER_BG);
            }
            graphics.text(font, r.label, x + PAD, ry, 0xFFFFFFFF, true);
            if (showStats) {
                graphics.text(font, r.stats, x + PAD + nameW + 8, ry, 0xFFFFFFFF, true);
            }
        }
        if (hovered != null) {
            graphics.setComponentTooltipForNextFrame(font, hovered.tip, mouseX, mouseY);
        }
    }

    /** Whether a click on {@code slotId} of {@code screen} must be cancelled (called from the slot click mixin). */
    public static boolean shouldBlock(AbstractContainerScreen<?> screen, int slotId) {
        if (slotId != ReforgeMenu.BUTTON_SLOT || !active() || !ReforgeHelperConfig.getInstance().isBlockWanted()
                || !ReforgeMenu.isTitle(screen.getTitle().getString())) {
            return false;
        }
        ItemStack item = ReforgeMenu.item(screen.getMenu());
        if (!ReforgeMenu.hasWanted(item, ReforgeHelperConfig.getInstance().wantedKeys())) {
            return false;
        }
        blocked++;
        long now = System.currentTimeMillis();
        if (now - lastBlockMs > 2_000L) {
            ReforgeTable.Reforge r = ReforgeTable.byKey(ReforgeMenu.reforge(item));
            ModChat.send("Reforge Helper", ModChat.text("It already has "),
                    ModChat.value(r != null ? r.name() : ReforgeMenu.reforge(item)), ModChat.text(", a wanted reforge: click not sent."));
        }
        lastBlockMs = now;
        return true;
    }

    /** Harness: Reforge button clicks cancelled. */
    public static int blockedClicks() {
        return blocked;
    }

    /** Harness: the panel's rows as last built, "name|stats" with the color codes left out. */
    public static List<String> rowsForTest() {
        List<String> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.name + "|" + net.minecraft.ChatFormatting.stripFormatting(r.stats));
        }
        return out;
    }

    /** Harness: the panel's header line, without color codes. */
    public static String headerForTest() {
        return net.minecraft.ChatFormatting.stripFormatting(header);
    }

    /** Harness: the GUI-space center of {@code name}'s row as last drawn, or null. */
    public static double[] rowCenterForTest(String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name.equals(name) && panelX != Integer.MIN_VALUE) {
                return new double[]{panelX + panelW / 2.0, panelY + HEAD_H + i * ROW_H + ROW_H / 2.0 - 1};
            }
        }
        return null;
    }
}
