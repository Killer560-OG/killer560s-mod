package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dungeonbreaker charges, and putting the blocks back.
 *
 * <p>killer560 (2026-09-28): "make sure dungeon breaker blocks come back after broken just like on main and I
 * have dungeon breaker charges just like on main."
 *
 * <p>Both halves matter for practice and for opposite reasons. The charges are the constraint - a breaker route
 * that uses more charges than you have is not a route, and a sim with unlimited ones would let him rehearse
 * something he cannot do. The blocks coming back is what makes the room reusable: without it the second run
 * through a room is through a room he has already demolished, and every run after that is a different room.
 *
 * <p>Taken from the wiki (checked 2026-09-28): the Dungeonbreaker holds a small number of charges that refill
 * over time, and blocks it breaks in a dungeon return after a delay. The numbers live here as named constants
 * so they are one edit when he tells me the real ones - I would rather have them visibly in one place and
 * wrong than spread through the code and wrong.
 */
public final class SimBreakerState {

    // killer560's own numbers, 2026-09-30: "it only uses 1 dungeonbreaker charge per block broken and it
    // has a max of 20 charges and 2 come back each second" and "for sim make it so it breaks but instantly
    // comes back". These were placeholders before, guessed and flagged as such.

    /** Charges held at once. */
    private static final int MAX_CHARGES = 20;

    /**
     * Server ticks to regain one charge.
     *
     * <p>Two a second, so one every ten ticks.
     */
    private static final int RECHARGE_TICKS = 10;

    /**
     * Server ticks before a broken block comes back. Ten seconds.
     *
     * <p>From the item's own page, checked 2026-09-30: "While in The Catacombs, consume 1 charge to break a
     * block. 20 blocks can be broken at a time, and re-appear after 10s. 2 charges are regenerated each
     * second." All three numbers here are that sentence.
     *
     * <p>This was ONE tick, on his 2026-09-28 "for sim make it so it breaks but instantly comes back", and he
     * reversed it on 2026-09-30: "they insta come back and dont wait like they should". Ten seconds is the
     * real behaviour, and it is the part that makes a breaker route a route - a hole that closes behind you
     * is a timing problem, and one that never closes is not.
     */
    private static final int RESTORE_TICKS = 200;

    private static int charges = MAX_CHARGES;
    private static int rechargeCounter;

    /** A block waiting to be put back, with the state it had. */
    private record Broken(ServerLevel level, BlockPos pos, BlockState state, int dueAtTick) {
    }

    private static final Deque<Broken> PENDING = new ArrayDeque<>();
    private static int tickCounter;

    private SimBreakerState() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimBreakerState::tick);
    }

    /** Wipes charges and pending restores - for a new room or leaving the sim. */
    public static synchronized void reset() {
        charges = MAX_CHARGES;
        rechargeCounter = 0;
        PENDING.clear();
    }

    public static synchronized int charges() {
        return charges;
    }

    public static synchronized int maxCharges() {
        return MAX_CHARGES;
    }

    /**
     * Spends a charge, if there is one.
     *
     * @return whether the break may go ahead
     */
    public static synchronized boolean trySpend() {
        if (charges <= 0) {
            return false;
        }
        charges--;
        return true;
    }

    /**
     * The highest "Echoes of the Lost" level, which is also the most charges a secret can give back.
     *
     * <p>Source, checked 2026-10-06 on hypixelskyblock.minecraft.wiki/w/Essence_Shops/Wither: the Wither Essence
     * Shop perk "Echoes of the Lost" - "Restore 1 charge on your Dungeonbreaker after collecting a secret." - has
     * five levels whose "Increase" row reads 1, 2, 3, 4, 5, at 250, 500, 1,000, 2,000 and 5,000 Wither Essence.
     */
    public static final int MAX_SECRET_CHARGES = 5;

    /** Every level of the perk is on until he turns it down: the sim's default is the maxed shop. */
    public static final int DEFAULT_SECRET_CHARGES = MAX_SECRET_CHARGES;

    private static final java.nio.file.Path SECRET_FILE =
            com.killer560.hub.util.ModPaths.config("killer560smod-sim-breaker-secret-charges.txt");

    /** -1 until read. Guarded by the class monitor, like the charge count. */
    private static int secretCharges = -1;

    /**
     * Charges a secret gives back: the sim's "Echoes of the Lost" level, 0 (off) to {@link #MAX_SECRET_CHARGES}.
     * killer560 (2026-10-06): "the regaining charges should be an optional slider or setting somewhere. The main
     * server it is under the essence shop for either essence or for undead that gives charges back each secret".
     */
    public static synchronized int secretCharges() {
        if (secretCharges < 0) {
            secretCharges = DEFAULT_SECRET_CHARGES;
            try {
                if (java.nio.file.Files.exists(SECRET_FILE)) {
                    int v = Integer.parseInt(java.nio.file.Files
                            .readString(SECRET_FILE, java.nio.charset.StandardCharsets.UTF_8).trim());
                    secretCharges = Math.max(0, Math.min(MAX_SECRET_CHARGES, v));
                }
            } catch (Exception ignored) {
                // Unreadable means unknown, and the default is a safe unknown.
            }
        }
        return secretCharges;
    }

    public static synchronized void setSecretCharges(int level) {
        secretCharges = Math.max(0, Math.min(MAX_SECRET_CHARGES, level));
        try {
            java.nio.file.Files.createDirectories(SECRET_FILE.getParent());
            java.nio.file.Files.writeString(SECRET_FILE, String.valueOf(secretCharges),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Losing the preference is not worth failing the click he just made.
        }
    }

    /**
     * A secret of his was found (chest, item, bat, essence - every path that reaches {@code SimScore}'s secret count):
     * {@link #secretCharges()} charges back, never past the maximum. The lore follows on the next server tick.
     *
     * <p>The 2-a-second regeneration is the item page's own ("2 charges are regenerated each second"); the per-secret
     * restore is the Wither shop perk above. It was a flat 2 on his word until the perk was found.
     */
    public static synchronized void secretFound() {
        int before = charges;
        charges = Math.min(MAX_CHARGES, charges + secretCharges());
        if (charges != before) {
            com.killer560.hub.util.ModLog.get("killer560smod-sim").info("Sim Dungeonbreaker: secret found, charges {} -> {}",
                    before, charges);
        }
    }

    /** Remembers a block so it can be put back later. Called with the state BEFORE it was broken. */
    public static synchronized void remember(ServerLevel level, BlockPos pos, BlockState state) {
        PENDING.add(new Broken(level, pos.immutable(), state, tickCounter + RESTORE_TICKS));
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        java.util.List<Broken> due = new java.util.ArrayList<>();
        int chargesNow;
        synchronized (SimBreakerState.class) {
            tickCounter++;
            if (charges < MAX_CHARGES && ++rechargeCounter >= RECHARGE_TICKS) {
                rechargeCounter = 0;
                charges++;
            }
            while (!PENDING.isEmpty() && PENDING.peek().dueAtTick() <= tickCounter) {
                due.add(PENDING.poll());
            }
            chargesNow = charges;
        }
        if (SimState.isActive() && (chargesNow != loreCharges || tickCounter % 20 == 0)) {
            loreCharges = chargesNow;
            writeChargesToLore(server, chargesNow);
        }
        // Restored outside the lock: setBlock can run arbitrary block logic and holding a lock across that is
        // how a deadlock gets written.
        for (Broken b : due) {
            if (b.level().getBlockState(b.pos()).isAir()) {
                // Only if nothing has taken its place. Putting a block back on top of something he built, or
                // inside him, would be worse than leaving the hole.
                b.level().setBlockAndUpdate(b.pos(), b.state());
            }
        }
    }

    /** The charge count last written into the item's lore, or -1 before the first write. Server thread. */
    private static int loreCharges = -1;

    private static final java.util.regex.Pattern CHARGES_LINE = java.util.regex.Pattern.compile("Charges: \\d+/\\d+");

    /**
     * Keeps the "Charges: N/M" line of every Dungeon Breaker the player holds equal to the real count.
     *
     * <p>On Hypixel the charge count lives in the item's lore and nowhere else, and that is where Auto Routes'
     * breaker node and the 0-ping Dungeon Breaker read it. The sim's item carried a static "Charges" line, so Auto
     * Routes had a sim-only branch reading this class instead. The sim now updates the lore the way Hypixel's server
     * does, and both read the same line in both places. Changed on the server's stack, so the ordinary inventory sync
     * carries it to the client.
     */
    private static void writeChargesToLore(net.minecraft.server.MinecraftServer server, int count) {
        net.minecraft.network.chat.Component line = com.killer560.hub.profileviewer.item.LegacyText.parse(
                "§8Charges: " + count + "/" + MAX_CHARGES);
        String want = line.getString();
        for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
            for (net.minecraft.world.item.ItemStack stack : sp.getInventory().getNonEquipmentItems()) {
                if (stack.isEmpty() || !"DUNGEONBREAKER".equals(
                        com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack))) {
                    continue;
                }
                var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
                if (lore == null) {
                    continue;
                }
                java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>(lore.lines());
                boolean changed = false;
                for (int i = 0; i < lines.size(); i++) {
                    String text = lines.get(i).getString();
                    if (CHARGES_LINE.matcher(text).find() && !text.equals(want)) {
                        lines.set(i, line);
                        changed = true;
                    }
                }
                if (changed) {
                    stack.set(net.minecraft.core.component.DataComponents.LORE,
                            new net.minecraft.world.item.component.ItemLore(lines, lines));
                }
            }
        }
    }

    /** Tells him where he stands, the way the real item's lore does. */
    public static void announce() {
        ModChat.send("Sim", ModChat.text("Dungeonbreaker charges: "),
                ModChat.value(charges() + "/" + maxCharges()));
    }
}
