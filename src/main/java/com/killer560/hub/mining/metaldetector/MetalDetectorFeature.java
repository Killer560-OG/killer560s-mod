package com.killer560.hub.mining.metaldetector;

import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.regex.Matcher;

/**
 * Metal Detector (legit, both builds): finds the Mines of Divan treasure from the Metal Detector's action-bar readout
 * and draws a waypoint, with a line to it, once one block is left. Nothing else: no movement, no clicks, no packets
 * (killer560, 2026-09-30: "a legit metal detector setting that just does the calculations and shows waypoint with a
 * line to it"; docs/MINING-PLAN.md section 3).
 *
 * <p>How it reads the game, all from Skyblocker's {@code MetalDetector} (a local reference copy): the readout
 * {@code §3§lTREASURE: §b37.4m} on the action bar; the mines' center from any "Keeper of ..." armor stand within 500
 * blocks; a reading is used only once the same number arrives twice from the same spot (the bar lags behind movement);
 * "You found" in chat means the chest was taken and the next one is a new search. {@link MetalDetectorSolver} does the
 * elimination over the 42 known chest spots.
 */
public final class MetalDetectorFeature {

    private static final MetalDetectorSolver SOLVER = new MetalDetectorSolver();
    private static double prevDistance = -1;
    private static Vec3 prevPos = null;
    private static int lastCount = -1;
    private static int tick = 0;
    private static boolean registered = false;

    private MetalDetectorFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        WorldRenderUtils.initThroughWalls();
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) {
                try {
                    onActionBar(ChatObserver.raw(message));
                } catch (RuntimeException ignored) {
                    // A parse bug must never eat the action bar or reach the packet path.
                }
            }
            return message;
        });
        ChatObserver.subscribe(MetalDetectorFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MetalDetectorFeature", MetalDetectorFeature::onTick));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(MetalDetectorFeature::resetAll));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Metal Detector render", () -> MetalDetectorConfig.getInstance().isEnabled()),
                MetalDetectorFeature::onWorldRender));
    }

    private static boolean active() {
        return MetalDetectorConfig.getInstance().isEnabled() && MiningIsland.crystalHollows()
                && MiningIsland.inArea("Mines of Divan");
    }

    private static void resetAll() {
        SOLVER.setCentre(null);
        prevDistance = -1;
        prevPos = null;
        lastCount = -1;
    }

    private static void onTick(Minecraft client) {
        if (!active() || client.player == null || client.level == null || SOLVER.hasCentre() || tick++ % 20 != 0) {
            return;
        }
        AABB box = client.player.getBoundingBox().inflate(500.0);
        for (ArmorStand stand : client.level.getEntitiesOfClass(ArmorStand.class, box, ArmorStand::hasCustomName)) {
            String name = ChatObserver.stripCodes(stand.getName().getString());
            Matcher m = MetalDetectorData.KEEPER.matcher(name == null ? "" : name.trim());
            if (!m.matches()) {
                continue;
            }
            Vec3i centre = MetalDetectorSolver.centreFromKeeper(m.group(1), stand.blockPosition());
            if (centre != null) {
                SOLVER.setCentre(centre);
                say(Component.literal("Found the center of the Mines of Divan."));
                return;
            }
        }
    }

    static void onActionBar(String raw) {
        Minecraft client = Minecraft.getInstance();
        if (raw == null || client.player == null || !active()) {
            return;
        }
        Matcher m = MetalDetectorData.TREASURE.matcher(raw);
        if (!m.find()) {
            return;
        }
        double distance = Double.parseDouble(m.group(2));
        Vec3 pos = client.player.position();
        boolean settled = distance == prevDistance && pos.equals(prevPos);
        prevDistance = distance;
        prevPos = pos;
        if (!settled || !SOLVER.hasCentre()) {
            return;
        }
        int count = SOLVER.addReading(distance, pos);
        if (count != lastCount && count >= 0) {
            lastCount = count;
            say(count == 1 ? Component.literal("Treasure found - follow the line.")
                    : Component.literal(count + " places the treasure can be. Move and stand still again."));
        }
    }

    private static void onChat(Component message) {
        if (!active()) {
            return;
        }
        String s = ChatObserver.strip(message);
        // A player's chat line starts with their rank or name, never with "You found".
        if (s != null && s.startsWith("You found")) {
            SOLVER.reset();
            lastCount = -1;
        }
    }

    private static void say(Component text) {
        if (MetalDetectorConfig.getInstance().isChatUpdates()) {
            ModChat.send("Metal Detector", ModChat.text(text.getString()));
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!active()) {
            return;
        }
        MetalDetectorConfig cfg = MetalDetectorConfig.getInstance();
        Vec3i solved = SOLVER.solved();
        List<Vec3i> possible = SOLVER.possible();
        if (solved != null) {
            float[] c = WorldRenderUtils.argbToFloats(cfg.getColor());
            AABB box = new AABB(solved.getX(), solved.getY(), solved.getZ(),
                    solved.getX() + 1, solved.getY() + 1, solved.getZ() + 1);
            WorldRenderUtils.renderFilledBoxes(context, new AABB[]{box}, new float[]{c[0], c[1], c[2], 0.4f}, 1, 1f, true);
            WorldRenderUtils.renderOutlineBoxes(context, new AABB[]{box}, new float[]{c[0], c[1], c[2], 1f}, 1, 2f, true);
            if (cfg.isShowLine()) {
                WorldRenderUtils.renderLine(context, WorldRenderUtils.tracerOrigin(context), box.getCenter(),
                        c[0], c[1], c[2], 1f, 2f, true);
            }
        } else if (cfg.isShowCandidates() && !possible.isEmpty()) {
            float[] c = WorldRenderUtils.argbToFloats(cfg.getCandidateColor());
            int n = possible.size();
            AABB[] boxes = new AABB[n];
            float[] rgba = new float[n * 4];
            for (int i = 0; i < n; i++) {
                Vec3i p = possible.get(i);
                boxes[i] = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
                rgba[i * 4] = c[0];
                rgba[i * 4 + 1] = c[1];
                rgba[i * 4 + 2] = c[2];
                rgba[i * 4 + 3] = 1f;
            }
            WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 2f, true);
        }
    }

    /** For the tab: what the search knows right now. */
    public static String status() {
        if (!SOLVER.hasCentre()) {
            return "Looking for a Keeper to find the center";
        }
        Vec3i s = SOLVER.solved();
        if (s != null) {
            return "Treasure at " + s.getX() + ", " + s.getY() + ", " + s.getZ();
        }
        int n = SOLVER.possible().size();
        return n == 0 ? "Center found; stand still with the detector out" : n + " possible spots";
    }
}
