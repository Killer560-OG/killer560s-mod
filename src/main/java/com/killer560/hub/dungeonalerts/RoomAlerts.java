package com.killer560.hub.dungeonalerts;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeoninfo.DungeonInfoFeature;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

import java.util.HashSet;
import java.util.Set;

/**
 * Room Alerts - reworked 2026-09-27 (killer560: "Make room alerts only have cleared room alert and secrets
 * in a room done alert"), replacing the old "walk into a named/puzzle room" trigger entirely. Presentation
 * still follows NoammAddons (26.1.2 upstream) {@code features/impl/dungeon/RoomAlerts.kt}: centered HUD text
 * (default at 44.4% screen height, meant for scale 2.5), NOTE_BLOCK_PLING vol 0.25 pitch 1, visible for
 * "Display Time" seconds (0.5-3.0, default 2.0) - Noamm's own two triggers ("Cleared" / "§aSecrets Done!")
 * are exactly the two this now alerts on:
 * <ul>
 * <li><b>Room Cleared</b> - only the room he is standing in (killer560, 2026-10-18: "room alerts should only be for the room
 * the player is currently in"), and only when he saw it uncleared and then saw it clear, via
 * {@link LiveMapFeature#isRoomCleared(int)}. Latched per room (grid main-tile index), cleared on world change. The text
 * is just "Room Cleared!"; the colour is a setting.
 * <li><b>Secrets Done</b> - can only really track the room the player is CURRENTLY standing in: the only
 * secrets-found signal this mod has is a single tab-list run-total ({@link DungeonInfoFeature#roomSecretsFound()}),
 * with no per-room breakdown, so which room a given secret came from is only known while you're in it (the
 * same limitation the Secrets HUD's own per-room line already accepts). Fires once per room, comparing the
 * found count against {@link RoomEntry#secrets} (that room's known total from the room database).
 * </ul>
 */
final class RoomAlerts {

    /** The alerts say only what happened, never which room (killer560, 2026-10-18). */
    static final String CLEARED_TEXT = "Room Cleared!";
    static final String SECRETS_TEXT = "Secrets Done!";

    private static String alertText = "";
    private static long alertUntilMs = 0L;
    private static int alertColor = DungeonAlertsConfig.DEFAULT_ROOM_ALERT_COLOR;

    /** Room-cleared alerts already fired this run, keyed by identified-room main-tile index. */
    private static final Set<Integer> clearedAlerted = new HashSet<>();
    /** Rooms seen NOT cleared while he stood in them: only those can alert when they clear. */
    private static final Set<Integer> armedRooms = new HashSet<>();
    /** Secrets-done alerts already fired this run, keyed by {@link RoomEntry} identity (LiveMap keeps one
     *  instance per grid slot, so this is a stable per-room key without needing an index). */
    private static final Set<RoomEntry> secretsAlerted = new HashSet<>();

    private RoomAlerts() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("RoomAlerts", client -> tick()));
    }

    static void onWorldChange() {
        alertUntilMs = 0L;
        clearedAlerted.clear();
        armedRooms.clear();
        secretsAlerted.clear();
    }

    private static void tick() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.roomAlertsOn() || !DungeonState.isInDungeon() || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        if (cfg.roomAlertsRoomCleared) {
            tickRoomCleared(cfg);
        }
        if (cfg.roomAlertsSecretsDone) {
            tickSecretsDone(cfg);
        }
    }

    private static void tickRoomCleared(DungeonAlertsConfig cfg) {
        // killer560, 2026-10-18: "room alerts should only be for the room the player is currently in" - so no room name,
        // only "Room Cleared!", and only for the room he is standing in when it clears.
        int here = LiveMapFeature.currentRoomIndex();
        if (here < 0) {
            return;
        }
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            int idx = room[0];
            if (!isInRoom(here, idx, LiveMapFeature.roomCellIndices(idx))) {
                continue;
            }
            if (clearedAlertDue(idx, LiveMapFeature.isRoomCleared(idx), armedRooms, clearedAlerted)) {
                fireAlert(cfg, CLEARED_TEXT, cfg.roomAlertsClearedColor);
            }
            return;
        }
    }

    /** Whether the cell he stands in belongs to the room whose main tile is {@code mainIdx} (a multi-tile room has several). */
    static boolean isInRoom(int here, int mainIdx, int[] cells) {
        if (here == mainIdx) {
            return true;
        }
        for (int c : cells) {
            if (c == here) {
                return true;
            }
        }
        return false;
    }

    /**
     * One step of the Room Cleared decision for the room he is standing in. A room alerts once, and only if he saw it NOT
     * cleared while inside and then saw it clear: walking into a room that is already cleared (a teammate did it) is
     * silent. {@code armed} holds the rooms seen uncleared; {@code alerted} the rooms already settled.
     */
    static boolean clearedAlertDue(int roomIdx, boolean clearedNow, Set<Integer> armed, Set<Integer> alerted) {
        if (alerted.contains(roomIdx)) {
            return false;
        }
        if (!clearedNow) {
            armed.add(roomIdx);
            return false;
        }
        alerted.add(roomIdx);
        return armed.contains(roomIdx);
    }

    private static void tickSecretsDone(DungeonAlertsConfig cfg) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        if (entry == null || entry.secrets <= 0 || secretsAlerted.contains(entry)) {
            return;
        }
        int found = DungeonInfoFeature.roomSecretsFound();
        if (found < entry.secrets) {
            return;
        }
        secretsAlerted.add(entry);
        fireAlert(cfg, SECRETS_TEXT, cfg.roomAlertsSecretsColor);
    }

    private static void fireAlert(DungeonAlertsConfig cfg, String text, int rgb) {
        alertText = text;
        alertColor = rgb & 0xFFFFFF;
        alertUntilMs = System.currentTimeMillis() + Math.round(cfg.roomAlertsDisplaySeconds * 1000.0);
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.25f, 1f);
    }

    /** For the testkit: the text and colour of the alert last raised (blank before any). */
    public static String lastAlertText() {
        return alertText;
    }

    public static int lastAlertColor() {
        return alertColor;
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
            // Wider than the old named-room text ("Water Board") to fit "<room> Secrets Done!"/"<room> Cleared!".
            return 160;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public boolean isEnabledInSettings() {
            return DungeonAlertsConfig.getInstance().roomAlertsOn();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            if (!example && (!DungeonAlertsConfig.getInstance().roomAlertsOn() || System.currentTimeMillis() >= alertUntilMs)) {
                return;
            }
            String text = example ? CLEARED_TEXT : alertText;
            int rgb = example ? DungeonAlertsConfig.getInstance().roomAlertsClearedColor : alertColor;
            HudSeen.markDrawn(id());
            graphics.centeredText(Minecraft.getInstance().font, text, x + width() / 2, y + 1, 0xFF000000 | rgb);
        }
    };
}
