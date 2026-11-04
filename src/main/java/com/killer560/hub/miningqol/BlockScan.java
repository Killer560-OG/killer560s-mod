package com.killer560.hub.miningqol;

import com.killer560.hub.util.ClientChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * The block scan the mining highlights share: every chunk within {@code radius} blocks of {@code center} and within
 * {@code dy} blocks above and below it, reading only the chunk sections whose palette may hold a wanted block ({@code
 * maybeHas}, the way Secret Waypoints' lever scan and MidasAltar do: the palette answers for a whole section at once).
 */
final class BlockScan {

    private BlockScan() {
    }

    /**
     * Positions inside the box whose state passes {@code palette} and then {@code exact} (which may look at neighbors),
     * at most {@code max} of them, nearest chunks not preferred (callers keep the cap well above a real count).
     */
    static List<BlockPos> scan(Level level, BlockPos center, int radius, int dy, Predicate<BlockState> palette,
                               BiPredicate<BlockPos, BlockState> exact, int max) {
        List<BlockPos> out = new ArrayList<>();
        if (level == null || center == null) return out;
        int minX = center.getX() - radius, maxX = center.getX() + radius;
        int minZ = center.getZ() - radius, maxZ = center.getZ() + radius;
        int minY = center.getY() - dy, maxY = center.getY() + dy;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!ClientChunks.isLoaded(level, cx, cz)) continue;
                var chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(palette)) continue;
                    int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
                    int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
                    int y0 = Math.max(minY, baseY), y1 = Math.min(maxY, baseY + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int y = y0; y <= y1; y++) {
                                BlockState st = section.getBlockState(x & 15, y & 15, z & 15);
                                if (palette.test(st) && exact.test(m.set(x, y, z), st)) {
                                    out.add(m.immutable());
                                    if (out.size() >= max) return out;
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }
}
