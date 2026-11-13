package com.killer560.hub.posmsg;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.OutgoingChat;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.leapmessage.LeapMessageFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Position-message waypoints: a circle on the ground ({@link PosmsgRenderer}) that types its own line
 * into Party Chat the moment you walk inside it.
 * <p>
 * Reworked 2026-09-16 on killer560's spec - "just draw a line around where the message should be sent
 * if i walk into it... it only needs to send a message like \"at hee2\" no coordinates or anything".
 * The old build instead sent a machine-readable {@code [PM]name|x|y|z|r} payload that other copies of
 * this mod parsed back out into a top-left HUD list; both the payload and that HUD are gone. What goes
 * out now is a plain sentence, so teammates on Odin, on NoammAddons or on no mod at all all read it.
 * <p>
 * Sent to Party Chat through {@link OutgoingChat} (NOT through {@code TranslateFeature.sendGenerated})
 * so Translate / Auto Correct / Chat Emotes can't rewrite a callout mid-run.
 * <p>
 * Firing is edge-triggered with hysteresis: entering the radius sends once, and the waypoint won't arm
 * again until you leave it by a clear margin ({@link #EXIT_MARGIN}), so standing on the boundary can't
 * machine-gun party chat. {@link #MIN_SEND_GAP_MS} is a second, global guard for overlapping circles.
 * "Only send once per run" ({@link PosmsgEntry#onceOnlyPerRun}, default OFF) is tracked here and reset
 * whenever {@link DungeonState#isInDungeon()} goes false -&gt; true, the same fresh-run edge every other
 * per-run reset in this mod watches, and on any world change.
 * <p>
 * Only live inside the F7/M7 boss fight ({@link #active()}): every preset is a boss-arena spot, and
 * killer560 wants the feature completely inert everywhere else - no rings, no sends - so a ring can't
 * show up at matching coordinates in some other floor or the hub, and nothing fires while clearing.
 * <p>
 * Leaping onto a teammate who stands in a ring (killer560, 2026-11-02: "it tries to send that at the same time as
 * other chat messages. delay it till the leap messages go off"): the line goes through
 * {@link OutgoingChat} and leaves at once (no queue, 2026-11-12). When the teleport lands BEFORE Hypixel's "You have teleported to X!" line (so Leap
 * Message has not asked for its lines yet), a ring entered by that jump within {@link #LEAP_MENU_WINDOW_MS} of a
 * leap menu being open is held until the leap line arrives (then it goes at once) or for at most
 * {@link #LEAP_HOLD_MAX_MS}; it is never dropped. A ring walked into, or jumped into with no leap menu, goes at once.
 */
public final class PosmsgFeature {


    /** You must get this much further out than the radius before the waypoint can fire again. */
    private static final double EXIT_MARGIN = 1.5;
    /** Never send two waypoints closer together than this, whatever the circles overlap. */
    private static final long MIN_SEND_GAP_MS = 1_500L;

    private static final Set<String> usedThisRun = new HashSet<>();
    private static final Set<String> inside = new HashSet<>();
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;
    private static long lastSendMs = 0L;

    /** A move this long in one tick is a teleport (a leap, a warp), not a step. */
    private static final double JUMP_BLOCKS = 4.0;
    /** A jump this soon after a leap menu was last open is taken for that leap. */
    static final long LEAP_MENU_WINDOW_MS = 3_000L;
    /** A ring held for the leap line goes anyway after this long. */
    static final long LEAP_HOLD_MAX_MS = 1_000L;

    private static Vec3 lastPos = null;
    private static long leapMenuSeenMs = Long.MIN_VALUE / 4;
    private static long leapLineSeenMs = Long.MIN_VALUE / 4;
    private static final List<PosmsgEntry> held = new ArrayList<>();
    private static long heldSinceMs = 0L;

    private PosmsgFeature() {
    }

    public static void register() {
        PosmsgConfig.getInstance();
        ChatObserver.subscribe(message -> {
            if (LeapMessageFeature.isLeapLine(ChatObserver.strip(message))) {
                leapLineSeenMs = nowMs();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PosmsgFeature.tick", PosmsgFeature::tick));
        LevelRenderEvents.COLLECT_SUBMITS.register(com.killer560.hub.util.FeatureGuard.collectFeatures(
                com.killer560.hub.util.FeatureBits.register("Posmsg render", () -> PosmsgConfig.getInstance().isEnabledRaw()), PosmsgRenderer::render));
    }

    /** Master switch, Skyblock gate AND the F7/M7 boss gate - the single check both the tick loop and
     *  {@link PosmsgRenderer} use, so "nothing draws" and "nothing sends" can never disagree. */
    static boolean active() { return PosmsgConfig.getInstance().isEnabled() && Floor7Tracker.inF7Boss(); }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            usedThisRun.clear();
            inside.clear();
            held.clear();
            lastPos = null;
        }
        long now = nowMs();
        // A Fast/Auto Leap opens the menu headless (no screen), so a leap of its own in progress counts too.
        if (isLeapMenu(McCompat.screen(client)) || com.killer560.hub.fastleap.LeapManager.isBusy()) {
            leapMenuSeenMs = now;
        }
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            usedThisRun.clear();
        }
        wasInDungeon = inDungeonNow;

        Player player = client.player;
        if (player == null || client.level == null) return;
        // Inside/outside membership is tracked every tick regardless of any gate, and only the SEND is
        // gated - so a ring you're already standing in when the feature (master, per-waypoint, or the
        // boss fight itself) becomes live does not fire the instant it goes live; walking out and back
        // in is what fires it. Previously the disabled branch dropped the id instead, which did the
        // exact opposite of what its own comment promised.
        boolean live = active();
        Vec3 pos = player.position();
        boolean jumped = lastPos != null && pos.distanceToSqr(lastPos) > JUMP_BLOCKS * JUMP_BLOCKS;
        lastPos = pos;
        // Landed by a leap whose "You have teleported to" line has not come yet: Leap Message's lines are still to be
        // asked for, so a ring this jump entered waits for them.
        // (A leap line in the last LEAP_HOLD_MAX_MS is this jump's: it came first and its lines are queued already.)
        boolean awaitingLeapLine = jumped && now - leapMenuSeenMs <= LEAP_MENU_WINDOW_MS
                && now - leapLineSeenMs > LEAP_HOLD_MAX_MS;
        releaseHeld(now);
        for (PosmsgEntry e : PosmsgConfig.getInstance().entries()) {
            if (!e.configured) continue;
            // Squared distances: no Vec3 per entry per tick and no sqrt.
            double distSq = pos.distanceToSqr(e.x, e.y, e.z);
            double exit = e.radius + EXIT_MARGIN;
            if (distSq > exit * exit) {
                inside.remove(e.id);
            } else if (distSq <= e.radius * e.radius && inside.add(e.id) && live && e.enabled) {
                if (awaitingLeapLine) {
                    if (held.isEmpty()) {
                        heldSinceMs = now;
                    }
                    held.add(e);
                } else {
                    trySend(e);
                }
            }
        }
    }

    /** Sends the rings held for a leap once its line has come (Leap Message has queued its lines by then: chat is
     *  handled before the end of the tick), or once they have waited {@link #LEAP_HOLD_MAX_MS}. */
    private static void releaseHeld(long now) {
        if (held.isEmpty() || (leapLineSeenMs < heldSinceMs && now - heldSinceMs < LEAP_HOLD_MAX_MS)) return;
        List<PosmsgEntry> go = new ArrayList<>(held);
        held.clear();
        if (!active()) return;
        for (PosmsgEntry e : go) {
            trySend(e);
        }
    }

    /** Hypixel's leap menus, by exact title (Spirit Leap; Infinileap's "Teleport to Player"). */
    private static boolean isLeapMenu(net.minecraft.client.gui.screens.Screen screen) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container)) return false;
        String t = ChatObserver.stripCodes(container.getTitle().getString()).trim().toLowerCase(Locale.ROOT);
        return t.equals("spirit leap") || t.equals("teleport to player");
    }

    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    /** Walk-in send: honors the global gap and the per-run gate, and stays completely silent when it
     *  declines (this fires on movement, not on a button press - a warning overlay every few steps
     *  would be worse than the missed callout). */
    private static void trySend(PosmsgEntry entry) {
        long now = System.currentTimeMillis();
        if (now - lastSendMs < MIN_SEND_GAP_MS) return;
        if (entry.onceOnlyPerRun && usedThisRun.contains(entry.id)) return;
        if (dispatch(entry)) {
            lastSendMs = now;
        }
    }

    /** Sends the given entry to Party Chat now, honoring the once-per-run gate. Used by the tab's
     *  {@code /killer560 posmsg send <name>} command, where an explicit click/command deserves an
     *  explicit reason when nothing happens. */
    public static void send(PosmsgEntry entry) {
        if (entry.onceOnlyPerRun && usedThisRun.contains(entry.id)) {
            ModOverlayMessage.show("[Posmsg] \"" + entry.name + "\" already sent this run.", 2500);
            return;
        }
        if (dispatch(entry)) {
            lastSendMs = System.currentTimeMillis();
        }
    }

    private static boolean dispatch(PosmsgEntry entry) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !SkyblockGate.allows()) return false;
        String text = entry.sendText();
        if (text.isBlank()) return false;
        // In line behind any other line of this mod's (a leap's "Leaping to" first), spaced so Hypixel takes both.
        if (!OutgoingChat.command("pc " + text)) return false;
        usedThisRun.add(entry.id);
        return true;
    }

    /** For {@code /killer560 posmsg add <message> <x> <y> <z> <radius>} - creates and immediately sends
     *  a brand-new, always-reusable custom waypoint. */
    public static PosmsgEntry addAndSend(String message, double x, double y, double z, double radius) {
        PosmsgConfig cfg = PosmsgConfig.getInstance();
        PosmsgEntry e = cfg.addNew();
        e.name = message;
        e.message = message;
        e.x = x;
        e.y = y;
        e.z = z;
        e.radius = radius;
        e.configured = true;
        cfg.save();
        // Already standing in it? Count it as entered so walking out and back in is what re-fires it.
        inside.add(e.id);
        send(e);
        return e;
    }
}
