package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqueue.DungeonQueueConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.partyfinder.PartyFinderOverlay;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig.CompactMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import com.killer560.hub.compat.McCompat;

/** Dungeon Queue: Auto Requeue ({@link com.killer560.hub.dungeonqueue.DungeonQueueFeature}) and Party Finder
 *  Overlay ({@link com.killer560.hub.partyfinder.PartyFinderOverlay}). */
public class DungeonQueueTab extends BaseTab {

    public DungeonQueueTab() {
        super("Dungeon Queue");
    }

    /** Auto Requeue and Party Finder Overlay are two features with a switch each, not one feature with sub-settings: the
     *  card is ON while either is, OFF only when both are (killer560, 2026-10-14). */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Auto Requeue", "Party Finder Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        // ---------------------------------------------------------------- Auto Requeue
        DungeonQueueConfig queue = DungeonQueueConfig.getInstance();
        widgets.add(onOffButton("Auto Requeue", queue::isEnabledRaw, queue::setEnabled, queue::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (queue.isEnabledRaw()) {
            widgets.add(new ThemedSliderButton(contentX, y, colW, 18, delayText(queue),
                    queue.getDelaySeconds() / (double) DungeonQueueConfig.MAX_DELAY_SECONDS) {
                @Override
                protected void updateMessage() {
                    setMessage(delayText(queue));
                }

                @Override
                protected void applyValue() {
                    queue.setDelaySeconds((int) Math.round(this.value * DungeonQueueConfig.MAX_DELAY_SECONDS));
                    saveOnRelease(() -> queue.save());
                }
            });
            widgets.add(toggle("Disable on Leave/Kick", queue::isDisableOnLeave, queue::setDisableOnLeave, queue::save,
                    colBX, y, colW));
            y += 24;
        }
        y += 6;

        // ---------------------------------------------------------------- Party Finder Overlay
        PartyFinderOverlayConfig pf = PartyFinderOverlayConfig.getInstance();
        widgets.add(onOffButton("Party Finder Overlay", pf::isEnabledRaw, pf::setEnabled, pf::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (!pf.isEnabledRaw()) {
            return widgets;
        }

        // Highlight
        widgets.add(rebuildToggle("Highlight", pf::isHighlight, pf::setHighlight, pf::save, requestRebuild,
                contentX, y, contentWidth));
        y += 20;
        if (pf.isHighlight()) {
            widgets.add(toggle("Ignore Cata Level", pf::isIgnoreCataRequirement, pf::setIgnoreCataRequirement, pf::save,
                    contentX, y, colW));
            widgets.add(toggle("Ignore Class Level", pf::isIgnoreRoleLevel, pf::setIgnoreRoleLevel, pf::save,
                    colBX, y, colW));
            y += 20;
            widgets.add(toggle("Ignore Own Class", pf::isIgnoreOwnRole, pf::setIgnoreOwnRole, pf::save,
                    contentX, y, contentWidth));
            y += 20;
            widgets.add(colorButton("Joinable Color", pf::getJoinableColor, pf::setJoinableColor,
                    PartyFinderOverlayConfig.DEFAULT_JOINABLE_COLOR, pf::save, contentX, y, colW));
            widgets.add(colorButton("Blocked Color", pf::getBlockedColor, pf::setBlockedColor,
                    PartyFinderOverlayConfig.DEFAULT_BLOCKED_COLOR, pf::save, colBX, y, colW));
            y += 20;
        }
        y += 4;

        // Member Count
        widgets.add(rebuildToggle("Member Count", pf::isMemberCount, pf::setMemberCount, pf::save, requestRebuild,
                contentX, y, pf.isMemberCount() ? colW : contentWidth));
        if (pf.isMemberCount()) {
            widgets.add(colorButton("Count Color", pf::getCountColor, pf::setCountColor,
                    PartyFinderOverlayConfig.DEFAULT_COUNT_COLOR, pf::save, colBX, y, colW));
        }
        y += 24;

        // Tooltip Stats
        widgets.add(rebuildToggle("Tooltip Stats", pf::isTooltip, pf::setTooltip, pf::save, requestRebuild,
                contentX, y, contentWidth));
        y += 20;
        if (!pf.isTooltip()) {
            return widgets;
        }
        widgets.add(toggle("Show Missing", pf::isShowMissing, pf::setShowMissing, pf::save, contentX, y, colW));
        widgets.add(SettingsButtonWidget.cycling(valueText("PB Mode", pf.getPbMode().label), (btn, dir) -> {
                    pf.setPbMode(SettingsButtonWidget.step(pf.getPbMode(), dir));
                    pf.save();
                    btn.setMessage(valueText("PB Mode", pf.getPbMode().label));
                }).bounds(colBX, y, colW, 18).build());
        y += 20;
        // Label renamed from "Compact" to "Party Finder style" (killer560 7.2) - the persisted CompactMode
        // enum values (NONE/STYLE1/STYLE2/CUSTOM) are untouched, so an existing selection still loads.
        widgets.add(SettingsButtonWidget.cycling(valueText("Party Finder style", pf.getCompactMode().label), (btn, dir) -> {
                    pf.setCompactMode(SettingsButtonWidget.step(pf.getCompactMode(), dir));
                    pf.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(toggle("Rank Name Colors", pf::isRankNameColors, pf::setRankNameColors, pf::save, colBX, y, colW));
        y += 20;
        if (pf.getCompactMode() == CompactMode.CUSTOM) {
            y = buildCustomStyleEditor(widgets, pf, requestRebuild, contentX, y, contentWidth, colW, colBX);
        }

        // ---------------------------------------------------------------- Style Preview
        // Always shown, no toggle (killer560, 2026-10-14: "it should always show down there").
        y += 4;
        int previewHeight = 8 + (PartyFinderOverlay.PREVIEW_LINE_COUNT + 1) * 10;
        widgets.add(new StylePreviewWidget(contentX, y, contentWidth, previewHeight, pf));
        return widgets;
    }

    /** The Custom style editor (killer560, 2026-10-15: "a better way for them to make and save a custom style"): a Saved
     *  Style button that steps through the named styles (loading each), a name box with Save and Delete, the style text with
     *  its placeholders as buttons that add themselves to it, Load Built-in to start from one of the built-in styles, and a
     *  line naming any {@code $Word} that is not a placeholder. The preview below shows the result live.
     *  @return the y under the editor */
    private int buildCustomStyleEditor(List<AbstractWidget> widgets, PartyFinderOverlayConfig pf, Runnable requestRebuild,
                                       int contentX, int y, int contentWidth, int colW, int colBX) {
        Minecraft client = Minecraft.getInstance();
        // Saved Style (steps through "none" then every saved style, loading it) and Load Built-in (copies a built-in's text).
        widgets.add(SettingsButtonWidget.cycling(savedStyleText(pf), (btn, dir) -> {
                    List<String> ring = new ArrayList<>();
                    ring.add("");
                    ring.addAll(pf.savedStyleNames());
                    int at = Math.max(0, ring.indexOf(pf.getActiveStyle()));
                    String next = ring.get(SettingsButtonWidget.step(at, dir, ring.size()));
                    if (next.isEmpty()) {
                        pf.clearActiveStyle();
                    } else {
                        pf.loadStyle(next);
                    }
                    pendingStyleName = next;
                    pf.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.cycling(valueText("Load Built-in", builtInLabel), (btn, dir) -> {
                    List<CompactMode> ring = builtInStyles();
                    int at = ring.indexOf(lastBuiltIn);
                    lastBuiltIn = ring.get(at < 0 ? (dir > 0 ? 0 : ring.size() - 1) : SettingsButtonWidget.step(at, dir, ring.size()));
                    builtInLabel = lastBuiltIn.label;
                    pf.startFromBuiltIn(lastBuiltIn);
                    pf.save();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
        y += 20;

        // Name, Save, Delete.
        int actW = 62;
        int nameW = Math.max(60, contentWidth - 2 * actW - 8);
        EditBox name = new EditBox(client.font, contentX, y, nameW, 18, Component.literal("Style Name"));
        name.setMaxLength(PartyFinderOverlayConfig.MAX_STYLE_NAME);
        name.setHint(Component.literal("\u00a78Style name"));
        name.setValue(pendingStyleName.isEmpty() ? pf.getActiveStyle() : pendingStyleName);
        name.setResponder(text -> pendingStyleName = text);
        widgets.add(name);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Save Style"), btn -> {
                    String wanted = name.getValue().isBlank() ? pf.getActiveStyle() : name.getValue();
                    if (pf.saveStyle(wanted, pf.getCustomStyle())) {
                        pendingStyleName = pf.getActiveStyle();
                        pf.save();
                        requestRebuild.run();
                    }
                }).bounds(contentX + nameW + 4, y, actW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("\u00a7cDelete"), btn -> {
                    String wanted = name.getValue().isBlank() ? pf.getActiveStyle() : name.getValue();
                    if (pf.deleteStyle(wanted)) {
                        pendingStyleName = "";
                        pf.save();
                        requestRebuild.run();
                    }
                }).bounds(contentX + nameW + 8 + actW, y, actW, 18).build());
        y += 20;

        // The style text itself.
        EditBox style = new EditBox(client.font, contentX, y, contentWidth, 18, Component.literal("Custom Style"));
        style.setMaxLength(PartyFinderOverlayConfig.MAX_STYLE_LENGTH);
        style.setHint(Component.literal("Custom Style"));
        style.setValue(pf.getCustomStyle());
        style.setResponder(text -> {
            pf.setCustomStyle(text);
            pf.save();
        });
        widgets.add(style);
        y += 20;

        // Placeholder buttons: each adds its $Word to the end of the style text.
        List<String> holders = PartyFinderOverlay.PLACEHOLDERS;
        int perRow = 5;
        int chipGap = 3;
        int chipW = (contentWidth - chipGap * (perRow - 1)) / perRow;
        for (int i = 0; i < holders.size(); i++) {
            String token = "$" + holders.get(i);
            int cx = contentX + (i % perRow) * (chipW + chipGap);
            int cy = y + (i / perRow) * 18;
            widgets.add(SettingsButtonWidget.builder(Component.literal(token), btn -> {
                        style.setValue(style.getValue() + token);
                    }).bounds(cx, cy, chipW, 16).build());
        }
        y += ((holders.size() + perRow - 1) / perRow) * 18 + 2;

        List<String> unknown = PartyFinderOverlay.unknownPlaceholders(pf.getCustomStyle());
        if (!unknown.isEmpty()) {
            widgets.add(new net.minecraft.client.gui.components.StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("\u00a7cNot a placeholder: " + String.join(" ", unknown)), client.font));
            y += 14;
        } else if (pf.isActiveStyleModified()) {
            widgets.add(new net.minecraft.client.gui.components.StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("\u00a77Changed since \"" + pf.getActiveStyle() + "\" was saved - Save Style keeps it"),
                    client.font));
            y += 14;
        }
        return y;
    }

    /** Scratch for the style editor; survives rebuilds because the tab instance does. */
    private String pendingStyleName = "";
    private CompactMode lastBuiltIn = null;
    private String builtInLabel = "pick one";

    /** The built-in styles Load Built-in steps through: every style that is a template. */
    private static List<CompactMode> builtInStyles() {
        List<CompactMode> out = new ArrayList<>();
        for (CompactMode m : CompactMode.values()) {
            if (m.template != null) {
                out.add(m);
            }
        }
        return out;
    }

    private static Component savedStyleText(PartyFinderOverlayConfig pf) {
        String active = pf.getActiveStyle();
        return valueText("Saved Style", active.isEmpty() ? "\u00a77none" : active + (pf.isActiveStyleModified() ? "\u00a77 *" : ""));
    }

    /** Live preview of "Party Finder style" (killer560 7.2): killer560/aut0balls/agreencatgirl/
     *  femboy_recruiter/latinomommy at cata/class 50/45/40/35/30, rendered through the exact same
     *  {@link PartyFinderOverlay#renderPreviewLines} the real tooltip formatting uses. Reads {@code cfg} fresh
     *  every frame, so it tracks the selected style, PB Mode, Rank Name Colors and Custom Style text live
     *  without needing {@code requestRebuild} (which would otherwise steal focus from the Custom Style box
     *  while typing). */
    private static final class StylePreviewWidget extends AbstractWidget {
        private static final int BG = 0xFF1A1108;
        private static final int BORDER = 0xFF663D1A;
        private static final int LABEL = 0xFFAAAAAA;
        private static final int LINE_H = 10;

        private final PartyFinderOverlayConfig cfg;

        StylePreviewWidget(int x, int y, int width, int height, PartyFinderOverlayConfig cfg) {
            super(x, y, width, height, Component.literal("Party Finder Style Preview"));
            this.cfg = cfg;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x0 = getX();
            int y0 = getY();
            graphics.fill(x0, y0, x0 + getWidth(), y0 + getHeight(), BG);
            graphics.outline(x0, y0, getWidth(), getHeight(), BORDER);
            graphics.text(Minecraft.getInstance().font, "Preview:", x0 + 4, y0 + 3, LABEL, false);
            int ly = y0 + 3 + LINE_H;
            for (Component line : PartyFinderOverlay.renderPreviewLines(cfg)) {
                graphics.text(Minecraft.getInstance().font, line, x0 + 4, ly, 0xFFFFFFFF, false);
                ly += LINE_H;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private static AbstractWidget toggle(String label, BoolGetter getter, BoolSetter setter, Runnable save,
                                         int x, int y, int w) {
        return onOffButton(label, getter::get, setter::set, save, null, x, y, w, 18);
    }

    private static AbstractWidget rebuildToggle(String label, BoolGetter getter, BoolSetter setter, Runnable save,
                                                Runnable requestRebuild, int x, int y, int w) {
        return onOffButton(label, getter::get, setter::set, save, requestRebuild, x, y, w, 18);
    }

    private static AbstractWidget colorButton(String label, IntSupplier getter, IntConsumer setter, int defaultArgb,
                                              Runnable save, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, getter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, getter.getAsInt(), defaultArgb, argb -> {
                        setter.accept(argb);
                        save.run();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private static Component delayText(DungeonQueueConfig cfg) {
        return Component.literal("Requeue Delay: " + cfg.getDelaySeconds() + "s");
    }

    private static Component valueText(String label, String value) {
        return Component.literal(label + ": §6" + value);
    }
}
