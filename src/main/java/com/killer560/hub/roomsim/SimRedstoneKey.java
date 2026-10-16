package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel's Redstone Key, played by the integrated server.
 *
 * <h2>What Hypixel does (and what this is based on)</h2>
 *
 * <ul>
 *   <li><b>hypixelskyblock.minecraft.wiki, Redstone Key</b> (2026-10-15): found in a room, taken by clicking it; its only
 *       use is to be placed on the Redstone Node in the same room, which "rewards 1 secret" and opens an entrance to
 *       another secret. Since 0.18.4 (May 2023) it is no longer an item: "Any player can use the Redstone Key once
 *       found". Three 1x1 rooms use it.</li>
 *   <li><b>How the client sees it</b>, from the shallow clones in killer560s-mod-logs (read, not copied): the key is a
 *       {@code player_head} whose profile id is {@code fed95410-aba1-39df-9b95-1d4f361eb66e} (Odin, NoammAddons, RSA,
 *       and this mod's own {@code SecretsFeature.isRedstoneKey}); picking it up turns that head to AIR (RSA watches
 *       exactly that block change to know the key was found); the node is a {@code redstone_block} that is clicked
 *       (QUOI and RSA click a REDSTONE_BLOCK once the key is found); when the key goes in, the node block changes
 *       (RSA marks it done on that change) and an ARMOR STAND wearing the key's head appears with its block position
 *       two below the node (RSA, equipment packet, {@code entity.getBlockY() + 2}); and RSM counts the node's secret off
 *       a {@code block.piston.contract}/{@code extend} sound.</li>
 *   <li><b>The node positions</b> are RSM's {@code REDSTONE_BLOCK} waypoints ({@code assets/rsm/secrets.json}) turned
 *       into capture coordinates by the turn that lands its chests, keys and levers on the capture's own blocks.
 *       Redstone Crypt's and Redstone Key's captures hold a redstone block exactly there. Golden Oasis' capture holds
 *       AIR there (capture (11, 71, 4), a stone-brick ledge in the wall) - killer560's Hypixel shot of 2026-10-15 shows
 *       the redstone block on that ledge - so the sim puts one there, only into air.</li>
 *   <li><b>What the key opens</b> in Golden Oasis is killer560's own description: "that third redstone is where i place
 *       the skull and it drops those redstone blocks by the lever" - the three redstone blocks at capture
 *       (27..29, 70, 26), between smooth stone slabs, that lead to the lever's acacia wood. What the node opens in the
 *       other two rooms is not known yet, so there it counts the secret and opens nothing.</li>
 * </ul>
 *
 * <p>Picking the key up is NOT a secret (the wiki gives the secret to the placing; the database lists the key's
 * position as the room's key secret, and Golden Oasis' three secrets are the key and two chests). Placing it is one,
 * counted for the room standing at the node through {@link SimScore#secretFound(BlockPos)}, which is what the room's
 * "x/y Secrets" action bar line reads - as Hypixel's does.
 */
public final class SimRedstoneKey {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** Hypixel's Redstone Key skull profile id - {@code SecretsFeature.isRedstoneKey}'s. */
    public static final UUID KEY_PROFILE_ID = UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");

    /**
     * The key's own skin, from Hypixel's item list (SECRET_DUNGEON_REDSTONE_KEY, api.hypixel.net/v2/resources/skyblock
     * /items as cached in killer560s-mod-logs), re-encoded with only the skin URL as SimKeys does for the door keys.
     */
    private static final String KEY_TEXTURE = java.util.Base64.getEncoder().encodeToString(
            ("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + "a223e36ac13f0f71abcfbf0c96fdc2010cc3e11ff2b0d8112d0e63f4b4aaa0de\"}}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /**
     * One room's node, in capture-local coordinates.
     *
     * @param node  the redstone block the key goes on
     * @param place put a redstone block there when the capture has air (Golden Oasis)
     * @param from  one corner of what placing the key takes away, inclusive; null for nothing
     * @param to    the other corner
     */
    private record Node(String room, int[] node, boolean place, int[] from, int[] to) {
    }

    private static final List<Node> NODES = List.of(
            new Node("Golden Oasis", new int[]{11, 71, 4}, true,
                    new int[]{27, 70, 26}, new int[]{29, 70, 26}),
            new Node("Redstone Crypt", new int[]{5, 72, 25}, false, null, null),
            new Node("Redstone Key", new int[]{28, 68, 8}, false, null, null));

    /** Key heads still lying where they were placed: world position to room name. */
    private static final Map<BlockPos, String> KEYS = new ConcurrentHashMap<>();
    /** Armed nodes: world position to room name. */
    private static final Map<BlockPos, String> NODE_ROOM = new ConcurrentHashMap<>();
    /** What each armed node takes away. */
    private static final Map<BlockPos, List<BlockPos>> NODE_OPENS = new ConcurrentHashMap<>();
    /** Rooms whose key has been found and not yet placed. */
    private static final Set<String> FOUND = ConcurrentHashMap.newKeySet();
    /** Keys placed this floor, for tests. */
    private static volatile int placedCount;

    private static ResolvableProfile keyProfile;

    private SimRedstoneKey() {
    }

    /** The key's skull profile: Hypixel's id and Hypixel's skin. */
    static ResolvableProfile keyProfile() {
        if (keyProfile == null) {
            Multimap<String, Property> backing = LinkedHashMultimap.create();
            backing.put("textures", new Property("textures", KEY_TEXTURE));
            keyProfile = ResolvableProfile.createResolved(
                    new GameProfile(KEY_PROFILE_ID, "RedstoneKey", new PropertyMap(backing)));
        }
        return keyProfile;
    }

    /** The key as a head item, for the placed key's armor stand. */
    static ItemStack keyHead() {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, keyProfile());
        return head;
    }

    /**
     * Puts a Redstone Key at a database key position (server thread, from {@link SimSecrets}): a player head wearing
     * Hypixel's key profile. A head already standing there keeps its rotation.
     */
    static void placeKey(ServerLevel level, BlockPos at, String room) {
        BlockState there = level.getBlockState(at);
        BlockState head = there.is(Blocks.PLAYER_HEAD) || there.is(Blocks.PLAYER_WALL_HEAD)
                ? there : Blocks.PLAYER_HEAD.defaultBlockState();
        level.setBlockAndUpdate(at, head);
        if (level.getBlockEntity(at) instanceof SkullBlockEntity skull) {
            ItemStack carrier = keyHead();
            skull.applyComponentsFromItemStack(carrier);
            skull.setChanged();
            BlockState state = level.getBlockState(at);
            level.sendBlockUpdated(at, state, state, 3);
            KEYS.put(at.immutable(), room);
        } else {
            LOGGER.warn("Sim redstone key at {}: no skull block entity after placing the head", at.toShortString());
        }
    }

    /** A new floor: the last one's keys are gone with it. Called from {@code SimSecrets.resetAudit}. */
    static void clearKeys() {
        KEYS.clear();
        FOUND.clear();
        placedCount = 0;
    }

    /** Drops everything, for leaving the sim. */
    public static void forget() {
        clearKeys();
        NODE_ROOM.clear();
        NODE_OPENS.clear();
    }

    /**
     * Arms every node on the floor just built - server thread, beside {@link SimRoomLevers#armFloor}.
     *
     * @return how many nodes were armed
     */
    public static int armFloor(ServerLevel level) {
        NODE_ROOM.clear();
        NODE_OPENS.clear();
        if (level == null) {
            return 0;
        }
        int armed = 0;
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            for (Node rule : NODES) {
                if (!rule.room().equalsIgnoreCase(placed.name())) {
                    continue;
                }
                RoomLibrary.Room room = RoomLibrary.get(placed.name());
                if (room == null) {
                    continue;
                }
                SimRoomPuzzles.Placement p = new SimRoomPuzzles.Placement(room,
                        placed.gridX(), placed.gridZ(), placed.pasteRotation(), placed.rotation());
                BlockPos node = SimRoomPuzzles.capturedPos(p, rule.node()[0], rule.node()[1], rule.node()[2]);
                if (rule.place() && level.getBlockState(node).isAir()) {
                    level.setBlockAndUpdate(node, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    SimBuildQueue.touched(node.getX(), node.getZ());
                }
                if (!level.getBlockState(node).is(Blocks.REDSTONE_BLOCK)) {
                    LOGGER.warn("Sim redstone key: {} has no redstone block at capture {},{},{} (world {}) - it is {}."
                                    + " Not armed; the capture or the rule is wrong.", placed.name(), rule.node()[0],
                            rule.node()[1], rule.node()[2], node, level.getBlockState(node).getBlock());
                    continue;
                }
                List<BlockPos> opens = new ArrayList<>();
                if (rule.from() != null) {
                    for (int x = Math.min(rule.from()[0], rule.to()[0]); x <= Math.max(rule.from()[0], rule.to()[0]); x++) {
                        for (int y = Math.min(rule.from()[1], rule.to()[1]); y <= Math.max(rule.from()[1], rule.to()[1]); y++) {
                            for (int z = Math.min(rule.from()[2], rule.to()[2]); z <= Math.max(rule.from()[2], rule.to()[2]); z++) {
                                opens.add(SimRoomPuzzles.capturedPos(p, x, y, z));
                            }
                        }
                    }
                }
                NODE_ROOM.put(node, placed.name());
                NODE_OPENS.put(node, opens);
                armed++;
                LOGGER.info("Sim redstone key: {}'s node at {} armed, opens {} block(s)", placed.name(), node,
                        opens.size());
            }
        }
        return armed;
    }

    /**
     * The key and the node answer the use-on-block packet on the SERVER, as on Hypixel - every client listener sees
     * the plain right click it would send there. {@code SimAbilities.blockWins} lets both beat a held ability item.
     */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hit == null || !(player instanceof ServerPlayer sp)
                    || !SimAbilities.simServer(sp) || !(level instanceof ServerLevel server)) {
                return InteractionResult.PASS;
            }
            BlockPos at = hit.getBlockPos().immutable();
            String keyRoom = KEYS.remove(at);
            if (keyRoom != null) {
                server.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                FOUND.add(keyRoom);
                say("Redstone Key", " found - put it on the room's redstone block.");
                LOGGER.info("Sim redstone key: {}'s key picked up at {}", keyRoom, at.toShortString());
                return InteractionResult.SUCCESS;
            }
            String nodeRoom = NODE_ROOM.get(at);
            if (nodeRoom == null || !FOUND.contains(nodeRoom)) {
                // No key yet: the click does nothing, as a redstone block does nothing.
                return InteractionResult.PASS;
            }
            NODE_ROOM.remove(at);
            FOUND.remove(nodeRoom);
            List<BlockPos> opens = NODE_OPENS.remove(at);
            server.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            // The key, shown where the node was: an invisible stand wearing it, block position two below the node.
            ArmorStand stand = new ArmorStand(server, at.getX() + 0.5, at.getY() - 1.45, at.getZ() + 0.5);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setNoBasePlate(true);
            stand.setInvulnerable(true);
            stand.setItemSlot(EquipmentSlot.HEAD, keyHead());
            server.addFreshEntity(stand);
            if (opens != null) {
                for (BlockPos pos : opens) {
                    server.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
            server.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                    SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5f, 0.6f);
            SimScore.secretFound(at);
            placedCount++;
            LOGGER.info("Sim redstone key: {}'s key placed on {}, {} block(s) opened", nodeRoom, at.toShortString(),
                    opens == null ? 0 : opens.size());
            say(nodeRoom, " - the Redstone Key is in. Secret found.");
            // SUCCESS: the held item's own use must not run on it.
            return InteractionResult.SUCCESS;
        });
    }

    private static void say(String good, String rest) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> ModChat.send("Sim", ModChat.good(good), ModChat.text(rest)));
    }

    /** An armed node whose room's key has been found - a click on it places the key. */
    public static boolean isReadyNode(BlockPos pos) {
        String room = NODE_ROOM.get(pos);
        return room != null && FOUND.contains(room);
    }

    /**
     * For tests (server thread): a key at {@code key} and an armed node at {@code node} for {@code room}, anywhere, so a
     * scenario can play the key in its own arena - the same server answers as a key room's. The node opens nothing.
     */
    public static void armForTest(ServerLevel level, BlockPos key, BlockPos node, String room) {
        level.setBlockAndUpdate(node, Blocks.REDSTONE_BLOCK.defaultBlockState());
        NODE_ROOM.put(node.immutable(), room);
        NODE_OPENS.put(node.immutable(), List.of());
        if (key != null) {
            FOUND.remove(room); // a fresh key: none found yet
            placeKey(level, key, room);
        }
    }

    /** For tests (server thread): {@code room}'s key found by somebody else - a teammate's pickup. */
    public static void foundForTest(String room) {
        FOUND.add(room);
    }

    /** For tests: armed nodes on this floor. */
    public static int armedCount() {
        return NODE_ROOM.size();
    }

    /** For tests: key heads still lying in the world. */
    public static int keysLying() {
        return KEYS.size();
    }

    /** For tests: keys placed on a node this floor. */
    public static int placedCount() {
        return placedCount;
    }

    /** For tests: whether a room's key has been found and not placed. */
    public static boolean keyFound(String room) {
        return room != null && FOUND.stream().anyMatch(room::equalsIgnoreCase);
    }
}
