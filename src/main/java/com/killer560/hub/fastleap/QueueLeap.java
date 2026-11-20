package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.spiritleap.SpiritLeapOverlayConfig;
import com.killer560.hub.util.RightClick;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Queue Leap (cheat build, default OFF; queueleap-1119, killer560 2026-11-18: "it should insta open the leap menu and let
 * you leap instantly ... it just queues the packets to go out as fast as possible").
 * <p>
 * His own right click with a Spirit Leap / Infinileap sends its use_item as always ({@code QueueLeapStartUseMixin} watches
 * {@code Minecraft.startUseItem} and acts only when a use really left the client). In that same tick the mod opens
 * {@link QueueLeapScreen}, the custom leap menu's boxes built from the tab-list teammates, and starts a queued
 * {@link LeapManager} leap: the real menu is hidden when it arrives, and his pick is clicked on it from the start of the
 * first tick its head is in (the one Fast Leap click path, through ActionGate). Movement is held from the use until the
 * server's teleport. With no known teammate, a leap already running or a believed cooldown, nothing changes.
 */
public final class QueueLeap {

    private static int usesBefore = -1;
    private static boolean openPending;

    private QueueLeap() {
    }

    /** HEAD of {@code startUseItem}: is this a right click Queue Leap takes? (A lone pearl is gone once used: look now.) */
    public static void beforeUse(Minecraft client) {
        usesBefore = -1;
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || !SpiritLeapOverlayConfig.getInstance().isQueueLeap()
                || client.player == null || McCompat.screen(client) != null || !DungeonState.isInDungeon()
                || LeapManager.isBusy() || LeapManager.cooldownTicks() > 0
                || !LeapManager.isLeapStack(client.player.getMainHandItem()) || !anyAlive()) {
            return;
        }
        usesBefore = RightClick.itemUsesSent();
    }

    /** RETURN of {@code startUseItem}: the use went out - queue the leap; the screen opens at the end of this tick. */
    public static void afterUse(Minecraft client) {
        int before = usesBefore;
        usesBefore = -1;
        if (before >= 0 && RightClick.itemUsesSent() > before && LeapManager.beginQueued(client)) {
            openPending = true;
        }
    }

    /** END_CLIENT_TICK. */
    public static void onEndTick(Minecraft client) {
        if (!openPending) {
            return;
        }
        openPending = false;
        if (LeapManager.awaitingPick() && McCompat.screen(client) == null) {
            List<String> names = new ArrayList<>(4);
            for (Teammates.Teammate t : Teammates.noSelf()) {
                names.add(t.name());
            }
            McCompat.setScreen(client, new QueueLeapScreen(names));
        }
    }

    private static boolean anyAlive() {
        for (Teammates.Teammate t : Teammates.noSelf()) {
            if (!t.dead()) {
                return true;
            }
        }
        return false;
    }
}
