package com.killer560.hub.namechanger;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * "Name Changer" / nick hider suite (killer560's roadmap: change your own displayed IGN client-side, rename specific
 * other players client-side, and a mode that randomizes everyone else's name).
 * <p>
 * How it works (modelled on quoi's {@code NickHider} + {@code FontMixin}, itself a port of Ownwn's Client-Custom-Name):
 * every piece of text the client draws - chat, name tags, tab list, scoreboard, item lore, container titles, tooltips -
 * ends up in {@code Font.prepareText(...)} (GUI text via {@code GuiTextRenderState.ensurePrepared}, world text via
 * {@code Font.drawInBatch}), and layout goes through {@code Font.width(...)}. {@code NameChangerFontMixin} rewrites
 * the text at exactly those two points, so nothing upstream (the chat log, components, item NBT, the chat input
 * box, commands, packets) is ever modified - replacement is purely what gets painted. Per-glyph styles are kept
 * (the replacement inherits the style of the name's first letter), so chat click/hover events keep working.
 * Text typed into edit boxes is deliberately left untouched ({@code NameChangerEditBoxMixin}) so what you see
 * while typing is exactly what gets sent.
 * <p>
 * Ships disabled by default. {@link #register()} must be called from the client entrypoint (it drives the
 * randomize-others player collection).
 */
public final class NameChangerFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-namechanger");

    /** Stable per-launch seed so "randomize others" gives the same fake name for a player all session. */
    private static final long SESSION_SEED = new SplittableRandom().nextLong();
    private static final int MAX_SEEN_PLAYERS = 4000;

    private static final Map<String, String> RANDOM_NAMES = new HashMap<>();
    private static final Set<String> SEEN_PLAYERS = new LinkedHashSet<>();

    private static volatile NameTable table;
    private static volatile int builtConfigVersion = -1;
    private static volatile int seenVersion = 0;
    private static volatile int builtSeenVersion = -1;
    private static volatile String builtOwnName = null;
    private static int tickCounter = 0;

    private NameChangerFeature() {
    }

    public static void register() {
        NameChangerConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(NameChangerFeature::tick);
    }

    private static void tick(Minecraft client) {
        // Ticks run between frames on the same thread, so no EditBox can be mid-render here: clears the suppression
        // counter in case an exception ever skipped the RETURN hook.
        NameReplacer.resetSuppress();
        if (++tickCounter % 20 != 0) {
            return;
        }
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isRandomizeOthers()) {
            return;
        }
        boolean added = false;
        String own = client.getUser() == null ? "" : client.getUser().getName();
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                added |= notePlayer(info.getProfile(), own);
            }
        }
        if (client.level != null) {
            for (Player player : client.level.players()) {
                added |= notePlayer(player.getGameProfile(), own);
            }
        }
        if (added) {
            synchronized (SEEN_PLAYERS) {
                seenVersion++;
            }
        }
    }

    private static boolean notePlayer(GameProfile profile, String ownName) {
        if (profile == null) {
            return false;
        }
        String name = profile.name();
        UUID id = profile.id();
        // Hypixel's fake tab-list columns ("!A-a" etc.) fail the name-shape check; Hypixel NPC player entities
        // use version-2 UUIDs (the same heuristic SkyHanni/Skyblocker use), real players are v4 (v3 offline).
        if (!NameTable.isValidName(name) || name.length() < 3 || name.equalsIgnoreCase(ownName)
                || (id != null && id.version() == 2)) {
            return false;
        }
        synchronized (SEEN_PLAYERS) {
            if (SEEN_PLAYERS.size() >= MAX_SEEN_PLAYERS && !SEEN_PLAYERS.contains(name)) {
                return false;
            }
            return SEEN_PLAYERS.add(name);
        }
    }

    /** @return the current lookup table, or {@code null} when the feature is off / has nothing to replace. */
    static NameTable currentTable() {
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        if (!cfg.isEnabled()) {
            return null;
        }
        // Font is used during early startup (loading overlay) - don't assume the client/user exist yet.
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.User user = mc == null ? null : mc.getUser();
        String own = user == null ? null : user.getName();
        int cv = NameChangerConfig.version();
        int sv = seenVersion;
        if (cv != builtConfigVersion || sv != builtSeenVersion || !java.util.Objects.equals(own, builtOwnName)) {
            rebuild(cfg, own, cv, sv);
        }
        NameTable t = table;
        return t == null || t.isEmpty() ? null : t;
    }

    private static synchronized void rebuild(NameChangerConfig cfg, String own, int cv, int sv) {
        if (cv == builtConfigVersion && sv == builtSeenVersion && java.util.Objects.equals(own, builtOwnName)) {
            return;
        }
        List<NameTable.Entry> entries = new ArrayList<>();
        // Priority order: own name, then manual mappings, then randomized others (first entry wins on duplicates).
        if (cfg.isOwnNameEnabled() && NameTable.isValidName(own) && !cfg.getOwnDisplayName().isEmpty()) {
            entries.add(new NameTable.Entry(own, styled(cfg.getOwnDisplayName(), cfg.getOwnColor()), false));
        }
        if (cfg.isMappingsEnabled()) {
            for (NameChangerConfig.Mapping m : cfg.mappings()) {
                String real = m.real == null ? "" : m.real.trim();
                if (NameTable.isValidName(real) && m.display != null && !m.display.isEmpty()) {
                    entries.add(new NameTable.Entry(real, styled(m.display, m.color), false));
                }
            }
        }
        if (cfg.isRandomizeOthers()) {
            synchronized (SEEN_PLAYERS) {
                for (String name : SEEN_PLAYERS) {
                    // Case-sensitive: displayed IGNs always use their real capitalization, and this keeps a player
                    // literally named e.g. "Mage" from eating the plain word "mage" in lore.
                    entries.add(new NameTable.Entry(name, randomNameFor(name), true));
                }
            }
        }
        table = new NameTable(entries);
        builtConfigVersion = cv;
        builtSeenVersion = sv;
        builtOwnName = own;
        NameReplacer.clearCaches();
        LOGGER.debug("[NameChanger] rebuilt name table ({} entries)", table.size);
    }

    /** A display name with its picked colour applied as a legacy code prefix (see {@link NameColor}) - the
     *  name itself may still carry {@code &} format codes, which {@link #colorize} converts as before. */
    static String styled(String display, int argb) {
        return NameColor.prefix(argb) + colorize(display);
    }

    /** Vanilla edit boxes filter out the § sign, so "&" + a format code is accepted too ("&6Cool" -> "§6Cool"). */
    static String colorize(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                sb.append('§');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CONSONANTS = "bcdfghjklmnprstvwxz";
    private static final String VOWELS = "aeiouy";

    /** Deterministic, session-stable fake IGN of the same length, drawn from the real IGN charset. */
    static String randomNameFor(String realName) {
        synchronized (RANDOM_NAMES) {
            String cached = RANDOM_NAMES.get(realName);
            if (cached != null) {
                return cached;
            }
            int len = realName.length();
            SplittableRandom rng = new SplittableRandom(SESSION_SEED ^ (realName.hashCode() * 0x9E3779B97F4A7C15L));
            StringBuilder sb = new StringBuilder(len);
            int digits = len >= 6 && rng.nextInt(3) == 0 ? 1 + rng.nextInt(Math.min(3, len - 4)) : 0;
            int letters = len - digits;
            boolean capitalize = rng.nextInt(3) != 0;
            int underscoreAt = letters >= 7 && rng.nextInt(4) == 0 ? 3 + rng.nextInt(letters - 5) : -1;
            boolean vowelNext = rng.nextBoolean();
            for (int i = 0; i < letters; i++) {
                char c;
                if (i == underscoreAt) {
                    c = '_';
                    vowelNext = rng.nextBoolean();
                } else {
                    String pool = vowelNext ? VOWELS : CONSONANTS;
                    c = pool.charAt(rng.nextInt(pool.length()));
                    // Mostly alternate consonant/vowel, with the odd double letter so names don't all look alike.
                    vowelNext = vowelNext ? rng.nextInt(6) == 0 : rng.nextInt(5) != 0;
                    if ((i == 0 && capitalize) || (i > 0 && sb.charAt(i - 1) == '_' && capitalize)) {
                        c = Character.toUpperCase(c);
                    }
                }
                sb.append(c);
            }
            for (int i = 0; i < digits; i++) {
                sb.append((char) ('0' + rng.nextInt(10)));
            }
            String out = sb.toString();
            RANDOM_NAMES.put(realName, out);
            return out;
        }
    }

    /** Number of distinct other players collected this session (for the settings tab). */
    public static int seenPlayerCount() {
        synchronized (SEEN_PLAYERS) {
            return SEEN_PLAYERS.size();
        }
    }
}
