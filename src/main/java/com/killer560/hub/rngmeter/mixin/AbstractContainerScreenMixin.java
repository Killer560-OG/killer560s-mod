package com.killer560.hub.rngmeter.mixin;

import com.killer560.hub.rngmeter.RngMeterOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the RNG Meter ranking directly inside every container (chest) screen's own render pass,
 * instead of relying on Fabric's {@code ScreenEvents.afterExtract} - that API is documented for
 * exactly this use case but produced zero visible output here for reasons not fully understood
 * (screen construction/title-matching logged fine, nothing ever appeared on screen, mirroring the
 * `/killer560` command bug that turned out to be a `setScreen` vs `setScreenAndShow` issue).
 * Hooking straight into {@code AbstractContainerScreen.extractRenderState} - the exact method every
 * chest-style Hypixel menu actually executes each frame - removes that dependency entirely.
 *
 * <p>Also appends the profit line onto item tooltips here, via {@code getTooltipFromContainerItem}
 * - confirmed by bytecode inspection to be the actual method every container/chest slot hover
 * tooltip goes through in 26.1.2 (unlike {@code ItemStack.getTooltipLines}, which despite having a
 * matching signature is only reachable through {@code Screen.getTooltipFromItem}, not from
 * container slot hovers - the original mixin targeted that method and never fired once in a full
 * play session because of it).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-rngmeter-tooltip");

    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$renderRngMeterOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                       float partialTick, CallbackInfo ci) {
        RngMeterOverlay.onContainerScreenRender((AbstractContainerScreen<?>) (Object) this, graphics);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void killer560smod$scrollRngMeterOverlay(double mouseX, double mouseY, double scrollX, double scrollY,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!this.getTitle().getString().toLowerCase(java.util.Locale.US).contains("rng")) {
            return;
        }
        if (RngMeterOverlay.handleScroll(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void killer560smod$appendProfitLine(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            if (!com.killer560.hub.rngmeter.RngMeterConfig.getInstance().isEnabled()) {
                return;
            }
            if (!this.getTitle().getString().toLowerCase(java.util.Locale.US).contains("rng")) {
                return;
            }
            List<Component> lines = cir.getReturnValue();
            Long requiredScore = RngMeterOverlay.parseRequiredScore(lines.stream().map(Component::getString).toList());
            if (requiredScore == null) {
                // Not a reward item with its own pity requirement (filler pane, the meter's info
                // item, or the player's own gear shown below the menu) - nothing to append.
                return;
            }
            String name = RngMeterOverlay.cleanItemName(stack.getHoverName().getString());
            String line = RngMeterOverlay.buildProfitLine(name, requiredScore);
            if (line == null) {
                // A category-button icon (its own name is a menu title, e.g. "Catacombs (F1) RNG
                // Meter") rather than a real reward - nothing to append.
                return;
            }
            LOGGER.info("Appending tooltip line for \"{}\": {}", name, line);
            List<Component> newLines = new ArrayList<>(lines);
            newLines.add(Component.literal(line));
            cir.setReturnValue(newLines);
        } catch (Exception e) {
            LOGGER.error("Failed to append RNG meter tooltip line", e);
        }
    }
}
