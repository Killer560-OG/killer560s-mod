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
 * A macro's own Start/Stop key stays its own business (each feature polls it). Auto Routes and AP3 are not here: their
 * stop rules are their own (docs/AP3.md, docs/LESSONS-AUTOMATION.md). Only presses count, never releases or repeats,
 * so a key held from before a start never stops it.
 */
public final class MacroStop {

    public enum Kind { CLICK, MOVEMENT }

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
        if (MACROS.isEmpty() || !anyRunning()) {
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
        if (MACROS.isEmpty() || !anyRunning()) {
            return false;
        }
        int code = KeyUtil.codeForMouseButton(button);
        return onPress(code, KeyUtil.bindDisplayName(code), false, false);
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
        for (Macro m : MACROS) {
            if (!running(m)) {
                continue;
            }
            boolean stop = escape || emergency || (m.kind() == Kind.MOVEMENT && worldKey);
            if (stop) {
                lastStop = m.name() + ": " + why;
                stops++;
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
