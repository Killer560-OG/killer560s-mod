package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mineshaft.Mineshaft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Glacite Mineshafts: independent display features (detection, waypoints, corpse locator, cold HUD, scrap button), each with
 *  its own switch, all off by default. See {@link Mineshaft}. */
public class MineshaftTab extends BaseTab {

    private static final String[] SWITCHES = {"Mineshaft Detection", "Mineshaft Waypoints", "Corpse Locator", "Cold And Time HUD",
            "Suspicious Scrap Button"};

    public MineshaftTab() {
        super("Glacite Mineshafts");
    }

    @Override
    public String cardDescription() {
        return "Corpses and cold HUD.";
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, SWITCHES);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int x, int y0, int w, Runnable rebuild) {
        List<AbstractWidget> out = new ArrayList<>();
        int[] y = {y0};
        boolean scan = isSearchScan();
        flag(out, x, y, w, "Mineshaft Detection", "detect", rebuild);
        if (Mineshaft.CFG.on("detect") || scan) {
            flag(out, x, y, w, "Share Type In Party Chat", "detectParty", null);
        }
        flag(out, x, y, w, "Mineshaft Waypoints", "waypoints", rebuild);
        flag(out, x, y, w, "Corpse Locator", "corpses", rebuild);
        if (Mineshaft.CFG.on("corpses") || scan) {
            flag(out, x, y, w, "Share Corpses In Party Chat", "corpseParty", null);
            flag(out, x, y, w, "Profit Per Corpse", "profit", null);
        }
        flag(out, x, y, w, "Cold And Time HUD", "hud", rebuild);
        if (Mineshaft.CFG.on("hud") || scan) {
            flag(out, x, y, w, "Cold Warning", "coldWarn", null);
            int min = Mineshaft.CFG.min("coldAt");
            double range = Mineshaft.CFG.max("coldAt") - min;
            out.add(normSlider(x, y[0], w, 18, () -> "Cold Warning At: §b" + Mineshaft.CFG.num("coldAt"),
                    (Mineshaft.CFG.num("coldAt") - min) / range,
                    v -> Mineshaft.CFG.set("coldAt", (int) Math.round(min + v * range)), Mineshaft.CFG::save));
            y[0] += 22;
        }
        flag(out, x, y, w, "Suspicious Scrap Button", "scrap", null);
        return out;
    }

    private static void flag(List<AbstractWidget> out, int x, int[] y, int w, String label, String key, Runnable rebuild) {
        out.add(onOffButton(label, () -> Mineshaft.CFG.on(key), v -> Mineshaft.CFG.set(key, v), Mineshaft.CFG::save, rebuild, x, y[0], w, 18));
        y[0] += 22;
    }
}
