package com.killer560.hub.chocolatefactory;

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
 * cheat auto buys by) and every other upgrade you can afford yellow;</li>
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
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GOLD = 0xFFFFAA00;
    private static final int AQUA = 0xFF55FFFF;

    private static ChocolateFactoryMenu.State state;
    private static int stateContainer = -1;
    private static final Set<Integer> strayAlerted = new HashSet<>();
    private static boolean towerReminded;
    /** Testkit read-outs: alerts and reminders given since launch. */
    private static int strayAlerts;
    private static int towerReminders;

    private ChocolateFactoryHelper() {
    }

    /** The info line's place in Edit HUD (2026-10-12): drawn over the factory menu, moved and scaled like any element. */
    public static final String INFO_LINE_ELEMENT_ID = "chocolate_info_line";
    private static com.killer560.hub.hud.ContainerOverlayElement infoLineElement;
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

    private static void tick(Minecraft client) {
        try {
            ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
            if (!cfg.active() || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
                    || !ChocolateFactoryMenu.isFactoryTitle(screen.getTitle().getString())) {
                state = null;
                stateContainer = -1;
                strayAlerted.clear();
                towerReminded = false;
                return;
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (menu.slots.size() < 54 || menu.getSlot(ChocolateFactoryMenu.INFO_SLOT).getItem().isEmpty()) {
                return;
            }
            List<ItemStack> top = new ArrayList<>(54);
            for (int i = 0; i < 54; i++) {
                top.add(menu.getSlot(i).getItem());
            }
            if (menu.containerId != stateContainer) {
                stateContainer = menu.containerId;
                strayAlerted.clear();
                towerReminded = false;
            }
            state = ChocolateFactoryMenu.read(top);
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

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
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
            for (ChocolateFactoryMenu.Upgrade u : st.upgrades()) {
                if (u != st.best() && st.chocolate() >= u.cost()) {
                    outline(graphics, menu, u.slot(), left, top, YELLOW);
                }
            }
            if (st.best() != null) {
                outline(graphics, menu, st.best().slot(), left, top, GREEN);
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
            String line = infoLine(st);
            lastInfoLine = line;
            // Just above the menu, unless he has placed it in Edit HUD (ContainerOverlayElement).
            infoLineElement.draw(graphics, left, top - 11, client.font.width(line), 9,
                    (g, x, y) -> g.text(client.font, line, x, y, 0xFFFFFFFF, true));
        }
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
