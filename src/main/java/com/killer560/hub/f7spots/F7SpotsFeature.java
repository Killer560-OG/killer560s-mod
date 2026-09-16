package com.killer560.hub.f7spots;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F7 Spots - F7/M7 walk-to waypoints, Storm's crush timer and Last Breath aim spots. Entry point; everything is
 * default OFF ({@link F7SpotsConfig}) and both coordinate lists ship empty.
 * <ul>
 * <li>{@link WalkWaypoint} - killer560's own "walk here" positions per phase/floor, box + beam + label + distance
 * ({@link F7SpotsRenderer}). Added in-game with "Add Waypoint Here" or by hand-editing the JSON.</li>
 * <li>{@link CrushTimer} - Storm (P2) crush timer/HUD + optional title, and the purple pad highlight.</li>
 * <li>{@link AimSpot} - aim markers per situation and per class; only NoammAddons' arrow-stack points ship
 * built in ({@link AimSpots}), everything else is killer560's to fill in.</li>
 * </ul>
 * Floor/phase detection is entirely reused: {@code DungeonState} for the floor, {@link Floor7Tracker} for the
 * boss phase (chat-driven, y-level fallback), and {@code witherdragons/P5State} for your dungeon class - so this
 * feature adds no detection of its own and works on p3sim.net wherever those already do.
 */
public final class F7SpotsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-f7spots");

    public static final HudElement HUD = new CrushHud();

    private static Object lastLevel = null;

    private F7SpotsFeature() {
    }

    public static void register() {
        F7SpotsConfig.getInstance();
        // ChatObserver (not Fabric CHAT/GAME) so a line another mod cancelled and re-added still arrives.
        ChatObserver.subscribe(F7SpotsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(F7SpotsFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!SkyblockGate.allows()) {
                return;
            }
            F7SpotsRenderer.render(context);
        });
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "f7spots_" + HUD.id()),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
        LOGGER.info("[F7Spots] Registered (all features default OFF, waypoint/aim lists empty)");
    }

    /** In the F7/M7 boss arena - {@link Floor7Tracker#inF7Boss()} (floor from DungeonState + arena bounds). */
    public static boolean inF7Boss() {
        return Floor7Tracker.inF7Boss();
    }

    private static void onChat(Component message) {
        CrushTimer.onChat(ChatObserver.strip(message));
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            CrushTimer.reset();
        }
        if (client.player == null || client.level == null) {
            return;
        }
        if (F7SpotsConfig.getInstance().isCrushTimerEnabled() || F7SpotsConfig.getInstance().isCrushTitleEnabled()) {
            CrushTimer.tick(client);
        }
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null || client.options.hideGui || !SkyblockGate.allows()) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(HUD);
        float scale = HudElementRegistry.resolveScale(HUD);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            HUD.render(graphics, 0, 0);
        } catch (RuntimeException ignored) {
            // A broken HUD element must never take down the HUD frame.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** "Crush: +4.2s (1)" / "Crush: 2.5s (1)" / "Crush: READY (2)". Movable/scalable in the HUD editor. */
    private static final class CrushHud implements HudElement {

        @Override
        public String id() {
            return "f7spots_crush";
        }

        @Override
        public String displayName() {
            return "Crush Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Kept clear of the Score Calculator (160), Thorn (140), Pathfinding (180) and Ability Timers (200).
            return 300;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 11;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            String line;
            if (client.screen instanceof HudEditorScreen) {
                line = "§6Crush: §e+4.2s §8(1)";
            } else {
                if (!F7SpotsConfig.getInstance().isCrushTimerEnabled()) {
                    return;
                }
                line = CrushTimer.hudText();
                if (line == null) {
                    return;
                }
            }
            graphics.text(client.font, line, x, y, 0xFFFFFFFF, true);
        }
    }
}
