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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which living mobs are a crypt's undead (or a prince): what a Spirit Sceptre CRYPT node aims at once its first uses
 * have gone down (killer560, 2026-10-15: "make the crypt node follow the same logic as bat where it will try to look at
 * a crypt as well").
 *
 * <p>Told by NAME, never by "a mob that appeared near me": entering a room brings every mob in it into tracking range at
 * once, so a spawn cannot be told from a mob coming into view. Hypixel names a crypt's undead on its name-tag armour stand,
 * {@code [Lv89] Crypt Undead 45k❤}, with no star (it does not count toward the clear); some are named after Hypixel staff
 * instead (an "Admin" version, a player entity with that name), and a prince is {@code Prince <Name>}. The names are
 * Devonian's {@code HideNoStarTag} exceptions (his copy, {@code UsersHunterdevonian}), which is the list of unstarred
 * dungeon mobs whose tags that feature must keep. The sim's own crypt mob is a zombie with the custom name
 * {@code Crypt Undead} or {@code Crypt Prince} ({@code roomsim.SimMobs.spawnCrypt}), read the same way.
 */
public final class CryptMobs {

    /** Devonian's name-tag shape: optional level, a symbol or two, the name, then the health and the heart. Bounded. */
    private static final Pattern TAG = Pattern.compile(
            "^(?:\\[Lv\\d{1,4}] )?[^\\sA-Za-z]{0,4} ?([A-Za-z_. ]{1,40}) [\\dkMB.,/]{1,24}❤$");
    private static final Pattern PRINCE = Pattern.compile("^(?:Crypt )?Prince(?: [A-Z][a-z]{1,15})?$");
    /** Staff-named crypt undead (Devonian HideNoStarTag). */
    private static final Set<String> STAFF = Set.of(
            "hypixel", "sfarnham", "aPunch", "Jayavarmen", "Don Pireso", "codename_B", "WilliamTiger", "TheMGRF",
            "Ob111", "Sylent", "Bloozing", "Nitroholic", "Minikloon", "Relenter", "Externalizable", "Plancke", "ChiLynn",
            "skyerzz", "Magicboys", "Cecer", "Likaos", "Linfoot", "Dctr", "_onah", "falloutowns", "LadyBleu",
            "Revengeee", "Bembo", "flameboy101", "JamieTheGeek", "Judg3", "Rezzus", "AgentK", "Thorlon", "Vinny",
            "fudgiethewhale", "DistrictGecko", "Dueces", "Cheesey", "BlocksKey", "DEADORKAI", "Plummel", "AdamWho",
            "Winghide", "MistressEldrid");

    private CryptMobs() {
    }

    /** Whether a mob or name-tag name (formatting stripped) is a crypt undead or a prince. */
    static boolean isCryptName(String raw) {
        if (raw == null) {
            return false;
        }
        String name = ChatFormatting.stripFormatting(raw).trim();
        if (name.isEmpty() || name.length() > 80 || name.contains("✯")) {
            return false;
        }
        Matcher m = TAG.matcher(name);
        if (m.matches()) {
            name = m.group(1).trim();
        }
        return name.equals("Crypt Undead") || name.startsWith("Crypt Undead ") || PRINCE.matcher(name).matches()
                || STAFF.contains(name);
    }

    /** A living mob that can be a crypt's undead at all: not an armour stand, bat, him, or a real player. */
    private static boolean candidate(Minecraft client, Entity e) {
        if (!(e instanceof LivingEntity l) || e instanceof ArmorStand || e instanceof Bat || e == client.player) {
            return false;
        }
        if (!l.isAlive() || l.isRemoved() || l.isDeadOrDying()) {
            return false;
        }
        // A real player has a version-4 UUID; Hypixel's NPC-style mobs (the staff-named undead) are version 2.
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
