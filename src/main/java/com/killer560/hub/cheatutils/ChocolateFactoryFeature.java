package com.killer560.hub.cheatutils;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Chocolate Factory - ported from QUOI {@code misc/ChocolateFactory.kt} (itself OdinLegacy's
 * ChocolateFactory, BSD-3-Clause). Only runs while the open container is titled exactly "Chocolate Factory".
 * <ul>
 * <li>Cookie: slot 13, button 1, PICKUP (QUOI). Strays: first slot whose name contains "CLICK ME!" or
 * "Golden Rabbit", button 0, PICKUP. Time Tower: slot 39, button 1, PICKUP when "Charges: N/M" N &gt; 0 and
 * "Status: INACTIVE". One action per random [min,max] delay (QUOI clicks cookie + stray in one tick; spread out
 * here for the min/max jitter).
 * <li>Best upgrade (QUOI {@code findBestUpgrade}): raw cps = cps (slot 13 "N per second") / "Total Multiplier: Xx"
 * (slot 45); base multiplier excludes an active Time Tower (level*0.1). Candidates: employee rabbits slots
 * 28-34 (+1..+7 raw cps), Time Tower 39 (+raw*0.1/8 avg), Coach Jackrabbit 42 (+0.01 multiplier). Cost = the
 * lore line after the "Cost" line. Picks min cost / extra-cps; buys only if chocolate (slot 13 name digits)
 * &gt;= cost, via middle-click (button 2, CLONE), on its own fixed "Upgrade delay" timer.
 * <li>Stop on unexpected screens: if a different screen replaces the factory while running (e.g. a
 * confirmation/submenu), automation halts with a chat message until every screen is closed.
 * </ul>
 * Clicks go through {@code MultiPlayerGameMode#handleContainerInput}, the ExperimentsFeature precedent.
 */
public final class ChocolateFactoryFeature {

    private static final String TITLE = "Chocolate Factory";
    private static final Map<Integer, Integer> RABBIT_SLOT_GAINS = Map.of(28, 1, 29, 2, 30, 3, 31, 4, 32, 5, 33, 6, 34, 7);
    private static final Pattern CPS = Pattern.compile("([\\d.,]+)\\s+per second");
    private static final Pattern TOTAL_MULT = Pattern.compile("Total Multiplier:\\s+([\\d.]+)x");
    private static final Pattern TT_STATUS = Pattern.compile("Status:\\s+(ACTIVE|INACTIVE)");
    private static final Pattern TT_CHARGES = Pattern.compile("Charges:\\s*(\\d+)\\s*/\\s*(\\d+)");

    private static boolean sessionActive = false;
    private static boolean halted = false;
    private static long nextActionMs = 0;
    private static long nextUpgradeMs = 0;
    private static int clicksThisSession = 0;
    private static int upgradesThisSession = 0;

    private record Candidate(int slot, long cost, double effectiveCost) {
    }

    private ChocolateFactoryFeature() {
    }

    static void tick(Minecraft client) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isChocolateEnabled() || client.player == null || client.gameMode == null) {
            endSession("disabled/no player");
            halted = false;
            return;
        }
        if (client.screen == null) {
            endSession("screen closed");
            if (halted) {
                halted = false;
                CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory halt cleared (all screens closed)");
            }
            return;
        }
        boolean isFactory = client.screen instanceof AbstractContainerScreen<?> s && TITLE.equals(s.getTitle().getString());
        if (!isFactory) {
            if (sessionActive) {
                String title = client.screen.getTitle().getString();
                CheatUtils.LOGGER.warn("[CheatUtils] ChocolateFactory unexpected screen '{}' ({}) - halting",
                        title, client.screen.getClass().getSimpleName());
                ModChat.send(CheatUtils.CHAT_TAG, ModChat.text("Chocolate Factory stopped: "),
                        ModChat.bad("unexpected screen"), ModChat.dim(" '" + title + "' - close it to re-arm"));
                endSession("unexpected screen");
                halted = true;
            }
            return;
        }
        if (halted) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.screen;
        AbstractContainerMenu menu = screen.getMenu();
        if (menu.slots.size() <= 45) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!sessionActive) {
            sessionActive = true;
            clicksThisSession = 0;
            upgradesThisSession = 0;
            nextActionMs = now + cfg.rollCfDelayMs();
            nextUpgradeMs = now + cfg.getCfUpgradeDelayMs();
            CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory session started (cookie={} upgrade={} strays={} timeTower={} delay={}-{}ms upgradeDelay={}ms)",
                    cfg.isCfClickCookie(), cfg.isCfAutoUpgrade(), cfg.isCfClaimStrays(), cfg.isCfAutoTimeTower(),
                    cfg.getCfMinDelayMs(), cfg.getCfMaxDelayMs(), cfg.getCfUpgradeDelayMs());
        }

        if (now >= nextActionMs) {
            nextActionMs = now + cfg.rollCfDelayMs();
            if (cfg.isCfAutoTimeTower() && shouldActivateTimeTower(menu.getSlot(39).getItem())) {
                click(client, menu, 39, 1, ContainerInput.PICKUP);
                CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory activated Time Tower");
            } else if (cfg.isCfClaimStrays() && clickStray(client, menu)) {
                // logged inside
            } else if (cfg.isCfClickCookie()) {
                click(client, menu, 13, 1, ContainerInput.PICKUP);
                clicksThisSession++;
            }
        }

        if (cfg.isCfAutoUpgrade() && now >= nextUpgradeMs) {
            nextUpgradeMs = now + cfg.getCfUpgradeDelayMs();
            long chocolate = parseDigits(CheatUtils.plainName(menu.getSlot(13).getItem()));
            Candidate best = findBestUpgrade(menu);
            if (best != null && chocolate >= best.cost()) {
                click(client, menu, best.slot(), 2, ContainerInput.CLONE);
                upgradesThisSession++;
                CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory bought slot {} '{}' cost={} (chocolate={}, cost/cps={})",
                        best.slot(), CheatUtils.plainName(menu.getSlot(best.slot()).getItem()), best.cost(), chocolate,
                        String.format(java.util.Locale.US, "%.1f", best.effectiveCost()));
            }
        }
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
    }

    private static boolean clickStray(Minecraft client, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            // Review fix (2026-09-15): factory slots only - never PICKUP an item out of the player's own
            // inventory just because its name happens to contain "Golden Rabbit".
            if (slot.container == client.player.getInventory()) {
                continue;
            }
            String name = CheatUtils.plainName(slot.getItem());
            if (name.contains("CLICK ME!") || name.contains("Golden Rabbit")) {
                click(client, menu, slot.index, 0, ContainerInput.PICKUP);
                CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory claimed stray '{}' (slot {})", name, slot.index);
                return true;
            }
        }
        return false;
    }

    private static void endSession(String why) {
        if (sessionActive) {
            CheatUtils.LOGGER.info("[CheatUtils] ChocolateFactory session ended ({}): {} cookie clicks, {} upgrades",
                    why, clicksThisSession, upgradesThisSession);
            sessionActive = false;
        }
    }

    private static Candidate findBestUpgrade(AbstractContainerMenu menu) {
        Double cps = firstMatchDouble(CheatUtils.lore(menu.getSlot(13).getItem()), CPS);
        Double totalMultiplier = firstMatchDouble(CheatUtils.lore(menu.getSlot(45).getItem()), TOTAL_MULT);
        if (cps == null || totalMultiplier == null || cps <= 0 || totalMultiplier <= 0) {
            return null;
        }
        double raw = cps / totalMultiplier;
        ItemStack timeTower = menu.getSlot(39).getItem();
        int ttLevel = parseTier(CheatUtils.plainName(timeTower));
        double baseMultiplier = Math.max(0.0, totalMultiplier - (isTimeTowerActive(timeTower) ? ttLevel * 0.1 : 0.0));
        if (raw <= 0 || baseMultiplier <= 0) {
            return null;
        }
        double average = raw * baseMultiplier;
        Candidate best = null;
        for (Map.Entry<Integer, Integer> e : RABBIT_SLOT_GAINS.entrySet()) {
            best = better(best, e.getKey(), parseCost(menu.getSlot(e.getKey()).getItem()),
                    average, (raw + e.getValue()) * baseMultiplier);
        }
        best = better(best, 39, parseCost(timeTower), average, raw * baseMultiplier + raw * 0.1 / 8.0);
        best = better(best, 42, parseCost(menu.getSlot(42).getItem()), average, raw * (baseMultiplier + 0.01));
        return best;
    }

    private static Candidate better(Candidate best, int slot, Long cost, double average, double newAverage) {
        if (cost == null) {
            return best;
        }
        double extra = newAverage - average;
        if (extra <= 0) {
            return best;
        }
        Candidate c = new Candidate(slot, cost, cost / extra);
        return best == null || c.effectiveCost() < best.effectiveCost() ? c : best;
    }

    private static Long parseCost(ItemStack stack) {
        List<String> lore = CheatUtils.lore(stack);
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Cost")) {
                if (i + 1 >= lore.size()) {
                    return null;
                }
                String digits = lore.get(i + 1).replaceAll("\\D", "");
                return digits.isEmpty() ? null : safeParseLong(digits);
            }
        }
        return null;
    }

    private static boolean isTimeTowerActive(ItemStack stack) {
        for (String line : CheatUtils.lore(stack)) {
            Matcher m = TT_STATUS.matcher(line);
            if (m.find()) {
                return "ACTIVE".equals(m.group(1));
            }
        }
        return false;
    }

    private static boolean shouldActivateTimeTower(ItemStack stack) {
        for (String line : CheatUtils.lore(stack)) {
            Matcher m = TT_CHARGES.matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1)) > 0 && !isTimeTowerActive(stack);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    private static Double firstMatchDouble(List<String> lines, Pattern p) {
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static long parseDigits(String s) {
        String digits = s.replaceAll("\\D", "");
        Long v = digits.isEmpty() ? null : safeParseLong(digits);
        return v == null ? 0L : v;
    }

    private static Long safeParseLong(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Roman numeral after the last space of the item name (QUOI parseUpgradeTier), 0 if none. */
    private static int parseTier(String name) {
        int idx = name.lastIndexOf(' ');
        if (idx < 0 || idx == name.length() - 1) {
            return 0;
        }
        String roman = name.substring(idx + 1);
        int total = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> -1;
            };
            if (v < 0) {
                return 0;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
