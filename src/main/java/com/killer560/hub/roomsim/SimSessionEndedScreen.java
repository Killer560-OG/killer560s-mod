package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** What a co-op guest sees when the owner's sim drops it: "Session ended: the owner left", and Back to Sim Menu. */
public final class SimSessionEndedScreen extends Screen {

    private static volatile String lastReason;
    private final String reason;

    SimSessionEndedScreen(String reason) {
        super(Component.literal("Session ended"));
        this.reason = reason == null ? "the connection to the owner was lost" : reason;
        lastReason = this.reason;
    }

    /** The reason the last one showed, for tests. */
    static String lastReason() { return lastReason; }

    /** The line it shows. */
    public String line() { return "Session ended: " + reason; }

    @Override
    protected void init() {
        int w = 200;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back to Sim Menu"),
                        b -> McCompat.setScreen(this.minecraft, SimMultiplayerScreen.simMenu()))
                .bounds((this.width - w) / 2, this.height / 2 + 10, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.centered(g, line(), this.width / 2, this.height / 2 - 14, MenuStyle.TXT);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() { McCompat.setScreen(this.minecraft, new TitleScreen()); }
}
