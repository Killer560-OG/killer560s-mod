package com.killer560.hub.bugreport;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.mixin.BossHealthOverlayAccessor;
import com.killer560.hub.scoreboard.ChunkedStat;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What a bug report holds about the live game, read once on the client thread (the zip is written elsewhere). Each text
 * file is bounded; a reader that throws writes its error instead of losing the file. Only sidebar and tab INFO lines
 * (lines with a ": ", plus the footer) are kept, never the player list.
 */
final class ReportSnapshot {
    private static final int MAX_LINES = 120;

    private ReportSnapshot() {
    }

    /** File name to content, in zip order. {@code title} and {@code description} may be empty. */
    static Map<String, String> take(String title, String description) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("note.txt", "Title: " + (title == null ? "" : title.strip()) + "\n\nDescription:\n"
                + (description == null || description.isBlank() ? "(none)" : description.strip()) + "\n");
        guard(() -> {
            ScoreboardData.refresh(mc);
            return "";
        });
        out.put("sidebar.txt", guard(() -> sidebar(mc)));
        out.put("state.txt", guard(() -> state(mc)));
        out.put("readers.txt", guard(ChunkedStat::report));
        out.put("hud.txt", guard(() -> hud()));
        out.put("automation.txt", automation());
        return out;
    }

    /** What the dungeon, network and automation state reads right now: one line each, read once (nothing per tick). */
    private static String automation() {
        StringBuilder sb = new StringBuilder();
        line(sb, "In a dungeon", com.killer560.hub.secrets.DungeonState::isInDungeon);
        line(sb, "Dungeon floor", com.killer560.hub.secrets.DungeonState::getFloor);
        line(sb, "F7/M7 boss phase", com.killer560.hub.secrets.DungeonState::isBossPhaseActive);
        line(sb, "Dungeon run", com.killer560.hub.secrets.DungeonRun::describe);
        line(sb, "Ping measured (ms)", () -> com.killer560.hub.util.PingTracker.hasMeasurement()
                ? com.killer560.hub.util.PingTracker.measuredMs() + ", best " + com.killer560.hub.util.PingTracker.bestMs()
                + ", automation " + com.killer560.hub.util.PingTracker.automationMs() : "no measurement yet");
        line(sb, "Server TPS estimate (-1 = unknown)", () -> String.format(java.util.Locale.US, "%.1f",
                com.killer560.hub.lagdisplay.LagDisplayFeature.tps()));
        line(sb, "Server corrections this session", com.killer560.hub.util.ServerCorrections::reports);
        line(sb, "Last server correction", com.killer560.hub.util.ServerCorrections::lastReport);
        line(sb, "Last server move (blocks)", com.killer560.hub.util.ServerCorrections::lastMoveDistance);
        line(sb, "Sim active", com.killer560.hub.roomsim.SimState::isActive);
        line(sb, "Sim run running", com.killer560.hub.roomsim.SimRun::isRunning);
        line(sb, "Sim co-op host", com.killer560.hub.roomsim.SimHost::status);
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            line(sb, "Auto Routes running", com.killer560.hub.autoroutes.RouteExecutor::isRunning);
            line(sb, "Auto Routes room", com.killer560.hub.autoroutes.RouteExecutor::runningRoom);
            line(sb, "Auto Routes last stop", com.killer560.hub.autoroutes.RouteExecutor::stopReason);
            line(sb, "Auto Routes last completed room", com.killer560.hub.autoroutes.RouteExecutor::lastCompletedRoom);
            line(sb, "AP3 running", com.killer560.hub.ap3.Ap3Executor::isRunning);
            line(sb, "AP3 active node", () -> {
                var n = com.killer560.hub.ap3.Ap3Executor.activeNode();
                return n == null ? "none" : String.format(java.util.Locale.US, "%s at %.2f, %.2f, %.2f yaw %.1f pitch %.1f",
                        n.type(), n.x(), n.y(), n.z(), n.yaw(), n.pitch());
            });
            line(sb, "AP3 last stop", com.killer560.hub.ap3.Ap3Executor::stopReason);
            line(sb, "Auto Clear", com.killer560.hub.autoclear.AutoClearFeature::status);
            line(sb, "Blink holding", com.killer560.hub.blink.BlinkFeature::isHolding);
            line(sb, "Blink Charge active", com.killer560.hub.blink.BlinkCharge::isActive);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, Supplier<?> value) {
        sb.append(label).append(": ");
        try {
            sb.append(value.get());
        } catch (RuntimeException | LinkageError e) {
            sb.append("error reading it: ").append(e);
        }
        sb.append('\n');
    }

    private static String guard(Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException | LinkageError e) {
            return "error reading this section: " + e + "\n";
        }
    }

    /** Section codes shown as {@code §} text, so a report shows the exact line shape. */
    static String esc(String s) {
        return s == null ? "" : s.replace("§", "\\u00a7");
    }

    private static String sidebar(Minecraft mc) {
        StringBuilder sb = new StringBuilder("Island: ").append(ScoreboardData.island()).append('\n');
        sb.append("Sidebar title: ").append(esc(ScoreboardData.objectiveTitle())).append('\n');
        sb.append("Sidebar lines (raw, section codes escaped):\n");
        int n = 0;
        for (String line : ScoreboardData.sidebarNow(mc)) {
            if (n++ >= MAX_LINES) break;
            sb.append(esc(line)).append('\n');
        }
        sb.append("Tab info lines (the player list is left out):\n");
        n = 0;
        for (String line : ScoreboardData.tabPlain()) {
            if (line.contains(": ") && n++ < MAX_LINES) sb.append(line).append('\n');
        }
        sb.append("Tab footer: ").append(ScoreboardData.tabFooter().replace('\n', '|')).append('\n');
        sb.append("Tab header: not read\n");
        return sb.toString();
    }

    private static String state(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build variant: ").append(BuildVariant.CHEAT_FEATURES_ENABLED ? "cheat" : "legit").append('\n');
        sb.append("Language: ").append(mc.getLanguageManager().getSelected()).append('\n');
        sb.append("GUI scale setting: ").append(mc.options.guiScale().get()).append(", in use: ")
                .append(mc.getWindow().getGuiScale()).append(", window ").append(mc.getWindow().getWidth()).append('x')
                .append(mc.getWindow().getHeight()).append('\n');
        sb.append("Resource packs: ").append(new TreeSet<>(mc.getResourcePackRepository().getSelectedIds())).append('\n');
        sb.append("Island: ").append(ScoreboardData.island()).append(" (known: ").append(ScoreboardData.islandKnown()).append(")\n");
        sb.append("Dungeon floor: ").append(com.killer560.hub.secrets.DungeonState.getFloor()).append('\n');
        sb.append("Skyblock Only gate: on Skyblock=").append(SkyblockGate.isOnSkyblock()).append(", allows=")
                .append(SkyblockGate.allows()).append('\n');
        sb.append("Action bar: ").append(esc(ScoreboardData.actionBar())).append('\n');
        sb.append("Boss bars: ").append(bossBars(mc)).append('\n');
        Screen screen = McCompat.screen(mc);
        sb.append("Open screen: ").append(screen == null ? "none" : screen.getClass().getSimpleName() + " '"
                + screen.getTitle().getString() + "'").append('\n');
        ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        sb.append("Held item Skyblock id: ").append(com.killer560.hub.autoroutes.ItemIdentity.skyblockId(held)).append('\n');
        sb.append("Features disabled by their guard: ").append(new TreeSet<>(FeatureGuard.disabled())).append('\n');
        return sb.toString();
    }

    private static String bossBars(Minecraft mc) {
        var overlay = McCompat.bossOverlay(mc);
        if (overlay == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (LerpingBossEvent e : ((BossHealthOverlayAccessor) overlay).killer560smod$getEvents().values()) {
            sb.append('[').append(ChatObserver.stripCodes(e.getName().getString())).append("] ");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String hud() {
        StringBuilder sb = new StringBuilder("id | enabled in settings | drawn in the last 10 s | position | why hidden\n");
        for (HudElement e : HudElementRegistry.all()) {
            boolean enabled;
            try {
                enabled = e.isEnabledInSettings();
            } catch (RuntimeException ex) {
                enabled = true;
            }
            boolean drawn = HudSeen.drawnRecently(e.id());
            int[] p = HudElementRegistry.resolvePosition(e);
            sb.append(e.id()).append(" | ").append(enabled).append(" | ").append(drawn).append(" | ")
                    .append(p == null ? "?" : p[0] + "," + p[1]).append(" | ")
                    .append(!enabled ? "its setting is off" : drawn ? "-" : "on, but it has not drawn (not in its area or state)").append('\n');
        }
        return sb.toString();
    }
}
