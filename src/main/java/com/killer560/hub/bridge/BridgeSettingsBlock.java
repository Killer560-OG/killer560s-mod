package com.killer560.hub.bridge;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Cross-Mod Bridge's settings, drawn as a small block inside the Party Interop tab (which already lives in
 * the New tab): a master toggle, one toggle per mod, and a live status line per mod. Kept here, in the bridge's
 * own package, so the Interop tab only needs a one-line call.
 */
public final class BridgeSettingsBlock {

    private static final int LINE_HEIGHT = 11;
    private static final int STATUS_LINES = 5;

    private BridgeSettingsBlock() {
    }

    /** Adds the block's widgets at {@code y}. @return the y just below the block. */
    public static int addWidgets(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Cross-Mod Bridge", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    BridgeFeature.onSettingsChanged();
                    requestRebuild.run();
                }).bounds(x, y, 220, 18).build());
        y += 22;
        if (!cfg.isEnabled()) {
            return y;
        }
        widgets.add(SettingsButtonWidget.builder(onOff("Devonian Bridge", cfg.isDevonian()), btn -> {
                    cfg.setDevonian(!cfg.isDevonian());
                    cfg.save();
                    BridgeFeature.onSettingsChanged();
                    btn.setMessage(onOff("Devonian Bridge", cfg.isDevonian()));
                }).bounds(x, y, 220, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("NoammAddons Bridge", cfg.isNoamm()), btn -> {
                    cfg.setNoamm(!cfg.isNoamm());
                    cfg.save();
                    BridgeFeature.onSettingsChanged();
                    btn.setMessage(onOff("NoammAddons Bridge", cfg.isNoamm()));
                }).bounds(x, y, 220, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Odin Bridge", cfg.isOdin()), btn -> {
                    cfg.setOdin(!cfg.isOdin());
                    cfg.save();
                    BridgeFeature.onSettingsChanged();
                    btn.setMessage(onOff("Odin Bridge", cfg.isOdin()));
                }).bounds(x, y, 220, 18).build());
        y += 22;
        widgets.add(new LiveText(x, y, width, LINE_HEIGHT * STATUS_LINES, BridgeSettingsBlock::status));
        return y + LINE_HEIGHT * STATUS_LINES + 4;
    }

    private static List<Component> status() {
        List<Component> lines = new ArrayList<>(STATUS_LINES);
        for (SocketAdapter adapter : BridgeFeature.adapters()) {
            BridgeStatus s = adapter.status();
            String detail = adapter.statusDetail();
            lines.add(ModChat.colored(adapter.displayName() + ": ", ModChat.TEXT)
                    .append(ModChat.colored(s.label, s.colour))
                    .append(detail == null || detail.isEmpty() ? Component.empty() : ModChat.dim(" - " + detail)));
        }
        List<MelodyIntel.Progress> melody = MelodyIntel.snapshot();
        if (!melody.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (MelodyIntel.Progress p : melody) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                int pct = p.percent();
                sb.append(p.player()).append(' ').append(pct < 0 ? "?" : pct + "%");
            }
            lines.add(ModChat.colored("Melody (Odin users): ", ModChat.TEXT).append(ModChat.value(sb.toString())));
        }
        return lines;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** Re-read every frame - statuses change while the screen is open. Inert: takes no clicks or focus. */
    private static final class LiveText extends AbstractWidget {

        private final Supplier<List<Component>> lines;

        private LiveText(int x, int y, int width, int height, Supplier<List<Component>> lines) {
            super(x, y, width, height, Component.empty());
            this.lines = lines;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int y = getY();
            for (Component line : lines.get()) {
                graphics.text(Minecraft.getInstance().font, line, getX(), y, 0xFFFFFFFF);
                y += LINE_HEIGHT;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal("Cross-Mod Bridge status"));
        }
    }
}
