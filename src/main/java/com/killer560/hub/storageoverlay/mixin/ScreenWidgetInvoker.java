package com.killer560.hub.storageoverlay.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@code Screen}'s own widget list management (both {@code protected}, verified via javap)
 *  so a real {@link net.minecraft.client.gui.components.EditBox} can be added as a genuine child
 *  widget of the storage overlay's still-vanilla container screen - per killer560's "double click the
 *  actual text and edit it there" request (2026-09-08), rather than hand-rolling keyboard/IME/cursor
 *  handling ourselves, this reuses vanilla's own, so typing, backspace, selection, and clipboard paste
 *  all just work exactly like every other real Minecraft text field. */
@Mixin(Screen.class)
public interface ScreenWidgetInvoker {

    @Invoker("addRenderableWidget")
    GuiEventListener killer560smod$addRenderableWidget(GuiEventListener widget);

    @Invoker("removeWidget")
    void killer560smod$removeWidget(GuiEventListener widget);
}
