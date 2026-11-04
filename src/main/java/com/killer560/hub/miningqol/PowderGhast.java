package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powder Ghast (killer560, 2026-11-03: "also add powder ghast alerts tracers and esp and such"), a Dwarven Mines event.
 *
 * <p>Facts from hypixelskyblock.minecraft.wiki (Powder Ghast): it spawns in the Dwarven Mines (Divan's Gateway, Rampart's
 * Quarry, Cliffside Veins or Forge Basin) with the lines "The sound of pickaxes clashing against the rock has attracted the
 * attention of the POWDER GHAST!" and "Find the Powder Ghast near the &lt;place&gt;!"; it despawns if not killed within a
 * minute, and every hit resets that minute. The wiki lists no kill or despawn line, so the end is seen on the entity (it
 * dies) or on the timer.
 *
 * <p>Alert: a title (and the place as subtitle) and a sound on the spawn line; an optional party callout (OFF by default).
 * Both lines are matched whole, with {@code matches()}, so "[VIP] Bob: The sound of ..." never fires it.
 *
 * <p>The ghast is found by its name tag ("Powder Ghast" as whole words, on the mob or on a stand above it), or, while the
 * event is running (spawn line seen, minute not up), as the nearest ghast in the Dwarven Mines. Real players are never it.
 * Box: legit draws it only while in plain sight (a block raycast), depth-tested; Through Walls (cheat build only) skips the
 * raycast and the depth test. Tracer: crosshair to the ghast, depth-tested like the Livid Solver's. Draw-only: nothing is
 * clicked; the only packet is the opt-in party line.
 */
public final class PowderGhast {

    public static final String TIMER_ID = "mining_powder_ghast";
    /** The minute is the server's: 1200 server ticks of {@link com.killer560.hub.util.PerTickServerClock}, so a lagging server
     *  keeps the ghast (and this countdown) longer instead of the HUD running out early (2026-11-03). */
    static final long LIFETIME_TICKS = 1200L;

    static final Pattern SPAWN = Pattern.compile(
            "^The sound of pickaxes clashing against the rock has attracted the attention of the POWDER GHAST!$");
    static final Pattern WHERE = Pattern.compile("^Find the Powder Ghast near the ([A-Z][A-Za-z' ]{1,30})!$");
    private static final Pattern NAME = Pattern.compile("(?:^|[\\s\\]])Powder Ghast(?=$|[\\s\\d])");

    /** Wall-clock ms the ghast despawns (spawn line or last hit + a minute); 0 = no event running. */
    private static volatile long deadline = 0L;
    private static volatile String place = null;
    /** The ghast entity being drawn, or -1. */
    private static volatile int targetId = -1;
    private static volatile boolean visible = false;
    private static volatile boolean throughWalls = false;
    private static int lastHurt = 0;
    private static long partyDueAt = 0L;
    private static boolean partySent = false;
    private static Object level = null;

    private static int alerts = 0;
    private static int partyCallouts = 0;
    private static int boxFrames = 0;
    private static int tracerFrames = 0;
    private static int kills = 0;

    private PowderGhast() {
    }

    static void register() {
        com.killer560.hub.util.PerTickServerClock.register();
        ChatObserver.subscribe(PowderGhast::onChat);
    }

    static void onChat(Component message) {
        try {
            MiningQolConfig cfg = MiningQolConfig.getInstance();
            if (!cfg.isPowderGhast() || !MiningState.dwarvenMines()) return;
            String raw = ChatObserver.strip(message);
            if (raw == null || raw.length() > 120) return;
            handle(raw.trim(), com.killer560.hub.util.PerTickServerClock.now());
        } catch (RuntimeException e) {
            com.killer560.hub.util.ModLog.get("killer560smod-miningqol").error("[PowderGhast] chat failed", e);
        }
    }

    private static void handle(String raw, long now) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (SPAWN.matcher(raw).matches()) {
            deadline = now + LIFETIME_TICKS;
            place = null;
            partySent = false;
            partyDueAt = now + 40L; // two seconds of server time
            alerts++;
            if (cfg.isPowderGhastTitle()) {
                Minecraft client = Minecraft.getInstance();
                McCompat.setTimes(client, 5, 50, 10);
                McCompat.setTitle(client, Component.literal("§d§lPOWDER GHAST"));
                McCompat.setSubtitle(client, Component.literal("§7Shoot it with arrows"));
            }
            if (cfg.isPowderGhastSound()) {
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f, 1.0f));
            }
            return;
        }
        Matcher m = WHERE.matcher(raw);
        if (m.matches() && deadline != 0L) {
            place = m.group(1).trim();
            if (cfg.isPowderGhastTitle()) {
                McCompat.setSubtitle(Minecraft.getInstance(), Component.literal("§7near the §e" + place));
            }
            sendParty();
        }
    }

    private static void sendParty() {
        if (partySent || !MiningQolConfig.getInstance().isPowderGhastParty()) return;
        partySent = true;
        partyCallouts++;
        com.killer560.hub.util.OutgoingChat.command(place == null ? "pc Powder Ghast spawned!"
                : "pc Powder Ghast near the " + place + "!");
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isPowderGhast() || client.level == null || client.player == null || !MiningState.dwarvenMines()) {
            clear();
            return;
        }
        if (level != client.level) {
            level = client.level;
            clear();
        }
        long now = com.killer560.hub.util.PerTickServerClock.now();
        if (deadline != 0L && !partySent && partyDueAt != 0L && now >= partyDueAt) {
            sendParty(); // no place line came: the spawn alone
        }
        Entity cur = targetId < 0 ? null : client.level.getEntity(targetId);
        if (cur instanceof LivingEntity l && (l.isDeadOrDying() || !l.isAlive())) {
            kills++;
            deadline = 0L;
            place = null;
            targetId = -1;
            cur = null;
        } else if (cur == null || cur.isRemoved()) {
            targetId = -1;
            cur = find(client);
            if (cur != null) {
                targetId = cur.getId();
                lastHurt = 0;
            }
        }
        if (cur instanceof LivingEntity l) {
            // Every hit resets its minute (wiki): the hurt flash starting is a hit.
            if (l.hurtTime > 0 && lastHurt == 0 && deadline != 0L) {
                deadline = now + LIFETIME_TICKS;
            }
            lastHurt = l.hurtTime;
        }
        if (deadline != 0L && now >= deadline) {
            // The minute is up. A ghast still loaded stays drawn ("alive") until it dies or goes.
            deadline = 0L;
            if (targetId < 0) {
                place = null;
            }
        }
        boolean walls = cfg.isPowderGhastEsp();
        throughWalls = walls;
        visible = cur != null && (walls || inSight(client, cur));
    }

    private static Entity find(Minecraft client) {
        Ghast nearest = null;
        double best = Double.MAX_VALUE;
        boolean event = deadline != 0L;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e == client.player) continue;
            if (e.hasCustomName() && NAME.matcher(ChatObserver.stripCodes(e.getCustomName().getString())).find()) {
                LivingEntity mob = e instanceof ArmorStand stand ? ghastUnder(client, stand)
                        : e instanceof LivingEntity l ? l : null;
                if (mob != null && mob.isAlive() && !(mob instanceof Player)) return mob;
            }
            if (event && e instanceof Ghast g && g.isAlive()) {
                double d = g.distanceToSqr(client.player);
                if (d < best) {
                    best = d;
                    nearest = g;
                }
            }
        }
        return nearest;
    }

    /** The mob under a name-tag stand: the entity with the id before it, else the nearest ghast below. */
    private static LivingEntity ghastUnder(Minecraft client, ArmorStand stand) {
        Entity prev = client.level.getEntity(stand.getId() - 1);
        if (prev instanceof Ghast g && g.isAlive()) return g;
        AABB below = stand.getBoundingBox().inflate(3.0, 0, 3.0).expandTowards(0, -6.0, 0);
        LivingEntity bestE = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : client.level.getEntities(stand, below)) {
            if (e instanceof Ghast g && g.isAlive()) {
                double d = e.distanceToSqr(stand);
                if (d < bestD) {
                    bestD = d;
                    bestE = g;
                }
            }
        }
        return bestE;
    }

    private static boolean inSight(Minecraft client, Entity mob) {
        Vec3 eye = client.player.getEyePosition();
        AABB b = mob.getBoundingBox();
        for (Vec3 to : new Vec3[]{b.getCenter(), mob.getEyePosition(), new Vec3(b.getCenter().x, b.minY + 0.2, b.getCenter().z)}) {
            if (client.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    client.player)).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    static void clear() {
        targetId = -1;
        visible = false;
        if (deadline != 0L) {
            deadline = 0L;
        }
        place = null;
        partyDueAt = 0L;
        lastHurt = 0;
    }

    static void render(LevelRenderContext context) {
        int id = targetId;
        Minecraft client = Minecraft.getInstance();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (id < 0 || client.level == null || client.player == null || !cfg.isPowderGhast()) return;
        Entity e = client.level.getEntity(id);
        if (e == null || e.isRemoved()) return;
        float pt = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        AABB box = e.getBoundingBox().move(Mth.lerp(pt, e.xo, e.getX()) - e.getX(),
                Mth.lerp(pt, e.yo, e.getY()) - e.getY(), Mth.lerp(pt, e.zo, e.getZ()) - e.getZ());
        if (cfg.isPowderGhastBox() && visible) {
            float[] k = WorldRenderUtils.argbToFloats(cfg.getPowderGhastBoxColor());
            float[] rgba = {k[0], k[1], k[2], k[3] <= 0f ? 1f : k[3]};
            WorldRenderUtils.renderOutlineBoxes(context, new AABB[]{box}, rgba, 1, 2f, throughWalls);
            boxFrames++;
        }
        if (cfg.isPowderGhastTracer()) {
            float[] k = WorldRenderUtils.argbToFloats(cfg.getPowderGhastTracerColor());
            WorldRenderUtils.renderLineStrip(context, new Vec3[]{WorldRenderUtils.tracerOrigin(context), box.getCenter()},
                    k[0], k[1], k[2], 1f, 2f);
            tracerFrames++;
        }
    }

    /** The HUD line now, or null when there is nothing to show. */
    static String hudLine() {
        long d = deadline;
        String where = place == null ? "" : " §7(" + place + ")";
        if (d != 0L) {
            long left = Math.max(0L, (d - com.killer560.hub.util.PerTickServerClock.now() + 19L) / 20L);
            return "§d§lPowder Ghast§7: §e" + left + "s" + where;
        }
        if (targetId >= 0) return "§d§lPowder Ghast alive" + where;
        return null;
    }


    public static int alerts() { return alerts; }

    public static int partyCallouts() { return partyCallouts; }

    public static int boxFrames() { return boxFrames; }

    public static int tracerFrames() { return tracerFrames; }

    public static int kills() { return kills; }

    public static int targetId() { return targetId; }

    public static boolean visibleNow() { return visible; }

    public static boolean throughWallsNow() { return throughWalls; }

    public static String placeNow() { return place; }

    /** Seconds until the despawn, or -1 with no event running. */
    public static long secondsLeft() {
        long d = deadline;
        return d == 0L ? -1L : Math.max(0L, (d - com.killer560.hub.util.PerTickServerClock.now()) / 20L);
    }

    public static String hudLineNow() { return hudLine(); }

    /** Pretends the minute is up now (testkit). */
    public static void expireNow() {
        if (deadline != 0L) {
            deadline = com.killer560.hub.util.PerTickServerClock.now() - 1L;
        }
    }

    /** The movable timer: seconds left (or "alive" when found without the spawn line), and the place. */
    public static final class Timer implements HudElement {

        private static final String SAMPLE = "§d§lPowder Ghast§7: §e42s §7(Cliffside Veins)";

        private String shown() {
            String s = hudLine();
            return s == null && HudVisibility.editorOpen() ? SAMPLE : s;
        }

        @Override
        public String id() { return TIMER_ID; }

        @Override
        public String displayName() { return "Powder Ghast Timer"; }

        @Override
        public int defaultX() { return 10; }

        @Override
        public int defaultY() { return 220; }

        @Override
        public int width() {
            String s = shown();
            return Math.max(60, Minecraft.getInstance().font.width(s == null ? SAMPLE : s));
        }

        @Override
        public int height() { return 10; }

        @Override
        public boolean isEnabledInSettings() {
            MiningQolConfig cfg = MiningQolConfig.getInstance();
            return cfg.isPowderGhastRaw() && cfg.isPowderGhastTimer();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            MiningQolConfig cfg = MiningQolConfig.getInstance();
            if (!cfg.isPowderGhast() || !cfg.isPowderGhastTimer() || HudVisibility.hidesHud()) return;
            String s = shown();
            if (s == null) return;
            graphics.text(Minecraft.getInstance().font, s, x, y, 0xFFFFFFFF, true);
            if (hudLine() != null) {
                HudSeen.markDrawn(TIMER_ID);
            }
        }
    }
}
