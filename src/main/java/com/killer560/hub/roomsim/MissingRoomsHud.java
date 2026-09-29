package com.killer560.hub.roomsim;

import com.killer560.hub.gui.profit.ProfitPanels;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Room Recorder still has not found.
 *
 * <p>killer560 (2026-09-28): "can you make a hud for the missing rooms that is a toggle that shows which rooms
 * are still needed to be found?" and "and the total number left to find".
 *
 * <p>It answers a question he otherwise has to leave the game to answer. The recorder reports what it HAS; what
 * matters while scanning is what it has not. Knowing there are four left, and which four, is the difference
 * between stopping at the right time and running the loop all night for nothing.
 *
 * <p>The list of rooms that exist comes from {@link RoomDatabase}'s real entries rather than from the library,
 * because the library only knows what it has seen and so can never say what is missing. A room counts as found
 * only when it is COMPLETE: a half-captured room is one the sim cannot build, so it is still work to do.
 *
 * <p>Registered as a normal {@link HudElement}, so it drags, scales and hides with every other overlay in the
 * mod instead of being a second thing with its own rules.
 */
public final class MissingRoomsHud implements HudElement {

    /** Enough to be useful at a glance; the count covers the rest. */
    private static final int MAX_LISTED = 12;

    private static final MissingRoomsHud INSTANCE = new MissingRoomsHud();

    private MissingRoomsHud() {
    }

    public static void register() {
        HudElementRegistry.register(INSTANCE);
    }

    /**
     * Every room the database knows that the library has not finished capturing.
     *
     * <p>Empty when the database has not loaded, and the caller is expected to know the difference - "nothing
     * missing" and "nothing to compare against" look identical otherwise, and the first is a lie.
     */
    public static List<String> missing() {
        List<String> out = new ArrayList<>();
        if (!RoomDatabase.isReady()) {
            return out;
        }
        for (RoomEntry entry : RoomDatabase.allEntries()) {
            if (entry == null || entry.name == null || entry.name.isBlank()) {
                continue;
            }
            RoomLibrary.Room have = RoomLibrary.get(entry.name);
            if (have == null || !have.complete()) {
                out.add(entry.name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    public String id() {
        return "missing-rooms";
    }

    @Override
    public String displayName() {
        return "Missing Rooms";
    }

    @Override
    public int defaultX() {
        return 6;
    }

    @Override
    public int defaultY() {
        return 80;
    }

    @Override
    public int width() {
        return 140;
    }

    @Override
    public int height() {
        return (Math.min(MAX_LISTED, Math.max(1, missing().size())) + 2) * 10;
    }

    @Override
    public boolean isRelevantNow() {
        return MissingRoomsConfig.getInstance().isEnabled();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!MissingRoomsConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
            return;
        }
        var font = Minecraft.getInstance().font;

        if (!RoomDatabase.isReady()) {
            graphics.text(font, "Missing rooms: room database not loaded", x, y, ProfitPanels.DIM, false);
            return;
        }

        List<String> missing = missing();
        graphics.text(font, "Rooms left to find: " + missing.size(), x, y, ProfitPanels.ACCENT, false);
        int lineY = y + 11;
        if (missing.isEmpty()) {
            graphics.text(font, "every known room captured", x, lineY, ProfitPanels.TEXT, false);
            return;
        }
        for (int i = 0; i < Math.min(MAX_LISTED, missing.size()); i++) {
            graphics.text(font, missing.get(i), x, lineY, ProfitPanels.TEXT, false);
            lineY += 10;
        }
        if (missing.size() > MAX_LISTED) {
            graphics.text(font, "...and " + (missing.size() - MAX_LISTED) + " more", x, lineY,
                    ProfitPanels.DIM, false);
        }
    }

    /** The whole list, for chat - the HUD is trimmed on purpose and sometimes he wants all of it. */
    public static String summary() {
        if (!RoomDatabase.isReady()) {
            return "The room database has not loaded, so nothing can be called missing yet.";
        }
        List<String> missing = missing();
        if (missing.isEmpty()) {
            return "Every room in the database has been captured.";
        }
        return missing.size() + " room(s) left: " + String.join(", ", missing);
    }
}
