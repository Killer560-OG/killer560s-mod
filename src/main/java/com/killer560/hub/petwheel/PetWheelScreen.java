package com.killer560.hub.petwheel;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The radial menu itself - opened by {@link PetWheelFeature} on the wheel keybind, never while another
 * screen is open. Picking a slice hands the pet to {@link PetWheelFeature#summon} and closes.
 * <p>
 * Rendering is deliberately simple: each slice is a themed square tile (icon + name) placed on a ring by
 * angle, hit-tested by the same atan2 sector math NoammAddons' own {@code PetMenu} wheel uses - not a filled
 * pie/annulus wedge. This codebase has no polygon/arc-fill primitive anywhere yet (checked
 * {@code SpiritLeapOverlayFeature} and {@code LeapOrderScreen}, this mod's two closest "replace a menu with a
 * custom shape" precedents - both draw only rectangles), and a hand-written triangle-fan renderer that could
 * not be verified against the real 26.1.2 {@code GuiGraphicsExtractor} without a live run felt like the
 * highest-risk part of this feature to ship unverified. See this wave's staging notes.
 * <p>
 * Per-frame cost: the render loop is O(slice count) (capped at {@link PetWheelConfig#MAX_SLICES}, 12), every
 * per-pet {@link ItemStack} icon is built once per screen instance and cached in {@link #iconCache} rather
 * than rebuilt every frame, and nothing here allocates per frame beyond the fixed-size per-slice draw calls.
 */
public class PetWheelScreen extends Screen {

    private static final int BG_DIM = 0x99000000;
    private static final int TILE_BG = 0xFF1A1A1A;
    private static final int TILE_BG_HOVER = 0xFF262626;
    private static final int TILE_BORDER = 0xFF663D1A;
    private static final int TILE_BORDER_HOVER = 0xFFCC6600;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_HOVER = 0xFFCC6600;

    private static final float BASE_RADIUS = 84f;
    private static final int BASE_TILE = 28;

    private final List<PetEntry> allPets;
    private final int sliceCount;
    private final float scale;
    private final PetWheelConfig.InteractionMode mode;
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    private int page = 0;
    private int lastMouseX = 0;
    private int lastMouseY = 0;

    public PetWheelScreen(List<PetEntry> allPets, int sliceCount, float scale, PetWheelConfig.InteractionMode mode) {
        super(Component.literal("Pet Wheel"));
        this.allPets = allPets;
        this.sliceCount = Math.max(1, sliceCount);
        this.scale = scale;
        this.mode = mode;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(allPets.size() / (double) sliceCount));
    }

    private List<PetEntry> currentPagePets() {
        int from = Math.min(page * sliceCount, allPets.size());
        int to = Math.min(from + sliceCount, allPets.size());
        return allPets.subList(from, to);
    }

    private record Layout(float centerX, float centerY, float radius, float innerRadius, int count) {
        float sliceAngle() {
            return count == 0 ? 0f : (float) (Math.PI * 2.0 / count);
        }
    }

    private Layout layout(int count) {
        float radius = BASE_RADIUS * scale;
        return new Layout(width / 2f, height / 2f - 6f, radius, radius * 0.35f, count);
    }

    /** Same sector math as NoammAddons' own pet wheel: no outer bound (direction, not distance, decides the
     *  slice - a radial menu shouldn't need pixel-perfect aim), only the dead-zone in the middle is excluded. */
    private Integer hoveredIndex(double mouseX, double mouseY, Layout layoutData) {
        if (layoutData.count() == 0) {
            return null;
        }
        double x = mouseX - layoutData.centerX();
        double y = mouseY - layoutData.centerY();
        if (Math.hypot(x, y) < layoutData.innerRadius()) {
            return null;
        }
        double sliceAngle = layoutData.sliceAngle();
        int idx = Math.floorMod((int) Math.floor((Math.atan2(y, x) + Math.PI / 2.0 + sliceAngle / 2.0) / sliceAngle), layoutData.count());
        return idx;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        graphics.fill(0, 0, width, height, BG_DIM);

        List<PetEntry> visible = currentPagePets();
        Layout layoutData = layout(visible.size());
        Integer hovered = hoveredIndex(mouseX, mouseY, layoutData);

        int tile = Math.max(16, Math.round(BASE_TILE * scale));
        for (int i = 0; i < visible.size(); i++) {
            drawSlice(graphics, visible.get(i), i, layoutData, tile, hovered != null && hovered == i);
        }

        if (pageCount() > 1) {
            graphics.centeredText(font, "Page " + (page + 1) + "/" + pageCount(),
                    (int) layoutData.centerX(), (int) (layoutData.centerY() - layoutData.radius() - tile - 22), TEXT);
            graphics.centeredText(font, "Scroll to flip pages",
                    (int) layoutData.centerX(), (int) (layoutData.centerY() - layoutData.radius() - tile - 11), TEXT);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSlice(GuiGraphicsExtractor graphics, PetEntry pet, int index, Layout layoutData, int tile, boolean hovered) {
        double angle = -Math.PI / 2.0 + index * layoutData.sliceAngle();
        float px = layoutData.centerX() + (float) (Math.cos(angle) * layoutData.radius());
        float py = layoutData.centerY() + (float) (Math.sin(angle) * layoutData.radius());
        int x0 = Math.round(px - tile / 2f);
        int y0 = Math.round(py - tile / 2f);

        graphics.fill(x0, y0, x0 + tile, y0 + tile, hovered ? TILE_BG_HOVER : TILE_BG);
        graphics.outline(x0, y0, tile, tile, hovered ? TILE_BORDER_HOVER : TILE_BORDER);
        graphics.item(iconFor(pet), x0 + (tile - 16) / 2, y0 + (tile - 16) / 2);
        graphics.centeredText(font, pet.shortLabel(), Math.round(px), y0 + tile + 2, hovered ? TEXT_HOVER : TEXT);
    }

    /** Built once per pet per screen instance and cached - see the class doc's per-frame-cost note. Same
     *  {@code minecraft:profile} technique {@code SkyblockItemStackFactory.build} already uses for a real
     *  textured head; falls back to a plain player head when the pet's skin wasn't captured yet. */
    private ItemStack iconFor(PetEntry pet) {
        return iconCache.computeIfAbsent(pet.uuid(), id -> {
            if (pet.skinValue() == null) {
                return new ItemStack(Items.PLAYER_HEAD);
            }
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            Multimap<String, Property> backing = HashMultimap.create();
            backing.put("textures", new Property("textures", pet.skinValue(), null));
            PropertyMap properties = new PropertyMap(backing);
            GameProfile profile = new GameProfile(UUID.randomUUID(), "PetWheel", properties);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
            return stack;
        });
    }

    /** Called by {@link PetWheelFeature} on the bind's key-up edge in hold-release mode. */
    void confirmSelection() {
        List<PetEntry> visible = currentPagePets();
        Layout layoutData = layout(visible.size());
        Integer hovered = hoveredIndex(lastMouseX, lastMouseY, layoutData);
        PetEntry chosen = hovered != null && hovered < visible.size() ? visible.get(hovered) : null;
        select(chosen);
    }

    private void select(PetEntry chosen) {
        if (chosen != null) {
            PetWheelFeature.summon(chosen);
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (mode != PetWheelConfig.InteractionMode.PRESS_CLICK) {
            return false;
        }
        List<PetEntry> visible = currentPagePets();
        Layout layoutData = layout(visible.size());
        Integer hovered = hoveredIndex(event.x(), event.y(), layoutData);
        select(hovered != null && hovered < visible.size() ? visible.get(hovered) : null);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int pages = pageCount();
        if (pages > 1 && scrollY != 0) {
            page = Math.floorMod(page + (scrollY < 0 ? 1 : -1), pages);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
