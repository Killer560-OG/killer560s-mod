package com.killer560.hub.modusers;

import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Mod User Tag in the tab list (killer560, 2026-10-19). Called from {@code ModUserTabMixin} at RETURN of
 * {@code PlayerTabOverlay.getNameForDisplay(PlayerInfo)}, which {@code extractRenderState} calls for every row.
 * <p>
 * On Skyblock every visible row is one of Hypixel's fake entries (an {@code !A-a} profile) whose display name carries the
 * real player's name as TEXT - "[400] Name (Mage 50)" - so the row's profile name never matches a player. The first
 * version keyed on the profile name and so never tagged a single row, his own included. This looks up the profile name
 * first (a plain server's real rows) and otherwise finds a mod user's name as a whole name-shaped word in Hypixel's own
 * row text ({@code getTabListDisplayName()}), never in the shown text, so a supporter custom name spliced in by
 * {@code SupportersTabListMixin} cannot hide the real name. The diamond goes at the front of the row.
 * <p>
 * The answer is cached per {@link PlayerInfo} against the row's display-name object, the relay list's version and the
 * settings. Render thread only.
 */
public final class ModUserTabTag {

    private static final Logger LOGGER = ModLog.get("killer560smod-modusers");
    private static boolean loggedOnce = false;

    private record Row(Component source, String profileName, String own, boolean showOwn, String shareAs,
            boolean seeOthers, int version, String variant) {
    }

    private static final Map<PlayerInfo, Row> ROWS = new WeakHashMap<>();

    private ModUserTabTag() {
    }

    /** {@code shown} with the mod user diamond in front, or {@code null} to leave the row alone. */
    public static Component apply(PlayerInfo info, Component shown) {
        if (info == null || shown == null || info.getProfile() == null) {
            return null;
        }
        String variant;
        try {
            variant = variantFor(info);
        } catch (RuntimeException e) {
            return null; // a cosmetic must never break the tab list
        }
        if (variant == null) {
            return null;
        }
        ChatFormatting colour = "cheat".equals(variant) ? ChatFormatting.RED : ChatFormatting.GREEN;
        if (!loggedOnce) {
            loggedOnce = true;
            LOGGER.info("Mod User Tag: tab list hook ran, tagged a row as {}", variant);
        }
        return Component.literal("◆ ").withStyle(colour).append(shown);
    }

    private static String variantFor(PlayerInfo info) {
        ModUserConfig cfg = ModUserConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        String own = client.player != null ? client.player.getGameProfile().name() : null;
        boolean showOwn = cfg.isShare();
        boolean seeOthers = cfg.isSeeOthers();
        if (!showOwn && !seeOthers) {
            return null;
        }
        String shareAs = cfg.getShareAs();
        int version = ModUsers.version();
        Component source = info.getTabListDisplayName();
        String profileName = info.getProfile().name();
        Row row = ROWS.get(info);
        // Compared field by field: a joined key string would allocate for every row on every frame.
        if (row != null && row.source() == source && row.showOwn() == showOwn && row.seeOthers() == seeOthers
                && row.version() == version && Objects.equals(row.profileName(), profileName)
                && Objects.equals(row.own(), own) && row.shareAs().equals(shareAs)) {
            return row.variant();
        }
        String variant = lookup(profileName, own, showOwn, seeOthers, shareAs);
        if (variant == null && source != null) {
            variant = scan(source.getString(), own, showOwn, seeOthers, shareAs);
        }
        ROWS.put(info, new Row(source, profileName, own, showOwn, shareAs, seeOthers, version, variant));
        return variant;
    }

    /** The first name-shaped word in {@code plain} that is a mod user (or you, while you show yourself). */
    private static String scan(String plain, String own, boolean showOwn, boolean seeOthers, String shareAs) {
        int n = plain.length();
        int i = 0;
        while (i < n) {
            if (!isNameChar(plain.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isNameChar(plain.charAt(j))) {
                j++;
            }
            if (j - i >= 3 && j - i <= 16) {
                String v = lookup(plain.substring(i, j), own, showOwn, seeOthers, shareAs);
                if (v != null) {
                    return v;
                }
            }
            i = j;
        }
        return null;
    }

    private static String lookup(String name, String own, boolean showOwn, boolean seeOthers, String shareAs) {
        if (name == null) {
            return null;
        }
        if (name.equals(own)) {
            return showOwn ? shareAs : null;
        }
        return seeOthers ? ModUsers.variantOf(name) : null;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
