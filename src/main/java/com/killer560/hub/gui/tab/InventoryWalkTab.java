package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.invwalk.InventoryWalkConfig;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Inventory Walk - cheat-only (see {@link com.killer560.hub.invwalk.InventoryWalkFeature}). Misc's cheat block. */
public class InventoryWalkTab extends BaseTab implements KeyCaptureTab {

    private static final int ROW = 18;
    private boolean capturing;

    public InventoryWalkTab() {
        super("Inventory Walk");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void cancelKeyCapture() {
        capturing = false;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        setLookKey(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        setLookKey(KeyUtil.codeForMouseButton(button));
    }

    private void setLookKey(int code) {
        InventoryWalkConfig cfg = InventoryWalkConfig.getInstance();
        cfg.setLookKeyCode(code);
        cfg.save();
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        var font = Minecraft.getInstance().font;
        InventoryWalkConfig cfg = InventoryWalkConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Inventory Walk", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
        y += ROW + 6;
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Inventory Walk", true));
        y += 16;
        String[] warning = {
                "§cKeeps your movement keys working while a menu is open. The server sees",
                "§cyou move with a menu open, which a normal client cannot do. Ban risk.",
                "§7Tested against GrimAC only - that says nothing about Watchdog."
        };
        for (String line : warning) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;
        w.add(SettingsButtonWidget.cycleEnum(() -> screensText(cfg), cfg::getScreens, cfg::setScreens, cfg::save).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(onOffButton("Jump", cfg::isJump, cfg::setJump, cfg::save, null, contentX, y, contentWidth, ROW));
        y += ROW + 4;
        w.add(onOffButton("Sprint", cfg::isSprint, cfg::setSprint, cfg::save, null, contentX, y, contentWidth, ROW));
        y += ROW + 4;
        w.add(onOffButton("Stop To Click", cfg::isStopToClick, cfg::setStopToClick, cfg::save, null, contentX, y, contentWidth, ROW));
        y += ROW + 4;
        double span = InventoryWalkConfig.MAX_PAUSE_TICKS;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, pauseText(cfg),
                Math.max(0.0, Math.min(1.0, cfg.getPauseAfterClickTicks() / span))) {
            @Override
            protected void updateMessage() {
                setMessage(pauseText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setPauseAfterClickTicks((int) Math.round(this.value * span));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(BlinkTab.keyText("Look Key", cfg.getLookKeyCode(), capturing), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Look Key: §ePress any key..."));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(onOffButton("Carry In Chests", cfg::isCarryInChests, cfg::setCarryInChests, cfg::save, null, contentX, y, contentWidth, ROW));
        y += ROW + 4;
        w.add(onOffButton(STAND_STILL, cfg::isStillInMenus, cfg::setStillInMenus, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
        if (!cfg.isStillInMenus() && !isSearchScan()) {
            return w;
        }
        y += ROW + 4;
        double stillSpan = InventoryWalkConfig.STILL_WHOLE_MENU - 1;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, stillText(cfg), (cfg.getStillTicks() - 1) / stillSpan) {
            @Override
            protected void updateMessage() {
                setMessage(stillText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setStillTicks(1 + (int) Math.round(this.value * stillSpan));
                saveOnRelease(() -> cfg.save());
            }
        });
        return w;
    }

    static final String STAND_STILL = "Stand Still In Server Menus";

    private static Component screensText(InventoryWalkConfig cfg) {
        return Component.literal("Screens: " + cfg.getScreens().label);
    }

    private static Component stillText(InventoryWalkConfig cfg) {
        int t = cfg.getStillTicks();
        return Component.literal("Stand Still For: " + (t >= InventoryWalkConfig.STILL_WHOLE_MENU ? "Whole Menu" : t + " tick(s)"));
    }

    private static Component pauseText(InventoryWalkConfig cfg) {
        int t = cfg.getPauseAfterClickTicks();
        return Component.literal("Pause After Click: " + (t == 0 ? "Off" : t + " tick(s)"));
    }
}
