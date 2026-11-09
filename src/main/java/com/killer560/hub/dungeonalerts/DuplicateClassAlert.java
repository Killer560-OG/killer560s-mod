package com.killer560.hub.dungeonalerts;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.OutgoingChat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duplicate Class Alert (Devonian PartyDuplicateAlert; featB-1108): a title naming the players who share a dungeon class,
 * once per duplicated class per run, optionally also to party chat ("Party Message", its own switch, OFF). It reads the
 * class map {@link ClassColors} already builds from the tab list every 10 ticks (no second tab scan), so the tab is parsed
 * for this switch too while it is on.
 */
final class DuplicateClassAlert {

    static final String KEY = "dupclass";
    private static final Set<DungeonClass> alerted = EnumSet.noneOf(DungeonClass.class);

    private DuplicateClassAlert() {
    }

    static boolean wanted() {
        return DungeonAlertsConfig.getInstance().duplicateClassAlert;
    }

    static void check(Map<String, DungeonClass> classes) {
        Map<DungeonClass, List<String>> by = new EnumMap<>(DungeonClass.class);
        classes.forEach((name, cls) -> by.computeIfAbsent(cls, k -> new ArrayList<>()).add(name));
        for (Map.Entry<DungeonClass, List<String>> e : by.entrySet()) {
            if (e.getValue().size() > 1 && alerted.add(e.getKey())) {
                String names = String.join(", ", e.getValue().stream().sorted().toList());
                DungeonAlertsFeature.alert(KEY, "§cDuplicate " + e.getKey().displayName() + "!", names);
                if (DungeonAlertsConfig.getInstance().duplicateClassParty) {
                    OutgoingChat.command("pc Duplicate class - " + e.getKey().displayName() + ": " + names);
                }
            }
        }
    }

    static void onWorldChange() {
        alerted.clear();
    }
}
