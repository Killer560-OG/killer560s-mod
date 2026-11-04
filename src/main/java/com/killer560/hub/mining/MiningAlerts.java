package com.killer560.hub.mining;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.dungeonalerts.SecretSound;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The Mining category's alert pieces (staging-mining): a vanilla title and a local UI sound, nothing sent anywhere. */
public final class MiningAlerts {

    private MiningAlerts() {
    }

    /** A title and subtitle on screen, held {@code stayTicks}. Render thread. */
    public static void title(String title, String subtitle, int stayTicks) {
        Minecraft client = Minecraft.getInstance();
        McCompat.setTimes(client, 5, stayTicks, 10);
        McCompat.setTitle(client, Component.literal(title));
        McCompat.setSubtitle(client, Component.literal(subtitle == null ? "" : subtitle));
    }

    public static void sound(SecretSound.SoundChoice choice) {
        if (choice != null) {
            DungeonAlertsFeature.playSound(choice.sound(), 1.0f, 1.0f);
        }
    }
}
