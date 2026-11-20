package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itemalerts.ItemAlertsConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Item Alerts ({@link com.killer560.hub.itemalerts.ItemAlertsFeature}): Bottle Full Alert and Runebook Rarity Alert, each its own switch, sound, Persistent / Timed mode and seconds. */
public class ItemAlertsTab extends BaseTab {

    public ItemAlertsTab() {
        super("Item Alerts");
    }

    /** The two alerts run on their own switches, so the card is ON while either is. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Bottle Full Alert", "Runebook Rarity Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ItemAlertsConfig c = ItemAlertsConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Bottle Full Alert", c::isBottleEnabledRaw, c::setBottleEnabled, c::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (c.isBottleEnabledRaw() || isSearchScan()) {
            y = rows(w, "Bottle", contentX, y, contentWidth, c::isBottleSound, c::setBottleSound, c::isBottlePersistent,
                    c::setBottlePersistent, c::getBottleSeconds, c::setBottleSeconds, c, requestRebuild);
        }
        w.add(onOffButton("Runebook Rarity Alert", c::isRunebookEnabledRaw, c::setRunebookEnabled, c::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (c.isRunebookEnabledRaw() || isSearchScan()) {
            rows(w, "Runebook", contentX, y, contentWidth, c::isRunebookSound, c::setRunebookSound, c::isRunebookPersistent,
                    c::setRunebookPersistent, c::getRunebookSeconds, c::setRunebookSeconds, c, requestRebuild);
        }
        return w;
    }

    /** Sound, Display (Persistent / Timed) and, in Timed, the Seconds slider; returns the next y. */
    private static int rows(List<AbstractWidget> w, String name, int x, int y, int width, BooleanSupplier sound, Consumer<Boolean> setSound,
                            BooleanSupplier persistent, Consumer<Boolean> setPersistent, IntSupplier seconds,
                            IntConsumer setSeconds, ItemAlertsConfig c, Runnable rebuild) {
        w.add(onOffButton(name + " Sound", sound, setSound, c::save, null, x, y, width, 18));
        y += 22;
        w.add(SettingsButtonWidget.cycling(modeText(name, persistent.getAsBoolean()), (btn, dir) -> {
            setPersistent.accept(!persistent.getAsBoolean());
            c.save();
            rebuild.run();
        }).bounds(x, y, width, 18).build());
        y += 22;
        if (!persistent.getAsBoolean() || isSearchScan()) {
            w.add(valueSlider(x, y, width, name + " Show Time", " s", ItemAlertsConfig.MIN_SECONDS, ItemAlertsConfig.MAX_SECONDS, 1, 0,
                    seconds::getAsInt, v -> setSeconds.accept((int) Math.round(v)), c::save));
            y += 22;
        }
        return y + 4;
    }

    private static Component modeText(String name, boolean persistent) {
        return Component.literal(name + " Display: §6" + (persistent ? "Persistent" : "Timed"));
    }
}
