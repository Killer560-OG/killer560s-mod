package com.killer560.hub.spotify;

import net.minecraft.network.chat.Component;

/**
 * Duck interface {@code spotify.mixin.SongTagStateMixin} adds to every {@code EntityRenderState}: the song line to draw
 * above this entity's nametag this frame, or null. Set at {@code AvatarRenderer.extractRenderState}'s TAIL (where the
 * entity is known) and read at the name tag submit (where only the state is). On {@code EntityRenderState}, not the
 * avatar's state, so Play as Mob's stand-in state can carry it too ({@code MobSkinRender.dress} copies it).
 * Outside the mixin package on purpose: a class in a mixin package cannot be referenced (CLAUDE.md).
 */
public interface SongTagState {
    Component killer560smod$songTag();

    void killer560smod$setSongTag(Component tag);
}
