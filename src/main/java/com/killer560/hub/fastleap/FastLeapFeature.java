package com.killer560.hub.fastleap;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig.LeapGroup;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.fastleap.Floor7Tracker.Phase;
import com.killer560.hub.fastleap.Floor7Tracker.Stage;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Fast Leap + Auto Leap - a faithful Java port of QUOI's {@code module/impl/floor7/AutoLeap.kt} (the I4 leap lives in
 * {@link I4LeapFeature}). Every leap has an enable switch, an "Auto" child and a target:
 * <ul>
 * <li><b>Fast leap</b>: left-clicking while holding a Spirit Leap / Infinileap (in a dungeon, no screen open) is
 * canceled and leaps to the target for where you are ({@link #attemptFastLeap()}), falling back to the P3 stage leap.
 * Rate-limited by "Fast leap click delay".</li>
 * <li><b>Auto</b>: QUOI's triggers - door opened (outside boss), your own Energy Crystal pickup line (P1), Storm's lightning line
 * (predev), 1st/2nd Storm crush (pads), teammate reaching the PY healer spot, Storm's death line, P3 section complete
 * (or gate blown), Necron's "impressive trick" line (middle), 2nd Necron ARGH (P4), picking up a relic (P5).</li>
 * <li><b>Target</b>: each leap's own pick, a class (first alive teammate of that class, Leap Order's class picks
 * applied) or a name; or, in Posmsg mode, whoever announced the spot (see {@link PosmsgTargets}), with the pick as backup.</li>
 * <li><b>Class Targets</b> (2026-10-27): a leap can have its own pick per class YOU play this run; that class's pick
 * replaces the Target, and a class with none uses it ({@link #leapToConfigured}).</li>
 * <li><b>Fast / Auto</b> (2026-10-13, v2): two switches per leap, each on or off by itself. An Auto leap swaps to the
 * leap item if it is not held, uses it, clicks the target's head and swaps to Return To ({@link LeapManager}).</li>
 * </ul>
 * One {@link #register()} call wires this, {@link I4LeapFeature}, {@link LeapManager}, {@link Floor7Tracker},
 * {@link Teammates} and {@link PosmsgTargets}.
 */
public final class FastLeapFeature {

    static final Logger LOGGER = ModLog.get("killer560smod-fastleap");

    private static final AABB GREEN_PAD_BOX = new AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0);
    private static final AABB YELLOW_PAD_BOX = new AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0);
    private static final AABB PURPLE_PAD_BOX = new AABB(95.0, 165.0, 86.0, 123.0, 172.0, 103.0);
    private static final AABB HEALER_PY_BOX = new AABB(52.0, 169.0, 63.0, 59.0, 171.0, 70.0);
    private static final AABB MIDDLE_BOX = new AABB(47.0, 64.0, 69.0, 62.0, 75.0, 84.0);

    private static final Set<String> STORM_CRUSH_MESSAGES = Set.of("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!");
    private static final Pattern CRYSTAL_PICKUP_REGEX = Pattern.compile("^(?:\\[[^]]{1,32}] )?([A-Za-z0-9_]{1,16}) picked up an Energy Crystal!$");
    private static final Pattern DOOR_OPEN_REGEX = Pattern.compile("^(?:\\[[^]]{1,32}] )?([A-Za-z0-9_]{1,16}) opened a (?:WITHER|Blood) door!");

    private static long lastClick = 0L;
    private static int arghCount = 0;
    private static int oofCount = 0;
    private static boolean pickedUpRelic = false;
    private static boolean leapedHealerPy = false;
    private static String doorOpener = "Unknown";
    private static boolean bloodOpen = false;
    private static Object lastLevel = null;
    private static boolean i4MovementHeld = false;

    private FastLeapFeature() {
    }

    public static void register() {
        Floor7Tracker.setListener(new Floor7Tracker.Listener() {
            @Override
            public void onStageComplete(Stage stage) {
                FastLeapConfig cfg = FastLeapConfig.getInstance();
                if (!cfg.isEnabled() || !cfg.isLeapAuto(LeapGroup.P3)
                        || !Floor7Tracker.inPhaseAt(Phase.P3)) {
                    return;
                }
                if (cfg.isOnlyWhenGateBlown()) {
                    return;
                }
                handleP3Leap(stage);
            }

            @Override
            public void onStageCompleteFull(Stage stage) {
                FastLeapConfig cfg = FastLeapConfig.getInstance();
                if (!cfg.isEnabled() || !cfg.isLeapAuto(LeapGroup.P3)
                        || !Floor7Tracker.inPhaseAt(Phase.P3)) {
                    return;
                }
                if (!cfg.isOnlyWhenGateBlown()) {
                    return;
                }
                handleP3Leap(stage);
            }
        });
        ChatObserver.subscribe(FastLeapFeature::onChat);
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("FastLeapFeature.onStartTick", FastLeapFeature::onStartTick));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("FastLeapFeature.onEndTick", FastLeapFeature::onEndTick));
    }

    // Events

    private static void onStartTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            onWorldChange();
        }
        Teammates.tick(client);
        Floor7Tracker.tick();
        // before anything here can send a use: a use in the turn tick must carry the turned look (leapsame-1105)
        LeapRotation.tick(client);
        try {
            LeapManager.onStartTick(client);
        } catch (RuntimeException e) {
            LeapManager.abort(e);
        }
        I4LeapFeature.tick(client);
        // I4 "Prevent Inputs": hold movement keys up while the device runs / the leap is pending
        if (I4LeapFeature.blocksInput() && McCompat.screen(client) == null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyShift.setDown(false);
            i4MovementHeld = true;
        } else if (i4MovementHeld) {
            i4MovementHeld = false;
            // with a screen open, closing it re-syncs the keys (MouseHandler.grabMouse -> KeyMapping.setAll)
            if (McCompat.screen(client) == null) {
                net.minecraft.client.KeyMapping.setAll();
            }
        }
        try {
            FastLeapStuckLog.tick(client);
        } catch (RuntimeException e) {
            LOGGER.error("[FastLeap] stuck-state log failed", e);
        }
    }

    private static void onEndTick(Minecraft client) {
        try {
            LeapManager.onEndTick(client);
        } catch (RuntimeException e) {
            LeapManager.abort(e);
        }
        LocalPlayer player = client.player;
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        if (player == null || !cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        // QUOI TickEvent.End relic trigger (based on NoammAddons M7Relics)
        if (cfg.isLeapAuto(LeapGroup.RELIC) && !pickedUpRelic && isInRelic()) {
            ItemStack relic = player.getInventory().getItem(8);
            if (!relic.isEmpty() && ChatObserver.strip(relic.getHoverName().getString()).contains("Relic")) {
                pickedUpRelic = true;
                LOGGER.info("[FastLeap] Relic picked up -> relic leap");
                leapToConfigured(LeapTarget.RELIC);
            }
        }
        // QUOI PacketEvent.ReceivedPost PY healer trigger (based on NoammAddons LeapCounter): a teammate reaches the
        // healer PY spot while you're waiting there after the first Storm crush. Checked per tick from entity positions
        // (including a pending interpolation target) instead of raw movement packets.
        if (Floor7Tracker.inF7Boss() && Floor7Tracker.inPhase(Phase.P2) && cfg.isLeapAuto(LeapGroup.PY_HEALER)
                && oofCount == 1 && !leapedHealerPy && isInHealerPy() && client.level != null) {
            String self = Teammates.selfName();
            for (Player other : client.level.players()) {
                String name = ChatObserver.strip(other.getName().getString());
                if (name.equalsIgnoreCase(self) || Teammates.byName(name) == null) {
                    continue;
                }
                Vec3 target = other.getInterpolation() != null && other.getInterpolation().hasActiveInterpolation()
                        ? com.killer560.hub.compat.McEntities.interpolationTarget(other.getInterpolation()) : other.position();
                if (HEALER_PY_BOX.contains(target) || HEALER_PY_BOX.contains(other.position())) {
                    leapedHealerPy = true;
                    LOGGER.info("[FastLeap] {} reached the PY healer spot -> PY healer leap", name);
                    leapToConfigured(LeapTarget.PY_HEALER);
                    break;
                }
            }
        }
    }

    private static void onWorldChange() {
        arghCount = 0;
        oofCount = 0;
        pickedUpRelic = false;
        leapedHealerPy = false;
        doorOpener = "Unknown";
        bloodOpen = false;
        Floor7Tracker.onWorldChange();
        PosmsgTargets.clear();
        Teammates.clear();
        LeapManager.onWorldChange();
        I4LeapFeature.onWorldChange();
        LeapRotation.clear();
    }

    private static void onChat(Component message) {
        String unformatted = ChatObserver.strip(message);
        LeapManager.onChat(unformatted);
        PosmsgTargets.onChat(unformatted);
        Floor7Tracker.onChat(unformatted);

        // QUOI Dungeon: door opener / blood open tracking
        if (DungeonState.isInDungeon()) {
            Matcher door = DOOR_OPEN_REGEX.matcher(unformatted);
            if (door.matches()) {
                doorOpener = door.group(1);
                onDoorOpen(doorOpener);
            }
            if ("The BLOOD DOOR has been opened!".equals(unformatted)) {
                bloodOpen = true;
            }
        }

        I4LeapFeature.onChat(unformatted);

        FastLeapConfig cfg = FastLeapConfig.getInstance();
        if (!cfg.isEnabled() || !Floor7Tracker.inF7Boss()) {
            return;
        }

        if (STORM_CRUSH_MESSAGES.contains(unformatted)) {
            oofCount++;
            if (oofCount == 1) {
                if (cfg.isLeapAuto(LeapGroup.PURPLE) && isInPurplePad()) {
                    leapToConfigured(LeapTarget.PURPLE);
                }
                if (cfg.isLeapAuto(LeapGroup.GREEN) && isInGreenPad()) {
                    leapToConfigured(LeapTarget.GREEN);
                }
            }
            if (oofCount == 2 && cfg.isLeapAuto(LeapGroup.YELLOW) && isInYellowPad()) {
                leapToConfigured(LeapTarget.YELLOW);
            }
        }

        // Crystal leap (killer560, 2026-11-12: "it should leap the second i pick up a crystal"): HIS OWN pickup line only
        Matcher pickup = CRYSTAL_PICKUP_REGEX.matcher(unformatted);
        if (pickup.matches() && cfg.isLeapAuto(LeapGroup.P1) && pickup.group(1).equalsIgnoreCase(Teammates.selfName())
                && isInP1()) {
            leapToConfigured(LeapTarget.P1);
        }

        if ("[BOSS] Storm: I'd be happy to show you what that's like!".equals(unformatted)
                && cfg.isLeapAuto(LeapGroup.PREDEV) && isInPredev()) {
            leapToConfigured(LeapTarget.PREDEV);
        }

        if ("[BOSS] Necron: ARGH!".equals(unformatted) && cfg.isLeapAuto(LeapGroup.P4)) {
            if (++arghCount == 2 && isInP4()) {
                leapToConfigured(LeapTarget.P4);
            }
        }

        if ("[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.".equals(unformatted)
                && cfg.isLeapAuto(LeapGroup.MIDDLE) && isOutsideMiddle()) {
            leapToConfigured(LeapTarget.MIDDLE);
        }

        if ("[BOSS] Storm: I should have known that I stood no chance.".equals(unformatted)
                && cfg.isLeapAuto(LeapGroup.STORM_DEATH)) {
            leapToConfigured(LeapTarget.STORM_DEATH);
        }
    }

    /** QUOI {@code on<DungeonEvent.DoorOpen>}. */
    private static void onDoorOpen(String opener) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isLeapAuto(LeapGroup.DOOR) || Floor7Tracker.inBoss()) {
            return;
        }
        if (opener.equalsIgnoreCase(Teammates.selfName())) {
            return;
        }
        if (cfg.isDisableAfterBloodOpen() && bloodOpen) {
            return;
        }
        LOGGER.info("[FastLeap] {} opened a door -> door opener leap", opener);
        leap(opener);
    }

    // Left click (called from FastLeapMouseHandlerMixin for a left-button press with no screen open)

    /** QUOI {@code on<MouseEvent.Click>}. @return true to cancel the click. */
    public static boolean onLeftClick() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !DungeonState.isInDungeon()) {
            return false;
        }
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        boolean i4 = I4LeapFeature.wantsFastLeap();
        if (!cfg.isEnabled() && !i4) {
            return false;
        }
        if (!isHoldingLeap(player)) {
            return false;
        }
        if (LeapManager.isBusy()) {
            // A leap is already in flight (its menu may be hidden) - swallow the click instead of queueing a
            // second leap that would only print "Queued" then "On cooldown".
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < cfg.getClickDelayMs()) {
            return true;
        }
        // One press, one leap (crystalleap-1112): a click landing within the click delay after a leap finished is the
        // same press arriving late; it only produced "Failed to leap! On cooldown".
        if (now - LeapManager.lastLeapMs() < cfg.getClickDelayMs()) {
            return true;
        }
        if (i4) {
            if (I4LeapFeature.autoI4StillShooting()) {
                // the device is still running under Auto i4 - don't leap out with shots left
                ModChat.send("I4 Leap", ModChat.bad("Device still running - not leaping"));
            } else {
                I4LeapFeature.leapToTarget();
            }
            lastClick = now;
            return true;
        }
        if (!attemptFastLeap()) {
            if (!cfg.isLeapFast(LeapGroup.P3) || !Floor7Tracker.inF7Boss()) {
                return true;
            }
            handleP3Leap(Floor7Tracker.getStageAt());
        }
        lastClick = now;
        return true;
    }

    static boolean isHoldingLeap(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (LeapManager.isLeapItem(held)) {
            return true;
        }
        // p3sim.net items may lack Hypixel's custom_data id
        return LeapManager.skyblockId(held) == null && !held.isEmpty()
                && ChatObserver.strip(held.getHoverName().getString()).contains("Spirit Leap");
    }

    private record Spot(LeapGroup group, java.util.function.BooleanSupplier inside, LeapTarget target) {
    }

    /** Checked in this order by {@link #attemptFastLeap}; the first group that is on and whose spot he stands in wins. */
    private static final java.util.List<Spot> SPOTS = java.util.List.of(
            new Spot(LeapGroup.PREDEV, FastLeapFeature::isInPredev, LeapTarget.PREDEV),
            new Spot(LeapGroup.RELIC, FastLeapFeature::isInRelic, LeapTarget.RELIC),
            new Spot(LeapGroup.P1, FastLeapFeature::isInP1, LeapTarget.P1),
            new Spot(LeapGroup.P4, FastLeapFeature::isInMiddle, LeapTarget.P4),
            new Spot(LeapGroup.GREEN, FastLeapFeature::isInGreenPad, LeapTarget.GREEN),
            new Spot(LeapGroup.YELLOW, FastLeapFeature::isInYellowPad, LeapTarget.YELLOW),
            new Spot(LeapGroup.PURPLE, FastLeapFeature::isInPurplePad, LeapTarget.PURPLE),
            new Spot(LeapGroup.PY_HEALER, FastLeapFeature::isInHealerPy, LeapTarget.PY_HEALER),
            new Spot(LeapGroup.STORM_DEATH, FastLeapFeature::isInP2, LeapTarget.STORM_DEATH));

    private static boolean attemptFastLeap() {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        if (!Floor7Tracker.inBoss()) {
            String opener = doorOpener;
            if (!cfg.isLeapFast(LeapGroup.DOOR) || "Unknown".equals(opener) || opener.equalsIgnoreCase(Teammates.selfName())
                    || (cfg.isDisableAfterBloodOpen() && bloodOpen)) {
                return false;
            }
            leap(opener);
            return true;
        }
        if (!Floor7Tracker.inF7Boss()) {
            return false;
        }
        for (Spot spot : SPOTS) {
            if (cfg.isLeapFast(spot.group()) && spot.inside().getAsBoolean()) {
                return leapToConfigured(spot.target());
            }
        }
        return cfg.isLeapFast(LeapGroup.MIDDLE) && isOutsideMiddle() && leapToConfigured(LeapTarget.MIDDLE);
    }

    private static void handleP3Leap(Stage completedStage) {
        Stage currentStage = Floor7Tracker.getStageAt();
        // don't leap if the player is already in a later stage
        if (currentStage == Stage.UNKNOWN || currentStage.number > completedStage.number) {
            return;
        }
        LeapTarget target = switch (completedStage) {
            case S1 -> LeapTarget.S1;
            case S2 -> LeapTarget.S2;
            case S3 -> LeapTarget.S3;
            case S4 -> LeapTarget.S4;
            default -> null;
        };
        if (target != null) {
            LOGGER.info("[FastLeap] P3 leap for completed {} (standing in {})", completedStage, currentStage);
            leapToConfigured(target);
        }
    }

    // Targets

    /** QUOI {@code leapToConfigured(name, clazz)} + Posmsg mode. @return whether a leap was requested. */
    static boolean leapToConfigured(LeapTarget target) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        String name = cfg.getTargetName(target);
        DungeonClass clazz = cfg.getTargetClass(target);
        boolean byClass = cfg.isTargetByClass(target);
        // Per-class target (2026-10-27): the class YOU play this run, from the dungeon tab list with Leap Order's class
        // pick for your own name applied (PartyTracker.selfClass), replaces the pick when that class has one set. In
        // Posmsg mode it replaces the backup pick; an announced position still goes first.
        DungeonClass mine = com.killer560.hub.leapmenu.PartyTracker.selfClass();
        // Only with Targets on Set Class (2026-11-02); on Universal every class uses the Universal target.
        FastLeapConfig.ClassPick pick = cfg.isUseClassTargets() ? cfg.getClassTarget(target, mine) : null;
        lastSelfClass = mine == null ? "" : mine.name();
        lastUsedClassTarget = pick != null;
        if (pick != null) {
            LOGGER.info("[FastLeap] {} as {}: per-class target {}", displayLabel(target), mine.displayName(),
                    pick.byClass() ? "class " + pick.clazz().name() : "name " + pick.name());
            if (pick.byClass()) {
                byClass = true;
                clazz = pick.clazz();
            } else {
                byClass = false;
                name = pick.name();
            }
        }
        switch (cfg.getTargetMode(target)) {
            case PER_LEAP -> {
                if (byClass ? clazz == null : name.isBlank()) {
                    ModChat.send("Fast Leap", ModChat.value(displayLabel(target)), ModChat.text(" has no target picked"));
                    return false;
                }
                if (byClass) {
                    leap(clazz);
                } else {
                    leap(name);
                }
                return true;
            }
            default -> {
                String sender = PosmsgTargets.sender(target);
                if (sender != null && LeapManager.resolveName(sender) != null) {
                    leap(sender);
                    return true;
                }
                // the leap's own pick first, then the other half of the target
                if (byClass && clazz != null) {
                    leap(clazz);
                    return true;
                }
                if (!name.isBlank()) {
                    leap(name);
                    return true;
                }
                if (clazz != null) {
                    leap(clazz);
                    return true;
                }
                ModChat.send("Fast Leap", ModChat.text("No one has announced "), ModChat.value(displayLabel(target)),
                        ModChat.text(" yet"));
                return false;
            }
        }
    }

    static String displayLabel(LeapTarget target) {
        return target.group == LeapGroup.P3 ? target.label : target.group.label;
    }

    private static void leap(String name) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        lastTarget = "name " + name;
        LeapManager.leap(name, cfg.isBlockInputs(), cfg.isFastMode(), true);
    }

    /** Every leap ultimately goes out through here or {@link LeapManager#leap(String, boolean, boolean, boolean)}. */
    private static void leap(DungeonClass clazz) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        lastTarget = "class " + clazz.name();
        LeapManager.leap(clazz, cfg.isBlockInputs(), cfg.isFastMode(), true);
    }

    /** What the last fast/auto leap was sent to ("class MAGE", "name Bob"; testkit). */
    private static volatile String lastTarget = "";

    public static String lastTarget() {
        return lastTarget;
    }

    /** Your class when the last configured leap was resolved ("MAGE", "" unknown) and whether its per-class target was
     *  used (testkit 983). */
    private static volatile String lastSelfClass = "";
    private static volatile boolean lastUsedClassTarget = false;

    public static String lastSelfClass() {
        return lastSelfClass;
    }

    public static boolean lastUsedClassTarget() {
        return lastUsedClassTarget;
    }

    // Positions (QUOI isInX helpers)

    private static boolean isIn(AABB box) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && box.contains(player.position());
    }

    private static boolean isInP1() {
        return Floor7Tracker.inPhaseAt(Phase.P1);
    }

    private static boolean isInPredev() {
        return Floor7Tracker.inPhaseAt(Phase.P3) && Floor7Tracker.inPhase(Phase.P1, Phase.P2);
    }

    private static boolean isInP4() {
        return Floor7Tracker.inPhaseAt(Phase.P4);
    }

    private static boolean isInRelic() {
        return Floor7Tracker.inPhaseAt(Phase.P5);
    }

    private static boolean isInGreenPad() {
        return isIn(GREEN_PAD_BOX);
    }

    private static boolean isInYellowPad() {
        return isIn(YELLOW_PAD_BOX);
    }

    private static boolean isInPurplePad() {
        return isIn(PURPLE_PAD_BOX);
    }

    private static boolean isInHealerPy() {
        return isIn(HEALER_PY_BOX) && Floor7Tracker.inPhase(Phase.P2);
    }

    private static boolean isInP2() {
        return Floor7Tracker.inPhaseAt(Phase.P2) && !isInPurplePad() && !isInGreenPad() && !isInYellowPad();
    }

    private static boolean isInMiddle() {
        return isIn(MIDDLE_BOX);
    }

    private static boolean isOutsideMiddle() {
        return Floor7Tracker.inPhaseAt(Phase.P4) && !isInMiddle();
    }
}
