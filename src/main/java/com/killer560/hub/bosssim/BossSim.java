package com.killer560.hub.bosssim;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.roomsim.SimWorld;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

import org.slf4j.Logger;

import java.util.Locale;

/**
 * The F7 boss in the dungeon sim: where it starts, and the server hooks it answers on.
 *
 * <p>Three ways in: the sim menu's F7 Boss entry ({@link BossStartScreen}), {@code /simboss [phase] [floor7|master]}, and
 * the floor's own way - once Blood is done, a portal in the Blood room ({@link BossPortal}) takes him into P1, as
 * Hypixel's does.
 *
 * <p>Everything here acts only inside the sim (the one gate the sim has: {@link SimState#canAct}, or a world the sim is
 * opening), and the server hooks answer only the integrated server's own player. On Hypixel none of it exists: the
 * command is not even in the tree outside the sim world, and nothing ticks.
 */
public final class BossSim {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final Identifier FIRST = Identifier.fromNamespaceAndPath("killer560smod", "bosssim_first");

    private static BossFight.Start pendingStart;
    private static boolean pendingMaster;
    private static boolean building;
    private static Object lastLevel;
    private static int outsideTicks;

    private BossSim() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BossSim::serverTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(BossFight::serverStopping);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BossSim.tick", BossSim::clientTick));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !serverOk(sp)) {
                return InteractionResult.PASS;
            }
            if (BossDevices.isArrowMark(entity)) {
                return InteractionResult.FAIL;
            }
            if (BossDevices.isArrowFrame(entity)) {
                BossDevices.noteActor(3, sp);
            }
            if (hand == InteractionHand.MAIN_HAND && BossFight.useEntity(sp, entity)) {
                return InteractionResult.SUCCESS;
            }
            BossStations.Station term = BossStations.terminalOf(entity);
            if (term == null && entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                LOGGER.info("[BossSim] a stand that is no terminal's was clicked at {}", entity.position());
            }
            if (term != null) {
                boolean done = BossStations.isDone(term);
                boolean live = term.section() == BossFight.section();
                LOGGER.info("[BossSim] S{} {} clicked ({}, {})", term.section(), term.label(), hand,
                        done ? "already done" : live ? "opening" : "not this section");
                if (hand == InteractionHand.MAIN_HAND) {
                    // Hypixel answers a stand click one tick later: the window, or one of its two refusals.
                    BossFight.later(1, "terminal open", () -> {
                        if (done) {
                            sp.sendSystemMessage(BossChat.legacy("§cThis Terminal has already been completed!"));
                        } else if (!live) {
                            sp.sendSystemMessage(BossChat.legacy("§cThis Terminal doesn't seem to be responsive at the moment."));
                        } else if (!BossStations.isDone(term)) {
                            BossTerminals.open(sp, term);
                        }
                    });
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !serverOk(sp)) {
                return InteractionResult.PASS;
            }
            if (BossDevices.isArrowFrame(entity) || BossStations.terminalOf(entity) != null) {
                return InteractionResult.FAIL;
            }
            if (entity instanceof EndCrystal) {
                BossFight.attackCrystal(sp, entity);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
        UseBlockCallback.EVENT.register(FIRST, (player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !serverOk(sp) || hit == null) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            BossDevices.noteBlockUse(sp, hit.getBlockPos());
            if (BossDevices.pullLight((ServerLevel) level, hit.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            BossStations.Station lever = BossStations.leverAt(hit.getBlockPos());
            if (lever != null) {
                if (BossFight.phase() == BossFight.Phase.P3) {
                    if (BossStations.isDone(lever)) {
                        sp.sendSystemMessage(BossChat.legacy("§cSomeone has already activated this lever!"));
                    } else if (lever.section() == BossFight.section()) {
                        BossStations.complete((ServerLevel) level, lever, sp);
                    } else {
                        // Another section's lever just toggles, with no credit.
                        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(hit.getBlockPos());
                        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
                            level.setBlock(hit.getBlockPos(), st.cycle(
                                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED), 3);
                        }
                    }
                }
                return InteractionResult.SUCCESS;
            }
            return BossFight.usePylon(sp) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseItemCallback.EVENT.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
        UseItemCallback.EVENT.register(FIRST, (player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !serverOk(sp) || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            return BossFight.usePylon(sp) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("simboss")
                    .requires(src -> {
                        Minecraft mc = Minecraft.getInstance();
                        return mc.getCurrentServer() == null && (mc.getSingleplayerServer() == null || SimState.canAct(mc));
                    })
                    .executes(ctx -> command("p1", null))
                    .then(ClientCommands.argument("phase", StringArgumentType.word())
                            .executes(ctx -> command(StringArgumentType.getString(ctx, "phase"), null))
                            .then(ClientCommands.argument("mode", StringArgumentType.word())
                                    .executes(ctx -> command(StringArgumentType.getString(ctx, "phase"),
                                            StringArgumentType.getString(ctx, "mode"))))));
        });
        BossPortal.register();
    }

    private static int command(String phase, String mode) {
        Minecraft mc = Minecraft.getInstance();
        String p = phase.toLowerCase(Locale.ROOT);
        if (p.equals("stop")) {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> BossFight.stop("/simboss stop"));
            }
            return 1;
        }
        BossFight.Start start = switch (p) {
            case "p1", "maxor" -> BossFight.Start.P1;
            case "p2", "storm" -> BossFight.Start.P2;
            case "p3", "s1", "goldor" -> BossFight.Start.S1;
            case "s2" -> BossFight.Start.S2;
            case "s3" -> BossFight.Start.S3;
            case "s4" -> BossFight.Start.S4;
            case "core" -> BossFight.Start.CORE;
            case "p4", "necron" -> BossFight.Start.P4;
            case "p5", "dragons" -> BossFight.Start.P5;
            default -> null;
        };
        if (start == null) {
            com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.text(
                    "Use /simboss p1|p2|s1|s2|s3|s4|core|p4|p5|stop [floor7|master]"));
            return 0;
        }
        boolean master = mode == null ? !"F7".equalsIgnoreCase(SimState.floorLabel()) || start == BossFight.Start.P5
                : mode.toLowerCase(Locale.ROOT).startsWith("m");
        // After the chat screen has closed, so the loading screen is not closed with it.
        mc.execute(() -> start(mc, start, master));
        return 1;
    }

    /** Builds the boss room (in the sim world, opening it if needed) and starts the fight at {@code from}. */
    public static void start(Minecraft client, BossFight.Start from, boolean master) {
        if (building) {
            return;
        }
        pendingStart = from;
        pendingMaster = master;
        LOGGER.info("[BossSim] start requested at {} ({})", from, master ? "Master Mode" : "Floor 7");
        if (SimState.canAct(client) && client.getSingleplayerServer() != null) {
            // Already in the sim (the floor's portal, or the menu from inside it): build beside the floor without
            // SimWorld.open, whose in-sim path resets the floor's per-map state (score, secrets, the run).
            com.killer560.hub.roomsim.SimWorld.showLoading(client, "Building the F7 boss");
            buildAndBegin(client);
        } else if (SimState.canOpen(client)) {
            SimWorld.open(client, "", BossSim::buildAndBegin, "Building the F7 boss");
        } else {
            pendingStart = null;
        }
    }

    private static void buildAndBegin(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pendingStart == null) {
            SimWorld.buildFinished(client, null);
            return;
        }
        BossFight.Start from = pendingStart;
        boolean master = pendingMaster;
        pendingStart = null;
        building = true;
        // A PC without his capture (a friend hosting co-op) fetches the published one first (coopnet-1110), off this thread.
        BossArena.fetchPublished().whenComplete((x, e) -> server.execute(() -> {
            // A phase change keeps his inventory and hotbar exactly as he left them (killer560, 2026-11-05: "i set up a
            // hotbar in p1 but then in p3 it was a different one"); the loadout is only for a start from outside a fight.
            // Every player's, by UUID (coopsim-1109): a guest's hotbar is his own too.
            boolean running = BossFight.phase() != BossFight.Phase.IDLE;
            BossFight.stop("rebuild");
            java.util.Map<java.util.UUID, net.minecraft.world.item.ItemStack[]> kept = new java.util.HashMap<>();
            for (ServerPlayer sp : running ? server.getPlayerList().getPlayers() : java.util.List.<ServerPlayer>of()) {
                net.minecraft.world.item.ItemStack[] inv = new net.minecraft.world.item.ItemStack[sp.getInventory().getContainerSize()];
                for (int i = 0; i < inv.length; i++) inv[i] = sp.getInventory().getItem(i).copy();
                kept.put(sp.getUUID(), inv);
            }
            long t0 = System.currentTimeMillis();
            // Out of the arena while it goes up (simspawn-1104): from a fresh world he stood at spawn, inside the box
            // being built, and from the floor every write was sent to his client as it landed.
            com.killer560.hub.roomsim.SimBuilder.holdPlayer(client, server.overworld());
            BossArena.build(server.overworld(), () -> {
                LOGGER.info("[BossSim] arena built from the {} in {} ms", BossArena.source(), System.currentTimeMillis() - t0);
                kept.forEach((uuid, inv) -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    for (int i = 0; sp != null && i < inv.length; i++) sp.getInventory().setItem(i, inv[i]);
                });
                BossFight.begin(server, from, master);
                building = false;
                client.execute(() -> SimWorld.buildFinished(client, "F7 boss"));
            });
        }));
    }

    /** The sim's server, acting for its own player. */
    static boolean serverOk(ServerPlayer sp) {
        Minecraft mc = Minecraft.getInstance();
        return SimState.canAct(mc) && mc.getSingleplayerServer() == sp.level().getServer() && BossFight.running();
    }

    private static void serverTick(MinecraftServer server) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != server || !SimState.isActive()) {
            return;
        }
        try {
            BossPortal.serverTick(server);
            BossFight.tick(server);
            BossTerminals.tick(server);
            leaveCheck(server);
        } catch (RuntimeException e) {
            LOGGER.error("[BossSim] server tick threw", e);
        }
    }

    /** The fight ends with the world: a new world drops its state (its entities went with the old server). */
    private static void clientTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (BossFight.running()) {
                // The old world's server is gone or going: drop the fight's state here (its entities went with it).
                BossFight.forget("world changed");
            }
            com.killer560.hub.secrets.DungeonState.simBossEnds();
        }
    }

    /**
     * Server thread: the fight ends once EVERY player has been outside the boss room for two seconds (back on the floor,
     * a new map); then the boss phase the clear features sit out for is over too. Read from the server's players, not
     * his client, so a guest still fighting keeps it going and a lone host leaving ends it as before (coopsim-1109).
     */
    private static void leaveCheck(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (!BossFight.running() || players.isEmpty()) {
            outsideTicks = 0;
            return;
        }
        boolean anyInside = false;
        for (ServerPlayer p : players) {
            anyInside |= insideBossRoom(p.getX(), p.getZ());
        }
        outsideTicks = anyInside ? 0 : outsideTicks + 1;
        if (outsideTicks == 40) {
            BossFight.stop("left the boss room");
            com.killer560.hub.secrets.DungeonState.simBossEnds();
        }
    }

    static boolean insideBossRoom(double x, double z) {
        return x > -7 && z > -7 && x < BossArena.X0 + BossArena.W && z < BossArena.Z0 + BossArena.D;
    }

    /** Testkit: whether a build is running. */
    public static boolean isBuilding() {
        return building || BossArena.building();
    }
}
