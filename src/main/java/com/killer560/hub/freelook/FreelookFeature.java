package com.killer560.hub.freelook;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Freelook (killer560, 2026-10-18: "add a custom f5 so you press f5 and it puts you in backward f5 if the setting is on.
 * You can have an option to hold it or to toggle it. In this f5 you can choose a button to hold and pan around you.").
 * "Freelook" is the name the mods that did this use (Freelook OG, Free Look UTI, Decent Freelook; Shoulder Check and
 * SimpleLook are the same idea).
 * <p>
 * <b>Rear view.</b> With the switch on, the game's perspective key (F5 by default) no longer cycles the three views: its
 * clicks are taken here at START_CLIENT_TICK, before vanilla's {@code Minecraft.handleKeybinds} reads them, and it goes
 * straight to the backward view ({@link CameraType#THIRD_PERSON_FRONT}, the camera in front of him looking back) and
 * back to whatever view he had. Hold keeps it while the key is held (the {@link KeyMapping}'s own down state, so it follows
 * whatever key he bound in Controls); Toggle switches on each press.
 * <p>
 * <b>Pan.</b> While the pan key is held the camera orbits him with the mouse: {@code mixin/FreelookMouseMixin} sends the
 * mouse's turn here instead of to the player, and {@code mixin/FreelookCameraMixin} hands {@code Camera.alignWithEntity}
 * this camera's yaw and pitch in place of his, so vanilla's own third-person placement (mirroring, distance, wall
 * collision) orbits around him. In first person a pan switches to the normal third-person view for as long as it is held.
 * On release the camera goes back behind him.
 * <p>
 * <b>Camera only.</b> His yaw, pitch and movement input are never touched and nothing is sent: the player's rotation is
 * not written (the mouse turn is simply not applied while panning), the crosshair pick still casts along his own look
 * ({@code Entity.getViewVector} is not the camera), and the camera pitch is clamped to -90..90. The camera yaw is never
 * wrapped. The perspective is a client option that no packet carries.
 */
public final class FreelookFeature {

    /** Test hook: stands in for the pan key (a harness key press never reaches the GLFW poll). Null in play. */
    public static volatile Boolean panHeldForTest;

    private static boolean rearActive;
    /** The view to go back to when the rear view ends. */
    private static CameraType beforeRear = CameraType.FIRST_PERSON;
    private static boolean panning;
    /** The view a pan switched away from (first person), or null when the pan did not change the view. */
    private static CameraType beforePan;
    private static float camYaw;
    private static float camPitch;

    private FreelookFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("FreelookFeature.tick", FreelookFeature::tick));
    }

    static void tick(Minecraft client) {
        FreelookConfig cfg = FreelookConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.options == null) {
            endAll(client);
            return;
        }
        // Something else changed the view under the rear view (a death screen, a respawn, another mod): it is over.
        if (rearActive && client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            rearActive = false;
        }

        // The rear view is driven by the Freelook key (killer560, 2026-10-18), not F5: F5 keeps vanilla cycling. Toggle flips
        // the view on each press of that key; Hold keeps it while the key is down.
        boolean panDown = panHeld(client, cfg);
        if (cfg.getMode() == FreelookConfig.Mode.TOGGLE) {
            if (panDown && !panKeyWasDown) {
                setRear(client, !rearActive);
            }
        } else if (panDown != rearActive) {
            setRear(client, panDown);
        }
        panKeyWasDown = panDown;

        if (panDown && !panning) {
            startPan(client);
        } else if (!panDown && panning) {
            stopPan(client);
        }
    }

    /** The Freelook key's state last tick, for the Toggle press edge. */
    private static boolean panKeyWasDown = false;

    private static boolean panHeld(Minecraft client, FreelookConfig cfg) {
        Boolean forced = panHeldForTest;
        if (forced != null) {
            return forced;
        }
        int code = cfg.getPanKeyCode();
        if (code == KeyUtil.NONE || McCompat.screen(client) != null || client.getWindow() == null) {
            return false;
        }
        return KeyUtil.isBindDown(client.getWindow(), code);
    }

    private static void setRear(Minecraft client, boolean on) {
        if (on == rearActive) {
            return;
        }
        if (on) {
            CameraType now = client.options.getCameraType();
            if (beforePan != null) {
                // The pan put him in third person from first: the rear view ends back in first person.
                now = beforePan;
                beforePan = null;
            }
            beforeRear = now == CameraType.THIRD_PERSON_BACK ? CameraType.FIRST_PERSON : now;
            rearActive = true;
            setView(client, CameraType.THIRD_PERSON_BACK);
        } else {
            rearActive = false;
            setView(client, beforeRear);
        }
    }

    private static void startPan(Minecraft client) {
        camYaw = client.player.getViewYRot(1f);
        camPitch = Mth.clamp(client.player.getViewXRot(1f), -90f, 90f);
        panning = true;
        if (client.options.getCameraType().isFirstPerson()) {
            beforePan = CameraType.FIRST_PERSON;
            setView(client, CameraType.THIRD_PERSON_BACK);
        }
    }

    private static void stopPan(Minecraft client) {
        panning = false;
        if (beforePan != null) {
            CameraType back = beforePan;
            beforePan = null;
            if (!rearActive && client.options != null) {
                setView(client, back);
            }
        }
    }

    private static void endAll(Minecraft client) {
        if (panning) {
            stopPan(client);
        }
        if (rearActive && client.options != null) {
            rearActive = false;
            if (client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
                setView(client, beforeRear);
            }
        }
        rearActive = false;
    }

    /** Vanilla's own perspective change ({@code Minecraft.handleKeybinds}, javap 26.1.2 and 26.2): set the option, and
     *  re-check the spectated entity's post effect when the change crosses first person. */
    private static void setView(Minecraft client, CameraType type) {
        CameraType old = client.options.getCameraType();
        if (old == type) {
            return;
        }
        client.options.setCameraType(type);
        if (old.isFirstPerson() != type.isFirstPerson() && client.gameRenderer != null) {
            client.gameRenderer.checkEntityPostEffect(type.isFirstPerson() ? client.getCameraEntity() : null);
        }
    }

    /** The mouse, while panning: turns the camera, never the player. Yaw is not wrapped; pitch stays in -90..90. */
    public static void turn(float dYaw, float dPitch) {
        camYaw += dYaw;
        camPitch = Mth.clamp(camPitch + dPitch, -90f, 90f);
    }

    public static boolean isPanning() {
        return panning;
    }

    public static boolean isRearActive() {
        return rearActive;
    }

    /** The pan camera's yaw (meaningful only while {@link #isPanning()}). */
    public static float cameraYaw() {
        return camYaw;
    }

    /** The pan camera's pitch (meaningful only while {@link #isPanning()}). */
    public static float cameraPitch() {
        return camPitch;
    }
}
