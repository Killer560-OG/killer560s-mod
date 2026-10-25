package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.maxor.MaxorConfig;
import com.killer560.hub.maxor.MaxorCrystalsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.killer560.hub.compat.McCompat;

/**
 * Maxor's Crystals settings - the F7/M7 Phase 1 crystal respawn timer, placement timer, unplaced-crystal alert,
 * active counter and the optional crystal highlight (see {@link MaxorCrystalsFeature}). Everything defaults OFF.
 */
public class MaxorTab extends BaseTab {

    /** Time the reset box stays red and locked after the first click (killer560, 2026-10-18: "click it once and it changes
     *  red with a 2s cooldown before they click it again to actually wipe it"). */
    static final long ARM_COOLDOWN_MS = 2000L;
    /** After this long armed with no second click, the box goes back to normal, so a stale arm cannot wipe the time later. */
    static final long ARM_TIMEOUT_MS = 10_000L;

    public MaxorTab() {
        super("Maxor's Crystals");
    }

    /** The card is ON while any of the timers, the alert, the counter or the highlight is, OFF only when all are. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Respawn Timer", "Place Timer", "Unplaced Crystal Alert", "Active Crystal Counter",
                "Highlight Crystals");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MaxorConfig cfg = MaxorConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        // No switch over the whole tab (killer560, 2026-10-14): every timer, the alert, the counter and the crystal boxes
        // are independent, each has its own switch, and the card is ON while any is.

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Crystal Timers (P1)", false));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Respawn Timer", cfg::getSpawnTimerRaw, cfg::setSpawnTimer));
        widgets.add(toggle(col2X, y, colW, "Place Timer", cfg::getPlaceTimerRaw, cfg::setPlaceTimer));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Unplaced Crystal Alert", cfg::getPlaceAlertRaw, cfg::setPlaceAlert));
        widgets.add(toggle(col2X, y, colW, "Active Crystal Counter", cfg::getActiveCounterRaw, cfg::setActiveCounter));
        y += 22;

        y += 6;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Crystal Highlight", false));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Highlight Crystals", cfg::getHighlightRaw, cfg::setHighlight));
        widgets.add(colorButton(col2X, y, colW, "Highlight Color", cfg.getHighlightColor(),
                MaxorConfig.DEFAULT_HIGHLIGHT_COLOR, cfg::setHighlightColor));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Filled Boxes", cfg::isHighlightFilled, cfg::setHighlightFilled));
        y += 34;

        // The reset sits alone at the very bottom, in its own box, away from the settings above it.
        widgets.add(new ResetBox(contentX, y, contentWidth, 22, cfg));

        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), name, current, defaultColor, argb -> {
                setter.accept(argb);
                MaxorConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return onOffButton(label, getter, setter, () -> MaxorConfig.getInstance().save(), null, x, y, width, 18);
    }

    /**
     * The reset box: a card of its own, not a settings button. Idle it is a dark box with the orange edge. The first click arms
     * it: the box turns red and shows a countdown for {@link #ARM_COOLDOWN_MS}, and clicks in that time do nothing. Once the
     * cooldown has passed, the next click wipes the best place time. Armed with no second click for {@link #ARM_TIMEOUT_MS},
     * it goes back to idle.
     */
    private static final class ResetBox extends AbstractWidget {
        private final MaxorConfig cfg;
        /** When the box was armed, 0 when idle. */
        private long armedAtMs = 0L;

        ResetBox(int x, int y, int w, int h, MaxorConfig cfg) {
            super(x, y, w, h, Component.literal("Reset Best Place Time"));
            this.cfg = cfg;
        }

        private boolean armed(long now) {
            return armedAtMs != 0L && now - armedAtMs < ARM_TIMEOUT_MS;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            long now = System.currentTimeMillis();
            if (!armed(now)) {
                // First click: arm it, nothing is wiped yet.
                if (cfg.getBestPlaceMs() == 0L) {
                    return;
                }
                armedAtMs = now;
                return;
            }
            if (now - armedAtMs < ARM_COOLDOWN_MS) {
                return; // still cooling down: ignore the click
            }
            cfg.setBestPlaceMs(0L);
            cfg.save();
            armedAtMs = 0L;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            long now = System.currentTimeMillis();
            boolean on = armed(now);
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int edge = on ? MenuStyle.RED_TITLE : MenuStyle.ACCENT_EDGE;
            GuiRects r = GuiRects.begin(graphics);
            ScreenChrome.card(r, x, y, w, h, isHovered && !on, on);
            r.outline(x + 1, y + 1, w - 2, h - 2, edge);
            if (on) {
                r.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0x66FF5555);
            }
            r.submit();

            String text;
            int colour;
            if (cfg.getBestPlaceMs() == 0L) {
                text = "Reset Best Place Time: none";
                colour = MenuStyle.DIM2;
            } else if (!on) {
                text = String.format(Locale.US, "Reset Best Place Time: %.3fs", cfg.getBestPlaceMs() / 1000.0);
                colour = MenuStyle.TXT;
            } else if (now - armedAtMs < ARM_COOLDOWN_MS) {
                long left = (ARM_COOLDOWN_MS - (now - armedAtMs) + 999) / 1000;
                text = "Wait " + left + "s...";
                colour = MenuStyle.RED_TITLE;
            } else {
                text = "Click again to wipe it";
                colour = MenuStyle.RED_TITLE;
            }
            ScreenChrome.centered(graphics, text, x + w / 2, y + (h - 8) / 2, colour);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
