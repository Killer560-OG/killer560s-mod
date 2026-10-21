package com.killer560.hub.chocolatefactory;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * Chocolate Factory helper - the LEGIT one, "just like SkyHanni's" (killer560, 2026-10-11). It never clicks anything;
 * while the Chocolate Factory menu is open it:
 * <ul>
 * <li>outlines the best-value upgrade green (cheapest chocolate per extra chocolate-per-second, the same valuation the
 * cheat auto buys by) once you can afford it, and keeps that outline on an upgrade you have picked up until the pick-up
 * ends (see updateHeldBest below);</li>
 * <li>outlines stray rabbits gold, and plays a ping with a chat line the moment one appears;</li>
 * <li>outlines the Time Tower aqua while it has a charge and is not running, with one chat reminder per menu open;</li>
 * <li>prints one line above the menu: chocolate per second, the best upgrade, its cost and how long until you can
 * afford it.</li>
 * </ul>
 * The reading is {@link ChocolateFactoryMenu}, shared with the cheat auto.
 */
public final class ChocolateFactoryHelper {

    private static final Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");
    private static final String CHAT = "Chocolate Factory";
    private static final int GREEN = 0xFF55FF55;
    private static final int GOLD = 0xFFFFAA00;
    private static final int AQUA = 0xFF55FFFF;

    private static ChocolateFactoryMenu.State state;
    private static int stateContainer = -1;
    /** The 54 stack objects the last read came from; see tick. */
    private static final ItemStack[] lastRefs = new ItemStack[54];
    private static boolean refsValid;
    private static final Set<Integer> strayAlerted = new HashSet<>();
    private static boolean towerReminded;
    /** The best upgrade as the reader had it on the last tick, so a pick-up has a slot to hold (killer560, 2026-10-18). */
    private static ChocolateFactoryMenu.Upgrade lastBest;
    /** The upgrade the cursor picked up, held as the best until the pick-up ends; null when nothing is held. */
    private static ChocolateFactoryMenu.Upgrade heldBest;
    private static int heldTicks;
    /** A held upgrade that stops reading (its item is blank while carried) is released after this many ticks. */
    private static final int HOLD_TICKS = 200;
    /** Testkit read-outs: alerts and reminders given since launch. */
    private static int strayAlerts;
    private static int towerReminders;

    private ChocolateFactoryHelper() {
    }

    /** The info line's place in Edit HUD (2026-10-12): drawn over the factory menu, moved and scaled like any element. */
    public static final String INFO_LINE_ELEMENT_ID = "chocolate_info_line";
    private static com.killer560.hub.hud.ContainerOverlayElement infoLineElement;
    private static ChocolateFactoryMenu.State lineState;
    private static ChocolateFactoryMenu.Upgrade lineBest;
    private static String lineCps = "";
    private static String lineBestText = "";
    private static int lineWidth;
    private static volatile String lastInfoLine = "§6123.4/s §7| Best: §aRabbit Sis §7- §61.25M §7(affordable now)";

    public static void register() {
        ChocolateFactoryConfig.getInstance();
        infoLineElement = com.killer560.hub.hud.ContainerOverlayElement.register(INFO_LINE_ELEMENT_ID,
                "Chocolate Factory Info Line", 230, 9, 10, 20, // no font yet at registration: a guess until drawn
                () -> ChocolateFactoryConfig.getInstance().isEnabled() && ChocolateFactoryConfig.getInstance().isInfoLine(),
                (g, x, y) -> g.text(Minecraft.getInstance().font, lastInfoLine, x, y, 0xFFFFFFFF, true))
                .previewSize(() -> new int[]{Minecraft.getInstance().font.width(lastInfoLine), 9});
        ClientTickEvents.END_CLIENT_TICK.register(ChocolateFactoryHelper::tick);
        ScreenEvents.AFTER_INIT.register(ChocolateFactoryHelper::onScreenInit);
        // Hoppity's Hunt egg timers and duplicate values (legit), and the cheat-only Egg ESP it registers itself.
        com.killer560.hub.chocolatefactory.egg.HoppityEggs.register();
    }

    /** The menu as last read (null when no factory is open) - the testkit reads it. */
    public static ChocolateFactoryMenu.State state() {
        return state;
    }

    public static int strayAlerts() {
        return strayAlerts;
    }

    public static int towerReminders() {
        return towerReminders;
    }

    /** Writes every occupied slot of a freshly opened factory to the log: slot number, item name and lore. Once per open,
     *  so a changed Hypixel layout can be read straight from the log (killer560, 2026-10-18: "make a logger ... you can see it all"). */
    private static void dumpMenu(List<ItemStack> top) {
        LOGGER.info("[ChocolateFactory] menu opened, dumping {} slots", top.size());
        for (int i = 0; i < top.size(); i++) {
            ItemStack stack = top.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            LOGGER.info("[ChocolateFactory] slot {}: {} :: {}", i, CheatUtils.plainName(stack),
                    String.join(" | ", CheatUtils.lore(stack)));
        }
    }

    private static void tick(Minecraft client) {
        try {
            ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
            if (!cfg.active() || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
                    || !ChocolateFactoryMenu.isFactoryTitle(screen.getTitle().getString())) {
                state = null;
                stateContainer = -1;
                refsValid = false;
                strayAlerted.clear();
                towerReminded = false;
                lastBest = null;
                heldBest = null;
                return;
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (menu.slots.size() < 54 || menu.getSlot(ChocolateFactoryMenu.INFO_SLOT).getItem().isEmpty()) {
                return;
            }
            // The server replaces a slot's stack object whenever it changes it, so when all 54 are the same objects as the
            // last read the reading would come out identical: reuse it instead of parsing ~250 names and lore lines a tick.
            boolean unchanged = refsValid && menu.containerId == stateContainer && state != null;
            for (int i = 0; i < 54; i++) {
                ItemStack s = menu.getSlot(i).getItem();
                if (lastRefs[i] != s) {
                    unchanged = false;
                    lastRefs[i] = s;
                }
            }
            if (!unchanged) {
                List<ItemStack> top = new ArrayList<>(54);
                for (int i = 0; i < 54; i++) {
                    top.add(lastRefs[i]);
                }
                if (menu.containerId != stateContainer) {
                    stateContainer = menu.containerId;
                    strayAlerted.clear();
                    towerReminded = false;
                    lastBest = null;
                    heldBest = null;
                    dumpMenu(top);
                }
                state = ChocolateFactoryMenu.read(top);
                refsValid = true;
            }
            updateHeldBest(menu, state);
            if (cfg.isStrayAlert()) {
                for (int slot : state.strays()) {
                    if (strayAlerted.add(slot)) {
                        strayAlerts++;
                        DungeonAlertsFeature.playSound(SecretSound.SoundChoice.PLING.sound(), 1.0f, 1.6f);
                        ModChat.send(CHAT, ModChat.good("A stray rabbit appeared"), ModChat.dim(" - click it!"));
                    }
                }
                strayAlerted.retainAll(state.strays());
            }
            if (cfg.isTimeTowerReminder() && state.timeTowerReady() && !towerReminded) {
                towerReminded = true;
                towerReminders++;
                ModChat.send(CHAT, ModChat.text("Your Time Tower has "), ModChat.value(String.valueOf(state.timeTowerCharges())),
                        ModChat.text(" charge" + (state.timeTowerCharges() == 1 ? "" : "s") + " and is not running."));
            }
        } catch (RuntimeException e) {
            LOGGER.error("[ChocolateFactory] helper tick failed", e);
        }
    }

    /**
     * Keeps the best outline on the upgrade the cursor picked up. Picking an upgrade up empties its slot in the container,
     * so the reader's next read re-picks the best from what is left and the outline jumps to another slot for no reason
     * (killer560, 2026-10-18). The hold starts on the first tick the cursor holds an item while the last read had a best,
     * and ends when the slot reads again with a different cost (bought, or changed, so the reader's own valuation is
     * fresh), when it reads again while the cursor is empty (put back unchanged), after {@link #HOLD_TICKS} blank ticks,
     * or when the menu changes or closes. A purchase that changes the cost therefore releases the hold on the tick the
     * new lore arrives. Read with {@link AbstractContainerMenu#getCarried()}, so no mouse hook is needed.
     */
    private static void updateHeldBest(AbstractContainerMenu menu, ChocolateFactoryMenu.State st) {
        boolean holding = !menu.getCarried().isEmpty();
        if (heldBest == null && holding && lastBest != null) {
            heldBest = lastBest;
            heldTicks = 0;
        }
        if (heldBest != null) {
            ChocolateFactoryMenu.Upgrade now = null;
            for (ChocolateFactoryMenu.Upgrade u : st.upgrades()) {
                if (u.slot() == heldBest.slot()) {
                    now = u;
                    break;
                }
            }
            heldTicks++;
            boolean release;
            if (now == null) {
                release = !holding || heldTicks > HOLD_TICKS;
            } else {
                release = !holding || now.cost() != heldBest.cost();
            }
            if (release) {
                heldBest = null;
            }
        }
        lastBest = st.best();
    }

    /** The upgrade the best outline and the info line show: the held one while a pick-up holds it, else the reader's best. */
    private static ChocolateFactoryMenu.Upgrade shownBest(ChocolateFactoryMenu.State st) {
        return heldBest != null ? heldBest : st.best();
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !ChocolateFactoryMenu.isFactoryTitle(container.getTitle().getString())) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            try {
                render(client, container, graphics);
            } catch (RuntimeException e) {
                LOGGER.error("[ChocolateFactory] render failed", e);
            }
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        ChocolateFactoryMenu.State st = state;
        if (!cfg.active() || st == null || screen.getMenu().containerId != stateContainer
                || !ChocolateFactoryMenu.isFactoryTitle(screen.getTitle().getString())) {
            return;
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos();
        int top = acc.killer560smod$getTopPos();
        AbstractContainerMenu menu = screen.getMenu();
        graphics.nextStratum();
        if (cfg.isHighlightBestUpgrade()) {
            // Only the single best upgrade is outlined, and only once you can afford it (killer560, 2026-10-18).
            ChocolateFactoryMenu.Upgrade shown = shownBest(st);
            if (shown != null && st.chocolate() >= shown.cost()) {
                outline(graphics, menu, shown.slot(), left, top, GREEN);
            }
        }
        if (cfg.isHighlightStrays()) {
            for (int slot : st.strays()) {
                outline(graphics, menu, slot, left, top, GOLD);
            }
        }
        if (cfg.isTimeTowerReminder() && st.timeTowerReady()) {
            outline(graphics, menu, st.timeTowerSlot(), left, top, AQUA);
        }
        if (cfg.isInfoLine()) {
            // Two lines above the menu: chocolate per second, then the best upgrade on the line below it (killer560, 2026-10-18).
            // Built once per reading (the menu updates about once a second), not every frame.
            ChocolateFactoryMenu.Upgrade shownNow = shownBest(st);
            if (st != lineState || shownNow != lineBest) {
                lineState = st;
                lineBest = shownNow;
                lineCps = cpsLine(st);
                lineBestText = bestLine(st);
                lineWidth = Math.max(client.font.width(lineCps), client.font.width(lineBestText));
                lastInfoLine = lineBestText.isEmpty() ? lineCps : lineCps + "\n" + lineBestText;
            }
            String cps = lineCps;
            String best = lineBestText;
            int w = lineWidth;
            infoLineElement.draw(graphics, left, top - 21, w, 19,
                    (g, x, y) -> {
                        // A dark backing behind both lines, so the info reads on any part of the menu.
                        g.fill(x - 3, y - 3, x + w + 3, y + 19, 0xB0000000);
                        g.text(client.font, cps, x, y, 0xFFFFFFFF, true);
                        if (!best.isEmpty()) {
                            g.text(client.font, best, x, y + 10, 0xFFFFFFFF, true);
                        }
                    });
        }
    }

    /**
     * The first of the two extra-stat lines: chocolate per second, the chocolate you hold, and the multiplier (killer560,
     * 2026-10-19: "rename upgrade info line to extra stat counter"). Lifetime chocolate and time to prestige are not read
     * yet: the menu's lore for them has not been sampled.
     */
    public static String cpsLine(ChocolateFactoryMenu.State st) {
        StringBuilder b = new StringBuilder();
        b.append("§6").append(String.format(java.util.Locale.US, "%,.1f", st.cps())).append("/s");
        b.append(" §7| §fChocolate §6").append(ChocolateFactoryMenu.compact(st.chocolate()));
        if (st.multiplier() > 0) {
            b.append(" §7| §fx").append(String.format(java.util.Locale.US, "%.2f", st.multiplier()));
        }
        return b.toString();
    }

    /** "| Best: Rabbit Sis - 1.25M (affordable now)" on the line below, or empty when there is no best upgrade. */
    public static String bestLine(ChocolateFactoryMenu.State st) {
        ChocolateFactoryMenu.Upgrade best = shownBest(st);
        if (best == null) {
            return "";
        }
        long secs = st.secondsUntil(best);
        return "§7Best: §a" + best.name() + " §7- §6" + ChocolateFactoryMenu.compact(best.cost())
                + " §7(" + (secs == 0 ? "§aaffordable now" : secs < 0 ? "?" : "in " + ChocolateFactoryMenu.duration(secs)) + "§7)";
    }

    /** "§6123.4/s §7| Best: §aRabbit Sis §7- §61.25M §7(affordable now)". */
    public static String infoLine(ChocolateFactoryMenu.State st) {
        StringBuilder b = new StringBuilder();
        b.append("§6").append(String.format(java.util.Locale.US, "%,.1f", st.cps())).append("/s");
        ChocolateFactoryMenu.Upgrade best = st.best();
        if (best != null) {
            long secs = st.secondsUntil(best);
            b.append(" §7| Best: §a").append(best.name()).append(" §7- §6").append(ChocolateFactoryMenu.compact(best.cost()))
                    .append(" §7(").append(secs == 0 ? "§aaffordable now" : secs < 0 ? "?" : "in " + ChocolateFactoryMenu.duration(secs))
                    .append("§7)");
        }
        return b.toString();
    }

    private static void outline(GuiGraphicsExtractor graphics, AbstractContainerMenu menu, int index, int left, int top,
                                int color) {
        if (index < 0 || index >= menu.slots.size()) {
            return;
        }
        Slot slot = menu.getSlot(index);
        int x0 = left + slot.x;
        int y0 = top + slot.y;
        graphics.outline(x0 - 1, y0 - 1, 18, 18, color);
        graphics.outline(x0, y0, 16, 16, color);
    }
}
