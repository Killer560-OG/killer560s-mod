package com.killer560.hub.invsort;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ItemNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.TreeSet;

/**
 * Which exact item a layout slot means (killer560, 2026-11-02: with two runebooks, fetching a layout from storage took
 * the WRONG one into his inventory). The Skyblock id alone cannot tell two runebooks, two enchanted books or two
 * Hyperions apart, so a layout saved since 2026-11-03 also keeps an EXACT key per slot, and every place the sorter
 * matches an item (the editor, the sort, the storage fetch and dump) compares those.
 * <p>
 * The key is {@code ID|U:<uuid>} for an item with a Skyblock {@code uuid} (one item in the whole game, whatever is done
 * to it), else {@code ID|D:<hash>} of its whole {@code ExtraAttributes} (enchantments, runes, attributes, gems...) with
 * nested tags read in sorted key order, else {@code ID|-} (no Skyblock data at all: a plain vanilla stack). A slot of an
 * older layout has only the id, which still matches any copy: {@link #same} compares the id part when one side has no
 * {@code |}, so old layouts load and sort exactly as before.
 */
public final class InvSortMatch {

    private InvSortMatch() {
    }

    /** The exact key of {@code stack}, or null for an empty stack or one with no identity. Upper case. */
    public static String exact(ItemStack stack) {
        String id = ItemIdentity.of(stack);
        if (id == null) {
            return null;
        }
        id = id.toUpperCase(Locale.ROOT).replace('|', '_'); // the separator never appears inside an id
        CompoundTag tag = ItemNbt.view(stack); // read only
        if (tag == null || tag.isEmpty()) {
            return id + "|-";
        }
        String uuid = tag.getStringOr("uuid", null);
        if (uuid != null && !uuid.isBlank()) {
            return id + "|U:" + uuid.trim().toUpperCase(Locale.ROOT);
        }
        StringBuilder b = new StringBuilder();
        canonical(tag, b, 0);
        String s = b.toString();
        return id + "|D:" + Integer.toHexString(s.hashCode()).toUpperCase(Locale.ROOT) + Integer.toHexString(s.length())
                .toUpperCase(Locale.ROOT);
    }

    /** The tag as text with every compound's keys sorted, so two copies of one item always read the same. */
    private static void canonical(CompoundTag tag, StringBuilder b, int depth) {
        b.append('{');
        for (String key : new TreeSet<>(tag.keySet())) {
            Tag value = tag.get(key);
            b.append(key).append(':');
            if (value instanceof CompoundTag inner && depth < 16) {
                canonical(inner, b, depth + 1);
            } else {
                b.append(value);
            }
            b.append(',');
        }
        b.append('}');
    }

    /** The id part of a key ({@code HYPERION|U:...} -> {@code HYPERION}); a key with no {@code |} is its own id. */
    public static String id(String key) {
        if (key == null) {
            return null;
        }
        int bar = key.indexOf('|');
        return bar < 0 ? key : key.substring(0, bar);
    }

    public static boolean isExact(String key) {
        return key != null && key.indexOf('|') >= 0;
    }

    /**
     * Whether two keys name the same item: equal, or, when one of them is an id alone (an old layout's slot), the same
     * id. Never true for null.
     */
    public static boolean same(String a, String b) {
        return InvSortPlanner.same(a, b);
    }

    /** Whether {@code stack} is the item {@code want} (an exact key, or an id alone) names. */
    public static boolean matches(ItemStack stack, String want) {
        return want != null && same(exact(stack), want);
    }
}
