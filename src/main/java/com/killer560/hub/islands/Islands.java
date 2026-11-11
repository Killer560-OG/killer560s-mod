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

    /** Whether the key bound to {@code m} is physically held now (keyboard or mouse), whatever the mod set it to. */
    public static boolean physical(Minecraft mc, net.minecraft.client.KeyMapping m) {
        com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(m.saveString());
        if (key == null || key == com.mojang.blaze3d.platform.InputConstants.UNKNOWN || mc.getWindow() == null) {
            return false;
        }
        return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                ? com.killer560.hub.compat.McInput.isNativeButtonDown(mc.getWindow(), key.getValue())
                : com.killer560.hub.compat.McInput.isNativeKeyDown(mc.getWindow(), key.getValue());
    }

    /** One press of {@code m}, as a human tap: the mapping's click count goes up once and vanilla handles it this tick.
     *  Nothing is held; an unbound mapping does nothing. */
    public static void tap(net.minecraft.client.KeyMapping m) {
        com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(m.saveString());
        if (key != null && key != com.mojang.blaze3d.platform.InputConstants.UNKNOWN) {
            net.minecraft.client.KeyMapping.click(key);
        }
    }

    /** Milliseconds as seconds with one decimal: "12.4s". */
    public static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }
}
