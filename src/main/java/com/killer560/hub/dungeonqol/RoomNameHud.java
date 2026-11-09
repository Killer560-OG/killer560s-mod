package com.killer560.hub.dungeonqol;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Current Room Name HUD (Devonian, featE-1108): the name of the dungeon room you stand in, from the map's room
 *  identification (the sidebar's Room line when the map has none). Re-read twice a second, never per frame. */
public final class RoomNameHud extends BasicHud {

    public static final HudElement HUD = new RoomNameHud();
    private static final String SAMPLE = "Room: Water Board";
    private static volatile String text = "";
    private static int ticks;

    private RoomNameHud() {
        super("room_name", "Current Room Name", 10, 380);
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("RoomNameHud", client -> {
            if (++ticks % 10 != 0) {
                return;
            }
            text = "";
            if (DungeonQolConfig.getInstance().isRoomHud() && DungeonState.isInDungeon()) {
                RoomEntry entry = LiveMapFeature.currentRoomEntry();
                String name = entry != null && entry.name != null ? entry.name : DungeonState.sidebarRoomName();
                text = name == null ? "" : "Room: " + name;
            }
        }));
    }

    /** What the HUD shows now (for the testkit). */
    public static String shown() {
        return text;
    }

    private static String line() {
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen ? SAMPLE : text;
    }

    @Override
    public int width() {
        return Math.max(1, Minecraft.getInstance().font.width(line()));
    }

    @Override
    public int height() {
        return 9;
    }

    @Override
    public boolean isEnabledInSettings() {
        return DungeonQolConfig.getInstance().isRoomHud();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        String s = line();
        if (!s.isEmpty()) {
            graphics.text(Minecraft.getInstance().font, s, x, y, 0xFFFFFFFF, true);
            HudSeen.markDrawn(id());
        }
    }
}
