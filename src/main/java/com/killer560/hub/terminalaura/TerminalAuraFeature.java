package com.killer560.hub.terminalaura;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.fastleap.LeapManager;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BodyAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Terminal Aura - opens P3 terminals by itself when you walk past one, cheat build only, default OFF.
 * A port of QUOI's {@code TerminalAura} (credited there to Kyleen), asked for by killer560 2026-09-16:
 * "it should be just like every other aura that you have implemented just for terminals".
 * <p>
 * This only <i>opens</i> a terminal - it right-clicks the "Inactive Terminal" armour stand. Solving what
 * is inside is Auto Terminals' job, and the two are independent: with Auto Terminals off, this just saves
 * the click that opens the GUI.
 * <p>
 * The CAMERA never moves. A terminal your look already hits is clicked as it is. One your look misses - behind you
 * at Aura FOV Any - drew GrimAC's {@code Hitboxes} flag (testkit 414, 2026-10-07), because the interact named an
 * entity the reported rotation does not point at. With Turn To Terminal on (the default) the aura turns your BODY to
 * it with {@link BodyAim} on one tick, so that tick's movement packet reports a look that hits the stand, clicks on
 * the next, and gives the body back to your view the tick after; the camera is held still throughout.
 */
public final class TerminalAuraFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-terminalaura");

    private static long lastClickMs = 0L;

    /** The body turn for a terminal the look misses; the camera stays put (see the class doc). */
    private static final BodyAim AIM = new BodyAim(() -> { });
    /** The stand the body was turned to last tick, clicked this tick; null when nothing is pending. */
    private static ArmorStand pendingStand;
    private static int pendingTicks;

    private TerminalAuraFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        TerminalAuraConfig.getInstance();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("TerminalAuraFeature.tick", TerminalAuraFeature::tick));
        LOGGER.info("[TerminalAura] Registered (default OFF)");
    }

    /**
     * Whether a movement key is being HELD, which is not the same question as whether the player is moving.
     *
     * <p>killer560 (2026-10-01): "holding any movement key at all whatsoever, not necessarily having velocity."
     * So this reads the key states and never the velocity. The two genuinely differ, and in both directions:
     * walking into a wall holds a key with no velocity at all, and being knocked across the platform by Goldor
     * gives plenty of velocity with no key down. He asked for the key.
     *
     * <p>The four directions and jump. SNEAK is deliberately NOT one of them: it does not move you, and it is
     * held down for the whole of an etherwarp, so counting it would switch the aura off every time he warped
     * between terminals - which is the opposite of useful. Say the word and it goes in.
     *
     * <p>Same idiom as {@code BloodRush.userInput}, including the open-screen test: a key cannot be "held" for
     * movement while a GUI has the keyboard.
     */
    private static boolean movementKeyHeld(Minecraft client) {
        var o = client.options;
        return McCompat.screen(client) == null
                && (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || o.keyJump.isDown());
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        // Before every other check, so a body turned for a terminal is always given back - even if the aura was
        // switched off or a screen opened in between.
        AIM.tick(player, pendingStand != null);
        TerminalAuraConfig cfg = TerminalAuraConfig.getInstance();
        if (!cfg.isEnabled() || player == null || client.level == null || client.gameMode == null) {
            pendingStand = null;
            return;
        }
        if (pendingStand != null) {
            clickPending(client, player);
            return;
        }
        // Any open screen means a terminal (or anything else) is already up - clicking another one
        // underneath it would queue a second GUI on top of the one being solved.
        if (McCompat.screen(client) != null || player.isDeadOrDying()) {
            return;
        }
        if (!Floor7Tracker.inF7Boss() || !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickMs < cfg.getDelayMs()) {
            return;
        }
        if (cfg.isGroundOnly() && !player.onGround()) {
            return;
        }
        if (cfg.isPauseOnMovementKeys() && movementKeyHeld(client)) {
            return;
        }
        if (cfg.isLeapDelayEnabled()
                && now - LeapManager.lastLeapMs() < (long) (cfg.getLeapDelaySeconds() * 1000.0)) {
            // Just landed from a leap: the terminals around the landing spot usually belong to whoever
            // you leapt to, so hold off rather than stealing the one they were walking into.
            return;
        }

        Target t = pick(client, player, cfg.getRange(), cfg.getFovDegrees());
        if (t == null) {
            return;
        }
        if (!t.lookHits() && cfg.isTurnToTerminal()) {
            // Turn this tick, click next tick, once this tick's movement packet has reported the turned look.
            Vec3 d = TerminalStands.center(t.stand()).subtract(player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            AIM.turnTo(player, yaw, Mth.clamp(pitch, -90f, 90f));
            pendingStand = t.stand();
            pendingTicks = 0;
            return;
        }
        if (send(client, player, t)) {
            lastClickMs = now;
        }
    }

    /** The click a body turn was made for, one tick later; retried for a few ticks if the ActionGate is busy. */
    private static void clickPending(Minecraft client, LocalPlayer player) {
        ArmorStand stand = pendingStand;
        double range = TerminalAuraConfig.getInstance().getRange();
        Vec3 eyes = player.getEyePosition();
        boolean stillThere = stand.isAlive() && !stand.isRemoved() && McCompat.screen(client) == null
                && com.killer560.hub.util.BlockHits.boxDistanceSq(eyes, stand.getBoundingBox()) <= range * range;
        Vec3 hit = stand.getBoundingBox().inflate(TerminalStands.INFLATE)
                .clip(eyes, TerminalStands.center(stand)).orElse(null);
        if (!stillThere || hit == null || ++pendingTicks > 5) {
            pendingStand = null;
            return;
        }
        if (send(client, player, new Target(stand, hit, true))) {
            lastClickMs = System.currentTimeMillis();
            pendingStand = null;
        }
    }

    /** A chosen terminal, the point to report, and whether the current look ray already hits its box. */
    private record Target(ArmorStand stand, Vec3 hit, boolean lookHits) {
    }

    /**
     * Clicks the nearest terminal within {@code range}, once, and reports whether the interact went out. Used by the
     * aura's own tick above and by AP3's Term Aura node - killer560 (2026-09-22): "once I step onto it if there is a
     * term in my range it will click it to open it once... It should effectively toggle term aura for a packet if
     * that makes sense. But not actually turn the setting on or off" - so this deliberately does NOT read
     * {@link TerminalAuraConfig#isEnabled()}, and the caller owns the retry.
     */
    public static boolean clickNearest(Minecraft client, Player player, double range) {
        return clickNearest(client, player, range, TerminalAuraConfig.MAX_FOV);
    }

    /**
     * As above, but only terminals within {@code fovDegrees} of where you are looking (360 = any direction), as
     * QUOI's "Aura FOV".
     */
    public static boolean clickNearest(Minecraft client, Player player, double range, int fovDegrees) {
        Target t = pick(client, player, range, fovDegrees);
        return t != null && send(client, player, t);
    }

    /** Mod-wide one-interaction-per-tick gate, then the interact and the swing. */
    private static boolean send(Minecraft client, Player player, Target t) {
        if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_AURA)) {
            return false;
        }
        client.gameMode.interact(player, t.stand(), new EntityHitResult(t.stand(), t.hit()), InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** The nearest terminal the aura may click, or null. */
    private static Target pick(Minecraft client, Player player, double range, int fovDegrees) {
        if (client.level == null || client.gameMode == null || range <= 0) {
            return null;
        }
        Vec3 eyes = player.getEyePosition();
        double rangeSqr = range * range;
        Vec3 look = player.getViewVector(1f).normalize();
        boolean fullCircle = fovDegrees >= TerminalAuraConfig.MAX_FOV;
        double minFovDot = Math.cos(Math.toRadians(fovDegrees / 2.0));
        // Sorted by distance, so "the nearest terminal" is what actually happens.
        //
        // This walked getEntitiesOfClass order and took the first one in range, which is chunk and spawn
        // order - not distance. With two terminals close together it could reach past the one he is standing
        // at for the one behind it. The class doc already claimed nearest; now it is true.
        List<ArmorStand> stands = new java.util.ArrayList<>(
                TerminalStands.near(client.level, player, range));
        stands.sort(java.util.Comparator.comparingDouble(
                st -> eyes.distanceToSqr(TerminalStands.center(st))));
        for (ArmorStand stand : stands) {
            Vec3 center = TerminalStands.center(stand);
            // Since Hypixel's 2026 terminal update a terminal does not open from below: QUOI (jcnlk's fork,
            // 077adbe/44eac98, 2026-10-07) skips any stand whose feet are above your EYES, not your feet.
            if (eyes.y < stand.getY()) {
                continue;
            }
            if (!fullCircle && center.subtract(eyes).normalize().dot(look) < minFovDot) {
                continue;
            }
            // To the BOX. The centre reads further than the server measures, so a terminal at the edge of
            // the 3.0 entity limit was refused when it was really in range.
            if (com.killer560.hub.util.BlockHits.boxDistanceSq(eyes,
                    stand.getBoundingBox()) > rangeSqr) {
                continue;
            }
            // Aim the interact at where the line from your eyes actually meets the stand's box, the same
            // point a real right-click would report - not at the entity's origin.
            AABB box = stand.getBoundingBox().inflate(TerminalStands.INFLATE);
            Vec3 hit = box.clip(eyes, center).orElse(null);
            if (hit == null) {
                continue;
            }
            // One per pass, so the delay actually paces them. The ActionGate is asked in send(), after the target
            // is chosen but before anything is sent or lastClickMs moves, so a denied tick costs nothing.
            boolean lookHits = box.clip(eyes, eyes.add(look.scale(range + 1.0))).isPresent();
            return new Target(stand, hit, lookHits);
        }
        return null;
    }
}
