package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sim's dungeon keys, shaped like Hypixel's (hypixelskyblock.minecraft.wiki, Wither Key / Blood Key, 2026-10-06): a
 * dropped key is a named, invisible armour stand ("Wither Key" / "Blood Key" - what the client's {@code DoorKeysFeature}
 * looks for) wearing the key's head ({@link #keyHead}: Hypixel's skins, a black orb and a red one),
 * it is picked up by being near it, and the key then belongs to the whole team ("usable by any person within the
 * dungeon, regardless of whether or not they are the player who obtained it"). A pickup says
 * {@code "<name> has obtained Wither Key!"} in chat, as Hypixel does; a wither door opened with the team's key says
 * {@code "<name> opened a WITHER door!"} ({@link SimDoors}).
 *
 * <p>The pickup range is {@link #pickupRange}.
 */
public final class SimKeys {

    /**
     * Blocks from the player's feet to the stand: Hypixel's range as the mod models it
     * ({@link com.killer560.hub.doorkeys.DungeonKeys#pickupRange} - the 0.27.2 +5 is sourced, the base 1 block is vanilla's
     * item reach and unverified for keys; the Magnetic Talisman does not apply to keys, confirmed in game). The
     * Autopilot's Key Base Range setting stands in for the base. Was a flat 3-block guess.
     */
    public static double pickupRange() {
        return com.killer560.hub.autosecret.AutoSecretConfig.getInstance().keyPickupRange();
    }

    /** Dropped keys not yet picked up: the stand's UUID -> blood key? */
    private static final Map<UUID, Boolean> DROPPED = new ConcurrentHashMap<>();
    private static volatile int witherKeys;
    private static volatile boolean bloodKey;
    private static volatile String lastPickup;

    private SimKeys() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimKeys::tick);
        // A right click on a key's stand would take its head off (vanilla armour-stand swap). Refused on the server
        // only, so the client sends exactly what it would send on Hypixel.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                !level.isClientSide() && entity != null && DROPPED.containsKey(entity.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    /** Drops a key at {@code pos} (server side). Client thread or any. */
    public static void drop(Minecraft client, Vec3 pos, boolean blood) {
        var server = client.getSingleplayerServer();
        if (!SimState.canAct(client) || server == null || pos == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ArmorStand stand = new ArmorStand(level, pos.x, pos.y, pos.z);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setNoBasePlate(true);
            stand.setInvulnerable(true);
            stand.setCustomName(Component.literal(blood ? "Blood Key" : "Wither Key"));
            stand.setCustomNameVisible(true);
            // A full-size stand standing on the floor: the head it wears floats at head height (helmet ~1.4-1.9 above
            // the floor), the name above it, and the stand's own position - what the client's DungeonKeys reads and
            // both pickup checks measure to - sits directly under the head.
            stand.setItemSlot(EquipmentSlot.HEAD, keyHead(blood));
            if (level.addFreshEntity(stand)) {
                DROPPED.put(stand.getUUID(), blood);
            }
        });
    }

    /**
     * Hypixel's key skins. hypixelskyblock.minecraft.wiki: "The Wither Key and Wither Essence use the same texture",
     * "The Blood Key and Overflux Power Orb use the same texture" (Wither Key / Blood Key pages, Trivia, 2026-10-06).
     * The Wither Essence item's skin is from the NotEnoughUpdates item repo (items/ESSENCE_WITHER.json, a black orb);
     * the Overflux Power Orb's from Hypixel's own api.hypixel.net/v2/resources/skyblock/items (a red orb in a purple
     * frame). Re-encoded with only the skin URL, as Hypixel's own unsigned heads carry.
     */
    private static final String WITHER_KEY_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzRkYjRhZGZhOWJmNDhmZjVkNDE3MDdhZTM0ZWE3OGJkMjM3MTY1OWZjZDhjZDg5MzQ3NDlhZjRjY2U5YiJ9fX0=";
    private static final String BLOOD_KEY_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjBkZTVlODk3NDk0MDM3NTkzNGQzMmY3MWM5MWFkMmQ1NzI4ZDM4ZTUxNjQ3ZGNjOGYzOTIwNmMwOTlhNTRjMiJ9fX0=";

    /** The player head a dropped key wears, carrying its skin through the {@code minecraft:profile} component. */
    static ItemStack keyHead(boolean blood) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE,
                com.killer560.hub.profileviewer.item.LegacyItems.skullProfile(blood ? BLOOD_KEY_TEXTURE : WITHER_KEY_TEXTURE));
        return head;
    }

    /** The team's wither keys (picked up, not yet used on a door). */
    public static int witherKeys() {
        return witherKeys;
    }

    public static boolean bloodKey() {
        return bloodKey;
    }

    /** Who picked up the last key and how far from it, for tests: "name|distance", or null. */
    public static String lastPickup() {
        return lastPickup;
    }

    public static int droppedCount() {
        return DROPPED.size();
    }

    /** Spends one team wither key; false when there is none. */
    static boolean useWitherKey() {
        if (witherKeys <= 0) {
            return false;
        }
        witherKeys--;
        return true;
    }

    static void useBloodKey() {
        bloodKey = false;
    }

    public static void clear() {
        DROPPED.clear();
        witherKeys = 0;
        bloodKey = false;
        lastPickup = null;
    }

    private static void tick(MinecraftServer server) {
        if (!SimState.isActive() || DROPPED.isEmpty()) {
            return;
        }
        try {
            ServerLevel level = server.overworld();
            double range = pickupRange();
            for (Map.Entry<UUID, Boolean> e : DROPPED.entrySet()) {
                Entity stand = level.getEntity(e.getKey());
                if (stand == null) {
                    continue;   // not in an entity-ticking section right now - never read as gone
                }
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    double d = sp.position().distanceTo(stand.position());
                    if (d > range) {
                        continue;
                    }
                    boolean blood = e.getValue();
                    DROPPED.remove(e.getKey());
                    stand.discard();
                    if (blood) {
                        bloodKey = true;
                    } else {
                        witherKeys++;
                    }
                    String name = sp.getGameProfile().name();
                    lastPickup = name + "|" + String.format(java.util.Locale.US, "%.2f", d);
                    Component line = Component.literal(name + " has obtained " + (blood ? "Blood Key" : "Wither Key") + "!");
                    for (ServerPlayer to : server.getPlayerList().getPlayers()) {
                        to.sendSystemMessage(line);
                    }
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // a key must never stall the server tick
        }
    }
}
