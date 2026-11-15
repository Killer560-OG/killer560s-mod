package com.killer560.hub.carnival;

import com.killer560.hub.islands.Islands;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.HotbarSelect;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.ViewAim;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The one way the Carnival's cheat features touch the game (cheat build only): a tap of your own Use or Attack key
 * through {@link ActionGate} ({@code CARNIVAL_WORLD}), a hotbar change through {@link HotbarSelect}
 * ({@code CARNIVAL_HOTBAR}), and turning the view only through {@link ViewAim} (Auto Routes' eased rotation, yaw a
 * delta on the running value, pitch within -90..+90), optionally with the screen held still.
 * Discrete input only: nothing here writes position or velocity. A server correction is reported and the feature
 * carries on.
 */
final class CarnivalInput {

    private static int packets = -1;
    private static int taps;

    private CarnivalInput() {
    }

    static void register() {
        ViewAim.register();
    }

    static float error(LocalPlayer player, Vec3 point) {
        return ViewAim.error(player, point);
    }

    static void aim(String owner, LocalPlayer player, Vec3 point, boolean moving, boolean freeCamera) {
        ViewAim.aim(owner, player, point, moving, freeCamera);
    }

    static void release(String owner) {
        ViewAim.release(owner);
    }

    static boolean userTookOver(String owner, LocalPlayer player) {
        return ViewAim.userTookOver(owner, player);
    }

    private static final java.util.Set<String> STOPPED = new java.util.HashSet<>();

    /** Whether {@code name} was stopped (Escape or the Emergency Cancel Key) for the rest of this game. */
    static boolean stopped(String name) {
        return STOPPED.contains(name);
    }

    /**
     * Registers {@code name} with MacroStop as a click macro: only Escape or the Emergency Cancel Key stop it, for the
     * rest of the game (one chat line); a new game re-arms it. Moving the mouse never stops it.
     */
    static void stopRule(String name, java.util.function.BooleanSupplier acting) {
        com.killer560.hub.macrostop.MacroStop.register(name, com.killer560.hub.macrostop.MacroStop.Kind.CLICK,
                () -> acting.getAsBoolean() && !STOPPED.contains(name), why -> {
                    STOPPED.add(name);
                    say(name, "Stopped for this game: you pressed " + why + ".");
                });
        CarnivalState.onGameStart(g -> STOPPED.remove(name));
    }

    /**
     * One tap of {@code key} through the gate; false when the gate held it (try again next tick). It waits a tick while
     * his own press of the same key is queued (one press a tick), and an attack waits while his right click goes this
     * tick (GrimAC PacketOrderJ: a use after an attack in one tick).
     */
    static boolean tap(KeyMapping key) {
        Minecraft mc = Minecraft.getInstance();
        if (ActionGate.hisClickQueued(key) || (key == mc.options.keyAttack && ActionGate.hisUseGoesThisTick(mc))
                || !ActionGate.tryAct(ActionGate.Actor.CARNIVAL_WORLD)) {
            return false;
        }
        Islands.tap(key);
        taps++;
        return true;
    }

    /** A dig the caller holds Attack for counts as one tap (harness counter). */
    static void countTap() {
        taps++;
    }

    /** Selects hotbar {@code slot} through the gate, as a number key would. */
    static boolean select(Minecraft mc, LocalPlayer player, int slot) {
        // GrimAC PacketOrderE: no set_carried_item after a use / click / attack in the same server tick.
        if (HotbarSelect.swapWouldFlag() || !ActionGate.tryAct(ActionGate.Actor.CARNIVAL_HOTBAR)) {
            return false;
        }
        return HotbarSelect.select(mc, player, slot);
    }

    /** Reports a server correction once (with {@code feature}'s name) and lets it carry on. */
    static void watchCorrections(String feature) {
        int now = ServerCorrections.packetCount();
        if (packets >= 0 && now != packets) {
            ServerCorrections.report(feature, "- carrying on", ServerCorrections.lastMoveDistance());
        }
        packets = now;
    }

    static void say(String feature, String text) {
        ModChat.send(feature, ModChat.text(text));
    }

    /** Harness: taps sent by every Carnival auto. */
    public static int taps() {
        return taps;
    }
}
