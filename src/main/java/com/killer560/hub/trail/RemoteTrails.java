package com.killer560.hub.trail;

import com.killer560.hub.supporters.SupportersAutoShare;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.supporters.SupportersFeature;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Other players' shared trails (killer560, 2026-10-13): a supporter's relay entry may carry a {@link TrailSpec}
 * ({@link SupportersFeature#trailFor}); this draws it behind that player with the same renderer as your own Ribbon Trail
 * ({@link RibbonTrail}, laid from their interpolated position each frame exactly as yours is), one ring buffer per
 * player. Only ribbons: a squares trail an older client shares never gets this far ({@link TrailSpec#fromJson}, 2026-10-14).
 * <p>
 * Bounded: at most {@link #MAX_PLAYERS} trails, the nearest ones within {@link #MAX_DISTANCE} blocks; each ring is
 * fixed-size (256 ribbon points). A player who leaves, goes out of range, stops sharing, turns invisible
 * or becomes a spectator loses their slot on the next tick; a world change, disconnect or "Show Others' Trails" OFF
 * drops them all. The local player is never drawn here - their own trail is {@link TrailFeature}'s.
 * <p>
 * Also nudges {@link SupportersAutoShare#shareCurrent} every {@link #SHARE_RETRY_TICKS} ticks, so a trail changed while
 * a push was throttled (or rejected for coming too soon) still reaches the relay without reopening the menu.
 */
public final class RemoteTrails {

    public static final int MAX_PLAYERS = 16;
    public static final double MAX_DISTANCE = 96.0;
    private static final int SHARE_RETRY_TICKS = 700;

    /** One player's trail. */
    static final class Slot {
        final RibbonTrail ribbon = new RibbonTrail();
        TrailSpec spec;
        int entityId;
        int[] fadeEnds;
    }

    private static final Map<UUID, Slot> SLOTS = new HashMap<>();
    private static ClientLevel lastLevel;
    private static volatile boolean clearRequested;
    private static int ticks;

    // Test hooks (read by the testkit).
    private static long framesDrawn;
    private static int lastDrawnTrails;

    private RemoteTrails() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("RemoteTrails", client -> tick()));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(RemoteTrails::onWorldRender);
        // DISCONNECT runs on the Netty thread (CLAUDE.md): leave a flag the next tick drains.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearRequested = true);
    }

    private static void tick() {
        if (++ticks % SHARE_RETRY_TICKS == 0 && SupportersAutoShare.isLinked()) {
            SupportersAutoShare.shareCurrent(null);
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        LocalPlayer self = client.player;
        if (clearRequested || level != lastLevel) {
            clearRequested = false;
            SLOTS.clear();
            lastLevel = level;
        }
        if (level == null || self == null || !SupportersConfig.getInstance().isShowOthersTrails()) {
            SLOTS.clear();
            return;
        }
        if (SupportersFeature.supporterCount() == 0) {
            SLOTS.clear();
            return;
        }
        // Who shares a trail and is drawable, nearest first.
        // The cheap map lookup goes first: on a lobby full of players almost none share a trail, so nothing else is
        // read for them, and the list is only built once someone does.
        List<AbstractClientPlayer> sharing = null;
        double maxSq = MAX_DISTANCE * MAX_DISTANCE;
        for (AbstractClientPlayer p : level.players()) {
            if (p == self || SupportersFeature.trailFor(p.getUUID()) == null || p.isRemoved() || p.isInvisible()
                    || p.isSpectator() || p.distanceToSqr(self) > maxSq) {
                continue;
            }
            if (sharing == null) {
                sharing = new ArrayList<>();
            }
            sharing.add(p);
        }
        if (sharing == null) {
            SLOTS.clear();
            return;
        }
        if (sharing.size() > MAX_PLAYERS) {
            sharing.sort((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)));
        }
        Set<UUID> keep = new HashSet<>();
        for (int i = 0; i < sharing.size() && i < MAX_PLAYERS; i++) {
            AbstractClientPlayer p = sharing.get(i);
            UUID id = p.getUUID();
            TrailSpec spec = SupportersFeature.trailFor(id);
            Slot slot = SLOTS.get(id);
            if (slot == null || slot.entityId != p.getId()) {
                slot = new Slot();
                SLOTS.put(id, slot);
            }
            slot.spec = spec;
            slot.entityId = p.getId();
            slot.fadeEnds = SupportersFeature.nameFadeEndsFor(id);
            keep.add(id);
        }
        SLOTS.keySet().retainAll(keep);
    }

    private static void onWorldRender(LevelRenderContext context) {
        lastDrawnTrails = 0;
        if (SLOTS.isEmpty() || !SupportersConfig.getInstance().isShowOthersTrails()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int drawn = 0;
        for (Iterator<Slot> it = SLOTS.values().iterator(); it.hasNext(); ) {
            Slot slot = it.next();
            Entity e = level.getEntity(slot.entityId);
            if (!(e instanceof Player p) || slot.spec == null) {
                continue;
            }
            long before = slot.ribbon.frames();
            slot.ribbon.draw(context, p, slot.spec, slot.fadeEnds, false);
            if (slot.ribbon.frames() > before) {
                drawn++;
            }
        }
        lastDrawnTrails = drawn;
        if (drawn > 0) {
            framesDrawn++;
        }
    }

    /** Frames in which at least one other player's trail was drawn. */
    public static long framesDrawn() {
        return framesDrawn;
    }

    /** Other players' trails drawn in the last frame. */
    public static int lastDrawnTrails() {
        return lastDrawnTrails;
    }

    /** Players with a trail slot right now. */
    public static int tracked() {
        return SLOTS.size();
    }

    /** {@code id}'s ribbon: {points, length in blocks, frames drawn} for their last frame, or null when untracked. */
    public static double[] ribbonState(UUID id) {
        Slot s = SLOTS.get(id);
        return s == null ? null : new double[]{s.ribbon.drawnPoints(), s.ribbon.drawnLength(), s.ribbon.frames()};
    }

    /** {@code id}'s ribbon vertices from its last drawn frame (see {@link RibbonTrail#vertices}), or null when untracked. */
    public static float[] ribbonVertices(UUID id) {
        Slot s = SLOTS.get(id);
        return s == null ? null : s.ribbon.vertices();
    }
}
