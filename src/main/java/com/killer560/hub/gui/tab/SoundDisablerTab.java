package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.sounddisabler.MutedSound;
import com.killer560.hub.sounddisabler.SoundDisablerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Misc's Sound Disabler (killer560, 2026-11-02: "add a sound disabler to hide stuff like bonzo staff sound effect"): the
 * master switch first, then one switch per {@link MutedSound} (all off by default), a Custom Sound IDs box and
 * Show Sound IDs, which names every sound in chat so an id can be found. Off by default. See
 * {@link com.killer560.hub.sounddisabler.SoundDisabler}.
 */
public class SoundDisablerTab extends BaseTab {

    public SoundDisablerTab() {
        super("Sound Disabler");
    }

    @Override
    public String cardDescription() {
        return "Mutes various sounds.";
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        int y = contentY;

        widgets.add(onOffButton("Sound Disabler", () -> cfg().isEnabled(), v -> cfg().setEnabled(v), () -> cfg().save(), null,
                contentX, y, contentWidth, 20));
        y += 24;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Sounds", false));
        y += 16;
        for (MutedSound s : MutedSound.values()) {
            widgets.add(onOffButton(s.label, () -> cfg().isOn(s), v -> cfg().setOn(s, v), () -> cfg().save(), null,
                    contentX, y, contentWidth, 20));
            y += 24;
        }
        y += 4;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Custom", false));
        y += 16;
        CommitEditBox custom = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Custom Sound IDs"));
        custom.setMaxLength(SoundDisablerConfig.CUSTOM_MAX);
        custom.setHint(Component.literal("Custom sound ids, comma-separated (entity.villager.*)"));
        custom.setValue(cfg.getCustom());
        custom.setResponder(text -> {
            cfg().setCustom(text);
            cfg().save();
        });
        widgets.add(custom);
        CommitEditBox.withSet(widgets, custom);
        y += 26;

        widgets.add(onOffButton("Show Sound IDs", () -> cfg().isShowIds(), v -> cfg().setShowIds(v), () -> cfg().save(), null,
                contentX, y, contentWidth, 20));
        return widgets;
    }

    /** Read on every click: a profile load replaces the instance. */
    private static SoundDisablerConfig cfg() {
        return SoundDisablerConfig.getInstance();
    }
}
