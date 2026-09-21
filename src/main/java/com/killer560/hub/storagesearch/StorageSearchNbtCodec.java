package com.killer560.hub.storagesearch;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Full-fidelity {@link ItemStack} list &lt;-&gt; Base64 string, so this feature's own caches (island chests,
 *  wardrobe/equipment/pets pages) persist exactly the way {@code StorageOverlayCache} already persists Ender Chest
 *  pages and backpacks - same codec, same compression, same "a Base64 string fits in the mod's plain JSON config
 *  style" reasoning. Split out here because two of this feature's caches need it and neither is allowed to reach
 *  into Storage Overlay's private copy. */
final class StorageSearchNbtCodec {

    private StorageSearchNbtCodec() {
    }

    static String encode(List<ItemStack> stacks) {
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) {
            return null;
        }
        HolderLookup.Provider provider = registryAccess;
        var ops = provider.createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            CompoundTag tag = new CompoundTag();
            if (stack != null && !stack.isEmpty()) {
                Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
                if (encoded instanceof CompoundTag stackTag) {
                    tag = stackTag;
                }
            }
            list.add(tag);
        }
        try {
            CompoundTag root = new CompoundTag();
            root.put("i", list);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    static List<ItemStack> decode(String blob) {
        if (blob == null || blob.isEmpty()) {
            return null;
        }
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) {
            return null;
        }
        try {
            HolderLookup.Provider provider = registryAccess;
            var ops = provider.createSerializationContext(NbtOps.INSTANCE);
            byte[] bytes = Base64.getDecoder().decode(blob);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            List<ItemStack> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                result.add(tag.isEmpty() ? ItemStack.EMPTY : ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cheap "did anything actually change" test, same item-id + count comparison {@code captureIfChanged} uses -
     *  keeps a per-tick re-capture from rewriting the cache file every tick once it has stabilised. */
    static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ItemStack sa = a.get(i);
            ItemStack sb = b.get(i);
            boolean emptyA = sa == null || sa.isEmpty();
            boolean emptyB = sb == null || sb.isEmpty();
            if (emptyA != emptyB) {
                return false;
            }
            if (emptyA) {
                continue;
            }
            if (sa.getCount() != sb.getCount() || !sa.getItem().equals(sb.getItem())) {
                return false;
            }
        }
        return true;
    }

    private static RegistryAccess registryAccess() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            return client.level.registryAccess();
        }
        return client.getConnection() != null ? client.getConnection().registryAccess() : null;
    }
}
