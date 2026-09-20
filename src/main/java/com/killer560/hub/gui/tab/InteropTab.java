package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.interop.InteropConfig;
import com.killer560.hub.interop.InteropSource;
import com.killer560.hub.interop.LocalModBridge;
import com.killer560.hub.interop.PartyInteropState;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Party Interop settings - see {@link com.killer560.hub.interop.InteropFeature}. Each source of party
 *  knowledge is switchable on its own; the status block underneath shows which other dungeon mods are
 *  installed here and what has actually been picked up this run. */
public class InteropTab extends BaseTab {

    private static final int LINE_HEIGHT = 11;
    private static final int STATUS_LINES = 8;

    public InteropTab() {
        super("Party Interop");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InteropConfig cfg = InteropConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Party Interop", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Work It Out Myself", cfg.isSelfDerivation()), btn -> {
                    cfg.setSelfDerivation(!cfg.isSelfDerivation());
                    cfg.save();
                    btn.setMessage(onOff("Work It Out Myself", cfg.isSelfDerivation()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Read Party Chat", cfg.isChatParsing()), btn -> {
                    cfg.setChatParsing(!cfg.isChatParsing());
                    cfg.save();
                    btn.setMessage(onOff("Read Party Chat", cfg.isChatParsing()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Use Mod Relay", cfg.isRelayData()), btn -> {
                    cfg.setRelayData(!cfg.isRelayData());
                    cfg.save();
                    btn.setMessage(onOff("Use Mod Relay", cfg.isRelayData()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Local Mod Bridge", cfg.isLocalBridge()), btn -> {
                    cfg.setLocalBridge(!cfg.isLocalBridge());
                    cfg.save();
                    btn.setMessage(onOff("Local Mod Bridge", cfg.isLocalBridge()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Log Pickups", cfg.isLogPickups()), btn -> {
                    cfg.setLogPickups(!cfg.isLogPickups());
                    cfg.save();
                    btn.setMessage(onOff("Log Pickups", cfg.isLogPickups()));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        // Live, not built once: this whole block changes during a run while the screen is open.
        widgets.add(new LiveTextWidget(contentX, y, contentWidth, LINE_HEIGHT * STATUS_LINES, InteropTab::status));

        return widgets;
    }

    private static List<Component> status() {
        List<Component> lines = new ArrayList<>(STATUS_LINES);
        String detected = DetectedMods.describe();
        lines.add(ModChat.colored("Other dungeon mods here: ", ModChat.TEXT).append(
                "none".equals(detected) ? ModChat.dim("none") : ModChat.value(detected)));

        InteropConfig cfg = InteropConfig.getInstance();
        if (cfg.isLocalBridge() && !LocalModBridge.anyTargetInstalled()) {
            lines.add(ModChat.colored("Local Mod Bridge: ", ModChat.TEXT)
                    .append(ModChat.colored("on, but none of those mods are installed here", ModChat.BAD)));
        }

        Map<InteropSource, Integer> bySource = PartyInteropState.acceptedBySource();
        int total = 0;
        for (int n : bySource.values()) {
            total += n;
        }
        if (total == 0) {
            lines.add(ModChat.colored("Picked up this run: ", ModChat.TEXT).append(ModChat.dim("nothing yet")));
        } else {
            StringBuilder breakdown = new StringBuilder();
            for (InteropSource source : InteropSource.values()) {
                Integer n = bySource.get(source);
                if (n != null && n > 0) {
                    if (!breakdown.isEmpty()) {
                        breakdown.append(", ");
                    }
                    breakdown.append(source.label()).append(' ').append(n);
                }
            }
            lines.add(ModChat.colored("Picked up this run: ", ModChat.TEXT)
                    .append(ModChat.value(total + " (" + breakdown + ")")));
        }

        lines.add(flagLine(PartyInteropState.Flag.MIMIC_KILLED));
        int secrets = PartyInteropState.counter(PartyInteropState.Counter.SECRETS_FOUND);
        lines.add(ModChat.colored("Secrets: ", ModChat.TEXT)
                .append(secrets < 0 ? ModChat.dim("unknown") : ModChat.value(String.valueOf(secrets))));

        List<String> recent = PartyInteropState.recentEvents(STATUS_LINES - lines.size());
        if (recent.isEmpty()) {
            lines.add(ModChat.dim("Waiting for a run."));
        } else {
            for (String event : recent) {
                lines.add(ModChat.dim("- " + event));
            }
        }
        return lines;
    }

    private static Component flagLine(PartyInteropState.Flag flag) {
        PartyInteropState.Fact<Boolean> fact = PartyInteropState.flagFact(flag);
        if (fact == null) {
            return ModChat.colored(flag.label() + ": ", ModChat.TEXT).append(ModChat.dim("not yet"));
        }
        String who = fact.reporter() == null || fact.reporter().isBlank() ? "" : ", " + fact.reporter();
        return ModChat.colored(flag.label() + ": ", ModChat.TEXT)
                .append(ModChat.colored("yes", ModChat.GOOD))
                .append(ModChat.dim(" (" + fact.source().label() + who + ")"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** A few lines of text re-read every frame - the run state changes while the screen is open, and a
     *  {@code StringWidget} built once would freeze on whatever it said when the tab was opened. Inert: it is
     *  never active, so it takes no clicks and no tab focus. (Same widget shape as {@code ModChatTab}'s.) */
    private static final class LiveTextWidget extends AbstractWidget {

        private final Supplier<List<Component>> lines;

        private LiveTextWidget(int x, int y, int width, int height, Supplier<List<Component>> lines) {
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
            output.add(NarratedElementType.TITLE, Component.literal("Party Interop status"));
        }
    }
}
