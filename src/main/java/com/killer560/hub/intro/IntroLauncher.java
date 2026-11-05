package com.killer560.hub.intro;

import com.killer560.hub.compat.McCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Opens the first-launch tour over the title screen once the game has loaded (killer560, 2026-11-01: on the main title
 * screen as soon as the game loads, not on joining a server), at most once a session and never again once done.
 *
 * <p>A client gametest run (the testkit: {@code fabric.client.gametest} is set) never gets it on its own, so the
 * hundreds of cases that start from the title screen are not interrupted; {@code -Dkiller560.test.intro=show} lets the
 * tour's own cases see it as a player would.
 */
public final class IntroLauncher {

    /** Ticks the title screen is up before the guide arrives (it fades in first). */
    private static final int DELAY_TICKS = 12;

    private static boolean triedThisSession;
    private static int titleTicks;

    private IntroLauncher() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(IntroLauncher::tick);
    }

    /** False in a gametest run unless the tour's own property asks for it. */
    static boolean allowedHere() {
        if (System.getProperty("fabric.client.gametest") == null) {
            return true;
        }
        return "show".equalsIgnoreCase(System.getProperty("killer560.test.intro"));
    }

    private static void tick(Minecraft client) {
        if (triedThisSession) {
            return;
        }
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof TitleScreen) || client.level != null) {
            titleTicks = 0;
            return;
        }
        if (++titleTicks < DELAY_TICKS) {
            return;
        }
        triedThisSession = true;
        if (!allowedHere() || IntroConfig.getInstance().isDone()) {
            return;
        }
        autoShown++;
        // Once and only once (killer560, 2026-11-01): seen from the moment it opens, so quitting mid-tour does not
        // bring it back.
        IntroConfig cfg = IntroConfig.getInstance();
        cfg.setDone(true);
        cfg.save();
        McCompat.setScreen(client, new IntroScreen(screen, true));
    }

    /** How many times the tour opened on its own this session (read by the testkit: once on a first install). */
    private static int autoShown;

    public static int autoShown() {
        return autoShown;
    }

    /** Opens the tour now over {@code parent} (Home's Replay Intro). */
    public static void replay(Screen parent) {
        McCompat.setScreen(Minecraft.getInstance(), new IntroScreen(parent));
    }

    /** For the testkit: let the launcher try again (after a case resets the done flag). */
    public static void resetSessionForTest() {
        triedThisSession = false;
        titleTicks = 0;
    }
}
