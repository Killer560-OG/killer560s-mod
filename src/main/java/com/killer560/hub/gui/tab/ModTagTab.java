package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.modusers.ModUsers;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mod User Tag: the symbol that marks other mod users, and whether you show yours. Everything is off by default. */
public class ModTagTab extends BaseTab {

    public ModTagTab() {
        super("Mod User Tag");
    }

    /** Two independent switches (seeing other users' tags, and sharing your own), so the card is ON while either is on
     *  (CLAUDE.md's masterToggles rule); with both off it turns on only the first, never the sharing one. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "See Other Mod Users", "Show Me As A Mod User");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ModUserConfig cfg = ModUserConfig.getInstance();

        widgets.add(onOffButton("See Other Mod Users", cfg::isSeeOthers, cfg::setSeeOthers, cfg::save,
                requestRebuild, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Me As A Mod User", cfg.isShare()), btn -> {
                    cfg.setShare(!cfg.isShare());
                    cfg.save();
                    ModUsers.announce();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // Tag Style (killer560, 2026-11-01: "Make it look like something that more resembles our mod"): how tags look to
        // you, with a preview of yours beside it. Left click steps forward, right click back.
        int styleW = contentWidth * 3 / 5;
        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.setTagStyle(SettingsButtonWidget.step(cfg.getTagStyle(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, styleW, 18).build());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        String me = mc.player != null ? mc.player.getGameProfile().name() : "Player";
        widgets.add(new net.minecraft.client.gui.components.StringWidget(contentX + styleW + 6, y + 5, contentWidth - styleW - 6,
                10, cfg.getTagStyle().tag(cfg.getShareAs(), Component.literal(me)), mc.font));
        y += 22;

        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            // A cheat-only row: at the bottom of the tab, under the red Cheat header.
            y += 6;
            widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat", true));
            y += 16;
            // Arrows like the other cycling settings: left click steps forward, right click back (killer560, 2026-10-19).
            widgets.add(SettingsButtonWidget.cycling(showAsText(cfg), (btn, dir) -> {
                        cfg.setShareAs("cheat".equals(cfg.getShareAs()) ? "legit" : "cheat");
                        cfg.save();
                        ModUsers.announce();
                        btn.setMessage(showAsText(cfg));
                        requestRebuild.run(); // the Tag Style preview shows the variant too
                    }).bounds(contentX, y, contentWidth, 18).build());
        }
        return widgets;
    }

    private static Component styleText(ModUserConfig cfg) {
        return Component.literal("Tag Style: " + cfg.getTagStyle().label);
    }

    private static Component showAsText(ModUserConfig cfg) {
        return Component.literal("Show As: " + ("cheat".equals(cfg.getShareAs()) ? "Cheat (red)" : "Legit (green)"));
    }
}
