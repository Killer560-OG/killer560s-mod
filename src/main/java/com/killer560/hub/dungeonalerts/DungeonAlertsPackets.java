package com.killer560.hub.dungeonalerts;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Public bridge from {@code DungeonAlertsPacketMixin} (main thread) into the package-private features.
 *  Each call is guarded so a feature bug can never break vanilla packet handling.
 *  <p>The handlers are passed as non-capturing method references with their arguments, not as capturing lambdas:
 *  these run for every sound and block packet in every world, and four capturing lambdas per sound packet were four
 *  objects per packet (mixin FPS pass, 2026-10-15). What runs, in which order and inside which guard, is unchanged. */
public final class DungeonAlertsPackets {

    private DungeonAlertsPackets() {
    }

    public static void onInitializeBorder(ClientboundInitializeBorderPacket packet) {
        guard("border", ShadowAssassinAlert::onInitializeBorder);
    }

    /** True to cancel this sound packet (Mute Chest/Lever Sounds). */
    public static boolean muteSound(ClientboundSoundPacket packet) {
        try {
            return SecretClicked.shouldMute(packet);
        } catch (RuntimeException e) {
            return false; // never let a sound packet throw on the packet path
        }
    }

    public static void onSound(ClientboundSoundPacket packet) {
        guard("sound/secret", SecretSound::onSoundPacket, packet);
        guard("sound/springboots", SpringBootsOverlay::onSoundPacket, packet);
        // Rag Axe lives in com.killer560.hub.ragaxe now; it reuses this one sound-packet bridge
        // rather than adding a second mixin for the same packet.
        guard("sound/ragaxe", com.killer560.hub.ragaxe.RagAxeFeature::onSoundPacket, packet);
        guard("sound/abilitycooldown", com.killer560.hub.abilitycooldown.AbilityCooldownFeature::onSoundPacket, packet);
    }

    public static void onTakeItem(ClientboundTakeItemEntityPacket packet, ClientLevel level) {
        guard("takeItem", SecretSound::onTakeItem, packet, level);
        // Secret Waypoints rides the same hook: an item picked up next to an ITEM waypoint takes that waypoint down.
        guard("takeItem/waypoints", com.killer560.hub.secretwaypoints.SecretWaypointsFeature::onItemTaken, packet, level);
    }

    public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, ClientLevel level) {
        guard("removeEntities", SecretSound::onRemoveEntities, packet, level);
    }

    public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
        // Only Terracotta Timer and Dimensional Slash read block updates: with both off, skip the gate and guard on every block packet.
        if (!blockWatchers()) {
            return;
        }
        guard("blockUpdate", DungeonAlertsPackets::terracottaBlock, packet);
    }

    public static void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        if (!blockWatchers() || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        guard("sectionUpdate", DungeonAlertsPackets::terracottaSection, packet);
    }

    private static boolean blockWatchers() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        return cfg.terracottaEnabled || cfg.dimensionalSlashAlert;
    }

    private static void terracottaBlock(ClientboundBlockUpdatePacket packet) {
        blockChange(packet.getPos(), packet.getBlockState());
    }

    private static void terracottaSection(ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates(DungeonAlertsPackets::blockChange);
    }

    private static void blockChange(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        TerracottaTimer.onBlockChange(pos, state);
        if (DungeonAlertsConfig.getInstance().dimensionalSlashAlert) {
            WitherKingAlerts.onBlockChange(pos, state);
        }
    }

    private static void guard(String what, Runnable action) {
        // Skyblock Only: every Dungeon Alerts packet reaction (Shadow Assassin, Secret Sound, Spring Boots, Rag Axe,
        // Terracotta) goes through here.
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            DungeonAlertsFeature.LOGGER.error("[DungeonAlerts] {} handler threw", what, e);
        }
    }

    private static <T> void guard(String what, Consumer<T> action, T arg) {
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        try {
            action.accept(arg);
        } catch (RuntimeException e) {
            DungeonAlertsFeature.LOGGER.error("[DungeonAlerts] {} handler threw", what, e);
        }
    }

    private static <A, B> void guard(String what, BiConsumer<A, B> action, A a, B b) {
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        try {
            action.accept(a, b);
        } catch (RuntimeException e) {
            DungeonAlertsFeature.LOGGER.error("[DungeonAlerts] {} handler threw", what, e);
        }
    }
}
