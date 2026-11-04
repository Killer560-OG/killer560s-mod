package com.killer560.hub.p3status;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.F7HudSlots;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.leveraura.P3SectionTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3 Status (2026-10-25, legit): F7/M7 Phase 3 at a glance, and Hypixel's progress titles made short.
 * <p>
 * <b>Status HUD</b> (Skyblocker {@code TerminalHud}, Odin {@code TerminalsStatus}): the current section's progress as
 * four short rows, "Terminals 2/4" (5 in section 2), "Device 0/1", "Levers 1/2" and, in sections 1-3, "Gate" with a mark
 * (killer560, 2026-11-02: "remove all that. it should just be terminal 0/4, device 0/1, lever 0/2 and such" - the per-row
 * list with names and Show Who Is At Each went). The section comes from {@link P3SectionTracker}, which every P3 feature
 * shares.
 * Which objective a line finished: a device is the section's only device; a terminal or lever is the unfinished one of
 * that kind nearest the named player (Skyblocker's rule), the first unfinished one when he is not in sight. A terminal's
 * own hologram overrides that whenever it is loaded: "Inactive Terminal" is not done, "Terminal Active" is done (the
 * same stands P3 Nav reads), so a guess is corrected as soon as the stand is in range.
 * <p>
 * <b>Titles</b> (Odin {@code TerminalTitles}, NoammAddons {@code F7Titles}): Hypixel's "Name activated a terminal!
 * (3/7)" title or subtitle is replaced by one short subtitle, "Name Terminal 3/7": the name in that player's dungeon class
 * color ({@code PartyTracker.classOf}, white when unknown) and "Terminal 3/7" in gold (killer560, 2026-11-02); "Device Done" and "Section Complete"
 * are shown as titles (with a pling when Title Sound is on) off the chat lines; and the P1 "The Energy Laser is charging
 * up!" subtitle can be dropped. Hooked in {@code terminals/mixin/TerminalQolTitleMixin} (Gui.setTitle/setSubtitle, both
 * version copies), after Terminal QoL's Hide Completion, which still wins when it is on. Only lines whose name group is a
 * real username are touched ({@code [A-Za-z0-9_]{1,16}}), only in F7/M7.
 */
public final class P3StatusFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-p3status");

    static final Pattern COMPLETION = Pattern.compile(
            "^([A-Za-z0-9_]{1,16}) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.{0,60})?$");
    static final String LASER_CHARGING = "The Energy Laser is charging up!";

    /** Terminal hologram positions per section (P3 Nav's NoammAddons table), numbered as Skyblocker numbers them. */
    private static final Vec3[][] TERMINALS = {
            {new Vec3(110, 113, 73), new Vec3(110, 119, 79), new Vec3(90, 112, 92), new Vec3(90, 122, 101)},
            {new Vec3(68, 109, 122), new Vec3(59, 119, 123), new Vec3(47, 109, 122), new Vec3(40, 124, 123),
                    new Vec3(39, 108, 142)},
            {new Vec3(-2, 109, 112), new Vec3(-2, 119, 93), new Vec3(18, 123, 93), new Vec3(-2, 109, 77)},
            {new Vec3(41, 109, 30), new Vec3(44, 121, 30), new Vec3(67, 109, 30), new Vec3(72, 114, 47)}};
    private static final Vec3[][] LEVERS = {
            {new Vec3(106.5, 124.5, 113.5), new Vec3(94.5, 124.5, 113.5)},
            {new Vec3(27.5, 124.5, 127.5), new Vec3(23.5, 132.5, 138.5)},
            {new Vec3(14.5, 122.5, 55.5), new Vec3(2.5, 122.5, 55.5)},
            {new Vec3(86.5, 128.5, 46.5), new Vec3(84.5, 121.5, 34.5)}};

    private static final double STAND_MATCH_SQ = 2.0;
    /** The fixed color of "Terminal 3/7" in the short title (killer560, 2026-11-02: "the terminal x/x line one color"). */
    static final int TITLE_COUNT_RGB = 0xFFAA00;
    /** The name's color when the player's class is not known. */
    static final int TITLE_UNKNOWN_RGB = 0xFFFFFF;

    public static final HudElement HUD = new StatusHud();

    // Per-run state, cleared when P3SectionTracker's generation changes.
    private static int generation = Integer.MIN_VALUE;
    private static final boolean[][] TERMINAL_DONE = new boolean[4][];
    private static final boolean[] DEVICE_DONE = new boolean[4];
    private static final boolean[][] LEVER_DONE = new boolean[4][2];
    private static long bigTitleAtMs;

    // Test hooks.
    private static volatile Component lastReplacementComponent = Component.empty();
    private static volatile String lastReplacement = "";
    private static volatile String lastBigTitle = "";
    private static long replaced;
    private static long dropped;

    static {
        for (int i = 0; i < 4; i++) {
            TERMINAL_DONE[i] = new boolean[TERMINALS[i].length];
        }
    }

    private P3StatusFeature() {
    }

    public static void register() {
        P3StatusConfig.getInstance();
        P3SectionTracker.register();
        P3SectionTracker.addObjectiveListener(P3StatusFeature::onObjective);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("P3StatusFeature.tick", P3StatusFeature::tick));
    }


    private static void syncRun() {
        int g = P3SectionTracker.generation();
        if (g != generation) {
            generation = g;
            for (int i = 0; i < 4; i++) {
                java.util.Arrays.fill(TERMINAL_DONE[i], false);
                java.util.Arrays.fill(LEVER_DONE[i], false);
                DEVICE_DONE[i] = false;
            }
        }
    }

    private static void onObjective(int section, String name, String kind, int current, int total) {
        if (section < 1 || section > 4) return;
        syncRun();
        int s = section - 1;
        Vec3 at = playerPos(name);
        switch (kind) {
            case "device" -> DEVICE_DONE[s] = true;
            case "terminal" -> markNearest(TERMINALS[s], TERMINAL_DONE[s], at);
            case "lever" -> markNearest(LEVERS[s], LEVER_DONE[s], at);
            default -> {
            }
        }
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (total > 0 && current == total && cfg.isSectionCompleteTitle()) {
            bigTitle("§a§lSection Complete");
        } else if ("device".equals(kind) && cfg.isDeviceDoneTitle()) {
            bigTitle("§b§lDevice Done");
        }
    }

    private static void markNearest(Vec3[] spots, boolean[] done, Vec3 at) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < spots.length; i++) {
            if (done[i]) continue;
            double d = at == null ? i : spots[i].distanceToSqr(at);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0) {
            done[best] = true;
        }
    }

    private static Vec3 playerPos(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        for (AbstractClientPlayer p : client.level.players()) {
            if (name.equals(p.getGameProfile().name())) return p.position();
        }
        return null;
    }

    private static void tick(Minecraft client) {
        P3SectionTracker.poll(client);
        syncRun();
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isShowHud() || client.level == null || !DungeonState.isF7OrM7() || !P3SectionTracker.running()) return;
        int s = P3SectionTracker.section() - 1;
        if (s < 0 || s > 3) return;
        // Terminal holograms are the truth wherever they are loaded.
        Vec3[] terms = TERMINALS[s];
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || stand.getCustomName() == null) continue;
            String n = ChatObserver.strip(stand.getCustomName());
            boolean inactive = "Inactive Terminal".equals(n);
            if (!inactive && !"Terminal Active".equals(n)) continue;
            for (int i = 0; i < terms.length; i++) {
                if (stand.position().distanceToSqr(terms[i]) <= STAND_MATCH_SQ) {
                    TERMINAL_DONE[s][i] = !inactive;
                }
            }
        }
    }


    /** From the title mixin: true cancels Hypixel's title/subtitle (a replacement may already have been shown). */
    public static boolean onTitle(Component text, boolean subtitle) {
        if (text == null) return false;
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isF7OrM7()) return false;
        String plain = ChatObserver.strip(text).trim();
        if (plain.isEmpty() || plain.length() > 120) return false;
        if (subtitle && cfg.isHideLaserSubtitle() && LASER_CHARGING.equals(plain)) {
            dropped++;
            return true;
        }
        if (!cfg.isRewriteTitles()) return false;
        Matcher m = COMPLETION.matcher(plain);
        if (!m.matches()) return false;
        Component line = shortTitle(m.group(1), m.group(2), parse(m.group(3)), parse(m.group(4)));
        Minecraft client = Minecraft.getInstance();
        if (!subtitle && System.currentTimeMillis() - bigTitleAtMs > 1500) {
            // Hypixel put the line in the TITLE slot: keep an (empty) title up so the subtitle draws.
            McCompat.setTitle(client, Component.empty());
        }
        McCompat.setSubtitle(client, line);
        lastReplacementComponent = line;
        lastReplacement = ChatObserver.strip(line);
        replaced++;
        return true;
    }

    /** "Name Terminal 3/7": the name in the player's class color, the rest in one fixed gold. */
    static Component shortTitle(String name, String kind, int cur, int tot) {
        DungeonClass cls = PartyTracker.classOf(name);
        int nameRgb = cls == null ? TITLE_UNKNOWN_RGB : cls.color() & 0xFFFFFF;
        String label = switch (kind) {
            case "terminal" -> "Terminal";
            case "device" -> "Device";
            default -> "Lever";
        };
        return Component.empty()
                .append(Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(nameRgb))))
                .append(Component.literal(" " + label + " " + cur + "/" + tot)
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(TITLE_COUNT_RGB))));
    }

    private static void bigTitle(String text) {
        Minecraft client = Minecraft.getInstance();
        McCompat.setTimes(client, 5, 30, 10);
        McCompat.setTitle(client, Component.literal(text));
        bigTitleAtMs = System.currentTimeMillis();
        lastBigTitle = ChatObserver.strip(text);
        LOGGER.info("[P3Status] title: {}", lastBigTitle);
        if (P3StatusConfig.getInstance().isTitleSound()) {
            DungeonAlertsFeature.playSound(SecretSound.SoundChoice.PLING.sound(), 1.0f, 1.6f);
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }


    /** The rows the HUD draws now (the editor's sample in the HUD editor). */
    static List<String> lines() {
        Minecraft client = Minecraft.getInstance();
        if (McCompat.screen(client) instanceof HudEditorScreen) {
            return List.of("§fTerminals §e2§7/§a5", "§fDevice §a1§7/§a1", "§fLevers §e1§7/§a2", "§fGate §c✘");
        }
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isShowHud() || !DungeonState.isF7OrM7() || !P3SectionTracker.running()) return List.of();
        int section = P3SectionTracker.section();
        int s = section - 1;
        if (s < 0 || s > 3) return List.of();
        List<String> out = new ArrayList<>(4);
        out.add("§fTerminals " + count(TERMINAL_DONE[s]));
        out.add("§fDevice " + count(new boolean[]{DEVICE_DONE[s]}));
        out.add("§fLevers " + count(LEVER_DONE[s]));
        if (section <= 3) {
            out.add("§fGate " + (P3SectionTracker.gateDestroyed() ? "§a✔" : "§c✘"));
        }
        return out;
    }

    /** "done/total", green when all are done. */
    private static String count(boolean[] done) {
        int n = 0;
        for (boolean d : done) {
            if (d) {
                n++;
            }
        }
        return (n == done.length ? "§a" : "§e") + n + "§7/§a" + done.length;
    }

    private static final class StatusHud implements HudElement {

        @Override
        public String id() { return "p3_status"; }

        @Override
        public String displayName() { return "P3 Status"; }

        @Override
        public int defaultX() { return F7HudSlots.x(F7HudSlots.P3_STATUS); }

        @Override
        public int defaultY() { return F7HudSlots.y(F7HudSlots.P3_STATUS); }

        @Override
        public int width() { return HudText.width(lines()); }

        @Override
        public int height() { return HudText.height(lines().size(), 10); }

        @Override
        public boolean isEnabledInSettings() {
            P3StatusConfig cfg = P3StatusConfig.getInstance();
            return cfg.getEnabledRaw() && cfg.getShowHudRaw();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            List<String> rows = lines();
            for (int row = 0; row < rows.size(); row++) {
                graphics.text(client.font, rows.get(row), x, y + row * 10, 0xFFFFFFFF, true);
            }
            if (!rows.isEmpty() && !(McCompat.screen(client) instanceof HudEditorScreen)) {
                HudSeen.markDrawn(id());
                lastDrawn = String.join("\n", rows);
            }
        }
    }


    private static volatile String lastDrawn = "";

    /** The HUD's rows as plain text, as {@link #lines()} builds them now. Render thread. */
    public static List<String> testLines() {
        List<String> out = new ArrayList<>();
        for (String l : lines()) {
            out.add(ChatObserver.strip(l));
        }
        return out;
    }

    /** What the HUD last really drew (rows joined by newlines, with formatting codes). */
    public static String testLastDrawn() { return lastDrawn; }

    /** The last short title as built, with its styles (the name's class color, the gold count). */
    public static Component testLastReplacementComponent() { return lastReplacementComponent; }

    public static String testLastReplacement() { return lastReplacement; }

    public static String testLastBigTitle() { return lastBigTitle; }

    public static long testReplaced() { return replaced; }

    public static long testDropped() { return dropped; }
}
