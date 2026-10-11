package com.killer560.hub.autokick;

import com.google.gson.JsonObject;
import com.killer560.hub.autokick.AutoKickConfig.ActionMode;
import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.partyfinder.PartyFinderStatsApi;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Kick - Party Finder only. killer560, 2026-10-09: "Auto kick is only something to be used during the party finder
 * stage while you are queuing for a run." (It used to kick in the middle of a run once the floor's time had passed.)
 * <p>
 * When Hypixel says "Party Finder > Name joined the dungeon group! (Class Level N)" and you are NOT in a dungeon, the
 * joiner's S+ personal best on the floor being queued is looked up ({@link PartyFinderStatsApi}, the Party Finder
 * overlay's own cached lookup), and if it is slower than that floor's time on the tab - or there is none - Auto Kick
 * warns (Warn Only, the default) or sends {@code /p kick <name>} (Kick). A lookup that fails never kicks anyone; it says
 * so. The floor being queued is the tab's Queue Floor, or (Last Run) the floor of the last run you were in.
 * <p>
 * Safety: never yourself (your own join line means you joined someone else's group, and nothing happens); never while
 * in a dungeon (anything still waiting is dropped the moment a run starts); one kick at a time through
 * {@link ActionGate} plus {@link #KICK_MIN_GAP_MS}; the name is the anchored {@code [A-Za-z0-9_]{1,16}} group of a line
 * that must start with "Party Finder > ", which no player's chat line can. Hypixel only lets the party leader kick.
 */
public final class AutoKickFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-autokick");

    /** The same line {@code leapmenu.PartyTracker} reads. */
    static final Pattern PF_JOINED = Pattern.compile(
            "^Party Finder > ([A-Za-z0-9_]{1,16}) joined the dungeon group! \\((\\w+) Level \\d+\\)$");

    /** A Party Finder PB as the stats lookup formats it ("4:12"). */
    private static final Pattern MM_SS = Pattern.compile("^(\\d{1,3}):(\\d{2})$");

    private static final long KICK_MIN_GAP_MS = 3_000L;
    /** How long a joiner's lookup may take before Auto Kick gives up on him (and kicks nobody). */
    private static final long LOOKUP_TIMEOUT_MS = 15_000L;

    private record Pending(String name, Floor floor, long requestedAtMs) {
    }

    private static final Map<String, Pending> waiting = new LinkedHashMap<>();
    private static final List<String> pendingKicks = new ArrayList<>();
    private static long lastKickAtMs = 0L;
    private static Floor lastRunFloor = null;
    /** Every decision, newest last ("name F7 S+ 4:12 limit 5:00 -> kick"), for logs and the testkit. */
    private static final List<String> decisions = new ArrayList<>();
    private static final List<String> kicksSent = new ArrayList<>();

    private AutoKickFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(AutoKickFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoKickFeature", client -> tick()));
    }

    /** The floor being queued: the tab's Queue Floor, else the last run's floor, else null. */
    public static Floor queueFloor() {
        Floor chosen = AutoKickConfig.getInstance().getQueueFloor();
        return chosen != null ? chosen : lastRunFloor;
    }

    public static List<String> decisions() {
        return List.copyOf(decisions);
    }

    public static List<String> kicksSent() {
        return List.copyOf(kicksSent);
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain != null) {
            onLine(plain.trim());
        }
    }

    /** One chat line (colour codes already stripped). */
    public static void onLine(String plain) {
        AutoKickConfig cfg = AutoKickConfig.getInstance();
        if (!cfg.isEnabled() || DungeonState.isInDungeon()) {
            return;
        }
        Matcher m = PF_JOINED.matcher(plain);
        if (!m.matches()) {
            return;
        }
        String name = m.group(1);
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        if (self != null && self.equalsIgnoreCase(name)) {
            return; // you joined someone else's group
        }
        Floor floor = queueFloor();
        if (floor == null) {
            ModChat.send("Auto Kick", ModChat.value(name), ModChat.text(" joined, but no floor is known yet - set "),
                    ModChat.value("Queue Floor"), ModChat.text(" on the Auto Kick tab, or play a run first."));
            decide(name + " no floor -> none");
            return;
        }
        waiting.put(name.toLowerCase(Locale.ROOT), new Pending(name, floor, System.currentTimeMillis()));
        PartyFinderStatsApi.request(List.of(name));
    }

    private static void tick() {
        if (DungeonState.isInDungeon()) {
            Floor f = Floor.fromRaw(DungeonState.getFloor());
            if (f != null) {
                lastRunFloor = f;
            }
            // Party Finder only: a run has started, so nothing still waiting is acted on.
            waiting.clear();
            pendingKicks.clear();
            return;
        }
        AutoKickConfig cfg = AutoKickConfig.getInstance();
        if (!cfg.isEnabled()) {
            waiting.clear();
            pendingKicks.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Pending>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Pending> e = it.next();
            Pending p = e.getValue();
            String name = p.name();
            PartyFinderStatsApi.PlayerStats stats = PartyFinderStatsApi.get(name);
            if (stats != null) {
                it.remove();
                judge(cfg, name, p.floor(), stats);
            } else if (PartyFinderStatsApi.hasFailed(name) || now - p.requestedAtMs() > LOOKUP_TIMEOUT_MS) {
                it.remove();
                ModChat.send("Auto Kick", ModChat.text("Couldn't look up "), ModChat.value(name),
                        ModChat.text("'s times"), ModChat.dim(" - not kicked."));
                decide(name + " " + p.floor().name() + " lookup failed -> none");
            }
        }
        if (!pendingKicks.isEmpty()) {
            drainPendingKicks();
        }
    }

    private static void judge(AutoKickConfig cfg, String name, Floor floor, PartyFinderStatsApi.PlayerStats stats) {
        int limit = cfg.getTargetSeconds(floor);
        int pb = sPlusSeconds(stats, floor);
        String limitStr = mmSs(limit);
        if (pb > 0 && pb <= limit) {
            decide(name + " " + floor.name() + " S+ " + mmSs(pb) + " limit " + limitStr + " -> ok");
            return;
        }
        String pbStr = pb > 0 ? mmSs(pb) : "none";
        boolean kick = cfg.getMode() == ActionMode.KICK_ALL;
        decide(name + " " + floor.name() + " S+ " + pbStr + " limit " + limitStr + " -> " + (kick ? "kick" : "warn"));
        ModChat.send("Auto Kick", ModChat.value(name), ModChat.text(" - " + floor.label() + " S+ "),
                ModChat.value(pbStr), ModChat.text(", limit "), ModChat.value(limitStr),
                ModChat.dim(kick ? ". Kicking." : ". Warn only, not kicked."));
        if (kick && !pendingKicks.contains(name)) {
            pendingKicks.add(name);
        }
    }

    /** The joiner's S+ personal best on {@code floor} in seconds, or 0 when he has none. */
    static int sPlusSeconds(PartyFinderStatsApi.PlayerStats stats, Floor floor) {
        JsonObject map = floor.master() ? stats.pbMaster() : stats.pbNormal();
        String t = ConfigJson.getString(ConfigJson.getObject(map, "s_plus"), "floor_" + floor.number(), null);
        if (t == null) {
            return 0;
        }
        Matcher m = Pattern.compile("^(\\d{1,3}):(\\d{2})$").matcher(t.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : 0;
    }

    private static void drainPendingKicks() {
        long now = System.currentTimeMillis();
        if (now - lastKickAtMs < KICK_MIN_GAP_MS || !ActionGate.tryAct(ActionGate.Actor.AUTO_KICK)) {
            return;
        }
        String name = pendingKicks.remove(0);
        if (!ServerCommands.toServer("p kick " + name)) {
            LOGGER.warn("[AutoKick] Couldn't send \"p kick {}\" - no connection. Dropping {} queued kick(s).",
                    name, pendingKicks.size());
            pendingKicks.clear();
            return;
        }
        lastKickAtMs = now;
        kicksSent.add(name);
    }

    private static void decide(String line) {
        decisions.add(line);
        if (decisions.size() > 50) {
            decisions.remove(0);
        }
        LOGGER.info("[AutoKick] {}", line);
    }

    private static String mmSs(int seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
