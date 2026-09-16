package com.killer560.hub.leapmessage;

import com.killer560.hub.cringe.CringeFeature;
import com.killer560.hub.translate.TranslateFeature;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Announces every Spirit Leap to Party Chat, per killer560's request - the part of Noamm's Leap Menu
 * he actually wanted (its "Announce Leap"/"Leap Message" sub-feature), not the whole custom menu.
 * Detected off Hypixel's own "You have teleported to X!" chat line (the exact string Noamm's own
 * CC0 {@code LeapMenu.kt} matches on - confirmed, not guessed). Two independent message types,
 * each with its own toggle - if both are on, both get sent:
 * <ul>
 *   <li>Leaping To - a custom, user-typed message ({@link LeapMessageConfig#getCustomMessage()}),
 *   with any {@code {name}} in it replaced by the target's IGN. Sent first.</li>
 *   <li>Cringe - runs this mod's own random cringe-line generator (the actual reason this feature
 *   exists: Noamm's own leap-message setting can only send fixed text, it has no way to invoke a
 *   different mod's command, which is what killer560 originally tried and couldn't get working). Sent
 *   0.5s after Leaping To if both are on.</li>
 * </ul>
 * If Leaping To is off, Cringe (when on) goes out immediately instead - the delay only exists to
 * space the two messages apart when both are actually being sent.
 */
public final class LeapMessageFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-leapmessage");

    private static final Pattern LEAP_MESSAGE = Pattern.compile("^You have teleported to (.+)!$");
    /** The IGN at the end of whatever the line captured, so a rank prefix or a stray formatting code never ends up
     *  in the party message (same trailing-IGN pattern the custom leap menu reads heads with). */
    private static final Pattern IGN = Pattern.compile("([A-Za-z0-9_]{1,16})\s*$");
    private static final int DELAY_TICKS = 10; // 0.5s at the normal 20 ticks/sec

    // Single pending slot, not a queue - leaps happening less than 0.5s apart would clobber a still-
    // pending one, but that's not a realistic scenario (Spirit Leap has its own cooldown far longer
    // than half a second), so a queue would just be unused complexity.
    private static int delayTicksRemaining = -1;
    private static Runnable pendingAction;

    // Real bug found and fixed (2026-09-14): matched message.getString() without stripping § codes (unlike the
    // other chat features here), so a formatted "§dYou have teleported to §bName§d!" line could never match -
    // and only listened on Fabric's GAME event, which never fires for a line another mod cancels via
    // ALLOW_GAME and re-adds to chat itself (confirmed happening to device lines with Odin installed).
    // ChatObserver sees both paths once; the text is stripped before matching.
    public static void register() {
        ChatObserver.subscribe(message -> onGameMessage(ChatObserver.strip(message)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        if (delayTicksRemaining < 0) {
            return;
        }
        delayTicksRemaining--;
        if (delayTicksRemaining <= 0) {
            delayTicksRemaining = -1;
            Runnable action = pendingAction;
            pendingAction = null;
            if (action != null) {
                action.run();
            }
        }
    }

    private static void onGameMessage(String text) {
        LeapMessageConfig cfg = LeapMessageConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        Matcher m = LEAP_MESSAGE.matcher(text);
        if (!m.matches()) {
            if (text.contains("You have teleported to")) {
                // Diagnostic (2026-09-14): a leap line that the exact regex rejected (e.g. stray formatting).
                LOGGER.warn("[LeapMessage] Leap-like line did NOT match regex (no party message sent): \"{}\"", text);
            }
            return;
        }
        String targetName = ign(m.group(1));
        LOGGER.info("[LeapMessage] Leap detected to \"{}\": leapingTo={} (message blank={}), cringe={}{}",
                targetName, cfg.isLeapingToEnabled(), cfg.getCustomMessage().isBlank(), cfg.isCringeEnabled(),
                delayTicksRemaining >= 0 ? " - NOTE: overwriting a still-pending delayed cringe send" : "");

        boolean sentLeapingTo = false;
        if (cfg.isLeapingToEnabled()) {
            sendLeapingTo(targetName);
            sentLeapingTo = true;
        }
        if (cfg.isCringeEnabled()) {
            if (sentLeapingTo) {
                pendingAction = () -> CringeFeature.sendRandom("pc");
                delayTicksRemaining = DELAY_TICKS;
            } else {
                CringeFeature.sendRandom("pc");
            }
        }
    }

    /** @return the plain IGN in {@code captured} (formatting stripped, rank prefix dropped), else the trimmed text. */
    private static String ign(String captured) {
        String plain = ChatObserver.strip(captured);
        Matcher m = IGN.matcher(plain);
        return m.find() ? m.group(1) : plain;
    }

    private static void sendLeapingTo(String targetName) {
        String message = LeapMessageConfig.getInstance().getCustomMessage().replace("{name}", targetName);
        if (!message.isBlank()) {
            TranslateFeature.sendGenerated(message, "pc");
        }
    }

    private LeapMessageFeature() {
    }
}
