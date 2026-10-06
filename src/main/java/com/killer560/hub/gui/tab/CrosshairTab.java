package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.crosshair.CrosshairRenderer;
import com.killer560.hub.crosshair.CustomCrosshairConfig;
import com.killer560.hub.crosshair.CustomCrosshairConfig.SizeMode;
import com.killer560.hub.crosshair.CustomCrosshairConfig.Style;
import com.killer560.hub.crosshair.CustomCrosshairFeature;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.hud.AutoScale;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Custom Crosshair editor - its own top-level tab (killer560, 2026-10-06). Settings on the left, a live preview on the
 * right that stays in view while the settings scroll (narrow menus put it on top instead). The preview draws through
 * {@link CrosshairRenderer} at the real in-game pixel size, over a background you can click through, and can show the
 * dynamic states (spread, recoil, target colours). Presets: built-ins, named presets saved in
 * {@code config/killer560/interface/crosshair/killer560smod-crosshair-presets/}, and a share code via the clipboard.
 */
public class CrosshairTab extends BaseTab {

    private static final int ROW = 18;
    private static final int STEP = 20;
    private static final int COL_GAP = 4;

    /** Last action's result ("Saved preset X", "Not a crosshair code"), shown under the presets. */
    private static String status = "";
    /** What is typed in the preset name box, kept across rebuilds. */
    private static String presetName = "";
    /** The saved preset waiting for a second Delete click. */
    private static String pendingDelete = null;

    public CrosshairTab() {
        super("Crosshair");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CustomCrosshairConfig cfg = CustomCrosshairConfig.getInstance();

        boolean side = contentWidth >= 360;
        int pw = side ? Math.max(130, Math.min(190, contentWidth * 2 / 5)) : contentWidth;
        int ph = side ? Math.min(pw, 160) : 96;
        Preview preview = new Preview(side ? contentX + contentWidth - pw : contentX, contentY, pw, ph, side);
        w.add(preview);
        int sx = contentX;
        int sw = side ? contentWidth - pw - 8 : contentWidth;
        int[] y = {side ? contentY : contentY + preview.getHeight() + 6};
        int half = (sw - COL_GAP) / 2;

        header(w, sx, y, sw, "Crosshair");
        w.add(SettingsButtonWidget.builder(onOff("Custom Crosshair", cfg.isEnabledRaw()), btn -> {
            cfg.setEnabled(!cfg.isEnabledRaw());
            cfg.save();
            requestRebuild.run();
        }).bounds(sx, y[0], sw, ROW).build());
        y[0] += STEP;
        w.add(cycle("Style", () -> cfg.getStyle().label, dir -> {
            Style[] v = Style.values();
            cfg.setStyle(v[Math.floorMod(cfg.getStyle().ordinal() + dir, v.length)]);
        }, cfg, requestRebuild, sx, y[0], sw));
        y[0] += STEP;
        w.add(cycle("Size Mode", () -> cfg.getSizeMode().label, dir -> {
            SizeMode[] v = SizeMode.values();
            cfg.setSizeMode(v[Math.floorMod(cfg.getSizeMode().ordinal() + dir, v.length)]);
        }, cfg, requestRebuild, sx, y[0], sw));
        y[0] += STEP;
        slider(w, sx, y, sw, "Scale", "x", CustomCrosshairConfig.MIN_SCALE, CustomCrosshairConfig.MAX_SCALE, 0.05f,
                cfg::getScale, cfg::setScale, cfg);

        Style style = cfg.getStyle();
        header(w, sx, y, sw, "Shape");
        if (style != Style.DOT) {
            toggle(w, sx, y[0], sw, "Center Dot", cfg::isDot, cfg::setDot, cfg, requestRebuild);
            y[0] += STEP;
        }
        if (style.hasArms()) {
            if (style != Style.T_SHAPE) {
                toggle(w, sx, y[0], half, "Top Arm", cfg::isArmTop, cfg::setArmTop, cfg, null);
            }
            toggle(w, sx + half + COL_GAP, y[0], half, "Bottom Arm", cfg::isArmBottom, cfg::setArmBottom, cfg, null);
            y[0] += STEP;
            toggle(w, sx, y[0], half, "Left Arm", cfg::isArmLeft, cfg::setArmLeft, cfg, null);
            toggle(w, sx + half + COL_GAP, y[0], half, "Right Arm", cfg::isArmRight, cfg::setArmRight, cfg, null);
            y[0] += STEP;
            slider(w, sx, y, sw, "Length", "", CustomCrosshairConfig.MIN_LENGTH, CustomCrosshairConfig.MAX_LENGTH,
                    0.5f, cfg::getLength, cfg::setLength, cfg);
            slider(w, sx, y, sw, "Thickness", "", CustomCrosshairConfig.MIN_THICKNESS,
                    CustomCrosshairConfig.MAX_THICKNESS, 0.5f, cfg::getThickness, cfg::setThickness, cfg);
            slider(w, sx, y, sw, "Gap", "", CustomCrosshairConfig.MIN_GAP, CustomCrosshairConfig.MAX_GAP, 0.5f,
                    cfg::getGap, cfg::setGap, cfg);
        }
        if (style == Style.DOT || cfg.isDot()) {
            slider(w, sx, y, sw, "Dot Size", "", CustomCrosshairConfig.MIN_DOT, CustomCrosshairConfig.MAX_DOT, 0.5f,
                    cfg::getDotSize, cfg::setDotSize, cfg);
        }
        if (style.hasCircle()) {
            slider(w, sx, y, sw, "Circle Radius", "", CustomCrosshairConfig.MIN_RADIUS,
                    CustomCrosshairConfig.MAX_RADIUS, 0.5f, cfg::getCircleRadius, cfg::setCircleRadius, cfg);
            slider(w, sx, y, sw, "Circle Thickness", "", CustomCrosshairConfig.MIN_RING,
                    CustomCrosshairConfig.MAX_RING, 0.5f, cfg::getCircleThickness, cfg::setCircleThickness, cfg);
        }
        slider(w, sx, y, sw, "Rotation", "°", CustomCrosshairConfig.MIN_ROTATION, CustomCrosshairConfig.MAX_ROTATION,
                1f, cfg::getRotation, cfg::setRotation, cfg);

        header(w, sx, y, sw, "Colour");
        color(w, sx, y[0], sw, "Color", cfg::getColor, cfg::setColor, 0xFFFFFFFF, cfg);
        y[0] += STEP;
        toggle(w, sx, y[0], half, "Chroma", cfg::isChroma, cfg::setChroma, cfg, requestRebuild);
        toggle(w, sx + half + COL_GAP, y[0], half, "Invert Blend", cfg::isInvertBlend, cfg::setInvertBlend, cfg, null);
        y[0] += STEP;
        if (cfg.isChroma()) {
            slider(w, sx, y, sw, "Chroma Speed", "x", CustomCrosshairConfig.MIN_CHROMA, CustomCrosshairConfig.MAX_CHROMA,
                    0.1f, cfg::getChromaSpeed, cfg::setChromaSpeed, cfg);
        }
        if (style == Style.DOT || cfg.isDot()) {
            toggle(w, sx, y[0], sw, "Separate Dot Color", cfg::isSeparateDotColor, cfg::setSeparateDotColor, cfg,
                    requestRebuild);
            y[0] += STEP;
            if (cfg.isSeparateDotColor()) {
                color(w, sx, y[0], sw, "Dot Color", cfg::getDotColor, cfg::setDotColor, 0xFFFF3030, cfg);
                y[0] += STEP;
            }
        }

        header(w, sx, y, sw, "Outline");
        toggle(w, sx, y[0], sw, "Outline", cfg::isOutline, cfg::setOutline, cfg, requestRebuild);
        y[0] += STEP;
        if (cfg.isOutline()) {
            slider(w, sx, y, sw, "Outline Thickness", "", CustomCrosshairConfig.MIN_OUTLINE,
                    CustomCrosshairConfig.MAX_OUTLINE, 0.5f, cfg::getOutlineThickness, cfg::setOutlineThickness, cfg);
            color(w, sx, y[0], sw, "Outline Color", cfg::getOutlineColor, cfg::setOutlineColor, 0xFF000000, cfg);
            y[0] += STEP;
        }

        header(w, sx, y, sw, "Dynamic");
        toggle(w, sx, y[0], sw, "Spread When Moving", cfg::isSpreadMoving, cfg::setSpreadMoving, cfg, requestRebuild);
        y[0] += STEP;
        toggle(w, sx, y[0], sw, "Spread When Sprinting", cfg::isSpreadSprinting, cfg::setSpreadSprinting, cfg,
                requestRebuild);
        y[0] += STEP;
        toggle(w, sx, y[0], sw, "Spread When Jumping", cfg::isSpreadJumping, cfg::setSpreadJumping, cfg,
                requestRebuild);
        y[0] += STEP;
        if (cfg.anySpread()) {
            slider(w, sx, y, sw, "Spread Amount", "", CustomCrosshairConfig.MIN_SPREAD,
                    CustomCrosshairConfig.MAX_SPREAD, 0.5f, cfg::getSpreadAmount, cfg::setSpreadAmount, cfg);
        }
        toggle(w, sx, y[0], sw, "Recoil On Attack", cfg::isRecoil, cfg::setRecoil, cfg, requestRebuild);
        y[0] += STEP;
        if (cfg.isRecoil()) {
            slider(w, sx, y, sw, "Recoil Amount", "", CustomCrosshairConfig.MIN_SPREAD,
                    CustomCrosshairConfig.MAX_SPREAD, 0.5f, cfg::getRecoilAmount, cfg::setRecoilAmount, cfg);
        }
        toggle(w, sx, y[0], sw, "Color On Entity", cfg::isColorOnEntity, cfg::setColorOnEntity, cfg, requestRebuild);
        y[0] += STEP;
        if (cfg.isColorOnEntity()) {
            color(w, sx, y[0], sw, "Entity Color", cfg::getEntityColor, cfg::setEntityColor, 0xFFFF3030, cfg);
            y[0] += STEP;
        }
        toggle(w, sx, y[0], sw, "Color On Block", cfg::isColorOnBlock, cfg::setColorOnBlock, cfg, requestRebuild);
        y[0] += STEP;
        if (cfg.isColorOnBlock()) {
            color(w, sx, y[0], sw, "Block Color", cfg::getBlockColor, cfg::setBlockColor, 0xFF40B0FF, cfg);
            y[0] += STEP;
        }

        header(w, sx, y, sw, "Visibility");
        toggle(w, sx, y[0], sw, "Attack Indicator", cfg::isAttackIndicator, cfg::setAttackIndicator, cfg, null);
        y[0] += STEP;
        toggle(w, sx, y[0], sw, "Hide In Menus", cfg::isHideInMenus, cfg::setHideInMenus, cfg, null);
        y[0] += STEP;
        toggle(w, sx, y[0], sw, "Show In Third Person", cfg::isShowInThirdPerson, cfg::setShowInThirdPerson, cfg,
                null);
        y[0] += STEP;

        header(w, sx, y, sw, "Presets");
        List<String> builtIn = CustomCrosshairConfig.BUILT_IN;
        for (int i = 0; i < builtIn.size(); i += 2) {
            for (int j = 0; j < 2 && i + j < builtIn.size(); j++) {
                String name = builtIn.get(i + j);
                w.add(SettingsButtonWidget.builder(Component.literal(name), btn -> {
                    cfg.applyBuiltIn(name);
                    cfg.save();
                    status = "Applied the " + name + " preset.";
                    requestRebuild.run();
                }).bounds(sx + j * (half + COL_GAP), y[0], half, ROW).build());
            }
            y[0] += STEP;
        }
        Minecraft mc = Minecraft.getInstance();
        int saveW = 60;
        EditBox nameBox = new EditBox(mc.font, sx, y[0], sw - saveW - COL_GAP, ROW, Component.literal("Preset name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.literal("Preset name..."));
        nameBox.setValue(presetName);
        nameBox.setResponder(text -> presetName = text);
        w.add(nameBox);
        w.add(SettingsButtonWidget.builder(Component.literal("Save Preset"), btn -> {
            String saved = cfg.savePreset(presetName);
            status = saved == null ? "Type a name first (letters, digits, space, - and _)." : "Saved preset " + saved + ".";
            if (saved != null) {
                presetName = "";
            }
            requestRebuild.run();
        }).bounds(sx + sw - saveW, y[0], saveW, ROW).build());
        y[0] += STEP;
        int delW = 50;
        for (String name : CustomCrosshairConfig.listPresets()) {
            w.add(SettingsButtonWidget.builder(Component.literal("Load: " + name), btn -> {
                status = cfg.loadPreset(name) ? "Loaded preset " + name + "." : "Could not read preset " + name + ".";
                cfg.save();
                pendingDelete = null;
                requestRebuild.run();
            }).bounds(sx, y[0], sw - delW - COL_GAP, ROW).build());
            boolean confirm = name.equals(pendingDelete);
            w.add(SettingsButtonWidget.builder(Component.literal(confirm ? "§cSure?" : "Delete"), btn -> {
                if (name.equals(pendingDelete)) {
                    status = CustomCrosshairConfig.deletePreset(name) ? "Deleted preset " + name + "."
                            : "Could not delete preset " + name + ".";
                    pendingDelete = null;
                } else {
                    pendingDelete = name;
                }
                requestRebuild.run();
            }).bounds(sx + sw - delW, y[0], delW, ROW).build());
            y[0] += STEP;
        }
        w.add(SettingsButtonWidget.builder(Component.literal("Copy Code"), btn -> {
            mc.keyboardHandler.setClipboard(cfg.toShareCode());
            status = "Crosshair code copied to the clipboard.";
            requestRebuild.run();
        }).bounds(sx, y[0], half, ROW).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Paste Code"), btn -> {
            String clip = mc.keyboardHandler.getClipboard();
            if (cfg.applyShareCode(clip)) {
                cfg.save();
                status = "Crosshair code applied.";
            } else {
                status = "The clipboard does not hold a crosshair code.";
            }
            requestRebuild.run();
        }).bounds(sx + half + COL_GAP, y[0], half, ROW).build());
        y[0] += STEP;
        w.add(SettingsButtonWidget.builder(Component.literal("Reset To Default"), btn -> {
            cfg.resetLook();
            cfg.save();
            status = "Crosshair reset to the default look.";
            requestRebuild.run();
        }).bounds(sx, y[0], sw, ROW).build());
        y[0] += STEP;
        if (!status.isEmpty()) {
            w.add(new StringWidget(sx, y[0], sw, 12, Component.literal("§7" + status), mc.font));
            y[0] += 14;
        }

        if (side) {
            // The preview's own column runs the full height of the settings, so it is never culled while they scroll
            // and it keeps drawing at the top of the visible area (see Preview).
            preview.setHeight(Math.max(preview.getHeight(), y[0] - contentY));
        }
        return w;
    }

    // ---- row helpers -----------------------------------------------------------------------------------------------

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** An ON/OFF row. {@code rebuild} non-null when the switch shows or hides other rows. */
    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, CustomCrosshairConfig cfg, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
            boolean now = !get.get();
            set.accept(now);
            cfg.save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(label, now));
            }
        }).bounds(x, y, width, ROW).build());
    }

    /** A dropdown-style cycling row: left click forward, right click back. */
    private static AbstractWidget cycle(String label, Supplier<String> value, IntConsumer step,
                                        CustomCrosshairConfig cfg, Runnable rebuild, int x, int y, int width) {
        return SettingsButtonWidget.builder(Component.literal(label + ": " + value.get()), btn -> {
            step.accept(1);
            cfg.save();
            rebuild.run();
        }).secondaryPress(btn -> {
            step.accept(-1);
            cfg.save();
            rebuild.run();
        }).bounds(x, y, width, ROW).build();
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float v);
    }

    /** A themed slider over [min, max], snapped to {@code step}. */
    private static void slider(List<AbstractWidget> w, int x, int[] y, int width, String label, String suffix,
                               float min, float max, float step, FloatGetter get, FloatSetter set,
                               CustomCrosshairConfig cfg) {
        double start = (Math.max(min, Math.min(max, get.get())) - min) / (double) (max - min);
        w.add(new ThemedSliderButton(x, y[0], width, ROW, sliderLabel(label, get.get(), suffix), start) {
            @Override
            protected void updateMessage() {
                setMessage(sliderLabel(label, get.get(), suffix));
            }

            @Override
            protected void applyValue() {
                float raw = (float) (min + this.value * (max - min));
                float snapped = Math.round(raw / step) * step;
                set.set(Math.max(min, Math.min(max, snapped)));
                cfg.save();
            }
        });
        y[0] += STEP;
    }

    private static Component sliderLabel(String label, float v, String suffix) {
        return Component.literal(label + ": " + CustomCrosshairConfig.fmt(v) + suffix);
    }

    private static void color(List<AbstractWidget> w, int x, int y, int width, String label, IntSupplier get,
                              IntConsumer set, int def, CustomCrosshairConfig cfg) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, get.getAsInt(), def,
                    argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
        }).bounds(x, y, width, ROW).build());
    }

    // ---- live preview ----------------------------------------------------------------------------------------------

    /**
     * The live preview: a sample background with the crosshair drawn at its real in-game size. Click the box for the
     * next background (right click: previous); click the strip under it to preview a dynamic state. In the side layout
     * the widget's rectangle is the whole right-hand column, and the box is drawn at the column's ORIGINAL top - the
     * content pane's top - so it stays in view however far the settings scroll.
     */
    static final class Preview extends AbstractWidget {

        static final String[] BACKGROUNDS = {"Sky", "Grass", "Stone", "Snow", "Dark", "Nether"};
        static final String[] STATES = {"Idle", "Moving", "Recoil", "On Entity", "On Block"};
        private static final int STATE_H = 16;

        static int background = 0;
        static int state = 0;

        private final int baseY;
        private final int boxH;
        private final boolean sticky;

        Preview(int x, int y, int width, int boxH, boolean sticky) {
            super(x, y, width, boxH + 4 + STATE_H, Component.literal("Crosshair Preview"));
            this.baseY = y;
            this.boxH = boxH;
            this.sticky = sticky;
        }

        private int top() {
            return sticky ? baseY : getY();
        }

        private boolean inBox(double mx, double my) {
            int t = top();
            return mx >= getX() && mx < getX() + getWidth() && my >= t && my < t + boxH;
        }

        private boolean inState(double mx, double my) {
            int t = top() + boxH + 4;
            return mx >= getX() && mx < getX() + getWidth() && my >= t && my < t + STATE_H;
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return this.visible && this.active && (inBox(mx, my) || inState(mx, my));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            step(event.x(), event.y(), 1);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 1 && isMouseOver(event.x(), event.y())) {
                step(event.x(), event.y(), -1);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        private void step(double mx, double my, int dir) {
            if (inState(mx, my)) {
                state = Math.floorMod(state + dir, STATES.length);
            } else {
                background = Math.floorMod(background + dir, BACKGROUNDS.length);
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = top();
            int w = getWidth();
            int h = boxH;
            drawBackground(g, x, y, w, h);
            g.outline(x, y, w, h, inBox(mouseX, mouseY) ? 0xFFCC6600 : 0xFF663D1A);

            CustomCrosshairConfig c = CustomCrosshairConfig.getInstance();
            Minecraft mc = Minecraft.getInstance();
            Window win = mc.getWindow();
            int gs = Math.max(1, win.getGuiScale());
            float f = AutoScale.appliedFactor(McCompat.screen(mc));
            float spread = 0f;
            int main = CustomCrosshairFeature.baseColor(c);
            switch (state) {
                case 1 -> spread = c.anySpread() ? c.getSpreadAmount() : 0f;
                case 2 -> {
                    if (c.isRecoil()) {
                        float phase = (System.currentTimeMillis() % 700L) / 700f;
                        spread = c.getRecoilAmount() * (float) Math.sin(phase * Math.PI);
                    }
                }
                case 3 -> main = c.isColorOnEntity() ? c.getEntityColor() : main;
                case 4 -> main = c.isColorOnBlock() ? c.getBlockColor() : main;
                default -> {
                }
            }
            int dot = c.isSeparateDotColor() ? c.getDotColor() : main;
            g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            try {
                CrosshairRenderer.draw(g, c, (x + w / 2f) * f * gs, (y + h / 2f) * f * gs, gs,
                        CustomCrosshairFeature.unit(c, gs), spread, main, dot);
            } finally {
                g.disableScissor();
            }
            var font = mc.font;
            String bg = BACKGROUNDS[background];
            g.text(font, bg, x + 4, y + h - 11, 0xFFFFFFFF, true);
            if (!c.isEnabledRaw()) {
                g.text(font, "Off in game", x + 4, y + 4, 0xFFFF7070, true);
            }

            int sy = y + h + 4;
            boolean hover = inState(mouseX, mouseY);
            g.fill(x, sy, x + w, sy + STATE_H, hover ? 0xFF262626 : 0xFF1A1A1A);
            g.outline(x, sy, w, STATE_H, hover ? 0xFFCC6600 : 0xFF663D1A);
            g.centeredText(font, "Preview: " + STATES[state], x + w / 2, sy + (STATE_H - 8) / 2, 0xFFFFFFFF);
        }

        private static void drawBackground(GuiGraphicsExtractor g, int x, int y, int w, int h) {
            switch (background) {
                case 1 -> {
                    int horizon = y + h * 11 / 20;
                    g.fillGradient(x, y, x + w, horizon, 0xFF6FA8FF, 0xFFB8D8FF);
                    g.fill(x, horizon, x + w, horizon + 3, 0xFF6BBF45);
                    g.fill(x, horizon + 3, x + w, y + h, 0xFF7A5534);
                }
                case 2 -> checker(g, x, y, w, h, 0xFF7F7F7F, 0xFF6E6E6E);
                case 3 -> g.fill(x, y, x + w, y + h, 0xFFF2F6FA);
                case 4 -> g.fill(x, y, x + w, y + h, 0xFF101010);
                case 5 -> checker(g, x, y, w, h, 0xFF6B1F1F, 0xFF7E2626);
                default -> g.fillGradient(x, y, x + w, y + h, 0xFF6FA8FF, 0xFFB8D8FF);
            }
        }

        /** A block-texture-like checker: one fill for the base, one per dark tile in runs of a row. */
        private static void checker(GuiGraphicsExtractor g, int x, int y, int w, int h, int base, int tile) {
            g.fill(x, y, x + w, y + h, base);
            int s = 12;
            for (int ty = 0; ty * s < h; ty++) {
                for (int tx = ty & 1; tx * s < w; tx += 2) {
                    g.fill(x + tx * s, y + ty * s, Math.min(x + w, x + tx * s + s), Math.min(y + h, y + ty * s + s),
                            tile);
                }
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Preview[%s, %s]", BACKGROUNDS[background], STATES[state]);
        }
    }
}
