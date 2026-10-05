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
                if (!RouteExecutor.mayYieldToMimic()) {
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
        LOGGER.info("[AutoRoutes] Kill Mimic: {} used straight down (use {})", mode.label(), uses);
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
