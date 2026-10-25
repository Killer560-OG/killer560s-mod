package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ChatObserver;
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
        if (!plain.startsWith("You are playing on profile: ") && !plain.startsWith("Profile ID: ")) {
            return; // PLAYING_ON / PROFILE_ID are anchored on these (fps-idle-1015)
        }
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
            String plain = com.killer560.hub.util.TabText.plain(info);
            // Only a line containing "Profile:" can match; testing for it first spares ~80 trims and Matchers per scan
            // in any world without that line, where this scans every 10 ticks forever (95-perf-features, 2026-10-15).
            if (plain == null || !plain.contains("Profile:")) {
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
        java.util.UUID id = client.getUser() == null ? null : client.getUser().getProfileId();
        // Asked every frame by the soul waypoints: built again only when the account or the profile name changes
        // (profileName is only ever replaced, so identity is exact; fps pass 2026-10-19).
        String name = profileName;
        KeyMemo memo = keyMemo;
        if (memo == null || memo.name != name || !java.util.Objects.equals(id, memo.id)) {
            String account = id == null ? "unknown" : id.toString();
            memo = new KeyMemo(name, id, account + "/" + (name.isEmpty() ? "?" : name.toLowerCase(Locale.ROOT)));
            keyMemo = memo;
        }
        return memo.value;
    }

    /** One immutable record, swapped whole, so a reader on another thread never pairs one name with another's key. */
    private record KeyMemo(String name, java.util.UUID id, String value) {
    }

    private static volatile KeyMemo keyMemo;

    public static String displayName() {
        return profileName.isEmpty() ? "unknown profile" : profileName;
    }
}
