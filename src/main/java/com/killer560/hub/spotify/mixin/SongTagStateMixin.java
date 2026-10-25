package com.killer560.hub.spotify.mixin;

import com.killer560.hub.spotify.SongTagState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Attaches {@link SongTagState} to every {@code EntityRenderState}: one {@code @Unique} field, null unless Song Tag set it. */
@Mixin(EntityRenderState.class)
public abstract class SongTagStateMixin implements SongTagState {

    @Unique
    private Component killer560smod$songTag;

    @Override
    public Component killer560smod$songTag() {
        return killer560smod$songTag;
    }

    @Override
    public void killer560smod$setSongTag(Component tag) {
        killer560smod$songTag = tag;
    }
}
