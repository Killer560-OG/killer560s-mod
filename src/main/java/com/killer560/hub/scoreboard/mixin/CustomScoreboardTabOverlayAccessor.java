package com.killer560.hub.scoreboard.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Tab list in vanilla display order ({@code getPlayerInfos()}) and its footer, for the Custom Scoreboard's tab-widget
 *  parsing. Callers check {@code instanceof} first, so a failed apply only falls back to unsorted player info. */
@Mixin(PlayerTabOverlay.class)
public interface CustomScoreboardTabOverlayAccessor {

    @Invoker("getPlayerInfos")
    List<PlayerInfo> killer560smod$getPlayerInfos();

    @Accessor("footer")
    Component killer560smod$getFooter();
}
