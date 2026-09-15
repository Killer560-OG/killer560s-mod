package com.killer560.hub.scoreboard;

import com.killer560.hub.scoreboard.CustomScoreboardConfig.Align;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One rendered Custom Scoreboard line (legacy {@code §} text) and its alignment - SkyHanni's {@code ScoreboardLine},
 *  plus the number helpers from its {@code CustomScoreboardUtils}. */
public record ScoreboardLine(String text, Align align) {

    public static ScoreboardLine of(String text) {
        return new ScoreboardLine(text, CustomScoreboardConfig.getInstance().getTextAlignment());
    }

    public boolean isBlank() {
        return text == null || text.isBlank();
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
        if (CustomScoreboardConfig.getInstance().getNumberFormat() == CustomScoreboardConfig.NumberFormat.SHORT) {
            return shortFormat(value);
        }
        if (value == Math.floor(value) && Math.abs(value) < 9.0E15) {
            return String.format(Locale.US, "%,d", (long) (double) value);
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
}
