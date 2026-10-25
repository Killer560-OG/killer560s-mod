package com.killer560.hub.croesus;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer.ChestType;
import com.killer560.hub.croesus.DungeonChestValuer.ChestValue;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Croesus extras (2026-10-25, dungeon-gaps-1025 item 5), all legit - they only drop a click he made or show a number:
 * <ul>
 *   <li>Reroll Protection (NoammAddons {@code ChestProfit.kt} "Reroll Value"): his click on a reward chest's Kismet
 *   "Reroll Chest" button (slot 50) is dropped while the chest's profit is above the threshold, or while prices have not
 *   loaded (the chest's worth is then unknown). Ctrl lets it through, as in NoammAddons. One chat line per chest screen.</li>
 *   <li>Kismet Count (SkyHanni {@code CroesusChestTracker.kt} "Kismet Amount"): the Kismet Feathers in his inventory as
 *   the reroll button's stack number. Inventory only: the client is never told what his sacks hold.</li>
 *   <li>Croesus Chests HUD: Hypixel's own tab line {@code " Unclaimed chests: N"} (Odin {@code Croesus.kt}
 *   {@code unclaimedChestsRegex}) as "N/60", and his Kismet Feathers, outside the menu; a chat warning once N reaches
 *   Chest Warning At (Odin's threshold, 55). Never a guess: the number is the tab's, with its age once it is not in the
 *   tab of the world he is in.</li>
 * </ul>
 */
public final class CroesusExtras {

    private static final Logger LOGGER = ModLog.get("killer560smod-croesus");
    public static final String KISMET_ID = "KISMET_FEATHER";
    /** Hypixel's tab widget line, whole: no player's tab name can contain a space and a colon. */
    private static final Pattern UNCLAIMED = Pattern.compile("^Unclaimed chests: (\\d{1,3})$");

    private static int kismets = 0;
    private static int unclaimed = -1;
    private static long unclaimedSeenAtMs = 0L;
    private static boolean unclaimedInThisWorld = false;
    private static int warnedAt = -1;
    private static Object lastLevel = null;
    private static int tickCounter = 0;
    /** The chest screen the last "blocked" line was said in: one line per screen, not one per click. */
    private static int warnedContainerId = Integer.MIN_VALUE;
    private static int blockedRerolls = 0;

    private CroesusExtras() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CroesusExtras", CroesusExtras::tick));
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            unclaimedInThisWorld = false;
        }
        CroesusConfig cfg = CroesusConfig.getInstance();
        if (client.player == null || (!cfg.isChestCountHud() && !cfg.isKismetCount())) {
            return;
        }
        if (++tickCounter < 10) {
            return;
        }
        tickCounter = 0;
        kismets = countKismets(client);
        if (cfg.isChestCountHud()) {
            readTab(client, cfg);
        }
    }

    private static int countKismets(Minecraft client) {
        var inv = client.player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && KISMET_ID.equals(skyblockId(stack))) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static String skyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        return data.copyTag().getStringOr("id", null);
    }

    private static void readTab(Minecraft client, CroesusConfig cfg) {
        if (client.getConnection() == null) {
            return;
        }
        PlayerTabOverlay overlay = McCompat.tabList(client);
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String s = ChatObserver.stripCodes(overlay.getNameForDisplay(info).getString());
            if (s == null || !s.contains("Unclaimed chests")) {
                continue;
            }
            Matcher m = UNCLAIMED.matcher(s.trim());
            if (m.matches()) {
                noteUnclaimed(Integer.parseInt(m.group(1)), cfg);
                return;
            }
        }
    }

    /** A count read off the tab (public for the testkit, which has no Hypixel tab widget). */
    public static void noteUnclaimed(int n, CroesusConfig cfg) {
        if (n != unclaimed) {
            LOGGER.info("[Croesus] tab says {} unclaimed chests (was {})", n, unclaimed);
        }
        unclaimed = n;
        unclaimedSeenAtMs = System.currentTimeMillis();
        unclaimedInThisWorld = true;
        int at = cfg.getChestWarnAt();
        if (n < at) {
            warnedAt = -1;
        } else if (n > warnedAt) {
            warnedAt = n;
            ModChat.send("Croesus", ModChat.bad(n + "/" + ChestProfitFeature.MAX_STORED_RUNS + " unclaimed chests"),
                    ModChat.text(" - Croesus is nearly full, claim some before new runs have no room."));
        }
    }

    // ---- Reroll Protection -------------------------------------------------------------------------------------

    /** From {@code croesus/mixin/CroesusRerollMixin} at HEAD of {@code AbstractContainerScreen#slotClicked}: his clicks
     *  only (Auto Croesus sends through the game mode directly and never passes here). */
    public static boolean shouldBlockSlotClick(AbstractContainerScreen<?> screen, Slot slot, int slotId, ContainerInput input) {
        CroesusConfig cfg = CroesusConfig.getInstance();
        if (!cfg.isRerollProtection() || slot == null || slotId != DungeonChestValuer.REROLL_BUTTON_SLOT
                || slot.index != DungeonChestValuer.REROLL_BUTTON_SLOT) {
            return false;
        }
        ChestType type = ChestType.fromName(ChestProfitFeature.titleOf(screen));
        ItemStack button = slot.getItem();
        if (type == null || !DungeonChestValuer.isRerollButton(button) || DungeonChestValuer.rerollNeedsKismet(button)
                || DungeonChestValuer.rerollAlreadyUsed(button)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (KeyUtil.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                || KeyUtil.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            LOGGER.info("[Croesus] Reroll Protection: Ctrl held - reroll of the {} chest let through", type.display);
            return false;
        }
        long above = cfg.getRerollProtectAboveK() * 1000L;
        boolean priced = DungeonChestValuer.pricesReady();
        ChestValue value = priced ? DungeonChestValuer.fromChestScreen(type, ChestProfitFeature.containerStacks(screen)) : null;
        if (value != null && value.profit() <= above) {
            return false;
        }
        blockedRerolls++;
        LOGGER.info("[Croesus] Reroll Protection: reroll of the {} chest blocked ({}, threshold {}) - not sent ({} so far)",
                type.display, value == null ? "prices not loaded" : "profit " + DungeonChestValuer.formatCoins(value.profit()),
                DungeonChestValuer.formatCoins(above), blockedRerolls);
        int id = screen.getMenu().containerId;
        if (id != warnedContainerId) {
            warnedContainerId = id;
            if (value == null) {
                ModChat.send("Croesus", ModChat.text("Reroll blocked: prices have not loaded, so this chest's worth is"
                        + " unknown."), ModChat.dim(" Hold Ctrl to reroll anyway."));
            } else {
                ModChat.send("Croesus", ModChat.text("Reroll blocked: this chest makes "),
                        ModChat.good("+" + DungeonChestValuer.formatCoins(value.profit())),
                        ModChat.text(", over your "), ModChat.value(DungeonChestValuer.formatCoins(above)),
                        ModChat.text(" limit."), ModChat.dim(" Hold Ctrl to reroll anyway."));
            }
        }
        return true;
    }

    /** For the testkit: rerolls Reroll Protection has dropped. */
    public static int blockedRerolls() {
        return blockedRerolls;
    }

    // ---- Kismet Count on the reroll button -----------------------------------------------------------------------

    /** After the slot's item is drawn (CroesusSlotMixin TAIL): his Kismet Feathers as the reroll button's number. */
    public static void drawKismetCount(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (slot.index != DungeonChestValuer.REROLL_BUTTON_SLOT || !CroesusConfig.getInstance().isKismetCount()
                || !DungeonChestValuer.isRerollButton(slot.getItem())
                || ChestType.fromName(ChestProfitFeature.titleOf(screen)) == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        String text = String.valueOf(kismets);
        lastDrawnKismetText = text;
        graphics.text(client.font, text, slot.x + 17 - client.font.width(text), slot.y + 9,
                kismets > 0 ? 0xFF55FF55 : 0xFFFF5555, true);
    }

    private static volatile String lastDrawnKismetText = null;

    /** For the testkit: the number last drawn on a reroll button (null before any). */
    public static String lastDrawnKismetText() {
        return lastDrawnKismetText;
    }

    public static int kismets() {
        return kismets;
    }

    public static int unclaimed() {
        return unclaimed;
    }

    // ---- Croesus Chests HUD ----------------------------------------------------------------------------------------

    public static final class ChestCountHudElement implements HudElement {
        public static final String ID = "croesus_chests";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "Croesus Chests";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 270;
        }

        @Override
        public int width() {
            List<String> lines = lines();
            return com.killer560.hub.hud.HudText.width(lines.isEmpty() ? sample() : lines);
        }

        @Override
        public int height() {
            List<String> lines = lines();
            return com.killer560.hub.hud.HudText.ROW * Math.max(1, lines.isEmpty() ? 2 : lines.size());
        }

        private static List<String> sample() {
            return List.of("§6Croesus: §a42§7/60", "§6Kismets: §f3");
        }

        /** What render() draws right now (empty for nothing). */
        static List<String> lines() {
            CroesusConfig cfg = CroesusConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            if (!cfg.isChestCountHud()) {
                return List.of();
            }
            if (McCompat.screen(client) instanceof HudEditorScreen) {
                return sample();
            }
            if (HudVisibility.hidesHud() || client.player == null
                    || !(unclaimedInThisWorld || DungeonState.isInDungeon())) {
                return List.of();
            }
            List<String> out = new ArrayList<>(2);
            if (unclaimed >= 0) {
                String color = unclaimed >= cfg.getChestWarnAt() ? "§c" : "§a";
                String line = "§6Croesus: " + color + unclaimed + "§7/" + ChestProfitFeature.MAX_STORED_RUNS;
                if (!unclaimedInThisWorld) {
                    long min = (System.currentTimeMillis() - unclaimedSeenAtMs) / 60_000L;
                    line += " §8(" + (min < 60 ? min + "m" : (min / 60) + "h") + " ago)";
                }
                out.add(line);
            } else {
                out.add("§6Croesus: §7not seen in the tab yet");
            }
            out.add("§6Kismets: " + (kismets > 0 ? "§f" : "§c") + kismets);
            return out;
        }

        @Override
        public boolean isEnabledInSettings() {
            return CroesusConfig.getInstance().isChestCountHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            List<String> lines = lines();
            if (lines.isEmpty()) {
                return;
            }
            HudSeen.markDrawn(id());
            lastHudLines = lines;
            Minecraft client = Minecraft.getInstance();
            int ty = y;
            for (String line : lines) {
                graphics.text(client.font, line, x, ty, 0xFFFFFFFF, true);
                ty += com.killer560.hub.hud.HudText.ROW;
            }
        }
    }

    private static volatile List<String> lastHudLines = List.of();

    /** For the testkit: the lines the Croesus Chests HUD drew last. */
    public static List<String> lastHudLines() {
        return lastHudLines;
    }
}
