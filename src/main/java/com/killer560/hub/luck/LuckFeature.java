package com.killer560.hub.luck;

import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How Lucky Are You / Full RNG Tracker (killer560, to-do Doc 2026-11-01, pre-2.0): every drop he gets against the drops
 * the odds say he should have got. {@link LuckDetector} finds attempts and drops, {@link LuckStore} keeps them in the
 * shared store, {@link LuckScreen} shows them, {@link LuckShare} trades summaries with other mod users who opted in.
 *
 * <p>Commands: {@code /luck} opens the screen (also {@code /profit luck}); {@code /luck calc <attempts> <magicFind>
 * <drop>} prints the expected drops and the chance of at least one, for every table entry with that name.
 */
public final class LuckFeature {

    private LuckFeature() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        LuckConfig.getInstance();
        LuckDetector.register();
        LuckShare.register();
        SharedRecords.onAccountChange(LuckStore::load);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("luck")
                        .executes(ctx -> {
                            open();
                            return 1;
                        })
                        .then(ClientCommands.literal("calc")
                                .then(ClientCommands.argument("attempts", IntegerArgumentType.integer(1, 100_000_000))
                                        .then(ClientCommands.argument("magicFind",
                                                        IntegerArgumentType.integer(0, LuckConfig.MAX_MAGIC_FIND))
                                                .then(ClientCommands.argument("drop", StringArgumentType.greedyString())
                                                        .suggests((ctx, builder) -> {
                                                            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                                            for (String n : names()) {
                                                                if (n.toLowerCase(Locale.ROOT).startsWith(typed)) {
                                                                    builder.suggest(n);
                                                                }
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> calc(IntegerArgumentType.getInteger(ctx, "attempts"),
                                                                IntegerArgumentType.getInteger(ctx, "magicFind"),
                                                                StringArgumentType.getString(ctx, "drop")))))))));
    }

    private static Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (DropTable.Drop d : DropTable.get().drops()) {
            if (d.tracked()) {
                out.add(d.name());
            }
        }
        return out;
    }

    public static void open() {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /profit: the chat screen closing after the command would replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new LuckScreen(null)));
    }

    /** {@code /luck calc}: one chat line per table entry called {@code drop}. Returns how many it printed. */
    static int calc(int attempts, int magicFind, String drop) {
        List<String> lines = calcLines(attempts, magicFind, drop);
        if (lines.isEmpty()) {
            ModChat.send(LuckDetector.FEATURE, ModChat.bad("No drop called \"" + drop + "\" in the table."));
            return 0;
        }
        for (String line : lines) {
            ModChat.send(LuckDetector.FEATURE, ModChat.text(line));
        }
        return lines.size();
    }

    static List<String> calcLines(int attempts, int magicFind, String drop) {
        List<String> out = new java.util.ArrayList<>();
        for (DropTable.Drop d : DropTable.get().named(drop)) {
            if (!d.tracked() || out.size() >= 8) {
                continue;
            }
            double c = d.mf() ? LuckMath.withMagicFind(d, magicFind) : LuckScreen.chanceShown(d);
            out.add(String.format(Locale.US, "%s (%s, %s): %s expected in %,d, %.1f%% for at least one",
                    d.name(), DropTable.get().activity(d.activity()).label(), LuckMath.oneIn(c),
                    LuckMath.expected(c * attempts), attempts, LuckMath.atLeastOne(c, attempts) * 100));
        }
        return out;
    }
}
