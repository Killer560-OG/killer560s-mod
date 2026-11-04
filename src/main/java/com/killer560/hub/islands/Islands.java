package com.killer560.hub.islands;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.scoreboard.ScoreboardData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;

/** Shared reads for the Rift and Fishing features (staged for after 2.0): island, alerts, keys. */
public final class Islands {

    private Islands() {
    }

    public static boolean inRift() {
        return ScoreboardData.inIsland("The Rift");
    }

    /** Whether the player's box touches {@code area}. */
    public static boolean inside(Minecraft mc, AABB area) {
        return mc.player != null && mc.player.getBoundingBox().intersects(area);
    }

    /** A title on screen and, when {@code sound}, a pling. */
    public static void alert(String title, String subtitle, boolean sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && title != null && !title.isBlank()) {
            McCompat.setTimes(mc, 0, 30, 5);
            McCompat.setTitle(mc, Component.literal(title));
            McCompat.setSubtitle(mc, Component.literal(subtitle == null ? "" : subtitle));
        }
        if (sound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f));
        }
    }

    /** Milliseconds as seconds with one decimal: "12.4s". */
    public static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }
}
