package com.killer560.hub.thorn;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
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

import java.util.regex.Pattern;

/**
 * Floor 4 / Master Mode 4 (Thorn) boss helpers - entry point. Three parts, all default OFF ({@link ThornConfig}):
 * <ul>
 * <li>{@link SpiritBearTracker} - Spirit Bear kill counter / spawn timer (ported from Odin {@code SpiritBear.kt}) plus
 * this mod's own overkill counter, drawn by {@link #HUD}.
 * <li>{@link ThornEspFeature} - Spirit Bear / spirit mob / Spirit Bow highlights (Dungeon ESP's styles and legit rules).
 * <li>{@link StunSpotWaypoints} - world waypoints from the config's stun-spot list (empty until coordinates are added).
 * </ul>
 * <b>"In the Thorn fight"</b> reuses the mod's own detection: {@link DungeonState#getFloor()} is "F4"/"M4" AND either
 * {@link LiveMapFeature#isInBoss()} (NoammAddons' floor-4 boss-room bounds, latched) or a {@code [BOSS] Thorn:} chat
 * line was seen this run (same kind of boss-dialogue trigger {@code DungeonState} uses for Maxor). p3sim.net only
 * simulates F7, so none of this can trigger there (the /killer560 sim override forces "F7", never F4).
 */
public final class ThornFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-thorn");
    /** Every Thorn dialogue line starts with this (e.g. his opening "[BOSS] Thorn: Welcome Adventurers! ..."). */
    private static final Pattern THORN_BOSS_LINE = Pattern.compile("^\\[BOSS] Thorn: ");

    private static boolean thornChatSeen = false;
    private static boolean lastActive = false;
    private static Object lastLevel = null;

    public static final HudElement HUD = new SpiritBearHud();

    private ThornFeature() {
    }

    public static void register() {
        ThornConfig.getInstance();
        ThornEspRenderer.init();
        // ChatObserver (not Fabric CHAT/GAME) so a line Odin/NoammAddons/Skyblocker cancelled and re-added still arrives.
        // Anchored server-format "[BOSS] Thorn: " prefix, so this mod's own client messages can't match.
        ChatObserver.subscribe(ThornFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(ThornFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!SkyblockGate.allows()) {
                return;
            }
            ThornEspFeature.render(context);
            StunSpotWaypoints.render(context);
        });
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "thorn_" + HUD.id()),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
        LOGGER.info("[Thorn] Registered (all features default OFF)");
    }

    /** "F4" / "M4" from the sidebar, else null. */
    public static String thornFloor() {
        String floor = DungeonState.getFloor();
        return "F4".equals(floor) || "M4".equals(floor) ? floor : null;
    }

    public static boolean isMasterMode() {
        return "M4".equals(thornFloor());
    }

    /** True while in the F4/M4 boss room (see class doc). */
    public static boolean inThornBoss() {
        return DungeonState.isInDungeon() && thornFloor() != null && (thornChatSeen || LiveMapFeature.isInBoss());
    }

    private static void onChat(Component message) {
        if (thornFloor() == null || thornChatSeen) {
            return;
        }
        if (THORN_BOSS_LINE.matcher(ChatObserver.strip(message)).find()) {
            thornChatSeen = true;
            LOGGER.info("[Thorn] Thorn dialogue seen on {} - boss fight latched", thornFloor());
        }
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            thornChatSeen = false;
            SpiritBearTracker.reset("world changed");
            ThornEspFeature.reset();
        }
        if (thornFloor() == null) {
            thornChatSeen = false;
        }
        boolean active = client.level != null && client.player != null && inThornBoss();
        if (active != lastActive) {
            LOGGER.info("[Thorn] Boss fight {} (floor={} chatLatch={} liveMapInBoss={})", active ? "ACTIVE" : "INACTIVE",
                    DungeonState.getFloor(), thornChatSeen, LiveMapFeature.isInBoss());
            lastActive = active;
            if (!active) {
                SpiritBearTracker.reset("left Thorn fight");
                ThornEspFeature.reset();
            }
        }
        if (!active) {
            return;
        }
        ThornConfig cfg = ThornConfig.getInstance();
        // Only runs while something reads it (HUD / chat). Turning both off resets it; turning one back on mid-fight
        // re-reads the ring's current state on the first tick (the tracker's baseline), so kills are still right.
        if (cfg.isBearHudEnabled() || cfg.isOverkillChatEnabled()) {
            SpiritBearTracker.tick(client);
        } else {
            SpiritBearTracker.reset("counter turned off");
        }
        ThornEspFeature.tick(client);
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this (killer560), the HUD editor still does.
        if (client.player == null || HudVisibility.menuOpen() || client.options.hideGui || !SkyblockGate.allows()) {
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

    /** "Bear: 17/25" / "Bear: 3.40s" / "Bear: Alive!" + optional overkill line. Movable/scalable in the HUD editor. */
    private static final class SpiritBearHud implements HudElement {

        @Override
        public String id() {
            return "thorn_spirit_bear";
        }

        @Override
        public String displayName() {
            return "Spirit Bear Counter";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Not 120: Posmsg / Spring Boots / the Score Calculator already default there.
            return 140;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 22;
        }

        @Override
        public boolean isRelevantNow() {
            // Floor 4 only - Thorn is the F4/M4 boss.
            return ThornConfig.getInstance().isBearHudEnabled() && thornFloor() != null;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            ThornConfig cfg = ThornConfig.getInstance();
            String line1;
            String line2;
            if (client.screen instanceof HudEditorScreen) {
                line1 = "§6Bear: §d17/25";
                line2 = "§6Overkill: §e3";
            } else {
                if (!cfg.isBearHudEnabled() || !inThornBoss()) {
                    return;
                }
                line1 = "§6Bear: " + SpiritBearTracker.stateText();
                line2 = cfg.isShowOverkill() ? SpiritBearTracker.overkillText() : null;
            }
            graphics.text(client.font, line1, x, y, 0xFFFFFFFF, true);
            if (line2 != null) {
                graphics.text(client.font, line2, x, y + 11, 0xFFFFFFFF, true);
            }
        }
    }
}
