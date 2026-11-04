package com.killer560.hub.mining.cold;

import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cold Overlay (legit, both builds; Skyblocker's GlaciteColdOverlay, SkyHanni's ColdOverlay): while the sidebar shows
 * Hypixel's "Cold: -N❄" line (the Glacite Tunnels and Mineshafts), vanilla's powder-snow frost is drawn over the screen,
 * more opaque the colder you are (N of 100), scaled by Strength. A campfire's "reduced your ❄ Cold to 0!" clears it at
 * once. Draws only. Off by default.
 */
public final class ColdOverlay {

    public static final IslandConfig CFG = IslandConfig.of("mining", "coldoverlay")
            .num("strength", 100, 10, 100);

    private static final Identifier POWDER_SNOW_OUTLINE = Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");
    private static final Pattern COLD = Pattern.compile("Cold: -?(\\d{1,4})❄"); // sidebar text is the server's alone
    private static final String CAMPFIRE = "The warmth of the campfire reduced your ❄ Cold to 0!";

    private static volatile int cold = 0;
    /** The opacity the last drawn frame used (read by the testkit: proves the layer drew). */
    private static volatile float lastDrawn = 0f;
    private static volatile long resetAt = 0;
    private static int tick = 0;

    private ColdOverlay() {
    }

    public static void register() {
        int bit = FeatureBits.register("Cold Overlay", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ColdOverlay.tick", bit, ColdOverlay::onTick));
        ChatObserver.subscribe(ColdOverlay::onChat);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath("killer560smod", "mining_cold_overlay"),
                (graphics, deltaTracker) -> draw(graphics));
    }

    /** The Cold read from the sidebar, 0..100 (read by the testkit). */
    public static int cold() {
        return cold;
    }

    /** The overlay's opacity right now, 0..1 (read by the testkit). */
    public static float opacity() {
        if (!CFG.isEnabled()) {
            return 0f;
        }
        return Math.min(100, cold) / 100f * CFG.num("strength") / 100f;
    }

    public static float lastDrawn() {
        return lastDrawn;
    }

    /** The Cold on a list of sidebar lines (color codes allowed), or 0 when none shows it. Testkit entry point. */
    public static int readCold(List<String> sidebar) {
        for (String line : sidebar) {
            Matcher m = COLD.matcher(ChatObserver.strip(line));
            if (m.find()) {
                try {
                    return Math.min(100, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void onTick(Minecraft client) {
        if (tick++ % 10 != 0) {
            return;
        }
        if (!CFG.isEnabled() || client.player == null || System.currentTimeMillis() - resetAt < 3000) {
            cold = 0;
            return;
        }
        cold = readCold(ScoreboardData.sidebarNow(client));
    }

    private static void onChat(Component message) {
        if (CFG.isEnabledRaw() && CAMPFIRE.equals(ChatObserver.strip(message))) {
            cold = 0;
            resetAt = System.currentTimeMillis();
        }
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        float a = opacity();
        if (a <= 0f || HudVisibility.hidesHud()) {
            lastDrawn = 0f;
            return;
        }
        lastDrawn = a;
        graphics.blit(RenderPipelines.GUI_TEXTURED, POWDER_SNOW_OUTLINE, 0, 0, 0.0f, 0.0f, graphics.guiWidth(),
                graphics.guiHeight(), graphics.guiWidth(), graphics.guiHeight(), ARGB.white(a));
    }
}
