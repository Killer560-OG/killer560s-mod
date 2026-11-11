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

    private static List<Fish> fish = List.of();
    private static Fish best;
    private static long lastHookedMs;
    private static boolean hooked;
    private static List<String> hud = List.of();
    /** Stand id -> wears the Yellow Fish head (a head never changes, so it is decoded once). */
    private static final java.util.Map<Integer, Boolean> YELLOW_BY_ID = new java.util.HashMap<>();

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
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || stand.distanceToSqr(mc.player) > RANGE * RANGE) {
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
        fish = list;
        FishingHook hook = mc.player.fishing;
        hooked = hook != null && hook.getHookedIn() != null;
        if (hooked) {
            lastHookedMs = System.currentTimeMillis();
        }
        best = pickBest(list, mc.player.getEyePosition());
        hud = buildHud();
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
            out.add("§a§lHOOKED §7- hold Use to reel");
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
