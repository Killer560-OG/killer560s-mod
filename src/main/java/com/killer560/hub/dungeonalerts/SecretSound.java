package com.killer560.hub.dungeonalerts;

import com.killer560.hub.compat.McPackets;
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

    /** Whether an item with this (formatting-stripped) name is one of Noamm's dungeon secret item drops. */
    public static boolean isDungeonItemDrop(String name) {
        return name != null && DUNGEON_ITEM_DROPS.contains(name);
    }

    private static long lastChestMs = System.currentTimeMillis();
    private static BlockPos lastInteractPos = null;
    private static long lastInteractMs = 0;

    private SecretSound() {
    }

    /** A chest's sound waits for proof it was not locked (killer560, 2026-11-01: "Make sure the secret sound doesnt play
     *  on a locked chest"): the chest's menu opening plays it at once, Hypixel's whole "That chest is locked!" line drops
     *  it, and with neither it plays after {@link #CHEST_CONFIRM_MS} (a round trip on any sane ping). */
    static final long CHEST_CONFIRM_MS = 500L;
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-secretsound");

    /** The wait for the locked line: twice the connection's round trip plus 50 ms, between 150 ms and {@link #CHEST_CONFIRM_MS}
     *  (killer560, 2026-11-09: "extremely delayed, 1-2 seconds" - the fixed 500 ms plus a slow menu; a locked chest still
     *  stays silent, 2026-11-01). */
    private static long confirmMs(Minecraft client) {
        var conn = client.getConnection();
        var info = conn == null || client.player == null ? null : conn.getPlayerInfo(client.player.getUUID());
        long rtt = info == null ? 100 : info.getLatency();
        return Math.max(150L, Math.min(CHEST_CONFIRM_MS, rtt * 2 + 50));
    }
    private static final String LOCKED_LINE = "That chest is locked!";
    /** One entry per chest click awaiting its proof (a second click inside the wait used to overwrite the first: one sound for two). */
    private static final java.util.ArrayDeque<Long> pendingChests = new java.util.ArrayDeque<>();
    private static int chestSoundsPlayed = 0;
    private static int lockedChestSoundsDropped = 0;

    static void register() {
        com.killer560.hub.util.ChatObserver.subscribe(message -> {
            if (!pendingChests.isEmpty()
                    && LOCKED_LINE.equals(com.killer560.hub.util.ChatObserver.strip(message).trim())) {
                long waited = System.currentTimeMillis() - pendingChests.pollFirst(); // Hypixel answers clicks in order: the oldest one was the locked one
                LOGGER.info("[SecretSound] locked chest, no sound ({} ms after the click)", waited);
                lockedChestSoundsDropped++;
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player && hitResult != null) {
                onInteract(hitResult.getBlockPos());
            }
            return InteractionResult.PASS;
        });
    }

    /** A block click as it LEAVES the client (RightClickSendMixin): counts the clicks UseBlockCallback never saw because a
     *  listener ahead of ours answered non-PASS (killer560, 2026-11-08: "the secret sounds still dont play every time i click
     *  a chest"). The same click seen by both is one: {@link #onInteract} drops a repeat of the position within 50 ms. */
    public static void onUseSent(BlockPos pos) {
        onInteract(pos);
    }

    private static void onInteract(BlockPos pos) {
        if (!detecting() || !DungeonState.isInDungeon() || !com.killer560.hub.util.SkyblockGate.allows()) {
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
            if (com.killer560.hub.secrets.DummyLevers.isDummy(pos)) {
                return; // a dummy lever is no secret (secrets/DummyLevers)
            }
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
        if (!detecting() || !DungeonState.isInDungeon() || DungeonAlertsFeature.inBoss()) {
            return;
        }
        if (packet.getSound().value() != SoundEvents.BAT_DEATH) {
            return;
        }
        onSecret(SecretType.BAT, BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()));
    }

    static void onTakeItem(ClientboundTakeItemEntityPacket packet, ClientLevel level) {
        if (!detecting() || level == null || !DungeonState.isInDungeon()) {
            return;
        }
        // Only the local player's own pickup of a secret item: a teammate's pickup nearby is no secret of ours.
        if (Minecraft.getInstance().player != null && packet.getPlayerId() == Minecraft.getInstance().player.getId()) {
            checkItemEntity(level.getEntity(packet.getItemId()));
        }
    }

    static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, ClientLevel level) {
        if (!detecting() || level == null || !DungeonState.isInDungeon()) {
            return;
        }
        McPackets.removedEntityIds(packet).forEach(id -> checkItemEntity(level.getEntity(id)));
    }

    private static void checkItemEntity(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (!(entity instanceof ItemEntity item) || client.player == null) {
            return;
        }
        if (!DungeonState.isInDungeon() || DungeonAlertsFeature.inBoss()) {
            return;
        }
        if (client.player.distanceToSqr(item) > 36) {
            return; // cheap test first: most removed items are far away, and the name below builds a string
        }
        String name = ChatFormatting.stripFormatting(item.getItem().getHoverName().getString());
        if (name == null || !DUNGEON_ITEM_DROPS.contains(name)) {
            return;
        }
        LOGGER.info("[SecretSound] secret item: " + name);
        onSecret(SecretType.ITEM, item.blockPosition());
    }

    /** The detections run for Secret Sound or for Secret Clicked's boxes. */
    private static boolean detecting() {
        return DungeonAlertsConfig.getInstance().secretSoundEnabled || SecretClicked.wanted();
    }

    private static void onSecret(SecretType type, BlockPos pos) {
        SecretClicked.onSecret(pos);
        if (!DungeonAlertsConfig.getInstance().secretSoundEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (type == SecretType.ITEM && now - lastChestMs < 2000) {
            return;
        }
        if (type == SecretType.CHEST) {
            lastChestMs = now;
            if (pendingChests.size() >= 16) pendingChests.pollFirst();
            pendingChests.addLast(now); // played by tick() once the chest is known not to be locked
            return;
        }
        play();
    }

    /** Plays a held chest sound once the chest's menu is up or {@link #CHEST_CONFIRM_MS} passed with no locked line. */
    static void tick(Minecraft client) {
        if (pendingChests.isEmpty()) {
            return;
        }
        boolean opened = com.killer560.hub.compat.McCompat.screen(client)
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
        long now = System.currentTimeMillis();
        long wait = confirmMs(client);
        while (!pendingChests.isEmpty() && (opened || now - pendingChests.peekFirst() >= wait)) {
            long waited = now - pendingChests.pollFirst();
            LOGGER.info("[SecretSound] chest sound after {} ms ({}, window {} ms)", waited, opened ? "menu opened" : "no locked line", wait);
            chestSoundsPlayed++;
            play();
        }
    }

    /** For the testkit: {chest sounds played, chest sounds dropped because the chest was locked}. */
    public static int[] chestSoundCounts() {
        return new int[]{chestSoundsPlayed, lockedChestSoundsDropped};
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
