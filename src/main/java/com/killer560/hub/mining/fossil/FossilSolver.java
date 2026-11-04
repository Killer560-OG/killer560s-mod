package com.killer560.hub.mining.fossil;

import com.killer560.hub.compat.McItems;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.partyfinder.PartyFinderParser;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fossil Solver (legit, both builds; the idea and the fossil shapes are Skyblocker's FossilSolver, the solver is this
 * mod's): in the Glacite Fossil Excavator, every placement of the eight fossils that agrees with the tiles already dug
 * (white panes are fossil, brown dirt is still unknown, anything else is empty) and with the "Fossil Excavation Progress"
 * percentage is counted, and each unknown tile gets the share of those placements that cover it. The best tiles get a
 * green outline, the others a blue fill as strong as their chance (Show Chances), and a line above the menu says how many
 * placements are left, the fossil once only one kind fits, and the fewest tiles still to dig against the chisel charges.
 * Draws only; you click. Off by default.
 */
public final class FossilSolver {

    public static final IslandConfig CFG = IslandConfig.of("mining", "fossil")
            .flag("chances", true)
            .flag("info", true);

    static final int UNKNOWN = 0;
    static final int EMPTY = 1;
    static final int FOSSIL = 2;
    private static final int SLOT = 16;
    private static final Pattern PROGRESS = Pattern.compile("Fossil Excavation Progress: (\\d{1,2}(?:\\.\\d)?)%");
    private static final Pattern CHARGES = Pattern.compile("Chisel Charges Remaining: (\\d{1,2})");

    /** What one board works out to. {@code chance[y*9+x]} is 0..1 for an unknown tile, 0 otherwise. */
    public record Result(int placements, double[] chance, List<Integer> best, String fossil, int minimumLeft) {
    }

    private static volatile Result last = null;
    private static volatile List<Integer> lastBest = List.of();

    private FossilSolver() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(FossilSolver::onScreenInit);
    }

    public static boolean isExcavatorTitle(String title) {
        return title != null && "Fossil Excavator".equals(ChatObserver.strip(title));
    }

    /** The result the last frame drew (read by the testkit). */
    public static Result last() {
        return last;
    }

    /** The slots the last frame outlined as best (read by the testkit). */
    public static List<Integer> lastBest() {
        return lastBest;
    }

    /**
     * Solves a board: {@code tiles[y*9+x]} is UNKNOWN (0), EMPTY (1) or FOSSIL (2); {@code percent} the progress shown, or
     * a negative number when none is. Testkit entry point.
     */
    public static Result solve(int[] tiles, double percent) {
        int fossils = 0;
        for (int t : tiles) {
            if (t == FOSSIL) {
                fossils++;
            }
        }
        int[] cover = new int[tiles.length];
        int n = 0;
        int minimum = Integer.MAX_VALUE;
        String kind = null;
        boolean oneKind = true;
        for (FossilShapes.Placement p : FossilShapes.ALL) {
            if (percent >= 0 && Math.abs(p.fossil().percent - percent) > 0.05) {
                continue;
            }
            if (!fits(p, tiles)) {
                continue;
            }
            n++;
            minimum = Math.min(minimum, p.fossil().tiles - fossils);
            if (kind == null) {
                kind = p.fossil().label;
            } else if (!kind.equals(p.fossil().label)) {
                oneKind = false;
            }
            for (int y = 0; y < FossilShapes.HEIGHT; y++) {
                for (int x = 0; x < FossilShapes.WIDTH; x++) {
                    int i = y * FossilShapes.WIDTH + x;
                    if (tiles[i] == UNKNOWN && p.has(x, y)) {
                        cover[i]++;
                    }
                }
            }
        }
        double[] chance = new double[tiles.length];
        int top = 0;
        for (int i = 0; i < tiles.length; i++) {
            chance[i] = n == 0 ? 0 : (double) cover[i] / n;
            top = Math.max(top, cover[i]);
        }
        List<Integer> best = new ArrayList<>();
        if (top > 0) {
            for (int i = 0; i < tiles.length; i++) {
                if (cover[i] == top) {
                    best.add(i);
                }
            }
        }
        return new Result(n, chance, List.copyOf(best), oneKind ? kind : null, n == 0 ? -1 : minimum);
    }

    private static boolean fits(FossilShapes.Placement p, int[] tiles) {
        for (int y = 0; y < FossilShapes.HEIGHT; y++) {
            for (int x = 0; x < FossilShapes.WIDTH; x++) {
                int t = tiles[y * FossilShapes.WIDTH + x];
                if (t == FOSSIL && !p.has(x, y) || t == EMPTY && p.has(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The board in a Fossil Excavator menu's first 54 slots, or null when the menu is smaller. */
    public static int[] board(AbstractContainerMenu menu) {
        int size = FossilShapes.WIDTH * FossilShapes.HEIGHT;
        if (menu.slots.size() < size) {
            return null;
        }
        int[] tiles = new int[size];
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            tiles[i] = s.is(McItems.WHITE_STAINED_GLASS_PANE) ? FOSSIL
                    : s.is(McItems.BROWN_STAINED_GLASS_PANE) ? UNKNOWN : EMPTY;
        }
        return tiles;
    }

    /** The first number one of the menu's item lores shows for {@code p}, or -1. */
    private static double loreNumber(AbstractContainerMenu menu, Pattern p) {
        int size = Math.min(menu.slots.size(), FossilShapes.WIDTH * FossilShapes.HEIGHT);
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            for (String line : PartyFinderParser.loreStrings(s)) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    try {
                        return Double.parseDouble(m.group(1));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !isExcavatorTitle(container.getTitle().getString())) {
            return;
        }
        last = null;
        lastBest = List.of();
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            try {
                render(container, graphics);
            } catch (RuntimeException e) {
                ModLog.get("killer560smod-mining").error("[Fossil Solver] render failed", e);
            }
        });
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (!CFG.isEnabled()) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int[] tiles = board(menu);
        if (tiles == null) {
            return;
        }
        Result r = solve(tiles, loreNumber(menu, PROGRESS));
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos();
        int top = acc.killer560smod$getTopPos();
        graphics.nextStratum();
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != UNKNOWN || r.chance()[i] <= 0) {
                continue;
            }
            Slot slot = menu.slots.get(i);
            int x0 = left + slot.x;
            int y0 = top + slot.y;
            if (r.best().contains(i)) {
                graphics.fill(x0, y0, x0 + SLOT, y0 + SLOT, 0x7055FF55);
                graphics.outline(x0 - 1, y0 - 1, SLOT + 2, SLOT + 2, 0xFF55FF55);
            } else if (CFG.on("chances")) {
                int alpha = (int) Math.round(30 + 150 * r.chance()[i]);
                graphics.fill(x0, y0, x0 + SLOT, y0 + SLOT, alpha << 24 | 0x3060FF);
            }
        }
        if (CFG.on("info")) {
            int charges = (int) loreNumber(menu, CHARGES);
            StringBuilder sb = new StringBuilder("§6Fossil Solver: §f");
            if (r.placements() == 0) {
                sb.append("no fossil fits");
            } else {
                sb.append(r.placements()).append(r.placements() == 1 ? " placement" : " placements");
                if (r.fossil() != null) {
                    sb.append(" §7· §e").append(r.fossil());
                }
                sb.append(" §7· §f").append(r.minimumLeft()).append(" to dig");
                if (charges >= 0) {
                    sb.append(charges >= r.minimumLeft() ? " §7(" : " §c(").append(charges).append(" charges)");
                }
            }
            graphics.text(Minecraft.getInstance().font, sb.toString(), left, top - 12, 0xFFFFFFFF, true);
        }
        last = r;
        lastBest = r.best();
    }

    /** A one-line summary of a result, for the testkit's notes. */
    public static String describe(Result r) {
        return String.format(Locale.ROOT, "%d placements, best %s, fossil %s, min %d", r.placements(), r.best(),
                r.fossil(), r.minimumLeft());
    }
}
