package com.killer560.hub.mobskin;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.supporters.SupportersFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Play as Mob" on screen. A player who plays as a mob is drawn as that mob: when {@code EntityRenderDispatcher}
 * has extracted a player's render state ({@code MobSkinDispatcherMixin}, at the RETURN of {@code extractEntity}), this
 * extracts the chosen mob's state from a private stand-in entity of that type and hands THAT to the level renderer
 * instead, with everything that makes it look like the player copied across: position, body and head yaw, pitch,
 * walk animation, age, light, outline (glow), invisibility, hurt flash, fire, crouch/swim and arm swing, the player's
 * nametag text, his shadow, and his size ({@code state.scale}, which already carries My Size / Others' Size and a
 * supporter's shared size). Your own Width/Height/Thickness stretch goes on your mob too ({@link MobSkinShape}).
 * <p>
 * Render state only. The stand-in is never added to the level, never ticked and never moved: it exists only so the
 * mob's own renderer has an entity of its type to read its looks from, and every position in the result is copied
 * from the player's own state. The real player entity, its hitbox, collision and everything the mod's features read
 * stay a Player - only the picture changes, on this client alone. Nothing here sends anything.
 * <p>
 * Who: you, with your own choice, in every view (F5 included); another player, with the mob he shares as a linked
 * supporter, while "Show Others' Mobs" is on - but never inside a dungeon, where a teammate drawn as a zombie would be
 * mistaken for an enemy (killer560's call to make; the default for now). Hypixel's NPC players (version-2 UUIDs) never.
 * A mob whose renderer throws once is marked broken for the session and draws the normal player, never a crash.
 */
public final class MobSkinRender {

    private static final Logger LOGGER = ModLog.get("killer560smod-mobskin");
    private static final int MAX_STAND_INS = 64;

    private record StandIn(EntityType<?> type, Level level, LivingEntity entity) {
    }

    private static final Map<UUID, StandIn> STAND_INS = new HashMap<>();
    private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();
    private static int nextStandInId = -1_000_000;

    // Test hooks (read by the testkit): how many player states were swapped, and the last one.
    private static volatile long swaps;
    private static volatile String lastSwap = null;

    private MobSkinRender() {
    }

    /** The mob {@code entity} is drawn as right now (an allowed id), or null for the normal player. */
    public static String mobFor(Entity entity) {
        if (!(entity instanceof AbstractClientPlayer player)) {
            return null;
        }
        String id;
        if (player == Minecraft.getInstance().player) {
            id = SupportersConfig.getInstance().getActiveOwnMob();
        } else {
            UUID uuid = player.getUUID();
            if (uuid == null || uuid.version() == 2 || !SupportersConfig.getInstance().isShowOthersMobs()
                    || DungeonState.isInDungeon()) {
                return null;
            }
            id = SupportersFeature.mobFor(uuid);
        }
        id = MobSkins.sanitize(id);
        return id == null || BROKEN.contains(id) ? null : id;
    }

    /**
     * {@code playerState} as the level renderer should draw it: the same object when {@code entity} is not a player
     * playing as a mob, else the mob's state dressed as the player. Never throws.
     */
    public static EntityRenderState swap(EntityRenderDispatcher dispatcher, Entity entity, EntityRenderState playerState,
                                         float partialTick) {
        if (!(playerState instanceof LivingEntityRenderState ps) || !(entity instanceof AbstractClientPlayer)) {
            return playerState;
        }
        String id = null;
        try {
            id = mobFor(entity);
            if (id == null) {
                return playerState;
            }
            LivingEntity standIn = standInFor(entity.getUUID(), id, entity.level());
            if (standIn == null) {
                return playerState;
            }
            EntityRenderer<? super LivingEntity, ?> renderer = dispatcher.getRenderer(standIn);
            EntityRenderState made = renderer.createRenderState(standIn, partialTick);
            if (!(made instanceof LivingEntityRenderState ms)) {
                markBroken(id, "its render state is not a living one");
                return playerState;
            }
            boolean local = entity == Minecraft.getInstance().player;
            dress(ps, ms, local);
            swaps++;
            lastSwap = entity.getUUID() + " " + id;
            return ms;
        } catch (Throwable t) {
            if (id != null) {
                markBroken(id, t.toString());
            }
            return playerState;
        }
    }

    private static void markBroken(String id, String why) {
        if (BROKEN.add(id)) {
            LOGGER.error("[Play as Mob] {} cannot be drawn, showing the player instead: {}", id, why);
        }
    }

    /** One stand-in per player, remade when his mob or the level changes. */
    private static LivingEntity standInFor(UUID owner, String id, Level level) {
        EntityType<?> type = MobSkins.type(id);
        if (type == null || level == null) {
            return null;
        }
        StandIn s = STAND_INS.get(owner);
        if (s != null && s.type() == type && s.level() == level) {
            return s.entity();
        }
        if (STAND_INS.size() >= MAX_STAND_INS) {
            STAND_INS.clear();
        }
        Entity made = type.create(level, EntitySpawnReason.LOAD);
        if (!(made instanceof LivingEntity living)) {
            markBroken(id, "not a living entity");
            return null;
        }
        // 26.2 throws "Tried to access entity ID before ID assignment" from getId() on an entity never given one, and
        // every renderer reads it (testkit 1018-mob-all marked all 78 mobs broken on 26.2 before this). Negative ids
        // never meet a server's, and the stand-in is never in the level to be looked up by it.
        living.setId(nextStandInId--);
        STAND_INS.put(owner, new StandIn(type, level, living));
        return living;
    }

    /** Copies onto the mob's state everything that makes it move and look like the player. */
    private static void dress(LivingEntityRenderState ps, LivingEntityRenderState ms, boolean local) {
        ms.x = ps.x;
        ms.y = ps.y;
        ms.z = ps.z;
        ms.ageInTicks = ps.ageInTicks;
        ms.distanceToCameraSq = ps.distanceToCameraSq;
        ms.isInvisible = ps.isInvisible;
        ms.isDiscrete = ps.isDiscrete;
        ms.displayFireAnimation = ps.displayFireAnimation;
        ms.lightCoords = ps.lightCoords;
        ms.outlineColor = ps.outlineColor;
        ms.nameTag = ps.nameTag;
        ms.scoreText = ps.scoreText;
        ms.shadowRadius = ps.shadowRadius;
        ms.shadowPieces.clear();
        ms.shadowPieces.addAll(ps.shadowPieces);

        ms.bodyRot = ps.bodyRot;
        ms.yRot = ps.yRot;
        ms.xRot = ps.xRot;
        ms.deathTime = ps.deathTime;
        ms.walkAnimationPos = ps.walkAnimationPos;
        ms.walkAnimationSpeed = ps.walkAnimationSpeed;
        ms.wornHeadAnimationPos = ps.wornHeadAnimationPos;
        ms.scale = ps.scale;
        ms.ticksSinceKineticHitFeedback = ps.ticksSinceKineticHitFeedback;
        ms.isUpsideDown = ps.isUpsideDown;
        ms.isFullyFrozen = ps.isFullyFrozen;
        ms.isInWater = ps.isInWater;
        ms.isAutoSpinAttack = ps.isAutoSpinAttack;
        ms.hasRedOverlay = ps.hasRedOverlay;
        ms.isInvisibleToPlayer = ps.isInvisibleToPlayer;
        ms.bedOrientation = ps.bedOrientation;
        ms.pose = ps.pose;

        if (ms instanceof HumanoidRenderState mh && ps instanceof HumanoidRenderState ph) {
            mh.isCrouching = ph.isCrouching;
            mh.swimAmount = ph.swimAmount;
            mh.isVisuallySwimming = ph.isVisuallySwimming;
            mh.isFallFlying = ph.isFallFlying;
            mh.isPassenger = ph.isPassenger;
        }
        if (ms instanceof ArmedEntityRenderState ma && ps instanceof ArmedEntityRenderState pa) {
            ma.attackTime = pa.attackTime;
            ma.attackArm = pa.attackArm;
            ma.mainArm = pa.mainArm;
        }

        // The nametag sits over the MOB's head (its own attachment point), moved with the size exactly as
        // SupportersScaleMixin moves a player's: by the size, plus your own Height for you, and the tag's 0.5-block lift
        // grown with it.
        float factor = ps.scale;
        float tagY = factor * (local ? SupportersConfig.getInstance().getModelHeight() : 1.0f);
        Vec3 att = ms.nameTagAttachment != null ? ms.nameTagAttachment : ps.nameTagAttachment;
        if (att != null && (factor != 1.0f || tagY != 1.0f)) {
            att = new Vec3(att.x * factor, att.y * tagY + 0.25 * (tagY - 1.0f), att.z * factor);
        }
        ms.nameTagAttachment = att;

        if (local && ms instanceof MobSkinShape shape) {
            SupportersConfig cfg = SupportersConfig.getInstance();
            float w = cfg.getModelWidth();
            float h = cfg.getModelHeight();
            float t = cfg.getModelThickness();
            if (w != 1.0f || h != 1.0f || t != 1.0f) {
                shape.killer560smod$setMobShape(new float[]{w, h, t});
            }
        }
    }

    /** Called by {@code MobSkinShapeMixin} right after a living renderer's own {@code scale}. */
    public static void applyShape(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack) {
        if (state instanceof MobSkinShape shape) {
            float[] s = shape.killer560smod$mobShape();
            if (s != null) {
                poseStack.scale(s[0], s[1], s[2]);
            }
        }
    }

    /** Mobs marked broken this session (testkit). */
    public static Set<String> broken() {
        return Set.copyOf(BROKEN);
    }

    public static long swaps() {
        return swaps;
    }

    public static String lastSwap() {
        return lastSwap;
    }
}
