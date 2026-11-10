package com.killer560.hub.terminals;

import com.killer560.hub.compat.McCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stream Safe's human cursor (killer560, 2026-10-19, 2026-10-26, rebuilt 2026-10-27: "make the auto terminals mouse look as
 * human as possible. So some misses and things and stuff like that and fully human pathfinding").
 * <p>
 * Shown only with Auto Terminals' Stream Safe on, and then it is the clock: a click goes out only once the cursor has
 * arrived on its slot and "pressed" ({@link #ready}). With Stream Safe off nothing is drawn and the Min/Max delays rule.
 * <ul>
 *   <li><b>Where:</b> targets are the slot AS DRAWN ({@link TerminalSolverFeature#slotCenterGui}: the Custom GUI panel's
 *       layout, or the vanilla slot), and the landing point is spread a little round the center like a hand's.</li>
 *   <li><b>Look:</b> the classic Windows arrow at the real pointer's size (window pixels times the monitor's content
 *       scale); the real pointer is hidden while it shows. No click feedback of its own: a real click draws none.</li>
 *   <li><b>Each move</b> is a plan of short segments, rebuilt from wherever the cursor is when the target changes, so it
 *       never jumps: a reaction pause (log-normal round Reaction Time), sometimes a hesitation, then a curved move
 *       (cubic Bezier with curvature that varies move to move and leans one way per terminal) at minimum-jerk speed for a
 *       Fitts-law duration with per-move variance. Sometimes the move overshoots past the slot and comes back; sometimes
 *       it misses (lands at the slot's edge or on a neighbor), pauses and corrects; on a long move it sometimes stops
 *       part way for a second look. Every plan ends ON the slot. Since 2026-11-01 a new target bends the motion
 *       already under way (velocity carries over), an approach lands still gliding, the press goes out a moment after
 *       the cursor is on the slot for good, and the hand settles inside the slot after it; no tremor (it read as jitter).</li>
 *   <li><b>The same slot again</b> (a Rubix pane needing 2) is known before the first click, so the cursor stays and
 *       clicks again after a double-click-like gap; it never leaves and comes back.</li>
 *   <li>Per-terminal randomness (speed, reaction, curvature lean) so two terminals never look the same, all bounded.</li>
 * </ul>
 * No allocation per frame: the plan is a fixed set of arrays.
 * <p>
 * It works on a container terminal and on any {@link Host} screen (Termism's practice board, through
 * {@link PracticeCursor}, since 2026-11-02), which aims through its own {@link SlotLocator}.
 */
public final class FakeCursor {

    /** Fitts' law, MacKenzie's form: A + B * log2(D / W + 1), ms at 100% speed. */
    private static final float FITTS_A_MS = 50f;
    private static final float FITTS_B_MS = 90f;
    private static final float MIN_MOVE_MS = 55f;

    /** The classic Windows arrow, 12x19: B black edge, W white fill, . clear. */
    private static final String[] ARROW = {
            "B...........",
            "BB..........",
            "BWB.........",
            "BWWB........",
            "BWWWB.......",
            "BWWWWB......",
            "BWWWWWB.....",
            "BWWWWWWB....",
            "BWWWWWWWB...",
            "BWWWWWWWWB..",
            "BWWWWWWWWWB.",
            "BWWWWWWBBBBB",
            "BWWWBWWB....",
            "BWWBBWWB....",
            "BWB..BWWB...",
            "BB...BWWB...",
            "B.....BWWB..",
            "......BWWB..",
            ".......BB...",
    };
    private static final int[][] RUNS = buildRuns();

    // ---- the plan: segments in time order; a hold has start == end ----
    private static final int MAX_SEGMENTS = 12;
    private static final long[] SEG_START = new long[MAX_SEGMENTS];
    private static final float[] SEG_DUR = new float[MAX_SEGMENTS];
    private static final float[] SX = new float[MAX_SEGMENTS];
    private static final float[] SY = new float[MAX_SEGMENTS];
    private static final float[] C1X = new float[MAX_SEGMENTS];
    private static final float[] C1Y = new float[MAX_SEGMENTS];
    private static final float[] C2X = new float[MAX_SEGMENTS];
    private static final float[] C2Y = new float[MAX_SEGMENTS];
    private static final float[] EX = new float[MAX_SEGMENTS];
    private static final float[] EY = new float[MAX_SEGMENTS];
    /** Time map of each segment: a quintic with s(0)=0, s(1)=1, no acceleration at either end, and these normalized
     *  speeds at the start and the end. 0/0 is the minimum-jerk profile; a move that starts while the hand is already
     *  moving carries that speed in (A), and an approach arrives still moving a little (B) so the hand glides on. */
    private static final float[] SEG_A = new float[MAX_SEGMENTS];
    private static final float[] SEG_B = new float[MAX_SEGMENTS];
    private static int segCount;
    private static long planEndMs;
    /** The press: when the click may go out (the cursor has been on the slot a moment), and whether it is a repeat. */
    private static long clickAtMs;
    /** Build cursor while planning: where the next segment starts, when, and the hand's velocity there (px/ms). */
    private static float bx;
    private static float by;
    private static long bt;
    private static float bvx;
    private static float bvy;

    private static boolean placed;
    private static float curX;
    private static float curY;
    private static int aimedSlot = -1;
    private static float aimedX;
    private static float aimedY;
    private static float aimedHalf;
    private static boolean firstMove;
    private static boolean hidden;
    private static boolean showing;
    private static long lastShownMs;
    /** The next click on the aimed slot is followed by another on it (a Rubix pane needing 2): keep the hand on it. */
    private static boolean repeatNext;

    private static float sessionSpeed = 1f;
    private static float sessionReaction = 1f;
    private static float sessionLean;

    // ---- testkit probes (280-289): read by reflection, nothing in the mod reads them ----
    static float startX = -1f;
    static float startY = -1f;
    static int frames;
    static float maxFramePx;
    static float maxPxPerMs;
    static int clickCount;
    static final int[] CLICK_SLOT = new int[256];
    static final float[] CLICK_X = new float[256];
    static final float[] CLICK_Y = new float[256];
    static final float[] CLICK_TX = new float[256];
    static final float[] CLICK_TY = new float[256];
    static final float[] CLICK_HALF = new float[256];
    static int clicksWaited;
    static int moves;
    static int misses;
    static int overshoots;
    static int doubleTakes;
    static int hesitations;
    static int repeats;
    /** Window px the cursor travelled in the 150 ms after each click (summed): the glide a hand keeps after pressing. */
    static float glideAfterClickPx;
    /** ms from the terminal opening to its first Stream Safe click, -1 until then. */
    static long firstClickAfterOpenMs = -1;
    private static long openedAtMs;
    private static long lastClickMs;
    /** Farthest the cursor got from a slot between two clicks on that same slot (window px). */
    static float repeatMaxPx;
    private static boolean inRepeat;
    private static float lastFrameX = Float.NaN;
    private static float lastFrameY = Float.NaN;
    private static long lastFrameMs;

    private static final float[] SCRATCH = new float[3];
    private static final float[] SCRATCH2 = new float[2];

    private FakeCursor() {
    }

    /** Where a slot is drawn, in GUI units: {@code out[0]}/{@code out[1]} the center, {@code out[2]} the drawn cell width.
     *  @return false when the slot cannot be placed. */
    @FunctionalInterface
    public interface SlotLocator {
        boolean locate(int slot, float[] out);
    }

    /** A screen that is not a container but hosts a terminal the cursor works on (Termism's practice board, 2026-11-02:
     *  "auto terms stream safe doesnt show in termism"). It gets the same drawing and pointer hiding a container gets. */
    public interface Host {
    }

    private static boolean hosts(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof AbstractContainerScreen<?> || screen instanceof Host;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FakeCursor::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!hosts(screen)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
                try {
                    render(graphics);
                } catch (RuntimeException e) {
                    // Cosmetic only: a bad frame must never reach the screen or the auto click.
                    showing = false;
                }
            });
        });
    }

    /** @return whether the cursor is in use (Stream Safe on and Auto Terminals clicking this terminal). */
    static boolean active() { return showing; }

    /** A terminal opened (not a quick reopen of the same one): a fresh hand, from the window center. */
    static void beginTerminal() {
        placed = false;
        aimedSlot = -1;
        segCount = 0;
        planEndMs = 0;
        firstMove = true;
        inRepeat = false;
        repeatNext = false;
        clickAtMs = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        sessionSpeed = clamp(1f + 0.08f * (float) r.nextGaussian(), 0.85f, 1.18f);
        sessionReaction = clamp(1f + 0.12f * (float) r.nextGaussian(), 0.8f, 1.25f);
        sessionLean = (r.nextBoolean() ? 1f : -1f) * (0.02f + 0.06f * r.nextFloat());
        startX = -1f;
        startY = -1f;
        frames = 0;
        maxFramePx = 0f;
        maxPxPerMs = 0f;
        clickCount = 0;
        clicksWaited = 0;
        repeatMaxPx = 0f;
        glideAfterClickPx = 0f;
        firstClickAfterOpenMs = -1;
        openedAtMs = System.currentTimeMillis();
        lastClickMs = 0;
        lastFrameX = Float.NaN;
        lastFrameY = Float.NaN;
        HumanClickOrder.begin();
    }

    /** Every frame a terminal is tracked: whether Stream Safe Auto Terminals is driving it. */
    static void setShowing(boolean show) {
        showing = show;
        if (show) {
            lastShownMs = System.currentTimeMillis();
            ensurePlaced();
        }
    }

    private static void ensurePlaced() {
        if (placed) return;
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) return;
        curX = client.getWindow().getWidth() / 2f;
        curY = client.getWindow().getHeight() / 2f;
        segCount = 0;
        planEndMs = 0;
        startX = curX;
        startY = curY;
        placed = true;
    }


    /** Heads for {@code slot}; a no-op while already headed there. */
    static void aim(AbstractContainerScreen<?> screen, int slot) { aim(screen, slot, false); }

    /** Melody's row button (killer560, 2026-11-01: "for melody have it insta go to the right side on the nodes to click.
     *  Wether it is time or not"): straight there, no reaction pause, no miss or overshoot, quicker than a normal move, so
     *  the hand is already resting on the button when the row comes due. */
    static void aimQuick(AbstractContainerScreen<?> screen, int slot) { aim(screen, slot, true); }

    private static void aim(AbstractContainerScreen<?> screen, int slot, boolean quick) {
        if (!showing || slot == aimedSlot) return;
        aim((s, out) -> TerminalSolverFeature.slotCenterGui(screen, s, out), slot, quick);
    }

    /** {@link #aim(AbstractContainerScreen, int)} for a board that is not a container (Termism). */
    static void aim(SlotLocator locator, int slot) { aim(locator, slot, false); }

    /** {@link #aimQuick(AbstractContainerScreen, int)} for a board that is not a container (Termism). */
    static void aimQuick(SlotLocator locator, int slot) { aim(locator, slot, true); }

    private static void aim(SlotLocator locator, int slot, boolean quick) {
        if (!showing || slot == aimedSlot) return;
        if (!locator.locate(slot, SCRATCH)) return;
        ensurePlaced();
        long now = System.currentTimeMillis();
        // Where the hand is and how fast it is going right now: a new target bends the motion already under way into an
        // arc toward it instead of stopping and starting again.
        float vx = 0f;
        float vy = 0f;
        if (segCount > 0 && now < planEndMs) {
            evalAt(now - 8, SCRATCH2);
            float px = SCRATCH2[0];
            float py = SCRATCH2[1];
            evalAt(now, SCRATCH2);
            vx = (SCRATCH2[0] - px) / 8f;
            vy = (SCRATCH2[1] - py) / 8f;
        }
        position(now);
        double gui = Minecraft.getInstance().getWindow().getGuiScale();
        aimedSlot = slot;
        aimedX = (float) (SCRATCH[0] * gui);
        aimedY = (float) (SCRATCH[1] * gui);
        aimedHalf = (float) Math.max(2.0, SCRATCH[2] * gui / 2.0);
        inRepeat = false;
        repeatNext = false;
        plan(now, vx, vy, quick);
    }

    /**
     * One move, rebuilt from where the hand is (and how it is moving) whenever the target changes (killer560, 2026-11-01:
     * "When a human clicks they may click it the first pixel they are on but their mouse will keep moving for a fraction of
     * a second and make more of an arcing motion. It should click things close together faster and things far away
     * longer"). Fitts-law duration (near is quick, far is longer), a curved path that leaves in the direction the hand was
     * already going, an arrival that is still gliding, and a short follow-through that settles inside the slot. The press
     * comes a moment after the cursor is on the slot for good, not after it has stopped.
     */
    private static void plan(long now, float vx, float vy, boolean quick) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        float speed = cfg.getFakeCursorSpeed() / 100f;
        float cell = aimedHalf * 2f;
        segCount = 0;
        bx = curX;
        by = curY;
        bt = now;
        bvx = vx;
        bvy = vy;
        boolean moving = Math.hypot(vx, vy) > 0.05;

        // A reaction only from rest: a hand already moving toward the board re-aims on the way.
        if (!quick && !moving) {
            float reaction = logNormal(r, cfg.getCursorReactionMs() * sessionReaction, 0.28f, 0.45f, 2.2f);
            if (firstMove) {
                // The first look at a fresh board: the terminal's opening settle already covers most of it.
                reaction *= 0.6f;
            } else if (clickCount > 1 && r.nextFloat() < 0.06f) {
                // (never on the move after the FIRST click: a pre-aimed first click followed by a long pause read as a stall)
                reaction += 150f + 230f * r.nextFloat();
                hesitations++;
            }
            hold(reaction);
        }
        firstMove = false;

        // Where the hand lands: round the center, inside the slot.
        float lx = aimedX + clamp((float) r.nextGaussian() * 0.12f * cell, -0.3f * cell, 0.3f * cell);
        float ly = aimedY + clamp((float) r.nextGaussian() * 0.12f * cell, -0.3f * cell, 0.3f * cell);
        float dx = lx - bx;
        float dy = ly - by;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int approach = -1;
        if (dist >= 1f) {
            moves++;
            if (!quick && clickCount > 1 && dist > 3.2f * cell && r.nextFloat() < 0.12f) {
                // A second look on a long move: stop part way (a little off the line), then go on.
                float f = 0.4f + 0.2f * r.nextFloat();
                float off = (r.nextFloat() - 0.5f) * 0.3f * dist;
                float mx = bx + dx * f - dy / dist * off;
                float my = by + dy * f + dx / dist * off;
                move(mx, my, cell, speed, r, 0.95f, 0f);
                hold(110f + 150f * r.nextFloat());
                doubleTakes++;
            }
            float roll = quick ? 100f : r.nextFloat() * 100f;
            if (roll < cfg.getCursorMissChance()) {
                // A miss: the move ends at the slot's edge or on a neighbor, a corrective pause, then onto the slot.
                double a = r.nextDouble() * Math.PI * 2.0;
                float err = (0.55f + 0.6f * r.nextFloat()) * cell;
                move(lx + (float) Math.cos(a) * err, ly + (float) Math.sin(a) * err, cell, speed, r, 1f, 0f);
                hold(logNormal(r, 170f, 0.25f, 0.6f, 1.6f));
                approach = move(lx, ly, cell, speed, r, 1.1f, glide(r));
                misses++;
            } else if (roll < cfg.getCursorMissChance() + cfg.getCursorOvershootChance()) {
                // An overshoot: carried past the slot along the move, a beat, then back onto it.
                float ux = (lx - bx) / Math.max(1f, (float) Math.hypot(lx - bx, ly - by));
                float uy = (ly - by) / Math.max(1f, (float) Math.hypot(lx - bx, ly - by));
                float past = (0.35f + 0.6f * r.nextFloat()) * cell;
                move(lx + ux * past, ly + uy * past, cell, speed, r, 1f, 0f);
                hold(40f + 90f * r.nextFloat());
                approach = move(lx, ly, cell, speed, r, 1.15f, glide(r));
                overshoots++;
            } else {
                approach = move(lx, ly, cell, speed, r, quick ? 0.6f : 1f, glide(r));
            }
        }
        long landMs = bt;
        followThrough(r);
        planEndMs = bt;
        clickAtMs = pressTime(approach, landMs, r);
    }

    /** How fast an approach is still going as it lands, as a share of an even pace: a hand glides onto a target. */
    private static float glide(ThreadLocalRandom r) { return 0.15f + 0.2f * r.nextFloat(); }

    /** The glide after landing: the hand runs on in the direction it was going and slows to a stop, never leaving the slot
     *  (the click may still be on its way, and a Rubix pane's second click needs the hand still on it). */
    private static void followThrough(ThreadLocalRandom r) {
        float v = (float) Math.hypot(bvx, bvy);
        if (v < 0.01f) return;
        float ms = 60f + 70f * r.nextFloat();
        float limit = aimedHalf * 0.85f;
        float k = 1f;
        for (int i = 0; i < 8; i++) {
            float ex = bx + bvx * k * ms / 2f;
            float ey = by + bvy * k * ms / 2f;
            if (Math.hypot(ex - aimedX, ey - aimedY) <= limit) break;
            k *= 0.5f;
        }
        float ex = bx + bvx * k * ms / 2f;
        float ey = by + bvy * k * ms / 2f;
        if (k < 0.05f || Math.hypot(ex - aimedX, ey - aimedY) > limit) {
            bvx = 0f;
            bvy = 0f;
            return;
        }
        add(bx, by, bx + bvx * k * ms / 3f, by + bvy * k * ms / 3f, ex, ey, ex, ey, ms, 1f, 0f);
        bvx = 0f;
        bvy = 0f;
    }

    /** When the press goes out: a moment after the approach has brought the cursor onto the slot for good (the first
     *  pixels on it, not the stop), never later than the end of the glide. */
    private static long pressTime(int approach, long landMs, ThreadLocalRandom r) {
        long press = (long) (10f + 35f * r.nextFloat());
        if (approach < 0) return Math.min(bt, landMs + press + 20);
        long t0 = SEG_START[approach];
        long t1 = t0 + (long) Math.ceil(SEG_DUR[approach]);
        long enter = t1;
        for (long t = t1; t >= t0; t -= 2) {
            evalAt(t, SCRATCH2);
            if (!insideAimed(SCRATCH2[0], SCRATCH2[1], 0.8f)) break;
            enter = t;
        }
        return Math.min(Math.max(enter + press, t0), bt);
    }

    private static boolean insideAimed(float x, float y, float share) {
        float lim = aimedHalf * share;
        return Math.abs(x - aimedX) <= lim && Math.abs(y - aimedY) <= lim;
    }

    /** Another click on the slot the cursor is already on: a double-click-like gap, the hand still on it. */
    private static void planRepeat(long now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        clickAtMs = now + (long) logNormal(r, 125f, 0.25f, 0.6f, 1.9f);
        inRepeat = true;
        repeats++;
    }

    private static void hold(float ms) {
        add(bx, by, bx, by, bx, by, bx, by, ms, 0f, 0f);
        bvx = 0f;
        bvy = 0f;
    }

    /** @return the index of the segment added, or -1. Leaves {@link #bvx}/{@link #bvy} at the speed it arrives with. */
    private static int move(float tx, float ty, float cell, float speed, ThreadLocalRandom r, float durScale, float endSpeed) {
        float dx = tx - bx;
        float dy = ty - by;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.5f) return -1;
        float fitts = FITTS_A_MS + FITTS_B_MS * (float) (Math.log(dist / Math.max(4f, cell) + 1.0) / Math.log(2.0));
        float variance = clamp(1f + 0.12f * (float) r.nextGaussian(), 0.75f, 1.35f);
        float dur = Math.max(MIN_MOVE_MS / Math.max(1f, speed), fitts * variance * sessionSpeed * durScale / speed);
        // Curvature: leans one way for this terminal, varies move to move.
        float k = clamp(sessionLean + 0.07f * (float) r.nextGaussian(), -0.2f, 0.2f);
        float px = -dy;
        float py = dx;
        float k2 = k * (0.3f + 0.6f * r.nextFloat());
        float c2x = bx + dx * 0.72f + px * k2;
        float c2y = by + dy * 0.72f + py * k2;
        float c1x;
        float c1y;
        float a = 0f;
        float v0 = (float) Math.hypot(bvx, bvy);
        if (v0 > 0.01f) {
            // Leave the way the hand is already going: the first control point along the current velocity, with the time
            // map's start speed set so the velocity carries over exactly. Kept within half the move.
            float lead = v0 * dur / 3f;
            float cap = 0.5f * dist;
            float scale = lead > cap ? cap / lead : 1f;
            c1x = bx + bvx * dur / 3f * scale;
            c1y = by + bvy * dur / 3f * scale;
            a = clamp(1f / scale, 0f, 2.5f);
        } else {
            float k1 = k * (0.6f + 0.6f * r.nextFloat());
            c1x = bx + dx * 0.3f + px * k1;
            c1y = by + dy * 0.3f + py * k1;
        }
        int i = add(bx, by, c1x, c1y, c2x, c2y, tx, ty, dur, a, endSpeed);
        // Arrival velocity: B'(1) * s'(1) / dur, with B'(1) = 3 (end - c2).
        bvx = 3f * (tx - c2x) * endSpeed / dur;
        bvy = 3f * (ty - c2y) * endSpeed / dur;
        return i;
    }

    private static int add(float sx, float sy, float c1x, float c1y, float c2x, float c2y, float ex, float ey, float ms,
                           float startSpeed, float endSpeed) {
        if (segCount >= MAX_SEGMENTS) return -1;
        int i = segCount++;
        SEG_START[i] = bt;
        SEG_DUR[i] = ms;
        SX[i] = sx;
        SY[i] = sy;
        C1X[i] = c1x;
        C1Y[i] = c1y;
        C2X[i] = c2x;
        C2Y[i] = c2y;
        EX[i] = ex;
        EY[i] = ey;
        SEG_A[i] = startSpeed;
        SEG_B[i] = endSpeed;
        bt += (long) Math.ceil(ms);
        bx = ex;
        by = ey;
        return i;
    }


    /** @return true when the cursor is on {@code slot} and its press is due - the click may go out now. */
    static boolean ready(int slot) {
        if (!showing) return true;
        long now = System.currentTimeMillis();
        boolean arrived = slot == aimedSlot && placed && now >= clickAtMs;
        if (arrived) {
            position(now);
            arrived = insideAimed(curX, curY, 1f) || now >= planEndMs;
        }
        if (!arrived) {
            clicksWaited++;
        }
        return arrived;
    }

    /** The click about to go out on the aimed slot is followed by another on the same slot (a Rubix pane needing 2+). */
    static void expectRepeat(boolean repeat) { repeatNext = repeat; }

    /** A click on {@code slot} went out. The hand keeps gliding; a repeat on the same slot waits for a fresh press gap. */
    static void clicked(int slot) {
        if (!showing) return;
        long now = System.currentTimeMillis();
        position(now);
        int i = clickCount % CLICK_SLOT.length;
        CLICK_SLOT[i] = slot;
        CLICK_X[i] = curX;
        CLICK_Y[i] = curY;
        CLICK_TX[i] = aimedX;
        CLICK_TY[i] = aimedY;
        CLICK_HALF[i] = aimedHalf;
        clickCount++;
        com.killer560.hub.util.ModLog.get("killer560smod-autoterminal").info("[FakeCursor] click #{} slot {} {} ms after the last (hand plan: {} segments, misses {}, double takes {}, hesitations {}) tick={}",
                clickCount, slot, lastClickMs > 0 ? now - lastClickMs : -1L, segCount, misses, doubleTakes, hesitations,
                Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : -1L);
        if (firstClickAfterOpenMs < 0) {
            firstClickAfterOpenMs = now - openedAtMs;
        }
        lastClickMs = now;
        if (slot == aimedSlot) {
            if (repeatNext) {
                planRepeat(now);
            } else {
                // Not this slot again before a fresh press, should anything ask (a retry of an unanswered click).
                clickAtMs = now + 120;
            }
        }
        repeatNext = false;
    }


    /** The plan's point at {@code t} into {@code out[0..1]} (the last point once the plan has ended). */
    private static void evalAt(long t, float[] out) {
        if (segCount == 0) {
            out[0] = curX;
            out[1] = curY;
            return;
        }
        int i = 0;
        while (i < segCount - 1 && t >= SEG_START[i] + (long) Math.ceil(SEG_DUR[i])) {
            i++;
        }
        float u0 = SEG_DUR[i] <= 0f ? 1f : clamp((t - SEG_START[i]) / SEG_DUR[i], 0f, 1f);
        float s = timeMap(u0, SEG_A[i], SEG_B[i]);
        float u = 1f - s;
        out[0] = u * u * u * SX[i] + 3f * u * u * s * C1X[i] + 3f * u * s * s * C2X[i] + s * s * s * EX[i];
        out[1] = u * u * u * SY[i] + 3f * u * u * s * C1Y[i] + 3f * u * s * s * C2Y[i] + s * s * s * EY[i];
    }

    /** Quintic Hermite: s(0)=0, s(1)=1, s'(0)=a, s'(1)=b, s''(0)=s''(1)=0. a=b=0 is minimum jerk. */
    private static float timeMap(float t, float a, float b) {
        float t2 = t * t;
        float t3 = t2 * t;
        float t4 = t3 * t;
        float t5 = t4 * t;
        float h1 = t - 6f * t3 + 8f * t4 - 3f * t5;
        float h4 = -4f * t3 + 7f * t4 - 3f * t5;
        float h5 = 10f * t3 - 15f * t4 + 6f * t5;
        return clamp(a * h1 + b * h4 + h5, -0.25f, 1.25f);
    }

    /** Sets {@link #curX}/{@link #curY} for {@code now} from the plan. */
    private static void position(long now) {
        if (!placed || segCount == 0) return;
        evalAt(now, SCRATCH2);
        curX = SCRATCH2[0];
        curY = SCRATCH2[1];
        if (inRepeat) {
            repeatMaxPx = Math.max(repeatMaxPx, (float) Math.hypot(curX - aimedX, curY - aimedY) - aimedHalf);
        }
    }

    private static void tick(Minecraft client) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean inTerminal = hosts(McCompat.screen(client));
        if (showing && (!inTerminal || now - lastShownMs > 250)) {
            showing = false;
        }
        // The real pointer is hidden while the human cursor shows (one arrow on screen), and with Stream Safe off and
        // Hide Mouse Cursor on, while auto clicks are landing.
        boolean wantHidden = inTerminal && (showing || (cfg.isHideMouseCursor() && cfg.isAutoTerminalsEnabled()
                && TerminalSolverFeature.autoClickedRecently(now)));
        if (client.getWindow() == null) return;
        com.mojang.blaze3d.platform.Window window = client.getWindow();
        if (wantHidden) {
            // Re-asserted every tick: the game sets its own cursor mode when a screen opens or closes.
            com.killer560.hub.compat.McWindow.setCursorHidden(window, true, false);
            hidden = true;
        } else if (hidden) {
            // Not always NORMAL: closing the terminal to no screen makes the game grab the mouse (cursor DISABLED) before this
            // tick ends, and writing NORMAL over that left the pointer free in the world until the next screen opened and closed.
            boolean grabbed = client.mouseHandler.isMouseGrabbed() && McCompat.screen(client) == null;
            com.killer560.hub.compat.McWindow.setCursorHidden(window, false, grabbed);
            hidden = false;
        }
    }

    /** True while the real pointer is hidden by this class (testkit 287 reads it). */
    static boolean realPointerHidden() { return hidden; }

    private static void render(GuiGraphicsExtractor g) {
        if (!showing || !placed) return;
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        position(now);
        if (!Float.isNaN(lastFrameX)) {
            float dx = curX - lastFrameX;
            float dy = curY - lastFrameY;
            float step = (float) Math.sqrt(dx * dx + dy * dy);
            maxFramePx = Math.max(maxFramePx, step);
            if (lastClickMs > 0 && now - lastClickMs <= 150) {
                glideAfterClickPx += step;
            }
            if (now > lastFrameMs) {
                maxPxPerMs = Math.max(maxPxPerMs, step / (now - lastFrameMs));
            }
        }
        lastFrameX = curX;
        lastFrameY = curY;
        lastFrameMs = now;
        frames++;

        float gui = (float) client.getWindow().getGuiScale();
        float content = contentScale(client);
        g.nextStratum();
        g.pose().pushMatrix();
        // Window pixels from here on: the arrow's tip at the pointer, one mask pixel = content-scale screen pixels.
        // From identity, not from the pose the screen left: one of the mod's own screens (Termism) is drawn under Auto
        // Scale's factor, which would otherwise scale the pointer's window position too and put it off the board.
        g.pose().identity();
        g.pose().scale(1f / gui, 1f / gui);
        g.pose().translate(curX, curY);
        g.pose().scale(content, content);
        for (int[] run : RUNS) {
            g.fill(run[1], run[0], run[2], run[0] + 1, run[3]);
        }
        g.pose().popMatrix();
    }

    private static float cachedContentScale = 1f;
    private static long contentScaleReadMs;

    /** The monitor's content (DPI) scale, which Windows also draws its pointer at. Read at most once a second. */
    private static float contentScale(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - contentScaleReadMs > 1000) {
            contentScaleReadMs = now;
            try {
                float v = com.killer560.hub.compat.McWindow.contentScale(client.getWindow());
                cachedContentScale = v > 0.5f && v < 4f ? v : 1f;
            } catch (Throwable t) {
                cachedContentScale = 1f;
            }
        }
        return cachedContentScale;
    }

    private static float logNormal(ThreadLocalRandom r, float median, float sigma, float lo, float hi) {
        float v = median * (float) Math.exp(sigma * r.nextGaussian());
        return clamp(v, median * lo, median * hi);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int[][] buildRuns() {
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            int x = 0;
            while (x < line.length()) {
                char c = line.charAt(x);
                int end = x;
                while (end < line.length() && line.charAt(end) == c) {
                    end++;
                }
                if (c != '.') {
                    runs.add(new int[]{row, x, end, c == 'B' ? 0xFF000000 : 0xFFFFFFFF});
                }
                x = end;
            }
        }
        return runs.toArray(new int[0][]);
    }
}
