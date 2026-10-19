package com.killer560.hub.petwheel;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.hud.GuiRects;
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
public class PetWheelScreen extends Screen implements MenuThemedScreen {

    /** The wheel draws over the world, so the dim stays lighter than a full menu's. Everything else is the menu
     *  theme's (2026-10-13): tiles are its cards, the hovered one its accent, the spokes its accent at half alpha. */
    private static final int BG_DIM = 0x99000000;
    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "petwheel";
    /** First tile drawn last frame, {x, y, w, h}; read by the testkit through {@link #themedPanelBounds}. */
    private int[] firstTile = new int[4];

    private static final float BASE_RADIUS = 84f;
    private static final int BASE_TILE = 28;
    /** Minimum extra room (beyond the tile's own footprint) two neighbouring tiles must keep between their
     *  centres, so a big Icon Size setting pushes the ring outward instead of overlapping slices. */
    private static final float TILE_SPACING_FACTOR = 1.15f;

    /** The current page's pets; replaced when the page is changed from the wheel. */
    private List<PetEntry> allPets;
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
    public PetWheelScreen(List<PetEntry> allPets, float scale, PetWheelConfig.InteractionMode mode) {
        this(allPets, scale, mode, false, null);
    }

    private PetWheelScreen(List<PetEntry> allPets, float scale, PetWheelConfig.InteractionMode mode,
                           boolean editMode, Screen editParent) {
        super(Component.literal(editMode ? "Edit Pets" : "Pet Wheel"));
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        this.allPets = allPets;
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
        return new PetWheelScreen(cfg.getWheelPets(), cfg.getScalePercent() / 100f,
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

    /** killer560, 2026-10-11: "remove the slices portion since you just add whatever pets you want and it auto
     *  updates where they go" - every pet on the wheel is one slice, so there is always exactly one page. */
    private int sliceCount() {
        return Math.max(1, totalSlots());
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(totalSlots() / (double) sliceCount()));
    }

    private int firstGlobalIndexOnPage(int p) {
        return Math.min(p * sliceCount(), totalSlots());
    }

    private int slotsOnPage(int p) {
        int from = firstGlobalIndexOnPage(p);
        int to = Math.min(from + sliceCount(), totalSlots());
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
        MenuStyle.use();
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

        // The wheel's pages (presets): the current one's number and name, and how to change it. Only with more than one.
        PetWheelConfig pageCfg = PetWheelConfig.getInstance();
        int top = (int) (layoutData.centerY() - layoutData.radius() - tile);
        if (editMode) {
            plateText(graphics, "Right click to edit, left click to rearrange",
                    width / 2, top - 22, MenuStyle.TXT);
        }
        if (pageCfg.getPageCount() > 1) {
            plateText(graphics, pageLabel(pageCfg), (int) layoutData.centerX(), editMode ? top - 11 : top - 22, MenuStyle.TXT);
            if (!editMode) {
                plateText(graphics, "Scroll or Left/Right to change page", (int) layoutData.centerX(), top - 11, MenuStyle.DIM);
            }
        }

        // Dragged slice follows the cursor as a small ghost icon, same idea as SlotBindsFeature's cursor-link.
        if (editMode && dragging && pendingSwapIndex != null) {
            PetEntry dragged = petAt(pendingSwapIndex);
            if (dragged != null) {
                int half = tile / 2;
                graphics.item(iconFor(dragged), (int) dragCurrentX - 8, (int) dragCurrentY - 8);
                graphics.outline((int) dragCurrentX - half, (int) dragCurrentY - half, tile, tile, MenuStyle.OR_HOVER);
            }
        }

        if (editMode) {
            plateText(graphics, "Escape to finish", width / 2, height - 16, MenuStyle.DIM);
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
            graphics.fill(inner, -1, outer, 1, MenuStyle.alpha(MenuStyle.OR, 0x80));
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

        if (index == 0) {
            firstTile = new int[]{x0, y0, tile, tile};
        }
        GuiRects r = GuiRects.begin(graphics);
        if (hovered || pending) {
            MenuStyle.glow(r, x0, y0, tile, tile, MenuStyle.OR, pending ? 0x60 : 0x40);
        }
        if (pet == null) {
            // The one trailing empty/"+" slot in edit mode is a recessed well - right-click it to fill it.
            MenuStyle.well(r, x0, y0, tile, tile, MenuStyle.WELL);
        } else {
            MenuStyle.dropShadow(r, x0, y0, tile, tile, 2);
            ScreenChrome.card(r, x0, y0, tile, tile, hovered);
        }
        if (hovered || pending) {
            r.outline(x0, y0, tile, tile, pending ? MenuStyle.TXT : MenuStyle.OR);
        }
        r.submit();

        if (pet == null) {
            drawScaledCentered(graphics, "+", px, py - 4 * scale, hovered ? MenuStyle.OR_HOVER : MenuStyle.DIM);
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
            drawScaledCentered(graphics, label, px, y0 + tile + 2, hovered ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }
    }

    /** killer560, 2026-10-11: "the scale should also scale the text" - slice labels draw at the wheel's Scale. */
    private void drawScaledCentered(GuiGraphicsExtractor graphics, String text, float x, float y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        plateText(graphics, text, 0, 0, color);
        graphics.pose().popMatrix();
    }

    /** Centred text on a small card plate: the wheel draws over the world, where the Light theme's dark text would
     *  vanish without one. */
    private void plateText(GuiGraphicsExtractor graphics, String text, int cx, int y, int color) {
        int w = font.width(text);
        GuiRects r = GuiRects.begin(graphics);
        MenuStyle.bevel(r, cx - w / 2 - 3, y - 2, w + 6, 12, MenuStyle.CARD_TOP, MenuStyle.CARD_BOTTOM);
        r.submit();
        ScreenChrome.text(graphics, text, cx - w / 2, y, color);
    }

    private void plateText(GuiGraphicsExtractor graphics, String text, float cx, float y, int color) {
        plateText(graphics, text, Math.round(cx), Math.round(y), color);
    }

    @Override
    public String themeId() {
        return THEME_ID;
    }

    @Override
    public int[] themedPanelBounds() {
        return firstTile.clone();
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
        int from = Math.min(page * sliceCount(), allPets.size());
        int to = Math.min(from + sliceCount(), allPets.size());
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
        // A mouse button bound as a page key changes page before the click can select or swap a slice.
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        if (matchesBind(cfg.getPrevPageKeyCode(), true, event.button()) && changePage(-1)) {
            return true;
        }
        if (matchesBind(cfg.getNextPageKeyCode(), true, event.button()) && changePage(1)) {
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
            } else if (hit >= 0) {
                // killer560 (2026-10-18): a blank slot takes a new pet on a left click as well as a right click. Only
                // replacing a pet that is already there stays on right click.
                PetWheelEditor.beginPick(hit, this);
            }
            return true;
        }
        // A slice is already selected from an earlier plain click - this press is the second half of the
        // two-click swap gesture, resolved immediately rather than waiting for its own release.
        dragArmed = false;
        dragging = false;
        if (hit >= 0 && hit != pendingSwapIndex && petAt(hit) != null) {
            swapSlots(pendingSwapIndex, hit);
        }
        pendingSwapIndex = null;
        return true;
    }

    /** Swaps two wheel slots, saves, and re-reads the list this screen draws from. {@code allPets} is a
     *  {@code List.copyOf} snapshot ({@link PetWheelConfig#getWheelPets}), so without the re-read the config
     *  swapped but the screen kept drawing the old order (killer560, 2026-10-18: drag and drop "does not move"). */
    private void swapSlots(int from, int to) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        cfg.swapInWheel(from, to);
        cfg.save();
        allPets = cfg.getWheelPets();
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
                    swapSlots(pendingSwapIndex, hit);
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

    /** "Page 2/3: Mining". */
    private static String pageLabel(PetWheelConfig cfg) {
        return "Page " + (cfg.getPageIndex() + 1) + "/" + cfg.getPageCount() + ": " + cfg.getCurrentPageName();
    }

    /** Moves to another of the wheel's pages (killer560, 2026-10-18) and shows its pets. The page is remembered, so the
     *  wheel opens on it next time. @return false when there is only one page. */
    boolean changePage(int dir) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        if (cfg.getPageCount() <= 1) {
            return false;
        }
        cfg.stepPage(dir);
        cfg.save();
        allPets = cfg.getWheelPets();
        pendingSwapIndex = null;
        dragArmed = false;
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && changePage(scrollY < 0 ? 1 : -1)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        if (matchesBind(cfg.getPrevPageKeyCode(), false, event.key()) && changePage(-1)) {
            return true;
        }
        if (matchesBind(cfg.getNextPageKeyCode(), false, event.key()) && changePage(1)) {
            return true;
        }
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT && changePage(1)) {
            return true;
        }
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT && changePage(-1)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /** True when {@code bind} (a key code, or a mouse code from {@link KeyUtil}) is the key or mouse button just pressed. */
    private static boolean matchesBind(int bind, boolean mouse, int keyOrButton) {
        if (bind == KeyUtil.NONE || KeyUtil.isMouseCode(bind) != mouse) {
            return false;
        }
        return mouse ? KeyUtil.mouseButton(bind) == keyOrButton : bind == keyOrButton;
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
