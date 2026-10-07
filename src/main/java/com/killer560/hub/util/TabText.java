package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The text of a tab-list entry's display name, flattened once per change instead of once per reader.
 *
 * <p>About a dozen features walk the tab list (Dungeon Info every tick, the party tracker, the score calculator,
 * Teammates, the island detector, Skyblock Area, ...) and each one called {@code getTabListDisplayName().getString()}
 * and stripped the colour codes of all ~80 entries on its own schedule, every time - the same 80 strings rebuilt and
 * regex-stripped several times a second while the entries change a few times a second at most (FPS sweep, 2026-10-07).
 *
 * <p>An entry's display name is replaced, not mutated, when the server updates it, so the memo is keyed on the
 * {@link Component} instance the entry holds now (and the language, which {@code getString} can depend on): a new
 * component is a miss and is flattened again, exactly as the reader would have. Off the client thread nothing is
 * memoised and the text is computed directly.
 */
public final class TabText {

    private record Memo(Component display, Language language, String raw, String plain) {
    }

    /** PlayerInfo does not override equals/hashCode, so this is an identity map that forgets departed entries. */
    private static final Map<PlayerInfo, Memo> MEMO = new WeakHashMap<>();

    private TabText() {
    }

    /** {@code getTabListDisplayName().getString()}, or null when the entry has no display name. */
    public static String raw(PlayerInfo info) {
        Memo m = memo(info);
        return m == null ? null : m.raw;
    }

    /**
     * {@link ChatObserver#stripCodes} of {@link #raw} - the same text {@code ChatFormatting.stripFormatting} gives -
     * or null when the entry has no display name.
     */
    public static String plain(PlayerInfo info) {
        Memo m = memo(info);
        return m == null ? null : m.plain;
    }

    private static Memo memo(PlayerInfo info) {
        Component display = info == null ? null : info.getTabListDisplayName();
        if (display == null) {
            return null;
        }
        Language language = Language.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.isSameThread()) {
            String raw = display.getString();
            return new Memo(display, language, raw, ChatObserver.stripCodes(raw));
        }
        Memo m = MEMO.get(info);
        if (m == null || m.display != display || m.language != language) {
            String raw = display.getString();
            m = new Memo(display, language, raw, ChatObserver.stripCodes(raw));
            MEMO.put(info, m);
        }
        return m;
    }
}
