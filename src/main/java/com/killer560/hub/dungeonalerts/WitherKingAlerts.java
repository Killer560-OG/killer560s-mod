package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.util.WorldRenderUtils;
import com.killer560.hub.witherdragons.P5State;
import com.killer560.hub.witherdragons.WitherDragon;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wither King Statue Box and Dimensional Slash Alert (Skytils MasterMode7Features; featB-1108), both M7 phase 5 only.
 * <ul>
 * <li>Statue Box: a wire box (27 wide, from 8 below to 15.5 above the dragon's spawn point) in the dragon's color around each
 * statue still standing. A statue is gone once its block (the one Wither Dragons already watches, {@code statuePos}) went
 * from solid to air; seen every 5 ticks, drawn from five cached boxes.
 * <li>Dimensional Slash: glowstone the server places (not over packed ice) marks a slash zone, a 10-block cube around it;
 * standing in one shows "Dimensional Slash!" (at most every 15 ticks). Any glowstone leaving clears the zones. Zones are
 * read off block packets only while the switch is on, capped at 64.
 * </ul>
 */
final class WitherKingAlerts {

    static final String KEY = "slash";
    private static final int ZONE_CAP = 64;
    private static final WitherDragon[] DRAGONS = WitherDragon.values();
    private static final AABB[] BOXES = new AABB[DRAGONS.length];
    private static final float[][] RGBA = new float[DRAGONS.length][];
    private static final boolean[] seenSolid = new boolean[DRAGONS.length];
    private static final boolean[] destroyed = new boolean[DRAGONS.length];
    private static final List<BlockPos> zones = new ArrayList<>();
    private static boolean active = false;
    private static int poll = 0;
    private static long lastSlash = -100L;
    private static int drawnFrames = 0;

    static {
        for (int i = 0; i < DRAGONS.length; i++) {
            BlockPos p = DRAGONS[i].spawnPos;
            BOXES[i] = new AABB(p.getX() - 13.5, p.getY() - 8, p.getZ() - 13.5, p.getX() + 13.5, p.getY() + 15.5, p.getZ() + 13.5);
            int argb = DRAGONS[i].argb;
            RGBA[i] = new float[]{(argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, 1f};
        }
    }

    private WitherKingAlerts() {
    }

    static void tick(Minecraft client) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!(cfg.witherKingStatueBox || cfg.dimensionalSlashAlert) || client.level == null || client.player == null
                || !SkyblockGate.allows()) {
            active = false;
            return;
        }
        if (++poll < 5) {
            return;
        }
        poll = 0;
        active = DungeonState.isF7OrM7() && P5State.inP5();
        if (!active) {
            return;
        }
        if (cfg.witherKingStatueBox) {
            for (int i = 0; i < DRAGONS.length; i++) {
                BlockPos s = DRAGONS[i].statuePos;
                if (!client.level.isLoaded(s)) {
                    continue;
                }
                if (!client.level.getBlockState(s).isAir()) {
                    seenSolid[i] = true;
                } else if (seenSolid[i]) {
                    destroyed[i] = true;
                }
            }
        }
        long now = client.level.getGameTime();
        if (cfg.dimensionalSlashAlert && !zones.isEmpty() && now - lastSlash >= 15 && inZone(client.player.blockPosition())) {
            lastSlash = now;
            DungeonAlertsFeature.alert(KEY, "§cDimensional Slash!", "");
        }
    }

    private static boolean inZone(BlockPos p) {
        for (BlockPos z : zones) {
            if (Math.abs(z.getX() - p.getX()) <= 5 && Math.abs(z.getY() - p.getY()) <= 5 && Math.abs(z.getZ() - p.getZ()) <= 5) {
                return true;
            }
        }
        return false;
    }

    /** A block packet's new state, seen before the level applies it (the old state is still in the level). */
    static void onBlockChange(BlockPos pos, BlockState state) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (!state.is(Blocks.GLOWSTONE)) {
            if (!zones.isEmpty() && level.getBlockState(pos).is(Blocks.GLOWSTONE)) {
                zones.clear();
            }
        } else if (active && zones.size() < ZONE_CAP && !level.getBlockState(pos).is(Blocks.PACKED_ICE)) {
            zones.add(pos.immutable());
        }
    }

    static void onWorldRender(LevelRenderContext context) {
        if (!active || !DungeonAlertsConfig.getInstance().witherKingStatueBox) {
            return;
        }
        drawnFrames++;
        for (int i = 0; i < BOXES.length; i++) {
            if (!destroyed[i]) {
                float[] c = RGBA[i];
                WorldRenderUtils.renderOutlineBox(context, BOXES[i], c[0], c[1], c[2], c[3], 3f);
            }
        }
    }

    static void onWorldChange() {
        zones.clear();
        Arrays.fill(seenSolid, false);
        Arrays.fill(destroyed, false);
        active = false;
    }

    /** For the testkit: slash zones held, whether the P5 gate is open, and how many statue boxes would draw. */
    static int drawnFrames() {
        return drawnFrames;
    }

    static int zoneCount() {
        return zones.size();
    }

    static boolean boxActive() {
        return active;
    }

    static int boxesDrawable() {
        int n = 0;
        for (boolean d : destroyed) {
            n += d ? 0 : 1;
        }
        return n;
    }
}
