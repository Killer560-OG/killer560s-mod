package com.killer560.hub.trail;

import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameColor;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * killer560's item 8.4, verbatim: "Trail: one square per tick, 1-100, orange while grounded, blue while
 * airborne, nothing while standing still."
 * <p>
 * Ticks from {@link ClientTickEvents#END_CLIENT_TICK} (real ticks, not render frames) and stores each
 * point in a fixed-capacity ring buffer sized to {@link TrailConfig#MAX_LENGTH} - drawing runs every
 * frame, so allocating here would show up as frame stutter (see {@code wave/fps-report.md}'s per-frame
 * cost audit). {@link TrailConfig#getLength()} only controls how much of that buffer is currently drawn;
 * the buffer itself never resizes, so dragging the length slider is free.
 * <p>
 * The Ribbon Trail ({@link RibbonTrail}, its own Cosmetics tab and {@link RibbonTrailConfig} since 2026-10-14) records
 * and draws per frame instead; this class only routes to it. Both can be on at once. Only the ribbon can be shared
 * (Share Trail); other players' shared ribbons are drawn by {@link RemoteTrails}. The squares are never sent.
 * <p>
 * Purely visual, client-side, no gameplay effect - legit in both builds. Renders through the same
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES} + {@link WorldRenderUtils} path as Etherwarp's
 * waypoint boxes ({@link com.killer560.hub.etherwarp.EtherwarpFeature#onWorldRender}), reusing
 * {@link WorldRenderUtils#renderFilledBox} ({@link SquareTrail}) rather than a second vertex-buffer approach: each trail
 * "square" is a thin, flat filled box sitting on the ground at the recorded feet position.
 */
public final class TrailFeature {

    /** The local player's squares (the ribbon's points live in {@link RibbonTrail#LOCAL}). */
    private static final SquareTrail SQUARES = new SquareTrail();

    private static boolean wasEnabled = false;
    private static ClientLevel lastLevel = null;
    private static volatile boolean clearRequested = false;
    private static boolean wasRibbon = false;
    /** The local look, rebuilt each tick from the settings (the renderers read it every frame). */
    private static volatile TrailSpec localSpec = null;
    /** The Name Changer's own colour and fade target for Name Fade (null when no colour is set), rebuilt each tick. */
    private static volatile int[] localFadeEnds = null;

    private TrailFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TrailFeature", client -> tick()));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(TrailFeature::onWorldRender);
        // Disconnecting drops client.level to null anyway (tick() below would catch that on its own next
        // tick), but clearing right away avoids a one-tick window where a stale trail could show through
        // on whatever's drawn immediately after - same belt-and-suspenders reset ChunkCacheManager uses.
        // DISCONNECT runs on the Netty thread (CLAUDE.md), so it only leaves a flag the next tick drains.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearRequested = true);
        RemoteTrails.register();
    }

    /** The local Ribbon Trail's look as it would be shared. */
    public static TrailSpec localSpec() {
        TrailSpec spec = localSpec;
        return spec != null ? spec : TrailSpec.fromConfig(RibbonTrailConfig.getInstance());
    }

    /** The Name Changer's own colour (head) and its fade target (tail) as 0xRRGGBB, or null when no colour is set. */
    static int[] ownFadeEnds() {
        NameChangerConfig nc = NameChangerConfig.getInstance();
        int from = nc.getOwnColor();
        if (from == NameColor.NONE) {
            return null;
        }
        int to = nc.isOwnColorFadeEnabled() && nc.getOwnColorFadeTo() != NameColor.NONE ? nc.getOwnColorFadeTo() : from;
        return new int[]{from & 0xFFFFFF, to & 0xFFFFFF};
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (clearRequested) {
            clearRequested = false;
            clear();
        }
        if (level != lastLevel) {
            // World or dimension change (including join/leave) - old squares belong to a place that no
            // longer exists, so don't let them draw stale in the new world.
            clear();
            lastLevel = level;
        }

        TrailConfig cfg = TrailConfig.getInstance();
        RibbonTrailConfig ribbon = RibbonTrailConfig.getInstance();
        localSpec = TrailSpec.fromConfig(ribbon);
        localFadeEnds = ownFadeEnds();
        boolean enabledNow = cfg.isEnabled();
        if (wasEnabled && !enabledNow) {
            // Same "leaving true -> false" edge-triggered reset every other per-run state in this mod
            // uses - avoids a big stale jump reappearing if the trail is re-enabled later.
            SQUARES.clear();
        }
        wasEnabled = enabledNow;
        boolean ribbonNow = ribbon.isEnabled();
        if (wasRibbon && !ribbonNow) {
            RibbonTrail.clear();
        }
        wasRibbon = ribbonNow;

        LocalPlayer player = client.player;
        if (!enabledNow || level == null || player == null) {
            SQUARES.forgetLast();
            return;
        }
        SQUARES.sample(player.getX(), player.getY(), player.getZ(), player.onGround(), cfg.getTeleportMode());
    }

    private static void onWorldRender(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        RibbonTrailConfig ribbon = RibbonTrailConfig.getInstance();
        if (ribbon.isEnabled()) {
            boolean hidden = !ribbon.isShowInFirstPerson() && client.options.getCameraType().isFirstPerson();
            RibbonTrail.LOCAL.draw(context, client.player, localSpec(), localFadeEnds, hidden);
        }
        TrailConfig cfg = TrailConfig.getInstance();
        if (!cfg.isEnabled() || SQUARES.size() == 0) {
            return;
        }
        if (!cfg.isShowInFirstPerson() && client.options.getCameraType().isFirstPerson()) {
            return;
        }
        SQUARES.render(context, cfg.getLength(), cfg.getSquareSize(), cfg.getOpacityPercent(), cfg.isFadeOut());
    }

    /** Drops every recorded point - called on world/dimension change, disconnect, and the trail being
     *  toggled off. */
    public static void clear() {
        SQUARES.clear();
        RibbonTrail.clear();
    }
}
