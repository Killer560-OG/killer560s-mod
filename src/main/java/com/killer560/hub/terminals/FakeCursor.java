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
 * The look of a person clicking, for screen sharing (killer560, 2026-10-19; rebuilt 2026-10-26: "the fake mouse does not
 * work. it shows up but doesn't match the terminal gui ... make it look like a human mouse with the same design. it
 * should start at the center and move not just teleport around").
 * <ul>
 *   <li><b>Where:</b> every target is the slot's center AS DRAWN, from {@link TerminalSolverFeature#slotCenterGui} -
 *       the Custom GUI panel's own layout when that is showing (the same math its click hit test uses), the vanilla
 *       slot otherwise. The old pointer used vanilla slot coordinates only, so on the Custom GUI it sat over hidden
 *       slots nowhere near the cells being clicked.</li>
 *   <li><b>Look:</b> the classic Windows arrow (12x19, black edge, white fill), drawn in WINDOW pixels - one mask pixel
 *       per screen pixel, times the monitor's content scale - so it is the size of the real pointer at any GUI scale.
 *       While it shows, the real pointer is hidden, so there is only ever one arrow.</li>
 *   <li><b>Motion:</b> it starts at the window center when a terminal opens (where Minecraft puts the pointer when a
 *       screen opens from the world) and moves along a slight curve with minimum-jerk speed (slow, fast, slow), a
 *       Fitts-law duration (longer for farther, smaller targets), and a small overshoot that settles back. It never
 *       jumps: a new target starts a new curve from wherever it is.</li>
 *   <li><b>Timing:</b> Auto Terminals AIMS it at the slot it is about to click ({@link #aim}) and holds that click
 *       until it has arrived ({@link #ready}), so the click lands with the pointer on the slot, never before it gets
 *       there. A move takes as long as a hand would at the chosen speed; when the click delay is shorter than the
 *       move, the click waits for the pointer (that is the cost of it looking real).</li>
 *   <li><b>Feedback:</b> none of its own. A real click on a terminal draws nothing extra, so neither does this.</li>
 * </ul>
 * No allocation per frame: the motion is a handful of floats, the arrow a static run table.
 */
public final class FakeCursor {

    /** Fitts' law, MacKenzie's form: duration = A + B * log2(D / W + 1), in ms at 100% speed. Mouse pointing values in
     *  the usual range for a practiced hand (intercept ~50 ms, ~100 ms a bit). */
    private static final float FITTS_A_MS = 50f;
    private static final float FITTS_B_MS = 100f;
    /** Shortest move, so a one-cell hop is still a visible move rather than a snap. */
    private static final float MIN_MOVE_MS = 60f;

    /**
     * The classic Windows arrow, 12 wide and 19 tall, one string per row: B black edge, W white fill, . clear. Turned
     * into runs once, below.
     */
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
    /** {row, x0, x1, argb} for every same-color run of {@link #ARROW}. */
    private static final int[][] RUNS = buildRuns();

    // ---- motion state, in window pixels ----
    private static boolean placed;
    private static float curX;
    private static float curY;
    private static float fromX;
    private static float fromY;
    private static float toX;
    private static float toY;
    private static float bendX;
    private static float bendY;
    private static float overX;
    private static float overY;
    private static long moveStartMs;
    private static float moveMs;
    private static int aimedSlot = -1;
    private static boolean hidden;
    /** True while a terminal Auto Terminals is driving is open with Fake Cursor on (set each frame by the solver). */
    private static boolean showing;
    private static long lastShownMs;

    // ---- testkit probes (278/279): read by reflection, nothing in the mod reads them ----
    static float startX = -1f;
    static float startY = -1f;
    static int frames;
    static float maxFramePx;
    static int clickCount;
    static final int[] CLICK_SLOT = new int[64];
    static final float[] CLICK_X = new float[64];
    static final float[] CLICK_Y = new float[64];
    static final float[] CLICK_TX = new float[64];
    static final float[] CLICK_TY = new float[64];
    /** Clicks Auto Terminals held back because the pointer was still on its way. */
    static int clicksWaited;
    /** Fastest the pointer moved between two drawn frames, in window pixels per ms. */
    static float maxPxPerMs;
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

    /** @return whether the pointer is in use: Fake Cursor on and Auto Terminals clicking this terminal. */
    static boolean active() {
        return showing;
    }

    /** A terminal opened (not a quick reopen of the same one): start over from the window center. */
    static void beginTerminal() {
        placed = false;
        aimedSlot = -1;
        startX = -1f;
        startY = -1f;
        frames = 0;
        maxFramePx = 0f;
        maxPxPerMs = 0f;
        clickCount = 0;
        clicksWaited = 0;
        lastFrameX = Float.NaN;
        lastFrameY = Float.NaN;
    }

    /** Called by the solver every frame a terminal is tracked: whether Auto Terminals is driving it with Fake Cursor on. */
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
        fromX = toX = curX;
        fromY = toY = curY;
        bendX = bendY = overX = overY = 0f;
        moveMs = 0f;
        startX = curX;
        startY = curY;
        placed = true;
    }

    /** Points the pointer at {@code slot} of the open terminal; a no-op when it is already headed there. */
    static void aim(AbstractContainerScreen<?> screen, int slot) {
        if (!showing || slot == aimedSlot) {
            return;
        }
        if (!TerminalSolverFeature.slotCenterGui(screen, slot, SCRATCH)) {
            return;
        }
        ensurePlaced();
        long now = System.currentTimeMillis();
        update(now);
        double gui = Minecraft.getInstance().getWindow().getGuiScale();
        float tx = (float) (SCRATCH[0] * gui);
        float ty = (float) (SCRATCH[1] * gui);
        float width = (float) Math.max(4.0, SCRATCH[2] * gui);
        float dx = tx - curX;
        float dy = ty - curY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float speed = TerminalSolverConfig.getInstance().getFakeCursorSpeed() / 100f;
        float fitts = FITTS_A_MS + FITTS_B_MS * (float) (Math.log(dist / width + 1.0) / Math.log(2.0));
        fromX = curX;
        fromY = curY;
        toX = tx;
        toY = ty;
        moveStartMs = now;
        moveMs = dist < 0.5f ? 0f : Math.max(MIN_MOVE_MS, fitts * (0.9f + 0.2f * rng.nextFloat())) / speed;
        // A slight curve: the control point sits off the straight line by 3-12% of the distance, either side.
        float bend = (0.03f + 0.09f * rng.nextFloat()) * (rng.nextBoolean() ? 1f : -1f);
        bendX = -dy * bend;
        bendY = dx * bend;
        // A small overshoot past the target along the move, which settles back by the end: up to 4% of the
        // distance, never more than a third of the cell, so it never leaves the slot it is going to.
        float over = Math.min(0.04f * dist * rng.nextFloat(), width / 3f);
        overX = dist > 0f ? dx / dist * over : 0f;
        overY = dist > 0f ? dy / dist * over : 0f;
        aimedSlot = slot;
    }

    /** @return true when the pointer has finished its move onto {@code slot} - the click may go out now. */
    static boolean ready(int slot) {
        if (!showing) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean arrived = slot == aimedSlot && placed && now - moveStartMs >= moveMs;
        if (!arrived) {
            clicksWaited++;
        }
        return arrived;
    }

    /** Records where the pointer was when a click it was aimed for went out. */
    static void clicked(int slot) {
        if (!showing) {
            return;
        }
        update(System.currentTimeMillis());
        int i = clickCount % CLICK_SLOT.length;
        CLICK_SLOT[i] = slot;
        CLICK_X[i] = curX;
        CLICK_Y[i] = curY;
        CLICK_TX[i] = toX;
        CLICK_TY[i] = toY;
        clickCount++;
    }

    /** Moves {@link #curX}/{@link #curY} to where the current curve puts the pointer at {@code now}. */
    private static void update(long now) {
        if (!placed) {
            return;
        }
        float t = moveMs <= 0f ? 1f : Math.min(1f, (now - moveStartMs) / moveMs);
        // Minimum-jerk profile: slow start, fast middle, slow arrival.
        float s = t * t * t * (10f + t * (-15f + 6f * t));
        float u = 1f - s;
        // Quadratic Bezier from -> to with the control point at the midpoint plus the bend.
        float cx = (fromX + toX) / 2f + bendX;
        float cy = (fromY + toY) / 2f + bendY;
        float x = u * u * fromX + 2f * u * s * cx + s * s * toX;
        float y = u * u * fromY + 2f * u * s * cy + s * s * toY;
        // Overshoot: rises after 60% of the move, peaks near 80%, back to zero at the end.
        if (t > 0.6f && t < 1f) {
            float k = (float) Math.sin(Math.PI * (t - 0.6f) / 0.4f);
            x += overX * k;
            y += overY * k;
        }
        curX = x;
        curY = y;
    }

    private static void tick(Minecraft client) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean inTerminal = McCompat.screen(client) instanceof AbstractContainerScreen<?>;
        if (showing && (!inTerminal || now - lastShownMs > 250)) {
            showing = false;
        }
        // The real pointer is hidden while the fake one shows (one arrow on screen), and, with Hide Mouse Cursor on,
        // while auto clicks are landing.
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

    private static void render(GuiGraphicsExtractor g) {
        if (!showing || !placed) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        update(now);
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
