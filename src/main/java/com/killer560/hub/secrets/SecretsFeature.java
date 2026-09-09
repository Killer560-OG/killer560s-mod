package com.killer560.hub.secrets;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
 *  Always gated on "connected to a server this mod's dungeon features are meant to run on" (hypixel.net
 *  or p3sim.net, per the roadmap's own server-gating note) plus each block type's own toggle. Two
 *  further OPTIONAL gates, per killer560's explicit follow-up request (2026-09-09) - see
 *  {@link DungeonState} for how each is really detected:
 *  <ul>
 *  <li>"Dungeons Only" (all 4 types) - only expand while actually inside a Catacombs/Master Mode run,
 *  not just anywhere on those servers.
 *  <li>"Boss Only" (Levers/Buttons only) - only expand during the real F7/M7 boss fight specifically.
 *  This is this mod's own answer to the same real edge case NoammAddons solves with a per-floor lever
 *  blacklist (some F7 puzzle levers need their real narrow hitbox to avoid misclicks) - instead of
 *  maintaining a blacklist of known-precise lever positions, Boss Only simply never touches ANY lever
 *  outside the boss fight, sidestepping the whole problem.
 *  </ul>
 *  Both default off, matching every other toggle here - expanding hitboxes anywhere on those servers
 *  remains the default (simpler) behavior unless killer560 opts into tighter gating. */
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
        SecretsConfig cfg = SecretsConfig.getInstance();
        if (!isOnDungeonServer() || !cfg.isLeversEnabled() || !passesDungeonsOnlyGate(cfg)) {
            return false;
        }
        return !cfg.isBossOnly() || DungeonState.isBossPhaseActive();
    }

    public static boolean shouldExpandButtons() {
        SecretsConfig cfg = SecretsConfig.getInstance();
        if (!isOnDungeonServer() || !cfg.isButtonsEnabled() || !passesDungeonsOnlyGate(cfg)) {
            return false;
        }
        return !cfg.isBossOnly() || DungeonState.isBossPhaseActive();
    }

    public static boolean shouldUseFullBoxButtons() {
        return SecretsConfig.getInstance().isButtonsFullBox();
    }

    public static boolean shouldExpandChests() {
        SecretsConfig cfg = SecretsConfig.getInstance();
        return isOnDungeonServer() && cfg.isChestsEnabled() && passesDungeonsOnlyGate(cfg);
    }

    public static boolean shouldExpandEssence() {
        SecretsConfig cfg = SecretsConfig.getInstance();
        return isOnDungeonServer() && cfg.isEssenceEnabled() && passesDungeonsOnlyGate(cfg);
    }

    // Real player-head skin profile IDs for Wither Essence, per NoammAddons' own DungeonUtils.isSecret
    // (decompiled 2026-09-09) - a skull-type block on Hypixel is a real vanilla SkullBlock/WallSkullBlock
    // using a custom player-skin texture, not a distinct block type of its own, so the ONLY reliable way
    // to tell Wither Essence apart from any other decorative skull in a dungeon room is this skin check.
    // Missed on the first pass (quoi's own SkullBlockMixin/WallSkullBlockMixin expand every skull
    // unconditionally, no skin check) - caught cross-referencing NoammAddons specifically, per
    // killer560's "double check you missed nothing important" follow-up request.
    private static final Set<UUID> WITHER_ESSENCE_PROFILE_IDS = Set.of(
            UUID.fromString("2865274b-3097-394e-8149-ec629c72d850"),
            UUID.fromString("e0f3e929-869e-3dca-9504-54c666ee6f23")
    );

    public static boolean isWitherEssence(BlockGetter level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof SkullBlockEntity skull)) {
            return false;
        }
        ResolvableProfile owner = skull.getOwnerProfile();
        if (owner == null) {
            return false;
        }
        GameProfile partial = owner.partialProfile();
        return partial != null && partial.id() != null && WITHER_ESSENCE_PROFILE_IDS.contains(partial.id());
    }

    // Per killer560's explicit "dungeons only" request (2026-09-09) - shared by all 4 block types. Just
    // an independent AND condition alongside "Boss Only" (Levers/Buttons only, checked separately above)
    // - being in the F7/M7 boss phase already implies being in a dungeon, so having both on at once is
    // simply redundant, never contradictory.
    private static boolean passesDungeonsOnlyGate(SecretsConfig cfg) {
        return !cfg.isDungeonsOnly() || DungeonState.isInDungeon();
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
