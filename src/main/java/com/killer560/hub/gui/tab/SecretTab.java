package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * One "Secret" tab holding Secret Sound, Secret Clicked and the Secrets HUD (killer560, 2026-11-01: "Merge secret
 * clicked and secret sound and secret hud under one secret tab"). Each part is still its own feature with its own
 * switch, built by its old tab class ({@link SecretSoundTab}, {@link SecretClickedTab}, {@link DungeonInfoTab}) under a
 * section header, and every widget keeps its old tooltip scope (first scope wins, see {@link SettingTooltips#scope}).
 * There is no switch over the whole: the card is ON while any part is ({@link #masterToggles}).
 */
public class SecretTab extends BaseTab {

    private static final int SECTION_GAP = 8;

    private final SecretSoundTab sound = new SecretSoundTab();
    private final SecretClickedTab clicked = new SecretClickedTab();
    private final DungeonInfoTab hud = new DungeonInfoTab();

    public SecretTab() {
        super("Secret");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Secret Sound", "Secret Clicked", "Mute Chest Sounds", "Mute Lever Sounds", "Secrets HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> out = new ArrayList<>();
        int y = contentY;
        for (BaseTab part : List.of(sound, clicked, hud)) {
            AbstractWidget header = SectionHeaders.row(contentX, y, contentWidth, part.name, false);
            SettingTooltips.scope(header, part.name);
            out.add(header);
            y += 16;
            List<AbstractWidget> built = part.buildWidgets(contentX, y, contentWidth, requestRebuild);
            int bottom = y;
            for (AbstractWidget w : built) {
                SettingTooltips.scope(w, part.name);
                bottom = Math.max(bottom, w.getY() + w.getHeight());
                out.add(w);
            }
            y = bottom + SECTION_GAP;
        }
        return out;
    }
}
