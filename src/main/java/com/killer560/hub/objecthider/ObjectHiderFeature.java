package com.killer560.hub.objecthider;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.objecthider.mixin.AbstractArrowInGroundAccessor;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.HugeExplosionParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SmokeParticle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Object Hider pack - a bundle of independent, client-side render suppressors aimed at M7 P5 / F7 P3, where the
 * screen is saturated with healer fairies, power orbs, soulweaver skulls, archer-passive bone meal, stuck arrows,
 * dying-dragon corpses, damage splashes and particle spam. Every toggle defaults to OFF
 * ({@link ObjectHiderConfig}).
 *
 * <h2>Design rule: cancel at render level, never remove entities</h2>
 * The reference implementations this is ported from ({@code Devonian}) mostly call
 * {@code level.removeEntity(id, DISCARDED)} from a packet handler. This port deliberately does NOT: an entity that
 * is gone from the client world desyncs raycasts, interaction, this mod's own ESP/solver scans and Hypixel's own
 * hit registration. Instead every entity hider is a pure predicate evaluated from
 * {@code EntityRenderDispatcher.shouldRender} ({@link com.killer560.hub.objecthider.mixin.ObjectHiderRenderMixin}) -
 * javap-verified against the 26.1.2 merged jar: {@code LevelRenderer} calls
 * {@code shouldRender(Entity, Frustum, double, double, double)} once per entity per frame and, when it returns
 * false (and the entity isn't carrying the local player), skips extraction and submission entirely. The entity
 * itself stays in the world untouched. Particles are cancelled in {@code ParticleEngine.add}
 * (the single funnel - {@code createParticle} calls {@code add}, javap-verified) or, where a position/flag
 * predicate is needed, on the {@code ClientboundLevelParticlesPacket} handler.
 *
 * <h2>Per-hider sources and exact predicates</h2>
 * <ul>
 * <li><b>Healer Fairy</b> - Devonian {@code features/dungeons/HideFairy.kt}: an {@code ARMOR_STAND} whose
 *     {@code MAINHAND} item carries {@code DataComponents.PROFILE} with partial-profile id
 *     {@code 93c42dbb-15e2-3d18-8a89-770e440e97d2}.</li>
 * <li><b>Power Orbs</b> - Devonian {@code features/dungeons/HideHealerOrbs.kt}: an {@code ARMOR_STAND} whose
 *     {@code HEAD} item's ExtraAttributes id is one of {@code DUNGEON_BLUE_SUPPORT_ORB} /
 *     {@code DUNGEON_RED_SUPPORT_ORB} / {@code DUNGEON_GREEN_SUPPORT_ORB}, plus the orb's separate floating
 *     label stand whose name starts with {@code "ABILITY DAMAGE"} / {@code "DAMAGE"} / {@code "DEFENSE"}. The
 *     orb's own dust cloud is a {@code ClientboundLevelParticlesPacket} with
 *     {@code particle.type == ParticleTypes.DUST}, {@code count == 0}, {@code isOverrideLimiter()} and
 *     {@code alwaysShow()}, within 8 blocks on each axis of {@code (orb.x, orb.y + 2, orb.z)} - all four flags
 *     are Devonian's, verbatim. (SkyHanni {@code features/misc/LesserOrbHider.kt} does the same thing with a
 *     4-block radius and a skull-texture match instead of the item id.)</li>
 * <li><b>Keep Nearby Orbs</b> - NOT in any reference mod; this mod's own option for "keep your own orbs". There
 *     is no client-side way to tell who dropped an orb, so the closest useful meaning is "keep the orb I'm
 *     standing in", i.e. don't hide an orb (or its dust) within the configured radius of the player.</li>
 * <li><b>Soulweaver Skulls</b> - Devonian {@code features/dungeons/HideSoulweaverSkulls.kt}: an
 *     {@code ARMOR_STAND} whose {@code HEAD} item profile id is
 *     {@code 2134ab1c-7c78-30e1-8513-a6346c2344fd}.</li>
 * <li><b>Archer Passive</b> - Devonian {@code features/dungeons/HideArcherPassive.kt}: a dropped item entity
 *     whose stack is {@code Items.BONE_MEAL} ("bonemeal go spin").</li>
 * <li><b>Sheep</b> - Devonian {@code features/dungeons/HideSheeps.kt}: {@code entity instanceof Sheep}.</li>
 * <li><b>Wither Cloak Creepers</b> - Devonian {@code features/misc/hiders/HideCloakCreepers.kt}: a
 *     {@link Creeper} that {@code isInvisible() && isPowered() && health == 20f}.</li>
 * <li><b>Wither Shield Hearts</b> - Devonian {@code features/misc/hiders/HideHypeHearts.kt}: a
 *     {@code ClientboundLevelParticlesPacket} of {@code ParticleTypes.HEART} with {@code count == 3},
 *     {@code alwaysShow()} and {@code isOverrideLimiter()}.</li>
 * <li><b>Grounded Arrows</b> - Devonian {@code features/misc/hiders/HideGroundedArrows.kt}: an
 *     {@link AbstractArrow} whose (protected) {@code isInGround()} is true - reached through
 *     {@link AbstractArrowInGroundAccessor}, the same {@code @Invoker} trick Devonian's
 *     {@code AbstractArrowAccessor} uses.</li>
 * <li><b>Blindness</b> - Devonian {@code features/misc/hiders/DisableBlindness.kt}: cancel
 *     {@link ClientboundUpdateMobEffectPacket} when {@code entityId == player.id} and the effect is
 *     {@code MobEffects.BLINDNESS}. This is the one hider that is not render-level (there is no render hook for
 *     "pretend I don't have this effect"); it drops one client-side effect application for the local player only.</li>
 * <li><b>Death Animations</b> - Devonian {@code features/misc/hiders/NoDeathAnimation.kt}: a {@link LivingEntity}
 *     that is not an {@link EnderDragon} and is {@code !isAlive() || health <= 0}, except entities named
 *     {@code ^\w+ Livid$} (Livid's clones must stay visible). The optional "Hide Dead Nametags" half reproduces
 *     Devonian's id-offset trick: Hypixel's nametag stand for a dying mob is {@code mobId + 1}
 *     ({@code + 3} for Withermancers, whose wither skulls take the ids in between).</li>
 * <li><b>Block Break / Explosion / Smoke Particles</b> - Devonian {@code features/misc/hiders/RemoveParticles.kt}
 *     ({@code RemoveBlockBreakParticle}, {@code RemoveExplosionParticle}, {@code RemoveSmokeParticle}):
 *     {@code particle instanceof TerrainParticle / HugeExplosionParticle / SmokeParticle}.</li>
 * <li><b>Dying Dragons (P5)</b> - Devonian {@code features/dungeons/m7/HideDyingDragons.kt}:
 *     {@code entity instanceof EnderDragon && dragonDeathTime > 0}.</li>
 * <li><b>Wither King Model</b> - Devonian {@code features/dungeons/m7/HideWitherKing.kt}: on M7, an
 *     {@code ARMOR_STAND} with {@code y in 9.0..25.0} and {@code z <= 45.0} (the arena box the King's model
 *     stands are built from), plus {@code ParticleTypes.WITCH} packets in the same box. Devonian keys off the
 *     spawn packet's coordinates; this port reads the live entity position, which is equivalent for these
 *     static stands.</li>
 * <li><b>Boss Damage Splash</b> - SkyHanni {@code features/dungeon/DungeonBossHideDamageSplash.kt} +
 *     {@code features/combat/damageindicator/DamageIndicatorManager.isDamageSplash}: in the boss room, an
 *     {@link ArmorStand} with {@code tickCount <= 300}, a custom name, whose comma-stripped clean name fully
 *     matches {@code [✧✯]?(\d+[⚔+✧❤♞☄✷ﬗ✯]*)}.</li>
 * <li><b>Clean End</b> - SkyHanni {@code features/dungeon/DungeonCleanEnd.kt}: once the boss is dead, hide every
 *     entity except the local player, and cancel every particle. After the chests spawn, nameless armor stands
 *     and other players come back so the end-of-run screen and the chest room are usable again. Optional
 *     F3/M3 exception: keep guardians visible while sneaking. SkyHanni triggers on its damage indicator's final
 *     boss dropping to {@code <= 0.5} health; this mod has no damage-indicator subsystem, so the trigger here is
 *     the {@code ☠ Defeated <boss> in <time>} chat line (the same line
 *     {@code com.killer560.hub.splittimers.SplitTimersFeature} already ends its run on) and "chests spawned" is
 *     SkyHanni's own {@code dungeon.end.chests.spawned} pattern, the re-printed
 *     {@code (Master Mode )?The Catacombs - Floor X} header. SkyHanni additionally mutes note-block sounds until
 *     the chests spawn; that half is NOT ported (no sound-cancel hook exists in this repo yet).</li>
 * </ul>
 */
public final class ObjectHiderFeature {

    /** Devonian {@code HideFairy.kt}: the healer fairy's skull profile id. */
    private static final UUID FAIRY_SKULL = UUID.fromString("93c42dbb-15e2-3d18-8a89-770e440e97d2");
    /** Devonian {@code HideSoulweaverSkulls.kt}: the soulweaver-gloves skull profile id. */
    private static final UUID SOULWEAVER_SKULL = UUID.fromString("2134ab1c-7c78-30e1-8513-a6346c2344fd");

    /** Devonian {@code HideHealerOrbs.kt} - the three dungeon support orbs, by ExtraAttributes id. */
    private static final Set<String> ORB_ITEM_IDS = Set.of(
            "DUNGEON_BLUE_SUPPORT_ORB", "DUNGEON_RED_SUPPORT_ORB", "DUNGEON_GREEN_SUPPORT_ORB");
    /** Devonian {@code HideHealerOrbs.kt} - the orb's floating label stand, matched by name prefix. */
    private static final List<String> ORB_LABEL_PREFIXES = List.of("ABILITY DAMAGE", "DAMAGE", "DEFENSE");

    /** Devonian {@code NoDeathAnimation.kt} - Livid's clones keep rendering while "dead". */
    private static final Pattern LIVID_NAME = Pattern.compile("^\\w+ Livid$");
    /** SkyHanni {@code DamageIndicatorManager.damagePattern}, matched against the comma-stripped clean name. */
    private static final Pattern DAMAGE_SPLASH = Pattern.compile("[✧✯]?(\\d+[⚔+✧❤♞☄✷ﬗ✯]*)");

    /** SplitTimers' own end-of-run line: "  ☠ Defeated Necron in 12m 3s" (optionally "(NEW RECORD!)"). */
    private static final Pattern BOSS_DEFEATED = Pattern.compile("^\\s*☠ Defeated .+ in .+$");
    /** SkyHanni {@code dungeon.end.chests.spawned}, formatting-stripped. */
    private static final Pattern CHESTS_SPAWNED = Pattern.compile("^\\s*(?:Master Mode )?The Catacombs - Floor .*$");

    /** Devonian {@code NoDeathAnimation.kt}: nametag-stand ids derived from a dying mob's id. */
    private static final Set<Integer> deadTags = ConcurrentHashMap.newKeySet();

    /** Live orb stand positions (x, y + 2, z), refreshed each client tick; read by the particle-packet hook. */
    private static volatile double[][] orbPositions = new double[0][];

    private static volatile boolean bossDone = false;
    private static volatile boolean chestsSpawned = false;
    private static Object lastLevel = null;

    private ObjectHiderFeature() {
    }

    public static void register() {
        // Load once here, on the client-init thread: shouldHideEntity()/shouldHideParticle() run on the render
        // thread and would otherwise be the first caller to lazily read the file.
        ObjectHiderConfig.getInstance();
        ChatObserver.subscribe(ObjectHiderFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    // -------------------------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------------------------

    private static void onChatMessage(net.minecraft.network.chat.Component message) {
        String text = ChatObserver.strip(message);
        if (text == null) {
            return;
        }
        if (!bossDone && BOSS_DEFEATED.matcher(text).matches()) {
            bossDone = true;
        } else if (bossDone && CHESTS_SPAWNED.matcher(text).matches()) {
            chestsSpawned = true;
        }
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            // Entity ids restart on every server/world switch, so nothing cached here may survive one.
            lastLevel = client.level;
            deadTags.clear();
            orbPositions = new double[0][];
            bossDone = false;
            chestsSpawned = false;
        }
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        if (client.level == null || !cfg.isHideHealerOrbs() || !DungeonState.isInDungeon()) {
            if (orbPositions.length != 0) {
                orbPositions = new double[0][];
            }
            return;
        }
        List<double[]> found = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand && isOrbStand(stand)) {
                // Devonian records the orb's particle origin as the stand position raised by 2 blocks.
                found.add(new double[] {stand.getX(), stand.getY() + 2.0, stand.getZ()});
            }
        }
        orbPositions = found.toArray(new double[0][]);
    }

    // -------------------------------------------------------------------------------------------
    // Entity render gate - called once per entity per frame from ObjectHiderRenderMixin
    // -------------------------------------------------------------------------------------------

    /** @return true to stop this entity rendering this frame. Never mutates or removes the entity. */
    public static boolean shouldHideEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (entity == client.player) {
            return false;
        }
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();

        if (cfg.isCleanEnd() && bossDone && inDungeon && cleanEndHides(entity, cfg, client)) {
            return true;
        }
        if (cfg.isHideGroundedArrows() && entity instanceof AbstractArrow arrow
                && ((AbstractArrowInGroundAccessor) arrow).killer560smod$isInGround()) {
            return true;
        }
        if (cfg.isHideCloakCreepers() && entity instanceof Creeper creeper
                && creeper.isInvisible() && creeper.isPowered() && creeper.getHealth() == 20.0f) {
            return true;
        }
        if (inDungeon) {
            if (cfg.isHideSheep() && entity instanceof Sheep) {
                return true;
            }
            if (cfg.isHideArcherPassive() && entity instanceof ItemEntity item
                    && item.getItem().getItem() == Items.BONE_MEAL) {
                return true;
            }
            if (cfg.isHideDyingDragons() && entity instanceof EnderDragon dragon && dragon.dragonDeathTime > 0) {
                return true;
            }
            if (entity instanceof ArmorStand stand && hidesArmorStand(stand, cfg)) {
                return true;
            }
        }
        return cfg.isHideDeathAnimations() && entity instanceof LivingEntity living && isDeadAndHidden(living, cfg);
    }

    private static boolean hidesArmorStand(ArmorStand stand, ObjectHiderConfig cfg) {
        if (cfg.isHideFairy() && hasSkullProfile(stand, EquipmentSlot.MAINHAND, FAIRY_SKULL)) {
            return true;
        }
        if (cfg.isHideSoulweaverSkulls() && hasSkullProfile(stand, EquipmentSlot.HEAD, SOULWEAVER_SKULL)) {
            return true;
        }
        if (cfg.isHideHealerOrbs() && isOrbStand(stand) && !isKeptOrb(stand, cfg)) {
            return true;
        }
        if (cfg.isHideWitherKing() && isWitherKingArea(stand.getY(), stand.getZ()) && isMasterSeven()) {
            return true;
        }
        return cfg.isHideBossDamageSplash() && LiveMapFeature.isInBoss() && isDamageSplash(stand);
    }

    /** SkyHanni {@code DungeonCleanEnd.onCheckRender}, minus its {@code lastBossId} guard (no damage indicator here). */
    private static boolean cleanEndHides(Entity entity, ObjectHiderConfig cfg, Minecraft client) {
        if (cfg.isCleanEndKeepGuardians() && entity instanceof Guardian && isFloorThree()
                && client.player != null && client.player.isShiftKeyDown()) {
            return false;
        }
        if (chestsSpawned && ((entity instanceof ArmorStand stand && !stand.hasCustomName())
                || entity instanceof RemotePlayer)) {
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------------------------
    // Particles
    // -------------------------------------------------------------------------------------------

    /** @return true to drop this particle before it is added to the engine (ParticleEngine.add HEAD). */
    public static boolean shouldHideParticle(Particle particle) {
        if (particle == null) {
            return false;
        }
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        if (cfg.isCleanEnd() && bossDone && DungeonState.isInDungeon()) {
            return true;
        }
        if (cfg.isHideBlockBreakParticles() && particle instanceof TerrainParticle) {
            return true;
        }
        if (cfg.isHideExplosionParticles() && particle instanceof HugeExplosionParticle) {
            return true;
        }
        return cfg.isHideSmokeParticles() && particle instanceof SmokeParticle;
    }

    /** @return true to drop a server particle packet whose flags/position identify it (orbs, hearts, King). */
    public static boolean shouldCancelParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (packet == null) {
            return false;
        }
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        Object type = packet.getParticle().getType();

        // Devonian HideHypeHearts.kt - wither-shield healing hearts.
        if (cfg.isHideHypeHearts() && type == ParticleTypes.HEART
                && packet.getCount() == 3 && packet.alwaysShow() && packet.isOverrideLimiter()) {
            return true;
        }
        if (!DungeonState.isInDungeon()) {
            return false;
        }
        // Devonian HideWitherKing.kt - the King's witch particles, inside the same arena box as his model.
        if (cfg.isHideWitherKing() && type == ParticleTypes.WITCH
                && isWitherKingArea(packet.getY(), packet.getZ()) && isMasterSeven()) {
            return true;
        }
        // Devonian HideHealerOrbs.kt - the orb's dust cloud, by flags + proximity to a tracked orb stand.
        if (cfg.isHideHealerOrbs() && type == ParticleTypes.DUST
                && packet.getCount() == 0 && packet.isOverrideLimiter() && packet.alwaysShow()) {
            double[][] orbs = orbPositions;
            for (double[] orb : orbs) {
                if (Math.abs(packet.getX() - orb[0]) < 8.0
                        && Math.abs(packet.getY() - orb[1]) < 8.0
                        && Math.abs(packet.getZ() - orb[2]) < 8.0) {
                    return !isKeptPosition(orb[0], orb[1] - 2.0, orb[2], cfg);
                }
            }
        }
        return false;
    }

    /** Devonian {@code DisableBlindness.kt} - drop the local player's blindness application. */
    public static boolean shouldCancelMobEffect(ClientboundUpdateMobEffectPacket packet) {
        if (packet == null || !ObjectHiderConfig.getInstance().isDisableBlindness()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client.player != null
                && packet.getEntityId() == client.player.getId()
                // Holder.is(Holder) is deprecated in 26.1.2; compare the resolved MobEffect instead.
                && packet.getEffect().value() == MobEffects.BLINDNESS.value();
    }

    // -------------------------------------------------------------------------------------------
    // Predicates
    // -------------------------------------------------------------------------------------------

    private static boolean hasSkullProfile(ArmorStand stand, EquipmentSlot slot, UUID id) {
        ItemStack stack = stand.getItemBySlot(slot);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        return profile != null && profile.partialProfile() != null && id.equals(profile.partialProfile().id());
    }

    private static boolean isOrbStand(ArmorStand stand) {
        String itemId = skyblockId(stand.getItemBySlot(EquipmentSlot.HEAD));
        if (itemId != null && ORB_ITEM_IDS.contains(itemId)) {
            return true;
        }
        if (!stand.hasCustomName()) {
            return false;
        }
        String name = ChatFormatting.stripFormatting(stand.getName().getString());
        if (name == null) {
            return false;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (String prefix : ORB_LABEL_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** "Keep Nearby Orbs": this mod's stand-in for "keep your own" - see the class doc. */
    private static boolean isKeptOrb(ArmorStand stand, ObjectHiderConfig cfg) {
        return isKeptPosition(stand.getX(), stand.getY(), stand.getZ(), cfg);
    }

    private static boolean isKeptPosition(double x, double y, double z, ObjectHiderConfig cfg) {
        if (!cfg.isKeepNearbyOrbs()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        double r = cfg.getKeepOrbRadius();
        return client.player.distanceToSqr(x, y, z) <= r * r;
    }

    /** SkyHanni {@code DamageIndicatorManager.isDamageSplash}. */
    private static boolean isDamageSplash(ArmorStand stand) {
        if (stand.tickCount > 300 || !stand.hasCustomName()) {
            return false;
        }
        String name = ChatFormatting.stripFormatting(stand.getName().getString());
        if (name == null) {
            return false;
        }
        return DAMAGE_SPLASH.matcher(name.replace(",", "")).matches();
    }

    /** Devonian {@code NoDeathAnimation.shouldHide}. */
    private static boolean isDeadAndHidden(LivingEntity entity, ObjectHiderConfig cfg) {
        if (entity instanceof EnderDragon) {
            return false;
        }
        if (cfg.isHideDeadNametags() && entity instanceof ArmorStand && deadTags.contains(entity.getId())) {
            return true;
        }
        if (entity.isAlive() && entity.getHealth() > 0.0f) {
            return false;
        }
        String name = entity.getName() == null ? null : entity.getName().getString();
        if (name != null && LIVID_NAME.matcher(name).matches()) {
            return false;
        }
        // Hypixel's nametag stand sits at mobId + 1; a Withermancer's two wither skulls take the ids between.
        int offset = entity instanceof WitherSkeleton && name != null && name.contains("Withermancer") ? 3 : 1;
        deadTags.add(entity.getId() + offset);
        return true;
    }

    /** Devonian {@code HideWitherKing.kt} - the arena box the King's armor stands / witch particles live in. */
    private static boolean isWitherKingArea(double y, double z) {
        return y >= 9.0 && y <= 25.0 && z <= 45.0;
    }

    private static boolean isMasterSeven() {
        return "M7".equals(DungeonState.getFloor());
    }

    /** SkyHanni's {@code DungeonApi.isOneOf("F3", "M3")} for the Clean End guardian exception. */
    private static boolean isFloorThree() {
        String floor = DungeonState.getFloor();
        return "F3".equals(floor) || "M3".equals(floor);
    }

    /** Hypixel Skyblock item id from CUSTOM_DATA "id" (same technique as {@code DungeonBreakerFeature}), or null. */
    private static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }
}
