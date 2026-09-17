package com.killer560.hub.armourdye;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a saved {@code material}/{@code pattern} identifier pair to a real {@link ArmorTrim}, the way Skyblocker's
 * {@code CustomArmorTrims} does. Trims are the other half of "armor skin": the skin decides which armour texture is
 * painted, the trim is the overlay on top of it, and both are read straight off the stack's data components by
 * {@code EquipmentLayerRenderer} - so overriding the component covers the body and the inventory icon at once.
 * <p>
 * Trim materials and patterns are datapack registries, so they only exist once a world is loaded. Everything here is
 * built lazily on first use from {@code level.registryAccess()} and thrown away on
 * {@link #invalidate()} when the world changes; until then {@link #resolve} just returns null and the real trim is
 * left alone. Nothing throws out of here - a failed lookup is a missing trim, never a broken frame.
 */
public final class ArmourTrims {

    private static final Map<String, ArmorTrim> CACHE = new HashMap<>();
    private static List<String> materialIds = List.of();
    private static List<String> patternIds = List.of();
    private static boolean built = false;

    private ArmourTrims() {
    }

    /** Called on world change / config reload so a new datapack's trims are picked up. */
    public static synchronized void invalidate() {
        CACHE.clear();
        materialIds = List.of();
        patternIds = List.of();
        built = false;
    }

    /** @return the trim for this pair, or null when either id is blank, unknown, or no world is loaded yet. */
    public static synchronized ArmorTrim resolve(String materialId, String patternId) {
        if (materialId == null || patternId == null || materialId.isBlank() || patternId.isBlank()) {
            return null;
        }
        build();
        return CACHE.get(materialId.trim() + "|" + patternId.trim());
    }

    /** Sorted material ids for the settings tab's cycle button; empty until a world is loaded. */
    public static synchronized List<String> materials() {
        build();
        return materialIds;
    }

    /** Sorted pattern ids for the settings tab's cycle button; empty until a world is loaded. */
    public static synchronized List<String> patterns() {
        build();
        return patternIds;
    }

    private static void build() {
        if (built) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return;
        }
        try {
            HolderLookup.Provider lookup = client.level.registryAccess();
            List<Holder.Reference<TrimMaterial>> mats =
                    lookup.lookupOrThrow(Registries.TRIM_MATERIAL).listElements().toList();
            List<Holder.Reference<TrimPattern>> pats =
                    lookup.lookupOrThrow(Registries.TRIM_PATTERN).listElements().toList();
            List<String> matIds = new ArrayList<>(mats.size());
            List<String> patIds = new ArrayList<>(pats.size());
            for (Holder.Reference<TrimMaterial> mat : mats) {
                Identifier matId = mat.key().identifier();
                matIds.add(matId.toString());
                for (Holder.Reference<TrimPattern> pat : pats) {
                    Identifier patId = pat.key().identifier();
                    CACHE.put(matId + "|" + patId, new ArmorTrim(mat, pat));
                }
            }
            for (Holder.Reference<TrimPattern> pat : pats) {
                patIds.add(pat.key().identifier().toString());
            }
            Collections.sort(matIds);
            Collections.sort(patIds);
            materialIds = List.copyOf(matIds);
            patternIds = List.copyOf(patIds);
            built = true;
        } catch (Exception e) {
            // Registries not ready (or a datapack removed one): stay unbuilt and try again next frame.
            CACHE.clear();
        }
    }
}
