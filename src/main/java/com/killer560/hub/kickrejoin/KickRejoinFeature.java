package com.killer560.hub.kickrejoin;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kick Rejoin Timer (killer560, 2026-11-03: "it should be for kicked to spawn. when you have to wait 1m to rejoin"):
 * SkyHanni's {@code SkyBlockKickDuration}, our own code. Kicked out of SkyBlock into a Hypixel lobby, Hypixel will not
 * let you back in for about a minute; a HUD counts it down and a title and sound say when you can rejoin.
 * <p>
 * The lines, as they appear in his own logs (26.1.2 Dungeons / Mod Only Test, July-October 2026):
 * <ul>
 *   <li>Start: "You were kicked while joining that server!" (after "Exception Connecting:ReadTimeoutException", then
 *       "You tried to rejoin too fast, please try again in a moment!" and the Prototype Lobby), and "A kick occurred in
 *       your connection, so you were put in the SkyBlock lobby!" ("An exception occurred ... SkyBlock Lobby!" too).
 *       Measured from those lines to "Welcome to Hypixel SkyBlock!": 61-82 s when he had to wait (refusals came up to
 *       79 s in), so the default wait is 60 s.</li>
 *   <li>Still waiting: "Cannot join SkyBlock for a moment! (Queue join in cooldown)" and "You tried to rejoin too fast,
 *       please try again in a moment!". Neither says how long; one that comes while the timer already reads "now"
 *       (or with no timer) puts {@link #REFUSAL_EXTRA_MS} back on it. A line stating the seconds ("You must wait 20
 *       seconds before joining SkyBlock") sets the timer to that - no such line is in his logs, so it is only matched in
 *       that one shape.</li>
 *   <li>Not counted: "You were spawned in Limbo." (an AFK / timeout limbo: his logs show SkyBlock again 10-20 s later
 *       with no refusal) and "There was a problem joining SkyBlock, try again in a moment!" (back 13 s later).</li>
 * </ul>
 * Every line is matched whole on the stripped text, so a player typing one (always behind a "name: " prefix) does
 * nothing. The timer clears once he is back on SkyBlock after having been off it, if he never left SkyBlock within
 * {@link #STAY_MS} (a failed island warp that left him where he was), or after 5 minutes. It never sends a command.
 */
public final class KickRejoinFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-kickrejoin");

    static final Pattern KICKED_JOINING = Pattern.compile("^You were kicked while joining that server!$");
    static final Pattern KICK_TO_LOBBY = Pattern.compile(
            "^An? (?:kick|exception) occurred in your connection, so you were put in the SkyBlock [lL]obby!$");
    static final Pattern REFUSED = Pattern.compile("^(?:Cannot join SkyBlock for a moment! \\(Queue join in cooldown\\)"
            + "|You tried to rejoin too fast, please try again in a moment!)$");
    static final Pattern STATED = Pattern.compile(
            "^(?:You must wait|Please wait) (\\d{1,3}) seconds? (?:before|to) (?:re)?join(?:ing)? SkyBlock[!.]?$");

    static final long REFUSAL_EXTRA_MS = 15_000L;
    static final long STAY_MS = 20_000L;
    static final long MAX_MS = 5 * 60_000L;

    private static long kickedAtMs = 0;
    private static long readyAtMs = 0;
    private static boolean seenOff = false;
    private static boolean readyFired = false;

    // ---- testkit ----
    private static volatile String lastTitle = null;
    private static volatile String lastHudText = null;
    private static volatile int sounds = 0;

    private KickRejoinFeature() {
    }

    public static void register() {
        KickRejoinConfig.getInstance();
        ChatObserver.subscribe(m -> onChat(ChatObserver.strip(m)));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("KickRejoinFeature", KickRejoinFeature::tick));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "kick_rejoin_timer"),
                (graphics, deltaTracker) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client)
                            || !HUD.isEnabledInSettings()) {
                        return;
                    }
                    com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HUD);
                });
    }

    static void onChat(String plain) {
        if (plain == null || !KickRejoinConfig.getInstance().isEnabled()) {
            return;
        }
        for (String raw : plain.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.length() > 120) {
                continue;
            }
            try {
                onLine(line, System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOGGER.warn("[KickRejoin] line '{}' failed: {}", line, e.toString());
            }
        }
    }

    private static void onLine(String line, long now) {
        Matcher m;
        if (KICKED_JOINING.matcher(line).matches() || KICK_TO_LOBBY.matcher(line).matches()) {
            // A second kick line while the timer runs (the kick, then the failed rejoin's own kick) restarts the wait.
            start(now, now + KickRejoinConfig.getInstance().getCooldownSeconds() * 1000L);
            LOGGER.info("[KickRejoin] kicked ('{}'): rejoin in {} s", line, KickRejoinConfig.getInstance().getCooldownSeconds());
        } else if (REFUSED.matcher(line).matches()) {
            if (kickedAtMs == 0) {
                start(now, now + REFUSAL_EXTRA_MS);
            } else if (readyAtMs - now < REFUSAL_EXTRA_MS) {
                readyAtMs = now + REFUSAL_EXTRA_MS;
                readyFired = false;
            }
            LOGGER.info("[KickRejoin] Hypixel refused the rejoin: {} ms left", readyAtMs - now);
        } else if ((m = STATED.matcher(line)).matches()) {
            long ms = Integer.parseInt(m.group(1)) * 1000L;
            if (kickedAtMs == 0) {
                start(now, now + ms);
            } else {
                readyAtMs = now + ms;
                readyFired = false;
            }
            LOGGER.info("[KickRejoin] Hypixel says wait {} ms", ms);
        }
    }

    private static void start(long kickedAt, long readyAt) {
        if (kickedAtMs == 0) {
            kickedAtMs = kickedAt;
            seenOff = !SkyblockGate.isOnSkyblock();
        }
        readyAtMs = readyAt;
        readyFired = false;
    }

    private static void clear() {
        kickedAtMs = 0;
        readyAtMs = 0;
        seenOff = false;
        readyFired = false;
    }

    private static void tick(Minecraft client) {
        if (kickedAtMs == 0) {
            return;
        }
        KickRejoinConfig cfg = KickRejoinConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean onSkyblock = SkyblockGate.isOnSkyblock();
        if (!cfg.isEnabled() || now - kickedAtMs > MAX_MS) {
            clear();
            return;
        }
        if (!onSkyblock) {
            seenOff = true;
        } else if (seenOff) {
            LOGGER.info("[KickRejoin] back on SkyBlock {} s after the kick", (now - kickedAtMs) / 1000);
            clear();
            return;
        } else if (now - kickedAtMs > STAY_MS) {
            clear(); // never left SkyBlock: a failed warp that kept him where he was
            return;
        }
        if (!readyFired && now >= readyAtMs && seenOff) {
            readyFired = true;
            if (cfg.isReadyTitle()) {
                lastTitle = "§aRejoin SkyBlock now!";
                McCompat.setTimes(client, 5, 50, 10);
                McCompat.setTitle(client, Component.literal(lastTitle));
                McCompat.setSubtitle(client, Component.literal("§7the rejoin wait is over"));
            }
            if (cfg.isReadySound()) {
                sounds++;
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 1.0f));
            }
        }
    }

    static String seconds(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
    }

    /** The HUD's text now, or null when it shows nothing. */
    static String hudText(long now) {
        if (kickedAtMs == 0 || SkyblockGate.isOnSkyblock()) {
            return null;
        }
        long left = readyAtMs - now;
        return "Kicked from SkyBlock " + seconds(now - kickedAtMs) + " ago - "
                + (left > 0 ? "rejoin in " + seconds(left + 999) : "rejoin now!");
    }

    // ---- testkit -------------------------------------------------------------------------------------------------

    public static String lastTitle() {
        return lastTitle;
    }

    public static String lastHudText() {
        return lastHudText;
    }

    public static int sounds() {
        return sounds;
    }

    public static boolean running() {
        return kickedAtMs > 0;
    }

    public static long remainingMs() {
        return kickedAtMs == 0 ? 0 : Math.max(0, readyAtMs - System.currentTimeMillis());
    }

    public static boolean readyFired() {
        return readyFired;
    }

    public static void testReset() {
        clear();
        lastTitle = null;
        lastHudText = null;
    }

    /** Moves the kick back by {@code ms}, so a case need not wait a minute. */
    public static void testAge(long ms) {
        if (kickedAtMs > 0) {
            kickedAtMs -= ms;
            readyAtMs -= ms;
        }
    }

    public static final HudElement HUD = new HudElement() {
        private static final String EXAMPLE = "Kicked from SkyBlock 12s ago - rejoin in 48s";

        @Override
        public String id() {
            return "kick_rejoin_timer";
        }

        @Override
        public String displayName() {
            return "Kick Rejoin Timer";
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
            return 1 + Minecraft.getInstance().font.width("Kicked from SkyBlock 4m 59s ago - rejoin in 4m 59s");
        }

        @Override
        public int height() {
            return 1 + com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            return KickRejoinConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
            String text = hudText(System.currentTimeMillis());
            if (text == null) {
                if (!example) {
                    return;
                }
                text = EXAMPLE;
            } else if (!example) {
                lastHudText = text;
                HudSeen.markDrawn(id());
            }
            var font = Minecraft.getInstance().font;
            int cut = text.indexOf(" - ");
            String head = cut < 0 ? text : text.substring(0, cut);
            String tail = cut < 0 ? "" : text.substring(cut);
            graphics.text(font, head, x + 1, y + 1, 0xFFFF5555, true);
            graphics.text(font, tail, x + 1 + font.width(head), y + 1,
                    tail.contains("now") ? 0xFF55FF55 : 0xFF55FFFF, true);
        }
    };
}
