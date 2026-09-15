package com.killer560.hub.dungeonqueue;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Auto Requeue - a port of Odin's {@code DungeonQueue.kt} auto-requeue (Devonian's {@code AutoRequeueDungeons.kt}
 * uses the same trigger and command):
 * <ul>
 * <li>Trigger: the "{@code > EXTRA STATS <}" line of the end-of-run summary.
 * <li>After Requeue Delay seconds, sends {@code /instancerequeue} - unless requeueing was disabled in the meantime.
 * <li>Disable on leave/kick: any party leave / kick / disband message (Odin's {@code PartyUtils} patterns that post
 * {@code PartyEvent.Leave}) skips the next requeue. Cleared on world change, like Odin's {@code LevelEvent.Load}.
 * </ul>
 * Safety on top of Odin: at most one requeue per world (i.e. per run), and a pending requeue is dropped on disconnect
 * or when the feature is turned off.
 */
public final class DungeonQueueFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonqueue");
    private static final String FEATURE = "Auto Requeue";

    private static final Pattern EXTRA_STATS = Pattern.compile("(?m)^\\s*> EXTRA STATS <\\s*$");

    private static final String NAME = "(?:\\[[^]]*?] ?)?\\w{1,16}";
    /** Odin {@code PartyUtils}: removeMember + disband patterns (both post {@code PartyEvent.Leave}). */
    private static final List<Pattern> PARTY_LEAVE = List.of(
            Pattern.compile("^" + NAME + " has left the party\\.$"),
            Pattern.compile("^" + NAME + " has been removed from the party\\.$"),
            Pattern.compile("^Kicked " + NAME + " because they were offline\\.$"),
            Pattern.compile("^" + NAME + " was removed from your party because they disconnected\\.$"),
            Pattern.compile("^The party was transferred to " + NAME + " because " + NAME + " left$"),
            Pattern.compile("^" + NAME + " has disbanded the party!$"),
            Pattern.compile("^You have been kicked from the party by " + NAME + "$"),
            Pattern.compile("^The party was disbanded because all invites expired and the party was empty\\.$"),
            Pattern.compile("^The party was disbanded because the party leader disconnected\\.$"),
            Pattern.compile("^You left the party\\.$"),
            Pattern.compile("^You are not currently in a party\\.$"));

    private static boolean disableRequeue = false;
    private static boolean requeuedThisRun = false;
    private static int pendingTicks = -1;
    private static Object lastLevel;

    private DungeonQueueFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(DungeonQueueFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(DungeonQueueFeature::tick);
    }

    private static void onChat(Component message) {
        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        String plain = ChatObserver.strip(message);

        if (EXTRA_STATS.matcher(plain).find()) {
            if (requeuedThisRun) {
                return;
            }
            requeuedThisRun = true;
            if (disableRequeue) {
                disableRequeue = false;
                ModChat.send(FEATURE, ModChat.text("Skipped - a party member left."));
                return;
            }
            pendingTicks = cfg.getDelaySeconds() * 20;
            return;
        }

        if (cfg.isDisableOnLeave()) {
            String trimmed = plain.trim();
            for (Pattern p : PARTY_LEAVE) {
                if (p.matcher(trimmed).matches()) {
                    disableRequeue = true;
                    if (pendingTicks >= 0) {
                        pendingTicks = -1;
                        ModChat.send(FEATURE, ModChat.text("Cancelled - a party member left."));
                    }
                    return;
                }
            }
        }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            pendingTicks = -1;
            lastLevel = null;
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            disableRequeue = false;
            requeuedThisRun = false;
        }
        if (pendingTicks < 0) {
            return;
        }
        if (!DungeonQueueConfig.getInstance().isEnabled()) {
            pendingTicks = -1;
            return;
        }
        if (pendingTicks-- > 0) {
            return;
        }
        pendingTicks = -1;
        if (disableRequeue) {
            return;
        }
        LOGGER.info("[AutoRequeue] Sending /instancerequeue");
        client.player.connection.sendCommand("instancerequeue");
    }
}
