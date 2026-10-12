package com.killer560.hub.croesus;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.croesus.DungeonChestValuer.ChestType;
import com.killer560.hub.croesus.DungeonChestValuer.ChestValue;
import com.killer560.hub.croesus.DungeonChestValuer.PricedItem;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import com.killer560.hub.compat.McCompat;

/**
 * Chest Profit overlay + Croesus Profit Logger claim detection (both builds).
 * <ul>
 *   <li>Open dungeon reward chest ("Bedrock Chest", in the end-of-run room or via Croesus): a panel next
 *   to the container listing each reward's value, the cost and the profit.</li>
 *   <li>Croesus run view ("Catacombs - Floor VII"): every unopened chest's profit, sorted, with the best
 *   one's slot highlighted green.</li>
 *   <li>Claim logging: a click on the slot-31 open button of a claimable chest (manual - via Fabric
 *   {@code ScreenMouseEvents} - or Auto Croesus calling {@link #onClaimClick}) becomes a pending claim.
 *   It is committed once the chest screen changes/closes or the button turns "Already opened!", and
 *   dropped on "You cannot afford this!" / "Whoa! Slow down there!" - the exact rollback pair Odin's
 *   KuudraTracker.kt uses for Hypixel's identical reward-chest UI - or if nothing changes within 6s.</li>
 * </ul>
 */
public final class ChestProfitFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-croesus");

    private static final long CONFIRM_MIN_MS = 1000L;
    private static final long CONFIRM_GIVE_UP_MS = 6000L;
    private static final long RUN_FLOOR_MEMORY_MS = 5 * 60 * 1000L;

    private static final int PANEL_BG = 0xD0141008;
    private static final int PANEL_BORDER = 0xFF663D1A;
    /** Chest view: the free claim / the key chest are filled at this alpha with a 2 px solid border, like the run list's
     *  action states. */
    private static final int CHEST_MARK_FILL_ALPHA = 0xA0;
    /** Run heads in the Croesus menu (2026-10-12 redesign): an unclaimed run gets a light tint of its colour and a 1 px
     *  border INSIDE its 16x16 slot (slots are 18 apart, so no two borders touch); a claimed run a darker tint, no
     *  border. Drawn behind the item from {@code croesus/mixin/CroesusSlotMixin}, so the item and tooltip cover it. */
    // killer560, 2026-10-13: "it is really hard still to tell what needs opened now". The two states that need him
    // (unclaimed, needs a key) are near-solid fills with a 2 px border; everything with nothing to do recedes: a light
    // tint or a faint outline, no bright border. Testkit 898 measures the gap (salience) as well as distinctness.
    private static final int RUN_UNCLAIMED_FILL_ALPHA = 0xB0;
    private static final int RUN_UNCLAIMED_BORDER_ALPHA = 0xFF;
    private static final int RUN_CLAIMED_FILL_ALPHA = 0x40;
    private static final int RUN_KEY_FILL_ALPHA = 0xB0;
    /** Not looked at: a 1 px outline at this alpha, no fill, no mark. */
    private static final int RUN_UNKNOWN_OUTLINE_ALPHA = 0x70;
    /** Key used: the darkest look, a heavier tint than "opened" and no border. */
    private static final int RUN_USED_FILL_ALPHA = 0x48;
    private static final int RUN_KEY_BORDER_ALPHA = 0xFF;
    /** A run head's state (killer560, 2026-10-12): what is left to do in it. */
    static final byte RUN_NONE = 0;
    static final byte RUN_UNCLAIMED = 1;
    /** Claimed, no key used, and a second chest clears Auto Croesus's key floor: go back with a key. Never hidden. */
    static final byte RUN_NEEDS_KEY = 2;
    /** Claimed, no key used, nothing left worth a key. Hidden by Hide Opened Runs. */
    static final byte RUN_OPENED = 3;
    /** "No more chests to open!": a key was used, the run is done. Hidden by Hide Opened Runs. */
    static final byte RUN_DONE = 4;
    /** Claimed, no key used, its chests not looked at this session: drawn as "needs a look" (key colour, border only),
     *  never hidden, until the run view is opened once (by him or Auto Croesus). */
    static final byte RUN_UNKNOWN = 5;
    /** The head line once a run is fully used up - SkyHanni's {@code chest.state.keyused} pattern ("No more chests to
     *  open!", CroesusChestTracker.kt, read 2026-10-12), beside quoi's "No chests opened yet!" and "Opened Chest: ...". */
    static final String RUN_DONE_LORE = "No more chests to open!";
    /** Each run's unopened chests (type -> profit after a key), by run (name, floor, players), from the run views
     *  opened this session. Session only: a run not looked at since the game started is RUN_UNKNOWN. */
    // Each run's chests and claims live in CroesusRunMemory (kept with the run when it moves, and across restarts).
    private static final java.util.regex.Pattern OPENED_CHEST = java.util.regex.Pattern.compile("^Opened [cC]hests?: (.{1,24})$");
    /** The run whose view is open (or about to be): the head last clicked, by him or by Auto Croesus. */
    private static volatile CroesusRunMemory.Run openRun = null;
    /** The remembered run of each slot of the Croesus page read last ({@link #readRunHeads}). */
    private static Map<Integer, CroesusRunMemory.Run> runBySlot = Map.of();
    private static Map<Integer, CroesusRunMemory.Entry> entryBySlot = Map.of();
    /**
     * Claims seen in a reward chest screen, by run key (killer560, 2026-10-12: "after claiming a chest sometimes the chest
     * will reappear orange ... when it should read it while I am in the chest"). Hypixel's list head can still say
     * "No chests opened yet!" (or not yet "No more chests to open!") for a refresh after a claim; for
     * {@link #CLAIM_TRUST_MS} the claim seen here wins over a head that has not caught up.
     */
    private static final long CLAIM_TRUST_MS = 60_000L;
    /** Last state logged per run key, and the rate limit on those INFO lines (his next log shows any flip). */
    private static final Map<String, Byte> LOGGED_STATE = new java.util.concurrent.ConcurrentHashMap<>();
    private static long logWindowStartMs = 0L;
    private static int logLinesInWindow = 0;
    /** "Croesus can keep up to 60 runs, and each run has a 72 hours expiry date." - hypixelskyblock.minecraft.wiki/w/Croesus
     *  (read 2026-10-12). 28 run slots a page, so 60 runs is the "(n/3)" pages killer560's screenshot shows. */
    public static final int MAX_STORED_RUNS = 60;
    private static final java.util.regex.Pattern MENU_PAGE = java.util.regex.Pattern.compile("^\\((\\d{1,2})/(\\d{1,2})\\) Croesus$");

    private enum Kind { NONE, CROESUS_MENU, RUN_VIEW, CHEST }

    private record PendingClaim(ChestValue value, String floor, int containerId, long clickedAtMs, String source) {
    }

    private static Kind kind = Kind.NONE;
    /** Per run-head slot in the Croesus menu: 0 = not a run, 1 = nothing claimed yet, 2 = already claimed.
     *  Built once per tick (not per frame) - the fps pass found lore scans in render paths. */
    private static byte[] runHeadState = new byte[0];
    /** The screen and the exact stacks {@link #runHeadState} was read from (killer560, 2026-10-12: "whenever I open the
     *  menu it does this quick flash showing the old run stuff"). The states are worked out in the frame, from the
     *  slots on screen, whenever either changes - a tick-only read left every frame between the content packet and the
     *  next tick drawing the runs unhidden and unhighlighted, which is the flash. */
    private static AbstractContainerScreen<?> runHeadScreen = null;
    private static int runHeadFingerprint = 0;
    /** The container the run states above were read from, so a newer screen never borrows them. */
    private static int runHeadContainerId = Integer.MIN_VALUE;
    /** killer560, 2026-10-12: "count from all pages combined out of how many at max it can store". Each Croesus page's
     *  {unclaimed, claimed} as last read this session, keyed by page; cleared when the page count changes. A page not
     *  seen yet leaves the total partial ("+"). */
    private static final Map<Integer, int[]> PAGE_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static int pageCount = 0;
    private static List<ChestValue> runViewChests = List.of();
    private static ChestValue chestScreenValue = null;
    private static String runViewFloor = null;
    private static String lastKnownRunFloor = null;
    private static long lastKnownRunFloorAtMs = 0L;
    private static PendingClaim pending = null;
    private static volatile boolean pendingFailed = false;

    private ChestProfitFeature() {
    }

    /** The panel's place in Edit HUD (2026-10-12): drawn here over the menu, moved and scaled like any element. */
    public static final String PANEL_ELEMENT_ID = "croesus_profit_panel";
    private static com.killer560.hub.hud.ContainerOverlayElement panelElement;
    /** The lines last drawn, for Edit HUD's preview over the same menu; a sample until a panel has drawn. */
    private static volatile List<Line> lastLines = List.of(
            new Line("Obsidian Chest", 0xFF000000 | ModChat.ORANGE, null, 0),
            new Line("Necron's Handle", 0xFF000000 | ModChat.TEXT, "12.3M", 0xFF000000 | ModChat.LIGHT_ORANGE),
            new Line("Cost", 0xFF000000 | ModChat.TEXT, "-2M", 0xFF000000 | ModChat.LIGHT_ORANGE),
            new Line("Profit", 0xFF000000 | ModChat.ORANGE, "+10.3M", 0xFF000000 | ModChat.GOOD));

    public static void register() {
        CroesusProfitLog.load();
        panelElement = com.killer560.hub.hud.ContainerOverlayElement.register(PANEL_ELEMENT_ID, "Croesus Chest Profit",
                120, panelHeight(lastLines), 10, 30, // no font yet at registration: a width guess until drawn
                () -> CroesusConfig.getInstance().isChestProfitEnabled(),
                (g, x, y) -> drawLines(g, x, y, lastLines))
                .previewSize(() -> new int[]{panelWidth(lastLines), panelHeight(lastLines)});
        ScreenEvents.AFTER_INIT.register(ChestProfitFeature::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ChestProfitFeature.tick", ChestProfitFeature::tick));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            kind = Kind.NONE;
            runViewChests = List.of();
            chestScreenValue = null;
            runHeadState = new byte[0];
            runHeadContainerId = Integer.MIN_VALUE;
            runHeadScreen = null;
        });
        ChatObserver.subscribe(message -> {
            if (pending == null) {
                return;
            }
            String plain = DungeonChestValuer.strip(message.getString()).trim();
            if (plain.equals("You cannot afford this!") || plain.equals("Whoa! Slow down there!")) {
                pendingFailed = true;
            }
        });
    }

    private static boolean active() {
        CroesusConfig cfg = CroesusConfig.getInstance();
        return cfg.isChestProfitEnabled() || cfg.isLoggerEnabled() || cfg.isAutoCroesusEnabled() || cfg.isHideOpenedRuns();
    }

    // ---- public API used by AutoCroesusFeature --------------------------------------------------

    public static List<ChestValue> currentRunViewChests() {
        return runViewChests;
    }

    public static String currentRunViewFloor() {
        return runViewFloor;
    }

    public static String titleOf(Screen screen) {
        return screen == null ? "" : DungeonChestValuer.strip(screen.getTitle().getString()).trim();
    }

    public static List<ItemStack> containerStacks(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        int size = Math.max(0, menu.slots.size() - 36);
        List<ItemStack> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(menu.slots.get(i).getItem());
        }
        return out;
    }

    /** Values every chest head in a Croesus run view (slots 0-27, named "<Type>[ Chest]", lore has "Contents"). */
    public static List<ChestValue> valueRunView(AbstractContainerScreen<?> screen) {
        List<ItemStack> stacks = containerStacks(screen);
        List<ChestValue> out = new ArrayList<>();
        for (int i = 0; i < Math.min(28, stacks.size()); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ChestType type = ChestType.fromName(DungeonChestValuer.strip(stack.getHoverName().getString()));
            if (type == null) {
                continue;
            }
            List<String> lore = DungeonChestValuer.cleanLore(stack);
            if (!lore.contains("Contents")) {
                continue;
            }
            out.add(DungeonChestValuer.fromLore(type, i, lore));
        }
        out.sort(Comparator.comparingLong(ChestValue::profit).reversed());
        return out;
    }

    /** Floor for a run-view title ("Master Catacombs - Floor VII" -> "M7"), falling back to the floor read
     *  off the Croesus head that was last clicked when the title is truncated ("Master Catacombs - Flo"). */
    public static String floorFromRunViewTitle(String title) {
        Matcher m = DungeonChestValuer.RUN_VIEW_TITLE.matcher(title);
        if (!m.matches()) {
            return null;
        }
        Integer n = DungeonChestValuer.roman(m.group(2));
        if (n == null) {
            return lastKnownRunFloor;
        }
        return (m.group(1) != null ? "M" : "F") + n;
    }

    /** Floor from a Croesus menu run head: name "Master Mode The Catacombs" -> M, lore "Floor VII" (quoi). */
    public static String floorFromCroesusHead(ItemStack stack) {
        String name = DungeonChestValuer.strip(stack.getHoverName().getString()).trim();
        if (!name.equals("The Catacombs") && !name.equals("Master Mode The Catacombs")) {
            return null;
        }
        for (String line : DungeonChestValuer.cleanLore(stack)) {
            if (line.startsWith("Floor ")) {
                Integer n = DungeonChestValuer.roman(line.substring("Floor ".length()).trim());
                if (n != null) {
                    return (name.startsWith("Master") ? "M" : "F") + n;
                }
            }
        }
        return null;
    }

    public static void rememberRunFloor(String floor) {
        if (floor != null) {
            lastKnownRunFloor = floor;
            lastKnownRunFloorAtMs = System.currentTimeMillis();
        }
    }

    private static String floorForChestScreen() {
        if (DungeonState.isInDungeon() && DungeonState.getFloor() != null) {
            return DungeonState.getFloor();
        }
        if (lastKnownRunFloor != null && System.currentTimeMillis() - lastKnownRunFloorAtMs < RUN_FLOOR_MEMORY_MS) {
            return lastKnownRunFloor;
        }
        return "Unknown";
    }

    /** Call right BEFORE a click on slot 31 of a reward chest screen is sent. No-op unless the logger is on
     *  and the button is a real claimable chest. */
    public static void onClaimClick(AbstractContainerScreen<?> screen, String source) {
        noteClaim(screen);
        if (!CroesusConfig.getInstance().isLoggerEnabled()) {
            return;
        }
        ChestType type = ChestType.fromName(titleOf(screen));
        if (type == null) {
            return;
        }
        List<ItemStack> stacks = containerStacks(screen);
        if (stacks.size() <= DungeonChestValuer.CLAIM_BUTTON_SLOT
                || !DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT))) {
            return;
        }
        ChestValue value = DungeonChestValuer.fromChestScreen(type, stacks);
        String floor = floorForChestScreen();
        pending = new PendingClaim(value, floor, screen.getMenu().containerId, System.currentTimeMillis(), source);
        pendingFailed = false;
    }

    // ---- screen hooks ----------------------------------------------------------------------------

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            try {
                render(containerScreen, graphics);
            } catch (Exception e) {
                LOGGER.error("[ChestProfit] Render failed", e);
            }
        });
        // killer560, 2026-09-20: "it will keep going until i press any key while it is going or it finishes."
        // ESC and the inventory key are let through as well as stopping, so the menu still closes the way it
        // normally would; everything else is swallowed, since a stray number key inside a Hypixel menu is a
        // real hotbar-swap packet.
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            try {
                // A hidden run takes no hotbar swap or drop either (each is a real click on it); Escape still closes.
                if (!AutoCroesusFeature.stopOnKeyPress()) {
                    return true;
                }
                return event.key() == InputConstants.KEY_ESCAPE;
            } catch (Exception e) {
                LOGGER.error("[Croesus] Key hook failed", e);
                return true;
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            try {
                if (AutoCroesusFeature.tryClickStartButton(event.x(), event.y())) {
                    return false;
                }
                if (!active()) {
                    return true;
                }
                Slot hovered = ((AbstractContainerScreenAccessor) containerScreen).killer560smod$getHoveredSlot();
                if (hovered == null) {
                    return true;
                }
                String title = titleOf(containerScreen);
                if (DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches()) {
                    String floor = floorFromCroesusHead(hovered.getItem());
                    rememberRunFloor(floor);
                    rememberRunHead(pageOf(title), hovered.index, hovered.getItem());
                } else if (hovered.index == DungeonChestValuer.CLAIM_BUTTON_SLOT && ChestType.fromName(title) != null) {
                    onClaimClick(containerScreen, "manual");
                }
            } catch (Exception e) {
                LOGGER.error("[ChestProfit] Click hook failed", e);
            }
            return true;
        });
    }

    private static void tick(Minecraft client) {
        resolvePending(client);
        if (!active()) {
            kind = Kind.NONE;
            return;
        }
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            setKind(Kind.NONE, "");
            return;
        }
        String title = titleOf(screen);
        if (DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches()) {
            runViewScreen = null;
            statesFor(screen);
            runViewChests = List.of();
            chestScreenValue = null;
            setKind(Kind.CROESUS_MENU, title);
            return;
        }
        if (DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches()) {
            runViewChests = valueRunView(screen);
            noteKeyWorth(runViewChests);
            chestMarks = chestMarks(runViewChests, openRun, screen.getMenu().slots.size());
            runViewScreen = screen;
            runViewFloor = floorFromRunViewTitle(title);
            rememberRunFloor(runViewFloor);
            chestScreenValue = null;
            setKind(Kind.RUN_VIEW, title);
            return;
        }
        runViewScreen = null;
        ChestType type = ChestType.fromName(title);
        if (type != null) {
            List<ItemStack> stacks = containerStacks(screen);
            if (stacks.size() > DungeonChestValuer.CLAIM_BUTTON_SLOT) {
                chestScreenValue = DungeonChestValuer.fromChestScreen(type, stacks);
                runViewChests = List.of();
                setKind(Kind.CHEST, title);
                return;
            }
        }
        runViewChests = List.of();
        chestScreenValue = null;
        setKind(Kind.NONE, "");
    }

    /**
     * killer560, 2026-09-20: "add a highlight for opened chests vs unopened chests by run as well. So before
     * I even open a run it tells me if i have a claimed chest there yet or not."
     * <p>
     * The head's own lore is the only thing the client is told: {@code "No chests opened yet!"} is present on
     * a completely untouched run and gone the moment anything in it is claimed (quoi AutoCroesus.kt reads the
     * same line). So this is a two-state answer - untouched vs. something claimed - and cannot tell a run with
     * one chest left from a fully emptied one.
     */
    /**
     * The run states of {@code screen}'s slots as they are right now: re-read whenever the screen or any run slot's stack
     * changed (a cheap identity fingerprint, so a frame costs 28 comparisons, not 28 lore scans). Empty for anything but
     * the Croesus menu and for a Croesus menu whose items have not arrived - never another screen's or page's states.
     */
    private static byte[] statesFor(AbstractContainerScreen<?> screen) {
        if (screen == null || !DungeonChestValuer.CROESUS_MENU_TITLE.matcher(titleOf(screen)).matches()) {
            return new byte[0];
        }
        List<Slot> slots = screen.getMenu().slots;
        int fp = 31 * CroesusRunMemory.version() + (int) AutoCroesusFeature.keyWorthFloor()
                + 7 * CroesusRunMemory.activeClaims(System.currentTimeMillis(), CLAIM_TRUST_MS);
        for (int slot : DungeonChestValuer.RUN_HEAD_SLOTS) {
            fp = 31 * fp + (slot < slots.size() ? System.identityHashCode(slots.get(slot).getItem()) : 0);
        }
        if (screen == runHeadScreen && !screen.getMenu().getCarried().isEmpty()) {
            // A click on a run head shows it on the cursor (its slot empty) until the server answers. That is not the
            // list changing: re-reading it then let the next run of the same party take the clicked run's place and
            // its memory (testkit 889). Keep what this screen showed before the click.
            return runHeadState;
        }
        if (screen != runHeadScreen || fp != runHeadFingerprint) {
            readRunHeads(screen);
            runHeadScreen = screen;
            runHeadFingerprint = fp;
        }
        return runHeadState;
    }

    private static void readRunHeads(AbstractContainerScreen<?> screen) {
        List<ItemStack> stacks = containerStacks(screen);
        int headPage = pageOf(titleOf(screen));
        byte[] state = new byte[stacks.size()];
        int unclaimed = 0;
        int claimed = 0;
        int needsKey = 0;
        int unknown = 0;
        long now = System.currentTimeMillis();
        List<CroesusRunMemory.Entry> entries = new ArrayList<>();
        List<ItemStack> entryHeads = new ArrayList<>();
        for (int i = 0; i < DungeonChestValuer.RUN_HEAD_SLOTS.length; i++) {
            int slot = DungeonChestValuer.RUN_HEAD_SLOTS[i];
            if (slot >= stacks.size()) {
                break;
            }
            ItemStack head = stacks.get(slot);
            if (head.isEmpty() || floorFromCroesusHead(head) == null) {
                continue;
            }
            List<String> lore = DungeonChestValuer.cleanLore(head);
            entries.add(CroesusRunMemory.entry(slot, (headPage - 1) * DungeonChestValuer.RUN_HEAD_SLOTS.length + i,
                    runSignature(head, lore), lore, now));
            entryHeads.add(head);
        }
        CroesusRunMemory.Run[] matched = CroesusRunMemory.match(entries);
        Map<Integer, CroesusRunMemory.Run> bySlot = new HashMap<>();
        Map<Integer, CroesusRunMemory.Entry> entries2 = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            int slot = entries.get(i).slot();
            ItemStack head = entryHeads.get(i);
            CroesusRunMemory.Run run = matched[i];
            if (run != null) {
                bySlot.put(slot, run);
            }
            entries2.put(slot, entries.get(i));
            byte st = headState(run, head);
            state[slot] = st;
            logTransition(run != null ? run.id : headPage + ":" + slot, st, run);
            if (st == RUN_UNCLAIMED) {
                unclaimed++;
            } else {
                claimed++;
                if (st == RUN_NEEDS_KEY) {
                    needsKey++;
                } else if (st == RUN_UNKNOWN) {
                    unknown++;
                }
            }
        }
        runHeadState = state;
        runHeadContainerId = screen.getMenu().containerId;
        runBySlot = bySlot;
        entryBySlot = entries2;
        if (stacks.stream().allMatch(ItemStack::isEmpty)) {
            return; // the page's items have not arrived: keep what this page held last time
        }
        Matcher m = MENU_PAGE.matcher(titleOf(screen));
        int page = m.matches() ? Integer.parseInt(m.group(1)) : 1;
        int pages = m.matches() ? Math.max(1, Integer.parseInt(m.group(2))) : 1;
        if (pages != pageCount) {
            PAGE_COUNTS.clear(); // runs expired or arrived across a page boundary: the old pages are not comparable
            pageCount = pages;
        }
        PAGE_COUNTS.put(page, new int[]{unclaimed, claimed, needsKey, unknown});
    }

    /** {unclaimed, claimed, pages seen, pages, worth a key, not looked at} across every Croesus page read this session. */
    public static int[] runTotals() {
        int u = 0;
        int c = 0;
        int k = 0;
        int q = 0;
        for (int[] v : PAGE_COUNTS.values()) {
            u += v[0];
            c += v[1];
            k += v[2];
            q += v[3];
        }
        return new int[]{u, c, PAGE_COUNTS.size(), pageCount, k, q};
    }

    /** A run's state from its head: the lore says unclaimed / done / which chest was opened; whether the rest is worth a
     *  key comes from the run's chests as last seen in its run view this session (by him or Auto Croesus). */
    private static byte headState(CroesusRunMemory.Run run, ItemStack head) {
        List<String> lore = DungeonChestValuer.cleanLore(head);
        if (lore.contains(RUN_DONE_LORE)) {
            return RUN_DONE;
        }
        boolean fresh = run != null && run.claimType != null && System.currentTimeMillis() - run.claimAtMs < CLAIM_TRUST_MS;
        if (fresh && run.claimKeyed) {
            return RUN_DONE; // a keyed claim just seen: the head will say "No more chests to open!" in a moment
        }
        String opened = null;
        if (lore.contains(DungeonChestValuer.RUN_UNOPENED_LORE)) {
            if (!fresh) {
                return RUN_UNCLAIMED;
            }
            opened = run.claimType; // the head lags a claim seen in the chest screen
        }
        for (String line : lore) {
            Matcher m = OPENED_CHEST.matcher(line);
            if (m.matches()) {
                ChestType t = ChestType.fromName(m.group(1).trim());
                opened = t == null ? null : t.name();
            }
        }
        Map<String, Long> chests = run == null ? null : run.chests;
        if (chests == null) {
            return RUN_UNKNOWN;
        }
        long floor = AutoCroesusFeature.keyWorthFloor();
        for (Map.Entry<String, Long> e : chests.entrySet()) {
            if (!e.getKey().equals(opened) && e.getValue() >= floor) {
                return RUN_NEEDS_KEY;
            }
        }
        return RUN_OPENED;
    }

    /** Which run a head is, stable while Croesus keeps it: its name (Master or not), floor and players. The expiry and
     *  "Completed: x ago" lines change with time, and "Opened Chest" changes with a claim, so neither is used. */
    static String runSignature(ItemStack head, List<String> lore) {
        StringBuilder sb = new StringBuilder(DungeonChestValuer.strip(head.getHoverName().getString()).trim());
        boolean players = false;
        for (String line : lore) {
            if (line.startsWith("Floor ")) {
                sb.append('|').append(line);
            } else if (line.equals("Players:")) {
                players = true;
            } else if (players && line.isBlank()) {
                players = false;
            } else if (players) {
                sb.append('|').append(line.trim());
            }
        }
        return sb.toString();
    }

    static int pageOf(String title) {
        Matcher m = MENU_PAGE.matcher(title == null ? "" : title);
        return m.matches() ? Integer.parseInt(m.group(1)) : 1;
    }

    /**
     * The head the run view about to open belongs to (his click, or Auto Croesus's): the remembered run the last read of
     * that page matched to the slot, or a new one. Runs are no longer known by their slot (killer560, 2026-10-12: "if it
     * moves it still needs to be tracked") but by party, floor and expiry instant - see {@link CroesusRunMemory}.
     */
    public static void rememberRunHead(int page, int slot, ItemStack head) {
        if (head == null || head.isEmpty() || floorFromCroesusHead(head) == null) {
            return;
        }
        CroesusRunMemory.Run run = runBySlot.get(slot);
        CroesusRunMemory.Entry e = entryBySlot.get(slot);
        List<String> lore = DungeonChestValuer.cleanLore(head);
        String sig = runSignature(head, lore);
        if (e == null || !e.sig().equals(sig)) {
            int index = java.util.Arrays.stream(DungeonChestValuer.RUN_HEAD_SLOTS).boxed().toList().indexOf(slot);
            e = CroesusRunMemory.entry(slot, (page - 1) * DungeonChestValuer.RUN_HEAD_SLOTS.length + Math.max(0, index),
                    sig, lore, System.currentTimeMillis());
            run = null;
        }
        openRun = run != null ? run : CroesusRunMemory.create(e);
    }

    /** In a reward chest screen, right before its claim is sent: this run's chest of that type is taken (with a key if
     *  the button listed one). Recorded whatever the logger setting. */
    private static void noteClaim(AbstractContainerScreen<?> screen) {
        CroesusRunMemory.Run run = openRun;
        ChestType type = ChestType.fromName(titleOf(screen));
        if (run == null || type == null) {
            return;
        }
        List<ItemStack> stacks = containerStacks(screen);
        if (stacks.size() <= DungeonChestValuer.CLAIM_BUTTON_SLOT
                || !DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT))) {
            return;
        }
        boolean keyed = DungeonChestValuer.cleanLore(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT)).contains("Dungeon Chest Key");
        CroesusRunMemory.noteClaim(run, type.name(), keyed, System.currentTimeMillis());
        LOGGER.info("[Croesus] Claim seen in the {} chest of run {}{}", type.display, CroesusRunMemory.describe(run),
                keyed ? " (with a key)" : "");
    }

    /** INFO when a run's state changes (at most 20 lines a minute), so a flip in his game shows in his log. */
    private static void logTransition(String key, byte st, CroesusRunMemory.Run run) {
        Byte was = LOGGED_STATE.put(key, st);
        if (was == null || was == st) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - logWindowStartMs > 60_000L) {
            logWindowStartMs = now;
            logLinesInWindow = 0;
        }
        if (++logLinesInWindow <= 20) {
            LOGGER.info("[Croesus] Run {} state {} -> {} (view known: {}, claim seen: {})", key, stateName(was),
                    stateName(st), run != null && run.chests != null, run != null && run.claimType != null);
        }
    }

    private static String stateName(byte st) {
        return switch (st) {
            case RUN_UNCLAIMED -> "unclaimed";
            case RUN_NEEDS_KEY -> "needs-key";
            case RUN_OPENED -> "opened";
            case RUN_DONE -> "key-used";
            case RUN_UNKNOWN -> "not-looked-at";
            default -> "none";
        };
    }

    /**
     * In a run view: each chest not opened yet, with what it would make after a key (a chest that lists the key already
     * has it in its profit - DungeonChestValuer.profitWithKey), recorded for the run's head. The head's "Opened Chest: X"
     * then says which one was taken, and the run needs a key while any other clears Auto Croesus's key floor
     * ({@code AutoCroesusFeature.keyWorthFloor()}, read when drawn, so a changed setting applies at once).
     */
    private static void noteKeyWorth(List<ChestValue> chests) {
        CroesusRunMemory.Run run = openRun;
        if (run == null || chests.isEmpty() || !DungeonChestValuer.pricesReady()) {
            return;
        }
        Map<String, Long> left = new java.util.HashMap<>();
        for (ChestValue c : chests) {
            if (!c.opened() && c.slot() >= 0) {
                left.merge(c.type().name(), DungeonChestValuer.profitWithKey(c), Math::max);
            }
        }
        CroesusRunMemory.noteChests(run, left);
    }

    // ---- a run's chest view: the free claim green, the key chest orange -------------------------------------------

    static final byte CHEST_FREE = 1;
    static final byte CHEST_KEY = 2;
    /** The run view the marks below were worked out for (null when no run view is open). */
    private static volatile AbstractContainerScreen<?> runViewScreen = null;
    /** Per menu slot of {@link #runViewScreen}: 0, {@link #CHEST_FREE} or {@link #CHEST_KEY}. */
    private static volatile byte[] chestMarks = new byte[0];

    /**
     * killer560, 2026-10-13: "in a chest menu if something is worth coming back and buying with a key, highlight it
     * yellow or orange. If there are multiple only highlight the best one." Nothing claimed yet: GREEN on the most
     * profitable chest at its normal cost (the free claim) when that profit is positive, ORANGE on the best of the rest whose profit after a key clears
     * Auto Croesus's key floor. One chest claimed without a key: no green, the same orange rule over what is left. A key
     * used (two chests opened, or a keyed claim just seen): nothing. The orange rule is exactly {@link #headState}'s
     * "needs a key" (same profitWithKey, same floor, same claimed chest excluded), so the list and this view agree.
     */
    static byte[] chestMarks(List<ChestValue> chests, CroesusRunMemory.Run run, int size) {
        byte[] out = new byte[size];
        String claimed = null;
        int opened = 0;
        for (ChestValue c : chests) {
            if (c.opened()) {
                opened++;
                claimed = c.type().name();
            }
        }
        boolean fresh = run != null && run.claimType != null && System.currentTimeMillis() - run.claimAtMs < CLAIM_TRUST_MS;
        if (opened >= 2 || (fresh && run.claimKeyed) || !DungeonChestValuer.pricesReady()) {
            return out;
        }
        if (claimed == null && fresh) {
            claimed = run.claimType; // the view lags a claim seen in the chest screen
        }
        ChestValue free = null;
        if (claimed == null) {
            for (ChestValue c : chests) {
                if (!c.opened() && c.slot() >= 0 && (free == null || c.profit() > free.profit())) {
                    free = c;
                }
            }
        }
        long floor = AutoCroesusFeature.keyWorthFloor();
        ChestValue key = null;
        long keyProfit = Long.MIN_VALUE;
        for (ChestValue c : chests) {
            if (c.opened() || c.slot() < 0 || c == free || c.type().name().equals(claimed)) {
                continue;
            }
            long p = DungeonChestValuer.profitWithKey(c);
            if (p >= floor && p > keyProfit) {
                key = c;
                keyProfit = p;
            }
        }
        // Green only on a free claim that makes money (killer560, 2026-10-13). The best chest stays out of the orange
        // pick either way, so a losing best chest changes nothing about the orange.
        if (free != null && free.profit() > 0 && free.slot() < size) {
            out[free.slot()] = CHEST_FREE;
        }
        if (key != null && key.slot() < size) {
            out[key.slot()] = CHEST_KEY;
        }
        return out;
    }

    /** For the testkit: the chest-view mark of every slot of the run view last read (0 none, 1 free, 2 key). */
    public static byte[] chestMarksForTest() {
        return runViewScreen == null ? new byte[0] : chestMarks.clone();
    }

    private static void drawChestSlot(GuiGraphicsExtractor graphics, Slot slot) {
        byte[] marks = chestMarks;
        if (slot.index < 0 || slot.index >= marks.length || marks[slot.index] == 0) {
            return;
        }
        CroesusConfig cfg = CroesusConfig.getInstance();
        boolean free = marks[slot.index] == CHEST_FREE;
        if (free ? !cfg.isHighlightFreeChest() : !cfg.isHighlightKeyChest()) {
            return;
        }
        int rgb = free ? cfg.getChestFreeColor() : cfg.getChestKeyColor();
        int x = slot.x;
        int y = slot.y;
        graphics.fill(x, y, x + 16, y + 16, (CHEST_MARK_FILL_ALPHA << 24) | rgb);
        insetBorder(graphics, x, y, 0xFF000000 | rgb);
        insetBorder(graphics, x + 1, y + 1, 14, 0xFF000000 | rgb);
    }

    /** For the testkit: the RUN_* state of every slot of the Croesus menu last read. */
    public static byte[] runStatesForTest() {
        return runHeadState.clone();
    }

    /** For the testkit: forget every "worth a key" answer. */
    public static void clearKeyWorthForTest() {
        CroesusRunMemory.clearForTest();
        LOGGED_STATE.clear();
        openRun = null;
        runBySlot = Map.of();
        entryBySlot = Map.of();
    }

    /** For the testkit: drop what is in memory and read the run file again, as a restart does. */
    public static void reloadRunMemoryForTest() {
        CroesusRunMemory.load();
        LOGGED_STATE.clear();
        openRun = null;
        runBySlot = Map.of();
        entryBySlot = Map.of();
        runHeadScreen = null;
    }

    // ---- run heads drawn by croesus/mixin/CroesusSlotMixin ------------------------------------------------------

    /** The RUN_* state of a slot of {@code screen} (only the Croesus menu it was read from; RUN_NONE otherwise). */
    private static int runState(AbstractContainerScreen<?> screen, Slot slot) {
        byte[] state = statesFor(screen);
        return slot.index >= 0 && slot.index < state.length ? state[slot.index] : 0;
    }

    /** For the testkit: the RUN_* state the menu would draw for {@code slot} of {@code screen} in this frame. */
    public static int runStateForTest(AbstractContainerScreen<?> screen, int slot) {
        return slot >= 0 && slot < screen.getMenu().slots.size() ? runState(screen, screen.getMenu().slots.get(slot)) : 0;
    }

    /** Highlight Runs, behind the item: called at the one {@code Slot.isFake()} call in {@code extractSlot}, where the
     *  pose is the container's (slot-local coordinates), so the item, the hover highlight and the tooltip all cover it. */
    public static void drawRunSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        CroesusConfig cfg = CroesusConfig.getInstance();
        if (!cfg.isChestProfitEnabled()) {
            return;
        }
        if (screen == runViewScreen) {
            drawChestSlot(graphics, slot); // a run's chest view: behind the item, under the tooltip, as in the list
            return;
        }
        if (!cfg.isHighlightRuns()) {
            return;
        }
        int state = runState(screen, slot);
        if (state == 0) {
            return;
        }
        int x = slot.x;
        int y = slot.y;
        switch (state) {
            case RUN_UNCLAIMED -> {
                graphics.fill(x, y, x + 16, y + 16, (RUN_UNCLAIMED_FILL_ALPHA << 24) | cfg.getRunUnclaimedColor());
                insetBorder(graphics, x, y, (RUN_UNCLAIMED_BORDER_ALPHA << 24) | cfg.getRunUnclaimedColor());
                insetBorder(graphics, x + 1, y + 1, 14, (RUN_UNCLAIMED_BORDER_ALPHA << 24) | cfg.getRunUnclaimedColor());
            }
            case RUN_NEEDS_KEY -> {
                // Hue AND treatment (2026-10-12, "these colors are hard to actually tell if they are different"):
                // a stronger fill and a 2 px border, plus the key mark drawn over the item (drawRunGlyph).
                graphics.fill(x, y, x + 16, y + 16, (RUN_KEY_FILL_ALPHA << 24) | cfg.getRunKeyColor());
                insetBorder(graphics, x, y, (RUN_KEY_BORDER_ALPHA << 24) | cfg.getRunKeyColor());
                insetBorder(graphics, x + 1, y + 1, 14, (RUN_KEY_BORDER_ALPHA << 24) | cfg.getRunKeyColor());
            }
            case RUN_UNKNOWN -> insetBorder(graphics, x, y, (RUN_UNKNOWN_OUTLINE_ALPHA << 24) | cfg.getRunUnknownColor());
            case RUN_OPENED -> graphics.fill(x, y, x + 16, y + 16, (RUN_CLAIMED_FILL_ALPHA << 24) | cfg.getRunOpenedColor());
            default -> graphics.fill(x, y, x + 16, y + 16, (RUN_USED_FILL_ALPHA << 24) | cfg.getRunClaimedColor());
        }
    }

    /** A 1 px border inside the 16x16 slot, so neighbouring borders never touch. */
    private static void insetBorder(GuiGraphicsExtractor graphics, int x, int y, int argb) {
        insetBorder(graphics, x, y, 16, argb);
    }

    private static void insetBorder(GuiGraphicsExtractor graphics, int x, int y, int size, int argb) {
        graphics.fill(x, y, x + size, y + 1, argb);
        graphics.fill(x, y + size - 1, x + size, y + size, argb);
        graphics.fill(x, y + 1, x + 1, y + size - 1, argb);
        graphics.fill(x + size - 1, y + 1, x + size, y + size - 1, argb);
    }

    /** 5x5 marks, drawn on a dark plate in the slot's top-right corner, so a state reads without its colour too. */
    private static final String[] KEY_MARK = {".....", "XX...", "XXXXX", "...XX", "....."};

    /**
     * The corner mark over the item (called at the end of {@code extractSlot}, after the item, still under the tooltip):
     * a key on a run worth a key. (The "?" on a run not looked at was removed, killer560 2026-10-13: "please remove that
     * question mark symbol I don't like it".)
     */
    public static void drawRunGlyph(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        CroesusConfig cfg = CroesusConfig.getInstance();
        if (!cfg.isChestProfitEnabled() || !cfg.isHighlightRuns()) {
            return;
        }
        int state = runState(screen, slot);
        if (state != RUN_NEEDS_KEY) {
            return;
        }
        String[] mark = KEY_MARK;
        int argb = 0xFF000000 | cfg.getRunKeyColor();
        int x0 = slot.x + 11;
        int y0 = slot.y;
        graphics.fill(x0 - 1, y0, x0 + 5, y0 + 6, 0xFF101010);
        for (int r = 0; r < 5; r++) {
            for (int col = 0; col < 5; col++) {
                if (mark[r].charAt(col) == 'X') {
                    graphics.fill(x0 + col, y0 + r, x0 + col + 1, y0 + r + 1, argb);
                }
            }
        }
    }

    /**
     * Hide Opened Runs: a run with nothing left to do (opened with nothing worth a key, or done with a key) is not drawn.
     * Only its texture: killer560, 2026-10-12, "I should still be able to click where there is no run to open it, just
     * the texture is missing" - so the click, a number key, the hover square and the tooltip all still work there.
     * A run that needs a key, or whose chests were not looked at yet, is never hidden.
     */
    public static boolean hidesSlot(AbstractContainerScreen<?> screen, Slot slot) {
        if (slot == null || !CroesusConfig.getInstance().isHideOpenedRuns()) {
            return false;
        }
        int st = runState(screen, slot);
        return st == RUN_OPENED || st == RUN_DONE;
    }

    private static void setKind(Kind newKind, String title) {
        kind = newKind;
    }

    private static void resolvePending(Minecraft client) {
        PendingClaim claim = pending;
        if (claim == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - claim.clickedAtMs();
        if (pendingFailed) {
            LOGGER.info("[Croesus] Claim of {} chest rejected by server chat - not logged", claim.value().type().display);
            pending = null;
            pendingFailed = false;
            return;
        }
        if (elapsed < CONFIRM_MIN_MS) {
            return;
        }
        boolean sameScreen = McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                && s.getMenu().containerId == claim.containerId()
                && ChestType.fromName(titleOf(s)) == claim.value().type();
        boolean stillClaimable = false;
        if (sameScreen) {
            List<ItemStack> stacks = containerStacks((AbstractContainerScreen<?>) McCompat.screen(client));
            stillClaimable = stacks.size() > DungeonChestValuer.CLAIM_BUTTON_SLOT
                    && DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT));
        }
        if (!sameScreen || !stillClaimable) {
            pending = null;
            commit(claim);
            return;
        }
        if (elapsed > CONFIRM_GIVE_UP_MS) {
            LOGGER.info("[Croesus] Claim of {} chest never confirmed (screen unchanged after {}ms) - not logged",
                    claim.value().type().display, elapsed);
            pending = null;
        }
    }

    private static void commit(PendingClaim claim) {
        ChestValue v = claim.value();
        CroesusProfitLog.record(claim.floor(), v, claim.source());
        if (!CroesusConfig.getInstance().isLoggerChatSummary()) {
            return;
        }
        Map<String, CroesusProfitLog.Totals> session = CroesusProfitLog.session();
        Map<String, CroesusProfitLog.Totals> allTime = CroesusProfitLog.allTime();
        CroesusProfitLog.Totals s = session.get(CroesusProfitLog.ALL);
        CroesusProfitLog.Totals a = allTime.get(CroesusProfitLog.ALL);
        ModChat.send("Croesus",
                ModChat.text("Claimed "),
                ModChat.value(v.type().display + " Chest"),
                ModChat.dim(" (" + claim.floor() + ") "),
                profitComponent(v.profit()),
                ModChat.dim(" | session "),
                profitComponent(s == null ? 0 : s.profit),
                ModChat.dim(" | all-time "),
                profitComponent(a == null ? 0 : a.profit),
                v.unpricedCount() > 0 ? ModChat.dim(" (" + v.unpricedCount() + " unpriced)") : ModChat.dim(""));
    }

    private static net.minecraft.network.chat.MutableComponent profitComponent(long profit) {
        String text = (profit >= 0 ? "+" : "") + DungeonChestValuer.formatCoins(profit);
        return profit >= 0 ? ModChat.good(text) : ModChat.bad(text);
    }

    // ---- rendering -------------------------------------------------------------------------------

    private record Line(String text, int color, String right, int rightColor, Component rich) {
        Line(String text, int color, String right, int rightColor) {
            this(text, color, right, rightColor, null);
        }
    }

    /** The colour legend: one line, each state's colour swatch (or mark) and a word. */
    private static Line legendLine(CroesusConfig cfg) {
        net.minecraft.network.chat.MutableComponent c = Component.empty();
        legendPart(c, "\u25A0", cfg.getRunUnclaimedColor(), "new  ");
        legendPart(c, "\u25A0", cfg.getRunKeyColor(), "key  ");
        legendPart(c, "□", cfg.getRunUnknownColor(), "unchecked  ");
        legendPart(c, "\u25A0", cfg.getRunOpenedColor(), "done  ");
        // The key-used tint is near black: drawn on the dark panel as a lighter grey of the same hue.
        legendPart(c, "\u25A0", lighten(cfg.getRunClaimedColor()), "used");
        return new Line(c.getString(), 0xFF000000 | ModChat.DIM, null, 0, c);
    }

    private static void legendPart(net.minecraft.network.chat.MutableComponent c, String mark, int rgb, String word) {
        c.append(Component.literal(mark).withStyle(net.minecraft.network.chat.Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb & 0xFFFFFF))));
        c.append(Component.literal(word).withStyle(net.minecraft.network.chat.Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(ModChat.DIM))));
    }

    private static int lighten(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        if (r + g + b > 3 * 90) {
            return rgb;
        }
        return ((r + 96) << 16) | ((g + 96) << 8) | (b + 96);
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (McCompat.screen(Minecraft.getInstance()) != screen) {
            return;
        }
        // Drawn from the container screen's own pass, so it sits over the menu rather than under its
        // darkened background - see AutoCroesusFeature.renderStartButton.
        AutoCroesusFeature.renderStartButton(graphics);
        if (!CroesusConfig.getInstance().isChestProfitEnabled()) {
            return;
        }
        String title = titleOf(screen);
        ContainerPos pos = positions(screen);
        List<Line> lines = new ArrayList<>();
        if (DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches()) {
            // The run highlight itself is drawn per slot, behind each item (drawRunSlot). This is only the info box -
            // drawn once THIS screen's items are in, so it never shows the totals of before the page loaded.
            if (!CroesusConfig.getInstance().isHighlightRuns()) {
                return;
            }
            statesFor(screen);
            if (containerStacks(screen).stream().allMatch(ItemStack::isEmpty)) {
                return;
            }
            int[] t = runTotals();
            boolean partial = t[2] < t[3];
            String plus = partial ? "+" : "";
            lines.add(new Line("Croesus", 0xFF000000 | ModChat.ORANGE, null, 0));
            lines.add(new Line("Unclaimed runs", 0xFF000000 | ModChat.TEXT, t[0] + plus + " / " + MAX_STORED_RUNS,
                    0xFF000000 | (t[0] > 0 ? ModChat.GOOD : ModChat.DIM)));
            lines.add(new Line("Already claimed", 0xFF000000 | ModChat.TEXT, t[1] + plus, 0xFF000000 | ModChat.DIM));
            if (t[4] > 0 || t[5] > 0) {
                lines.add(new Line("Worth a key", 0xFF000000 | ModChat.TEXT, t[4] + plus + (t[5] > 0 ? " (" + t[5] + " not looked at)" : ""),
                        0xFF000000 | ModChat.LIGHT_ORANGE));
            }
            if (partial) {
                lines.add(new Line("Pages seen " + t[2] + " of " + t[3], 0xFF000000 | ModChat.DIM, null, 0));
            }
            if (CroesusConfig.getInstance().isShowRunLegend()) {
                lines.add(legendLine(CroesusConfig.getInstance()));
            }
            drawPanel(screen, graphics, pos, lines);
            return;
        }
        if (kind == Kind.RUN_VIEW && DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches()) {
            lines.add(new Line("Chest Profit" + (runViewFloor != null ? " - " + runViewFloor : ""), 0xFF000000 | ModChat.ORANGE, null, 0));
            if (!DungeonChestValuer.pricesReady()) {
                lines.add(new Line("Fetching prices...", 0xFF000000 | ModChat.DIM, null, 0));
            }
            List<ChestValue> chests = runViewChests;
            byte[] marks = screen == runViewScreen ? chestMarks : new byte[0];
            ChestValue keyChest = null;
            for (ChestValue c : chests) {
                String label = c.type().display + (c.opened() ? " (opened)" : "") + (c.unpricedCount() > 0 ? " *" : "");
                int labelColor = c.opened() ? 0xFF000000 | ModChat.DIM : c.type().color;
                lines.add(new Line(label, labelColor, signed(c.profit()), c.opened() ? 0xFF000000 | ModChat.DIM : profitColor(c.profit())));
                if (c.slot() >= 0 && c.slot() < marks.length && marks[c.slot()] == CHEST_KEY) {
                    keyChest = c;
                }
            }
            if (keyChest != null && CroesusConfig.getInstance().isHighlightKeyChest()) {
                long withKey = DungeonChestValuer.profitWithKey(keyChest);
                lines.add(new Line("Key: " + keyChest.type().display, 0xFF000000 | ModChat.TEXT, signed(withKey), profitColor(withKey)));
            }
            if (chests.stream().anyMatch(c -> c.unpricedCount() > 0)) {
                lines.add(new Line("* has unpriced items", 0xFF000000 | ModChat.DIM, null, 0));
            }
        } else if (kind == Kind.CHEST && chestScreenValue != null && ChestType.fromName(title) == chestScreenValue.type()) {
            ChestValue v = chestScreenValue;
            lines.add(new Line(v.type().display + " Chest", 0xFF000000 | ModChat.ORANGE, null, 0));
            if (!DungeonChestValuer.pricesReady()) {
                lines.add(new Line("Fetching prices...", 0xFF000000 | ModChat.DIM, null, 0));
            }
            for (PricedItem item : v.items()) {
                String right = item.excluded() ? "excluded" : item.unitPrice() == null ? "?" : DungeonChestValuer.formatCoins(item.total());
                lines.add(new Line(item.name(), 0xFF000000 | ModChat.TEXT, right,
                        0xFF000000 | (item.unitPrice() == null ? ModChat.DIM : ModChat.LIGHT_ORANGE)));
            }
            lines.add(new Line("Cost", 0xFF000000 | ModChat.TEXT, v.cost() == 0 ? "FREE" : "-" + DungeonChestValuer.formatCoins(v.cost()),
                    0xFF000000 | ModChat.LIGHT_ORANGE));
            lines.add(new Line("Profit", 0xFF000000 | ModChat.ORANGE, signed(v.profit()), profitColor(v.profit())));
        } else {
            return;
        }
        drawPanel(screen, graphics, pos, lines);
    }

    private static String signed(long profit) {
        return (profit >= 0 ? "+" : "") + DungeonChestValuer.formatCoins(profit);
    }

    private static int profitColor(long profit) {
        return 0xFF000000 | (profit >= 0 ? ModChat.GOOD : ModChat.BAD);
    }

    private record ContainerPos(int left, int top, int right) {
    }

    private static ContainerPos positions(AbstractContainerScreen<?> screen) {
        com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor acc =
                (com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos();
        int top = acc.killer560smod$getTopPos();
        // leftPos = (width - imageWidth) / 2, so the container's right edge mirrors its left edge.
        int right = screen.width - left;
        return new ContainerPos(left, top, right);
    }

    private static void drawPanel(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
                                  ContainerPos pos, List<Line> lines) {
        int panelW = panelWidth(lines);
        int panelH = panelHeight(lines);
        // Its own spot beside the container, unless he has placed it in Edit HUD (ContainerOverlayElement).
        int x = pos.right() + 6;
        if (x + panelW > screen.width - 2) {
            x = Math.max(2, pos.left() - panelW - 6);
        }
        int y = Math.max(2, pos.top());
        lastLines = List.copyOf(lines);
        if (panelElement == null) {
            drawLines(graphics, x, y, lines);
            return;
        }
        panelElement.draw(graphics, x, y, panelW, panelH, (g, px, py) -> drawLines(g, px, py, lines));
    }

    private static final int PANEL_GAP = 10;
    private static final int PANEL_PADDING = 4;

    private static int panelWidth(List<Line> lines) {
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (Line line : lines) {
            int w = line.rich() != null ? font.width(line.rich())
                    : font.width(line.text()) + (line.right() != null ? PANEL_GAP + font.width(line.right()) : 0);
            width = Math.max(width, w);
        }
        return width + PANEL_PADDING * 2;
    }

    private static int panelHeight(List<Line> lines) {
        return lines.size() * 10 + PANEL_PADDING * 2 - 1;
    }

    private static void drawLines(GuiGraphicsExtractor graphics, int x, int y, List<Line> lines) {
        Font font = Minecraft.getInstance().font;
        int panelW = panelWidth(lines);
        int panelH = panelHeight(lines);
        graphics.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        graphics.outline(x, y, panelW, panelH, PANEL_BORDER);
        int ty = y + PANEL_PADDING;
        for (Line line : lines) {
            if (line.rich() != null) {
                graphics.text(font, line.rich(), x + PANEL_PADDING, ty, line.color(), true);
                ty += 10;
                continue;
            }
            graphics.text(font, line.text(), x + PANEL_PADDING, ty, line.color(), true);
            if (line.right() != null) {
                graphics.text(font, line.right(), x + panelW - PANEL_PADDING - font.width(line.right()), ty,
                        line.rightColor(), true);
            }
            ty += 10;
        }
    }
}
