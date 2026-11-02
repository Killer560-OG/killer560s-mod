package com.killer560.hub.containercover;

import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The one rule for every GUI this mod draws over a real container screen (killer560, 2026-11-01: hovering blank Storage
 * Overlay space hovered a hidden chest slot and a click there picked up his Spirit Sceptre; "make that happen for all
 * guis we draw over"): a vanilla slot under something we drew never hovers, never shows its tooltip or highlight, and
 * never takes a click, a hotbar number key, Q, a middle click, a shift click, a double-click collect or a drag.
 * <p>
 * Two kinds of cover:
 * <ul>
 *   <li><b>Whole screen</b> - a reskin that hides the real menu (Storage Overlay, AH/Bazaar reskin, Spirit Leap menu,
 *       Terminal Custom GUI, the sorter's locked inventory): {@link #registerFull} once, or have the screen class
 *       implement {@link FullyCovered}.</li>
 *   <li><b>A panel</b> drawn beside or over the menu (Croesus profit, Start buttons, Superpairs, Listing Helper, item
 *       browser, search bar, RNG ranking): {@link #coverRect} every frame it is drawn, in GUI-scaled screen coordinates.
 *       A rect lapses {@link #RECT_TTL_NANOS} after its last report, so a panel that stops drawing stops covering.</li>
 * </ul>
 * {@code containercover.mixin.ContainerCoverMixin} applies it: {@code getHoveredSlot} answers null under a cover (the
 * one source of {@code hoveredSlot}, which the highlight, tooltip, number keys, Q, F, the scroll actions and every other
 * feature's hover read), and {@code mouseClicked}/{@code mouseReleased}/{@code mouseDragged} stop before their slot logic
 * (child widgets still get the event). A feature's own panel clicks are claimed before that (Fabric allow-listeners or
 * HEAD injects), and its deliberate slot redirects through {@code SlotClickInvoker} are not affected.
 */
public final class ContainerCover {

    /** A screen whose real slots are all hidden behind what it draws. */
    public interface FullyCovered {
        default boolean coversAllSlots() {
            return true;
        }
    }

    /** A panel stops covering this long after it was last drawn (several frames even at 20 fps). */
    static final long RECT_TTL_NANOS = 250_000_000L;

    private static final List<Predicate<Screen>> FULL = new CopyOnWriteArrayList<>();
    private static final Map<String, Rect> RECTS = new ConcurrentHashMap<>();

    private record Rect(Screen screen, double x0, double y0, double x1, double y1, long atNanos) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private ContainerCover() {
    }

    /** Registers a whole-screen cover; {@code covers} runs on the render thread for every hover test, so keep it cheap. */
    public static void registerFull(Predicate<Screen> covers) {
        FULL.add(covers);
    }

    /** Reports that {@code owner}'s panel was drawn on {@code screen} this frame at {@code x0,y0}-{@code x1,y1}. */
    public static void coverRect(Screen screen, String owner, double x0, double y0, double x1, double y1) {
        if (screen == null || owner == null) {
            return;
        }
        RECTS.put(owner, new Rect(screen, Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1),
                System.nanoTime()));
    }

    /** {@link #coverRect} on whatever screen is open (for draw code that is not handed the screen). */
    public static void coverRectOnCurrentScreen(String owner, double x0, double y0, double x1, double y1) {
        coverRect(McCompat.screen(Minecraft.getInstance()), owner, x0, y0, x1, y1);
    }

    /** Whether every real slot of {@code screen} is hidden behind something this mod draws. */
    public static boolean coversAll(Screen screen) {
        if (screen == null) {
            return false;
        }
        if (screen instanceof FullyCovered f && f.coversAllSlots()) {
            return true;
        }
        for (Predicate<Screen> p : FULL) {
            try {
                if (p.test(screen)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A broken predicate must not take the screen's input path down; it simply does not cover.
            }
        }
        return false;
    }

    /** Whether the point {@code x,y} of {@code screen} lies under something this mod draws. */
    public static boolean covers(Screen screen, double x, double y) {
        if (screen == null) {
            return false;
        }
        if (coversAll(screen)) {
            return true;
        }
        if (RECTS.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        for (Map.Entry<String, Rect> e : RECTS.entrySet()) {
            Rect r = e.getValue();
            if (now - r.atNanos() > RECT_TTL_NANOS || r.screen() != screen) {
                if (now - r.atNanos() > RECT_TTL_NANOS) {
                    RECTS.remove(e.getKey(), r);
                }
                continue;
            }
            if (r.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** For the testkit: the owners whose panel currently covers {@code x,y} on {@code screen} (empty when none). */
    public static List<String> coveringOwners(Screen screen, double x, double y) {
        List<String> out = new java.util.ArrayList<>();
        if (screen == null) {
            return out;
        }
        if (coversAll(screen)) {
            out.add("full");
        }
        long now = System.nanoTime();
        for (Map.Entry<String, Rect> e : RECTS.entrySet()) {
            Rect r = e.getValue();
            if (now - r.atNanos() <= RECT_TTL_NANOS && r.screen() == screen && r.contains(x, y)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** For the testkit: the last reported rect of {@code owner} on the open screen as {x0,y0,x1,y1}, or null. */
    public static double[] rectOf(String owner) {
        Rect r = RECTS.get(owner);
        if (r == null || System.nanoTime() - r.atNanos() > RECT_TTL_NANOS) {
            return null;
        }
        return new double[]{r.x0(), r.y0(), r.x1(), r.y1()};
    }
}
