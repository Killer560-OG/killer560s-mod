package com.killer560.hub.supporters;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat half of killer560's item 8.5. Hypixel formats almost all of its chat (public, party, guild, /msg) as
 * plain, unstructured system-chat text rather than vanilla's signed player-chat packets - the same reason
 * {@code ChatObserver}, {@code InteropChatParser} and Click Translate all work on raw chat lines instead of a
 * structured sender field. So instead of inventing a new chat hook, this reuses the exact one those features
 * already use: {@link ChatObserver#addRewriter}, the shared "rewrite a line right before it's added to chat"
 * funnel wired through {@code ChatComponentMixin} - confirmed (see that mixin's own doc) to catch every real
 * add path Hypixel actually uses.
 * <p>
 * Per {@code SUPPORTERS-CONTRACT.md} ("for chat, replace only the sender name token, and only when you can
 * tie the message to that player's UUID or exact current IGN"): {@link #SENDER} is anchored at the very
 * start of the line (mirrors {@code InteropChatParser.PARTY_LINE}'s precedent for Hypixel's own
 * {@code Channel > [rank] Name: message} shape, generalized to cover public/{@code /msg} chat too), so it can
 * only ever capture the SENDER position - never a supporter's name merely mentioned later in someone else's
 * message. The captured token must then exactly equal a supporter's CURRENT resolved ign
 * ({@link SupportersFeature#findChatSender}, never their stored vanity name) before anything is replaced.
 * Only the captured span is spliced; the rest of the line's styling (rank color, click/hover events) is
 * left completely alone.
 */
final class SupportersChatRewriter {

    // Optional channel prefix, any number of "[rank] " tags, the sender token, then ": ". Deliberately a
    // superset of Hypixel's known formats rather than one exact regex per channel - a false NEGATIVE here
    // just means a supporter's chat shows their real IGN for that one line, which is harmless; the exact-ign
    // check below is what keeps a false POSITIVE from ever mattering.
    private static final Pattern SENDER = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer) > |From |To )?(?:\\[[^\\]]{1,24}] )*(\\w{1,16}): ");

    private SupportersChatRewriter() {
    }

    static void register() {
        ChatObserver.addRewriter(SupportersChatRewriter::rewrite);
    }

    private static Component rewrite(Component message, String plain) {
        if (plain.isEmpty() || !SupportersFeature.cosmeticsActive()) {
            return null;
        }
        Matcher m = SENDER.matcher(plain);
        if (!m.find()) {
            return null;
        }
        String displayText = PlayerNameDisplay.findChatSender(m.group(1));
        if (displayText == null) {
            return null;
        }
        try {
            return NameSplice.splice(message, new int[]{m.start(1)}, new int[]{m.end(1)}, new String[]{displayText});
        } catch (RuntimeException e) {
            return null; // never let a cosmetic rewrite break the player's actual chat
        }
    }
}
