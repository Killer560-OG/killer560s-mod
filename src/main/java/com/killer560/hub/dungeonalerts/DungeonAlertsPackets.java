package com.killer560.hub.dungeonalerts;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

/** Public bridge from {@code DungeonAlertsPacketMixin} (main thread) into the package-private features.
 *  Each call is guarded so a feature bug can never break vanilla packet handling. */
public final class DungeonAlertsPackets {

    private DungeonAlertsPackets() {
    }

    public static void onInitializeBorder(ClientboundInitializeBorderPacket packet) {
        guard("border", ShadowAssassinAlert::onInitializeBorder);
    }

    public static void onSound(ClientboundSoundPacket packet) {
        guard("sound/secret", () -> SecretSound.onSoundPacket(packet));
        guard("sound/springboots", () -> SpringBootsOverlay.onSoundPacket(packet));
        guard("sound/ragnarock", () -> RagnarockAlert.onSoundPacket(packet));
    }

    public static void onTakeItem(ClientboundTakeItemEntityPacket packet, ClientLevel level) {
        guard("takeItem", () -> SecretSound.onTakeItem(packet, level));
    }

    public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, ClientLevel level) {
        guard("removeEntities", () -> SecretSound.onRemoveEntities(packet, level));
    }

    public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
        guard("blockUpdate", () -> TerracottaTimer.onBlockChange(packet.getPos(), packet.getBlockState()));
    }

    public static void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        if (!DungeonAlertsConfig.getInstance().terracottaEnabled) {
            return;
        }
        guard("sectionUpdate", () -> packet.runUpdates(TerracottaTimer::onBlockChange));
    }

    private static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            DungeonAlertsFeature.LOGGER.error("[DungeonAlerts] {} handler threw", what, e);
        }
    }
}
