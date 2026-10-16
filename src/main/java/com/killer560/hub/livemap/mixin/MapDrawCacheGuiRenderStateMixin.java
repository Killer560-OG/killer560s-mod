package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.MapDrawCache;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every element added to the GUI render state to {@link MapDrawCache} while the Dungeon Map records one of its
 * parts (otherwise a static read and a return). Same class and method names in 26.1.2 and 26.2 (javap).
 * The testkit's 95-perf-livemap asserts the cache hits, so a target that stopped matching shows up there.
 */
@Mixin(GuiRenderState.class)
public abstract class MapDrawCacheGuiRenderStateMixin {

    /** Every return of the hooked methods: a call made from inside one of them is the render state's own, not drawn. */
    @Inject(method = {"addGuiElement", "addText", "addItem", "addPicturesInPictureState", "addBlitToCurrentLayer",
            "addGlyphToCurrentLayer", "up", "nextStratum", "blurBeforeThisStratum"}, at = @At("RETURN"))
    private void killer560smod$mapCacheReturn(CallbackInfo ci) {
        MapDrawCache.onReturn();
    }

    @Inject(method = "addGuiElement", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheElement(GuiElementRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(0, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "addText", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheText(GuiTextRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(1, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "addItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheItem(GuiItemRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(2, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "addPicturesInPictureState", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCachePip(PictureInPictureRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(3, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "addBlitToCurrentLayer", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheBlit(BlitRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(4, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "addGlyphToCurrentLayer", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheGlyph(GuiElementRenderState state, CallbackInfo ci) {
        if (MapDrawCache.onAdd(5, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "up", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheUp(CallbackInfo ci) {
        if (MapDrawCache.onAdd(6, null)) {
            ci.cancel();
        }
    }

    @Inject(method = "nextStratum", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheNextStratum(CallbackInfo ci) {
        if (MapDrawCache.onAdd(7, null)) {
            ci.cancel();
        }
    }

    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void killer560smod$mapCacheBlur(CallbackInfo ci) {
        if (MapDrawCache.onAdd(8, null)) {
            ci.cancel();
        }
    }
}
