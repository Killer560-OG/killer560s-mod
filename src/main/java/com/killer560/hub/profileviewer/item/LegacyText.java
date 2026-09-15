package com.killer560.hub.profileviewer.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Turns a 1.8 "§"-coded string (item names/lore in Hypixel's API NBT) into a styled Component with an
 * explicit non-italic base, the same idea as meowdding item-data-fixer's {@code LegacyTextFixer} -
 * otherwise modern tooltips render custom names and lore italic/purple. Legacy semantics: a color code
 * resets formatting, format codes stack, {@code §r} resets.
 */
public final class LegacyText {

    private static final Style BASE = Style.EMPTY.withItalic(false);

    private LegacyText() {
    }

    public static Component parse(String text) {
        MutableComponent out = Component.empty().withStyle(BASE);
        if (text == null || text.isEmpty()) {
            return out;
        }
        Style style = BASE.withColor(ChatFormatting.WHITE);
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                i++;
                if (fmt == null) {
                    continue;
                }
                if (buf.length() > 0) {
                    out.append(Component.literal(buf.toString()).withStyle(style));
                    buf.setLength(0);
                }
                if (fmt == ChatFormatting.RESET) {
                    style = BASE.withColor(ChatFormatting.WHITE);
                } else if (fmt.isColor()) {
                    style = BASE.withColor(fmt);
                } else {
                    style = style.applyFormat(fmt);
                }
                continue;
            }
            buf.append(c);
        }
        if (buf.length() > 0) {
            out.append(Component.literal(buf.toString()).withStyle(style));
        }
        return out;
    }

    public static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }
}
