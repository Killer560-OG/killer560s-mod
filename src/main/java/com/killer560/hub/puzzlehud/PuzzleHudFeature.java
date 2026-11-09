package com.killer560.hub.puzzlehud;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puzzle HUD (Odin PuzzleHud, Devonian PuzzlesDisplay and PuzzleTimers): the dungeon's puzzles with their state, who solved
 * or failed each and how long it took. State is the tab list's puzzle rows (the same lines Score Calculator reads,
 * {@link ScoreCalculatorFeature#TAB_PUZZLE}), polled twice a second only inside a dungeon with the switch on; who comes from
 * Hypixel's {@code PUZZLE SOLVED!} / {@code PUZZLE FAIL!} lines. A puzzle's clock starts when the tab list first names it and
 * stops when it turns done or failed. The drawn rows are rebuilt on each poll and cached, so a frame only draws text.
 */
public final class PuzzleHudFeature {

    public static final HudElement HUD = new Hud();

    private static final Pattern COUNT = Pattern.compile("^\\s*Puzzles: \\((\\d+)\\)\\s*$");
    private static final Pattern SOLVED = Pattern.compile("^PUZZLE SOLVED! (?:\\[[^\\]]{1,20}\\] )?([A-Za-z0-9_]{1,16}) ([^!]{1,80})! Good job!$");
    private static final Pattern FAILED = Pattern.compile("^PUZZLE FAIL! (?:\\[[^\\]]{1,20}\\] )?([A-Za-z0-9_]{1,16}) ");
    private static final int MAX_PUZZLES = 12;
    private static final long WHO_WINDOW_MS = 15_000L;
    private static final char DONE = '✔';
    private static final char FAIL = '✖';
    private static final char OPEN = '✦';

    private static final class Puzzle {
        final String name;
        char state = OPEN;
        /** 0 until the tab list names the puzzle (someone opened it); a Funny Map puzzle waits at 0:00 meanwhile. */
        long start;
        long end;
        String who;

        Puzzle(String name, long start) {
            this.name = name;
            this.start = start;
        }
    }

    /** Drawn text: a header and rows, measured once when built. */
    private record Block(String header, List<String> rows, int w, int h) {
        static Block of(String header, List<String> rows) {
            Minecraft mc = Minecraft.getInstance();
            int w = mc.font.width(header);
            for (String r : rows) {
                w = Math.max(w, mc.font.width(r));
            }
            return new Block(header, List.copyOf(rows), w, HudText.height(rows.size() + 1, HudText.ROW));
        }
    }

    private record Recent(String name, String text, long ms) {
    }

    private static final Map<String, Puzzle> PUZZLES = new LinkedHashMap<>();
    private static final ArrayDeque<Recent> SOLVES = new ArrayDeque<>();
    private static Recent lastFail;
    private static int total;
    private static int ticks;
    private static Object lastLevel;
    private static volatile Block current;
    private static Block editorBlock;

    private PuzzleHudFeature() {
    }

    public static void register() {
        PuzzleHudConfig.getInstance();
        ChatObserver.subscribe(m -> onLine(ChatObserver.strip(m), System.currentTimeMillis()));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PuzzleHudFeature", PuzzleHudFeature::tick));
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            clear();
        }
        if (!PuzzleHudConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            if (current != null) {
                clear();
            }
            return;
        }
        if (++ticks % 10 != 0 || client.getConnection() == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = TabText.plain(info);
            if (plain != null && plain.contains(":")) {
                lines.add(plain);
            }
        }
        long now = System.currentTimeMillis();
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED
                && com.killer560.hub.livemap.LiveMapConfig.getInstance().isFunnyMap()) {
            seedFromMap(com.killer560.hub.livemap.LiveMapFeature.identifiedPuzzleNames(), now);
        }
        poll(lines, now);
    }

    /** Funny Map has identified the dungeon's puzzle rooms: list each at once, its clock waiting at 0:00 until the tab
     *  list names it (killer560, 2026-11-09). Public so the testkit can feed the names. */
    public static synchronized void seedFromMap(List<String> names, long now) {
        boolean added = false;
        for (String name : names) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (!PUZZLES.containsKey(key) && PUZZLES.size() < MAX_PUZZLES) {
                PUZZLES.put(key, new Puzzle(name.trim(), 0L));
                added = true;
            }
        }
        if (added) {
            rebuild(now);
        }
    }

    private static synchronized void clear() {
        PUZZLES.clear();
        SOLVES.clear();
        lastFail = null;
        total = 0;
        current = null;
    }

    /** Tab list lines of one poll; public so the testkit can feed real tab lines. */
    public static synchronized void poll(List<String> tabLines, long now) {
        boolean sawAny = false;
        for (String plain : tabLines) {
            Matcher m = COUNT.matcher(plain);
            if (m.matches()) {
                total = Integer.parseInt(m.group(1));
                sawAny = true;
                continue;
            }
            m = ScoreCalculatorFeature.TAB_PUZZLE.matcher(plain);
            if (!m.matches() || "???".equals(m.group(1))) {
                continue;
            }
            sawAny = true;
            String key = m.group(1).trim().toLowerCase(Locale.ROOT);
            Puzzle p = PUZZLES.get(key);
            if (p == null && PUZZLES.size() < MAX_PUZZLES) {
                p = new Puzzle(m.group(1).trim(), now);
                PUZZLES.put(key, p);
            }
            if (p == null) {
                continue;
            }
            if (p.start == 0L) {
                p.start = now; // a puzzle the map listed first: its clock starts when the tab list names it
            }
            char s = m.group(2).charAt(0);
            if (p.state != s) {
                p.state = s;
                p.end = s == OPEN ? 0L : now;
                p.who = null;
            }
            if (m.group(3) != null) {
                p.who = m.group(3);
            }
        }
        if (sawAny) {
            rebuild(now);
        }
    }

    /** One chat line (stripped); public so the testkit can feed real chat lines. */
    public static synchronized void onLine(String plain, long now) {
        if (plain.isEmpty() || !PuzzleHudConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        Matcher m = SOLVED.matcher(plain);
        if (m.matches()) {
            SOLVES.addLast(new Recent(m.group(1), m.group(2).toLowerCase(Locale.ROOT), now));
            if (SOLVES.size() > 8) {
                SOLVES.removeFirst();
            }
        } else if ((m = FAILED.matcher(plain)).find()) {
            lastFail = new Recent(m.group(1), plain.toLowerCase(Locale.ROOT), now);
        } else {
            return;
        }
        rebuild(now);
    }

    /** Who solved or failed a puzzle the tab list gave no name for: the matching line within the window of the state change. */
    private static String whoFor(String key, Puzzle p) {
        if (p.state == DONE) {
            for (Recent r : SOLVES) {
                if (r.text().contains(key) && Math.abs(r.ms() - p.end) < WHO_WINDOW_MS) {
                    return r.name();
                }
            }
        } else if (p.state == FAIL && lastFail != null && Math.abs(lastFail.ms() - p.end) < WHO_WINDOW_MS) {
            return lastFail.name();
        }
        return null;
    }

    private static void rebuild(long now) {
        PuzzleHudConfig cfg = PuzzleHudConfig.getInstance();
        List<String> rows = new ArrayList<>();
        int done = 0;
        for (Map.Entry<String, Puzzle> e : PUZZLES.entrySet()) {
            Puzzle p = e.getValue();
            if (p.who == null && p.state != OPEN) {
                p.who = whoFor(e.getKey(), p);
            }
            done += p.state == DONE ? 1 : 0;
            StringBuilder sb = new StringBuilder(p.state == DONE ? "§a" : p.state == FAIL ? "§c" : "§e");
            sb.append(p.state).append(' ').append(p.name);
            if (cfg.isShowWho() && p.who != null) {
                sb.append(" §7").append(p.who);
            }
            if (cfg.isShowTimes()) {
                sb.append(" §f").append(time(p.start == 0L ? 0L : (p.end > 0L ? p.end : now) - p.start));
            }
            rows.add(sb.toString());
        }
        current = Block.of("Puzzles " + done + "/" + Math.max(total, PUZZLES.size()), rows);
    }

    private static String time(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** The rows now drawn, for the testkit. */
    public static List<String> rowsForTest() {
        Block b = current;
        return b == null ? List.of() : b.rows();
    }

    public static String headerForTest() {
        Block b = current;
        return b == null ? "" : b.header();
    }

    private static final class Hud extends BasicHud {
        Hud() {
            super("puzzle_hud", "Puzzle HUD", 200, 120);
        }

        private Block shown() {
            if (McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen) {
                if (editorBlock == null) {
                    editorBlock = Block.of("Puzzles 1/3", List.of("§a" + DONE + " Water Board §7Steve §f1:12",
                            "§e" + OPEN + " Creeper Beams §f0:41", "§c" + FAIL + " Boulder §7Alex §f2:05"));
                }
                return editorBlock;
            }
            return current;
        }

        @Override
        public int width() {
            Block b = shown();
            return b == null ? 1 : b.w();
        }

        @Override
        public int height() {
            Block b = shown();
            return b == null ? HudText.ROW : b.h();
        }

        @Override
        public boolean isEnabledInSettings() {
            return PuzzleHudConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Block b = shown();
            if (b == null) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            graphics.text(client.font, b.header(), x, y, ThemeAccent.accent(0xFFFFAA00), true);
            for (int i = 0; i < b.rows().size(); i++) {
                graphics.text(client.font, b.rows().get(i), x, y + (i + 1) * HudText.ROW, 0xFFFFFFFF, true);
            }
            HudSeen.markDrawn(id());
        }
    }
}
