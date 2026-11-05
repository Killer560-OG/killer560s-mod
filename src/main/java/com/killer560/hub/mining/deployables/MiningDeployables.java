package com.killer560.hub.mining.deployables;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.RiftFishingHud;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deployables (Mining; legit, both builds; killer560, 2026-11-04: "look up the lanters and things for mining as well and add
 * a circle area of affect option to show a circle and if you are in it then you get its affect. also add a hud timer").
 *
 * <p>A placed lantern (Dwarven, Mithril, Titanium, Glacite, Will-o'-wisp: 30 blocks, 5 minutes, mining speed and fortune)
 * shows Hypixel's own label armor stand named {@code "<Name> <seconds>s"} (the shape SkyHanni's Deployable patterns read,
 * e.g. "Umberella 298s"; facts only, no code taken). That one stand is the whole signature: its position is the center
 * every range check measures from, and its counting name is the timer. Power orbs, the Umberella and Black Holes carry the
 * same label, so "Other Deployables" adds them for free; flares carry no label (only a skull), and are not covered.
 *
 * <p>Each deployable gets a ring of its radius on the ground under it or a see-through sphere of it (the Door Keys range
 * styles, killer560 2026-11-04: "how you do circles for the wither key. i want those two options"), in the In Radius
 * color while you stand inside it, and a movable HUD timer (Always, or Only In Radius). A Glacite Lantern or Will-o'-wisp
 * buffs the whole Glacite Mineshaft, so there you count as inside it anywhere.
 *
 * <p>Cost: armor stands are read every 10 ticks and only while the switch is on and (lanterns) on a mining island or
 * (Other Deployables) anywhere on Skyblock; the inside test runs per tick over at most {@link #MAX} entries; ring points and
 * colors are rebuilt only when they change, so a frame allocates nothing.
 */
public final class MiningDeployables {

    /** Every deployable with a timer label. Radius in blocks (Hypixel SkyBlock wiki, Deployables). */
    public enum Kind {
        DWARVEN_LANTERN("Dwarven Lantern", "§f", 30, true, false),
        MITHRIL_LANTERN("Mithril Lantern", "§a", 30, true, false),
        TITANIUM_LANTERN("Titanium Lantern", "§9", 30, true, false),
        GLACITE_LANTERN("Glacite Lantern", "§5", 30, true, true),
        WILL_O_WISP("Will-o'-wisp", "§6", 30, true, true),
        RADIANT("Radiant", "§a", 18, false, false),
        MANA_FLUX("Mana Flux", "§9", 18, false, false),
        OVERFLUX("Overflux", "§5", 18, false, false),
        PLASMAFLUX("Plasmaflux", "§d", 20, false, false),
        UMBERELLA("Umberella", "§9", 30, false, false),
        BLACK_HOLE("Black Hole", "§5", 20, false, false);

        public final String label;
        final String color;
        public final int radius;
        public final boolean lantern;
        /** Buffs the whole Glacite Mineshaft (SkyHanni's fullShaft; the Mineshaft is wider than entity render range). */
        final boolean wholeMineshaft;

        Kind(String label, String color, int radius, boolean lantern, boolean wholeMineshaft) {
            this.label = label;
            this.color = color;
            this.radius = radius;
            this.lantern = lantern;
            this.wholeMineshaft = wholeMineshaft;
        }
    }

    public static final int STYLE_CIRCLE = 0;
    public static final int STYLE_SPHERE = 1;
    public static final String[] STYLE_NAMES = {"Circle", "Sphere"};
    public static final String[] TIMER_NAMES = {"Always", "Only In Radius"};
    public static final int DEFAULT_COLOR = 0x80FFAA00;
    public static final int DEFAULT_INSIDE_COLOR = 0x8055FF55;

    public static final IslandConfig CFG = IslandConfig.of("mining", "deployables")
            .flag("circle", true)
            .num("style", STYLE_CIRCLE, 0, STYLE_NAMES.length - 1)
            .num("thickness", 4, 1, 15)
            .num("lift", 1, -20, 40)
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .num("insideColor", DEFAULT_INSIDE_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .flag("timer", true)
            .num("timerShows", 0, 0, TIMER_NAMES.length - 1)
            .flag("others", false);

    static final int MAX = 16;
    private static final int RING_SEGMENTS = 64;
    private static final int SCAN_TICKS = 10;
    private static final Map<String, Kind> BY_LABEL = new HashMap<>();
    private static final Pattern LABEL;

    static {
        StringBuilder names = new StringBuilder();
        for (Kind k : Kind.values()) {
            BY_LABEL.put(k.label, k);
            names.append(names.isEmpty() ? "" : "|").append(Pattern.quote(k.label));
        }
        LABEL = Pattern.compile("^(" + names + ") (\\d{1,4})s$");
    }

    /** One deployable on screen. Fields are written on the client thread only. */
    static final class Tracked {
        final Entity stand;
        final Kind kind;
        double x, y, z, groundY = Double.NaN;
        int lastSecs = -1;
        long expiresAt;
        boolean inside;
        boolean seen;
        Vec3[] ring;
        double ringY = Double.NaN;

        Tracked(Entity stand, Kind kind) {
            this.stand = stand;
            this.kind = kind;
        }
    }

    private static final List<Tracked> tracked = new ArrayList<>();
    private static volatile List<String> hudLines = List.of();
    private static ClientLevel lastLevel;
    private static int tick;
    private static int colorFor = 0, insideColorFor = 0;
    private static float[] rgba = WorldRenderUtils.argbToFloats(DEFAULT_COLOR);
    private static float[] insideRgba = WorldRenderUtils.argbToFloats(DEFAULT_INSIDE_COLOR);
    private static volatile String lastStyle = "";
    private static volatile long framesDrawn;

    private MiningDeployables() {
    }

    public static void register() {
        int bit = FeatureBits.register("Deployables", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MiningDeployables.tick", bit, MiningDeployables::onTick));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(bit, MiningDeployables::onWorldRender));
        HudElementRegistry.register(new RiftFishingHud("mining_deployables", "Deployable Timers", 10, 240,
                () -> CFG.isEnabledRaw() && CFG.on("timer"), () -> hudLines));
    }

    private static void onTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            tracked.clear();
        }
        boolean others = CFG.on("others");
        boolean mining = MiningState.onMiningIsland();
        if (!CFG.isEnabled() || client.player == null || client.level == null || (!mining && !others)) {
            if (!tracked.isEmpty() || !hudLines.isEmpty()) {
                tracked.clear();
                hudLines = List.of();
            }
            return;
        }
        boolean scan = tick++ % SCAN_TICKS == 0;
        if (scan) {
            scan(client, mining, others);
        }
        boolean flipped = false;
        boolean shaft = MiningState.mineshaft();
        double px = client.player.getX(), py = client.player.getY(), pz = client.player.getZ();
        for (Tracked t : tracked) {
            t.x = t.stand.getX();
            t.y = t.stand.getY();
            t.z = t.stand.getZ();
            double dx = px - t.x, dy = py - t.y, dz = pz - t.z;
            boolean in = (shaft && t.kind.wholeMineshaft) || dx * dx + dy * dy + dz * dz < (double) t.kind.radius * t.kind.radius;
            flipped |= in != t.inside;
            t.inside = in;
        }
        if (scan || flipped || tick % 5 == 0) {
            hudLines = buildLines();
        }
    }

    /** Reads the label stands: new ones join, gone or expired ones leave, a changed second resets the timer. */
    private static void scan(Minecraft client, boolean mining, boolean others) {
        for (Tracked t : tracked) {
            t.seen = false;
        }
        long now = System.currentTimeMillis();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand) || !e.hasCustomName() || e.isRemoved()) {
                continue;
            }
            Matcher m = LABEL.matcher(ChatObserver.stripCodes(e.getCustomName().getString()));
            if (!m.matches()) {
                continue;
            }
            Kind kind = BY_LABEL.get(m.group(1));
            if (kind == null || (kind.lantern ? !mining : !others)) {
                continue;
            }
            Tracked t = find(e);
            if (t == null) {
                if (tracked.size() >= MAX) {
                    continue;
                }
                t = new Tracked(e, kind);
                tracked.add(t);
            }
            t.seen = true;
            int secs = Integer.parseInt(m.group(2));
            if (secs != t.lastSecs) {
                t.lastSecs = secs;
                t.expiresAt = now + secs * 1000L;
            }
        }
        tracked.removeIf(t -> !t.seen || t.stand.isRemoved() || t.expiresAt <= now);
    }

    private static Tracked find(Entity e) {
        for (Tracked t : tracked) {
            if (t.stand == e) {
                return t;
            }
        }
        return null;
    }

    private static List<String> buildLines() {
        if (!CFG.on("timer") || tracked.isEmpty()) {
            return List.of();
        }
        boolean onlyInside = CFG.num("timerShows") == 1;
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>(tracked.size());
        for (Tracked t : tracked) {
            if (onlyInside && !t.inside) {
                continue;
            }
            long left = Math.max(0, t.expiresAt - now) / 1000;
            out.add(String.format(Locale.ROOT, "%s%s §e%d:%02d%s", t.kind.color, t.kind.label, left / 60, left % 60,
                    t.inside ? " §a(in range)" : ""));
        }
        return out;
    }

    /** The ground under the stand (the first non-air block within 8 below), found once per stand position. */
    private static double groundY(ClientLevel level, Tracked t) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(Math.floor(t.x), Math.floor(t.y), Math.floor(t.z));
        for (int i = 0; i < 8; i++) {
            p.move(0, -1, 0);
            if (!level.getBlockState(p).isAir()) {
                return p.getY() + 1.0;
            }
        }
        return t.y;
    }

    private static void onWorldRender(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (tracked.isEmpty() || !CFG.on("circle") || client.level == null || !CFG.isEnabled()) {
            return;
        }
        int c = CFG.num("color"), ic = CFG.num("insideColor");
        if (c != colorFor || ic != insideColorFor) {
            colorFor = c;
            insideColorFor = ic;
            rgba = WorldRenderUtils.argbToFloats(c);
            insideRgba = WorldRenderUtils.argbToFloats(ic);
        }
        boolean sphere = CFG.num("style") == STYLE_SPHERE;
        float width = CFG.num("thickness");
        double lift = CFG.num("lift") / 10.0;
        for (Tracked t : tracked) {
            float[] col = t.inside ? insideRgba : rgba;
            if (sphere) {
                WorldRenderUtils.renderFilledSphere(context, t.stand.position(), t.kind.radius, 12, 24, col[0], col[1], col[2], col[3]);
                continue;
            }
            if (t.ring == null || t.ring[0].x != t.x + t.kind.radius || t.ring[0].z != t.z || Double.isNaN(t.groundY)) {
                t.groundY = groundY(client.level, t);
                t.ring = null;
            }
            double y = t.groundY + lift;
            if (t.ring == null || y != t.ringY) {
                t.ring = WorldRenderUtils.ringPoints(t.x, y, t.z, t.kind.radius, RING_SEGMENTS);
                t.ringY = y;
            }
            WorldRenderUtils.renderLineStrip(context, t.ring, col[0], col[1], col[2], col[3], width);
        }
        lastStyle = sphere ? "sphere" : "circle";
        framesDrawn++;
    }

    // ---- testkit entry points --------------------------------------------------------------------------------------

    /** Each tracked deployable as "label|in|out seconds-left", in the order seen. */
    public static List<String> tracked() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (Tracked t : List.copyOf(tracked)) {
            out.add(t.kind.label + "|" + (t.inside ? "in" : "out") + "|" + Math.max(0, t.expiresAt - now) / 1000);
        }
        return out;
    }

    /** The HUD's lines right now (empty while it has nothing to show). */
    public static List<String> hudLines() {
        return hudLines;
    }

    /** Frames that drew at least one circle or sphere. */
    public static long framesDrawn() {
        return framesDrawn;
    }

    /** "circle" or "sphere" for the last frame drawn, "" before any. */
    public static String lastDrawnStyle() {
        return lastStyle;
    }

    /** The y the circle of the deployable labeled {@code label} is drawn at, or NaN. */
    public static double ringY(String label) {
        for (Tracked t : List.copyOf(tracked)) {
            if (t.kind.label.equals(label)) {
                return t.ringY;
            }
        }
        return Double.NaN;
    }
}
