package com.killer560.hub.invsort;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Dump Into Chest (killer560, 2026-11-04: "for inventory sort make an option to dump all items into a chest as well").
 * With a chest, Ender Chest page, backpack or barrel open, a button on the screen (or the Dump Key) shift-clicks every
 * item of the player's inventory into the container: one shift-click per paced tick through {@link ActionGate}, at the
 * sorter's own Ticks Between Moves. Never slot 8 (Hypixel's locked slot, {@link InventoryLayout#LOCKED_SLOT}), never the
 * hotbar unless Dump Hotbar is on, never the SkyBlock Menu. Only menus whose title says they are a storage container
 * qualify: a shift-click in a shop or any other Hypixel menu would buy or sell. Each slot is clicked once (a shift-click
 * moves as much as fits), items that fit nowhere are skipped, and the run ends when everything was tried, the container
 * is full, or the screen closes. Cheat build only, like the rest of the sorter.
 */
public final class InvSortDump {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    /** Container titles that are plain storage. Everything else (shops, the SkyBlock Menu, minions...) is refused. */
    private static final Pattern STORAGE_TITLE = Pattern.compile("(?i)(large )?chest|barrel|ender chest.*|.*backpack.*");
    private static final int BTN_H = 12;
    private static final int PANEL_BG = 0xE0201810;
    private static final int PANEL_BORDER = 0xFFCC6600;
    private static final String LABEL = "Dump Into Chest";

    private static AbstractContainerScreen<?> running;
    private static final Set<Integer> tried = new HashSet<>();
    private static int wait;
    private static int moved;
    private static boolean keyWasDown;
    private static int[] rect;
    private static Screen rectFor;

    private InvSortDump() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("InvSortDump.tick", InvSortDump::tick));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InvSortDump.keys", InvSortDump::pollKey));
        MacroStop.register("Inventory Dump", MacroStop.Kind.CLICK, InvSortDump::isRunning, why -> stop("you pressed " + why));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                return;
            }
            com.killer560.hub.gui.BelowTooltip.register(screen, (s, g, mx, my, delta) -> {
                try {
                    draw(cs, g, mx, my);
                } catch (Exception e) {
                    LOGGER.error("[InvSort] dump button draw failed", e);
                }
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (rectFor != screen || rect == null || McInput.button(event) != 0 || !buttonVisible(cs)) {
                    return true;
                }
                if (event.x() >= rect[0] && event.x() < rect[0] + rect[2] && event.y() >= rect[1] && event.y() < rect[1] + rect[3]) {
                    if (isRunning()) {
                        stop("stopped by user");
                    } else {
                        start(cs);
                    }
                    return false;
                }
                return true;
            });
        });
    }

    public static boolean isRunning() {
        return running != null;
    }

    /** Whether a screen is a container the dump may fill. */
    public static boolean eligible(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> acs) || screen instanceof SortingInventoryScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            return false;
        }
        String title = acs.getTitle().getString().trim();
        return STORAGE_TITLE.matcher(title).matches() || StorageOverlayFeature.storageKeyForTitle(title) != null;
    }

    private static boolean buttonVisible(AbstractContainerScreen<?> cs) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        return cfg.isEnabled() && cfg.isDumpButton() && McCompat.screen(Minecraft.getInstance()) == cs && eligible(cs)
                && !InventorySorterExecutor.isRunning();
    }

    private static void draw(AbstractContainerScreen<?> cs, GuiGraphicsExtractor g, int mx, int my) {
        rect = null;
        rectFor = null;
        if (!buttonVisible(cs)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String label = isRunning() ? "Stop Dumping" : LABEL;
        int w = font.width(label) + 10;
        AbstractContainerScreenAccessor geo = (AbstractContainerScreenAccessor) cs;
        int x = geo.killer560smod$getLeftPos() + 176 - w;
        int y = Math.max(1, geo.killer560smod$getTopPos() - BTN_H - 3);
        com.killer560.hub.containercover.ContainerCover.coverRect(cs, "invsort.dump", x, y, x + w, y + BTN_H);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + BTN_H;
        g.fill(x, y, x + w, y + BTN_H, hover ? 0xFF4A3010 : PANEL_BG);
        g.outline(x, y, w, BTN_H, ThemeAccent.accent(PANEL_BORDER));
        g.text(font, label, x + 5, y + 2, 0xFFFFFFFF, false);
        rect = new int[]{x, y, w, BTN_H};
        rectFor = cs;
    }

    private static void pollKey(Minecraft client) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        int key = cfg.getDumpKey();
        boolean down = key != KeyUtil.NONE && client.getWindow() != null && KeyUtil.isBindDown(client.getWindow(), key);
        boolean edge = down && !keyWasDown;
        keyWasDown = down;
        if (!edge || !cfg.isEnabled() || client.player == null || isRunning() || InventorySorterExecutor.isRunning()) {
            return;
        }
        Screen screen = McCompat.screen(client);
        if (eligible(screen)) {
            start((AbstractContainerScreen<?>) screen);
        }
    }

    /** Starts a dump into the open container. @return why not, or null. */
    public static String start(AbstractContainerScreen<?> cs) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        String why = null;
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || !cfg.isEnabled() || client.player == null) {
            why = "The Auto Inventory Sorter is off.";
        } else if (isRunning() || InventorySorterExecutor.isRunning()) {
            why = "Something is already running.";
        } else if (!eligible(cs)) {
            why = "This menu is not a chest.";
        }
        if (why != null) {
            return why;
        }
        running = cs;
        tried.clear();
        wait = 0;
        moved = 0;
        LOGGER.info("[InvSort] Dump Into Chest started (hotbar={})", cfg.isDumpHotbar());
        ModChat.log(InventorySorterCommands.FEATURE, ModChat.text("Dumping your inventory into the chest. Escape cancels."));
        return null;
    }

    private static void stop(String reason) {
        if (running == null) {
            return;
        }
        running = null;
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Stopped: "), ModChat.text(reason));
        LOGGER.info("[InvSort] Dump stopped: {} ({} clicks)", reason, tried.size());
    }

    private static void finish(String note) {
        running = null;
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.good("Dumped "),
                ModChat.text(tried.size() + " stack" + (tried.size() == 1 ? "" : "s") + " into the chest" + note));
        LOGGER.info("[InvSort] Dump done: {} clicks{}", tried.size(), note);
    }

    private static void tick(Minecraft client) {
        if (running == null) {
            return;
        }
        if (client.player == null) {
            running = null;
            return;
        }
        Screen screen = McCompat.screen(client);
        if (screen != running) {
            stop("the chest closed");
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        AbstractContainerMenu menu = running.getMenu();
        Slot next = nextSlot(client, menu);
        if (next == null) {
            finish(containerFull(menu) ? " (the chest is full)" : "");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.INVENTORY_SORTER, running)) {
            return;
        }
        tried.add(next.index);
        client.gameMode.handleContainerInput(menu.containerId, next.index, 0, ContainerInput.QUICK_MOVE, client.player);
        moved++;
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        int extra = cfg.getRandomExtraTicks();
        wait = Math.max(0, cfg.getTicksBetweenMoves() - 1) + (extra <= 0 ? 0 : ThreadLocalRandom.current().nextInt(extra + 1));
    }

    /** The next player-inventory slot that may be dumped and still has somewhere to go, or null. */
    private static Slot nextSlot(Minecraft client, AbstractContainerMenu menu) {
        Inventory inv = client.player.getInventory();
        boolean hotbar = InventorySorterConfig.getInstance().isDumpHotbar();
        List<Slot> top = new ArrayList<>();
        List<Slot> mine = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container == inv) {
                mine.add(s);
            } else {
                top.add(s);
            }
        }
        for (Slot s : mine) {
            int idx = s.getContainerSlot();
            if (idx < 0 || idx > 35 || idx == InventoryLayout.LOCKED_SLOT || (idx < 9 && !hotbar) || tried.contains(s.index)) {
                continue;
            }
            ItemStack stack = s.getItem();
            if (stack.isEmpty() || InvSortStorage.SKYBLOCK_MENU.equalsIgnoreCase(ItemIdentity.of(stack))) {
                continue;
            }
            if (fits(top, stack)) {
                return s;
            }
        }
        return null;
    }

    private static boolean fits(List<Slot> top, ItemStack stack) {
        for (Slot t : top) {
            ItemStack in = t.getItem();
            if (in.isEmpty() && t.mayPlace(stack)) {
                return true;
            }
            if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < Math.min(in.getMaxStackSize(), t.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containerFull(AbstractContainerMenu menu) {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        for (Slot t : menu.slots) {
            if (t.container != inv && t.getItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
