package com.killer560.hub.scoreboard;

import com.killer560.hub.scoreboard.CustomScoreboardConfig.Align;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One rendered Custom Scoreboard line (legacy {@code §} text) and its alignment - SkyHanni's {@code ScoreboardLine},
 * plus the number helpers from its {@code CustomScoreboardUtils}. Optional extras:
 * <ul>
 * <li>{@code hover}/{@code command} - SkyBlock Custom Scoreboard's {@code LineActions}: tooltip lines and a command run
 * when the line is clicked while chat is open ("Clickable Lines");</li>
 * <li>{@code popup}/{@code popupUntilMs} - the "(+N)" number-change text drawn after the line, fading out
 * ({@link NumberChangeTracker}).</li>
 * </ul>
 */
public record ScoreboardLine(String text, Align align, List<String> hover, String command, String popup, long popupUntilMs) {

    public ScoreboardLine(String text, Align align) {
        this(text, align, null, null, null, 0L);
    }

    public static ScoreboardLine of(String text) {
        return new ScoreboardLine(text, CustomScoreboardConfig.getInstance().getTextAlignment());
    }

    public boolean isBlank() {
        return text == null || text.isBlank();
    }

    public boolean hasActions() {
        return (hover != null && !hover.isEmpty()) || command != null;
    }

    /** Copy with tooltip lines and/or a click command (either may be null). */
    public ScoreboardLine withActions(List<String> hoverLines, String clickCommand) {
        return new ScoreboardLine(text, align, hoverLines, clickCommand, popup, popupUntilMs);
    }

    public ScoreboardLine withPopup(String popupText, long untilMs) {
        return new ScoreboardLine(text, align, hover, command, popupText, untilMs);
    }

    /** The popup text if it hasn't expired yet, else null. */
    public String activePopup(long nowMs) {
        return popup != null && nowMs < popupUntilMs ? popup : null;
    }

    public static List<ScoreboardLine> of(List<String> texts) {
        List<ScoreboardLine> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            if (t != null) {
                out.add(of(t));
            }
        }
        return out;
    }

    /** SkyHanni's {@code CustomScoreboardUtils.formatNumberDisplay}. */
    public static String formatNumberDisplay(String text, String number, String color) {
        return switch (CustomScoreboardConfig.getInstance().getNumberDisplayFormat()) {
            case TEXT_COLOR_NUMBER -> "§f" + text + ": " + color + number;
            case COLOR_TEXT_NUMBER -> color + text + ": " + number;
            case COLOR_NUMBER_TEXT -> color + number + " " + color + text;
            case COLOR_NUMBER_RESET_TEXT -> color + number + " §f" + text;
        };
    }

    /** SkyHanni's {@code formatStringNum}: re-formats a scoreboard number (long = separators, short = 1.2M).
     *  Anything that doesn't parse is returned unchanged. */
    public static String formatStringNum(String raw) {
        if (raw == null) {
            return "0";
        }
        Double value = parse(raw);
        if (value == null) {
            return raw;
        }
        return formatNumber(value);
    }

    public static String formatNumber(double value) {
        if (CustomScoreboardConfig.getInstance().getNumberFormat() == CustomScoreboardConfig.NumberFormat.SHORT) {
            return shortFormat(value);
        }
        if (value == Math.floor(value) && Math.abs(value) < 9.0E15) {
            return String.format(Locale.US, "%,d", (long) value);
        }
        return String.format(Locale.US, "%,.1f", value);
    }

    public static Double parse(String raw) {
        String s = raw.replace(",", "").trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shortFormat(double v) {
        double abs = Math.abs(v);
        String suffix;
        double div;
        if (abs >= 1e9) {
            suffix = "B";
            div = 1e9;
        } else if (abs >= 1e6) {
            suffix = "M";
            div = 1e6;
        } else if (abs >= 1e3) {
            suffix = "k";
            div = 1e3;
        } else {
            return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
        }
        String n = String.format(Locale.US, "%.1f", v / div);
        if (n.endsWith(".0")) {
            n = n.substring(0, n.length() - 2);
        }
        return n + suffix;
    }

    public static boolean isZero(String formattedNumber) {
        if (formattedNumber == null) {
            return true;
        }
        Double d = parse(formattedNumber);
        return d != null && d == 0;
    }

    /** "4d 12h" style, at most {@code maxUnits} non-zero units (SkyHanni {@code Duration.format(maxUnits)}). */
    public static String formatDuration(long millis, int maxUnits) {
        if (millis < 1000) {
            return "0s";
        }
        long s = millis / 1000;
        long[] values = {s / 31_536_000L, (s % 31_536_000L) / 86_400L, (s % 86_400L) / 3600L, (s % 3600L) / 60L, s % 60L};
        String[] units = {"y", "d", "h", "m", "s"};
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < values.length && used < maxUnits; i++) {
            if (values[i] == 0) {
                if (used > 0) {
                    used++;
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(values[i]).append(units[i]);
            used++;
        }
        return sb.toString();
    }
}
