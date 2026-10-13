package com.killer560.hub.etherwarpoverlay;

import com.killer560.hub.armourdye.mixin.CustomDataTagAccessor;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DryVegetationBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.ShortDryGrassBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.killer560.hub.compat.McCompat;

/**
 * Real Hypixel Skyblock Etherwarp landing-prediction overlay, ported from Odin's own {@code Etherwarp.kt}
 * - specifically just its real "Show Guess" box (the client-side-predicted-execution half of Odin's own
 * feature, which forcibly overrides the player's real position the instant Etherwarp is used, is
 * deliberately NOT ported here: getting a real voxel raycast even slightly wrong there could put the
 * player inside a wall or off a ledge, a real safety risk this pure-rendering version can't have since
 * it never touches the player's actual position - the real server-side Etherwarp still runs completely
 * unmodified).
 * <p>
 * Real mechanic: while holding a real Etherwarp-granting item (the Aspect of the Void / Etherwarp
 * Conduit, or any item with the real "ethermerge" NBT flag) and either holding shift or holding the
 * Conduit specifically, this raycasts a real voxel traversal along your exact look direction up to a
 * real max distance (57 blocks, extended by the real "tuned_transmission" reforge/enchant stat) using
 * the same real per-block-type passability rules Odin's own confirmed, shipped mod uses, and highlights
 * where you'd land - green if it's a real safe landing spot, red if it isn't.
 */
public final class EtherwarpOverlayFeature {

    private static final String ETHERWARP_CONDUIT_ID = "ETHERWARP_CONDUIT";

    private record EtherPos(boolean succeeded, BlockPos pos, BlockState state) {
        static final EtherPos NONE = new EtherPos(false, null, null);
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-etherwarpoverlay");

    /** What the last frame did: "drawn ..." or why it drew nothing (testkit reads it; one string write a frame). */
    private static volatile String lastOutcome = "never";

    private EtherwarpOverlayFeature() {
    }

    public static String lastOutcome() {
        return lastOutcome;
    }

    public static void register() {
        // COLLECT_SUBMITS, while the frame is still gathering what to draw. It was AFTER_TRANSLUCENT_FEATURES, which on
        // 26.2 runs after that frame's submitted geometry has been drawn, so the box was dropped on some frames and drawn
        // a frame late on others, from the previous frame's camera - behind the block while he walked into it
        // (killer560, 2026-10-13, 26.2). Testkit 1322 lost it on 4 of 27 walking frames there and on none after the move.
        LevelRenderEvents.COLLECT_SUBMITS.register(EtherwarpOverlayFeature::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        EtherwarpOverlayConfig cfg = EtherwarpOverlayConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || McCompat.screen(client) != null || client.player == null || client.level == null) {
            lastOutcome = "off";
            return;
        }

        ItemStack mainHand = client.player.getMainHandItem();
        if (!client.player.isShiftKeyDown()) {
            // Not sneaking: only the bare conduit shows the overlay (the test below), and that is an id read. Asked
            // first so whatever he is holding is not lore-scanned every frame just to be turned away (FPS sweep).
            CompoundTag held = com.killer560.hub.util.ItemNbt.view(mainHand);
            String heldId = held == null || !held.contains("id") ? null : held.getStringOr("id", null);
            if (!ETHERWARP_CONDUIT_ID.equals(heldId)) {
                lastOutcome = "not sneaking";
                return;
            }
        }
        CompoundTag etherData = getEtherwarpData(mainHand);
        if (etherData == null) {
            lastOutcome = "no etherwarp item";
            return;
        }
        // Read the id off the tag getEtherwarpData already copied (2026-09-20, FPS pass): the old
        // getSkyblockId(mainHand) helper deep-copied the held item's whole NBT a second time, every
        // frame an etherwarp item was in hand. That helper had no other caller and is gone.
        String diagItemId = etherData.contains("id") ? etherData.getStringOr("id", null) : null;
        boolean isConduit = ETHERWARP_CONDUIT_ID.equals(diagItemId);
        if (!client.player.isShiftKeyDown() && !isConduit) {
            lastOutcome = "not sneaking";
            return;
        }

        double distance = 57.0 + etherData.getIntOr("tuned_transmission", 0);
        EtherPos etherPos = getEtherPos(client.level, client.player.position(), client.player, distance);
        if (!etherPos.succeeded() && !cfg.isShowWhenFailed()) {
            lastOutcome = "failed " + etherPos.pos();
            return;
        }
        if (etherPos.pos() == null) {
            lastOutcome = "no target";
            return;
        }
        lastOutcome = "drawn " + etherPos.pos().toShortString() + (etherPos.succeeded() ? " ok" : " failed");

        AABB box = cfg.isFullBlock() ? new AABB(etherPos.pos()) : realBoxFor(client.level, etherPos.pos());
        // Pulled 1% of the way towards the camera, so lines drawn exactly on the block's own faces never tie with them
        // for depth. Scaling about the eye moves every point along its own sight line: the box lands on exactly the same
        // pixels and only its depth changes (growing the box instead pushed the lines visibly outside the face up close).
        box = towardsCamera(box, com.killer560.hub.compat.McRender.cameraPos(context));
        // Colour + style are user-picked since 2026-09-16 (killer560's testing feedback); the defaults are the
        // exact green/red outline this always drew. Style semantics copied from Simon Says so "Filled" and
        // "Filled+Outline" look the same across the mod: a plain fill uses the colour's own alpha, the combo
        // halves the fill so the outline still reads. Never let a render exception escape into the frame.
        try {
            float[] c = WorldRenderUtils.argbToFloats(etherPos.succeeded() ? cfg.getSafeColor() : cfg.getFailedColor());
            switch (cfg.getStyle()) {
                case FILLED -> WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], c[3]);
                case FILLED_OUTLINE -> {
                    WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], c[3] * 0.5f);
                    WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
                }
                default -> {
                    WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
                    Vec3 cam = com.killer560.hub.compat.McRender.cameraPos(context);
                    Direction near = nearFace(box, cam);
                    if (near != null) {
                        WorldRenderUtils.renderOutlineBox(context, nearFaceFrame(box, near, cam), c[0], c[1], c[2], 1f, 2f);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[EtherwarpOverlay] Render failed: {}", e.toString());
        }
    }

    /**
     * An outline is only its twelve edges, so pressed against the block - camera 0.3 from the face, which then fills
     * the whole view - all four edges of the face in front of him are off screen and the box shows nothing at all.
     * killer560's friend (2026-10-13): "for the box it doesnt draw on the face close to the player"; testkit 1321
     * measured it on 26.1.2, 26.2 and with Sodium (every edge sample off screen, every pixel of the face unchanged),
     * while every other camera-facing face drew at every angle and distance in both styles. So when the camera is
     * within {@link #NEAR_FACE_DISTANCE} of a face and inside its footprint (the face fills the view), that one face gets
     * a smaller outline on its own plane, shrunk around the camera so it stays on screen ({@link #nearFaceFrame}).
     * It was tinted first, and killer560 (2026-10-13, 26.2) did not want the shading: "I do not want the etherwarp
     * overlay to turn shaded red when I am close"; he wants the outline on the front face. Anywhere else the outline is
     * exactly what it always was.
     */
    static final double NEAR_FACE_DISTANCE = 0.85;
    /** Half-width of the near-face outline per block of distance: 0.6 is a 31-degree half angle, inside any FOV. */
    private static final double NEAR_FACE_REACH = 0.6;

    /**
     * The flat box (zero thickness on the face's axis) outlining the visible part of {@code face}: centred on where the
     * camera looks straight at the face, at most NEAR_FACE_REACH x distance either side, never past the face's own edges,
     * and a hair in front of the plane so it never z-fights the block.
     */
    static AABB nearFaceFrame(AABB box, Direction face, Vec3 cam) {
        Direction.Axis axis = face.getAxis();
        double plane = (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis))
                + face.getAxisDirection().getStep() * 0.003;
        double dist = Math.abs(cam.get(axis) - plane);
        double reach = Math.min(NEAR_FACE_REACH * dist, 0.5);
        double[] lo = new double[3];
        double[] hi = new double[3];
        for (Direction.Axis a : Direction.Axis.values()) {
            if (a == axis) {
                lo[a.ordinal()] = plane;
                hi[a.ordinal()] = plane;
                continue;
            }
            double min = box.min(a);
            double max = box.max(a);
            double half = Math.min(reach, (max - min) / 2.0);
            double centre = Math.max(min + half, Math.min(max - half, cam.get(a)));
            lo[a.ordinal()] = centre - half;
            hi[a.ordinal()] = centre + half;
        }
        return new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** The box face the camera is pressed up against (within its footprint, nearest first), or null. */
    static Direction nearFace(AABB box, Vec3 cam) {
        Direction best = null;
        double bestDist = NEAR_FACE_DISTANCE;
        for (Direction d : Direction.values()) {
            Direction.Axis axis = d.getAxis();
            double plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
            double dist = (cam.get(axis) - plane) * d.getAxisDirection().getStep();
            if (dist <= 0 || dist > bestDist) {
                continue;
            }
            boolean inFront = true;
            for (Direction.Axis other : Direction.Axis.values()) {
                if (other != axis && (cam.get(other) < box.min(other) || cam.get(other) > box.max(other))) {
                    inFront = false;
                }
            }
            if (inFront) {
                best = d;
                bestDist = dist;
            }
        }
        return best;
    }

    /** How far towards the camera the drawn box is pulled, as a fraction of its distance. */
    static final double TOWARDS_CAMERA = 0.01;

    /** {@code box} scaled about {@code cam} by 1 - TOWARDS_CAMERA: the same on screen, a little nearer in depth. */
    static AABB towardsCamera(AABB box, Vec3 cam) {
        double k = 1.0 - TOWARDS_CAMERA;
        return new AABB(cam.x + (box.minX - cam.x) * k, cam.y + (box.minY - cam.y) * k, cam.z + (box.minZ - cam.z) * k,
                cam.x + (box.maxX - cam.x) * k, cam.y + (box.maxY - cam.y) * k, cam.z + (box.maxZ - cam.z) * k);
    }

    private static AABB realBoxFor(Level level, BlockPos pos) {
        var shape = level.getBlockState(pos).getShape(level, pos);
        return (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds()).move(pos);
    }

    /**
     * Reads whatever is in the main hand every frame, so {@code CustomData#copyTag}'s full NBT deep copy here
     * was a real per-frame cost for EVERY held item with a CUSTOM_DATA component (i.e. almost every Skyblock
     * item), not just Etherwarp ones. Reads the live backing tag instead via the same read-only
     * {@link CustomDataTagAccessor} mixin Armour Recolour already uses for the identical reason - see that
     * mixin's own doc comment. Never mutates the returned tag.
     */
    private static CompoundTag getEtherwarpData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = ((CustomDataTagAccessor) (Object) data).killer560smod$getTag();
        if (tag == null) {
            return null;
        }
        String id = tag.contains("id") ? tag.getStringOr("id", null) : null;
        // killer560 (2026-09-29): "the etehrwarp overlay isnt working with my etherwwarp still."
        //
        // Acceptance was ethermerge==1 or the bare conduit's id, and nothing else. That is the tag Hypixel
        // sets when a conduit is merged INTO an item - but it is not the only way to be holding an etherwarp,
        // and an item that has one and no ethermerge tag was silently ignored with no way to tell from the
        // outside. The third test below is the item's own words: a Skyblock item that can etherwarp says
        // "Ability: Etherwarp" in its lore, so an Aspect of the Void or Aspect of the End that has the ability
        // is recognised whatever its tags look like.
        boolean isEtherItem = tag.getIntOr("ethermerge", 0) == 1
                || ETHERWARP_CONDUIT_ID.equals(id)
                || loreMentionsEtherwarp(stack);
        return isEtherItem ? tag : null;
    }

    /**
     * Whether the item's own tooltip says it has Etherwarp.
     *
     * <p>Read from the LORE rather than from a list of ids, because the list of items that can etherwarp is
     * Hypixel's to change and a hard-coded one is wrong the day it does. Lore is short - a dozen lines - and
     * this only runs for an item that already has CUSTOM_DATA and is in the main hand.
     */
    private static boolean loreMentionsEtherwarp(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (var line : lore.lines()) {
            String text = line.getString();
            if (text.contains("Etherwarp") || text.contains("etherwarp")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block an etherwarp from here would actually land on, or null if there is nowhere legal.
     *
     * <p>Exists so the dungeon sim can teleport to the SAME place this overlay draws. killer560 (2026-09-28):
     * "make sure etherwarp cannot put me into blocks it shoudl function the same as on main. Make sure
     * etherwarp overlay works as well." Those are one request, not two: the sim had its own simpler idea of a
     * legal target - a raycast plus two air blocks - which disagrees with this one on slabs, stairs and
     * anything whose collision box is not a full cube, and a sim that lands somewhere the overlay did not
     * highlight teaches the opposite of what it is for.
     *
     * <p>Returns the block to stand ON. The caller decides where that puts the feet, because the clearance
     * this traversal computes already accounts for a partial block's real height.
     */
    public static BlockPos resolveTarget(Level level, Vec3 position,
                                         net.minecraft.world.entity.player.Player player, double distance) {
        EtherPos pos = getEtherPos(level, position, player, distance);
        return pos != null && pos.succeeded() ? pos.pos() : null;
    }

    /** Where the feet end up when standing on {@code target} - the top of its real collision box. */
    public static double standYOn(Level level, BlockPos target) {
        var shape = level.getBlockState(target).getCollisionShape(level, target);
        double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        return target.getY() + Math.max(1.0, Math.ceil(top));
    }

    /**
     * Where the etherwarp ray starts: the feet plus the eye height Hypixel casts from - 1.62 standing, 0.4 swimming, and
     * sneaking the modern 1.27, except on p3sim.net, which casts from 1.8.9's 1.54
     * ({@link com.killer560.hub.livemap.autoclear.TeleportUtils#sneakEye}).
     */
    public static Vec3 castStart(Vec3 position, net.minecraft.world.entity.player.Player player) {
        double eyeHeight = player.getPose() == Pose.SWIMMING ? 0.4
                : player.isCrouching() ? com.killer560.hub.livemap.autoclear.TeleportUtils.sneakEye() : 1.62;
        return new Vec3(position.x, position.y + eyeHeight, position.z);
    }

    private static EtherPos getEtherPos(Level level, Vec3 position, net.minecraft.world.entity.player.Player player,
                                         double distance) {
        Vec3 start = castStart(position, player);
        Vec3 lookAngle = player.getLookAngle();
        Vec3 end = start.add(lookAngle.x * distance, lookAngle.y * distance, lookAngle.z * distance);
        return traverseVoxels(level, start, end);
    }

    private static EtherPos traverseVoxels(Level level, Vec3 start, Vec3 end) {
        double x0 = start.x, y0 = start.y, z0 = start.z;
        double x1 = end.x, y1 = end.y, z1 = end.z;

        double x = Math.floor(x0), y = Math.floor(y0), z = Math.floor(z0);
        double endX = Math.floor(x1), endY = Math.floor(y1), endZ = Math.floor(z1);

        double dirX = x1 - x0, dirY = y1 - y0, dirZ = z1 - z0;

        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);

        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;

        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);

        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

        for (int i = 0; i < 1000; i++) {
            BlockPos blockPos = new BlockPos((int) x, (int) y, (int) z);
            BlockState state = level.getBlockState(blockPos);
            Block block = state.getBlock();

            boolean isPassable = isPassable(block);
            boolean isSolid = !isPassable;

            if (isSolid) {
                var collisionShape = state.getCollisionShape(level, blockPos);
                int collisionTop = (int) Math.ceil(collisionShape.isEmpty() ? 0.0 : collisionShape.max(Direction.Axis.Y));
                int clearanceBaseY = blockPos.getY() + Math.max(1, collisionTop);

                BlockPos feetPos = new BlockPos(blockPos.getX(), clearanceBaseY, blockPos.getZ());
                BlockState feetState = level.getBlockState(feetPos);
                if (!isPassable(feetState.getBlock()) || blocksFeet(feetState.getBlock())) {
                    return new EtherPos(false, blockPos, state);
                }

                BlockPos headPos = new BlockPos(blockPos.getX(), clearanceBaseY + 1, blockPos.getZ());
                BlockState headState = level.getBlockState(headPos);
                if (!isPassable(headState.getBlock()) || blocksFeet(headState.getBlock())) {
                    return new EtherPos(false, blockPos, state);
                }

                return new EtherPos(true, blockPos, state);
            }

            if (x == endX && y == endY && z == endZ) {
                return EtherPos.NONE;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return EtherPos.NONE;
    }

    private static boolean isPassable(Block block) {
        return block instanceof AirBlock
                || block instanceof FlowerBlock || block instanceof TallGrassBlock || block instanceof BushBlock
                || block instanceof TallFlowerBlock || block instanceof ShortDryGrassBlock
                || block instanceof TorchBlock || block instanceof RedstoneTorchBlock
                || block instanceof TripWireBlock || block instanceof TripWireHookBlock
                || block instanceof RailBlock
                || block instanceof FireBlock
                || block instanceof VineBlock
                || block instanceof LiquidBlock
                || block instanceof SaplingBlock
                || block instanceof CropBlock || block instanceof StemBlock
                || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
                || block instanceof SugarCaneBlock
                || block instanceof MushroomBlock
                || block instanceof NetherWartBlock
                || block instanceof RedStoneWireBlock || block instanceof ComparatorBlock || block instanceof RepeaterBlock
                || block instanceof SmallDripleafBlock
                || block instanceof DoublePlantBlock
                || block instanceof LeverBlock
                || block instanceof SnowLayerBlock
                || block instanceof BubbleColumnBlock
                || block instanceof GrowingPlantBlock
                || block instanceof PistonHeadBlock
                || block instanceof DryVegetationBlock
                || block instanceof ButtonBlock
                || block instanceof LanternBlock
                || block instanceof SkullBlock || block instanceof WallSkullBlock
                || block instanceof LadderBlock
                || block instanceof FlowerPotBlock
                || block instanceof WebBlock
                || block instanceof NetherPortalBlock;
    }

    private static boolean blocksFeet(Block block) {
        return block instanceof SkullBlock || block instanceof WallSkullBlock
                || block instanceof FlowerPotBlock
                || block instanceof LadderBlock
                || block instanceof VineBlock;
    }
}
