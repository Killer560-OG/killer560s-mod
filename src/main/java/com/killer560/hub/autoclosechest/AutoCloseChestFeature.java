package com.killer560.hub.autoclosechest;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * "Auto close chest" - killer560's request. Real reference: QUOI's own confirmed, compiling
 * {@code AutoCloseChest.kt} (Secrets group, credited "Kyleen" in that source) - a real Dungeon Secrets
 * QoL feature: a real Hypixel secret reward chest opens as a plain vanilla chest GUI titled exactly
 * "Chest", "Large Chest", or "Trapped Chest" (a fixed real menu type in {@link #SECRET_CHEST_MENU_TYPES}
 * and title in {@link #SECRET_CHEST_TITLES}, both ported directly from that source) - this closes it
 * before the player ever sees it, by intercepting the real {@link ClientboundOpenScreenPacket} at the
 * mixin layer and canceling it outright (see {@code AutoCloseChestMixin}) rather than opening the screen
 * and closing it again a tick later, which would still show a visible flash.
 * <p>
 * Gated to real dungeons only ({@link DungeonState#isInDungeon()}) - same "don't touch anything outside
 * a dungeon" safety default this mod already applies to Full Block/other secret-adjacent features,
 * since a real vanilla "Chest"/"Large Chest"/"Trapped Chest" title is common enough outside dungeons
 * (a player's own storage, a friend's island) that this needs a real scope guard to avoid closing chests
 * having nothing to do with dungeon secrets.
 */
public final class AutoCloseChestFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoclosechest");

    private static final Set<String> SECRET_CHEST_TITLES = Set.of("Chest", "Large Chest", "Trapped Chest");
    private static final Set<MenuType<?>> SECRET_CHEST_MENU_TYPES = Set.of(
            MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
            MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
            MenuType.GENERIC_3x3
    );

    private AutoCloseChestFeature() {
    }

    /** Called from {@code AutoCloseChestMixin} at the HEAD of {@code ClientPacketListener#handleOpenScreen}
     *  - a real screen-opening packet the game hasn't acted on yet. @return true if the mixin should
     *  cancel the vanilla handler entirely (the screen never opens). */
    public static boolean shouldAutoClose(ClientboundOpenScreenPacket packet) {
        AutoCloseChestConfig cfg = AutoCloseChestConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return false;
        }
        if (!SECRET_CHEST_MENU_TYPES.contains(packet.getType())) {
            return false;
        }
        String title = extractPlainTitle(packet.getTitle());
        return SECRET_CHEST_TITLES.contains(title);
    }

    /** Sends the real close-container packet back to the server, matching what vanilla would send if the
     *  player had opened then immediately closed the chest themselves - the server never even needs to
     *  know the client refused to render the screen. */
    public static void autoClose(ClientboundOpenScreenPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.player.connection.send(new ServerboundContainerClosePacket(packet.getContainerId()));
        LOGGER.info("[AutoCloseChest] Auto-closed a real secret chest (container {}).", packet.getContainerId());
    }

    private static String extractPlainTitle(Component title) {
        return title.getString().trim();
    }
}
