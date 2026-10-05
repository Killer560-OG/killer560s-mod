package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The parts of survival mode that get in the way of practising, taken back out.
 *
 * <p>killer560 (2026-09-28): "make it so i dont take fall damage. also let me do /fly to fly. If i ever die i
 * shouldd be teleported back to the middle of the room."
 *
 * <p>All three follow from asking for survival instead of creative, and they are the right corrections rather
 * than a retreat from it. Survival is wanted because creative flight lets a route skip the jumps it exists to
 * rehearse - the point was never to be punished for rehearsing them. A drop that would cost half a health bar
 * on Hypixel is part of a route; dying to it in a practice room just means walking back.
 *
 * <p>So: fall damage off, flight on demand rather than always, and death turned into a trip back to the middle
 * of the room instead of a respawn screen. Cancelling the death rather than letting it happen and respawning
 * afterwards keeps the run's own state intact - a respawn would move him to the world spawn, hundreds of
 * blocks from the room, which is the exact problem the snap-to-room fix existed to solve.
 */
public final class SimSurvival {

    /** Where to put him back. Set when a room is built, so it is always the room he is actually in. */
    private static BlockPos homePoint;

    private SimSurvival() {
    }

    /** Remembered by the builder once it knows where the room's floor is. */
    public static void setHome(BlockPos pos) {
        homePoint = pos == null ? null : pos.immutable();
    }

    public static void register() {
        // No fall damage. Checked per damage rather than by resetting fallDistance every tick: resetting it
        // silently changes what the game thinks happened, while refusing the damage leaves everything else -
        // the landing, the sound, the animation - exactly as it was.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer) || !SimState.isActive()) {
                return true;
            }
            // The whole fall and fire TAGS rather than DamageTypes.FALL alone: IS_FIRE is what carries lava
            // (killer560, 2026-10-04: "Make it so lava doesn't do damage"), and IS_FALL is the set vanilla's own
            // FALL_DAMAGE rule switches off. SimWorld.freezeWorld turns both game rules off as well.
            return !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                    && !source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE);
        });

        // Put the flames out too. Lava still sets him alight even when the burn does nothing, and a screen
        // full of fire in a practice room is the damage's noise without the damage.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive()) {
                return;
            }
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.isOnFire()) {
                    sp.clearFire();
                }
                // The sim runs on EASY now (SimWorld), where hunger drains; PEACEFUL used to refill it. A route
                // practised for half an hour must not lose sprint to an empty food bar.
                if (sp.getFoodData().getFoodLevel() < 20) {
                    sp.getFoodData().eat(20, 1.0f);
                }
            }
        });

        // Death goes back to the middle of the room instead of to a respawn screen.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player) || !SimState.isActive()) {
                return true;
            }
            player.setHealth(player.getMaxHealth());
            player.getFoodData().eat(20, 1.0f);
            player.clearFire();
            player.fallDistance = 0;
            BlockPos home = homePoint;
            if (home != null && player.level() instanceof ServerLevel level) {
                player.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                        java.util.Set.of(), player.getYRot(), player.getXRot(), false);
            }
            ModChat.send("Sim", ModChat.dim("back to the middle of the room"));
            return false;
        });

        // Flight has to be re-asserted: the server resets abilities on respawn, on dimension change and
        // whenever it feels like syncing them, so setting the flag once does not keep it.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!flying || !SimState.isActive()) {
                return;
            }
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (!sp.getAbilities().mayfly) {
                    sp.getAbilities().mayfly = true;
                    sp.onUpdateAbilities();
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                // Sim-only, like /map: an ungated /fly would claim the name on Hypixel, where the server has
                // its own idea of what that command means.
                dispatcher.register(ClientCommands.literal("fly")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            toggle();
                            return 1;
                        })));
    }

    private static boolean flying;

    private static void toggle() {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        flying = !flying;
        boolean on = flying;
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                sp.getAbilities().mayfly = on;
                if (!on) {
                    // Dropped as well as disallowed, or he stays hovering with no way down.
                    sp.getAbilities().flying = false;
                }
                sp.onUpdateAbilities();
            }
        });
        ModChat.send("Sim", ModChat.text("Flight "), ModChat.value(on ? "ON" : "OFF"));
    }

    /** Turns flight back off and forgets the room, for leaving the sim. */
    public static void reset() {
        flying = false;
        homePoint = null;
    }
}
