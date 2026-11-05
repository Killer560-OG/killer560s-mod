package com.killer560.hub.bosssim;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * {@code /simbosscapture} (dev builds only): copies the F7 boss room's blocks, as this client has them loaded, into the
 * file of the boss phase it is run in ({@link BossArena#capturePath(String)}): the sim builds its room from the P1 capture
 * and takes P3's start from the P3 one (bossfloor-1104: one file for every phase let a P3 capture overwrite the P1 room).
 * Reading only: it looks at the chunks the client already has and sends nothing.
 *
 * <p>Only loaded chunks are copied; a column that was not loaded keeps whatever an earlier capture had there, so a second
 * capture from another spot fills in what the first could not see.
 */
final class BossCapture {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    private BossCapture() {
    }

    static void register() {
        if (!com.killer560.hub.BuildVariant.DEV_TOOLS) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simbosscapture").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> capture(mc));
                    return 1;
                })));
    }

    private static void capture(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.getCurrentServer() == null || !Floor7Tracker.inF7Boss()) {
            ModChat.send("Sim", ModChat.text("Run this in an F7 or M7 boss on Hypixel."));
            return;
        }
        long t0 = System.currentTimeMillis();
        int x0 = BossArena.X0, y0 = BossArena.Y0, z0 = BossArena.Z0, w = BossArena.W, h = BossArena.H, d = BossArena.D;
        Floor7Tracker.Phase at = Floor7Tracker.getPhase() != Floor7Tracker.Phase.UNKNOWN ? Floor7Tracker.getPhase()
                : Floor7Tracker.getPhaseAt();
        if (at == Floor7Tracker.Phase.UNKNOWN) {
            ModChat.send("Sim", ModChat.text("The boss phase is not known yet - run this again in a moment."));
            return;
        }
        String phase = at.name();
        BossArena.Captured old = BossArena.readCapture(phase);
        List<String> palette = new ArrayList<>();
        Map<String, Short> index = new HashMap<>();
        java.util.function.Function<String, Short> idOf = s -> index.computeIfAbsent(s, k -> {
            palette.add(k);
            return (short) (palette.size() - 1);
        });
        idOf.apply("minecraft:air");
        Map<BlockState, Short> byState = new java.util.IdentityHashMap<>();
        short[] cells = new short[w * h * d];
        int loadedColumns = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int wx = x0 + x, wz = z0 + z;
                boolean loaded = level.hasChunk(wx >> 4, wz >> 4);
                if (loaded) {
                    loadedColumns++;
                }
                for (int y = 0; y < h; y++) {
                    int i = (y * d + z) * w + x;
                    BlockState st;
                    if (loaded) {
                        st = level.getBlockState(new BlockPos(wx, y0 + y, wz));
                    } else if (old != null && old.x0() == x0 && old.z0() == z0 && old.w() == w && old.d() == d) {
                        st = old.states()[old.cells()[i]];
                    } else {
                        st = BossBlocks.AIR;
                    }
                    Short id = byState.get(st);
                    if (id == null) {
                        id = idOf.apply(BlockStateParser.serialize(st));
                        byState.put(st, id);
                    }
                    cells[i] = id;
                }
            }
        }
        int columns = w * d;
        int seen = loadedColumns;
        LOGGER.info("[BossSim] capture read {} of {} columns in {} ms", seen, columns, System.currentTimeMillis() - t0);
        Path out = BossArena.capturePath(phase);
        Thread writer = new Thread(() -> {
            try {
                Files.createDirectories(out.getParent());
                Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
                try (OutputStream raw = Files.newOutputStream(tmp);
                     DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16)))) {
                    o.write("K5BA".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    o.writeInt(2);
                    o.writeUTF(phase);
                    o.writeInt(x0);
                    o.writeInt(y0);
                    o.writeInt(z0);
                    o.writeInt(w);
                    o.writeInt(h);
                    o.writeInt(d);
                    o.writeInt(palette.size());
                    for (String s : palette) {
                        o.writeUTF(s);
                    }
                    int i = 0;
                    while (i < cells.length) {
                        int j = i;
                        while (j < cells.length && cells[j] == cells[i]) {
                            j++;
                        }
                        o.writeInt(j - i);
                        o.writeShort(cells[i]);
                        i = j;
                    }
                }
                Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[BossSim] {} boss capture saved to {} ({} block states)", phase, out, palette.size());
                mc.execute(() -> ModChat.send("Sim", ModChat.text("Boss room captured in " + phase + ": " + seen + "/"
                        + columns + " columns loaded.")));
            } catch (IOException e) {
                LOGGER.error("[BossSim] could not save the boss capture", e);
                mc.execute(() -> ModChat.send("Sim", ModChat.text("Could not save the boss capture - see the log.")));
            }
        }, "killer560smod-boss-capture");
        writer.setDaemon(true);
        writer.start();
    }
}
