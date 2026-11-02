package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mask Keybinds (legit, killer560 2026-11-01): in the /stats menu, a key equips Spirit or Bonzo's Mask, whichever is
 *  first in the order and off cooldown. See {@link com.killer560.hub.maskinvincibility.MaskKeybind}. */
public class MaskKeybindsTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing = false;

    public MaskKeybindsTab() {
        super("Mask Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (capturing) {
            cfg.setMaskKey(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
            cfg.save();
        }
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Mask Keybinds", cfg::isMaskKeyEnabledRaw, cfg::setMaskKeyEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isMaskKeyEnabledRaw()) {
            return w;
        }
        w.add(SettingsButtonWidget.builder(capturing ? Component.literal("Press any key...")
                        : Component.literal("Equip Mask Key: " + KeyUtil.bindDisplayName(cfg.getMaskKey())), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        w.add(SettingsButtonWidget.cycling(orderLabel(cfg), (btn, dir) -> {
                    cfg.setMaskKeyBonzoFirst(!cfg.isMaskKeyBonzoFirst());
                    cfg.save();
                    btn.setMessage(orderLabel(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        w.add(onOffButton("Dungeons Only", cfg::isMaskKeyDungeonsOnly, cfg::setMaskKeyDungeonsOnly, cfg::save, null,
                contentX, y, contentWidth, 18));
        y += 22;
        w.add(onOffButton("Boss Only", cfg::isMaskKeyBossOnly, cfg::setMaskKeyBossOnly, cfg::save, null,
                contentX, y, contentWidth, 18));
        return w;
    }

    private static Component orderLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Order: §b" + (cfg.isMaskKeyBonzoFirst() ? "Bonzo First" : "Spirit First"));
    }
}
