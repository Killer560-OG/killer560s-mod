package com.killer560.hub.termlog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.terminals.TerminalType;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * Terminal Open Logger - a passive diagnostic for how Hypixel decides whether a P3 terminal opens.
 *
 * <p>killer560, 2026-10-07: "In the recent update they changed something about terms such that you cannot open it
 * from below the actual terminal anymore, but I don't know exactly how it works now, so can you make a logger and I
 * will manually go test opening terminals a bunch of times on the main server and we can figure it out."
 *
 * <p>It runs by itself inside an F7/M7 boss (killer560, 2026-10-07: "The term log should always be running once I
 * enter boss"): the setting defaults ON and is an override, and the logger is ARMED only while
 * {@link DungeonState#isBossPhaseActive()} or {@link Floor7Tracker#inF7Boss()} says he is in the boss. The arming is
 * decided once a tick (a few field reads on F7/M7, one string compare elsewhere); disarmed, the packet hook costs a
 * volatile read and nothing else runs.
 *
 * <p>While armed, every right click / interact / use (and left click on a terminal's stand) HE sends within
 * {@link #NEAR_RANGE} blocks of an "Inactive Terminal" / "Terminal Active" armour stand becomes one ATTEMPT: the
 * packet(s) of that client tick, where he stood and looked, the nearest terminal stand and every stand beside it, the
 * blocks it stands on, the geometry between them, and what the crosshair had. For {@link #WINDOW_TICKS} ticks it
 * then watches for the OUTCOME - a container opening (and its title), chat and action-bar lines, the stand's name -
 * and writes the attempt as one JSON line to {@code config/killer560/dungeons/termlog/killer560smod-termlog-attempts.jsonl}.
 *
 * <p>It only reads. It is fed from the outbound send hook ({@code termlog/mixin/TermLogOutboundMixin}) and the
 * open-screen handler ({@code TermLogOpenScreenMixin}), sends nothing, cancels nothing. Off, the packet hook costs a
 * volatile read. Every entry point catches everything: the send path and the chat path are the network path, and a
 * throw there drops him from Hypixel (CLAUDE.md).
 */
public final class TerminalOpenLogger {

    private static final Logger LOGGER = ModLog.get("killer560smod-termlog");

    /** An attempt counts only within this many blocks of a terminal stand. */
    public static final double NEAR_RANGE = 8.0;
    /** How long an attempt watches for its outcome, in client ticks. */
    public static final int WINDOW_TICKS = 20;
    /** Records per file; the file then rotates to {@code .1} (so at most twice this is ever kept). */
    public static final int MAX_RECORDS = 5000;
    private static final int MAX_PENDING = 16;
    private static final int MAX_PACKETS_PER_ATTEMPT = 8;
    private static final int MAX_LINES_PER_ATTEMPT = 6;
    private static final int MAX_LINE_LENGTH = 200;
    private static final int RECENT_CAP = 64;
    /** Stands within this of the nearest terminal stand are listed beside it (Hypixel stacks several). */
    private static final double STAND_CLUSTER = 3.0;
    private static final int MAX_STANDS_LISTED = 8;
    private static final String FEATURE = "TermLog";
    /** How long the logger stays armed after the boss gate last held. */
    private static final int BOSS_GRACE_TICKS = 100;
    private static long lastBossTick = -1;
    /** The world the boss gate last held in; the grace never carries into another world. */
    private static Object lastBossLevel;

    private static volatile boolean enabled;
    /** Enabled AND in an F7/M7 boss, decided once per tick. The packet, screen and chat hooks read only this. */
    private static volatile boolean armed;
    private static boolean registered;

    /** Client ticks since registration (END_CLIENT_TICK). Render thread only, like everything below. */
    private static long clientTick;
    private static final ArrayDeque<Attempt> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static long sessionRecords;
    private static long hookFailures;

    /** File work happens here, in order, off the render thread. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-termlog-io");
        t.setDaemon(true);
        return t;
    });
    /** Lines in the current file; -1 until counted (on the IO thread). */
    private static int linesInFile = -1;

    private TerminalOpenLogger() {
    }

    // ---- registration ----------------------------------------------------------------------------------------

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        TerminalOpenLoggerConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TerminalOpenLogger.tick", TerminalOpenLogger::tick));
        ChatObserver.subscribe(message -> onChat(message, false));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Raw packet-path event: nothing may escape it.
            try {
                if (overlay) {
                    onChat(message, true);
                }
            } catch (Throwable t) {
                hookFailed("action bar", t);
            }
        });
        TerminalOpenLoggerCommands.register();
        LOGGER.info("[TermLog] Registered (default ON, records only in an F7/M7 boss)");
    }

    static void onEnabledChanged(boolean on) {
        enabled = on;
        if (!on) {
            armed = false;
        }
    }

    /** Whether attempts are being recorded right now (enabled and in the boss). */
    public static boolean isArmed() {
        return armed;
    }

    /** The boss gate: F7/M7 and in its boss, by the Maxor line or by position. */
    private static boolean inBoss() {
        if (!DungeonState.isF7OrM7()) {
            return false;
        }
        return DungeonState.isBossPhaseActive() || Floor7Tracker.inF7Boss();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** The attempts file: {@code config/killer560/dungeons/termlog/killer560smod-termlog-attempts.jsonl}. */
    public static Path file() {
        return ModPaths.config("killer560smod-termlog-attempts.jsonl");
    }

    static Path rotatedFile() {
        Path f = file();
        return f.resolveSibling("killer560smod-termlog-attempts.1.jsonl");
    }

    // ---- packet path -------------------------------------------------------------------------------------------

    /** From the outbound send hook, for every packet this client sends. Never throws. */
    public static void onPacketSent(Packet<?> packet) {
        if (!armed) {
            return;
        }
        try {
            if (!(packet instanceof ServerboundInteractPacket || packet instanceof ServerboundUseItemOnPacket
                    || packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundAttackPacket)) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) {
                return;
            }
            capture(mc, packet);
        } catch (Throwable t) {
            hookFailed("outbound packet", t);
        }
    }

    /** From the open-screen handler on the render thread, before the screen is set. Never throws. */
    public static void onOpenScreen(ClientboundOpenScreenPacket packet) {
        if (!armed && PENDING.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread() || PENDING.isEmpty()) {
                return;
            }
            String title = clip(ChatFormatting.stripFormatting(packet.getTitle().getString()));
            String terminal = terminalType(title);
            boolean claimed = false;
            for (Attempt a : PENDING) {
                if (a.openTick >= 0 || a.sharedOpen) {
                    continue;
                }
                if (!claimed) {
                    a.openTick = clientTick;
                    a.openTitle = title;
                    a.openTerminal = terminal;
                    a.openContainerId = packet.getContainerId();
                    claimed = true;
                } else {
                    // A later attempt was still waiting when the screen came: which click opened it is unknowable.
                    a.sharedOpen = true;
                    a.openTitle = title;
                    a.openTerminal = terminal;
                }
            }
        } catch (Throwable t) {
            hookFailed("open screen", t);
        }
    }

    private static void onChat(Component message, boolean overlay) {
        if (PENDING.isEmpty() || message == null) {
            return;
        }
        try {
            if (!Minecraft.getInstance().isSameThread()) {
                return;
            }
            // This mod's own lines (ModChat's orange prefix) are not the server's answer.
            if (message.getStyle().getColor() != null && message.getStyle().getColor().getValue() == ModChat.ORANGE) {
                return;
            }
            String plain = ChatFormatting.stripFormatting(message.getString());
            if (plain == null || plain.isBlank()) {
                return;
            }
            // The health/mana action bar repeats every second and says nothing about the terminal.
            if (overlay && (plain.indexOf('❤') >= 0 || plain.indexOf('✎') >= 0)) {
                return;
            }
            String line = clip(plain.trim());
            for (Attempt a : PENDING) {
                List<String> into = overlay ? a.actionBar : a.chat;
                if (into.size() < MAX_LINES_PER_ATTEMPT && !into.contains(line)) {
                    into.add(line);
                }
            }
        } catch (Throwable t) {
            hookFailed("chat", t);
        }
    }

    private static void hookFailed(String where, Throwable t) {
        hookFailures++;
        if (hookFailures <= 5) {
            LOGGER.error("[TermLog] {} hook threw - nothing was changed, the game carries on", where, t);
        }
    }

    // ---- capture -----------------------------------------------------------------------------------------------

    private static final class Attempt {
        final long tick;
        final JsonObject record;
        final JsonArray packets;
        final int standId;
        final String standName;
        final double dyEyeStand;
        final double dyEyeBlock;
        final float pitch;
        final double boxDist;
        final Boolean los;
        final int pendingAtClick;
        long openTick = -1;
        boolean sharedOpen;
        String openTitle;
        String openTerminal;
        int openContainerId = -1;
        final List<String> chat = new ArrayList<>();
        final List<String> actionBar = new ArrayList<>();

        Attempt(long tick, JsonObject record, JsonArray packets, int standId, String standName, double dyEyeStand,
                double dyEyeBlock, float pitch, double boxDist, Boolean los, int pendingAtClick) {
            this.tick = tick;
            this.record = record;
            this.packets = packets;
            this.standId = standId;
            this.standName = standName;
            this.dyEyeStand = dyEyeStand;
            this.dyEyeBlock = dyEyeBlock;
            this.pitch = pitch;
            this.boxDist = boxDist;
            this.los = los;
            this.pendingAtClick = pendingAtClick;
        }
    }

    private static void capture(Minecraft mc, Packet<?> packet) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        List<ArmorStand> terminals = level.getEntitiesOfClass(ArmorStand.class,
                player.getBoundingBox().inflate(NEAR_RANGE), TerminalOpenLogger::isTerminalStand);
        if (terminals.isEmpty()) {
            return;
        }
        Vec3 eye = player.getEyePosition();

        int targetId = packet instanceof ServerboundInteractPacket i ? i.entityId()
                : packet instanceof ServerboundAttackPacket at ? at.entityId() : -1;
        Entity target = targetId >= 0 ? level.getEntity(targetId) : null;

        // The terminal: the stand he clicked if it is one, else the nearest by distance to its box.
        ArmorStand stand = null;
        if (target instanceof ArmorStand st && isTerminalStand(st)) {
            stand = st;
        } else {
            double best = Double.MAX_VALUE;
            for (ArmorStand st : terminals) {
                double d = boxDistance(eye, st.getBoundingBox());
                if (d < best) {
                    best = d;
                    stand = st;
                }
            }
        }
        if (stand == null) {
            return;
        }
        // A left click only counts on the terminal's own cluster of stands, never on a mob next to it.
        if (packet instanceof ServerboundAttackPacket && (target == null
                || target.position().distanceTo(stand.position()) > STAND_CLUSTER)) {
            return;
        }

        JsonObject desc = describePacket(packet, target);
        Attempt last = PENDING.peekLast();
        if (last != null && last.tick == clientTick) {
            // Same client tick: vanilla sends main hand then off hand, or interact then use - one click.
            if (last.packets.size() < MAX_PACKETS_PER_ATTEMPT) {
                last.packets.add(desc);
            }
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            resolve(mc, PENDING.pollFirst());
        }

        JsonObject rec = new JsonObject();
        rec.addProperty("v", 1);
        rec.addProperty("time", Instant.now().toString());
        rec.addProperty("clientTick", clientTick);
        rec.addProperty("gameTime", level.getGameTime());
        JsonArray packets = new JsonArray();
        packets.add(desc);
        rec.add("packets", packets);

        // ---- the player ----
        JsonObject p = new JsonObject();
        p.add("feet", vec(player.position()));
        p.add("eye", vec(eye));
        p.addProperty("yaw", r(player.getYRot()));
        p.addProperty("pitch", r(player.getXRot()));
        p.addProperty("onGround", player.onGround());
        p.add("velocity", vec(player.getDeltaMovement()));
        p.addProperty("sneaking", player.isShiftKeyDown());
        ItemStack held = player.getMainHandItem();
        p.addProperty("heldItem", held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        p.addProperty("heldName", held.isEmpty() ? "" : clip(ChatFormatting.stripFormatting(held.getHoverName().getString())));
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
        p.addProperty("pingMs", info == null ? -1 : info.getLatency());
        rec.add("player", p);

        // ---- the crosshair ----
        rec.add("crosshair", describeCrosshair(level, mc.hitResult));

        // ---- the stand(s) ----
        rec.add("stand", describeStand(stand, target));
        JsonArray cluster = new JsonArray();
        List<ArmorStand> near = level.getEntitiesOfClass(ArmorStand.class,
                stand.getBoundingBox().inflate(STAND_CLUSTER), st -> st != null && !st.isRemoved());
        for (ArmorStand st : near) {
            if (cluster.size() >= MAX_STANDS_LISTED) {
                break;
            }
            cluster.add(describeStand(st, target));
        }
        rec.add("standsNearby", cluster);

        // ---- the terminal block ----
        BlockPos standFeet = BlockPos.containing(stand.position());
        BlockPos termBlock = null;
        String rule = "none";
        double bestSq = Double.MAX_VALUE;
        JsonArray around = new JsonArray();
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos bp = standFeet.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(bp);
                    if (s.isAir()) {
                        continue;
                    }
                    around.add(dx + "," + dy + "," + dz + "=" + blockId(s));
                    if (isCommandBlock(s)) {
                        double d = Vec3.atCenterOf(bp).distanceToSqr(stand.position());
                        if (d < bestSq) {
                            bestSq = d;
                            termBlock = bp;
                            rule = "command_block";
                        }
                    }
                }
            }
        }
        if (termBlock == null && !level.getBlockState(standFeet).isAir()) {
            termBlock = standFeet;
            rule = "at_stand";
        }
        if (termBlock == null && !level.getBlockState(standFeet.below()).isAir()) {
            termBlock = standFeet.below();
            rule = "below_stand";
        }
        JsonObject block = new JsonObject();
        block.addProperty("rule", rule);
        if (termBlock != null) {
            BlockState s = level.getBlockState(termBlock);
            block.add("pos", pos(termBlock));
            block.addProperty("id", blockId(s));
            block.addProperty("state", s.toString());
        }
        rec.add("block", block);
        rec.add("blocksAroundStand", around);

        // ---- geometry ----
        AABB box = stand.getBoundingBox();
        AABB inflated = box.inflate(0.1);
        Vec3 centre = box.getCenter();
        Vec3 look = player.getViewVector(1.0f);
        JsonObject g = new JsonObject();
        double dyEyeStand = eye.y - box.minY;
        g.addProperty("dyEyeStandFeet", r(dyEyeStand));
        g.addProperty("dyEyeStandTop", r(eye.y - box.maxY));
        g.addProperty("dyEyeStandCentre", r(eye.y - centre.y));
        g.addProperty("dyFeetStandFeet", r(player.getY() - box.minY));
        double dyEyeBlock = Double.NaN;
        Boolean losBlock = null;
        if (termBlock != null) {
            dyEyeBlock = eye.y - (termBlock.getY() + 1.0);
            g.addProperty("dyEyeBlockTop", r(dyEyeBlock));
            g.addProperty("dyEyeBlockCentre", r(eye.y - (termBlock.getY() + 0.5)));
            Vec3 bc = Vec3.atCenterOf(termBlock);
            g.addProperty("eyeToBlockCentre", r(eye.distanceTo(bc)));
            g.addProperty("boxDistBlock", r(Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, termBlock))));
            g.addProperty("lookAngleToBlock", r(angle(look, bc.subtract(eye))));
            losBlock = losToBlock(level, player, eye, termBlock, ClipContext.Block.OUTLINE);
            g.addProperty("losBlock", losBlock);
            g.addProperty("losBlockCollider", losToBlock(level, player, eye, termBlock, ClipContext.Block.COLLIDER));
        }
        double dx = centre.x - eye.x;
        double dz = centre.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double boxDist = boxDistance(eye, box);
        g.addProperty("horizDistStand", r(horiz));
        g.addProperty("eyeToStandCentre", r(eye.distanceTo(centre)));
        g.addProperty("boxDistStand", r(boxDist));
        g.addProperty("boxDistStandInflated", r(boxDistance(eye, inflated)));
        g.addProperty("lookAngleToStand", r(angle(look, centre.subtract(eye))));
        g.addProperty("pitchToStandCentre", r(-Math.toDegrees(Math.atan2(centre.y - eye.y, horiz))));
        Vec3 far = eye.add(look.scale(NEAR_RANGE));
        g.addProperty("lookHitsStandBox", box.contains(eye) || box.clip(eye, far).isPresent());
        g.addProperty("lookHitsStandBoxInflated", inflated.contains(eye) || inflated.clip(eye, far).isPresent());
        boolean losStand = losToBox(level, player, eye, box, ClipContext.Block.OUTLINE);
        g.addProperty("losStand", losStand);
        g.addProperty("losStandCollider", losToBox(level, player, eye, box, ClipContext.Block.COLLIDER));
        rec.add("geometry", g);

        Attempt a = new Attempt(clientTick, rec, packets, stand.getId(), standName(stand), dyEyeStand, dyEyeBlock,
                player.getXRot(), boxDist, losBlock != null ? losBlock : losStand, PENDING.size());
        PENDING.addLast(a);
    }

    private static JsonObject describePacket(Packet<?> packet, Entity target) {
        JsonObject o = new JsonObject();
        if (packet instanceof ServerboundInteractPacket i) {
            o.addProperty("type", "interact");
            o.addProperty("entityId", i.entityId());
            o.addProperty("hand", String.valueOf(i.hand()));
            if (i.location() != null) {
                o.add("hitOnEntity", vec(i.location()));
            }
            o.addProperty("secondaryAction", i.usingSecondaryAction());
        } else if (packet instanceof ServerboundAttackPacket at) {
            o.addProperty("type", "attack");
            o.addProperty("entityId", at.entityId());
        } else if (packet instanceof ServerboundUseItemOnPacket u) {
            o.addProperty("type", "use_item_on");
            o.addProperty("hand", String.valueOf(u.getHand()));
            BlockHitResult hit = u.getHitResult();
            o.add("blockPos", pos(hit.getBlockPos()));
            o.addProperty("face", String.valueOf(hit.getDirection()));
            o.add("hitLocation", vec(hit.getLocation()));
            o.addProperty("inside", hit.isInside());
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                o.addProperty("block", blockId(mc.level.getBlockState(hit.getBlockPos())));
            }
        } else if (packet instanceof ServerboundUseItemPacket u) {
            o.addProperty("type", "use_item");
            o.addProperty("hand", String.valueOf(u.getHand()));
            o.addProperty("yaw", r(u.getYRot()));
            o.addProperty("pitch", r(u.getXRot()));
        }
        if (target != null) {
            JsonObject t = new JsonObject();
            t.addProperty("type", String.valueOf(EntityType.getKey(target.getType())));
            t.addProperty("name", clip(ChatFormatting.stripFormatting(target.getName().getString())));
            t.add("pos", vec(target.position()));
            t.add("box", box(target.getBoundingBox()));
            o.add("target", t);
        }
        return o;
    }

    private static JsonObject describeCrosshair(ClientLevel level, HitResult hit) {
        JsonObject o = new JsonObject();
        if (hit == null) {
            o.addProperty("type", "NONE");
            return o;
        }
        o.addProperty("type", String.valueOf(hit.getType()));
        o.add("location", vec(hit.getLocation()));
        if (hit instanceof EntityHitResult eh && eh.getEntity() != null) {
            Entity e = eh.getEntity();
            o.addProperty("entityId", e.getId());
            o.addProperty("entityType", String.valueOf(EntityType.getKey(e.getType())));
            o.addProperty("entityName", clip(ChatFormatting.stripFormatting(e.getName().getString())));
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            o.add("blockPos", pos(bh.getBlockPos()));
            o.addProperty("face", String.valueOf(bh.getDirection()));
            o.addProperty("block", blockId(level.getBlockState(bh.getBlockPos())));
        }
        return o;
    }

    private static JsonObject describeStand(ArmorStand st, Entity target) {
        JsonObject o = new JsonObject();
        o.addProperty("id", st.getId());
        o.addProperty("name", standName(st));
        o.add("pos", vec(st.position()));
        o.add("box", box(st.getBoundingBox()));
        o.addProperty("marker", st.isMarker());
        o.addProperty("small", st.isSmall());
        o.addProperty("invisible", st.isInvisible());
        o.addProperty("clicked", target != null && target.getId() == st.getId());
        return o;
    }

    // ---- outcome -----------------------------------------------------------------------------------------------

    private static void tick(Minecraft mc) {
        clientTick++;
        boolean bossNow = enabled && mc.player != null && inBoss();
        if (bossNow) {
            lastBossTick = clientTick;
            lastBossLevel = mc.level;
        }
        // A short grace, so a sidebar that blinks out for a tick (seen in the testkit right after a terminal opened)
        // does not drop a click; it is still OFF the moment the setting is, and it ends at once on leaving the world or
        // the floor (testkit 409 found it still armed outside any dungeon 5 s after a boss).
        boolean graceHolds = lastBossTick >= 0 && clientTick - lastBossTick <= BOSS_GRACE_TICKS
                && mc.level != null && mc.level == lastBossLevel && DungeonState.isF7OrM7();
        boolean nowArmed = enabled && (bossNow || graceHolds);
        if (nowArmed != armed) {
            armed = nowArmed;
            LOGGER.info("[TermLog] {} (floor {}, boss phase {})", nowArmed ? "armed - in the boss" : "disarmed",
                    DungeonState.getFloor(), DungeonState.isBossPhaseActive());
            if (nowArmed && TerminalOpenLoggerConfig.getInstance().isChatLines()) {
                ModChat.send(FEATURE, ModChat.dim("logging terminal clicks this boss (/termlog summary)"));
            }
        }
        if (PENDING.isEmpty()) {
            return;
        }
        if (!enabled) {
            PENDING.clear();
            return;
        }
        Iterator<Attempt> it = PENDING.iterator();
        while (it.hasNext()) {
            Attempt a = it.next();
            if (clientTick - a.tick >= WINDOW_TICKS) {
                it.remove();
                resolve(mc, a);
            }
        }
    }

    private static void resolve(Minecraft mc, Attempt a) {
        if (a == null) {
            return;
        }
        String outcome;
        if (a.openTick >= 0) {
            outcome = a.openTerminal != null ? "OPEN" : "OTHER_SCREEN";
        } else if (a.sharedOpen) {
            outcome = "OPEN_SHARED";
        } else {
            outcome = "NO_OPEN";
        }
        JsonObject o = new JsonObject();
        o.addProperty("result", outcome);
        o.addProperty("windowTicks", WINDOW_TICKS);
        if (a.openTick >= 0) {
            o.addProperty("delayTicks", a.openTick - a.tick);
            o.addProperty("containerId", a.openContainerId);
        }
        if (a.openTitle != null) {
            o.addProperty("title", a.openTitle);
        }
        if (a.openTerminal != null) {
            o.addProperty("terminal", a.openTerminal);
        }
        JsonArray chat = new JsonArray();
        a.chat.forEach(chat::add);
        o.add("chat", chat);
        JsonArray bar = new JsonArray();
        a.actionBar.forEach(bar::add);
        o.add("actionBar", bar);
        Entity standNow = mc.level == null ? null : mc.level.getEntity(a.standId);
        String nameAfter = standNow instanceof ArmorStand st ? standName(st) : null;
        o.addProperty("standNameAtClick", a.standName);
        if (nameAfter != null) {
            o.addProperty("standNameAfter", nameAfter);
        }
        o.addProperty("standGone", standNow == null);
        o.addProperty("standNameChanged", nameAfter != null && !nameAfter.equals(a.standName));
        o.addProperty("pendingAtClick", a.pendingAtClick);
        a.record.add("outcome", o);

        String line = a.record.toString();
        synchronized (RECENT) {
            RECENT.addLast(line);
            while (RECENT.size() > RECENT_CAP) {
                RECENT.pollFirst();
            }
        }
        sessionRecords++;
        try {
            IO.execute(() -> append(line));
        } catch (Exception e) {
            LOGGER.warn("[TermLog] could not queue a record: {}", e.toString());
        }
        if (TerminalOpenLoggerConfig.getInstance().isChatLines()) {
            ModChat.send(FEATURE, chatLine(outcome, a));
        }
    }

    private static Component chatLine(String outcome, Attempt a) {
        StringBuilder rest = new StringBuilder();
        if (a.openTick >= 0) {
            rest.append(' ').append(a.openTick - a.tick).append('t');
        }
        // Kept short for a real run; the title and everything else are in the file.
        rest.append(" | eye-stand ").append(signed(a.dyEyeStand));
        if (!Double.isNaN(a.dyEyeBlock)) {
            rest.append(" | eye-block ").append(signed(a.dyEyeBlock));
        }
        rest.append(" | pitch ").append(Math.round(a.pitch));
        rest.append(" | dist ").append(String.format(Locale.ROOT, "%.2f", a.boxDist));
        rest.append(" | LOS ").append(a.los == null ? "?" : a.los ? "yes" : "no");
        if (!a.chat.isEmpty()) {
            rest.append(" | chat: ").append(a.chat.get(0));
        }
        String head = switch (outcome) {
            case "OPEN" -> "OPEN";
            case "OPEN_SHARED" -> "OPEN? (another click was waiting)";
            case "OTHER_SCREEN" -> "OTHER SCREEN";
            default -> "NO OPEN";
        };
        return Component.empty()
                .append("OPEN".equals(outcome) ? ModChat.good(head) : "NO_OPEN".equals(outcome) ? ModChat.bad(head)
                        : ModChat.value(head))
                .append(ModChat.text(rest.toString()));
    }

    // ---- file --------------------------------------------------------------------------------------------------

    /** IO thread only. */
    private static void append(String line) {
        try {
            Path f = file();
            if (linesInFile < 0) {
                linesInFile = countLines(f);
            }
            if (linesInFile >= MAX_RECORDS) {
                Files.move(f, rotatedFile(), StandardCopyOption.REPLACE_EXISTING);
                linesInFile = 0;
            }
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            linesInFile++;
        } catch (Exception e) {
            LOGGER.warn("[TermLog] could not write {}: {}", file(), e.toString());
        }
    }

    private static int countLines(Path f) {
        if (!Files.exists(f)) {
            return 0;
        }
        int n = 0;
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            while (in.readLine() != null) {
                n++;
            }
        } catch (Exception e) {
            return 0;
        }
        return n;
    }

    /** Waits (bounded) for every queued write. For the commands and the testkit. */
    public static void flush() {
        try {
            Future<?> done = IO.submit(() -> { });
            done.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A slow disk only delays the next read.
        }
    }

    /** Deletes both files. Runs after every queued write. */
    public static void clearFiles() {
        try {
            IO.submit(() -> {
                try {
                    Files.deleteIfExists(file());
                    Files.deleteIfExists(rotatedFile());
                } catch (Exception e) {
                    LOGGER.warn("[TermLog] could not clear: {}", e.toString());
                }
                linesInFile = 0;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // reported above
        }
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    /** Lines in the current file after every queued write. */
    public static int recordsInFile() {
        flush();
        return countLines(file());
    }

    /** The last {@value #RECENT_CAP} records this session, oldest first (JSON lines). For the testkit. */
    public static List<String> recentRecords() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    public static long sessionRecords() {
        return sessionRecords;
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    // ---- summary -----------------------------------------------------------------------------------------------

    /** Lines of {@code /termlog summary}: open rate bucketed by eye height and pitch, from both files. */
    public static List<String> summaryLines() {
        flush();
        List<String> lines = new ArrayList<>();
        Map<Double, int[]> byEyeStand = new TreeMap<>();
        Map<Double, int[]> byEyeBlock = new TreeMap<>();
        Map<Double, int[]> byPitch = new TreeMap<>();
        Map<Double, int[]> byDist = new TreeMap<>();
        int total = 0;
        int open = 0;
        int skipped = 0;
        for (Path f : new Path[]{rotatedFile(), file()}) {
            if (!Files.exists(f)) {
                continue;
            }
            try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String raw;
                int read = 0;
                while ((raw = in.readLine()) != null && read++ < MAX_RECORDS * 2) {
                    try {
                        JsonObject rec = JsonParser.parseString(raw).getAsJsonObject();
                        String result = rec.getAsJsonObject("outcome").get("result").getAsString();
                        if (!rightClick(rec) || !("OPEN".equals(result) || "NO_OPEN".equals(result))) {
                            skipped++;
                            continue;
                        }
                        boolean opened = "OPEN".equals(result);
                        JsonObject g = rec.getAsJsonObject("geometry");
                        total++;
                        if (opened) {
                            open++;
                        }
                        bucket(byEyeStand, g.get("dyEyeStandFeet").getAsDouble(), 0.5, opened);
                        if (g.has("dyEyeBlockTop")) {
                            bucket(byEyeBlock, g.get("dyEyeBlockTop").getAsDouble(), 0.5, opened);
                        }
                        bucket(byPitch, rec.getAsJsonObject("player").get("pitch").getAsDouble(), 10.0, opened);
                        bucket(byDist, g.get("boxDistStand").getAsDouble(), 0.5, opened);
                    } catch (Exception e) {
                        skipped++;
                    }
                }
            } catch (Exception e) {
                lines.add("could not read " + f.getFileName() + ": " + e);
            }
        }
        lines.add(String.format(Locale.ROOT, "%d right-click attempt(s), %d opened%s", total, open,
                skipped > 0 ? " (" + skipped + " skipped: left clicks, other screens, or two clicks one open)" : ""));
        if (total == 0) {
            return lines;
        }
        table(lines, "eye - stand feet", byEyeStand, 0.5, "");
        if (!byEyeBlock.isEmpty()) {
            table(lines, "eye - terminal block top", byEyeBlock, 0.5, "");
        }
        table(lines, "pitch (negative = looking up)", byPitch, 10.0, "");
        table(lines, "distance to stand box", byDist, 0.5, "");
        return lines;
    }

    private static boolean rightClick(JsonObject rec) {
        for (JsonElement e : rec.getAsJsonArray("packets")) {
            String t = e.getAsJsonObject().get("type").getAsString();
            if (!"attack".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static void bucket(Map<Double, int[]> map, double v, double width, boolean opened) {
        double key = Math.floor(v / width) * width;
        int[] c = map.computeIfAbsent(key, k -> new int[2]);
        c[0]++;
        if (opened) {
            c[1]++;
        }
    }

    private static void table(List<String> lines, String title, Map<Double, int[]> map, double width, String unit) {
        lines.add(title + ":");
        for (Map.Entry<Double, int[]> e : map.entrySet()) {
            int[] c = e.getValue();
            String range = width >= 1 ? String.format(Locale.ROOT, "%+.0f..%+.0f", e.getKey(), e.getKey() + width)
                    : String.format(Locale.ROOT, "%+.1f..%+.1f", e.getKey(), e.getKey() + width);
            lines.add(String.format(Locale.ROOT, "  %s%s  %d/%d open (%d%%)", range, unit, c[1], c[0],
                    Math.round(100.0 * c[1] / c[0])));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Hypixel's names over a P3 terminal, unfinished and finished (see p3nav/P3NavFeature). Exact, not contains. */
    static boolean isTerminalStand(ArmorStand stand) {
        if (stand == null || stand.isRemoved()) {
            return false;
        }
        String n = standName(stand);
        return "Inactive Terminal".equals(n) || "Terminal Active".equals(n) || "Active Terminal".equals(n);
    }

    private static String standName(ArmorStand stand) {
        Component name = stand.getCustomName();
        if (name == null) {
            return "";
        }
        String s = ChatFormatting.stripFormatting(name.getString());
        return s == null ? "" : clip(s.trim());
    }

    private static String terminalType(String title) {
        for (TerminalType type : TerminalType.values()) {
            Matcher m = type.titlePattern().matcher(title);
            if (m.matches()) {
                return type.name();
            }
        }
        return null;
    }

    private static boolean isCommandBlock(BlockState s) {
        return s.is(Blocks.COMMAND_BLOCK) || s.is(Blocks.CHAIN_COMMAND_BLOCK) || s.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    private static String blockId(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    private static double boxDistance(Vec3 eye, AABB box) {
        return Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, box));
    }

    /** Whether a ray from the eye reaches the stand's box before any block. */
    private static boolean losToBox(ClientLevel level, LocalPlayer player, Vec3 eye, AABB box, ClipContext.Block mode) {
        if (box.contains(eye)) {
            return true;
        }
        Vec3 centre = box.getCenter();
        Vec3 entry = box.clip(eye, centre).orElse(centre);
        BlockHitResult hit = level.clip(new ClipContext(eye, entry, mode, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) >= entry.distanceToSqr(eye) - 1e-6;
    }

    /** Whether a ray from the eye to the block's centre meets that block first. */
    private static boolean losToBlock(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, ClipContext.Block mode) {
        BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), mode, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private static double angle(Vec3 a, Vec3 b) {
        double la = a.length();
        double lb = b.length();
        if (la < 1e-9 || lb < 1e-9) {
            return 0;
        }
        double c = Math.max(-1, Math.min(1, a.dot(b) / (la * lb)));
        return Math.toDegrees(Math.acos(c));
    }

    private static String signed(double v) {
        return String.format(Locale.ROOT, "%+.2f", v);
    }

    private static double r(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(r(v.x));
        a.add(r(v.y));
        a.add(r(v.z));
        return a;
    }

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static JsonArray box(AABB b) {
        JsonArray a = new JsonArray();
        a.add(r(b.minX));
        a.add(r(b.minY));
        a.add(r(b.minZ));
        a.add(r(b.maxX));
        a.add(r(b.maxY));
        a.add(r(b.maxZ));
        return a;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LINE_LENGTH ? s : s.substring(0, MAX_LINE_LENGTH);
    }
}
