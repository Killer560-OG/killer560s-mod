package com.killer560.hub.blessings;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dungeon Blessings tracker - the run's Power/Time/Wisdom/Stone/Life levels on a movable HUD, with an optional
 * client-chat announcement and an optional once-per-blessing party message. Default OFF ({@link BlessingsConfig}).
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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-blessings");

    public static final HudElement HUD = new BlessingsHud();

    private static Object lastLevel = null;

    private BlessingsFeature() {
    }

    public static void register() {
        BlessingsConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(BlessingsFeature::tick);
        LOGGER.info("[Blessings] Registered (HUD + announcements default OFF)");
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
            return 80;
        }

        @Override
        public int height() {
            return 9 * Blessing.values().length;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            BlessingsConfig cfg = BlessingsConfig.getInstance();
            boolean editor = client.screen instanceof HudEditorScreen;
            if (!editor && !cfg.isHudEnabled()) {
                return;
            }
            int row = 0;
            Blessing[] all = Blessing.values();
            for (int i = 0; i < all.length; i++) {
                Blessing blessing = all[i];
                if (!cfg.isShown(blessing)) {
                    continue;
                }
                int level = editor ? EXAMPLE[i] : BlessingTracker.level(blessing);
                if (level <= 0) {
                    continue;
                }
                graphics.text(client.font, lineFor(blessing, level, cfg), x, y + row * 9,
                        0xFF000000 | cfg.getColor(blessing), true);
                row++;
            }
        }
    }
}
