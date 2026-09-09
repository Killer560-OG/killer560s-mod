package com.killer560.hub.secrets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/** Expands the interaction hitbox of levers/buttons/chests/skulls (Wither Essence) so they're easier to
 *  click - killer560's explicit request (2026-09-09), building on the roadmap's "Full Block" item. Ported
 *  from BOTH real reference mods, cross-checked against each other (decompiled via CFR/javap, 2026-09-09):
 *  quoi's own {@code FullBlock}/{@code ButtonBlockMixin}/{@code ChestBlockMixin}/{@code LeverBlockMixin}/
 *  {@code SkullBlockMixin}/{@code WallSkullBlockMixin}, and NoammAddons' own {@code Secrets}/
 *  {@code MixinButtonBlock}/{@code MixinLeverBlock}/{@code MixinSkullBlock}. Both mods independently
 *  arrived at the same core trick for every block that has REAL solid collision (chests, skulls) -
 *  {@link com.killer560.hub.secrets.mixin.BlockBehaviourMixin} - that cross-confirmation is why it's
 *  included here too; see that class's own doc for why it matters.
 *  <p>
 *  Deliberately simpler than either reference in one way: neither an in-dungeon-room "is this actually a
 *  known secret" check (NoammAddons gates its Skull mixin on {@code DungeonUtils.isSecret(pos)}, which
 *  needs a whole secrets-position database this mod doesn't have yet) nor a per-floor lever blacklist
 *  (NoammAddons excludes certain lever positions on Floor 7). Gated only on "connected to a server this
 *  mod's dungeon features are meant to run on" (hypixel.net or p3sim.net, per the roadmap's own
 *  server-gating note) plus each block type's own toggle - expanding a lever/button/chest/skull hitbox
 *  anywhere on those servers is harmless even outside an active dungeon room, just occasionally
 *  unnecessary. Revisit with real room-awareness if this mod ever builds a secrets/room-position
 *  database for something else. */
public final class SecretsFeature {

    private SecretsFeature() {
    }

    /** Real bug found and fixed (2026-09-09): every one of these mixins' {@code getShape} overrides gets
     *  called far earlier than "the game is actually running" - {@code Blocks.<clinit>} calls
     *  {@code getShape} on every block state while bootstrapping the block registry itself, to precompute
     *  occlusion/collision caches, which happens before {@code Minecraft.getInstance()} even exists yet
     *  (confirmed via a real crash log: {@code NullPointerException... because the return value of
     *  "Minecraft.getInstance()" is null}, at {@code Blocks.<clinit> -> initCache -> getOcclusionShape ->
     *  getShape -> shouldExpandChests -> isOnDungeonServer}). Every one of the 5 block mixins here can run
     *  during that same bootstrap window, not just chests, so this null-guards defensively rather than
     *  fixing chests alone. */
    private static boolean isOnDungeonServer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) {
            return false;
        }
        String ip = server.ip.toLowerCase(Locale.US);
        return ip.contains("hypixel.net") || ip.contains("p3sim.net");
    }

    public static boolean shouldExpandLevers() {
        return isOnDungeonServer() && SecretsConfig.getInstance().isLeversEnabled();
    }

    public static boolean shouldExpandButtons() {
        return isOnDungeonServer() && SecretsConfig.getInstance().isButtonsEnabled();
    }

    public static boolean shouldUseFullBoxButtons() {
        return SecretsConfig.getInstance().isButtonsFullBox();
    }

    public static boolean shouldExpandChests() {
        return isOnDungeonServer() && SecretsConfig.getInstance().isChestsEnabled();
    }

    public static boolean shouldExpandEssence() {
        return isOnDungeonServer() && SecretsConfig.getInstance().isEssenceEnabled();
    }

    // Ported 1:1 from quoi's own ButtonBlockMixin (its real hardcoded per-face shapes, 2026-09-09) - the
    // "Flat" option: wider than vanilla's real narrow button hitbox (covers the whole attached face) but
    // still thin (an eighth-block deep), unlike the "Full Box" option which is the entire 1x1x1 cube.
    public static final VoxelShape BUTTON_FLOOR_SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0);
    public static final VoxelShape BUTTON_CEILING_SHAPE = Shapes.box(0.0, 0.875, 0.0, 1.0, 1.0, 1.0);
    public static final VoxelShape BUTTON_NORTH_SHAPE = Shapes.box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0);
    public static final VoxelShape BUTTON_SOUTH_SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125);
    public static final VoxelShape BUTTON_WEST_SHAPE = Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0);
    public static final VoxelShape BUTTON_EAST_SHAPE = Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0);
}
