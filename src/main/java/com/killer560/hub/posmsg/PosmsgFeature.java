package com.killer560.hub.posmsg;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
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
 * Sent to Party Chat directly via {@code sendCommand} (NOT through {@code TranslateFeature.sendGenerated})
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
 */
public final class PosmsgFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-posmsg");

    /** You must get this much further out than the radius before the waypoint can fire again. */
    private static final double EXIT_MARGIN = 1.5;
    /** Never send two waypoints closer together than this, whatever the circles overlap. */
    private static final long MIN_SEND_GAP_MS = 1_500L;

    private static final Set<String> usedThisRun = new HashSet<>();
    private static final Set<String> inside = new HashSet<>();
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;
    private static long lastSendMs = 0L;

    private PosmsgFeature() {
    }

    public static void register() {
        PosmsgConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(PosmsgFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(PosmsgRenderer::render);
    }

    /** Master switch, Skyblock gate AND the F7/M7 boss gate - the single check both the tick loop and
     *  {@link PosmsgRenderer} use, so "nothing draws" and "nothing sends" can never disagree. */
    static boolean active() {
        return PosmsgConfig.getInstance().isEnabled() && Floor7Tracker.inF7Boss();
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            usedThisRun.clear();
            inside.clear();
        }
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            usedThisRun.clear();
            LOGGER.info("[Posmsg] New dungeon run detected - cleared once-per-run usage");
        }
        wasInDungeon = inDungeonNow;

        Player player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        // Inside/outside membership is tracked every tick regardless of any gate, and only the SEND is
        // gated - so a ring you're already standing in when the feature (master, per-waypoint, or the
        // boss fight itself) becomes live does not fire the instant it goes live; walking out and back
        // in is what fires it. Previously the disabled branch dropped the id instead, which did the
        // exact opposite of what its own comment promised.
        boolean live = active();
        Vec3 pos = player.position();
        for (PosmsgEntry e : PosmsgConfig.getInstance().entries()) {
            if (!e.configured) {
                continue;
            }
            double distance = pos.distanceTo(new Vec3(e.x, e.y, e.z));
            if (distance > e.radius + EXIT_MARGIN) {
                inside.remove(e.id);
            } else if (distance <= e.radius && inside.add(e.id) && live && e.enabled) {
                trySend(e);
            }
        }
    }

    /** Walk-in send: honours the global gap and the per-run gate, and stays completely silent when it
     *  declines (this fires on movement, not on a button press - a warning overlay every few steps
     *  would be worse than the missed callout). */
    private static void trySend(PosmsgEntry entry) {
        long now = System.currentTimeMillis();
        if (now - lastSendMs < MIN_SEND_GAP_MS) {
            return;
        }
        if (entry.onceOnlyPerRun && usedThisRun.contains(entry.id)) {
            return;
        }
        if (dispatch(entry)) {
            lastSendMs = now;
        }
    }

    /** Sends the given entry to Party Chat now, honouring the once-per-run gate. Used by the tab's
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
        if (client.player == null || !SkyblockGate.allows()) {
            return false;
        }
        String text = entry.sendText();
        if (text.isBlank()) {
            return false;
        }
        client.player.connection.sendCommand("pc " + text);
        usedThisRun.add(entry.id);
        LOGGER.info("[Posmsg] Sent \"{}\" for waypoint \"{}\"", text, entry.name);
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
