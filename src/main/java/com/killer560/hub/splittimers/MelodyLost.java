package com.killer560.hub.splittimers;

import com.killer560.hub.bridge.MelodyIntel;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.secrets.DungeonRun;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Time Lost To Melody" (melodylost-1118, killer560: "if the last terminal to end was a melody terminal then it will look at
 * whatever was completed before that"). Per P3 section S1-S4: when the LAST objective of the section (the one that took it to
 * N/N) is a terminal whose player had Melody open in THAT section, the time lost is that completion minus the previous
 * completion of the same section. Anything else adds nothing: a Melody finished earlier, a device or lever last, no Melody
 * evidence, or a missing previous line (kept as "unknown", still 0). Nothing carries across sections: the evidence is dated
 * with the stage it was seen in ({@link MelodyIntel#hadSource}) and the line state is per stage. Chat is client side only.
 */
public final class MelodyLost {

    private static final Pattern LINE =
            Pattern.compile("^(\\w{1,16}) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final String GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?";

    /** Milliseconds lost per section 1-4; {@code decided[s]} is null until the section ended, then the reason. */
    private static final long[] lost = new long[5];
    private static final String[] who = new String[5];
    private static final String[] decided = new String[5];
    private static int prevStage;
    private static int prevN;
    private static long prevAt;
    private static final DungeonRun.Tracker RUN = new DungeonRun.Tracker();

    private MelodyLost() {
    }

    public static void register() {
        ChatObserver.subscribe(msg -> {
            String plain = ChatObserver.strip(msg);
            if (plain == null || !TerminalTimersConfig.getInstance().isMelodyLostActive()) {
                return;
            }
            if (plain.equals(GOLDOR_START)) {
                reset();
                return;
            }
            Matcher m = plain.contains("d a ") ? LINE.matcher(plain) : null;
            if (m != null && m.find()) {
                onObjective(m.group(1), m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                        Floor7Tracker.getStage().number, System.currentTimeMillis());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (RUN.newRun(client.level)) {
                reset();
            }
        });
    }

    static void reset() {
        java.util.Arrays.fill(lost, 0L);
        java.util.Arrays.fill(who, null);
        java.util.Arrays.fill(decided, null);
        prevStage = 0;
        prevN = 0;
        prevAt = 0L;
        MelodyIntel.clear();
    }

    /** One "name activated a terminal! (n/m)" style line in P3 stage {@code stage} at {@code now}. Public for tests. */
    public static void onObjective(String name, String kind, int n, int total, int stage, long now) {
        if (stage < 1 || stage > 4) {
            return;
        }
        boolean havePrev = stage == prevStage && n == prevN + 1 && n > 1;
        long since = now - prevAt;
        prevStage = stage;
        prevN = n;
        prevAt = now;
        if (n != total || decided[stage] != null) {
            return;
        }
        String source = MelodyIntel.hadSource(name, stage);
        MelodyIntel.forgetHad(name);
        if (!kind.equals("terminal")) {
            decided[stage] = "last was a " + kind;
        } else if (!havePrev) {
            decided[stage] = "unknown: no previous completion";
        } else if (source == null) {
            decided[stage] = "last terminal was not a Melody";
        } else {
            decided[stage] = "Melody by " + source;
            lost[stage] = since;
            who[stage] = name;
        }
        TerminalTimersConfig cfg = TerminalTimersConfig.getInstance();
        if (lost[stage] > 0 && cfg.isMelodyLostPhase()) {
            ModChat.send("Melody Lost", ModChat.text("Time lost to Melody (S" + stage + "): "),
                    ModChat.value(secs(lost[stage])), ModChat.text(" ("), ModChat.colored(name, ThemeAccent.onDark(ThemeAccent.accent(ModChat.ORANGE))),
                    ModChat.text(")"));
        }
        if (stage == 4 && cfg.isMelodyLostTotal()) {
            MutableComponent parts = ModChat.text("");
            for (int s = 1; s <= 4; s++) {
                parts.append(ModChat.dim(s == 1 ? " (" : " | ")).append(ModChat.text("S" + s + " ")).append(ModChat.value(secs(lost[s])));
            }
            ModChat.send("Melody Lost", ModChat.text("Total time lost to Melody: "), ModChat.value(secs(total())),
                    parts, ModChat.dim(")"));
        }
    }

    static long total() {
        return lost[1] + lost[2] + lost[3] + lost[4];
    }

    /** Section {@code s}'s loss in ms (0 unless its last objective was a Melody terminal). */
    public static long lostMs(int s) {
        return s >= 1 && s <= 4 ? lost[s] : 0L;
    }

    /** Why section {@code s} came out as it did (null while it is running), for tests and logs. */
    public static String decision(int s) {
        return s >= 1 && s <= 4 ? decided[s] : null;
    }

    private static String secs(long ms) {
        return String.format(Locale.US, "%.2fs", SplitTimersConfig.shown(ms) / 1000.0);
    }

    /** "S2 Melody Lost§f: 4.50s" rows for sections that lost time, then the total, for the Split Timers block. */
    public static List<String> splitLines() {
        List<String> out = new ArrayList<>();
        if (!TerminalTimersConfig.getInstance().isMelodyLostSplits() || total() <= 0L) {
            return out;
        }
        for (int s = 1; s <= 4; s++) {
            if (lost[s] > 0) {
                out.add("§6S" + s + " Melody Lost§f: " + secs(lost[s]));
            }
        }
        out.add("§6Melody Lost Total§f: " + secs(total()));
        return out;
    }

    /** The HUD's rows: each finished section, then the total; empty until a section has ended. */
    public static List<String> hudLines() {
        List<String> out = new ArrayList<>();
        for (int s = 1; s <= 4; s++) {
            if (decided[s] != null) {
                out.add("S" + s + ": " + secs(lost[s]));
            }
        }
        if (!out.isEmpty()) {
            out.add("Total: " + secs(total()));
        }
        return out;
    }

    public static final BasicHud HUD = new BasicHud("melody_lost", "Time Lost To Melody", 10, 520) {
        private List<String> lines() {
            boolean editor = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
            return editor ? List.of("S1: 4.50s", "S2: 0.00s", "S3: 2.15s", "S4: 0.00s", "Total: 6.65s") : hudLines();
        }

        @Override
        public int width() {
            int w = 20;
            for (String l : lines()) {
                w = Math.max(w, Minecraft.getInstance().font.width(l));
            }
            return w;
        }

        @Override
        public int height() {
            return Math.max(9, lines().size() * 9);
        }

        @Override
        public boolean isEnabledInSettings() {
            return TerminalTimersConfig.getInstance().isMelodyLostHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!isEnabledInSettings()) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            int row = 0;
            for (String l : lines()) {
                int c = l.startsWith("Total") ? ThemeAccent.onDark(ThemeAccent.light(0xFFFFA040)) : ThemeAccent.onDark(ThemeAccent.accent(0xFFCC6600));
                graphics.text(client.font, l, x, y + row * 9, 0xFF000000 | (c & 0xFFFFFF), true);
                row++;
            }
        }
    };
}
