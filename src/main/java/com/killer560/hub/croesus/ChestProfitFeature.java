package com.killer560.hub.croesus;

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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-croesus");

    private static final long CONFIRM_MIN_MS = 1000L;
    private static final long CONFIRM_GIVE_UP_MS = 6000L;
    private static final long RUN_FLOOR_MEMORY_MS = 5 * 60 * 1000L;

    private static final int PANEL_BG = 0xD0141008;
    private static final int PANEL_BORDER = 0xFF663D1A;
    private static final int HIGHLIGHT_BEST_FILL = 0x7000FF00;
    private static final int HIGHLIGHT_BEST_BORDER = 0xFF00FF00;
    private static final int HIGHLIGHT_SECOND_FILL = 0x40FFA040;
    private static final int HIGHLIGHT_SECOND_BORDER = 0xFFFFA040;
    /** Run heads in the Croesus menu: green = nothing claimed from that run yet, red = something already was. */
    private static final int RUN_UNCLAIMED_FILL = 0x5000FF00;
    private static final int RUN_UNCLAIMED_BORDER = 0xFF00FF00;
    private static final int RUN_CLAIMED_FILL = 0x50FF5555;

    private enum Kind { NONE, CROESUS_MENU, RUN_VIEW, CHEST }

    private record PendingClaim(ChestValue value, String floor, int containerId, long clickedAtMs, String source) {
    }

    private static Kind kind = Kind.NONE;
    /** Per run-head slot in the Croesus menu: 0 = not a run, 1 = nothing claimed yet, 2 = already claimed.
     *  Built once per tick (not per frame) - the fps pass found lore scans in render paths. */
    private static byte[] runHeadState = new byte[0];
    private static int unclaimedRuns = 0;
    private static int claimedRuns = 0;
    private static List<ChestValue> runViewChests = List.of();
    private static ChestValue chestScreenValue = null;
    private static String runViewFloor = null;
    private static String lastKnownRunFloor = null;
    private static long lastKnownRunFloorAtMs = 0L;
    private static PendingClaim pending = null;
    private static volatile boolean pendingFailed = false;
    private static String lastLoggedKindKey = "";

    private ChestProfitFeature() {
    }

    public static void register() {
        CroesusProfitLog.load();
        ScreenEvents.AFTER_INIT.register(ChestProfitFeature::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(ChestProfitFeature::tick);
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            kind = Kind.NONE;
            runViewChests = List.of();
            chestScreenValue = null;
            runHeadState = new byte[0];
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
        return cfg.isChestProfitEnabled() || cfg.isLoggerEnabled() || cfg.isAutoCroesusEnabled();
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
        LOGGER.info("[Croesus] Claim click on {} {} chest (profit {}, {}) - awaiting confirmation",
                floor, type.display, value.profit(), source);
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
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            setKind(Kind.NONE, "");
            return;
        }
        String title = titleOf(screen);
        if (DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches()) {
            readRunHeads(screen);
            runViewChests = List.of();
            chestScreenValue = null;
            setKind(Kind.CROESUS_MENU, title);
            return;
        }
        if (DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches()) {
            runViewChests = valueRunView(screen);
            runViewFloor = floorFromRunViewTitle(title);
            rememberRunFloor(runViewFloor);
            chestScreenValue = null;
            setKind(Kind.RUN_VIEW, title);
            return;
        }
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
    private static void readRunHeads(AbstractContainerScreen<?> screen) {
        List<ItemStack> stacks = containerStacks(screen);
        byte[] state = new byte[stacks.size()];
        int unclaimed = 0;
        int claimed = 0;
        for (int slot : DungeonChestValuer.RUN_HEAD_SLOTS) {
            if (slot >= stacks.size()) {
                break;
            }
            ItemStack head = stacks.get(slot);
            if (head.isEmpty() || floorFromCroesusHead(head) == null) {
                continue;
            }
            if (DungeonChestValuer.cleanLore(head).contains(DungeonChestValuer.RUN_UNOPENED_LORE)) {
                state[slot] = 1;
                unclaimed++;
            } else {
                state[slot] = 2;
                claimed++;
            }
        }
        runHeadState = state;
        unclaimedRuns = unclaimed;
        claimedRuns = claimed;
    }

    private static void setKind(Kind newKind, String title) {
        kind = newKind;
        String key = newKind + "|" + title;
        if (!key.equals(lastLoggedKindKey)) {
            lastLoggedKindKey = key;
            if (newKind != Kind.NONE) {
                LOGGER.info("[ChestProfit] Valuing {} screen \"{}\" (prices ready={})", newKind, title, DungeonChestValuer.pricesReady());
            }
        }
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
        boolean sameScreen = client.screen instanceof AbstractContainerScreen<?> s
                && s.getMenu().containerId == claim.containerId()
                && ChestType.fromName(titleOf(s)) == claim.value().type();
        boolean stillClaimable = false;
        if (sameScreen) {
            List<ItemStack> stacks = containerStacks((AbstractContainerScreen<?>) client.screen);
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

    private record Line(String text, int color, String right, int rightColor) {
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (Minecraft.getInstance().screen != screen) {
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
        if (kind == Kind.CROESUS_MENU && DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches()) {
            if (!CroesusConfig.getInstance().isHighlightRuns()) {
                return;
            }
            for (int slot = 0; slot < runHeadState.length && slot < screen.getMenu().slots.size(); slot++) {
                if (runHeadState[slot] == 0) {
                    continue;
                }
                Slot s = screen.getMenu().slots.get(slot);
                int x = pos.left() + s.x;
                int y = pos.top() + s.y;
                if (runHeadState[slot] == 1) {
                    graphics.fill(x, y, x + 16, y + 16, RUN_UNCLAIMED_FILL);
                    graphics.outline(x - 1, y - 1, 18, 18, RUN_UNCLAIMED_BORDER);
                } else {
                    graphics.fill(x, y, x + 16, y + 16, RUN_CLAIMED_FILL);
                }
            }
            lines.add(new Line("Croesus", 0xFF000000 | ModChat.ORANGE, null, 0));
            lines.add(new Line("Unclaimed runs", 0xFF000000 | ModChat.TEXT, String.valueOf(unclaimedRuns),
                    0xFF000000 | (unclaimedRuns > 0 ? ModChat.GOOD : ModChat.DIM)));
            lines.add(new Line("Already claimed", 0xFF000000 | ModChat.TEXT, String.valueOf(claimedRuns),
                    0xFF000000 | ModChat.DIM));
            drawPanel(screen, graphics, pos, lines);
            return;
        }
        if (kind == Kind.RUN_VIEW && DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches()) {
            lines.add(new Line("Chest Profit" + (runViewFloor != null ? " - " + runViewFloor : ""), 0xFF000000 | ModChat.ORANGE, null, 0));
            if (!DungeonChestValuer.pricesReady()) {
                lines.add(new Line("Fetching prices...", 0xFF000000 | ModChat.DIM, null, 0));
            }
            List<ChestValue> chests = runViewChests;
            ChestValue best = null;
            ChestValue second = null;
            for (ChestValue c : chests) {
                if (c.opened()) {
                    continue;
                }
                if (best == null) {
                    best = c;
                } else if (second == null) {
                    second = c;
                }
            }
            // killer560, 2026-09-20: "highlight ... the second best if it makes profit assuming i use a
            // dungeon chest key on it". A chest whose lore already lists "Dungeon Chest Key" has that key
            // priced into its own profit; one that doesn't has the key subtracted here.
            boolean keyHighlight = CroesusConfig.getInstance().isHighlightSecondWithKey();
            long secondWithKey = second == null ? 0L : DungeonChestValuer.profitWithKey(second);
            boolean secondPaysForKey = second != null && secondWithKey > 0;
            for (ChestValue c : chests) {
                String label = c.type().display + (c.opened() ? " (opened)" : "") + (c.unpricedCount() > 0 ? " *" : "");
                int labelColor = c.opened() ? 0xFF000000 | ModChat.DIM : c.type().color;
                lines.add(new Line(label, labelColor, signed(c.profit()), c.opened() ? 0xFF000000 | ModChat.DIM : profitColor(c.profit())));
            }
            if (keyHighlight && second != null && !second.requiresKey()) {
                lines.add(new Line("2nd w/ key", 0xFF000000 | ModChat.TEXT, signed(secondWithKey), profitColor(secondWithKey)));
            }
            if (CroesusConfig.getInstance().isHighlightBest()) {
                for (ChestValue c : chests) {
                    if (c.slot() < 0 || c.opened()) {
                        continue;
                    }
                    Slot slot = screen.getMenu().slots.get(c.slot());
                    int x = pos.left() + slot.x;
                    int y = pos.top() + slot.y;
                    if (c == best) {
                        graphics.fill(x, y, x + 16, y + 16, HIGHLIGHT_BEST_FILL);
                        graphics.outline(x - 1, y - 1, 18, 18, HIGHLIGHT_BEST_BORDER);
                    } else if (c == second && keyHighlight && secondPaysForKey) {
                        graphics.fill(x, y, x + 16, y + 16, HIGHLIGHT_SECOND_FILL);
                        graphics.outline(x - 1, y - 1, 18, 18, HIGHLIGHT_SECOND_BORDER);
                    } else if (c == second && !keyHighlight && c.profit() > 0) {
                        graphics.fill(x, y, x + 16, y + 16, HIGHLIGHT_SECOND_FILL);
                    }
                }
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
        Font font = Minecraft.getInstance().font;
        int gap = 10;
        int width = 0;
        for (Line line : lines) {
            int w = font.width(line.text()) + (line.right() != null ? gap + font.width(line.right()) : 0);
            width = Math.max(width, w);
        }
        int padding = 4;
        int panelW = width + padding * 2;
        int panelH = lines.size() * 10 + padding * 2 - 1;
        int x = pos.right() + 6;
        if (x + panelW > screen.width - 2) {
            x = Math.max(2, pos.left() - panelW - 6);
        }
        int y = Math.max(2, pos.top());
        graphics.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        graphics.outline(x, y, panelW, panelH, PANEL_BORDER);
        int ty = y + padding;
        for (Line line : lines) {
            graphics.text(font, line.text(), x + padding, ty, line.color(), true);
            if (line.right() != null) {
                graphics.text(font, line.right(), x + panelW - padding - font.width(line.right()), ty, line.rightColor(), true);
            }
            ty += 10;
        }
    }
}
