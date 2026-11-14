package com.killer560.hub.boss;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.bloodcamp.BloodCampAim;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.loadoutkeybinds.LoadoutSwapper;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.pathfinding.ShortWalk;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.AttackCooldown;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.HotbarSelect;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.RightClick;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto M5 (cheat build, default OFF; Livid Solver tab, stormm5-1111). From Livid's welcome line (the solver's invuln
 * countdown starting) it runs one fight: walk to the saved mid spot by keys ({@link ShortWalk}, the body turned with the
 * camera held), select the chosen Hypixel loadout ({@link LoadoutSwapper}), cast the Ragnarock so its 3 s channel ends
 * as the invulnerability does (swap to it, use it the next tick, swap back once the channel is over), then turn the view
 * to the real Livid the solver found with the Blood Camp aura's human aim and hit it when the crosshair is on it, in
 * reach and the weapon is charged.
 * <p>
 * Runs only while the Livid Solver itself is on (its rows sit under it). Every swap waits out
 * {@link HotbarSelect#swapWouldFlag()}; the cast and each hit wait a tick while his own attack, hotbar key or use is
 * queued; nothing writes a position or velocity, and pitch stays within +-90 with the yaw unwrapped. Escape, a movement
 * key or the Emergency Cancel Key end the run ({@link MacroStop}).
 */
public final class AutoM5Feature {

    private enum Phase { IDLE, WALK, LOADOUT, BUFF, BACK, FIGHT, DONE }

    private static final String RAGNAROCK = "RAGNAROCK_AXE";
    /** The Ragnarock channels for 3 s: cast this many ticks before invulnerability ends. */
    private static final int CHANNEL_TICKS = 60;
    /** A swap or a cast that cannot go for this long is given up (the fight still runs). */
    private static final int STEP_BUDGET_TICKS = 40;

    private static final BodyAim BODY = new BodyAim(() -> { });
    private static final BloodCampAim AIM = new BloodCampAim();

    private static Phase phase = Phase.IDLE;
    private static int lastInvuln;
    private static int stepTicks;
    private static boolean loadoutAsked;
    private static int ragSlot = -1;
    private static int backSlot = -1;
    private static boolean cast;
    private static long ticks;
    private static long nextHitTick;
    private static boolean sawLivid;
    /** Testkit readouts. */
    private static int runs;
    private static int casts;
    private static int hits;
    private static int walks;
    private static int loadouts;

    private AutoM5Feature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        MacroStop.register("Auto M5", MacroStop.Kind.MOVEMENT, AutoM5Feature::running,
                why -> end(Minecraft.getInstance(), "stopped (" + why + ")"));
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoM5Feature.tick",
                FeatureBits.register("Auto M5 tick", () -> LividSolverConfig.getInstance().getAutoM5Raw() || running()),
                AutoM5Feature::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (phase == Phase.FIGHT) {
                AIM.frame(Minecraft.getInstance());
            }
        });
    }

    private static boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        LividSolverConfig cfg = LividSolverConfig.getInstance();
        if (player == null || client.gameMode == null || client.level == null || !cfg.isAutoM5()
                || !LividSolverFeature.onFloor5()) {
            if (phase != Phase.IDLE) {
                end(client, null);
                phase = Phase.IDLE;
            }
            lastInvuln = 0;
            return;
        }
        ticks++;
        int invuln = LividSolverFeature.invulnTicks();
        boolean welcome = invuln > lastInvuln && lastInvuln == 0;
        lastInvuln = invuln;
        if (welcome && (phase == Phase.IDLE || phase == Phase.DONE)) {
            begin(client, player, cfg);
        }
        BODY.tick(player, phase == Phase.WALK);
        switch (phase) {
            case WALK -> walk(client, player);
            case LOADOUT -> loadout(client, cfg);
            case BUFF -> buff(client, player, cfg, invuln);
            case BACK -> back(client, player, invuln);
            case FIGHT -> fight(client, player, cfg, invuln);
            default -> { }
        }
    }

    private static void begin(Minecraft client, LocalPlayer player, LividSolverConfig cfg) {
        runs++;
        stepTicks = 0;
        loadoutAsked = false;
        cast = false;
        sawLivid = false;
        ragSlot = backSlot = -1;
        ModChat.log("[Auto M5] started");
        BlockPos mid = cfg.isM5Mid() ? cfg.getM5MidSpot() : null;
        ShortWalk.Plan plan = mid == null ? null : ShortWalk.plan(client.level, player, mid, null);
        if (plan != null) {
            ShortWalk.start(plan, "Auto M5");
            walks++;
            phase = Phase.WALK;
        } else {
            if (mid != null) {
                ModChat.log("[Auto M5] no way on foot to mid");
            }
            phase = Phase.LOADOUT;
        }
    }

    private static void walk(Minecraft client, LocalPlayer player) {
        float pitch = Mth.clamp(player.getXRot(), -90f, 90f);
        ShortWalk.Status status = ShortWalk.tick(client.level, player, yaw -> BODY.turn(player, yaw, pitch));
        if (status == ShortWalk.Status.WALKING) {
            return;
        }
        if (status != ShortWalk.Status.ARRIVED) {
            ModChat.log("[Auto M5] walk to mid ended: " + status.name().toLowerCase(java.util.Locale.ROOT));
        }
        ShortWalk.stop("Auto M5 " + status.name().toLowerCase(java.util.Locale.ROOT));
        next(Phase.LOADOUT);
    }

    private static void loadout(Minecraft client, LividSolverConfig cfg) {
        if (!cfg.isM5ArmorSwap()) {
            next(Phase.BUFF);
        } else if (!loadoutAsked) {
            loadoutAsked = true;
            if (LoadoutSwapper.requestLoadout(client, cfg.getM5Loadout())) {
                loadouts++;
            } else {
                next(Phase.BUFF);
            }
        } else if (!LoadoutSwapper.isBusy() || ++stepTicks > 200) {
            next(Phase.BUFF);
        }
    }

    private static void buff(Minecraft client, LocalPlayer player, LividSolverConfig cfg, int invuln) {
        if (!cfg.isM5Buff()) {
            next(Phase.FIGHT);
            return;
        }
        if (invuln > CHANNEL_TICKS || McCompat.screen(client) != null) {
            return;
        }
        if (++stepTicks > STEP_BUDGET_TICKS) {
            ModChat.log("[Auto M5] Ragnarock cast given up");
            next(Phase.BACK);
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (!ItemIdentity.matches(player.getMainHandItem(), RAGNAROCK)) {
            if (ragSlot < 0) {
                ragSlot = ItemIdentity.findHotbarSlot(player, RAGNAROCK);
                if (ragSlot < 0) {
                    ModChat.log("[Auto M5] no Ragnarock in the hotbar");
                    next(Phase.FIGHT);
                    return;
                }
                backSlot = selected;
            }
            if (!HotbarSelect.swapWouldFlag() && !ActionGate.hisAttackOrSwapQueued(client)
                    && ActionGate.tryAct(ActionGate.Actor.M5_HOTBAR)) {
                HotbarSelect.select(client, player, ragSlot);
            }
            return;
        }
        // The cast: a plain use, never in a tick where his attack, hotbar key or own use goes after it.
        if (ActionGate.hisAttackOrSwapQueued(client) || ActionGate.hisUseGoesThisTick(client)
                || !ActionGate.tryAct(ActionGate.Actor.M5_WORLD)) {
            return;
        }
        RightClick.beforePlainUse(client, player, "[Auto M5] Ragnarock");
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        casts++;
        cast = true;
        ModChat.log("[Auto M5] Ragnarock cast with " + invuln + " invulnerable ticks left");
        next(Phase.BACK);
    }

    /** Back to the weapon he held once the channel is over (a swap during it might end it). */
    private static void back(Minecraft client, LocalPlayer player, int invuln) {
        stepTicks++;
        if (backSlot < 0 || player.getInventory().getSelectedSlot() == backSlot) {
            next(Phase.FIGHT);
            return;
        }
        if ((cast && stepTicks < CHANNEL_TICKS && invuln > 0) || stepTicks > CHANNEL_TICKS + STEP_BUDGET_TICKS) {
            if (stepTicks > CHANNEL_TICKS + STEP_BUDGET_TICKS) {
                next(Phase.FIGHT);
            }
            return;
        }
        if (!HotbarSelect.swapWouldFlag() && !ActionGate.hisAttackOrSwapQueued(client)
                && ActionGate.tryAct(ActionGate.Actor.M5_HOTBAR)) {
            HotbarSelect.select(client, player, backSlot);
            next(Phase.FIGHT);
        }
    }

    private static void fight(Minecraft client, LocalPlayer player, LividSolverConfig cfg, int invuln) {
        Entity livid = LividSolverFeature.realLivid();
        if (livid == null) {
            AIM.tick(client, null, null, false);
            if (sawLivid) {
                end(client, "Livid gone");
            }
            return;
        }
        sawLivid = true;
        if (cfg.isM5Aim()) {
            AIM.setSlowness(0.3 + (cfg.getM5Smoothing() - 1) * (3.7 / 9.0));
            Vec3 point = cfg.isM5AimHead() ? livid.getEyePosition() : livid.getBoundingBox().getCenter();
            AIM.tick(client, livid, point, false);
        }
        if (!cfg.isM5Kill() || invuln > 0 || ticks < nextHitTick || McCompat.screen(client) != null
                || !(client.hitResult instanceof EntityHitResult hit) || hit.getEntity() != livid) {
            return;
        }
        double reach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
        if (BlockHits.boxDistanceSq(player.getEyePosition(), livid.getBoundingBox()) > reach * reach
                || ActionGate.hisUseGoesThisTick(client) || ActionGate.hisAttackOrSwapQueued(client)
                || !ActionGate.tryAct(ActionGate.Actor.M5_WORLD)) {
            return;
        }
        client.gameMode.attack(player, livid);
        McCompat.swingAttack(player);
        hits++;
        nextHitTick = ticks + AttackCooldown.intervalTicks(client);
    }

    private static void next(Phase p) {
        phase = p;
        stepTicks = 0;
    }

    private static void end(Minecraft client, String why) {
        if (phase == Phase.WALK) {
            ShortWalk.stop("Auto M5 ended");
        }
        if (running() && why != null) {
            ModChat.log("[Auto M5] " + why);
        }
        LocalPlayer player = client.player;
        BODY.tick(player, false);
        AIM.reset(player);
        phase = Phase.DONE;
    }

    /** Called by the Set Mid Here button: the block his feet stand in now. */
    public static void setMidHere() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            LividSolverConfig.getInstance().setM5MidSpot(player.blockPosition());
            LividSolverConfig.getInstance().save();
        }
    }

    public static String phaseName() { return phase.name(); }
    public static int runs() { return runs; }
    public static int casts() { return casts; }
    public static int hits() { return hits; }
    public static int walks() { return walks; }
    public static int loadouts() { return loadouts; }
}
