package com.killer560.hub.terminals;

import com.killer560.hub.compat.McCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;

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
 *       part way for a second look. Every plan ends ON the slot, then a short press. A tiny tremor rides on top.</li>
 *   <li><b>The same slot again</b> (a Rubix pane needing 2) is known before the first click, so the cursor stays and
 *       clicks again after a double-click-like gap; it never leaves and comes back.</li>
 *   <li>Per-terminal randomness (speed, reaction, curvature lean) so two terminals never look the same, all bounded.</li>
 * </ul>
 * No allocation per frame: the plan is a fixed set of arrays.
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
    private static int segCount;
    private static long planEndMs;
    /** Build cursor while planning: where the next segment starts and when. */
    private static float bx;
    private static float by;
    private static long bt;

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

    // ---- this terminal's own hand ----
    private static float sessionSpeed = 1f;
    private static float sessionReaction = 1f;
    private static float sessionLean;
    private static float tremorPhase1;
    private static float tremorPhase2;
    private static float tremorF1 = 8f;
    private static float tremorF2 = 11f;

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
    /** Farthest the cursor got from a slot between two clicks on that same slot (window px). */
    static float repeatMaxPx;
    private static boolean inRepeat;
    private static float lastFrameX = Float.NaN;
    private static float lastFrameY = Float.NaN;
    private static long lastFrameMs;

    private static final float[] SCRATCH = new float[3];

    private FakeCursor() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FakeCursor::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) {
                return;
            }
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
    static boolean active() {
        return showing;
    }

    /** A terminal opened (not a quick reopen of the same one): a fresh hand, from the window center. */
    static void beginTerminal() {
        placed = false;
        aimedSlot = -1;
        segCount = 0;
        planEndMs = 0;
        firstMove = true;
        inRepeat = false;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        sessionSpeed = clamp(1f + 0.08f * (float) r.nextGaussian(), 0.85f, 1.18f);
        sessionReaction = clamp(1f + 0.12f * (float) r.nextGaussian(), 0.8f, 1.25f);
        sessionLean = (r.nextBoolean() ? 1f : -1f) * (0.02f + 0.06f * r.nextFloat());
        tremorPhase1 = r.nextFloat() * 6.283f;
        tremorPhase2 = r.nextFloat() * 6.283f;
        tremorF1 = 7f + 3f * r.nextFloat();
        tremorF2 = 10f + 3f * r.nextFloat();
        startX = -1f;
        startY = -1f;
        frames = 0;
        maxFramePx = 0f;
        maxPxPerMs = 0f;
        clickCount = 0;
        clicksWaited = 0;
        repeatMaxPx = 0f;
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
        if (placed) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) {
            return;
        }
        curX = client.getWindow().getWidth() / 2f;
        curY = client.getWindow().getHeight() / 2f;
        segCount = 0;
        planEndMs = 0;
        startX = curX;
        startY = curY;
        placed = true;
    }

    // ------------------------------------------------------------------ planning

    /** Heads for {@code slot}; a no-op while already headed there. */
    static void aim(AbstractContainerScreen<?> screen, int slot) {
        if (!showing || slot == aimedSlot) {
            return;
        }
        if (!TerminalSolverFeature.slotCenterGui(screen, slot, SCRATCH)) {
            return;
        }
        ensurePlaced();
        long now = System.currentTimeMillis();
        position(now);
        double gui = Minecraft.getInstance().getWindow().getGuiScale();
        aimedSlot = slot;
        aimedX = (float) (SCRATCH[0] * gui);
        aimedY = (float) (SCRATCH[1] * gui);
        aimedHalf = (float) Math.max(2.0, SCRATCH[2] * gui / 2.0);
        inRepeat = false;
        plan(now);
    }

    private static void plan(long now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        float speed = cfg.getFakeCursorSpeed() / 100f;
        float cell = aimedHalf * 2f;
        segCount = 0;
        bx = curX;
        by = curY;
        bt = now;

        // Reaction before the hand moves; the first look at a fresh board takes a little longer.
        float reaction = logNormal(r, cfg.getCursorReactionMs() * sessionReaction * (firstMove ? 1.35f : 1f), 0.28f, 0.45f, 2.2f);
        firstMove = false;
        if (r.nextFloat() < 0.06f) {
            reaction += 150f + 230f * r.nextFloat();
            hesitations++;
        }
        hold(reaction);

        // Where the press lands: round the center, inside the slot.
        float lx = aimedX + clamp((float) r.nextGaussian() * 0.12f * cell, -0.3f * cell, 0.3f * cell);
        float ly = aimedY + clamp((float) r.nextGaussian() * 0.12f * cell, -0.3f * cell, 0.3f * cell);
        float dx = lx - bx;
        float dy = ly - by;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist >= 1f) {
            moves++;
            if (dist > 3.2f * cell && r.nextFloat() < 0.12f) {
                // A second look on a long move: stop part way (a little off the line), then go on.
                float f = 0.4f + 0.2f * r.nextFloat();
                float off = (r.nextFloat() - 0.5f) * 0.3f * dist;
                float mx = bx + dx * f - dy / dist * off;
                float my = by + dy * f + dx / dist * off;
                move(mx, my, cell, speed, r, 0.95f);
                hold(110f + 150f * r.nextFloat());
                doubleTakes++;
            }
            float roll = r.nextFloat() * 100f;
            if (roll < cfg.getCursorMissChance()) {
                // A miss: the move ends at the slot's edge or on a neighbor, a corrective pause, then onto the slot.
                double a = r.nextDouble() * Math.PI * 2.0;
                float err = (0.55f + 0.6f * r.nextFloat()) * cell;
                move(lx + (float) Math.cos(a) * err, ly + (float) Math.sin(a) * err, cell, speed, r, 1f);
                hold(logNormal(r, 170f, 0.25f, 0.6f, 1.6f));
                move(lx, ly, cell, speed, r, 1.1f);
                misses++;
            } else if (roll < cfg.getCursorMissChance() + cfg.getCursorOvershootChance()) {
                // An overshoot: carried past the slot along the move, a beat, then back onto it.
                float ux = (lx - bx) / Math.max(1f, (float) Math.hypot(lx - bx, ly - by));
                float uy = (ly - by) / Math.max(1f, (float) Math.hypot(lx - bx, ly - by));
                float past = (0.35f + 0.6f * r.nextFloat()) * cell;
                move(lx + ux * past, ly + uy * past, cell, speed, r, 1f);
                hold(40f + 90f * r.nextFloat());
                move(lx, ly, cell, speed, r, 1.15f);
                overshoots++;
            } else {
                move(lx, ly, cell, speed, r, 1f);
            }
        }
        // The press.
        hold(logNormal(r, 45f, 0.3f, 0.5f, 2.2f));
        planEndMs = bt;
    }

    /** Another click on the slot the cursor is already on: a double-click-like gap, no movement. */
    private static void planRepeat(long now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        segCount = 0;
        bx = curX;
        by = curY;
        bt = now;
        hold(logNormal(r, 125f, 0.25f, 0.6f, 1.9f));
        planEndMs = bt;
        inRepeat = true;
        repeats++;
    }

    private static void hold(float ms) {
        add(bx, by, bx, by, bx, by, bx, by, ms);
    }

    private static void move(float tx, float ty, float cell, float speed, ThreadLocalRandom r, float durScale) {
        float dx = tx - bx;
        float dy = ty - by;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.5f) {
            return;
        }
        float fitts = FITTS_A_MS + FITTS_B_MS * (float) (Math.log(dist / Math.max(4f, cell) + 1.0) / Math.log(2.0));
        float variance = clamp(1f + 0.12f * (float) r.nextGaussian(), 0.75f, 1.35f);
        float dur = Math.max(MIN_MOVE_MS, fitts * variance * sessionSpeed * durScale / speed);
        // Curvature: leans one way for this terminal, varies move to move.
        float k = clamp(sessionLean + 0.07f * (float) r.nextGaussian(), -0.2f, 0.2f);
        float px = -dy;
        float py = dx;
        float k1 = k * (0.6f + 0.6f * r.nextFloat());
        float k2 = k * (0.3f + 0.6f * r.nextFloat());
        add(bx, by,
                bx + dx * 0.3f + px * k1, by + dy * 0.3f + py * k1,
                bx + dx * 0.72f + px * k2, by + dy * 0.72f + py * k2,
                tx, ty, dur);
    }

    private static void add(float sx, float sy, float c1x, float c1y, float c2x, float c2y, float ex, float ey, float ms) {
        if (segCount >= MAX_SEGMENTS) {
            return;
        }
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
        bt += (long) Math.ceil(ms);
        bx = ex;
        by = ey;
    }

    // ------------------------------------------------------------------ the click gate

    /** @return true when the cursor has arrived on {@code slot} and pressed - the click may go out now. */
    static boolean ready(int slot) {
        if (!showing) {
            return true;
        }
        boolean arrived = slot == aimedSlot && placed && System.currentTimeMillis() >= planEndMs;
        if (!arrived) {
            clicksWaited++;
        }
        return arrived;
    }

    /** A click on {@code slot} went out. The next click on the same slot waits for a fresh press gap. */
    static void clicked(int slot) {
        if (!showing) {
            return;
        }
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
        if (slot == aimedSlot) {
            planRepeat(now);
        }
    }

    // ------------------------------------------------------------------ evaluation and drawing

    /** Sets {@link #curX}/{@link #curY} for {@code now} from the plan. */
    private static void position(long now) {
        if (!placed || segCount == 0) {
            return;
        }
        int i = 0;
        while (i < segCount - 1 && now >= SEG_START[i] + (long) Math.ceil(SEG_DUR[i])) {
            i++;
        }
        float t = SEG_DUR[i] <= 0f ? 1f : clamp((now - SEG_START[i]) / SEG_DUR[i], 0f, 1f);
        float s = t * t * t * (10f + t * (-15f + 6f * t));
        float u = 1f - s;
        float x = u * u * u * SX[i] + 3f * u * u * s * C1X[i] + 3f * u * s * s * C2X[i] + s * s * s * EX[i];
        float y = u * u * u * SY[i] + 3f * u * u * s * C1Y[i] + 3f * u * s * s * C2Y[i] + s * s * s * EY[i];
        // Physiological tremor: well under a pixel, two slow sines.
        float sec = (now % 100000L) / 1000f;
        x += 0.35f * (float) (0.6 * Math.sin(6.283 * tremorF1 * sec + tremorPhase1) + 0.4 * Math.sin(6.283 * tremorF2 * sec + tremorPhase2));
        y += 0.35f * (float) (0.6 * Math.cos(6.283 * tremorF2 * sec + tremorPhase2) + 0.4 * Math.sin(6.283 * tremorF1 * sec + tremorPhase1));
        curX = x;
        curY = y;
        if (inRepeat) {
            repeatMaxPx = Math.max(repeatMaxPx, (float) Math.hypot(x - aimedX, y - aimedY) - aimedHalf);
        }
    }

    private static void tick(Minecraft client) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean inTerminal = McCompat.screen(client) instanceof AbstractContainerScreen<?>;
        if (showing && (!inTerminal || now - lastShownMs > 250)) {
            showing = false;
        }
        // The real pointer is hidden while the human cursor shows (one arrow on screen), and with Stream Safe off and
        // Hide Mouse Cursor on, while auto clicks are landing.
        boolean wantHidden = inTerminal && (showing || (cfg.isHideMouseCursor() && cfg.isAutoTerminalsEnabled()
                && TerminalSolverFeature.autoClickedRecently(now)));
        if (client.getWindow() == null) {
            return;
        }
        long handle = client.getWindow().handle();
        if (wantHidden) {
            // Re-asserted every tick: the game sets its own cursor mode when a screen opens or closes.
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            hidden = true;
        } else if (hidden) {
            // Not always NORMAL: closing the terminal to no screen makes the game grab the mouse (cursor DISABLED) before this
            // tick ends, and writing NORMAL over that left the pointer free in the world until the next screen opened and closed.
            boolean grabbed = client.mouseHandler.isMouseGrabbed() && McCompat.screen(client) == null;
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, grabbed ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
            hidden = false;
        }
    }

    /** True while the real pointer is hidden by this class (testkit 287 reads it). */
    static boolean realPointerHidden() {
        return hidden;
    }

    private static void render(GuiGraphicsExtractor g) {
        if (!showing || !placed) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        position(now);
        if (!Float.isNaN(lastFrameX)) {
            float dx = curX - lastFrameX;
            float dy = curY - lastFrameY;
            float step = (float) Math.sqrt(dx * dx + dy * dy);
            maxFramePx = Math.max(maxFramePx, step);
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
        g.pose().scale(1f / gui, 1f / gui);
        g.pose().translate(Math.round(curX), Math.round(curY));
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
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.FloatBuffer x = stack.mallocFloat(1);
                java.nio.FloatBuffer y = stack.mallocFloat(1);
                GLFW.glfwGetWindowContentScale(client.getWindow().handle(), x, y);
                float v = x.get(0);
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

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

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
