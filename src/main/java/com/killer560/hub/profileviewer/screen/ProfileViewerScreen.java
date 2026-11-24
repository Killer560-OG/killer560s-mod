package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.profileviewer.data.Networth;
import com.killer560.hub.profileviewer.ProfileViewerConfig;
import com.killer560.hub.profileviewer.ProfileViewerFeature;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.AccessoryBagView;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SkyblockWeight;
import com.killer560.hub.profileviewer.data.ProfileExtras;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.util.ModChat;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
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
import com.killer560.hub.compat.McCompat;

/**
 * The /pv screen. Layout follows the old GuiProfileViewer (fixed panel, page tabs along the top, profile
 * picker in the corner) in this mod's black + orange chrome (same colors as {@code ModScreen} /
 * {@code StorageSearchScreen}). Drawn immediate-mode: each frame registers its clickable regions, and
 * {@link #mouseClicked} hit-tests the last frame's list. The only real widget is the 3D skin preview.
 */
public class ProfileViewerScreen extends Screen implements MenuThemedScreen {

    // The page colors, taken from the menu theme (or this screen's own Theme setting) by syncTheme() at the start of
    // every frame (2026-10-13); the values here are Amber's. Not final on purpose: every page reads them.
    static int ACCENT = 0xFFCC6600;
    static int BORDER = 0xFF553311;
    static int PANEL_BG = 0xFF0D0D0D;
    static int BOX_BG = 0xFF121212;
    static int BAR_BG = 0xFF262626;
    static int TEXT = 0xFF000000 | ModChat.TEXT;
    static int VALUE = 0xFF000000 | ModChat.LIGHT_ORANGE;
    static int DIM = 0xFF000000 | ModChat.DIM;
    static int BAD = 0xFF000000 | ModChat.BAD;
    static int MAXED = 0xFFFFAA00;
    /** A "done/have it" green, readable on the theme. */
    static int GOOD = 0xFF55FF55;
    /** A slot's or tile's recessed square, and its edge. */
    static int SLOT_BG = 0xFF1C1C1C;
    static int SLOT_EDGE = 0xFF2E2E2E;
    /** Alternate table rows. */
    static int STRIPE = 0xFF161616;
    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "profileviewer";

    /** The vanilla tooltip stays dark on every theme, so tooltip lines keep the old fixed colors. */
    private static final int TIP_ACCENT = 0xFFCC6600;
    private static final int TIP_TEXT = 0xFF000000 | ModChat.TEXT;
    private static final int TIP_VALUE = 0xFF000000 | ModChat.LIGHT_ORANGE;
    private static final int TIP_DIM = 0xFF000000 | ModChat.DIM;
    private static final int TIP_BAD = 0xFF000000 | ModChat.BAD;
    private static final int TIP_MAXED = 0xFFFFAA00;
    private static final int TIP_GOOD = 0xFF55FF55;

    /** Derives the menu palette and points every page color at it. */
    static void syncTheme() {
        MenuStyle.use();
        ACCENT = MenuStyle.OR;
        BORDER = MenuStyle.ACCENT_EDGE;
        PANEL_BG = MenuStyle.PANEL_BOTTOM;
        BOX_BG = MenuStyle.CARD_TOP;
        BAR_BG = MenuStyle.TRACK;
        TEXT = MenuStyle.TXT;
        VALUE = MenuStyle.OR_LIGHT;
        DIM = MenuStyle.DIM;
        BAD = MenuStyle.RED;
        MAXED = ScreenChrome.readable(0xFFFFAA00);
        GOOD = ScreenChrome.readable(0xFF55FF55);
        SLOT_BG = MenuStyle.WELL;
        SLOT_EDGE = MenuStyle.BLACK;
        STRIPE = MenuStyle.alpha(MenuStyle.TXT, 0x0A);
    }

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

    private static final String[] INV_VIEWS = {"Inventory", "Ender Chest", "Backpacks", "Wardrobe", "Accessories", "Vault", "Quiver",
            "Fishing Bag", "Potion Bag", "Sacks"};
    /** NEU Classic's selector icons for the views above (InventoriesPage's vanilla stand-ins), and their numbers. */
    private static final ItemStack[] INV_ICONS = {new ItemStack(Items.CHEST), new ItemStack(Items.ENDER_CHEST),
            new ItemStack(Items.BUNDLE), new ItemStack(Items.LEATHER_CHESTPLATE), new ItemStack(Items.GOLDEN_APPLE),
            new ItemStack(Items.IRON_DOOR), new ItemStack(Items.ARROW), new ItemStack(Items.FISHING_ROD),
            new ItemStack(Items.POTION), new ItemStack(Items.BARREL)};
    private static final String[] INV_NUMBERS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};
    static final net.minecraft.resources.Identifier CHEST_TEXTURE =
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    private enum State { LOADING, ERROR, EMPTY, READY }

    // killer560: a right-hand section showing the last people you've pv'd (head + name, your own head
    // pinned on top) that you can click back into, plus a box to type someone else's name.

    /** The sidebar's narrowest; it widens toward {@link #SIDEBAR_W_WANTED} when the window has the room, so a
     *  16-character name fits in the box (killer560, 2026-10-08: "make that slightly larger for longer names").
     *  120: the themed box's text line is its width minus 10 (ThemedEditBox), so 112 left 94 px for a 96 px
     *  "WWWWWWWWWWWWWWWW"; 120 leaves 102 (the name and its cursor), and a recent-views row's name 98. */
    private static final int SIDEBAR_W = 78;
    private static final int SIDEBAR_W_WANTED = 120;
    private static final int SUGGESTION_ROWS = 6;
    private static final int SUGGESTION_ROW_H = 11;
    private static final int SIDEBAR_GAP = 6;
    private static final int SIDEBAR_ROW_H = 16;
    /** Shared across every open screen this session - a head/name only needs fetching once. */
    private static final Map<UUID, ItemStack> HEAD_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LIVE_NAME_CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> HEAD_FETCHING = ConcurrentHashMap.newKeySet();

    private static final DateTimeFormatter JOINED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    /** Heads and live names kept at most this many; a session that views many players starts over past it. */
    private static final int MAX_HEADS = 64;

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
    /** Accessories view: the sort mode and the main bag flattened once per decode. */
    private int accSort;
    private List<List<ItemStack>> accPagesKey;
    private List<ItemStack> accFlat = List.of();
    private static final int ACC_MISSING_SHADE = 0xA0101010;
    /** Vertical scroll (in panel-rows) for the Ender Chest / Backpacks tiled multi-panel view. */
    private int invScroll = 0;
    private int petScroll = 0;
    private SbProfile.Pet pinnedPet;
    boolean dropdownOpen = false;
    /** Show Status: fetched only on the click, answered into these fields off the render thread. */
    boolean statusOpen = false;
    private final int[] statusButton = {-1, -1, 0, 0};
    private volatile boolean statusPending;
    private volatile String statusFail;
    private UUID statusFor;

    /** The lines the Status popup shows now (the testkit reads them). */
    List<String> statusLines() {
        ProfileViewerApi.StatusResult r = uuid == null ? null : ProfileViewerApi.getCachedStatus(uuid);
        if (r == null) {
            return List.of(statusPending ? "Checking" + dots() : statusFail != null ? statusFail : "Checking" + dots());
        }
        return switch (r.state()) {
            case ONLINE -> List.of("Online - " + r.where());
            case HIDDEN -> List.of("Hidden (API off)");
            case UNAVAILABLE -> List.of("Status unavailable");
            case OFFLINE -> r.lastSeenMs() > 0
                    ? List.of("Offline", "Last seen: " + DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT)
                            .format(Instant.ofEpochMilli(r.lastSeenMs()).atZone(ZoneId.systemDefault())))
                    : List.of("Offline");
        };
    }

    /** The Basic page of a loaded profile carries the Status control itself (under the Joined row, the NEU player
     *  box, or in SkyBlockPV's box under the player); every other page keeps it in the header. */
    boolean statusInPage() {
        return state == State.READY && page == PAGE_BASIC && profile() != null;
    }

    /** A Status button at {@code x, y, w} on the Basic page, remembered for the popup and the testkit. */
    private void statusButtonAt(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        statusButton[0] = x;
        statusButton[1] = y;
        statusButton[2] = w;
        statusButton[3] = 16;
        button(g, x, y, w, 16, "Status ▾", mx, my, statusOpen, this::toggleStatus);
    }

    private void toggleStatus() {
        statusOpen = !statusOpen;
        if (!statusOpen || uuid == null || ProfileViewerApi.getCachedStatus(uuid) != null || statusPending) return;
        statusPending = true;
        statusFail = null;
        statusFor = uuid;
        ProfileViewerApi.fetchStatus(uuid).whenComplete((r, t) -> {
            statusPending = false;
            if (t != null) {
                String m = String.valueOf(t.getMessage());
                statusFail = m.contains("404") ? "Status unavailable" : "Couldn't check status";
            }
        });
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        if (sbpvStyle()) return; // SkyBlockPV shows the text inside its own box
        List<String> lines = statusLines();
        boolean inPage = statusInPage();
        int w = inPage ? statusButton[2] : 60;
        if (inPage) { // under the player / the Joined row: wrapped to the button's width, never wider than it
            List<String> wrapped = new ArrayList<>();
            for (String s : lines) {
                while (!s.isEmpty()) {
                    String part = this.font.plainSubstrByWidth(s, w - 8);
                    if (part.isEmpty()) part = s.substring(0, 1);
                    wrapped.add(part);
                    s = s.substring(part.length()).stripLeading();
                }
            }
            lines = wrapped;
        } else {
            for (String s : lines) w = Math.max(w, this.font.width(s) + 12);
        }
        int h = lines.size() * 12 + 6;
        int x = inPage ? statusButton[0] : Math.max(4, Math.min(statusButton[0], this.width - 4 - w));
        int y = statusButton[1] + statusButton[3] + 1;
        if (y + h > (inPage ? panelY + panelH : this.height)) y = Math.max(0, statusButton[1] - 1 - h);
        popup(g, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) {
            tx(g, lines.get(i), x + 6, y + 4 + i * 12, i == 0 ? VALUE : DIM);
        }
    }

    private int profileButtonX = -1;
    private int profileButtonY = -1;
    private int profileButtonH = 16;
    /** True while the header's buttons draw: they keep their hover with the profile list open (it opens from them). */
    private boolean drawingHeader;

    int panelX, panelY, panelW, panelH;
    /** The Style setting as of the last {@link #init()}; the in-screen Style button changes both. */
    private ProfileViewerConfig.Style pvStyle = ProfileViewerConfig.Style.DEFAULT;
    /** {x, y, w, h} of the Style button as last drawn (a right click there steps back). */
    private final int[] styleButton = new int[4];
    int contentX, contentY, contentW, contentH;
    /** Scroll offset / sub-view / pager index for the extra pages; reset on page or profile change. */
    int pageScroll = 0;
    int subView = 0;
    int subPage = 0;
    private final ExtraPages extraPages = new ExtraPages(this);
    private final WeightPage weightPage = new WeightPage(this);
    private final List<int[]> tabRects = new ArrayList<>();
    /** The networth the Basic Info line last showed (null while calculating); read by the testkit. */
    volatile Long lastBasicNetworth;

    private boolean sidebarVisible;
    int sidebarX, sidebarY, sidebarW, sidebarH;
    private EditBox switchBox;
    /** Kept outside the widget so a background rebuild (e.g. the skin fetch finishing) can't wipe out
     *  what's mid-typing when {@link #init()} recreates the widget. */
    private String switchBoxText = "";
    /** The Inventories tab's search across every container (one matcher, the Storage Search's), its box and text. */
    private final PvSearch invFind = new PvSearch();
    private EditBox invSearch;
    private String invSearchText = "";
    /** Tab-completion for the name box: names from the tab list, the recent views and every name looked up
     *  this session that start with what is typed. Tab cycles them, a click picks one. */
    private List<String> suggestions = List.of();
    private int suggestionIndex = -1;
    private boolean applyingSuggestion = false;

    record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return inside(mx, my, x, y, w, h); }
    }

    final List<Hotspot> hotspots = new ArrayList<>();
    /** Right-click targets (a cycling button's step back). */
    private final List<Hotspot> rightHotspots = new ArrayList<>();
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
        if (newTarget == null) return;
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
        if (switchBox == null) return;
        String name = switchBoxText.trim();
        if (name.isEmpty()) return;
        switchBoxText = "";
        switchBox.setValue("");
        suggestions = List.of();
        suggestionIndex = -1;
        open(ProfileViewerFeature.targetForName(name));
    }


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
                if (gen != generation) return;
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
            if (gen != generation) return;
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

    private static void minecraftExecute(Runnable r) { net.minecraft.client.Minecraft.getInstance().execute(r); }

    private void rebuildSafe() {
        if (this.minecraft != null && McCompat.screen(this.minecraft) == this) {
            this.rebuildWidgets();
        }
    }

    private SbProfile profile() {
        return state == State.READY && result != null && !result.profiles().isEmpty()
                ? result.profiles().get(Math.min(profileIndex, result.profiles().size() - 1)) : null;
    }


    @Override
    protected void init() {
        // 2026-10-08: a hair bigger than the old 480 x 290 now the backend footer is gone.
        pvStyle = ProfileViewerConfig.getInstance().getStyle();
        boolean iconTabs = pvStyle != ProfileViewerConfig.Style.DEFAULT;
        if (iconTabs) {
            // The originals' own sizes, centered on the screen (PvStyleChrome cites the lines); the tabs stand above it
            // and the profile button sits under it, so a short screen keeps both on it.
            panelW = PvStyleChrome.panelW(pvStyle, this.width, this.height);
            panelH = PvStyleChrome.panelH(pvStyle, this.height);
            panelY = Math.max(PvStyleChrome.tabRise(pvStyle), Math.min((this.height - panelH) / 2, this.height - panelH - 23));
        } else {
            panelW = Math.min(this.width - 12, 520);
            panelH = Math.min(this.height - 12, 320);
            panelY = (this.height - panelH) / 2;
        }
        layoutTabs(); // after panelW: the first open used last frame's width (0), squeezing every tab
        panelX = (this.width - panelW) / 2;
        contentX = panelX + 8;
        // Default: two rows of named tabs inside the panel; the other Styles stand icon tabs on its top edge.
        contentY = iconTabs ? panelY + 26 : panelY + 29 + TAB_ROWS * 21;
        contentW = panelW - 16;
        contentH = Math.max(60, panelY + panelH - 8 - contentY);

        if (page == PAGE_BASIC && skinProfile != null && profile() != null && ProfileViewerConfig.getInstance().isShowSkin()) {
            // NEU Classic: in pv_basic's model frame; otherwise at the top of the left box.
            boolean neu = pvStyle == ProfileViewerConfig.Style.NEU;
            int[] m = PvStyleChrome.NEU_BASIC_MODEL;
            // NEU draws the player at scale 36 standing on (63, 135) under the name at y 54 (BasicPage.java:636-643).
            int skinH = neu ? 78 : Math.min(120, contentH - 90);
            if (pvStyle != ProfileViewerConfig.Style.DEFAULT && !neu) { // SkyBlockPV: in its player frame, middle column
                int w = (int) (panelW * 0.2), h = (int) (w * 1.1);
                PlayerSkinWidget skin = new PlayerSkinWidget(w, h - h / 10, this.minecraft.getEntityModels(),
                        this.minecraft.getSkinManager().createLookup(skinProfile, false));
                skin.setPosition(panelX + (panelW - w) / 2, sbpvPlayerY() + h / 10);
                addRenderableWidget(skin);
            } else if (skinH >= 50) {
                PlayerSkinWidget skin = new PlayerSkinWidget(skinH / 2 + 10, skinH, this.minecraft.getEntityModels(),
                        this.minecraft.getSkinManager().createLookup(skinProfile, false));
                skin.setPosition(neu ? panelX + 63 - (skinH / 2 + 10) / 2 : contentX + (118 - (skinH / 2 + 10)) / 2,
                        neu ? panelY + 138 - skinH : contentY + 4);
                addRenderableWidget(skin);
            }
        }

        // Recent-views sidebar: only when it fits in the panel's own centering margin, so it never
        // squeezes the main content or overlaps the edge of the window on a small screen.
        int margin = (this.width - panelW) / 2;
        int gap = SIDEBAR_GAP + (pvStyle == ProfileViewerConfig.Style.DEFAULT || pvStyle == ProfileViewerConfig.Style.NEU ? 0 : 24); // SkyBlockPV's right tabs
        // NEU Classic has no sidebar: recent players are tabs on the panel's right edge and the name box sits under the
        // panel's right corner, 100 wide (GuiProfileViewer.java:325, :722-776).
        boolean neuChrome = pvStyle == ProfileViewerConfig.Style.NEU;
        sidebarVisible = !neuChrome && margin >= gap + SIDEBAR_W;
        switchBox = null;
        invSearch = new ThemedEditBox(this.font, 0, 0, 90, 14, Component.literal("Search items")).hint("Search items...");
        invSearch.setMaxLength(40);
        invSearch.setValue(invSearchText);
        invSearch.setResponder(text -> {
            invSearchText = text;
            invFind.setQuery(text);
        });
        invSearch.visible = false;
        addRenderableWidget(invSearch);
        if (sidebarVisible || neuChrome) {
            sidebarX = panelX + panelW + gap;
            sidebarY = panelY;
            sidebarW = Math.min(SIDEBAR_W_WANTED, margin - gap - 2);
            sidebarH = panelH;
            switchBox = neuChrome
                    ? new ThemedEditBox(this.font, panelX + panelW - 100, panelY + panelH + 5, 100, 20, Component.literal("Player name"))
                    : new ThemedEditBox(this.font, sidebarX + 4, sidebarY + 16, sidebarW - 8, 14, Component.literal("Player name"));
            ((ThemedEditBox) switchBox).hint("Name...");
            switchBox.setMaxLength(16);
            switchBox.setValue(switchBoxText);
            switchBox.setResponder(text -> {
                switchBoxText = text;
                if (!applyingSuggestion) {
                    updateSuggestions();
                }
            });
            addRenderableWidget(switchBox);
            updateSuggestions();
        }
    }

    /** Every page tab on exactly {@link #TAB_ROWS} rows (killer560, 2026-10-08: "adjust the top sections to all
     *  fit in 2 rows"): half the tabs per row, each row stretched to the panel's width in proportion to its
     *  labels, or squeezed (label clipped) when the window is too narrow for them. */
    static final int TAB_ROWS = 2;

    private void layoutTabs() {
        tabRects.clear();
        int perRow = (PAGES.length + TAB_ROWS - 1) / TAB_ROWS;
        int avail = panelW - 12;
        for (int row = 0; row < TAB_ROWS; row++) {
            int from = row * perRow;
            int to = Math.min(PAGES.length, from + perRow);
            if (from >= to) break;
            int gaps = (to - from - 1) * 3;
            int natural = 0;
            for (int i = from; i < to; i++) {
                natural += this.font.width(PAGES[i]) + 24;
            }
            double scale = (double) (avail - gaps) / Math.max(1, natural);
            int tx = 6;
            for (int i = from; i < to; i++) {
                int w = i == to - 1 ? 6 + avail - tx : (int) Math.floor((this.font.width(PAGES[i]) + 24) * scale);
                tabRects.add(new int[]{tx, row, Math.max(20, w)});
                tx += w + 3;
            }
        }
    }


    private void updateSuggestions() {
        suggestionIndex = -1;
        String typed = switchBoxText == null ? "" : switchBoxText.trim();
        suggestions = typed.isEmpty() ? List.of() : completions(typed);
        if (switchBox != null) {
            String first = suggestions.isEmpty() ? null : suggestions.get(0);
            switchBox.setSuggestion(first != null && first.length() > typed.length() && typed.equals(switchBoxText)
                    ? first.substring(typed.length()) : null);
        }
    }

    /** Known names starting with {@code typed} (case-insensitive): players in the tab list first, then recent
     *  views, then anyone else looked up this session. Public for the testkit. */
    public List<String> completions(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        List<String> online = new ArrayList<>();
        net.minecraft.client.multiplayer.ClientPacketListener conn = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (conn != null) {
            for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getOnlinePlayers()) {
                GameProfile gp = info.getProfile();
                // Hypixel's fake tab columns ("!A-a") fail the name check; its NPCs carry version-2 uuids.
                if (gp != null && ProfileViewerApi.isValidName(gp.name()) && (gp.id() == null || gp.id().version() != 2)) {
                    online.add(gp.name());
                }
            }
        }
        online.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> known = new ArrayList<>();
        for (ProfileViewerConfig.RecentView v : ProfileViewerConfig.getInstance().getRecentViews()) {
            known.add(LIVE_NAME_CACHE.getOrDefault(v.uuid(), v.name()));
        }
        ProfileViewerFeature.Target self = ProfileViewerFeature.self();
        if (self != null && self.name() != null) {
            known.add(self.name());
        }
        known.addAll(LIVE_NAME_CACHE.values());
        for (List<String> source : List.of(online, known)) {
            for (String name : source) {
                if (name != null && ProfileViewerApi.isValidName(name) && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        List<String> list = new ArrayList<>(out.values());
        return list.size() > 20 ? new ArrayList<>(list.subList(0, 20)) : list;
    }

    /** Tab: fill the box with the next suggestion (cycling), keeping the list from what was actually typed. */
    private boolean cycleSuggestion(boolean backwards) {
        if (switchBox == null || suggestions.isEmpty()) return false;
        suggestionIndex = Math.floorMod(suggestionIndex + (backwards ? -1 : 1), suggestions.size());
        pickSuggestion(suggestions.get(suggestionIndex));
        return true;
    }

    private void pickSuggestion(String name) {
        applyingSuggestion = true;
        try {
            switchBox.setValue(name);
            switchBox.setSuggestion(null);
            switchBoxText = name;
        } finally {
            applyingSuggestion = false;
        }
    }

    private void drawSuggestions(GuiGraphicsExtractor g, int mx, int my) {
        if (switchBox == null || !switchBox.isFocused() || suggestions.isEmpty()) return;
        int x = switchBox.getX();
        int y = switchBox.getY() + switchBox.getHeight() + 1;
        int w = switchBox.getWidth();
        int n = Math.min(SUGGESTION_ROWS, suggestions.size());
        int first = suggestionIndex >= SUGGESTION_ROWS ? suggestionIndex - SUGGESTION_ROWS + 1 : 0;
        popup(g, x, y, w, n * SUGGESTION_ROW_H + 2);
        for (int r = 0; r < n; r++) {
            int i = first + r;
            if (i >= suggestions.size()) break;
            String name = suggestions.get(i);
            int ry = y + 1 + r * SUGGESTION_ROW_H;
            boolean hover = inside(mx, my, x, ry, w, SUGGESTION_ROW_H);
            if (hover || i == suggestionIndex) {
                selBand(g, x + 1, ry, w - 2, SUGGESTION_ROW_H);
            }
            tx(g, this.font.plainSubstrByWidth(name, w - 6), x + 3, ry + 2, i == suggestionIndex ? VALUE : TEXT);
            overlayHotspots.add(new Hotspot(x, ry, w, SUGGESTION_ROW_H, () -> {
                pickSuggestion(name);
                submitSwitch();
            }));
        }
    }

    private void setPage(int newPage) {
        if (newPage == page) return;
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


    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (com.killer560.hub.compat.McInput.button(event) == 0) {
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
            if (statusOpen && !sbpvStyle()) { // a click outside the Status button closes the popup (the button itself toggles it)
                boolean onButton = inside(event.x(), event.y(), statusButton[0], statusButton[1], statusButton[2], statusButton[3]);
                if (!onButton) {
                    statusOpen = false;
                    return true;
                }
            }
            for (int i = hotspots.size() - 1; i >= 0; i--) {
                Hotspot h = hotspots.get(i);
                if (h.contains(event.x(), event.y())) {
                    click();
                    h.action().run();
                    return true;
                }
            }
        } else if (com.killer560.hub.compat.McInput.button(event) == 1 && !dropdownOpen
                && rightHotspots.stream().anyMatch(h -> h.contains(event.x(), event.y()))) {
            click();
            rightHotspots.stream().filter(h -> h.contains(event.x(), event.y())).findFirst().get().action().run();
            return true;
        } else if (com.killer560.hub.compat.McInput.button(event) == 1 && !dropdownOpen
                && inside(event.x(), event.y(), styleButton[0], styleButton[1], styleButton[2], styleButton[3])) {
            click();
            stepStyle(-1);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void click() { this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

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
            } else if (invView == PvSearch.SACKS) {
                pageScroll = Math.max(0, pageScroll - (int) Math.signum(scrollY));
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
        if (page == PAGE_INVENTORIES && state == State.READY && invSearch.visible) {
            if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
                setFocused(invSearch);
                invSearch.setFocused(true);
                return true;
            }
            if (invSearch.isFocused()) { // typed digits and arrows belong to the box, not the page keys
                return super.keyPressed(event);
            }
        }
        if (switchBox != null && switchBox.isFocused()) {
            // Enter submits the typed name; otherwise let the box handle its own text/cursor keys
            // (in particular Left/Right, which would otherwise page instead of moving the cursor).
            if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
                submitSwitch();
                return true;
            }
            if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && cycleSuggestion(event.hasShiftDown())) {
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
    public String themeId() { return THEME_ID; }

    @Override
    public int[] ownColours() { return pvStyle.colours; }

    /** The in-screen Style button: steps the Style setting ({@code dir} 1 forward, -1 back), saves it and lays out again. */
    void stepStyle(int dir) {
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        cfg.setStyle(com.killer560.hub.gui.SettingsButtonWidget.step(cfg.getStyle(), dir));
        cfg.save();
        rebuildSafe();
    }

    @Override
    public int[] themedPanelBounds() { return new int[]{panelX, panelY, panelW, panelH}; }

    @Override
    public void onClose() { McCompat.setScreen(this.minecraft, parent); }

    @Override
    public boolean isPauseScreen() { return false; }


    /** NEU blurs the world behind its translucent panel; SkyBlockPV dims it with vanilla's gradient, and blurs too only
     *  in its the reference theme (BaseWindowedPvScreen.kt:546-552, "background_blur"). Default keeps the vanilla menu background. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        switch (pvStyle) {
            case DEFAULT -> super.extractBackground(g, mouseX, mouseY, partialTick);
            case NEU, SBPV_CLEAR -> {
                extractBlurredBackground(g);
                extractTransparentBackground(g);
            }
            default -> extractTransparentBackground(g);
        }
    }

    /** SkyBlockPv draws its text without a shadow, so its styles turn the menu's shadow off for this screen's frame only. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        syncTheme(); // first: the palette sets the shadow, which the SkyBlockPv styles then turn off
        boolean shadow = MenuStyle.TEXT_SHADOW;
        if (pvStyle != ProfileViewerConfig.Style.DEFAULT && pvStyle != ProfileViewerConfig.Style.NEU) {
            MenuStyle.TEXT_SHADOW = false;
        }
        try {
            drawScreen(g, mouseX, mouseY, partialTick);
        } finally {
            MenuStyle.TEXT_SHADOW = shadow;
        }
    }

    private void drawScreen(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hotspots.clear();
        rightHotspots.clear();
        overlayHotspots.clear();
        pendingTooltip = null;
        pendingItemTooltip = ItemStack.EMPTY;

        if (pvStyle == ProfileViewerConfig.Style.DEFAULT) {
            ScreenChrome.backdrop(g, this.width, this.height);
            ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        } else {
            // NEU draws its unpressed tabs behind the panel; SkyBlockPV draws all of them over it (drawIconTabs).
            if (pvStyle == ProfileViewerConfig.Style.NEU) {
                drawIconTabs(g, mouseX, mouseY, true);
                drawNeuRecent(g, mouseX, mouseY, false);
            }
            PvStyleChrome.frame(g, pvStyle, panelX, panelY, panelW, panelH);
            if (pvStyle == ProfileViewerConfig.Style.NEU) {
                drawNeuRecent(g, mouseX, mouseY, true);
            }
            Identifier over = !neuPage() ? null : switch (page) { // The Mining page has none: plain pv_bg
                case PAGE_BASIC -> PvStyleChrome.NEU_BASIC;
                case PAGE_DUNGEONS -> PvStyleChrome.NEU_DUNG;
                case PAGE_INVENTORIES -> PvStyleChrome.NEU_INVS;
                case PAGE_PETS -> PvStyleChrome.NEU_PETS;
                case PAGE_COLLECTIONS -> PvStyleChrome.NEU_COLS;
                case PAGE_CRIMSON -> PvStyleChrome.NEU_CRIMSON;
                case PAGE_MUSEUM -> PvStyleChrome.NEU_MUSEUM;
                case PAGE_BESTIARY -> PvStyleChrome.NEU_BESTIARY;
                case PAGE_RIFT -> PvStyleChrome.NEU_RIFT;
                case PAGE_FARMING -> PvStyleChrome.NEU_GARDEN;
                default -> null;
            };
            if (over != null) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, over, panelX, panelY, PvStyleChrome.NEU_W, PvStyleChrome.NEU_H);
            }
        }

        invSearch.visible = false; // drawInventories shows it
        if (page != PAGE_INVENTORIES) {
            invSearch.setFocused(false);
        }
        drawingHeader = true;
        drawHeader(g, mouseX, mouseY);
        drawingHeader = false;
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

        // 2026-10-08: no "SkyBlockPV Backend · 3m ago" footer any more (it sat over the content); the data's
        // age is on the Refresh button's hover instead.

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (switchBox != null) {
            drawSuggestions(g, mouseX, mouseY);
        }

        if (dropdownOpen) {
            drawDropdown(g, mouseX, mouseY);
        }
        if (statusOpen && statusButton[0] >= 0) {
            drawStatus(g);
        }

        if (!pendingItemTooltip.isEmpty()) {
            com.killer560.hub.pricetooltip.PriceTooltipFeature.drawItemTooltip(g, this.font, pendingItemTooltip, mouseX, mouseY);
        } else if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.setTooltipForNextFrame(this.font, pendingTooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    String dots() { return ".".repeat((int) (System.currentTimeMillis() / 400 % 4)); }

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        boolean iconTabs = pvStyle != ProfileViewerConfig.Style.DEFAULT;
        int nameX;
        if (iconTabs) {
            // NEU and SkyBlockPV have no title bar: just the player's name.
            nameX = panelX + 4;
        } else {
            // Header bar (the menu's): the brand-gradient title, then the player's name.
            GuiRects hr = GuiRects.begin(g);
            MenuStyle.dropShadow(hr, panelX + 2, panelY + 2, panelW - 4, 22, 2);
            MenuStyle.bevel(hr, panelX + 2, panelY + 2, panelW - 4, 22, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
            hr.submit();
            MenuStyle.gradientText(g, "Profile Viewer", panelX + 8, panelY + 8, 1f, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
            nameX = panelX + 16 + this.font.width("Profile Viewer");
            tx(g, "·", nameX - 4, panelY + 8, DIM);
        }
        boolean neuChrome = pvStyle == ProfileViewerConfig.Style.NEU;
        if (!neuChrome || state != State.READY) { // NEU Classic draws the name over the player on its Basic page only
            tx(g, displayName == null ? "" : displayName, nameX + 4, panelY + 8, VALUE);
        }

        SbProfile p = profile();
        // NEU's profile button says only the profile's name, its gamemode icon 21 px left of it (:333-350).
        String profileLabel = p == null ? null : neuChrome ? p.cuteName : "Profile: " + p.cuteName + gameModeTag(p) + " ▾";
        String styleLabel = "Style: " + pvStyle.label;
        String refresh = neuChrome ? "Refresh" : "⟳ Refresh";
        Identifier modeIcon = neuChrome && p != null ? PvStyleChrome.neuGameMode(p.gameMode) : null;
        if (modeIcon != null) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, modeIcon, panelX - 21, panelY + panelH + 5, 16, 16);
        }
        int bh, rx, ry, rw, px, py, pw, sx, sy, sw, stx, sty;
        String statusLabel = "Status ▾";
        int stw = this.font.width(statusLabel) + 10;
        // The profile button is as wide as its longest label ("Profile: Pomegranate ▾"), never a fixed 100.
        int profW = Math.max(100, profileLabel == null ? 0 : this.font.width(profileLabel) + 10);
        if (pvStyle == ProfileViewerConfig.Style.NEU) {
            // 20 tall dropdowns 3 px under the panel (GuiProfileViewer.java:331-357); wider than 100 only for a long name.
            bh = 20;
            px = panelX; py = panelY + panelH + 3; pw = profW;
            sx = 5; sy = 5; sw = this.font.width(styleLabel) + 10; // the Style button is top left in every style (killer560, 2026-11-09)
            rx = px + pw + 6; ry = py; rw = 100;
            stx = rx + rw + 6; sty = py;
        } else if (iconTabs) {
            // SkyBlockPV: the profile dropdown under the panel's left corner (:505), its icon buttons at the screen's
            // top left, 20 tall (:198-237).
            bh = 20;
            px = panelX; py = panelY + panelH; pw = profW;
            sx = 5; sy = 5; sw = this.font.width(styleLabel) + 10;
            rx = sx + sw + 2; ry = 5; rw = this.font.width(refresh) + 10;
            stx = rx + rw + 2; sty = 5;
        } else {
            bh = 16;
            int right = panelX + panelW - 6;
            rw = this.font.width(refresh) + 10;
            right -= rw;
            rx = right; ry = panelY + 4;
            pw = profileLabel == null ? 0 : this.font.width(profileLabel) + 10;
            if (profileLabel != null) right -= pw + 4;
            px = right; py = ry;
            sw = this.font.width(styleLabel) + 10;
            sx = 5; sy = 5;
            stx = px - stw - 4; sty = ry;
        }
        if (!statusInPage()) {
            statusButton[0] = stx;
            statusButton[1] = sty;
            statusButton[2] = stw;
            statusButton[3] = bh;
            button(g, stx, sty, stw, bh, statusLabel, mx, my, statusOpen, this::toggleStatus);
        }
        button(g, rx, ry, rw, bh, refresh, mx, my, false, () -> load(true));
        if (result != null && state == State.READY && inside(mx, my, rx, ry, rw, bh) && !dropdownOpen) {
            long age = (System.currentTimeMillis() - result.fetchedAt()) / 1000;
            pendingTooltip = List.of(Component.literal("Updated " + (age < 60 ? age + "s ago" : (age / 60) + "m ago"))
                    .withStyle(style(DIM)));
        }
        if (profileLabel != null) {
            profileButtonX = px;
            profileButtonY = py;
            profileButtonH = bh;
            button(g, px, py, pw, bh, profileLabel, mx, my, dropdownOpen, () -> dropdownOpen = !dropdownOpen);
        }
        // Style: left click the next one, right click the one before; saved at once (the settings tab's Style row).
        styleButton[0] = sx;
        styleButton[1] = sy;
        styleButton[2] = sw;
        styleButton[3] = bh;
        button(g, sx, sy, sw, bh, styleLabel, mx, my, false, () -> stepStyle(1));
        if (inside(mx, my, sx, sy, sw, bh) && !dropdownOpen) {
            pendingTooltip = List.of(Component.literal("Style: " + pvStyle.label).withStyle(style(VALUE)),
                    Component.literal("Left click: next style. Right click: the one before.").withStyle(style(DIM)));
        }

        if (iconTabs) {
            if (pvStyle != ProfileViewerConfig.Style.NEU) {
                drawIconTabs(g, mx, my, true);
            }
            drawIconTabs(g, mx, my, false);
            return;
        }
        for (int i = 0; i < PAGES.length && i < tabRects.size(); i++) {
            int[] rect = tabRects.get(i);
            int tx = panelX + rect[0];
            int ty = panelY + 28 + rect[1] * 21;
            int w = rect[2];
            final int idx = i;
            boolean sel = i == page;
            boolean hover = inside(mx, my, tx, ty, w, 18) && !dropdownOpen;
            GuiRects tr = GuiRects.begin(g);
            if (sel) {
                // The sidebar's selected look: accent glow and gradient, accent strip on the left.
                MenuStyle.glow(tr, tx, ty, w, 18, MenuStyle.OR, 0x22);
                MenuStyle.hGradient(tr, tx + 1, ty + 1, tx + w - 1, ty + 17, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM, 6);
                tr.outline(tx, ty, w, 18, MenuStyle.ACCENT_EDGE);
                tr.fill(tx + 1, ty + 1, tx + w - 1, ty + 2, MenuStyle.HILITE);
                tr.fill(tx + 1, ty + 1, tx + 3, ty + 17, MenuStyle.OR);
            } else {
                ScreenChrome.card(tr, tx, ty, w, 18, hover);
                if (hover) {
                    tr.outline(tx, ty, w, 18, MenuStyle.OR2);
                }
            }
            tr.submit();
            g.item(pageIcon(i), tx + 2, ty + 1);
            tx(g, this.font.plainSubstrByWidth(PAGES[i], w - 21), tx + 19, ty + 5, sel ? VALUE : TEXT);
            hotspots.add(new Hotspot(tx, ty, w, 18, () -> setPage(idx)));
        }
    }

    /** NEU's and SkyBlockPV's page tabs, one row of item icons on the panel's top edge, the page name on hover.
     *  {@code unpressed} draws the tabs not selected (before the panel), else the selected one, the icons and
     *  hotspots, and SkyBlockPV's page name under the panel. */
    private void drawIconTabs(GuiGraphicsExtractor g, int mx, int my, boolean unpressed) {
        int w = PvStyleChrome.tabW(pvStyle);
        int h = PvStyleChrome.tabH(pvStyle);
        int x0 = panelX + PvStyleChrome.tabX0(pvStyle);
        int step = Math.min(PvStyleChrome.tabStep(pvStyle), (panelX + panelW - x0) / PAGES.length);
        int ty = panelY - PvStyleChrome.tabRise(pvStyle);
        boolean neu = pvStyle == ProfileViewerConfig.Style.NEU;
        for (int slot = 0; slot < PAGES.length; slot++) {
            int i = neu ? NEU_TAB_ORDER[slot] : slot;
            if ((i == page) == unpressed) continue;
            int tx = x0 + slot * step;
            PvStyleChrome.tab(g, pvStyle, slot, tx, ty, i == page, inside(mx, my, tx, ty, w, h) && !dropdownOpen);
        }
        if (unpressed) return;
        for (int slot = 0; slot < PAGES.length; slot++) {
            int i = neu ? NEU_TAB_ORDER[slot] : slot;
            int tx = x0 + slot * step;
            g.item(neu ? neuPageIcon(i) : pageIcon(i), tx + PvStyleChrome.iconDx(pvStyle), ty + PvStyleChrome.iconDy(pvStyle, i == page));
            final int idx = i;
            hotspots.add(new Hotspot(tx, ty, w, h, () -> setPage(idx)));
            if (inside(mx, my, tx, ty, w, h) && !dropdownOpen) {
                pendingTooltip = neu ? neuTabTip(i) : List.of(Component.literal(PAGES[i]).withStyle(style(VALUE)));
            }
        }
        PvStyleChrome.pageTitle(g, pvStyle, PAGES[page], panelX, panelY, panelW, panelH);
    }

    private static String gameModeTag(SbProfile p) {
        return switch (p.gameMode) {
            case "ironman" -> " ♲";
            case "island" -> " ☀";
            case "bingo" -> " Ⓑ";
            default -> "";
        };
    }

    /** Built once: the tabs draw every frame. */
    private static final ItemStack[] PAGE_ICONS = new ItemStack[PAGES.length];

    private static ItemStack pageIcon(int page) {
        ItemStack icon = PAGE_ICONS[page];
        if (icon == null) {
            icon = PAGE_ICONS[page] = newPageIcon(page);
        }
        return icon;
    }

    /** NEU Classic's tab order (GuiProfileViewer.ProfileViewerPage): Basic, Dungeon, Extra (our Misc), Inventories,
     *  Collections, Pets, Mining, Bestiary, Crimson, Museum, Rift, Garden; our Networth and Weight pages after them. */
    static final int[] NEU_TAB_ORDER = {0, 1, 12, 2, 4, 3, 5, 6, 7, 8, 9, 10, 11, 13};
    private static final ItemStack[] NEU_ICONS = new ItemStack[PAGES.length];
    private static final List<?>[] NEU_TIPS = new List<?>[PAGES.length];

    /** NEU Classic's tab icons and their one-line colored hover names. */
    private static ItemStack neuPageIcon(int page) {
        ItemStack icon = NEU_ICONS[page];
        if (icon == null) {
            icon = NEU_ICONS[page] = new ItemStack(switch (page) {
                case PAGE_BASIC -> Items.PAPER;
                case PAGE_DUNGEONS -> Items.DEAD_BUSH;
                case PAGE_MISC -> Items.BOOK;
                case PAGE_INVENTORIES -> Items.ENDER_CHEST;
                case PAGE_COLLECTIONS -> Items.PAINTING;
                case PAGE_PETS -> Items.BONE;
                case PAGE_MINING -> Items.IRON_PICKAXE;
                case PAGE_BESTIARY -> Items.IRON_SWORD;
                case PAGE_CRIMSON -> Items.NETHERRACK;
                case PAGE_MUSEUM -> Items.LEATHER_CHESTPLATE;
                case PAGE_RIFT -> Items.ENDER_EYE;
                case PAGE_FARMING -> Items.GRASS_BLOCK;
                case PAGE_NETWORTH -> Items.GOLD_INGOT;
                default -> Items.ANVIL;
            });
        }
        return icon;
    }

    @SuppressWarnings("unchecked")
    private static List<Component> neuTabTip(int page) {
        List<?> tip = NEU_TIPS[page];
        if (tip == null) {
            String name = switch (page) {
                case PAGE_BASIC -> "Skills";
                case PAGE_DUNGEONS -> "Dungeoneering";
                case PAGE_MISC -> "Profile Stats";
                case PAGE_INVENTORIES -> "Storage";
                case PAGE_MINING -> "Heart of the Mountain";
                case PAGE_CRIMSON -> "Crimson Isle";
                case PAGE_FARMING -> "Garden";
                default -> PAGES[page];
            };
            int color = switch (page) {
                case PAGE_BASIC -> 0xFF5555FF;
                case PAGE_DUNGEONS -> 0xFFFFFF55;
                case PAGE_MISC -> 0xFFAAAAAA;
                case PAGE_INVENTORIES -> 0xFF55FFFF;
                case PAGE_PETS -> 0xFF55FF55;
                case PAGE_MINING, PAGE_RIFT -> 0xFFAA00AA;
                case PAGE_BESTIARY -> 0xFFFF5555;
                case PAGE_CRIMSON -> 0xFFAA0000;
                case PAGE_FARMING -> 0xFF00AA00;
                case PAGE_WEIGHT -> 0xFFFFFFFF;
                default -> 0xFFFFAA00; // Collections, Museum, Networth
            };
            tip = NEU_TIPS[page] = List.of(Component.literal(name).withStyle(style(color)));
        }
        return (List<Component>) tip;
    }

    private static ItemStack newPageIcon(int page) {
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


    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        box(g, sidebarX, sidebarY, sidebarW, sidebarH);
        int x = sidebarX + 4;
        int y = sidebarY + 4;
        tx(g, "Switch", x, y, DIM);
        // The EditBox widget itself renders/handles input; just leave its row clear and add the button.
        y += 30;
        int btnY = y;
        button(g, x, btnY, sidebarW - 8, 14, "Go", mx, my, false, this::submitSwitch);
        y += 19;

        GuiRects sep = GuiRects.begin(g);
        MenuStyle.separator(sep, sidebarX + 4, sidebarX + sidebarW - 4, y);
        sep.submit();
        y += 4;
        tx(g, "Recent", x, y, DIM);
        y += SIDEBAR_ROW_H;

        ProfileViewerFeature.Target self = ProfileViewerFeature.self();
        y = sidebarRow(g, x, y, self.uuid(), self.name(), mx, my, () -> open(self));
        GuiRects div = GuiRects.begin(g);
        MenuStyle.separator(div, sidebarX + 4, sidebarX + sidebarW - 4, y + 1);
        div.submit();
        y += 5;

        int bottom = sidebarY + sidebarH - 4;
        for (ProfileViewerConfig.RecentView v : ProfileViewerConfig.getInstance().getRecentViews()) {
            if (v.uuid().equals(self.uuid()) || y + SIDEBAR_ROW_H > bottom) continue;
            y = sidebarRow(g, x, y, v.uuid(), v.name(), mx, my, () -> open(new ProfileViewerFeature.Target(
                    LIVE_NAME_CACHE.getOrDefault(v.uuid(), v.name()), v.uuid())));
        }
    }

    /** NEU Classic's recent players: tabs on the panel's right edge, 28 px apart from its top, your own head first;
     *  hover shows the name. The open player's tab goes in front of the panel ({@code front}), the rest behind it. */
    private void drawNeuRecent(GuiGraphicsExtractor g, int mx, int my, boolean front) {
        ProfileViewerFeature.Target self = ProfileViewerFeature.self();
        int x = panelX + panelW;
        neuRecentRow(g, x, panelY, true, self.uuid(), self.name(), mx, my, front, null);
        int i = 1;
        for (ProfileViewerConfig.RecentView v : ProfileViewerConfig.getInstance().getRecentViews()) {
            if (i >= NEU_RECENT_MAX) break;
            if (v.uuid().equals(self.uuid())) continue;
            neuRecentRow(g, x, panelY + i * 28, false, v.uuid(), v.name(), mx, my, front, v);
            i++;
        }
    }

    private static final int NEU_RECENT_MAX = 7;

    private void neuRecentRow(GuiGraphicsExtractor g, int x, int y, boolean top, UUID id, String storedName,
                              int mx, int my, boolean front, ProfileViewerConfig.RecentView v) {
        boolean selected = id.equals(this.uuid);
        if (selected == front) PvStyleChrome.neuRecentTab(g, x, y, top, selected);
        if (!front) return;
        g.item(headFor(id), x + 3, y + 6);
        if (inside(mx, my, x, y, 28, 28) && !dropdownOpen) {
            String name = LIVE_NAME_CACHE.getOrDefault(id, storedName);
            pendingTooltip = List.of(Component.literal(name).withStyle(style(VALUE)));
            hotspots.add(new Hotspot(x, y, 28, 28, () -> open(v == null ? ProfileViewerFeature.self()
                    : new ProfileViewerFeature.Target(LIVE_NAME_CACHE.getOrDefault(id, name), id))));
        }
    }

    /** One head + name row; returns the y for the next row. */
    private int sidebarRow(GuiGraphicsExtractor g, int x, int y, UUID uuid, String storedName, int mx, int my, Runnable onClick) {
        boolean hover = inside(mx, my, x - 2, y - 1, sidebarW - 4, SIDEBAR_ROW_H) && !dropdownOpen;
        if (hover) {
            GuiRects hr = GuiRects.begin(g);
            ScreenChrome.row(hr, x - 2, y - 1, sidebarW - 4, SIDEBAR_ROW_H, true, false, false);
            hr.submit();
        }
        if (uuid.equals(this.uuid)) {
            selBand(g, x - 2, y - 2, sidebarW - 4, SIDEBAR_ROW_H - 1);
        }
        g.item(headFor(uuid), x, y - 1);
        String name = LIVE_NAME_CACHE.getOrDefault(uuid, storedName);
        tx(g, this.font.plainSubstrByWidth(name, sidebarW - 22), x + 18, y + 3, hover || uuid.equals(this.uuid) ? VALUE : TEXT);
        hotspots.add(new Hotspot(x - 2, y - 1, sidebarW - 4, SIDEBAR_ROW_H, onClick));
        return y + SIDEBAR_ROW_H;
    }

    /** A player-head icon for the sidebar, fetched (and cached process-wide) from the session service the
     *  same way the Basic Info skin preview is - a plain head until the async lookup lands. */
    private static ItemStack headFor(UUID uuid) {
        ItemStack cached = HEAD_CACHE.get(uuid);
        if (cached != null) return cached;
        if (HEAD_FETCHING.add(uuid)) {
            ProfileViewerApi.fetchSkinProfile(uuid).thenAccept(gp -> minecraftExecute(() -> {
                if (gp == null) return;
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
                if (HEAD_CACHE.size() >= MAX_HEADS) {
                    HEAD_CACHE.clear();
                    HEAD_FETCHING.clear();
                    LIVE_NAME_CACHE.clear();
                }
                HEAD_CACHE.put(uuid, head);
                if (gp.name() != null && !gp.name().isEmpty()) {
                    LIVE_NAME_CACHE.put(uuid, gp.name());
                }
            }));
        }
        return new ItemStack(Items.PLAYER_HEAD);
    }

    private void drawDropdown(GuiGraphicsExtractor g, int mx, int my) {
        if (result == null) return;
        List<SbProfile> list = result.profiles();
        int w = 150;
        for (SbProfile p : list) {
            w = Math.max(w, this.font.width(p.cuteName + gameModeTag(p) + "  (selected)") + 12);
        }
        int x = profileButtonX >= 0 ? profileButtonX : panelX + panelW - 6 - w;
        x = Math.max(panelX + 4, Math.min(x, panelX + panelW - 4 - w));
        int rowH = 14;
        int listH = list.size() * rowH + 4;
        int y = profileButtonY < 0 ? panelY + 21 : profileButtonY + profileButtonH + 1;
        if (y + listH > this.height && profileButtonY - 1 - listH >= 0) {
            y = profileButtonY - 1 - listH; // no room under the button: open above it
        }
        popup(g, x, y, w, listH);
        for (int i = 0; i < list.size(); i++) {
            SbProfile p = list.get(i);
            int ry = y + 2 + i * rowH;
            boolean hover = inside(mx, my, x, ry, w, rowH);
            if (hover) {
                selBand(g, x + 1, ry, w - 2, rowH);
            }
            String label = p.cuteName + gameModeTag(p) + (p.selected ? "  (selected)" : "");
            tx(g, label, x + 6, ry + 3, i == profileIndex ? VALUE : TEXT);
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
                ScreenChrome.text(g, seq, contentX + contentW / 2 - this.font.width(seq) / 2, y, TEXT);
                y += 11;
            }
        }
        y += 6;
        button(g, contentX + contentW / 2 - 30, y, 60, 16, "Retry", mx, my, false, () -> load(true));
    }


    /** SkyBlockPV's player frame on its main page: the middle 20 % column, 1.1 times as high as wide, centered with its
     *  status button (5 + 20 + 3 below) in the content height; its top. */
    private int sbpvPlayerY() {
        return contentY + (panelY + panelH - contentY - (int) ((int) (panelW * 0.2) * 1.1) - 28) / 2;
    }

    /** SkyBlockPV's main page (MainScreen): Info on the left, the player in the middle 20 %, Skills and Slayer chips on
     *  the right then Essence, each a label (title bar over a box) 10 below the top; lines and chips have their hovers. */
    private void drawSbpvBasic(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int middle = (int) (panelW * 0.2), side = (panelW - middle) / 2, text = PvStyleChrome.sbpvText(pvStyle);
        int fh = (int) (middle * 1.1), fy = sbpvPlayerY();
        PvStyleChrome.playerFrame(g, pvStyle, panelX + side, fy, middle, fh);
        int sx = panelX + side, sy = fy + fh + 5;
        PvStyleChrome.playerFrame(g, pvStyle, sx, sy, middle, 20);
        // The empty box under the player holds the status: "Status" until clicked, then the fetched text (full text on hover).
        String full = statusOpen ? String.join(", ", statusLines()) : "Status";
        centeredIn(g, this.font.plainSubstrByWidth(full, middle - 8), sx, middle, sy + 6, PvStyleChrome.sbpvText(pvStyle));
        statusButton[0] = sx;
        statusButton[1] = sy;
        statusButton[2] = middle;
        statusButton[3] = 20;
        hotspots.add(new Hotspot(sx, sy, middle, 20, this::toggleStatus));
        if (inside(mx, my, sx, sy, middle, 20) && !dropdownOpen) {
            pendingTooltip = List.of(Component.literal(full).withStyle(style(VALUE)),
                    Component.literal(statusOpen ? "Click to hide" : "Click to check status").withStyle(style(DIM)));
        }
        // Info: the gray lines centered in the column, 10 below the top (MainScreen's spacer); all text built once.
        ProfileExtras ex = p.extras().isDone() && !p.extras().isCompletedExceptionally() ? p.extras().getNow(null) : null;
        ExtraPages.NetworthState nw = extraPages.networthState(p);
        long worth = nw.result() != null ? nw.result().total(ProfileViewerConfig.getInstance().getNetworthView()) : nw.failed() ? -2 : -1;
        SbpvMain m = sbpvMain;
        if (m == null || m.profile != p || m.worth != worth || m.extras != ex) {
            m = sbpvMain = new SbpvMain(p, worth, ex);
        }
        int maxW = 0;
        for (String line : m.info) {
            maxW = Math.max(maxW, this.font.width(line));
        }
        int y = sbpvLabel(g, "Info", panelX, contentY + 10, side, 7 + m.info.length * 11 - 2 + 7, text);
        int lx = panelX + (side - maxW) / 2;
        for (int i = 0; i < m.info.length; i++) {
            if (m.infoTips[i] != null && !dropdownOpen && inside(mx, my, lx, y - 1, this.font.width(m.info[i]), 10)) {
                pendingTooltip = m.infoTips[i];
            }
            if (i == m.info.length - 1) {
                hotspots.add(new Hotspot(lx, y - 1, this.font.width(m.info[i]), 10, () -> setPage(PAGE_NETWORTH)));
            }
            y = sbpvLine(g, m.info[i], lx, y, side - 20, text);
        }
        // Skills, Slayer, then Essence (only when there is any): chip sections side - 20 wide, 10 apart from the top.
        int rx = panelX + side + middle;
        int ry = contentY + 10;
        for (SbpvChips c : m.sections) {
            ry = sbpvChips(g, c, rx, ry, side - 20, mx, my, text) + 10;
        }
    }

    private SbpvMain sbpvMain;

    /** One SkyBlockPV chip section: its title, and per chip the icon, the text and the hover (null for none). */
    private record SbpvChips(String title, ItemStack[] icons, String[] values, List<Component>[] tips) {
    }

    /** Everything SkyBlockPV's main page writes, built once per profile, net worth and extras (MainScreen.kt). */
    private static final class SbpvMain {
        final SbProfile profile;
        final long worth;
        final ProfileExtras extras;
        final String[] info;
        final List<Component>[] infoTips;
        final List<SbpvChips> sections = new ArrayList<>();

        @SuppressWarnings("unchecked")
        SbpvMain(SbProfile p, long worth, ProfileExtras ex) {
            this.profile = p;
            this.worth = worth;
            this.extras = ex;
            double sum = 0;
            int count = 0;
            if (p.skillXp != null) {
                for (String id : LevelTables.SKILL_ORDER) {
                    if (LevelTables.countsForAverage(id) && LevelTables.skill(id) != null) {
                        sum += LevelTables.skillLevel(id, p.skillXp.getOrDefault(id, 0L), p.skillCaps.getOrDefault(id, -1)).level();
                        count++;
                    }
                }
            }
            String sb = p.sbLevel + (p.sbLevelProgress > 0 ? "." + String.format(Locale.ROOT, "%02d", p.sbLevelProgress) : "");
            List<String> lines = new ArrayList<>();
            List<List<Component>> tips = new ArrayList<>();
            lines.add("Purse: " + commas(p.purse));
            tips.add(null);
            String solo = p.personalBank > 0 ? shorten(p.personalBank) : null, coop = p.bank > 0 ? shorten(p.bank) : null;
            lines.add("Bank: " + (p.bank < 0 ? "API off" : solo != null && coop != null ? solo + "/" + coop : solo != null ? solo : coop != null ? coop : "0"));
            tips.add(p.bank < 0 ? null : List.of(Component.literal("§7Solo Bank: §6" + commas(Math.max(0, p.personalBank))),
                    Component.literal("§7Profile Bank: §6" + commas(Math.max(0, p.bank)))));
            lines.add("SkyBlock Level: " + sb);
            tips.add(List.of(Component.literal("§7Progress: " + p.sbLevelProgress + "/100")));
            if (p.firstJoin > 0) {
                java.time.ZonedDateTime at = Instant.ofEpochMilli(p.firstJoin).atZone(ZoneId.systemDefault());
                lines.add("First Join: " + SBPV_JOIN.format(at));
                tips.add(List.of(Component.literal(SBPV_JOIN_FULL.format(at))));
            }
            if (count > 0) {
                lines.add("Skill Avg: " + String.format(Locale.ROOT, "%.2f", sum / count));
                tips.add(null);
            }
            lines.add("Fairy Souls: " + p.fairySouls);
            tips.add(null);
            if (ex != null) {
                long kills = 0, deaths = 0;
                for (long v : ex.misc.kills().values()) kills = Math.max(kills, v);
                for (long v : ex.misc.deaths().values()) deaths = Math.max(deaths, v);
                lines.add("K/D: " + shorten(kills) + "/" + shorten(deaths) + " ("
                        + (deaths == 0 ? "∞" : String.format(Locale.ROOT, "%.2f", (double) kills / deaths)) + ")");
                tips.add(null);
            }
            lines.add("Net Worth: " + (worth >= 0 ? shorten(worth) : worth == -2 ? "Failed to load" : "Loading..."));
            tips.add(worth >= 0 ? List.of(Component.literal("§eNetworth: §a" + commas(worth)),
                    Component.literal("§7Click for the Networth page")) : null);
            info = lines.toArray(new String[0]);
            infoTips = tips.toArray(new List[0]);

            if (p.skillXp != null) {
                List<String> ids = LevelTables.SKILL_ORDER;
                int n = ids.size();
                ItemStack[] icons = new ItemStack[n];
                String[] values = new String[n];
                List<Component>[] chipTips = new List[n];
                for (int i = 0; i < n; i++) {
                    String id = ids.get(i);
                    long xp = p.skillXp.getOrDefault(id, 0L);
                    int cap = p.skillCaps.getOrDefault(id, -1);
                    LevelTables.Level lvl = LevelTables.skillLevel(id, xp, cap);
                    LevelTables.Skill def = LevelTables.skill(id);
                    icons[i] = skillIcon(id);
                    values[i] = String.valueOf(lvl.level());
                    List<Component> t = new ArrayList<>();
                    t.add(Component.literal("§e" + (def != null ? def.name() : SbProfile.titleCase(id))));
                    t.add(Component.literal("§7Exp: " + shorten(xp)));
                    boolean capped = def != null && cap > 0 && cap < def.maxLevel() && lvl.maxed();
                    t.add(Component.literal("§7Progress: " + (lvl.maxed() && !capped ? "§cMaxed!" : capped ? "§5Reached max skill cap!"
                            : "§a" + String.format(Locale.ROOT, "%.2f", lvl.progress() * 100) + "% to next")));
                    if (def != null && def.totalXp().length > 0 && lvl.level() != def.maxLevel()) {
                        long need = def.totalXp()[Math.min(def.maxLevel(), def.totalXp().length) - 1];
                        t.add(Component.literal("§7Progress to max: §e" + commas(xp) + "§6/§e" + shorten(need)
                                + "§a (" + String.format(Locale.ROOT, "%.2f", xp * 100.0 / need) + "%)"));
                    }
                    chipTips[i] = List.copyOf(t);
                }
                sections.add(new SbpvChips("Skills", icons, values, chipTips));
            }
            List<LevelTables.Slayer> slayers = LevelTables.slayers();
            int n = slayers.size();
            ItemStack[] icons = new ItemStack[n];
            String[] values = new String[n];
            List<Component>[] chipTips = new List[n];
            for (int i = 0; i < n; i++) {
                LevelTables.Slayer s = slayers.get(i);
                SbProfile.SlayerStat st = p.slayers.get(s.apiId());
                long xp = st == null ? 0 : st.xp();
                LevelTables.Level lvl = LevelTables.slayerLevel(s, xp);
                icons[i] = slayerIcon(s.apiId());
                values[i] = String.valueOf(lvl.level());
                long last = s.leveling().length == 0 ? 0 : s.leveling()[s.leveling().length - 1];
                StringBuilder kills = new StringBuilder("§7Kills: ");
                int tiers = s.bossXp() == null ? 5 : s.bossXp().length;
                for (int t = 0; t < tiers; t++) {
                    Integer k = st == null ? null : st.kills().get(t);
                    kills.append(t == 0 ? "§7" : "§8/§7").append(commas(k == null ? 0 : k));
                }
                List<Component> t = new ArrayList<>();
                t.add(Component.literal("§e" + SbProfile.titleCase(s.key())));
                t.add(Component.literal(kills.toString()));
                t.add(Component.literal("§7Exp: §e" + commas(xp) + (last > 0 && xp >= last ? " §cMaxed!"
                        : "§6/§e" + commas(last) + "§a (" + String.format(Locale.ROOT, "%.2f", last <= 0 ? 0 : xp * 100.0 / last) + "%)")));
                if (!lvl.maxed() && lvl.level() < s.leveling().length) {
                    long next = s.leveling()[lvl.level()];
                    t.add(Component.literal("§7Next Level: §e" + commas(xp) + "§6/§e" + commas(next)
                            + "§a (" + String.format(Locale.ROOT, "%.2f", xp * 100.0 / Math.max(1, next)) + "%)"));
                }
                chipTips[i] = List.copyOf(t);
            }
            sections.add(new SbpvChips("Slayer", icons, values, chipTips));
            if (ex != null && !ex.misc.essence().isEmpty()) {
                List<Map.Entry<String, Long>> es = new ArrayList<>(ex.misc.essence().entrySet());
                long total = 0;
                for (Map.Entry<String, Long> e : es) total += e.getValue();
                if (total > 0) {
                    int k = es.size();
                    ItemStack[] ei = new ItemStack[k];
                    String[] ev = new String[k];
                    for (int i = 0; i < k; i++) {
                        ei[i] = essenceIcon(es.get(i).getKey());
                        ev[i] = shorten(es.get(i).getValue());
                    }
                    sections.add(new SbpvChips("Essence", ei, ev, new List[k]));
                }
            }
        }
    }

    /** A vanilla stand-in for each essence's head (SkyBlockPV shows the essence item itself). */
    private static ItemStack essenceIcon(String type) {
        return new ItemStack(switch (type.toUpperCase(Locale.ROOT)) {
            case "WITHER" -> Items.WITHER_SKELETON_SKULL;
            case "UNDEAD" -> Items.SKELETON_SKULL;
            case "DRAGON" -> Items.DRAGON_HEAD;
            case "GOLD" -> Items.GOLD_NUGGET;
            case "DIAMOND" -> Items.DIAMOND;
            case "ICE" -> Items.ICE;
            case "SPIDER" -> Items.SPIDER_EYE;
            case "CRIMSON" -> Items.CRIMSON_FUNGUS;
            default -> Items.PAPER;
        });
    }

    private static final DateTimeFormatter SBPV_JOIN_FULL = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.US);


    private static final DateTimeFormatter SBPV_JOIN = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US);

    /** SkyBlockPV's label: its title bar (w - 10 wide, 20 high, the title centered over w) over a box {@code inner} + 0
     *  high; returns the y of the box's first content line (7 in). */
    private static final String[] SBPV_CLASSES = {"healer", "mage", "berserk", "archer", "tank"},
            SBPV_BOSSES = {"Bonzo", "Scarf", "Prof.", "Thorn", "Livid", "Sadan", "Necron"};
    private SbProfile.DungeonStats sbpvDunFor;
    private String[] sbpvDunInfo, sbpvDunLevels;
    private float[] sbpvDunDone;
    private String[][] sbpvDunRuns;
    private int[] sbpvDunCols;

    /** SkyBlockPV's DungeonScreen: Dungeon Info, Dungeon Levels (Catacombs and the five classes, each with its bar, the
     *  selected class dark green) and Dungeon Runs (boss, Cata, Master completions, then Total), side by side 5 apart and
     *  centered, each label its content plus 20 a side; stacked when they do not fit. The text is built once a profile. */
    private void drawSbpvDungeons(GuiGraphicsExtractor g, SbProfile.DungeonStats d) {
        if (sbpvDunFor != d) {
            sbpvDunFor = d;
            sbpvDunLevels = new String[6];
            sbpvDunDone = new float[6];
            LevelTables.Level cata = LevelTables.catacombsLevel(d.catacombsXp(), true);
            sbpvDunLevels[0] = "Catacombs: " + cata.level();
            sbpvDunDone[0] = cata.level() >= 50 ? 1 : (float) cata.progress();
            double sum = 0;
            for (int i = 0; i < 5; i++) {
                LevelTables.Level l = LevelTables.catacombsLevel(d.classXp().getOrDefault(SBPV_CLASSES[i], 0L), true);
                sum += Math.min(50, l.level());
                sbpvDunLevels[i + 1] = SbProfile.titleCase(SBPV_CLASSES[i]) + ": " + l.level();
                sbpvDunDone[i + 1] = l.maxed() ? 1 : (float) l.progress();
            }
            sbpvDunInfo = new String[] {"Class Average: " + String.format(Locale.ROOT, "%.2f", sum / 5), "Secrets: " + commas(d.secrets()),
                    "Secrets/Run: " + String.format(Locale.ROOT, "%.2f", d.totalRuns() == 0 ? 0 : (double) d.secrets() / d.totalRuns())};
            sbpvDunRuns = new String[9][];
            sbpvDunRuns[0] = new String[] {"", "Cata", "Master"};
            long cTotal = 0, mTotal = 0;
            for (int f = 1; f <= 7; f++) {
                SbProfile.Floor n = d.normal().get(f), m = d.master().get(f);
                long c = n == null ? 0 : n.completions(), mc = m == null ? 0 : m.completions();
                cTotal += c;
                mTotal += mc;
                sbpvDunRuns[f] = new String[] {SBPV_BOSSES[f - 1], commas(c), commas(mc)};
            }
            sbpvDunRuns[8] = new String[] {"Total", commas(cTotal), commas(mTotal)};
            sbpvDunCols = new int[3];
            for (String[] row : sbpvDunRuns) {
                for (int c = 0; c < 3; c++) {
                    sbpvDunCols[c] = Math.max(sbpvDunCols[c], this.font.width(row[c]));
                }
            }
        }
        int text = PvStyleChrome.sbpvText(pvStyle), infoW = 0, levW = 91, runW = sbpvDunCols[0] + sbpvDunCols[1] + sbpvDunCols[2] + 20;
        for (String line : sbpvDunInfo) {
            infoW = Math.max(infoW, this.font.width(line));
        }
        for (String line : sbpvDunLevels) {
            levW = Math.max(levW, this.font.width(line));
        }
        boolean row = infoW + levW + runW + 120 + 10 <= panelW;
        int x = row ? panelX + (panelW - infoW - levW - runW - 130) / 2 : 0, y = contentY;
        int lx = row ? x : panelX + (panelW - infoW - 40) / 2;
        int cy = sbpvLabel(g, "Dungeon Info", lx, y, infoW + 50, 7 + 3 * 11 - 2 + 7, text);
        for (String line : sbpvDunInfo) {
            cy = sbpvLine(g, line, lx + 20, cy, infoW, text);
        }
        if (row) {
            x += infoW + 45;
        } else {
            y = cy + 10;
        }
        lx = row ? x : panelX + (panelW - levW - 40) / 2;
        cy = sbpvLabel(g, "Dungeon Levels", lx, y, levW + 50, 7 + 6 * 15 + 5 * 5 + 7, text);
        for (int i = 0; i < 6; i++) {
            boolean sel = i > 0 && SBPV_CLASSES[i - 1].equals(d.selectedClass());
            g.text(this.font, sbpvDunLevels[i], lx + 20, cy, i == 0 ? text : sel ? 0xFF00AA00 : 0xFF555555, false);
            PvStyleChrome.bar(g, lx + 20, cy + 10, sbpvDunDone[i]);
            cy += 20;
        }
        if (row) {
            x += levW + 45;
        } else {
            y = cy + 7;
        }
        lx = row ? x : panelX + (panelW - runW - 40) / 2;
        cy = sbpvLabel(g, "Dungeon Runs", lx, y, runW + 50, 7 + 9 * 11 - 2 + 7, text);
        for (String[] r : sbpvDunRuns) {
            for (int c = 0, cx = lx + 20; c < 3; cx += sbpvDunCols[c++] + 10) {
                g.text(this.font, r[c], cx, cy, 0xFF555555, false);
            }
            cy += 11;
        }
    }

    private int sbpvLabel(GuiGraphicsExtractor g, String title, int x, int y, int w, int inner, int text) {
        PvStyleChrome.title(g, pvStyle, x, y, w - 10, 20);
        g.text(this.font, title, x + (w - this.font.width(title)) / 2, y + 6, text, false);
        box(g, x, y + 20, w - 10, inner);
        return y + 27;
    }

    /** A SkyBlockPV createSection: chips (rounded_box_thin, 2 px in: a 12 px icon, 1 px, the text) 5 apart, as many to
     *  a row as fit (w - 20) / (widest + 10), each row centered, each chip's hover its tooltip; returns the label's bottom. */
    private int sbpvChips(GuiGraphicsExtractor g, SbpvChips c, int x, int y, int w, int mx, int my, int text) {
        int n = c.values().length, chipW = 0;
        for (String v : c.values()) {
            chipW = Math.max(chipW, 17 + this.font.width(v));
        }
        int perRow = Math.max(1, (w - 20) / (chipW + 10)), rows = (n + perRow - 1) / perRow;
        int cy = sbpvLabel(g, c.title(), x, y, w, 14 + rows * 16 + (rows - 1) * 5, text);
        for (int r = 0; r < rows; r++) {
            int k0 = r * perRow, k1 = Math.min(n, k0 + perRow), rowW = -5;
            for (int i = k0; i < k1; i++) {
                rowW += 22 + this.font.width(c.values()[i]);
            }
            int cx = x + 10 + (w - 20 - rowW) / 2;
            for (int i = k0; i < k1; i++) {
                String lv = c.values()[i];
                int cw = 17 + this.font.width(lv);
                PvStyleChrome.chip(g, pvStyle, cx, cy, cw, 16);
                g.pose().pushMatrix();
                g.pose().translate(cx + 2, cy + 2);
                g.pose().scale(0.75f, 0.75f);
                g.item(c.icons()[i], 0, 0);
                g.pose().popMatrix();
                g.text(this.font, lv, cx + 15, cy + 4, text, false);
                if (c.tips()[i] != null && !dropdownOpen && inside(mx, my, cx, cy, cw, 16)) {
                    pendingTooltip = c.tips()[i];
                }
                cx += cw + 5;
            }
            cy += 21;
        }
        return cy - 5 + 7;
    }

    private void drawBasic(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        if (sbpvStyle()) {
            drawSbpvBasic(g, p, mx, my);
            return;
        }
        if (neuPage()) {
            drawNeuBasic(g, p, mx, my);
            return;
        }
        minRowScale = 1f;
        int leftW = 118;
        box(g, contentX, contentY, leftW, contentH);
        int skinBottom = contentY + 4 + (skinProfile != null && ProfileViewerConfig.getInstance().isShowSkin()
                ? Math.min(120, contentH - 90) + 4 : 0);
        int y = Math.max(skinBottom, contentY + 6);
        int lx = contentX + 6;
        int sw = 106;
        y = stat2(g, lx, y, sw, "SB Level", p.sbLevel + (p.sbLevelProgress > 0 ? "." + String.format(Locale.ROOT, "%02d", p.sbLevelProgress) : ""));
        y = stat2(g, lx, y, sw, "Fairy Souls", String.valueOf(p.fairySouls));
        y = stat2(g, lx, y, sw, "Purse", shorten(p.purse));
        if (p.bank >= 0) {
            int by = y;
            y = stat2(g, lx, y, sw, "Bank", shorten(p.bank) + (p.personalBank > 0 ? " / " + shorten(p.personalBank) : ""));
            if (inside(mx, my, lx, by, sw, 10)) {
                pendingTooltip = List.of(
                        Component.literal("Co-op bank: " + commas(p.bank)).withStyle(style(VALUE)),
                        Component.literal("Personal bank: " + commas(p.personalBank)).withStyle(style(VALUE)));
            }
        } else {
            y = stat2(g, lx, y, sw, "Bank", "API off");
        }
        // killer560, 2026-10-08: "a line below the bank that says the networth". The same estimate as the
        // Networth page (shared cache), and a click opens that page.
        ExtraPages.NetworthState nw = extraPages.networthState(p);
        int ny = y;
        Networth.View nonCos = com.killer560.hub.profileviewer.ProfileViewerConfig.getInstance().getNetworthView();
        lastBasicNetworth = nw.result() == null ? null : nw.result().total(nonCos);
        y = stat2(g, lx, y, sw, "Networth", nw.result() != null ? shorten(nw.result().total(nonCos)) : nw.failed() ? "-" : "...");
        if (inside(mx, my, lx, ny, sw, 10) && !dropdownOpen) {
            pendingTooltip = List.of(
                    Component.literal(nw.result() != null ? commas(nw.result().total(nonCos)) + " coins (estimate)"
                            : nw.failed() ? "Couldn't calculate networth." : "Calculating...").withStyle(style(VALUE)),
                    Component.literal("Click for the Networth page").withStyle(style(DIM)));
        }
        hotspots.add(new Hotspot(lx, ny, sw, 10, () -> setPage(PAGE_NETWORTH)));
        if (p.motes > 0) {
            y = stat2(g, lx, y, sw, "Motes", shorten(p.motes));
        }
        if (p.firstJoin > 0) {
            y = stat2(g, lx, y, sw, "Joined", JOINED_FORMAT
                    .format(Instant.ofEpochMilli(p.firstJoin).atZone(ZoneId.systemDefault())));
        }
        if (p.memberCount > 1) {
            y = stat2(g, lx, y, sw, "Co-op", p.memberCount + " members");
        }
        // directly under the last stat row (Joined), the panel's width
        statusButtonAt(g, lx, Math.min(y + 3, contentY + contentH - 18), sw, mx, my);

        int rx = contentX + leftW + 6;
        int rw = contentW - leftW - 6;
        int rh = contentH;
        box(g, rx, contentY, rw, contentH);

        // Skills: 6 rows, then Slayers: 2 rows; rows close up (21 -> 17) to fit the 202 px panel.
        int rowH = Math.max(17, Math.min(21, (rh - 31) / 8));
        int sy = contentY + 5;
        if (p.skillXp == null) {
            tx(g, "Skills", rx + 6, sy, ACCENT);
            tx(g, "Skills API is disabled.", rx + 6, sy + 14, DIM);
            sy += 30;
        } else {
            double sum = 0;
            int count = 0;
            List<String> skills = LevelTables.SKILL_ORDER;
            int colW = (rw - 18) / 2;
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
                // 2026-10-08: the parenthesis is the level with NO cap at all, not the table's 60 - "I have more
                // farming overflow than just 60".
                if (lvl.maxed()) {
                    int uncapped = LevelTables.uncappedSkillLevel(id, xp);
                    if (uncapped > lvl.level()) {
                        overflow = " (" + uncapped + ")";
                    }
                }
                bar(g, cx, cy, colW, skillIcon(id), name, lvl, mx, my, xp, overflow);
                i++;
            }
            String avg = count == 0 ? "" : String.format(Locale.ROOT, "Skill Avg %.2f", sum / count);
            tx(g, "Skills", rx + 6, sy, ACCENT);
            tx(g, avg, rx + rw - 6 - this.font.width(avg), sy, VALUE);
            sy += 12 + ((skills.size() + 1) / 2) * rowH + 2;
        }

        tx(g, "Slayers", rx + 6, sy, ACCENT);
        List<LevelTables.Slayer> slayers = LevelTables.slayers();
        int colW = (rw - 24) / 3;
        long totalXp = 0;
        for (int i = 0; i < slayers.size(); i++) {
            LevelTables.Slayer s = slayers.get(i);
            SbProfile.SlayerStat stat = p.slayers.get(s.apiId());
            long xp = stat == null ? 0 : stat.xp();
            totalXp += xp;
            int cx = rx + 6 + (i % 3) * (colW + 6);
            int cy = sy + 12 + (i / 3) * rowH;
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
        tx(g, total, rx + rw - 6 - this.font.width(total), sy, VALUE);
    }

    /** NEU Classic's Basic Info (BasicPage): the name on a dark strip over the model, the two weights and the net
     *  worth above it, the SkyBlock level block, Social, and the 2 x 8 skill and slayer grid, all at its own panel
     *  positions. Everything drawn is built once per profile and net worth value ({@link NeuBasic}). */
    private void drawNeuBasic(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ExtraPages.NetworthState nw = extraPages.networthState(p);
        long worth = nw.result() != null ? nw.result().total(ProfileViewerConfig.getInstance().getNetworthView()) : nw.failed() ? -2 : -1;
        lastBasicNetworth = worth >= 0 ? worth : null;
        NeuBasic nb = neuBasic;
        if (nb == null || nb.profile != p || nb.worth != worth || !java.util.Objects.equals(nb.name, displayName)) {
            nb = neuBasic = new NeuBasic(p, worth, displayName, uuid);
        }
        var f = this.font;
        int gx = panelX, gy = panelY;
        boolean hov = !dropdownOpen;
        if (!nb.name.isEmpty()) {
            int half = f.width(nb.name) / 2;
            g.fill(gx + 63 - half - 1, gy + 53, gx + 63 + half + 1, gy + 62, 0x40000000);
            g.text(f, nb.name, gx + 63 - half, gy + 54, 0xFFFFFFFF, true);
        }
        for (int i = 0; i < 2; i++) {
            String w = nb.weights[i];
            int wx = gx + 63 - f.width(w) / 2, wy = gy + 14 + i * 10;
            g.text(f, w, wx, wy, 0xFFFFFFFF, true);
            if (hov && inside(mx, my, wx, wy - 2, f.width(w), 9)) pendingTooltip = nb.weightTips[i];
        }
        int nwW = f.width(nb.worthLine);
        int nwX = nwW >= 117 ? gx + 8 : gx + 68 - nwW / 2;
        g.text(f, nb.worthLine, nwX, gy + 34, 0xFFFFFFFF, true);
        if (hov && inside(mx, my, nwX, gy + 32, nwW, 12)) {
            pendingTooltip = nb.worthTip;
        }
        hotspots.add(new Hotspot(nwX, gy + 32, nwW, 12, () -> setPage(PAGE_NETWORTH)));
        statusButtonAt(g, gx + PvStyleChrome.NEU_BASIC_MODEL[0], gy + PvStyleChrome.NEU_BASIC_MODEL[1]
                + PvStyleChrome.NEU_BASIC_MODEL[3] + 3, PvStyleChrome.NEU_BASIC_MODEL[2], mx, my);

        // SkyBlock level: the skull at 1.5x, its number above it at 1.5x, "NN/100" at 0.9x and the bar
        g.pose().pushMatrix();
        g.pose().translate(gx + 162, gy + 74);
        g.pose().scale(1.5f, 1.5f);
        g.item(headFor(uuid), 0, 0);
        g.pose().popMatrix();
        g.pose().pushMatrix();
        g.pose().translate(gx + 171 - f.width(nb.sbLevel) * 0.75f, gy + 62 - 6f);
        g.pose().scale(1.5f, 1.5f);
        g.text(f, nb.sbLevel, 0, 0, PvStyleChrome.sbLevelColor(p.sbLevel), true);
        g.pose().popMatrix();
        g.pose().pushMatrix();
        g.pose().translate(gx + 132, gy + 94);
        g.pose().scale(0.9f, 0.9f);
        g.text(f, nb.sbProgress, 0, 0, 0xFFFFFF55, true);
        g.pose().popMatrix();
        PvStyleChrome.neuBar(g, gx + 132, gy + 104, 80, p.sbLevelProgress / 100f, false);

        if (nb.cells == null) {
            g.text(f, "Skills API not enabled!", gx + 322 - f.width("Skills API not enabled!") / 2, gy + 97, 0xFFFF5555, true);
        }
        for (NeuCell c : nb.cells == null ? nb.slayersOnly : nb.cells) {
            int x = gx + c.x, y = gy + c.y;
            PvStyleChrome.neuAligned(g, c.name, c.color, c.level, 0xFFFFFFFF, x + 14, y - 4, 60);
            PvStyleChrome.neuBar(g, x, y + 6, 80, c.progress, c.maxed);
            g.pose().pushMatrix();
            g.pose().translate(x, y - 6);
            g.pose().scale(0.7f, 0.7f);
            g.item(c.icon, 0, 0);
            g.pose().popMatrix();
            if (hov && mx > x && mx < x + 80 && my > y - 4 && my < y + 13) {
                pendingTooltip = c.tip;
            }
        }
    }

    private NeuBasic neuBasic;

    /** One NEU skill or slayer cell: its panel position, the text and bar it draws and its hover. */
    private record NeuCell(int x, int y, ItemStack icon, String name, int color, String level, float progress, boolean maxed,
                           List<Component> tip) {
    }

    /** Everything NEU's Basic page draws, built off the render path once per profile, net worth and name. */
    private static final class NeuBasic {
        final SbProfile profile;
        final long worth;
        final String name, worthLine, sbLevel, sbProgress;
        final String[] weights = new String[2];
        @SuppressWarnings("unchecked")
        final List<Component>[] weightTips = (List<Component>[]) new List<?>[2];
        final List<Component> worthTip;
        final List<NeuCell> cells, slayersOnly;

        /** NEU's skill grid, column by column (BasicPage via ProfileViewer.skillToSkillDisplayMap); Social goes
         *  under the SkyBlock level instead of taking a grid cell. */
        private static final String[] SKILLS = {"TAMING", "MINING", "FORAGING", "ENCHANTING", "CARPENTRY", "FARMING", "COMBAT",
                "FISHING", "ALCHEMY", "RUNECRAFTING"};
        private static final String[] SKILL_NAMES = {"Taming", "Mining", "Foraging", "Enchanting", "Carpentry", "Farming",
                "Combat", "Fishing", "Alchemy", "Runecrafting"};
        private static final int[] SKILL_COLORS = {0xFFFF55FF, 0xFFAAAAAA, 0xFF00AA00, 0xFF55FF55, 0xFFAA0000, 0xFFFFFF55,
                0xFFFF5555, 0xFF55FFFF, 0xFF5555FF, 0xFFAA00AA};
        private static final String[] SLAYERS = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
        private static final String[] SLAYER_NAMES = {"Rev Slayer", "Tara Slayer", "Sven Slayer", "Ender Slayer",
                "Blaze Slayer", "Vampire Slayer"};

        NeuBasic(SbProfile p, long worth, String name, UUID uuid) {
            this.profile = p;
            this.worth = worth;
            this.name = name == null ? "" : name;
            SkyblockWeight.Both both = SkyblockWeight.compute(p);
            SkyblockWeight.Result[] rs = {both.senither(), both.lily()};
            String[] labels = {"Senither Weight: ", "Lily Weight: "};
            for (int i = 0; i < 2; i++) {
                SkyblockWeight.Result r = rs[i];
                weights[i] = "§a" + labels[i] + "§6" + commas(Math.round(r.total()));
                weightTips[i] = List.of(lit("§aSkills: §6" + commas(Math.round(r.skills().total()))),
                        lit("§aSlayer: §6" + commas(Math.round(r.slayers().total()))),
                        lit("§aDungeons: §6" + commas(Math.round(r.dungeons().total()))));
            }
            worthLine = "§aNet Worth: " + (worth >= 0 ? "§6" + commas(worth) : worth == -1 ? "§eLoading..." : "§cAn error occurred");
            worthTip = List.of(lit(worth >= 0 ? "§6" + commas(worth) + " coins (estimate)" : worthLine),
                    lit("§7Click for the Networth page"));
            sbLevel = String.valueOf(p.sbLevel);
            sbProgress = p.sbLevelProgress + "/100";

            List<NeuCell> slayerCells = new ArrayList<>();
            List<LevelTables.Slayer> all = LevelTables.slayers();
            for (int i = 0; i < SLAYERS.length; i++) {
                LevelTables.Slayer def = null;
                for (LevelTables.Slayer s : all) if (s.apiId().equals(SLAYERS[i])) def = s;
                if (def == null) continue;
                SbProfile.SlayerStat stat = p.slayers.get(def.apiId());
                long xp = stat == null ? 0 : stat.xp();
                LevelTables.Level lvl = LevelTables.slayerLevel(def, xp);
                long maxXp = def.leveling().length == 0 ? 0 : def.leveling()[def.leveling().length - 1];
                List<Component> tip = tip(SLAYER_NAMES[i], 0xFFFFAA00, lvl, xp, maxXp, def.leveling().length);
                int tiers = def.bossXp() == null ? 5 : Math.min(5, def.bossXp().length);
                for (int t = 0; t < tiers; t++) {
                    Integer k = stat == null ? null : stat.kills().get(t);
                    tip.add(lit("§7T" + (t + 1) + " Kills: §c" + (k == null ? 0 : k)));
                }
                slayerCells.add(new NeuCell(237 + 86, 24 + 21 * (i + 2), new ItemStack(SLAYER_ICONS[i]), SLAYER_NAMES[i], 0xFFFFAA00,
                        String.valueOf(lvl.level()), (float) lvl.progress(), lvl.maxed(), List.copyOf(tip)));
            }
            slayersOnly = List.copyOf(slayerCells);
            if (p.skillXp == null) {
                cells = null;
                return;
            }
            List<NeuCell> out = new ArrayList<>();
            for (int i = 0; i < SKILLS.length; i++) {
                out.add(skillCell(p, SKILLS[i], SKILL_NAMES[i], SKILL_COLORS[i], 237 + 86 * (i / 8), 24 + 21 * (i % 8), SKILL_ICONS[i]));
            }
            out.add(skillCell(p, "SOCIAL", "Social", 0xFF00AA00, 132, 124, Items.EMERALD));
            out.addAll(slayerCells);
            cells = List.copyOf(out);
        }

        private static final net.minecraft.world.item.Item[] SKILL_ICONS = {Items.GHAST_SPAWN_EGG, Items.STONE_PICKAXE,
                Items.OAK_SAPLING, Items.ENCHANTING_TABLE, Items.CRAFTING_TABLE, Items.GOLDEN_HOE, Items.STONE_SWORD,
                Items.FISHING_ROD, Items.BREWING_STAND, Items.MAGMA_CREAM};
        private static final net.minecraft.world.item.Item[] SLAYER_ICONS = {Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.BONE,
                Items.ENDER_PEARL, Items.BLAZE_ROD, Items.REDSTONE};

        private static NeuCell skillCell(SbProfile p, String id, String name, int color, int x, int y, net.minecraft.world.item.Item icon) {
            long xp = p.skillXp.getOrDefault(id, 0L);
            int cap = p.skillCaps.getOrDefault(id, -1);
            LevelTables.Level lvl = LevelTables.skillLevel(id, xp, cap);
            LevelTables.Skill def = LevelTables.skill(id);
            int maxLevel = def == null ? 0 : Math.min(def.totalXp().length, cap > 0 ? cap : def.maxLevel());
            long maxXp = maxLevel == 0 ? 0 : def.totalXp()[maxLevel - 1];
            return new NeuCell(x, y, new ItemStack(icon), name, color, String.valueOf(lvl.level()), (float) lvl.progress(),
                    lvl.maxed(), List.copyOf(tip(name, color, lvl, xp, maxXp, maxLevel)));
        }

        /** NEU's skill hover: the name, Progress (cur/max to the next level, or MAXED!) and Total XP to the max level. */
        private static List<Component> tip(String name, int color, LevelTables.Level lvl, long xp, long maxXp, int maxLevel) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name).withStyle(style(color)));
            if (lvl.maxed()) {
                tip.add(lit("§7Progress: §6MAXED!"));
            } else {
                tip.add(lit("§7Progress: §5" + PvStyleChrome.neuShort(lvl.xpIntoLevel()) + "/" + PvStyleChrome.neuShort(lvl.xpForLevel())
                        + "§8 (" + PvStyleChrome.neuTenths(lvl.progress() * 100) + "% to " + (lvl.level() + 1) + ")"));
            }
            tip.add(lit("§7Total XP: §5" + commas(xp) + "§8 (" + PvStyleChrome.neuTenths(maxXp <= 0 ? 100 : Math.min(100, xp * 100.0 / maxXp))
                    + "% to " + maxLevel + ")"));
            return tip;
        }

        private static Component lit(String s) { return Component.literal(s); }
    }

    /** Icon + "Name Level" + progress bar, the reference style. Hover shows XP numbers. */
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
        fitRow(g, tx, y, bw, name, TEXT, levelText, lvl.maxed() ? MAXED : VALUE, 0, 0);
        int barY = y + 11;
        ScreenChrome.progress(g, tx, barY, bw, 4, lvl.maxed() ? 1 : lvl.progress(), lvl.maxed() ? MAXED : ACCENT);
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


    private void drawDungeons(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        SbProfile.DungeonStats d = p.dungeons;
        if (neuPage()) {
            drawNeuDungeons(g, d, mx, my);
            return;
        }
        if (sbpvStyle()) {
            drawSbpvDungeons(g, d);
            return;
        }
        int leftW = 170;
        box(g, contentX, contentY, leftW, contentH);
        int x = contentX + 6;
        int y = contentY + 5;
        // Class rows shrink to fit a short panel (NEU Classic's 202 px): 20 apart at full size, never under 16.
        int classH = Math.max(16, Math.min(20, (contentH - 68) / (d.classXp().size() + 1)));
        tx(g, "Catacombs", x, y, ACCENT);
        y += 12;
        LevelTables.Level cata = LevelTables.catacombsLevel(d.catacombsXp(), true);
        bar(g, x, y, leftW - 12, new ItemStack(Items.WITHER_SKELETON_SKULL), "Catacombs", cata, mx, my, d.catacombsXp());
        y += classH + 4;

        String selected = d.selectedClass().isEmpty() ? "None" : SbProfile.titleCase(d.selectedClass());
        tx(g, "Classes", x, y, ACCENT);
        tx(g, selected, x + leftW - 12 - this.font.width(selected), y, VALUE);
        y += 12;
        double classSum = 0;
        for (Map.Entry<String, Long> e : d.classXp().entrySet()) {
            LevelTables.Level lvl = LevelTables.catacombsLevel(e.getValue(), true);
            classSum += Math.min(50, lvl.level());
            String name = SbProfile.titleCase(e.getKey());
            if (e.getKey().equals(d.selectedClass())) {
                selBand(g, x - 2, y - 1, leftW - 8, classH - 2);
            }
            bar(g, x, y, leftW - 12, classIcon(e.getKey()), name, lvl, mx, my, e.getValue());
            y += classH;
        }
        y += 2;
        y = stat2(g, x, y, leftW - 12, "Class Average", String.format(Locale.ROOT, "%.2f", d.classXp().isEmpty() ? 0 : classSum / d.classXp().size()));
        y = stat2(g, x, y, leftW - 12, "Secrets", commas(d.secrets()));
        y = stat2(g, x, y, leftW - 12, "Total Runs", commas(d.totalRuns()));
        if (d.totalRuns() > 0 && y + 9 <= contentY + contentH) {
            stat2(g, x, y, leftW - 12, "Secrets / Run", String.format(Locale.ROOT, "%.2f", (double) d.secrets() / d.totalRuns()));
        }

        int rx = contentX + leftW + 6;
        int rw = contentW - leftW - 6;
        box(g, rx, contentY, rw, contentH);
        int[] cols = {rx + 6, rx + 6 + (int) (rw * 0.26), rx + 6 + (int) (rw * 0.44), rx + 6 + (int) (rw * 0.60), rx + 6 + (int) (rw * 0.80)};
        int ty = contentY + 5;
        String[] headers = {"Floor", "Runs", "Score", "S+", "S"};
        for (int i = 0; i < headers.length; i++) {
            tx(g, headers[i], cols[i], ty, ACCENT);
        }
        ty += 12;
        int rowH = Math.max(10, Math.min(12, (contentH - 38) / 17));
        for (int floor = 0; floor <= 7; floor++) {
            ty = floorRow(g, cols, ty, rowH, floor == 0 ? "Entrance" : "Floor " + floor, d.normal().get(floor), floor % 2 == 0);
        }
        ty += 4;
        g.fill(cols[0] - 3, ty, contentX + contentW - 6, ty + 1, ACCENT);
        ty += 3;
        tx(g, "Master Mode", cols[0], ty, ACCENT);
        ty += rowH;
        for (int floor = 1; floor <= 7; floor++) {
            ty = floorRow(g, cols, ty, rowH, "Master " + floor, d.master().get(floor), floor % 2 == 1);
        }
    }

    /** NEU Classic's Dungeons page: our numbers in pv_dung's five boxes (level, stats, selected class, floors, classes). */
    private void drawNeuDungeons(GuiGraphicsExtractor g, SbProfile.DungeonStats d, int mx, int my) {
        int[] lv = PvStyleChrome.NEU_DUNG_LEVEL, st = PvStyleChrome.NEU_DUNG_STATS, cl = PvStyleChrome.NEU_DUNG_CLASS,
                fl = PvStyleChrome.NEU_DUNG_FLOORS, cs = PvStyleChrome.NEU_DUNG_CLASSES;
        int x = panelX + lv[0] + 5, w = lv[2] - 10, y = panelY + lv[1] + 5;
        tx(g, "Catacombs", x, y, ACCENT);
        LevelTables.Level cata = LevelTables.catacombsLevel(d.catacombsXp(), true);
        bar(g, x, y + 12, w, new ItemStack(Items.WITHER_SKELETON_SKULL), "Catacombs", cata, mx, my, d.catacombsXp());
        double classSum = 0;
        int cx = panelX + cs[0] + 5, cy = panelY + cs[1] + 5;
        tx(g, "Classes", cx, cy, ACCENT);
        cy += 12;
        for (Map.Entry<String, Long> e : d.classXp().entrySet()) {
            LevelTables.Level lvl = LevelTables.catacombsLevel(e.getValue(), true);
            classSum += Math.min(50, lvl.level());
            String name = SbProfile.titleCase(e.getKey());
            if (e.getKey().equals(d.selectedClass())) {
                selBand(g, cx - 2, cy - 1, w + 4, 18);
                int sy = panelY + cl[1] + 5;
                tx(g, "Selected Class", x, sy, ACCENT);
                bar(g, x, sy + 12, w, classIcon(e.getKey()), name, lvl, mx, my, e.getValue());
            }
            bar(g, cx, cy, w, classIcon(e.getKey()), name, lvl, mx, my, e.getValue());
            cy += 22;
        }
        if (d.selectedClass().isEmpty()) {
            stat2(g, x, panelY + cl[1] + 5, w, "Selected Class", "None");
        }
        stat2(g, x, y + 32, w, "Class Average", String.format(Locale.ROOT, "%.2f", d.classXp().isEmpty() ? 0 : classSum / d.classXp().size()));
        int sy = panelY + st[1] + 5;
        sy = stat2(g, x, sy, w, "Secrets", commas(d.secrets()));
        sy = stat2(g, x, sy, w, "Total Runs", commas(d.totalRuns()));
        if (d.totalRuns() > 0) {
            stat2(g, x, sy, w, "Secrets / Run", String.format(Locale.ROOT, "%.2f", (double) d.secrets() / d.totalRuns()));
        }
        int fx = panelX + fl[0];
        int[] cols = {fx + 5, fx + 20, fx + 44, fx + 70, fx + 97};
        int ty = panelY + fl[1] + 4;
        tx(g, "Runs", cols[1], ty, ACCENT);
        tx(g, "Best", cols[2], ty, ACCENT);
        tx(g, "S+", cols[3], ty, ACCENT);
        tx(g, "S", cols[4], ty, ACCENT);
        ty += 11;
        for (int floor = 0; floor <= 7; floor++) {
            ty = floorRow(g, cols, fx + fl[2] - 2, ty, 10, floor == 0 ? "E" : "F" + floor, d.normal().get(floor), floor % 2 == 0);
        }
        ty += 1;
        g.fill(cols[0] - 3, ty, fx + fl[2] - 2, ty + 1, ACCENT);
        ty += 2;
        for (int floor = 1; floor <= 7; floor++) {
            ty = floorRow(g, cols, fx + fl[2] - 2, ty, 10, "M" + floor, d.master().get(floor), floor % 2 == 1);
        }
    }

    private int floorRow(GuiGraphicsExtractor g, int[] cols, int y, int rowH, String label, SbProfile.Floor f, boolean shade) {
        return floorRow(g, cols, contentX + contentW - 6, y, rowH, label, f, shade);
    }

    private int floorRow(GuiGraphicsExtractor g, int[] cols, int right, int y, int rowH, String label, SbProfile.Floor f, boolean shade) {
        if (shade) {
            g.fill(cols[0] - 3, y - 1, right, y + rowH - 1, STRIPE);
        }
        tx(g, label, cols[0], y, f == null ? DIM : TEXT);
        tx(g, f == null ? "-" : commas(f.completions()), cols[1], y, f == null ? DIM : VALUE);
        tx(g, f == null || f.bestScore() <= 0 ? "-" : String.valueOf(f.bestScore()), cols[2], y, f == null ? DIM : TEXT);
        tx(g, f == null ? "-" : time(f.fastestSPlus()), cols[3], y, f == null || f.fastestSPlus() <= 0 ? DIM : MAXED);
        tx(g, f == null ? "-" : time(f.fastestS()), cols[4], y, f == null || f.fastestS() <= 0 ? DIM : VALUE);
        return y + rowH;
    }

    /** The ONE row helper of the text-block pages (Crimson Isle, Misc, Rift, Farming, Dungeons...): a section header
     *  ({@link #heading}) puts a thin theme-colored divider above its first row, and the rows under it alternate a faint
     *  shade. Theme colors only. */
    private boolean dividerPending;
    private int rowAlt;

    void heading(GuiGraphicsExtractor g, String text, int x, int y) {
        tx(g, text, x, y, ACCENT);
        dividerPending = true;
        rowAlt = 0;
    }

    int stat2(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
        if (dividerPending) {
            dividerPending = false;
            g.fill(x, y - 2, x + w, y - 1, (ACCENT & 0xFFFFFF) | 0x66000000);
        }
        if ((rowAlt++ & 1) == 1) {
            g.fill(x - 2, y - 1, x + w + 2, y + 10, STRIPE);
        }
        fitRow(g, x, y, w, label, DIM, value, VALUE, 0, 0);
        return y + 11;
    }

    /** The smallest scale any fitRow of the current frame used (the testkit reads it; reset by drawBasic). */
    float minRowScale = 1f;

    /** Smallest gap between a label and its value, in pixels. */
    static final int ROW_GAP = 6;

    /** A label at the left and a value at the right of a w-wide row with at least {@link #ROW_GAP} between them, from
     *  measured widths: when both do not fit the whole row is scaled down uniformly (never under 0.7), and only a label
     *  that still does not fit is cut (with ".."), never overlapped. {@code leftPad} / {@code gapExtra} leave room for an
     *  icon the caller drew (leftPad) or a wider gap (gapExtra). Returns the scale used (the testkit reads it). */
    float fitRow(GuiGraphicsExtractor g, int x, int y, int w, String label, int labelColor, String value, int valueColor,
                 int leftPad, int gapExtra) {
        int need = leftPad + this.font.width(label) + ROW_GAP + gapExtra + this.font.width(value);
        float s = need <= w ? 1f : Math.max(0.7f, (float) w / need);
        float lw = w / s; // the row's width in scaled units
        String shown = label;
        int room = (int) lw - leftPad - ROW_GAP - gapExtra - this.font.width(value);
        if (this.font.width(shown) > room) {
            shown = this.font.plainSubstrByWidth(label, Math.max(0, room - this.font.width("..")), false).stripTrailing() + "..";
        }
        if (s == 1f) {
            tx(g, shown, x + leftPad, y, labelColor);
            tx(g, value, x + w - this.font.width(value), y, valueColor);
        } else {
            g.pose().pushMatrix();
            g.pose().translate(x, y + (1 - s) * 4);
            g.pose().scale(s, s);
            tx(g, shown, leftPad, 0, labelColor);
            tx(g, value, (int) lw - this.font.width(value), 0, valueColor);
            g.pose().popMatrix();
        }
        minRowScale = Math.min(minRowScale, s);
        return s;
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
        if (ms <= 0) return "-";
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }


    private void drawInventories(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int navW = 84, n = INV_VIEWS.length;
        boolean neu = neuPage(), sbpv = pvStyle != ProfileViewerConfig.Style.DEFAULT && !neu;
        if (!sbpv) {
            box(g, contentX, contentY, navW, contentH);
        }
        CompletableFuture<SbProfile.Inventories> future = p.inventories();
        SbProfile.Inventories inv = future.isDone() && !future.isCompletedExceptionally() ? future.getNow(null) : null;
        // The sack names join the search index only once someone searches or opens Sacks (the extras parse starts then).
        Map<String, Long> sackCounts = null;
        if (invView == PvSearch.SACKS || invFind.active()) {
            CompletableFuture<ProfileExtras> ef = p.extras();
            ProfileExtras ex = ef.isDone() && !ef.isCompletedExceptionally() ? ef.getNow(null) : null;
            sackCounts = ex == null ? null : ex.misc.sacks();
        }
        invFind.index(inv, sackCounts);
        int step = Math.min(19, Math.max(13, (contentH - 28) / n));
        int ny = contentY + 4;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Runnable pick = () -> {
                invView = idx;
                invPage = 0;
                invScroll = 0;
                pageScroll = 0;
            };
            int hits = invFind.active() ? invFind.count(i) : 0;
            String hitText = String.valueOf(hits);
            List<Component> tip = List.of(Component.literal(INV_VIEWS[i] + (invFind.active() ? "  (" + hits + (hits == 1 ? " match)" : " matches)") : "")));
            if (neu) { // The selector: pv_elements cells, 3 a row, the item numbered (InventoriesPage)
                int ix = panelX + 19 + 34 * (i % 3), iy = panelY + 26 + 34 * (i / 3), o = i == invView ? 1 : 0;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, o == 1 ? PvStyleChrome.NEU_INV_TAB_ON : PvStyleChrome.NEU_INV_TAB, ix - 2, iy - 2, 20, 20);
                g.item(INV_ICONS[i], ix + o, iy + o);
                g.itemDecorations(this.font, INV_ICONS[i], ix + o, iy + o, hits > 0 ? hitText : INV_NUMBERS[i]);
                if (hits > 0) {
                    g.outline(ix - 2, iy - 2, 20, 20, ACCENT);
                }
                hotspots.add(new Hotspot(ix - 2, iy - 2, 20, 20, pick));
                if (inside(mx, my, ix - 2, iy - 2, 20, 20)) {
                    pendingTooltip = tip;
                }
            } else if (sbpv) { // SkyBlockPV's categories: tabs/right on the window's right edge, 22 apart from y + 20
                int tstep = Math.min(22, (panelH - 24) / n);
                int tx = panelX + panelW - 9, ty = panelY + 20 + tstep * i;
                PvStyleChrome.sideTab(g, pvStyle, tx, ty, i == invView);
                g.item(INV_ICONS[i], tx + 10, ty + 2);
                if (hits > 0) {
                    tx(g, hitText, tx + 28 - this.font.width(hitText), ty + 11, ACCENT);
                }
                hotspots.add(new Hotspot(tx, ty, 31, Math.min(20, tstep), pick));
                if (inside(mx, my, tx, ty, 31, Math.min(20, tstep))) {
                    pendingTooltip = tip;
                }
            } else {
                button(g, contentX + 4, ny, navW - 8, step - 3, INV_VIEWS[i], mx, my, i == invView, pick);
                if (hits > 0) {
                    tx(g, hitText, contentX + navW - 8 - 4 - this.font.width(hitText), ny + (step - 3 - 8) / 2 + 1, ACCENT);
                }
                ny += step;
            }
        }
        // The search box: under the buttons (Default), under the selector (NEU), along the bottom (SkyBlockPV).
        invSearch.visible = true;
        invSearch.setX(neu ? panelX + 17 : contentX + 4);
        invSearch.setY(neu ? panelY + 26 + 34 * 4 + 2 : contentY + contentH - (sbpv ? 16 : 18));
        invSearch.setWidth(neu ? 100 : sbpv ? Math.min(160, contentW / 2) : navW - 8);

        // Everything right of pv_invs' second line (x 221) is the selected inventory's.
        // SkyBlockPV: the views float on the window, no boxes.
        int rx = neu ? panelX + 222 : sbpv ? contentX : contentX + navW + 6;
        int rw = neu ? PvStyleChrome.NEU_W - 226 : sbpv ? contentW : contentW - navW - 6;
        if (!sbpv) {
            box(g, rx, contentY, rw, contentH);
        }

        if (!p.inventoryApi) {
            centeredIn(g, "Inventory API is disabled for this profile.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        if (!future.isDone()) {
            centeredIn(g, "Decoding items" + dots(), rx, rw, contentY + contentH / 2 - 4, VALUE);
            return;
        }
        // getNow rethrows (CompletionException) for a failed decode - never let that escape into render.
        if (inv == null) {
            centeredIn(g, "Couldn't decode this profile's items.", rx, rw, contentY + contentH / 2 - 4, BAD);
            return;
        }

        int top = contentY + 22;
        int bottom = contentY + contentH - (sbpv ? 20 : 4);
        switch (invView) {
            case PvSearch.INVENTORY -> {
                if (neu) {
                    drawNeuInventory(g, inv, mx, my);
                    return;
                }
                if (sbpv) {
                    drawSbpvInventory(g, inv, mx, my);
                    return;
                }
                tx(g, "Inventory" + found(PvSearch.INVENTORY), rx + 6, contentY + 6, ACCENT);
                // Armor (helmet first) + equipment beside the main grid, the hotbar under it: 208 x 78 laid out once,
                // then sized to the panel like every other grid.
                scaled(g, rx, rw, top, bottom, 208, 78, mx, my, (lx, ly) -> {
                    List<ItemStack> armor = inv.armor();
                    for (int i = 0; i < 4; i++) {
                        ItemStack s = armor.size() == 4 ? armor.get(3 - i) : ItemStack.EMPTY;
                        slot(g, 0, i * 18, s, lx, ly, false);
                        slot(g, 20, i * 18, get(inv.equipment(), i), lx, ly, false);
                    }
                    List<ItemStack> items = inv.inventory();
                    for (int i = 9; i < 36; i++) {
                        slot(g, 46 + ((i - 9) % 9) * 18, ((i - 9) / 9) * 18, get(items, i), lx, ly, false);
                    }
                    for (int i = 0; i < 9; i++) {
                        slot(g, 46 + i * 18, 3 * 18 + 6, get(items, i), lx, ly, false);
                    }
                });
            }
            // killer560: "for the enderchest and backpacks, make that fit a similar style to our custom
            // gui overlay, so I can see more at once" - tile every page/backpack in a scrollable grid of
            // panels (like the storage overlay does) instead of one page with "< 1/5 >" arrows.
            case PvSearch.ENDER -> {
                tx(g, "Ender Chest" + found(PvSearch.ENDER), rx + 6, contentY + 6, ACCENT);
                multiPanelGrid(g, rx, rw, top, bottom, marked(PvSearch.ENDER, numbered("Page", inv.enderChest().size())), inv.enderChest(), mx, my);
            }
            case PvSearch.BACKPACKS -> {
                List<SbProfile.Backpack> bps = inv.backpacks();
                tx(g, "Backpacks" + found(PvSearch.BACKPACKS), rx + 6, contentY + 6, ACCENT);
                if (bps.isEmpty()) {
                    centeredIn(g, "No backpacks.", rx, rw, contentY + contentH / 2 - 4, DIM);
                    return;
                }
                // 2026-10-08: no backpack-item icon in front of the label - just the storage overlay's panel.
                List<String> labels = new ArrayList<>();
                List<List<ItemStack>> itemLists = new ArrayList<>();
                for (SbProfile.Backpack bp : bps) {
                    labels.add("Backpack " + (bp.slot() + 1));
                    itemLists.add(bp.items());
                }
                multiPanelGrid(g, rx, rw, top, bottom, marked(PvSearch.BACKPACKS, labels), itemLists, mx, my);
            }
            case PvSearch.WARDROBE -> pagedGrid(g, "Wardrobe", inv.wardrobe(), rx, rw, top, bottom, mx, my, inv.wardrobeEquipped());
            case PvSearch.ACCESSORIES -> accessoryGrid(g, accOwned(inv.accessories()), false, rx, rw, 0, top, mx, my);
            case PvSearch.VAULT -> drawBag(g, "Personal Vault", PvSearch.VAULT, inv.personalVault(), "Empty or not shared.", rx, rw, top, bottom, mx, my);
            case PvSearch.QUIVER -> {
                drawBag(g, "Quiver", PvSearch.QUIVER, inv.quiver(), "Empty.", rx, rw, top, bottom, mx, my);
                int arrows = countItems(inv.quiver());
                if (arrows > 0) {
                    String total = commasText(arrows) + " arrows";
                    tx(g, total, rx + rw - 8 - this.font.width(total), contentY + 6, VALUE);
                }
            }
            case PvSearch.FISHING -> drawBag(g, "Fishing Bag", PvSearch.FISHING, inv.fishingBag(), "Empty.", rx, rw, top, bottom, mx, my);
            case PvSearch.POTION -> drawBag(g, "Potion Bag", PvSearch.POTION, inv.potionBag(), "Empty.", rx, rw, top, bottom, mx, my);
            default -> extraPages.drawSacks(g, p, rx, rw, mx, my, invFind);
        }
    }

    private static String commasText(long v) { return String.format(Locale.ROOT, "%,d", v); }

    /** One container shown alone (vault, quiver, fishing bag, potion bag): its title with the match count, then its grid. */
    private void drawBag(GuiGraphicsExtractor g, String title, int view, List<ItemStack> items, String empty, int rx, int rw,
                         int top, int bottom, int mx, int my) {
        tx(g, title + found(view), rx + 6, contentY + 6, ACCENT);
        if (items.isEmpty()) {
            centeredIn(g, empty, rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        scaled(g, rx, rw, top, bottom, 9 * 18, rowsOf(items) * 18, mx, my, (lx, ly) -> grid(g, items, 0, 0, lx, ly, -1));
    }

    private static int rowsOf(List<ItemStack> items) { return Math.max(1, (items.size() + 8) / 9); }

    /** "  (3)" after a view's title while the search is on: how many of its items match ("no matches" for none). */
    private String found(int view) {
        if (!invFind.active()) return "";
        int n = invFind.count(view);
        return n == 0 ? "  (no matches)" : "  (" + n + ")";
    }

    /** The panel labels with their own match counts, so each page or backpack shows what it holds. */
    private List<String> marked(int view, List<String> labels) {
        if (!invFind.active()) return labels;
        List<String> out = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            int n = invFind.count(view, i);
            out.add(n > 0 ? labels.get(i) + " (" + n + ")" : labels.get(i));
        }
        return out;
    }

    @FunctionalInterface
    private interface GridDraw { void draw(int mx, int my); }

    /** THE sizing rule for every single grid: the layout is drawn once at 1x from (0, 0) and scaled by the largest
     *  quarter step (1 to 3) that fits both the width and the height of the area, centered in it. The mouse is mapped
     *  into the layout so hovers, tooltips and counts stay aligned. Returns the screen y of the block's bottom. */
    private int scaled(GuiGraphicsExtractor g, int rx, int rw, int top, int bottom, int gw, int gh, int mx, int my, GridDraw d) {
        float sc = Math.min((rw - 12f) / gw, (bottom - top) / (float) gh);
        sc = Math.max(1f, Math.min(3f, (float) Math.floor(sc * 4f) / 4f));
        int ox = rx + Math.round((rw - gw * sc) / 2f);
        int oy = top + Math.max(0, Math.round(((bottom - top) - gh * sc) / 2f));
        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.pose().scale(sc, sc);
        try {
            d.draw((int) Math.floor((mx - ox) / sc), (int) Math.floor((my - oy) / sc));
        } finally {
            g.pose().popMatrix();
        }
        return oy + Math.round(gh * sc);
    }

    /** Search marks on an inventory slot (Inventories page only): a match gets the accent edge, the rest dim. */
    private void findMark(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (page != PAGE_INVENTORIES || !invFind.active() || stack.isEmpty()) return;
        if (invFind.matches(stack)) {
            g.outline(x, y, 18, 18, ACCENT);
        } else {
            g.fill(x + 1, y + 1, x + 17, y + 17, 0xA0000000);
        }
    }

    /** Matches the search holds in one view (all pages), for the testkit and the buttons. */
    public int findCount(int view) { return invFind.count(view); }

    public void setFind(String text) {
        invSearch.setValue(text);
    }

    public boolean findFocused() { return invSearch.isFocused(); }

    /** The Inventory view on pv_invs: armor and equipment in its 2 x 4 box (InventoriesPage: armor at 173, 67 - 18i,
     *  equipment at 192, 13 + 18i; its weapon, rod and tool boxes stay empty, we have no "best item" pick), and the
     *  inventory in a vanilla chest panel centered on 320, 101 with the hotbar as its fourth row. */
    private void drawNeuInventory(GuiGraphicsExtractor g, SbProfile.Inventories inv, int mx, int my) {
        List<ItemStack> armor = inv.armor();
        for (int i = 0; i < 4; i++) {
            slotItem(g, panelX + 172, panelY + 66 - 18 * i, armor.size() == 4 ? armor.get(i) : ItemStack.EMPTY, mx, my);
            slotItem(g, panelX + 191, panelY + 12 + 18 * i, get(inv.equipment(), i), mx, my);
        }
        int rows = 4, h = rows * 18 + 17, x = panelX + 320 - 88, y = panelY + 101 - (h + 7) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE, x, y, 0f, 0f, 176, h, 256, 256);
        g.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE, x, y + h, 0f, 215f, 176, 7, 256, 256);
        g.text(this.font, INV_VIEWS[0], x + 8, y + 6, 0xFF404040, false);
        List<ItemStack> items = inv.inventory();
        for (int i = 0; i < 36; i++) {
            int row = i < 9 ? 3 : (i - 9) / 9;
            slotItem(g, x + 7 + (i % 9) * 18, y + 17 + row * 18, get(items, i), mx, my);
        }
    }

    /** SkyBlockPV's Inventory view (InventoryScreen): armor (helmet first) and equipment in a 2 x 4 block, 10 px, then
     *  the inventory 9 x 4 with the hotbar as its last row; 20 px cells inside 2 px padding, the row centered in the window. */
    private void drawSbpvInventory(GuiGraphicsExtractor g, SbProfile.Inventories inv, int mx, int my) {
        int x = panelX + (panelW - 238) / 2 + 2, y = panelY + (panelH - 84) / 2 + 2;
        List<ItemStack> armor = inv.armor(), items = inv.inventory();
        for (int i = 0; i < 4; i++) {
            sbpvCell(g, x, y + 20 * i, armor.size() == 4 ? armor.get(3 - i) : ItemStack.EMPTY, mx, my);
            sbpvCell(g, x + 20, y + 20 * i, get(inv.equipment(), i), mx, my);
        }
        for (int i = 0; i < 36; i++) {
            sbpvCell(g, x + 54 + (i % 9) * 20, y + 20 * (i < 9 ? 3 : i / 9 - 1), get(items, i), mx, my);
        }
    }

    private void sbpvCell(GuiGraphicsExtractor g, int x, int y, ItemStack stack, int mx, int my) {
        slotBox(g, x, y, 20, 0);
        slotItem(g, x + 1, y + 1, stack, mx, my);
    }

    private void pagedGrid(GuiGraphicsExtractor g, String title, List<List<ItemStack>> pages, int rx, int rw, int top,
                           int bottom, int mx, int my, int wardrobeEquipped) {
        if (pages.isEmpty()) {
            tx(g, title, rx + 6, contentY + 6, ACCENT);
            centeredIn(g, "Empty.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        invPage = Math.floorMod(invPage, pages.size());
        pager(g, title + pageFound(PvSearch.WARDROBE), rx, rw, invPage, pages.size(), mx, my);
        int equippedCol = -1;
        if (wardrobeEquipped > 0 && (wardrobeEquipped - 1) / 9 == invPage) {
            equippedCol = (wardrobeEquipped - 1) % 9;
        }
        List<ItemStack> items = pages.get(invPage);
        final int ec = equippedCol;
        scaled(g, rx, rw, top, bottom, 9 * 18, rowsOf(items) * 18, mx, my, (lx, ly) -> grid(g, items, 0, 0, lx, ly, ec));
    }

    /** "  (2 here, 5 in all)" for a paged view while the search is on. */
    private String pageFound(int view) {
        if (!invFind.active()) return "";
        int all = invFind.count(view);
        return all == 0 ? "  (no matches)" : "  (" + invFind.count(view, invPage) + " here, " + all + " in all)";
    }

    /** The bag grid with its sort button and pager, for the main bag here and the Rift Accessory Bag on the Rift page. A
     *  null {@code owned} is a bag the API does not send. Every accessory is its own slot of the 9 x 5 page (SkyBlock's own
     *  bag menu, 0.27.3): missing ones grayed, toggled-off ones marked with a red corner. */
    void accessoryGrid(GuiGraphicsExtractor g, List<ItemStack> owned, boolean accRift, int rx, int rw, int unused, int top,
                       int mx, int my) {
        int by = contentY + 3;
        button(g, rx + 6, by, 110, 14, "Sort: " + AccessoryBagView.SORT_LABELS[accSort], mx, my, false,
                () -> { accSort = (accSort + 1) % AccessoryBagView.SORT_LABELS.length; invPage = 0; });
        if (owned == null) {
            centeredIn(g, "The API does not send the Rift Accessory Bag yet.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        List<AccessoryBagView.Slot> slots = AccessoryBagView.slots(owned, accRift, accSort);
        int pages = Math.max(1, (slots.size() + AccessoryBagView.PAGE - 1) / AccessoryBagView.PAGE);
        invPage = Math.floorMod(invPage, pages);
        if (pages > 1) {
            String label = (invPage + 1) + " / " + pages;
            int right = rx + rw - 6;
            button(g, right - 16, by, 16, 14, ">", mx, my, false, () -> invPage++);
            tx(g, label, right - 22 - this.font.width(label), by + 4, VALUE);
            button(g, right - 42 - this.font.width(label), by, 16, 14, "<", mx, my, false, () -> invPage--);
        }
        if (!accRift && invFind.active()) {
            String f = found(PvSearch.ACCESSORIES);
            tx(g, "Accessories" + f, rx + 124, by + 4, ACCENT);
        }
        int from = invPage * AccessoryBagView.PAGE;
        int rows = (AccessoryBagView.PAGE + 8) / 9;
        int below = scaled(g, rx, rw, top + 2, contentY + contentH - 4 - 24, 9 * 18, rows * 18, mx, my, (lx, ly) -> {
            for (int i = 0; i < AccessoryBagView.PAGE; i++) {
                int x = (i % 9) * 18;
                int y = (i / 9) * 18;
                AccessoryBagView.Slot s = from + i < slots.size() ? slots.get(from + i) : null;
                if (s == null) {
                    slotBox(g, x, y, 18, 0);
                    continue;
                }
                if (s.state() == AccessoryBagView.State.MISSING) {
                    slotBox(g, x, y, 18, 0);
                    g.item(s.stack(), x + 1, y + 1);
                    g.fill(x + 1, y + 1, x + 17, y + 17, ACC_MISSING_SHADE);
                    if (inside(lx, ly, x, y, 18, 18) && !dropdownOpen) {
                        pendingTooltip = List.of(
                                Component.literal(s.name()).withStyle(style(0xFF000000 | LevelTables.rarityColor(s.tier()))),
                                Component.literal("Missing").withStyle(style(DIM)));
                    }
                    continue;
                }
                slot(g, x, y, s.stack(), lx, ly, false);
                if (s.state() == AccessoryBagView.State.DISABLED) {
                    g.fill(x + 1, y + 1, x + 17, y + 17, ACC_MISSING_SHADE);
                    g.fill(x + 12, y + 1, x + 17, y + 6, BAD);
                }
            }
        });
        int[] c = AccessoryBagView.counts(slots);
        int gx = rx + 8, ly = below + 4;
        tx(g, c[0] + " owned", gx, ly, VALUE);
        tx(g, c[1] + " toggled off", gx + this.font.width(c[0] + " owned") + 10, ly, BAD);
        tx(g, c[2] + " missing", gx, ly + 11, DIM);
    }

    /** The main bag's pages flattened once per decoded inventory (the pages list is fixed after decode). */
    private List<ItemStack> accOwned(List<List<ItemStack>> pages) {
        if (pages != accPagesKey) {
            List<ItemStack> flat = new ArrayList<>();
            for (List<ItemStack> page : pages) flat.addAll(page);
            accPagesKey = pages;
            accFlat = flat;
        }
        return accFlat;
    }

    private static List<String> numbered(String prefix, int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            out.add(prefix + " " + i);
        }
        return out;
    }

    /** Tiles every page/backpack as its own panel and scrolls vertically through the rest. Each panel is drawn exactly
     *  like the Storage Overlay's (its theme's panel, label and slot cells - killer560, 2026-10-08: "just have our
     *  custom backpack overlay no matter what"), the whole block sized by the largest scale (up to 3x) at which one
     *  panel still fits the area, and nothing is drawn below it: a panel row that does not fit is clipped there. */
    private void multiPanelGrid(GuiGraphicsExtractor g, int rx, int rw, int top, int bottom, List<String> labels,
                                List<List<ItemStack>> pages, int mx, int my) {
        if (pages.isEmpty()) {
            centeredIn(g, "Empty.", rx, rw, contentY + contentH / 2 - 4, DIM);
            return;
        }
        int panelW = OVERLAY_PANEL_W;
        int gap = 6;
        int visibleH = Math.max(0, bottom - top);
        int maxRows = 1;
        for (List<ItemStack> pg : pages) {
            maxRows = Math.max(maxRows, rowsOf(pg));
        }
        float f = 1f;
        for (float c : new float[]{3f, 2.5f, 2f, 1.75f, 1.5f, 1.25f}) {
            if (panelW * c <= rw - 8 && overlayPanelHeight(maxRows) * c <= visibleH) {
                f = c;
                break;
            }
        }
        float lw = rw / f;
        int lh = (int) (visibleH / f);
        int cols = Math.max(1, (int) ((lw - 8 + gap) / (panelW + gap)));
        int startX = Math.max(0, (int) ((lw - (cols * (panelW + gap) - gap)) / 2));

        int panelRows = (pages.size() + cols - 1) / cols;
        int[] rowH = new int[panelRows];
        for (int r = 0; r < panelRows; r++) {
            int maxItemRows = 1;
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx < pages.size()) {
                    maxItemRows = Math.max(maxItemRows, rowsOf(pages.get(idx)));
                }
            }
            rowH[r] = overlayPanelHeight(maxItemRows) + gap;
        }

        // The max scroll always leaves at least the last row on screen, even if that one row alone is
        // taller than the viewport - never scrolls one row past the end into blank space.
        int maxScrollRow = panelRows - 1;
        int used = 0;
        for (int r = panelRows - 1; r >= 0; r--) {
            if (used + rowH[r] - gap > lh) break;
            used += rowH[r];
            maxScrollRow = r;
        }
        invScroll = Math.max(0, Math.min(invScroll, maxScrollRow));

        int lmx = (int) Math.floor((mx - rx) / f), lmy = (int) Math.floor((my - top) / f);
        g.enableScissor(rx + 1, top, rx + rw - 1, bottom); // set before the pose, in screen coordinates
        g.pose().pushMatrix();
        g.pose().translate(rx, top);
        g.pose().scale(f, f);
        try {
            int y = 0;
            for (int r = invScroll; r < panelRows && y < lh; r++) {
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    if (idx < pages.size()) {
                        overlayPanel(g, startX + c * (panelW + gap), y, labels.get(idx), pages.get(idx), lmx, lmy, 0, lh);
                    }
                }
                y += rowH[r];
            }
        } finally {
            g.pose().popMatrix();
            g.disableScissor();
        }

        if (maxScrollRow > 0) {
            int trackX = rx + rw - 4;
            int thumbH = Math.max(8, visibleH * (panelRows - maxScrollRow) / panelRows);
            int thumbY = top + (visibleH - thumbH) * invScroll / Math.max(1, maxScrollRow);
            ScreenChrome.scrollbar(g, trackX - 1, top, 4, visibleH, thumbY, thumbH, false);
        }
    }

    /** {@code StorageOverlayFeature}'s panel: 9 slots of 18 plus a 2-pixel margin each side. */
    static final int OVERLAY_PANEL_W = 9 * 18 + 4;

    int overlayPanelHeight(int itemRows) { return this.font.lineHeight + 4 + itemRows * 18 + 2; }

    /** One Storage Overlay panel (theme colors from the Storage Overlay's own setting): label strip, the slot-cell
     *  grid and the items, with the rarity background and a tooltip only for a slot inside {@code clipTop..clipBottom}. */
    void overlayPanel(GuiGraphicsExtractor g, int x, int y, String label, List<ItemStack> items, int mx, int my,
                      int clipTop, int clipBottom) {
        com.killer560.hub.gui.PanelColours theme = com.killer560.hub.storageoverlay.StorageOverlayConfig.getInstance().colours();
        int rows = Math.max(1, (items.size() + 8) / 9);
        int h = overlayPanelHeight(rows);
        g.fill(x, y, x + OVERLAY_PANEL_W, y + h, theme.panelBg());
        g.outline(x, y, OVERLAY_PANEL_W, h, theme.border());
        tx(g, this.font.plainSubstrByWidth(label, OVERLAY_PANEL_W - 6), x + 3, y + 3, theme.text());
        int gridX = x + 2;
        int gridY = y + this.font.lineHeight + 4;
        int gw = 9 * 18;
        int gh = rows * 18;
        g.fill(gridX, gridY, gridX + gw, gridY + gh, theme.cellBg());
        for (int col = 0; col <= 9; col++) {
            g.fill(gridX + col * 18, gridY, gridX + col * 18 + 1, gridY + gh, theme.cellLine());
        }
        for (int row = 0; row <= rows; row++) {
            g.fill(gridX, gridY + row * 18, gridX + gw, gridY + row * 18 + 1, theme.cellLine());
        }
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.isEmpty()) continue;
            int sx = gridX + (i % 9) * 18 + 1;
            int sy = gridY + (i / 9) * 18 + 1;
            rarity(g, stack, sx, sy);
            g.item(stack, sx, sy);
            g.itemDecorations(this.font, stack, sx, sy);
            findMark(g, stack, sx - 1, sy - 1);
            if (inside(mx, my, sx, sy, 16, 16) && my >= clipTop && my < clipBottom && !dropdownOpen) {
                g.fill(sx, sy, sx + 16, sy + 16, MenuStyle.alpha(MenuStyle.TXT, 0x40));
                pendingItemTooltip = stack;
            }
        }
    }

    private void pager(GuiGraphicsExtractor g, String title, int rx, int rw, int index, int count, int mx, int my) {
        tx(g, title, rx + 24, contentY + 7, ACCENT);
        if (count <= 1) return;
        String label = (index + 1) + " / " + count;
        int right = rx + rw - 6;
        button(g, right - 16, contentY + 3, 16, 14, ">", mx, my, false, () -> invPage++);
        tx(g, label, right - 22 - this.font.width(label), contentY + 7, VALUE);
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
        slotBox(g, x, y, 18, highlight ? ACCENT : 0);
        slotItem(g, x, y, stack, mx, my);
    }

    /** The item of an 18 px slot at x, y, without the cell (the page textures paint their own). */
    void slotItem(GuiGraphicsExtractor g, int x, int y, ItemStack stack, int mx, int my) {
        if (!stack.isEmpty()) {
            rarity(g, stack, x + 1, y + 1);
            g.item(stack, x + 1, y + 1);
            g.itemDecorations(this.font, stack, x + 1, y + 1);
            findMark(g, stack, x, y);
            if (inside(mx, my, x, y, 18, 18) && !dropdownOpen) {
                g.fill(x + 1, y + 1, x + 17, y + 17, MenuStyle.alpha(MenuStyle.TXT, 0x40));
                pendingItemTooltip = stack;
            }
        }
    }

    /** The Item Rarity feature's background, when that feature is on (killer560, 2026-10-08: "if I have things like
     *  item rarities on then in the pv menu it should still have item rarities"). Same call, same settings, as the
     *  container-slot mixin - this screen is not a container, so the mixin never sees its items. */
    static void rarity(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        try {
            com.killer560.hub.itemrarity.ItemRarityFeature.drawBackground(g, stack, x, y, false);
        } catch (Throwable ignored) {
            // Visual only; a lore edge case must never break the screen.
        }
    }

    static ItemStack get(List<ItemStack> list, int i) { return i >= 0 && i < list.size() ? list.get(i) : ItemStack.EMPTY; }

    private static int countItems(List<ItemStack> list) {
        int n = 0;
        for (ItemStack s : list) {
            n += s.getCount();
        }
        return n;
    }


    private void drawPets(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        if (neuPage()) {
            drawNeuPets(g, p, mx, my);
            return;
        }
        if (pvStyle != ProfileViewerConfig.Style.DEFAULT) {
            drawSbpvPets(g, p, mx, my);
            return;
        }
        int infoW = 150;
        int gx = contentX;
        int gw = contentW - infoW - 6;
        box(g, gx, contentY, gw, contentH);
        tx(g, "Pets (" + p.pets.size() + ")", gx + 6, contentY + 5, ACCENT);
        if (p.petScore > 0) {
            String score = "Pet Score " + p.petScore;
            tx(g, score, gx + gw - 6 - this.font.width(score), contentY + 5, VALUE);
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
            if (row >= visibleRows) break;
            SbProfile.Pet pet = p.pets.get(i);
            int x = startX + (i % cols) * cell;
            int y = startY + row * cell;
            int rarity = 0xFF000000 | LevelTables.rarityColor(pet.tier);
            slotBox(g, x + 1, y + 1, cell - 2, pet.active ? ACCENT : (rarity & 0x80FFFFFF));
            if (pet == pinnedPet) {
                g.outline(x, y, cell, cell, MenuStyle.TXT);
            }
            g.item(pet.icon(), x + 2, y + 2);
            boolean hover = inside(mx, my, x, y, cell, cell) && !dropdownOpen;
            if (hover) {
                hovered = pet;
                g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, MenuStyle.alpha(MenuStyle.TXT, 0x40));
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("[Lvl " + petLevel(pet) + "] ").withStyle(style(DIM))
                        .append(Component.literal(pet.displayName()).withStyle(style(rarity))));
                tip.add(Component.literal(SbProfile.titleCase(pet.tier)).withStyle(style(rarity)));
                if (pet.active) {
                    tip.add(Component.literal("Active pet").withStyle(style(ACCENT)));
                }
                pendingTooltip = tip;
            }
            hotspots.add(new Hotspot(x, y, cell, cell, () -> pinnedPet = pinnedPet == pet ? null : pet));
        }
        if (totalRows > visibleRows) {
            int trackX = gx + gw - 4;
            int trackH = visibleRows * cell;
            int thumbH = Math.max(8, trackH * visibleRows / totalRows);
            int thumbY = startY + (trackH - thumbH) * petScroll / Math.max(1, totalRows - visibleRows);
            ScreenChrome.scrollbar(g, trackX - 1, startY, 4, trackH, thumbY, thumbH, false);
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
        int rarity = ScreenChrome.readable(0xFF000000 | LevelTables.rarityColor(show.tier));
        g.item(show.icon(), x, y);
        tx(g, this.font.plainSubstrByWidth(show.displayName(), infoW - 30), x + 20, y, rarity);
        tx(g, SbProfile.titleCase(show.tier) + (show.active ? " · Active" : ""), x + 20, y + 10, DIM);
        petInfo(g, show, x, y + 26, infoW - 12);
    }

    /** NEU Classic's Pets page on pv_pets: 20 pets a page in 5 x 4 pv_elements cells over the left 190 px (the selected
     *  one tinted orange), the pet drawn large in the white frame and its numbers in the gray box at 319 (PetsPage). */
    private void drawNeuPets(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int pages = Math.max(1, (p.pets.size() + 19) / 20);
        petScroll = Math.floorMod(petScroll, pages);
        centeredIn(g, "Pets", panelX, 200, panelY + 10, VALUE);
        if (pages > 1) {
            button(g, panelX + 73, panelY + 6, 12, 16, "<", mx, my, false, () -> petScroll--);
            button(g, panelX + 115, panelY + 6, 12, 16, ">", mx, my, false, () -> petScroll++);
        }
        SbProfile.Pet hovered = null;
        for (int i = petScroll * 20; i < Math.min(p.pets.size(), petScroll * 20 + 20); i++) {
            SbProfile.Pet pet = p.pets.get(i);
            int x = panelX + 5 + Math.round(15 + 35 * (i % 5)), y = panelY + 7 + Math.round(24.4f + 44.4f * ((i % 20) / 5));
            SbProfile.Pet sel = pinnedPet != null ? pinnedPet : activePet(p);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, pet == sel ? PvStyleChrome.NEU_PET_SEL : PvStyleChrome.NEU_INV_TAB, x, y, 20, 20);
            g.item(pet.icon(), x + 2, y + 2);
            if (inside(mx, my, x, y, 20, 20) && !dropdownOpen) {
                hovered = pet;
                pendingTooltip = List.of(Component.literal("[Lvl " + petLevel(pet) + "] " + pet.displayName())
                        .withStyle(style(0xFF000000 | LevelTables.rarityColor(pet.tier))));
            }
            hotspots.add(new Hotspot(x, y, 20, 20, () -> pinnedPet = pinnedPet == pet ? null : pet));
        }
        SbProfile.Pet show = hovered != null ? hovered : pinnedPet != null ? pinnedPet : activePet(p);
        if (show == null) {
            return;
        }
        int rarity = ScreenChrome.readable(0xFF000000 | LevelTables.rarityColor(show.tier));
        centeredIn(g, this.font.plainSubstrByWidth(show.displayName(), 96), panelX + 204, 96, panelY + 30, rarity);
        g.pose().pushMatrix();
        g.pose().translate(panelX + 220, panelY + 66);
        g.pose().scale(4f, 4f);
        g.item(show.icon(), 0, 0);
        g.pose().popMatrix();
        petInfo(g, show, panelX + 319, panelY + 28, 98);
    }

    /** SkyBlockPV's Pets page (PetScreen): 22 px slots 27 apart over the left 65 % (the selected pet's tinted green, each
     *  pet's level in its rarity color), and the Info label (title bar + box) on the right with its gray lines. */
    private void drawSbpvPets(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        int x0 = panelX + 5, y0 = contentY, left = (panelW - 10) * 65 / 100, right = panelW - 10 - left;
        int perRow = Math.max(1, left / 28), rows = Math.max(1, (panelY + panelH - y0) / 27);
        int totalRows = (p.pets.size() + perRow - 1) / perRow;
        petScroll = Math.max(0, Math.min(petScroll, totalRows - rows));
        SbProfile.Pet sel = pinnedPet != null ? pinnedPet : activePet(p);
        for (int i = petScroll * perRow; i < p.pets.size() && i / perRow - petScroll < rows; i++) {
            SbProfile.Pet pet = p.pets.get(i);
            int x = x0 + (i % perRow) * 27, y = y0 + (i / perRow - petScroll) * 27;
            int rarity = 0xFF000000 | LevelTables.rarityColor(pet.tier);
            PvStyleChrome.slot(g, pvStyle, x, y, 22, pet == sel ? 0xFF55FF55 : -1);
            g.item(pet.icon(), x + 3, y + 3);
            String lvl = String.valueOf(pet.level.level());
            g.text(this.font, lvl, x + 20 - this.font.width(lvl), y + 12, rarity, true);
            if (inside(mx, my, x, y, 22, 22) && !dropdownOpen) {
                pendingTooltip = List.of(Component.literal("[Lvl " + lvl + "] " + pet.displayName()).withStyle(style(rarity)));
            }
            hotspots.add(new Hotspot(x, y, 22, 22, () -> pinnedPet = pet));
        }
        int ix = x0 + left + 5, text = PvStyleChrome.sbpvText(pvStyle);
        PvStyleChrome.title(g, pvStyle, ix, y0, right - 10, 20);
        g.text(this.font, "Info", ix + (right - this.font.width("Info")) / 2, y0 + 6, text, false);
        int n = sel == null ? 1 : 4 + (sel.candyUsed > 0 ? 1 : 0) + (sel.heldItem != null ? 1 : 0);
        box(g, ix, y0 + 20, right - 10, 12 + n * 11);
        int y = sbpvLine(g, "Name: " + (sel == null ? "Unknown" : SbProfile.titleCase(sel.type)), ix + 10, y0 + 27, right - 20, text);
        if (sel == null) {
            return;
        }
        y = sbpvLine(g, "Level: " + petLevel(sel), ix + 10, y, right - 20, text);
        y = sbpvLine(g, "Exp: " + shorten(sel.exp), ix + 10, y, right - 20, text);
        y = sbpvLine(g, sel.level.maxed() ? "Progress Max: Maxed" : "Progress Next: " + Math.round(sel.level.progress() * 100) + "%",
                ix + 10, y, right - 20, text);
        if (sel.candyUsed > 0) {
            y = sbpvLine(g, "Candy Used: " + sel.candyUsed + "/10", ix + 10, y, right - 20, text);
        }
        if (sel.heldItem != null) {
            sbpvLine(g, "Held Item: " + SbProfile.titleCase(sel.heldItem.replace("PET_ITEM_", "")), ix + 10, y, right - 20, text);
        }
    }

    /** One of SkyBlockPV's gray, unshadowed info lines; returns the next line's y (9 + its 2 px spacing). */
    private int sbpvLine(GuiGraphicsExtractor g, String s, int x, int y, int w, int color) {
        g.text(this.font, this.font.plainSubstrByWidth(s, w), x, y, color, false);
        return y + 11;
    }

    /** The pet's level, XP, held item, candy and skin lines from x, y, w wide. */
    private void petInfo(GuiGraphicsExtractor g, SbProfile.Pet show, int x, int y, int w) {
        LevelTables.Level lvl = show.level;
        y = stat2(g, x, y, w, "Level", petLevel(show));
        ScreenChrome.progress(g, x, y, w, 4, lvl.maxed() ? 1 : lvl.progress(), lvl.maxed() ? MAXED : ACCENT);
        y += 8;
        if (!lvl.maxed()) {
            y = stat2(g, x, y, w, "Next", shorten(lvl.xpIntoLevel()) + " / " + shorten(lvl.xpForLevel()));
        }
        y = stat2(g, x, y, w, "Total XP", shorten(show.exp));
        if (show.heldItem != null) {
            tx(g, "Held Item", x, y, DIM);
            y += 10;
            tx(g, this.font.plainSubstrByWidth(SbProfile.titleCase(show.heldItem.replace("PET_ITEM_", "")), w), x, y, VALUE);
            y += 11;
        }
        if (show.candyUsed > 0) {
            y = stat2(g, x, y, w, "Candy Used", String.valueOf(show.candyUsed));
        }
        if (show.skin != null) {
            tx(g, "Skin", x, y, DIM);
            y += 10;
            tx(g, this.font.plainSubstrByWidth(SbProfile.titleCase(show.skin), w), x, y, VALUE);
        }
    }

    /** "100", or "100 (102)" when its XP is past the cap (the skills' format). */
    static String petLevel(SbProfile.Pet pet) {
        return pet.level.level() + (pet.uncappedLevel > 0 ? " (" + pet.uncappedLevel + ")" : "");
    }

    private static SbProfile.Pet activePet(SbProfile p) {
        for (SbProfile.Pet pet : p.pets) {
            if (pet.active) return pet;
        }
        return null;
    }


    net.minecraft.client.gui.Font font() { return this.font; }

    /** A content box: the menu's card. */
    ProfileViewerConfig.Style pvStyle() { return pvStyle; }

    /** One of the SkyBlockPV styles, with a profile loaded. */
    boolean sbpvStyle() {
        return pvStyle != ProfileViewerConfig.Style.DEFAULT && pvStyle != ProfileViewerConfig.Style.NEU && state == State.READY;
    }

    /** The pages NEU Classic draws in NEU's own layout, as bits of the page number. */
    private static final int NEU_PAGES = 1 << PAGE_BASIC | 1 << PAGE_DUNGEONS | 1 << PAGE_INVENTORIES | 1 << PAGE_PETS
            | 1 << PAGE_COLLECTIONS | 1 << PAGE_MINING | 1 << PAGE_CRIMSON | 1 << PAGE_MUSEUM | 1 << PAGE_BESTIARY | 1 << PAGE_RIFT | 1 << PAGE_FARMING;

    /** NEU Classic's pages in NEU's own layout (its page textures pv_basic, pv_dung, pv_invs, pv_pets, pv_cols,
     *  pv_crimson_isle_page, pv_museum, pv_bestiary_tab, pv_rift, garden/background paint the boxes; Mining is plain pv_bg). */
    boolean neuPage() {
        return pvStyle == ProfileViewerConfig.Style.NEU && state == State.READY && page >= 0 && (NEU_PAGES >> page & 1) != 0;
    }

    void box(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (neuPage()) {
            return;
        }
        if (pvStyle == ProfileViewerConfig.Style.DEFAULT) {
            ScreenChrome.card(g, x, y, w, h, false);
        } else {
            PvStyleChrome.box(g, pvStyle, x, y, w, h);
        }
    }

    /** A recessed item square; {@code edge} 0 is the plain outline, anything else an accent/rarity edge inside it. */
    void slotBox(GuiGraphicsExtractor g, int x, int y, int size, int edge) {
        if (pvStyle != ProfileViewerConfig.Style.DEFAULT) {
            PvStyleChrome.slot(g, pvStyle, x, y, size);
            if (edge != 0) {
                GuiRects.begin(g).outline(x, y, size, size, edge).submit();
            }
            return;
        }
        GuiRects r = GuiRects.begin(g);
        r.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_BG);
        r.outline(x, y, size, size, SLOT_EDGE);
        r.fill(x + 1, y + 1, x + size - 1, y + 2, MenuStyle.shadow(0x60));
        if (edge != 0) {
            r.outline(x, y, size, size, edge);
        }
        r.submit();
    }

    /** The highlighted row of a list or popup: the menu's selected-row gradient with its accent strip. */
    void selBand(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        ScreenChrome.row(r, x, y, w, h + 1, false, true, false);
        r.submit();
    }

    /** A popup (name suggestions, the profile list): a raised card with the accent ring. */
    private void popup(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 3);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.DRAWER_TOP, MenuStyle.DRAWER_BOTTOM);
        r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.OR2);
        r.submit();
    }

    /** A cycling button: left click runs {@code action}, right click {@code back}. */
    void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my, boolean selected, Runnable action, Runnable back) {
        button(g, x, y, w, h, label, mx, my, selected, action);
        rightHotspots.add(new Hotspot(x, y, w, h, back));
    }

    void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my, boolean selected, Runnable action) {
        boolean hover = inside(mx, my, x, y, w, h) && (!dropdownOpen || drawingHeader);
        if (pvStyle == ProfileViewerConfig.Style.DEFAULT) {
            ScreenChrome.button(g, x, y, w, h, label, hover, selected);
        } else {
            PvStyleChrome.button(g, pvStyle, x, y, w, h, label, hover, selected);
        }
        hotspots.add(new Hotspot(x, y, w, h, action));
    }

    void centered(GuiGraphicsExtractor g, String text, int y, int color) { centeredIn(g, text, contentX, contentW, y, color); }

    void centeredIn(GuiGraphicsExtractor g, String text, int x, int w, int y, int color) {
        tx(g, text, x + (w - this.font.width(text)) / 2, y, color);
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** A tooltip line's style. Tooltips are vanilla's (dark on every theme), so a page color is swapped back for the
     *  fixed color it had before the theme reached this screen. */
    static Style style(int argb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(tipColour(argb) & 0xFFFFFF)).withItalic(false);
    }

    private static int tipColour(int c) {
        if (c == VALUE) return TIP_VALUE;
        if (c == DIM) return TIP_DIM;
        if (c == TEXT) return TIP_TEXT;
        if (c == ACCENT) return TIP_ACCENT;
        if (c == BAD) return TIP_BAD;
        if (c == MAXED) return TIP_MAXED;
        return c == GOOD ? TIP_GOOD : c;
    }

    /** Page text in the theme (readable on Light; shadow on dark themes only). */
    static void tx(GuiGraphicsExtractor g, String s, int x, int y, int color) { ScreenChrome.text(g, s, x, y, color); }

    /** A styled line (a rank tag) keeps its own colors; only its unstyled parts take {@code color}. */
    static void tx(GuiGraphicsExtractor g, Component s, int x, int y, int color) {
        ScreenChrome.text(g, s.getVisualOrderText(), x, y, color);
    }

    static String commas(double v) { return String.format(Locale.US, "%,d", (long) Math.floor(v)); }

    static String shorten(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%.2fB", v / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "%.2fM", v / 1_000_000);
        return abs >= 10_000 ? String.format(Locale.US, "%.1fK", v / 1_000) : commas(v);
    }
}
