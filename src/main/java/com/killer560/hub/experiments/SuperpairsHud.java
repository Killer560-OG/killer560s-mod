package com.killer560.hub.experiments;

import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Superpairs Overview (killer560, 2026-11-01: "add a hud option to show how many things are left for superpairs. It
 * should look like skyhannis where it shows found stuff undiscovered pairs and undiscovered powers"). A panel beside the
 * Superpairs board, drawn in the container's own render pass (like the Start ETable button, so the menu's dark
 * background never covers it), movable in Edit HUD Positions. The numbers are {@link ExperimentSolver#superpairsSummary}:
 * <ul>
 *   <li><b>Found</b>: every reward seen, green once claimed, white with both tiles known, gray with one ("1/2").</li>
 *   <li><b>Powerups</b>: every powerup turned over.</li>
 *   <li><b>Undiscovered pairs</b>: the half-found ones (partner certainly still covered), plus "up to N new" pairs
 *       neither of whose tiles has been seen.</li>
 *   <li><b>Undiscovered powerups</b>: exact once the board forces it (0 or 1), otherwise "up to N": Hypixel does not
 *       publish how many powerups a board has, so the count is never guessed.</li>
 * </ul>
 * Shown whenever the Superpairs Overview setting is on, a Superpairs board is open and the Experiments solver or Auto
 * E-Table is on (either one reads the board each tick; it never clicks anything itself).
 */
public final class SuperpairsHud {

    static final String ELEMENT_ID = "superpairs_overview";
    private static final int WIDTH = 170;
    private static final int LINE = 10;
    /** At most this many found rewards are listed; the rest are counted on one line. */
    private static final int MAX_FOUND_LINES = 14;

    /** Frames drawn over a real board, and the last lines drawn (testkit evidence). */
    private static volatile long framesDrawn;
    private static volatile List<String> lastLines = List.of();

    private SuperpairsHud() {
    }

    static void register() {
        HudElementRegistry.markContainerOnly(ELEMENT_ID);
        HudElementRegistry.register(new BasicHud(ELEMENT_ID, "Superpairs Overview", 4, 40) {

            // The editor's box is the preview's own size (testkit 394: it was 170x94 around a 190x84 preview).
            @Override
            public int width() {
                return panelWidth(PREVIEW);
            }

            @Override
            public int height() {
                return 4 + LINE * PREVIEW.size();
            }

            @Override
            public boolean isEnabledInSettings() {
                return ExperimentsConfig.getInstance().isSuperpairsHudEnabled();
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                drawPanel(graphics, x, y, PREVIEW);
            }
        });
    }

    /** What the HUD editor shows. */
    private static final List<String> PREVIEW = List.of("§6Superpairs", "§7Found:", " §aEnchanted Book (Sharpness VII)",
            " §fTitanic Experience Bottle", " §71/2 Grand Experience Bottle", "§7Powerups: §bInstant Find",
            "§7Undiscovered pairs: §e3 §7+ up to §e4 §7new", "§7Undiscovered powerups: §7up to §e2");

    /** The panel's unscaled width for these lines: {@link #WIDTH}, or wider for a long line. */
    private static int panelWidth(List<String> lines) {
        Font font = Minecraft.getInstance().font;
        int w = WIDTH;
        if (font == null) {
            return w;
        }
        for (String line : lines) {
            w = Math.max(w, font.width(line.replaceAll("§.", "")) + 8);
        }
        return w;
    }

    /** From the container render pass ({@link ExperimentsFeature#renderStartButtonOverContainer}). */
    static void renderOverContainer(GuiGraphicsExtractor graphics, ExperimentSolver.SuperpairsSummary summary) {
        if (summary == null || !ExperimentsConfig.getInstance().isSuperpairsHudEnabled()) {
            return;
        }
        HudElement element = HudElementRegistry.byId(ELEMENT_ID);
        if (element == null) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(element).clone();
        float scale = HudElementRegistry.resolveScale(element);
        pos[1] = (int) com.killer560.hub.inventorysearch.InventorySearchFeature.keepAbove(
                com.killer560.hub.compat.McCompat.screen(net.minecraft.client.Minecraft.getInstance()), pos[0], pos[1],
                element.width() * scale, element.height() * scale); // clear of the search bar
        List<String> lines = lines(summary);
        lastLines = lines;
        framesDrawn++;
        HudSeen.markDrawn(ELEMENT_ID);
        graphics.pose().pushMatrix();
        graphics.pose().translate(pos[0], pos[1]);
        graphics.pose().scale(scale, scale);
        int[] size = drawPanel(graphics, 0, 0, lines);
        graphics.pose().popMatrix();
        com.killer560.hub.containercover.ContainerCover.coverRectOnCurrentScreen("experiments.superpairs",
                pos[0], pos[1], pos[0] + size[0] * scale, pos[1] + size[1] * scale);
    }

    static List<String> lines(ExperimentSolver.SuperpairsSummary s) {
        List<String> out = new ArrayList<>();
        out.add("§6Superpairs");
        out.add("§7Found: §f" + s.found().size());
        int shown = 0;
        for (ExperimentSolver.FoundKind kind : s.found()) {
            if (shown == MAX_FOUND_LINES) {
                out.add(" §7... " + (s.found().size() - shown) + " more");
                break;
            }
            String name = kind.name() == null ? "?" : kind.name().replaceAll("§.", "");
            out.add(kind.claimed() ? " §a" + name : kind.seen() >= 2 ? " §f" + name : " §71/2 " + name);
            shown++;
        }
        out.add("§7Powerups: " + (s.powerups().isEmpty() ? "§7none yet" : "§b" + String.join(", ", s.powerups())));
        if (!s.consistent()) {
            out.add("§7Undiscovered: §cboard not read yet");
            return out;
        }
        int newMin = s.newPairsMin();
        int newMax = s.newPairsMax();
        String newPairs = newMax <= 0 ? ""
                : newMin == newMax ? " §7+ §e" + newMax + " §7new"
                : newMin == 0 ? " §7+ up to §e" + newMax + " §7new"
                : " §7+ §e" + newMin + "§7-§e" + newMax + " §7new";
        out.add("§7Undiscovered pairs: §e" + s.undiscoveredPairsMin() + newPairs);
        int exact = s.powerupsExact();
        out.add("§7Undiscovered powerups: " + (exact >= 0 ? "§e" + exact : "§7up to §e" + s.coveredPowerupsMax()));
        return out;
    }

    /** @return the panel's unscaled {width, height}. */
    private static int[] drawPanel(GuiGraphicsExtractor graphics, int x, int y, List<String> lines) {
        Font font = Minecraft.getInstance().font;
        int w = panelWidth(lines);
        int h = 4 + LINE * lines.size();
        com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(
                com.killer560.hub.gui.OverlayThemes.EXPERIMENTS_START_BUTTONS);
        graphics.fill(x, y, x + w, y + h, menu == null ? 0xC01A1A1A : menu.panel());
        graphics.outline(x, y, w, h, menu == null ? 0xFFCC6600 : menu.accent());
        int ty = y + 3;
        for (String line : lines) {
            graphics.text(font, line, x + 4, ty, 0xFFFFFFFF, true);
            ty += LINE;
        }
        return new int[]{w, h};
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static List<String> lastLines() {
        return lastLines;
    }
}
