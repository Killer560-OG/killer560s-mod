package com.killer560.hub.worldexport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Armor stands (how Hypixel shows fairy souls, NPC heads and holograms) and other players (NPCs) are entities, not
 * chunk data, so the chunk cache never holds them. This records them from the live client: every entity in view is
 * remembered by UUID while recording is on (a walk collects the lot), and an export also adds whatever is in view at
 * that moment. Written to {@code entities.json} next to the world. Local only: reads the client's entity list.
 */
public final class EntityRecorder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, JsonObject> SEEN = new LinkedHashMap<>();
    private static ClientLevel lastLevel;
    private static boolean recording;
    private static boolean registered;

    private EntityRecorder() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                SEEN.clear();
                lastLevel = client.level;
            }
            if (recording && client.level != null && client.player != null && client.player.tickCount % 10 == 0) {
                scan(client.level);
            }
        });
    }

    public static boolean isRecording() { return recording; }

    public static void setRecording(boolean on) { recording = on; }

    public static int count() { return SEEN.size(); }

    public static void clear() { SEEN.clear(); }

    private static void scan(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        for (Entity e : level.entitiesForRendering()) {
            if (e == mc.player || e.isRemoved()) continue;
            if (e instanceof ArmorStand || e instanceof Player) {
                SEEN.put(e.getUUID(), describe(level, e));
            }
        }
    }

    private static JsonObject describe(ClientLevel level, Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", e instanceof Player ? "player" : "armor_stand");
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("yaw", e.getYRot());
        o.addProperty("invisible", e.isInvisible());
        if (e.hasCustomName() && e.getCustomName() != null) {
            o.addProperty("name", e.getCustomName().getString());
        }
        if (e instanceof Player p) {
            o.addProperty("playerName", p.getGameProfile().name());
        }
        if (e instanceof ArmorStand stand) {
            o.addProperty("marker", stand.isMarker());
            o.addProperty("small", stand.isSmall());
        }
        if (e instanceof net.minecraft.world.entity.LivingEntity living) {
            ItemStack head = living.getItemBySlot(EquipmentSlot.HEAD);
            if (!head.isEmpty()) {
                var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                JsonElement encoded = ItemStack.CODEC.encodeStart(ops, head).result().orElse(null);
                if (encoded != null) {
                    o.add("head", encoded);
                }
            }
        }
        return o;
    }

    /** Writes everything recorded plus what is in view now. @return how many entities were written. */
    public static int write(ClientLevel level, Path file) throws IOException {
        scan(level);
        JsonArray arr = new JsonArray();
        SEEN.values().forEach(arr::add);
        JsonObject root = new JsonObject();
        root.add("entities", arr);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        return arr.size();
    }
}
