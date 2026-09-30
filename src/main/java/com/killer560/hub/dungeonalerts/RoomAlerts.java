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
 * <li><b>Room Cleared</b> - watches EVERY identified room on the map grid (not just the one you're standing
 * in) via {@link LiveMapFeature#isRoomCleared(int)}, so a teammate clearing a room elsewhere still alerts
 * you. Latched per room (grid main-tile index) so it only ever fires once, and cleared on world change.
 * <li><b>Secrets Done</b> - can only really track the room the player is CURRENTLY standing in: the only
 * secrets-found signal this mod has is a single tab-list run-total ({@link DungeonInfoFeature#roomSecretsFound()}),
 * with no per-room breakdown, so which room a given secret came from is only known while you're in it (the
 * same limitation the Secrets HUD's own per-room line already accepts). Fires once per room, comparing the
 * found count against {@link RoomEntry#secrets} (that room's known total from the room database).
 * </ul>
 */
final class RoomAlerts {

    private static String alertText = "";
    private static long alertUntilMs = 0L;

    /** Room-cleared alerts already fired this run, keyed by identified-room main-tile index. */
    private static final Set<Integer> clearedAlerted = new HashSet<>();
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
        secretsAlerted.clear();
    }

    private static void tick() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.roomAlertsEnabled || !DungeonState.isInDungeon() || !com.killer560.hub.util.SkyblockGate.allows()) {
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
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            int idx = room[0];
            if (clearedAlerted.contains(idx) || !LiveMapFeature.isRoomCleared(idx)) {
                continue;
            }
            clearedAlerted.add(idx);
            RoomEntry entry = LiveMapFeature.roomEntryAt(idx);
            String name = entry != null && entry.name != null ? entry.name : "Room";
            fireAlert(cfg, name + " Cleared!");
        }
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
        String name = entry.name != null ? entry.name : "Room";
        fireAlert(cfg, name + " Secrets Done!");
    }

    private static void fireAlert(DungeonAlertsConfig cfg, String text) {
        if (cfg.roomAlertsTitle) {
            alertText = text;
            alertUntilMs = System.currentTimeMillis() + Math.round(cfg.roomAlertsDisplaySeconds * 1000.0);
        }
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.25f, 1f);
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
            return DungeonAlertsConfig.getInstance().roomAlertsEnabled;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            if (!example && (!DungeonAlertsConfig.getInstance().roomAlertsEnabled || System.currentTimeMillis() >= alertUntilMs)) {
                return;
            }
            String text = example ? "Water Board Cleared!" : alertText;
            HudSeen.markDrawn(id());
            graphics.centeredText(Minecraft.getInstance().font, text, x + width() / 2, y + 1, 0xFF000000 | ModChat.LIGHT_ORANGE);
        }
    };
}
