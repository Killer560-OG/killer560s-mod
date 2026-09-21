package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.profileviewer.ProfileViewerConfig;
import com.killer560.hub.profileviewer.ProfileViewerFeature;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.util.ModChat;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The /pv screen. Layout follows NEU's old GuiProfileViewer (fixed panel, page tabs along the top, profile
 * picker in the corner) in this mod's black + orange chrome (same colors as {@code ModScreen} /
 * {@code StorageSearchScreen}). Drawn immediate-mode: each frame registers its clickable regions, and
 * {@link #mouseClicked} hit-tests the last frame's list. The only real widget is the 3D skin preview.
 */
public class ProfileViewerScreen extends Screen {

    static final int ACCENT = 0xFFCC6600;
    static final int BORDER = 0xFF553311;
    static final int PANEL_BG = 0xFF0D0D0D;
    static final int BOX_BG = 0xFF121212;
    static final int BAR_BG = 0xFF262626;
    static final int TEXT = 0xFF000000 | ModChat.TEXT;
    static final int VALUE = 0xFF000000 | ModChat.LIGHT_ORANGE;
    static final int DIM = 0xFF000000 | ModChat.DIM;
    static final int BAD = 0xFF000000 | ModChat.BAD;
    static final int MAXED = 0xFFFFAA00;

    static final String[] PAGES = {"Basic Info", "Dungeons", "Inventories", "Pets", "Collections", "Mining", "Bestiary",
            "Crimson Isle", "Museum", "Rift", "Farming", "Networth", "Misc", "Weight"};
    static final int PAGE_BASIC = 0;
    static final int PAGE_DUNGEONS = 1;
    static final int PAGE_INVENTORIES = 2;
    static final int PAGE_PETS = 3;
    static final int PAGE_COLLECTIONS = 4;
    static final int PAGE_MINING = 5;
    static final int PAGE_BESTIARY = 6;
    static final int PAGE_CRIMSON = 7;
    static final int PAGE_MUSEUM = 8;
    static final int PAGE_RIFT = 9;
    static final int PAGE_FARMING = 10;
    static final int PAGE_NETWORTH = 11;
    static final int PAGE_MISC = 12;
    /** Appended last so a remembered page index from an older config still points at the same page. */
    static final int PAGE_WEIGHT = 13;

    private static final String[] INV_VIEWS = {"Inventory", "Ender Chest", "Backpacks", "Wardrobe", "Accessories", "Vault", "Bags"};

    private enum State { LOADING, ERROR, EMPTY, READY }

    // ------------------------------------------------------------------ recent-views sidebar
    // killer560: a right-hand section showing the last people you've pv'd (head + name, your own head
    // pinned on top) that you can click back into, plus a box to type someone else's name.

    private static final int SIDEBAR_W = 78;
    private static final int SIDEBAR_GAP = 6;
    private static final int SIDEBAR_ROW_H = 16;
    /** Shared across every open screen this session - a head/name only needs fetching once. */
    private static final Map<UUID, ItemStack> HEAD_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LIVE_NAME_CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> HEAD_FETCHING = ConcurrentHashMap.newKeySet();

    private final Screen parent;
    private ProfileViewerFeature.Target target;

    private State state = State.LOADING;
    private String status = "Looking up player...";
    private int generation = 0;
    private UUID uuid;
    private String displayName;
    private ProfileViewerApi.ProfilesResult result;
    private int profileIndex = 0;
    private GameProfile skinProfile;

    private int page;
    private int invView = 0;
    private int invPage = 0;
    /** Vertical scroll (in panel-rows) for the Ender Chest / Backpacks tiled multi-panel view. */
    private int invScroll = 0;
    private int petScroll = 0;
    private SbProfile.Pet pinnedPet;
    boolean dropdownOpen = false;
    private int profileButtonX = -1;

    int panelX, panelY, panelW, panelH;
    int contentX, contentY, contentW, contentH;
    /** Scroll offset / sub-view / pager index for the extra pages; reset on page or profile change. */
    int pageScroll = 0;
    int subView = 0;
    int subPage = 0;
    private final ExtraPages extraPages = new ExtraPages(this);
    private final WeightPage weightPage = new WeightPage(this);
    private final List<int[]> tabRects = new ArrayList<>();

    private boolean sidebarVisible;
    int sidebarX, sidebarY, sidebarW, sidebarH;
    private EditBox switchBox;
    /** Kept outside the widget so a background rebuild (e.g. the skin fetch finishing) can't wipe out
     *  what's mid-typing when {@link #init()} recreates the widget. */
    private String switchBoxText = "";

    record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    final List<Hotspot> hotspots = new ArrayList<>();
    private final List<Hotspot> overlayHotspots = new ArrayList<>();
    List<Component> pendingTooltip;
    ItemStack pendingItemTooltip = ItemStack.EMPTY;

    public ProfileViewerScreen(Screen parent, ProfileViewerFeature.Target target) {
        super(Component.literal("Profile Viewer"));
        this.parent = parent;
        this.target = target;
        this.displayName = target.name();
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        this.page = cfg.isRememberLastPage() ? Math.max(0, Math.min(PAGES.length - 1, cfg.getLastPage())) : 0;
        LevelTables.ensureLoaded();
        load(false);
    }

    /** Switches this same screen to a different player - used by the recent-views heads and the name box
     *  so picking someone doesn't stack a new screen on top of this one. */
    private void open(ProfileViewerFeature.Target newTarget) {
        if (newTarget == null) {
            return;
        }
        target = newTarget;
        displayName = newTarget.name();
        uuid = newTarget.uuid();
        skinProfile = null;
        result = null;
        profileIndex = 0;
        invView = 0;
        invPage = 0;
        invScroll = 0;
        petScroll = 0;
        pinnedPet = null;
        subView = 0;
        subPage = 0;
        pageScroll = 0;
        load(false);
    }

    /** The typed-name box: reuses {@code /pv}'s own name/uuid resolution (including the tab-list shortcut),
     *  so an invalid name surfaces through the same error screen the command would show. */
    private void submitSwitch() {
        if (switchBox == null) {
            return;
        }
        String name = switchBoxText.trim();
        if (name.isEmpty()) {
            return;
        }
        switchBoxText = "";
        switchBox.setValue("");
        open(ProfileViewerFeature.targetForName(name));
    }

    // ------------------------------------------------------------------ loading

    private void load(boolean force) {
        int gen = ++generation;
        state = State.LOADING;
        extraPages.reset(force);
        weightPage.reset();
        dropdownOpen = false;
        rebuildSafe();
        CompletableFuture<ProfileViewerApi.ResolvedPlayer> resolved;
        if (target.uuid() != null) {
            resolved = CompletableFuture.completedFuture(new ProfileViewerApi.ResolvedPlayer(target.uuid(), target.name()));
        } else {
            status = "Looking up " + target.name() + "...";
            resolved = ProfileViewerApi.resolve(target.name());
        }
        resolved.thenCompose(player -> {
            minecraftExecute(() -> {
                if (gen != generation) {
                    return;
                }
                uuid = player.uuid();
                if (player.name() != null && ProfileViewerApi.isValidName(player.name())) {
                    displayName = player.name();
                }
                target = new ProfileViewerFeature.Target(displayName, uuid);
                status = "Loading SkyBlock profiles...";
                if (ProfileViewerConfig.getInstance().isShowSkin() && skinProfile == null) {
                    ProfileViewerApi.fetchSkinProfile(uuid).thenAccept(gp -> minecraftExecute(() -> {
                        if (gen == generation && gp != null) {
                            skinProfile = gp;
                            rebuildSafe();
                        }
                    }));
                }
            });
            return ProfileViewerApi.fetchProfiles(player.uuid(), force);
        }).whenComplete((res, err) -> minecraftExecute(() -> {
            if (gen != generation) {
                return;
            }
            if (err != null) {
                state = State.ERROR;
                status = ProfileViewerApi.messageFor(err);
                rebuildSafe();
                return;
            }
            // Resolution + fetch both succeeded, so this uuid is a real account - safe to remember. Keyed
            // by uuid (never the typed/displayed name) so a later rename can't fork the history.
            ProfileViewerConfig.getInstance().recordRecentView(uuid, displayName);
            result = res;
            if (res.profiles().isEmpty()) {
                state = State.EMPTY;
                status = displayName + " has no SkyBlock profiles.";
                rebuildSafe();
                return;
            }
            profileIndex = Math.max(0, Math.min(profileIndex, res.profiles().size() - 1));
            state = State.READY;
            rebuildSafe();
        }));
    }

    private static void minecraftExecute(Runnable r) {
        net.minecraft.client.Minecraft.getInstance().execute(r);
    }

    private void rebuildSafe() {
        if (this.minecraft != null && this.minecraft.screen == this) {
            this.rebuildWidgets();
        }
    }

    private SbProfile profile() {
        return state == State.READY && result != null && !result.profiles().isEmpty()
                ? result.profiles().get(Math.min(profileIndex, result.profiles().size() - 1)) : null;
    }

    // ------------------------------------------------------------------ layout / widgets

    @Override
    protected void init() {
        panelW = Math.min(this.width - 12, 480);
        // Page tabs wrap onto extra rows when they don't fit; the panel grows by those rows.
        tabRects.clear();
        int tx = 6;
        int row = 0;
        for (int i = 0; i < PAGES.length; i++) {
            int w = this.font.width(PAGES[i]) + 24;
            if (tx + w > panelW - 6 && tx > 6) {
                row++;
                tx = 6;
            }
            tabRects.add(new int[]{tx, row, w});
            tx += w + 3;
        }
        int rows = row + 1;
        panelH = Math.min(this.height - 12, 290 + (rows - 1) * 21);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        contentX = panelX + 8;
        contentY = panelY + 29 + rows * 21;
        contentW = panelW - 16;
        contentH = Math.max(60, panelY + panelH - 16 - contentY);

        if (page == PAGE_BASIC && skinProfile != null && profile() != null && ProfileViewerConfig.getInstance().isShowSkin()) {
            int skinH = Math.min(120, contentH - 90);
            if (skinH >= 50) {
                PlayerSkinWidget skin = new PlayerSkinWidget(skinH / 2 + 10, skinH, this.minecraft.getEntityModels(),
                        this.minecraft.getSkinManager().createLookup(skinProfile, false));
                skin.setPosition(contentX + (118 - (skinH / 2 + 10)) / 2, contentY + 4);
                addRenderableWidget(skin);
            }
        }

        // Recent-views sidebar: only when it fits in the panel's own centering margin, so it never
        // squeezes the main content or overlaps the edge of the window on a small screen.
        int margin = (this.width - panelW) / 2;
        sidebarVisible = margin >= SIDEBAR_GAP + SIDEBAR_W;
        switchBox = null;
        if (sidebarVisible) {
            sidebarX = panelX + panelW + SIDEBAR_GAP;
            sidebarY = panelY;
            sidebarW = SIDEBAR_W;
            sidebarH = panelH;
            switchBox = new EditBox(this.font, sidebarX + 4, sidebarY + 16, sidebarW - 8, 14, Component.literal("Player name"));
            switchBox.setMaxLength(16);
            switchBox.setHint(Component.literal("Name..."));
            switchBox.setValue(switchBoxText);
            switchBox.setResponder(text -> switchBoxText = text);
            addRenderableWidget(switchBox);
        }
    }

    private void setPage(int newPage) {
        if (newPage == page) {
            return;
        }
        page = newPage;
        dropdownOpen = false;
        pageScroll = 0;
        subView = 0;
        subPage = 0;
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        if (cfg.isRememberLastPage()) {
            cfg.setLastPage(page);
            cfg.save();
        }
        rebuildSafe();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (Hotspot h : overlayHotspots) {
                if (h.contains(event.x(), event.y())) {
                    click();
                    h.action().run();
                    return true;
                }
            }
            if (dropdownOpen) {
                dropdownOpen = false;
                return true;
            }
            for (int i = hotspots.size() - 1; i >= 0; i--) {
                Hotspot h = hotspots.get(i);
                if (h.contains(event.x(), event.y())) {
                    click();
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void click() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page == PAGE_PETS && state == State.READY) {
            petScroll = Math.max(0, petScroll - (int) Math.signum(scrollY));
            return true;
        }
        if (page == PAGE_INVENTORIES && state == State.READY) {
            // Ender Chest / Backpacks are tiled (multiple panels visible at once) and scroll vertically
            // through the rest; every other sub-view still pages one item at a time.
            if (invView == 1 || invView == 2) {
                invScroll = Math.max(0, invScroll - (int) Math.signum(scrollY));
            } else {
                invPage += scrollY < 0 ? 1 : -1;
            }
            return true;
        }
        if (page > PAGE_PETS && state == State.READY) {
            pageScroll = Math.max(0, pageScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (switchBox != null && switchBox.isFocused()) {
            // Enter submits the typed name; otherwise let the box handle its own text/cursor keys
            // (in particular Left/Right, which would otherwise page instead of moving the cursor).
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                submitSwitch();
                return true;
            }
            return super.keyPressed(event);
        }
        if (state == State.READY) {
            if (event.key() == InputConstants.KEY_LEFT) {
                if (page > PAGE_PETS) {
                    subPage--;
                } else {
                    invPage--;
                }
                return true;
            }
            if (event.key() == InputConstants.KEY_RIGHT) {
                if (page > PAGE_PETS) {
                    subPage++;
                } else {
                    invPage++;
                }
                return true;
            }
            if (event.key() >= InputConstants.KEY_1 && event.key() <= InputConstants.KEY_9 && event.key() - InputConstants.KEY_1 < PAGES.length) {
                setPage(event.key() - InputConstants.KEY_1);
                return true;
            }
            if (event.key() == InputConstants.KEY_0 && PAGES.length > 9) {
                setPage(9);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hotspots.clear();
        overlayHotspots.clear();
        pendingTooltip = null;
        pendingItemTooltip = ItemStack.EMPTY;

        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, BORDER);

        drawHeader(g, mouseX, mouseY);
        if (sidebarVisible) {
            drawSidebar(g, mouseX, mouseY);
        }

        switch (state) {
            case LOADING -> centered(g, status + dots(), contentY + contentH / 2 - 4, VALUE);
            case ERROR -> drawError(g, mouseX, mouseY);
            case EMPTY -> centered(g, status, contentY + contentH / 2 - 4, DIM);
            case READY -> {
                SbProfile p = profile();
                switch (page) {
                    case PAGE_BASIC -> drawBasic(g, p, mouseX, mouseY);
                    case PAGE_DUNGEONS -> drawDungeons(g, p, mouseX, mouseY);
                    case PAGE_INVENTORIES -> drawInventories(g, p, mouseX, mouseY);
                    case PAGE_PETS -> drawPets(g, p, mouseX, mouseY);
                    default -> {
                        try {
                            if (page == PAGE_WEIGHT) {
                                weightPage.draw(g, p, mouseX, mouseY);
                            } else {
                                extraPages.draw(g, page, p, mouseX, mouseY);
                            }
                        } catch (RuntimeException e) {
                            // A malformed/unexpected field must never take the screen down mid-frame.
                            centered(g, "Couldn't show this page (" + e.getClass().getSimpleName() + ").", contentY + contentH / 2 - 4, BAD);
                        }
                    }
                }
            }
        }

        if (result != null && state == State.READY) {
            long age = (System.currentTimeMillis() - result.fetchedAt()) / 1000;
            String foot = result.source() + " · " + (age < 60 ? age + "s ago" : (age / 60) + "m ago");
            g.text(this.font, foot, panelX + panelW - 8 - this.font.width(foot), panelY + panelH - 12, DIM, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (dropdownOpen) {
            drawDropdown(g, mouseX, mouseY);
        }

        if (!pendingItemTooltip.isEmpty()) {
            g.setTooltipForNextFrame(this.font, pendingItemTooltip, mouseX, mouseY);
        } else if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.setTooltipForNextFrame(this.font, pendingTooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    String dots() {
        return ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
    }

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        g.fill(panelX, panelY, panelX + panelW, panelY + 24, 0xFF000000);
        g.fill(panelX, panelY + 23, panelX + panelW, panelY + 24, ACCENT);
        g.text(this.font, "Profile Viewer", panelX + 8, panelY + 8, ACCENT, false);
        int nameX = panelX + 16 + this.font.width("Profile Viewer");
        g.text(this.font, "·", nameX - 4, panelY + 8, DIM, false);
        g.text(this.font, displayName == null ? "" : displayName, nameX + 4, panelY + 8, VALUE, false);

        int right = panelX + panelW - 6;
        // Refresh
        String refresh = "⟳ Refresh";
        int rw = this.font.width(refresh) + 10;
        right -= rw;
        button(g, right, panelY + 4, rw, 16, refresh, mx, my, false, () -> load(true));
        // Profile picker
        SbProfile p = profile();
        if (p != null) {
            String label = "Profile: " + p.cuteName + gameModeTag(p) + " ▾";
            int pw = this.font.width(label) + 10;
            right -= pw + 4;
            profileButtonX = right;
            button(g, right, panelY + 4, pw, 16, label, mx, my, dropdownOpen, () -> dropdownOpen = !dropdownOpen);
        }

        // Page tabs
        for (int i = 0; i < PAGES.length && i < tabRects.size(); i++) {
            int[] rect = tabRects.get(i);
            int tx = panelX + rect[0];
            int ty = panelY + 28 + rect[1] * 21;
            int w = rect[2];
            final int idx = i;
            boolean sel = i == page;
            boolean hover = inside(mx, my, tx, ty, w, 18) && !dropdownOpen;
            g.fill(tx, ty, tx + w, ty + 18, sel ? 0xFF2A1A0A : hover ? 0xFF222222 : BOX_BG);
            g.outline(tx, ty, w, 18, sel ? ACCENT : hover ? ACCENT : BORDER);
            g.item(pageIcon(i), tx + 2, ty + 1);
            g.text(this.font, PAGES[i], tx + 19, ty + 5, sel ? VALUE : TEXT, false);
            hotspots.add(new Hotspot(tx, ty, w, 18, () -> setPage(idx)));
        }
    }

    private static String gameModeTag(SbProfile p) {
        return switch (p.gameMode) {
            case "ironman" -> " ♲";
            case "island" -> " ☀";
            case "bingo" -> " Ⓑ";
            default -> "";
        };
    }

    private static ItemStack pageIcon(int page) {
        return new ItemStack(switch (page) {
            case PAGE_BASIC -> Items.PLAYER_HEAD;
            case PAGE_DUNGEONS -> Items.WITHER_SKELETON_SKULL;
            case PAGE_INVENTORIES -> Items.CHEST;
            case PAGE_PETS -> Items.BONE;
            case PAGE_COLLECTIONS -> Items.ITEM_FRAME;
            case PAGE_MINING -> Items.DIAMOND_PICKAXE;
            case PAGE_BESTIARY -> Items.ZOMBIE_HEAD;
            case PAGE_CRIMSON -> Items.BLAZE_POWDER;
            case PAGE_MUSEUM -> Items.GOLD_BLOCK;
            case PAGE_RIFT -> Items.ENDER_EYE;
            case PAGE_FARMING -> Items.WHEAT;
            case PAGE_NETWORTH -> Items.GOLD_INGOT;
            case PAGE_WEIGHT -> Items.ANVIL;
            default -> Items.PAPER;
        });
    }

    // ------------------------------------------------------------------ recent-views sidebar

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        box(g, sidebarX, sidebarY, sidebarW, sidebarH);
        int x = sidebarX + 4;
        int y = sidebarY + 4;
        text(g, "Switch", x, y, DIM);
        // The EditBox widget itself renders/handles input; just leave its row clear and add the button.
        y += 30;
        int btnY = y;
        button(g, x, btnY, sidebarW - 8, 14, "Go", mx, my, false, this::submitSwitch);
        y += 19;

        g.fill(sidebarX + 2, y, sidebarX + sidebarW - 2, y + 1, BORDER);
        y += 4;
        text(g, "Recent", x, y, DIM);
        y += SIDEBAR_ROW_H;

        ProfileViewerFeature.Target self = ProfileViewerFeature.self();
        y = sidebarRow(g, x, y, self.uuid(), self.name(), mx, my, () -> open(self));

        int bottom = sidebarY + sidebarH - 4;
        for (ProfileViewerConfig.RecentView v : ProfileViewerConfig.getInstance().getRecentViews()) {
            if (v.uuid().equals(self.uuid()) || y + SIDEBAR_ROW_H > bottom) {
                continue;
            }
            y = sidebarRow(g, x, y, v.uuid(), v.name(), mx, my, () -> open(new ProfileViewerFeature.Target(
                    LIVE_NAME_CACHE.getOrDefault(v.uuid(), v.name()), v.uuid())));
        }
    }

    /** One head + name row; returns the y for the next row. */
    private int sidebarRow(GuiGraphicsExtractor g, int x, int y, UUID uuid, String storedName, int mx, int my, Runnable onClick) {
        boolean hover = inside(mx, my, x - 2, y - 1, sidebarW - 4, SIDEBAR_ROW_H) && !dropdownOpen;
        if (hover) {
            g.fill(x - 2, y - 1, x + sidebarW - 6, y - 1 + SIDEBAR_ROW_H, 0xFF2A1A0A);
        }
        g.item(headFor(uuid), x, y - 1);
        String name = LIVE_NAME_CACHE.getOrDefault(uuid, storedName);
        g.text(this.font, this.font.plainSubstrByWidth(name, sidebarW - 22), x + 18, y + 3, hover ? VALUE : TEXT, false);
        hotspots.add(new Hotspot(x - 2, y - 1, sidebarW - 4, SIDEBAR_ROW_H, onClick));
        return y + SIDEBAR_ROW_H;
    }

    private void text(GuiGraphicsExtractor g, String str, int x, int y, int color) {
        g.text(this.font, str, x, y, color, false);
    }

    /** A player-head icon for the sidebar, fetched (and cached process-wide) from the session service the
     *  same way the Basic Info skin preview is - a plain head until the async lookup lands. */
    private static ItemStack headFor(UUID uuid) {
        ItemStack cached = HEAD_CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (HEAD_FETCHING.add(uuid)) {
            ProfileViewerApi.fetchSkinProfile(uuid).thenAccept(gp -> minecraftExecute(() -> {
                if (gp == null) {
                    return;
                }
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
                HEAD_CACHE.put(uuid, head);
                if (gp.name() != null && !gp.name().isEmpty()) {
                    LIVE_NAME_CACHE.put(uuid, gp.name());
                }
            }));
        }
        return new ItemStack(Items.PLAYER_HEAD);
    }

    private void drawDropdown(GuiGraphicsExtractor g, int mx, int my) {
        if (result == null) {
            return;
        }
        List<SbProfile> list = result.profiles();
        int w = 150;
        for (SbProfile p : list) {
            w = Math.max(w, this.font.width(p.cuteName + gameModeTag(p) + "  (selected)") + 12);
        }
        int x = profileButtonX >= 0 ? profileButtonX : panelX + panelW - 6 - w;
        x = Math.max(panelX + 4, Math.min(x, panelX + panelW - 4 - w));
        int y = panelY + 21;
        int rowH = 14;
        g.fill(x, y, x + w, y + list.size() * rowH + 4, 0xF0000000);
        g.outline(x, y, w, list.size() * rowH + 4, ACCENT);
        for (int i = 0; i < list.size(); i++) {
            SbProfile p = list.get(i);
            int ry = y + 2 + i * rowH;
            boolean hover = inside(mx, my, x, ry, w, rowH);
            if (hover) {
                g.fill(x + 1, ry, x + w - 1, ry + rowH, 0xFF2A1A0A);
            }
            String label = p.cuteName + gameModeTag(p) + (p.selected ? "  (selected)" : "");
            g.text(this.font, label, x + 6, ry + 3, i == profileIndex ? VALUE : TEXT, false);
            final int idx = i;
            overlayHotspots.add(new Hotspot(x, ry, w, rowH, () -> {
                profileIndex = idx;
                dropdownOpen = false;
                invPage = 0;
                invScroll = 0;
                pageScroll = 0;
                subView = 0;
                subPage = 0;
                petScroll = 0;
                pinnedPet = null;
                rebuildSafe();
            }));
        }
    }

    private void drawError(GuiGraphicsExtractor g, int mx, int my) {
        String[] lines = status.split("\n");
        int y = contentY + contentH / 2 - (lines.length * 11) / 2 - 12;
        centered(g, "Couldn't load " + (displayName == null ? "profile" : displayName), y, BAD);
        y += 16;
        for (String line : lines) {
            for (var seq : this.font.split(Component.literal(line), contentW - 20)) {
                g.centeredText(this.font, seq, contentX + contentW / 2, y, TEXT);
                y += 11;
            }
        }
        y += 6;
        button(g, contentX + contentW / 2 - 30, y, 60, 16, "Retry", mx, my, false, () -> load(true));
    }

    // ------------------------------------------------------------------ basic info

    private void drawBasic(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int leftW = 118;
        box(g, contentX, contentY, leftW, contentH);
        int skinBottom = contentY + 4 + (skinProfile != null && ProfileViewerConfig.getInstance().isShowSkin()
                ? Math.min(120, contentH - 90) + 4 : 0);
        int y = Math.max(skinBottom, contentY + 6);
        int lx = contentX + 6;
        y = stat(g, lx, y, "SB Level", p.sbLevel + (p.sbLevelProgress > 0 ? "." + String.format(Locale.ROOT, "%02d", p.sbLevelProgress) : ""));
        y = stat(g, lx, y, "Fairy Souls", String.valueOf(p.fairySouls));
        y = stat(g, lx, y, "Purse", shorten(p.purse));
        if (p.bank >= 0) {
            int by = y;
            y = stat(g, lx, y, "Bank", shorten(p.bank) + (p.personalBank > 0 ? " / " + shorten(p.personalBank) : ""));
            if (inside(mx, my, lx, by, leftW - 12, 10)) {
                pendingTooltip = List.of(
                        Component.literal("Co-op bank: " + commas(p.bank)).withStyle(style(VALUE)),
                        Component.literal("Personal bank: " + commas(p.personalBank)).withStyle(style(VALUE)));
            }
        } else {
            y = stat(g, lx, y, "Bank", "API off");
        }
        if (p.motes > 0) {
            y = stat(g, lx, y, "Motes", shorten(p.motes));
        }
        if (p.firstJoin > 0) {
            y = stat(g, lx, y, "Joined", DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
                    .format(Instant.ofEpochMilli(p.firstJoin).atZone(ZoneId.systemDefault())));
        }
        if (p.memberCount > 1) {
            stat(g, lx, y, "Co-op", p.memberCount + " members");
        }

        int rx = contentX + leftW + 6;
        int rw = contentW - leftW - 6;
        box(g, rx, contentY, rw, contentH);

        // Skills
        int sy = contentY + 5;
        if (p.skillXp == null) {
            g.text(this.font, "Skills", rx + 6, sy, ACCENT, false);
            g.text(this.font, "Skills API is disabled.", rx + 6, sy + 14, DIM, false);
            sy += 30;
        } else {
            double sum = 0;
            int count = 0;
            List<String> skills = LevelTables.SKILL_ORDER;
            int colW = (rw - 18) / 2;
            int rowH = 21;
            int i = 0;
            for (String id : skills) {
                long xp = p.skillXp.getOrDefault(id, 0L);
                LevelTables.Level lvl = LevelTables.skillLevel(id, xp, p.skillCaps.getOrDefault(id, -1));
                if (LevelTables.countsForAverage(id) && LevelTables.skill(id) != null) {
                    sum += lvl.level();
                    count++;
                }
                int cx = rx + 6 + (i % 2) * (colW + 6);
                int cy = sy + 12 + (i / 2) * rowH;
                LevelTables.Skill def = LevelTables.skill(id);
                String name = def != null ? def.name() : SbProfile.titleCase(id);
                // killer560: "if I have overflow skills show the real skill level to the left... to the
                // right in parentheses show what level I would be with all the overflow xp I have" - a
                // capped skill (Farming/Taming/Foraging) keeps banking XP past the cap; the uncapped level
                // (cap=-1, the table's own max) shows what that XP is worth once the cap is raised.
                String overflow = null;
                if (lvl.maxed()) {
                    LevelTables.Level uncapped = LevelTables.skillLevel(id, xp, -1);
                    if (uncapped.level() > lvl.level()) {
                        overflow = " (" + uncapped.level() + ")";
                    }
                }
                bar(g, cx, cy, colW, skillIcon(id), name, lvl, mx, my, xp, overflow);
                i++;
            }
            String avg = count == 0 ? "" : String.format(Locale.ROOT, "Skill Avg %.2f", sum / count);
            g.text(this.font, "Skills", rx + 6, sy, ACCENT, false);
            g.text(this.font, avg, rx + rw - 6 - this.font.width(avg), sy, VALUE, false);
            sy += 12 + ((skills.size() + 1) / 2) * rowH + 2;
        }

        // Slayers
        g.text(this.font, "Slayers", rx + 6, sy, ACCENT, false);
        List<LevelTables.Slayer> slayers = LevelTables.slayers();
        int colW = (rw - 24) / 3;
        long totalXp = 0;
        for (int i = 0; i < slayers.size(); i++) {
            LevelTables.Slayer s = slayers.get(i);
            SbProfile.SlayerStat stat = p.slayers.get(s.apiId());
            long xp = stat == null ? 0 : stat.xp();
            totalXp += xp;
            int cx = rx + 6 + (i % 3) * (colW + 6);
            int cy = sy + 12 + (i / 3) * 21;
            LevelTables.Level lvl = LevelTables.slayerLevel(s, xp);
            bar(g, cx, cy, colW, slayerIcon(s.apiId()), s.name(), lvl, mx, my, xp);
            if (inside(mx, my, cx, cy, colW, 18) && stat != null) {
                List<Component> tip = new ArrayList<>(pendingTooltip == null ? List.of() : pendingTooltip);
                for (Map.Entry<Integer, Integer> k : stat.kills().entrySet()) {
                    tip.add(Component.literal("Tier " + (k.getKey() + 1) + " kills: " + commas(k.getValue())).withStyle(style(DIM)));
                }
                pendingTooltip = tip;
            }
        }
        String total = "Total XP " + commas(totalXp);
        g.text(this.font, total, rx + rw - 6 - this.font.width(total), sy, VALUE, false);
    }

    private int stat(GuiGraphicsExtractor g, int x, int y, String label, String value) {
        g.text(this.font, label, x, y, DIM, false);
        g.text(this.font, value, x + 106 - this.font.width(value), y, VALUE, false);
        return y + 11;
    }

    /** Icon + "Name Level" + progress bar, NEU style. Hover shows XP numbers. */
    void bar(GuiGraphicsExtractor g, int x, int y, int w, ItemStack icon, String name, LevelTables.Level lvl,
                     int mx, int my, long totalXp) {
        bar(g, x, y, w, icon, name, lvl, mx, my, totalXp, null);
    }

    /** @param levelSuffix appended after the level number (e.g. an overflow level in parentheses), or null. */
    void bar(GuiGraphicsExtractor g, int x, int y, int w, ItemStack icon, String name, LevelTables.Level lvl,
                     int mx, int my, long totalXp, String levelSuffix) {
        g.item(icon, x, y);
        int tx = x + 18;
        int bw = w - 18;
        String levelText = levelSuffix == null ? String.valueOf(lvl.level()) : lvl.level() + levelSuffix;
        g.text(this.font, this.font.plainSubstrByWidth(name, bw - this.font.width(levelText) - 4), tx, y, TEXT, false);
        g.text(this.font, levelText, tx + bw - this.font.width(levelText), y, lvl.maxed() ? MAXED : VALUE, false);
        int barY = y + 11;
        g.fill(tx, barY, tx + bw, barY + 4, BAR_BG);
        int fill = (int) Math.round(bw * (lvl.maxed() ? 1 : lvl.progress()));
        g.fill(tx, barY, tx + fill, barY + 4, lvl.maxed() ? MAXED : ACCENT);
        if (inside(mx, my, x, y, w, 17)) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name + " " + lvl.level()).withStyle(style(VALUE)));
            if (lvl.maxed()) {
                tip.add(Component.literal("MAXED").withStyle(style(MAXED)));
            } else {
                tip.add(Component.literal("Progress: " + commas(lvl.xpIntoLevel()) + " / " + commas(lvl.xpForLevel())
                        + String.format(Locale.ROOT, " (%.1f%%)", lvl.progress() * 100)).withStyle(style(TEXT)));
            }
            tip.add(Component.literal("Total XP: " + commas(totalXp)).withStyle(style(DIM)));
            pendingTooltip = tip;
        }
    }

    static ItemStack skillIcon(String id) {
        return new ItemStack(switch (id) {
            case "FARMING" -> Items.GOLDEN_HOE;
            case "MINING" -> Items.STONE_PICKAXE;
            case "COMBAT" -> Items.STONE_SWORD;
            case "FORAGING" -> Items.JUNGLE_SAPLING;
            case "FISHING" -> Items.FISHING_ROD;
            case "ENCHANTING" -> Items.ENCHANTING_TABLE;
            case "ALCHEMY" -> Items.BREWING_STAND;
            case "CARPENTRY" -> Items.CRAFTING_TABLE;
            case "TAMING" -> Items.GHAST_SPAWN_EGG;
            case "HUNTING" -> Items.LEAD;
            case "RUNECRAFTING" -> Items.MAGMA_CREAM;
            case "SOCIAL" -> Items.EMERALD;
            default -> Items.PAPER;
        });
    }

    private static ItemStack slayerIcon(String apiId) {
        return new ItemStack(switch (apiId) {
            case "zombie" -> Items.ROTTEN_FLESH;
            case "spider" -> Items.COBWEB;
            case "wolf" -> Items.MUTTON;
            case "enderman" -> Items.ENDER_PEARL;
            case "blaze" -> Items.BLAZE_POWDER;
            case "vampire" -> Items.REDSTONE;
            default -> Items.PAPER;
        });
    }

    // ------------------------------------------------------------------ dungeons

    private void drawDungeons(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        SbProfile.DungeonStats d = p.dungeons;
        int leftW = 170;
        box(g, contentX, contentY, leftW, contentH);
        int x = contentX + 6;
        int y = contentY + 5;
        g.text(this.font, "Catacombs", x, y, ACCENT, false);
        y += 12;
        LevelTables.Level cata = LevelTables.catacombsLevel(d.catacombsXp(), true);
        bar(g, x, y, leftW - 12, new ItemStack(Items.WITHER_SKELETON_SKULL), "Catacombs", cata, mx, my, d.catacombsXp());
        y += 24;

        String selected = d.selectedClass().isEmpty() ? "None" : SbProfile.titleCase(d.selectedClass());
        g.text(this.font, "Classes", x, y, ACCENT, false);
        g.text(this.font, selected, x + leftW - 12 - this.font.width(selected), y, VALUE, false);
        y += 12;
        double classSum = 0;
        for (Map.Entry<String, Long> e : d.classXp().entrySet()) {
            LevelTables.Level lvl = LevelTables.catacombsLevel(e.getValue(), false);
            classSum += Math.min(50, lvl.level());
            String name = SbProfile.titleCase(e.getKey());
            if (e.getKey().equals(d.selectedClass())) {
                g.fill(x - 2, y - 1, x + leftW - 10, y + 17, 0xFF2A1A0A);
            }
            bar(g, x, y, leftW - 12, classIcon(e.getKey()), name, lvl, mx, my, e.getValue());
            y += 20;
        }
        y += 2;
        y = stat2(g, x, y, leftW - 12, "Class Average", String.format(Locale.ROOT, "%.2f", d.classXp().isEmpty() ? 0 : classSum / d.classXp().size()));
        y = stat2(g, x, y, leftW - 12, "Secrets", commas(d.secrets()));
        y = stat2(g, x, y, leftW - 12, "Total Runs", commas(d.totalRuns()));
        if (d.totalRuns() > 0) {
            stat2(g, x, y, leftW - 12, "Secrets / Run", String.format(Locale.ROOT, "%.2f", (double) d.secrets() / d.totalRuns()));
        }

        int rx = contentX + leftW + 6;
        int rw = contentW - leftW - 6;
        box(g, rx, contentY, rw, contentH);
        int[] cols = {rx + 6, rx + 6 + (int) (rw * 0.26), rx + 6 + (int) (rw * 0.44), rx + 6 + (int) (rw * 0.60), rx + 6 + (int) (rw * 0.80)};
        int ty = contentY + 5;
        String[] headers = {"Floor", "Runs", "Score", "S+", "S"};
        for (int i = 0; i < headers.length; i++) {
            g.text(this.font, headers[i], cols[i], ty, ACCENT, false);
        }
        ty += 12;
        int rowH = Math.max(10, Math.min(12, (contentH - 22) / 16));
        for (int floor = 0; floor <= 7; floor++) {
            ty = floorRow(g, cols, ty, rowH, floor == 0 ? "Entrance" : "Floor " + floor, d.normal().get(floor), floor % 2 == 0);
        }
        ty += 3;
        for (int floor = 1; floor <= 7; floor++) {
            ty = floorRow(g, cols, ty, rowH, "Master " + floor, d.master().get(floor), floor % 2 == 1);
        }
    }

    private int floorRow(GuiGraphicsExtractor g, int[] cols, int y, int rowH, String label, SbProfile.Floor f, boolean shade) {
        if (shade) {
            g.fill(cols[0] - 3, y - 1, cols[0] - 3 + (contentX + contentW - cols[0] - 3), y + rowH - 1, 0xFF161616);
        }
        g.text(this.font, label, cols[0], y, f == null ? DIM : TEXT, false);
        g.text(this.font, f == null ? "-" : commas(f.completions()), cols[1], y, f == null ? DIM : VALUE, false);
        g.text(this.font, f == null || f.bestScore() <= 0 ? "-" : String.valueOf(f.bestScore()), cols[2], y, f == null ? DIM : TEXT, false);
        g.text(this.font, f == null ? "-" : time(f.fastestSPlus()), cols[3], y, f == null || f.fastestSPlus() <= 0 ? DIM : MAXED, false);
        g.text(this.font, f == null ? "-" : time(f.fastestS()), cols[4], y, f == null || f.fastestS() <= 0 ? DIM : VALUE, false);
        return y + rowH;
    }

    int stat2(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
        g.text(this.font, label, x, y, DIM, false);
        g.text(this.font, value, x + w - this.font.width(value), y, VALUE, false);
        return y + 11;
    }

    static ItemStack classIcon(String c) {
        return new ItemStack(switch (c) {
            case "healer" -> Items.POTION;
            case "mage" -> Items.BLAZE_ROD;
            case "berserk" -> Items.IRON_SWORD;
            case "archer" -> Items.BOW;
            case "tank" -> Items.LEATHER_CHESTPLATE;
            default -> Items.PAPER;
        });
    }

    static String time(long ms) {
        if (ms <= 0) {
            return "-";
        }
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ inventories

    private void drawInventories(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int navW = 84;
        box(g, contentX, contentY, navW, contentH);
        int ny = contentY + 4;
        for (int i = 0; i < INV_VIEWS.length; i++) {
            final int idx = i;
            button(g, contentX + 4, ny, navW - 8, 16, INV_VIEWS[i], mx, my, i == invView, () -> {
                invView = idx;
                invPage = 0;
                invScroll = 0;
            });
            ny += 19;
        }

        int rx = contentX + navW + 6;
        int rw = contentW - navW - 6;
        box(g, rx, contentY, rw, contentH);

        if (!p.inventoryApi) {
            centeredIn(g, "Inventory API is disabled for this profile.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        CompletableFuture<SbProfile.Inventories> future = p.inventories();
        if (!future.isDone()) {
            centeredIn(g, "Decoding items" + dots(), rx, rw, contentY + contentH / 2 - 4, VALUE);
            return;
        }
        // getNow rethrows (CompletionException) for a failed decode - never let that escape into render.
        SbProfile.Inventories inv = future.isCompletedExceptionally() ? null : future.getNow(null);
        if (inv == null) {
            centeredIn(g, "Couldn't decode this profile's items.", rx, rw, contentY + contentH / 2 - 4, BAD);
            return;
        }

        int gridX = rx + (rw - 9 * 18) / 2;
        int top = contentY + 22;
        switch (invView) {
            case 0 -> {
                g.text(this.font, "Inventory", rx + 6, contentY + 6, ACCENT, false);
                // Armor (helmet first) + equipment beside the main grid.
                int sideX = rx + 8;
                List<ItemStack> armor = inv.armor();
                for (int i = 0; i < 4; i++) {
                    ItemStack s = armor.size() == 4 ? armor.get(3 - i) : ItemStack.EMPTY;
                    slot(g, sideX, top + i * 18, s, mx, my, false);
                    slot(g, sideX + 20, top + i * 18, i < inv.equipment().size() ? inv.equipment().get(i) : ItemStack.EMPTY, mx, my, false);
                }
                int mainX = Math.max(sideX + 46, gridX);
                List<ItemStack> items = inv.inventory();
                for (int i = 9; i < 36; i++) {
                    slot(g, mainX + ((i - 9) % 9) * 18, top + ((i - 9) / 9) * 18, get(items, i), mx, my, false);
                }
                int hotbarY = top + 3 * 18 + 6;
                for (int i = 0; i < 9; i++) {
                    slot(g, mainX + i * 18, hotbarY, get(items, i), mx, my, false);
                }
                int bagY = hotbarY + 26;
                if (!inv.quiver().isEmpty() || !inv.fishingBag().isEmpty()) {
                    g.text(this.font, "Arrows in quiver: " + countItems(inv.quiver()), mainX, bagY, DIM, false);
                }
            }
            // killer560: "for the enderchest and backpacks, make that fit a similar style to our custom
            // gui overlay, so I can see more at once" - tile every page/backpack in a scrollable grid of
            // panels (like the storage overlay does) instead of one page with "< 1/5 >" arrows.
            case 1 -> {
                g.text(this.font, "Ender Chest", rx + 6, contentY + 6, ACCENT, false);
                multiPanelGrid(g, rx, rw, top, numbered("Page", inv.enderChest().size()), null, inv.enderChest(), mx, my);
            }
            case 2 -> {
                List<SbProfile.Backpack> bps = inv.backpacks();
                g.text(this.font, "Backpacks", rx + 6, contentY + 6, ACCENT, false);
                if (bps.isEmpty()) {
                    centeredIn(g, "No backpacks.", rx, rw, contentY + contentH / 2 - 4, DIM);
                    return;
                }
                List<String> labels = new ArrayList<>();
                List<ItemStack> icons = new ArrayList<>();
                List<List<ItemStack>> itemLists = new ArrayList<>();
                for (SbProfile.Backpack bp : bps) {
                    labels.add("Backpack " + (bp.slot() + 1));
                    icons.add(bp.icon());
                    itemLists.add(bp.items());
                }
                multiPanelGrid(g, rx, rw, top, labels, icons, itemLists, mx, my);
            }
            case 3 -> pagedGrid(g, "Wardrobe", inv.wardrobe(), rx, rw, gridX, top, mx, my, inv.wardrobeEquipped());
            case 4 -> pagedGrid(g, "Accessory Bag", inv.accessories(), rx, rw, gridX, top, mx, my, -1);
            case 5 -> {
                g.text(this.font, "Personal Vault", rx + 6, contentY + 6, ACCENT, false);
                if (inv.personalVault().isEmpty()) {
                    centeredIn(g, "Empty or not shared.", rx, rw, contentY + contentH / 2 - 4, DIM);
                } else {
                    grid(g, inv.personalVault(), gridX, top, mx, my, -1);
                }
            }
            default -> {
                List<List<ItemStack>> bags = new ArrayList<>();
                List<String> names = new ArrayList<>();
                if (!inv.quiver().isEmpty()) {
                    bags.add(inv.quiver());
                    names.add("Quiver");
                }
                if (!inv.fishingBag().isEmpty()) {
                    bags.add(inv.fishingBag());
                    names.add("Fishing Bag");
                }
                if (!inv.potionBag().isEmpty()) {
                    bags.add(inv.potionBag());
                    names.add("Potion Bag");
                }
                if (bags.isEmpty()) {
                    centeredIn(g, "No bags.", rx, rw, contentY + contentH / 2 - 4, DIM);
                    return;
                }
                invPage = Math.floorMod(invPage, bags.size());
                pager(g, names.get(invPage), rx, rw, invPage, bags.size(), mx, my);
                grid(g, bags.get(invPage), gridX, top, mx, my, -1);
            }
        }
    }

    private void pagedGrid(GuiGraphicsExtractor g, String title, List<List<ItemStack>> pages, int rx, int rw, int gridX,
                           int top, int mx, int my, int wardrobeEquipped) {
        if (pages.isEmpty()) {
            g.text(this.font, title, rx + 6, contentY + 6, ACCENT, false);
            centeredIn(g, "Empty.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        invPage = Math.floorMod(invPage, pages.size());
        pager(g, title, rx, rw, invPage, pages.size(), mx, my);
        int equippedCol = -1;
        if (wardrobeEquipped > 0 && (wardrobeEquipped - 1) / 9 == invPage) {
            equippedCol = (wardrobeEquipped - 1) % 9;
        }
        grid(g, pages.get(invPage), gridX, top, mx, my, equippedCol);
    }

    private static List<String> numbered(String prefix, int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            out.add(prefix + " " + i);
        }
        return out;
    }

    /** Tiles every page/backpack as its own small labelled grid, several per row (as many as fit), and
     *  scrolls vertically through the rest - the storage overlay's "columns of panels" layout, so far
     *  more is visible than the old one-at-a-time "< 1/5 >" pager. {@code icons} may be null (Ender Chest
     *  pages don't have one); when given it must be the same size as {@code pages}. */
    private void multiPanelGrid(GuiGraphicsExtractor g, int rx, int rw, int top, List<String> labels,
                                List<ItemStack> icons, List<List<ItemStack>> pages, int mx, int my) {
        if (pages.isEmpty()) {
            centeredIn(g, "Empty.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        int cellW = 9 * 18;
        int labelH = 11;
        int gap = 8;
        int panelW = cellW + gap;
        int cols = Math.max(1, (rw - gap) / panelW);
        int startX = rx + Math.max(0, (rw - (cols * panelW - gap)) / 2);

        int panelRows = (pages.size() + cols - 1) / cols;
        int[] rowH = new int[panelRows];
        for (int r = 0; r < panelRows; r++) {
            int maxItemRows = 1;
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx < pages.size()) {
                    maxItemRows = Math.max(maxItemRows, Math.max(1, (pages.get(idx).size() + 8) / 9));
                }
            }
            rowH[r] = labelH + maxItemRows * 18 + gap;
        }

        // The max scroll always leaves at least the last row on screen, even if that one row alone is
        // taller than the viewport - never scrolls one row past the end into blank space.
        int visibleH = Math.max(0, contentY + contentH - top - 2);
        int maxScrollRow = panelRows - 1;
        int used = 0;
        for (int r = panelRows - 1; r >= 0; r--) {
            if (used + rowH[r] > visibleH) {
                break;
            }
            used += rowH[r];
            maxScrollRow = r;
        }
        invScroll = Math.max(0, Math.min(invScroll, maxScrollRow));

        int y = top;
        for (int r = invScroll; r < panelRows && y < top + visibleH; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= pages.size()) {
                    continue;
                }
                int px = startX + c * panelW;
                int labelOffset = 0;
                if (icons != null && !icons.get(idx).isEmpty()) {
                    g.item(icons.get(idx), px, y - 1);
                    labelOffset = 12;
                }
                g.text(this.font, this.font.plainSubstrByWidth(labels.get(idx), cellW - labelOffset), px + labelOffset, y, ACCENT, false);
                grid(g, pages.get(idx), px, y + labelH, mx, my, -1);
            }
            y += rowH[r];
        }

        if (maxScrollRow > 0) {
            int trackX = rx + rw - 4;
            g.fill(trackX, top, trackX + 2, top + visibleH, 0xFF1A1A1A);
            int thumbH = Math.max(8, visibleH * (panelRows - maxScrollRow) / panelRows);
            int thumbY = top + (visibleH - thumbH) * invScroll / Math.max(1, maxScrollRow);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ACCENT);
        }
    }

    private void pager(GuiGraphicsExtractor g, String title, int rx, int rw, int index, int count, int mx, int my) {
        g.text(this.font, title, rx + 24, contentY + 7, ACCENT, false);
        if (count <= 1) {
            return;
        }
        String label = (index + 1) + " / " + count;
        int right = rx + rw - 6;
        button(g, right - 16, contentY + 3, 16, 14, ">", mx, my, false, () -> invPage++);
        g.text(this.font, label, right - 22 - this.font.width(label), contentY + 7, VALUE, false);
        button(g, right - 42 - this.font.width(label), contentY + 3, 16, 14, "<", mx, my, false, () -> invPage--);
    }

    void grid(GuiGraphicsExtractor g, List<ItemStack> items, int x, int y, int mx, int my, int highlightCol) {
        int rows = Math.max(1, (items.size() + 8) / 9);
        for (int i = 0; i < rows * 9; i++) {
            int col = i % 9;
            slot(g, x + col * 18, y + (i / 9) * 18, get(items, i), mx, my, col == highlightCol);
        }
    }

    void slot(GuiGraphicsExtractor g, int x, int y, ItemStack stack, int mx, int my, boolean highlight) {
        g.fill(x, y, x + 18, y + 18, 0xFF1C1C1C);
        g.outline(x, y, 18, 18, highlight ? ACCENT : 0xFF2E2E2E);
        if (!stack.isEmpty()) {
            g.item(stack, x + 1, y + 1);
            g.itemDecorations(this.font, stack, x + 1, y + 1);
            if (inside(mx, my, x, y, 18, 18) && !dropdownOpen) {
                g.fill(x + 1, y + 1, x + 17, y + 17, 0x40FFFFFF);
                pendingItemTooltip = stack;
            }
        }
    }

    static ItemStack get(List<ItemStack> list, int i) {
        return i >= 0 && i < list.size() ? list.get(i) : ItemStack.EMPTY;
    }

    private static int countItems(List<ItemStack> list) {
        int n = 0;
        for (ItemStack s : list) {
            n += s.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ pets

    private void drawPets(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int infoW = 150;
        int gx = contentX;
        int gw = contentW - infoW - 6;
        box(g, gx, contentY, gw, contentH);
        g.text(this.font, "Pets (" + p.pets.size() + ")", gx + 6, contentY + 5, ACCENT, false);
        if (p.petScore > 0) {
            String score = "Pet Score " + p.petScore;
            g.text(this.font, score, gx + gw - 6 - this.font.width(score), contentY + 5, VALUE, false);
        }
        if (p.pets.isEmpty()) {
            centeredIn(g, "No pets.", gx, gw, contentY + contentH / 2 - 4, DIM);
        }
        int cell = 20;
        int cols = Math.max(1, (gw - 12) / cell);
        int visibleRows = Math.max(1, (contentH - 22) / cell);
        int totalRows = (p.pets.size() + cols - 1) / cols;
        petScroll = Math.max(0, Math.min(petScroll, Math.max(0, totalRows - visibleRows)));
        int startX = gx + (gw - cols * cell) / 2;
        int startY = contentY + 18;
        SbProfile.Pet hovered = null;
        for (int i = petScroll * cols; i < p.pets.size(); i++) {
            int row = i / cols - petScroll;
            if (row >= visibleRows) {
                break;
            }
            SbProfile.Pet pet = p.pets.get(i);
            int x = startX + (i % cols) * cell;
            int y = startY + row * cell;
            int rarity = 0xFF000000 | LevelTables.rarityColor(pet.tier);
            g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, 0xFF1A1A1A);
            g.outline(x + 1, y + 1, cell - 2, cell - 2, pet.active ? ACCENT : (rarity & 0x80FFFFFF));
            if (pet == pinnedPet) {
                g.outline(x, y, cell, cell, 0xFFFFFFFF);
            }
            g.item(pet.icon(), x + 2, y + 2);
            boolean hover = inside(mx, my, x, y, cell, cell) && !dropdownOpen;
            if (hover) {
                hovered = pet;
                g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, 0x40FFFFFF);
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("[Lvl " + pet.level.level() + "] ").withStyle(style(DIM))
                        .append(Component.literal(pet.displayName()).withStyle(style(rarity))));
                tip.add(Component.literal(SbProfile.titleCase(pet.tier)).withStyle(style(rarity)));
                if (pet.active) {
                    tip.add(Component.literal("Active pet").withStyle(style(ACCENT)));
                }
                tip.add(Component.literal("Click to pin").withStyle(style(DIM)));
                pendingTooltip = tip;
            }
            hotspots.add(new Hotspot(x, y, cell, cell, () -> pinnedPet = pinnedPet == pet ? null : pet));
        }
        if (totalRows > visibleRows) {
            int trackX = gx + gw - 4;
            int trackH = visibleRows * cell;
            g.fill(trackX, startY, trackX + 2, startY + trackH, 0xFF1A1A1A);
            int thumbH = Math.max(8, trackH * visibleRows / totalRows);
            int thumbY = startY + (trackH - thumbH) * petScroll / Math.max(1, totalRows - visibleRows);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ACCENT);
        }

        int ix = contentX + contentW - infoW;
        box(g, ix, contentY, infoW, contentH);
        SbProfile.Pet show = hovered != null ? hovered : pinnedPet != null ? pinnedPet : activePet(p);
        if (show == null) {
            centeredIn(g, "No active pet.", ix, infoW, contentY + contentH / 2 - 4, DIM);
            return;
        }
        int x = ix + 6;
        int y = contentY + 6;
        int rarity = 0xFF000000 | LevelTables.rarityColor(show.tier);
        g.item(show.icon(), x, y);
        g.text(this.font, this.font.plainSubstrByWidth(show.displayName(), infoW - 30), x + 20, y, rarity, false);
        g.text(this.font, SbProfile.titleCase(show.tier) + (show.active ? " · Active" : ""), x + 20, y + 10, DIM, false);
        y += 26;
        LevelTables.Level lvl = show.level;
        int w = infoW - 12;
        y = stat2(g, x, y, w, "Level", String.valueOf(lvl.level()));
        g.fill(x, y, x + w, y + 4, BAR_BG);
        g.fill(x, y, x + (int) Math.round(w * (lvl.maxed() ? 1 : lvl.progress())), y + 4, lvl.maxed() ? MAXED : ACCENT);
        y += 8;
        if (!lvl.maxed()) {
            y = stat2(g, x, y, w, "Next", shorten(lvl.xpIntoLevel()) + " / " + shorten(lvl.xpForLevel()));
        }
        y = stat2(g, x, y, w, "Total XP", shorten(show.exp));
        if (show.heldItem != null) {
            g.text(this.font, "Held Item", x, y, DIM, false);
            y += 10;
            g.text(this.font, this.font.plainSubstrByWidth(SbProfile.titleCase(show.heldItem.replace("PET_ITEM_", "")), w), x, y, VALUE, false);
            y += 11;
        }
        if (show.candyUsed > 0) {
            y = stat2(g, x, y, w, "Candy Used", String.valueOf(show.candyUsed));
        }
        if (show.skin != null) {
            g.text(this.font, "Skin", x, y, DIM, false);
            y += 10;
            g.text(this.font, this.font.plainSubstrByWidth(SbProfile.titleCase(show.skin), w), x, y, VALUE, false);
        }
    }

    private static SbProfile.Pet activePet(SbProfile p) {
        for (SbProfile.Pet pet : p.pets) {
            if (pet.active) {
                return pet;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing helpers

    net.minecraft.client.gui.Font font() {
        return this.font;
    }

    void box(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BOX_BG);
        g.outline(x, y, w, h, BORDER);
    }

    void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my, boolean selected, Runnable action) {
        boolean hover = inside(mx, my, x, y, w, h) && (!dropdownOpen || y < panelY + 22);
        g.fill(x, y, x + w, y + h, selected ? 0xFF2A1A0A : hover ? 0xFF262626 : 0xFF1A1A1A);
        g.outline(x, y, w, h, selected || hover ? ACCENT : 0xFF663D1A);
        g.centeredText(this.font, label, x + w / 2, y + (h - 8) / 2, selected ? VALUE : 0xFFFFFFFF);
        hotspots.add(new Hotspot(x, y, w, h, action));
    }

    void centered(GuiGraphicsExtractor g, String text, int y, int color) {
        centeredIn(g, text, contentX, contentW, y, color);
    }

    void centeredIn(GuiGraphicsExtractor g, String text, int x, int w, int y, int color) {
        g.text(this.font, text, x + (w - this.font.width(text)) / 2, y, color, false);
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static Style style(int argb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF)).withItalic(false);
    }

    static String commas(double v) {
        return String.format(Locale.US, "%,d", (long) Math.floor(v));
    }

    static String shorten(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000_000) {
            return String.format(Locale.US, "%.2fB", v / 1_000_000_000);
        }
        if (abs >= 1_000_000) {
            return String.format(Locale.US, "%.2fM", v / 1_000_000);
        }
        if (abs >= 10_000) {
            return String.format(Locale.US, "%.1fK", v / 1_000);
        }
        return commas(v);
    }
}
