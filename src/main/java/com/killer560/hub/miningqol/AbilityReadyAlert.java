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
 * those it shows nothing rather than a guess.
 */
public final class AbilityReadyAlert {

    public static final String POPUP_ID = "mining_ability_ready";
    public static final String TIMER_ID = "mining_ability_timer";
    static final long POPUP_MS = 2500;

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
    private static String lastUsed = null;

    private static volatile String popupText = null;
    private static volatile long popupUntil = 0;
    private static volatile List<String> timerLines = List.of();
    private static int popupsShown = 0;
    private static int popupFrames = 0;

    private AbilityReadyAlert() {
    }

    static void register() {
        ChatObserver.subscribe(AbilityReadyAlert::onChat);
    }

    static void onChat(Component message) {
        if (!MiningQolConfig.getInstance().isAbilityAlert()) {
            return;
        }
        String raw = ChatObserver.strip(message);
        if (raw == null || raw.length() > 80) {
            return;
        }
        long now = System.currentTimeMillis();
        Matcher m = READY.matcher(raw);
        if (m.matches()) {
            onReady(m.group(1).trim(), now);
            return;
        }
        m = USED.matcher(raw);
        if (m.matches()) {
            String name = m.group(1).trim();
            USED_AT.put(name, now);
            lastUsed = name;
            Long learned = LEARNED.get(name);
            if (learned != null) {
                READY_AT.put(name, now + learned);
            }
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
        // The tab widget, when shown, is the server's own number: it replaces the estimate.
        for (String line : MiningState.pickaxeAbilityLines()) {
            Matcher m = TAB.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).trim();
            if (m.group(3) == null) {
                READY_AT.remove(name);
            } else {
                long s = Long.parseLong(m.group(3)) + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)) * 60);
                READY_AT.put(name, now + s * 1000L);
            }
        }
        List<String> out = new ArrayList<>();
        READY_AT.entrySet().removeIf(e -> e.getValue() <= now);
        for (Map.Entry<String, Long> e : READY_AT.entrySet()) {
            long left = (e.getValue() - now + 999) / 1000;
            out.add("§6" + e.getKey() + ": §e" + (left >= 60 ? (left / 60) + "m " + (left % 60) + "s" : left + "s"));
        }
        timerLines = List.copyOf(out);
    }

    static void reset() {
        READY_AT.clear();
        USED_AT.clear();
        lastUsed = null;
        popupText = null;
        timerLines = List.of();
    }

    // ---- testkit hooks

    public static String popup() {
        return System.currentTimeMillis() < popupUntil ? popupText : null;
    }

    public static int popupsShown() {
        return popupsShown;
    }

    public static int popupFrames() {
        return popupFrames;
    }

    public static List<String> timer() {
        return timerLines;
    }

    public static Long learnedMs(String name) {
        return LEARNED.get(name);
    }

    /** The big "X READY" popup, centered by default; movable in the HUD editor. */
    public static final class Popup implements HudElement {

        private static final float SCALE = 2.0f;

        private String shown() {
            String p = popup();
            if (p == null && HudVisibility.editorOpen()) {
                return "§a§lPickobulus READY";
            }
            return p;
        }

        @Override
        public String id() {
            return POPUP_ID;
        }

        @Override
        public String displayName() {
            return "Ability Ready Popup";
        }

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
        public int height() {
            return (int) (10 * SCALE);
        }

        @Override
        public boolean isEnabledInSettings() {
            return MiningQolConfig.getInstance().isAbilityAlertRaw();
        }

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
        public String id() {
            return TIMER_ID;
        }

        @Override
        public String displayName() {
            return "Ability Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 200;
        }

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
        public int height() {
            return HudText.height(Math.max(1, shown().size()), 10);
        }

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
