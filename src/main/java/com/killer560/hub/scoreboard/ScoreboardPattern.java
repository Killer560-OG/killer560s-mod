package com.killer560.hub.scoreboard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regexes ported 1:1 from SkyHanni's {@code features/gui/customscoreboard/ScoreboardPattern.kt} (hannibal002/SkyHanni,
 * beta branch, 2026-09) plus the handful of scoreboard patterns that Custom Scoreboard borrows from other SkyHanni
 * APIs ({@code PurseApi.coinsPattern}, {@code BitsApi.bitsScoreboardPattern}, {@code MiningApi.heatPattern/coldPattern},
 * {@code ServerRestartTitle.restartingGreedyPattern}, {@code RiftBloodEffigies.heartsPattern},
 * {@code HypixelData.skyblockAreaPattern}) and the tab-widget headers from {@code data/model/TabWidget.kt}.
 * <p>
 * Every scoreboard pattern is matched with {@link java.util.regex.Matcher#matches()} (whole line) against the
 * sidebar line in legacy {@code §}-formatted form, exactly like SkyHanni's {@code sidebarLinesFormatted}. Tab
 * patterns are matched against the unformatted tab line.
 */
public final class ScoreboardPattern {

    private ScoreboardPattern() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static final String HEALTH_ICON = "❤";
    private static final String MINING_FORTUNE_ICON = "☘";

    // ---- main ----
    public static final Pattern MOTES = p("(?:§.)*Motes: (?:§.)*(?<motes>[\\d,]+).*");
    public static final Pattern COPPER = p("(?:§.)*Copper: (?:§.)*(?<copper>[\\d,]+).*");
    public static final Pattern SOWDUST = p("\\s?(?:§.)*Sowdust: (?:§.)*(?<sowdust>[\\d,]+)");
    public static final Pattern SOWDUST_GAINED = p("^(?:§.)*Sowdust: (?:§.)*[\\d,.kKmMbB]+ §7\\(\\+[\\d.kKmMbB]+\\)");
    public static final Pattern GEMS = p("(?:§.)*Gems: (?:§.)*(?<gems>[\\d,]+).*");
    public static final Pattern LOCATION = p("\\s*(?<location>§.. .*)");
    public static final Pattern LOBBY_CODE = p("\\s*§.(?:\\d{2}/?){3} §8(?<code>.*)");
    public static final Pattern DATE = p("\\s*(?:(?:Late|Early) )?(?:Spring|Summer|Autumn|Winter) \\d+(?:st|nd|rd|th)?.*");
    public static final Pattern TIME = p("\\s*§7\\d+:\\d+(?:am|pm)\\s*(?<symbol>§b☽|§e☀|§.⚡|§.☔)?.*");
    public static final Pattern FOOTER = p("§e(?:www|alpha)\\.hypixel\\.net");
    public static final Pattern YEAR_VOTES = p("§6Year \\d+ Votes");
    public static final Pattern VOTES = p("§.\\|+(?:§f)?\\|+ §.+");
    public static final Pattern WAITING_FOR_VOTE = p("§7Waiting for|§7your vote\\.\\.\\.");
    public static final Pattern NORTH_STARS = p("North Stars: §d(?<northstars>[\\w,]+).*");
    public static final Pattern PROFILE_TYPE = p("\\s*(?:§7♲ §7Ironman|§a☀ §aStranded|§.Ⓑ §.Bingo).*");

    // ---- multi use ----
    public static final Pattern AUTO_CLOSING = p("(?:§.)*Auto-closing in: §c(?:\\d+:)?\\d+");
    public static final Pattern STARTING_IN = p("(?:§.)*Starting in: §.(?:\\d+:)?\\d+");
    public static final Pattern TIME_ELAPSED = p("(?:§.)*Time Elapsed: (?:§.)*(?<time>(?:\\w+[ydhms] ?)+)");
    public static final Pattern INSTANCE_SHUTDOWN = p("(?:§.)*Instance Shutdown In: (?:§.)*(?<time>(?:\\w+[ydhms] ?)+)");
    public static final Pattern TIME_LEFT = p("(?:§.)*Time Left: (?:§.)*[\\w:,.\\s]+");

    // ---- dungeon ----
    public static final Pattern M7_DRAGONS = p("§cNo Alive Dragons|§8- (?:§.)+[\\w\\s]+Dragon§a [\\w,.]+(?:§." + HEALTH_ICON + "?)?");
    public static final Pattern KEYS = p("Keys: §.■ §.[✗✓] §.■ §a.x");
    public static final Pattern CLEARED = p("(?:§.)*Cleared: (?:§.)*(?<percent>[\\w,.]+)% (?:§.)*\\((?:§.)*(?<score>[\\w,.]+)(?:§.)*\\)");
    public static final Pattern SOLO = p("§3§lSolo");
    public static final Pattern TEAMMATES = p("(?:§.)*(?<classAbbv>\\[\\w]) (?:§.)*(?<username>\\w{2,16}) (?:(?:§.)*(?<classLevel>\\[Lvl?(?<level>[\\w,.]+)?]?)|(?:§(?<color>.))*(?<health>[\\w,.]+)(?:§.)*.?)");
    public static final Pattern FLOOR3_GUARDIANS = p("§. - §.(?:Healthy|Reinforced|Laser|Chaos)§a [\\w,.]*(?:§c" + HEALTH_ICON + ")?");

    // ---- kuudra ----
    public static final Pattern WAVE = p("(?:§.)*Wave: (?:§.)*\\d+(?:§.)*(?: §.- §.\\d+:\\d+)?");
    public static final Pattern TOKENS = p("(?:§.)*Tokens: §.(?<tokens>[\\w,]+)");
    public static final Pattern SUBMERGES = p("(?:§.)*Submerges In: (?:§.)*[\\w\\s?]+");

    // ---- farming ----
    public static final Pattern MEDALS = p("§[6fc]§l(?:GOLD|SILVER|BRONZE) §fmedals: §[6fc][\\d.,]+");
    public static final Pattern LOCKED = p("\\s*§cLocked.*");
    public static final Pattern CLEAN_UP = p("\\s*(?:§.)*Cleanup(?:§.)*: (?:§.)*.*");
    public static final Pattern PASTING = p("\\s*(?:§.)*(?:Barn )?Pasting§7: (?:§.)*[\\d,.]+%?");
    public static final Pattern PELTS = p("(?:§.)*Pelts: (?:§.)*(?<pelts>[\\d,]+).*");
    public static final Pattern MOB_LOCATION = p("(?:§.)*Tracker Mob Location:");
    public static final Pattern JACOBS_CONTEST = p("§eJacob's Contest");
    public static final Pattern PLOT = p("\\s*§aPlot §7-.*");

    // ---- mining ----
    public static final Pattern POWDER = p("(?:§.)*᠅ §.(?<type>Gemstone|Mithril|Glacite)(?: Powder)?(?:§.)*:? (?:§.)*(?<amount>[\\d,.]*)");
    public static final Pattern WIND_COMPASS = p("§9Wind Compass");
    public static final Pattern WIND_COMPASS_ARROW = p("\\s*(?:§.|[⋖⋗≈])+\\s*(?:§.|[⋖⋗≈])*\\s*");
    public static final Pattern MINING_EVENT = p("Event: §.§[lL].*");
    public static final Pattern MINING_EVENT_ZONE = p("Zone: §.*");
    public static final Pattern RAFFLE_USELESS = p("Find tickets on the|ground and bring them|to the raffle box");
    public static final Pattern RAFFLE_TICKETS = p("Tickets: §a\\d+ §7\\(\\d+(?:\\.\\d)?%\\)");
    public static final Pattern RAFFLE_POOL = p("Pool: §6\\d+");
    public static final Pattern MITHRIL_USELESS = p("§7Give Tasty Mithril to Don!");
    public static final Pattern MITHRIL_REMAINING = p("Remaining: §a(?:\\d+ Tasty Mithril|FULL)");
    public static final Pattern MITHRIL_YOUR_MITHRIL = p("Your Tasty Mithril: §c\\d+.*");
    public static final Pattern NEARBY_PLAYERS = p("Nearby Players: §.(?:\\d+|N/A)(?: §cMAX)?");
    public static final Pattern GOBLIN_USELESS = p("§7Kill goblins!");
    public static final Pattern REMAINING_GOBLIN = p("Remaining: §a\\d+ goblins?");
    public static final Pattern YOUR_GOBLIN_KILLS = p("Your kills: §c\\d+ ☠(?: §a\\(\\+\\d+\\))?");
    public static final Pattern MINESHAFT_NOT_STARTED = p("(?:§.)*Not started.*");
    public static final Pattern FORTUNATE_FREEZING_BONUS = p("Event Bonus: §6\\+\\d+" + MINING_FORTUNE_ICON);
    public static final Pattern FOSSIL_DUST = p("Fossil Dust: (?:§f)*[\\d.,]+.*");

    // ---- combat ----
    public static final Pattern MAGMA_CHAMBER = p("Magma Chamber");
    public static final Pattern MAGMA_BOSS = p("§7Boss: §[c6e]\\d+%");
    public static final Pattern DAMAGE_SOAKED = p("§7Damage Soaked:");
    public static final Pattern KILL_MAGMAS = p("§6Kill the Magmas:");
    public static final Pattern KILL_MAGMAS_BAR = p("(?:(?:§.)*▎)+.*"); // same language as SkyHanni's "(?:(?:§.)*▎+)+.*" without the nested "+" (polynomial backtracking)
    public static final Pattern REFORMING = p("§cThe boss is (?:re)?forming!");
    public static final Pattern BOSS_HEALTH = p("§7Boss Health:");
    public static final Pattern BOSS_HEALTH_BAR = p("§.[\\w,.]+§f/§a10M§c" + HEALTH_ICON);
    public static final Pattern BOSS_HP = p("(?:Protector|Dragon) HP: §a[\\d,.]* §c" + HEALTH_ICON);
    public static final Pattern BOSS_DAMAGE = p("Your Damage: §c[\\d,.]+");
    public static final Pattern SLAYER_QUEST = p("Slayer Quest");

    // ---- misc ----
    public static final Pattern ESSENCE = p("\\s*.*Essence: §.(?<essence>-?\\d+(?::?,\\d{3})*(?:\\.\\d+)?)");
    public static final Pattern REDSTONE = p("\\s*(?:§.)*⚡ §cRedstone: (?:§.)*\\d+%");
    public static final Pattern VISITING = p("\\s*§a✌ §7\\(§.\\d+(?:§.)?/\\d+(?:§.)?\\)");
    public static final Pattern FLIGHT_DURATION = p("\\s*Flight Duration: §a(?::?\\d{1,3})*");
    public static final Pattern DOJO_CHALLENGE = p("(?:§.)*Challenge: (?:§.)*(?<challenge>.+)");
    public static final Pattern DOJO_DIFFICULTY = p("(?:§.)*Difficulty: (?:§.)*(?<difficulty>.+)");
    public static final Pattern DOJO_POINTS = p("(?:§.)*Points: (?:§.)*[\\w.]+.*");
    public static final Pattern DOJO_TIME = p("(?:§.)*Time: (?:§.)*[\\w.]+.*");
    public static final Pattern OBJECTIVE = p("(?:§.)*(?:Objective|Quest).*");
    public static final Pattern QUEUE = p("Queued:.*");
    public static final Pattern QUEUE_TIER = p("Tier: §e.*");
    public static final Pattern QUEUE_POSITION = p("Position: (?:§.)*#\\d+ (?:§.)*Since: .*");
    public static final Pattern QUEUE_WAITING_FOR_LEADER = p("§aWaiting on party leader!");
    public static final Pattern ANNIVERSARY = p("(?:§d\\d+(?:st|nd|rd|th) Anniversary|§bCentury Raffle)§f (?:\\d|:)+");
    public static final Pattern THIRD_OBJECTIVE_LINE = p("§eProtect Elle §7\\(§.\\d+%§7\\)|\\s*§.\\(§.[\\w,.]+§.\\/§.[\\w,.]+§.\\)|§f Mages.*|§f Barbarians.*|§edefeat Kuudra|§eand stun him|§.Fish \\d .*[fF]ish §.[✖✔]");
    public static final Pattern WTF_ARE_THOSE_LINES = p("§eMine \\d+ .*|§eKill 100 Automatons|§eFind a Jungle Key|§eFind the \\d+ Missing Pieces?|§eTalk to the Goblin King|§eBring items to Moby| Glowing Mushroom §8x\\d");
    public static final Pattern DARK_AUCTION_CURRENT_ITEM = p("Current Item:");

    // ---- events ----
    public static final Pattern TRAVELING_ZOO = p("§aTraveling Zoo§f \\d*:\\d+");
    public static final Pattern NEW_YEAR = p("§dNew Year Event!§f \\d*:?\\d+");
    public static final Pattern SPOOKY = p("§6Spooky Festival§f \\d*:?\\d+");
    public static final Pattern WINTER_EVENT_START = p("(?:§.)*Event Start: §.[\\d:]+$");
    public static final Pattern WINTER_NEXT_WAVE = p("(?:§.)*Next Wave: (?:§.)*(?:[\\d:]+|Soon!)");
    public static final Pattern WINTER_WAVE = p("(?:§.)*Wave \\d+");
    public static final Pattern WINTER_MAGMA_LEFT = p("(?:§.)*Magma Cubes Left: §.-?\\d+");
    public static final Pattern WINTER_TOTAL_DMG = p("(?:§.)*Your Total Damage: §.[\\d+,.]+.*$");
    public static final Pattern WINTER_CUBE_DMG = p("(?:§.)*Your Cube Damage: §.[\\d+,.]+$");

    // ---- rift ----
    public static final Pattern RIFT_DIMENSION = p("\\s*(?:§f)?Rift Dimension");
    public static final Pattern RIFT_HOT_DOG_TITLE = p("§6Hot Dog Contest");
    public static final Pattern RIFT_HOT_DOG_EATEN = p("Eaten: §.\\d+/\\d+");
    public static final Pattern RIFT_AVEIKX = p("Time spent sitting|with Ävaeìkx: .*");
    public static final Pattern RIFT_HAY_EATEN = p("Hay Eaten: §.[\\d,.]+/[\\d,.]+");
    public static final Pattern CLUES = p("Clues: §.\\d+/\\d+");
    public static final Pattern BARRY_PROTESTORS_QUESTLINE = p("§eFirst Up|Find and talk with Barry");
    public static final Pattern BARRY_PROTESTORS_HANDLED = p("Protestors handled: §b\\d+\\/\\d+");
    public static final Pattern TIME_SLICED = p("§c§lTIME SLICED!");
    public static final Pattern BIG_DAMAGE = p("\\s*Big damage in: §d[\\w\\s]+");

    // ---- carnival ----
    public static final Pattern CARNIVAL = p("§eCarnival§f \\d+(?::\\d+)*");
    public static final Pattern CARNIVAL_TASKS = p("§.§l(?:Catch a Fish|Fruit Digging|Zombie Shootout)");
    public static final Pattern CARNIVAL_TOKENS = p("(?:§f)*Carnival Tokens: §e[\\d,]+");
    public static final Pattern CARNIVAL_FRUITS = p("(?:§f)?Fruits: §.\\d+§./§.\\d+");
    public static final Pattern CARNIVAL_SCORE = p("(?:§f)?Score: §.\\d+.*");
    public static final Pattern CARNIVAL_CATCH_STREAK = p("(?:§f)?Catch Streak: §.\\d+");
    public static final Pattern CARNIVAL_ACCURACY = p("(?:§f)?Accuracy: §.\\d+(?:\\.\\d+)?%");
    public static final Pattern CARNIVAL_KILLS = p("(?:§f)?Kills: §.\\d+");

    // ---- galatea ----
    public static final Pattern WHISPERS = p("(?:§f)?Whispers: §[36][\\w,.]+.*");
    public static final Pattern HOTF = p("\\s*§aHOTF§f: §a[\\w,.]+.*");
    public static final Pattern AGATHAS_CONTEST = p("§eAgatha's Contest §a.*");
    public static final Pattern MIRIAS_CONTEST = p("§eMiria's Contest §a.*");

    // ---- safari ----
    public static final Pattern CAPTURED_MOBS = p("Captured Mobs: §e(?<capturedMobs>\\d+)");

    /** Half-updated scoreboard lines SkyHanni swallows instead of reporting as unknown. */
    public static final List<Pattern> BROKEN = List.of(
            p("\\s*§.§l⚡ §cRedston"),
            p("\\s*§ce: §e§b\\d+%"),
            p("\\s*Starting in: §a0 §c[\\d:]+"),
            p("(?:§.)*᠅ §.(?<type>Gemstone|Mithril|Glacite)(?: Powder)?.*"));

    // ---- borrowed from other SkyHanni APIs ----
    /** PurseApi.coinsPattern */
    public static final Pattern COINS = p("(?:§.)*(?:Piggy|Purse): §6(?<coins>[\\d,.]+)(?: ?(?:§.)*\\([+-](?<earned>[\\d,.]+)\\)?|.*)?$");
    /** BitsApi.bitsScoreboardPattern */
    public static final Pattern BITS = p("^Bits: §b(?<amount>[\\d,.]+).*$");
    /** MiningApi.heatPattern */
    public static final Pattern HEAT = p("^Heat: (?<scoreboard>§.(?<heat>\\d+|IMMUNE)♨?)$");
    /** MiningApi.coldPattern */
    public static final Pattern COLD = p("(?:§.)*Cold: §.(?<cold>-?\\d+)❄");
    /** ServerRestartTitle.restartingGreedyPattern */
    public static final Pattern SERVER_CLOSING = p("§cServer closing.*");
    /** RiftBloodEffigies.heartsPattern */
    public static final Pattern EFFIGIES = p("Effigies: (?<hearts>(?:(?:§[7c])?⧯)*)");
    /** HypixelData.skyblockAreaPattern (restricted to the area symbols so profile-type lines don't count). */
    public static final Pattern SKYBLOCK_AREA = p("\\s*§\\d(?<symbol>[⏣ф]) §(?<color>.)(?<area>.*)");

    // ---- tab list (unformatted) ----
    public static final Pattern TAB_EVENT_TIME_ENDS = p("\\s+Ends In: (?<time>.*)");
    public static final Pattern TAB_EVENT_TIME_STARTS = p("\\s+Starts In: (?<time>.*)");
    public static final Pattern TAB_PLAYER_LIST = p("Players \\((?<amount>\\d+)\\)");
    public static final Pattern TAB_AREA = p("(?:Area|Dungeon): (?<island>.*)");
    public static final Pattern TAB_SERVER = p("Server: (?<serverid>.*)");
    public static final Pattern TAB_GEMS = p("Gems: (?<gems>.*)");
    public static final Pattern TAB_PROFILE = p("Profile: (?<profile>[\\w\\s]+?)(?:[ ♲Ⓑ☀]+)?");
    public static final Pattern TAB_SB_LEVEL = p("SB Level: \\[(?<level>\\d+)] (?<xp>\\d+).*");
    public static final Pattern TAB_BANK = p("Bank: (?<amount>[^§/]+?)(?: / (?<personal>.*))?");
    public static final Pattern TAB_SOULFLOW = p("Soulflow: (?<amount>.*)");
    public static final Pattern TAB_EVENT = p("Event: (?<event>.*)");
    public static final Pattern TAB_COPPER = p("Copper: (?<copper>.+)");
    public static final Pattern TAB_SOWDUST = p("Sowdust: (?<sowdust>.+)");
    public static final Pattern TAB_POWDERS = p("Powders:");
    public static final Pattern TAB_POWDER_LINE = p("\\s*(?<type>Mithril|Gemstone|Glacite)(?: Powder)?: (?<amount>[\\d,.]+).*");
    public static final Pattern TAB_BROODMOTHER = p("Broodmother: (?<stage>.*)");

    // ---- action bar (unformatted) ----
    public static final Pattern ACTION_BAR_SECRETS = p(".*?(?<found>\\d+)/(?<total>\\d+) Secrets.*");
}
