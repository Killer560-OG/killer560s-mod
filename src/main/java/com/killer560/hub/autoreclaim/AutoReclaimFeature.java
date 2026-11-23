package com.killer560.hub.autoreclaim;

import com.killer560.hub.accadvisor.AccessoryAdvisor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.storageoverlay.StoragePageSlots;
import com.killer560.hub.storagesearch.StorageSearchIndex;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Auto Reclaim (0.27.3's /reclaim): every item {@link ReclaimRules} says /reclaim returns something for is filled in the
 * theme's accent in any open container (inventory, Ender Chest page, backpack, Accessory Bag, the Storage Overlay's
 * grid, and the vanilla /storage overview's page icons). {@code /autoreclaim} lists them from what the mod has cached:
 * the live inventory, Storage Overlay's page cache (via {@link StorageSearchIndex}) and the Accessory Bag pages seen
 * open this session. Purely visual: it never clicks or moves an item (the cheat flow waits on a /reclaim menu capture).
 */
public final class AutoReclaimFeature {

    private static final org.slf4j.Logger LOG = com.killer560.hub.util.ModLog.get("killer560smod-autoreclaim");
    private static final java.util.regex.Pattern CLAIM_DONE = java.util.regex.Pattern.compile("^Your (.+) claim has been processed!");
    private static String handItem;

    public static final String CHAT = "Auto Reclaim";
    private static final int MAX_LINES = 25;
    private static final int FILL_ALPHA = 0x80;
    private static final int BAG_SCAN_TICKS = 10;

    /** Accessory Bag page number -> how many of its items need reclaiming, for pages opened this session. */
    private static final Map<Integer, Integer> BAG = new TreeMap<>();
    /** The /storage overview screen last scanned and the overview slots (page icons) holding a match. */
    private static Screen overviewScreen;
    private static final BitSet OVERVIEW = new BitSet();
    private static int tick;

    private AutoReclaimFeature() {
    }

    public static void register() {
        ReclaimButtons.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> && "Storage".equals(screen.getTitle().getString())
                    && AutoReclaimConfig.getInstance().isEnabled()) {
                scanOverview(screen);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            watchHandReclaim(client);
            if (++tick % BAG_SCAN_TICKS == 0 && com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                    && AutoReclaimConfig.getInstance().isEnabled()) {
                scanBag(s);
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && handItem != null && !ReclaimMenu.isRunning()) {
                java.util.regex.Matcher m = CLAIM_DONE.matcher(message.getString());
                if (m.find()) {
                    LOG.info("Reclaimed by hand, not matched by ReclaimRules: item {} claim \"{}\"", handItem, m.group(1));
                }
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("autoreclaim").executes(ctx -> {
                    report();
                    return 1;
                })));
    }

    /** Remembers the id of an item the player put in the Reclaim Menu by hand that ReclaimRules does not know (null otherwise). */
    private static void watchHandReclaim(Minecraft client) {
        if (com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                && ReclaimMenu.TITLE.equals(s.getTitle().getString()) && s.getMenu().slots.size() > 22) {
            ItemStack in = s.getMenu().getSlot(22).getItem();
            if (!in.isEmpty()) {
                handItem = ReclaimRules.reason(in) == null ? String.valueOf(com.killer560.hub.cheatutils.CheatUtils.skyblockId(in)) : null;
            }
        } else {
            handItem = null;
        }
    }

    private static void clear() {
        handItem = null;
        ReclaimRules.clear();
        BAG.clear();
        OVERVIEW.clear();
        overviewScreen = null;
    }

    /** The slot fill color: the menu theme's accent. */
    private static int fillColor() {
        return (FILL_ALPHA << 24) | (ThemeAccent.accent(0xFFFFAA00) & 0xFFFFFF);
    }

    /** Fills a real container slot that holds a match (or, on the /storage overview, a page icon that holds one). */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (!AutoReclaimConfig.getInstance().isEnabled()) {
            return;
        }
        if (ReclaimRules.reason(slot.getItem()) != null || (screen == overviewScreen && OVERVIEW.get(slot.index))) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, fillColor());
        }
    }

    /** The same fill for a stack the Storage Overlay's grid draws at {@code x, y}; true when it was a match. */
    public static boolean drawGridHighlight(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        if (!AutoReclaimConfig.getInstance().isEnabled() || ReclaimRules.reason(stack) == null) {
            return false;
        }
        graphics.fill(x, y, x + 16, y + 16, fillColor());
        return true;
    }

    /** The outline color for a grid page panel that holds a match. */
    public static int panelColor() {
        return 0xFF000000 | ThemeAccent.accent(0xFFFFAA00);
    }

    private static void scanOverview(Screen screen) {
        OVERVIEW.clear();
        overviewScreen = screen;
        for (StorageSearchIndex.Entry e : StorageSearchIndex.build(false).entries()) {
            if (e.type().isStorage() && ReclaimRules.reason(e.stack()) != null) {
                OVERVIEW.set(e.type() == StorageSearchIndex.SourceType.ENDER_CHEST
                        ? StoragePageSlots.enderChestSlot(e.storageNumber())
                        : StoragePageSlots.backpackSlot(e.storageNumber()));
            }
        }
    }

    /** Remembers how many matches the open Accessory Bag page holds (its own slots only, not the player's inventory). */
    private static void scanBag(AbstractContainerScreen<?> screen) {
        int[] page = AccessoryAdvisor.page(screen.getTitle().getString());
        if (page == null) {
            return;
        }
        int n = 0;
        for (Slot s : screen.getMenu().slots) {
            if (!(s.container instanceof Inventory) && ReclaimRules.reason(s.getItem()) != null) {
                n++;
            }
        }
        if (n > 0) {
            BAG.put(page[0], n);
        } else {
            BAG.remove(page[0]);
        }
    }

    /** {@code /autoreclaim}: a chat list of what was found and where. */
    static void report() {
        if (!AutoReclaimConfig.getInstance().isEnabled()) {
            ModChat.send(CHAT, ModChat.bad("Turn Auto Reclaim on first."));
            return;
        }
        StorageSearchIndex index = StorageSearchIndex.build(true);
        int found = 0;
        for (StorageSearchIndex.Entry e : index.entries()) {
            String why = ReclaimRules.reason(e.stack());
            if (why == null) {
                continue;
            }
            if (found++ < MAX_LINES) {
                ModChat.send(CHAT, ModChat.text(e.name() + " "), ModChat.dim("(" + why + ") " + e.location()));
            }
        }
        int bag = BAG.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<Integer, Integer> p : BAG.entrySet()) {
            ModChat.send(CHAT, ModChat.text("Accessory Bag page " + p.getKey() + ": "),
                    ModChat.dim(p.getValue() + " item(s)"));
        }
        long unopened = index.storages().stream().filter(s -> !s.hasContents()).count();
        ModChat.send(CHAT, ModChat.value((found + bag) + " item(s) to reclaim"
                + (found > MAX_LINES ? " (first " + MAX_LINES + " listed)" : "")),
                ModChat.dim(unopened > 0 ? "; " + unopened + " storage page(s) never opened" : ""));
        if (AutoReclaimConfig.getInstance().isPullAndReclaim()) {
            ReclaimMenu.start(pullCommands(index));
        }
    }

    /** The storage commands that open every page holding a match ("enderchest 3", "backpack 2", "accessories"). */
    private static List<String> pullCommands(StorageSearchIndex index) {
        List<String> pulls = new java.util.ArrayList<>();
        for (StorageSearchIndex.Entry e : index.entries()) {
            if (e.type().isStorage() && ReclaimRules.reason(e.stack()) != null) {
                String cmd = (e.type() == StorageSearchIndex.SourceType.ENDER_CHEST ? "enderchest " : "backpack ") + e.storageNumber();
                if (!pulls.contains(cmd)) {
                    pulls.add(cmd);
                }
            }
        }
        if (BAG.containsKey(1)) {
            pulls.add("accessories"); // page 1 only: the next-page click is not captured
        }
        return pulls;
    }

    /** The /reclaim menu's "Reclaim All" button: every page, then every match; closes the menu and runs the same flow as the command. */
    public static void startAll() {
        List<String> pulls = pullCommands(StorageSearchIndex.build(true));
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && !ReclaimMenu.isRunning()) {
            mc.player.closeContainer();
            ReclaimMenu.start(pulls);
        }
    }

    /** For the testkit: the matches the report would list, as "name (reason) location". */
    public static List<String> reportLines() {
        return StorageSearchIndex.build(true).entries().stream()
                .filter(e -> ReclaimRules.reason(e.stack()) != null)
                .map(e -> e.name() + " (" + ReclaimRules.reason(e.stack()) + ") " + e.location()).toList();
    }
}
