package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Secret Clicked and the chest/lever sound mutes (see {@link com.killer560.hub.dungeonalerts.SecretClicked}), in the
 *  Secrets folder. Three independent switches; the card is ON while any is. Backed by {@link DungeonAlertsConfig}. */
public class SecretClickedTab extends BaseTab {

    public SecretClickedTab() {
        super("Secret Clicked");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Secret Clicked", "Mute Chest Sounds", "Mute Lever Sounds");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Secret Clicked", cfg.isSecretClickedEnabled()), btn -> {
                    cfg.setSecretClickedEnabled(!cfg.isSecretClickedEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isSecretClickedEnabled()) {
            y = toggle(w, contentX, y, contentWidth, "Locked Chest Sound", cfg::isSecretClickedLockedSound, cfg::setSecretClickedLockedSound);
        }
        y += 4;
        y = toggle(w, contentX, y, contentWidth, "Mute Chest Sounds", cfg::isMuteChestSounds, cfg::setMuteChestSounds);
        toggle(w, contentX, y, contentWidth, "Mute Lever Sounds", cfg::isMuteLeverSounds, cfg::setMuteLeverSounds);
        return w;
    }

    private static int toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                              Consumer<Boolean> set) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    DungeonAlertsConfig.getInstance().save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
        return y + 22;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
