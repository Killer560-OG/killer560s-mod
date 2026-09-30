package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Getting around inside the dungeon sim: {@code /goto <room>} and {@code /c <blocks>}.
 *
 * <p>killer560 (2026-09-30): "add the command /tpto or /goto ___ the blank is a room name that can tab autofill
 * for the current map and teleports you to it", and "add /c (number) that clips me that many blocks in that
 * direction."
 *
 * <p>Both names are registered for the room jump, the way {@link SimItems} registers {@code /simitem} and
 * {@code /item}: he asked for either name, and a name he has to remember is a name he will type wrong.
 *
 * <p><b>Every command here is gated with {@code requires(...)}, not merely guarded inside the body.</b> A client
 * command claims its NAME globally, so an ungated {@code /c} would swallow whatever Hypixel does with that name
 * for the whole session. With the gate the command does not exist outside the sim at all - it does not
 * tab-complete there and the server's own command still runs. That is the same reasoning that let the item
 * command take the short name {@code /item}.
 *
 * <p><b>Why these may write positions, and why nothing outside {@code roomsim} may copy them.</b> The standing
 * rule in this mod is never to write position or velocity ({@code setPos}, {@code setDeltaMovement}): Hypixel
 * reconstructs which key combination could have produced a movement delta and lags you back when none can, which
 * is why AP3 steers with discrete key presses and nothing else. That rule is about a server that is not ours. In
 * the sim the "server" is the client's own integrated server, no packet reaches Hypixel, and a teleport is simply
 * a teleport. The gate on {@link SimState#canAct} is what makes that true rather than hoped for - it requires a
 * singleplayer server AND no connected server - and it is the single safety boundary of this package. Do not
 * lift this pattern out of {@code roomsim}; outside it, it is a ban.
 */
public final class SimTeleportCommands {

    /**
     * The furthest a single clip may move him.
     *
     * <p>The sim's grid is 11 cells of 32 blocks, so nothing on a floor is more than about 350 blocks away.
     * A bound stops a typo ({@code /c 100000}) throwing him into chunks the integrated server would then have
     * to generate, and Brigadier reports it before the command ever runs.
     */
    private static final double MAX_CLIP = 512.0;

    private SimTeleportCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}, alongside the other sim registrations. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            // Registered twice, as /goto and /tpto - killer560 asked for "either". Both are the same node
            // built twice rather than a redirect, because a redirect to a gated node does not tab-complete
            // its argument reliably and the whole point of this command is the completion.
            for (String name : new String[]{"goto", "tpto"}) {
                dispatcher.register(ClientCommands.literal(name)
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.argument("room", StringArgumentType.greedyString())
                                // Greedy, because room names have spaces in them - "Higher Blaze", "Lava
                                // Ravine", "Quartz Knight". A single-word argument would stop at the space and
                                // the second word would be parsed as a command that does not exist.
                                .suggests((ctx, builder) -> suggestRooms(builder))
                                .executes(ctx -> goTo(Minecraft.getInstance(),
                                        StringArgumentType.getString(ctx, "room"))))
                        .executes(ctx -> {
                            ModChat.send("Sim", ModChat.dim("/" + name + " <room> - rooms on this floor: "
                                    + String.join(", ", roomNames())));
                            return 1;
                        }));
            }
            dispatcher.register(ClientCommands.literal("c")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .then(ClientCommands.argument("blocks",
                                    DoubleArgumentType.doubleArg(-MAX_CLIP, MAX_CLIP))
                            .executes(ctx -> clip(Minecraft.getInstance(),
                                    DoubleArgumentType.getDouble(ctx, "blocks"))))
                    .executes(ctx -> {
                        ModChat.send("Sim", ModChat.dim("/c <blocks> - clip along your look vector, "
                                + "negative goes backwards"));
                        return 1;
                    }));
        });
    }

    // ---------------------------------------------------------------- completion

    /**
     * The distinct rooms on the floor he is standing in, in paste order.
     *
     * <p>Not the whole library. {@link SimRoomIndex} is rebuilt by every build path and holds one
     * {@link SimRoomIndex.Placed} per placement, so it is the only thing that knows which twenty-odd of the 135
     * captured rooms are actually on the ground right now - and offering him a room that is not there would be a
     * completion that cannot be executed.
     *
     * <p>Distinct by name, and a {@link LinkedHashSet} so paste order survives: a floor can hold two placements
     * of the same template and the second entry would be a duplicate line in the list with no way to pick it.
     */
    private static List<String> roomNames() {
        Set<String> out = new LinkedHashSet<>();
        for (SimRoomIndex.Placed p : SimRoomIndex.placed()) {
            if (p.name() != null && !p.name().isBlank()) {
                out.add(p.name());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Completion for the room argument.
     *
     * <p>Matched on PREFIX first and then on any substring, because a greedy argument hands the whole of what he
     * has typed to the matcher: with "bla" typed, prefix matching alone offers nothing at all, while the rooms he
     * means are "Higher Blaze" and "Lower Blaze". Prefix matches are offered first so the common case - typing
     * the start of the name - is not buried under substring hits.
     *
     * <p>{@code builder.getRemaining()} is the whole rest of the line for a greedy argument, spaces included, so
     * a half-typed second word still matches and the suggestion replaces everything from the start of the
     * argument. That is what makes "Higher B" complete to "Higher Blaze" rather than to "Higher BHigher Blaze".
     */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestRooms(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        List<String> names = roomNames();
        List<String> ordered = new ArrayList<>();
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).startsWith(typed)) {
                ordered.add(n);
            }
        }
        for (String n : names) {
            if (!ordered.contains(n) && n.toLowerCase(Locale.ROOT).contains(typed)) {
                ordered.add(n);
            }
        }
        // suggest() directly rather than SharedSuggestionProvider.suggest(...), which would re-filter on prefix
        // and throw the substring matches away again.
        for (String n : ordered) {
            builder.suggest(n);
        }
        return builder.buildFuture();
    }

    // ---------------------------------------------------------------- /goto

    /**
     * Puts him in the named room on the current floor.
     *
     * <p>The landing spot is not computed here. {@link SimBuilder#snapPlayerTo} already answers "somewhere in
     * this room he can actually stand" - it scans the cell's centre column from the bottom of the world up for a
     * solid block with two blocks of air on it, which is the same test that decides whether an etherwarp is
     * legal - and it is the behaviour he already gets when a single room is built. A second copy of that scan
     * would be a second place for the three bugs it has already had (the roof, the basement floor, the negative
     * y sentinel) to come back.
     *
     * <p>It is package-private rather than public, so this call needs no change to {@link SimBuilder}.
     */
    private static int goTo(Minecraft client, String wanted) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Only works inside the sim."));
            return 1;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return 1;
        }
        List<SimRoomIndex.Placed> placed = SimRoomIndex.placed();
        if (placed.isEmpty()) {
            ModChat.send("Sim", ModChat.dim("No floor is loaded yet - nothing to go to."));
            return 1;
        }
        SimRoomIndex.Placed target = resolve(placed, wanted);
        if (target == null) {
            ModChat.send("Sim", ModChat.text("No room called "), ModChat.value(wanted),
                    ModChat.dim(" on this floor. Rooms: " + String.join(", ", roomNames())));
            return 1;
        }
        ServerLevel level = server.overworld();
        // gridX/gridZ is the room's top-left cell, which is the cell every build path already snaps to for the
        // room it placed there - so a multi-tile room lands in the same corner it lands in when it is built on
        // its own, rather than in whichever tile a second definition of "the middle" happened to pick.
        SimBuilder.snapPlayerTo(client, level, target.gridX(), target.gridZ());
        ModChat.send("Sim", ModChat.text("Teleported to "), ModChat.value(target.name()));
        return 1;
    }

    /**
     * Which placed room he meant.
     *
     * <p>Exact name first (case-insensitive), then a unique prefix, then a unique substring. Ambiguity is
     * REFUSED rather than guessed: "blaze" matches both halves of the blaze pair and dropping him in the wrong
     * one is worse than making him type another letter.
     */
    private static SimRoomIndex.Placed resolve(List<SimRoomIndex.Placed> placed, String wanted) {
        String want = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) {
            return null;
        }
        for (SimRoomIndex.Placed p : placed) {
            if (p.name() != null && p.name().toLowerCase(Locale.ROOT).equals(want)) {
                return p;
            }
        }
        SimRoomIndex.Placed prefix = unique(placed, want, true);
        if (prefix != null) {
            return prefix;
        }
        return unique(placed, want, false);
    }

    /** The one placed room whose name prefixes/contains {@code want}, or null when none or several do. */
    private static SimRoomIndex.Placed unique(List<SimRoomIndex.Placed> placed, String want, boolean asPrefix) {
        SimRoomIndex.Placed hit = null;
        for (SimRoomIndex.Placed p : placed) {
            if (p.name() == null) {
                continue;
            }
            String lower = p.name().toLowerCase(Locale.ROOT);
            boolean matches = asPrefix ? lower.startsWith(want) : lower.contains(want);
            if (!matches) {
                continue;
            }
            // Two PLACEMENTS of the same template are not an ambiguity - either will do, and refusing there
            // would make a duplicated room unreachable by name.
            if (hit != null && !hit.name().equalsIgnoreCase(p.name())) {
                return null;
            }
            if (hit == null) {
                hit = p;
            }
        }
        return hit;
    }

    // ---------------------------------------------------------------- /c

    /**
     * Moves him {@code blocks} along the direction he is looking. Negative goes backwards.
     *
     * <p>"Clip" is taken at its word: no collision walk, so it goes through whatever is in the way. That is the
     * point of it in a practice world - reaching the other side of a wall to set up a jump is exactly the thing
     * a collision check would refuse.
     *
     * <p>The y is clamped into the world. A clip straight down from the floor of a bottom-aligned map would
     * otherwise put him below the void, where there is nothing to land on and nothing to see.
     */
    private static int clip(Minecraft client, double blocks) {
        if (!SimState.canAct(client) || client.player == null) {
            ModChat.send("Sim", ModChat.text("Only works inside the sim."));
            return 1;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return 1;
        }
        Vec3 look = client.player.getLookAngle();
        Vec3 from = client.player.position();
        double x = from.x + look.x * blocks;
        double z = from.z + look.z * blocks;
        double rawY = from.y + look.y * blocks;
        ServerLevel level = server.overworld();
        double y = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 2, rawY));
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        var uuid = client.player.getUUID();
        // Server thread, and the SERVER's copy of the player.
        //
        // Not client.player.setPos: the integrated server holds the authoritative position, so moving only the
        // client entity is a desync the very next tick undoes - the same correction Hypixel would apply, for the
        // same reason. Empty relative set keeps yaw and pitch, because a clip that also spun the camera would be
        // useless for lining a jump up.
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            // Cast rather than serverLevel(): ServerPlayer has no serverLevel() accessor in 26.1.2, and a
            // ServerPlayer's level is always a ServerLevel.
            sp.teleportTo((ServerLevel) sp.level(), x, y, z, Set.<Relative>of(), yaw, pitch, false);
        });
        if (y != rawY) {
            ModChat.send("Sim", ModChat.text("Clipped "), ModChat.value(format(blocks)),
                    ModChat.dim(" block(s) - height clamped to the world"));
        }
        return 1;
    }

    /** "4" rather than "4.0" for a whole number, which is what he will nearly always type. */
    private static String format(double blocks) {
        return blocks == Math.rint(blocks)
                ? String.valueOf((long) blocks)
                : String.valueOf(blocks);
    }
}
