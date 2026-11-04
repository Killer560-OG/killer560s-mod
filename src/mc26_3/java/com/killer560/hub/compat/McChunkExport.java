package com.killer560.hub.compat;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a client chunk into the vanilla Anvil chunk tag (world export, dev + cheat builds only). The
 * {@code SerializableChunkData} constructor differs between Minecraft versions (26.2 has a carving-mask array that 26.3
 * dropped), which is why this is a facade; the public signature is identical in every version's copy.
 */
public final class McChunkExport {

    private McChunkExport() {
    }

    /** The chunk as a "full" Anvil chunk: block states, biomes and the given block entities; no light, no heightmaps. */
    public static CompoundTag chunkTag(Level level, LevelChunk chunk, List<CompoundTag> blockEntities) {
        LevelChunkSection[] sections = chunk.getSections();
        List<SerializableChunkData.SectionData> sectionData = new ArrayList<>();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            sectionData.add(new SerializableChunkData.SectionData(chunk.getSectionYFromSectionIndex(i), section, null, null));
        }
        ShortList[] post = new ShortList[chunk.getSectionsCount()];
        for (int i = 0; i < post.length; i++) {
            post[i] = new ShortArrayList();
        }
        SerializableChunkData d;
        d = new SerializableChunkData(level.palettedContainerFactory(), chunk.getPos(), chunk.getMinSectionY(), 0L, 0L,
                ChunkStatus.FULL, null, null, UpgradeData.EMPTY, Map.of(), new ChunkAccess.PackedTicks(List.of(), List.of()),
                post, false, sectionData, List.of(), blockEntities, new CompoundTag());
        return d.write();
    }
}
