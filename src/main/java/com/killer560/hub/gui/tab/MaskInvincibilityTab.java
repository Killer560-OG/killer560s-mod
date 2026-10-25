package com.killer560.hub.gui.tab;

import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Mask/pet invincibility timer settings - see
 *  {@link com.killer560.hub.maskinvincibility.MaskInvincibilityFeature}'s class doc for the real
 *  Odin-ported proc detection this is built on. The cheat-build auto-swap
 *  ({@link com.killer560.hub.maskinvincibility.MaskSwapper}) has its own red section, {@link AutoMaskTab},
 *  since 2026-10-04. */
public class MaskInvincibilityTab extends BaseTab {

    public MaskInvincibilityTab() {
        super("Mask Invincibility");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(onOffButton("Mask Invincibility", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Spirit", cfg::isShowSpirit, cfg::setShowSpirit, cfg::save, null, col1, y, 100, 18));

        widgets.add(onOffButton("Bonzo", cfg::isShowBonzo, cfg::setShowBonzo, cfg::save, null, col2, y, 100, 18));

        widgets.add(onOffButton("Phoenix", cfg::isShowPhoenix, cfg::setShowPhoenix, cfg::save, null, col3, y, 108, 18));
        y += 22;

        widgets.add(onOffButton("Item Icons", cfg::isShowItemIcons, cfg::setShowItemIcons, cfg::save,
                requestRebuild, col1, y, 100, 18));

        if (cfg.isShowItemIcons()) {
            widgets.add(onOffButton("Hide Mask Names", cfg::isHideMaskNames, cfg::setHideMaskNames, cfg::save,
                    null, col2, y, 208, 18));
        }
        y += 22;

        // killer560 9.1: "add an option to only show the invulnerabilites in dungeons, and one for boss only".
        // Even halves of the tab's real content width, same fix as Announce In Chat/To Party just below.
        int gateGap = 8;
        int gateW = (contentWidth - gateGap) / 2;
        widgets.add(onOffButton("Only In Dungeons", cfg::isOnlyInDungeons, cfg::setOnlyInDungeons, cfg::save,
                null, contentX, y, gateW, 18));

        widgets.add(onOffButton("Boss Only", cfg::isBossOnly, cfg::setBossOnly, cfg::save,
                null, contentX + gateW + gateGap, y, gateW, 18));
        y += 22;

        // Announce To Party is the only announce (killer560, 2026-10-18: "remove the announce in chat option"), full width.
        widgets.add(onOffButton("Announce To Party", cfg::isAnnounceToParty, cfg::setAnnounceToParty, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 24;

        return widgets;
    }
}
