package com.killer560.hub.teamledger;

import com.killer560.hub.dungeonqueue.PvJoinLink;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.runsummary.RunHistoryStore;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Teammate Ledger (idea 4, killer560 2026-11-23: "sure good idea"): what YOUR OWN saved runs say about one teammate,
 * from the party list each run already stores by UUID ({@link RunRecord.PartyMember}). Nothing new is recorded, nothing
 * is sent anywhere and no player line is logged: it is read from the run history on demand (a Party Finder join line or
 * {@code /teammate NAME}), so it needs no second store and never disagrees with /log. It covers the runs the history
 * keeps (Dungeon Run Summary's "Runs kept", at most 500), which the text says.
 */
public final class TeammateLedger {

    private static final Logger LOGGER = ModLog.get("killer560smod-teamledger");

    public static final IslandConfig CFG = IslandConfig.of("dungeon", "teamledger").flag("onJoin", true);

    /** What the saved runs say about one player. {@code runs == 0}: never in a saved run with you. */
    public record Summary(int runs, int finished, String floor, int floorRuns, long avgMs, int deaths,
                          String topCause, int topCauseCount, int unfinished) {
    }

    private TeammateLedger() {
    }

    public static void register() {
        ChatObserver.subscribe(TeammateLedger::onChat);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("teammate").then(ClientCommands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            Minecraft.getInstance().execute(() -> show(name, true));
                            return 1;
                        }))));
    }

    private static void onChat(Component message) {
        try {
            String plain = ChatObserver.strip(message);
            if (plain != null) onLine(plain.trim());
        } catch (RuntimeException e) {
            LOGGER.warn("[TeamLedger] line failed", e);
        }
    }

    /** One chat line (color codes stripped): a Party Finder join prints the ledger line for that player. */
    public static void onLine(String plain) {
        if (!CFG.isEnabled() || !CFG.on("onJoin") || !SkyblockGate.allows()) return;
        Matcher m = PvJoinLink.PF_JOINED.matcher(plain);
        if (!m.matches()) return;
        String name = m.group(1);
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> show(name, false));
    }

    /** Prints the ledger line for {@code name}; a join with no saved run together prints nothing unless {@code always}. */
    static void show(String name, boolean always) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || name.equalsIgnoreCase(client.player.getGameProfile().name())) return;
        UUID id = PlayerNames.uuidFor(name);
        Summary s = summarize(RunHistoryStore.runs(), name, id == null ? null : id.toString());
        if (s.runs() == 0 && !always) return;
        client.player.sendSystemMessage(Component.literal("§6>> §r" + name + ": " + describe(s)));
    }

    /** Reads {@code runs} (newest first) for the player with this UUID (dashed text, may be null) or this IGN. */
    public static Summary summarize(List<RunRecord> runs, String name, String uuid) {
        int count = 0, finished = 0, deaths = 0;
        Map<String, long[]> perFloor = new HashMap<>(); // floor -> {finished runs, total ms}
        Map<String, Integer> causes = new HashMap<>();
        for (RunRecord r : runs) {
            RunRecord.PartyMember me = find(r, name, uuid);
            if (me == null) continue;
            count++;
            boolean done = r.hypixelTime() != null && !r.hypixelTime().isBlank() && r.totalMs() > 0;
            if (done) {
                finished++;
                long[] f = perFloor.computeIfAbsent(r.floorLabel(), k -> new long[2]);
                f[0]++;
                f[1] += r.totalMs();
            }
            for (String cause : me.deathCauses()) {
                causes.merge(causeOf(cause), 1, Integer::sum);
            }
            deaths += Math.max(me.deaths(), me.deathCauses().size());
        }
        String floor = null;
        long[] best = null;
        for (Map.Entry<String, long[]> e : perFloor.entrySet()) {
            if (best == null || e.getValue()[0] > best[0]) {
                floor = e.getKey();
                best = e.getValue();
            }
        }
        String cause = null;
        int causeN = 0;
        for (Map.Entry<String, Integer> e : causes.entrySet()) {
            if (e.getValue() > causeN) {
                cause = e.getKey();
                causeN = e.getValue();
            }
        }
        return new Summary(count, finished, floor, best == null ? 0 : (int) best[0],
                best == null ? 0 : best[1] / best[0], deaths, cause, causeN, count - finished);
    }

    private static RunRecord.PartyMember find(RunRecord r, String name, String uuid) {
        for (RunRecord.PartyMember m : r.party()) {
            if (uuid != null && !uuid.isBlank() && uuid.equalsIgnoreCase(m.uuid())) return m;
            if (m.name() != null && m.name().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    /** "was killed by Goldor's lasers" -> "Goldor's lasers" (the death line's own words after the last "by "). */
    static String causeOf(String line) {
        int i = line.lastIndexOf(" by ");
        return (i >= 0 ? line.substring(i + 4) : line).trim().toLowerCase(Locale.US);
    }

    public static String describe(Summary s) {
        if (s.runs() == 0) return "no saved runs with you";
        StringBuilder sb = new StringBuilder(s.runs() + (s.runs() == 1 ? " run" : " runs") + " with you");
        if (s.floor() != null) {
            sb.append(", avg ").append(RunRecord.formatTime(s.avgMs())).append(' ').append(s.floor());
            if (s.floorRuns() > 1) sb.append(" (").append(s.floorRuns()).append(" runs)");
        }
        sb.append(", ").append(s.deaths()).append(s.deaths() == 1 ? " death" : " deaths");
        if (s.topCause() != null && s.deaths() > 0) sb.append(" (").append(s.topCauseCount()).append(" to ").append(s.topCause()).append(')');
        if (s.unfinished() > 0) sb.append(", ").append(s.unfinished()).append(" unfinished");
        return sb.toString();
    }
}
