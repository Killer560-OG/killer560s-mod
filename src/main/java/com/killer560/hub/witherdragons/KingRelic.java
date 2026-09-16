package com.killer560.hub.witherdragons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * The five Corrupted King Relics. Skyblock item ids and cauldron positions from Odin's {@code KingRelics.Relic}
 * (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/features/impl/boss/KingRelics.kt);
 * item names ("Corrupted Red Relic") and the placed-relic armor-stand x/z ({@code coords}) from NoammAddons'
 * {@code WitherRelic.kt} (local copy C:\Users\Hunter\noammaddonsmod, utils/dungeons/enums). Both sources agree
 * on all five cauldron positions.
 */
public enum KingRelic {
    GREEN("GREEN_KING_RELIC", "Green", 'a', 0xFF55FF55, new BlockPos(49, 7, 44), 50, 45),
    PURPLE("PURPLE_KING_RELIC", "Purple", '5', 0xFFAA00AA, new BlockPos(54, 7, 41), 55, 42),
    BLUE("BLUE_KING_RELIC", "Blue", 'b', 0xFF5555FF, new BlockPos(59, 7, 44), 60, 45),
    ORANGE("ORANGE_KING_RELIC", "Orange", '6', 0xFFFFAA00, new BlockPos(57, 7, 42), 58, 43),
    RED("RED_KING_RELIC", "Red", 'c', 0xFFFF5555, new BlockPos(51, 7, 42), 52, 43);

    public final String id;
    public final String colourName;
    public final char colorCode;
    public final int argb;
    public final BlockPos cauldronPos;
    final int placedX;
    final int placedZ;

    KingRelic(String id, String colourName, char colorCode, int argb, BlockPos cauldronPos, int placedX, int placedZ) {
        this.id = id;
        this.colourName = colourName;
        this.colorCode = colorCode;
        this.argb = argb;
        this.cauldronPos = cauldronPos;
        this.placedX = placedX;
        this.placedZ = placedZ;
    }

    public String colored() {
        return "§" + colorCode + colourName;
    }

    /** "Corrupted Red Relic" (NoammAddons formalName). */
    public String itemName() {
        return "Corrupted " + colourName + " Relic";
    }

    /** By Skyblock id first (Odin), then by item name colour (NoammAddons) - e.g. on p3sim, where items may
     *  not carry Hypixel's custom data. */
    public static KingRelic fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = skyblockId(stack);
        if (id != null) {
            for (KingRelic r : values()) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
        }
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return name == null ? null : fromName(name);
    }

    /** Hypixel Skyblock item id from CUSTOM_DATA "id" (same technique as CheatUtils/DungeonBreakerFeature). */
    static String skyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    public static KingRelic fromName(String name) {
        String trimmed = name.trim();
        for (KingRelic r : values()) {
            if (r.itemName().equalsIgnoreCase(trimmed)) {
                return r;
            }
        }
        return null;
    }

    public static KingRelic fromColour(String colour) {
        for (KingRelic r : values()) {
            if (r.colourName.equalsIgnoreCase(colour.trim())) {
                return r;
            }
        }
        return null;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
