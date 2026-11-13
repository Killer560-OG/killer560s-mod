package com.killer560.hub.creationcooldown;

import com.killer560.hub.autoclear.AutoClearTargets;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creation Cooldown (to-do Doc 2026-11-01, "Look at creation cooldown in skyhanni"): a port of SkyHanni's
 * {@code DungeonCreationCooldown}. Hypixel will not create another dungeon instance for 30 s after you entered one; the
 * clock starts on any player's "&lt;name&gt; entered The Catacombs, Floor N!" line (Hypixel prints only the creator's line to the party)
 * <ul>
 *   <li>HUD "Creation Cooldown: 12s" while it runs, in a dungeon (only in the green Entrance room with Only In Green
 *       Room, SkyHanni's "Only Show In Entrance Room") and, with Show On All Islands, anywhere else on Skyblock.</li>
 *   <li>Block Instance Creation: a {@code /joininstance} or {@code /joindungeon} during the cooldown is held back with
 *       a chat line - typed ones through Fabric's ALLOW_COMMAND, the mod's own (the /f7 shortcuts, Party Commands' !f7,
 *       Insta Clear Warning's requeue) in {@link com.killer560.hub.util.ServerCommands#toServer}.</li>
 *   <li>Chat When Over: one line when the cooldown ends.</li>
 * </ul>
 * The clock itself always runs (it is one chat match): Insta Clear Warning's requeue waits on {@link #remainingMs}.
 */
public final class CreationCooldownFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-creationcooldown");
    private static final String FEATURE = "Creation Cooldown";
    public static final long COOLDOWN_MS = 30_000L;

    /**
     * One line of the entry announcement, e.g. "[MVP+] Chissl entered The Catacombs, Floor I!" or "... entered MM The
     * Catacombs, Floor I!". Anchored, with a {@code [A-Za-z0-9_]{1,16}} name: a player's chat always has a prefix
     * before any text it carries, so "[VIP] Bob: Me entered The Catacombs..." cannot match.
     */
    static final Pattern ENTERED = Pattern.compile(
            "^(?:\\[[^\\]]{1,20}] ){0,2}([A-Za-z0-9_]{1,16}) entered (?:MM )?The Catacombs, .{1,30}!$");

    private static long endsAtMs = 0;
    private static boolean warnedOver = true;
    private static int blockedCount = 0;
    private static String lastDrawn = null;

    private CreationCooldownFeature() {
    }

    public static void register() {
        CreationCooldownConfig.getInstance();
        ChatObserver.subscribe(m -> onChat(ChatObserver.strip(m)));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> !blocks(command));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CreationCooldownFeature", CreationCooldownFeature::tick));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "creation_cooldown"),
                (graphics, deltaTracker) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client)
                            || !HUD.isEnabledInSettings()) {
                        return;
                    }
                    com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HUD);
                });
        // Over every other screen too (killer560, 2026-11-09: "show in any gui"): the HUD layer sits under a screen, so a
        // screen that hides gameplay HUDs gets the timer from its own afterExtract (chat, terminals and the HUD editor
        // are drawn by the layer or the editor already).
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!HudVisibility.menuOpen() || screen instanceof HudEditorScreen) {
                return;
            }
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                if (HUD.isEnabledInSettings() && !McCompat.hudHidden(Minecraft.getInstance())) {
                    com.killer560.hub.hud.HudElementRegistry.drawAt(g, HUD);
                }
            });
        });
    }

    static void onChat(String plain) {
        if (plain == null || !plain.contains("entered")) {
            return;
        }
        for (String line : plain.split("\n")) {
            if (ENTERED.matcher(line.trim()).matches()) {
                start(System.currentTimeMillis());
                return;
            }
        }
    }

    /** The clock starts now (also the testkit's entry point). */
    public static void start(long nowMs) {
        endsAtMs = nowMs + COOLDOWN_MS;
        warnedOver = false;
        LOGGER.info("[CreationCooldown] entered a dungeon: no new instance for {} s", COOLDOWN_MS / 1000);
    }

    /** Milliseconds until a new instance can be made, 0 when none is pending. */
    public static long remainingMs() {
        return Math.max(0L, endsAtMs - System.currentTimeMillis());
    }

    /** Whether the cooldown has ever started since the client did (false: nothing is known about it). */
    public static boolean known() {
        return endsAtMs > 0;
    }

    /**
     * Whether {@code command} (no leading slash) is an instance creation that Block Instance Creation holds back now.
     * Says so in chat when it does.
     */
    public static boolean blocks(String command) {
        if (command == null || !CreationCooldownConfig.getInstance().isBlockCreation() || !SkyblockGate.allows()) {
            return false;
        }
        String c = command.trim().toLowerCase(Locale.ROOT);
        if (!c.startsWith("joininstance") && !c.startsWith("joindungeon")) {
            return false;
        }
        long left = remainingMs();
        if (left <= 0) {
            return false;
        }
        blockedCount++;
        LOGGER.info("[CreationCooldown] blocked /{} ({} ms left)", command, left);
        ModChat.send(FEATURE, ModChat.bad("Blocked instance creation"), ModChat.text(" - cooldown: "),
                ModChat.value(seconds(left)));
        return true;
    }

    private static void tick(Minecraft client) {
        if (warnedOver || endsAtMs == 0 || remainingMs() > 0) {
            return;
        }
        warnedOver = true;
        if (CreationCooldownConfig.getInstance().isChatWhenOver() && SkyblockGate.allows() && client.player != null) {
            ModChat.send(FEATURE, ModChat.good("Dungeon creation cooldown is over!"));
        }
    }

    static String seconds(long ms) {
        return ((ms + 999) / 1000) + "s";
    }

    /** SkyHanni's isEnabled: in a dungeon (only the Entrance with Only In Green Room), or anywhere on Skyblock with Show On All Islands. */
    static boolean showsHere() {
        CreationCooldownConfig cfg = CreationCooldownConfig.getInstance();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (DungeonState.isInDungeon()) {
            return !cfg.isOnlyInGreenRoom() || inEntrance();
        }
        return cfg.isShowOnAllIslands() && SkyblockGate.isOnSkyblock();
    }

    /** In the Entrance (green) room - or before the map knows any room, which is where a run starts (SkyHanni's rule). */
    static boolean inEntrance() {
        if (LiveMapFeature.isInBoss()) {
            return false;
        }
        DungeonLayout layout = DungeonLayout.current();
        int here = layout.currentRoom();
        if (here < 0) {
            return true;
        }
        int entrance = AutoClearTargets.entranceRoom(layout);
        return entrance < 0 || here == entrance;
    }

    public static int blockedCount() {
        return blockedCount;
    }

    public static String lastDrawn() {
        return lastDrawn;
    }

    public static void testReset() {
        endsAtMs = 0;
        warnedOver = true;
        lastDrawn = null;
    }

    public static final HudElement HUD = new BasicHud("creation_cooldown", "Creation Cooldown", 10, 160) {
        private static final String LABEL = "Creation Cooldown: ";

        @Override
        public int width() {
            var font = Minecraft.getInstance().font;
            return 1 + font.width(LABEL) + font.width("30s");
        }

        @Override
        public int height() {
            return 1 + com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            return CreationCooldownConfig.getInstance().isEnabledRaw();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
            long left = remainingMs();
            if (!example && (left <= 0 || !showsHere())) {
                return;
            }
            String value = seconds(example && left <= 0 ? COOLDOWN_MS : left);
            if (!example) {
                lastDrawn = value;
                HudSeen.markDrawn(id());
            }
            var font = Minecraft.getInstance().font;
            graphics.text(font, LABEL, x + 1, y + 1, ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE)), true);
            graphics.text(font, value, x + 1 + font.width(LABEL), y + 1, 0xFF55FFFF, true);
        }
    };
}
