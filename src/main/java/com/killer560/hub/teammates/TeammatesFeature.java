package com.killer560.hub.teammates;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.objecthider.ObjectHiderFeature;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.killer560.hub.compat.McRender;

/**
 * Teammate Highlight - boxes your dungeon party in the world, coloured by their dungeon class, with optional name and
 * distance labels.
 * <p>
 * Ported from Devonian {@code features/dungeons/HighlightTeammates.kt} (box/outline switch, "Highlight Self" option,
 * teammate-role colour, wire + fill box). Devonian's default style is vanilla's entity glow outline with a box as the
 * alternative; this port keeps only the box styles - the glow path in this mod is the {@code cheatutils} WitherGlow
 * mixins, which only ask {@code MobEspFeature}, and those mixins are not this feature's to edit.
 * <p>
 * Reused, not rebuilt:
 * <ul>
 * <li><b>Who is a teammate and what class they are</b>: {@link PartyTracker#teammates()} / {@link PartyTracker#classOf}
 * (tab-list "[lvl] Name (Class XL)" parse shared with Class Colors / Leap Menu), and {@link PartyTracker#isDead} for
 * the "(DEAD)" skip.
 * <li><b>Class colours</b>: {@link DungeonClass#color()} - the same shared palette Class Colors paints nametags and
 * the tab list with, and that Live Map paints its teammate dots with. No second set of colours.
 * <li><b>Line of sight / through walls</b>: the same {@code Level#clip} raycast and the same two no-depth pipelines
 * {@code mobesp.MobEspFeature} uses (via {@link TeammatesEspRenderer}). Line-of-sight only is legit and is the only
 * option on the legit jar; Through Walls is cheat-build only, same rule as Dungeon ESP / Wither ESP.
 * <li><b>Label billboard</b>: the 26.1.2 nametag transform used by {@code f7spots.F7SpotsRenderer.renderLabel} and
 * Thorn's stun spots.
 * </ul>
 * <p>
 * <b>Object Hider:</b> {@code objecthider.ObjectHiderFeature.shouldHideEntity} cancels an entity's rendering. Its one
 * predicate that can match a real teammate is <b>Clean End</b> ({@code cleanEndHides}), which hides everything once
 * the boss is dead and only stops hiding {@code RemotePlayer}s again after the reward chests spawn. A highlight drawn
 * on a hidden teammate would be a box floating around nothing, so every candidate here is passed through
 * {@code shouldHideEntity} first and skipped while Object Hider is hiding them - the hider wins. Every other Object
 * Hider predicate matches armor stands, particles, arrows, sheep, creepers or dying dragons, none of which this
 * feature ever looks at, so there is nothing else to reconcile.
 */
public final class TeammatesFeature {

    /** Entity id -> ARGB, rebuilt every client tick; read by the render callback. */
    private static volatile Map<Integer, Integer> targets = Map.of();

    private TeammatesFeature() {
    }

    public static void register() {
        TeammatesEspRenderer.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TeammatesFeature", com.killer560.hub.util.FeatureBits.register("Teammates tick", () -> TeammatesConfig.getInstance().isEnabledRaw()), client -> tick()));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(com.killer560.hub.util.FeatureGuard.afterFeatures(
                com.killer560.hub.util.FeatureBits.register("Teammates render", () -> TeammatesConfig.getInstance().isEnabledRaw()), TeammatesFeature::render));
    }

    private static void tick() {
        TeammatesConfig cfg = TeammatesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        // Dungeon only (Devonian gates on Dungeons.players) - and DungeonState.isInDungeon() is true on p3sim.net
        // while the /killer560 sim override is on, so the feature works there too.
        if (!cfg.isEnabled() || !DungeonState.isInDungeon() || client.level == null || client.player == null) {
            targets = Map.of();
            return;
        }
        boolean throughWalls = cfg.isThroughWalls();
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();
        List<String> party = PartyTracker.teammates();
        Map<Integer, Integer> found = null;

        for (Player player : client.level.players()) {
            boolean self = player == client.player;
            if (self) {
                if (!cfg.isHighlightSelf()) {
                    continue;
                }
            } else if (!isTeammate(player, party)) {
                continue;
            }
            String name = player.getGameProfile().name();
            if (cfg.isSkipDead() && PartyTracker.isDead(name)) {
                continue;
            }
            if (ObjectHiderFeature.shouldHideEntity(player)) {
                // Clean End (and only Clean End) can hide a real teammate - don't leave an empty box behind.
                continue;
            }
            if (player.isRemoved() || (!self && player.distanceToSqr(client.player) > rangeSq)) {
                continue;
            }
            if (!self && !throughWalls && !hasLineOfSight(client, eye, player)) {
                continue;
            }
            DungeonClass clazz = PartyTracker.classOf(name);
            if (found == null) {
                found = new LinkedHashMap<>(); // never mutated after it is published below
            }
            found.put(player.getId(), clazz != null ? clazz.color() : cfg.getUnknownColor());
        }
        targets = found == null ? Map.of() : found;
    }

    /** A real (v4-UUID, i.e. not a Hypixel NPC) player the party tracker lists. */
    private static boolean isTeammate(Player player, List<String> party) {
        if (player.getUUID().version() != 4) {
            return false;
        }
        String name = player.getGameProfile().name();
        for (String member : party) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        // Inside a dungeon the tab list is authoritative and always carries a class; a player the class parse knows
        // is a teammate even if the party-chat list hasn't caught up (rejoin, leader transfer).
        if (PartyTracker.classOf(name) != null) {
            return true;
        }
        // Nothing known at all (p3sim.net's tab list has no class entries and there is no party) - in a real
        // dungeon the only other real players present are your party, so fall back to "every real player".
        return party.isEmpty();
    }

    /** {@code MobEspFeature.hasLineOfSight} - a real block raycast from the camera to the target's eyes. */
    private static boolean hasLineOfSight(Minecraft client, Vec3 eye, Entity target) {
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        return result.getType() == HitResult.Type.MISS;
    }

    private static void render(LevelRenderContext context) {
        Map<Integer, Integer> current = targets;
        TeammatesConfig cfg = TeammatesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (current.isEmpty() || client.level == null || client.player == null || !cfg.isEnabled()) {
            return;
        }
        boolean throughWalls = cfg.isThroughWalls();
        boolean filled = cfg.getStyle() == TeammatesConfig.Style.FILLED;
        float lineWidth = cfg.getLineWidth();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 selfPos = client.player.getPosition(partialTick);
        // Boxes into two reusable lists drawn with one call each, labels after them (FPS pass, 2026-10-15: a fill call,
        // an outline call and a String.format per teammate per frame before). Same boxes, colours and text.
        FILLS.clear();
        LINES.clear();
        int labels = 0;
        for (Map.Entry<Integer, Integer> target : current.entrySet()) {
            Entity entity = client.level.getEntity(target.getKey());
            if (!(entity instanceof Player player) || player.isRemoved()) {
                continue;
            }
            int color = target.getValue();
            double lx = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
            double ly = net.minecraft.util.Mth.lerp(partialTick, player.yo, player.getY());
            double lz = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
            double dx = lx - player.getX(), dy = ly - player.getY(), dz = lz - player.getZ();
            AABB bb = player.getBoundingBox();
            float a = TeammatesEspRenderer.alpha(color);
            float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
            if (filled) {
                FILLS.add(bb.minX + dx, bb.minY + dy, bb.minZ + dz, bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz,
                        r, g, b, a * 0.35f);
            }
            LINES.add(bb.minX + dx, bb.minY + dy, bb.minZ + dz, bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz, r, g, b, a);

            double ddx = lx - selfPos.x, ddy = ly - selfPos.y, ddz = lz - selfPos.z;
            String label = labelText(cfg, player.getId(), player.getGameProfile().name(),
                    Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz));
            if (label != null) {
                if (labels == LABEL_TEXT.length) {
                    LABEL_TEXT = java.util.Arrays.copyOf(LABEL_TEXT, labels * 2);
                    LABEL_POS = java.util.Arrays.copyOf(LABEL_POS, labels * 2 * 4);
                }
                LABEL_TEXT[labels] = label;
                LABEL_POS[labels * 4] = lx;
                LABEL_POS[labels * 4 + 1] = ly + player.getBbHeight() + 0.5;
                LABEL_POS[labels * 4 + 2] = lz;
                LABEL_POS[labels * 4 + 3] = 0xFF000000 | (color & 0xFFFFFF);
                labels++;
            }
        }
        FILLS.drawFilled(context, TeammatesEspRenderer.fillType(throughWalls));
        LINES.drawOutlines(context, TeammatesEspRenderer.lineType(throughWalls), lineWidth);
        for (int i = 0; i < labels; i++) {
            renderLabel(context, LABEL_POS[i * 4], LABEL_POS[i * 4 + 1], LABEL_POS[i * 4 + 2], LABEL_TEXT[i],
                    (int) LABEL_POS[i * 4 + 3]);
            LABEL_TEXT[i] = null;
        }
    }

    /** Render-thread scratch for {@link #render}. */
    private static final com.killer560.hub.util.BoxBatch FILLS = new com.killer560.hub.util.BoxBatch();
    private static final com.killer560.hub.util.BoxBatch LINES = new com.killer560.hub.util.BoxBatch();
    private static String[] LABEL_TEXT = new String[8];
    private static double[] LABEL_POS = new double[8 * 4];

    /** One label per teammate entity id, rebuilt only when what it says changes (render thread). */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Label> LABELS =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    private record Label(String name, int mode, long metres, String text) {
    }

    /** {@code F7SpotsRenderer.labelText}: name, name + distance, or distance alone; null when neither is on. */
    private static String labelText(TeammatesConfig cfg, int id, String name, double distance) {
        boolean showName = cfg.isShowName();
        boolean showDistance = cfg.isShowDistance();
        if (!showName && !showDistance) {
            return null;
        }
        if (!showDistance) {
            return name;
        }
        // Math.round is String.format("%.0f")'s HALF_UP for a distance (never negative), without the Formatter.
        long metres = Math.round(distance);
        int mode = showName ? 1 : 0;
        Label cached = LABELS.get(id);
        if (cached != null && cached.metres() == metres && cached.mode() == mode && cached.name().equals(name)) {
            return cached.text();
        }
        if (LABELS.size() > 64) {
            LABELS.clear(); // ids of players long gone; the party is never this big
        }
        String d = metres + "m";
        String text = showName ? name + " §7(" + d + ")" : d;
        LABELS.put(id, new Label(name, mode, metres, text));
        return text;
    }

    /** {@code F7SpotsRenderer.renderLabel} - the same billboard transform Thorn's stun spots use. */
    private static void renderLabel(LevelRenderContext context, double x, double y, double z,
                                    String text, int color) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(context);
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(McRender.cameraRotation(context));
            poseStack.scale(s, -s, s);
            McRender.drawText(context, font, text, -font.width(text) / 2f, -font.lineHeight / 2f, color, false, poseStack, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }
}
