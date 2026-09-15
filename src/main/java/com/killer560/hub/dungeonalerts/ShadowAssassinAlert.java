package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.sounds.SoundEvents;

/**
 * Shadow Assassin Alert - ported from QUOI's {@code module/impl/dungeon/ShadowAssassinAlert.kt}: Hypixel sends a
 * {@code ClientboundInitializeBorderPacket} (the red world-border flash) when a Shadow Assassin teleports behind
 * you. QUOI (area = Dungeon) fires on every such packet except in the F2/F3 boss fights, showing an empty title
 * with subtitle "§aShadow Assassin!", stay 35 ticks, no fades, and playing {@code BLAZE_HURT} (vol 1, pitch 1 -
 * QUOI's {@code setTitle} defaults). Party chat is this mod's own optional extra (off by default).
 */
final class ShadowAssassinAlert {

    private ShadowAssassinAlert() {
    }

    static void register() {
        // Packet hook lives in DungeonAlertsPacketMixin (handleInitializeBorder).
    }

    /** Called on the main thread for every ClientboundInitializeBorderPacket. */
    static void onInitializeBorder() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.shadowAssassinEnabled || !DungeonState.isInDungeon()) {
            return;
        }
        int floor = DungeonAlertsFeature.floorNumber();
        if ((floor == 2 || floor == 3) && DungeonAlertsFeature.inBoss()) {
            DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Border packet ignored (F{} boss)", floor);
            return;
        }
        DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Shadow Assassin alert (border packet, floor={})", DungeonState.getFloor());
        DungeonAlertsFeature.showTitle("", "§aShadow Assassin!", 0, 35, 0);
        DungeonAlertsFeature.playSound(SoundEvents.BLAZE_HURT, 1.0f, 1.0f);
        if (cfg.shadowAssassinPartyChat) {
            DungeonAlertsFeature.sendPartyChat("Shadow Assassin!");
        }
    }
}
