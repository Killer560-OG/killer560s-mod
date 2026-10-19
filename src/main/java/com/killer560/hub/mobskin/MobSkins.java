package com.killer560.hub.mobskin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which mobs "Play as Mob" offers (Player Cosmetics; killer560's to-do item, 2026-10-18: "Make an option to change your
 * character to any ingame mob. Still have scale sliders apply. If you are a supporter it can be shared.").
 * <p>
 * {@link #IDS} is the allowlist, as wire ids (the vanilla entity id without {@code minecraft:}). The relay keeps the SAME
 * list ({@code MOB_IDS} in its src/supporterShape.ts) and refuses anything else, and this client drops anything else it
 * receives, so a shared value can only ever name one of these. Every living mob is on it except the bosses and giants
 * (ender dragon, wither, giant, elder guardian, warden) and the ones that make no sense following a player (shulker, the
 * illusioner nobody ever sees, armor stands and mannequins). An id this Minecraft version does not have is simply not
 * offered and draws the normal player; the list is never a reason to crash.
 */
public final class MobSkins {

    public static final List<String> IDS = List.of(
            "allay", "armadillo", "axolotl", "bat", "bee", "blaze", "bogged", "breeze", "camel", "cat", "cave_spider",
            "chicken", "cod", "copper_golem", "cow", "creaking", "creeper", "dolphin", "donkey", "drowned", "enderman",
            "endermite", "evoker", "fox", "frog", "ghast", "glow_squid", "goat", "guardian", "happy_ghast", "hoglin",
            "horse", "husk", "iron_golem", "llama", "magma_cube", "mooshroom", "mule", "ocelot", "panda", "parrot",
            "phantom", "pig", "piglin", "piglin_brute", "pillager", "polar_bear", "pufferfish", "rabbit", "ravager",
            "salmon", "sheep", "silverfish", "skeleton", "skeleton_horse", "slime", "sniffer", "snow_golem", "spider",
            "squid", "stray", "strider", "tadpole", "trader_llama", "tropical_fish", "turtle", "vex", "villager",
            "vindicator", "wandering_trader", "witch", "wither_skeleton", "wolf", "zoglin", "zombie", "zombie_horse",
            "zombie_villager", "zombified_piglin");

    private static volatile List<String> available;

    private MobSkins() {
    }

    /** {@code id} when it is on the allowlist, else null (blank, unknown, a boss, anything with a namespace). */
    public static String sanitize(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.equals(id) && IDS.contains(id) ? id : null;
    }

    /** The entity type for an allowed id in THIS Minecraft version, or null (not allowed, or this version lacks it). */
    public static EntityType<?> type(String id) {
        // Asked per swapped player per frame by the renderer (fps pass 2026-10-19). The built-in entity registry is
        // frozen before any of this runs, so the answer for an id never changes: remembered per id (bounded).
        if (id == null) {
            return typeUncached(null);
        }
        Object known = TYPES.get(id);
        if (known != null) {
            return known == NO_TYPE ? null : (EntityType<?>) known;
        }
        EntityType<?> type = typeUncached(id);
        if (TYPES.size() < 256) {
            TYPES.put(id, type == null ? NO_TYPE : type);
        }
        return type;
    }

    private static final Object NO_TYPE = new Object();
    private static final java.util.Map<String, Object> TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    private static EntityType<?> typeUncached(String id) {
        String ok = sanitize(id);
        if (ok == null) {
            return null;
        }
        Identifier key = Identifier.withDefaultNamespace(ok);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        // ENTITY_TYPE is a defaulted registry: never accept its default standing in for a missing id.
        return type != null && key.equals(EntityType.getKey(type)) ? type : null;
    }

    /** The allowed ids this Minecraft version actually has, in list order - what the Player Cosmetics tab cycles. */
    public static List<String> available() {
        List<String> a = available;
        if (a == null) {
            List<String> out = new ArrayList<>();
            for (String id : IDS) {
                if (type(id) != null) {
                    out.add(id);
                }
            }
            a = List.copyOf(out);
            available = a;
        }
        return a;
    }

    /** The mob's in-game name ("Zombified Piglin"), or "Player" for none. */
    public static String displayName(String id) {
        EntityType<?> type = type(id);
        if (type == null) {
            return "Player";
        }
        return type.getDescription().getString();
    }
}
