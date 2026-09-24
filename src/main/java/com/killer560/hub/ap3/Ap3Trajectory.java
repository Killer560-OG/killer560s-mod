package com.killer560.hub.ap3;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records what the player ACTUALLY does, tick by tick, so a movement can be replayed through the planner's own
 * physics and the two compared.
 * <p>
 * Written 2026-09-23 because a jump killer560 makes by hand - his neo - is one the planner insists cannot be done,
 * and after a night of arguing with simulations the only way left to tell which of us is wrong is to record the
 * real thing. Every claim about that jump so far has come from a model; this is the ground truth it has to match.
 * <p>
 * Toggled with {@code /ap3 record}. It writes {@code config/killer560smod/ap3-trajectory.json}: one entry a tick
 * with position, velocity, whether he was on the ground, his yaw and the keys he was holding, plus the speed
 * attribute in force - which is the other thing that has been assumed rather than measured.
 */
public final class Ap3Trajectory {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3-route");
    /** Long enough for any single movement, short enough that a forgotten recording cannot eat memory. */
    private static final int MAX_TICKS = 400;

    private static boolean recording;
    private static JsonArray ticks;
    private static int count;

    private Ap3Trajectory() {
    }

    /** @return true if recording started, false if it stopped (and was written). */
    public static boolean toggle() {
        if (recording) {
            stop();
            return false;
        }
        recording = true;
        ticks = new JsonArray();
        count = 0;
        LOGGER.info("[AP3 trajectory] recording started");
        return true;
    }

    public static boolean isRecording() {
        return recording;
    }

    /** Called every client tick while AP3 is active. Cheap: a handful of doubles into a list. */
    public static void onClientTick(Minecraft client) {
        if (!recording || client == null || client.player == null) {
            return;
        }
        LocalPlayer p = client.player;
        Vec3 pos = p.position();
        Vec3 vel = p.getDeltaMovement();
        JsonObject o = new JsonObject();
        o.addProperty("t", count);
        o.addProperty("x", pos.x);
        o.addProperty("y", pos.y);
        o.addProperty("z", pos.z);
        o.addProperty("vx", vel.x);
        o.addProperty("vy", vel.y);
        o.addProperty("vz", vel.z);
        o.addProperty("onGround", p.onGround());
        o.addProperty("sprinting", p.isSprinting());
        o.addProperty("crouching", p.isCrouching());
        o.addProperty("yaw", p.getYRot());
        // What he is actually holding - the inputs a replay has to reproduce.
        o.addProperty("fwd", p.input == null ? 0f : p.input.getMoveVector().y);
        o.addProperty("strafe", p.input == null ? 0f : p.input.getMoveVector().x);
        o.addProperty("jumping", p.input != null && p.input.keyPresses.jump());
        ticks.add(o);
        if (++count >= MAX_TICKS) {
            ModChat.send("AP3", ModChat.text("Recording full at "), ModChat.value(MAX_TICKS + " ticks"),
                    ModChat.dim(" - saved."));
            stop();
        }
    }

    private static void stop() {
        recording = false;
        if (ticks == null || ticks.isEmpty()) {
            LOGGER.info("[AP3 trajectory] recording stopped with nothing in it");
            return;
        }
        try {
            JsonObject root = new JsonObject();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // The speed in force, because the planner's whole reach calculation hangs off it and it has been
                // taken from a dump rather than measured live.
                root.addProperty("baseSpeedAttr", Ap3Executor.routeModel(mc.player).baseSpeedAttr);
            }
            root.addProperty("ticks", ticks.size());
            root.add("path", ticks);
            Path f = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("killer560smod").resolve("ap3-trajectory.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[AP3 trajectory] wrote {} ticks to {}", ticks.size(), f.getFileName());
            ModChat.send("AP3", ModChat.text("Recorded "), ModChat.value(ticks.size() + " ticks"),
                    ModChat.dim(" to ap3-trajectory.json"));
        } catch (Throwable t) {
            LOGGER.warn("[AP3 trajectory] could not write the recording", t);
        } finally {
            ticks = null;
        }
    }
}
