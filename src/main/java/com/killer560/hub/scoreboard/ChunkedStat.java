package com.killer560.hub.scoreboard;

import java.util.List;
import java.util.regex.Matcher;

import static com.killer560.hub.scoreboard.ScoreboardData.group;
import static com.killer560.hub.scoreboard.ScoreboardData.sidebar;
import static com.killer560.hub.scoreboard.ScoreboardData.tabHeaderGroup;
import static com.killer560.hub.scoreboard.ScoreboardData.tabLineGroup;
import static com.killer560.hub.scoreboard.ScoreboardLine.formatStringNum;
import static com.killer560.hub.scoreboard.ScoreboardLine.isZero;

/**
 * The stats grouped by the "Chunked Stats" line - the {@code ChunkedStatsLine} / SkyBlock Custom Scoreboard's
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
            if (copper == null) copper = tabHeaderGroup(ScoreboardPattern.TAB_COPPER, "copper");
            if (copper == null) copper = tabLineGroup(ScoreboardPattern.TAB_COPPER, "copper");
            return remember(copper);
        }
    },
    SOWDUST("Sowdust", ScoreboardEntry.SOWDUST, "§2") {
        @Override
        String raw() {
            String sowdust = group(ScoreboardPattern.SOWDUST, sidebar(), "sowdust");
            if (sowdust == null) sowdust = tabHeaderGroup(ScoreboardPattern.TAB_SOWDUST, "sowdust");
            if (sowdust == null) sowdust = tabLineGroup(ScoreboardPattern.TAB_SOWDUST, "sowdust");
            return remember(sowdust);
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

    private String lastRaw;
    private String lastIsland;
    private long lastAtMs;
    private Object lastLevel;

    /**
     * For a Garden stat: a value just read is kept (with its time and island, for bug reports); with no source this tick,
     * the last one read in this world is used while the island is the Garden, so a line the server drops for a moment
     * (or a tab widget reshuffle) never blanks the stat. Left alone, the value is never used outside the Garden.
     */
    String remember(String found) {
        Object level = net.minecraft.client.Minecraft.getInstance().level;
        if (found != null) {
            lastRaw = found;
            lastIsland = ScoreboardData.island();
            lastAtMs = System.currentTimeMillis();
            lastLevel = level;
            return found;
        }
        return level != null && level == lastLevel && ScoreboardData.inIsland("Garden") ? lastRaw : null;
    }

    /** Bug report text: the last value this stat read this session, when and where; empty if none. */
    private String lastSeen() {
        if (lastRaw == null) return "";
        return "; last read '" + lastRaw.trim() + "' at " + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(lastAtMs),
                java.time.ZoneId.systemDefault()).withNano(0) + " on island '" + lastIsland + "'";
    }

    String color() { return color; }

    /** One line per stat for a bug report: what the reader parsed, or "no source", and whether its line may show here. */
    public static String report() {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        StringBuilder sb = new StringBuilder();
        for (ChunkedStat s : values()) {
            String raw = s.raw();
            sb.append(s.label).append(": ").append(raw == null ? "no source" : "'" + raw.trim() + "' -> " + formatStringNum(raw.trim()))
                    .append("; line allowed on this island: ").append(s.entry.showIsland())
                    .append("; visible: ").append(s.entry.visible(cfg)).append(s.lastSeen()).append('\n');
        }
        return sb.toString();
    }

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
