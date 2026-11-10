package com.killer560.hub.bosssim;

import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * P3's twenty-nine stations - seventeen terminals, eight levers and four devices, 7/8/7/7 per section - as
 * Hypixel shows them: a terminal is an invisible stand named "Inactive Terminal" over one named "CLICK HERE", a lever has
 * one marker stand "Not Activated" over it, a device "Inactive" over "Device". Done, they read "Terminal Active",
 * "Activated", "Device"/"Active". Positions are Hypixel's (the same ones the mod's Terminal ESP, Lever Aura and Secret
 * Aura read; ADM's recordings agree to the block).
 *
 * <p>Completion lines are Hypixel's: {@code "<name> activated a terminal! (n/m)"}, {@code "... activated a lever! ..."},
 * {@code "... completed a device! ..."}, each also sent as a subtitle (what Terminal Titles and the QOL mixin read).
 */
public final class BossStations {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    public enum Kind { TERMINAL, LEVER, DEVICE }

    /** One station. {@code at} is its (top) stand's position; {@code lever} the lever block for a lever. */
    public record Station(Kind kind, int section, Vec3 at, String label, BlockPos lever) {
    }

    /**
     * Each gate as the box of blocks that closes the way from section n to n+1 (n = index + 1), y from the ring's floor up
     * to Hypixel's gate top (134). x/z from the mod's Gate Highlight boxes, widened to close the whole corridor.
     */
    static final int[][] GATES = {
            {89, BossArena.P3_FLOOR + 1, 122, 113, 134, 124},
            {18, BossArena.P3_FLOOR + 1, 121, 19, 134, 145},
            {-6, BossArena.P3_FLOOR + 1, 50, 18, 134, 51},
    };

    private static final List<Station> ALL = List.of(
            t(1, 1, 110.5, 112.0, 73.5), t(1, 2, 110.5, 118.0, 79.5), t(1, 3, 90.5, 111.0, 92.5), t(1, 4, 90.5, 121.0, 101.5),
            l(1, "east lever", 106, 124, 113), l(1, "west lever", 94, 124, 113),
            d(1, "SS", 110.5, 119.0, 91.5),
            t(2, 1, 68.5, 108.0, 122.5), t(2, 2, 59.5, 119.0, 123.5), t(2, 3, 47.5, 108.0, 122.5), t(2, 4, 39.5, 107.0, 142.5),
            t(2, 5, 40.5, 123.0, 123.5),
            l(2, "low lever", 27, 124, 127), l(2, "high lever", 23, 132, 138),
            d(2, "Lights", 60.5, 131.0, 142.5),
            t(3, 1, -1.5, 108.0, 112.5), t(3, 2, -1.5, 118.0, 93.5), t(3, 3, 18.5, 122.0, 93.5), t(3, 4, -1.5, 108.0, 77.5),
            l(3, "west lever", 2, 122, 55), l(3, "east lever", 14, 122, 55),
            d(3, "Arrows", -1.5, 119.0, 74.5),
            t(4, 1, 41.5, 108.0, 30.5), t(4, 2, 44.5, 120.0, 30.5), t(4, 3, 67.5, 108.0, 30.5), t(4, 4, 72.5, 114.0, 47.5),
            l(4, "low lever", 84, 121, 34), l(4, "high lever", 86, 128, 46),
            d(4, "Target", 63.5, 126.0, 34.5));

    private static Station t(int section, int n, double x, double y, double z) {
        return new Station(Kind.TERMINAL, section, new Vec3(x, y, z), "T" + n, null);
    }

    private static Station l(int section, String label, int x, int y, int z) {
        return new Station(Kind.LEVER, section, new Vec3(x + 0.5, y + 0.688, z + 0.5), label, new BlockPos(x, y, z));
    }

    private static Station d(int section, String label, double x, double y, double z) {
        return new Station(Kind.DEVICE, section, new Vec3(x, y, z), label, null);
    }

    public static List<Station> all() {
        return ALL;
    }

    /** A section's count: terminals + levers + device (7; S2 has five terminals, 8). */
    public static int total(int section) {
        return section == 2 ? 8 : 7;
    }

    // ---- per-fight state -----------------------------------------------------------------------------------------

    /** Hypixel's stand heights relative to the station (after the first position sync), from ADM's recordings. */
    private static final double TOP_DY = 0.02, BOTTOM_DY = -0.355;
    static final String INACTIVE = "§cInactive Terminal";
    static final String CLICK_HERE = "§e§lCLICK HERE";

    private static final boolean[] done = new boolean[ALL.size()];
    private static final ArmorStand[] tops = new ArmorStand[ALL.size()];
    private static final ArmorStand[] bottoms = new ArmorStand[ALL.size()];

    static boolean isDone(Station s) {
        return done[ALL.indexOf(s)];
    }

    static int count(int section) {
        int n = 0;
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).section() == section && done[i]) {
                n++;
            }
        }
        return n;
    }

    /** Clears every stand and every done flag. */
    static void reset() {
        for (int i = 0; i < ALL.size(); i++) {
            done[i] = false;
            if (tops[i] != null) {
                tops[i].discard();
            }
            if (bottoms[i] != null) {
                bottoms[i].discard();
            }
            tops[i] = null;
            bottoms[i] = null;
        }
    }

    /** Spawns every station's stands (P3 begins). Stations of sections before {@code fromSection} start done. */
    static void spawn(ServerLevel level, int fromSection) {
        for (int i = 0; i < ALL.size(); i++) {
            Station s = ALL.get(i);
            done[i] = s.section() < fromSection;
            switch (s.kind()) {
                case TERMINAL -> {
                    tops[i] = stand(level, s.at().x, s.at().y + TOP_DY, s.at().z, INACTIVE, false);
                    bottoms[i] = stand(level, s.at().x, s.at().y + BOTTOM_DY, s.at().z, CLICK_HERE, false);
                }
                case DEVICE -> {
                    tops[i] = stand(level, s.at().x, s.at().y, s.at().z, "§cInactive", false);
                    bottoms[i] = stand(level, s.at().x, s.at().y - 0.375, s.at().z, "§cDevice", false);
                }
                case LEVER -> tops[i] = stand(level, s.at().x, s.at().y, s.at().z, "§cNot Activated", true);
            }
            if (done[i] && s.kind() == Kind.LEVER) {
                BossBlocks.set(level, s.lever(), BossBlocks.of("minecraft:lever[face=floor,facing=north,powered=true]"));
            }
            refresh(i);
        }
    }

    private static ArmorStand stand(ServerLevel level, double x, double y, double z, String name, boolean marker) {
        ArmorStand s = new BossFight.TransientStand(level, x, y, z);
        s.snapTo(x, y, z, 0f, 0f);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setSilent(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(s, true);
        if (marker) {
            // Hypixel's lever stands carry armor-stand flags 18 (marker 16 + bit 2); terminal and device stands none.
            s.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) 18);
        }
        s.setCustomName(BossChat.legacy(name));
        s.setCustomNameVisible(true);
        level.addFreshEntity(s);
        return s;
    }

    private static void name(ArmorStand s, String n) {
        if (s == null) {
            return;
        }
        if (n.isEmpty()) {
            s.setCustomNameVisible(false);
            s.setCustomName(null);
        } else {
            s.setCustomName(BossChat.legacy(n));
            s.setCustomNameVisible(true);
        }
    }

    private static void refresh(int i) {
        Station s = ALL.get(i);
        boolean d = done[i];
        switch (s.kind()) {
            case TERMINAL -> {
                if (tops[i] != null) {
                    double y = s.at().y + (d ? BOTTOM_DY : TOP_DY);
                    tops[i].snapTo(s.at().x, y, s.at().z, 0f, 0f);
                }
                name(tops[i], d ? "§aTerminal Active" : INACTIVE);
                name(bottoms[i], d ? "" : CLICK_HERE);
            }
            case DEVICE -> {
                name(tops[i], d ? "§aDevice" : "§cInactive");
                name(bottoms[i], d ? "§aActive" : "§cDevice");
            }
            case LEVER -> name(tops[i], d ? "§aActivated" : "§cNot Activated");
        }
    }

    /** The terminal whose stand this is, or null. */
    static Station terminalOf(net.minecraft.world.entity.Entity e) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).kind() == Kind.TERMINAL && (e == tops[i] || e == bottoms[i])) {
                return ALL.get(i);
            }
        }
        return null;
    }

    static Station leverAt(BlockPos pos) {
        for (Station s : ALL) {
            if (s.kind() == Kind.LEVER && s.lever().equals(pos)) {
                return s;
            }
        }
        return null;
    }

    static Station device(int section) {
        for (Station s : ALL) {
            if (s.kind() == Kind.DEVICE && s.section() == section) {
                return s;
            }
        }
        throw new IllegalArgumentException("no device in section " + section);
    }

    /**
     * Marks a station done by {@code by} and says so as Hypixel does. A station of a later section done early counts for
     * that section (its line carries that section's count), as on Hypixel. Returns false when it was already done.
     */
    static boolean complete(ServerLevel level, Station s, ServerPlayer by) {
        int i = ALL.indexOf(s);
        if (done[i]) {
            return false;
        }
        done[i] = true;
        refresh(i);
        if (s.kind() == Kind.LEVER) {
            BossBlocks.set(level, s.lever(), BossBlocks.of("minecraft:lever[face=floor,facing=north,powered=true]"));
        }
        String verb = switch (s.kind()) {
            case TERMINAL -> "activated a terminal!";
            case LEVER -> "activated a lever!";
            case DEVICE -> "completed a device!";
        };
        int n = count(s.section());
        int total = total(s.section());
        String name = by == null ? "Someone" : by.getGameProfile().name();
        String line = "§b" + name + "§r§a " + verb + " (§c" + n + "§r§a/" + total + ")";
        BossChat.chat(level.getServer(), line);
        // Hypixel shows each completion as a subtitle too: an empty title, the line without its resets, 0/40/0.
        BossChat.title(level.getServer(), "", line.replace("§r", ""), 0, 40, 0);
        LOGGER.info("[BossSim] S{} {} {} by {} ({}/{})", s.section(), s.kind(), s.label(), name, n, total);
        BossFight.onStationDone(level, s);
        return true;
    }

    /** Debug and testkit: every station's id and whether it is done, "S1 T1=false, ...". */
    public static String describe() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) {
            parts.add("S" + ALL.get(i).section() + " " + ALL.get(i).label() + "=" + done[i]);
        }
        return String.join(", ", parts);
    }

    /** Debug and testkit: completes a station as its player would (the same path), server thread. */
    public static boolean testComplete(net.minecraft.server.MinecraftServer server, int section, String label) {
        return testComplete(server, section, label, null);
    }

    /** Testkit (coopsim-1109): as {@link #testComplete}, credited to player {@code by} (null: the first player). */
    public static boolean testComplete(net.minecraft.server.MinecraftServer server, int section, String label,
                                       java.util.UUID by) {
        for (Station s : ALL) {
            if (s.section() == section && s.label().equals(label)) {
                ServerPlayer p = by != null ? server.getPlayerList().getPlayer(by)
                        : server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
                return complete(server.overworld(), s, p);
            }
        }
        return false;
    }

    /** Debug and testkit: the station's position (top stand for a terminal, the lever block's center for a lever). */
    public static double[] position(int section, String label) {
        for (Station s : ALL) {
            if (s.section() == section && s.label().equals(label)) {
                return new double[]{s.at().x, s.at().y, s.at().z};
            }
        }
        return null;
    }

    static Component plain(String s) {
        return Component.literal(s);
    }
}
