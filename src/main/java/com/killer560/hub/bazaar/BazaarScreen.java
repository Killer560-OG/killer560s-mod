package com.killer560.hub.bazaar;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.auction.BazaarIcons;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * The Bazaar screen with no Hypixel menu behind it: /killer560bz, the keybind and the settings button, only with an
 * active Booster Cookie (see {@link BazaarFeature}). It draws {@link BazaarView} - the same frame a reskinned Hypixel
 * menu draws - so when a product is opened and Hypixel's menu replaces this screen, nothing on screen moves.
 */
public final class BazaarScreen extends Screen {

    private final Screen parent;

    public BazaarScreen(Screen parent) {
        super(Component.literal("Bazaar"));
        this.parent = parent;
        BazaarApi.ensureAutoStarted();
        if (!BazaarCatalog.isLoaded()) {
            CompletableFuture.runAsync(BazaarCatalog::ensureLoaded);
        }
        if (!BazaarView.sessionLive(BazaarReskin.RECENT_MS)) {
            BazaarView.freshSession();
        }
    }

    @Override
    protected void init() {
        BazaarIcons.revalidatePackModels();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        BazaarView.draw(g, this.width, this.height, mouseX, mouseY, null, false, "screen");
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        BazaarView.press(event.x(), event.y(), event.button(), event.hasShiftDown(), null);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        BazaarView.scroll(mouseX, mouseY, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (BazaarView.key(keyEvent)) {
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        BazaarView.charTyped(event);
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
