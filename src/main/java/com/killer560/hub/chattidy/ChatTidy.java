package com.killer560.hub.chattidy;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chat Tidy (killer560, 2026-10-07): "make a setting so getting 2x chat messages stack them and make a hider for things
 * like `Your Implosion hit 2 enemies for 14,736,463.2 damage.` or `A Crypt Wither Skull exploded, hitting you for
 * 23,760`".
 * <p>
 * Both act at DISPLAY time, from {@code chattidy/mixin/ChatTidyMixin} inside {@code ChatComponent.addMessage}, after
 * Fabric's receive events and after {@link ChatObserver} has dispatched the line (its hook sits at that method's HEAD,
 * this one later, at the visible-filter call). So every listener in the mod still sees a hidden line and every copy of
 * a stacked one, and vanilla still writes each original to the log as {@code [CHAT]}. Nothing here runs on a raw
 * packet listener; the mixin catches anything thrown and lets vanilla add the line as normal.
 * <p>
 * <b>Stack Duplicate Messages</b>: a line identical to the NEWEST line in chat (same text, same colours and formatting,
 * same click events; hover is ignored) replaces it with one copy ending in a grey {@code (x2)}, {@code (x3)} ...
 * Consecutive only: stacking across other lines would move an old line down to the bottom out of order, and "2x chat
 * messages" is the case asked for. Never stacked: blank lines and lines with no letter or digit (Hypixel's
 * {@code -----} / {@code ▬▬▬} separators, so two back-to-back boxes keep their spacing), signed player chat (vanilla
 * deletes those by signature), and a line from a different source or tag than the one above it.
 * <p>
 * <b>Hide Damage Messages</b>: Hypixel's combat spam, two families, from his own Dungeons log (2026-09 to 2026-10-07,
 * every shape listed in docs/FEATURES.md), SkyHanni 7.48.0 {@code DungeonChatFilter} and Skyblocker 6.9.1's Implosion /
 * Spirit Sceptre / Molten Wave filters. Server lines only ({@code SYSTEM_SERVER}), and every pattern is anchored on the
 * whole line with a name class that cannot hold {@code :} or {@code [}, so a player typing the same text in any channel
 * ({@code Party > [MVP+] Eve: Your Implosion hit ...}) is never hidden.
 */
public final class ChatTidy {

    private static final Logger LOGGER = ModLog.get("killer560smod-chattidy");

    /** Hypixel writes damage with thousands commas ({@code 14,736,463.2}) except some boss hits ({@code 10800.0}). */
    private static final String NUM = "-?(?:\\d{1,3}(?:,\\d{3}){1,6}|\\d{1,12})(?:\\.\\d{1,2})?";
    /** A mob, trap or boss-ability name: starts upper-case, letters/apostrophes/spaces only - never a chat prefix. */
    private static final String NAME = "[A-Z][A-Za-z' ]{0,47}";
    private static final int MAX_LINE = 160;

    /** Your Implosion / Guided Sheep / Spirit Sceptre / Witherborn / Explosive Shot / Spirit Pet / Thunderstorm /
     *  Molten Wave ... hit N enemy|enemies for N damage. */
    static final Pattern ABILITY_DAMAGE = Pattern.compile(
            "^Your [A-Z][A-Za-z' -]{0,39} hit \\d{1,4} enem(?:y|ies) for " + NUM + " damage\\.$");

    static final Pattern[] INCOMING_HITS = {
            // A Crypt Wither Skull / Spirit Sheep / Chicken Mine exploded, hitting you for N damage.
            Pattern.compile("^A " + NAME + " exploded, hitting you for " + NUM + " damage\\.$"),
            // Maxor's Frenzy hit you for N damage. / The Arrow Trap hit you for N damage! / Storm's Giga Lightning
            // hit you for N true damage.
            Pattern.compile("^" + NAME + " hit you for " + NUM + " (?:true )?damage[.!]$"),
            // SkyHanni's "<x> hit you with <y> for N damage!" (not in his logs).
            Pattern.compile("^" + NAME + " hit you with " + NAME + " for " + NUM + " damage!$"),
            // The Stormy Fels struck you for N damage! / The Spirit Chicken's lightning struck you for N damage.
            Pattern.compile("^" + NAME + " struck you for " + NUM + " damage[.!]$"),
            // The Lost Adventurer used Dragon's Breath on you! / The Frozen Adventurer used Ice Spray on you!
            Pattern.compile("^The " + NAME + " used " + NAME + " on you!$"),
            // Your bone plating reduced the damage you took by N!
            Pattern.compile("^Your bone plating reduced the damage you took by " + NUM + "!$"),
    };

    // Stacking state: the GuiMessage this feature last put at the top, what it stacked, and how many times.
    private static GuiMessage stackTop;
    private static Component stackBase;
    private static Key stackKey;
    private static int stackCount;

    // Test hooks.
    private static long hidden;
    private static long stacked;
    private static long failures;
    private static volatile String lastFailure;
    private static final ArrayDeque<String> OBSERVED = new ArrayDeque<>();

    private ChatTidy() {
    }

    public static void register() {
        ChatTidyConfig.getInstance();
        // Only for the test hook below: proves a hidden line still reached the mod's chat listeners.
        ChatObserver.subscribe(message -> {
            String plain = ChatObserver.strip(message);
            synchronized (OBSERVED) {
                OBSERVED.addLast(plain.length() > 200 ? plain.substring(0, 200) : plain);
                while (OBSERVED.size() > 32) {
                    OBSERVED.removeFirst();
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hider
    // ------------------------------------------------------------------------------------------------------------

    /** Called from the mixin. True drops the line from the chat window (it was already observed and is still logged). */
    public static boolean shouldHide(Component message, GuiMessageSource source) {
        if (source != GuiMessageSource.SYSTEM_SERVER || message == null) {
            return false;
        }
        ChatTidyConfig cfg = ChatTidyConfig.getInstance();
        boolean ability = cfg.isHideAbilityDamage();
        boolean incoming = cfg.isHideIncomingHits();
        if (!ability && !incoming) {
            return false;
        }
        boolean hide = (ability && isAbilityDamage(ChatObserver.strip(message)))
                || (incoming && isIncomingHit(ChatObserver.strip(message)));
        if (hide) {
            hidden++;
        }
        return hide;
    }

    public static boolean isAbilityDamage(String plain) {
        return plain != null && plain.length() <= MAX_LINE && ABILITY_DAMAGE.matcher(plain).matches();
    }

    public static boolean isIncomingHit(String plain) {
        if (plain == null || plain.length() > MAX_LINE) {
            return false;
        }
        for (Pattern p : INCOMING_HITS) {
            if (p.matcher(plain).matches()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Stacking
    // ------------------------------------------------------------------------------------------------------------

    public static boolean stackingOn() {
        return ChatTidyConfig.getInstance().isStackDuplicates();
    }

    /**
     * The content to show in place of {@code top} when {@code incoming} repeats it, or null when it does not. The
     * returned line is the FIRST copy's component (its click and hover events) plus a grey {@code (xN)}.
     */
    public static Component stack(GuiMessage top, GuiMessage incoming) {
        if (top == null || incoming == null || top.signature() != null || incoming.signature() != null
                || top.source() != incoming.source() || !Objects.equals(top.tag(), incoming.tag())) {
            return null;
        }
        String plain = ChatObserver.strip(incoming.content());
        if (!stackable(plain)) {
            return null;
        }
        Component base;
        Key topKey;
        int count;
        if (top == stackTop && stackBase != null) {
            base = stackBase;
            topKey = stackKey;
            count = stackCount;
        } else {
            base = top.content();
            topKey = key(base);
            count = 1;
        }
        if (!topKey.equals(key(incoming.content()))) {
            return null;
        }
        count++;
        stackBase = base;
        stackKey = topKey;
        stackCount = count;
        stackTop = null; // set by stacked() once the replacement is really in the chat
        return base.copy().append(Component.literal(" (x" + count + ")").withStyle(ChatFormatting.GRAY));
    }

    /** The mixin put {@code replacement} at the top of chat. */
    public static void stacked(GuiMessage replacement) {
        stackTop = replacement;
        stacked++;
    }

    /** Something to stack: not blank, and not a pure separator ({@code -----}, {@code ▬▬▬▬}). */
    static boolean stackable(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        for (int i = 0; i < plain.length(); i++) {
            if (Character.isLetterOrDigit(plain.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** What two lines must share to stack: the visible text and, per run, the style (minus hover). */
    record Key(String text, List<Style> styles) {
    }

    static Key key(Component content) {
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        Style[] last = {null};
        content.getVisualOrderText().accept((position, style, codepoint) -> {
            Style s = style.withHoverEvent(null);
            if (!s.equals(last[0])) {
                styles.add(s);
                text.append('￿');
                last[0] = s;
            }
            text.appendCodePoint(codepoint);
            return true;
        });
        return new Key(text.toString(), styles);
    }

    public static void fail(RuntimeException e) {
        failures++;
        lastFailure = e.toString();
        LOGGER.error("[ChatTidy] chat add hook threw; the line was added as normal", e);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test hooks (testkit, by reflection)
    // ------------------------------------------------------------------------------------------------------------

    /** The newest {@code max} messages in the chat window, newest first, as plain text. Render thread only. */
    public static List<String> testChatLines(int max) {
        List<String> out = new ArrayList<>();
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        List<GuiMessage> all = ((com.killer560.hub.chattidy.mixin.ChatTidyAccessor) chat).killer560smod$getAllMessages();
        for (int i = 0; i < all.size() && i < max; i++) {
            out.add(all.get(i).content().getString());
        }
        return out;
    }

    /** The colour of the last run of the newest chat message ("gray" for a stack suffix), or "" if none. */
    public static String testNewestLastColour() {
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        List<GuiMessage> all = ((com.killer560.hub.chattidy.mixin.ChatTidyAccessor) chat).killer560smod$getAllMessages();
        if (all.isEmpty()) {
            return "";
        }
        Key k = key(all.get(0).content());
        if (k.styles().isEmpty() || k.styles().get(k.styles().size() - 1).getColor() == null) {
            return "";
        }
        return k.styles().get(k.styles().size() - 1).getColor().toString();
    }

    public static List<String> testObserved() {
        synchronized (OBSERVED) {
            return new ArrayList<>(OBSERVED);
        }
    }

    public static long testHidden() {
        return hidden;
    }

    public static long testStacked() {
        return stacked;
    }

    public static long testFailures() {
        return failures;
    }

    public static String testLastFailure() {
        return lastFailure;
    }
}
