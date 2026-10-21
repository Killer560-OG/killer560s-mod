package com.killer560.hub.cheatutils;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import com.killer560.hub.chocolatefactory.ChocolateFactoryMenu;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Chocolate Factory - originally ported from QUOI {@code misc/ChocolateFactory.kt} (OdinLegacy's, BSD-3-Clause).
 * Only runs while the open container is titled exactly "Chocolate Factory". What is where comes from
 * {@link ChocolateFactoryMenu}, the reader the legit helper uses too (killer560, 2026-10-11: "It will click the cookie
 * but does nothing else. I think the menu has changed" - it had: the Time Tower moved 39 -> 38 and the Coach 42 -> 41,
 * and 42 is now a menu button).
 * <ul>
 * <li>One action per random [min,max] delay, in priority order: activate the Time Tower (right-click) when it has a
 * charge and is inactive; claim a stray (top three rows, "CLICK ME!" / "Golden Rabbit"); click the chocolate.</li>
 * <li>Best upgrade on its own "Upgrade delay" timer: the cheapest chocolate per extra chocolate-per-second among the
 * seven employees, the Time Tower and the Coach, bought (middle-click, QUOI's) only when affordable.</li>
 * <li>Never clicks {@link ChocolateFactoryMenu#DESTRUCTIVE_SLOTS} or anything outside the factory's own slots.</li>
 * <li>A different screen replacing the factory halts it until every screen is closed.</li>
 * </ul>
 * Every click claims the tick through {@code ActionGate}; clicks go through {@code MultiPlayerGameMode#handleContainerInput}.
 */
public final class ChocolateFactoryFeature {

    private static boolean sessionActive = false;
    private static boolean halted = false;
    private static long nextActionMs = 0;
    private static long nextUpgradeMs = 0;
    /** Clicks sent, per kind (the testkit reads these). */
    private static int cookieClicks;
    private static int strayClicks;
    private static int towerClicks;
    private static int upgradeClicks;
    /** Client ticks seen, and the one the last click went out on: two clicks never go on back-to-back ticks. */
    private static long ticks;
    private static long lastClickTick = -10;

    private ChocolateFactoryFeature() {
    }

    public static int[] clickCounts() {
        return new int[]{cookieClicks, strayClicks, towerClicks, upgradeClicks};
    }

    static void tick(Minecraft client) {
        ticks++;
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isChocolateEnabled() || client.player == null || client.gameMode == null) {
            endSession();
            halted = false;
            return;
        }
        if (McCompat.screen(client) == null) {
            endSession();
            halted = false;
            return;
        }
        boolean isFactory = McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                && ChocolateFactoryMenu.isFactoryTitle(s.getTitle().getString());
        if (!isFactory) {
            if (sessionActive) {
                String title = McCompat.screen(client).getTitle().getString();
                CheatUtils.LOGGER.warn("[CheatUtils] ChocolateFactory unexpected screen '{}' ({}) - halting",
                        title, McCompat.screen(client).getClass().getSimpleName());
                ModChat.send(CheatUtils.CHAT_TAG, ModChat.text("Chocolate Factory stopped: "),
                        ModChat.bad("unexpected screen"), ModChat.dim(" '" + title + "' - close it to re-arm"));
                endSession();
                halted = true;
            }
            return;
        }
        if (halted) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) McCompat.screen(client);
        AbstractContainerMenu menu = screen.getMenu();
        if (menu.slots.size() < 54 + 36) {
            return;
        }
        if (menu.getSlot(ChocolateFactoryMenu.INFO_SLOT).getItem().isEmpty()) {
            return; // contents not arrived yet
        }
        long now = System.currentTimeMillis();
        // Reading the menu is 54 lore scans and a handful of regexes: skip it on every tick where neither timer is due.
        if (sessionActive && now < nextActionMs && !(cfg.isCfAutoUpgrade() && now >= nextUpgradeMs)) {
            return;
        }
        List<ItemStack> top = new ArrayList<>(54);
        for (int i = 0; i < 54; i++) {
            top.add(menu.getSlot(i).getItem());
        }
        ChocolateFactoryMenu.State state = ChocolateFactoryMenu.read(top);
        if (!sessionActive) {
            sessionActive = true;
            nextActionMs = now + cfg.rollCfDelayMs();
            nextUpgradeMs = now + cfg.getCfUpgradeDelayMs();
            CheatUtils.LOGGER.info("[ChocoFactory] session start: chocolate={} towerReady={} strays={} firstActionInMs={} "
                    + "upgradeInMs={} tick={}", state.chocolate(), state.timeTowerReady(), state.strays().size(),
                    nextActionMs - now, nextUpgradeMs - now, ticks);
        }

        // Both timers claim the tick through the gate, so a cookie click and a purchase never share a tick, and a
        // denied tick leaves its timer where it was (2026-09-21 gate audit).
        if (now >= nextActionMs) {
            Boolean sent = null; // null = nothing was due to click at all
            if (cfg.isCfAutoTimeTower() && state.timeTowerReady()) {
                sent = click(client, screen, menu, state.timeTowerSlot(), 1, ContainerInput.PICKUP);
                if (sent) {
                    towerClicks++;
                    CheatUtils.LOGGER.info("[ChocoFactory] Time Tower activated: slot={} tick={}", state.timeTowerSlot(), ticks);
                }
            } else if (cfg.isCfClaimStrays() && !state.strays().isEmpty()) {
                sent = click(client, screen, menu, state.strays().get(0), 0, ContainerInput.PICKUP);
                if (sent) {
                    strayClicks++;
                    CheatUtils.LOGGER.info("[ChocoFactory] stray claimed: slot={} straysLeft={} tick={}", state.strays().get(0),
                            state.strays().size() - 1, ticks);
                }
            } else if (cfg.isCfClickCookie()) {
                sent = click(client, screen, menu, ChocolateFactoryMenu.INFO_SLOT, 1, ContainerInput.PICKUP);
                if (sent) {
                    cookieClicks++;
                    CheatUtils.LOGGER.debug("[ChocoFactory] cookie click #{} tick={}", cookieClicks, ticks);
                }
            }
            if (sent != null && !sent) {
                CheatUtils.LOGGER.debug("[ChocoFactory] action click held (gap tick or ActionGate) tick={}", ticks);
            }
            if (sent == null || sent) {
                nextActionMs = now + cfg.rollCfDelayMs();
            }
        }

        if (cfg.isCfAutoUpgrade() && now >= nextUpgradeMs) {
            ChocolateFactoryMenu.Upgrade best = state.best();
            if (best == null || state.chocolate() < best.cost()) {
                CheatUtils.LOGGER.info("[ChocoFactory] no upgrade: best={} cost={} chocolate={} tick={}",
                        best == null ? "none" : best.name(), best == null ? 0 : best.cost(), state.chocolate(), ticks);
                nextUpgradeMs = now + cfg.getCfUpgradeDelayMs();
            } else if (click(client, screen, menu, best.slot(), 2, ContainerInput.CLONE)) {
                upgradeClicks++;
                CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory bought {} (slot {}) for {}", best.name(),
                        best.slot(), best.cost());
                nextUpgradeMs = now + cfg.getCfUpgradeDelayMs();
            }
        }
    }

    /** @return false when {@link com.killer560.hub.util.ActionGate} held this tick back - nothing was sent. */
    private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, AbstractContainerMenu menu,
                                 int slot, int button, ContainerInput input) {
        if (slot < 0 || slot >= 54 || ChocolateFactoryMenu.DESTRUCTIVE_SLOTS.contains(slot)) {
            return false;
        }
        // The action and upgrade timers are independent, so a purchase could follow a chocolate click on the very
        // next client tick, and two client ticks can reach the server inside one of its ticks: testkit 754 saw two
        // clicks in one server tick that way. Leave at least one empty tick between any two clicks.
        if (ticks - lastClickTick < 2) {
            return false;
        }
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.CHOCOLATE_FACTORY, screen)) {
            return false;
        }
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
        lastClickTick = ticks;
        return true;
    }

    private static void endSession() {
        if (sessionActive) {
            CheatUtils.LOGGER.info("[ChocoFactory] session end: clicks cookie={} stray={} tower={} upgrade={} tick={}",
                    cookieClicks, strayClicks, towerClicks, upgradeClicks, ticks);
        }
        sessionActive = false;
    }
}
