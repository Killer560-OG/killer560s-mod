package com.killer560.hub.autoroutes;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * Kill Mimic (killer560, 2026-10-05: "if it detects it just clicked a trapped chest it should hype straight down once
 * or use spirit sceptre straight down until it detects the mimic is killed").
 * <p>
 * Triggered by OUR click on a trapped chest ({@link AwaitEvents#onLocalBlockClick} - by hand or by a route node), with
 * Auto Routes on and in a dungeon. It waits for the route to be between nodes (or only waiting on an await), then
 * swaps to the item, aims straight down (pitch 90: a camera turn in legit mode, in obvious mode the body
 * with the camera held - {@link RouteExecutor#useHeldItem}), and uses it: a wither blade once, a Spirit Sceptre again every interact
 * delay until the mimic is seen dying ({@link AwaitEvents#mimicKilledSince}), five seconds at most, or until he presses
 * a movement key. Then the slot he held goes back. While it runs a route starts no node and moves nothing
 * ({@link RouteExecutor} holds on {@link #isBusy}).
 */
public final class MimicKiller {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    /** Longest it waits for a running node to finish before giving up on the mimic. */
    private static final int PENDING_TIMEOUT = 100;
    /** Spirit Sceptre: longest it keeps using for one mimic. */
    private static final int USE_TIMEOUT = 100;
    private static final int AIM_TIMEOUT = 20;
    /**
     * Legit mode: the Hyperion is only used with the camera at least this far down. Wither Impact teleports along the
     * look, so a use at any shallower pitch is a teleport - and right after the chest click the look points at the
     * mimic. Measured 2026-10-11 (testkit 96-ar-644, Secret Aura on): the AIM timeout fired it at pitch 50.9, killer560's
     * "the auto routes kill the mimic then teleport in the direction of the mimic".
     */
    static final float MIN_HYPERION_PITCH = 80f;
    /** Legit Hyperion: how long it keeps aiming down before giving up WITHOUT using it. */
    private static final int AIM_GIVE_UP = 60;

    private enum State { IDLE, PENDING, AIM, USING }

    private static State state = State.IDLE;
    private static AutoRoutesConfig.KillMimic mode;
    private static int itemSlot;
    private static int slotBefore;
    private static int armedAt;
    private static int stateTicks;
    private static int lastUseTick;
    private static int uses;
    private static boolean[] keysAtStart;

    private MimicKiller() {
    }

    public static boolean isBusy() {
        return state != State.IDLE;
    }

    /** Whether it holds the camera (aiming down or using) - Secret Aura turns nothing meanwhile. */
    public static boolean holdsCamera() {
        return state == State.AIM || state == State.USING;
    }

    /** From {@link AwaitEvents}: we just clicked a trapped chest. */
    static void onTrappedChestClicked(Minecraft client, BlockPos chest) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        AutoRoutesConfig.KillMimic m = cfg.getKillMimic();
        LocalPlayer player = client.player;
        if (m == AutoRoutesConfig.KillMimic.OFF || state != State.IDLE || player == null || !cfg.isEnabled()
                || !com.killer560.hub.secrets.DungeonState.isInDungeon()) {
            return;
        }
        int slot = ItemIdentity.findHotbarSlot(player, m.itemId());
        if (slot < 0) {
            AutoRoutesFeature.chatBad("Kill Mimic: no " + (m == AutoRoutesConfig.KillMimic.HYPERION ? "wither blade"
                    : "Spirit Sceptre") + " in the hotbar.");
            return;
        }
        mode = m;
        itemSlot = slot;
        slotBefore = player.getInventory().getSelectedSlot();
        armedAt = AwaitEvents.now();
        uses = 0;
        state = State.PENDING;
        stateTicks = 0;
        LOGGER.info("[AutoRoutes] Kill Mimic: trapped chest at {} - {} from slot {}", chest.toShortString(), m.label(),
                slot + 1);
    }

    /** Every client tick, before the route's own tick. */
    static void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            finish(client, null, "world change");
            return;
        }
        stateTicks++;
        if (AwaitEvents.mimicKilledSince(armedAt)) {
            finish(client, player, "mimic killed after " + uses + " use(s)");
            return;
        }
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        switch (state) {
            case PENDING -> {
                // Also wait out an aura's body turn (Secret Aura clicking the chest turns him to it and gives the view
                // back a tick later): that hand-back writes the rotation, and landing on top of the aim it undid it.
                if (!RouteExecutor.mayYieldToMimic() || com.killer560.hub.util.TurnFirst.anyTurnOut()) {
                    if (stateTicks > PENDING_TIMEOUT) {
                        finish(client, player, "the route's node never finished");
                    }
                    return;
                }
                RouteExecutor.select(client, player, itemSlot);
                keysAtStart = movementKeys(client);
                if (legit) {
                    RouteRotation.beginApproach(player.getYRot(), 90f, false, 0f, 0f);
                }
                state = State.AIM;
                stateTicks = 0;
            }
            case AIM -> {
                if (tookControl(client)) {
                    finish(client, player, "you moved");
                    return;
                }
                // The route lets go of sneak while this holds it; the use goes out once the server has shift up.
                boolean sneakDown = player.getLastSentInput().shift() && stateTicks <= AIM_TIMEOUT;
                boolean turning = legit && !RouteRotation.settled(1.0f) && stateTicks <= AIM_TIMEOUT;
                if (legit && mode == AutoRoutesConfig.KillMimic.HYPERION && player.getXRot() < MIN_HYPERION_PITCH) {
                    // NEVER at a bad pitch: keep aiming (re-issued if something else let go of or moved the approach),
                    // and past AIM_GIVE_UP give up with nothing used.
                    if (stateTicks > AIM_GIVE_UP) {
                        AutoRoutesFeature.chatBad("Kill Mimic: could not aim straight down - Hyperion not used.");
                        finish(client, player, String.format(java.util.Locale.ROOT,
                                "gave up: pitch %.1f after %d ticks, Hyperion not used", player.getXRot(), stateTicks));
                        return;
                    }
                    if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                        RouteRotation.beginApproach(player.getYRot(), 90f, false, 0f, 0f);
                    }
                    return;
                }
                if (sneakDown || turning) {
                    return;
                }
                use(client, player);
                if (mode == AutoRoutesConfig.KillMimic.HYPERION) {
                    // Once: Wither Impact straight down implodes where he stands.
                    finish(client, player, "wither impact used");
                    return;
                }
                state = State.USING;
                stateTicks = 0;
            }
            case USING -> {
                if (tookControl(client)) {
                    finish(client, player, "you moved");
                    return;
                }
                if (stateTicks > USE_TIMEOUT) {
                    AutoRoutesFeature.chatBad("Kill Mimic: the mimic did not die in " + USE_TIMEOUT / 20 + " s.");
                    finish(client, player, "timed out");
                    return;
                }
                int delay = Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks());
                if (AwaitEvents.now() - lastUseTick >= delay) {
                    use(client, player);
                }
            }
            default -> {
            }
        }
    }

    private static void use(Minecraft client, LocalPlayer player) {
        RouteExecutor.useHeldItem(client, player, player.getYRot(), 90f, false);
        uses++;
        lastUseTick = AwaitEvents.now();
        // The pitch the use went out at in legit mode is the camera's (obvious mode sends 90 in the packet).
        LOGGER.info("[AutoRoutes] Kill Mimic: {} used straight down (use {}, {} pitch {})", mode.label(), uses,
                AutoRoutesConfig.getInstance().isLegitMode() ? "camera" : "packet",
                AutoRoutesConfig.getInstance().isLegitMode()
                        ? String.format(java.util.Locale.ROOT, "%.1f", player.getXRot()) : "90.0");
    }

    private static void finish(Minecraft client, LocalPlayer player, String why) {
        LOGGER.info("[AutoRoutes] Kill Mimic done: {}", why);
        if (player != null && state != State.PENDING && slotBefore >= 0 && slotBefore <= 8) {
            RouteExecutor.select(client, player, slotBefore);
        }
        if (state == State.AIM || state == State.USING) {
            if (AutoRoutesConfig.getInstance().isLegitMode()) {
                // The camera is the route's again: its next node aims where it needs.
                RouteRotation.clear();
            }
        }
        state = State.IDLE;
    }

    private static boolean[] movementKeys(Minecraft client) {
        var o = client.options;
        return new boolean[]{o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
                o.keyJump.isDown()};
    }

    /** A movement key pressed since it started (a key already held then - the walk to the chest - is not one). */
    private static boolean tookControl(Minecraft client) {
        if (com.killer560.hub.compat.McCompat.screen(client) != null || keysAtStart == null) {
            return false;
        }
        boolean[] now = movementKeys(client);
        for (int i = 0; i < now.length; i++) {
            if (now[i] && !keysAtStart[i]) {
                return true;
            }
            if (!now[i]) {
                keysAtStart[i] = false;
            }
        }
        return false;
    }
}
