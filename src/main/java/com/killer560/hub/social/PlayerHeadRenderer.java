package com.killer560.hub.social;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small flat "player head" icon for a Best Friends / Friends List row.
 * <p>
 * Nothing in this mod already draws a flat head icon (checked before writing this) - the closest vanilla
 * equivalent in this Minecraft version is {@link net.minecraft.client.gui.components.PlayerSkinWidget}, a
 * full rotatable 3D model viewer built for the skin-customization screen, not a small list-row icon. This
 * instead blits the classic flat regions straight off the raw skin texture: the 8x8 face at (8,8) and the
 * 8x8 hat-layer overlay at (40,8) in the standard 64x64 skin layout, scaled up with a pose-stack scale - the
 * same {@code pushMatrix/translate/scale/popMatrix} pattern {@code scoreboard.CustomScoreboardFeature}
 * already uses to scale its own HUD board.
 * <p>
 * Skins are resolved through {@link net.minecraft.client.resources.SkinManager#createLookup}, the same lazy
 * "shows the default Steve/Alex skin immediately, swaps to the real one once it loads" supplier vanilla's own
 * {@code PlayerSkinWidget} is built on, so a row never blocks or flashes empty while a texture downloads. A
 * UUID that never resolves to a real skin (offline, or simply never seen by the session service) just keeps
 * showing the default - exactly like the tab list does for the same case.
 */
public final class PlayerHeadRenderer {

    private static final Map<UUID, Supplier<PlayerSkin>> CACHE = new ConcurrentHashMap<>();

    private PlayerHeadRenderer() {
    }

    /** Draws a {@code size}x{@code size} face icon (hat layer included) for {@code id}/{@code name} with its
     *  top-left corner at {@code x, y}. */
    public static void draw(GuiGraphicsExtractor g, UUID id, String name, int x, int y, int size) {
        PlayerSkin skin = skinFor(id, name);
        Identifier texture = skin.body().texturePath();
        float scale = size / 8.0f;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            // Base face: 8x8 region at (8,8) in the 64x64 skin layout.
            g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
            // Hat overlay layer, drawn on top - same second region every classic flat head icon uses.
            g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static PlayerSkin skinFor(UUID id, String name) {
        if (id == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        Supplier<PlayerSkin> lookup = CACHE.computeIfAbsent(id, key -> {
            GameProfile profile = new GameProfile(key, name == null || name.isBlank() ? key.toString() : name);
            return Minecraft.getInstance().getSkinManager().createLookup(profile, true);
        });
        PlayerSkin skin = lookup.get();
        return skin != null ? skin : DefaultPlayerSkin.get(id);
    }
}
