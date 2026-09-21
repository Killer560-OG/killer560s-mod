package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modchat.ModChatConfig;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayRoom;
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

/** Mod Chat settings - see {@link com.killer560.hub.modchat.ModChatFeature}. Messages go over the mod's own
 *  relay, never over Hypixel chat, so non-mod-users cannot see them. */
public class ModChatTab extends BaseTab {

    private static final int LINE_HEIGHT = 11;

    public ModChatTab() {
        super("Mod Chat");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ModChatConfig cfg = ModChatConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Mod Chat", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Room: " + (cfg.isPartyRoom() ? "Party" : "Global")), btn -> {
                    cfg.setPartyRoom(!cfg.isPartyRoom());
                    cfg.save();
                    btn.setMessage(Component.literal("Room: " + (cfg.isPartyRoom() ? "Party" : "Global")));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Log To Chat", cfg.isLogToChat()), btn -> {
                    cfg.setLogToChat(!cfg.isLogToChat());
                    cfg.save();
                    btn.setMessage(onOff("Log To Chat", cfg.isLogToChat()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Presence Alerts", cfg.isPresenceAlerts()), btn -> {
                    cfg.setPresenceAlerts(!cfg.isPresenceAlerts());
                    cfg.save();
                    btn.setMessage(onOff("Presence Alerts", cfg.isPresenceAlerts()));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        // Live, not built-once: the connection comes and goes while this screen is open.
        widgets.add(new LiveTextWidget(contentX, y, contentWidth, LINE_HEIGHT * 3, ModChatTab::status));

        return widgets;
    }

    private static List<Component> status() {
        RelayClient.State state = RelayClient.state();
        int colour = switch (state) {
            case CONNECTED -> ModChat.GOOD;
            case RETRYING, NO_URL -> ModChat.BAD;
            default -> ModChat.DIM;
        };
        List<Component> lines = new ArrayList<>(3);
        lines.add(ModChat.colored("Relay: ", ModChat.TEXT).append(ModChat.colored(RelayClient.statusText(), colour)));
        String room = RelayClient.room();
        lines.add(ModChat.colored("Room: ", ModChat.TEXT).append(ModChat.value(
                room == null || room.isEmpty() ? "-" : RelayRoom.describe(room))));
        List<String> online = RelayClient.online();
        List<String> others = new ArrayList<>(online.size());
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        for (String name : online) {
            if (self == null || !self.equalsIgnoreCase(name)) {
                others.add(name);
            }
        }
        lines.add(ModChat.colored("Mod users here: ", ModChat.TEXT).append(others.isEmpty()
                ? ModChat.dim(state == RelayClient.State.CONNECTED ? "nobody else yet" : "-")
                : ModChat.value(String.join(", ", others))));
        return lines;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** A few lines of text re-read every frame - the relay's state changes while the screen is open, and a
     *  {@code StringWidget} built once would freeze on whatever it said when the tab was opened. Inert: it is
     *  never active, so it takes no clicks and no tab focus. */
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
            output.add(NarratedElementType.TITLE, Component.literal("Mod Chat relay status"));
        }
    }
}
