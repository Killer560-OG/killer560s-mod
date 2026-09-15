package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Secret Sound - ported from NoammAddons (26.1.2 upstream) {@code features/impl/dungeon/Secrets.kt} ("Secret
 * Sound" section) + the {@code DungeonEvent.SecretEvent} sources in {@code event/EventDispatcher.kt} and
 * {@code utils/dungeons/DungeonUtils.kt}:
 * <ul>
 * <li>BAT: a {@code BAT_DEATH} sound packet while in a dungeon and not in boss.
 * <li>ITEM: {@code ClientboundTakeItemEntityPacket} / {@code ClientboundRemoveEntitiesPacket} for an item entity
 * named in Noamm's {@code dungeonItemDrops} list within 6 blocks (distSqr &lt;= 36), dungeon and not boss.
 * <li>CHEST / LEVER / WITHER_ESSENCE / REDSTONE_KEY: right-clicking a chest, trapped chest, lever, or a skull
 * whose owner UUID is one of Noamm's essence/key UUIDs, while in a dungeon. Noamm hooks the outgoing
 * {@code ServerboundUseItemOnPacket}; here Fabric's {@link UseBlockCallback}, which Fabric fires from its
 * client {@code MultiPlayerGameModeMixin#useItemOn} right before that packet is sent.
 * </ul>
 * Noamm's rules: an ITEM within 2000 ms of the last CHEST is skipped (the chest's own drop), and the sound is
 * played via its "Play Sound" action, which plays it 5 times at once. Default sound EXPERIENCE_ORB_PICKUP,
 * volume 0.5, pitch 1.0 (Noamm's {@code createSoundSettings} defaults).
 */
public final class SecretSound {

    public enum SoundChoice {
        EXPERIENCE_ORB("Experience Orb", () -> SoundEvents.EXPERIENCE_ORB_PICKUP),
        PLING("Note Pling", () -> SoundEvents.NOTE_BLOCK_PLING.value()),
        HARP("Note Harp", () -> SoundEvents.NOTE_BLOCK_HARP.value()),
        BELL("Note Bell", () -> SoundEvents.NOTE_BLOCK_BELL.value()),
        CHIME("Note Chime", () -> SoundEvents.NOTE_BLOCK_CHIME.value()),
        AMETHYST("Amethyst Chime", () -> SoundEvents.AMETHYST_BLOCK_CHIME),
        LEVEL_UP("Level Up", () -> SoundEvents.PLAYER_LEVELUP),
        ARROW_HIT("Arrow Hit", () -> SoundEvents.ARROW_HIT_PLAYER),
        ITEM_PICKUP("Item Pickup", () -> SoundEvents.ITEM_PICKUP),
        VILLAGER_YES("Villager Yes", () -> SoundEvents.VILLAGER_YES),
        ANVIL("Anvil Land", () -> SoundEvents.ANVIL_LAND),
        BLAZE_HURT("Blaze Hurt", () -> SoundEvents.BLAZE_HURT);

        public final String label;
        private final Supplier<SoundEvent> sound;

        SoundChoice(String label, Supplier<SoundEvent> sound) {
            this.label = label;
            this.sound = sound;
        }

        public SoundEvent sound() {
            return sound.get();
        }

        public static SoundChoice byName(String name) {
            for (SoundChoice c : values()) {
                if (c.name().equals(name)) {
                    return c;
                }
            }
            return EXPERIENCE_ORB;
        }
    }

    enum SecretType { CHEST, LEVER, WITHER_ESSENCE, REDSTONE_KEY, BAT, ITEM }

    private static final Set<String> WITHER_ESSENCE = Set.of("2865274b-3097-394e-8149-ec629c72d850", "e0f3e929-869e-3dca-9504-54c666ee6f23");
    private static final Set<String> REDSTONE_KEY = Set.of("fed95410-aba1-39df-9b95-1d4f361eb66e");
    private static final List<String> DUNGEON_ITEM_DROPS = List.of(
            "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion",
            "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion",
            "Healing 8 Splash Potion", "Decoy", "Inflatable Jerry", "Spirit Leap",
            "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key",
            "Treasure Talisman", "Revive Stone", "Architect's First Draft",
            "Secret Dye", "Candycomb");

    private static long lastChestMs = System.currentTimeMillis();
    private static BlockPos lastInteractPos = null;
    private static long lastInteractMs = 0;

    private SecretSound() {
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player && hitResult != null) {
                onInteract(hitResult.getBlockPos());
            }
            return InteractionResult.PASS;
        });
    }

    private static void onInteract(BlockPos pos) {
        if (!DungeonAlertsConfig.getInstance().secretSoundEnabled || !DungeonState.isInDungeon()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pos.equals(lastInteractPos) && now - lastInteractMs < 50) {
            return; // same click reported for both hands
        }
        lastInteractPos = pos.immutable();
        lastInteractMs = now;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Block block = level.getBlockState(pos).getBlock();
        SecretType type;
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            type = SecretType.CHEST;
        } else if (block == Blocks.LEVER) {
            type = SecretType.LEVER;
        } else if (block instanceof SkullBlock && level.getBlockEntity(pos) instanceof SkullBlockEntity skull
                && skull.getOwnerProfile() != null && skull.getOwnerProfile().partialProfile().id() != null) {
            String id = skull.getOwnerProfile().partialProfile().id().toString();
            if (WITHER_ESSENCE.contains(id)) {
                type = SecretType.WITHER_ESSENCE;
            } else if (REDSTONE_KEY.contains(id)) {
                type = SecretType.REDSTONE_KEY;
            } else {
                return;
            }
        } else {
            return;
        }
        onSecret(type, pos);
    }

    static void onSoundPacket(ClientboundSoundPacket packet) {
        if (!DungeonAlertsConfig.getInstance().secretSoundEnabled || !DungeonState.isInDungeon() || DungeonAlertsFeature.inBoss()) {
            return;
        }
        if (packet.getSound().value() != SoundEvents.BAT_DEATH) {
            return;
        }
        onSecret(SecretType.BAT, BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()));
    }

    static void onTakeItem(ClientboundTakeItemEntityPacket packet, ClientLevel level) {
        if (!DungeonAlertsConfig.getInstance().secretSoundEnabled || level == null) {
            return;
        }
        checkItemEntity(level.getEntity(packet.getItemId()));
    }

    static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, ClientLevel level) {
        if (!DungeonAlertsConfig.getInstance().secretSoundEnabled || level == null) {
            return;
        }
        packet.getEntityIds().forEach(id -> checkItemEntity(level.getEntity(id)));
    }

    private static void checkItemEntity(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (!(entity instanceof ItemEntity item) || client.player == null) {
            return;
        }
        if (!DungeonState.isInDungeon() || DungeonAlertsFeature.inBoss()) {
            return;
        }
        String name = ChatFormatting.stripFormatting(item.getItem().getHoverName().getString());
        if (name == null || !DUNGEON_ITEM_DROPS.contains(name)) {
            return;
        }
        if (client.player.distanceToSqr(item) > 36) {
            return;
        }
        onSecret(SecretType.ITEM, item.blockPosition());
    }

    private static void onSecret(SecretType type, BlockPos pos) {
        long now = System.currentTimeMillis();
        if (type == SecretType.ITEM && now - lastChestMs < 2000) {
            return;
        }
        if (type == SecretType.CHEST) {
            lastChestMs = now;
        }
        DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Secret {} at {} - playing sound", type, pos.toShortString());
        play();
    }

    /** Noamm's "Play Sound" action - the configured sound 5 times at once. Also used by the tab's test button. */
    public static void play() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        SoundEvent sound = SoundChoice.byName(cfg.secretSoundId).sound();
        for (int i = 0; i < 5; i++) {
            DungeonAlertsFeature.playSound(sound, cfg.secretSoundVolume, cfg.secretSoundPitch);
        }
    }
}
