package com.killer560.hub.autoroutes;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which living mobs are a crypt's undead (or a prince): what a Spirit Sceptre CRYPT node aims at once its first uses
 * have gone down (killer560, 2026-10-15: "make the crypt node follow the same logic as bat where it will try to look at
 * a crypt as well").
 *
 * <p>Told by NAME, never by "a mob that appeared near me": entering a room brings every mob in it into tracking range at
 * once, so a spawn cannot be told from a mob coming into view. Hypixel names a crypt's undead on its name-tag armour stand,
 * {@code ⚔ Crypt Undead 512.6k❤}, with no star (it does not count toward the clear), and there are only two kinds,
 * Crypt Undead and Prince (killer560, 2026-10-16, from a run screenshot). The sim's own crypt mob carries the same tag as
 * its custom name ({@code roomsim.SimMobs.spawnCrypt}), read the same way.
 */
public final class CryptMobs {

    /**
     * Hypixel's crypt-mob name tag, whole (killer560's run screenshot, 2026-10-16: {@code ⚔ Crypt Undead 512.6k❤}): an
     * optional icon of one to three symbols (no letter, digit or space, so no {@code [VIP] Name:} chat prefix fits), one
     * of exactly the two names, a space, the health (up to four digits, an optional one-to-three-digit fraction, an
     * optional k / M / B) and the heart. Anchored, every quantifier bounded.
     */
    static final Pattern TAG = Pattern.compile(
            "^(?:[^\\sA-Za-z0-9]{1,3} )?(Crypt Undead|Prince) \\d{1,4}(?:\\.\\d{1,3})?[kMB]?❤$");

    private CryptMobs() {
    }

    /** Whether a mob or name-tag name (formatting codes stripped) is a crypt undead's or a prince's tag. */
    static boolean isCryptName(String raw) {
        if (raw == null) {
            return false;
        }
        String name = ChatFormatting.stripFormatting(raw).trim();
        return name.length() <= 48 && TAG.matcher(name).matches();
    }

    /** A living mob that can be a crypt's undead at all: not an armour stand, bat, him, or a real player. */
    private static boolean candidate(Minecraft client, Entity e) {
        if (!(e instanceof LivingEntity l) || e instanceof ArmorStand || e instanceof Bat || e == client.player) {
            return false;
        }
        if (!l.isAlive() || l.isRemoved() || l.isDeadOrDying()) {
            return false;
        }
        // A real player has a version-4 UUID; Hypixel's NPC-style mobs are version 2.
        return !(e instanceof Player p) || p.getUUID().version() != 4;
    }

    /**
     * Every crypt undead / prince alive within {@code range} blocks of him, by its own name or the name-tag stand
     * floating over it.
     */
    static List<LivingEntity> near(Minecraft client, LocalPlayer player, double range) {
        List<LivingEntity> out = new ArrayList<>();
        if (client.level == null) {
            return out;
        }
        double r2 = range * range;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!candidate(client, e) || e.distanceToSqr(player) > r2) {
                continue;
            }
            LivingEntity mob = (LivingEntity) e;
            if (isCryptName(mob.getName().getString()) || (mob.hasCustomName() && isCryptName(
                    mob.getCustomName().getString())) || tagOver(client, mob)) {
                out.add(mob);
            }
        }
        return out;
    }

    /** A named armour stand over the mob (Hypixel's name tag) that reads as a crypt mob. */
    private static boolean tagOver(Minecraft client, LivingEntity mob) {
        AABB box = mob.getBoundingBox();
        AABB over = new AABB(box.minX - 0.4, box.minY, box.minZ - 0.4, box.maxX + 0.4, box.maxY + 1.5, box.maxZ + 0.4);
        for (ArmorStand stand : client.level.getEntitiesOfClass(ArmorStand.class, over, ArmorStand::hasCustomName)) {
            if (isCryptName(stand.getCustomName().getString())) {
                return true;
            }
        }
        return false;
    }
}
