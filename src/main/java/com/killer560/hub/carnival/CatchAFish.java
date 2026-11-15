package com.killer560.hub.carnival;

import com.killer560.hub.armourdye.ArmourDye;
import com.killer560.hub.islands.IslandHud;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Catch a Fish Helper (Skyblocker's Catch a Fish helper, which makes the yellow fish glow): while you play Catch a Fish,
 * outlines every fish in the pond - the 200-point Yellow Fish in their own color, the rest in another (Outlines, or
 * Yellow Only) - boxes the fish to go for (the nearest Yellow Fish, else the nearest fish) with a line to it, and a HUD
 * with your catch streak and score from the sidebar, the yellow fish up, whether your hook has a fish on it (hold Use to
 * reel it in) and how long the streak has left without a hook (3 seconds, the wiki's rule). Display only: it never casts
 * or reels (Auto Catch a Fish, cheat build, does, and shares this class's fish pick).
 *
 * <p>A fish is an armor stand wearing a player head within 30 blocks; a Yellow Fish is the head with Skyblocker's
 * texture (unverified in game). Red and Blue fish have no known texture, so they share the second color.
 */
public final class CatchAFish {

    /** The skin hash in Skyblocker's CARNIVAL_YELLOW_FISH texture. */
    static final String YELLOW_HASH = "360f4f99c78adddeab276ebdf6ab9a0fcffabd49f0b543e1981ac7bd3d55b18a";
    private static final double RANGE = 30;
    /** The wiki: the catch streak resets after 3 seconds without hooking a fish. */
    static final long STREAK_MS = 3000;

    /** A fish in the pond. */
    public record Fish(ArmorStand stand, boolean yellow) {
        public AABB box() {
            Vec3 p = stand.position();
            double top = stand.getBoundingBox().maxY;
            return new AABB(p.x - 0.35, top - 0.6, p.z - 0.35, p.x + 0.35, top + 0.1, p.z + 0.35);
        }
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-carnival");
    private static List<Fish> fish = List.of();
    private static Fish best;
    private static long lastHookedMs;
    private static boolean hooked;
    private static Bar hookedBar;
    private static List<String> hud = List.of();
    /** Stand id -> wears the Yellow Fish head (a head never changes, so it is decoded once). */
    private static final java.util.Map<Integer, Boolean> YELLOW_BY_ID = new java.util.HashMap<>();

    /** Fish stands that were hooked or flung out of the pond: never a target again (ids, kept only while the entity lives). */
    private static final java.util.Set<Integer> FLUNG = new java.util.HashSet<>();
    private static final java.util.Map<Integer, Vec3> LAST_POS = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Double> POND_Y = new java.util.HashMap<>();
    /** A caught fish is thrown: it moves this far in one tick, or climbs this far above where it swam. */
    static final double FLING_JUMP = 1.2;
    static final double FLING_RISE = 1.5;

    private CatchAFish() {
    }

    private static final List<java.util.function.BooleanSupplier> WANTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static void addWant(java.util.function.BooleanSupplier w) {
        WANTS.add(w);
        FeatureBits.changed();
    }

    static boolean wanted() {
        if (CatchAFishConfig.getInstance().isEnabledRaw()) {
            return true;
        }
        for (java.util.function.BooleanSupplier w : WANTS) {
            if (w.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    public static void register() {
        CarnivalState.register();
        CarnivalState.onGameEnd(CatchAFish::logFlags);
        CarnivalState.onGameStart(g -> forgetFlung());
        CarnivalState.addDemand(CatchAFish::wanted);
        int bit = FeatureBits.register("Catch a Fish Helper", CatchAFish::wanted);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CatchAFish.tick", bit, CatchAFish::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(bit, CatchAFish::render));
    }

    static boolean active() {
        return CarnivalState.playing(CarnivalState.Game.CATCH_A_FISH) && CarnivalState.inCarnival();
    }

    /** Whether a head's texture is the Yellow Fish's. */
    static boolean isYellow(ItemStack head) {
        String tex = ArmourDye.headTextureOf(head);
        if (tex == null) {
            return false;
        }
        try {
            String json = new String(Base64.getDecoder().decode(tex.trim()), StandardCharsets.UTF_8);
            return json.contains(YELLOW_HASH);
        } catch (IllegalArgumentException e) {
            return tex.contains(YELLOW_HASH);
        }
    }

    private static void tick(Minecraft mc) {
        if (!active() || mc.level == null || mc.player == null) {
            fish = List.of();
            best = null;
            hud = List.of();
            return;
        }
        List<Fish> list = new ArrayList<>();
        FishingHook myHook = mc.player.fishing;
        Entity hookedFish = myHook == null ? null : myHook.getHookedIn();
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        List<ArmorStand> tags = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || stand.distanceToSqr(mc.player) > RANGE * RANGE) {
                continue;
            }
            alive.add(stand.getId());
            Lead.sample(stand.getId(), stand.position());
            if (stand.hasCustomName() && stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                tags.add(stand);
            }
            if (isFlung(stand.getId(), stand.position(), hookedFish != null && hookedFish.getId() == stand.getId())) {
                continue;
            }
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head == null || head.isEmpty()
                    || !BuiltInRegistries.ITEM.getKey(head.getItem()).getPath().equals("player_head")) {
                continue;
            }
            if (YELLOW_BY_ID.size() > 512) {
                YELLOW_BY_ID.clear();
            }
            list.add(new Fish(stand, YELLOW_BY_ID.computeIfAbsent(stand.getId(), k -> isYellow(head))));
        }
        FLUNG.retainAll(alive);
        LAST_POS.keySet().retainAll(alive);
        POND_Y.keySet().retainAll(alive);
        BACK.keySet().retainAll(alive);
        Lead.retain(alive);
        fish = list;
        readBars(list, tags);
        FishingHook hook = mc.player.fishing;
        hooked = hook != null && hook.getHookedIn() != null;
        hookedBar = hooked ? BARS.get(hook.getHookedIn().getId()) : null;
        if (hooked) {
            lastHookedMs = System.currentTimeMillis();
        }
        best = pickBest(list, mc.player.getEyePosition());
        hud = buildHud();
    }

    /** Matches each fish to the name tag standing over it and parses it as a bar; the first tag seen a game is logged raw. */
    private static void readBars(List<Fish> list, List<ArmorStand> tags) {
        BARS.clear();
        for (Fish f : list) {
            Vec3 p = f.stand().position();
            for (ArmorStand t : tags) {
                double dy = t.getY() - p.y;
                double dx = t.getX() - p.x;
                double dz = t.getZ() - p.z;
                if (dy > -0.5 && dy < 3 && dx * dx + dz * dz < 2.25) {
                    String text = t.getCustomName().getString();
                    if (!barLogged) {
                        barLogged = true;
                        LOGGER.info("[CatchAFish] name tag over a fish: '{}'", text);
                    }
                    Bar b = parseBar(text);
                    if (b != null) {
                        BARS.put(f.stand().getId(), b);
                        break;
                    }
                }
            }
        }
    }

    /** Whether the stand {@code id} at {@code pos} is a fish that was hooked or thrown out of the water: hooked now, moved
     *  more than {@link #FLING_JUMP} in a tick, or risen more than {@link #FLING_RISE} above its first height. Once true it
     *  stays true for that stand. */
    static boolean isFlung(int id, Vec3 pos, boolean hookedNow) {
        Vec3 prev = LAST_POS.put(id, pos);
        double y0 = POND_Y.computeIfAbsent(id, k -> pos.y);
        boolean jump = prev != null && prev.distanceTo(pos) > FLING_JUMP;
        boolean rise = pos.y - y0 > FLING_RISE;
        if (hookedNow || jump || rise) {
            if (FLUNG.add(id)) {
                flagged[hookedNow ? 0 : jump ? 1 : 2]++;
            }
            BACK.remove(id);
        } else if (FLUNG.contains(id)) {
            // Not permanent: a stand seen back at the height it swam at, standing still, for 10 ticks swims again
            // (a thrown fish respawns at the pond; one flagged by a one-tick jump stays a target otherwise lost for the game).
            boolean still = prev != null && prev.distanceTo(pos) < 0.2;
            int n = still && Math.abs(pos.y - y0) < 0.5 ? BACK.merge(id, 1, Integer::sum) : 0;
            if (n >= UNFLING_TICKS) {
                FLUNG.remove(id);
                BACK.remove(id);
                flagged[3]++;
            } else if (n == 0) {
                BACK.remove(id);
            }
        }
        return FLUNG.contains(id);
    }

    private static final java.util.Map<Integer, Integer> BACK = new java.util.HashMap<>();
    static final int UNFLING_TICKS = 10;
    /** This game's flags by reason: hooked, jumped, rose, and cleared again. */
    private static final int[] flagged = new int[4];

    /** Harness: clears the flung memory. */
    public static void forgetFlung() {
        FLUNG.clear();
        LAST_POS.clear();
        POND_Y.clear();
        BACK.clear();
        BARS.clear();
        java.util.Arrays.fill(flagged, 0);
        barLogged = false;
        Lead.clear();
    }

    /** Harness: a name tag read as a bar: {fraction or -1, seconds left or -1}, null when it is not a bar. */
    public static double[] barForTest(String tag) {
        Bar b = parseBar(tag);
        return b == null ? null : new double[]{b.fraction(), b.secondsLeft()};
    }

    /** Harness: {@link #isFlung} for a stand at a position. */
    public static boolean flungForTest(int id, double x, double y, double z, boolean hooked) {
        return isFlung(id, new Vec3(x, y, z), hooked);
    }

    /** Harness: flags this game, as hooked/jumped/rose/cleared. */
    public static int[] flaggedForTest() {
        return flagged.clone();
    }

    /** One INFO line a game on why stands were flagged (the owner's log shows whether the rule is too strict). */
    private static void logFlags(CarnivalState.Game g, int score, long ms) {
        if (g == CarnivalState.Game.CATCH_A_FISH) {
            LOGGER.info("[CatchAFish] stands flagged this game: hooked {}, jumped {}, rose {}; cleared again {}", flagged[0],
                    flagged[1], flagged[2], flagged[3]);
        }
    }

    // ---- fish bars and the will-it-catch check -------------------------------------------------------------------

    /** What a fish's name tag says: progress as 0..1 and the seconds left, -1 where the tag does not say. */
    record Bar(double fraction, double secondsLeft) {
    }

    private static final java.util.Map<Integer, Bar> BARS = new java.util.HashMap<>();
    private static boolean barLogged;
    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("(\\d{1,3})\\s?%");
    private static final java.util.regex.Pattern SECONDS = java.util.regex.Pattern.compile("(\\d{1,2}(?:\\.\\d)?)\\s?s\\b");
    private static final String ON = "█▮■▰|#=", OFF = "░▯□▱-.";

    /** Reads a name tag as a progress bar: a percent, a seconds figure, or filled over empty bar glyphs; null if none. */
    static Bar parseBar(String tag) {
        if (tag == null || tag.length() > 80) {
            return null;
        }
        double frac = -1;
        double secs = -1;
        java.util.regex.Matcher m = PERCENT.matcher(tag);
        if (m.find()) {
            frac = Math.min(100, Integer.parseInt(m.group(1))) / 100.0;
        }
        m = SECONDS.matcher(tag);
        if (m.find()) {
            secs = Double.parseDouble(m.group(1));
        }
        if (frac < 0) {
            int on = 0;
            int off = 0;
            for (char c : tag.toCharArray()) {
                on += ON.indexOf(c) >= 0 ? 1 : 0;
                off += OFF.indexOf(c) >= 0 ? 1 : 0;
            }
            if (on + off >= 4) {
                frac = on / (double) (on + off);
            }
        }
        return frac < 0 && secs < 0 ? null : new Bar(frac, secs);
    }

    /** The bar over a fish (a named stand within 1.5 blocks sideways and up to 3 above it), or null. */
    static Bar barOf(int fishId) {
        return BARS.get(fishId);
    }

    /** Whether the bobber will get a fish: one is on it, or a fish's path (its smoothed speed) comes within a block of it in
     *  the next 3 seconds. */
    static boolean willCatch(FishingHook hook, List<Fish> fishes) {
        if (hook.getHookedIn() != null) {
            return true;
        }
        Vec3 h = hook.position();
        for (Fish f : fishes) {
            Vec3 p = f.stand().position();
            Vec3 v = Lead.velocity(f.stand().getId());
            for (int t = 0; t <= 60; t += 3) {
                double dx = p.x + v.x * t - h.x;
                double dz = p.z + v.z * t - h.z;
                if (dx * dx + dz * dz < 1.0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether nothing solid stands between the eye and the fish's center. */
    static boolean inSight(net.minecraft.client.player.LocalPlayer player, Fish f) {
        net.minecraft.world.phys.HitResult h = player.level().clip(new net.minecraft.world.level.ClipContext(
                player.getEyePosition(), f.box().getCenter(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return h.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK;
    }

    /** The fish to go for: the nearest Yellow Fish, else the nearest fish. */
    static Fish pickBest(List<Fish> list, Vec3 from) {
        Fish b = null;
        double bd = Double.MAX_VALUE;
        for (Fish f : list) {
            double d = f.stand().position().distanceToSqr(from) - (f.yellow() ? 10_000 : 0);
            if (d < bd) {
                bd = d;
                b = f;
            }
        }
        return b;
    }

    static List<Fish> fish() {
        return fish;
    }

    static Fish best() {
        return best;
    }

    /** Whether your hook has a fish on it now. */
    static boolean hooked() {
        return hooked;
    }

    private static void render(LevelRenderContext context) {
        CatchAFishConfig cfg = CatchAFishConfig.getInstance();
        if (!cfg.isEnabled() || !active()) {
            return;
        }
        if (cfg.isOutlines()) {
            for (Fish f : fish) {
                if (cfg.isYellowOnly() && !f.yellow()) {
                    continue;
                }
                float[] c = WorldRenderUtils.argbToFloats(f.yellow() ? cfg.getYellowColor() : cfg.getOtherColor());
                WorldRenderUtils.renderOutlineBox(context, f.box(), c[0], c[1], c[2], 1f, f.yellow() ? 3f : 1.5f);
            }
        }
        Fish b = best;
        if (b != null && cfg.isBest()) {
            float[] c = WorldRenderUtils.argbToFloats(b.yellow() ? cfg.getYellowColor() : cfg.getOtherColor());
            WorldRenderUtils.renderFilledBox(context, b.box(), c[0], c[1], c[2], 0.35f);
            if (cfg.isLine()) {
                WorldRenderUtils.renderLine(context, WorldRenderUtils.tracerOrigin(context), b.box().getCenter(), c[0],
                        c[1], c[2], 1f, 2f, false);
            }
        }
    }

    private static List<String> buildHud() {
        List<String> out = new ArrayList<>();
        out.add("§3§lCatch a Fish");
        int yellow = 0;
        for (Fish f : fish) {
            if (f.yellow()) {
                yellow++;
            }
        }
        out.add("§7Fish up: §f" + fish.size() + " §7(yellow §e" + yellow + "§7)");
        if (CarnivalState.streak() >= 0) {
            out.add("§7Catch Streak: §f" + CarnivalState.streak());
        }
        if (hooked) {
            Bar b = hookedBar;
            out.add("§a§lHOOKED §7- hold Use to reel" + (b == null ? "" : (b.fraction() >= 0
                    ? " §7(bar §f" + Math.round(b.fraction() * 100) + "%§7)" : "")
                    + (b.secondsLeft() >= 0 ? " §7(§f" + b.secondsLeft() + "s§7 left)" : "")));
        } else if (lastHookedMs > 0) {
            long left = STREAK_MS - (System.currentTimeMillis() - lastHookedMs);
            if (left > 0) {
                out.add("§7Streak safe for §e" + String.format(Locale.US, "%.1fs", left / 1000.0));
            }
        }
        return out;
    }

    static List<String> lines() {
        CatchAFishConfig cfg = CatchAFishConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHud() || !active()) {
            return List.of();
        }
        return hud;
    }

    // ---- harness -------------------------------------------------------------------------------------------------

    /** Harness: fish seen now. */
    public static int fishCount() {
        return fish.size();
    }

    /** Harness: yellow fish seen now. */
    public static int yellowCount() {
        int n = 0;
        for (Fish f : fish) {
            if (f.yellow()) {
                n++;
            }
        }
        return n;
    }

    /** Harness: whether the best fish is a yellow one (null with none). */
    public static Boolean bestYellow() {
        Fish b = best;
        return b == null ? null : b.yellow();
    }

    /** Harness: the HUD's lines now. */
    public static List<String> hudLines() {
        return lines();
    }

    public static final class Hud extends IslandHud {

        private static final List<String> SAMPLE = List.of("§3§lCatch a Fish", "§7Fish up: §f6 §7(yellow §e1§7)",
                "§7Catch Streak: §f4", "§a§lHOOKED §7- hold Use to reel");

        @Override
        public String id() {
            return "carnival_catch_a_fish";
        }

        @Override
        public String displayName() {
            return "Catch a Fish Helper";
        }

        @Override
        public int defaultY() {
            return 270;
        }

        @Override
        public boolean isEnabledInSettings() {
            CatchAFishConfig cfg = CatchAFishConfig.getInstance();
            return cfg.isEnabled() && cfg.isHud();
        }

        @Override
        protected List<String> lines() {
            return CatchAFish.lines();
        }

        @Override
        protected List<String> sample() {
            return SAMPLE;
        }
    }
}
