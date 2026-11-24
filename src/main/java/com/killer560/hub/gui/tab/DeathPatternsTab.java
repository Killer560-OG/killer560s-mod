package com.killer560.hub.gui.tab;

import com.killer560.hub.deathpatterns.DeathPatterns;

import java.util.List;

/** Death Patterns card (both builds, information only): see {@link DeathPatterns}. */
public class DeathPatternsTab extends IslandFeatureTab {

    public DeathPatternsTab() {
        super("Death Patterns", DeathPatterns.CFG, false, "What keeps killing you.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Remind At A Repeated Death", "remind");
        r.header("Your Deaths", false);
        List<String> floors = DeathPatterns.floors();
        if (!DeathPatterns.ensureLoaded()) {
            r.note("Loading...");
        } else if (floors.isEmpty()) {
            r.note("Not enough data yet: it fills as you run dungeons.");
        } else {
            int i = Math.min(cfg.num("floor"), floors.size() - 1);
            r.cycle("Floor", "floor", floors.size(), n -> floors.get(Math.min(n, floors.size() - 1)), true);
            DeathPatterns.lines(floors.get(i)).forEach(r::note);
        }
    }
}
