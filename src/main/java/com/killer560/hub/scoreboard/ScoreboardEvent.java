package com.killer560.hub.scoreboard;

import com.killer560.hub.scoreboard.CustomScoreboardConfig.Align;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.killer560.hub.scoreboard.ScoreboardData.allMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.firstMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.inIslandOrUnknown;
import static com.killer560.hub.scoreboard.ScoreboardData.matches;
import static com.killer560.hub.scoreboard.ScoreboardData.nextAfter;
import static com.killer560.hub.scoreboard.ScoreboardData.sidebar;
import static com.killer560.hub.scoreboard.ScoreboardData.sublistAfter;

/**
 * The reorderable "Events" block - SkyHanni's {@code ScoreboardConfigEventElement} + {@code events/ScoreboardEvent*}.
 * Default order/on-set follows SkyHanni's {@code defaultOption} (Queue, Anniversary and Starting Soon tab events are
 * off by default there too). The Dungeons event also adds the action bar's "x/y Secrets" count.
 */
public enum ScoreboardEvent {

    VOTING("Voting", true, ScoreboardPattern.YEAR_VOTES, ScoreboardPattern.VOTES, ScoreboardPattern.WAITING_FOR_VOTE) {
        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Hub");
        }
    },
    SERVER_CLOSE("Server Closing", true, ScoreboardPattern.SERVER_CLOSING) {
        @Override
        List<String> texts() {
            String line = firstMatches(ScoreboardPattern.SERVER_CLOSING, sidebar());
            return line == null ? List.of() : List.of(line.split("§8")[0]);
        }
    },
    DUNGEONS("Dungeons", true, ScoreboardPattern.M7_DRAGONS, ScoreboardPattern.AUTO_CLOSING, ScoreboardPattern.STARTING_IN,
            ScoreboardPattern.KEYS, ScoreboardPattern.TIME_ELAPSED, ScoreboardPattern.CLEARED, ScoreboardPattern.SOLO,
            ScoreboardPattern.TEAMMATES, ScoreboardPattern.FLOOR3_GUARDIANS) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (String line : allMatches(patterns, sidebar())) {
                out.add(line.startsWith("§r") ? line.substring(2) : line);
            }
            Matcher m = ScoreboardPattern.ACTION_BAR_SECRETS.matcher(ScoreboardData.actionBar());
            if (!out.isEmpty() && m.matches()) {
                out.add("§fSecrets: §b" + m.group("found") + "§7/§b" + m.group("total"));
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Catacombs");
        }
    },
    KUUDRA("Kuudra", true, ScoreboardPattern.AUTO_CLOSING, ScoreboardPattern.STARTING_IN, ScoreboardPattern.TIME_ELAPSED,
            ScoreboardPattern.INSTANCE_SHUTDOWN, ScoreboardPattern.WAVE, ScoreboardPattern.TOKENS, ScoreboardPattern.SUBMERGES) {
        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Kuudra");
        }
    },
    DOJO("Dojo", true, ScoreboardPattern.DOJO_CHALLENGE, ScoreboardPattern.DOJO_DIFFICULTY, ScoreboardPattern.DOJO_POINTS,
            ScoreboardPattern.DOJO_TIME) {
        @Override
        boolean showWhen() {
            String area = firstMatches(ScoreboardPattern.SKYBLOCK_AREA, sidebar());
            return area != null && area.contains("Dojo");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Crimson Isle");
        }
    },
    DARK_AUCTION("Dark Auction", true, ScoreboardPattern.STARTING_IN, ScoreboardPattern.TIME_LEFT,
            ScoreboardPattern.DARK_AUCTION_CURRENT_ITEM) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>(allMatches(List.of(ScoreboardPattern.STARTING_IN, ScoreboardPattern.TIME_LEFT), sidebar()));
            String current = firstMatches(ScoreboardPattern.DARK_AUCTION_CURRENT_ITEM, sidebar());
            if (current != null) {
                out.add(current);
                String next = nextAfter(sidebar(), current, 1);
                if (next != null) {
                    out.add(next);
                }
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Dark Auction");
        }
    },
    JACOB_CONTEST("Jacob's Contest", true, ScoreboardPattern.JACOBS_CONTEST) {
        @Override
        List<String> texts() {
            return headerSection(ScoreboardPattern.JACOBS_CONTEST, 3);
        }
    },
    JACOB_MEDALS("Jacob's Medals", true, ScoreboardPattern.MEDALS),
    GALATEA("Galatea", true, ScoreboardPattern.WHISPERS, ScoreboardPattern.HOTF, ScoreboardPattern.AGATHAS_CONTEST,
            ScoreboardPattern.MIRIAS_CONTEST) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            addIfPresent(out, firstMatches(ScoreboardPattern.WHISPERS, sidebar()));
            addIfPresent(out, firstMatches(ScoreboardPattern.HOTF, sidebar()));
            out.addAll(headerSection(ScoreboardPattern.AGATHAS_CONTEST, 2));
            out.addAll(headerSection(ScoreboardPattern.MIRIAS_CONTEST, 2));
            return out;
        }
    },
    SAFARI("Safari", true, ScoreboardPattern.CAPTURED_MOBS),
    TRAPPER("Trapper", true, ScoreboardPattern.PELTS, ScoreboardPattern.MOB_LOCATION) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            addIfPresent(out, firstMatches(ScoreboardPattern.PELTS, sidebar()));
            String mobLocation = firstMatches(ScoreboardPattern.MOB_LOCATION, sidebar());
            if (mobLocation != null) {
                out.add(mobLocation);
                addIfPresent(out, nextAfter(sidebar(), mobLocation, 1));
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("The Farming Islands");
        }
    },
    GARDEN("Garden", true, ScoreboardPattern.LOCKED, ScoreboardPattern.PASTING, ScoreboardPattern.CLEAN_UP) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (String line : allMatches(patterns, sidebar())) {
                out.add(line.trim());
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Garden");
        }
    },
    FLIGHT_DURATION("Flight Duration", true, ScoreboardPattern.FLIGHT_DURATION) {
        @Override
        List<String> texts() {
            String line = firstMatches(ScoreboardPattern.FLIGHT_DURATION, sidebar());
            return line == null ? List.of() : List.of(line.trim());
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Private Island", "Garden");
        }
    },
    NEW_YEAR("New Year", true, ScoreboardPattern.NEW_YEAR),
    WINTER("Winter", true, ScoreboardPattern.WINTER_EVENT_START, ScoreboardPattern.WINTER_NEXT_WAVE,
            ScoreboardPattern.WINTER_WAVE, ScoreboardPattern.WINTER_MAGMA_LEFT, ScoreboardPattern.WINTER_TOTAL_DMG,
            ScoreboardPattern.WINTER_CUBE_DMG) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (String line : allMatches(patterns, sidebar())) {
                if (!line.endsWith("Soon!")) {
                    out.add(line);
                }
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Jerry's Workshop");
        }
    },
    SPOOKY("Spooky Festival", true, ScoreboardPattern.SPOOKY) {
        @Override
        List<String> texts() {
            String time = firstMatches(ScoreboardPattern.SPOOKY, sidebar());
            if (time == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add(time);
            out.add("§7Your Candy: ");
            String candy = null;
            for (String footerLine : ScoreboardData.tabFooter().split("\n")) {
                if (footerLine.startsWith("Your Candy:")) {
                    candy = footerLine.substring("Your Candy:".length()).trim();
                    break;
                }
            }
            out.add(candy != null ? candy : "§cCandy not found");
            return out;
        }
    },
    BROODMOTHER("Broodmother", true) {
        @Override
        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (int i : ScoreboardData.tabWidget(ScoreboardPattern.TAB_BROODMOTHER)) {
                out.add(ScoreboardData.tabFormatted().get(i).trim());
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Spider's Den");
        }
    },
    MINING_EVENTS("Mining Events", true, ScoreboardPattern.WIND_COMPASS, ScoreboardPattern.WIND_COMPASS_ARROW,
            ScoreboardPattern.NEARBY_PLAYERS, ScoreboardPattern.MINING_EVENT, ScoreboardPattern.MINING_EVENT_ZONE,
            ScoreboardPattern.MITHRIL_REMAINING, ScoreboardPattern.MITHRIL_YOUR_MITHRIL, ScoreboardPattern.RAFFLE_TICKETS,
            ScoreboardPattern.RAFFLE_POOL, ScoreboardPattern.YOUR_GOBLIN_KILLS, ScoreboardPattern.REMAINING_GOBLIN,
            ScoreboardPattern.FORTUNATE_FREEZING_BONUS, ScoreboardPattern.FOSSIL_DUST, ScoreboardPattern.RAFFLE_USELESS,
            ScoreboardPattern.MITHRIL_USELESS, ScoreboardPattern.GOBLIN_USELESS, ScoreboardPattern.MINESHAFT_NOT_STARTED) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> sb = sidebar();
            List<ScoreboardLine> out = new ArrayList<>();
            String compassTitle = firstMatches(ScoreboardPattern.WIND_COMPASS, sb);
            String compassArrow = firstMatches(ScoreboardPattern.WIND_COMPASS_ARROW, sb);
            if (compassTitle != null && compassArrow != null) {
                out.add(ScoreboardLine.of(compassTitle));
                out.add(new ScoreboardLine(compassArrow, Align.CENTER));
            }
            String nearby = firstMatches(ScoreboardPattern.NEARBY_PLAYERS, sb);
            if (nearby != null) {
                out.add(ScoreboardLine.of("§dBetter Together"));
                out.add(ScoreboardLine.of(" " + nearby));
            }
            String zoneEvent = firstMatches(ScoreboardPattern.MINING_EVENT, sb);
            if (zoneEvent != null) {
                out.add(ScoreboardLine.of(zoneEvent.substring("Event: ".length())));
                String zone = firstMatches(ScoreboardPattern.MINING_EVENT_ZONE, sb);
                if (zone != null) {
                    out.add(ScoreboardLine.of("in " + zone.substring("Zone: ".length())));
                }
            }
            List<String> rest = new ArrayList<>();
            rest.addAll(allMatches(List.of(ScoreboardPattern.MITHRIL_REMAINING, ScoreboardPattern.MITHRIL_YOUR_MITHRIL), sb));
            rest.addAll(allMatches(List.of(ScoreboardPattern.RAFFLE_TICKETS, ScoreboardPattern.RAFFLE_POOL), sb));
            rest.addAll(allMatches(List.of(ScoreboardPattern.YOUR_GOBLIN_KILLS, ScoreboardPattern.REMAINING_GOBLIN), sb));
            addIfPresent(rest, firstMatches(ScoreboardPattern.FORTUNATE_FREEZING_BONUS, sb));
            addIfPresent(rest, firstMatches(ScoreboardPattern.FOSSIL_DUST, sb));
            out.addAll(ScoreboardLine.of(rest));
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Dwarven Mines", "Crystal Hollows", "Mineshaft");
        }
    },
    DAMAGE("Dragon Damage", true, ScoreboardPattern.BOSS_HP, ScoreboardPattern.BOSS_DAMAGE) {
        @Override
        boolean showIsland() {
            return inIslandOrUnknown("The End");
        }
    },
    MAGMA_BOSS("Magma Boss", true, ScoreboardPattern.MAGMA_BOSS, ScoreboardPattern.DAMAGE_SOAKED,
            ScoreboardPattern.KILL_MAGMAS, ScoreboardPattern.KILL_MAGMAS_BAR, ScoreboardPattern.REFORMING,
            ScoreboardPattern.BOSS_HEALTH, ScoreboardPattern.BOSS_HEALTH_BAR) {
        @Override
        boolean showWhen() {
            String area = firstMatches(ScoreboardPattern.SKYBLOCK_AREA, sidebar());
            return area != null && area.contains("Magma Chamber");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Crimson Isle");
        }
    },
    CARNIVAL("Carnival", true, ScoreboardPattern.CARNIVAL, ScoreboardPattern.CARNIVAL_TOKENS,
            ScoreboardPattern.CARNIVAL_TASKS, ScoreboardPattern.TIME_LEFT, ScoreboardPattern.CARNIVAL_CATCH_STREAK,
            ScoreboardPattern.CARNIVAL_FRUITS, ScoreboardPattern.CARNIVAL_ACCURACY, ScoreboardPattern.CARNIVAL_KILLS,
            ScoreboardPattern.CARNIVAL_SCORE) {
        @Override
        List<String> texts() {
            String header = firstMatches(ScoreboardPattern.CARNIVAL, sidebar());
            if (header == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add(header);
            out.addAll(allMatches(patterns.subList(1, patterns.size()), sidebar()));
            return out;
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Hub");
        }
    },
    RIFT("Rift", true, ScoreboardPattern.EFFIGIES, ScoreboardPattern.RIFT_HOT_DOG_TITLE, ScoreboardPattern.TIME_LEFT,
            ScoreboardPattern.RIFT_HOT_DOG_EATEN, ScoreboardPattern.RIFT_AVEIKX, ScoreboardPattern.RIFT_HAY_EATEN,
            ScoreboardPattern.CLUES, ScoreboardPattern.BARRY_PROTESTORS_QUESTLINE, ScoreboardPattern.BARRY_PROTESTORS_HANDLED,
            ScoreboardPattern.TIME_SLICED, ScoreboardPattern.BIG_DAMAGE, ScoreboardPattern.RIFT_DIMENSION) {
        @Override
        List<String> texts() {
            return allMatches(patterns.subList(0, patterns.size() - 1), sidebar());
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("The Rift");
        }
    },
    ESSENCE("Essence", true, ScoreboardPattern.ESSENCE) {
        @Override
        List<String> texts() {
            String line = firstMatches(ScoreboardPattern.ESSENCE, sidebar());
            return line == null ? List.of() : List.of(line);
        }
    },
    ACTIVE_TABLIST_EVENTS("Active Tab Events", true, ScoreboardPattern.TRAVELING_ZOO) {
        private final List<String> blocked = List.of("Spooky Festival", "Carnival", "th SkyBlock Anniversary", "New Year Celebration");

        @Override
        List<String> texts() {
            String name = tabEventName();
            if (name == null || blocked.contains(name.replaceAll("§.", ""))) {
                return List.of();
            }
            String time = tabEventTime(ScoreboardPattern.TAB_EVENT_TIME_ENDS);
            return time == null ? List.of() : List.of(name, " Ends in: §e" + time);
        }
    },
    REDSTONE("Redstone", true, ScoreboardPattern.REDSTONE) {
        @Override
        List<String> texts() {
            String line = firstMatches(ScoreboardPattern.REDSTONE, sidebar());
            return line == null ? List.of() : List.of(line);
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Private Island");
        }
    },
    QUEUE("Queue", false, ScoreboardPattern.QUEUE, ScoreboardPattern.QUEUE_TIER, ScoreboardPattern.QUEUE_POSITION,
            ScoreboardPattern.QUEUE_WAITING_FOR_LEADER),
    ANNIVERSARY("Anniversary", false, ScoreboardPattern.ANNIVERSARY) {
        @Override
        List<String> texts() {
            String line = firstMatches(ScoreboardPattern.ANNIVERSARY, sidebar());
            return line == null ? List.of() : List.of(line);
        }
    },
    STARTING_SOON_TABLIST_EVENTS("Starting Soon Tab Events", false) {
        @Override
        List<String> texts() {
            String name = tabEventName();
            if (name == null) {
                return List.of();
            }
            String time = tabEventTime(ScoreboardPattern.TAB_EVENT_TIME_STARTS);
            return time == null ? List.of() : List.of(name, " Starts in: §e" + time);
        }
    };

    public final String label;
    public final boolean enabledByDefault;
    public final List<Pattern> patterns;

    ScoreboardEvent(String label, boolean enabledByDefault, Pattern... patterns) {
        this.label = label;
        this.enabledByDefault = enabledByDefault;
        this.patterns = List.of(patterns);
    }

    /** Default: every sidebar line matching any of this event's patterns, in sidebar order. */
    List<String> texts() {
        return allMatches(patterns, sidebar());
    }

    List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
        return ScoreboardLine.of(texts());
    }

    boolean showIsland() {
        return true;
    }

    boolean showWhen() {
        return true;
    }

    boolean visible(CustomScoreboardConfig cfg) {
        return showIsland() && (!cfg.isHideIrrelevantLines() || showWhen());
    }

    static void addIfPresent(List<String> out, String line) {
        if (line != null) {
            out.add(line);
        }
    }

    /** Header line + up to {@code after} following lines, minus the footer (SkyHanni {@code sublistAfter}). */
    static List<String> headerSection(Pattern header, int after) {
        String line = firstMatches(header, sidebar());
        if (line == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(line);
        for (String next : sublistAfter(sidebar(), line, after)) {
            if (!matches(ScoreboardPattern.FOOTER, next)) {
                out.add(next);
            }
        }
        return out;
    }

    /** Formatted event name from the tab list's "Event: X" widget header, or null. */
    static String tabEventName() {
        List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_EVENT);
        if (widget.isEmpty()) {
            return null;
        }
        String formatted = ScoreboardData.tabFormatted().get(widget.get(0));
        int idx = formatted.indexOf("Event:");
        if (idx < 0) {
            return null;
        }
        String name = formatted.substring(idx + "Event:".length()).trim();
        while (name.startsWith("§r")) {
            name = name.substring(2).trim();
        }
        return name.isEmpty() ? null : name;
    }

    static String tabEventTime(Pattern pattern) {
        List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_EVENT);
        for (int i : widget) {
            Matcher m = pattern.matcher(ScoreboardData.tabPlain().get(i));
            if (m.matches()) {
                return m.group("time");
            }
        }
        return null;
    }
}
