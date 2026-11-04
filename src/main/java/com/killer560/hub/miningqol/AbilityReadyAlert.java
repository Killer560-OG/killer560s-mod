package com.killer560.hub.miningqol;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ability Ready Alert (legit, both builds; killer560 2026-11-01: "a on screen pop up whenever an ability is ready to be used
 * again with an optional timer till it comes back up").
 *
 * <p>The popup: Hypixel says {@code "Mining Speed Boost is now available!"} (green and gold; SkyHanni's ChatFilter and
 * Skyblocker's "Mining Ability Alert" chat rule key on the same line) the moment a pickaxe ability is ready again; this
 * shows "&lt;ability&gt; READY" large on screen for {@link #POPUP_MS} and optionally plays a sound. The pattern is anchored
 * and allows only letters, spaces and apostrophes before " is now available!", so a player's chat line ("Bob: ...") can
 * never match.
 *
 * <p>The timer: a movable HUD line per ability still cooling down. Its time comes from, in order: the tab list's "Pickaxe
 * Ability:" widget ({@code " Pickobulus: 34s"}), Hypixel's {@code "This ability is on cooldown for 34s."} reply, or the
 * cooldown this session measured between "You used your X Pickaxe Ability!" and X's "is now available!" line. With none of
 * those it shows nothing rather than a guess. The widget refreshes only every ~3 s, so a reading sets a deadline and the
 * line counts down from it every second ({@link #applyTab}); the use and ready lines are hard resets.
 */
public final class AbilityReadyAlert {

    public static final String POPUP_ID = "mining_ability_ready";
    public static final String TIMER_ID = "mining_ability_timer";
    static final long POPUP_MS = 2500;

    /** Heart of the Mountain pickaxe abilities (hypixelskyblock wiki, Heart of the Mountain, 2026-11-03: six, plus the
     *  names of earlier ones and both spellings of Pickobulus). */
    static final java.util.Set<String> MINING_ABILITIES = java.util.Set.of("Mining Speed Boost", "Pickobulus", "Pickobolus",
            "Tunnel Vision", "Maniac Miner", "Gemstone Infusion", "Sheer Force", "Vein Seeker", "Hazardous Miner",
            "Anomalous Desire");
    /** Names Hypixel called a "Pickaxe Ability" this session (a new or renamed one still counts). */
    private static final java.util.Set<String> PICKAXE_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Pattern READY = Pattern.compile("^([A-Z][A-Za-z' ]{1,30}) is now available!$");
    private static final Pattern USED = Pattern.compile("^You used your ([A-Z][A-Za-z' ]{1,30}) Pickaxe Ability!$");
    private static final Pattern COOLDOWN = Pattern.compile("^This ability is on cooldown for (\\d{1,4})s\\.$");
    /** " Pickobulus: 34s", "Mining Speed Boost: 1m 5s", "Pickobulus: Available". */
    private static final Pattern TAB = Pattern.compile("^([A-Z][A-Za-z' ]{1,30}): (?:Available|(?:(\\d{1,2})m ?)?(\\d{1,3})s)$");

    /** Ability -> wall-clock ms it is ready again (estimated). Client thread only. */
    private static final Map<String, Long> READY_AT = new LinkedHashMap<>();
    /** Ability -> when "You used your X" was seen; ability -> measured cooldown ms. */
    private static final Map<String, Long> USED_AT = new LinkedHashMap<>();
    private static final Map<String, Long> LEARNED = new LinkedHashMap<>();
    /** Ability -> the tab line last acted on. A reading is acted on only when it CHANGES, so the widget's ~3 s refresh
     *  never holds the countdown still (killer560, 2026-11-03: "only counts down every 3s"). */
    private static final Map<String, String> LAST_TAB = new LinkedHashMap<>();
    /** A new tab reading moves the deadline only when it disagrees by more than this (the widget rounds to a second). */
    static final long RESYNC_MS = 1500;
    private static String lastUsed = null;

    private static volatile String popupText = null;
    private static volatile long popupUntil = 0;
    private static volatile List<String> timerLines = List.of();
    private static int popupsShown = 0;
    private static int popupFrames = 0;

    private AbilityReadyAlert() {
    }

    static void register() { ChatObserver.subscribe(AbilityReadyAlert::onChat); }

    static void onChat(Component message) {
        if (!MiningQolConfig.getInstance().isAbilityAlert()) {
            return;
        }
        String raw = ChatObserver.strip(message);
        if (raw == null || raw.length() > 80) {
            return;
        }
        handle(raw, System.currentTimeMillis());
    }

    private static void handle(String raw, long now) {
        Matcher m = READY.matcher(raw);
        if (m.matches()) {
            // Mining abilities only (killer560, 2026-11-03: "only pick up mining abilities not other stuff"): a pickaxe
            // ability by name, or one this session saw used as a "Pickaxe Ability".
            String name = m.group(1).trim();
            if (MINING_ABILITIES.contains(name) || PICKAXE_SEEN.contains(name)) {
                onReady(name, now);
            }
            return;
        }
        m = USED.matcher(raw);
        if (m.matches()) {
            String name = m.group(1).trim();
            PICKAXE_SEEN.add(name);
            USED_AT.put(name, now);
            lastUsed = name;
            Long learned = LEARNED.get(name);
            if (learned != null) {
                READY_AT.put(name, now + learned);
            }
            // The tab still shows the old "Available" for a few seconds: only its next change counts.
            LAST_TAB.put(name, currentTab(name));
            return;
        }
        m = COOLDOWN.matcher(raw);
        if (m.matches() && lastUsed != null) {
            READY_AT.put(lastUsed, now + Long.parseLong(m.group(1)) * 1000L);
        }
    }

    private static void onReady(String name, long now) {
        Long used = USED_AT.remove(name);
        if (used != null && now - used > 1000 && now - used < 10 * 60_000L) {
            LEARNED.put(name, now - used);
        }
        READY_AT.remove(name);
        // A hard reset: the tab may still show "3s" for a few seconds; only its next change counts.
        LAST_TAB.put(name, currentTab(name));
        popupText = "§a§l" + name + " READY";
        popupUntil = now + POPUP_MS;
        popupsShown++;
        if (MiningQolConfig.getInstance().isAbilitySound()) {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.8f));
        }
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isAbilityAlert() || !cfg.isAbilityTimer()) {
            timerLines = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        applyTab(MiningState.pickaxeAbilityLines(), now);
        timerLines = render(now);
    }

    /** The lines at {@code now}: ceil(deadline - now), never negative; a finished one leaves (the popup says READY). */
    private static List<String> render(long now) {
        List<String> out = new ArrayList<>();
        READY_AT.entrySet().removeIf(e -> e.getValue() <= now);
        for (Map.Entry<String, Long> e : READY_AT.entrySet()) {
            long left = Math.max(0, (e.getValue() - now + 999) / 1000);
            out.add("§6" + e.getKey() + ": §e" + (left >= 60 ? (left / 60) + "m " + (left % 60) + "s" : left + "s"));
        }
        return List.copyOf(out);
    }

    /**
     * The tab widget is the server's own number, but Hypixel refreshes it only every ~3 s. Each reading that CHANGED
     * sets a local deadline (reading + the moment it changed); the shown time counts down from that every second, and a
     * later reading moves the deadline only when it disagrees by more than {@link #RESYNC_MS}, so a stale read can never
     * make it jump back up. "Available" ends it.
     */
    static void applyTab(List<String> lines, long now) {
        for (String line : lines) {
            Matcher m = TAB.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).trim();
            if (line.equals(LAST_TAB.get(name))) {
                continue;
            }
            LAST_TAB.put(name, line);
            if (m.group(3) == null) {
                READY_AT.remove(name);
            } else {
                long s = Long.parseLong(m.group(3)) + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)) * 60);
                long deadline = now + s * 1000L;
                Long cur = READY_AT.get(name);
                if (cur == null || Math.abs(cur - deadline) > RESYNC_MS) {
                    READY_AT.put(name, deadline);
                }
            }
        }
    }

    private static List<String> testTab = null;

    private static String currentTab(String name) {
        List<String> lines = testTab != null ? testTab : MiningState.pickaxeAbilityLines();
        for (String line : lines) {
            if (line.startsWith(name + ": ")) {
                return line;
            }
        }
        return null;
    }

    static void reset() {
        LAST_TAB.clear();
        READY_AT.clear();
        USED_AT.clear();
        lastUsed = null;
        popupText = null;
        timerLines = List.of();
    }


    public static String popup() { return System.currentTimeMillis() < popupUntil ? popupText : null; }

    public static int popupsShown() { return popupsShown; }

    public static int popupFrames() { return popupFrames; }

    public static List<String> timer() { return timerLines; }

    /** Testkit: feeds tab lines at a given wall-clock time and returns the timer lines at {@code readAt}. */
    public static List<String> timerAtForTest(List<String> tab, long now, long readAt) {
        applyTab(tab, now);
        return render(readAt);
    }

    /** Testkit: a chat line at {@code now} (used / ready / cooldown), as Hypixel would send it. */
    public static void chatForTest(String raw, List<String> tabNow, long now) {
        testTab = tabNow;
        try {
            handle(raw, now);
        } finally {
            testTab = null;
        }
    }

    public static void resetForTest() {
        reset();
        LEARNED.clear();
    }

    public static Long learnedMs(String name) { return LEARNED.get(name); }

    /** The big "X READY" popup, centered by default; movable in the HUD editor. */
    public static final class Popup implements HudElement {

        private static final float SCALE = 2.0f;

        private String shown() {
            String p = popup();
            return p == null && HudVisibility.editorOpen() ? "§a§lPickobulus READY" : p;
        }

        @Override
        public String id() { return POPUP_ID; }

        @Override
        public String displayName() { return "Ability Ready Popup"; }

        @Override
        public int defaultX() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 200 : mc.getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 80 : mc.getWindow().getGuiScaledHeight() / 3;
        }

        @Override
        public int width() {
            String s = shown();
            return (int) (Minecraft.getInstance().font.width(s == null ? "§a§lPickobulus READY" : s) * SCALE);
        }

        @Override
        public int height() { return (int) (10 * SCALE); }

        @Override
        public boolean isEnabledInSettings() { return MiningQolConfig.getInstance().isAbilityAlertRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MiningQolConfig.getInstance().isAbilityAlert() || HudVisibility.hidesHud()) {
                return;
            }
            String s = shown();
            if (s == null) {
                return;
            }
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(SCALE, SCALE);
            graphics.text(Minecraft.getInstance().font, s, 0, 0, 0xFFFFFFFF, true);
            pose.popMatrix();
            if (popup() != null) {
                popupFrames++;
                HudSeen.markDrawn(POPUP_ID);
            }
        }
    }

    /** The cooldown lines. */
    public static final class Timer implements HudElement {

        private List<String> shown() {
            List<String> l = timerLines;
            if (l.isEmpty() && HudVisibility.editorOpen()) {
                return List.of("§6Pickobulus: §e34s");
            }
            return l;
        }

        @Override
        public String id() { return TIMER_ID; }

        @Override
        public String displayName() { return "Ability Timer"; }

        @Override
        public int defaultX() { return 10; }

        @Override
        public int defaultY() { return 200; }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            int w = 60;
            for (String s : shown()) {
                w = Math.max(w, font.width(s));
            }
            return w;
        }

        @Override
        public int height() { return HudText.height(Math.max(1, shown().size()), 10); }

        @Override
        public boolean isEnabledInSettings() {
            MiningQolConfig cfg = MiningQolConfig.getInstance();
            return cfg.isAbilityAlertRaw() && cfg.isAbilityTimer();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            MiningQolConfig cfg = MiningQolConfig.getInstance();
            if (!cfg.isAbilityAlert() || !cfg.isAbilityTimer() || HudVisibility.hidesHud()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int lineY = y;
            for (String s : shown()) {
                graphics.text(font, s, x, lineY, 0xFFFFFFFF, true);
                lineY += 10;
            }
            if (!timerLines.isEmpty()) {
                HudSeen.markDrawn(TIMER_ID);
            }
        }
    }
}
