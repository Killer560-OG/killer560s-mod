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

    /** Places teammates into the 4 leap menu spots: saved names keep their spot if that player is present, then
     *  everyone else fills the empty spots in the order given. Entries are null for spots nobody fills. */
    public static String[] arrange(List<String> available, List<String> savedSlots) {
        String[] out = new String[4];
        List<String> remaining = new ArrayList<>(available);
        for (int i = 0; i < 4 && i < savedSlots.size(); i++) {
            String wanted = savedSlots.get(i);
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
        for (int i = 0; i < 4 && !remaining.isEmpty(); i++) {
            if (out[i] == null) {
                out[i] = remaining.remove(0);
            }
        }
        return out;
    }

    /** The class whose Leap Order applies right now: your class from the dungeon tab list, else the one last
     *  picked in the editor. */
    public static DungeonClass playingClass() {
        DungeonClass fromTab = PartyTracker.selfClass();
        return fromTab != null ? fromTab : LeapMenuConfig.getInstance().getLastEditedClass();
    }
}
