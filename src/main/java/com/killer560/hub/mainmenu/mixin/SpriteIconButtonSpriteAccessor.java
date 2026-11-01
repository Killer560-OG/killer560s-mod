package com.killer560.hub.mainmenu.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code SpriteIconButton.sprite} (protected final WidgetSprites, javap 26.3 and 26.2), so the themed button box
 * can tell the pause menu's dark-outlined icons from the title screen's light ones. See {@code MainMenuTheme#iconWell}.
 */
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonSpriteAccessor {

    @Accessor("sprite")
    WidgetSprites killer560smod$getSprite();
}
