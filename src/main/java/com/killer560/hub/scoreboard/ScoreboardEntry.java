package com.killer560.hub.scoreboard;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.scoreboard.CustomScoreboardConfig.Align;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.killer560.hub.scoreboard.ScoreboardData.firstMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.group;
import static com.killer560.hub.scoreboard.ScoreboardData.inIsland;
import static com.killer560.hub.scoreboard.ScoreboardData.inIslandOrUnknown;
import static com.killer560.hub.scoreboard.ScoreboardData.island;
import static com.killer560.hub.scoreboard.ScoreboardData.matches;
import static com.killer560.hub.scoreboard.ScoreboardData.nextAfter;
import static com.killer560.hub.scoreboard.ScoreboardData.sidebar;
import static com.killer560.hub.scoreboard.ScoreboardData.tabHeaderGroup;
import static com.killer560.hub.scoreboard.ScoreboardLine.formatNumberDisplay;
import static com.killer560.hub.scoreboard.ScoreboardLine.formatStringNum;
import static com.killer560.hub.scoreboard.ScoreboardLine.isZero;

/**
 * The reorderable Custom Scoreboard lines - SkyHanni's {@code ScoreboardConfigElement} + {@code elements/ScoreboardElement*}.
 * Default order and default-on set follow SkyHanni's {@code defaultOptions}; the elements that need SkyHanni-only
 * APIs (Mayor, Cookie Buff, Maxwell Power/Tuning, Quiver, Chunked Stats) are not ported, SB Level is off by default
 * like SkyHanni. {@link #showIsland()} is SkyHanni's island filter (skipped while the island is unknown, e.g. on
 * p3sim); {@link #showWhen()} only applies with "Hide Irrelevant Lines" on.
 */
public enum ScoreboardEntry {

    TITLE("Title", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            Align align = cfg.getTitleAlignment();
            List<ScoreboardLine> out = new ArrayList<>();
            if (cfg.isUseCustomTitle()) {
                for (String part : cfg.getCustomTitle().replace("&&", "§").split("\\\\n")) {
                    out.add(new ScoreboardLine(part, align));
                }
            } else if (!ScoreboardData.objectiveTitle().isEmpty()) {
                out.add(new ScoreboardLine(ScoreboardData.objectiveTitle(), align));
            }
            return out;
        }

        @Override
        List<String> sample() {
            return List.of("§6§lSKYBLOCK");
        }
    },
    LOBBY_CODE("Lobby Code", true, ScoreboardPattern.LOBBY_CODE) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single(trim(firstMatches(ScoreboardPattern.LOBBY_CODE, sidebar())));
        }

        @Override
        List<String> sample() {
            return List.of("§709/15/26 §8mega77CK");
        }
    },
    EMPTY_LINE("Separator", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    },
    DATE("Date", true, ScoreboardPattern.DATE) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single(trim(firstMatches(ScoreboardPattern.DATE, sidebar())));
        }

        @Override
        List<String> sample() {
            return List.of("Late Summer 11th");
        }
    },
    TIME("Time", true, ScoreboardPattern.TIME) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single(trim(firstMatches(ScoreboardPattern.TIME, sidebar())));
        }

        @Override
        List<String> sample() {
            return List.of("§710:40pm §b☽");
        }
    },
    ISLAND("Island", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return island().isEmpty() ? List.of() : single("§7㋖ §a" + island());
        }

        @Override
        List<String> sample() {
            return List.of("§7㋖ §aHub");
        }
    },
    PLAYER_AMOUNT("Player Amount", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String amount = tabHeaderGroup(ScoreboardPattern.TAB_PLAYER_LIST, "amount");
            return amount == null ? List.of() : single(formatNumberDisplay("Players", amount, "§a"));
        }

        @Override
        List<String> sample() {
            return List.of("§fPlayers: §a69");
        }
    },
    LOCATION("Location", true, ScoreboardPattern.LOCATION, ScoreboardPattern.PLOT) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> out = new ArrayList<>();
            String area = firstMatches(ScoreboardPattern.SKYBLOCK_AREA, sidebar());
            if (area != null) {
                out.add(area.trim());
            }
            String plot = firstMatches(ScoreboardPattern.PLOT, sidebar());
            if (plot != null) {
                out.add(plot.trim());
            }
            return ScoreboardLine.of(out);
        }

        @Override
        List<String> sample() {
            return List.of("§7⏣ §bVillage");
        }
    },
    VISITING("Visiting", true, ScoreboardPattern.VISITING) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single(firstMatches(ScoreboardPattern.VISITING, sidebar()));
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Private Island", "Garden");
        }
    },
    PROFILE("Profile", true, ScoreboardPattern.PROFILE_TYPE) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String typeLine = firstMatches(ScoreboardPattern.PROFILE_TYPE, sidebar());
            String tabProfile = null;
            for (String line : ScoreboardData.tabPlain()) {
                String t = line.trim();
                if (t.startsWith("Profile: ")) {
                    tabProfile = t;
                    break;
                }
            }
            String probe = (typeLine == null ? "" : typeLine) + " " + (tabProfile == null ? "" : tabProfile);
            String symbol;
            String type;
            if (probe.contains("♲")) {
                symbol = "§7♲ ";
                type = "Ironman";
            } else if (probe.contains("☀")) {
                symbol = "§a☀ ";
                type = "Stranded";
            } else if (probe.contains("Ⓑ")) {
                symbol = "§9Ⓑ ";
                type = "Bingo";
            } else {
                symbol = "§e";
                type = "Normal";
            }
            if (cfg.isShowProfileName() && tabProfile != null) {
                Matcher m = ScoreboardPattern.TAB_PROFILE.matcher(tabProfile);
                if (m.matches()) {
                    String name = m.group("profile").trim();
                    if (!name.isEmpty()) {
                        type = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    }
                }
            }
            if (typeLine == null && tabProfile == null && ScoreboardData.sidebar().isEmpty()) {
                return List.of();
            }
            return single(symbol + type);
        }

        @Override
        List<String> sample() {
            return List.of("§7♲ Ironman");
        }
    },
    EMPTY_LINE2("Separator", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    },
    PURSE("Purse", true, ScoreboardPattern.COINS) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String line = firstMatches(ScoreboardPattern.COINS, sidebar());
            String coins = group(ScoreboardPattern.COINS, sidebar(), "coins");
            String label = line != null && line.replaceAll("§.", "").startsWith("Piggy") ? "Piggy" : "Purse";
            return number(cfg, label, coins, "§6");
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }

        @Override
        List<String> sample() {
            return List.of("§fPurse: §652,763,737");
        }
    },
    MOTES("Motes", true, ScoreboardPattern.MOTES) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Motes", group(ScoreboardPattern.MOTES, sidebar(), "motes"), "§d");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("The Rift");
        }
    },
    BANK("Bank", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_BANK);
            if (widget.isEmpty()) {
                return List.of();
            }
            Matcher m = ScoreboardPattern.TAB_BANK.matcher(ScoreboardData.tabPlain().get(widget.get(0)).trim());
            if (!m.matches()) {
                return List.of();
            }
            String amount = m.group("amount").trim();
            String personal = m.group("personal");
            if (cfg.isHideEmptyLines() && isZero(amount) && (personal == null || isZero(personal))) {
                return List.of();
            }
            String value = amount + (personal != null ? " §7/ §6" + personal.trim() : "");
            return single(formatNumberDisplay("Bank", value, "§6"));
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }
    },
    BITS("Bits", true, ScoreboardPattern.BITS) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Bits", group(ScoreboardPattern.BITS, sidebar(), "amount"), "§b");
        }

        @Override
        boolean showIsland() {
            return !inIsland("Catacombs", "Kuudra");
        }
    },
    COPPER("Copper", true, ScoreboardPattern.COPPER) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String copper = group(ScoreboardPattern.COPPER, sidebar(), "copper");
            if (copper == null) {
                copper = tabHeaderGroup(ScoreboardPattern.TAB_COPPER, "copper");
            }
            return number(cfg, "Copper", copper, "§c");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Garden");
        }
    },
    SOWDUST("Sowdust", true, ScoreboardPattern.SOWDUST, ScoreboardPattern.SOWDUST_GAINED) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String sowdust = group(ScoreboardPattern.SOWDUST, sidebar(), "sowdust");
            if (sowdust == null) {
                sowdust = tabHeaderGroup(ScoreboardPattern.TAB_SOWDUST, "sowdust");
            }
            return number(cfg, "Sowdust", sowdust, "§2");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Garden");
        }
    },
    GEMS("Gems", true, ScoreboardPattern.GEMS) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String gems = tabHeaderGroup(ScoreboardPattern.TAB_GEMS, "gems");
            if (gems == null) {
                gems = group(ScoreboardPattern.GEMS, sidebar(), "gems");
            }
            return number(cfg, "Gems", gems, "§a");
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift", "Catacombs", "Kuudra");
        }
    },
    HEAT("Heat", true, ScoreboardPattern.HEAT) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String display = group(ScoreboardPattern.HEAT, sidebar(), "scoreboard");
            if (display == null) {
                return List.of();
            }
            String heat = group(ScoreboardPattern.HEAT, sidebar(), "heat");
            if (cfg.isHideEmptyLines() && "0".equals(heat)) {
                return List.of();
            }
            return single(formatNumberDisplay("Heat", display, "§c"));
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Crystal Hollows");
        }
    },
    COLD("Cold", true, ScoreboardPattern.COLD) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String cold = group(ScoreboardPattern.COLD, sidebar(), "cold");
            if (cold == null) {
                return List.of();
            }
            int value;
            try {
                value = -Math.abs(Integer.parseInt(cold));
            } catch (NumberFormatException e) {
                return List.of();
            }
            if (cfg.isHideEmptyLines() && value == 0) {
                return List.of();
            }
            return single(formatNumberDisplay("Cold", value + "❄", "§b"));
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Dwarven Mines", "Mineshaft");
        }
    },
    NORTH_STARS("North Stars", true, ScoreboardPattern.NORTH_STARS) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "North Stars", group(ScoreboardPattern.NORTH_STARS, sidebar(), "northstars"), "§d");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Jerry's Workshop");
        }
    },
    SOULFLOW("Soulflow", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Soulflow", tabHeaderGroup(ScoreboardPattern.TAB_SOULFLOW, "amount"), "§3");
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }
    },
    EMPTY_LINE3("Separator", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    },
    EVENTS("Events", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<ScoreboardLine> out = new ArrayList<>();
            for (CustomScoreboardConfig.Row<ScoreboardEvent> row : cfg.events()) {
                if (!row.enabled || !row.id.visible(cfg)) {
                    continue;
                }
                List<ScoreboardLine> lines = row.id.lines(cfg);
                if (lines.isEmpty()) {
                    continue;
                }
                out.addAll(lines);
                if (!cfg.isShowAllActiveEvents()) {
                    break;
                }
            }
            return out;
        }

        @Override
        boolean showWhen() {
            return true;
        }

        @Override
        List<String> sample() {
            return List.of("§7Time Elapsed: §a1m 12s", "§7Cleared: §c42% §8(143)");
        }
    },
    EMPTY_LINE4("Separator", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    },
    OBJECTIVE("Objective", true, ScoreboardPattern.OBJECTIVE, ScoreboardPattern.THIRD_OBJECTIVE_LINE,
            ScoreboardPattern.WTF_ARE_THOSE_LINES) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> sb = sidebar();
            String objective = firstMatches(ScoreboardPattern.OBJECTIVE, sb);
            if (objective == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add(objective);
            String next = nextAfter(sb, objective, 1);
            if (next != null) {
                out.add(next);
            }
            int index = 2;
            while (matches(ScoreboardPattern.THIRD_OBJECTIVE_LINE, nextAfter(sb, objective, index))) {
                out.add(nextAfter(sb, objective, index));
                index++;
            }
            return ScoreboardLine.of(out);
        }

        @Override
        List<String> sample() {
            return List.of("Objective", "§eTalk to the Goblin King");
        }
    },
    SLAYER("Slayer", true, ScoreboardPattern.SLAYER_QUEST) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> sb = sidebar();
            String header = firstMatches(ScoreboardPattern.SLAYER_QUEST, sb);
            if (header == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add(header);
            out.addAll(ScoreboardData.sublistAfter(sb, header, 2));
            return ScoreboardLine.of(out);
        }
    },
    POWDER("Powder", true, ScoreboardPattern.POWDER) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String[] names = {"Mithril", "Gemstone", "Glacite"};
            String[] colors = {"§2", "§d", "§b"};
            String[] amounts = new String[3];
            List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_POWDERS);
            for (int i = 1; i < widget.size(); i++) {
                Matcher m = ScoreboardPattern.TAB_POWDER_LINE.matcher(ScoreboardData.tabPlain().get(widget.get(i)));
                if (m.matches()) {
                    amounts[indexOf(names, m.group("type"))] = m.group("amount");
                }
            }
            for (String line : sidebar()) {
                Matcher m = ScoreboardPattern.POWDER.matcher(line);
                if (m.matches() && !m.group("amount").isEmpty()) {
                    int idx = indexOf(names, m.group("type"));
                    if (amounts[idx] == null) {
                        amounts[idx] = m.group("amount");
                    }
                }
            }
            boolean allEmpty = true;
            for (String a : amounts) {
                allEmpty &= isZero(a);
            }
            if (allEmpty && (cfg.isHideEmptyLines() || amounts[0] == null && amounts[1] == null && amounts[2] == null)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add("§9§lPowder");
            for (int i = 0; i < 3; i++) {
                out.add(" §7- " + formatNumberDisplay(names[i], formatStringNum(amounts[i] == null ? "0" : amounts[i]), colors[i]));
            }
            return ScoreboardLine.of(out);
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Dwarven Mines", "Crystal Hollows", "Mineshaft");
        }
    },
    PARTY("Party", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> members;
            try {
                members = PartyTracker.teammates();
            } catch (RuntimeException e) {
                members = Collections.emptyList();
            }
            if (members.isEmpty() && cfg.isHideEmptyLines()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            out.add(members.isEmpty() ? "§9§lParty" : "§9§lParty (" + members.size() + ")");
            for (int i = 0; i < members.size() && i < cfg.getMaxPartyMembers(); i++) {
                out.add(" §7- §f" + members.get(i));
            }
            return ScoreboardLine.of(out);
        }

        @Override
        boolean showWhen() {
            CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
            return cfg.isShowPartyEverywhere()
                    || inIsland("Dungeon Hub", "Kuudra", "Crimson Isle", "Dwarven Mines", "Mineshaft");
        }

        @Override
        boolean showIsland() {
            return !inIsland("Catacombs");
        }
    },
    FOOTER("Footer", true, ScoreboardPattern.FOOTER) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String footer = cfg.getCustomFooter();
            String hypixel = firstMatches(ScoreboardPattern.FOOTER, sidebar());
            if (hypixel != null && hypixel.contains("alpha") && footer.equals(CustomScoreboardConfig.DEFAULT_FOOTER)) {
                footer = "&&ealpha.hypixel.net";
            }
            List<ScoreboardLine> out = new ArrayList<>();
            for (String part : footer.replace("&&", "§").split("\\\\n")) {
                out.add(new ScoreboardLine(part, cfg.getFooterAlignment()));
            }
            return out;
        }

        @Override
        List<String> sample() {
            return List.of("§ewww.hypixel.net");
        }
    },
    EXTRA("Unknown Lines", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return ScoreboardLine.of(CustomScoreboardFeature.unknownLines());
        }
    },
    SKYBLOCK_XP("SB Level", false) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_SB_LEVEL);
            if (widget.isEmpty()) {
                return List.of();
            }
            Matcher m = ScoreboardPattern.TAB_SB_LEVEL.matcher(ScoreboardData.tabPlain().get(widget.get(0)).trim());
            if (!m.matches()) {
                return List.of();
            }
            return ScoreboardLine.of(List.of(
                    formatNumberDisplay("SB Level", m.group("level"), "§b"),
                    formatNumberDisplay("XP", m.group("xp") + "§3/§b100", "§b")));
        }
    },
    EMPTY_LINE5("Separator", false) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    },
    EMPTY_LINE6("Separator", false) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return single("");
        }
    };

    public final String label;
    public final boolean enabledByDefault;
    public final List<Pattern> patterns;

    ScoreboardEntry(String label, boolean enabledByDefault, Pattern... patterns) {
        this.label = label;
        this.enabledByDefault = enabledByDefault;
        this.patterns = List.of(patterns);
    }

    abstract List<ScoreboardLine> lines(CustomScoreboardConfig cfg);

    boolean showIsland() {
        return true;
    }

    boolean showWhen() {
        return true;
    }

    /** HUD-editor preview text when there's no live data. */
    List<String> sample() {
        return List.of();
    }

    boolean visible(CustomScoreboardConfig cfg) {
        return showIsland() && (!cfg.isHideIrrelevantLines() || showWhen());
    }

    public boolean isSeparator() {
        return name().startsWith("EMPTY_LINE");
    }

    static List<ScoreboardLine> single(String text) {
        return text == null ? List.of() : List.of(ScoreboardLine.of(text));
    }

    static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** A "Label: value" currency line; hidden when missing, or zero with "Hide Empty Lines". */
    static List<ScoreboardLine> number(CustomScoreboardConfig cfg, String label, String raw, String color) {
        if (raw == null) {
            return cfg.isHideEmptyLines() ? List.of() : single(formatNumberDisplay(label, "0", color));
        }
        String value = formatStringNum(raw.trim());
        if (cfg.isHideEmptyLines() && isZero(value)) {
            return List.of();
        }
        return single(formatNumberDisplay(label, value, color));
    }
}
