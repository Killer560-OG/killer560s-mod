package com.killer560.hub.compactorpreview;

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
 * "Compactor Preview" - hover a Personal Deletor and the items it deletes are drawn as a small slot grid, so you do not
 * have to open its menu (killer560, 2026-11-25: "no preview for anything but the deletor"; compactors get none). Shown
 * on hover, or only while a key is held (setting). Visual only, on both builds.
 * <p>
 * Data shape: the item's custom data holds one string entry per configured item, {@code personal_deletor_<n>}, whose
 * value is a SkyBlock id, and {@code PERSONAL_DELETOR_ACTIVE}. Deletor slots by tier (rows of 7): 4000 7, 5000 14,
 * 6000 21, 7000 28 (patch notes 0.27.3).
 * <p>
 * The grid and lines are {@link ClientTooltipComponent}s spliced into the tooltip list by {@code CompactorPreviewTooltipMixin}
 * just before vanilla draws it, so they move, flip and clamp with the tooltip like any other line.
 */
public final class CompactorPreviewFeature {

    private static final Pattern ITEM_ID = Pattern.compile("^PERSONAL_DELETOR_(\\d{1,5})$");

    /** A deletor's rows of 7 by tier. */
    private static final Map<String, Integer> DELETOR_ROWS = Map.of("4000", 1, "5000", 2, "6000", 3, "7000", 4);

    private CompactorPreviewFeature() {
    }

    /** What a deletor holds. {@code slots}: slot number -> SkyBlock id, in slot order. */
    public record Contents(String tier, int rows, int cols, Map<Integer, String> slots, Boolean active) {
        public int capacity() {
            return rows * cols;
        }
    }

    /** The contents of {@code stack}, or null when it is not a Personal Deletor. Public for the tests. */
    public static Contents contentsOf(ItemStack stack) {
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null) {
            return null;
        }
        Matcher m = ITEM_ID.matcher(tag.getStringOr("id", ""));
        if (!m.matches()) {
            return null;
        }
        String tier = m.group(1);
        String prefix = "personal_deletor_";
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
        int rows = DELETOR_ROWS.getOrDefault(tier, 1);
        Boolean active = tag.contains("PERSONAL_DELETOR_ACTIVE") ? tag.getBooleanOr("PERSONAL_DELETOR_ACTIVE", false) : null;
        return new Contents(tier, rows, 7, slots, active);
    }

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

    /** The lines to add for {@code stack}, or null when it is not a deletor. */
    private static List<ClientTooltipComponent> buildExtra(ItemStack stack) {
        Contents contents = contentsOf(stack);
        if (contents == null) {
            return null;
        }
        List<ClientTooltipComponent> extra = new ArrayList<>(4);
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
        // when nothing recognizable is found the grid simply goes last.
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
        return ItemIcons.catalogIcon(skyblockId);
    }
}
