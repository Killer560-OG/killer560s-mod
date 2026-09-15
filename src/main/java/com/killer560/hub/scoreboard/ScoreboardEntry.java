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
 * Default order and default-on set follow SkyHanni's {@code defaultOptions} (SB Level and Chunked Stats are off by
 * default like SkyHanni). Mayor, Cookie Buff, Maxwell Power/Tuning and Quiver read {@link ScoreboardExtraData}.
 * {@link #showIsland()} is SkyHanni's island filter (skipped while the island is unknown, e.g. on
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
            String line = firstMatches(ScoreboardPattern.LOBBY_CODE, sidebar());
            Matcher m = line == null ? null : ScoreboardPattern.LOBBY_CODE.matcher(line);
            if (m == null || !m.matches()) {
                return single(trim(line));
            }
            String code = m.group("code").trim();
            return single(cfg.isDateInLobbyCode() ? "§7" + cfg.getDateFormat().today() + " §8" + code : "§8" + code);
        }

        @Override
        List<String> sample() {
            CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
            return List.of((cfg.isDateInLobbyCode() ? "§7" + cfg.getDateFormat().today() + " " : "") + "§8mega77CK");
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
            String line = trim(firstMatches(ScoreboardPattern.TIME, sidebar()));
            return single(cfg.isTime24h() ? to24h(line) : line);
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
            if (amount == null) {
                return List.of();
            }
            int max = cfg.isShowMaxIslandPlayers() ? maxIslandPlayers() : -1;
            return single(formatNumberDisplay("Players", amount + (max > 0 ? "§7/§a" + max : ""), "§a"));
        }

        @Override
        List<String> sample() {
            return List.of(formatNumberDisplay("Players", CustomScoreboardConfig.getInstance().isShowMaxIslandPlayers()
                    ? "69§7/§a80" : "69", "§a"));
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
            String label = line != null && line.replaceAll("§.", "").startsWith("Piggy") ? "Piggy" : "Purse";
            return number(cfg, label, "purse", ChunkedStat.PURSE.raw(), "§6");
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
            return number(cfg, "Motes", "motes", ChunkedStat.MOTES.raw(), "§d");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("The Rift");
        }
    },
    BANK("Bank", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String[] bank = ChunkedStat.bank();
            if (bank == null) {
                return List.of();
            }
            String amount = bank[0];
            String personal = bank[1];
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
            return number(cfg, "Bits", "bits", ChunkedStat.BITS.raw(), "§b");
        }

        @Override
        boolean showIsland() {
            return !inIsland("Catacombs", "Kuudra");
        }
    },
    COPPER("Copper", true, ScoreboardPattern.COPPER) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Copper", "copper", ChunkedStat.COPPER.raw(), "§c");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Garden");
        }
    },
    SOWDUST("Sowdust", true, ScoreboardPattern.SOWDUST, ScoreboardPattern.SOWDUST_GAINED) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Sowdust", "sowdust", ChunkedStat.SOWDUST.raw(), "§2");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Garden");
        }
    },
    GEMS("Gems", true, ScoreboardPattern.GEMS) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Gems", "gems", ChunkedStat.GEMS.raw(), "§a");
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
            return number(cfg, "North Stars", "northstars", ChunkedStat.NORTH_STARS.raw(), "§d");
        }

        @Override
        boolean showIsland() {
            return inIslandOrUnknown("Jerry's Workshop");
        }
    },
    SOULFLOW("Soulflow", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            return number(cfg, "Soulflow", "soulflow", tabHeaderGroup(ScoreboardPattern.TAB_SOULFLOW, "amount"), "§3");
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
    COOKIE("Cookie Buff", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            long expires = ScoreboardExtraData.cookieExpiresAtMs();
            long now = System.currentTimeMillis();
            String value;
            if (expires < 0) {
                value = "§cOpen SB Menu!";
            } else if (expires <= now) {
                if (cfg.isHideEmptyLines()) {
                    return List.of();
                }
                value = "§cNot Active";
            } else {
                value = ScoreboardLine.formatDuration(expires - now, 2);
            }
            return List.of(ScoreboardLine.of("§dCookie Buff§f: " + value)
                    .withActions(List.of("§7Click to open the Booster Cookie menu"), "boostercookiemenu"));
        }

        @Override
        List<String> sample() {
            return List.of("§dCookie Buff§f: 3d 17h");
        }
    },
    QUIVER("Quiver", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String arrow = ScoreboardExtraData.quiverArrow();
            int amount = ScoreboardExtraData.quiverAmount();
            String text;
            if (arrow == null || amount < 0) {
                text = "§cChange your Arrow once";
            } else if (arrow.equalsIgnoreCase("None")) {
                text = "No Arrows selected";
            } else {
                double percent = amount * 100.0 / MAX_ARROW_AMOUNT;
                String color = !cfg.isColorArrowAmount() ? "" : percent <= 10 ? "§c" : percent <= 25 ? "§6"
                        : percent <= 50 ? "§e" : percent <= 75 ? "§2" : "§a";
                String shown = ScoreboardExtraData.wearingSkeletonMasterChestplate() ? "∞"
                        : cfg.getArrowDisplay() == CustomScoreboardConfig.ArrowDisplay.PERCENTAGE
                        ? String.format(java.util.Locale.US, "%.1f%%", percent)
                        : String.format(java.util.Locale.US, "%,d", amount);
                text = formatNumberDisplay(arrow, color + shown, "§f");
            }
            return List.of(ScoreboardLine.of(text).withActions(List.of("§7Click to open the quiver"), "quiver"));
        }

        @Override
        boolean showWhen() {
            return ScoreboardExtraData.hasBow();
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }

        @Override
        List<String> sample() {
            return List.of(formatNumberDisplay("Flint Arrow", "1,234", "§f"));
        }
    },
    POWER("Maxwell Power", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            String power = ScoreboardExtraData.maxwellPower();
            List<String> hover = List.of("§7Updated from the Your Bags menu", "§7and Maxwell's Thaumaturgy menu");
            if (power == null) {
                return List.of(ScoreboardLine.of("§cOpen \"Your Bags\"!").withActions(hover, null));
            }
            int mp = ScoreboardExtraData.magicalPower();
            String value = power + (cfg.isShowMagicalPower() && mp >= 0
                    ? " §7(§6" + String.format(java.util.Locale.US, "%,d", mp) + "§7)" : "");
            return List.of(ScoreboardLine.of(formatNumberDisplay("Power", value, "§a")).withActions(hover, null));
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }

        @Override
        List<String> sample() {
            return List.of(formatNumberDisplay("Power", "Sighted §7(§61,263§7)", "§a"));
        }
    },
    TUNING("Maxwell Tuning", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<ScoreboardExtraData.Tuning> tunings = ScoreboardExtraData.tunings();
            if (tunings == null) {
                return single("§cTalk to \"Maxwell\"!");
            }
            if (tunings.isEmpty()) {
                return cfg.isHideEmptyLines() ? List.of() : single("§cNo Maxwell Tunings :(");
            }
            String title = tunings.size() == 1 ? "Tuning" : "Tunings";
            CustomScoreboardConfig.NumberDisplayFormat format = cfg.getNumberDisplayFormat();
            if (cfg.isCompactTuning()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tunings.size() && i < 3; i++) {
                    ScoreboardExtraData.Tuning t = tunings.get(i);
                    if (i > 0) {
                        sb.append("§7, ");
                    }
                    sb.append(switch (format) {
                        case TEXT_COLOR_NUMBER -> t.icon() + t.color() + t.value();
                        case COLOR_TEXT_NUMBER -> t.color() + t.icon() + t.value();
                        case COLOR_NUMBER_TEXT -> t.color() + t.value() + t.icon();
                        case COLOR_NUMBER_RESET_TEXT -> t.color() + t.value() + "§f" + t.icon();
                    });
                }
                return single(formatNumberDisplay(title, sb.toString(), "§f"));
            }
            List<String> out = new ArrayList<>();
            out.add(title + ":");
            for (int i = 0; i < tunings.size() && i < cfg.getTuningAmount(); i++) {
                ScoreboardExtraData.Tuning t = tunings.get(i);
                out.add(" §7- §f" + switch (format) {
                    case TEXT_COLOR_NUMBER -> t.name() + ": " + t.icon() + t.color() + t.value();
                    case COLOR_TEXT_NUMBER -> t.color() + t.name() + ": " + t.icon() + t.value();
                    case COLOR_NUMBER_TEXT -> t.color() + t.value() + t.icon() + " " + t.name();
                    case COLOR_NUMBER_RESET_TEXT -> t.color() + t.value() + "§f" + t.icon() + " " + t.name();
                });
            }
            return ScoreboardLine.of(out);
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }

        @Override
        List<String> sample() {
            return List.of("Tunings: §c❁34§7, §e⚔20§7, §9☣7");
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
    MAYOR("Mayor", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            ScoreboardExtraData.Candidate mayor = ScoreboardExtraData.mayor();
            if (mayor == null) {
                return List.of();
            }
            List<ScoreboardLine> out = new ArrayList<>();
            String time = cfg.isShowMayorTime()
                    ? "§7 (§e" + ScoreboardLine.formatDuration(ScoreboardExtraData.timeUntilNextMayorMs(), 2) + "§7)" : "";
            List<String> mayorHover = perkHover(mayor);
            mayorHover.add("");
            mayorHover.add("§eClick to open the calendar");
            out.add(ScoreboardLine.of(candidateName(mayor.name()) + time).withActions(mayorHover, "calendar"));
            if (cfg.isShowMayorPerks()) {
                for (ScoreboardExtraData.Perk perk : mayor.perks()) {
                    out.add(ScoreboardLine.of(" §7- §e" + perk.name()).withActions(wrap(perk.description(), "§7"), "calendar"));
                }
            }
            ScoreboardExtraData.Candidate minister = ScoreboardExtraData.minister();
            if (cfg.isShowMinister() && minister != null) {
                out.add(ScoreboardLine.of(candidateName(minister.name())).withActions(perkHover(minister), "calendar"));
                if (cfg.isShowMayorPerks()) {
                    for (ScoreboardExtraData.Perk perk : minister.perks()) {
                        out.add(ScoreboardLine.of(" §7- §e" + perk.name()).withActions(wrap(perk.description(), "§7"), "calendar"));
                    }
                }
            }
            return out;
        }

        @Override
        boolean showIsland() {
            return !inIsland("The Rift");
        }

        @Override
        List<String> sample() {
            return List.of("§2Diana §7(§e4d 12h§7)", " §7- §eLucky!", " §7- §eMythological Ritual", " §7- §ePet XP Buff");
        }
    },
    PARTY("Party", true) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> members;
            String leader;
            try {
                members = PartyTracker.teammates();
                leader = ScoreboardPartyLeader.get();
            } catch (RuntimeException e) {
                members = Collections.emptyList();
                leader = null;
            }
            if (members.isEmpty()) {
                leader = null;
            }
            if (members.isEmpty() && cfg.isHideEmptyLines()) {
                return List.of();
            }
            List<ScoreboardLine> out = new ArrayList<>();
            out.add(ScoreboardLine.of(members.isEmpty() ? "§9§lParty" : "§9§lParty (" + members.size() + ")")
                    .withActions(List.of("§7Click to run /party list"), "party list"));
            int shown = 0;
            if (cfg.isShowPartyLeader() && leader != null) {
                out.add(ScoreboardLine.of(" §7- §f" + leader + " §e♚"));
            }
            for (String member : members) {
                if (shown >= cfg.getMaxPartyMembers()) {
                    break;
                }
                if (cfg.isShowPartyLeader() && member.equalsIgnoreCase(leader)) {
                    continue;
                }
                out.add(ScoreboardLine.of(" §7- §f" + member));
                shown++;
            }
            return out;
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
    CHUNKED_STATS("Chunked Stats", false) {
        @Override
        List<ScoreboardLine> lines(CustomScoreboardConfig cfg) {
            List<String> parts = new ArrayList<>();
            for (CustomScoreboardConfig.Row<ChunkedStat> row : cfg.chunkedStats()) {
                if (!row.enabled || !row.id.entry.visible(cfg)) {
                    continue;
                }
                String display = row.id.display(cfg);
                if (display != null) {
                    parts.add(display);
                }
            }
            List<String> out = new ArrayList<>();
            int per = Math.max(1, cfg.getStatsPerLine());
            for (int i = 0; i < parts.size(); i += per) {
                out.add(String.join(" §7| ", parts.subList(i, Math.min(parts.size(), i + per))));
            }
            return ScoreboardLine.of(out);
        }

        @Override
        List<String> sample() {
            return List.of("§652,763,737 §7| §d64,647 §7| §6249M", "§b59,264 §7| §c23,495 §7| §23,210,307");
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

    static final int MAX_ARROW_AMOUNT = 2880;

    private static final Pattern TIME_12H = Pattern.compile("(?<prefix>.*?)(?<hour>\\d{1,2}):(?<minute>\\d{2})(?<ampm>am|pm)(?<rest>.*)");
    private static final Pattern VISITING_AMOUNT = Pattern.compile("(?:§.)*(\\d+)(?:§.)*/(\\d+)");
    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fA-F]");

    /** SkyHanni's {@code SkyBlockTime.formatted(timeFormat24h = true)} applied to the sidebar's "10:40pm" line. */
    static String to24h(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TIME_12H.matcher(line);
        if (!m.matches()) {
            return line;
        }
        int hour = Integer.parseInt(m.group("hour")) % 12;
        if (m.group("ampm").equals("pm")) {
            hour += 12;
        }
        return m.group("prefix") + String.format(java.util.Locale.US, "%02d", hour) + ":" + m.group("minute") + m.group("rest");
    }

    /** SkyHanni's {@code HypixelData.getMaxPlayersForCurrentServer} with the SkyHanni-REPO {@code IslandType.json} caps. */
    static int maxIslandPlayers() {
        String visiting = firstMatches(ScoreboardPattern.VISITING, sidebar());
        if (visiting != null) {
            Matcher m = VISITING_AMOUNT.matcher(visiting);
            if (m.find()) {
                return Integer.parseInt(m.group(2));
            }
        }
        String lobby = group(ScoreboardPattern.LOBBY_CODE, sidebar(), "code");
        if (lobby != null && lobby.trim().startsWith("mega")) {
            return 60;
        }
        return switch (island()) {
            case "The End" -> 28;
            case "Kuudra", "Mineshaft", "Critter Safari", "Safari" -> 4;
            case "Dwarven Mines", "Hub" -> 26;
            case "Catacombs" -> 5;
            case "Dark Auction", "Moonglade Marsh", "Galatea" -> 12;
            case "Jerry's Workshop" -> 27;
            case "Backwater Bayou", "Lotus Atoll" -> 16;
            case "" -> -1;
            default -> 24;
        };
    }

    /** SkyHanni {@code ElectionApi.mayorNameWithColorCode}. */
    static String candidateName(String name) {
        return candidateColor(name) + name;
    }

    private static String candidateColor(String name) {
        return switch (name) {
            case "Aatrox" -> "§3";
            case "Cole" -> "§e";
            case "Diana" -> "§2";
            case "Diaz" -> "§6";
            case "Finnegan", "Paul" -> "§c";
            case "Foxy", "Scorpius", "Jerry", "Derpy" -> "§d";
            case "Marina" -> "§b";
            default -> "§e";
        };
    }

    private static List<String> perkHover(ScoreboardExtraData.Candidate candidate) {
        List<String> out = new ArrayList<>();
        for (ScoreboardExtraData.Perk perk : candidate.perks()) {
            if (!out.isEmpty()) {
                out.add("");
            }
            out.add(candidateColor(candidate.name()) + perk.name() + ":");
            for (String line : wrap(perk.description(), "§7")) {
                out.add("  " + line);
            }
        }
        return out;
    }

    /** Word-wraps legacy text to ~40 visible characters, carrying the last colour code onto each new line. */
    static List<String> wrap(String text, String baseColor) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder(baseColor);
        int visible = 0;
        String lastColor = baseColor;
        for (String word : text.split(" ")) {
            int wordVisible = word.replaceAll("§.", "").length();
            if (visible > 0 && visible + 1 + wordVisible > 40) {
                out.add(line.toString());
                line = new StringBuilder(lastColor);
                visible = 0;
            }
            if (visible > 0) {
                line.append(' ');
                visible++;
            }
            line.append(word);
            visible += wordVisible;
            Matcher m = COLOR_CODE.matcher(word);
            while (m.find()) {
                lastColor = m.group();
            }
        }
        if (visible > 0) {
            out.add(line.toString());
        }
        return out;
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
    static List<ScoreboardLine> number(CustomScoreboardConfig cfg, String label, String trackKey, String raw, String color) {
        if (raw == null) {
            return cfg.isHideEmptyLines() ? List.of() : single(formatNumberDisplay(label, "0", color));
        }
        String value = formatStringNum(raw.trim());
        if (cfg.isHideEmptyLines() && isZero(value)) {
            return List.of();
        }
        ScoreboardLine line = ScoreboardLine.of(formatNumberDisplay(label, value, color));
        return List.of(NumberChangeTracker.track(cfg, line, trackKey, raw.trim(), color));
    }
}
