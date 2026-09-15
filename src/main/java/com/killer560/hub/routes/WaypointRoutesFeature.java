package com.killer560.hub.routes;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Waypoint Routes - ColeWeight/SkyHanni-style ordered routes. The active route for the current Skyblock area
 * (tab-list "Area:" line, see {@link SkyblockArea}) renders numbered boxes, a line to the next point and
 * optionally lines between all points; walking within the route's radius of the next point advances it.
 * Visual only - never moves the camera or sends anything. Keybinds are raw-polled like the rest of this mod.
 * <p>
 * Progress: index in [0, size) = heading to that point; index == size = paused (just recorded, or a one-shot
 * route finished) - walking back onto point 1 restarts it.
 */
public final class WaypointRoutesFeature {

    public static final String CHAT = "Waypoint Routes";
    private static final long CLEAR_CONFIRM_MS = 3000L;

    private static final boolean[] keyWasDown = new boolean[WaypointRoutesConfig.KEY_NAMES.length];
    private static final Map<String, Integer> progress = new HashMap<>();
    private static String lastActiveId = null;
    private static String lastAreaKey = null;
    private static long clearArmedUntilMs = 0L;

    private WaypointRoutesFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaypointRoutesFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WaypointRoutesFeature::onWorldRender);
    }

    // ------------------------------------------------------------------
    // Public API (tab)
    // ------------------------------------------------------------------

    /** Active route for the current area, falling back to the "Any Area" binding. */
    public static Route activeRoute() {
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        String key = SkyblockArea.key();
        Route route = RouteStore.byId(cfg.getActiveRouteId(key));
        if (route == null && !SkyblockArea.GLOBAL.equals(key)) {
            route = RouteStore.byId(cfg.getActiveRouteId(SkyblockArea.GLOBAL));
        }
        return route;
    }

    public static int getProgress(Route route) {
        int idx = progress.getOrDefault(route.id, 0);
        return Math.max(0, Math.min(idx, route.points.size()));
    }

    public static void setProgress(Route route, int index) {
        progress.put(route.id, Math.max(0, Math.min(index, route.points.size())));
    }

    public static void restart(Route route) {
        setProgress(route, 0);
    }

    public static void startAtNearest(Route route) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || route.points.isEmpty()) {
            setProgress(route, 0);
            return;
        }
        Vec3 pos = client.player.position();
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.points.size(); i++) {
            double d = pos.distanceToSqr(anchor(route.points.get(i)));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        setProgress(route, best);
    }

    public static void forget(String routeId) {
        progress.remove(routeId);
    }

    public static void importFromClipboard() {
        Minecraft client = Minecraft.getInstance();
        RouteCodec.Result result = RouteCodec.parse(client.keyboardHandler.getClipboard());
        if (result.error() != null) {
            ModChat.send(CHAT, ModChat.bad(result.error()));
            return;
        }
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        Route first = null;
        int totalPoints = 0;
        for (RouteCodec.Imported imported : result.routes()) {
            Route route = RouteStore.create(imported.name());
            if (imported.color() != null) {
                route.color = imported.color();
            }
            route.points.addAll(imported.points());
            totalPoints += imported.points().size();
            if (first == null) {
                first = route;
            }
        }
        RouteStore.save();
        String key = SkyblockArea.key();
        cfg.setActiveRouteId(key, first.id);
        cfg.setSelectedRouteId(first.id);
        cfg.save();
        ModChat.send(CHAT, ModChat.text("Imported "), ModChat.value(String.valueOf(totalPoints)),
                ModChat.text(totalPoints == 1 ? " point" : " points"),
                ModChat.text(result.routes().size() > 1 ? " into " + result.routes().size() + " routes, active: " : " as "),
                ModChat.value(first.name), ModChat.dim(" (" + SkyblockArea.label(key) + ")"));
    }

    public static void exportToClipboard(Route route) {
        if (route == null || route.points.isEmpty()) {
            ModChat.send(CHAT, ModChat.bad("Nothing to export - the route has no points."));
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(RouteCodec.toColeweight(route));
        ModChat.send(CHAT, ModChat.text("Copied "), ModChat.value(route.name), ModChat.text(" ("),
                ModChat.value(String.valueOf(route.points.size())), ModChat.text(" points, Coleweight format)."));
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void tick(Minecraft client) {
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null) {
            lastActiveId = null;
            lastAreaKey = null;
            return;
        }
        SkyblockArea.tick(client);
        pollKeys(client, cfg);

        Route route = activeRoute();
        String areaKey = SkyblockArea.key();
        String id = route == null ? null : route.id;
        if (!Objects.equals(id, lastActiveId) || !Objects.equals(areaKey, lastAreaKey)) {
            lastActiveId = id;
            lastAreaKey = areaKey;
            if (route != null) {
                if (cfg.isStartAtNearest()) {
                    startAtNearest(route);
                } else {
                    restart(route);
                }
            }
        }
        if (route != null) {
            advance(client, route);
        }
    }

    private static void advance(Minecraft client, Route route) {
        List<Route.Point> pts = route.points;
        int size = pts.size();
        if (size == 0) {
            return;
        }
        Vec3 pos = client.player.position();
        double r2 = route.getRadius() * route.getRadius();
        int idx = getProgress(route);
        if (idx >= size) {
            if (size >= 2 && pos.distanceToSqr(anchor(pts.get(0))) <= r2
                    && pos.distanceToSqr(anchor(pts.get(size - 1))) > r2) {
                setProgress(route, 1);
            }
            return;
        }
        if (pos.distanceToSqr(anchor(pts.get(idx))) > r2) {
            return;
        }
        if (size == 1) {
            if (!route.loop) {
                setProgress(route, 1);
            }
            return;
        }
        int next = idx + 1;
        if (next >= size) {
            if (route.loop) {
                next = 0;
            } else {
                next = size;
                ModChat.send(CHAT, ModChat.text("Finished "), ModChat.value(route.name), ModChat.text("."));
            }
        }
        setProgress(route, next);
    }

    private static void pollKeys(Minecraft client, WaypointRoutesConfig cfg) {
        for (int i = 0; i < keyWasDown.length; i++) {
            int code = cfg.getKeyCode(i);
            boolean down = code >= 0 && client.getWindow() != null && InputConstants.isKeyDown(client.getWindow(), code);
            // Keys typed into chat/any screen still update state, so closing it never fires an action.
            if (down && !keyWasDown[i] && client.screen == null) {
                onKey(client, cfg, i);
            }
            keyWasDown[i] = down;
        }
    }

    private static void onKey(Minecraft client, WaypointRoutesConfig cfg, int which) {
        switch (which) {
            case WaypointRoutesConfig.KEY_ADD -> addPointHere(client, cfg);
            case WaypointRoutesConfig.KEY_REMOVE_LAST -> {
                Route route = activeRoute();
                if (route == null || route.points.isEmpty()) {
                    ModChat.send(CHAT, ModChat.bad("No active route with points here."));
                    return;
                }
                Route.Point removed = route.points.remove(route.points.size() - 1);
                RouteStore.save();
                setProgress(route, getProgress(route));
                ModChat.send(CHAT, ModChat.text("Removed point "), ModChat.value("#" + (route.points.size() + 1)),
                        ModChat.dim(" (" + removed.x() + ", " + removed.y() + ", " + removed.z() + ")"),
                        ModChat.text(" from "), ModChat.value(route.name));
            }
            case WaypointRoutesConfig.KEY_CLEAR -> {
                Route route = activeRoute();
                if (route == null || route.points.isEmpty()) {
                    ModChat.send(CHAT, ModChat.bad("No active route with points here."));
                    return;
                }
                long now = System.currentTimeMillis();
                if (now > clearArmedUntilMs) {
                    clearArmedUntilMs = now + CLEAR_CONFIRM_MS;
                    ModChat.send(CHAT, ModChat.text("Press again to clear all "),
                            ModChat.value(String.valueOf(route.points.size())), ModChat.text(" points of "),
                            ModChat.value(route.name), ModChat.text("."));
                    return;
                }
                clearArmedUntilMs = 0L;
                route.points.clear();
                RouteStore.save();
                setProgress(route, 0);
                ModChat.send(CHAT, ModChat.text("Cleared "), ModChat.value(route.name), ModChat.text("."));
            }
            case WaypointRoutesConfig.KEY_NEXT, WaypointRoutesConfig.KEY_PREVIOUS -> {
                Route route = activeRoute();
                if (route == null || route.points.isEmpty()) {
                    return;
                }
                int size = route.points.size();
                int idx = getProgress(route);
                boolean forward = which == WaypointRoutesConfig.KEY_NEXT;
                int n;
                if (idx >= size) {
                    n = forward ? 0 : size - 1;
                } else if (route.loop) {
                    n = Math.floorMod(idx + (forward ? 1 : -1), size);
                } else {
                    n = Math.max(0, Math.min(size - 1, idx + (forward ? 1 : -1)));
                }
                setProgress(route, n);
                ModChat.send(CHAT, ModChat.text("Next point: "), ModChat.value("#" + (n + 1)));
            }
            default -> {
            }
        }
    }

    private static void addPointHere(Minecraft client, WaypointRoutesConfig cfg) {
        String key = SkyblockArea.key();
        Route route = activeRoute();
        if (route == null) {
            route = RouteStore.create("Route");
            cfg.setActiveRouteId(key, route.id);
            cfg.setSelectedRouteId(route.id);
            cfg.save();
            ModChat.send(CHAT, ModChat.text("Created "), ModChat.value(route.name),
                    ModChat.dim(" (" + SkyblockArea.label(key) + ")"));
        }
        Vec3 pos = client.player.position();
        // The block being stood on (a hair below the feet), or the feet block while airborne.
        BlockPos block = BlockPos.containing(pos.x, pos.y - 0.2, pos.z);
        if (!route.points.isEmpty()) {
            Route.Point last = route.points.get(route.points.size() - 1);
            if (last.x() == block.getX() && last.y() == block.getY() && last.z() == block.getZ()) {
                ModChat.send(CHAT, ModChat.bad("That block is already the last point."));
                return;
            }
        }
        route.points.add(new Route.Point(block.getX(), block.getY(), block.getZ(), ""));
        RouteStore.save();
        // Paused while recording, so the tracer doesn't pull back to point 1 after every add.
        setProgress(route, route.points.size());
        lastActiveId = route.id;
        lastAreaKey = key;
        ModChat.send(CHAT, ModChat.text("Added "), ModChat.value("#" + route.points.size()),
                ModChat.dim(" (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")"),
                ModChat.text(" to "), ModChat.value(route.name));
    }

    /** Top-centre of the waypoint block - where a player standing on it has their feet. */
    private static Vec3 anchor(Route.Point p) {
        return new Vec3(p.x() + 0.5, p.y() + 1.0, p.z() + 0.5);
    }

    private static Vec3 center(Route.Point p) {
        return new Vec3(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static void onWorldRender(LevelRenderContext context) {
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null) {
            return;
        }
        Route route = activeRoute();
        if (route == null || route.points.isEmpty()) {
            return;
        }
        List<Route.Point> pts = route.points;
        int size = pts.size();
        float[] c = WorldRenderUtils.argbToFloats(route.color);
        float[] hc = WorldRenderUtils.argbToFloats(cfg.getTargetColor());
        float thickness = cfg.getLineThickness();
        int idx = getProgress(route);
        int target = idx < size ? idx : -1;
        int upcoming = -1;
        if (target >= 0 && size > 1) {
            upcoming = target + 1 < size ? target + 1 : (route.loop ? 0 : -1);
        }

        if (cfg.isRouteLines() && size >= 2) {
            List<Vec3> strip = new ArrayList<>(size + 1);
            for (Route.Point p : pts) {
                strip.add(center(p));
            }
            if (route.loop && size > 2) {
                strip.add(center(pts.get(0)));
            }
            WorldRenderUtils.renderLineStrip(context, strip, c[0], c[1], c[2], 0.6f, thickness);
        }

        for (int i = 0; i < size; i++) {
            Route.Point p = pts.get(i);
            AABB box = new AABB(p.x(), p.y(), p.z(), p.x() + 1, p.y() + 1, p.z() + 1);
            if (i == target) {
                WorldRenderUtils.renderFilledBox(context, box, hc[0], hc[1], hc[2], 0.4f);
                WorldRenderUtils.renderOutlineBox(context, box, hc[0], hc[1], hc[2], 1f, thickness + 1f);
            } else if (i == upcoming) {
                WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], 0.2f);
                WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 0.9f, thickness);
            } else {
                WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 0.55f, thickness);
            }
        }

        Camera camera = client.gameRenderer.getMainCamera();
        if (cfg.isLineToNext() && target >= 0) {
            Vector3fc forward = camera.forwardVector();
            Vec3 start = camera.position().add(forward.x(), forward.y(), forward.z());
            WorldRenderUtils.renderLineStrip(context, List.of(start, center(pts.get(target))),
                    hc[0], hc[1], hc[2], 1f, thickness);
        }

        Vec3 playerPos = client.player.position();
        for (int i = 0; i < size; i++) {
            boolean isTarget = i == target;
            if (!cfg.isShowNumbers() && !(isTarget && cfg.isShowDistance())) {
                continue;
            }
            Route.Point p = pts.get(i);
            String label = null;
            if (cfg.isShowNumbers()) {
                label = (i + 1) + (p.label().isEmpty() ? "" : " " + p.label());
            }
            String distance = null;
            if (isTarget && cfg.isShowDistance()) {
                distance = String.format(Locale.US, "%.1fm", Math.sqrt(playerPos.distanceToSqr(anchor(p))));
            }
            int labelColor = isTarget ? (0xFF000000 | cfg.getTargetColor()) : 0xFFFFFFFF;
            renderText(context, camera, p.x() + 0.5, p.y() + 1.6, p.z() + 0.5,
                    label, labelColor, distance, 0xFF000000 | ModChat.LIGHT_ORANGE, cfg.getTextScale());
        }
    }

    /** Billboard text - camera rotation then (+s, -s, +s), the 26.1.2 nametag transform (see
     *  SimonSaysFeature.renderNumber / BloodCampFeature for why not (-s, -s, s)). SEE_THROUGH so the
     *  numbers show through terrain. Grows with distance so far points stay readable. */
    private static void renderText(LevelRenderContext context, Camera camera, double x, double y, double z,
                                   String line1, int color1, String line2, int color2, float scaleMul) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null || (line1 == null && line2 == null)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * scaleMul * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));

        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(s, -s, s);
            float yOff = (line1 != null && line2 != null) ? -font.lineHeight : -font.lineHeight / 2f;
            if (line1 != null) {
                font.drawInBatch(line1, -font.width(line1) / 2f, yOff, color1, false, poseStack.last().pose(),
                        bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
                yOff += font.lineHeight + 1;
            }
            if (line2 != null) {
                font.drawInBatch(line2, -font.width(line2) / 2f, yOff, color2, false, poseStack.last().pose(),
                        bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            }
        } finally {
            // Review fix (2026-09-15): never leave the shared level PoseStack unbalanced if a draw throws.
            poseStack.popPose();
        }
    }
}
