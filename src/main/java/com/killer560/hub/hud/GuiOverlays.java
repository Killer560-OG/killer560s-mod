package com.killer560.hub.hud;

import com.killer560.hub.dvd.DvdFeature;
import com.killer560.hub.etherwarp.EtherwarpHudElement;
import com.killer560.hub.experiments.ExperimentsFeature;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The always-on overlays that used to be seven {@code @Inject(method = "extractRenderState", at = @At("TAIL"))}
 * mixins on {@code Gui}, now Fabric HUD layers added after every vanilla one.
 * <p>
 * Moved 2026-10-04: on 26.2 {@code Gui.extractRenderState} is {@code (DeltaTracker, boolean, boolean)} with no
 * graphics argument, so every one of those mixins failed to apply and the 26.2 jar crashed at startup (found by
 * the testkit's first 26.2 run). Fabric's layer API is the same on both versions - {@code Ap3Feature} already used
 * it from shared code. Registered in the order the mixin configs were listed in fabric.mod.json, so what drew on
 * top still does.
 */
public final class GuiOverlays {

    /** killer560's theme orange, forced onto every {@link ModOverlayMessage} popup (2026-09-14: "any mod generated
     *  text should follow the same color pattern"). */
    private static final int THEME_ORANGE = 0xFFCC6600;

    private GuiOverlays() {
    }

    public static void register() {
        add("overlay_message", GuiOverlays::drawOverlayMessage);
        add("gif_player", GifPlayerFeature::renderOverlay);
        add("dvd", DvdFeature::renderOverlay);
        add("experiments_status", ExperimentsFeature::renderOverlay);
        add("ability_timers", graphics -> {
            // Chat stays see-through (killer560: "dont make it hide the gui if i open chat"); the HUD editor
            // draws the element itself.
            if (!HudVisibility.menuOpen()) {
                HudElementRegistry.drawAt(graphics, HudElementRegistry.byId("ability_timers"));
            }
        });
        add("dungeon_info", graphics -> {
            if (!HudVisibility.menuOpen()) {
                HudElementRegistry.drawAt(graphics, HudElementRegistry.byId("dungeon_info"));
            }
        });
        add("etherwarp_waypoints", graphics -> {
            HudElement element = HudElementRegistry.byId("etherwarp_waypoints");
            if (element instanceof EtherwarpHudElement e && e.isVisible()) {
                HudElementRegistry.drawAt(graphics, element);
            }
        });
    }

    private static void add(String path, java.util.function.Consumer<GuiGraphicsExtractor> draw) {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", path), (graphics, deltaTracker) -> draw.accept(graphics));
    }

    /** {@link ModOverlayMessage}, centred on screen and word-wrapped to two thirds of its width (2026-09-07), in
     *  the theme colour with any caller's own formatting codes stripped. */
    private static void drawOverlayMessage(GuiGraphicsExtractor graphics) {
        String message = ModOverlayMessage.current();
        if (message == null) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message);
        if (plain == null) {
            plain = message;
        }
        Font font = Minecraft.getInstance().font;
        // Auto Scale: the popup is laid out on a canvas 1/f the size of the GUI area and drawn scaled by f, so it
        // is the same fraction of the window on every monitor and still centred and wrapped to two thirds.
        float f = AutoScale.current();
        int canvasW = AutoScale.layoutSize(graphics.guiWidth(), f);
        int canvasH = AutoScale.layoutSize(graphics.guiHeight(), f);
        int wrapWidth = canvasW * 2 / 3;
        List<FormattedCharSequence> lines = font.split(Component.literal(plain), wrapWidth);
        int totalHeight = lines.size() * font.lineHeight;
        int centerX = canvasW / 2;
        int startY = (canvasH - totalHeight) / 2;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(f, f);
            for (int i = 0; i < lines.size(); i++) {
                graphics.centeredText(font, lines.get(i), centerX, startY + i * font.lineHeight, THEME_ORANGE);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
