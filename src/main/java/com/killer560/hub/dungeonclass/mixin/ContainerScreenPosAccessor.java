package com.killer560.hub.dungeonclass.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the container panel's own screen-space origin (javap-verified 26.1.2: {@code protected
 *  int leftPos}/{@code topPos}) - {@link com.killer560.hub.dungeonclass.ClassSelectionOverlay} needs it to turn
 *  a {@code Slot}'s panel-relative {@code x}/{@code y} into the absolute screen coordinates its own top bar
 *  and class-box overlay are drawn in. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenPosAccessor {

    @Accessor("leftPos")
    int killer560smod$leftPos();

    @Accessor("topPos")
    int killer560smod$topPos();
}
