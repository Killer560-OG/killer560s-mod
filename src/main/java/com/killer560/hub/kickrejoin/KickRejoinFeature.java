package com.killer560.hub.kickrejoin;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.OutgoingChat;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

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
 *   <li>The wait is always {@link #WAIT_MS} from the kick line (killer560, 2026-11-03: "have it be 60 seconds no
 *       matter what that's what's hypixels rejoin policy"). Hypixel's refusals while waiting ("Cannot join SkyBlock for
 *       a moment! (Queue join in cooldown)", "You tried to rejoin too fast, please try again in a moment!") change
 *       nothing.</li>
 *   <li>Not counted: "You were spawned in Limbo." (an AFK / timeout limbo: his logs show SkyBlock again 10-20 s later
 *       with no refusal) and "There was a problem joining SkyBlock, try again in a moment!" (back 13 s later).</li>
 * </ul>
 * Every line is matched whole on the stripped text, so a player typing one (always behind a "name: " prefix) does
 * nothing. The timer clears once he is back on SkyBlock after having been off it, if he never left SkyBlock within
 * {@link #STAY_MS} (a failed island warp that left him where he was), or after 5 minutes.
 * <p>Party Message (off by default, his own text): once per kick, a second after the kick line and only once he is
 * really off SkyBlock, it asks Hypixel with "/pl" (killer560, 2026-11-03: "It can just do /pl to see if I am in a party
 * or not") and reads the reply, as it appears in his logs: a separator line, "Party Members (N)", a blank line, "Party
 * Leader: ...", optionally "Party Moderators: ..." and "Party Members: ...", a separator; or a separator, "You are not
 * in a party right now." (or "You are not currently in a party."), a separator. A member list means "/pc text"
 * {@link #PC_GAP_MS} after the reply; "not in a party", or no reply within {@link #PL_REPLY_MS}, means nothing. The reply
 * to our own /pl is hidden from his chat (only inside that window, and only those exact lines) and handed to
 * {@link PartyTracker}, which learns the party from it. Both go through {@link OutgoingChat}, the queue every automated line uses (0.5 s apart, one resend on a slow-down). A kick line that
 * restarts a running wait asks and sends nothing again. Nothing else is ever sent.
 */
public final class KickRejoinFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-kickrejoin");

    static final Pattern KICKED_JOINING = Pattern.compile("^You were kicked while joining that server!$");
    static final Pattern KICK_TO_LOBBY = Pattern.compile(
            "^An? (?:kick|exception) occurred in your connection, so you were put in the SkyBlock [lL]obby!$");
    static final long WAIT_MS = 60_000L;
    static final long PARTY_DELAY_MS = 1_000L;
    static final long PL_REPLY_MS = 3_000L;
    static final long PC_GAP_MS = 750L;
    /** A list that has not seen its closing separator is taken as finished this long after its last line. */
    static final long LIST_SETTLE_MS = 500L;

    static final Pattern SEPARATOR = Pattern.compile("^-{20,80}$");
    static final Pattern LIST_HEADER = Pattern.compile("^Party Members \\(\\d{1,2}\\)$");
    static final Pattern LIST_LINE = Pattern.compile("^Party (?:Leader|Moderators|Members): .{1,600}$");
    static final Pattern NOT_IN_PARTY = Pattern.compile(
            "^You are not (?:currently in a party\\.|in a party right now[.!])$");

    private enum Pl { NONE, WAIT, LIST, NOT }

    private static Pl pl = Pl.NONE;
    private static long plSentAtMs = 0;
    private static long plLastLineMs = 0;
    private static long pcAtMs = 0;
    static final long STAY_MS = 20_000L;
    static final long MAX_MS = 5 * 60_000L;

    private static long kickedAtMs = 0;
    private static long readyAtMs = 0;
    private static boolean seenOff = false;
    private static boolean readyFired = false;
    private static boolean partyDone = false;

    // ---- testkit ----
    private static volatile String lastTitle = null;
    private static volatile String lastHudText = null;
    private static volatile int sounds = 0;
    private static volatile String lastPartySent = null;
    private static volatile int partySends = 0;
    private static volatile int plSends = 0;
    private static volatile int hiddenLines = 0;
    private static volatile String plVerdict = null;

    private KickRejoinFeature() {
    }

    public static void register() {
        KickRejoinConfig.getInstance();
        ChatObserver.subscribe(m -> onChat(ChatObserver.strip(m)));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || message == null || pl == Pl.NONE) {
                return true;
            }
            try {
                return !hidePlReply(message, System.currentTimeMillis());
            } catch (RuntimeException e) {
                // Raw packet-path listener: a throw here disconnects from Hypixel. Never let one out.
                LOGGER.error("[KickRejoin] /pl reply read failed", e);
                return true;
            }
        });
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
        if (KICKED_JOINING.matcher(line).matches() || KICK_TO_LOBBY.matcher(line).matches()) {
            // A second kick line while the timer runs (the kick, then the failed rejoin's own kick) restarts the wait.
            start(now, now + WAIT_MS);
            LOGGER.info("[KickRejoin] kicked ('{}'): rejoin in {} s", line, WAIT_MS / 1000);
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
        partyDone = false;
        kickedAtMs = 0;
        readyAtMs = 0;
        seenOff = false;
        readyFired = false;
    }

    private static void tick(Minecraft client) {
        KickRejoinConfig cfg = KickRejoinConfig.getInstance();
        long now = System.currentTimeMillis();
        tickParty(cfg, now);
        if (kickedAtMs == 0) {
            return;
        }
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
        if (!partyDone && seenOff && now - kickedAtMs >= PARTY_DELAY_MS) {
            partyDone = true;
            sendParty(cfg);
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

    /** The 1 s point of a kick: ask Hypixel whether he is in a party. */
    private static void sendParty(KickRejoinConfig cfg) {
        if (!cfg.isPartyMessage() || KickRejoinConfig.chatSafe(cfg.getPartyText()).isEmpty()) {
            return;
        }
        pl = Pl.WAIT;
        plSentAtMs = System.currentTimeMillis();
        pcAtMs = 0;
        plVerdict = null;
        if (OutgoingChat.command("pl")) {
            plSends++;
            LOGGER.info("[KickRejoin] /pl sent to see whether he is in a party");
        } else {
            pl = Pl.NONE;
        }
    }

    /**
     * Whether this line is part of the reply to our /pl (then it is hidden and {@link PartyTracker} gets it). Only while
     * a /pl of ours is out, only within {@link #PL_REPLY_MS}, only Hypixel's exact reply lines.
     */
    private static boolean hidePlReply(Component message, long now) {
        if (now - plSentAtMs > PL_REPLY_MS) {
            return false;
        }
        String plain = ChatObserver.strip(message);
        if (plain == null) {
            return false;
        }
        String line = plain.trim();
        boolean hide = false;
        if (SEPARATOR.matcher(line).matches()) {
            hide = true;
            if (pl == Pl.LIST || pl == Pl.NOT) {
                decide(now); // the closing separator
            }
        } else if (pl == Pl.WAIT && LIST_HEADER.matcher(line).matches()) {
            pl = Pl.LIST;
            hide = true;
        } else if (pl == Pl.WAIT && NOT_IN_PARTY.matcher(line).matches()) {
            pl = Pl.NOT;
            hide = true;
        } else if (pl == Pl.LIST && (line.isEmpty() || LIST_LINE.matcher(line).matches())) {
            hide = true;
        }
        if (!hide) {
            return false;
        }
        plLastLineMs = now;
        hiddenLines++;
        if (!line.isEmpty() && !SEPARATOR.matcher(line).matches()) {
            PartyTracker.feed(message);
        }
        return true;
    }

    private static void decide(long now) {
        if (pl == Pl.LIST) {
            pcAtMs = now + PC_GAP_MS;
            plVerdict = "party";
            LOGGER.info("[KickRejoin] /pl: in a party - party message in {} ms", PC_GAP_MS);
        } else {
            plVerdict = pl == Pl.NOT ? "no party" : "no reply";
            LOGGER.info("[KickRejoin] /pl: {} - no party message", plVerdict);
        }
        pl = Pl.NONE;
    }

    private static void tickParty(KickRejoinConfig cfg, long now) {
        if (pl == Pl.LIST && now - plLastLineMs > LIST_SETTLE_MS) {
            decide(now); // the list ended without the closing separator reaching us
        } else if (pl == Pl.NOT && now - plLastLineMs > LIST_SETTLE_MS) {
            decide(now);
        } else if (pl == Pl.WAIT && now - plSentAtMs > PL_REPLY_MS) {
            decide(now); // no reply: nothing is sent
        }
        if (pcAtMs > 0 && now >= pcAtMs) {
            pcAtMs = 0;
            String text = KickRejoinConfig.chatSafe(cfg.getPartyText());
            if (cfg.isEnabled() && cfg.isPartyMessage() && !text.isEmpty() && OutgoingChat.command("pc " + text)) {
                lastPartySent = text;
                partySends++;
                LOGGER.info("[KickRejoin] party message sent");
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

    public static String lastPartySent() {
        return lastPartySent;
    }

    public static int partySends() {
        return partySends;
    }

    public static int plSends() {
        return plSends;
    }

    public static int hiddenLines() {
        return hiddenLines;
    }

    /** "party", "no party" or "no reply" for the last /pl, null before one is decided. */
    public static String plVerdict() {
        return plVerdict;
    }

    public static boolean readyFired() {
        return readyFired;
    }

    public static void testReset() {
        clear();
        lastTitle = null;
        lastHudText = null;
        lastPartySent = null;
        plVerdict = null;
        pl = Pl.NONE;
        pcAtMs = 0;
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
