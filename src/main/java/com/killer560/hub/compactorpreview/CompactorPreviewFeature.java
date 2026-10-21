package com.killer560.hub.compactorpreview;

import com.killer560.hub.itembrowser.ItemBrowserConfig;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.petwheel.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Compactor Preview" - hover a Personal Compactor or Personal Deletor in a container and the items you have set up in
 * it are drawn as a small slot grid inside the item's tooltip, so you do not have to open its menu to see them. Shown
 * on hover, or only while a key is held (setting). Visual only, on both builds.
 * <p>
 * Data shape (read from Skyblocker's open source behaviour and the wiki, checked against the item ids): the item's
 * custom data holds one string entry per configured slot, {@code personal_compact_<n>} or {@code personal_deletor_<n>},
 * whose value is the SkyBlock id of the item in slot n; a deletor also has {@code PERSONAL_DELETOR_ACTIVE}. Sizes by
 * tier (rows x columns): 4000 1x1, 5000 1x3, 6000 1x7, 7000 2x6 (the wiki lists 12 slots for the 7000).
 * <p>
 * The grid is a {@link ClientTooltipComponent} spliced into the tooltip list by {@code CompactorPreviewTooltipMixin}
 * just before vanilla draws it, so it moves, flips and clamps with the tooltip like any other line.
 */
public final class CompactorPreviewFeature {

    private static final Pattern ITEM_ID = Pattern.compile("^PERSONAL_(COMPACTOR|DELETOR)_(\\d{1,5})$");

    /** Rows x columns by tier. */
    private static final Map<String, int[]> SIZES = Map.of(
            "4000", new int[]{1, 1},
            "5000", new int[]{1, 3},
            "6000", new int[]{1, 7},
            "7000", new int[]{2, 6});
    private static final int[] DEFAULT_SIZE = {1, 6};

    private CompactorPreviewFeature() {
    }

    /** What a compactor or deletor holds. {@code slots}: slot number -> SkyBlock id, in slot order. */
    public record Contents(boolean deletor, String tier, int rows, int cols, Map<Integer, String> slots, Boolean active) {
        public int capacity() {
            return rows * cols;
        }
    }

    /** The contents of {@code stack}, or null when it is not a Personal Compactor / Deletor. Public for the tests. */
    public static Contents contentsOf(ItemStack stack) {
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null) {
            return null;
        }
        Matcher m = ITEM_ID.matcher(tag.getStringOr("id", ""));
        if (!m.matches()) {
            return null;
        }
        boolean deletor = m.group(1).equals("DELETOR");
        String tier = m.group(2);
        String prefix = deletor ? "personal_deletor_" : "personal_compact_";
        Map<Integer, String> slots = new TreeMap<>();
        for (String key : tag.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(key.substring(prefix.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            String value = tag.getStringOr(key, "");
            if (index >= 0 && index < 64 && !value.isEmpty()) {
                slots.put(index, value);
            }
        }
        int[] size = SIZES.getOrDefault(tier, DEFAULT_SIZE);
        Boolean active = tag.contains("PERSONAL_DELETOR_ACTIVE") ? tag.getBooleanOr("PERSONAL_DELETOR_ACTIVE", false) : null;
        return new Contents(deletor, tier, size[0], size[1], slots, active);
    }

    // ------------------------------------------------------------------ gating

    /** Whether the grid should be added to a tooltip this frame: feature on, Skyblock gate, mode/key rule. */
    public static boolean visible() {
        CompactorPreviewConfig cfg = CompactorPreviewConfig.getInstance();
        if (!cfg.isEnabledRaw()) {
            return false;
        }
        if (cfg.isSkyblockOnly() && !SkyblockGate.allows()) {
            return false;
        }
        Boolean forced = keyHeldForTest;
        if (forced != null) {
            return modeAllows(cfg.getMode(), forced);
        }
        Minecraft mc = Minecraft.getInstance();
        return modeAllows(cfg.getMode(), mc != null && KeyUtil.isBindDown(mc.getWindow(), cfg.getKeyCode()));
    }

    /** Test hook (docs/TESTING-HOOKS.md): when non-null it replaces the polled key state, because a harness key press
     *  never reaches the GLFW poll. Always null in play. */
    public static volatile Boolean keyHeldForTest;

    /** The mode rule on its own, public for the tests. */
    public static boolean modeAllows(CompactorPreviewConfig.Mode mode, boolean keyHeld) {
        return mode == CompactorPreviewConfig.Mode.ON_HOVER || keyHeld;
    }

    // ------------------------------------------------------------------ tooltip hook

    /** The container slot under the cursor, or null. */
    private static ItemStack hoveredStack() {
        Screen screen = com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance());
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return null;
        }
        Slot hovered = ((AbstractContainerScreenAccessor) container).killer560smod$getHoveredSlot();
        return hovered == null ? null : hovered.getItem();
    }

    /** Called by the tooltip mixin with the list about to be drawn; returns it unchanged, or a copy with the grid added. */
    public static List<ClientTooltipComponent> augment(List<ClientTooltipComponent> components) {
        if (!visible()) {
            return components;
        }
        ItemStack stack = hoveredStack();
        if (stack == null || stack.isEmpty()) {
            return components;
        }
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null) {
            return components;
        }
        // Hover path runs every frame: the answer for one stack's tag (contents, icons, built lines) is kept, so a hover costs
        // an identity compare. Re-resolved after a couple of seconds so an icon catalog that finishes loading is picked up.
        long now = System.nanoTime();
        CachedExtra c = cache;
        if (c == null || c.tag != tag || now - c.at > CACHE_NANOS) {
            c = new CachedExtra(tag, now, buildExtra(stack));
            cache = c;
        }
        return c.extra == null ? components : withExtra(components, c.extra);
    }

    private static final long CACHE_NANOS = 2_000_000_000L;

    private record CachedExtra(CompoundTag tag, long at, List<ClientTooltipComponent> extra) {
    }

    private static volatile CachedExtra cache;

    /** {@link #augment(List)} for a known stack and without the visibility check. Public for the tests. */
    public static List<ClientTooltipComponent> augment(List<ClientTooltipComponent> components, ItemStack stack) {
        List<ClientTooltipComponent> extra = buildExtra(stack);
        return extra == null ? components : withExtra(components, extra);
    }

    private static List<ClientTooltipComponent> withExtra(List<ClientTooltipComponent> components, List<ClientTooltipComponent> extra) {
        List<ClientTooltipComponent> out = new ArrayList<>(components.size() + extra.size());
        out.addAll(components);
        out.addAll(insertionIndex(components), extra);
        return out;
    }

    /** The lines to add for {@code stack}, or null when it is not a compactor / deletor. */
    private static List<ClientTooltipComponent> buildExtra(ItemStack stack) {
        Contents contents = contentsOf(stack);
        if (contents == null) {
            return null;
        }
        List<ClientTooltipComponent> extra = new ArrayList<>(2);
        if (contents.active() != null) {
            extra.add(ClientTooltipComponent.create(Component.literal("Active: ")
                    .append(Component.literal(contents.active() ? "YES" : "NO")
                            .withStyle(ChatFormatting.BOLD, contents.active() ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .getVisualOrderText()));
        }
        if (contents.slots().isEmpty()) {
            int n = contents.capacity();
            extra.add(ClientTooltipComponent.create(Component.literal(n + (n == 1 ? " slot" : " slots"))
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText()));
        } else {
            extra.add(new CompactorGridComponent(contents, resolveStacks(contents)));
        }
        return extra;
    }

    /** Before the second blank line (the gap that follows the item's description), else at the very end. */
    private static int insertionIndex(List<ClientTooltipComponent> components) {
        // Blank lines are components of height 10 and width 0; the first text line is the name. Position is cosmetic, so
        // when nothing recognisable is found the grid simply goes last.
        int blanks = 0;
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < components.size(); i++) {
            ClientTooltipComponent c = components.get(i);
            if (c.getWidth(mc.font) == 0 && !(c instanceof CompactorGridComponent)) {
                blanks++;
                if (blanks == 2) {
                    return i;
                }
            }
        }
        return components.size();
    }

    /** The icon for each configured slot, in slot order. Public for the tests. */
    public static Map<Integer, ItemStack> resolveStacks(Contents contents) {
        Map<Integer, ItemStack> out = new TreeMap<>();
        for (Map.Entry<Integer, String> e : contents.slots().entrySet()) {
            out.put(e.getKey(), iconFor(e.getValue()));
        }
        return out;
    }

    private static ItemStack iconFor(String skyblockId) {
        try {
            // The real catalog (skins, models): whatever is already on disk, with the Item Browser on or off. The Item Browser
            // switch only controls downloading it; a missing entry falls back to the offline look-alike below.
            SkyblockItemEntry entry = SkyblockItemRepository.findOnDisk(skyblockId);
            if (entry == null && ItemBrowserConfig.getInstance().isEnabled()) {
                entry = SkyblockItemRepository.findById(skyblockId);
            }
            if (entry != null) {
                return SkyblockItemStackFactory.build(entry);
            }
        } catch (Exception ignored) {
            // fall through to the offline look-alike
        }
        return ItemIcons.forId(skyblockId);
    }
}
