package com.killer560.hub.bloodcamp;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Watcher's blood mobs by name (killer560, 2026-10-15: recognise them "so mobs that landed while he was far away are
 * tracked"). The list is hypixelskyblock.minecraft.wiki's Blood Room / The Watcher page, read 2026-10-15: the undead
 * mobs he summons on every floor, the Wandering Soul, the reincarnated bosses from Floor III on (Bonzo, Scarf, Spirit
 * Bear, Livid) and the Floor VII Sadan giants he reanimates. The wiki gives no separate Master Mode list.
 * <p>
 * Matched on a mob's NAME-TAG armour stand (a custom name on a stand, never chat), anchored on the whole line and
 * bounded: an optional "[Lv123] " prefix, the exact name, an optional health ("2.1M", "400k/1.2M"), and the heart that
 * ends every Hypixel mob name tag - "Revoker 1.4M❤". The stand is mapped to its mob the way Dungeon ESP maps starred
 * stands (the entity one id below it, else the first living thing in the stand's box moved down). A player-model NPC
 * (version-2 UUID) whose own name is exactly one of the names counts too; a real player never does.
 */
final class BloodMobNames {

    static final List<String> NAMES = List.of(
            // undead, every floor
            "Mr. Dead", "Revoker", "Tear", "Skull", "Walker", "Cannibal", "Frost", "Putrid", "Reaper", "Vader", "Flamer",
            "Freak", "Leech", "Mute", "Ooze", "Parasite", "Psycho", "Wandering Soul", "Doze", "Fels", "Watchful Eye",
            // reincarnated bosses (Floor III on)
            "Bonzo", "Scarf", "Spirit Bear", "Livid",
            // Floor VII: Sadan's giants
            "Bigfoot", "Jolly Pink Giant", "L.A.S.R.", "The Diamond Giant");

    private static final Set<String> NAME_SET = Set.copyOf(NAMES);

    // Hypixel puts an affix word before some blood mobs' names ("Stormy Doze", "Stormy Mr. Dead", "Army Walker", killer560
    // 2026-10-18 screenshots), so one optional capitalised word may come before the name.
    static final Pattern TAG = Pattern.compile("^(?:\\[Lv\\d{1,4}] )?(?:[A-Z][a-z]+ )?(" + String.join("|",
            NAMES.stream().map(Pattern::quote).toList())
            + ")(?: \\d{1,4}(?:[.,]\\d{1,3})?[kMB]?(?:/\\d{1,4}(?:[.,]\\d{1,3})?[kMB]?)?)?❤$");

    private BloodMobNames() {
    }

    /** Whether a stripped name-tag line names a blood mob. */
    static boolean isBloodTag(String plain) {
        return plain != null && plain.length() <= 64 && TAG.matcher(plain).matches();
    }

    /** The blood mob a matching name-tag stand belongs to, or null. */
    static Entity mobFor(Minecraft client, ArmorStand stand) {
        Entity byId = client.level.getEntity(stand.getId() - 1);
        if (isMob(client, byId)) {
            return byId;
        }
        List<Entity> below = client.level.getEntities(stand, stand.getBoundingBox().inflate(0.3, 0.0, 0.3)
                .expandTowards(0.0, -2.5, 0.0), e -> !(e instanceof ArmorStand) && !(e instanceof ExperienceOrb));
        for (Entity e : below) {
            if (isMob(client, e)) {
                return e;
            }
        }
        return null;
    }

    /** A player-model NPC named exactly as a blood mob. */
    static boolean isNamedNpc(Minecraft client, Entity e) {
        return e instanceof Player p && p != client.player && p.getUUID().version() == 2
                && NAME_SET.contains(p.getName().getString());
    }

    private static boolean isMob(Minecraft client, Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof ArmorStand || e == client.player || !e.isAlive()) {
            return false;
        }
        return !(e instanceof Player p) || p.getUUID().version() == 2;
    }
}
