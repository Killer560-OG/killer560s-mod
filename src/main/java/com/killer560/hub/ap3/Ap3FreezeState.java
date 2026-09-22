package com.killer560.hub.ap3;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Freeze State + tick rewind, a node-placing tool for AP3 - killer560 (2026-09-21): "create a freezestate command.
 * This should freeze my character where they are. If I want to do /ap3 add ___ it should use my angle even if my
 * character has a different facing angle because it is frozen. Also I should be able to set a keybind for backwards
 * or forwards in time by 1 tick. It should have a configurable amount of ticks it remembers. Make it so on main I
 * have to type /rewind for it to go back as it will insta ban."
 * <ul>
 * <li><b>History</b>: every client tick while NOT frozen, the player's position, velocity, rotation, ground state
 *     and fall distance are recorded, newest last, up to {@link Ap3Config#getRewindTicks()}.</li>
 * <li><b>Frozen</b> ({@code /freezestate} or its key): {@code mixin/Ap3FreezeMixin} skips {@code LocalPlayer.aiStep}
 *     (no input, no gravity, no travel), so the character stays exactly where it is. The camera is free: the view
 *     yaw/pitch are separate values the mouse steers ({@code Ap3ViewYawMixin}, {@code Ap3MouseYawMixin}), and
 *     {@code /ap3 add} records THOSE angles ({@link #placementYaw}) - the frozen character keeps its own facing.</li>
 * <li><b>Stepping</b>: back / forward one recorded tick puts the character exactly in that tick's state (freezing
 *     first if needed). Unfreezing continues from the state you are on - its velocity included - and the recorded
 *     ticks after it are dropped, a new timeline.</li>
 * </ul>
 * <b>This writes the player's position directly.</b> In singleplayer or on p3sim that is the point; on Hypixel the
 * server sees you teleport, which is an instant flag (ban). So on hypixel.net the keys refuse and only the typed
 * {@code /rewind} works, every use behind a loud warning. Cheat build only (registered from {@link Ap3Keybinds}).
 */
public final class Ap3FreezeState {

    private record Snap(double x, double y, double z, double vx, double vy, double vz, float yRot, float xRot,
                        boolean onGround, double fallDistance) {
    }

    private static final List<Snap> history = new ArrayList<>();
    private static boolean frozen;
    /** While frozen: the history index the character is on. */
    private static int cursor = -1;
    private static float viewYaw;
    private static float viewPitch;

    private Ap3FreezeState() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Ap3FreezeState::tick);
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static float viewYaw() {
        return viewYaw;
    }

    public static float viewPitch() {
        return viewPitch;
    }

    /** Mouse turn while frozen: the free camera moves, the character does not. */
    public static void turnView(float dYaw, float dPitch) {
        viewYaw += dYaw;
        viewPitch = Mth.clamp(viewPitch + dPitch, -90f, 90f);
    }

    /** The yaw {@code /ap3 add} records: the free camera's while frozen, else the player's own. */
    static float placementYaw(LocalPlayer player) {
        return frozen ? viewYaw : player.getYRot();
    }

    static float placementPitch(LocalPlayer player) {
        return frozen ? viewPitch : player.getXRot();
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            history.clear();
            frozen = false;
            cursor = -1;
            return;
        }
        if (frozen && stepRan) {
            stepRan = false;
            record(player);
            cursor = history.size() - 1;
            player.setDeltaMovement(Vec3.ZERO); // the recorded snap keeps the real velocity for the next step/resume
        }
        if (frozen) {
            // aiStep is skipped, so vanilla no longer moves the hand sway: chase the free camera the way it would.
            player.yBobO = player.yBob;
            player.xBobO = player.xBob;
            player.yBob += (viewYaw - player.yBob) * 0.5f;
            player.xBob += (viewPitch - player.xBob) * 0.5f;
            return;
        }
        record(player);
    }

    private static void record(LocalPlayer player) {
        Vec3 p = player.position();
        Vec3 v = player.getDeltaMovement();
        history.add(new Snap(p.x, p.y, p.z, v.x, v.y, v.z, player.getYRot(), player.getXRot(), player.onGround(),
                player.fallDistance));
        int cap = Ap3Config.getInstance().getRewindTicks();
        if (history.size() > cap) {
            history.subList(0, history.size() - cap).clear();
        }
    }

    // ------------------------------------------------------------------------------------------- actions

    /** {@code /freezestate} and its key. */
    public static void toggle() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (frozen) {
            unfreeze(player);
        } else {
            freeze(player, true);
        }
    }

    private static void freeze(LocalPlayer player, boolean announce) {
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("Freeze State");
        }
        // The tick you froze on is the newest one you can come back to - unless this tick's end already recorded
        // exactly this state (a key press lands after it), which would make the first rewind a no-op.
        Vec3 p = player.position();
        Snap last = history.isEmpty() ? null : history.get(history.size() - 1);
        if (last == null || last.x != p.x || last.y != p.y || last.z != p.z) {
            record(player);
        }
        cursor = history.size() - 1;
        viewYaw = player.getYRot();
        viewPitch = player.getXRot();
        frozen = true;
        if (onHypixel()) {
            hypixelWarning("Freeze State");
        }
        if (announce) {
            ModChat.send("Freeze State", ModChat.good("Frozen"));
        }
    }

    private static void unfreeze(LocalPlayer player) {
        frozen = false;
        pendingForward = 0;
        stepRan = false;
        if (cursor >= 0 && cursor < history.size()) {
            Snap s = history.get(cursor);
            // Resume the timeline from here: its motion carries on, and the ticks after it no longer happened.
            player.setDeltaMovement(s.vx, s.vy, s.vz);
            history.subList(cursor + 1, history.size()).clear();
        }
        cursor = -1;
        ModChat.send("Freeze State", ModChat.text("Resumed"));
    }

    /** Forward steps still to simulate (one real game tick each - see {@link #consumeForwardStep}). */
    private static int pendingForward;

    /**
     * Back ({@code dir = -1}): the character goes to the tick before the one it is on now, from the recorded history.
     * Forward ({@code +1}): PREDICTED, not replayed (killer560, 2026-09-21: "the go forward should predict my
     * movement and put me there") - the game itself runs exactly one movement tick from the current state (its
     * velocity, the keys you are holding, gravity), then freezes again; anything recorded after this tick is dropped,
     * since it is a new timeline. No chat per step. {@code typed}: from /rewind; the keys refuse on Hypixel.
     */
    public static void step(int dir, int count, boolean typed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        boolean hypixel = onHypixel();
        if (hypixel && !typed) {
            ModChat.send("Freeze State", ModChat.bad("Rewind keys are disabled on Hypixel - it teleports you, which is an "
                    + "instant ban. If you really mean it, type /rewind."));
            return;
        }
        if (!frozen) {
            freeze(player, false);
        }
        if (hypixel) {
            hypixelWarning("/rewind");
        }
        if (dir > 0) {
            history.subList(cursor + 1, history.size()).clear();
            pendingForward += Math.max(1, count);
            return;
        }
        pendingForward = 0;
        int target = Math.max(0, cursor - Math.max(1, count));
        if (target != cursor) {
            cursor = target;
            apply(player, history.get(cursor));
        }
    }

    /**
     * From {@code Ap3FreezeMixin} at the head of a frozen {@code aiStep}: true = let this one tick run (a forward
     * step). The tick starts from the state the character is on, velocity included; {@link #tick} records the
     * result and freezes it again.
     */
    public static boolean consumeForwardStep(LocalPlayer player) {
        if (pendingForward <= 0 || cursor < 0 || cursor >= history.size()) {
            return false;
        }
        pendingForward--;
        Snap s = history.get(cursor);
        player.setDeltaMovement(s.vx, s.vy, s.vz);
        stepRan = true;
        return true;
    }

    private static boolean stepRan;

    /** Puts the character exactly in a recorded tick's state (frozen: no velocity until resumed). */
    private static void apply(LocalPlayer player, Snap s) {
        player.setPos(s.x, s.y, s.z);
        player.setDeltaMovement(Vec3.ZERO);
        // The recorded running values themselves, reached by adding the difference to the live ones - never wrapped.
        player.setYRot(player.getYRot() + (s.yRot - player.getYRot()));
        player.setXRot(s.xRot);
        player.yHeadRot = s.yRot;
        player.yBodyRot = s.yRot;
        player.setOnGround(s.onGround);
        player.fallDistance = s.fallDistance;
        player.setOldPosAndRot();
    }

    // ------------------------------------------------------------------------------------------- Hypixel guard

    static boolean onHypixel() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    private static void hypixelWarning(String what) {
        String bar = "§4§l!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!";
        raw(bar);
        raw("§c§l  WARNING: " + what + " ON HYPIXEL WILL GET YOU BANNED");
        raw("§c  It moves your character without movement the server can accept.");
        raw("§c  Hypixel sees a teleport / a hovering player and flags it instantly.");
        raw("§c  Use it in singleplayer or on p3sim only.");
        raw(bar);
    }

    private static void raw(String s) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(s));
        }
    }
}
