package com.killer560.hub.leftclickew;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.leftclickew.mixin.LeftClickUseInvoker;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Left Click Etherwarp (cheat build only, off by default; OdinClient's Etherwarp Helper and Nebulune's left click
 * etherwarp, reference-mods-1018 item 3). With an etherwarp-capable item in hand (ethermerge AOTV/AOTE, Etherwarp
 * Conduit: {@link ItemIdentity#isEtherwarpItem}) a left click is an etherwarp: vanilla's attack never runs (no swing, no
 * attack, no dig), and the click is turned into a vanilla RIGHT click ({@code Minecraft.startUseItem}, so exactly the
 * packets a right click sends - the block half first when the crosshair is on a block, then the use) once the server has
 * him sneaking.
 *
 * <p><b>Sneak timing - the minimum the server needs, no random delay.</b> Odin and Nebulune hold the sneak a random 2-4
 * ticks first; killer560's rule is to match a human's packets, not to add waits. The server applies sneak from the
 * input packet ({@code ServerboundPlayerInputPacket}), which goes out in the player's tick, AFTER {@code handleKeybinds}
 * where the click is read. So:
 * <ul>
 *   <li>the server already has the sneak ({@code getLastSentInput().shift()}, he is holding sneak): the right click goes
 *       on the click's own tick;</li>
 *   <li>otherwise sneak goes down in this tick's input ({@code mixin/LeftClickEtherwarpInputMixin}), and the right click
 *       goes at the head of the next tick's {@code handleKeybinds}, before that tick's input packet lets the sneak up
 *       again - one tick, the shortest a sneak-then-click can be. Auto Routes' etherwarp makes the same check
 *       ("QUOI ClearExecutor's rule").</li>
 * </ul>
 * Sneak is let up after the use unless he holds it himself (only the forced bit is dropped; his key decides). Nothing
 * writes position, velocity or rotation; the look is his own. Zero Ping Etherwarp sees the use like any other.
 *
 * <p>Stands down (vanilla's left click) while Auto Routes or the Interactive Map is moving him, since those own the
 * sneak and the use.
 */
public final class LeftClickEtherwarp {

    private static final Logger LOGGER = ModLog.get("killer560smod-leftclickew");

    /** A sneak that has not reached the server after this many ticks is given up on (nothing is sent). */
    static final int SNEAK_TIMEOUT_TICKS = 5;

    /** Ticks since the sneak was asked for; -1 when no click waits. */
    private static int pendingTicks = -1;
    private static long clientTick;
    private static long clickTick;

    // counters, for the testkit and the log
    private static long clicks;
    private static long warps;
    private static long sameTick;
    private static long nextTick;
    private static long dropped;
    private static long lastWaitTicks = -1;

    private LeftClickEtherwarp() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            clientTick++;
            if (pendingTicks >= 0 && (McCompat.screen(client) != null || client.player == null)) {
                drop(client.player == null ? "no player" : "a screen opened before the sneak reached the server");
            }
        });
    }

    private static boolean on() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && LeftClickEtherwarpConfig.getInstance().isEnabled();
    }

    /** True when a left click with this hand should be an etherwarp. */
    private static boolean applies(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!on() || player == null || mc.gameMode == null || player.isSpectator()) {
            return false;
        }
        if (!ItemIdentity.isEtherwarpItem(player.getMainHandItem())) {
            return false;
        }
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return false;
        }
        return !com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                && !com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy();
    }

    /**
     * {@code mixin/LeftClickEtherwarpMinecraftMixin}, HEAD of {@code Minecraft.startAttack} (inside handleKeybinds).
     * True: the click is an etherwarp and vanilla's attack does not run.
     */
    public static boolean onLeftClick(Minecraft mc) {
        if (!applies(mc)) {
            return false;
        }
        clicks++;
        LocalPlayer player = mc.player;
        if (player.getLastSentInput().shift()) {
            sameTick++;
            fire(mc, 0, "the server already has the sneak");
            return true;
        }
        if (pendingTicks >= 0) {
            LOGGER.info("[LeftClickEw] click at tick {} while the last one waits for its sneak - one warp per sneak",
                    clientTick);
            return true;
        }
        pendingTicks = 0;
        clickTick = clientTick;
        LOGGER.info("[LeftClickEw] click at tick {} with {}: sneak goes down in this tick's input, the use next tick",
                clientTick, ItemIdentity.skyblockId(player.getMainHandItem()));
        return true;
    }

    /** {@code mixin/LeftClickEtherwarpMinecraftMixin}, HEAD of {@code continueAttack}: no dig or swing from a held click. */
    public static boolean suppressHeldAttack(Minecraft mc, boolean leftDown) {
        return leftDown && applies(mc);
    }

    /**
     * {@code mixin/LeftClickEtherwarpMinecraftMixin}, HEAD of {@code handleKeybinds}: inside the tick, before this
     * tick's input and movement packets and before vanilla's own clicks are read.
     */
    public static void beforeKeybinds(Minecraft mc) {
        if (pendingTicks < 0) {
            return;
        }
        LocalPlayer player = mc.player;
        if (!on() || player == null || mc.gameMode == null) {
            drop("switched off");
            return;
        }
        if (!ItemIdentity.isEtherwarpItem(player.getMainHandItem())) {
            drop("the etherwarp item left his hand");
            return;
        }
        pendingTicks++;
        if (player.getLastSentInput().shift()) {
            nextTick++;
            int waited = pendingTicks;
            pendingTicks = -1;
            fire(mc, waited, "the sneak reached the server in the last input packet");
            return;
        }
        if (pendingTicks > SNEAK_TIMEOUT_TICKS) {
            drop("the sneak never reached the server");
        }
    }

    /** {@code mixin/LeftClickEtherwarpInputMixin}: sneak held in this tick's input while a click waits for it. */
    public static boolean forceSneak() {
        return pendingTicks >= 0 && on();
    }

    private static void fire(Minecraft mc, int waited, String why) {
        LocalPlayer player = mc.player;
        warps++;
        lastWaitTicks = waited;
        LOGGER.info(String.format(Locale.ROOT, "[LeftClickEw] etherwarp #%d at tick %d (%d tick(s) after the click; %s): "
                        + "look %.2f / %.2f, from %.2f %.2f %.2f, %s held", warps, clientTick, waited, why,
                Mth.wrapDegrees(player.getYRot()), player.getXRot(), player.getX(), player.getY(), player.getZ(),
                ItemIdentity.skyblockId(player.getMainHandItem())));
        com.killer560.hub.zpew.ZpewSource zpew = com.killer560.hub.zpew.ZeroPingEtherwarp.sending(com.killer560.hub.zpew.ZpewSource.LEFT_CLICK);
        try {
            ((LeftClickUseInvoker) mc).killer560smod$startUseItem();
        } finally {
            com.killer560.hub.zpew.ZeroPingEtherwarp.done(zpew);
        }
    }

    private static void drop(String why) {
        if (pendingTicks < 0) {
            return;
        }
        dropped++;
        LOGGER.info("[LeftClickEw] click of tick {} dropped after {} tick(s): {} - nothing sent", clickTick, pendingTicks,
                why);
        pendingTicks = -1;
    }

    // ---- read by the testkit (reflection) --------------------------------------------------------------------

    public static long clicks() {
        return clicks;
    }

    public static long warps() {
        return warps;
    }

    public static long sameTick() {
        return sameTick;
    }

    public static long nextTick() {
        return nextTick;
    }

    public static long dropped() {
        return dropped;
    }

    public static long lastWaitTicks() {
        return lastWaitTicks;
    }

    public static boolean pending() {
        return pendingTicks >= 0;
    }
}
