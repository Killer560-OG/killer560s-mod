package com.killer560.hub.interop;

import com.killer560.hub.scorecalc.ScoreCalculator;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what OTHER dungeon mods announce in party chat, so a player running only this mod still benefits when
 * a party mate runs NoammAddons, Odin, SkyHanni, Devonian, QUOI or Skytils.
 * <p>
 * This is the route that actually helps strangers: their announcement is an ordinary Hypixel party message that
 * every client in the party receives, so nothing has to be installed on our side and no other mod's server is
 * ever contacted.
 * <p>
 * Deliberately conservative, because a parser that fires on the wrong line is worse than one that misses:
 * <ul>
 * <li>Only lines that match Hypixel's own {@code Party > [rank] Name: message} shape are considered.</li>
 * <li>Only while {@link DungeonState#isInDungeon()}.</li>
 * <li>Our own messages are skipped - we already know anything we said.</li>
 * <li>The payload must be an <b>exact</b> (case-insensitive, trimmed) match against a known announcement, or
 * match an anchored regex. No "contains" matching: a party mate typing "the mimic is in the room below" must
 * not mark the mimic dead.</li>
 * <li>Mimic claims are ignored below floor 6, where there is no mimic to kill.</li>
 * </ul>
 * Anything learned this way is stamped {@link InteropSource#CHAT}, the least trusted source, because a human
 * can type these words by hand and because the wording belongs to someone else's mod and can change.
 * <p>
 * Where the strings come from (all read from the mods' own sources/jars on disk, not guessed):
 * <ul>
 * <li>NoammAddons {@code utils/ChatUtils.kt} sends every announcement through {@code /pc} with formatting
 * stripped, so its wire text is plain. Its score announcements are {@code "Mimic Killed"},
 * {@code "Prince Killed"} and {@code "Bat Killed"}
 * ({@code utils/dungeons/map/handlers/ScoreCalculation.kt}).</li>
 * <li>Odin {@code features/impl/dungeon/Mimic} sends {@code "Mimic Killed!"}, {@code "Prince Killed!"} and
 * {@code "Bat Killed!"} via {@code sendCommand("pc …")}.</li>
 * <li>The wider vocabulary ({@code mimic dead}, {@code mimic slain}, the {@code $skytils-dungeon-score-*$}
 * markers, {@code child destroyed!} …) is the set NoammAddons itself listens for
 * ({@code ScoreCalculation.kt} {@code mimicMessages}/{@code princeMessages}/{@code batMessages}), i.e. the
 * de-facto shared vocabulary those mods already use to understand each other.</li>
 * </ul>
 * What is deliberately NOT parsed, having checked every {@code /pc} call site in all five mods (2026-09-20):
 * <ul>
 * <li><b>Per-room secret counts</b> - none of them announce these in chat at all. NoammAddons and Odin share
 * them over their own websockets between their own users, which we are not going to connect to. Our own
 * per-room numbers come from the action bar in {@link SelfDerivation}, and party-wide ones would come over
 * our own relay.</li>
 * <li><b>M7 dragon spawns</b> - no chat call exists in any of them either; it is all client-side HUD. We
 * already derive dragon spawns ourselves from Hypixel's flame-particle burst
 * ({@code WitherDragonsFeature}), which is strictly better than a relayed message anyway.</li>
 * <li><b>Terminal / device / lever completion</b> - nobody announces it because Hypixel already does, as a
 * normal server line every client receives. {@link SelfDerivation} reads that line.</li>
 * <li>Flavour and status chatter ({@code "Blaze Done!"}, {@code "Blaze puzzle solved!"},
 * {@code "270 Score!"}, {@code "FAST WATCHER"}, {@code "SS Broke!"}, leap messages) - real announcements, but
 * nothing here needs them yet. They are easy to add later; the point of not adding them now is that every
 * pattern is a chance to fire on the wrong line.</li>
 * </ul>
 */
public final class InteropChatParser {

    // Hypixel's own party line. The rank tag is optional (no rank = no brackets).
    private static final Pattern PARTY_LINE =
            Pattern.compile("^Party > (?:\\[[^\\]]{1,24}] )?(\\w{1,16}): (.+)$");

    // The cross-mod vocabulary. Lower-case, trimmed, matched in full - never as a substring.
    private static final Set<String> MIMIC = Set.of(
            "mimic killed", "mimic killed!", "mimic dead", "mimic dead!", "mimic slain", "mimic slain!",
            "mimic destroyed!", "mimic obliterated!", "mimic exorcised!", "mimic annhilated!",
            "child destroyed!", "breefing killed", "breefing dead",
            "$skytils-dungeon-score-mimic$");
    private static final Set<String> PRINCE = Set.of(
            "prince killed", "prince killed!", "prince dead", "prince dead!", "prince slain", "prince slain!",
            "$skytils-dungeon-score-prince$");
    private static final Set<String> BAT = Set.of(
            "bat killed", "bat killed!", "bat dead", "bat dead!", "bat slain", "bat slain!",
            "$skytils-dungeon-score-bat$");

    private InteropChatParser() {
    }

    static void register() {
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
    }

    private static void onChat(String plain) {
        InteropConfig cfg = InteropConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isChatParsing() || !DungeonState.isInDungeon() || plain.isEmpty()) {
            return;
        }
        Matcher line = PARTY_LINE.matcher(plain);
        if (!line.matches()) {
            return;
        }
        String sender = line.group(1);
        if (isSelf(sender)) {
            return;
        }
        String body = line.group(2).trim().toLowerCase(Locale.US);
        if (body.isEmpty()) {
            return;
        }
        if (MIMIC.contains(body)) {
            // No mimic exists below floor 6, so a "mimic killed" there is someone messing about.
            if (ScoreCalculator.floorNumber(DungeonState.getFloor()) >= 6) {
                PartyInteropState.offerFlag(PartyInteropState.Flag.MIMIC_KILLED, InteropSource.CHAT, sender);
            }
            return;
        }
        if (PRINCE.contains(body)) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.PRINCE_KILLED, InteropSource.CHAT, sender);
            return;
        }
        if (BAT.contains(body)) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BAT_KILLED, InteropSource.CHAT, sender);
        }
    }

    private static boolean isSelf(String name) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.getGameProfile().name().equalsIgnoreCase(name);
    }
}
