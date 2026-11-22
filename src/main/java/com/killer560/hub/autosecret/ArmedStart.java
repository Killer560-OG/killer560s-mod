package com.killer560.hub.autosecret;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdoors.WitherDoorsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * One "armed" start for Auto Clear, Auto Secret and Dungeon Autopilot in the green room (killer560, 2026-11-21: "if i que
 * any of the auto clear style things while in green make sure it is queued for after the door drops not just that it
 * instantly blacklists rooms"). Started before the run, an auto only arms: nothing runs, plans or gives up on a room until
 * the run's own start signal ({@link WitherDoorsFeature#runStarted}: Mort's opening line or the read map; the sim's GO),
 * then it starts as if pressed that tick. Its own key again, Escape or the Emergency Cancel Key disarm it; a world change
 * drops it.
 */
public final class ArmedStart {
    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");

    private static String pending;
    private static Runnable start;
    private static Object level;
    private static boolean registered;

    private ArmedStart() {
    }

    /**
     * Called first thing by a start that passed its own checks.
     * @return true when the start was taken here (armed, or a second press disarmed it): the caller returns
     */
    public static boolean defer(String name, Runnable onStart) {
        Minecraft client = Minecraft.getInstance();
        if (name.equals(pending)) {
            LOGGER.info("[ArmedStart] {} disarmed by its own key", name);
            clear();
            ModChat.send(name, ModChat.dim("Disarmed"));
            return true;
        }
        if (WitherDoorsFeature.runStarted() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return false;
        }
        register();
        pending = name;
        start = onStart;
        level = client.level;
        LOGGER.info("[ArmedStart] {} armed in the green room - starts when the run does", name);
        ModChat.send(name, ModChat.text("Armed"), ModChat.dim(" - starts when the run does"));
        GreenRoomStart.arm();
        return true;
    }

    /** The auto armed now, or null (testkit, HUD). */
    public static String pending() {
        return pending;
    }

    private static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(ArmedStart::tick);
        MacroStop.register("Armed start", MacroStop.Kind.CLICK, () -> pending != null, why -> {
            LOGGER.info("[ArmedStart] {} disarmed: you pressed {}", pending, why);
            ModChat.send(pending, ModChat.dim("Disarmed"));
            clear();
        });
    }

    private static void tick(Minecraft client) {
        if (pending == null) {
            return;
        }
        if (client.level != level || !DungeonState.isInDungeon()) {
            LOGGER.info("[ArmedStart] {} dropped: the world changed before the run started", pending);
            clear();
            return;
        }
        if (!WitherDoorsFeature.runStarted()) {
            return;
        }
        Runnable go = start;
        LOGGER.info("[ArmedStart] {}: the run started - starting it now", pending);
        clear();
        go.run();
    }

    private static void clear() {
        pending = null;
        start = null;
        level = null;
    }
}
