package com.killer560.hub.chocolatefactory.egg;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.chocolatefactory.ChocolateFactoryConfig;
import com.killer560.hub.chocolatefactory.egg.HoppityCalendar.Meal;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Egg ESP - CHEAT BUILD ONLY (killer560, 2026-10-19: "an egg ESP option for the cheat variant"). Boxes the hidden
 * Hoppity's Hunt Chocolate Eggs through walls, coloured by meal (Breakfast gold, Lunch blue, Dinner green).
 * <p>
 * What counts as an egg is the shape Skyblocker's EggFinder (LGPL-3.0, read for behaviour only, no code taken) keys
 * on: an invisible, unnamed armour stand wearing a player head whose skin is one of the three egg skins. We match the
 * skin by its texture id inside the decoded {@code textures} property rather than the whole base64 value, so a
 * re-signed profile with a new timestamp still matches. The three ids were decoded from Skyblocker's EggType values
 * (2026-10-19); Brunch, Dejeuner and Supper wear the same three heads.
 * <p>
 * Purely visual: nothing is clicked or sent. In a legit build {@link #register} returns at once and javac folds every
 * branch on {@link BuildVariant#CHEAT_FEATURES_ENABLED} away.
 */
public final class EggEsp {

    private static final Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");

    private static final Map<String, Meal> TEXTURE_IDS = Map.of(
            "a49333d85b8a315d0336eb2df37d8a714ca24c51b8c6074f1b5b927deb516c24", Meal.BREAKFAST,
            "7ae6d2d31d8167bcaf95293b68a4acd872d66e751db5a34f2cbc6766a0356d0a", Meal.LUNCH,
            "e5e36165819fd2850f98552edcd763ff986313119283c126ace0c4cc495e76a8", Meal.DINNER);
    private static final int[] MEAL_RGB = {0xFFAA00, 0x5555FF, 0x55FF55};
    private static final int SCAN_EVERY_TICKS = 10;

    /** Decoded-texture verdicts by raw property value; Optional.empty() = not an egg. Bounded: cleared past 512. */
    private static final Map<String, Optional<Meal>> CACHE = new HashMap<>();

    private record Egg(double x, double y, double z, Meal meal) {
    }

    private static volatile List<Egg> eggs = List.of();
    private static int ticks;

    private EggEsp() {
    }

    static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        RenderType unused = ThroughWalls.LINES;
        ClientTickEvents.END_CLIENT_TICK.register(EggEsp::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(EggEsp::onWorldRender);
    }

    /** The ESP is on: cheat build, helper on, Egg ESP on, in Skyblock, during the hunt. */
    public static boolean active() {
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        return BuildVariant.CHEAT_FEATURES_ENABLED && cfg.isEnabled() && cfg.isEggEsp() && SkyblockGate.allows()
                && HoppityCalendar.huntActive(System.currentTimeMillis());
    }

    /** Eggs seen on the last scan - the testkit reads it. */
    public static int eggCount() {
        return eggs.size();
    }

    private static void tick(Minecraft client) {
        try {
            if (!active() || client.level == null || client.player == null) {
                if (!eggs.isEmpty()) {
                    eggs = List.of();
                }
                return;
            }
            if (++ticks % SCAN_EVERY_TICKS != 0) {
                return;
            }
            List<Egg> found = new ArrayList<>();
            for (Entity e : client.level.entitiesForRendering()) {
                if (!(e instanceof ArmorStand stand) || !stand.isInvisible() || stand.hasCustomName()) {
                    continue;
                }
                Meal meal = mealOf(stand.getItemBySlot(EquipmentSlot.HEAD));
                if (meal != null) {
                    found.add(new Egg(stand.getX(), stand.getY(), stand.getZ(), meal));
                }
            }
            eggs = List.copyOf(found);
        } catch (RuntimeException e) {
            LOGGER.error("[EggEsp] scan failed", e);
        }
    }

    /** The meal whose egg skin this head wears, or null. */
    static Meal mealOf(ItemStack head) {
        if (head == null || head.isEmpty()) {
            return null;
        }
        ResolvableProfile profile = head.get(DataComponents.PROFILE);
        if (profile == null || profile.partialProfile() == null) {
            return null;
        }
        Iterator<Property> it = profile.partialProfile().properties().get("textures").iterator();
        if (!it.hasNext()) {
            return null;
        }
        String value = it.next().value();
        if (value == null || value.length() > 4096) {
            return null;
        }
        Optional<Meal> cached = CACHE.get(value);
        if (cached == null) {
            if (CACHE.size() > 512) {
                CACHE.clear();
            }
            cached = Optional.ofNullable(decode(value));
            CACHE.put(value, cached);
        }
        return cached.orElse(null);
    }

    /** The meal named by the texture id inside a base64 {@code textures} value, or null. */
    static Meal decode(String base64) {
        try {
            String s = base64.trim();
            int pad = (4 - s.length() % 4) % 4;
            String json = new String(Base64.getDecoder().decode(s + "=".repeat(pad == 3 ? 0 : pad)),
                    StandardCharsets.UTF_8);
            for (Map.Entry<String, Meal> e : TEXTURE_IDS.entrySet()) {
                if (json.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Not base64 - not an egg.
        }
        return null;
    }

    private static void onWorldRender(LevelRenderContext context) {
        List<Egg> snap = eggs;
        if (snap.isEmpty() || !active()) {
            return;
        }
        boolean hideFound = ChocolateFactoryConfig.getInstance().isEggEspHideFound();
        List<Egg> shown = new ArrayList<>(snap.size());
        for (Egg egg : snap) {
            if (!hideFound || HoppityEggs.status(egg.meal()) != HoppityEggs.Status.FOUND) {
                shown.add(egg);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        // One pass per render type, never interleaved - the same rule StorageSearchEsp and SecretWaypointsRenderer
        // follow ("Not building!" otherwise).
        McRender.inCameraSpace(context, ThroughWalls.FILLED, (pose, buffer) -> {
            for (Egg egg : shown) {
                int rgb = MEAL_RGB[egg.meal().ordinal()];
                filledBox(pose.pose(), buffer, boxFor(egg), r(rgb), g(rgb), b(rgb), 0.3f);
            }
        });
        McRender.inCameraSpace(context, ThroughWalls.LINES, (pose, buffer) -> {
            for (Egg egg : shown) {
                int rgb = MEAL_RGB[egg.meal().ordinal()];
                lineBox(pose, buffer, boxFor(egg), r(rgb), g(rgb), b(rgb), 1f, 2.5f);
            }
        });
    }

    /** The head of a full-size armour stand sits about 1.45-2.0 blocks above its feet. */
    private static AABB boxFor(Egg egg) {
        return new AABB(egg.x() - 0.3, egg.y() + 1.4, egg.z() - 0.3, egg.x() + 0.3, egg.y() + 2.0, egg.z() + 0.3);
    }

    private static float r(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    private static float g(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    private static float b(int rgb) {
        return (rgb & 0xFF) / 255f;
    }

    /** Holder idiom - built once, on {@link #register()} (cheat build only). */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_eggesp_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/eggesp_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_eggesp_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/eggesp_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, float r, float g, float b, float a,
                                float width) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        float[] c = {x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
        for (int i = 0; i < EDGES.length; i += 2) {
            int i0 = EDGES[i] * 3;
            int i1 = EDGES[i + 1] * 3;
            float dx = c[i1] - c[i0], dy = c[i1 + 1] - c[i0 + 1], dz = c[i1 + 2] - c[i0 + 2];
            buffer.addVertex(pose, c[i0], c[i0 + 1], c[i0 + 2]).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
            buffer.addVertex(pose, c[i1], c[i1 + 1], c[i1 + 2]).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
        }
    }

    private static void filledBox(Matrix4f m, VertexConsumer buf, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        float[][] quads = {
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
                {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1},
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
                {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}
        };
        for (float[] q : quads) {
            for (int i = 0; i < 12; i += 3) {
                buf.addVertex(m, q[i], q[i + 1], q[i + 2]).setColor(r, g, b, a);
            }
        }
    }
}
