package com.killer560.hub.macrostop;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What stops a macro (killer560, 2026-11-01): "for auto etable only make it stop if I press escape or the emergency
 * cancel key not other keys. Do the same for other macros too. For movement macros it should be any movement key or
 * escape or chat or the emergency cancel key."
 * <p>
 * Every macro registers here once, as a {@link Kind#CLICK} macro (it clicks in menus: Auto E-Table, Bazaar Flip, Auto
 * Croesus, Auto Sell, Auto Anvil, Inventory Sorter, Storage Scan All) or a {@link Kind#MOVEMENT} macro (it moves him:
 * Auto Fairy Souls, Auto Secret, Auto Clear, Blood Rush). {@code MacroStopKeyMixin} / {@code MacroStopMouseMixin} hand
 * every fresh raw press here, at the head of Minecraft's own key and mouse handlers, ahead of every screen, so Block
 * Input or a menu cannot hide it:
 * <ul>
 *   <li>Escape stops both kinds - with no screen open (it opens the pause menu), a container open (it closes it) or a
 *       sign open (Bazaar Flip's amount sign, where its stop press is swallowed).</li>
 *   <li>The Emergency Cancel Key ({@link MacroStopConfig}) stops both kinds - with no screen, a container or a sign open.</li>
 *   <li>A movement key (forward, back, left, right, jump) or the chat / command key, with no screen open, stops a
 *       movement macro.</li>
 *   <li>Nothing else does: no other key, no mouse button, no scroll. Keys typed into a screen that is not a container
 *       (chat, this mod's menu) are never counted, per the CLAUDE.md "any key stops it" lesson.</li>
 * </ul>
 * A macro's own Start/Stop key stays its own business (each feature polls it). Only presses count, never releases or
 * repeats, so a key held from before a start never stops it.
 * <p>
 * <b>The Emergency Cancel Key stops everything automatic</b> (killer560, 2026-11-01: "an emergency cancel that misses
 * half the automation would be surprising"). Automation with stop rules of its own - Auto Routes, AP3, Mimic Killer, the
 * Interactive Map's executor and the etherwarp / pearl hoppers - registers as {@link Kind#EMERGENCY}: only this key stops
 * it here, and its own rules are untouched. Automation that acts by itself whenever it is on (auras, triggerbots, Auto
 * Terminals, the auto puzzles, Auto I4...) has nothing to "stop", so the key also starts an <b>emergency hold</b>: every
 * automated interaction through {@code util/ActionGate} is refused (his own binds - Slot Binds, Pet Wheel, Loadout
 * Keybinds - excepted), the auto puzzles are reset and do not act, Auto Routes and AP3 do not start a node, and Auto I4
 * does not fire. The hold ends when he presses the key again with nothing running, on a world change, or when he starts
 * a click or movement macro himself. Chat says each.
 */
public final class MacroStop {

    public enum Kind {
        /** Clicks in menus: Escape or the Emergency Cancel Key. */
        CLICK,
        /** Moves him: also a movement key or the chat key, with no screen open. */
        MOVEMENT,
        /** Has stop rules of its own (Auto Routes, AP3...): only the Emergency Cancel Key, here. */
        EMERGENCY
    }

    /** What a macro wants done with a press: the stop decision is made here; the macro says whether the press then
     *  still reaches the game. */
    @FunctionalInterface
    public interface Swallow {
        /** @param stopped whether this press just stopped the macro
         *  @return true to cancel the press */
        boolean swallow(int code, boolean stopped, Screen screen);
    }

    private record Macro(String name, Kind kind, BooleanSupplier running, Consumer<String> stop, Swallow swallow) {
    }

    private static final List<Macro> MACROS = new CopyOnWriteArrayList<>();
    /** Run when an emergency hold starts (the auto puzzles reset themselves). */
    private static final List<Runnable> ON_EMERGENCY = new CopyOnWriteArrayList<>();
    private static volatile boolean emergencyHold = false;
    /** Whether a CLICK or MOVEMENT macro was running last tick: one starting releases the hold (he started it). */
    private static boolean userMacroWasRunning = false;
    private static volatile int emergencies = 0;

    static {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean running = false;
            for (Macro m : MACROS) {
                if (m.kind() != Kind.EMERGENCY && running(m)) {
                    running = true;
                    break;
                }
            }
            if (running && !userMacroWasRunning && emergencyHold) {
                release("a macro was started");
            }
            userMacroWasRunning = running;
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(
                (client, level) -> {
                    if (emergencyHold) {
                        release("the world changed");
                    }
                });
    }

    /** The last stop (macro name and reason) and how many macros it has stopped - testkit evidence. */
    private static volatile String lastStop = "";
    private static volatile int stops = 0;

    private MacroStop() {
    }

    /** Never swallows: the press does what it always does. */
    public static final Swallow PASS = (code, stopped, screen) -> false;
    /** Swallows the press that stopped it, so it lands on nothing (Auto E-Table: "just stop the script"). */
    public static final Swallow SWALLOW_STOP = (code, stopped, screen) -> stopped;
    /** Swallows the Emergency Cancel Key press that stopped it; Escape still closes the menu as it always did. */
    public static final Swallow SWALLOW_CANCEL_KEY = (code, stopped, screen) -> stopped && code != Keys.ESCAPE;

    public static void register(String name, Kind kind, BooleanSupplier running, Consumer<String> stop) {
        register(name, kind, running, stop, kind == Kind.MOVEMENT ? PASS : SWALLOW_CANCEL_KEY);
    }

    public static void register(String name, Kind kind, BooleanSupplier running, Consumer<String> stop, Swallow swallow) {
        MACROS.add(new Macro(name, kind, running, stop, swallow));
    }

    /** From {@code MacroStopKeyMixin}: a fresh key press (action 1). @return true to cancel it. */
    public static boolean onKey(KeyEvent event, int code) {
        if (!isCancelKey(code) && (MACROS.isEmpty() || !anyRunning())) {
            return false;
        }
        Options o = Minecraft.getInstance().options;
        boolean movement = o.keyUp.matches(event) || o.keyDown.matches(event) || o.keyLeft.matches(event)
                || o.keyRight.matches(event) || o.keyJump.matches(event);
        boolean chat = o.keyChat.matches(event) || o.keyCommand.matches(event);
        return onPress(code, KeyUtil.keyName(code), movement, chat);
    }

    /** From {@code MacroStopMouseMixin}: a fresh mouse button press. Only the Emergency Cancel Key can be one. */
    public static boolean onMouseButton(int button) {
        int code = KeyUtil.codeForMouseButton(button);
        if (!isCancelKey(code) && (MACROS.isEmpty() || !anyRunning())) {
            return false;
        }
        return onPress(code, KeyUtil.bindDisplayName(code), false, false);
    }

    /** Something to do the moment an emergency hold starts (it runs on the render thread, from the key press). */
    public static void onEmergency(Runnable action) {
        ON_EMERGENCY.add(action);
    }

    /** True while the Emergency Cancel Key's hold is on: automation acting by itself must not act. */
    public static boolean emergencyHeld() {
        return emergencyHold;
    }

    /** Emergency Cancel Key presses that started a hold (testkit). */
    public static int emergencies() {
        return emergencies;
    }

    /** Ends the hold (testkit, and the release rules). */
    public static void release(String why) {
        if (!emergencyHold) {
            return;
        }
        emergencyHold = false;
        com.killer560.hub.util.ModLog.get("killer560smod-macrostop").info("[MacroStop] emergency hold released: {}", why);
        com.killer560.hub.util.ModChat.send("Emergency Cancel", com.killer560.hub.util.ModChat.text("Automation released ("
                + why + ")."));
    }

    private static void startHold(List<String> stopped) {
        emergencyHold = true;
        emergencies++;
        for (Runnable r : ON_EMERGENCY) {
            try {
                r.run();
            } catch (RuntimeException e) {
                com.killer560.hub.util.ModLog.get("killer560smod-macrostop").error("[MacroStop] emergency action failed", e);
            }
        }
        com.killer560.hub.util.ModLog.get("killer560smod-macrostop").info("[MacroStop] emergency hold: stopped {}",
                stopped.isEmpty() ? "nothing running" : stopped);
        com.killer560.hub.util.ModChat.send("Emergency Cancel", com.killer560.hub.util.ModChat.bad("All automation stopped"),
                com.killer560.hub.util.ModChat.text(stopped.isEmpty() ? "" : " (" + String.join(", ", stopped) + ")"),
                com.killer560.hub.util.ModChat.dim(" - press the key again, change worlds or start a macro to release it."));
    }

    private static boolean isCancelKey(int code) {
        int cancelKey = MacroStopConfig.getInstance().getEmergencyCancelKeyCode();
        return cancelKey != KeyUtil.NONE && code == cancelKey;
    }

    private static boolean anyRunning() {
        for (Macro m : MACROS) {
            if (running(m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean running(Macro m) {
        try {
            return m.running().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The rule itself, without the game (the testkit drives it directly too).
     * @return true to cancel the press
     */
    public static boolean onPress(int code, String keyName, boolean movementKey, boolean chatKey) {
        Screen screen = McCompat.screen(Minecraft.getInstance());
        // A sign is not typing here: the only sign open during a macro is Bazaar Flip's amount sign, which the flip fills
        // itself, and Escape / the Emergency Cancel Key must still stop it there (testkit 2767).
        boolean typing = screen != null && !(screen instanceof AbstractContainerScreen<?>)
                && !(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen);
        int cancelKey = MacroStopConfig.getInstance().getEmergencyCancelKeyCode();
        boolean escape = !typing && code == Keys.ESCAPE;
        boolean emergency = !typing && cancelKey != KeyUtil.NONE && code == cancelKey;
        boolean worldKey = screen == null && (movementKey || chatKey);
        String why = emergency ? "the Emergency Cancel Key (" + keyName + ")"
                : escape ? "Escape" : chatKey ? "opening chat (" + keyName + ")" : "the " + keyName + " key";
        boolean cancel = false;
        List<String> stopped = new java.util.ArrayList<>();
        for (Macro m : MACROS) {
            if (!running(m)) {
                continue;
            }
            boolean stop = emergency || (m.kind() != Kind.EMERGENCY && escape)
                    || (m.kind() == Kind.MOVEMENT && worldKey);
            if (stop) {
                lastStop = m.name() + ": " + why;
                stops++;
                stopped.add(m.name());
                try {
                    m.stop().accept(why);
                } catch (RuntimeException e) {
                    com.killer560.hub.util.ModLog.get("killer560smod-macrostop").error("[MacroStop] stopping {} failed", m.name(), e);
                }
            }
            if (m.swallow().swallow(code, stop, screen)) {
                cancel = true;
            }
        }
        if (emergency) {
            if (emergencyHold && stopped.isEmpty()) {
                release("the Emergency Cancel Key again");
            } else {
                startHold(stopped);
            }
            cancel = true; // the panic button lands on nothing else
        }
        return cancel;
    }

    /** "Auto E-Table: Escape" - the last macro this stopped and why (testkit). */
    public static String lastStop() {
        return lastStop;
    }

    public static int stops() {
        return stops;
    }

    /** The names of the registered macros (testkit). */
    public static List<String> registered() {
        return MACROS.stream().map(m -> m.kind() + " " + m.name()).toList();
    }
}
