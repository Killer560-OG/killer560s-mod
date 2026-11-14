package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.DungeonClass;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/** Party-member lookup for the Leap Menu. Hypixel doesn't expose a real client-side party API, so -
 *  same tradeoff every other dungeon QoL mod in this space makes - "the party" is approximated as
 *  every other real {@link Player} entity currently loaded in the world. Inside a Catacombs instance
 *  that's exactly your party (nobody else's player entities are ever sent to your client there); outside
 *  a dungeon it may include randoms standing nearby, which is an acceptable tradeoff for a menu whose
 *  main use case (organizing/coloring dungeon teammates) only matters inside one anyway. */
public final class LeapMenuFeature {

    private LeapMenuFeature() {
    }

    public static List<Player> currentPartyMembers() {
        Minecraft client = Minecraft.getInstance();
        List<Player> result = new ArrayList<>();
        if (client.level == null || client.player == null) {
            return result;
        }
        for (Player p : client.level.players()) {
            if (p != client.player) {
                result.add(p);
            }
        }
        return result;
    }

    /** The 4 spots for the class you are playing: teammates moved by hand (the saved spots) keep their spot, and
     *  everyone else goes where the Default Order puts their class. The custom leap menu and the Leap Order editor
     *  both lay out through here, so the editor shows exactly what the menu will. */
    public static String[] layout(List<String> available, DungeonClass playing) {
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        return layout(available, cfg.getClassOrder(playing), cfg.getDefaultOrder(), PartyTracker::classOf);
    }

    /** {@link #layout(List, DungeonClass)} with every input given (pure; the testkit calls it directly). */
    public static String[] layout(List<String> available, List<String> pinnedSlots, LeapOrderMode mode,
                                  java.util.function.Function<String, DungeonClass> classOf) {
        String[] out = new String[4];
        List<String> remaining = new ArrayList<>(available);
        for (int i = 0; i < 4 && i < pinnedSlots.size(); i++) {
            String wanted = pinnedSlots.get(i);
            if (wanted == null || wanted.isEmpty()) {
                continue;
            }
            for (int j = 0; j < remaining.size(); j++) {
                if (remaining.get(j).equalsIgnoreCase(wanted)) {
                    out[i] = remaining.remove(j);
                    break;
                }
            }
        }
        (mode == null ? LeapOrderMode.ODIN : mode).place(remaining, out, classOf);
        return out;
    }

    /** The class whose Leap Order applies right now, from the most current source (leaporder-1102): your class on the
     *  dungeon tab list; else the class Hypixel last said you selected (its class menu, a Party Finder group, a
     *  dungeon's pre-start line, kept across restarts); else a pick on your own name; else the class last opened in the
     *  editor. It used to go straight from the tab list to the editor's last class, so in the Hub, and in a dungeon
     *  before the tab list named you, it read whatever class you last edited, not the one you had selected. */
    public static DungeonClass playingClass() {
        Minecraft client = Minecraft.getInstance();
        String me = client.player == null ? null : client.player.getGameProfile().name();
        // A pick on your own name comes first (killer560 11-13): it is what you told the Leap Order you play.
        if (me != null && com.killer560.hub.dungeonclass.ClassOverrides.has(me)) {
            return PartyTracker.classOf(me);
        }
        DungeonClass fromTab = me == null ? null : PartyTracker.detectedClassOf(me);
        if (fromTab != null) {
            return fromTab;
        }
        // The sims have no class on their tab list and are not a Hypixel run: they keep the editor's class, as before.
        boolean sim = com.killer560.hub.secrets.DungeonState.isRoomSim() || com.killer560.hub.secrets.DungeonState.isSimOverrideActive();
        DungeonClass selected = sim ? null : PartyTracker.selectedClass();
        if (selected != null) {
            return selected;
        }
        DungeonClass picked = PartyTracker.selfClass();
        return picked != null ? picked : LeapMenuConfig.getInstance().getLastEditedClass();
    }
}
