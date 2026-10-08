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
 * Chat Hider (killer560, 2026-10-08: "Combine hide chat and tidy chat into one setting called Chat Hider") - the
 * display-time half: Stack Duplicate Messages and Hide Damage Messages, both under Chat Hider's master switch
 * ({@link ChatTidyConfig}). The other six hides (Hide Useless Messages ... Hide Non-Rank Invites) are matched in
 * {@code objecthider/ObjectHiderFeature} at receive time, as before. Chat Tidy first shipped 2026-10-07 ("make a setting
 * so getting 2x chat messages stack them and make a hider for things like `Your Implosion hit 2 enemies for
 * 14,736,463.2 damage.` or `A Crypt Wither Skull exploded, hitting you for 23,760`").
 * <p>
 * Both act at DISPLAY time, from {@code chattidy/mixin/ChatTidyMixin} inside {@code ChatComponent.addMessage}, after
 * Fabric's receive events and after {@link ChatObserver} has dispatched the line (its hook sits at that method's HEAD,
 * this one later, at the visible-filter call). So every listener in the mod still sees a hidden line and every copy of
 * a stacked one, and vanilla still writes each original to the log as {@code [CHAT]}. Nothing here runs on a raw
 * packet listener; the mixin catches anything thrown and lets vanilla add the line as normal.
 * <p>
 * <b>Stack Duplicate Messages</b>: a line identical to one of the last {@value #STACK_WINDOW_MESSAGES} messages in chat
 * that arrived at most {@value #STACK_WINDOW_SECONDS} s ago (same text, same colours and formatting, same click events;
 * hover is ignored) takes that copy's place: the earlier copy is removed and one line ending in a grey {@code (x2)},
 * {@code (x3)} ... is added at the bottom, where the newest chat is read (killer560, 2026-10-08: "if a message comes in
 * between something that would stack then they should still stack"; until then only consecutive repeats stacked). The
 * window is bounded both ways so an old line is never pulled down from far up the history, and the search is at most
 * 50 identity look-ups per line. Never stacked: blank lines and lines with no letter or digit (Hypixel's {@code -----}
 * / {@code ▬▬▬} separators, so back-to-back boxes keep their spacing), signed player chat (vanilla deletes those by
 * signature), a line from a different source or tag, and a copy the chat's visible filter hides.
 * <p>
 * <b>Hide Damage Messages</b>: Hypixel's combat spam, two families (ability damage and incoming hits, one switch since
 * 2026-10-08: "Hide damage messages should hide ability and incoming hit lines no matter what"), from his own Dungeons
 * log (2026-09 to 2026-10-07, every shape listed in docs/FEATURES.md), SkyHanni 7.48.0 {@code DungeonChatFilter} and
 * Skyblocker 6.9.1's Implosion / Spirit Sceptre / Molten Wave filters. Server lines only ({@code SYSTEM_SERVER}), and
 * every pattern is anchored on the whole line with a name class that cannot hold {@code :} or {@code [}, so a player
 * typing the same text in any channel ({@code Party > [MVP+] Eve: Your Implosion hit ...}) is never hidden.
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

    /** How far back a repeat may find its earlier copy: this many chat messages, and ... */
    public static final int STACK_WINDOW_MESSAGES = 50;
    /** ... no older than this (60 s; {@code GuiMessage.addedTime} is in gui ticks). */
    public static final int STACK_WINDOW_SECONDS = 60;
    private static final int STACK_WINDOW_TICKS = STACK_WINDOW_SECONDS * 20;

    /** A stacked line this feature put into chat: the first copy's component, its key, and the count shown. */
    private static final class Stack {
        final GuiMessage message;
        final Component base;
        final Key key;
        final int count;

        Stack(GuiMessage message, Component base, Key key, int count) {
            this.message = message;
            this.base = base;
            this.key = key;
            this.count = count;
        }
    }

    /** Live stacks, newest last, by GuiMessage identity (a record's equals compares content). Bounded. */
    private static final ArrayList<Stack> STACKS = new ArrayList<>();
    private static final int MAX_STACKS = 64;
    /** Keys of recent un-stacked messages, by identity, so a repeat costs one key build, not fifty. Bounded. */
    private static final java.util.IdentityHashMap<GuiMessage, Key> KEYS = new java.util.IdentityHashMap<>();
    private static final ArrayDeque<GuiMessage> KEY_ORDER = new ArrayDeque<>();
    private static final int MAX_KEYS = 160;

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
        if (!ChatTidyConfig.getInstance().isHideDamageMessages()) {
            return false;
        }
        String plain = ChatObserver.strip(message);
        boolean hide = isAbilityDamage(plain) || isIncomingHit(plain);
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

    /** Where a repeat stacks: the earlier copy's index in {@code allMessages} and the line to show in its place. */
    public record Match(int index, GuiMessage earlier, Component content, Component base, Key key, int count) {
    }

    /**
     * The earlier copy {@code incoming} repeats within the window, or null. {@code all} is the chat's
     * {@code allMessages} (newest first); {@code visible} is its visible-message filter. The returned content is the
     * FIRST copy's component (its click and hover events) plus a grey {@code (xN)}.
     */
    public static Match findStack(List<GuiMessage> all, GuiMessage incoming, java.util.function.Predicate<GuiMessage> visible) {
        if (incoming == null || incoming.signature() != null || all.isEmpty()) {
            return null;
        }
        String plain = ChatObserver.strip(incoming.content());
        if (!stackable(plain)) {
            return null;
        }
        Key incomingKey = key(incoming.content());
        int n = Math.min(STACK_WINDOW_MESSAGES, all.size());
        for (int i = 0; i < n; i++) {
            GuiMessage earlier = all.get(i);
            if (incoming.addedTime() - earlier.addedTime() > STACK_WINDOW_TICKS) {
                break; // newest first: everything further up is older still
            }
            if (earlier.signature() != null || earlier.source() != incoming.source()
                    || !Objects.equals(earlier.tag(), incoming.tag()) || !visible.test(earlier)) {
                continue;
            }
            Stack stack = stackOf(earlier);
            Key earlierKey = stack != null ? stack.key : cachedKey(earlier);
            if (!earlierKey.equals(incomingKey)) {
                continue;
            }
            Component base = stack != null ? stack.base : earlier.content();
            int count = (stack != null ? stack.count : 1) + 1;
            Component shown = base.copy().append(Component.literal(" (x" + count + ")").withStyle(ChatFormatting.GRAY));
            return new Match(i, earlier, shown, base, earlierKey, count);
        }
        remember(incoming, incomingKey);
        return null;
    }

    /** The mixin replaced {@code match.earlier()} with {@code replacement} at the bottom of chat. */
    public static void stacked(Match match, GuiMessage replacement) {
        Stack old = stackOf(match.earlier());
        if (old != null) {
            STACKS.remove(old);
        }
        STACKS.add(new Stack(replacement, match.base(), match.key(), match.count()));
        while (STACKS.size() > MAX_STACKS) {
            STACKS.remove(0);
        }
        stacked++;
    }

    /** {@code message}'s content without a stack count this feature added (Copy Chat copies the message itself). */
    public static Component unstackedContent(GuiMessage message) {
        Stack stack = stackOf(message);
        return stack != null ? stack.base : message.content();
    }

    private static Stack stackOf(GuiMessage message) {
        for (int i = STACKS.size() - 1; i >= 0; i--) {
            if (STACKS.get(i).message == message) {
                return STACKS.get(i);
            }
        }
        return null;
    }

    private static Key cachedKey(GuiMessage message) {
        Key k = KEYS.get(message);
        if (k == null) {
            k = key(message.content());
            remember(message, k);
        }
        return k;
    }

    private static void remember(GuiMessage message, Key k) {
        if (KEYS.put(message, k) == null) {
            KEY_ORDER.addLast(message);
            while (KEY_ORDER.size() > MAX_KEYS) {
                KEYS.remove(KEY_ORDER.removeFirst());
            }
        }
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
    public record Key(String text, List<Style> styles) {
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

    /** Wrapped chat lines whose message is no longer in the chat (a stack that left a line behind), and lines in
     *  {@code trimmedMessages} in total. Render thread. */
    public static int[] testTrimmedOrphans() {
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        List<GuiMessage> all = ((com.killer560.hub.chattidy.mixin.ChatTidyAccessor) chat).killer560smod$getAllMessages();
        List<GuiMessage.Line> lines = ((com.killer560.hub.copychat.mixin.ChatComponentAccessor) chat).killer560smod$getTrimmedMessages();
        java.util.Set<GuiMessage> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        live.addAll(all);
        int orphans = 0;
        for (GuiMessage.Line line : lines) {
            if (!live.contains(line.parent())) {
                orphans++;
            }
        }
        return new int[]{orphans, lines.size()};
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
