package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.SplitTimersConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Split Timers settings - see {@link com.killer560.hub.splittimers.SplitTimersFeature}'s class doc
 *  for the real Odin-ported boss-dialogue split points this is built on. */
public class SplitTimersTab extends BaseTab {

    public SplitTimersTab() {
        super("Split Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();

        widgets.add(onOffButton("Split Timers", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Announce In Chat", cfg::isAnnounceInChat, cfg::setAnnounceInChat, cfg::save,
                null, contentX, y, 220, 18));
        y += 24;

        // The layout itself is fixed (killer560, 2026-10-08): Blood Open, Watcher, Portal Entry, Boss Entry, a line, the
        // boss phases and Boss, a line, Total and Lag. Only the "(lagless)" parentheses and the extra rows/blocks toggle.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Split Layout", false));
        y += 14;
        widgets.add(onOffButton("Lagless Times", cfg::isLaglessTimes, cfg::setLaglessTimes, cfg::save,
                null, contentX, y, 160, 18));
        widgets.add(onOffButton("Watcher Move", cfg::isWatcherMoveSplit, cfg::setWatcherMoveSplit, cfg::save,
                null, contentX + 168, y, 160, 18));
        y += 26;

        // Core entry times (2026-09-16, killer560: "time to enter core after terms finish").
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Core Entry (F7/M7)", false));
        y += 14;
        widgets.add(onOffButton("Core Entry Times", cfg::isCoreEntryTimes, cfg::setCoreEntryTimes, cfg::save,
                requestRebuild, contentX, y, 220, 18));
        y += 22;
        if (cfg.isCoreEntryTimes()) {
            widgets.add(onOffButton("Slowest To Chat", cfg::isCoreEntrySlowestChat, cfg::setCoreEntrySlowestChat,
                    cfg::save, null, contentX, y, 160, 18));
            widgets.add(onOffButton("Slowest To Party", cfg::isCoreEntrySlowestParty, cfg::setCoreEntrySlowestParty,
                    cfg::save, null, contentX + 168, y, 160, 18));
            y += 22;
            // killer560, 2026-09-20: "at the very bottom of the split timers show the slowest person into
            // core and their time" - separate from the chat/party lines above, which stay chat-only.
            widgets.add(onOffButton("Slowest In HUD", cfg::isCoreEntrySlowestHud, cfg::setCoreEntrySlowestHud,
                    cfg::save, null, contentX, y, 220, 18));
            y += 22;
        }

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "M7 Phase 5 Lines", false));
        y += 14;
        widgets.add(onOffButton("P5 Dragon Lines", cfg::isP5DragonLines, cfg::setP5DragonLines, cfg::save,
                null, contentX, y, 160, 18));
        widgets.add(onOffButton("P5 Relic Lines", cfg::isP5RelicLines, cfg::setP5RelicLines, cfg::save,
                null, contentX + 168, y, 160, 18));
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(p5PosLabel(cfg), (btn, dir) -> {
                    cfg.setP5LinesRight(!cfg.isP5LinesRight());
                    cfg.save();
                    btn.setMessage(p5PosLabel(cfg));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        return widgets;
    }

    private static Component p5PosLabel(SplitTimersConfig cfg) {
        return Component.literal("P5 Lines Position: §6" + (cfg.isP5LinesRight() ? "Right" : "Below"));
    }
}
