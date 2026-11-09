package com.killer560.hub.dungeonqol;

import com.killer560.hub.util.ItemNbt;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Item Quality tooltip (Athen, featE-1108): a dungeon drop carries {@code baseStatBoostPercentage} (0-50) and
 *  {@code item_tier} (the floor it dropped on, 0 = Entrance) in its custom data; the tooltip gets one last line,
 *  "Item Quality: 47/50 (Floor 7)". Only the drawn tooltip changes. */
public final class ItemQualityFeature {

    private static int lastKey = -1;
    private static Component lastLine;

    private ItemQualityFeature() {
    }

    /** The tooltip with the quality line added, or {@code lines} itself when the feature is off or the item has none. */
    public static List<Component> addLine(ItemStack stack, List<Component> lines) {
        if (!DungeonQolConfig.getInstance().isItemQuality()) {
            return lines;
        }
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null || !tag.contains("baseStatBoostPercentage")) {
            return lines;
        }
        int quality = tag.getIntOr("baseStatBoostPercentage", 0);
        int floor = tag.getIntOr("item_tier", -1);
        List<Component> out = new ArrayList<>(lines);
        out.add(line(quality, floor));
        return out;
    }

    static Component line(int quality, int floor) {
        int key = quality << 8 | (floor & 0xFF);
        if (key != lastKey || lastLine == null) {
            String where = floor < 0 ? "" : floor == 0 ? " (Entrance)" : " (Floor " + floor + ")";
            ChatFormatting color = quality >= 50 ? ChatFormatting.GOLD : quality >= 40 ? ChatFormatting.GREEN
                    : quality >= 25 ? ChatFormatting.YELLOW : ChatFormatting.RED;
            lastLine = Component.literal("Item Quality: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(quality)).withStyle(color))
                    .append(Component.literal("/50" + where).withStyle(ChatFormatting.GRAY));
            lastKey = key;
        }
        return lastLine;
    }
}
