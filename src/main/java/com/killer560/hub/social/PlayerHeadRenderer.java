package com.killer560.hub.social;

import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.util.ModNet;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small flat "player head" icon for a Best Friends / Friends List row: the 8x8 face at (8,8) and the 8x8
 * hat layer at (40,8) of the standard 64x64 skin, blitted and scaled with the pose stack.
 * <p>
 * <b>Where the skin comes from</b> (2026-10-04, killer560: "make sure all their skins sync properly too").
 * This used to hand {@code SkinManager.createLookup} a bare {@code new GameProfile(uuid, name)}. SkinManager
 * only ever unpacks the profile's own {@code textures} property (javap, {@code SkinManager$1.load}); it never
 * goes to the session server for one, so a bare profile resolved to the default Steve/Alex for every friend,
 * permanently, because the lookup was cached by UUID. On top of that a friend's UUID is not persisted, so a
 * row with no UUID drew the default too. Now, in order:
 * <ol>
 *   <li>no UUID yet: ask {@link PlayerNames} (cache/tab list), and start one async name lookup;</li>
 *   <li>the friend is on the current tab list: use that {@link PlayerInfo}'s skin, which already carries
 *       signed textures from the server;</li>
 *   <li>otherwise fetch the full profile (with textures) once through the session service - the same
 *       {@link ProfileViewerApi#fetchSkinProfile} the Profile Viewer's skin uses - and build the
 *       SkinManager lookup from THAT. Failures retry after a minute.</li>
 * </ol>
 * Until the real skin is in, the UUID's default skin is shown, never a blank square.
 */
public final class PlayerHeadRenderer {

    private static final long RETRY_MS = 60_000L;

    /** Resolved lookups, keyed by UUID. A value is only stored once it is built from a textured profile. */
    private static final Map<UUID, Supplier<PlayerSkin>> RESOLVED = new ConcurrentHashMap<>();
    /** UUID -> time of the last failed/started profile fetch, so a dead lookup is not hammered every frame. */
    private static final Map<UUID, Long> FETCH_AT = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** Lowercase names a UUID lookup has already been started for this session. */
    private static final Set<String> NAME_LOOKUPS = ConcurrentHashMap.newKeySet();

    private PlayerHeadRenderer() {
    }

    /** Draws a {@code size}x{@code size} face icon (hat layer included) for {@code id}/{@code name} with its
     *  top-left corner at {@code x, y}. {@code id} may be null; the name is then resolved in the background. */
    public static void draw(GuiGraphicsExtractor g, UUID id, String name, int x, int y, int size) {
        PlayerSkin skin = skinFor(resolveId(id, name));
        Identifier texture = skin.body().texturePath();
        float scale = size / 8.0f;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
        } finally {
            g.pose().popMatrix();
        }
    }

    /** {@code id} if known, else whatever {@link PlayerNames} has cached for the name (starting one lookup). */
    public static UUID resolveId(UUID id, String name) {
        if (id != null || name == null || name.isBlank()) {
            return id;
        }
        UUID known = PlayerNames.uuidFor(name);
        if (known == null && NAME_LOOKUPS.add(name.toLowerCase(Locale.US))) {
            PlayerNames.resolveAsync(name, resolved -> {
            });
        }
        return known;
    }

    private static PlayerSkin skinFor(UUID id) {
        if (id == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        Supplier<PlayerSkin> resolved = RESOLVED.get(id);
        if (resolved != null) {
            PlayerSkin skin = resolved.get();
            if (skin != null) {
                return skin;
            }
        }
        // On the tab list right now: the server already sent this player's signed textures.
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null) {
                return info.getSkin();
            }
        }
        startFetch(id);
        return DefaultPlayerSkin.get(id);
    }

    private static void startFetch(UUID id) {
        if (ModNet.offline() || IN_FLIGHT.contains(id)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = FETCH_AT.get(id);
        if (last != null && now - last < RETRY_MS) {
            return;
        }
        if (!IN_FLIGHT.add(id)) {
            return;
        }
        FETCH_AT.put(id, now);
        ProfileViewerApi.fetchSkinProfile(id).whenComplete((GameProfile profile, Throwable error) -> {
            IN_FLIGHT.remove(id);
            if (profile == null || error != null) {
                return; // FETCH_AT already holds the attempt time, so this retries after RETRY_MS
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> RESOLVED.put(id, mc.getSkinManager().createLookup(profile, false)));
        });
    }
}
