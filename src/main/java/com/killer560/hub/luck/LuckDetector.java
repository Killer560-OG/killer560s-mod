package com.killer560.hub.luck;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.rngmeter.MagicFindTracker;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds what How Lucky Are You counts, from three places, never from a player's chat line or a re-picked item:
 * <ul>
 * <li><b>Drop lines.</b> Hypixel's {@code RARE DROP!}, {@code VERY RARE DROP!}, {@code CRAZY RARE DROP!},
 *     {@code INSANE DROP!}, {@code PRAY TO RNGESUS DROP!}, {@code RNGESUS INCARNATE DROP!} and {@code PET DROP!} lines,
 *     anchored at the start of the line, so {@code [VIP] Bob: RARE DROP! Warden Heart} (any typed chat starts with a
 *     channel, rank or name) never matches. Formats from SkyHanni's and Skyblocker's regex tests (slayer drops are in
 *     brackets, often with "2x "), not copied code.</li>
 * <li><b>Slayer kills.</b> {@code SLAYER QUEST COMPLETE!} / {@code NICE! SLAYER BOSS SLAIN!} (once per 5 s, both can
 *     come for one boss) count one attempt at the boss and tier the sidebar showed in the last minute ("Revenant Horror
 *     V"; the sidebar cannot be typed into by other players). The kill waits up to 3 s for Hypixel's "Zombie Slayer LVL
 *     7 - ..." line, so drops above his slayer level add nothing, and the slayer's RNG Meter (read from the /rng menu)
 *     raises its selected drop 1x to 3x as it fills.</li>
 * <li><b>Mob kills</b> ({@link LuckKills}): a mob he hit that dies counts one attempt at its wiki table (every mob and
 *     sea creature page), with the Looting of the weapon he holds and his Magic Find.</li>
 * <li><b>Claims</b>: a Kuudra Paid Chest ({@link LuckKuudra}), a Crystal Nucleus loot message and a Superpairs claim
 *     (from the Experiments profit tracker) each count an attempt with their items as drops.</li>
 * <li><b>Inventory gains</b> of a table item that has no drop line (dyes), only with no screen open (a chest, trade, Auction
 *     House or storage move is never a drop). A gain is NOT a drop when the stack's Skyblock UUID was already seen in his
 *     inventory, or when the same item left his inventory in the last 10 minutes (thrown with Q and picked back up, moved
 *     out and back): that is the "dropped it and picked it up" case the owner named. A gain within 20 s of a drop line for
 *     the same item is that drop line's, not a second drop.</li>
 * </ul>
 * Dungeon chests are counted from the Croesus claim log instead ({@link LuckStore}).
 */
public final class LuckDetector {

    private static final Logger LOGGER = ModLog.get("killer560smod-luck");
    public static final String FEATURE = "Luck";

    /** A drop line. Group 1 the kind, 2 an amount, 3 the item, 4 the Magic Find. */
    static final Pattern DROP = Pattern.compile(
            "^(RARE|VERY RARE|CRAZY RARE|INSANE|PRAY TO RNGESUS|RNGESUS INCARNATE|PET) DROP!\\s+\\(?(?:(\\d{1,4})x )?"
                    + "(?:◆ )?([A-Za-z0-9'\\-:.,&! ]{1,64}?)\\)?"
                    + "(?:\\s+\\(\\+(\\d{1,6})%?[^)]{0,6}Magic Find!?\\))?(?:\\s+\\(\\+[^)]{1,20}\\))?$");
    static final Pattern SLAYER_DONE = Pattern.compile("^\\s*(?:SLAYER QUEST COMPLETE!|NICE! SLAYER BOSS SLAIN!)\\s*$");
    static final Pattern SLAYER_SIDEBAR = Pattern.compile("(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood"
            + "|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend|Bloodfiend)(?: (V|IV|III|II|I))?\\b");
    private static final Pattern PET_RARITY = Pattern.compile("^(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC) ");
    /**
     * Hypixel's line after a slayer kill: "Zombie Slayer LVL 7 - Next LVL in 46,100 XP!" or "... LVL 9 - LVL MAXED OUT!"
     * (SkyHanni's and Skyblocker's slayer level regexes; an optional "» " lead). Whole line: another player's chat line
     * starts with a channel, rank or name and never matches.
     */
    static final Pattern SLAYER_LEVEL = Pattern.compile("^(?:» )?(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL "
            + "(\\d{1,2}) - (?:Next LVL in [\\d,]{1,15} XP!|LVL MAXED OUT!)$");
    /** "RNG Meter - 123,456 Stored XP" after a slayer kill (wording not seen live; a mismatch only skips the update). */
    static final Pattern METER_XP = Pattern.compile("^RNG Meter - ([\\d,]{1,15}) Stored XP$");
    /** The Crystal Nucleus loot message (the shelved Nucleus tracker's patterns, from SkyHanni's CrystalNucleusApi). */
    static final Pattern NUCLEUS_START = Pattern.compile("^\\s{0,64}CRYSTAL NUCLEUS LOOT BUNDLE\\s{0,64}$");
    static final Pattern NUCLEUS_END = Pattern.compile("^\\s{0,64}▬{20,128}\\s{0,64}$");
    private static final Pattern COUNT_PREFIX = Pattern.compile("^(\\d{1,6})x\\s+(.+)$");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.+?)\\s+x(\\d{1,6})$");
    /** Slayer XP per boss tier (Slayer page): the RNG Meter gains it from Tier III up. */
    private static final int[] SLAYER_XP = {0, 5, 25, 100, 500, 1500};
    private static final int[] VAMPIRE_XP = {0, 10, 25, 60, 120, 150};
    private static final long LEVEL_WAIT_MS = 3_000;
    private static final long MOB_MEMORY_MS = 15_000;

    private static final long QUEST_MEMORY_MS = 60_000;
    private static final long KILL_MEMORY_MS = 30_000;
    private static final long LOSS_MEMORY_MS = 10 * 60_000;
    private static final long CHAT_CREDIT_MS = 20_000;

    // Client thread only (chat subscribers and the tick both run there).
    private static String questActivity;
    private static long questSeenMs;
    private static String lastKillActivity;
    private static long lastKillMs;
    private static int ticks;
    /** A slayer kill waiting (up to {@link #LEVEL_WAIT_MS}) for Hypixel's level line, so locked drops are left out. */
    private record PendingKill(String activity, int magicFind, long atMs) {
    }
    private static PendingKill pendingKill;
    private static String lastMobActivity;
    private static long lastMobMs;
    private static List<String> nucleusItems;
    private static long nucleusAtMs;
    /** The loot message ends with the same bar it opens with, and ChatObserver drops a line repeated within 250 ms,
     *  so the closing bar can be swallowed: the bundle also ends this long after its header. */
    private static final long NUCLEUS_MAX_MS = 1_500;

    private static Map<String, Integer> snapshot;
    private static final Set<String> SEEN_UUIDS = new HashSet<>();
    /** Item names whose stacks have carried a Skyblock UUID. */
    private static final Set<String> UUID_NAMES = new HashSet<>();
    private record Loss(String name, long atMs) {
    }
    private static final Deque<Loss> LOSSES = new ArrayDeque<>();
    private record Credit(String name, long atMs) {
    }
    private static final Deque<Credit> CREDITS = new ArrayDeque<>();

    // Test hooks (testkit reads them).
    static volatile String lastDecision = "";
    static volatile int excludedPickups;

    private LuckDetector() {
    }

    public static void register() {
        ChatObserver.subscribe(message -> onChat(ChatObserver.raw(message)));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LuckDetector.onTick", LuckDetector::onTick));
    }

    private static boolean active() {
        return LuckConfig.getInstance().isEnabled() && SkyblockGate.allows();
    }

    static void onChat(String raw) {
        if (raw == null || !active()) {
            return;
        }
        if (nucleusItems != null) {
            onNucleusLine(raw);
            return;
        }
        if (!raw.contains("DROP!") && !raw.contains("SLAYER") && !raw.contains("Slayer LVL") && !raw.contains("RNG Meter")
                && !raw.contains("NUCLEUS")) {
            return;
        }
        String text = ChatFormatting.stripFormatting(raw);
        if (text == null) {
            return;
        }
        if (NUCLEUS_START.matcher(text).matches()) {
            nucleusItems = new java.util.ArrayList<>();
            nucleusAtMs = System.currentTimeMillis();
            return;
        }
        text = text.trim();
        if (SLAYER_DONE.matcher(text).matches()) {
            onSlayerKill();
            return;
        }
        Matcher lvl = SLAYER_LEVEL.matcher(text);
        if (lvl.matches()) {
            onSlayerLevel(bossOfWord(lvl.group(1)), Integer.parseInt(lvl.group(2)));
            return;
        }
        Matcher meterXp = METER_XP.matcher(text);
        if (meterXp.matches()) {
            onMeterXp(meterXp.group(1));
            return;
        }
        Matcher m = DROP.matcher(text);
        if (!m.matches()) {
            return;
        }
        int amount = 1;
        int mf = -1;
        try {
            if (m.group(2) != null) {
                amount = Integer.parseInt(m.group(2));
            }
            if (m.group(4) != null) {
                mf = (int) Math.min(100_000, Long.parseLong(m.group(4)));
            }
        } catch (NumberFormatException ignored) {
        }
        String name = m.group(3).trim();
        if ("PET".equals(m.group(1))) {
            name = PET_RARITY.matcher(name).replaceFirst("");
        }
        onDrop(name, amount, "chat", mf >= 0 ? mf : magicFind());
    }

    private static void onSlayerKill() {
        long now = System.currentTimeMillis();
        if (now - lastKillMs < 5_000) {
            return; // the second of the two lines for one boss
        }
        refreshQuest();
        if (questActivity == null || now - questSeenMs > QUEST_MEMORY_MS) {
            lastDecision = "slayer kill with no quest on the sidebar - not counted";
            LOGGER.info("[Luck] A slayer boss died but no quest was on the sidebar in the last minute; not counted");
            return;
        }
        lastKillActivity = questActivity;
        lastKillMs = now;
        if (pendingKill != null) {
            finishSlayerKill(); // a second boss before the first one's level line: count the first as it stands
        }
        pendingKill = new PendingKill(questActivity, magicFind(), now);
        lastDecision = "slayer kill " + questActivity + " waiting for the level line";
    }

    /** "Zombie" (the level line's word) -> "revenant" (the table's boss key). */
    static String bossOfWord(String word) {
        return switch (word) {
            case "Zombie" -> "revenant";
            case "Spider" -> "tarantula";
            case "Wolf" -> "sven";
            case "Enderman" -> "voidgloom";
            case "Blaze" -> "inferno";
            default -> "vampire";
        };
    }

    private static String bossOf(String slayerActivity) {
        String[] p = slayerActivity.split(":");
        return p.length >= 2 ? p[1] : "";
    }

    private static int tierOf(String slayerActivity) {
        String[] p = slayerActivity.split(":");
        try {
            return p.length >= 3 ? Integer.parseInt(p[2]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void onSlayerLevel(String boss, int level) {
        LuckStore.setSlayerLevel(boss, level);
        if (pendingKill != null && bossOf(pendingKill.activity()).equals(boss)) {
            finishSlayerKill();
        }
    }

    /** "RNG Meter - 123,456 Stored XP" for the boss just killed: the meter's stored XP, as Hypixel says it. */
    private static void onMeterXp(String digits) {
        if (lastKillActivity == null || System.currentTimeMillis() - lastKillMs > KILL_MEMORY_MS) {
            return;
        }
        if (pendingKill != null) {
            finishSlayerKill(); // the roll used the meter before this kill's XP
        }
        String key = "slayer:" + bossOf(lastKillActivity);
        LuckStore.Meter m = LuckStore.meter(key);
        try {
            long xp = Long.parseLong(digits.replace(",", ""));
            if (m != null) {
                LuckStore.setMeter(key, new LuckStore.Meter(m.item(), xp, m.required()));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * Counts the waiting slayer kill: drops above his slayer level (from the level line, or the last one seen) add
     * nothing, and the RNG Meter's selected drop is raised by its fill. Then the meter gains the tier's slayer XP.
     */
    private static void finishSlayerKill() {
        PendingKill k = pendingKill;
        pendingKill = null;
        if (k == null) {
            return;
        }
        String boss = bossOf(k.activity());
        int level = LuckStore.slayerLevel(boss);
        String meterKey = "slayer:" + boss;
        LuckStore.Meter m = LuckStore.meter(meterKey);
        LuckMath.Bonus bonus = new LuckMath.Bonus(k.magicFind(), 0, level, m == null ? null : m.item(),
                m == null ? 0 : m.progress());
        LuckStore.attempt(k.activity(), bonus);
        int tier = tierOf(k.activity());
        int[] xp = "vampire".equals(boss) ? VAMPIRE_XP : SLAYER_XP;
        if (m != null && tier >= 3 && tier < xp.length) {
            LuckStore.setMeter(meterKey, new LuckStore.Meter(m.item(), m.xp() + xp[tier], m.required()));
        }
        lastDecision = "attempt " + k.activity() + " at " + k.magicFind() + "% MF, slayer level "
                + (level < 0 ? "unknown" : level) + (m == null ? "" : ", meter " + m.item() + " "
                + Math.round(m.progress() * 100) + "%");
        LOGGER.info("[Luck] {} kill counted at {}% Magic Find (slayer level {}{})", k.activity(), k.magicFind(),
                level < 0 ? "unknown" : level, m == null ? "" : ", RNG Meter on " + m.item());
    }

    /**
     * The RNG Meter menu was read ({@code RngMeterOverlay}): remembers a slayer meter's selected drop and fill. The
     * menu's title names the slayer ("Revenant Horror", or its "Zombie" word); dungeon and other meters are not used.
     */
    public static void onMeterMenu(String title, String item, long current, long required) {
        if (title == null || item == null || item.isBlank() || required <= 0) {
            return;
        }
        String t = title.toLowerCase(Locale.ROOT);
        String[][] words = {{"revenant", "revenant"}, {"zombie", "revenant"}, {"tarantula", "tarantula"},
                {"spider", "tarantula"}, {"sven", "sven"}, {"wolf", "sven"}, {"voidgloom", "voidgloom"},
                {"enderman", "voidgloom"}, {"inferno", "inferno"}, {"blaze", "inferno"}, {"riftstalker", "vampire"},
                {"vampire", "vampire"}};
        java.util.Arrays.stream(words).filter(w -> t.contains(w[0])).findFirst().ifPresent(w ->
                LuckStore.setMeter("slayer:" + w[1], new LuckStore.Meter(item, Math.max(0, current), required)));
    }

    /** A mob he hit died ({@link LuckKills}): one attempt at its table, with his Looting and Magic Find. */
    static void onMobKill(String activity, int looting, String why) {
        lastMobActivity = activity;
        lastMobMs = System.currentTimeMillis();
        int mf = magicFind();
        LuckStore.attempt(activity, new LuckMath.Bonus(mf, looting, -1, null, 0));
        lastDecision = "attempt " + activity + " at " + mf + "% MF, Looting " + looting + " (" + why + ")";
        LOGGER.info("[Luck] {} kill counted at {}% Magic Find, Looting {} ({})", activity, mf, looting, why);
    }

    // ---- claims: Kuudra, Crystal Nucleus, Experiments -------------------------------------------------------------

    /** One claim of {@code activity} holding {@code items}: an attempt, and each table item in it as a drop. */
    static void onClaim(String activity, List<String> items, String source) {
        DropTable table = DropTable.get();
        LuckStore.attempt(activity, LuckMath.Bonus.mf(0));
        int found = 0;
        for (String item : items) {
            DropTable.Drop d = table.find(activity, item);
            if (d == null && !item.startsWith("Enchanted Book (")) {
                d = table.find(activity, "Enchanted Book (" + item + ")");
            }
            if (d != null) {
                LuckStore.drop(d.key(), 1, source, 0);
                found++;
                announce(d.key());
            }
        }
        lastDecision = "claim " + activity + ": " + found + " table item(s) of " + items.size();
        LOGGER.info("[Luck] {} claim counted ({} of its {} items in the table)", activity, found, items.size());
    }

    private static void onNucleusLine(String raw) {
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain == null) {
            return;
        }
        if (NUCLEUS_END.matcher(plain).matches()) {
            finishNucleus();
            return;
        }
        if (nucleusItems.size() > 64) {
            nucleusItems = null; // never a loot message: stop reading
            return;
        }
        if (!plain.startsWith("    ")) {
            return; // the loot lines are indented four spaces; a player's line never is
        }
        String line = plain.trim();
        Matcher p = COUNT_PREFIX.matcher(line);
        Matcher s = COUNT_SUFFIX.matcher(line);
        String name = p.matches() ? p.group(2) : s.matches() ? s.group(1) : line;
        if (!name.isBlank()) {
            nucleusItems.add(name.trim());
        }
    }

    private static void finishNucleus() {
        List<String> items = nucleusItems;
        nucleusItems = null;
        if (items != null) {
            onClaim("nucleus", items, "nucleus");
        }
    }

    /** A Superpairs claim ({@code ExperimentsProfitTracker}): one attempt at its stake, with its rewards. */
    public static void onExperimentClaim(String game, String stakes, List<String> rewards) {
        if (!active() || !"Superpairs".equals(game) || stakes == null) {
            return;
        }
        java.util.stream.Stream.of("Metaphysical", "Transcendent", "Supreme").filter(stakes::contains).findFirst()
                .ifPresent(stake -> onClaim("experiment:" + stake, rewards, "experiment"));
    }

    /** A drop line or inventory gain for {@code name}: decides its key and records it. */
    static void onDrop(String name, int amount, String source, int mf) {
        DropTable table = DropTable.get();
        String key = keyFor(table, name);
        LuckStore.drop(key, amount, source, mf);
        if ("chat".equals(source)) {
            CREDITS.addLast(new Credit(DropTable.normalize(name), System.currentTimeMillis()));
        }
        lastDecision = "drop " + key + " (" + source + ")";
        LOGGER.info("[Luck] Drop {} x{} from {} at {}% MF", key, amount, source, mf);
        if (key.startsWith("slayer:")) {
            // The RNG Meter's selected drop came: Hypixel takes its price off the stored XP (Pity and RNG Meters).
            String meterKey = "slayer:" + bossOf(key.substring(0, key.indexOf('|')));
            LuckStore.Meter m = LuckStore.meter(meterKey);
            if (m != null && DropTable.normalize(m.item()).equals(DropTable.normalize(name))) {
                LuckStore.setMeter(meterKey, new LuckStore.Meter(m.item(), Math.max(0, m.xp() - m.required()), m.required()));
            }
        }
        announce(key);
    }

    /**
     * The drop's key: the mob or slayer boss just killed (the more recent first; a slayer quest on the sidebar counts as
     * the boss being fought), else the table's only line-dropped activity for it.
     */
    static String keyFor(DropTable table, String name) {
        long now = System.currentTimeMillis();
        String slayer = now - lastKillMs < KILL_MEMORY_MS ? lastKillActivity
                : questActivity != null && now - questSeenMs < QUEST_MEMORY_MS ? questActivity : null;
        String mob = lastMobActivity != null && now - lastMobMs < MOB_MEMORY_MS ? lastMobActivity : null;
        boolean mobFirst = mob != null && (slayer == null || lastMobMs > lastKillMs);
        for (String act : mobFirst ? new String[]{mob, slayer} : new String[]{slayer, mob}) {
            if (act == null) {
                continue;
            }
            DropTable.Drop d = table.find(act, name);
            if (d != null) {
                return d.key();
            }
        }
        DropTable.Drop only = null;
        for (DropTable.Drop d : table.named(name)) {
            if (DropTable.claimOnly(d.activity())) {
                continue; // chest, bundle and experiment items arrive by claim, not by a line or a pickup
            }
            if (only != null && !only.activity().equals(d.activity())) {
                only = null;
                break;
            }
            only = d;
        }
        if (only != null && (slayer == null || !only.activity().startsWith("slayer:"))) {
            return only.key();
        }
        String recent = mobFirst ? mob : slayer != null ? slayer : mob;
        return (recent != null ? recent : "other") + "|" + name;
    }

    private static void announce(String key) {
        if (!LuckConfig.getInstance().isAnnounce()) {
            return;
        }
        String profile = com.killer560.hub.gui.profit.ProfitScope.currentSkyblockProfile();
        LuckConfig cfg = LuckConfig.getInstance();
        for (LuckStore.Row r : LuckStore.rows(profile, cfg.getChestOdds(), false)) {
            if (!r.key().equals(key)) {
                continue;
            }
            if (r.drop() == null || r.expected() <= 0) {
                ModChat.send(FEATURE, ModChat.value(r.name()), ModChat.dim(" counted (" + r.got() + " so far)"));
                return;
            }
            ModChat.send(FEATURE, ModChat.value(r.name()), ModChat.dim(" " + LuckMath.oneIn(r.drop().chance()) + ": "),
                    ModChat.text(r.got() + " got, " + LuckMath.expected(r.expected()) + " expected in "
                            + String.format(Locale.US, "%,d", r.attempts()) + " - "),
                    r.percentile() >= 0.5 ? ModChat.good(LuckMath.describe(r.percentile()))
                            : ModChat.bad(LuckMath.describe(r.percentile())));
            return;
        }
    }

    /** Magic Find for an attempt now: the manual value, or the last one Hypixel printed on a drop line (0 before any). */
    static int magicFind() {
        LuckConfig cfg = LuckConfig.getInstance();
        if (cfg.getMagicFindMode() == LuckConfig.MagicFindMode.MANUAL) {
            return cfg.getManualMagicFind();
        }
        Integer seen = MagicFindTracker.getLastMagicFind();
        return seen == null ? 0 : Math.max(0, seen);
    }

    private static void refreshQuest() {
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = mc.isSameThread() ? ScoreboardData.sidebarNow(mc) : ScoreboardData.sidebar();
        String found = questFrom(lines);
        if (found != null) {
            questActivity = found;
            questSeenMs = System.currentTimeMillis();
        }
    }

    /** The slayer activity a sidebar shows ("Revenant Horror V" -> slayer:revenant:5), or null. */
    static String questFrom(List<String> lines) {
        boolean inQuest = false;
        for (String line : lines) {
            String s = ChatFormatting.stripFormatting(line);
            if (s == null) {
                continue;
            }
            if (s.contains("Slayer Quest")) {
                inQuest = true;
                continue;
            }
            if (!inQuest) {
                continue;
            }
            Matcher m = SLAYER_SIDEBAR.matcher(s);
            if (m.find()) {
                String boss = switch (m.group(1)) {
                    case "Revenant Horror", "Atoned Horror" -> "revenant";
                    case "Tarantula Broodfather", "Conjoined Brood" -> "tarantula";
                    case "Sven Packmaster" -> "sven";
                    case "Voidgloom Seraph" -> "voidgloom";
                    case "Inferno Demonlord" -> "inferno";
                    default -> "vampire";
                };
                int tier = m.group(2) == null ? ("Atoned Horror".equals(m.group(1))
                        || "Conjoined Brood".equals(m.group(1)) ? 5 : 0) : roman(m.group(2));
                return tier == 0 ? null : "slayer:" + boss + ":" + tier;
            }
        }
        return null;
    }

    private static int roman(String r) {
        return switch (r) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
    }

    // ---- tick: sidebar + inventory -----------------------------------------------------------------------------------

    private static void onTick(Minecraft mc) {
        if (mc.player == null || !active()) {
            snapshot = null;
            return;
        }
        if (++ticks % 20 == 0) {
            refreshQuest();
        }
        if (nucleusItems != null && System.currentTimeMillis() - nucleusAtMs > NUCLEUS_MAX_MS) {
            finishNucleus(); // its closing bar was dropped as a repeat of the opening one
        }
        if (pendingKill != null && System.currentTimeMillis() - pendingKill.atMs() > LEVEL_WAIT_MS) {
            finishSlayerKill(); // no level line came: count it with the last level seen
        }
        if (!LuckConfig.getInstance().isInventoryDrops()) {
            snapshot = null;
            return;
        }
        if (McCompat.screen(mc) != null) {
            snapshot = null; // re-baseline after the screen closes: nothing moved in a menu is a drop
            return;
        }
        if (ticks % 5 == 0) {
            scanInventory(mc);
        }
    }

    private static void scanInventory(Minecraft mc) {
        long now = System.currentTimeMillis();
        while (!LOSSES.isEmpty() && now - LOSSES.peekFirst().atMs() > LOSS_MEMORY_MS) {
            LOSSES.removeFirst();
        }
        while (!CREDITS.isEmpty() && now - CREDITS.peekFirst().atMs() > CHAT_CREDIT_MS) {
            CREDITS.removeFirst();
        }
        Map<String, Integer> now0 = new HashMap<>();
        Map<String, String> newUuidName = new HashMap<>();
        DropTable table = DropTable.get();
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(st.getHoverName().getString());
            if (name == null || table.named(name).isEmpty()) {
                continue;
            }
            String norm = DropTable.normalize(name);
            now0.merge(norm, st.getCount(), Integer::sum);
            String uuid = ItemProtect.itemUuid(st);
            if (uuid != null && !uuid.isBlank()) {
                UUID_NAMES.add(norm);
                if (SEEN_UUIDS.add(uuid) && snapshot != null) {
                    newUuidName.put(uuid, name);
                }
            }
        }
        Map<String, Integer> before = snapshot;
        snapshot = now0;
        if (before == null) {
            return; // baseline (first tick, or a screen just closed): every UUID seen here is "his already"
        }
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int lost = e.getValue() - now0.getOrDefault(e.getKey(), 0);
            for (int k = 0; k < lost; k++) {
                LOSSES.addLast(new Loss(e.getKey(), now));
            }
        }
        for (Map.Entry<String, Integer> e : now0.entrySet()) {
            int gained = e.getValue() - before.getOrDefault(e.getKey(), 0);
            for (int k = 0; k < gained; k++) {
                onGain(e.getKey(), newUuidName, table);
            }
        }
    }

    private static void onGain(String norm, Map<String, String> newUuidName, DropTable table) {
        if (takeFirst(LOSSES, l -> l.name().equals(norm))) {
            excludedPickups++;
            lastDecision = "gain of " + norm + " ignored: it left the inventory in the last 10 minutes";
            LOGGER.info("[Luck] {} came back into the inventory (thrown or moved out and picked up) - not a drop", norm);
            return;
        }
        if (takeFirst(CREDITS, c -> c.name().equals(norm))) {
            lastDecision = "gain of " + norm + " is the drop line's";
            return;
        }
        boolean dye = false;
        String name = null;
        for (DropTable.Drop d : table.named(norm)) {
            if (DropTable.claimOnly(d.activity())) {
                continue; // a chest or bundle dye (Necron, Livid, Tentacle, Jade) comes by its claim
            }
            dye |= "dye".equals(d.category());
            name = d.name();
        }
        if (!dye) {
            // Inventory gains count only for items that drop without a line (dyes); the rest come by chat or claim.
            lastDecision = "gain of " + norm + " left to its drop line / claim";
            return;
        }
        if (UUID_NAMES.contains(norm)) {
            // Stacks of this item carry a Skyblock UUID: a real drop is a UUID never seen in his inventory before.
            String fresh = newUuidName.entrySet().stream().filter(e -> DropTable.normalize(e.getValue()).equals(norm))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (fresh == null) {
                excludedPickups++;
                lastDecision = "gain of " + norm + " ignored: no new item UUID (an item he already had)";
                LOGGER.info("[Luck] {} came back with a UUID already seen - not a drop", norm);
                return;
            }
            newUuidName.remove(fresh);
        }
        onDrop(name, 1, "inventory", magicFind());
    }

    private static <T> boolean takeFirst(Deque<T> q, java.util.function.Predicate<T> p) {
        for (Iterator<T> it = q.iterator(); it.hasNext(); ) {
            if (p.test(it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** Test hook: forget the inventory baseline, seen UUIDs, losses and slayer memory. */
    static void resetForTest() {
        snapshot = null;
        SEEN_UUIDS.clear();
        UUID_NAMES.clear();
        LOSSES.clear();
        CREDITS.clear();
        questActivity = null;
        questSeenMs = 0;
        lastKillActivity = null;
        lastKillMs = 0;
        pendingKill = null;
        lastMobActivity = null;
        lastMobMs = 0;
        nucleusItems = null;
        excludedPickups = 0;
        lastDecision = "";
        LuckKills.resetForTest();
        LuckKuudra.resetForTest();
    }
}
