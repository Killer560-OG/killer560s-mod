package com.killer560.hub.compactorpreview;

import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.petwheel.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.profileviewer.data.SbProfile;
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
 * "Compactor Preview" - hover a Personal Compactor and its tooltip lists the Sack items it compacts; hover a Personal
 * Deletor and the items it deletes are drawn as a small slot grid, so you do not have to open either menu. Shown on
 * hover, or only while a key is held (setting). Visual only, on both builds.
 * <p>
 * Data shape: the item's custom data holds one string entry per configured item, {@code personal_compact_<n>} or
 * {@code personal_deletor_<n>}, whose value is a SkyBlock id; a deletor also has {@code PERSONAL_DELETOR_ACTIVE}.
 * SkyBlock 0.27.3 (2026-11-17) reworked compactors: no slots, they compact the Sack items you toggle on, and each skill
 * has its own ({@code PERSONAL_COMPACTOR_<SKILL>_4000}). Where that version keeps its toggles is not captured yet
 * (killer560s-mod-logs/pv0273-1117/NEEDS-CAPTURE.md), so a compactor lists the ids it carries as ON and otherwise points
 * at /compactor. Deletor slots by tier (rows of 7): 4000 7, 5000 14, 6000 21, 7000 28 (patch notes 0.27.3).
 * <p>
 * The grid and lines are {@link ClientTooltipComponent}s spliced into the tooltip list by {@code CompactorPreviewTooltipMixin}
 * just before vanilla draws it, so they move, flip and clamp with the tooltip like any other line.
 */
public final class CompactorPreviewFeature {

    private static final Pattern ITEM_ID = Pattern.compile("^PERSONAL_(COMPACTOR|DELETOR)_(?:([A-Z]{1,16})_)?(\\d{1,5})$");

    /** A deletor's rows of 7 by tier. */
    private static final Map<String, Integer> DELETOR_ROWS = Map.of("4000", 1, "5000", 2, "6000", 3, "7000", 4);
    /** Most compactor lines listed before "+N more". */
    private static final int MAX_LINES = 12;

    private CompactorPreviewFeature() {
    }

    /** What a compactor or deletor holds. {@code slots}: slot number -> SkyBlock id, in slot order; {@code skill} is the
     *  skill of a 0.27.3 compactor ("MINING"), else null; a compactor has no grid (0 rows). */
    public record Contents(boolean deletor, String skill, String tier, int rows, int cols, Map<Integer, String> slots, Boolean active) {
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
        String skill = m.group(2);
        String tier = m.group(3);
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
        int rows = deletor ? DELETOR_ROWS.getOrDefault(tier, 1) : 0;
        Boolean active = tag.contains("PERSONAL_DELETOR_ACTIVE") ? tag.getBooleanOr("PERSONAL_DELETOR_ACTIVE", false) : null;
        return new Contents(deletor, skill, tier, rows, deletor ? 7 : 0, slots, active);
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

    /** The lines to add for {@code stack}, or null when it is not a compactor / deletor. */
    private static List<ClientTooltipComponent> buildExtra(ItemStack stack) {
        Contents contents = contentsOf(stack);
        if (contents == null) {
            return null;
        }
        List<ClientTooltipComponent> extra = new ArrayList<>(4);
        if (!contents.deletor()) {
            for (Component line : compactorLines(contents)) {
                extra.add(ClientTooltipComponent.create(line.getVisualOrderText()));
            }
            return extra;
        }
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

    /**
     * A compactor's lines: "Compacts:" and each Sack item it carries turned ON (by name, at most {@link #MAX_LINES}),
     * or, when the item carries none, where to set them. Public for the tests.
     */
    public static List<Component> compactorLines(Contents contents) {
        List<Component> out = new ArrayList<>();
        if (contents.skill() != null) {
            out.add(Component.literal(SbProfile.titleCase(contents.skill()) + " Compactor").withStyle(ChatFormatting.GRAY));
        }
        if (contents.slots().isEmpty()) {
            out.add(Component.literal("Choose the Sack items it compacts in /compactor").withStyle(ChatFormatting.GRAY));
            return out;
        }
        out.add(Component.literal("Compacts:").withStyle(ChatFormatting.GRAY));
        int shown = 0;
        for (String id : contents.slots().values()) {
            if (shown++ == MAX_LINES) {
                out.add(Component.literal(" +" + (contents.slots().size() - MAX_LINES) + " more").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            out.add(Component.literal(" ON ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(nameFor(id)).withStyle(ChatFormatting.WHITE)));
        }
        return out;
    }

    /** The catalog's plain name for an id, else the id in title case. */
    private static String nameFor(String id) {
        try {
            SkyblockItemEntry e = SkyblockItemRepository.findOnDisk(id);
            if (e != null && e.name() != null) {
                String plain = ChatFormatting.stripFormatting(e.name());
                if (plain != null && !plain.isBlank()) {
                    return plain;
                }
            }
        } catch (Exception ignored) {
            // fall back to the id
        }
        return SbProfile.titleCase(id);
    }
}
