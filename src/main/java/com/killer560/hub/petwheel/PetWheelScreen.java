package com.killer560.hub.petwheel;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;

/**
 * The radial menu itself - opened by {@link PetWheelFeature} on the wheel keybind for picking a pet to summon,
 * or by {@link com.killer560.hub.gui.tab.PetWheelTab}'s "Edit Pets" button (via {@link #forEdit}) to rearrange
 * the wheel in place. Picking a slice in the summon path hands the pet to {@link PetWheelFeature#summon} and
 * closes; edit mode never talks to Hypixel itself (see {@link PetWheelEditor} for the one thing that does -
 * opening the real {@code /pets} menu for a right-clicked slot).
 * <p>
 * Rendering is deliberately simple: each slice is a themed square tile (icon + name) placed on a ring by
 * angle, hit-tested by the same atan2 sector math NoammAddons' own {@code PetMenu} wheel uses - not a filled
 * pie/annulus wedge. This codebase has no polygon/arc-fill primitive anywhere yet (checked
 * {@code SpiritLeapOverlayFeature} and {@code LeapOrderScreen}, this mod's two closest "replace a menu with a
 * custom shape" precedents - both draw only rectangles). The divider lines below ARE new (killer560, 2026-09-27:
 * "show the pie chart lines between pets"), and are safe to add despite that: {@code GuiGraphicsExtractor.pose()}'s
 * real return type in this MC version is {@code org.joml.Matrix3x2fStack} (confirmed with {@code javap} against the real
 * 26.1.2 mapped jar for this task, not guessed), which has a real {@code rotate(float)} - translating to the
 * wheel's centre, rotating to a slice boundary and filling a thin rect from the inner dead-zone out to the rim
 * draws exactly one spoke, with the same angle convention {@link #hoveredIndex}/{@link #drawSlice} already use.
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
    private static final int TILE_BORDER_PENDING = 0xFFFFFFFF;
    private static final int TILE_BG_EMPTY = 0xFF141414;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_HOVER = 0xFFCC6600;
    private static final int TEXT_DIM = 0xFF9A8C80;
    /** 50% alpha of {@link #TILE_BORDER_HOVER} - the pie-chart divider spokes, orange-themed like everything
     *  else in this tab rather than a plain white/grey line. */
    private static final int DIVIDER_COLOR = 0x80CC6600;

    private static final float BASE_RADIUS = 84f;
    private static final int BASE_TILE = 28;
    /** Minimum extra room (beyond the tile's own footprint) two neighbouring tiles must keep between their
     *  centres, so a big Icon Size setting pushes the ring outward instead of overlapping slices. */
    private static final float TILE_SPACING_FACTOR = 1.15f;

    private final List<PetEntry> allPets;
    private final int sliceCount;
    private final float scale;
    private final float iconScale;
    private final boolean hideLevel;
    private final boolean hideName;
    private final PetWheelConfig.InteractionMode mode;
    private final boolean editMode;
    private final Screen editParent;
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    private int page = 0;
    private int lastMouseX = 0;
    private int lastMouseY = 0;

    // ---- edit-mode swap/drag state (see the class doc + PetWheelConfig#swapInWheel) ----
    private Integer pendingSwapIndex = null;
    private boolean dragArmed = false;
    private boolean dragging = false;
    private double dragCurrentX = 0;
    private double dragCurrentY = 0;

    /** Live wheel: pick a slice to summon it. */
    public PetWheelScreen(List<PetEntry> allPets, int sliceCount, float scale, PetWheelConfig.InteractionMode mode) {
        this(allPets, sliceCount, scale, mode, false, null);
    }

    private PetWheelScreen(List<PetEntry> allPets, int sliceCount, float scale, PetWheelConfig.InteractionMode mode,
                           boolean editMode, Screen editParent) {
        super(Component.literal(editMode ? "Edit Pets" : "Pet Wheel"));
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        this.allPets = allPets;
        this.sliceCount = Math.max(1, sliceCount);
        this.scale = scale;
        this.iconScale = cfg.getIconScalePercent() / 100f;
        this.hideLevel = cfg.isHideLevel();
        this.hideName = cfg.isHideName();
        this.mode = mode;
        this.editMode = editMode;
        this.editParent = editParent;
    }

    /**
     * killer560's reworked "Edit Pets": clicking it "opens the wheel as is" rather than a separate two-column
     * picker screen. Read literally that would leave no way to add a first pet to an empty wheel at all, so
     * this always shows the wheel's real slices PLUS exactly one trailing empty/"+" slot past the end (see
     * {@link #totalSlots}) - right-clicking it works the same as any other slot and appends instead of
     * replacing. Re-reads {@link PetWheelConfig} fresh every open/reopen so an edit made through
     * {@link PetWheelEditor} is reflected immediately when it reopens this screen.
     */
    public static PetWheelScreen forEdit(Screen parent) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        return new PetWheelScreen(cfg.getWheelPets(), cfg.getSliceCount(), cfg.getScalePercent() / 100f,
                cfg.getMode(), true, parent);
    }

    public Screen editParent() {
        return editParent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Edit mode always shows one more slot than there are real pets - see {@link #forEdit}'s doc. */
    private int totalSlots() {
        return editMode ? allPets.size() + 1 : allPets.size();
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(totalSlots() / (double) sliceCount));
    }

    private int firstGlobalIndexOnPage(int p) {
        return Math.min(p * sliceCount, totalSlots());
    }

    private int slotsOnPage(int p) {
        int from = firstGlobalIndexOnPage(p);
        int to = Math.min(from + sliceCount, totalSlots());
        return to - from;
    }

    /** @return the pet at a global (page-independent) slot index, or null for edit mode's trailing empty slot. */
    private PetEntry petAt(int globalIndex) {
        return globalIndex >= 0 && globalIndex < allPets.size() ? allPets.get(globalIndex) : null;
    }

    /** Icon(16px) * both size settings, then the tile grows to fit it plus a little padding - killer560:
     *  "make a way to have the pictures alot bigger as well." At the defaults (scale=1, iconScale=1) this is
     *  16+... = the original fixed 28px tile, unchanged. */
    private int currentTile() {
        int iconPixelSize = Math.round(16 * scale * iconScale);
        return Math.max(16, Math.max(Math.round(BASE_TILE * scale), iconPixelSize + 10));
    }

    private record Layout(float centerX, float centerY, float radius, float innerRadius, int count) {
        float sliceAngle() {
            return count == 0 ? 0f : (float) (Math.PI * 2.0 / count);
        }
    }

    /** Radius grows past the plain {@code BASE_RADIUS * scale} baseline once the tile itself (driven by Icon
     *  Size, independently of the overall Scale slider) would otherwise make neighbouring slices overlap. */
    private Layout layout(int count, int tile) {
        float radius = BASE_RADIUS * scale;
        if (count >= 2) {
            float sliceAngle = (float) (Math.PI * 2.0 / count);
            float neededRadius = (float) ((tile * TILE_SPACING_FACTOR) / (2.0 * Math.sin(sliceAngle / 2.0)));
            radius = Math.max(radius, neededRadius);
        }
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

    /** @return the global slot index under {@code (mx, my)} on the current page, or -1 if none. Edit-mode
     *  swap/drag/right-click all hit-test through this one method so they always agree with what's drawn. */
    private int hitGlobalIndex(double mx, double my) {
        int count = slotsOnPage(page);
        Layout layoutData = layout(count, currentTile());
        Integer local = hoveredIndex(mx, my, layoutData);
        return local == null || local >= count ? -1 : firstGlobalIndexOnPage(page) + local;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        graphics.fill(0, 0, width, height, BG_DIM);

        int count = slotsOnPage(page);
        int from = firstGlobalIndexOnPage(page);
        int tile = currentTile();
        Layout layoutData = layout(count, tile);
        Integer hoveredLocal = hoveredIndex(mouseX, mouseY, layoutData);

        drawDividers(graphics, layoutData);
        for (int i = 0; i < count; i++) {
            int globalIndex = from + i;
            PetEntry pet = petAt(globalIndex);
            boolean hovered = hoveredLocal != null && hoveredLocal == i;
            boolean pending = pendingSwapIndex != null && pendingSwapIndex == globalIndex;
            drawSlice(graphics, pet, i, layoutData, tile, hovered, pending);
        }

        if (editMode) {
            graphics.centeredText(font, "Right click to edit, left click to rearrange",
                    width / 2, (int) (layoutData.centerY() - layoutData.radius() - tile - 22), TEXT);
        } else if (pageCount() > 1) {
            graphics.centeredText(font, "Page " + (page + 1) + "/" + pageCount(),
                    (int) layoutData.centerX(), (int) (layoutData.centerY() - layoutData.radius() - tile - 22), TEXT);
            graphics.centeredText(font, "Scroll to flip pages",
                    (int) layoutData.centerX(), (int) (layoutData.centerY() - layoutData.radius() - tile - 11), TEXT);
        }
        if (editMode && pageCount() > 1) {
            graphics.centeredText(font, "Page " + (page + 1) + "/" + pageCount() + " - scroll to flip",
                    (int) layoutData.centerX(), (int) (layoutData.centerY() - layoutData.radius() - tile - 11), TEXT_DIM);
        }

        // Dragged slice follows the cursor as a small ghost icon, same idea as SlotBindsFeature's cursor-link.
        if (editMode && dragging && pendingSwapIndex != null) {
            PetEntry dragged = petAt(pendingSwapIndex);
            if (dragged != null) {
                int half = tile / 2;
                graphics.item(iconFor(dragged), (int) dragCurrentX - 8, (int) dragCurrentY - 8);
                graphics.outline((int) dragCurrentX - half, (int) dragCurrentY - half, tile, tile, TILE_BORDER_PENDING);
            }
        }

        if (editMode) {
            graphics.centeredText(font, "Escape to finish", width / 2, height - 16, TEXT_DIM);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** killer560: "show the pie chart lines between pets" - one spoke per slice boundary, from the dead-zone
     *  out to the rim. See the class doc for why {@code rotate} here is safe to use. */
    private void drawDividers(GuiGraphicsExtractor graphics, Layout layoutData) {
        int count = layoutData.count();
        if (count < 2) {
            return;
        }
        float sliceAngle = layoutData.sliceAngle();
        int inner = Math.round(layoutData.innerRadius());
        int outer = Math.round(layoutData.radius());
        graphics.pose().pushMatrix();
        graphics.pose().translate(layoutData.centerX(), layoutData.centerY());
        for (int i = 0; i < count; i++) {
            // Boundary BEFORE slice i, in drawSlice's own "-PI/2 + index*sliceAngle" slice-centre convention.
            float angle = (float) (-Math.PI / 2.0 + i * sliceAngle - sliceAngle / 2.0);
            graphics.pose().pushMatrix();
            graphics.pose().rotate(angle);
            graphics.fill(inner, -1, outer, 1, DIVIDER_COLOR);
            graphics.pose().popMatrix();
        }
        graphics.pose().popMatrix();
    }

    private void drawSlice(GuiGraphicsExtractor graphics, PetEntry pet, int index, Layout layoutData, int tile,
                           boolean hovered, boolean pending) {
        double angle = -Math.PI / 2.0 + index * layoutData.sliceAngle();
        float px = layoutData.centerX() + (float) (Math.cos(angle) * layoutData.radius());
        float py = layoutData.centerY() + (float) (Math.sin(angle) * layoutData.radius());
        int x0 = Math.round(px - tile / 2f);
        int y0 = Math.round(py - tile / 2f);

        int border = pending ? TILE_BORDER_PENDING : hovered ? TILE_BORDER_HOVER : TILE_BORDER;
        graphics.fill(x0, y0, x0 + tile, y0 + tile, pet == null ? TILE_BG_EMPTY : hovered ? TILE_BG_HOVER : TILE_BG);
        graphics.outline(x0, y0, tile, tile, border);

        if (pet == null) {
            // The one trailing empty/"+" slot in edit mode - right-click it to fill it.
            graphics.centeredText(font, "+", Math.round(px), Math.round(py) - 4, hovered ? TEXT_HOVER : TEXT_DIM);
            return;
        }

        int iconPixelSize = Math.round(16 * scale * iconScale);
        float iconMatrixScale = iconPixelSize / 16f;
        graphics.pose().pushMatrix();
        graphics.pose().translate(px, py);
        graphics.pose().scale(iconMatrixScale, iconMatrixScale);
        graphics.item(iconFor(pet), -8, -8);
        graphics.pose().popMatrix();

        String label = sliceLabel(pet);
        if (!label.isEmpty()) {
            graphics.centeredText(font, label, Math.round(px), y0 + tile + 2, hovered ? TEXT_HOVER : TEXT);
        }
    }

    /** killer560: "add an option to hide their level and an option to hide the pets name" - each toggle is
     *  independent, so all four combinations (both shown, either alone, neither) are possible. */
    private String sliceLabel(PetEntry pet) {
        if (hideName && hideLevel) {
            return "";
        }
        if (hideName) {
            return pet.level() >= 0 ? "[Lvl " + pet.level() + "]" : "";
        }
        if (hideLevel) {
            return pet.name();
        }
        return pet.shortLabel();
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

    /** Hold-release confirm at the cursor's position right now (read from the mouse handler, which goes through
     *  Auto Scale's coordinate funnel like every mouse event this screen gets), falling back to the last drawn
     *  position. Called from {@link #keyReleased}/{@link #mouseReleased} on the frame the bind goes up, and by
     *  {@link PetWheelFeature}'s tick poll as a fallback. No-op in edit mode - that screen is never driven by the
     *  wheel keybind at all, only opened by the "Edit Pets" button. */
    void confirmSelection() {
        double mx = lastMouseX;
        double my = lastMouseY;
        if (minecraft != null && minecraft.getWindow() != null) {
            mx = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
            my = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
        }
        confirmSelectionAt(mx, my);
    }

    private void confirmSelectionAt(double mouseX, double mouseY) {
        if (editMode) {
            return;
        }
        List<PetEntry> visible = currentPagePets();
        Layout layoutData = layout(visible.size(), currentTile());
        Integer hovered = hoveredIndex(mouseX, mouseY, layoutData);
        PetEntry chosen = hovered != null && hovered < visible.size() ? visible.get(hovered) : null;
        select(chosen);
    }

    /** Live-wheel-only helper (edit mode uses {@link #petAt}/{@link #hitGlobalIndex} instead, since it also
     *  has to represent the one trailing empty slot {@link PetEntry} can't hold). */
    private List<PetEntry> currentPagePets() {
        int from = Math.min(page * sliceCount, allPets.size());
        int to = Math.min(from + sliceCount, allPets.size());
        return allPets.subList(from, to);
    }

    private void select(PetEntry chosen) {
        if (chosen != null) {
            PetWheelFeature.summon(chosen);
        }
        if (minecraft != null) {
            McCompat.setScreen(minecraft, null);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (editMode) {
            return editModeClick(event);
        }
        if (mode != PetWheelConfig.InteractionMode.PRESS_CLICK) {
            return false;
        }
        List<PetEntry> visible = currentPagePets();
        Layout layoutData = layout(visible.size(), currentTile());
        Integer hovered = hoveredIndex(event.x(), event.y(), layoutData);
        select(hovered != null && hovered < visible.size() ? visible.get(hovered) : null);
        return true;
    }

    /**
     * killer560: "If i right click a slot it opens my pet menu... For left click i can left click a pet and
     * left click another and they swap or i can drag and drop it." Right-click always hands off to
     * {@link PetWheelEditor} immediately (one gesture, no drag). Left-click arms {@link #pendingSwapIndex} on
     * the first press; {@link #mouseDragged}/{@link #mouseReleased} turn that into a drag-and-drop swap if the
     * mouse moves before release, otherwise it stays armed across the release and THIS method finalizes the
     * swap on whatever slice a later, separate click lands on - the two-click gesture from the brief.
     */
    private boolean editModeClick(MouseButtonEvent event) {
        int hit = hitGlobalIndex(event.x(), event.y());
        if (event.button() == 1) {
            if (hit >= 0 && pendingSwapIndex == null) {
                PetWheelEditor.beginPick(hit, this);
            }
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        if (pendingSwapIndex == null) {
            if (hit >= 0 && petAt(hit) != null) {
                pendingSwapIndex = hit;
                dragArmed = true;
                dragging = false;
                dragCurrentX = event.x();
                dragCurrentY = event.y();
            }
            return true;
        }
        // A slice is already selected from an earlier plain click - this press is the second half of the
        // two-click swap gesture, resolved immediately rather than waiting for its own release.
        dragArmed = false;
        dragging = false;
        if (hit >= 0 && hit != pendingSwapIndex && petAt(hit) != null) {
            PetWheelConfig.getInstance().swapInWheel(pendingSwapIndex, hit);
            PetWheelConfig.getInstance().save();
        }
        pendingSwapIndex = null;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (editMode && dragArmed && pendingSwapIndex != null) {
            dragging = true;
            dragCurrentX = event.x();
            dragCurrentY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    /** True when this is a live hold-release wheel and {@code keyOrButton} is its bind's key (or mouse button). */
    private boolean isHoldReleaseBind(boolean mouse, int keyOrButton) {
        if (editMode || mode != PetWheelConfig.InteractionMode.HOLD_RELEASE) {
            return false;
        }
        int bind = PetWheelConfig.getInstance().getKeyCode();
        if (bind == KeyUtil.NONE || KeyUtil.isMouseCode(bind) != mouse) {
            return false;
        }
        return mouse ? KeyUtil.mouseButton(bind) == keyOrButton : bind == keyOrButton;
    }

    /**
     * Hold-release: the bind's key-up reaches this screen on the frame it happens (vanilla's
     * {@code KeyboardHandler.keyPress} hands a RELEASE to the open screen's {@code keyReleased}, javap 26.1.2 and
     * 26.2), so the pet is picked and {@code /pets} sent right here instead of at the next END_CLIENT_TICK poll
     * (killer560, 2026-10-07: "that same in-game tick with 0 delay").
     */
    @Override
    public boolean keyReleased(KeyEvent event) {
        if (isHoldReleaseBind(false, event.key())) {
            confirmSelection();
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // Same as keyReleased for a mouse-button bind: MouseHandler.onButton hands every release to the open screen.
        if (isHoldReleaseBind(true, event.button())) {
            confirmSelectionAt(event.x(), event.y());
            return true;
        }
        if (editMode && dragArmed) {
            dragArmed = false;
            if (dragging) {
                int hit = hitGlobalIndex(event.x(), event.y());
                if (hit >= 0 && hit != pendingSwapIndex && petAt(hit) != null) {
                    PetWheelConfig.getInstance().swapInWheel(pendingSwapIndex, hit);
                    PetWheelConfig.getInstance().save();
                }
                pendingSwapIndex = null;
                dragging = false;
            }
            // else: no movement between press and release - leave pendingSwapIndex armed for the second,
            // separate click (see editModeClick's doc).
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int pages = pageCount();
        if (pages > 1 && scrollY != 0) {
            page = Math.floorMod(page + (scrollY < 0 ? 1 : -1), pages);
            pendingSwapIndex = null;
            dragArmed = false;
            dragging = false;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (editMode && minecraft != null) {
            McCompat.setScreen(minecraft, editParent);
            return;
        }
        super.onClose();
    }
}
