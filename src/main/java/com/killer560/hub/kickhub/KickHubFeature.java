package com.killer560.hub.kickhub;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kicked To Hub (killer560, 2026-11-03: "look at skyhanni's kicked from server stuff if you get sent to the hub and add
 * that"). SkyHanni's behavior, our own code:
 * <ul>
 *   <li>SkyHanni {@code ServerRestartTitle}: the sidebar's "Server closing: mm:ss" becomes a "Server Restart in" title
 *       (Restart Countdown). Ours also titles the chat's "[Important] This server will restart soon: ..." (Restart
 *       Alert).</li>
 *   <li>SkyHanni {@code SkyBlockKickDuration}: kicked out of SkyBlock into a Hypixel lobby ("You were kicked while
 *       joining that server!" outside SkyBlock, "There was a problem joining SkyBlock, try again in a moment!"), a HUD
 *       shows how long ago, and after Rejoin Warn Time (60 s) a "Try rejoining SkyBlock now!" title and a sound (Rejoin
 *       Timer). Cleared on reaching SkyBlock, or after 5 minutes.</li>
 *   <li>Beyond SkyHanni (which has no warp back): sent to the Hub ("Evacuating to Hub...", "A kick occurred in your
 *       connection, so you were put in the SkyBlock lobby!", or after a restart warning), the island you were on is
 *       remembered from the tab list's "Area:" line; on reaching the Hub a title and sound (Hub Alert), a clickable
 *       [Warp Back] chat line running the island's real warp (Warp Back Line), and with Auto Warp Back (off by
 *       default) that one command after Auto Warp Delay.</li>
 * </ul>
 * Every line is matched whole ({@code matches()}) on the stripped text: another player's chat always carries a
 * "name: " prefix, so a typed copy never matches. Auto Warp Back sends one command; a rate-limit reply ("Couldn't warp
 * you! Try again later.", "Whoa! Slow down there!", ...) within 10 s of it reschedules it at most twice, 10 s and 20 s
 * later. Any command the player sends, leaving the Hub, a disconnect or the switch going off cancels it. Never in a
 * dungeon or Kuudra: those cannot be warped back into.
 */
public final class KickHubFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-kickhub");
    public static final String FEATURE = "Kicked To Hub";

    static final Pattern RESTART = Pattern.compile("^\\[Important] This server will restart soon: (.{1,40})$");
    static final Pattern WARP_OUT = Pattern.compile("^You have (\\d{1,3}) seconds to warp out! CLICK to warp now!$");
    static final Pattern EVACUATING = Pattern.compile("^Evacuating to Hub\\.\\.\\.$");
    static final Pattern KICK_TO_LOBBY = Pattern.compile(
            "^An? (?:kick|exception) occurred in your connection, so you were put in the SkyBlock [lL]obby!$");
    static final Pattern KICKED_JOINING = Pattern.compile("^You were kicked while joining that server!$");
    static final Pattern PROBLEM_JOINING = Pattern.compile("^There was a problem joining SkyBlock, try again in a moment!$");
    /** Hypixel's replies that mean "not now": the warp cooldown, the command rate limit, a too-fast join. */
    static final Pattern RATE_LIMIT = Pattern.compile("^(?:Couldn't warp you! Try again later\\. \\([A-Z_]{1,40}\\)"
            + "|(?:Whoa|Woah)! Slow down there!|You are sending commands too fast! Please slow down\\."
            + "|You (?:must wait|tried to rejoin too fast|joined too fast)[^\\n]{0,80})$");
    static final Pattern SERVER_CLOSING = Pattern.compile("^Server closing: (\\d{1,2}):(\\d{2})(?: .{0,20})?$");
    private static final Pattern TAB_AREA = Pattern.compile("^Area:\\s*(.{1,40})$");

    /** Tab-list island name (lower case) -> the Hypixel command, no slash (hypixelskyblock.minecraft.wiki Fast Travel). */
    static final Map<String, String> WARPS = Map.ofEntries(
            Map.entry("private island", "is"),
            Map.entry("garden", "warp garden"),
            Map.entry("the farming islands", "warp barn"),
            Map.entry("the park", "warp park"),
            Map.entry("gold mine", "warp gold"),
            Map.entry("deep caverns", "warp deep"),
            Map.entry("dwarven mines", "warp dwarves"),
            Map.entry("crystal hollows", "warp crystals"),
            Map.entry("spider's den", "warp spider"),
            Map.entry("the end", "warp end"),
            Map.entry("crimson isle", "warp isle"),
            Map.entry("jerry's workshop", "warp jerry"),
            Map.entry("the rift", "warp rift"),
            Map.entry("dungeon hub", "warp dungeon_hub"),
            Map.entry("backwater bayou", "warp bayou"),
            Map.entry("galatea", "warp galatea"));

    static final String HUB = "Hub";
    /** How long after a kick or restart line the Hub may still count as "sent there by it". */
    static final long ARRIVAL_WINDOW_MS = 120_000L;
    /** A Hub reached this recently, before a "put in the SkyBlock lobby" line, is the kick's own arrival. */
    static final long RECENT_HUB_MS = 30_000L;
    static final long RATE_REPLY_MS = 10_000L;
    static final long RETRY_STEP_MS = 10_000L;
    static final int MAX_SENDS = 3;
    static final long KICK_TIMER_MAX_MS = 5 * 60_000L;

    // ---- where he is ----
    private static String area = "";
    private static String previousArea = "";
    private static long areaSinceMs = 0;
    private static int tickCount = 0;

    // ---- a kick or evacuation waiting for the Hub ----
    private static String origin = null;
    private static String reason = null;
    private static long awaitUntilMs = 0;

    // ---- Auto Warp Back ----
    private static String autoCommand = null;
    private static long autoAtMs = 0;
    private static int autoSends = 0;
    private static long lastAutoSentMs = 0;
    private static boolean ourSend = false;

    // ---- Rejoin Timer ----
    private static long kickedOutAtMs = 0;
    private static boolean rejoinWarned = false;

    // ---- Restart Countdown ----
    private static long lastCountdownSecond = -1;

    // ---- testkit ----
    private static volatile String lastTitle = null;
    private static volatile String lastOffer = null;
    private static volatile String lastOrigin = null;
    private static volatile int sounds = 0;
    private static volatile int autoSent = 0;
    private static volatile String lastHudText = null;

    private KickHubFeature() {
    }

    public static void register() {
        KickHubConfig.getInstance();
        ChatObserver.subscribe(m -> onChat(ChatObserver.strip(m)));
        // Any command he sends himself (typed, a keybind, the [Warp Back] click) cancels a pending automatic one; our
        // own leaves through ServerCommands.toServer, below this event.
        ClientSendMessageEvents.COMMAND.register(command -> {
            if (!ourSend && autoAtMs > 0) {
                LOGGER.info("[KickHub] /{} sent by the player: automatic warp back canceled", command);
                cancelAuto();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> cancelAuto());
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("KickHubFeature", KickHubFeature::tick));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "kick_duration"),
                (graphics, deltaTracker) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client)
                            || !HUD.isEnabledInSettings()) {
                        return;
                    }
                    com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HUD);
                });
    }

    // ---- chat ----------------------------------------------------------------------------------------------------

    static void onChat(String plain) {
        if (plain == null || !KickHubConfig.getInstance().isEnabled()) {
            return;
        }
        for (String raw : plain.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.length() > 140) {
                continue;
            }
            try {
                onLine(line, System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOGGER.warn("[KickHub] line '{}' failed: {}", line, e.toString());
            }
        }
    }

    private static void onLine(String line, long now) {
        KickHubConfig cfg = KickHubConfig.getInstance();
        Matcher m;
        if ((m = RESTART.matcher(line)).matches()) {
            if (inInstance()) {
                return;
            }
            LOGGER.info("[KickHub] restart warning ({}) on {}", m.group(1), area);
            expectHub(area, "restart", now);
            if (cfg.isRestartAlert()) {
                title("§cServer restarting soon", "§7" + m.group(1));
                sound();
            }
        } else if ((m = WARP_OUT.matcher(line)).matches()) {
            if (!inInstance()) {
                expectHub(area, "restart", now);
            }
        } else if (EVACUATING.matcher(line).matches()) {
            if (inInstance()) {
                return;
            }
            LOGGER.info("[KickHub] evacuating to the Hub from {}", area);
            expectHub(area, "evacuated", now);
        } else if (KICK_TO_LOBBY.matcher(line).matches()) {
            // This line comes once he is already in the Hub: the island is the one before it.
            String from = HUB.equals(area) && now - areaSinceMs < RECENT_HUB_MS ? previousArea : area;
            LOGGER.info("[KickHub] kicked into the SkyBlock lobby from {}", from);
            expectHub(from, "kicked", now);
        } else if (KICKED_JOINING.matcher(line).matches()) {
            // SkyHanni's rule: it is a kick out of SkyBlock only when he is not on SkyBlock.
            if (!SkyblockGate.isOnSkyblock()) {
                kickedOut(now);
            }
        } else if (PROBLEM_JOINING.matcher(line).matches()) {
            kickedOut(now);
        } else if (RATE_LIMIT.matcher(line).matches()) {
            onRateLimit(now);
        }
    }

    private static void expectHub(String from, String why, long now) {
        origin = from == null || from.isEmpty() ? null : from;
        reason = why;
        awaitUntilMs = now + ARRIVAL_WINDOW_MS;
        cancelAuto();
    }

    private static void kickedOut(long now) {
        if (!KickHubConfig.getInstance().isRejoinTimer()) {
            return;
        }
        kickedOutAtMs = now;
        rejoinWarned = false;
        LOGGER.info("[KickHub] kicked out of SkyBlock: rejoin timer started");
    }

    private static void onRateLimit(long now) {
        if (autoCommand == null || lastAutoSentMs == 0 || now - lastAutoSentMs > RATE_REPLY_MS) {
            return;
        }
        if (autoSends >= MAX_SENDS) {
            ModChat.send(FEATURE, ModChat.bad("Hypixel would not warp you back"), ModChat.text(" - use "),
                    ModChat.value("/" + autoCommand));
            cancelAuto();
            return;
        }
        autoAtMs = now + RETRY_STEP_MS * autoSends;
        lastAutoSentMs = 0;
        ModChat.log(FEATURE, ModChat.text("Hypixel said not yet; trying /" + autoCommand + " again in "
                + (RETRY_STEP_MS * autoSends / 1000) + "s"));
    }

    // ---- tick ----------------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        if (client.level == null || client.getConnection() == null) {
            area = "";
            return;
        }
        KickHubConfig cfg = KickHubConfig.getInstance();
        if (!cfg.isEnabled()) {
            origin = null;
            awaitUntilMs = 0;
            cancelAuto();
            kickedOutAtMs = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (tickCount++ % 10 == 0) {
            String read = readArea(client);
            if (!read.isEmpty() && !read.equals(area)) {
                if (!area.isEmpty()) {
                    previousArea = area;
                }
                area = read;
                areaSinceMs = now;
                if (autoAtMs > 0 && !HUB.equals(area)) {
                    LOGGER.info("[KickHub] left the Hub for {}: automatic warp back canceled", area);
                    cancelAuto();
                }
            }
            if (tickCount % 20 == 1) {
                restartCountdown(client, cfg);
            }
        }
        if (awaitUntilMs > 0) {
            if (now > awaitUntilMs) {
                awaitUntilMs = 0;
                origin = null;
            } else if (HUB.equals(area) && (origin == null || !HUB.equals(origin))) {
                awaitUntilMs = 0;
                arrived(cfg, now);
            }
        }
        if (autoAtMs > 0 && now >= autoAtMs) {
            fireAuto(cfg, now);
        }
        if (kickedOutAtMs > 0) {
            if (SkyblockGate.isOnSkyblock() || now - kickedOutAtMs > KICK_TIMER_MAX_MS || !cfg.isRejoinTimer()) {
                kickedOutAtMs = 0;
            } else if (!rejoinWarned && now - kickedOutAtMs >= cfg.getRejoinWarnSeconds() * 1000L) {
                rejoinWarned = true;
                title("§eTry rejoining SkyBlock now!", "");
                sound();
            }
        }
    }

    private static void arrived(KickHubConfig cfg, long now) {
        String from = origin;
        origin = null;
        lastOrigin = from;
        String command = from == null ? null : WARPS.get(from.toLowerCase(Locale.ROOT));
        LOGGER.info("[KickHub] in the Hub after '{}' from {} (warp: {})", reason, from, command);
        if (cfg.isHubAlert()) {
            title("§cSent to the Hub", from == null ? "" : "§7from " + from);
            sound();
        }
        if (command == null) {
            return;
        }
        if (cfg.isWarpBackLine()) {
            lastOffer = command;
            MutableComponent warp = Component.literal("[Warp Back]").withStyle(st -> st.withColor(ChatFormatting.GREEN)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand("/" + command))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("/" + command))));
            ModChat.send(FEATURE, ModChat.text("You were sent to the Hub from "), ModChat.value(from),
                    ModChat.text(". "), warp);
        }
        if (cfg.isAutoWarpBack()) {
            autoCommand = command;
            autoSends = 0;
            autoAtMs = now + cfg.getAutoDelaySeconds() * 1000L;
            ModChat.send(FEATURE, ModChat.text("Warping back to "), ModChat.value(from),
                    ModChat.dim(" in " + cfg.getAutoDelaySeconds() + "s (any command cancels)"));
        }
    }

    private static void fireAuto(KickHubConfig cfg, long now) {
        String command = autoCommand;
        autoAtMs = 0;
        if (command == null || !cfg.isAutoWarpBack() || !HUB.equals(area) || inInstance()) {
            cancelAuto();
            return;
        }
        ourSend = true;
        boolean sent;
        try {
            sent = ServerCommands.toServer(command);
        } finally {
            ourSend = false;
        }
        if (sent) {
            autoSends++;
            autoSent++;
            lastAutoSentMs = now;
            LOGGER.info("[KickHub] automatic warp back: /{} (send {})", command, autoSends);
        }
    }

    private static void cancelAuto() {
        autoAtMs = 0;
        autoCommand = null;
        autoSends = 0;
        lastAutoSentMs = 0;
    }

    /** SkyHanni's ServerRestartTitle, from the sidebar's "Server closing: mm:ss": a title at every 30 s mark and each of
     *  the last 10 seconds. */
    private static void restartCountdown(Minecraft client, KickHubConfig cfg) {
        if (!cfg.isRestartCountdown() || !SkyblockGate.isOnSkyblock() || inInstance()) {
            return;
        }
        for (String formatted : com.killer560.hub.scoreboard.ScoreboardData.sidebarNow(client)) {
            String line = ChatObserver.stripCodes(formatted);
            if (line == null) {
                continue;
            }
            Matcher m = SERVER_CLOSING.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            long total = Integer.parseInt(m.group(1)) * 60L + Integer.parseInt(m.group(2));
            if (total == lastCountdownSecond) {
                return;
            }
            lastCountdownSecond = total;
            if (total <= 10 || total % 30 == 0) {
                expectHub(area, "restart", System.currentTimeMillis());
                title("§cServer Restart in §b" + total / 60 + ":" + String.format(Locale.ROOT, "%02d", total % 60), "");
            }
            return;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static boolean inInstance() {
        String a = area.toLowerCase(Locale.ROOT);
        return DungeonState.isInDungeon() || a.contains("catacombs") || a.equals("kuudra") || a.contains("instanced");
    }

    private static String readArea(Minecraft client) {
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = com.killer560.hub.util.TabText.plain(info);
            if (plain == null || !plain.contains("Area:")) {
                continue;
            }
            Matcher m = TAB_AREA.matcher(plain.trim());
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private static void title(String title, String subtitle) {
        lastTitle = title;
        Minecraft client = Minecraft.getInstance();
        McCompat.setTimes(client, 5, 50, 10);
        McCompat.setTitle(client, Component.literal(title));
        McCompat.setSubtitle(client, Component.literal(subtitle));
    }

    private static void sound() {
        if (!KickHubConfig.getInstance().isAlertSound()) {
            return;
        }
        sounds++;
        Minecraft client = Minecraft.getInstance();
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 1.0f));
    }

    static String ago(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
    }

    // ---- testkit -------------------------------------------------------------------------------------------------

    public static String lastTitle() {
        return lastTitle;
    }

    public static String lastOffer() {
        return lastOffer;
    }

    public static String lastOrigin() {
        return lastOrigin;
    }

    public static int sounds() {
        return sounds;
    }

    public static int autoSent() {
        return autoSent;
    }

    public static String area() {
        return area;
    }

    public static boolean autoPending() {
        return autoAtMs > 0;
    }

    public static boolean awaitingHub() {
        return awaitUntilMs > 0;
    }

    public static String lastHudText() {
        return lastHudText;
    }

    public static boolean rejoinWarned() {
        return rejoinWarned;
    }

    public static void testReset() {
        origin = null;
        reason = null;
        awaitUntilMs = 0;
        cancelAuto();
        kickedOutAtMs = 0;
        rejoinWarned = false;
        lastCountdownSecond = -1;
        lastTitle = null;
        lastOffer = null;
        lastOrigin = null;
        lastHudText = null;
    }

    /** Moves the kick timer's start back by {@code ms}, so a case need not wait a minute for the rejoin warning. */
    public static void testAgeKick(long ms) {
        if (kickedOutAtMs > 0) {
            kickedOutAtMs -= ms;
        }
    }

    public static final HudElement HUD = new HudElement() {
        private static final String LABEL = "Kicked from SkyBlock ";

        @Override
        public String id() {
            return "kick_duration";
        }

        @Override
        public String displayName() {
            return "Kick Duration";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 175;
        }

        @Override
        public int width() {
            var font = Minecraft.getInstance().font;
            return 1 + font.width(LABEL) + font.width("4m 59s ago");
        }

        @Override
        public int height() {
            return 1 + com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            KickHubConfig cfg = KickHubConfig.getInstance();
            return cfg.isEnabled() && cfg.isRejoinTimer();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
            if (!example && kickedOutAtMs <= 0) {
                return;
            }
            String value = (example && kickedOutAtMs <= 0 ? "12s" : ago(System.currentTimeMillis() - kickedOutAtMs)) + " ago";
            if (!example) {
                lastHudText = LABEL + value;
                HudSeen.markDrawn(id());
            }
            var font = Minecraft.getInstance().font;
            graphics.text(font, LABEL, x + 1, y + 1, 0xFFFF5555, true);
            graphics.text(font, value, x + 1 + font.width(LABEL), y + 1, 0xFF55FFFF, true);
        }
    };

    /** The names of the lines this feature reads, for the FEATURES.md text and the testkit. */
    static List<Pattern> patterns() {
        return List.of(RESTART, WARP_OUT, EVACUATING, KICK_TO_LOBBY, KICKED_JOINING, PROBLEM_JOINING, RATE_LIMIT);
    }
}
