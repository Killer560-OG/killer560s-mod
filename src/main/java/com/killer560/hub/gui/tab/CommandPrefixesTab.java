package com.killer560.hub.gui.tab;

import com.killer560.hub.commandprefix.CommandPrefixConfig;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Command Prefixes: which names open the mod's main command, and the alternate command character. See {@link com.killer560.hub.commandprefix.CommandPrefixFeature}. */
public class CommandPrefixesTab extends BaseTab {

    private static final int ROW = 20;
    private static final int GAP = 24;

    public CommandPrefixesTab() {
        super("Command Prefixes");
    }

    /** The four names and the alternate prefix each run alone, so the card is ON while any is on. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        List<SettingsButtonWidget> out = new ArrayList<>(switchRows(built, "/killer560", "/k", "/k5", "/k560"));
        out.addAll(switchRows(built, "Alternate Prefix"));
        return out;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CommandPrefixConfig cfg = CommandPrefixConfig.getInstance();
        int y = contentY;
        for (String name : CommandPrefixConfig.NAMES) {
            w.add(onOffButton("/" + name, () -> cfg.isNameOn(name), on -> cfg.setNameOn(name, on), cfg::save, null,
                    contentX, y, contentWidth, ROW));
            y += GAP;
        }
        w.add(onOffButton("Alternate Prefix", cfg::isAltOn, cfg::setAltOn, cfg::save, requestRebuild,
                contentX, y, contentWidth, ROW));
        y += GAP;
        if (!cfg.isAltOn() && !isSearchScan()) {
            return w;
        }
        w.add(SettingsButtonWidget.cycling(charText(cfg), (btn, dir) -> {
            String[] p = CommandPrefixConfig.PRESETS;
            int i = java.util.Arrays.asList(p).indexOf(cfg.altChar());
            cfg.setAltChar(p[i < 0 ? 0 : SettingsButtonWidget.step(i, dir, p.length)]);
            cfg.save();
            if (requestRebuild != null) {
                requestRebuild.run();
            }
        }).bounds(contentX, y, contentWidth, ROW).build());
        y += GAP;
        CommitEditBox box = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, ROW,
                Component.literal("Custom Character"));
        box.setMaxLength(2);
        box.setValue(cfg.altChar());
        box.onEnter(() -> {
            // The box starts with the current character: a typed one after it replaces it, so the last one counts.
            String v = box.getValue().trim();
            if (!v.isEmpty() && cfg.setAltChar(new String(Character.toChars(v.codePointBefore(v.length()))))) {
                cfg.save();
                if (requestRebuild != null) {
                    requestRebuild.run();
                }
            } else {
                ModOverlayMessage.show("§c[Command Prefixes] Enter one character that is not a space or \"/\".", 3000);
            }
        });
        w.add(box);
        CommitEditBox.withSet(w, box);
        return w;
    }

    private static Component charText(CommandPrefixConfig cfg) {
        return Component.literal("Character: " + cfg.altChar());
    }
}
