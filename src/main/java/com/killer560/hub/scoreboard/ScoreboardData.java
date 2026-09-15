package com.killer560.hub.scoreboard;

import com.killer560.hub.scoreboard.mixin.CustomScoreboardTabOverlayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw Skyblock data the Custom Scoreboard is built from, refreshed on the client tick:
 * <ul>
 * <li>sidebar lines in legacy {@code §} form - same construction as SkyHanni's {@code ScoreboardData}
 * ({@code team prefix + team suffix}, highest score first, duplicate colour codes at the prefix/suffix seam removed)
 * and the same formatted-text conversion as SkyHanni's {@code formattedTextCompatLessResets()};</li>
 * <li>the tab list in vanilla display order (unformatted + formatted) and its footer;</li>
 * <li>the latest action bar text (captured by {@code CustomScoreboardGuiMixin}).</li>
 * </ul>
 */
public final class ScoreboardData {

    private static final Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER = Comparator
            .comparing(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);
    private static final Pattern COLOR_CODES = Pattern.compile("(?:§[0-9a-fk-orA-FK-OR])+");
    private static final Pattern RESETS = Pattern.compile("(?:§f)*?(?<!§)§r");
    private static final Map<Integer, ChatFormatting> COLOR_LUT = new HashMap<>();

    static {
        for (ChatFormatting f : ChatFormatting.values()) {
            TextColor c = f.isColor() ? TextColor.fromLegacyFormat(f) : null;
            if (c != null) {
                COLOR_LUT.putIfAbsent(c.getValue(), f);
            }
        }
    }

    private static List<String> sidebarLines = Collections.emptyList();
    private static String objectiveTitle = "";
    private static List<String> tabPlain = Collections.emptyList();
    private static List<String> tabFormatted = Collections.emptyList();
    private static String tabFooterPlain = "";
    private static volatile String actionBarPlain = "";
    private static volatile long actionBarAtMs = 0L;
    private static String island = "";
    private static boolean accessorUsable = true;

    private ScoreboardData() {
    }

    // ---- refresh ----

    static void refresh(Minecraft client) {
        if (client.level == null || client.player == null) {
            sidebarLines = Collections.emptyList();
            objectiveTitle = "";
            tabPlain = Collections.emptyList();
            tabFormatted = Collections.emptyList();
            tabFooterPlain = "";
            island = "";
            return;
        }
        readSidebar(client);
        readTab(client);
        island = "";
        for (String line : tabPlain) {
            Matcher m = ScoreboardPattern.TAB_AREA.matcher(line.trim());
            if (m.matches()) {
                island = m.group("island").trim();
                break;
            }
        }
    }

    /** Same objective choice as vanilla {@code Gui#extractScoreboardSidebar}: team-colour slot first, then SIDEBAR. */
    public static Objective sidebarObjective(Minecraft client) {
        if (client.level == null) {
            return null;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = null;
        if (client.player != null) {
            PlayerTeam team = scoreboard.getPlayersTeam(client.player.getScoreboardName());
            if (team != null) {
                DisplaySlot slot = DisplaySlot.teamColorToSlot(team.getColor());
                if (slot != null) {
                    objective = scoreboard.getDisplayObjective(slot);
                }
            }
        }
        return objective != null ? objective : scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    private static void readSidebar(Minecraft client) {
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = sidebarObjective(client);
        if (objective == null) {
            sidebarLines = Collections.emptyList();
            objectiveTitle = "";
            return;
        }
        objectiveTitle = formatted(objective.getDisplayName(), false);
        List<PlayerScoreEntry> entries = new ArrayList<>();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            if (!entry.isHidden()) {
                entries.add(entry);
            }
        }
        entries.sort(SCORE_DISPLAY_ORDER);
        List<String> lines = new ArrayList<>(Math.min(15, entries.size()));
        for (int i = 0; i < entries.size() && i < 15; i++) {
            lines.add(lineText(scoreboard, entries.get(i)));
        }
        sidebarLines = Collections.unmodifiableList(lines);
    }

    private static String lineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        if (entry.display() != null) {
            return formatted(entry.display(), true);
        }
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return entry.owner();
        }
        String start = formatted(team.getPlayerPrefix(), true);
        String owner = entry.owner();
        String ownerPlain = ChatFormatting.stripFormatting(owner);
        // Hypixel's owners are invisible colour-code-only names; only keep a real, visible owner (other servers).
        if (ownerPlain != null && !ownerPlain.isBlank()) {
            start = start + owner;
        }
        String end = formatted(team.getPlayerSuffix(), true);
        return joinPrefixSuffix(start, end);
    }

    /** SkyHanni's {@code ScoreboardData.formatLines}: strip colour codes from the start of the suffix that the
     *  prefix already ended with, so e.g. {@code §8- §c§aApex Dra§agon} matches the dragon regex. */
    static String joinPrefixSuffix(String start, String end) {
        String lastColor = lastColorCode(start);
        if (lastColor != null) {
            List<String> suffixes = new ArrayList<>();
            for (int i = 0; i + 1 < lastColor.length(); i += 2) {
                suffixes.add(lastColor.substring(i, i + 2));
            }
            for (String suffix : new ArrayList<>(suffixes)) {
                if (end.startsWith(suffix)) {
                    end = end.substring(suffix.length());
                    suffixes.remove(suffix);
                }
            }
        }
        return start + end;
    }

    private static String lastColorCode(String s) {
        Matcher m = COLOR_CODES.matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static void readTab(Minecraft client) {
        if (client.getConnection() == null || client.gui == null) {
            tabPlain = Collections.emptyList();
            tabFormatted = Collections.emptyList();
            tabFooterPlain = "";
            return;
        }
        PlayerTabOverlay overlay = client.gui.getTabList();
        List<PlayerInfo> infos = null;
        Component footer = null;
        if (accessorUsable && overlay instanceof CustomScoreboardTabOverlayAccessor accessor) {
            try {
                infos = accessor.killer560smod$getPlayerInfos();
                footer = accessor.killer560smod$getFooter();
            } catch (LinkageError | RuntimeException e) {
                // A half-applied accessor throws AbstractMethodError/IllegalStateException at call time; fall back
                // to the unsorted connection list from now on.
                accessorUsable = false;
                infos = null;
                footer = null;
            }
        }
        if (infos == null) {
            Collection<PlayerInfo> listed = client.getConnection().getListedOnlinePlayers();
            infos = new ArrayList<>(listed);
            infos.sort(Comparator.comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        }
        List<String> plain = new ArrayList<>(infos.size());
        List<String> fmt = new ArrayList<>(infos.size());
        for (PlayerInfo info : infos) {
            Component name = overlay.getNameForDisplay(info);
            String f = removeResets(formatted(name, true));
            String p = ChatFormatting.stripFormatting(name.getString());
            fmt.add(f);
            plain.add(p == null ? "" : p);
        }
        tabPlain = Collections.unmodifiableList(plain);
        tabFormatted = Collections.unmodifiableList(fmt);
        String footerPlain = footer == null ? "" : ChatFormatting.stripFormatting(footer.getString());
        tabFooterPlain = footerPlain == null ? "" : footerPlain;
    }

    /** Called from {@code Gui#setOverlayMessage} (action bar). */
    public static void onActionBar(Component message) {
        if (message == null) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        actionBarPlain = plain == null ? "" : plain;
        actionBarAtMs = System.currentTimeMillis();
    }

    // ---- text conversion ----

    /**
     * Component to legacy {@code §} text, SkyHanni's {@code computeFormattedTextCompat(noExtraResets = true)}: each
     * styled run is prefixed with its colour + decoration codes, except a leading plain {@code §f}.
     */
    public static String formatted(Component component, boolean lessResets) {
        if (component == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(50);
        boolean[] wasFormatted = {false};
        component.visit((style, text) -> {
            if (text.isEmpty()) {
                return Optional.empty();
            }
            String chatStyle = chatStyle(style);
            if (!chatStyle.isEmpty() && (wasFormatted[0] || !chatStyle.equals("§f"))) {
                sb.append(chatStyle);
                wasFormatted[0] = true;
            }
            sb.append(text);
            if (!lessResets) {
                sb.append("§r");
                wasFormatted[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        String out = sb.toString();
        if (out.endsWith("§r")) {
            out = out.substring(0, out.length() - 2);
        }
        if (out.startsWith("§r")) {
            out = out.substring(2);
        }
        return out;
    }

    private static String chatStyle(Style style) {
        StringBuilder sb = new StringBuilder();
        TextColor color = style.getColor();
        if (color != null) {
            ChatFormatting f = COLOR_LUT.get(color.getValue());
            if (f != null) {
                sb.append(f);
            }
        }
        if (style.isBold()) {
            sb.append("§l");
        }
        if (style.isItalic()) {
            sb.append("§o");
        }
        if (style.isUnderlined()) {
            sb.append("§n");
        }
        if (style.isStrikethrough()) {
            sb.append("§m");
        }
        if (style.isObfuscated()) {
            sb.append("§k");
        }
        return sb.toString();
    }

    public static String removeResets(String s) {
        return s == null ? "" : RESETS.matcher(s).replaceAll("");
    }

    // ---- accessors ----

    public static List<String> sidebar() {
        return sidebarLines;
    }

    public static String objectiveTitle() {
        return objectiveTitle;
    }

    public static List<String> tabPlain() {
        return tabPlain;
    }

    public static List<String> tabFormatted() {
        return tabFormatted;
    }

    public static String tabFooter() {
        return tabFooterPlain;
    }

    /** Latest action bar text, or "" if none arrived in the last 3 seconds. */
    public static String actionBar() {
        return System.currentTimeMillis() - actionBarAtMs < 3000L ? actionBarPlain : "";
    }

    /** Island name from the tab list's "Area:"/"Dungeon:" line, or "" when unknown (e.g. p3sim). */
    public static String island() {
        return island;
    }

    public static boolean islandKnown() {
        return !island.isEmpty();
    }

    /** True when the island is known and equals one of {@code names}; always true when the island is unknown. */
    public static boolean inIslandOrUnknown(String... names) {
        if (island.isEmpty()) {
            return true;
        }
        for (String n : names) {
            if (island.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    /** True only when the island is known and equals one of {@code names}. */
    public static boolean inIsland(String... names) {
        for (String n : names) {
            if (island.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    // ---- list helpers (SkyHanni RegexUtils / CollectionUtils equivalents) ----

    public static boolean matches(Pattern pattern, String line) {
        return line != null && pattern.matcher(line).matches();
    }

    public static String firstMatches(Pattern pattern, List<String> lines) {
        for (String line : lines) {
            if (matches(pattern, line)) {
                return line;
            }
        }
        return null;
    }

    public static List<String> allMatches(List<Pattern> patterns, List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            for (Pattern pattern : patterns) {
                if (matches(pattern, line)) {
                    out.add(line);
                    break;
                }
            }
        }
        return out;
    }

    public static String group(Pattern pattern, List<String> lines, String group) {
        for (String line : lines) {
            Matcher m = pattern.matcher(removeResets(line).trim());
            if (m.matches()) {
                try {
                    String g = m.group(group);
                    if (g != null) {
                        return g;
                    }
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static String nextAfter(List<String> lines, String item, int skip) {
        int i = item == null ? -1 : lines.indexOf(item);
        if (i < 0 || i + skip >= lines.size()) {
            return null;
        }
        return lines.get(i + skip);
    }

    public static List<String> sublistAfter(List<String> lines, String item, int amount) {
        int i = lines.indexOf(item);
        if (i < 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(lines.subList(i + 1, Math.min(lines.size(), i + 1 + amount)));
    }

    /**
     * A tab widget (SkyHanni {@code TabWidget}): the header line (unindented, matching {@code header}) plus the
     * indented lines under it. Returns indices into {@link #tabPlain()}/{@link #tabFormatted()}; empty if absent.
     */
    public static List<Integer> tabWidget(Pattern header) {
        List<Integer> out = new ArrayList<>();
        List<String> lines = tabPlain;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(" ") || !header.matcher(line.trim()).matches()) {
                continue;
            }
            out.add(i);
            for (int j = i + 1; j < lines.size() && lines.get(j).startsWith(" ") && !lines.get(j).isBlank(); j++) {
                out.add(j);
            }
            break;
        }
        return out;
    }

    /** First named group of {@code header}'s tab widget header line, or null. */
    public static String tabHeaderGroup(Pattern header, String group) {
        List<Integer> widget = tabWidget(header);
        if (widget.isEmpty()) {
            return null;
        }
        Matcher m = header.matcher(tabPlain.get(widget.get(0)).trim());
        return m.matches() ? m.group(group) : null;
    }
}
