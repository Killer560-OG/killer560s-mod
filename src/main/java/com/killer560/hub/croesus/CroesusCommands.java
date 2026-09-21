package com.killer560.hub.croesus;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

import java.util.Map;

/**
 * The {@code /croesus} command tree (killer560, 2026-09-20: "Remove the session and all time text inside the
 * mod menu, instead i should type /croesus profit session or all to see it" - and {@code /croesus} on its own
 * opens the tracker).
 * <ul>
 *   <li>{@code /croesus} - opens {@link CroesusTrackerScreen} (totals, item log, big drops).</li>
 *   <li>{@code /croesus profit session} / {@code /croesus profit all} - prints those totals in chat.</li>
 *   <li>{@code /croesus items} / {@code /croesus drops} - opens the tracker on that view.</li>
 * </ul>
 * Registered as its own root, the same way {@code /ar} and {@code /posmsg} are - it is the syntax killer560
 * typed. Spelled "croesus" throughout, never his "croeseus" typo.
 */
public final class CroesusCommands {

    public static final String FEATURE = "Croesus";

    private CroesusCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("croesus")
                        .executes(context -> open(CroesusTrackerScreen.View.TOTALS))
                        .then(ClientCommands.literal("items")
                                .executes(context -> open(CroesusTrackerScreen.View.ITEMS)))
                        .then(ClientCommands.literal("drops")
                                .executes(context -> open(CroesusTrackerScreen.View.DROPS)))
                        .then(ClientCommands.literal("profit")
                                .executes(context -> open(CroesusTrackerScreen.View.TOTALS))
                                .then(ClientCommands.literal("session")
                                        .executes(context -> print("Session", CroesusProfitLog.session())))
                                .then(ClientCommands.literal("all")
                                        .executes(context -> print("All-time", CroesusProfitLog.allTime()))))));
    }

    private static int open(CroesusTrackerScreen.View view) {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /killer560 and /k560search - the chat screen closing after the command would
        // otherwise replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new CroesusTrackerScreen(null, view)));
        return 1;
    }

    private static int print(String heading, Map<String, CroesusProfitLog.Totals> totals) {
        if (totals.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text(heading + ": "), ModChat.dim("nothing claimed yet."));
            return 1;
        }
        ModChat.send(FEATURE, ModChat.colored(heading, ModChat.ORANGE),
                ModChat.dim("  (" + CroesusProfitLog.entryCount() + " claims logged)"));
        for (Map.Entry<String, CroesusProfitLog.Totals> e : totals.entrySet()) {
            CroesusProfitLog.Totals t = e.getValue();
            ModChat.send(FEATURE, ModChat.value(e.getKey()),
                    ModChat.dim(": " + t.chests + " chests, cost "),
                    ModChat.text(DungeonChestValuer.formatCoins(t.cost)),
                    ModChat.dim(", value "),
                    ModChat.text(DungeonChestValuer.formatCoins(t.value)),
                    ModChat.dim(", profit "),
                    t.profit >= 0 ? ModChat.good("+" + DungeonChestValuer.formatCoins(t.profit))
                            : ModChat.bad(DungeonChestValuer.formatCoins(t.profit)));
        }
        return 1;
    }
}
