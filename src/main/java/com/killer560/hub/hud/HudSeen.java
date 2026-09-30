package com.killer560.hub.hud;

import java.util.HashMap;
import java.util.Map;

/**
 * "When did this HUD element last actually draw?" - the one source of truth the HUD position editor uses to
 * decide what may be dragged.
 * <p>
 * killer560 (2026-09-30): "lets say I am doing nothing and the only gui element on my screen is the real
 * time. I am in an area where i could open the etable to see its gui but im not there right now. Then I
 * should only be able to edit the time element. There should be a 10 second grace window. So any hud that
 * has been on my screen in the last 10s i should be able to edit."
 * <p>
 * The honest answer to "was it on screen?" is the draw call itself, so every element stamps itself from
 * inside its own real render path, past all of its own gates - not from a second "should this be visible?"
 * predicate evaluated in the editor, which is a different opinion that drifts from the first one the moment
 * a render path grows a condition the predicate does not know about. (It already had: {@code SplitTimers}'
 * old {@code isRelevantNow} tested {@code isInDungeon()} and its {@code render} did not.) The only thing
 * still asked of the element directly is {@link HudElement#isEnabledInSettings()}, which is the setting
 * toggle and nothing else.
 * <p>
 * Opening the editor is deliberately NOT a draw: {@link #markDrawn} ignores every stamp while
 * {@link HudEditorScreen} is the open screen, because the editor calls {@code render()} on each listed
 * element to draw its preview and that preview must not be what keeps the element listed next time.
 */
public final class HudSeen {

    /**
     * How long after an element last drew it stays editable in the HUD position editor. killer560 asked
     * for ten seconds; this is the only place that number lives.
     */
    public static final long GRACE_MS = 10_000L;

    /** id -> a one-element long array holding the last draw's {@code System.currentTimeMillis()}. A mutable
     *  slot rather than a {@code Map<String, Long>} because {@link #markDrawn} runs once per drawing element
     *  per frame, and re-boxing a Long there would allocate on every frame of every HUD (same reason
     *  {@code HudConfig.getPosition} stopped using {@code getOrDefault}). */
    private static final Map<String, long[]> LAST_DRAWN = new HashMap<>();

    /** See {@link #markHudFrame}. */
    private static long lastLiveFrameMs = 0L;

    private HudSeen() {
    }

    /**
     * Called once per frame from {@link HudInGameRenderer}, past its own gates: the HUD layer is live right
     * now, so the grace clock is running.
     * <p>
     * It has to be a separate clock from wall time because the "Edit HUD Positions" button lives on a
     * settings screen, and no HUD draws while a screen is open. Measured against wall time, browsing settings
     * for eleven seconds and then pressing that button would have shown him an editor with nothing in it -
     * which is the failure this whole change exists to avoid. So a HUD that is not drawing because something
     * is covering it does not age; only time he spent with the HUD actually live counts against the window.
     */
    public static void markHudFrame() {
        lastLiveFrameMs = System.currentTimeMillis();
    }

    /** The moment the HUD was last live, which is what the editor measures its grace window from. Falls back
     *  to now if the HUD has never drawn this session (nothing will be within grace either way). */
    public static long lastLiveFrameMs() {
        return lastLiveFrameMs == 0L ? System.currentTimeMillis() : lastLiveFrameMs;
    }

    /**
     * Called by an element from inside its own draw path, at the point it has committed to putting pixels on
     * screen - past the feature's enabled check, past its location/phase gates, and past any "nothing to show
     * right now" early-out. Stamping earlier than that would make an element that renders nothing look seen.
     */
    public static void markDrawn(String id) {
        if (id == null || HudVisibility.editorOpen()) {
            return;
        }
        long[] slot = LAST_DRAWN.get(id);
        if (slot == null) {
            slot = new long[1];
            LAST_DRAWN.put(id, slot);
        }
        slot[0] = System.currentTimeMillis();
    }

    /** True once this element has drawn at least once this session. Distinguishes "you have not been
     *  anywhere that shows it" from "it is switched off", which the editor tells the player apart. */
    public static boolean everDrawn(String id) {
        return LAST_DRAWN.containsKey(id);
    }

    /** Milliseconds since the element last drew, or {@link Long#MAX_VALUE} if it never has. */
    public static long msSince(String id, long asOfMs) {
        long[] slot = LAST_DRAWN.get(id);
        return slot == null ? Long.MAX_VALUE : asOfMs - slot[0];
    }

    /** Whether the element had drawn within {@link #GRACE_MS} of {@code asOfMs}. The editor passes the moment
     *  it was opened, not "now": nothing draws while the editor is up, so measuring against a running clock
     *  would quietly empty the list ten seconds after he opened it. */
    public static boolean drawnWithinGrace(String id, long asOfMs) {
        return msSince(id, asOfMs) <= GRACE_MS;
    }

    /** Live version of {@link #drawnWithinGrace} for callers outside the editor (currently the clamp memo in
     *  {@link HudElementRegistry}, which skips re-measuring an element that is not drawing). */
    public static boolean drawnRecently(String id) {
        return drawnWithinGrace(id, System.currentTimeMillis());
    }

    /** Drops an element's stamp - paired with {@link HudElementRegistry#unregister}, so a GIF that is
     *  removed and a different one later registered under the same id cannot inherit its "seen" time. */
    public static void forget(String id) {
        LAST_DRAWN.remove(id);
    }
}
