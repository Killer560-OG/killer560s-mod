package com.killer560.hub.scoreboard;

import java.util.List;
import java.util.regex.Matcher;

import static com.killer560.hub.scoreboard.ScoreboardData.group;
import static com.killer560.hub.scoreboard.ScoreboardData.sidebar;
import static com.killer560.hub.scoreboard.ScoreboardData.tabHeaderGroup;
import static com.killer560.hub.scoreboard.ScoreboardLine.formatStringNum;
import static com.killer560.hub.scoreboard.ScoreboardLine.isZero;

/**
 * The stats grouped by the "Chunked Stats" line - SkyHanni's {@code ChunkedStatsLine} / SkyBlock Custom Scoreboard's
 * {@code ChunkedStat}. Each stat is only shown where its own line would be ({@link ScoreboardEntry#visible}) and, with
 * "Hide Empty Lines", only when non-zero. Also holds the raw value readers shared with the matching
 * {@link ScoreboardEntry} lines.
 */
public enum ChunkedStat {
    PURSE("Purse", ScoreboardEntry.PURSE, "§6") {
        @Override
        String raw() { return group(ScoreboardPattern.COINS, sidebar(), "coins"); }
    },
    MOTES("Motes", ScoreboardEntry.MOTES, "§d") {
        @Override
        String raw() { return group(ScoreboardPattern.MOTES, sidebar(), "motes"); }
    },
    BANK("Bank", ScoreboardEntry.BANK, "§6") {
        @Override
        String raw() {
            String[] bank = bank();
            return bank == null ? null : bank[0];
        }

        @Override
        String display(CustomScoreboardConfig cfg) {
            String[] bank = bank();
            if (bank == null || cfg.isHideEmptyLines() && isZero(bank[0]) && (bank[1] == null || isZero(bank[1]))) return null;
            return "§6" + bank[0] + (bank[1] != null ? "§7/§6" + bank[1] : "");
        }
    },
    BITS("Bits", ScoreboardEntry.BITS, "§b") {
        @Override
        String raw() { return group(ScoreboardPattern.BITS, sidebar(), "amount"); }
    },
    COPPER("Copper", ScoreboardEntry.COPPER, "§c") {
        @Override
        String raw() {
            String copper = group(ScoreboardPattern.COPPER, sidebar(), "copper");
            return copper != null ? copper : tabHeaderGroup(ScoreboardPattern.TAB_COPPER, "copper");
        }
    },
    SOWDUST("Sowdust", ScoreboardEntry.SOWDUST, "§2") {
        @Override
        String raw() {
            String sowdust = group(ScoreboardPattern.SOWDUST, sidebar(), "sowdust");
            return sowdust != null ? sowdust : tabHeaderGroup(ScoreboardPattern.TAB_SOWDUST, "sowdust");
        }
    },
    GEMS("Gems", ScoreboardEntry.GEMS, "§a") {
        @Override
        String raw() {
            String gems = tabHeaderGroup(ScoreboardPattern.TAB_GEMS, "gems");
            return gems != null ? gems : group(ScoreboardPattern.GEMS, sidebar(), "gems");
        }
    },
    HEAT("Heat", ScoreboardEntry.HEAT, "§c") {
        @Override
        String raw() { return group(ScoreboardPattern.HEAT, sidebar(), "heat"); }

        @Override
        String display(CustomScoreboardConfig cfg) {
            String display = group(ScoreboardPattern.HEAT, sidebar(), "scoreboard");
            if (display == null || cfg.isHideEmptyLines() && "0".equals(raw())) return null;
            return "§c♨ " + display;
        }
    },
    COLD("Cold", ScoreboardEntry.COLD, "§b") {
        @Override
        String raw() {
            String cold = group(ScoreboardPattern.COLD, sidebar(), "cold");
            if (cold == null) return null;
            try {
                return String.valueOf(-Math.abs(Integer.parseInt(cold)));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        String display(CustomScoreboardConfig cfg) {
            String cold = raw();
            if (cold == null || cfg.isHideEmptyLines() && "0".equals(cold)) return null;
            return "§b" + cold + "❄";
        }
    },
    NORTH_STARS("North Stars", ScoreboardEntry.NORTH_STARS, "§d") {
        @Override
        String raw() { return group(ScoreboardPattern.NORTH_STARS, sidebar(), "northstars"); }
    };

    public final String label;
    final ScoreboardEntry entry;
    private final String color;

    ChunkedStat(String label, ScoreboardEntry entry, String color) {
        this.label = label;
        this.entry = entry;
        this.color = color;
    }

    /** The unformatted value as the sidebar / tab list shows it, or null when absent. */
    abstract String raw();

    String color() { return color; }

    /** Colored value for the chunked line, or null to leave this stat out. */
    String display(CustomScoreboardConfig cfg) {
        String raw = raw();
        if (raw == null) return null;
        String value = formatStringNum(raw.trim());
        if (cfg.isHideEmptyLines() && isZero(value)) return null;
        return color() + value;
    }

    /** {"amount", "personal or null"} from the tab list's Bank widget, or null. */
    static String[] bank() {
        List<Integer> widget = ScoreboardData.tabWidget(ScoreboardPattern.TAB_BANK);
        if (widget.isEmpty()) return null;
        Matcher m = ScoreboardPattern.TAB_BANK.matcher(ScoreboardData.tabPlain().get(widget.get(0)).trim());
        if (!m.matches()) return null;
        String personal = m.group("personal");
        return new String[]{m.group("amount").trim(), personal == null ? null : personal.trim()};
    }
}
