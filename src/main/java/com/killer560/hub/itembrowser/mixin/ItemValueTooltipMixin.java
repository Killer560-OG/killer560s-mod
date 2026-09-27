package com.killer560.hub.itembrowser.mixin;

import com.killer560.hub.itembrowser.ItemBrowserConfig;
import com.killer560.hub.itembrowser.SkyblockItemValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyHanni-style "Item Value" tooltip line - killer560 (2026-09-27): "make a whole new setting just like
 * skyhanni that shows the items value on its tooltip." Off by default; see {@link ItemBrowserConfig#isShowItemValue()}.
 * <p>
 * Same injection point/reasoning as {@link com.killer560.hub.enchantcolors.mixin.EnchantColorsTooltipMixin}
 * (confirmed the funnel for the player's own inventory and every plain screen); a second mixin on
 * {@code AbstractContainerScreen#getTooltipFromContainerItem} ({@link ItemValueContainerTooltipMixin})
 * covers real container slot hovers, which {@code RngMeterOverlay}'s own mixin found don't actually reach
 * here despite {@code getTooltipFromContainerItem} calling this method internally - so both are hooked,
 * same as that feature ended up needing.
 */
@Mixin(Screen.class)
public abstract class ItemValueTooltipMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$appendItemValue(Minecraft client, ItemStack stack,
                                                        CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> withValue = ItemValueTooltipHelper.appendValue(stack, cir.getReturnValue());
            if (withValue != null) {
                cir.setReturnValue(withValue);
            }
        } catch (Throwable ignored) {
            // Visual-only; the unmodified vanilla tooltip is already the return value.
        }
    }
}

/** Shared by both mixins in this package so the "should I append, and what line" logic lives in one place. */
final class ItemValueTooltipHelper {
    private ItemValueTooltipHelper() {
    }

    private static final String PREFIX = "§7Value: §6";

    static List<Component> appendValue(ItemStack stack, List<Component> lines) {
        if (!ItemBrowserConfig.getInstance().isShowItemValue() || stack == null || stack.isEmpty()) {
            return null;
        }
        // Idempotent: getTooltipFromContainerItem calls getTooltipFromItem internally on at least some
        // code paths (see this mixin's own class doc), so both of this feature's hooks can see the SAME
        // list with the line already appended - never add it twice.
        for (Component line : lines) {
            if (line.getString().startsWith(PREFIX)) {
                return null;
            }
        }
        Long value = SkyblockItemValue.getValue(stack);
        if (value == null) {
            return null; // "no price known" - leave the tooltip alone rather than show a misleading line
        }
        List<Component> withValue = new ArrayList<>(lines);
        withValue.add(Component.literal(PREFIX + SkyblockItemValue.formatCoins(value) + " coins"));
        return withValue;
    }
}
