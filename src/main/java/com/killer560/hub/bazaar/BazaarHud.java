package com.killer560.hub.bazaar;

import com.killer560.hub.compat.McCompat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * "In the bazaar menu have it hide all other HUDs" (killer560, 2026-10-07). One gate: every HUD layer added through
 * Fabric's {@code HudElementRegistry} (addFirst, addLast, attachElementBefore/After - every bar, the scoreboard, the
 * map, timers, overlays, this mod's and any other mod's) is wrapped at registration by
 * {@code bazaar/mixin/HudLayerGateMixin}, and the wrapper skips the layer while {@link #hidesHud()} - the Bazaar screen
 * or a reskinned Bazaar menu is open and Hide HUDs in Bazaar is on. Vanilla's own elements (hotbar, chat) are left to
 * vanilla; the Bazaar's backdrop covers them.
 * <p>
 * Also the frame log the testkit reads to prove a page switch never shows a frame of Hypixel's chest or an empty
 * panel: while recording, every Bazaar frame ({@link BazaarView}), every frame a chest is drawn as Hypixel's
 * ({@code BazaarReskinBackgroundMixin}) and every frame with no screen or another one (a layer of our own, exempt from
 * the gate) adds a line.
 */
public final class BazaarHud {

    /** A layer that the gate never wraps. */
    public interface Ungated extends HudElement {
    }

    private static final int FRAME_LOG = 400;
    private static final ArrayDeque<String> FRAMES = new ArrayDeque<>();
    private static volatile boolean recording;
    private static int skipped;

    private BazaarHud() {
    }

    static void register() {
        // FIRST: the first layers draw in Gui's own pass every frame, before any screen; the ones after SUBTITLES are
        // deferred into the open screen's background (docs/LESSONS-GUI.md).
        Ungated probe = (graphics, delta) -> probe();
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath("killer560smod", "bazaar_frame_probe"), probe);
    }

    /** True while HUD layers must not draw: the Bazaar is on screen and Hide HUDs in Bazaar is on. */
    public static boolean hidesHud() {
        return BazaarConfig.getInstance().isHideHuds() && bazaarOnScreen();
    }

    /** The Bazaar screen, or a Bazaar menu drawn as it, is the open screen. */
    public static boolean bazaarOnScreen() {
        Screen s = McCompat.screen(Minecraft.getInstance());
        return s instanceof BazaarScreen || (s != null && BazaarReskin.isHiding(s));
    }

    /** The wrapper the mixin installs around each registered layer. */
    public static HudElement gate(HudElement element) {
        if (element == null || element instanceof Ungated) {
            return element;
        }
        return (graphics, delta) -> {
            if (hidesHud()) {
                skipped++;
                return;
            }
            element.extractRenderState(graphics, delta);
        };
    }

    /** Gui's pass, every frame: records the frames no Bazaar or chest draw would (no screen, another screen). */
    private static void probe() {
        if (!recording) {
            return;
        }
        Screen s = McCompat.screen(Minecraft.getInstance());
        if (s == null) {
            recordFrame("none");
        } else if (!(s instanceof BazaarScreen) && !(s instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen)) {
            recordFrame("other " + s.getClass().getSimpleName());
        }
    }

    /** One drawn frame: the Bazaar view ("screen ..."/"reskin ...") or Hypixel's chest ("vanilla '<title>'"). */
    public static void recordFrame(String what) {
        if (!recording) {
            return;
        }
        synchronized (FRAMES) {
            FRAMES.addLast(System.currentTimeMillis() + " " + what);
            while (FRAMES.size() > FRAME_LOG) {
                FRAMES.removeFirst();
            }
        }
    }

    public static boolean recording() {
        return recording;
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** Starts (clearing the log) or stops (keeping it for {@link #framesForTest}) recording one line per frame. */
    public static void recordFramesForTest(boolean on) {
        if (on) {
            synchronized (FRAMES) {
                FRAMES.clear();
            }
        }
        recording = on;
    }

    /** The recorded frames: {@code <ms> none | vanilla <class> '<title>' | screen <view> | reskin <view>}. */
    public static List<String> framesForTest() {
        synchronized (FRAMES) {
            return new ArrayList<>(FRAMES);
        }
    }

    /** How many layer draws the gate has skipped since launch. */
    public static int skippedForTest() {
        return skipped;
    }
}
