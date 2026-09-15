package com.killer560.hub.dungeonalerts;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;

/**
 * Room Alerts - alert when you walk into a chosen room, using {@link LiveMapFeature#currentRoomEntry()}
 * (read-only). Presentation copied from NoammAddons (26.1.2 upstream) {@code features/impl/dungeon/RoomAlerts.kt}:
 * centered HUD text (default at 44.4% screen height, meant for scale 2.5), NOTE_BLOCK_PLING vol 0.25 pitch 1,
 * visible for "Display Time" seconds (0.5-3.0, default 2.0), cleared on world change.
 * <p>
 * Trigger differs from Noamm on purpose: Noamm alerts on room STATE changes ("Cleared" / "§aSecrets Done!") from
 * its map scanner; this mod's Live Map has no room clear/secret state, so this alerts on ENTERING a room whose
 * name is in the configured comma-separated list, or (toggle) any PUZZLE-type room from the room database.
 */
final class RoomAlerts {

    private static RoomEntry lastRoom = null;
    private static String alertText = "";
    private static long alertUntilMs = 0L;

    private RoomAlerts() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    static void onWorldChange() {
        lastRoom = null;
        alertUntilMs = 0L;
    }

    private static void tick() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.roomAlertsEnabled || !DungeonState.isInDungeon()) {
            lastRoom = null;
            return;
        }
        RoomEntry room = LiveMapFeature.currentRoomEntry();
        if (room == lastRoom) {
            return;
        }
        RoomEntry previous = lastRoom;
        lastRoom = room;
        if (room == null || room.name == null || previous != null && room.name.equals(previous.name)) {
            return;
        }
        if (!matches(cfg, room)) {
            return;
        }
        DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Room alert: entered \"{}\" (type={})", room.name, room.type);
        if (cfg.roomAlertsTitle) {
            alertText = room.name;
            alertUntilMs = System.currentTimeMillis() + Math.round(cfg.roomAlertsDisplaySeconds * 1000.0);
        }
        if (cfg.roomAlertsChat) {
            ModChat.send("Room Alerts", ModChat.text("Entered "), ModChat.value(room.name));
        }
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.25f, 1f);
    }

    private static boolean matches(DungeonAlertsConfig cfg, RoomEntry room) {
        if (cfg.roomAlertsPuzzles && "PUZZLE".equalsIgnoreCase(room.type)) {
            return true;
        }
        String target = room.name.trim().toLowerCase(Locale.US);
        for (String part : cfg.roomAlertsNames.split(",")) {
            if (!part.isBlank() && part.trim().toLowerCase(Locale.US).equals(target)) {
                return true;
            }
        }
        return false;
    }

    static final HudElement HUD = new HudElement() {
        @Override
        public String id() {
            return "room_alerts";
        }

        @Override
        public String displayName() {
            return "Room Alerts";
        }

        @Override
        public int defaultX() {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            return (int) (Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.444f);
        }

        @Override
        public int width() {
            return 100;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            if (!example && (!DungeonAlertsConfig.getInstance().roomAlertsEnabled || System.currentTimeMillis() >= alertUntilMs)) {
                return;
            }
            String text = example ? "Water Board" : alertText;
            graphics.centeredText(Minecraft.getInstance().font, text, x + width() / 2, y + 1, 0xFF000000 | ModChat.LIGHT_ORANGE);
        }
    };
}
