package com.killer560.hub.witherdragons;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Shared M7 Phase 5 state for the Wither Dragons / King Relics features.
 * <ul>
 * <li>P5 detection = Odin {@code DungeonUtils.getF7Phase() == M7Phases.P5}: floor 7 + in boss + player y &lt;= 45
 * (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/utils/skyblock/dungeon/DungeonUtils.kt).
 * "In boss" reuses {@link DungeonState#isBossPhaseActive()} (Maxor's line / {@code /killer560 sim}); as a
 * fallback for a late join (Maxor's line never seen) F7/M7 + x,z &gt;= 0 also counts - the dungeon room grid is
 * entirely at negative x/z (origin -185,-185), the boss arena at positive. On p3sim.net the y &lt;= 45 check
 * alone is used, since its sidebar/chat format isn't confirmed.
 * </ul>
 */
public final class P5State {

    private P5State() {
    }

    public static boolean inP5() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return false;
        if (client.player.getY() > 45) return false;
        if (DungeonState.isBossPhaseActive() || isP3Sim(client)) return true;
        return DungeonState.isF7OrM7() && client.player.getX() >= 0 && client.player.getZ() >= 0;
    }

    public static boolean isP3Sim(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    /** Your class: the config override if set, else Hypixel's dungeon tab list (via {@link PartyTracker}). */
    public static DungeonClass selfClass() {
        DungeonClass override = WitherDragonsConfig.getInstance().getClassOverride().clazz;
        if (override != null) return override;
        return PartyTracker.selfClass();
    }
}
