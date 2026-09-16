package com.killer560.hub.fastleap;

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
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast Leap + Auto Leap - a faithful Java port of QUOI's {@code module/impl/floor7/AutoLeap.kt} (the I4 leap lives in
 * {@link I4LeapFeature}). Every leap has an enable switch, an "Auto" child and a target:
 * <ul>
 * <li><b>Fast leap</b>: left-clicking while holding a Spirit Leap / Infinileap (in a dungeon, no screen open) is
 * cancelled and leaps to the target for where you are ({@link #attemptFastLeap()}), falling back to the P3 stage leap.
 * Rate-limited by "Fast leap click delay".</li>
 * <li><b>Auto</b>: QUOI's triggers - door opened (outside boss), 2nd Energy Laser charge (P1), Storm's lightning line
 * (predev), 1st/2nd Storm crush (pads), teammate reaching the PY healer spot, Storm's death line, P3 section complete
 * (or gate blown), Necron's "impressive trick" line (middle), 2nd Necron ARGH (P4), picking up a relic (P5).</li>
 * <li><b>Target mode</b>: Name, Class (first alive teammate of that class), or Posmsg (see {@link PosmsgTargets}; falls
 * back to the leap's name, then class).</li>
 * </ul>
 * One {@link #register()} call wires this, {@link I4LeapFeature}, {@link LeapManager}, {@link Floor7Tracker},
 * {@link Teammates} and {@link PosmsgTargets}.
 * <p>
 * TEMPORARY (2026-09-15): {@link LeapGroup#TEST} ("Test Leap") is a testing aid with no position or phase
 * condition - it always leaps to its own class. It touches exactly two places in here, both marked TEMPORARY:
 * the top of {@link #attemptFastLeap()} (manual click) and {@link #testAutoOverride(FastLeapConfig)} (the
 * automatic side). Expected to be removed once killer560 has finished testing.
 */
public final class FastLeapFeature {

    static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-fastleap");

    private static final AABB GREEN_PAD_BOX = new AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0);
    private static final AABB YELLOW_PAD_BOX = new AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0);
    private static final AABB PURPLE_PAD_BOX = new AABB(95.0, 165.0, 86.0, 123.0, 172.0, 103.0);
    private static final AABB HEALER_PY_BOX = new AABB(52.0, 169.0, 63.0, 59.0, 171.0, 70.0);
    private static final AABB MIDDLE_BOX = new AABB(47.0, 64.0, 69.0, 62.0, 75.0, 84.0);

    private static final Set<String> STORM_CRUSH_MESSAGES = Set.of("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!");
    private static final Pattern DOOR_OPEN_REGEX = Pattern.compile("^(?:\\[[^]]+] )?(\\w+) opened a (?:WITHER|Blood) door!");

    private static long lastClick = 0L;
    private static int arghCount = 0;
    private static int crystalCount = 0;
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
                if (!cfg.isEnabled() || !cfg.isLeapEnabled(LeapGroup.P3) || !cfg.isLeapAuto(LeapGroup.P3)
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
                if (!cfg.isEnabled() || !cfg.isLeapEnabled(LeapGroup.P3) || !cfg.isLeapAuto(LeapGroup.P3)
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
        ClientTickEvents.START_CLIENT_TICK.register(FastLeapFeature::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(FastLeapFeature::onEndTick);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------------------

    private static void onStartTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            onWorldChange();
        }
        Teammates.tick(client);
        try {
            LeapManager.onStartTick(client);
        } catch (RuntimeException e) {
            LeapManager.abort(e);
        }
        I4LeapFeature.tick(client);
        // I4 "Prevent Inputs": hold movement keys up while the device runs / the leap is pending
        if (I4LeapFeature.blocksInput() && client.screen == null) {
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
            if (client.screen == null) {
                net.minecraft.client.KeyMapping.setAll();
            }
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
        if (cfg.isLeapEnabled(LeapGroup.RELIC) && cfg.isLeapAuto(LeapGroup.RELIC) && !pickedUpRelic && isInRelic()) {
            ItemStack relic = player.getInventory().getItem(8);
            if (ChatObserver.strip(relic.getHoverName().getString()).contains("Relic")) {
                pickedUpRelic = true;
                LOGGER.info("[FastLeap] Relic picked up -> relic leap");
                leapToConfigured(LeapTarget.RELIC);
            }
        }
        // QUOI PacketEvent.ReceivedPost PY healer trigger (based on NoammAddons LeapCounter): a teammate reaches the
        // healer PY spot while you're waiting there after the first Storm crush. Checked per tick from entity positions
        // (including a pending interpolation target) instead of raw movement packets.
        if (Floor7Tracker.inF7Boss() && Floor7Tracker.inPhase(Phase.P2) && cfg.isLeapEnabled(LeapGroup.PY_HEALER)
                && cfg.isLeapAuto(LeapGroup.PY_HEALER) && oofCount == 1 && !leapedHealerPy && isInHealerPy()
                && client.level != null) {
            String self = Teammates.selfName();
            for (Player other : client.level.players()) {
                String name = ChatObserver.strip(other.getName().getString());
                if (name.equalsIgnoreCase(self) || Teammates.byName(name) == null) {
                    continue;
                }
                Vec3 target = other.getInterpolation() != null && other.getInterpolation().hasActiveInterpolation()
                        ? other.getInterpolation().position() : other.position();
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
        crystalCount = 0;
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
                if (cfg.isLeapEnabled(LeapGroup.PURPLE) && cfg.isLeapAuto(LeapGroup.PURPLE) && isInPurplePad()) {
                    leapToConfigured(LeapTarget.PURPLE);
                }
                if (cfg.isLeapEnabled(LeapGroup.GREEN) && cfg.isLeapAuto(LeapGroup.GREEN) && isInGreenPad()) {
                    leapToConfigured(LeapTarget.GREEN);
                }
            }
            if (oofCount == 2 && cfg.isLeapEnabled(LeapGroup.YELLOW) && cfg.isLeapAuto(LeapGroup.YELLOW) && isInYellowPad()) {
                leapToConfigured(LeapTarget.YELLOW);
            }
        }

        if ("The Energy Laser is charging up!".equals(unformatted) && cfg.isLeapEnabled(LeapGroup.P1) && cfg.isLeapAuto(LeapGroup.P1)) {
            if (++crystalCount == 2 && isInP1()) {
                leapToConfigured(LeapTarget.P1);
            }
        }

        if ("[BOSS] Storm: I'd be happy to show you what that's like!".equals(unformatted)
                && cfg.isLeapEnabled(LeapGroup.PREDEV) && cfg.isLeapAuto(LeapGroup.PREDEV) && isInPredev()) {
            leapToConfigured(LeapTarget.PREDEV);
        }

        if ("[BOSS] Necron: ARGH!".equals(unformatted) && cfg.isLeapEnabled(LeapGroup.P4) && cfg.isLeapAuto(LeapGroup.P4)) {
            if (++arghCount == 2 && isInP4()) {
                leapToConfigured(LeapTarget.P4);
            }
        }

        if ("[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.".equals(unformatted)
                && cfg.isLeapEnabled(LeapGroup.MIDDLE) && cfg.isLeapAuto(LeapGroup.MIDDLE) && isOutsideMiddle()) {
            leapToConfigured(LeapTarget.MIDDLE);
        }

        if ("[BOSS] Storm: I should have known that I stood no chance.".equals(unformatted)
                && cfg.isLeapEnabled(LeapGroup.STORM_DEATH) && cfg.isLeapAuto(LeapGroup.STORM_DEATH)) {
            leapToConfigured(LeapTarget.STORM_DEATH);
        }
    }

    /** QUOI {@code on<DungeonEvent.DoorOpen>}. */
    private static void onDoorOpen(String opener) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isLeapEnabled(LeapGroup.DOOR) || !cfg.isLeapAuto(LeapGroup.DOOR) || Floor7Tracker.inBoss()) {
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

    // ------------------------------------------------------------------------------------------------------------
    // Left click (called from FastLeapMouseHandlerMixin for a left-button press with no screen open)
    // ------------------------------------------------------------------------------------------------------------

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
            if (!cfg.isLeapEnabled(LeapGroup.P3) || !Floor7Tracker.inF7Boss()) {
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

    private static boolean attemptFastLeap() {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        // TEMPORARY test leap (2026-09-15, killer560: "add a test fast leap... it will always leap to that class
        // no matter where I am and it is only for testing right now and will be removed after I finish testing").
        // Deliberately the FIRST thing checked, above every position/phase branch below, so the test leap wins
        // wherever you're standing - that's the whole point of it, and the settings page says so in grey.
        if (cfg.isLeapEnabled(LeapGroup.TEST)) {
            DungeonClass test = cfg.getTestLeapClass();
            if (test == null) {
                ModChat.send("Fast Leap", ModChat.bad("Test leap is on but no class is selected"));
                return true;
            }
            LOGGER.info("[FastLeap] Test leap (position ignored) -> {}", test.displayName());
            leapNow(test, cfg);
            return true;
        }
        if (!Floor7Tracker.inBoss()) {
            String opener = doorOpener;
            if (!cfg.isLeapEnabled(LeapGroup.DOOR) || "Unknown".equals(opener) || opener.equalsIgnoreCase(Teammates.selfName())
                    || (cfg.isDisableAfterBloodOpen() && bloodOpen)) {
                return false;
            }
            leap(opener);
            return true;
        }
        if (!Floor7Tracker.inF7Boss()) {
            return false;
        }
        if (cfg.isLeapEnabled(LeapGroup.PREDEV) && isInPredev()) {
            return leapToConfigured(LeapTarget.PREDEV);
        }
        if (cfg.isLeapEnabled(LeapGroup.RELIC) && isInRelic()) {
            return leapToConfigured(LeapTarget.RELIC);
        }
        if (cfg.isLeapEnabled(LeapGroup.P1) && isInP1()) {
            return leapToConfigured(LeapTarget.P1);
        }
        if (cfg.isLeapEnabled(LeapGroup.P4) && isInMiddle()) {
            return leapToConfigured(LeapTarget.P4);
        }
        if (cfg.isLeapEnabled(LeapGroup.GREEN) && isInGreenPad()) {
            return leapToConfigured(LeapTarget.GREEN);
        }
        if (cfg.isLeapEnabled(LeapGroup.YELLOW) && isInYellowPad()) {
            return leapToConfigured(LeapTarget.YELLOW);
        }
        if (cfg.isLeapEnabled(LeapGroup.PURPLE) && isInPurplePad()) {
            return leapToConfigured(LeapTarget.PURPLE);
        }
        if (cfg.isLeapEnabled(LeapGroup.PY_HEALER) && isInHealerPy()) {
            return leapToConfigured(LeapTarget.PY_HEALER);
        }
        if (cfg.isLeapEnabled(LeapGroup.STORM_DEATH) && isInP2()) {
            return leapToConfigured(LeapTarget.STORM_DEATH);
        }
        if (cfg.isLeapEnabled(LeapGroup.MIDDLE) && isOutsideMiddle()) {
            return leapToConfigured(LeapTarget.MIDDLE);
        }
        return false;
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

    // ------------------------------------------------------------------------------------------------------------
    // Targets
    // ------------------------------------------------------------------------------------------------------------

    /** QUOI {@code leapToConfigured(name, clazz)} + Posmsg mode. @return whether a leap was requested. */
    static boolean leapToConfigured(LeapTarget target) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        String name = cfg.getTargetName(target);
        DungeonClass clazz = cfg.getTargetClass(target);
        switch (cfg.getTargetMode()) {
            case NAME -> {
                if (name.isBlank()) {
                    return false;
                }
                leap(name);
                return true;
            }
            case CLASS -> {
                if (clazz == null) {
                    return false;
                }
                leap(clazz);
                return true;
            }
            default -> {
                String sender = PosmsgTargets.sender(target);
                if (sender != null && LeapManager.resolveName(sender) != null) {
                    leap(sender);
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
        DungeonClass test = testAutoOverride(cfg);
        if (test != null) {
            leapNow(test, cfg);
            return;
        }
        LeapManager.leap(name, cfg.isBlockInputs(), cfg.isFastMode(), cfg.isSwapBack());
    }

    private static void leap(DungeonClass clazz) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        DungeonClass test = testAutoOverride(cfg);
        leapNow(test != null ? test : clazz, cfg);
    }

    /** Fires a leap at a class with no test-leap override applied - every leap ultimately goes out through
     *  here or {@link LeapManager#leap(String, boolean, boolean, boolean)}. */
    private static void leapNow(DungeonClass clazz, FastLeapConfig cfg) {
        LeapManager.leap(clazz, cfg.isBlockInputs(), cfg.isFastMode(), cfg.isSwapBack());
    }

    /** TEMPORARY test leap (2026-09-15, killer560) - see {@link LeapGroup#TEST}. The ONE decision point for the
     *  automatic side: {@link #leap(String)} and {@link #leap(DungeonClass)} are what every auto trigger in this
     *  class funnels through, so with "Test Leap" AND its "Auto" both on, whichever auto trigger fires (door
     *  opened, Storm crush, P3 stage done, relic, ...) is redirected to the test class instead of that leap's own
     *  target - no per-trigger special cases anywhere else. With Auto off, the test leap only acts on a manual
     *  fast-leap click (see {@link #attemptFastLeap()}) and the automatic leaps behave exactly as before.
     *  @return the class to leap to instead, or null when the test leap isn't overriding anything. */
    private static DungeonClass testAutoOverride(FastLeapConfig cfg) {
        if (!cfg.isLeapEnabled(LeapGroup.TEST) || !cfg.isLeapAuto(LeapGroup.TEST)) {
            return null;
        }
        return cfg.getTestLeapClass();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Positions (QUOI isInX helpers)
    // ------------------------------------------------------------------------------------------------------------

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
