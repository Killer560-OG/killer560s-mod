package com.killer560.hub.auction;

import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.packdisabler.ItemLooks;
import com.killer560.hub.packdisabler.PackDisabler;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real icon of every Bazaar product. Until 2026-10-07 the browser built each icon once, at fetch time, from the
 * live item catalog alone, and 1,744 of 2,201 products drew as paper with Hypixel's resource pack not loaded: every
 * enchantment, shard, essence and faction rabbit (not in that catalog), every legacy material the old remap table did
 * not list ({@code SEEDS}, {@code CARROT_ITEM}, {@code INK_SACK:3}...), and every item whose catalog entry is only a
 * resource-pack model on a paper base (Umber, Tungsten, all gemstones). Order of preference here:
 * <ol>
 *   <li>Hypixel's own resource-pack model, when that model is really loaded (checked against the client's resources,
 *       so a pack disabler or a lobby without the pack falls through instead of drawing paper);</li>
 *   <li>the shared SkyBlock item table ({@link ItemLooks}, via {@link BazaarCatalog}): a player-head texture, a
 *       modern item id, or a 1.8 id + damage through vanilla's flattening fix ({@link LegacyItems#legacyItem}), or
 *       for an item that never had one, Pack Disabler's own texture;</li>
 *   <li>the live item catalog, for products newer than the table;</li>
 *   <li>paper, counted as a fallback ({@link #fallbacks}).</li>
 * </ol>
 * Built lazily on the render thread and cached; nothing here touches the network.
 */
public final class BazaarIcons {

    /** Where an icon came from. {@code FALLBACK} is the only one that means "we do not know this item". */
    public enum Source { PACK_MODEL, SKULL, VANILLA, LEGACY, OWN_TEXTURE, CATALOG, FALLBACK }

    private record Resolved(ItemStack stack, Source source) {
    }

    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MODEL_LOADED = new ConcurrentHashMap<>();

    private BazaarIcons() {
    }

    public static ItemStack icon(String productId) {
        return resolve(productId).stack();
    }

    public static Source source(String productId) {
        return resolve(productId).source();
    }

    /** Products in {@code ids} that resolve to nothing but the paper fallback. */
    public static List<String> fallbacks(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (source(id) == Source.FALLBACK) {
                out.add(id);
            }
        }
        return out;
    }

    /** Forget which resource-pack models are loaded (call when a screen opens: packs change on a server switch). */
    public static void revalidatePackModels() {
        MODEL_LOADED.clear();
        CACHE.values().removeIf(r -> r.source() == Source.PACK_MODEL);
        CACHE.entrySet().removeIf(e -> {
            BazaarCatalog.Entry entry = BazaarCatalog.get(e.getKey());
            return entry != null && entry.packModel() != null;
        });
    }

    private static Resolved resolve(String productId) {
        if (productId == null) {
            return new Resolved(new ItemStack(Items.PAPER), Source.FALLBACK);
        }
        Resolved cached = CACHE.get(productId);
        if (cached != null) {
            return cached;
        }
        Resolved r;
        try {
            r = build(productId);
        } catch (Exception e) {
            r = new Resolved(new ItemStack(Items.PAPER), Source.FALLBACK);
        }
        CACHE.put(productId, r);
        return r;
    }

    private static Resolved build(String productId) {
        BazaarCatalog.Entry e = BazaarCatalog.get(productId);
        if (e != null) {
            Resolved base = fromTable(e);
            if (e.packModel() != null && packModelLoaded(e.packModel())) {
                ItemStack stack = base.source() == Source.FALLBACK || base.source() == Source.SKULL
                        ? new ItemStack(Items.PAPER) : base.stack().copy();
                stack.set(DataComponents.ITEM_MODEL, Identifier.tryParse(e.packModel()));
                glint(stack, e.glint());
                return new Resolved(stack, Source.PACK_MODEL);
            }
            if (base.source() != Source.FALLBACK) {
                return base;
            }
        }
        SkyblockItemEntry live = SkyblockItemRepository.findById(productId);
        if (live != null) {
            if (live.skinValue() != null) {
                return new Resolved(SkyblockItemStackFactory.build(live), Source.CATALOG);
            }
            if (live.itemModel() != null && packModelLoaded(live.itemModel())) {
                return new Resolved(SkyblockItemStackFactory.build(live), Source.PACK_MODEL);
            }
            ItemStack stack = ItemIcons.forId(live.material());
            if (!stack.is(Items.PAPER) || "PAPER".equals(live.material()) && live.itemModel() == null) {
                return new Resolved(stack, Source.CATALOG);
            }
        }
        return new Resolved(new ItemStack(Items.PAPER), Source.FALLBACK);
    }

    private static Resolved fromTable(BazaarCatalog.Entry e) {
        if (e.skinHash() != null) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + e.skinHash() + "\"}}}";
            String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            stack.set(DataComponents.PROFILE, LegacyItems.skullProfile(value));
            glint(stack, e.glint());
            return new Resolved(stack, Source.SKULL);
        }
        if (e.vanillaId() != null) {
            Identifier id = Identifier.tryParse("minecraft:" + e.vanillaId());
            Item item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null && item != Items.AIR) {
                ItemStack stack = new ItemStack(item);
                glint(stack, e.glint());
                return new Resolved(stack, Source.VANILLA);
            }
        }
        if (e.legacy() != null) {
            int colon = e.legacy().lastIndexOf(':');
            String name = colon > 0 ? e.legacy().substring(0, colon) : e.legacy();
            int damage = 0;
            try {
                damage = colon > 0 ? Integer.parseInt(e.legacy().substring(colon + 1)) : 0;
            } catch (NumberFormatException ignored) {
            }
            Item item = LegacyItems.legacyItem(name, damage);
            // A paper result is only an icon when paper is what the item really is (Enchanted Paper's base is paper,
            // but a pack-model item's base is paper too, and that one we do not know).
            if (item != null && (item != Items.PAPER || e.packModel() == null)) {
                ItemStack stack = new ItemStack(item);
                glint(stack, e.glint());
                return new Resolved(stack, Source.LEGACY);
            }
        }
        if (e.ownTexture() != null) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, ItemLooks.ownModel(e.ownTexture()));
            glint(stack, e.glint());
            return new Resolved(stack, Source.OWN_TEXTURE);
        }
        return new Resolved(new ItemStack(Items.PAPER), Source.FALLBACK);
    }

    private static void glint(ItemStack stack, boolean on) {
        if (on) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
    }

    /** Is {@code model} (an item_model id) defined by a loaded resource pack? Its definition lives at
     *  {@code assets/<ns>/items/<path>.json}. A pack disabler means Hypixel's pack never loads, so skip the lookup. */
    static boolean packModelLoaded(String model) {
        // Either pack disabler (Noamm's mod, or ours switched on) means the old look is wanted, not Hypixel's model.
        if (model == null || DetectedMods.isPackDisablerActive() || PackDisabler.isActive()) {
            return false;
        }
        return MODEL_LOADED.computeIfAbsent(model, m -> {
            try {
                Identifier id = Identifier.tryParse(m);
                if (id == null) {
                    return false;
                }
                Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json");
                return Minecraft.getInstance().getResourceManager().getResource(file).isPresent();
            } catch (Exception ex) {
                return false;
            }
        });
    }
}
