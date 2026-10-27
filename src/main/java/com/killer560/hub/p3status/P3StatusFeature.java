package com.killer560.hub.p3status;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
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
 * <b>Status HUD</b> (Skyblocker {@code TerminalHud}, Odin {@code TerminalsStatus}): the current section's terminals,
 * device, two levers and gate, each done or not, and (Show Who Is At Each) the name of a player standing within 5 blocks
 * of an unfinished one. The section and its "(n/m)" come from {@link P3SectionTracker}, which every P3 feature shares.
 * Which objective a line finished: a device is the section's only device; a terminal or lever is the unfinished one of
 * that kind nearest the named player (Skyblocker's rule), the first unfinished one when he is not in sight. A terminal's
 * own hologram overrides that whenever it is loaded: "Inactive Terminal" is not done, "Terminal Active" is done (the
 * same stands P3 Nav reads), so a guess is corrected as soon as the stand is in range.
 * <p>
 * <b>Titles</b> (Odin {@code TerminalTitles}, NoammAddons {@code F7Titles}): Hypixel's "Name activated a terminal!
 * (3/7)" title or subtitle is replaced by one short subtitle, "Name Terminal 3/7"; "Device Done" and "Section Complete"
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
    /** Device and lever blocks per section (Skyblocker {@code goldorwaypoints.json}). */
    private static final Vec3[] DEVICES = {
            new Vec3(110.5, 121.5, 91.5), new Vec3(60.5, 132.5, 143.5), new Vec3(0.5, 120.5, 77.5), new Vec3(63.5, 127.5, 35.5)};
    private static final Vec3[][] LEVERS = {
            {new Vec3(106.5, 124.5, 113.5), new Vec3(94.5, 124.5, 113.5)},
            {new Vec3(27.5, 124.5, 127.5), new Vec3(23.5, 132.5, 138.5)},
            {new Vec3(14.5, 122.5, 55.5), new Vec3(2.5, 122.5, 55.5)},
            {new Vec3(86.5, 128.5, 46.5), new Vec3(84.5, 121.5, 34.5)}};

    private static final double STAND_MATCH_SQ = 2.0;
    private static final double AT_RANGE_SQ = 25.0;

    public static final HudElement HUD = new StatusHud();

    // Per-run state, cleared when P3SectionTracker's generation changes.
    private static int generation = Integer.MIN_VALUE;
    private static final boolean[][] TERMINAL_DONE = new boolean[4][];
    private static final boolean[] DEVICE_DONE = new boolean[4];
    private static final boolean[][] LEVER_DONE = new boolean[4][2];
    /** Who stands at each unfinished objective of the current section, refreshed each tick ("" = nobody). */
    private static String[] terminalAt = new String[0];
    private static String deviceAt = "";
    private static final String[] LEVER_AT = {"", ""};
    private static long bigTitleAtMs;

    // Test hooks.
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

    // ------------------------------------------------------------------------------------------------- tracking

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
        if (section < 1 || section > 4) {
            return;
        }
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
            if (done[i]) {
                continue;
            }
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
        if (client.level == null) {
            return null;
        }
        for (AbstractClientPlayer p : client.level.players()) {
            if (name.equals(p.getGameProfile().name())) {
                return p.position();
            }
        }
        return null;
    }

    private static void tick(Minecraft client) {
        P3SectionTracker.poll(client);
        syncRun();
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isShowHud() || client.level == null || !DungeonState.isF7OrM7() || !P3SectionTracker.running()) {
            return;
        }
        int s = P3SectionTracker.section() - 1;
        if (s < 0 || s > 3) {
            return;
        }
        // Terminal holograms are the truth wherever they are loaded.
        Vec3[] terms = TERMINALS[s];
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || stand.getCustomName() == null) {
                continue;
            }
            String n = ChatObserver.strip(stand.getCustomName());
            boolean inactive = "Inactive Terminal".equals(n);
            if (!inactive && !"Terminal Active".equals(n)) {
                continue;
            }
            for (int i = 0; i < terms.length; i++) {
                if (stand.position().distanceToSqr(terms[i]) <= STAND_MATCH_SQ) {
                    TERMINAL_DONE[s][i] = !inactive;
                }
            }
        }
        boolean who = cfg.isShowWhoIsAt();
        if (terminalAt.length != terms.length) {
            terminalAt = new String[terms.length];
        }
        for (int i = 0; i < terms.length; i++) {
            terminalAt[i] = who && !TERMINAL_DONE[s][i] ? nameNear(client, terms[i]) : "";
        }
        deviceAt = who && !DEVICE_DONE[s] ? nameNear(client, DEVICES[s]) : "";
        for (int i = 0; i < 2; i++) {
            LEVER_AT[i] = who && !LEVER_DONE[s][i] ? nameNear(client, LEVERS[s][i]) : "";
        }
    }

    private static String nameNear(Minecraft client, Vec3 spot) {
        for (AbstractClientPlayer p : client.level.players()) {
            if (p.position().distanceToSqr(spot) < AT_RANGE_SQ) {
                String n = p.getGameProfile().name();
                return n.length() > 12 ? n.substring(0, 12) : n;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------------------------------------- titles

    /** From the title mixin: true cancels Hypixel's title/subtitle (a replacement may already have been shown). */
    public static boolean onTitle(Component text, boolean subtitle) {
        if (text == null) {
            return false;
        }
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isF7OrM7()) {
            return false;
        }
        String plain = ChatObserver.strip(text).trim();
        if (plain.isEmpty() || plain.length() > 120) {
            return false;
        }
        if (subtitle && cfg.isHideLaserSubtitle() && LASER_CHARGING.equals(plain)) {
            dropped++;
            return true;
        }
        if (!cfg.isRewriteTitles()) {
            return false;
        }
        Matcher m = COMPLETION.matcher(plain);
        if (!m.matches()) {
            return false;
        }
        String kind = m.group(2);
        String label = switch (kind) {
            case "terminal" -> "§5Terminal";
            case "device" -> "§bDevice";
            default -> "§cLever";
        };
        int cur = parse(m.group(3));
        int tot = parse(m.group(4));
        String line = "§e" + m.group(1) + " " + label + " " + (cur == tot ? "§a" : "§c") + cur + "§7/§a" + tot;
        Minecraft client = Minecraft.getInstance();
        if (!subtitle && System.currentTimeMillis() - bigTitleAtMs > 1500) {
            // Hypixel put the line in the TITLE slot: keep an (empty) title up so the subtitle draws.
            McCompat.setTitle(client, Component.empty());
        }
        McCompat.setSubtitle(client, Component.literal(line));
        lastReplacement = ChatObserver.strip(line);
        replaced++;
        return true;
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

    // ------------------------------------------------------------------------------------------------- HUD

    /** The rows the HUD draws now (the editor's sample in the HUD editor). */
    static List<String> lines() {
        Minecraft client = Minecraft.getInstance();
        if (McCompat.screen(client) instanceof HudEditorScreen) {
            return List.of("§6P3 Section 2 §7(§e3§7/§a8§7)", "§fTerminal 1 §a✔", "§fTerminal 2 §eHxMateA",
                    "§fTerminal 3 §c✘", "§fDevice §a✔", "§fLever 1 §c✘", "§fLever 2 §a✔", "§fGate §c✘");
        }
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        if (!cfg.isShowHud() || !DungeonState.isF7OrM7() || !P3SectionTracker.running()) {
            return List.of();
        }
        int section = P3SectionTracker.section();
        int s = section - 1;
        if (s < 0 || s > 3) {
            return List.of();
        }
        List<String> out = new ArrayList<>(10);
        int cur = P3SectionTracker.sectionCurrent();
        int tot = P3SectionTracker.sectionTotal();
        out.add("§6P3 Section " + section + (tot > 0 ? " §7(§e" + cur + "§7/§a" + tot + "§7)" : ""));
        for (int i = 0; i < TERMINALS[s].length; i++) {
            out.add("§fTerminal " + (i + 1) + " " + status(TERMINAL_DONE[s][i], i < terminalAt.length ? terminalAt[i] : ""));
        }
        out.add("§fDevice " + status(DEVICE_DONE[s], deviceAt));
        for (int i = 0; i < 2; i++) {
            out.add("§fLever " + (i + 1) + " " + status(LEVER_DONE[s][i], LEVER_AT[i]));
        }
        if (section <= 3) {
            out.add("§fGate " + (P3SectionTracker.gateDestroyed() ? "§a✔" : "§c✘"));
        }
        return out;
    }

    private static String status(boolean done, String at) {
        if (done) {
            return "§a✔";
        }
        return at == null || at.isEmpty() ? "§c✘" : "§e" + at;
    }

    private static final class StatusHud implements HudElement {

        @Override
        public String id() {
            return "p3_status";
        }

        @Override
        public String displayName() {
            return "P3 Status";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 480;
        }

        @Override
        public int width() {
            return HudText.width(lines());
        }

        @Override
        public int height() {
            return HudText.height(lines().size(), 10);
        }

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

    // ------------------------------------------------------------------------------------------------- test hooks

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
    public static String testLastDrawn() {
        return lastDrawn;
    }

    public static String testLastReplacement() {
        return lastReplacement;
    }

    public static String testLastBigTitle() {
        return lastBigTitle;
    }

    public static long testReplaced() {
        return replaced;
    }

    public static long testDropped() {
        return dropped;
    }
}
