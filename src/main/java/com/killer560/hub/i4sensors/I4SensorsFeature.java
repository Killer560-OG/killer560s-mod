package com.killer560.hub.i4sensors;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "I4" (the F7/M7 P3 section-4 arrow device right before Necron's P4) sensor logger - killer560's "add a
 * bunch of sensors for this as well so we can make a spec safe auto i4" request, rebuilt 2026-09-14 for
 * sim testing ("build any and all loggers you need for it to prepare it for sim testing").
 * <p>
 * Real device layout now CONFIRMED from NoammAddons' own {@code I4Helper.kt}/{@code AutoI4.kt} (cloned
 * reference at C:\Users\Hunter\noammaddonsmod): a 3x3 wall of target blocks at z=50, x in {68,66,64}, y in
 * {130,128,126}; a lit target is {@code EMERALD_BLOCK}, a hit one turns {@code BLUE_TERRACOTTA}; the player
 * stands on the device at y~127, x 62-65, z 34-37 and shoots them with a bow. Noamm times its rod swap /
 * mask swap / leap off server ticks counted from Storm's death line (174 / 244 / 307) and treats an armor
 * stand renamed "Active" or the "completed a device!" chat line as completion. The original version of this
 * class only diffed the standing platform (QUOI's pre4Box) and never watched the target wall at all.
 * <p>
 * LOGGING ONLY - nothing here clicks, rotates, or changes anything. The focused sensors are always on
 * (no toggle) whenever the player is near the device on hypixel.net or p3sim.net, and every line is
 * state-change-driven or capped, so a sim run's log has everything needed to build Auto i4 against real
 * data. The config toggle now only adds the old verbose block diff of the wider area around the device.
 * Every line carries {@link #clock()} - wall-clock ms and client ticks since Storm's death line - so the
 * log lines up directly with Noamm's server-tick timings.
 */
public final class I4SensorsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-i4sensors");
    private static final String TAG = "[I4Sensors]";

    // Real AABB from QUOI's AutoLeap.kt (pre4Box) - the platform the player shoots from.
    private static final AABB PRE4_BOX = new AABB(60, 125, 32, 67, 132, 39);
    // Real target wall from NoammAddons' I4Helper.devBlocks - index = row * 3 + col, row 0 = top (y 130),
    // col 0 = x 68.
    static final List<BlockPos> DEV_BLOCKS = List.of(
            new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
            new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
            new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
    // Everything around the wall, so a p3sim layout that differs from Hypixel's (different z, extra rows,
    // a different "lit" block) still shows up in the log instead of silently looking like "nothing lit".
    private static final int WALL_MIN_X = 61, WALL_MAX_X = 71, WALL_MIN_Y = 123, WALL_MAX_Y = 133,
            WALL_MIN_Z = 48, WALL_MAX_Z = 52;
    private static final AABB NEAR_BOX = new AABB(45, 110, 20, 85, 150, 65);
    private static final AABB ARROW_BOX = new AABB(55, 118, 28, 78, 140, 56);
    private static final AABB STAND_BOX = new AABB(56, 118, 40, 76, 140, 58);
    private static final String STORM_DEATH_LINE = "[BOSS] Storm: I should have known that I stood no chance.";
    private static final int MAX_WALL_EXTRA_LINES = 300;
    private static final int MAX_VERBOSE_LINES = 400;

    // --- session (player near the device) ---
    private static boolean near = false;
    private static long nearSinceMs = 0L;
    private static boolean onDev = false;
    private static long onDevSinceMs = 0L;
    private static final Map<BlockPos, BlockState> wallStates = new HashMap<>();
    private static int wallExtraLines = 0;
    private static Map<BlockPos, BlockState> verboseStates = new HashMap<>();
    private static int verboseLines = 0;
    private static String lastHeldDesc = null;
    private static String lastHelmetDesc = null;
    private static int lastSelectedSlot = -1;
    private static float lastLoggedHealth = -1f;
    private static long lastHealthLogMs = 0L;
    private static boolean useWasDown = false;
    private static long lastUseDownMs = 0L;
    private static final Map<Integer, ArrowTrack> arrows = new HashMap<>();
    private static final Map<Integer, String> standNames = new HashMap<>();
    // session summary
    private static int useClicks = 0;
    private static int arrowsSeen = 0;
    private static int litEvents = 0;
    private static int hitEvents = 0;
    private static long firstLitAtMs = 0L;
    private static long lastHitAtMs = 0L;

    // --- timeline (persists across sessions within a world, reset on world change) ---
    private static long stormDeathAtMs = 0L;
    private static int stormDeathClientTick = -1;
    private static int clientTick = 0;
    private static Object lastLevel = null;

    private static final class ArrowTrack {
        final long spawnedAtMs;
        Vec3 lastPos;
        int stillTicks = 0;
        boolean landedLogged = false;

        ArrowTrack(long spawnedAtMs, Vec3 pos) {
            this.spawnedAtMs = spawnedAtMs;
            this.lastPos = pos;
        }
    }

    private I4SensorsFeature() {
    }

    public static void register() {
        // Real bug found and fixed (2026-09-14, real Hypixel F7 log): Fabric's CHAT/GAME events never fired for a
        // line another mod cancels via ALLOW_GAME and re-adds straight to ChatComponent (Odin's Terminal Splits
        // does this to "completed a device!"), so this always-on chat logging stayed silent for it. ChatObserver
        // sees both paths, de-duplicated, non-overlay only.
        ChatObserver.subscribe(message -> onChatMessage(message, false));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private static void onChatMessage(Component message, boolean overlay) {
        if (overlay) {
            return;
        }
        String raw = message.getString();
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain == null) {
            plain = raw;
        }
        Minecraft client = Minecraft.getInstance();
        if (!isOnDungeonServer(client)) {
            return;
        }
        if (plain.equals(STORM_DEATH_LINE)) {
            stormDeathAtMs = System.currentTimeMillis();
            stormDeathClientTick = clientTick;
            LOGGER.info("{} Storm death line seen - i4 timeline t=0 starts now (Noamm: rod 174t, mask 244t, leap 307t). pos={}",
                    TAG, client.player != null ? fmt(client.player.position()) : "none");
            return;
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        boolean relevant = lower.contains("completed a device") || lower.contains("device")
                || lower.contains("mask saved") || lower.contains("saved your life") || lower.contains("phoenix")
                || lower.contains("[boss] goldor") || lower.contains("[boss] necron") || lower.contains("[boss] storm")
                || lower.contains("melody") || lower.contains("teleported to") || lower.contains("core entrance")
                || lower.contains("gate");
        if (!relevant) {
            return;
        }
        // Log boss/device lines everywhere in boss (they drive the timeline), everything else only near i4.
        if (!near && !lower.contains("completed a device") && !lower.startsWith("[boss]")) {
            return;
        }
        LOGGER.info("{} {} chat: \"{}\"{} near={} onDev={} pos={}", TAG, clock(), plain,
                raw.equals(plain) ? "" : " (raw had formatting)", near, onDev,
                client.player != null ? fmt(client.player.position()) : "none");
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void tick() {
        clientTick++;
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            if (lastLevel != null) {
                if (near) {
                    endSession("world changed");
                }
                if (stormDeathAtMs > 0) {
                    LOGGER.info("{} World changed - i4 timeline reset.", TAG);
                }
                stormDeathAtMs = 0L;
                stormDeathClientTick = -1;
            }
            lastLevel = client.level;
        }
        LocalPlayer player = client.player;
        boolean nowNear = player != null && client.level != null && isOnDungeonServer(client)
                && NEAR_BOX.contains(player.position());
        if (nowNear && !near) {
            beginSession(client);
        } else if (!nowNear && near) {
            endSession(player == null ? "no player" : "left i4 area");
        }
        near = nowNear;
        if (!near) {
            return;
        }

        tickOnDev(player);
        tickWall(client);
        tickPlayerState(player);
        tickUseInput(client, player);
        tickArrows(client, player);
        tickArmorStands(client);
        if (I4SensorsConfig.getInstance().isEnabled()) {
            tickVerboseDiff(client);
        }
    }

    private static void beginSession(Minecraft client) {
        nearSinceMs = System.currentTimeMillis();
        wallStates.clear();
        wallExtraLines = 0;
        verboseStates = new HashMap<>();
        verboseLines = 0;
        lastHeldDesc = null;
        lastHelmetDesc = null;
        lastSelectedSlot = -1;
        lastLoggedHealth = -1f;
        useWasDown = false;
        arrows.clear();
        standNames.clear();
        useClicks = 0;
        arrowsSeen = 0;
        litEvents = 0;
        hitEvents = 0;
        firstLitAtMs = 0L;
        lastHitAtMs = 0L;
        onDev = false;
        LOGGER.info("{} {} Near i4 - sensors ON. pos={} server={} inDungeon={} floor={} bossPhase={} verboseDiff={}",
                TAG, clock(), fmt(client.player.position()),
                client.getCurrentServer() != null ? client.getCurrentServer().ip : "none",
                DungeonState.isInDungeon(), DungeonState.getFloor(), DungeonState.isBossPhaseActive(),
                I4SensorsConfig.getInstance().isEnabled());
        // Initial wall snapshot so the log shows what was already lit before any change arrives.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEV_BLOCKS.size(); i++) {
            BlockState state = client.level.getBlockState(DEV_BLOCKS.get(i));
            wallStates.put(DEV_BLOCKS.get(i), state);
            sb.append(i == 0 ? "" : " ").append(i).append('=').append(blockId(state));
        }
        LOGGER.info("{} {} Target wall snapshot (index=row*3+col, row0=y130, col0=x68): {}", TAG, clock(), sb);
    }

    private static void endSession(String reason) {
        long now = System.currentTimeMillis();
        LOGGER.info("{} {} Sensors OFF ({}) - {}ms near, right-clicks={} arrowsSeen={} litEvents={} hitEvents={} "
                        + "firstLit->lastHit={}ms wallExtraLines={}",
                TAG, clock(), reason, now - nearSinceMs, useClicks, arrowsSeen, litEvents, hitEvents,
                firstLitAtMs > 0 && lastHitAtMs > 0 ? lastHitAtMs - firstLitAtMs : -1, wallExtraLines);
        near = false;
        onDev = false;
        arrows.clear();
    }

    /** Noamm's own isOnDev(): |y - 127| < 0.5, x in [62, 65], z in [34, 37]. */
    private static void tickOnDev(LocalPlayer player) {
        Vec3 p = player.position();
        boolean nowOnDev = Math.abs(p.y - 127.0) < 0.5 && p.x >= 62.0 && p.x <= 65.0 && p.z >= 34.0 && p.z <= 37.0;
        if (nowOnDev != onDev) {
            long now = System.currentTimeMillis();
            LOGGER.info("{} {} On device: {} -> {} pos={} inPre4Box={}{}", TAG, clock(), onDev, nowOnDev, fmt(p),
                    PRE4_BOX.contains(p), nowOnDev ? "" : " (was on for " + (now - onDevSinceMs) + "ms)");
            onDevSinceMs = now;
            onDev = nowOnDev;
        }
    }

    private static void tickWall(Minecraft client) {
        long now = System.currentTimeMillis();
        for (int x = WALL_MIN_X; x <= WALL_MAX_X; x++) {
            for (int y = WALL_MIN_Y; y <= WALL_MAX_Y; y++) {
                for (int z = WALL_MIN_Z; z <= WALL_MAX_Z; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.level.getBlockState(pos);
                    BlockState old = wallStates.put(pos, state);
                    if (old == null || old == state) {
                        continue;
                    }
                    int index = DEV_BLOCKS.indexOf(pos);
                    if (index >= 0) {
                        String from = blockId(old);
                        String to = blockId(state);
                        if (to.equals("emerald_block")) {
                            litEvents++;
                            if (firstLitAtMs == 0L) {
                                firstLitAtMs = now;
                            }
                        } else if (from.equals("emerald_block") && to.equals("blue_terracotta")) {
                            hitEvents++;
                            lastHitAtMs = now;
                        }
                        LOGGER.info("{} {} TARGET #{} (row {}, col {}) {}: {} -> {} | wall now [{}] | {}ms since last right-click",
                                TAG, clock(), index, index / 3, index % 3, pos, from, to, wallSummary(client),
                                lastUseDownMs > 0 ? now - lastUseDownMs : -1);
                    } else if (wallExtraLines < MAX_WALL_EXTRA_LINES) {
                        wallExtraLines++;
                        LOGGER.info("{} {} Non-target block near wall changed {}: {} -> {}{}", TAG, clock(), pos,
                                blockId(old), blockId(state),
                                wallExtraLines == MAX_WALL_EXTRA_LINES ? " (cap reached, further ones suppressed)" : "");
                    }
                }
            }
        }
    }

    private static void tickPlayerState(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        String heldDesc = itemDesc(held);
        if (!heldDesc.equals(lastHeldDesc)) {
            LOGGER.info("{} {} Held item: {} -> {}", TAG, clock(), lastHeldDesc, heldDesc);
            lastHeldDesc = heldDesc;
        }
        String helmetDesc = itemDesc(player.getItemBySlot(EquipmentSlot.HEAD));
        if (!helmetDesc.equals(lastHelmetDesc)) {
            LOGGER.info("{} {} Helmet: {} -> {}", TAG, clock(), lastHelmetDesc, helmetDesc);
            lastHelmetDesc = helmetDesc;
        }
        int slot = player.getInventory().getSelectedSlot();
        if (slot != lastSelectedSlot) {
            LOGGER.info("{} {} Hotbar slot: {} -> {}", TAG, clock(), lastSelectedSlot, slot);
            lastSelectedSlot = slot;
        }
        // Death ticks / mask pops show up as health drops - log real changes of >= 1 HP, max ~5/sec.
        float health = player.getHealth();
        long now = System.currentTimeMillis();
        if (lastLoggedHealth < 0f || (Math.abs(health - lastLoggedHealth) >= 1f && now - lastHealthLogMs >= 200L)) {
            LOGGER.info("{} {} Health: {} -> {} (max {})", TAG, clock(),
                    lastLoggedHealth < 0f ? "?" : String.format(Locale.US, "%.1f", lastLoggedHealth),
                    String.format(Locale.US, "%.1f", health), String.format(Locale.US, "%.1f", player.getMaxHealth()));
            lastLoggedHealth = health;
            lastHealthLogMs = now;
        }
    }

    /** Auto i4's own shots don't press the use key - let them count as "last right-click" so the arrow/target
     *  timing lines correlate with automated shots too. */
    static void noteAutoShot(long atMs) {
        lastUseDownMs = atMs;
    }

    private static void tickUseInput(Minecraft client, LocalPlayer player) {
        boolean down = client.options.keyUse.isDown();
        if (down && !useWasDown) {
            long now = System.currentTimeMillis();
            useClicks++;
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            Vec3 eye = player.getEyePosition();
            Vec3 look = lookVector(yaw, pitch);
            String aimAtWall = "ray misses z=50 plane";
            if (look.z > 1e-4) {
                double t = (50.0 - eye.z) / look.z;
                Vec3 hitPlane = eye.add(look.scale(t));
                BlockPos nearest = null;
                double best = Double.MAX_VALUE;
                for (BlockPos target : DEV_BLOCKS) {
                    double d = Math.hypot(hitPlane.x - (target.getX() + 0.5), hitPlane.y - (target.getY() + 0.5));
                    if (d < best) {
                        best = d;
                        nearest = target;
                    }
                }
                aimAtWall = String.format(Locale.US, "ray hits z=50 at (%.2f, %.2f), nearest target #%d %s off by %.2f blocks",
                        hitPlane.x, hitPlane.y, DEV_BLOCKS.indexOf(nearest), nearest, best);
            }
            String crosshair = client.hitResult instanceof BlockHitResult bh
                    ? bh.getBlockPos() + " " + blockId(client.level.getBlockState(bh.getBlockPos()))
                    : String.valueOf(client.hitResult != null ? client.hitResult.getType() : null);
            LOGGER.info("{} {} Right-click #{} held={} yaw={} pitch={} eye={} onDev={} | {} | crosshair={} | {}ms since previous",
                    TAG, clock(), useClicks, lastHeldDesc, String.format(Locale.US, "%.2f", yaw),
                    String.format(Locale.US, "%.2f", pitch), fmt(eye), onDev, aimAtWall, crosshair,
                    lastUseDownMs > 0 ? now - lastUseDownMs : -1);
            lastUseDownMs = now;
        }
        useWasDown = down;
    }

    private static void tickArrows(Minecraft client, LocalPlayer player) {
        long now = System.currentTimeMillis();
        Set<Integer> present = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractArrow arrow) || !ARROW_BOX.contains(entity.position())) {
                continue;
            }
            int id = entity.getId();
            present.add(id);
            Vec3 pos = entity.position();
            ArrowTrack track = arrows.get(id);
            if (track == null) {
                arrowsSeen++;
                arrows.put(id, new ArrowTrack(now, pos));
                Entity owner = arrow.getOwner();
                LOGGER.info("{} {} Arrow #{} appeared id={} pos={} velocity={} owner={} distToPlayer={} {}ms since last right-click",
                        TAG, clock(), arrowsSeen, id, fmt(pos), fmt(entity.getDeltaMovement()),
                        owner == null ? "unknown" : owner == player ? "SELF" : owner.getName().getString(),
                        String.format(Locale.US, "%.2f", pos.distanceTo(player.position())),
                        lastUseDownMs > 0 ? now - lastUseDownMs : -1);
                continue;
            }
            if (!track.landedLogged) {
                if (pos.distanceToSqr(track.lastPos) < 1.0e-4) {
                    track.stillTicks++;
                } else {
                    track.stillTicks = 0;
                }
                if (track.stillTicks >= 2) {
                    track.landedLogged = true;
                    logArrowEnd(id, track, pos, "stopped", now);
                }
            }
            track.lastPos = pos;
        }
        arrows.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            ArrowTrack track = entry.getValue();
            if (!track.landedLogged) {
                logArrowEnd(entry.getKey(), track, track.lastPos, "despawned", now);
            }
            return true;
        });
    }

    private static void logArrowEnd(int id, ArrowTrack track, Vec3 pos, String how, long now) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos target : DEV_BLOCKS) {
            double d = pos.distanceTo(Vec3.atCenterOf(target));
            if (d < best) {
                best = d;
                nearest = target;
            }
        }
        LOGGER.info("{} {} Arrow id={} {} at {} after {}ms - nearest target #{} {} ({} blocks away, now {})",
                TAG, clock(), id, how, fmt(pos), now - track.spawnedAtMs, DEV_BLOCKS.indexOf(nearest), nearest,
                String.format(Locale.US, "%.2f", best),
                blockId(Minecraft.getInstance().level.getBlockState(nearest)));
    }

    private static void tickArmorStands(Minecraft client) {
        Set<Integer> present = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || !STAND_BOX.contains(entity.position())) {
                continue;
            }
            int id = entity.getId();
            present.add(id);
            Component custom = entity.getCustomName();
            String name = custom != null ? custom.getString() : "";
            String old = standNames.put(id, name);
            if (old == null) {
                if (!name.isEmpty()) {
                    LOGGER.info("{} {} Armor stand id={} at {} named \"{}\"", TAG, clock(), id, fmt(entity.position()), name);
                }
            } else if (!old.equals(name)) {
                LOGGER.info("{} {} Armor stand id={} at {} renamed \"{}\" -> \"{}\"", TAG, clock(), id,
                        fmt(entity.position()), old, name);
            }
        }
        standNames.keySet().removeIf(id -> !present.contains(id));
    }

    /** The old wide-area block diff (now the only thing the config toggle controls), capped. */
    private static void tickVerboseDiff(Minecraft client) {
        if (verboseLines >= MAX_VERBOSE_LINES) {
            return;
        }
        for (int x = (int) NEAR_BOX.minX; x <= (int) NEAR_BOX.maxX; x += 1) {
            for (int y = 120; y <= 135; y++) {
                for (int z = 30; z <= 52; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.level.getBlockState(pos);
                    BlockState previous = verboseStates.put(pos, state);
                    if (previous != null && previous != state && verboseLines < MAX_VERBOSE_LINES) {
                        verboseLines++;
                        LOGGER.info("{} {} [verbose] Block changed at {}: {} -> {}", TAG, clock(), pos, blockId(previous), blockId(state));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String clock() {
        if (stormDeathAtMs == 0L) {
            return "[t=? no Storm death seen]";
        }
        return "[t=+" + (System.currentTimeMillis() - stormDeathAtMs) + "ms/+" + (clientTick - stormDeathClientTick) + "ct]";
    }

    static boolean isOnDungeonServer(Minecraft client) {
        if (client.getCurrentServer() == null) {
            return false;
        }
        String ip = client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        return ip.contains("hypixel.net") || ip.contains("p3sim.net");
    }

    private static String wallSummary(Minecraft client) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEV_BLOCKS.size(); i++) {
            String id = blockId(client.level.getBlockState(DEV_BLOCKS.get(i)));
            char c = id.equals("emerald_block") ? 'E' : id.equals("blue_terracotta") ? 'B' : id.equals("air") ? '.' : '?';
            sb.append(c);
            if (i % 3 == 2 && i < DEV_BLOCKS.size() - 1) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    static String itemDesc(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String skyblockId = null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            skyblockId = tag.contains("id") ? tag.getStringOr("id", null) : null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                + (skyblockId != null ? " sb=" + skyblockId : "")
                + " \"" + ChatFormatting.stripFormatting(stack.getHoverName().getString()) + "\"";
    }

    private static Vec3 lookVector(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
