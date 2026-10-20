package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * A Shadow Assassin's leap, as the CLIENT sees it: why a right click on a block goes nowhere while one leaps at him.
 * <p>
 * Hypixel shows the leap with a {@code ClientboundInitializeBorderPacket} (the red flash Shadow Assassin Alert reads,
 * QUOI's {@code ShadowAssassinAlert.kt}) whose border leaves him and the blocks round him outside it. Vanilla's
 * {@code MultiPlayerGameMode.useItemOn} returns {@code FAIL} before sending anything for a block outside the client's
 * world border (javap, 26.1.2 and 26.2: {@code WorldBorder.isWithinBounds(BlockPos)} at the top of the method), so
 * every chest, lever or skull click - his, Secret Aura's, a route node's - is dropped client-side until Hypixel sends
 * the border back. QUOI's {@code Tweaks.fixInteract} and RSM's {@code WorldBorderFix} wrap that very check for the
 * same reason; this mod does not bypass it, it waits for the border to lift (killer560, 2026-10-20: "if I am trying to
 * grab a chest when it leaps to me then it'll sit there and the auto route freezes for forever").
 * <p>
 * The border is the signal: the click can register again exactly when {@link #refuses} goes false. The assassin itself
 * (a player entity named "Shadow Assassin", Mob ESP's miniboss rule) is only named in the log lines.
 */
public final class ShadowAssassinLeap {

    /**
     * The longest a click is held for the border to lift (ms). A leap's flash lasts about the 35-tick title Shadow
     * Assassin Alert shows; past this the border is not a leap's, and the click goes as before (refused, and the
     * clicker's own give-up rules apply). A ceiling that ends a hold, never a wait anything sits out.
     */
    public static final long HOLD_MAX_MS = 5000L;

    /** How far from him a Shadow Assassin is named as the leaper. */
    private static final double NEAR = 12.0;

    private ShadowAssassinLeap() {
    }

    /** True when vanilla would refuse a right click on {@code pos} client-side: it is outside the client's world border. */
    public static boolean refuses(Minecraft client, BlockPos pos) {
        return client != null && client.level != null && pos != null && !client.level.getWorldBorder().isWithinBounds(pos);
    }

    /** "a Shadow Assassin 1.8 blocks away", or "no Shadow Assassin in sight" - for log and chat lines. */
    public static String describe(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            return "no Shadow Assassin in sight";
        }
        double best = Double.MAX_VALUE;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof Player p && p != client.player && "Shadow Assassin".equals(p.getName().getString())) {
                best = Math.min(best, p.distanceTo(client.player));
            }
        }
        return best <= NEAR ? String.format(Locale.US, "a Shadow Assassin %.1f blocks away", best)
                : "no Shadow Assassin in sight";
    }
}
