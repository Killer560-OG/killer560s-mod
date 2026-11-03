package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.MouseButtons;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

/**
 * Resume Held Break After Menus (Mining, default ON; killer560 2026-11-03: "something keeps making my left click stop
 * holding break"). His log (AttackHoldProbe, 2026-11-03) showed the cause: a menu the SERVER opens mid-dig (the
 * "Commissions" container, 2-3 s after "X Commission Complete!") goes through Gui.setScreen, which releases every key
 * mapping, and while a screen is open Minecraft.tick holds missTime at 10000. When the menu closes the attack key stays
 * up although his finger never left the button, and vanilla only digs again after a fresh press.
 *
 * <p>When a screen that opened while the attack key was held closes, and the left button is STILL physically down (the
 * button events the game saw, {@link MouseButtons}, and the platform's own button state, SDL on 26.3 / GLFW on 26.2), the
 * window is focused, the mouse is grabbed again and the attack key is bound to the left button, the attack key is set
 * down again with {@code KeyMapping.set(left button, true)} - what the button's own press does to the mapping, without the
 * click count a new press adds - and missTime is cleared from the screen's 10000 to the 0 vanilla's own next
 * {@code continueAttack(false)} would give it. From the next tick vanilla's {@code continueAttack(true)} digs as if the
 * button had been held through: the same START_DESTROY_BLOCK a held button sends when it sweeps onto a block, no click,
 * no entity attack, nothing else sent. Released during the menu: nothing happens. Mining islands only (the setting's
 * scope): in dungeons and combat, other features key off the attack button and a held sword digs nothing anyway.
 */
public final class HeldBreakResume {

    private static final Logger LOGGER = ModLog.get("killer560smod-miningqol");

    private static boolean wasKeyDown = false;
    private static boolean hadScreen = false;
    /** A screen opened while the attack key was held and the button has stayed down since. */
    private static boolean armed = false;
    private static int resumes = 0;
    private static volatile boolean trustTrackedForTest = false;

    private HeldBreakResume() {
    }

    static void tick(Minecraft client) {
        Screen screen = McCompat.screen(client);
        boolean keyDown = client.options.keyAttack.isDown();
        boolean button = buttonDown(client);
        if (screen != null && !hadScreen && wasKeyDown) {
            armed = true;
        }
        if (armed && !button) {
            armed = false; // let go during the menu: his release stands
        }
        if (screen == null && hadScreen && armed) {
            armed = false;
            tryResume(client);
            keyDown = client.options.keyAttack.isDown();
        }
        hadScreen = screen != null;
        wasKeyDown = keyDown;
    }

    private static void tryResume(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isResumeHeldBreak() || client.player == null || client.level == null || !MiningState.onMiningIsland()
                || !client.isWindowActive() || !client.mouseHandler.isMouseGrabbed()
                || client.options.keyAttack.isDown() || !buttonDown(client)) {
            return;
        }
        var left = McInput.storedButtonInput(0);
        if (!client.options.keyAttack.matches(left)) {
            return; // attack is on another button: not this case
        }
        if (client.missTime >= 10000) {
            client.missTime = 0;
        }
        KeyMapping.set(left, true);
        resumes++;
        LOGGER.info("[AttackHold] menu closed with left click still held: break resumed");
    }

    private static boolean buttonDown(Minecraft client) {
        if (!MouseButtons.isDown(0)) {
            return false;
        }
        return trustTrackedForTest || McInput.isStoredButtonDown(client.getWindow(), 0);
    }

    // ---- testkit hooks

    public static int resumes() {
        return resumes;
    }

    /** The testkit's button events reach MouseButtons but not the platform's button state: trust the events alone. */
    public static void trustTrackedButtonForTest(boolean on) {
        trustTrackedForTest = on;
    }
}
