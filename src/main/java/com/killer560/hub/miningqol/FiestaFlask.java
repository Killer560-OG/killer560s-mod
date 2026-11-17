package com.killer560.hub.miningqol;

import com.google.gson.JsonObject;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.TabOrder;
import com.killer560.hub.util.TabText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fiesta Flask Timer (killer560 2026-11-16: "a fiesta flask countdown timer till it expires as a hud").
 *
 * <p>Start: a use of a FIESTA_FLASK is sent and the held stack then shrinks (or the flask leaves the slot) within
 * {@link #CONFIRM_TICKS} ticks; the click alone starts nothing (a second flask while one is active is refused by the server,
 * so its stack stays). Sync: a tab-list line naming "Fiesta Flask" with a time sets the remaining time. The wiki says the
 * timer is paused outside a Mining Fiesta, so it counts down only while a tab or scoreboard line names "Mining Fiesta"
 * (setting "Pause Outside Mining Fiesta"). The remaining time is saved per account.
 * <p>What the server shows after a drink is unproven: for {@link #OBSERVE_MS} after a use this logs one always-on line per
 * relevant chat or tab line (logger killer560smod-fiestaflask), so the next real drink proves it.
 */
public final class FiestaFlask {

    public static final String ID = "mining_fiesta_flask";
    static final int CONFIRM_TICKS = 40;
    static final long OBSERVE_MS = 5000;
    private static final Logger LOG = ModLog.get("killer560smod-fiestaflask");
    private static final Path STATE = ModPaths.config("killer560smod-mining-fiestaflask.json");
    private static final Pattern UNIT = Pattern.compile("(\\d{1,3})\\s*([hms])\\b");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    /** Remaining effect time in ms, or -1 with none active. Client thread only. */
    private static long remainingMs = -1;
    private static long lastMs;
    private static long savedMs;
    private static boolean fiesta;
    private static boolean warned;
    private static String uuid = "";
    private static volatile String text = null;
    private static int ticks;
    private static int pendingTick = -1;
    private static int pendingSlot;
    private static int pendingCount;
    private static long observeUntil;
    private static int observed;
    private static int startedCount;
    private static int alerts;
    private static boolean loaded;

    private FiestaFlask() {
    }

    static void register() {
        ChatObserver.subscribe(FiestaFlask::onChat);
        HudElementRegistry.register(new Timer());
    }

    /** The "start" count, for the testkit. */
    public static int startedCount() { return startedCount; }

    /** Expiry alerts sent (chat line and ding), for the testkit. */
    public static int alerts() { return alerts; }

    public static long remainingMs() { return remainingMs; }

    public static String text() { return text; }

    /** A use packet is leaving (RightClick.onUse): remember the flask and its stack to see whether it was consumed. */
    public static void onUse(LocalPlayer player, net.minecraft.world.InteractionHand hand) {
        if (!MiningQolConfig.getInstance().isFiestaFlask()) return;
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() || !"FIESTA_FLASK".equals(CheatUtils.skyblockId(held))) return;
        Inventory inv = player.getInventory();
        pendingSlot = hand == net.minecraft.world.InteractionHand.MAIN_HAND ? inv.getSelectedSlot() : 40;
        pendingCount = held.getCount();
        pendingTick = ticks;
        observeUntil = System.currentTimeMillis() + OBSERVE_MS;
        observed = 0;
        LOG.info("[FiestaFlask] use sent: slot {} count {} remaining {} ms, watching chat and tab for {} s", pendingSlot + 1,
                pendingCount, remainingMs, OBSERVE_MS / 1000);
    }

    private static void onChat(Component message) {
        if (System.currentTimeMillis() > observeUntil || observed >= 20) return;
        String raw = ChatObserver.strip(message);
        if (raw == null) return;
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("flask") || low.contains("fiesta") || low.contains("consume") || low.contains("effect")) {
            observed++;
            LOG.info("[FiestaFlask] chat: {}", raw.length() > 160 ? raw.substring(0, 160) : raw);
        }
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        ticks++;
        if (!cfg.isFiestaFlask() || client.player == null) {
            text = null;
            return;
        }
        if (!loaded) onJoin();
        if (pendingTick >= 0) checkConsumed(client.player, cfg);
        if (ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        List<String> tab = new ArrayList<>();
        for (PlayerInfo info : TabOrder.listed(client)) {
            String plain = TabText.plain(info);
            if (plain != null) tab.add(plain.trim());
        }
        scan(tab, ScoreboardData.sidebarNow(client), now, cfg);
    }

    private static void checkConsumed(LocalPlayer player, MiningQolConfig cfg) {
        ItemStack s = player.getInventory().getItem(pendingSlot);
        boolean same = !s.isEmpty() && "FIESTA_FLASK".equals(CheatUtils.skyblockId(s));
        if (!same || s.getCount() < pendingCount) {
            LOG.info("[FiestaFlask] consumed: slot {} count {} -> {}", pendingSlot + 1, pendingCount, same ? s.getCount() : 0);
            pendingTick = -1;
            start(System.currentTimeMillis(), cfg.getFiestaFlaskMinutes() * 60_000L);
        } else if (ticks - pendingTick > CONFIRM_TICKS) {
            LOG.info("[FiestaFlask] no stack change within {} ticks: not consumed, timer unchanged", CONFIRM_TICKS);
            pendingTick = -1;
        }
    }

    /** Starts (or restarts) the countdown with {@code ms} left. */
    static void start(long now, long ms) {
        remainingMs = ms;
        lastMs = now;
        warned = false;
        startedCount++;
        save(now);
        step(now, MiningQolConfig.getInstance());
    }

    /** One look at the tab and sidebar lines: fiesta state, a server reading of the flask, then the countdown. */
    static void scan(List<String> tab, List<String> sidebar, long now, MiningQolConfig cfg) {
        boolean seen = false;
        for (List<String> lines : List.of(tab, sidebar)) {
            for (String line : lines) {
                String low = line.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("mining fiesta")) seen = true;
                if (low.contains("fiesta flask")) {
                    long ms = parseMs(line.substring(low.indexOf("fiesta flask") + 12));
                    if (now < observeUntil && observed < 20) {
                        observed++;
                        LOG.info("[FiestaFlask] line: {}", line.length() > 160 ? line.substring(0, 160) : line);
                    }
                    if (ms > 0 && (remainingMs < 0 || Math.abs(ms - remainingMs) > 1500)) {
                        remainingMs = ms;
                        lastMs = now;
                        warned = false;
                        save(now);
                    }
                }
            }
        }
        if (seen != fiesta) {
            // Count up to the change at the old state's rate, then switch.
            step(now, cfg);
            fiesta = seen;
        }
        step(now, cfg);
    }

    /** The time in "1h 5m 3s", "59m 12s" or "59:12" after the flask's name; 0 with none. */
    static long parseMs(String s) {
        long ms = 0;
        Matcher m = UNIT.matcher(s);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            ms += m.group(2).equals("h") ? n * 3_600_000L : m.group(2).equals("m") ? n * 60_000L : n * 1000L;
        }
        if (ms == 0 && (m = CLOCK.matcher(s)).find()) {
            long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(2));
            ms = m.group(3) == null ? (a * 60 + b) * 1000L : ((a * 60 + b) * 60 + Long.parseLong(m.group(3))) * 1000L;
        }
        return ms;
    }

    /** Moves the countdown to {@code now}, expires it, warns and rebuilds the HUD text. */
    static void step(long now, MiningQolConfig cfg) {
        if (remainingMs < 0) {
            text = null;
            return;
        }
        boolean running = fiesta || !cfg.isFiestaFlaskPause();
        if (running) remainingMs -= Math.max(0, now - lastMs);
        lastMs = now;
        if (remainingMs <= 0) {
            remainingMs = -1;
            text = null;
            save(now);
            if (cfg.isFiestaFlaskAlert()) {
                ModChat.send("Fiesta Flask", ModChat.text("Your Fiesta Flask expired."));
                playPling();
                alerts++;
            }
            return;
        }
        int warn = cfg.getFiestaFlaskWarn();
        if (warn > 0 && !warned && remainingMs <= warn * 1000L) {
            warned = true;
            ModChat.send("Fiesta Flask", ModChat.text("Your Fiesta Flask ends in " + format(remainingMs) + "."));
            playPling();
        }
        text = "Fiesta Flask: " + format(remainingMs) + (running ? "" : " (paused)");
        if (now - savedMs >= 30_000L) save(now);
    }

    private static void playPling() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f, 1f));
    }

    static String format(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /** Joined a world: load this account's saved time (the effect does not end while logged out, so none is subtracted). */
    public static void onJoin() {
        Minecraft mc = Minecraft.getInstance();
        String id = mc.getUser() == null ? "" : String.valueOf(mc.getUser().getProfileId());
        loaded = true;
        if (id.equals(uuid) && remainingMs >= 0) return;
        uuid = id;
        remainingMs = -1;
        try {
            if (Files.exists(STATE)) {
                JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(STATE)).getAsJsonObject();
                JsonObject mine = o.has(uuid) && o.get(uuid).isJsonObject() ? o.getAsJsonObject(uuid) : null;
                if (mine != null) {
                    long ms = ConfigJson.getLong(mine, "remainingMs", -1);
                    remainingMs = ms > 0 ? ms : -1;
                }
            }
        } catch (Exception e) {
            LOG.warn("[FiestaFlask] state unreadable: {}", e.toString());
        }
        lastMs = System.currentTimeMillis();
        warned = false;
    }

    private static void save(long now) {
        savedMs = now;
        try {
            JsonObject o = new JsonObject();
            if (Files.exists(STATE)) {
                o = com.google.gson.JsonParser.parseString(Files.readString(STATE)).getAsJsonObject();
            }
            JsonObject mine = new JsonObject();
            mine.addProperty("remainingMs", remainingMs);
            o.add(uuid, mine);
            ConfigJson.write(STATE, o);
        } catch (Exception e) {
            LOG.warn("[FiestaFlask] state not saved: {}", e.toString());
        }
    }

    /** Testkit: forgets everything in memory (the state file is left) and re-reads it. */
    public static void reloadForTest() {
        remainingMs = -1;
        uuid = "";
        text = null;
        onJoin();
    }

    public static void resetForTest() {
        remainingMs = -1;
        text = null;
        pendingTick = -1;
        fiesta = false;
        startedCount = 0;
        try {
            Files.deleteIfExists(STATE);
        } catch (Exception ignored) {
        }
    }

    /** Testkit: one scan at {@code now} from the given lines. */
    public static void scanForTest(List<String> tab, List<String> sidebar, long now) {
        scan(tab, sidebar, now, MiningQolConfig.getInstance());
    }

    /** Testkit: the server confirmed a drink. */
    public static void startForTest(long now, long ms) { start(now, ms); }

    /** The HUD line, theme-colored; a sample in the editor. */
    public static final class Timer extends BasicHud {
        public Timer() {
            super(ID, "Fiesta Flask Timer", 10, 230);
        }

        private String shown() {
            String t = text;
            return t == null && HudVisibility.editorOpen() ? "Fiesta Flask: 12m 34s" : t;
        }

        @Override
        public int width() {
            String s = shown();
            return Minecraft.getInstance().font.width(s == null ? "Fiesta Flask: 12m 34s" : s);
        }

        @Override
        public int height() { return 10; }

        @Override
        public boolean isEnabledInSettings() { return MiningQolConfig.getInstance().isFiestaFlaskRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MiningQolConfig.getInstance().isFiestaFlask() || HudVisibility.hidesHud()) return;
            String s = shown();
            if (s == null) return;
            graphics.text(Minecraft.getInstance().font, s, x, y, ThemeAccent.onDark(ThemeAccent.accent(0xFFFFAA00)), true);
            if (text != null) HudSeen.markDrawn(ID);
        }
    }
}
