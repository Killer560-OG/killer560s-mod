package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Skyblock profile the player is on, so the fairy-soul log is per profile (souls are found per profile, and a
 * second profile starts from zero).
 * <p>
 * Three sources, same ones Skyblocker's {@code utils/Utils} uses: the join message
 * "You are playing on profile: Mango", the "Profile ID: &lt;uuid&gt;" line (sent on join, and by {@code /profileid}),
 * and the tab list's "Profile: Mango" line. The key is the Minecraft account UUID plus the profile name, so two
 * accounts on the same PC never share a log.
 */
public final class ProfileTracker {

    private static final Pattern PLAYING_ON = Pattern.compile("^You are playing on profile: ([A-Za-z]+)\\b.*$");
    private static final Pattern PROFILE_ID = Pattern.compile("^Profile ID: ([0-9a-fA-F-]{32,36})$");
    private static final Pattern TAB_PROFILE = Pattern.compile("^Profile:\\s*([A-Za-z]+)\\b.*$");

    private static String profileName = "";
    private static String profileId = "";
    private static int ticks = 0;
    private static boolean subscribed = false;

    private ProfileTracker() {
    }

    public static void register() {
        if (subscribed) {
            return;
        }
        subscribed = true;
        ChatObserver.subscribe(ProfileTracker::onChat);
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message).trim();
        Matcher playing = PLAYING_ON.matcher(plain);
        if (playing.matches()) {
            setName(playing.group(1));
            return;
        }
        Matcher id = PROFILE_ID.matcher(plain);
        if (id.matches()) {
            profileId = id.group(1);
        }
    }

    /** Call once per client tick; the tab-list read happens twice a second. */
    public static void tick(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        if (ticks++ % 10 != 0 || !profileName.isEmpty()) {
            return;
        }
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_PROFILE.matcher(plain.trim());
            if (m.matches()) {
                setName(m.group(1));
                return;
            }
        }
    }

    private static void setName(String name) {
        String clean = name.trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase(profileName)) {
            return;
        }
        String previousKey = key();
        profileName = clean;
        FairySoulStore.onProfileChanged(previousKey, key(), profileName, profileId);
    }

    public static void onWorldChange() {
        // The profile can change on a world switch; it is re-announced in chat right after joining.
        profileName = "";
    }

    public static String profileName() {
        return profileName;
    }

    public static String profileId() {
        return profileId;
    }

    /** Storage key: "&lt;account uuid&gt;/&lt;profile name&gt;", or ".../?" until the profile is known. */
    public static String key() {
        Minecraft client = Minecraft.getInstance();
        String account = client.getUser() == null || client.getUser().getProfileId() == null
                ? "unknown" : client.getUser().getProfileId().toString();
        return account + "/" + (profileName.isEmpty() ? "?" : profileName.toLowerCase(Locale.ROOT));
    }

    public static String displayName() {
        return profileName.isEmpty() ? "unknown profile" : profileName;
    }
}
