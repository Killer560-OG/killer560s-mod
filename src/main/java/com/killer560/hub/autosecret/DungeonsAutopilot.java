package com.killer560.hub.autosecret;

import com.killer560.hub.autoclear.AutoClearConfig;
import com.killer560.hub.autoclear.AutoClearFeature;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdoors.WitherDoorsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Dungeons Autopilot (apmenu-1121, killer560 2026-11-21: "one button with a couple of different modes ... it should all
 * just be under one keybind"): the one switch over Auto Clear, Auto Secret and Dungeon Autopilot. ON starts a run (in the
 * green room it arms and starts with the run): first the Blood Rush part (Clear: the rush-path rooms, then the doors;
 * Door: the doors only), then the planner, which runs the Mode, Clear First and the rest. OFF stops all of it. The
 * switch is never saved, so a restart never starts a run. The behavior of each part stays in its own feature; this only
 * starts the next one when the last ends (on its own tick, never inside the callback).
 */
public final class DungeonsAutopilot {
    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");
    private static final String CHAT = "Dungeons Autopilot";

    private enum Phase { IDLE, ARMED, RUSH, SECRETS }

    private static boolean on;
    private static Phase phase = Phase.IDLE;
    /** The next step, run on the next tick (set by a finished part's callback). */
    private static Runnable next;
    private static Object level;
    /** Why it last switched off (the "off:" log line; read by the testkit). */
    private static String lastOffReason = "";

    private DungeonsAutopilot() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DungeonsAutopilot.tick", DungeonsAutopilot::tick));
        GreenRoomStart.register();
    }

    public static boolean isOn() {
        return on;
    }

    /** On and waiting for the run to start (green room). */
    static boolean isArmed() {
        return on && phase == Phase.ARMED;
    }

    /** The card's switch and its Toggle Keybind. */
    public static void setOn(boolean v) {
        if (v == on) {
            return;
        }
        if (!v) {
            stop("you switched it off");
            return;
        }
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        on = true;
        level = Minecraft.getInstance().level;
        phase = Phase.ARMED;
        if (!runGoing()) {
            ModChat.send(CHAT, ModChat.text("Armed"), ModChat.dim(" - starts when the run does"));
            GreenRoomStart.arm();
        }
        LOGGER.info("[DungeonsAutopilot] on: mode {}, blood rush {}", cfg().getMode(), cfg().getBloodRush());
    }

    /**
     * The old Dungeon Autopilot, Auto Secret, Auto Clear and Blood Rush keys, in that order (the first bound wins), for the
     * one Toggle Keybind. Each old key is cleared and its file saved, so the move happens once and an unbind stays.
     */
    public static int takeOldBind() {
        AutoClearConfig ac = AutoClearConfig.getInstance();
        LiveMapConfig lm = LiveMapConfig.getInstance();
        int k = cfg().oldStartKey();
        if (k == KeyUtil.NONE) {
            k = ac.getKeybindRaw();
        }
        if (k == KeyUtil.NONE) {
            k = lm.getBloodRushKeyCode();
        }
        if (k != KeyUtil.NONE) {
            cfg().setToggleKey(KeyUtil.NONE);
            cfg().setAutopilotKey(KeyUtil.NONE);
            cfg().save();
            ac.setKeybind(KeyUtil.NONE);
            ac.save();
            lm.setBloodRushKeyCode(KeyUtil.NONE);
            lm.save();
            LOGGER.info("[DungeonsAutopilot] old start key {} moved to the Toggle Keybind", k);
        }
        return k;
    }

    private static AutoSecretConfig cfg() {
        return AutoSecretConfig.getInstance();
    }

    private static boolean runGoing() {
        return DungeonState.isInDungeon() && !LiveMapFeature.isInBoss() && WitherDoorsFeature.runStarted();
    }

    private static void stop(String why) {
        boolean was = on;
        lastOffReason = why;
        on = false;
        phase = Phase.IDLE;
        next = null;
        ArmedStart.cancel("Dungeon Autopilot");
        AutoClearConfig.getInstance().setRunOverride(null, null);
        if (AutoSecretFeature.isRunning()) {
            AutoSecretFeature.stop(why, true);
        }
        AutoClearFeature.cancel();
        if (was) {
            LOGGER.info("[DungeonsAutopilot] off: {}", why);
        }
    }

    /** Auto Enable On Dungeon Entry's edge: in a dungeon on the last tick, that tick's world, the world it last fired in. */
    private static boolean wasInDungeon;
    private static Object seenLevel;
    private static Object enabledLevel;

    /**
     * Auto Enable On Dungeon Entry (killer560, 2026-11-26: "add a toggle to auto enable auto pilot on entering a dungeon"):
     * switches it on once as he enters a dungeon, as if he pressed it (in the green room it arms and starts with the run).
     * Once per entry: switched off during that run it stays off (no new entry, and never twice in one world outside the
     * sim), and a rejoin of a run he left does not fire it. Two boolean reads a tick otherwise.
     */
    private static void autoEnable(Minecraft client) {
        Object lvl = client.level;
        boolean in = lvl != null && DungeonState.isInDungeon();
        boolean entered = in && (!wasInDungeon || lvl != seenLevel);
        wasInDungeon = in;
        seenLevel = lvl;
        if (!entered || on || !cfg().isAutoEnable() || !AutoSecretConfig.allowed() || LiveMapFeature.isInBoss()
                || com.killer560.hub.secrets.DungeonRun.resumed() || lvl == enabledLevel && !DungeonState.isRoomSim()) {
            return;
        }
        enabledLevel = lvl;
        LOGGER.info("[DungeonsAutopilot] Auto Enable On Dungeon Entry: entered a dungeon - switching on");
        setOn(true);
    }

    private static void tick(Minecraft client) {
        autoEnable(client);
        if (!on) {
            return;
        }
        if (client.level != level) {
            stop("world changed");
            return;
        }
        if (next != null) {
            Runnable r = next;
            next = null;
            r.run();
            return;
        }
        switch (phase) {
            case ARMED -> {
                if (runGoing()) {
                    rush();
                }
            }
            case RUSH -> {
                if (!AutoClearFeature.isBusy()) {
                    stop("Auto Clear was canceled");
                }
            }
            case SECRETS -> {
                if (!AutoSecretFeature.isRunning() && ArmedStart.pending() == null) {
                    stop(AutoSecretFeature.lastStopReason());
                }
            }
            default -> {
            }
        }
    }

    /** A part ended: "you pressed ..." (Escape, the Emergency Cancel Key) switches it all off, anything else moves on. */
    private static void after(String reason, Runnable then) {
        next = reason != null && reason.startsWith("you pressed") ? () -> stop(reason) : then;
    }

    private static void rush() {
        AutoSecretConfig.BloodRush b = cfg().getBloodRush();
        if (b == AutoSecretConfig.BloodRush.NONE) {
            secrets();
            return;
        }
        phase = Phase.RUSH;
        if (b == AutoSecretConfig.BloodRush.CLEAR && AutoClearFeature.startRun(AutoClearConfig.Mode.BLOOD_RUSH_SPLIT, false,
                () -> after(null, DungeonsAutopilot::doors), r -> after(r, DungeonsAutopilot::doors))) {
            return;
        }
        doors();
    }

    private static void doors() {
        if (!AutoClearFeature.startRun(AutoClearConfig.Mode.ANY_MOB_ROOM, true,
                () -> after(null, DungeonsAutopilot::rushDone), r -> after(r, DungeonsAutopilot::rushDone))) {
            rushDone();   // the Blood door is already open
        }
    }

    private static void rushDone() {
        if (cfg().isDisableAfterBloodRush()) {
            ModChat.send(CHAT, ModChat.dim("Blood Rush done - switched off"));
            stop("Disable After Blood Rush");
            return;
        }
        secrets();
    }

    /** The planner runs every Mode (and Clear First, Clear Room Before Secreting) from here on. */
    private static void secrets() {
        phase = Phase.SECRETS;
        // the run's start signal can read true for a tick as a floor loads; then the secrets part arms in the green room
        if (!AutoSecretFeature.startAutopilot() && ArmedStart.pending() == null) {
            stop("the secrets part could not start");
        }
    }
}
