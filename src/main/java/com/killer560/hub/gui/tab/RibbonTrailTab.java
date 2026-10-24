package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.supporters.SupportersAutoShare;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.trail.RibbonTrailConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** The Ribbon Trail cosmetic (killer560, 2026-10-14: "make a trail setting under cosmetics now instead of its own toggle
 *  for the ribbon ... The trail though for ribbon can be shared"): a vertical band streaming behind you like a Roblox
 *  trail, drawn by {@link com.killer560.hub.trail.RibbonTrail}. Independent of the squares {@link TrailTab}; both can be
 *  on. Its Sharing section holds the same Share Ribbon Trail / Show Others' Trails switches as Player Cosmetics (where the other
 *  supporter switches are), so a supporter finds them from either tab. Purely visual, legit in both builds.
 *  <p>Since 2026-10-18 (killer560: "the ribbon trail is the only one that can be shared of the trails and it only shows
 *  up in player cosmetics to be adjusted") it is no longer a card of its own: {@link CosmeticsTab} builds it as its
 *  Ribbon Trail section, {@code embedded}, without a heading or the Sharing switches (Player Cosmetics has them on top). */
public class RibbonTrailTab extends BaseTab {

    private final boolean embedded;

    public RibbonTrailTab() {
        this(false);
    }

    /** @param embedded true when it is a section of Player Cosmetics rather than a tab of its own. */
    public RibbonTrailTab(boolean embedded) {
        super("Ribbon Trail");
        this.embedded = embedded;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        RibbonTrailConfig cfg = RibbonTrailConfig.getInstance();
        int[] y = {contentY};
        if (!embedded) {
            // A supporter's shared trail follows these settings (Share Trail); a no-op otherwise. Player Cosmetics does
            // this itself for the embedded copy.
            SupportersAutoShare.shareCurrent(null);
            header(w, contentX, y, contentWidth, "Ribbon Trail");
        }
        w.add(SettingsButtonWidget.builder(onOff("Ribbon Trail", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (cfg.isEnabledRaw()) {
            appearance(w, contentX, y, contentWidth, cfg, requestRebuild);
        }
        if (!embedded) {
            sharing(w, contentX, y, contentWidth, requestRebuild);
        }
        return w;
    }

    private static void appearance(List<AbstractWidget> w, int x, int[] y, int width, RibbonTrailConfig cfg,
                                   Runnable requestRebuild) {
        header(w, x, y, width, "Appearance");
        w.add(new ThemedSliderButton(x, y[0], width, 18, heightLabel(cfg),
                (cfg.getRibbonHeight() - RibbonTrailConfig.MIN_RIBBON_HEIGHT)
                        / (RibbonTrailConfig.MAX_RIBBON_HEIGHT - RibbonTrailConfig.MIN_RIBBON_HEIGHT)) {
            @Override
            protected void updateMessage() {
                setMessage(heightLabel(cfg));
            }

            @Override
            protected void applyValue() {
                float v = (float) (RibbonTrailConfig.MIN_RIBBON_HEIGHT
                        + this.value * (RibbonTrailConfig.MAX_RIBBON_HEIGHT - RibbonTrailConfig.MIN_RIBBON_HEIGHT));
                cfg.setRibbonHeight(Math.round(v * 20f) / 20f);
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(x, y[0], width, 18, lengthLabel(cfg),
                (cfg.getRibbonSeconds() - RibbonTrailConfig.MIN_RIBBON_SECONDS)
                        / (RibbonTrailConfig.MAX_RIBBON_SECONDS - RibbonTrailConfig.MIN_RIBBON_SECONDS)) {
            @Override
            protected void updateMessage() {
                setMessage(lengthLabel(cfg));
            }

            @Override
            protected void applyValue() {
                float v = (float) (RibbonTrailConfig.MIN_RIBBON_SECONDS
                        + this.value * (RibbonTrailConfig.MAX_RIBBON_SECONDS - RibbonTrailConfig.MIN_RIBBON_SECONDS));
                cfg.setRibbonSeconds(Math.round(v * 10f) / 10f);
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(x, y[0], width, 18, opacityLabel(cfg),
                (cfg.getOpacityPercent() - RibbonTrailConfig.MIN_OPACITY_PERCENT)
                        / (double) (RibbonTrailConfig.MAX_OPACITY_PERCENT - RibbonTrailConfig.MIN_OPACITY_PERCENT)) {
            @Override
            protected void updateMessage() {
                setMessage(opacityLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setOpacityPercent(RibbonTrailConfig.MIN_OPACITY_PERCENT + (int) Math.round(
                        this.value * (RibbonTrailConfig.MAX_OPACITY_PERCENT - RibbonTrailConfig.MIN_OPACITY_PERCENT)));
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        header(w, x, y, width, "Ribbon Colors");
        w.add(SettingsButtonWidget.cycling(colorModeLabel(cfg), (btn, dir) -> {
                    cfg.setColorMode(SettingsButtonWidget.step(cfg.getColorMode(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 18).build());
        y[0] += 22;

        if (cfg.getColorMode() == RibbonTrailConfig.ColorMode.GRADIENT) {
            w.add(SettingsButtonWidget.cycling(colorCountLabel(cfg), (btn, dir) -> {
                        cfg.setColorCount(cfg.getColorCount() == 2 ? 3 : 2);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
            w.add(colorButton(x, y[0], width, "Head Color", cfg.getHeadColor(), RibbonTrailConfig.DEFAULT_HEAD_COLOR,
                    cfg::setHeadColor));
            y[0] += 22;
            if (cfg.getColorCount() >= 3) {
                w.add(colorButton(x, y[0], width, "Middle Color", cfg.getMidColor(), RibbonTrailConfig.DEFAULT_MID_COLOR,
                        cfg::setMidColor));
                y[0] += 22;
            }
            w.add(colorButton(x, y[0], width, "Tail Color", cfg.getTailColor(), RibbonTrailConfig.DEFAULT_TAIL_COLOR,
                    cfg::setTailColor));
            y[0] += 22;
        }

        w.add(SettingsButtonWidget.cycling(teleportLabel(cfg), (btn, dir) -> {
                    cfg.setTeleportMode(SettingsButtonWidget.step(cfg.getTeleportMode(), dir));
                    cfg.save();
                    btn.setMessage(teleportLabel(cfg));
                }).bounds(x, y[0], width, 18).build());
        y[0] += 22;

        toggle(w, x, y[0], width, "Show In First Person", cfg::isShowInFirstPerson, v -> {
            cfg.setShowInFirstPerson(v);
            cfg.save();
        });
        y[0] += 22;
    }

    /** The same two switches as Player Cosmetics, on the same SupportersConfig. */
    private static void sharing(List<AbstractWidget> w, int x, int[] y, int width, Runnable requestRebuild) {
        SupportersConfig sc = SupportersConfig.getInstance();
        header(w, x, y, width, "Sharing");
        int half = (width - 4) / 2;
        boolean canShare = SupportersAutoShare.isLinked();
        SettingsButtonWidget share = SettingsButtonWidget.builder(onOff("Share Ribbon Trail", sc.isShareTrail() && canShare),
                btn -> {
                    sc.setShareTrail(!sc.isShareTrail());
                    sc.save();
                    requestRebuild.run();
                }).bounds(x, y[0], half, 18).build();
        share.active = canShare;
        w.add(share);
        w.add(SettingsButtonWidget.builder(onOff("Show Others' Trails", sc.isShowOthersTrails()), btn -> {
                    sc.setShowOthersTrails(!sc.isShowOthersTrails());
                    sc.save();
                    btn.setMessage(onOff("Show Others' Trails", sc.isShowOthersTrails()));
                }).bounds(x + half + 4, y[0], width - half - 4, 18).build());
        y[0] += 20;
        String blocker = SupportersAutoShare.trailShareBlocker();
        if (blocker != null && (sc.isShareTrail() || !canShare)) {
            w.add(new StringWidget(x, y[0], width, 12, Component.literal("§7" + blocker), Minecraft.getInstance().font));
            y[0] += 14;
        }
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), name, current, defaultColor,
                    argb -> {
                        setter.accept(argb);
                        RibbonTrailConfig.getInstance().save();
                    }));
        }).bounds(x, y, width, 18).build();
    }

    private static Component heightLabel(RibbonTrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Ribbon Height: %.2f", cfg.getRibbonHeight()));
    }

    private static Component lengthLabel(RibbonTrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Ribbon Length: %.1fs", cfg.getRibbonSeconds()));
    }

    private static Component opacityLabel(RibbonTrailConfig cfg) {
        return Component.literal("Opacity: " + cfg.getOpacityPercent() + "%");
    }

    private static Component teleportLabel(RibbonTrailConfig cfg) {
        return Component.literal("On Teleport: " + cfg.getTeleportMode().label());
    }

    private static Component colorModeLabel(RibbonTrailConfig cfg) {
        return Component.literal("Color Mode: " + cfg.getColorMode().label());
    }

    private static Component colorCountLabel(RibbonTrailConfig cfg) {
        return Component.literal("Colors: " + cfg.getColorCount());
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                               Supplier<Boolean> get, Consumer<Boolean> set) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
