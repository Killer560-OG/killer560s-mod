package com.killer560.hub.runsummary;

import com.killer560.hub.dungeonclass.DungeonClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a logged run says about itself, as § formatted lines: the run summary's own lines, then the party - one
 * line per member with class, rooms, secrets and deaths, then which rooms each of them cleared and what killed them.
 * The run log ({@code /log}) and the Dungeon Sim's run detail page both show this, so the two cannot describe a run
 * differently.
 */
public final class RunDetails {

    private RunDetails() {
    }

    public static List<String> lines(RunRecord run) {
        List<String> out = new ArrayList<>(RunSummaryFeature.summaryLines(run));
        if (!run.party().isEmpty()) {
            out.add("");
            out.add("§6§lParty");
            for (RunRecord.PartyMember member : run.party()) {
                out.add(partyLine(member));
            }
            boolean anyRooms = false;
            for (RunRecord.PartyMember member : run.party()) {
                anyRooms |= !member.soloRoomNames().isEmpty() || !member.stackedRoomNames().isEmpty();
            }
            if (anyRooms) {
                out.add("");
                out.add("§6§lRooms cleared");
                for (RunRecord.PartyMember member : run.party()) {
                    if (member.soloRoomNames().isEmpty() && member.stackedRoomNames().isEmpty()) {
                        continue;
                    }
                    String line = "§7" + RunSummaryFeature.currentIgn(member) + "§f: "
                            + (member.soloRoomNames().isEmpty() ? "§7-" : "§f" + String.join(", ", member.soloRoomNames()));
                    if (!member.stackedRoomNames().isEmpty()) {
                        line += " §8| §7with others: §f" + String.join(", ", member.stackedRoomNames());
                    }
                    out.add(line);
                }
            }
            boolean anyDeaths = false;
            for (RunRecord.PartyMember member : run.party()) {
                anyDeaths |= !member.deathCauses().isEmpty();
            }
            if (anyDeaths) {
                out.add("");
                out.add("§6§lDeaths");
                for (RunRecord.PartyMember member : run.party()) {
                    for (String cause : member.deathCauses()) {
                        out.add("§7" + RunSummaryFeature.currentIgn(member) + "§f: " + cause);
                    }
                }
            }
        }
        if (run.map() == null || run.map().isEmpty()) {
            out.add("");
            out.add("§7No map recorded (nothing was scanning the dungeon that run).");
        }
        return out;
    }

    /** "Name  Mage 42 | rooms 4-7 | secrets 12 | deaths 0". */
    public static String partyLine(RunRecord.PartyMember member) {
        DungeonClass cls = member.dungeonClass() == null ? null : DungeonClass.byName(member.dungeonClass());
        String classText = member.dungeonClass() == null ? "-" : member.dungeonClass()
                + (member.classLevel() >= 0 ? " " + member.classLevel() : "");
        return "§7" + RunSummaryFeature.currentIgn(member) + " §f" + colorFor(cls) + classText
                + " §8| §7rooms §f" + member.roomsLabel()
                + " §8| §7secrets §f" + orDash(member.secrets())
                + " §8| §7deaths §f" + orDash(member.deaths());
    }

    public static String colorFor(DungeonClass cls) {
        if (cls == null) {
            return "§f";
        }
        return switch (cls) {
            case MAGE -> "§9";
            case TANK -> "§a";
            case HEALER -> "§d";
            case ARCHER -> "§c";
            case BERSERKER -> "§6";
        };
    }

    private static String orDash(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }
}
