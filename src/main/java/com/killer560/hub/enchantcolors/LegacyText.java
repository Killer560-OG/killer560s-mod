package com.killer560.hub.enchantcolors;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Flattens a lore {@link Component} back into a legacy "§"-coded string and back again.
 * <p>
 * Hypixel writes lore as 1.8 "§"-coded text; by the time it reaches the client it has been split into
 * styled component children. SkyHanni's enchant parser runs its patterns against the re-flattened,
 * codes-intact form (its {@code formattedTextCompat()}) for exactly this reason - the enchant NAME and its
 * colour have to be matched together, and the real strings contain duplicated/garbage runs like
 * {@code §5§r§d§l§r§d§lUltimate Wise V}. Same idea here.
 * <p>
 * Rebuilding is safe because Minecraft's own {@code StringDecomposer} still interprets "§" codes inside a
 * literal component (which is why every settings label in this mod can write {@code "§aON"}), and because a
 * legacy COLOUR code resets bold/italic - so a rebuilt line that starts with a colour code renders exactly
 * the way Hypixel's own line did.
 */
public final class LegacyText {

    private static final Map<Integer, Character> RGB_TO_CODE = buildRgbToCode();

    private LegacyText() {
    }

    private static Map<Integer, Character> buildRgbToCode() {
        Map<Integer, Character> map = new HashMap<>();
        for (ChatFormatting fmt : ChatFormatting.values()) {
            Integer rgb = fmt.getColor();
            if (fmt.isColor() && rgb != null) {
                map.putIfAbsent(rgb, fmt.getChar());
            }
        }
        return map;
    }

    /** @return the component's text with "§" codes re-inserted for every style change, or "" if empty. */
    public static String toLegacy(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        // visit(...) walks the whole tree with each child's style already resolved against its parents, so
        // the styles seen here are the effective ones - no manual style merging needed.
        component.visit((Style style, String text) -> {
            if (!text.isEmpty()) {
                appendCodes(out, style);
                out.append(text);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static void appendCodes(StringBuilder out, Style style) {
        TextColor color = style.getColor();
        Character code = color == null ? null : RGB_TO_CODE.get(color.getValue());
        if (code != null) {
            // A colour code clears every other legacy flag, so it must come first.
            out.append('§').append(code.charValue());
        } else if (color != null) {
            // Truecolor (no legacy equivalent): reset, then let the flags below re-apply. Hypixel lore never
            // uses these, but another mod's injected line might.
            out.append('§').append(ChatFormatting.RESET.getChar());
        }
        if (style.isObfuscated()) {
            out.append('§').append(ChatFormatting.OBFUSCATED.getChar());
        }
        if (style.isBold()) {
            out.append('§').append(ChatFormatting.BOLD.getChar());
        }
        if (style.isStrikethrough()) {
            out.append('§').append(ChatFormatting.STRIKETHROUGH.getChar());
        }
        if (style.isUnderlined()) {
            out.append('§').append(ChatFormatting.UNDERLINE.getChar());
        }
        if (style.isItalic()) {
            out.append('§').append(ChatFormatting.ITALIC.getChar());
        }
    }

    /**
     * The "§" codes still in effect at {@code index}, as a ready-to-emit prefix (e.g. {@code "§9"} or
     * {@code "§d§l"}). Used to re-state a colour explicitly on an enchant we are NOT recolouring: once an
     * earlier enchant on the same line has been given a new code, any following text without a code of its
     * own would otherwise inherit it.
     *
     * @return the prefix, {@code "§r"} if formatting is active but no colour was ever set, or {@code ""} if
     *         nothing is in effect at all.
     */
    public static String activeCodesAt(String legacy, int index) {
        char color = 0;
        StringBuilder formats = new StringBuilder();
        for (int i = 0; i + 1 < index && i + 1 < legacy.length(); i++) {
            if (legacy.charAt(i) != '§') {
                continue;
            }
            char code = Character.toLowerCase(legacy.charAt(i + 1));
            ChatFormatting fmt = ChatFormatting.getByCode(code);
            if (fmt == null) {
                continue;
            }
            if (fmt == ChatFormatting.RESET) {
                color = 0;
                formats.setLength(0);
            } else if (fmt.isColor()) {
                color = code;
                formats.setLength(0);
            } else if (formats.indexOf(String.valueOf(code)) < 0) {
                formats.append(code);
            }
            i++;
        }
        if (color == 0 && formats.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append('§').append(color == 0 ? ChatFormatting.RESET.getChar() : color);
        for (int i = 0; i < formats.length(); i++) {
            out.append('§').append(formats.charAt(i));
        }
        return out.toString();
    }

    /** Rebuilds a lore line, keeping the original line component's own root style so a rebuilt line that
     *  happens NOT to start with a colour code still inherits whatever the tooltip gave it. */
    public static Component rebuild(Component original, String legacy) {
        Style root = original == null ? Style.EMPTY : original.getStyle();
        return Component.empty().withStyle(root).append(Component.literal(legacy));
    }
}
