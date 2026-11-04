package com.killer560.hub.gui.tab;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Base of every Rift and Fishing tab (staged for after 2.0): the feature's switch first (labeled with the tab's name, key "enabled"), then the rows
 * {@link #rows} adds while it is on. Row helpers save on every change. A cheat-only tab builds nothing in a legit jar.
 */
public abstract class IslandFeatureTab extends BaseTab implements KeyCaptureTab {

    protected final IslandConfig cfg;
    private final boolean cheat;
    private final String blurb;
    private String capturing;

    protected IslandFeatureTab(String name, IslandConfig cfg, boolean cheat, String blurb) {
        super(name);
        this.cfg = cfg;
        this.cheat = cheat;
        this.blurb = blurb;
    }

    @Override
    public boolean isCheatOnly() {
        return cheat;
    }

    @Override
    public String cardDescription() {
        return blurb;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (cheat && !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        int[] y = {contentY};
        w.add(SettingsButtonWidget.builder(onOff(name, cfg.isEnabledRaw()), b -> {
            cfg.setEnabled(!cfg.isEnabledRaw());
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 26;
        if (cfg.isEnabledRaw() || isSearchScan()) {
            rows(new Rows(w, contentX, y, contentWidth, requestRebuild));
        }
        return w;
    }

    /** The feature's own rows, shown while its switch is on. */
    protected abstract void rows(Rows r);

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturing != null) {
            cfg.set(capturing, keyCode);
            cfg.save();
        }
        capturing = null;
    }

    @Override
    public void cancelKeyCapture() {
        capturing = null;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        if (capturing != null) {
            cfg.set(capturing, KeyUtil.codeForMouseButton(button));
            cfg.save();
        }
        capturing = null;
    }

    /** Lays rows out top to bottom. */
    protected final class Rows {
        private final List<AbstractWidget> w;
        private final int x;
        private final int[] y;
        private final int width;
        private final Runnable rebuild;

        Rows(List<AbstractWidget> w, int x, int[] y, int width, Runnable rebuild) {
            this.w = w;
            this.x = x;
            this.y = y;
            this.width = width;
            this.rebuild = rebuild;
        }

        public void toggle(String label, String key) {
            toggle(label, key, false);
        }

        /** An ON/OFF row; {@code rebuild} redraws the tab after a press (for rows that show or hide others). */
        public void toggle(String label, String key, boolean rebuildAfter) {
            w.add(SettingsButtonWidget.builder(onOff(label, cfg.on(key)), b -> {
                cfg.set(key, !cfg.on(key));
                cfg.save();
                b.setMessage(onOff(label, cfg.on(key)));
                if (rebuildAfter) {
                    rebuild.run();
                }
            }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
        }

        /** A whole-number slider over the key's declared range, shown as {@code label: <value><unit>}. */
        public void slider(String label, String key, String unit) {
            int min = cfg.min(key);
            int max = cfg.max(key);
            double range = Math.max(1, max - min);
            w.add(new ThemedSliderButton(x, y[0], width, 18, sliderText(label, key, unit), (cfg.num(key) - min) / range) {
                @Override
                protected void updateMessage() {
                    setMessage(sliderText(label, key, unit));
                }

                @Override
                protected void applyValue() {
                    cfg.set(key, (int) Math.round(min + this.value * range));
                    saveOnRelease(cfg::save);
                }
            });
            y[0] += 22;
        }

        private Component sliderText(String label, String key, String unit) {
            return Component.literal(label + ": §b" + cfg.num(key) + unit);
        }

        /** A row stepping through {@code count} named values stored as an index. */
        public void cycle(String label, String key, int count, IntFunction<String> names) {
            w.add(SettingsButtonWidget.cycling(Component.literal(label + ": §b" + names.apply(cfg.num(key))), (b, dir) -> {
                cfg.set(key, SettingsButtonWidget.step(cfg.num(key), dir, count));
                cfg.save();
                b.setMessage(Component.literal(label + ": §b" + names.apply(cfg.num(key))));
            }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
        }

        /** Click, then press a key (or mouse button); Escape clears it. */
        public void key(String label, String key) {
            int code = cfg.num(key);
            Component msg = key.equals(capturing) ? Component.literal("Press any key...")
                    : Component.literal(label + ": " + (code == KeyUtil.NONE ? "§7Not Set" : KeyUtil.bindDisplayName(code)));
            w.add(SettingsButtonWidget.builder(msg, b -> {
                capturing = key;
                b.setMessage(Component.literal("Press any key..."));
            }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
        }

        public void text(String label, String key, int maxLength) {
            Minecraft mc = Minecraft.getInstance();
            w.add(new StringWidget(x, y[0] + 2, width, 12, Component.literal("§7" + label), mc.font));
            y[0] += 14;
            EditBox box = new EditBox(mc.font, x, y[0], width, 18, Component.literal(label));
            box.setMaxLength(maxLength);
            box.setValue(cfg.text(key));
            box.setResponder(v -> {
                cfg.set(key, v);
                cfg.save();
            });
            w.add(box);
            y[0] += 22;
        }

        /** A section header; red for a cheat-only section. */
        public void header(String text, boolean red) {
            w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, red), Minecraft.getInstance().font));
            y[0] += 14;
        }

        public void note(String text) {
            w.add(new StringWidget(x, y[0], width, 12, Component.literal("§7" + text), Minecraft.getInstance().font));
            y[0] += 14;
        }

        public void button(String label, Runnable action) {
            w.add(SettingsButtonWidget.builder(Component.literal(label), b -> {
                action.run();
                rebuild.run();
            }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
        }
    }
}
