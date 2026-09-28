package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Map codes from runs he has actually played, so a real floor can be replayed in the sim.
 *
 * <p>killer560 (2026-09-28): "The previous map will load one from previous runs logged on main that we have".
 * A map code is small, so this keeps one file per run rather than anything clever - they can be read, edited
 * and handed to someone else, which is the point of a code in the first place.
 *
 * <p>Recorded from the real dungeon, not from the sim: the value is practising the floor he actually got.
 */
public final class SimRunHistory {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");
    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-sim-runs");

    private SimRunHistory() {
    }

    /**
     * Saves the layout currently being played as a map code.
     *
     * <p>Called from a real dungeon, which is why it takes the layout rather than reading the sim: replaying a
     * sim map would only ever give back what the sim already generated.
     */
    public static void recordCurrent(DungeonLayout layout, String floor) {
        if (layout == null) {
            return;
        }
        try {
            String code = MapCode.encode(layout);
            Files.createDirectories(DIR);
            String name = (floor == null ? "run" : floor) + "-" + Instant.now().toEpochMilli() + ".txt";
            Files.writeString(DIR.resolve(name), code, StandardCharsets.UTF_8);
            LOGGER.info("Saved a sim map code for {}", floor);
        } catch (Exception e) {
            LOGGER.warn("Could not save the run's map code", e);
        }
    }

    /** Saved runs, newest first - the one he just played is the one he most likely wants to redo. */
    public static List<String> savedRuns() {
        List<String> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(DIR)) {
                return out;
            }
            try (var files = Files.list(DIR)) {
                files.filter(p -> p.toString().endsWith(".txt"))
                        .map(p -> p.getFileName().toString().replace(".txt", ""))
                        .forEach(out::add);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not list saved runs", e);
        }
        Collections.sort(out);
        Collections.reverse(out);
        return out;
    }

    /** Loads a saved run into the sim. */
    public static void load(Minecraft client, String name) {
        try {
            String code = Files.readString(DIR.resolve(name + ".txt"), StandardCharsets.UTF_8).trim();
            SimWorld.open(client, code);
        } catch (Exception e) {
            ModChat.send("Sim", ModChat.text("Could not read that saved run"));
            LOGGER.warn("Could not read saved run {}", name, e);
        }
    }
}
