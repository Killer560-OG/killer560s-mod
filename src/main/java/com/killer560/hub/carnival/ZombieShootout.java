package com.killer560.hub.carnival;

import com.killer560.hub.islands.IslandHud;
import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Zombie Shootout Helper (SkyHanni's Zombie Shootout and Skyblocker's helper): while you play Zombie Shootout, each
 * zombie is outlined in its armor's color with a timer over it counting down its lifetime, the best target (a lit
 * Redstone Lamp first, then the zombie worth the most, soonest gone) gets its own box, a lit lamp is boxed with a line to
 * it, and a HUD shows the best target, the lamp's time left and the zombies up. Display only: it never shoots
 * (the cheat build's Zombie Shootout Triggerbot and Auto Zombie Shootout do, and share this class's target pick).
 *
 * <p>Points and lifetimes are the wiki's (Leather 30/7 s, Iron 50/6 s, Gold 80/5 s, Diamond 120/4 s, a baby Leather
 * zombie twice the points; a lamp 100 and 1.2x points for 6 s). SkyHanni's lifetimes are a second longer each;
 * unverified in game which is current, as is whether the tier is on the helmet (SkyHanni) or the chestplate
 * (Skyblocker) - both are read. Lamps are Skyblocker's twelve spots plus any lamp near you when the game starts.
 */
public final class ZombieShootout {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");

    /** A zombie's armor tier. */
    public enum Tier {
        LEATHER("leather_", "Leather", 30, 7000, 0xFFA0522D),
        IRON("iron_", "Iron", 50, 6000, 0xFFD8D8D8),
        GOLD("golden_", "Gold", 80, 5000, 0xFFFFD700),
        DIAMOND("diamond_", "Diamond", 120, 4000, 0xFF55FFFF);

        final String prefix;
        public final String label;
        public final int points;
        public final long lifetimeMs;
        public final int color;

        Tier(String prefix, String label, int points, long lifetimeMs, int color) {
            this.prefix = prefix;
            this.label = label;
            this.points = points;
            this.lifetimeMs = lifetimeMs;
            this.color = color;
        }

        /** The tier of a zombie's helmet or chestplate, or null. */
        public static Tier of(Zombie z) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST}) {
                ItemStack s = z.getItemBySlot(slot);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                for (Tier t : values()) {
                    if (id.startsWith(t.prefix)) {
                        return t;
                    }
                }
            }
            return null;
        }
    }

    /** Something worth shooting: a zombie or a lit lamp. */
    public record Target(Zombie zombie, BlockPos lamp, AABB box, int points, long msLeft, int color, String label) {
        public Vec3 aimPoint() {
            return box.getCenter();
        }
    }

    /** Skyblocker's lamp spots. */
    private static final BlockPos[] KNOWN_LAMPS = {
            new BlockPos(-96, 76, 61), new BlockPos(-99, 77, 62), new BlockPos(-102, 75, 62),
            new BlockPos(-106, 77, 61), new BlockPos(-109, 75, 60), new BlockPos(-112, 76, 58),
            new BlockPos(-115, 77, 55), new BlockPos(-117, 76, 52), new BlockPos(-118, 76, 49),
            new BlockPos(-119, 75, 45), new BlockPos(-119, 77, 42), new BlockPos(-118, 76, 39)};
    public static final long LAMP_MS = 6000;
    private static final double RANGE = 64;

    private static final Map<Integer, Long> FIRST_SEEN = new HashMap<>();
    private static final Set<BlockPos> LAMPS = new LinkedHashSet<>();
    private static final Map<BlockPos, Long> LIT_SINCE = new HashMap<>();
    private static boolean lampsScanned;
    private static List<Target> targets = List.of();
    private static Target best;
    private static List<String> hud = List.of();

    private ZombieShootout() {
    }

    /** True while the helper, the triggerbot or the auto needs the targets read. */
    static boolean wanted() {
        if (ZombieShootoutConfig.getInstance().isEnabledRaw()) {
            return true;
        }
        for (java.util.function.BooleanSupplier w : WANTS) {
            if (w.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static final List<java.util.function.BooleanSupplier> WANTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A cheat feature that shoots registers its switch here so the targets are read for it too. */
    static void addWant(java.util.function.BooleanSupplier w) {
        WANTS.add(w);
        FeatureBits.changed();
    }

    public static void register() {
        CarnivalState.register();
        CarnivalState.addDemand(ZombieShootout::wanted);
        CarnivalState.onGameStart(g -> {
            if (g == CarnivalState.Game.ZOMBIE_SHOOTOUT) {
                FIRST_SEEN.clear();
                LIT_SINCE.clear();
                lampsScanned = false;
            }
        });
        int bit = FeatureBits.register("Zombie Shootout Helper", ZombieShootout::wanted);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ZombieShootout.tick", bit, ZombieShootout::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(bit, ZombieShootout::render));
    }

    static boolean active() {
        return CarnivalState.playing(CarnivalState.Game.ZOMBIE_SHOOTOUT) && CarnivalState.inCarnival();
    }

    private static void tick(Minecraft mc) {
        if (!active() || mc.level == null || mc.player == null) {
            targets = List.of();
            best = null;
            hud = List.of();
            return;
        }
        if (!lampsScanned) {
            scanLamps(mc.level, mc.player.blockPosition());
        }
        long now = System.currentTimeMillis();
        List<Target> list = new ArrayList<>();
        Set<Integer> alive = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Zombie z) || !z.isAlive() || z.distanceToSqr(mc.player) > RANGE * RANGE) {
                continue;
            }
            Tier t = Tier.of(z);
            if (t == null) {
                continue;
            }
            alive.add(z.getId());
            long seen = FIRST_SEEN.computeIfAbsent(z.getId(), k -> now);
            long left = Math.max(0, t.lifetimeMs - (now - seen));
            int pts = t == Tier.LEATHER && z.isBaby() ? t.points * 2 : t.points;
            list.add(new Target(z, null, z.getBoundingBox(), pts, left, t.color, (z.isBaby() ? "Baby " : "") + t.label));
        }
        FIRST_SEEN.keySet().retainAll(alive);
        for (BlockPos p : LAMPS) {
            if (lit(mc.level, p)) {
                long since = LIT_SINCE.computeIfAbsent(p, k -> now);
                list.add(new Target(null, p, new AABB(p), 100, Math.max(0, LAMP_MS - (now - since)), 0xFFFF5555, "Lamp"));
            } else {
                LIT_SINCE.remove(p);
            }
        }
        targets = list;
        best = pickBest(list, null);
        hud = buildHud();
    }

    private static void scanLamps(ClientLevel level, BlockPos at) {
        lampsScanned = true;
        LAMPS.clear();
        for (BlockPos p : KNOWN_LAMPS) {
            if (isLamp(level, p)) {
                LAMPS.add(p);
            }
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                for (int y = -6; y <= 12; y++) {
                    m.set(at.getX() + x, at.getY() + y, at.getZ() + z);
                    if (isLamp(level, m)) {
                        LAMPS.add(m.immutable());
                    }
                }
            }
        }
        LOGGER.info("[Zombie Shootout] {} redstone lamp(s) watched", LAMPS.size());
    }

    private static boolean isLamp(ClientLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath().equals("redstone_lamp");
    }

    private static boolean lit(ClientLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT)
                && BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().equals("redstone_lamp");
    }

    /**
     * The target to shoot first among {@code list}: a lit lamp (100 points and 1.2x after), then the most points, then
     * the soonest gone; {@code visible} (may be null) filters out what it says no to.
     */
    static Target pickBest(List<Target> list, java.util.function.Predicate<Target> visible) {
        Target b = null;
        for (Target t : list) {
            // A target past its expected lifetime is still ranked (the lifetimes are unverified, so it may well still be
            // up); its time left only breaks ties.
            if (visible != null && !visible.test(t)) {
                continue;
            }
            if (b == null || better(t, b)) {
                b = t;
            }
        }
        return b;
    }

    private static boolean better(Target a, Target b) {
        boolean la = a.lamp() != null;
        boolean lb = b.lamp() != null;
        if (la != lb) {
            return la;
        }
        if (a.points() != b.points()) {
            return a.points() > b.points();
        }
        return a.msLeft() < b.msLeft();
    }

    /** The target a ray from {@code player}'s eye along its look meets first within {@code reach}, if any, with no
     *  block in front of it. */
    static Target targetUnderCrosshair(LocalPlayer player, double reach) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(player.getXRot(), player.getYRot()).scale(reach));
        Target hit = null;
        double hitDist = Double.MAX_VALUE;
        for (Target t : targets) {
            AABB box = t.box().inflate(t.lamp() != null ? 0.0 : 0.1);
            Optional<Vec3> at = box.clip(eye, end);
            if (box.contains(eye)) {
                at = Optional.of(eye);
            }
            if (at.isPresent()) {
                double d = eye.distanceTo(at.get());
                if (d < hitDist) {
                    hitDist = d;
                    hit = t;
                }
            }
        }
        if (hit == null) {
            return null;
        }
        HitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        if (block.getType() == HitResult.Type.BLOCK) {
            double bd = eye.distanceTo(block.getLocation());
            boolean thatLamp = hit.lamp() != null
                    && ((net.minecraft.world.phys.BlockHitResult) block).getBlockPos().equals(hit.lamp());
            if (bd < hitDist - 0.05 && !thatLamp) {
                return null;
            }
        }
        return hit;
    }

    /** Whether nothing solid stands between the eye and {@code t}'s aim point. */
    static boolean inSight(LocalPlayer player, Target t) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = t.aimPoint();
        HitResult block = player.level().clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        if (block.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        return t.lamp() != null && ((net.minecraft.world.phys.BlockHitResult) block).getBlockPos().equals(t.lamp());
    }

    static List<Target> targets() {
        return targets;
    }

    static Target best() {
        return best;
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    private static void render(LevelRenderContext context) {
        ZombieShootoutConfig cfg = ZombieShootoutConfig.getInstance();
        if (!cfg.isEnabled() || !active()) {
            return;
        }
        int top = 0;
        for (Target t : targets) {
            if (t.zombie() != null) {
                top = Math.max(top, t.points());
            }
        }
        Target b = best;
        for (Target t : targets) {
            if (t.lamp() != null) {
                if (cfg.isLamps()) {
                    float[] c = WorldRenderUtils.argbToFloats(t.color());
                    WorldRenderUtils.renderOutlineBox(context, t.box().inflate(0.02), c[0], c[1], c[2], 1f, 4f);
                    if (cfg.isLampLine()) {
                        WorldRenderUtils.renderLine(context, WorldRenderUtils.tracerOrigin(context), t.aimPoint(),
                                c[0], c[1], c[2], 1f, 2f, false);
                    }
                }
                continue;
            }
            boolean show = !cfg.isHighestOnly() || t.points() == top;
            if (cfg.isOutlines() && show) {
                float[] c = WorldRenderUtils.argbToFloats(t.color());
                WorldRenderUtils.renderOutlineBox(context, t.box().inflate(0.05), c[0], c[1], c[2], 1f, 2.5f);
            }
            if (cfg.isTimers() && show) {
                double secs = t.msLeft() / 1000.0;
                String col = secs > 3 ? "§a" : secs > 1.5 ? "§e" : "§c";
                IslandRender.label(context, t.zombie().getX(), t.box().maxY + 0.4, t.zombie().getZ(),
                        col + String.format(Locale.US, "%.1fs", secs) + " §7" + t.points(), 0xFFFFFF);
            }
        }
        if (cfg.isBest() && b != null) {
            float[] c = WorldRenderUtils.argbToFloats(cfg.getBestColor());
            WorldRenderUtils.renderFilledBox(context, b.box().inflate(0.08), c[0], c[1], c[2], 0.3f);
        }
    }

    private static List<String> buildHud() {
        List<String> out = new ArrayList<>();
        out.add("§c§lZombie Shootout");
        Target b = best;
        if (b != null) {
            out.add("§7Shoot: §f" + b.label() + " §7(" + b.points() + ", " + String.format(Locale.US, "%.1fs", b.msLeft()
                    / 1000.0) + ")");
        }
        for (Target t : targets) {
            if (t.lamp() != null) {
                out.add("§cLamp lit: §f" + String.format(Locale.US, "%.1fs", t.msLeft() / 1000.0) + " §7left");
            }
        }
        int zombies = 0;
        for (Target t : targets) {
            if (t.zombie() != null) {
                zombies++;
            }
        }
        out.add("§7Zombies up: §f" + zombies);
        return out;
    }

    static List<String> lines() {
        ZombieShootoutConfig cfg = ZombieShootoutConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHud() || !active()) {
            return List.of();
        }
        return hud;
    }

    // ---- harness -------------------------------------------------------------------------------------------------

    /** Harness: how many targets are seen now (zombies and lit lamps). */
    public static int targetCount() {
        return targets.size();
    }

    /** Harness: the best target's label ("Diamond", "Lamp"), or null. */
    public static String bestLabel() {
        Target b = best;
        return b == null ? null : b.label();
    }

    /** Harness: lamps watched. */
    public static int lampCount() {
        return LAMPS.size();
    }

    /** Harness: the HUD's lines now. */
    public static List<String> hudLines() {
        return lines();
    }

    public static final class Hud extends IslandHud {

        private static final List<String> SAMPLE = List.of("§c§lZombie Shootout", "§7Shoot: §fDiamond §7(120, 2.4s)",
                "§cLamp lit: §f4.1s §7left", "§7Zombies up: §f5");

        @Override
        public String id() {
            return "carnival_zombie_shootout";
        }

        @Override
        public String displayName() {
            return "Zombie Shootout Helper";
        }

        @Override
        public int defaultY() {
            return 300;
        }

        @Override
        public boolean isEnabledInSettings() {
            ZombieShootoutConfig cfg = ZombieShootoutConfig.getInstance();
            return cfg.isEnabled() && cfg.isHud();
        }

        @Override
        protected List<String> lines() {
            return ZombieShootout.lines();
        }

        @Override
        protected List<String> sample() {
            return SAMPLE;
        }
    }
}
