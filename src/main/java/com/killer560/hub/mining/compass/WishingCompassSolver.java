package com.killer560.hub.mining.compass;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Wishing Compass Solver (legit, both builds; Skyblocker's WishingCompassSolver, rewritten): in the Crystal Hollows, a
 * Wishing Compass shoots a trail of happy-villager particles toward the structure it points at. The first 25 trail
 * particles of a use give a direction from where you stood; a second use at least 8 blocks away gives another, and where
 * the two lines pass within 5 blocks of each other is the target: a box and label there (Show Waypoint) and its
 * coordinates in chat, named after the zone you used it in. Unlike Skyblocker, it never cancels a use. It reads the
 * particles the server sends and your own right-click; it sends nothing. Off by default.
 */
public final class WishingCompassSolver {

    public static final IslandConfig CFG = IslandConfig.of("mining", "compass")
            .flag("waypoint", true);

    private enum State { IDLE, FIRST, WAIT_SECOND, SECOND }

    private static final int PARTICLES_PER_LINE = 25;
    private static final long PARTICLE_GAP_MS = 500;
    private static final double PARTICLE_STEP = 0.9;
    private static final double MIN_MOVE = 8;
    private static final double MAX_MISS = 5;
    private static final String FAIL = "The Wishing Compass can't seem to locate anything!";

    private static State state = State.IDLE;
    private static Vec3 startOne = Vec3.ZERO;
    private static Vec3 startTwo = Vec3.ZERO;
    private static Vec3 dirOne = Vec3.ZERO;
    private static Vec3 dirTwo = Vec3.ZERO;
    private static int used = 0;
    private static long lastParticle = 0;
    private static Vec3 lastPos = Vec3.ZERO;
    private static volatile Vec3 target = null;
    private static volatile String targetName = "";

    private WishingCompassSolver() {
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            onUse(hand);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            onUse(hand);
            return InteractionResult.PASS;
        });
        ChatObserver.subscribe(WishingCompassSolver::onChat);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            reset();
            target = null;
        }));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Wishing Compass render", CFG::isEnabledRaw), WishingCompassSolver::onWorldRender));
    }

    /** The solved target, or null (read by the testkit). */
    public static Vec3 target() {
        return target;
    }

    public static String targetName() {
        return targetName;
    }

    /** The solver's step: IDLE, FIRST, WAIT_SECOND or SECOND (read by the testkit). */
    public static String state() {
        return state.name();
    }

    private static void reset() {
        state = State.IDLE;
        used = 0;
        dirOne = Vec3.ZERO;
        dirTwo = Vec3.ZERO;
    }

    private static void onChat(Component message) {
        if (CFG.isEnabledRaw() && state != State.IDLE && FAIL.equals(ChatObserver.strip(message))) {
            reset();
        }
    }

    private static void onUse(InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !CFG.isEnabled() || !MiningIsland.crystalHollows()) {
            return;
        }
        ItemStack stack = mc.player.getItemInHand(hand);
        String id = ItemProtect.skyblockId(stack);
        if (!"WISHING_COMPASS".equals(id) && !stack.getHoverName().getString().contains("Wishing Compass")) {
            return;
        }
        startUse(mc.player.position().add(0, 1.62, 0));
    }

    /** A compass use from {@code eye}: the first, or the second once 8 blocks from the first. Testkit entry point. */
    public static void startUse(Vec3 eye) {
        long now = System.currentTimeMillis();
        switch (state) {
            case IDLE -> begin(State.FIRST, eye, now);
            case WAIT_SECOND -> {
                if (startOne.distanceTo(eye) < MIN_MOVE) {
                    ModChat.send("Wishing Compass", ModChat.text("Move at least 8 blocks from your first use, then use it again."));
                    return;
                }
                begin(State.SECOND, eye, now);
            }
            case FIRST, SECOND -> {
                if (now - lastParticle < PARTICLE_GAP_MS) {
                    return; // still reading this use's trail
                }
                reset();
                begin(State.FIRST, eye, now);
            }
        }
    }

    private static void begin(State s, Vec3 eye, long now) {
        state = s;
        used = 0;
        lastParticle = now;
        lastPos = eye;
        if (s == State.FIRST) {
            startOne = eye;
            dirOne = Vec3.ZERO;
        } else {
            startTwo = eye;
            dirTwo = Vec3.ZERO;
        }
    }

    /** Called from the particle packet handler on the render thread. */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet, long now) {
        if ((state != State.FIRST && state != State.SECOND) || !CFG.isEnabled() || !MiningIsland.crystalHollows()) {
            return;
        }
        if (com.killer560.hub.compat.McPackets.particle(packet) == null || com.killer560.hub.compat.McPackets.particle(packet).getType() != ParticleTypes.HAPPY_VILLAGER) {
            return;
        }
        onTrailPoint(new Vec3(com.killer560.hub.compat.McPackets.particleX(packet), com.killer560.hub.compat.McPackets.particleY(packet), com.killer560.hub.compat.McPackets.particleZ(packet)), now);
    }

    static void onTrailPoint(Vec3 p, long now) {
        lastParticle = now;
        if (p.distanceTo(lastPos) > PARTICLE_STEP) {
            return; // not the next point of this trail
        }
        lastPos = p;
        Vec3 start = state == State.FIRST ? startOne : startTwo;
        Vec3 d = p.subtract(start);
        if (d.lengthSqr() < 1e-6) {
            return;
        }
        Vec3 step = d.normalize().scale(1.0 / PARTICLES_PER_LINE);
        if (state == State.FIRST) {
            dirOne = dirOne.add(step);
        } else {
            dirTwo = dirTwo.add(step);
        }
        if (++used < PARTICLES_PER_LINE) {
            return;
        }
        if (state == State.FIRST) {
            state = State.WAIT_SECOND;
            ModChat.send("Wishing Compass", ModChat.text("First reading taken. Move 8 blocks or more and use it again."));
            return;
        }
        Vec3 t = solve(startOne, dirOne, startTwo, dirTwo);
        reset();
        if (t == null) {
            ModChat.send("Wishing Compass", ModChat.bad("The two readings do not meet; use it twice again."));
            return;
        }
        target = t;
        targetName = zoneTarget(startOne);
        ModChat.send("Wishing Compass", ModChat.text(targetName + ": "),
                ModChat.value(String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(t.x), (int) Math.floor(t.y), (int) Math.floor(t.z))));
    }

    /**
     * Where line {@code a + s*da} and line {@code b + t*db} come closest, the midpoint of the two closest points, or null
     * when they are parallel or miss by more than 5 blocks. Testkit entry point.
     */
    public static Vec3 solve(Vec3 a, Vec3 da, Vec3 b, Vec3 db) {
        Vec3 w = a.subtract(b);
        double aa = da.dot(da);
        double bb = da.dot(db);
        double cc = db.dot(db);
        double dd = da.dot(w);
        double ee = db.dot(w);
        double den = aa * cc - bb * bb;
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        double s = (bb * ee - cc * dd) / den;
        double t = (aa * ee - bb * dd) / den;
        Vec3 p1 = a.add(da.scale(s));
        Vec3 p2 = b.add(db.scale(t));
        if (p1.distanceTo(p2) > MAX_MISS || s < 0 || t < 0) {
            return null;
        }
        return p1.add(p2).scale(0.5);
    }

    /** What the compass points at from a zone (Skyblocker's zone boxes; the Nucleus is the middle). */
    static String zoneTarget(Vec3 p) {
        if (p.y < 64) {
            return "Khazad-dûm (Magma Fields)";
        }
        if (p.x >= 462 && p.x <= 564 && p.z >= 461 && p.z <= 565) {
            return "Compass target (used in the Nucleus, which reads poorly)";
        }
        boolean east = p.x >= 512;
        boolean south = p.z >= 512;
        if (!east && !south) {
            return "Jungle Temple or Odawa (Jungle)";
        }
        if (east && !south) {
            return "Mines of Divan (Mithril Deposits)";
        }
        if (!east) {
            return "Goblin King or Queen (Goblin Holdout)";
        }
        return "Lost Precursor City (Precursor Remnants)";
    }

    private static void onWorldRender(LevelRenderContext context) {
        Vec3 t = target;
        if (t == null || !CFG.on("waypoint") || !CFG.isEnabled() || !MiningIsland.crystalHollows()) {
            return;
        }
        AABB box = new AABB(t.x - 1, t.y - 1, t.z - 1, t.x + 1, t.y + 1, t.z + 1);
        WorldRenderUtils.renderOutlineBox(context, box, 1f, 0.85f, 0.2f, 1f, 2f);
        IslandRender.label(context, t.x, t.y + 2, t.z, targetName, 0xFFD54F);
    }
}
