package com.killer560.hub.gui.tab;

import com.killer560.hub.leftclicksecrets.LeftClickSecretsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Left Click Opens Secrets (cheat build only, in the red Secrets folder): see
 *  {@link com.killer560.hub.leftclicksecrets.LeftClickSecrets}. */
public class LeftClickSecretsTab extends BaseTab {

    public LeftClickSecretsTab() {
        super("Left Click Opens Secrets");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        LeftClickSecretsConfig cfg = LeftClickSecretsConfig.getInstance();
        w.add(onOffButton("Left Click Opens Secrets", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 20));
        return w;
    }
}
