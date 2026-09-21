package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One global gate that every automated interaction in the mod passes through.
 * <p>
 * killer560 (2026-09-20): "Make sure that stuff like breaker aura, and flicking levers aren't gonna go off in the
 * same packet if that's an issue and something that could ban me", "make sure every type of aura has some sort of
 * coordination so it won't try to for instance brake blocks while I am inside of a terminal or anything like that"
 * and "if I have two levers in my range at once ... have it only pick one and then the other on the next tick".
 * <p>
 * Each feature used to carry its own {@code lastClickMs}, which stops that feature double-clicking but does nothing
 * about two different features deciding to act on the same client tick. Two interaction packets in one tick is not
 * something a hand can produce, and it is exactly the pattern server-side anti-cheat looks for. This class enforces,
 * globally:
 * <ul>
 *   <li><b>One automated interaction per client tick</b> - the first accepted claim owns the tick.</li>
 *   <li><b>A minimum spacing</b> between consecutive automated interactions ({@link #setMinSpacingTicks}).</li>
 *   <li><b>Context</b>: a {@link Kind#WORLD} actor never fires while a CONTAINER screen is open (see the
 *       WORLD case below for why only containers); a {@link Kind#SCREEN} actor
 *       only fires while the exact screen it believes it is driving is the focused one.</li>
 *   <li><b>Mutual exclusion</b> between the two classes: for {@link #CROSS_CLASS_TICKS} ticks after a GUI
 *       automation clicks, world auras stand down, and vice versa.</li>
 *   <li><b>Transition safety</b>: nothing fires in the ticks around a screen opening/closing, a world/dimension
 *       swap, or a teleport/leap (a position jump), which is where this goes wrong in practice.</li>
 *   <li><b>Deterministic priority</b>: {@link Actor} is declared highest-priority first. A lower-priority actor
 *       that saw a higher-priority actor asking on the previous tick waits (for at most
 *       {@link #MAX_YIELD_TICKS} ticks, so nothing starves) instead of stealing the slot.</li>
 * </ul>
 * This only ever <i>delays</i> an action; it never invents one, and it never changes a feature's own click delay.
 * <p>
 * Not a Fabric-API class on purpose: {@link #onClientTick(Minecraft)} is registered by
 * {@code DungeonExtrasFeature.register()} on {@code START_CLIENT_TICK}, which always runs before every
 * {@code END_CLIENT_TICK} feature regardless of registration order. Until that first observation lands
 * {@link #armed} is false and only the wall-clock spacing applies, so a missing registration can never wedge every
 * feature off.
 */
public final class ActionGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-actiongate");

    /** Ticks the other class of actor must stay quiet for after an interaction. */
    public static final int CROSS_CLASS_TICKS = 4;
    /** Ticks nothing may act for after a screen opens or closes. */
    public static final int SCREEN_SETTLE_TICKS = 3;
    /** Ticks nothing may act for after the level object is replaced (world/dimension swap, respawn). */
    public static final int WORLD_SETTLE_TICKS = 20;
    /** Ticks nothing may act for after the player is moved a long way in one tick (leap, teleport, setback). */
    public static final int TELEPORT_SETTLE_TICKS = 6;
    /** Squared blocks of single-tick movement that counts as a teleport rather than running. */
    private static final double TELEPORT_DIST_SQ = 16.0;
    /** How long a low-priority actor may be made to wait for a higher-priority one before it goes anyway. */
    public static final int MAX_YIELD_TICKS = 2;

    private static final long LOG_EVERY_MS = 5_000L;

    /** What an automated action interacts with, which decides the screen rules it has to obey. */
    public enum Kind {
        /** Blocks/entities in the world. Must never fire while any screen is open. */
        WORLD,
        /** Slots inside a container the feature is driving. Must only fire while that exact screen is focused. */
        SCREEN,
        /** A chat command. No screen rules, but still takes a slot so it can't ride along with a click. */
        COMMAND
    }

    /**
     * Every automated actor in the mod, <b>declared highest priority first</b>. When two want the same tick the
     * earlier one here wins; see {@link #shouldYield}.
     * <p>
     * Order rationale: things that lose the run if they are late come first (route execution, ultimates, terminals,
     * the boss levers), then puzzle/phase work, then the convenience auras, then the "no one dies if this is a tick
     * late" shop/farm automation.
     */
    public enum Actor {
        /** AP3 / Auto Routes / auto-clear executors. Reserved - see the notes; not wired up yet. */
        ROUTE(Kind.WORLD),
        /** Class ultimate fired off a chat trigger. Losing the tick loses the ult, so it sits near the top. */
        AUTO_ULT(Kind.WORLD),
        /** Auto Terminals solving an open terminal GUI. */
        TERMINAL_SOLVER(Kind.SCREEN),
        /** Terminal Aura / Terminal Triggerbot right-clicking the terminal block in the world. */
        TERMINAL_AURA(Kind.WORLD),
        /** Lever Aura (P3 / S2 levers). */
        LEVER_AURA(Kind.WORLD),
        /** Simon Says (Device) button clicking. */
        SIMON_SAYS(Kind.WORLD),
        /** Auto Puzzles / the boulder + water solvers clicking in the world. */
        PUZZLE_WORLD(Kind.WORLD),
        /** Auto Puzzles clicking inside a puzzle GUI. */
        PUZZLE_SCREEN(Kind.SCREEN),
        /** Goldor / Storm arrow-align triggerbot. */
        ARROW_ALIGN(Kind.WORLD),
        /** Blood camp triggerbot. */
        BLOOD_CAMP(Kind.WORLD),
        /** i4 sensor automation. */
        I4(Kind.WORLD),
        /** Mask Invincibility's death-item swap: the /stats command, the click in its menu, and the rod cast.
         *  Three actors because they are three different kinds of action and the gate's rules differ per kind. */
        MASK_SWAP_CMD(Kind.COMMAND),
        MASK_SWAP_MENU(Kind.SCREEN),
        MASK_SWAP_ROD(Kind.WORLD),
        /** Auto Door Opener. */
        DOOR_OPENER(Kind.WORLD),
        /** Secret Aura. */
        SECRET_AURA(Kind.WORLD),
        /** Secret Triggerbot. */
        SECRET_TRIGGER(Kind.WORLD),
        /** Breaker Aura. */
        BREAKER_AURA(Kind.WORLD),
        /** Auto Croesus clicking inside the Croesus menus. */
        CROESUS(Kind.SCREEN),
        /** Auto Croesus right-clicking the Croesus NPC to re-open the menu - the one world step in that flow. */
        CROESUS_NPC(Kind.WORLD),
        /** Auto Quiz / Weirdos / experiment solvers clicking in their GUI. */
        EXPERIMENTS(Kind.SCREEN),
        /** Chocolate Factory. */
        CHOCOLATE_FACTORY(Kind.SCREEN),
        /** Auto GFS (a {@code /gfs} command, not a click). */
        AUTO_GFS(Kind.COMMAND);

        private final Kind kind;

        Actor(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private static final int COUNT = Actor.values().length;

    // Settings (owned + persisted by DungeonExtrasConfig, pushed in on load/save).
    /** Fixed one-tick floor - see isEnabled()'s comment. Not a setting. */
    private static final int minSpacingTicks = 1;

    private static boolean armed = false;
    private static long tick = 0L;
    private static long claimedTick = Long.MIN_VALUE;
    private static Actor claimant = null;
    private static long lastActionNanos = Long.MIN_VALUE / 4;
    /** The spacing this particular gap has to clear, re-rolled after every accepted action (see {@link #rollSpacing}). */
    private static long spacingNanos = 0L;
    private static long lastWorldTick = Long.MIN_VALUE / 4;
    private static long lastScreenTick = Long.MIN_VALUE / 4;
    private static long settleUntilTick = Long.MIN_VALUE;
    private static long teleportSettleUntilTick = Long.MIN_VALUE;
    private static Actor selfTeleportActor = null;
    private static long selfTeleportUntilTick = Long.MIN_VALUE;

    private static final boolean[] WANTED_THIS_TICK = new boolean[COUNT];
    private static final boolean[] WANTED_LAST_TICK = new boolean[COUNT];
    private static final int[] YIELD_STREAK = new int[COUNT];
    private static final long[] LAST_LOG_MS = new long[COUNT];
    private static final String[] LAST_LOG_REASON = new String[COUNT];

    private static WeakReference<Screen> prevScreen = new WeakReference<>(null);
    private static WeakReference<ClientLevel> prevLevel = new WeakReference<>(null);
    private static Vec3 prevPos = null;

    private ActionGate() {
    }

    // ---- settings ----

    /**
     * True while a CONTAINER screen is open - the only kind of screen a world action must stand down for.
     * <p>
     * Hypixel opened that container, so it knows the menu is up, and a world interaction sent while it is up
     * is something no legitimate client produces. A purely client-side screen (this mod's menu, the HUD
     * editor, chat, the pause menu) is invisible to the server, so acting with one open is indistinguishable
     * from ordinary play. Features call this instead of testing {@code client.screen != null}, which blocked
     * far more than safety required (killer560, 2026-09-20).
     */
    public static boolean containerScreenOpen(Minecraft client) {
        return client != null && client.screen instanceof AbstractContainerScreen<?>;
    }

    // killer560, 2026-09-21, asked where the Action Gate settings should live: "Everything should by default
    // be one tick and be unchangable. Thus it wont need a tab." So there is no setting, no tab and no way to
    // switch this off - the gate is always on at a one-tick floor. That is the right call: it exists to stop
    // the mod emitting two interactions in a tick, and a switch to turn that back on is a switch to make
    // himself detectable. The jitter below stays, because it is not a preference - a flat floor would emit a
    // perfectly regular click whenever the gate is saturated, which is its own signature.
    public static boolean isEnabled() {
        return true;
    }

    public static int getMinSpacingTicks() {
        return minSpacingTicks;
    }

    /**
     * The spacing floor, plus its own 0-1 tick of jitter.
     * <p>
     * killer560's rule: "don't make it a metronome". A flat floor is harmless while a feature's own randomised
     * delay is the thing actually pacing it, but the moment something saturates the gate - a Melody lookahead
     * burst draining, two auras alternating - a fixed floor would emit interactions at exactly N x 50 ms
     * forever, which is a cleaner signature than the bursts this class was written to remove. Re-rolled after
     * every accepted action so no two consecutive gaps are the same. This never shortens anything: a feature's
     * own delay still applies on top, and the gate only ever delays.
     */
    private static long rollSpacing() {
        if (minSpacingTicks <= 0) {
            return 0L;
        }
        // Jitter is 0-20ms, not 0-50ms. A full tick of jitter on top of a one-tick floor averages 75ms,
        // which silently caps every automation at ~13 clicks/second - and killer560 had just asked Arrow
        // Align for 15-17 cps (59-67ms), a rate the gate would then have made unreachable while looking
        // like the feature's own settings were being honoured. 0-20ms still breaks up the metronome (no two
        // gaps alike) while leaving his configured rates achievable. The gate only ever delays.
        return (minSpacingTicks * 50L + ThreadLocalRandom.current().nextLong(0L, 21L)) * 1_000_000L;
    }

    // ---- per-tick observation ----

    /** Registered on {@code START_CLIENT_TICK} so it runs before every feature's {@code END_CLIENT_TICK} logic. */
    public static void onClientTick(Minecraft client) {
        armed = true;
        tick++;
        System.arraycopy(WANTED_THIS_TICK, 0, WANTED_LAST_TICK, 0, COUNT);
        Arrays.fill(WANTED_THIS_TICK, false);

        Screen screen = client.screen;
        if (screen != prevScreen.get()) {
            // The tick a terminal opens and the tick it closes are the two moments a world aura must not fire in:
            // the client still has the old screen state, the server already has the new one.
            prevScreen = new WeakReference<>(screen);
            settle(SCREEN_SETTLE_TICKS);
        }
        ClientLevel level = client.level;
        if (level != prevLevel.get()) {
            prevLevel = new WeakReference<>(level);
            prevPos = null;
            settle(WORLD_SETTLE_TICKS);
        }
        LocalPlayer player = client.player;
        if (player == null || level == null) {
            prevPos = null;
            settle(1);
            return;
        }
        Vec3 pos = player.position();
        if (prevPos != null && pos.distanceToSqr(prevPos) > TELEPORT_DIST_SQ) {
            // Leap / etherwarp / boss teleport: the world around us is being replaced, so any block we picked a
            // tick ago is stale. Acting here sends an interaction at a position the server no longer agrees with.
            // Kept separate from settleUntilTick so an actor that moved us ON PURPOSE can waive it - see
            // expectSelfTeleport; a warp chain like Ice Fill would otherwise keep tripping its own settle.
            long until = tick + TELEPORT_SETTLE_TICKS;
            if (until > teleportSettleUntilTick) {
                teleportSettleUntilTick = until;
            }
        }
        prevPos = pos;
    }

    private static void settle(int ticks) {
        long until = tick + ticks;
        if (until > settleUntilTick) {
            settleUntilTick = until;
        }
    }

    /** Forces a stand-down window on every actor, e.g. around a swap the caller knows the server hasn't seen yet. */
    public static void settleFor(int ticks) {
        settle(Math.max(0, ticks));
    }

    /**
     * Declares that {@code actor} has just moved the player itself (an Ice Fill / Reposition warp), so the
     * position jump the next tick observes is its own doing and not a leap it has to stand down for.
     * <p>
     * Without this the warp solvers deadlock against their own teleport window: Ice Fill's default is a warp
     * every 2 ticks, {@link #TELEPORT_SETTLE_TICKS} is 6, so every hop would wait three times as long as the
     * setting says. The waiver is narrow on purpose - one actor, a handful of ticks - so a real leap in the
     * middle of a puzzle still settles every other actor, and still settles this one once the window lapses.
     */
    public static void expectSelfTeleport(Actor actor) {
        selfTeleportActor = actor;
        selfTeleportUntilTick = tick + 3;
    }

    // ---- the gate ----

    /** For {@link Kind#WORLD} and {@link Kind#COMMAND} actors. */
    public static boolean tryAct(Actor actor) {
        return tryAct(actor, null);
    }

    /**
     * Ask for this tick's single automated interaction.
     *
     * @param ownScreen for a {@link Kind#SCREEN} actor, the screen it believes it is driving. The gate refuses
     *                  unless that is literally the focused screen and a container screen, so a GUI clicker can
     *                  never fire because some unrelated menu happens to be open. Ignored otherwise.
     * @return true exactly once per client tick, mod-wide. Callers must not act when this is false; they should
     * simply try again next tick.
     */
    public static boolean tryAct(Actor actor, Screen ownScreen) {
        WANTED_THIS_TICK[actor.ordinal()] = true;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return false;
        }
        Screen screen = client.screen;
        switch (actor.kind) {
            case WORLD -> {
                // Only a CONTAINER screen blocks a world action, not every screen (killer560, 2026-09-20:
                // "delete the in menus setting but let stuff still work in things like our mod menu").
                // The distinction is what the SERVER can see. Hypixel opened the container, so it knows that
                // menu is up, and a world interaction while it is up is something no real client can send -
                // a hard tell. The player's own inventory counts too: closing it sends a container-close for
                // id 0, so that state is server-visible as well. A purely client-side screen - this mod's
                // menu, the HUD editor, chat, the pause menu, Termism - is invisible to the server, so acting
                // with one open looks exactly like ordinary play on the wire and blocking it bought nothing.
                if (screen instanceof AbstractContainerScreen<?>) {
                    return deny(actor, "a container screen is open");
                }
                if (armed && tick - lastScreenTick < CROSS_CLASS_TICKS) {
                    return deny(actor, "a GUI automation just clicked");
                }
            }
            case SCREEN -> {
                if (ownScreen == null || screen != ownScreen) {
                    return deny(actor, "its own screen is not the focused one");
                }
                if (!(screen instanceof AbstractContainerScreen<?>)) {
                    return deny(actor, "focused screen is not a container");
                }
                if (armed && tick - lastWorldTick < CROSS_CLASS_TICKS) {
                    return deny(actor, "a world aura just clicked");
                }
            }
            case COMMAND -> {
                // No screen rules - a command is legal with a menu open - but it still takes the tick's slot.
            }
        }
        if (armed && tick < settleUntilTick) {
            return deny(actor, "settling after a screen/world transition");
        }
        if (armed && tick < teleportSettleUntilTick
                && !(actor == selfTeleportActor && tick <= selfTeleportUntilTick)) {
            return deny(actor, "settling after a teleport");
        }
        if (armed && claimedTick == tick) {
            return deny(actor, "this tick is already taken by " + claimant);
        }
        long now = System.nanoTime();
        if (now - lastActionNanos < spacingNanos) {
            return deny(actor, "min spacing");
        }
        if (armed && shouldYield(actor)) {
            return deny(actor, "yielding one tick to a higher-priority action");
        }
        claimedTick = tick;
        claimant = actor;
        lastActionNanos = now;
        spacingNanos = rollSpacing();
        YIELD_STREAK[actor.ordinal()] = 0;
        if (actor.kind == Kind.WORLD) {
            lastWorldTick = tick;
        } else if (actor.kind == Kind.SCREEN) {
            lastScreenTick = tick;
        }
        return true;
    }

    /**
     * Deterministic tie-break. If anything strictly higher priority asked on the previous tick, this actor waits so
     * the higher-priority one gets the slot regardless of which feature's tick handler happens to run first.
     * Capped at {@link #MAX_YIELD_TICKS} consecutive ticks so a chatty high-priority actor can't starve anyone.
     */
    private static boolean shouldYield(Actor actor) {
        int self = actor.ordinal();
        if (YIELD_STREAK[self] >= MAX_YIELD_TICKS) {
            return false;
        }
        for (int i = 0; i < self; i++) {
            if (WANTED_LAST_TICK[i]) {
                YIELD_STREAK[self]++;
                return true;
            }
        }
        YIELD_STREAK[self] = 0;
        return false;
    }

    private static boolean deny(Actor actor, String reason) {
        int i = actor.ordinal();
        long now = System.currentTimeMillis();
        if (!reason.equals(LAST_LOG_REASON[i]) || now - LAST_LOG_MS[i] > LOG_EVERY_MS) {
            LAST_LOG_REASON[i] = reason;
            LAST_LOG_MS[i] = now;
            LOGGER.debug("[ActionGate] {} held back: {}.", actor, reason);
        }
        return false;
    }

    /** Wipes the per-tick bookkeeping (world change, feature disabled, /ap3 stop...). Settings are untouched. */
    public static void reset() {
        claimedTick = Long.MIN_VALUE;
        claimant = null;
        lastActionNanos = Long.MIN_VALUE / 4;
        spacingNanos = rollSpacing();
        lastWorldTick = Long.MIN_VALUE / 4;
        lastScreenTick = Long.MIN_VALUE / 4;
        teleportSettleUntilTick = Long.MIN_VALUE;
        selfTeleportActor = null;
        selfTeleportUntilTick = Long.MIN_VALUE;
        Arrays.fill(WANTED_THIS_TICK, false);
        Arrays.fill(WANTED_LAST_TICK, false);
        Arrays.fill(YIELD_STREAK, 0);
    }
}
