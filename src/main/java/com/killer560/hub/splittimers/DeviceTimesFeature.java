package com.killer560.hub.splittimers;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "For others [not terminals] it should go based off of section open. So use the split timer to figure
 * out when p2 started, then how long it took for their chat message, then next to it the time (od has
 * this feature.)" - killer560's request. Real reference: Odin's own confirmed, compiling
 * {@code TerminalTimes.kt} ("Terminal Splits" setting) - the real chat line every lever/device completion
 * sends ("PlayerName completed a device! (3/7)", real format ported directly from that source) gets
 * rewritten in place to append how long the currently in-progress split segment (tracked by
 * {@link SplitTimersFeature}, e.g. "P2") has been running when that completion happened.
 * <p>
 * Deliberately excludes real terminal completions (a real "completed a terminal!" line matches the same
 * base pattern) - those already get their own individual per-terminal timing from
 * {@code TerminalSolverFeature}'s "Announce Completion Time" (a GUI-open/close based measurement, more
 * precise than this chat-line approach), so handling them here too would just double-report the same
 * real event.
 * <p>
 * Real in-place rewrite uses Fabric API's own {@code ClientReceiveMessageEvents.MODIFY_GAME} - the real
 * completion line is a server-generated event message (not a signed player chat message), so it arrives
 * on the GAME channel, which is the only one Fabric API actually supports rewriting (there is no real
 * {@code MODIFY_CHAT} equivalent for signed messages - confirmed by inspecting the real
 * {@code ClientReceiveMessageEvents} class, not guessed).
 */
public final class DeviceTimesFeature {

    // Real format ported directly from Odin's own confirmed regex, narrowed to lever/device only (see
    // this class's own doc comment for why terminals are deliberately excluded here).
    private static final Pattern DEVICE_COMPLETE_REGEX =
            Pattern.compile("^(.{1,16}) (activated|completed) a (lever|device)! \\((\\d+)/(\\d+)\\)$");

    private DeviceTimesFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(DeviceTimesFeature::onModifyGameMessage);
    }

    private static Component onModifyGameMessage(Component message, boolean overlay) {
        if (overlay || !SplitTimersConfig.getInstance().isEnabled()
                || !SplitTimersConfig.getInstance().isAnnounceDeviceTimes() || !DungeonState.isInDungeon()) {
            return message;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null) {
            return message;
        }
        Matcher m = DEVICE_COMPLETE_REGEX.matcher(plain);
        if (!m.find()) {
            return message;
        }
        long segmentStartedAtMs = SplitTimersFeature.getCurrentSegmentStartedAtMs();
        if (segmentStartedAtMs <= 0) {
            return message;
        }
        String label = SplitTimersFeature.getCurrentSegmentLabel();
        double seconds = (System.currentTimeMillis() - segmentStartedAtMs) / 1000.0;
        String suffix = label != null
                ? String.format(Locale.US, " §8(§7%.1fs since %s started§8)", seconds, label)
                : String.format(Locale.US, " §8(§7%.1fs§8)", seconds);

        MutableComponent rewritten = message.copy();
        rewritten.append(Component.literal(suffix));
        return rewritten;
    }
}
