package com.killer560.hub.thorn;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Thorn Triggerbot (killer560, 2026-11-01: "add a thorn triggerbot while holding tribal spear. This triggerbot should
 * just hold rightclick if i am holding the tribal spear. Make sure if something gets in between me and thorn that isnt
 * a block that itll still hold (like bats shouldnt stop it)"). Cheat build only, default OFF.
 * <p>
 * In the F4/M4 Thorn fight, with the Tribal Spear ({@code TRIBAL_SPEAR}, whose Thwack throw stunlocks Thorn) in hand, it
 * holds the use key down - the same thing as a held right click, so vanilla sends the throws at vanilla's own held-key
 * rate and nothing is sent by this class. With "Only When Aimed" on (the default), it holds only while the crosshair line
 * meets Thorn (the arena's Ghast) with no BLOCK in front of him: a bat or any other mob in the way is not a block, so it
 * keeps holding. It lets go only of a hold it made itself; a right click you are holding yourself is never touched.
 */
public final class ThornTriggerbot {

    static final String TRIBAL_SPEAR_ID = "TRIBAL_SPEAR";
    private static final double AIM_DISTANCE = 128.0;

    private static boolean holding = false;
    private static int ticksHeld = 0;

    private ThornTriggerbot() {
    }

    /** Called every client tick from {@link ThornFeature}; {@code active} is "in the Thorn fight". */
    static void tick(Minecraft client, boolean active) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) return;
        LocalPlayer player = client.player;
        boolean want = active && player != null && client.level != null && McCompat.screen(client) == null
                && ThornConfig.getInstance().isTriggerbotEnabled() && !player.isDeadOrDying()
                && TRIBAL_SPEAR_ID.equals(ItemIdentity.skyblockId(player.getMainHandItem()))
                && (!ThornConfig.getInstance().isTriggerbotAimed() || aimedAtThorn(client, player));
        if (want) {
            if (!holding) {
                if (client.options.keyUse.isDown()) {
                    return; // he is holding it himself: leave his key alone
                }
                holding = true;
            }
            client.options.keyUse.setDown(true);
            ticksHeld++;
        } else {
            release(client);
        }
    }

    static void release(Minecraft client) {
        if (holding) {
            holding = false;
            client.options.keyUse.setDown(false);
        }
    }

    /** Whether this triggerbot is holding the use key right now, and for how many ticks in all - for the testkit. */
    public static boolean isHolding() { return holding; }

    public static int ticksHeld() { return ticksHeld; }

    /** The crosshair line meets Thorn's box, and no block stands between the eyes and that point. Entities in between
     *  are ignored on purpose. */
    static boolean aimedAtThorn(Minecraft client, LocalPlayer player) {
        Vec3 eyes = player.getEyePosition(1.0f);
        Vec3 end = eyes.add(player.getViewVector(1.0f).scale(AIM_DISTANCE));
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof Ghast) || !e.isAlive()) continue;
            AABB box = e.getBoundingBox();
            Vec3 hit = box.contains(eyes) ? eyes : box.clip(eyes, end).orElse(null);
            if (hit == null) continue;
            HitResult wall = client.level.clip(new ClipContext(eyes, hit, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (wall.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
