package com.killer560.hub.blessings;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

/**
 * Dungeon Blessings tracker - the run's Power/Time/Wisdom/Stone/Life levels on a movable HUD. Default OFF
 * ({@link BlessingsConfig}). Used to also have an optional client-chat announcement and an optional
 * once-per-blessing party message; removed 2026-09-27 (killer560: "remove the announcments tab in blessings").
 * <ul>
 * <li>Parsing: {@link BlessingTracker} - the existing tab-list packet hook, no new mixin, same footer strings
 * NoammAddons ({@code DungeonListener.kt} / {@code enums/Blessing.kt}) and Devonian
 * ({@code features/dungeons/BlessingsDisplay.kt}) use.</li>
 * <li>HUD layout: one line per enabled blessing, "Power: 12" (or "Power XII" with Roman Numerals on) - Devonian's
 * two formats, drawn in its order (Power, Time, Wisdom, Stone, Life) in its colours.</li>
 * <li>LEVELS ONLY: no source gives a level -> stat table, so no derived stat is ever shown.</li>
 * <li>Reset on world change, like both sources.</li>
 * </ul>
 */
public final class BlessingsFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-blessings");

    public static final HudElement HUD = new BlessingsHud();

    private static Object lastLevel = null;

    private BlessingsFeature() {
    }

    public static void register() {
        BlessingsConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BlessingsFeature.tick", BlessingsFeature::tick));
        LOGGER.info("[Blessings] Registered (HUD default OFF)");
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            BlessingTracker.reset();
        }
    }

    /** "Power: 12" / "Power XII". */
    private static String lineFor(Blessing blessing, int level, BlessingsConfig cfg) {
        return cfg.isRomanNumerals()
                ? blessing.displayName() + " " + Blessing.toRoman(level)
                : blessing.displayName() + ": " + level;
    }

    /** Movable/scalable in the HUD editor; drawn by {@code hud/HudInGameRenderer} (its id is in
     *  {@code UNDRAWN_ELEMENT_IDS}). */
    private static final class BlessingsHud implements HudElement {

        /** Editor preview values, Devonian's {@code getEditText()} sample set. */
        private static final int[] EXAMPLE = {29, 5, 11, 10, 36};

        @Override
        public String id() {
            return "blessings";
        }

        @Override
        public String displayName() {
            return "Blessings";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Free row in the x=10 column: 100/140/160/180/200/220/280/300/320/340 are taken by other elements.
            return 360;
        }

        @Override
        public int width() {
            // The drawn lines (it was a fixed 80 by every blessing's row, shown or not).
            int w = 1;
            for (Row line : lines()) {
                w = Math.max(w, Minecraft.getInstance().font.width(line.text()));
            }
            return w;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(lines().size(), 9);
        }

        private record Row(String text, int color) {
        }

        /** The rows render() draws; render() and the box both read it. */
        private static java.util.List<Row> lines() {
            Minecraft client = Minecraft.getInstance();
            BlessingsConfig cfg = BlessingsConfig.getInstance();
            boolean editor = McCompat.screen(client) instanceof HudEditorScreen;
            java.util.List<Row> out = new java.util.ArrayList<>();
            if (!editor && !cfg.isHudEnabled()) {
                return out;
            }
            Blessing[] all = Blessing.values();
            for (int i = 0; i < all.length; i++) {
                Blessing blessing = all[i];
                if (!cfg.isShown(blessing)) {
                    continue;
                }
                int level = editor ? EXAMPLE[i] : BlessingTracker.level(blessing);
                if (level > 0) {
                    out.add(new Row(lineFor(blessing, level, cfg), cfg.getColor(blessing)));
                }
            }
            return out;
        }

        @Override
        public boolean isEnabledInSettings() {
            // Setting only - being in a dungeon is what the draw stamp in render() below reports.
            return BlessingsConfig.getInstance().isHudEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            int row = 0;
            for (Row line : lines()) {
                graphics.text(client.font, line.text(), x, y + row * 9, 0xFF000000 | line.color(), true);
                row++;
            }
            if (row > 0) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
