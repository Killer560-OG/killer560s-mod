package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodrush.BloodRushConfig;
import com.killer560.hub.bloodrush.BloodRushSpots;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Blood Rush Helper ({@link com.killer560.hub.bloodrush.BloodRushHelperFeature}): the switch, the roles, what shows, the
 *  colors, then the cheat block (Through Walls, Whole Rush Path). */
public class BloodRushHelperTab extends BaseTab {

    public BloodRushHelperTab() {
        super("Blood Rush Helper");
    }

    /** The helper and its passive learner each run on their own flag (BloodRushHelperFeature.tick), so the card is ON
     *  while either is on (CLAUDE.md masterToggles rule). */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Blood Rush Helper", "Learn Spawn Spots");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colB = contentX + colW + gap;
        int y = contentY;

        w.add(onOffButton("Blood Rush Helper", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 26;
        // Learning runs while the helper is off too (bloodrush2-1104), so its row is built either way and it is one of the
        // card's master toggles (CLAUDE.md masterToggles rule): the card reads ON while it learns.
        w.add(onOffButton("Learn Spawn Spots", cfg::isLearnSpots, cfg::setLearnSpots, cfg::save, null,
                contentX, y, colW, 18));
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        w.add(onOffButton("Show Learned Spots", cfg::isShowLearned, cfg::setShowLearned, cfg::save, null,
                colB, y, colW, 18));
        y += 22;

        w.add(SettingsButtonWidget.cycling(killersText(cfg), (btn, dir) -> {
            cfg.setKillers(SettingsButtonWidget.step(cfg.getKillers() - 1, dir, BloodRushConfig.MAX_KILLERS) + 1);
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y, colW, 18).build());
        w.add(SettingsButtonWidget.cycling(roleText(cfg), (btn, dir) -> {
            cfg.setRole(SettingsButtonWidget.step(cfg.getRole(), dir, cfg.getKillers() + 1));
            cfg.save();
            btn.setMessage(roleText(cfg));
        }).bounds(colB, y, colW, 18).build());
        y += 22;

        w.add(onOffButton("Show Others' Spots", cfg::isShowOthers, cfg::setShowOthers, cfg::save, null,
                contentX, y, colW, 18));
        w.add(onOffButton("All Rooms", cfg::isAllRooms, cfg::setAllRooms, cfg::save, null, colB, y, colW, 18));
        y += 22;

        w.add(SettingsButtonWidget.cycleEnum(() -> doorKillsText(cfg), cfg::getDoorKills, cfg::setDoorKills, cfg::save).bounds(contentX, y, colW, 18).build());
        w.add(onOffButton("Drop Bottom After Fairy", cfg::isDropBottom, cfg::setDropBottom, cfg::save, null,
                colB, y, colW, 18));
        y += 22;


        w.add(color("My Spots Color", cfg.getMineColor(), BloodRushConfig.DEFAULT_MINE_COLOR, v -> {
            cfg.setMineColor(v);
            cfg.save();
        }, contentX, y, colW));
        w.add(color("Others' Spots Color", cfg.getOthersColor(), BloodRushConfig.DEFAULT_OTHERS_COLOR, v -> {
            cfg.setOthersColor(v);
            cfg.save();
        }, colB, y, colW));
        y += 22;

        w.add(color("Learned Spot Color", cfg.getLearnedColor(), BloodRushConfig.DEFAULT_LEARNED_COLOR, v -> {
            cfg.setLearnedColor(v);
            cfg.save();
        }, contentX, y, colW));
        int fMin = BloodRushConfig.MIN_FILL;
        int fMax = BloodRushConfig.MAX_FILL;
        w.add(normSlider(colB, y, colW, 18, () -> "Fill Opacity: " + cfg.getFillOpacity() + "%",
                (cfg.getFillOpacity() - fMin) / (double) (fMax - fMin),
                v -> cfg.setFillOpacity((int) Math.round(fMin + v * (fMax - fMin))), cfg::save));
        y += 22;

        w.add(onOffButton("Filled Boxes", cfg::isFilled, cfg::setFilled, cfg::save, null, contentX, y, colW, 18));
        int oMin = BloodRushConfig.MIN_OUTLINE;
        int oMax = BloodRushConfig.MAX_OUTLINE;
        w.add(normSlider(colB, y, colW, 18, () -> "Outline Width: " + cfg.getOutlineWidth(),
                (cfg.getOutlineWidth() - oMin) / (double) (oMax - oMin),
                v -> cfg.setOutlineWidth((int) Math.round(oMin + v * (oMax - oMin))), cfg::save));
        y += 22;

        w.add(SettingsButtonWidget.builder(Component.literal("Forget Learned Spots (" + BloodRushSpots.counts().size()
                + " rooms)"), btn -> {
            BloodRushSpots.forgetAll();
            btn.setMessage(Component.literal("Forget Learned Spots (0 rooms)"));
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - ESP", true));
        y += 16;
        w.add(onOffButton("Through Walls", cfg::isThroughWallsRaw, cfg::setThroughWalls, cfg::save, null,
                contentX, y, colW, 18));
        w.add(onOffButton("Whole Rush Path", cfg::isWholePathRaw, cfg::setWholePath, cfg::save, null,
                colB, y, colW, 18));
        return w;
    }

    private static AbstractWidget color(String label, int value, int def, java.util.function.IntConsumer set, int x, int y,
                                        int width) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, value), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, value, def, set::accept));
        }).bounds(x, y, width, 18).build();
    }

    private static Component doorKillsText(BloodRushConfig cfg) {
        return Component.literal("Door Kills: §6" + cfg.getDoorKills().label);
    }

    private static Component killersText(BloodRushConfig cfg) {
        int k = cfg.getKillers();
        return Component.literal("Killers: §6" + (k == 1 ? "Just Me" : k));
    }

    private static Component roleText(BloodRushConfig cfg) {
        return Component.literal("My Role: §6" + (cfg.getRole() == 0 ? "Door" : cfg.getRole() + " of " + cfg.getKillers()));
    }
}
