package com.killer560.hub.runstats;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Run Stats - one client-side summary of who did what, printed after a dungeon run ends.
 *
 * <p>Trigger: the "{@code > EXTRA STATS <}" line of the end-of-run block, the same trigger
 * {@code dungeonqueue} and {@code splittimers} already use. Optionally re-sends {@code /showextrastats}
 * first (off by default - it is a server command), and optionally repeats a one-line version in party chat
 * (off by default, at most once per run). Everything else is local only.
 *
 * <p>Read {@link RunStatsTracker}'s class doc before changing the numbers: Hypixel's end-of-run page has no
 * per-player stats whatsoever, so rooms are a derived attribution (printed as a range when people stacked) and
 * secrets come from a Hypixel lifetime-secret delta, not from chat.
 */
public final class RunStatsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-runstats");
    private static final String FEATURE = "Run Stats";

    private static final Pattern EXTRA_STATS = Pattern.compile("(?m)^\\s*> EXTRA STATS <\\s*$");

    private static Object lastLevel;
    private static boolean summarisedThisRun = false;
    private static int pendingTicks = -1;
    private static int pollTick = 0;

    private RunStatsFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(RunStatsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(RunStatsFeature::tick);
    }

    // ------------------------------------------------------------------------------------------- chat

    private static void onChat(Component message) {
        RunStatsConfig cfg = RunStatsConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        String plain = ChatObserver.strip(message);
        RunStatsTracker.onChatLine(client, plain.trim());

        if (!EXTRA_STATS.matcher(plain).find() || summarisedThisRun) {
            return;
        }
        summarisedThisRun = true;
        // One last poll so a room cleared in the same tick as the boss dying still counts.
        RunStatsTracker.pollRooms(client);
        RunStatsTracker.captureSecrets(client);
        if (cfg.isAutoShowExtraStats() && client.player != null) {
            client.player.connection.sendCommand("showextrastats");
        }
        pendingTicks = Math.max(1, cfg.getDelaySeconds() * 20);
    }

    // ------------------------------------------------------------------------------------------- tick

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            pendingTicks = -1;
            lastLevel = null;
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            summarisedThisRun = false;
            pendingTicks = -1;
            RunStatsTracker.resetRun();
        }
        RunStatsConfig cfg = RunStatsConfig.getInstance();
        if (!cfg.isEnabled()) {
            pendingTicks = -1;
            return;
        }
        if (DungeonState.isInDungeon()) {
            if (!RunStatsTracker.snapshotTaken()) {
                RunStatsTracker.snapshotSecrets(client);
            }
            if (++pollTick >= 5) {
                pollTick = 0;
                RunStatsTracker.pollRooms(client);
            }
        }
        if (pendingTicks < 0 || pendingTicks-- > 0) {
            return;
        }
        pendingTicks = -1;
        printSummary(client, cfg);
    }

    // ------------------------------------------------------------------------------------------- output

    private static void printSummary(Minecraft client, RunStatsConfig cfg) {
        List<PlayerRunStats> rows = RunStatsTracker.buildRows(client);
        if (rows.isEmpty()) {
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Run summary"),
                ModChat.dim(" (rooms are estimated from the map)"));
        for (PlayerRunStats row : rows) {
            client.player.sendSystemMessage(rowComponent(row, cfg));
        }
        if (cfg.isAnnounceToParty()) {
            client.player.connection.sendCommand("pc " + partyLine(rows, cfg));
        }
        LOGGER.info("[RunStats] {}", partyLine(rows, cfg));
    }

    private static MutableComponent rowComponent(PlayerRunStats row, RunStatsConfig cfg) {
        MutableComponent out = ModChat.prefix(FEATURE);
        out.append(name(row));
        out.append(ModChat.dim(": "));
        boolean first = true;
        if (row.hasRooms()) {
            out.append(withRoomTooltip(ModChat.value(row.roomsLabel() + " rooms"), row));
            first = false;
        }
        if (row.hasSecrets()) {
            if (!first) {
                out.append(ModChat.dim(" | "));
            }
            out.append(ModChat.value(row.secrets() + " secrets"));
            first = false;
        }
        if (cfg.isShowDeaths() && !row.deaths().isEmpty()) {
            if (!first) {
                out.append(ModChat.dim(" | "));
            }
            MutableComponent deaths = ModChat.bad(row.deaths().size() + (row.deaths().size() == 1 ? " death" : " deaths"));
            out.append(tooltip(deaths, String.join("\n", row.deaths())));
        }
        return out;
    }

    private static MutableComponent name(PlayerRunStats row) {
        DungeonClass cls = row.dungeonClass();
        // DungeonClass.color() is 0xAARRGGBB; TextColor wants plain RGB.
        int color = cls == null ? ModChat.LIGHT_ORANGE : cls.color() & 0x00FFFFFF;
        MutableComponent out = ModChat.colored(row.name(), color);
        if (cls != null) {
            out.append(ModChat.dim(" (" + cls.displayName() + ")"));
        }
        return out;
    }

    private static MutableComponent withRoomTooltip(MutableComponent text, PlayerRunStats row) {
        List<List<String>> breakdown = RunStatsTracker.roomBreakdown(row.name());
        StringBuilder sb = new StringBuilder();
        for (String room : breakdown.get(0)) {
            sb.append(room).append(" (alone)\n");
        }
        for (String room : breakdown.get(1)) {
            sb.append(room).append(" (stacked)\n");
        }
        if (sb.isEmpty()) {
            return text;
        }
        sb.append("\nRooms are credited to whoever stood in them when the map marked them cleared.");
        return tooltip(text, sb.toString());
    }

    private static MutableComponent tooltip(MutableComponent text, String hover) {
        Style style = text.getStyle().withHoverEvent(new HoverEvent.ShowText(
                Component.literal(hover).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(ModChat.TEXT)))));
        return text.setStyle(style);
    }

    /** The single opt-in party-chat line - deliberately compact and marked as an estimate. */
    private static String partyLine(List<PlayerRunStats> rows, RunStatsConfig cfg) {
        StringBuilder sb = new StringBuilder("Run stats (est):");
        for (int i = 0; i < rows.size(); i++) {
            PlayerRunStats row = rows.get(i);
            sb.append(i == 0 ? " " : " | ").append(row.name()).append(' ').append(row.roomsLabel()).append('r');
            if (row.hasSecrets()) {
                sb.append(' ').append(row.secrets()).append('s');
            }
            if (cfg.isShowDeaths() && !row.deaths().isEmpty()) {
                sb.append(' ').append(row.deaths().size()).append('d');
            }
        }
        return sb.length() > 240 ? sb.substring(0, 240) : sb.toString();
    }
}
