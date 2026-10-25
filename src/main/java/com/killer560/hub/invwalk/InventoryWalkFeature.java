package com.killer560.hub.invwalk;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inventory Walk (cheat build only; killer560, 2026-10-25: "can you try making inventory walk"). Vanilla lets go of
 * every key when a screen opens ({@code Minecraft.setScreen} -> {@code KeyMapping.releaseAll}, javap 26.1.2) and, while
 * one is open, keyboard events go to the screen and never to the key mappings, so he stops dead in every chest, menu
 * and terminal. With this on, while an eligible screen is open, each tick at START_CLIENT_TICK - before the player's
 * tick reads its input - sets the movement mappings (forward, back, left, right, and optionally jump and sprint) to
 * what the keyboard really has held. Whole key presses only, exactly as vanilla's own key handling sets them: nothing
 * fractional, no position or velocity written, no rotation (the mouse is the screen's). Sneak is never set (shift is
 * the screen's shift-click).
 *
 * <p>Eligible: a container screen (Containers) or any screen (All Screens), never while a text box has focus, nor a
 * chat, sign or book screen. Optionally the keys are let go for a few ticks after each slot click (Pause After Click).
 *
 * <p>Every activation, key change, click and deactivation is logged (logger killer560smod-invwalk), with a summary line
 * every second while it is active, so a test on Hypixel can be read back from the log.
 */
public final class InventoryWalkFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-invwalk");

    /** Test hook: the key-mapping names (e.g. "key.forward") to treat as physically held. Null in play. */
    public static volatile Set<String> heldForTest;

    private static boolean active;
    private static String activeScreen = "";
    private static int activeTicks;
    private static Vec3 activeStart;
    private static Vec3 lastPos;
    private static String lastKeys = "";
    private static int pauseLeft;
    /** Clicks made while walking, held until a still tick has reached the server (Stop To Click). */
    private static final ArrayDeque<Packet<?>> heldClicks = new ArrayDeque<>();
    private static boolean sendingHeld;
    private static int heldClickTicks;
    private static long clicksHeld;

    /** Totals, for the testkit and the log. */
    private static long activations;
    private static long walkingTicks;
    private static long clicksWhileWalking;
    private static long clicksTotal;

    private InventoryWalkFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("InventoryWalkFeature.tick", InventoryWalkFeature::tick));
    }

    static void tick(Minecraft client) {
        InventoryWalkConfig cfg = InventoryWalkConfig.getInstance();
        LocalPlayer player = client.player;
        Screen screen = McCompat.screen(client);
        boolean eligible = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && cfg.isEnabled() && player != null
                && screen != null && client.getWindow() != null && eligible(screen, cfg) && SkyblockGate.allows();
        if (!eligible) {
            if (active) {
                deactivate(client, screen == null ? "screen closed" : !cfg.isEnabled() ? "switched off"
                        : "screen " + screen.getClass().getSimpleName() + " not eligible");
            }
            return;
        }
        if (!active) {
            active = true;
            activeScreen = screen.getClass().getName();
            activeTicks = 0;
            activeStart = player.position();
            lastPos = activeStart;
            lastKeys = "";
            activations++;
            LOGGER.info("[InvWalk] on in {} (title \"{}\") at {}", activeScreen, screen.getTitle().getString(), fmt(activeStart));
        }
        activeTicks++;

        if (!heldClicks.isEmpty()) {
            heldClickTicks++;
            if (stillToServer(player) || heldClickTicks > 10) {
                flushClicks(client, heldClickTicks > 10 ? "gave up waiting for a still tick" : "still tick reached the server");
            }
        }
        List<String> held = new ArrayList<>();
        boolean paused = pauseLeft > 0 || !heldClicks.isEmpty();
        if (pauseLeft > 0) {
            pauseLeft--;
        }
        apply(client, client.options.keyUp, !paused, held);
        apply(client, client.options.keyDown, !paused, held);
        apply(client, client.options.keyLeft, !paused, held);
        apply(client, client.options.keyRight, !paused, held);
        apply(client, client.options.keyJump, !paused && cfg.isJump(), held);
        apply(client, client.options.keySprint, !paused && cfg.isSprint(), held);

        String keys = String.join(",", held) + (paused ? " (paused)" : "");
        if (!keys.equals(lastKeys)) {
            LOGGER.info("[InvWalk] keys [{}] at {} in {}", keys, fmt(player.position()), screen.getClass().getSimpleName());
            lastKeys = keys;
        }
        boolean moving = !held.isEmpty() && !paused;
        if (moving) {
            walkingTicks++;
        }
        if (activeTicks % 20 == 0) {
            Vec3 now = player.position();
            double moved = lastPos == null ? 0 : Math.hypot(now.x - lastPos.x, now.z - lastPos.z);
            LOGGER.info(String.format(Locale.ROOT, "[InvWalk] %d tick(s) in %s: at %s, %.2f blocks this second, onGround=%s "
                            + "sprinting=%s keys [%s]", activeTicks, screen.getClass().getSimpleName(), fmt(now), moved,
                    player.onGround(), player.isSprinting(), keys));
            lastPos = now;
        }
    }

    /** Sets {@code mapping} to the keyboard's state (or up, when {@code allowed} is false). */
    private static void apply(Minecraft client, KeyMapping mapping, boolean allowed, List<String> held) {
        boolean down = allowed && physicallyDown(client, mapping);
        if (mapping.isDown() != down) {
            mapping.setDown(down);
        }
        if (down) {
            held.add(shortName(mapping.getName()));
        }
    }

    private static boolean physicallyDown(Minecraft client, KeyMapping mapping) {
        Set<String> forced = heldForTest;
        if (forced != null) {
            return forced.contains(mapping.getName());
        }
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key == null || key == InputConstants.UNKNOWN) {
            return false;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return KeyUtil.isKeyDown(client.getWindow(), key.getValue());
    }

    static boolean eligible(Screen screen, InventoryWalkConfig cfg) {
        if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen || screen instanceof BookEditScreen) {
            return false;
        }
        if (screen.getFocused() instanceof EditBox) {
            return false;
        }
        return cfg.getScreens() == InventoryWalkConfig.Screens.ALL || screen instanceof AbstractContainerScreen<?>;
    }

    private static void deactivate(Minecraft client, String why) {
        flushClicks(client, "Inventory Walk off: " + why);
        active = false;
        pauseLeft = 0;
        LocalPlayer player = client.player;
        Vec3 end = player == null ? null : player.position();
        double moved = end == null || activeStart == null ? 0 : Math.hypot(end.x - activeStart.x, end.z - activeStart.z);
        LOGGER.info(String.format(Locale.ROOT, "[InvWalk] off (%s) after %d tick(s) in %s: moved %.2f blocks, ended at %s",
                why, activeTicks, activeScreen, moved, end == null ? "-" : fmt(end)));
        if (McCompat.screen(client) != null && client.options != null) {
            // Still in a screen (switched off, or a text box took focus): let the keys go, as vanilla would have.
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
        }
    }

    /**
     * {@code mixin/InventoryWalkSendMixin}: every packet this client sends. Returns true when a click is held back (Stop To
     * Click): GrimAC's MultiActionsC flags a container click whose server-side state has a movement key in the last input
     * packet, a sprint, or a sneak - every click made while walking drew it (testkit 1025-invwalk-inventory, 2026-10-25).
     */
    public static boolean onPacketSent(Packet<?> packet) {
        if (sendingHeld) {
            return false;
        }
        if (packet instanceof ServerboundContainerClosePacket && !heldClicks.isEmpty()) {
            // The menu closes with clicks still held: they go first, in order, then the close.
            flushClicks(Minecraft.getInstance(), "menu closing");
            return false;
        }
        if (!(packet instanceof ServerboundContainerClickPacket click)) {
            return false;
        }
        clicksTotal++;
        if (!active) {
            return false;
        }
        Minecraft mc0 = Minecraft.getInstance();
        if (InventoryWalkConfig.getInstance().isStopToClick() && mc0.player != null && mc0.isSameThread()
                && (!heldClicks.isEmpty() || !stillToServer(mc0.player))) {
            heldClicks.addLast(packet);
            clicksHeld++;
            if (heldClicks.size() == 1) {
                heldClickTicks = 0;
            }
            LOGGER.info(String.format(Locale.ROOT, "[InvWalk] click container %d slot %d button %d %s held (%d held): the "
                            + "server's last input %s, sprinting=%s - stopping for a still tick first", click.containerId(),
                    click.slotNum(), click.buttonNum(), click.containerInput(), heldClicks.size(),
                    inputText(mc0.player.getLastSentInput()), mc0.player.isSprinting()));
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        boolean moving = !lastKeys.isEmpty() && !lastKeys.endsWith("(paused)");
        if (moving) {
            clicksWhileWalking++;
        }
        int pause = InventoryWalkConfig.getInstance().getPauseAfterClickTicks();
        if (pause > 0) {
            pauseLeft = pause;
        }
        LOGGER.info(String.format(Locale.ROOT, "[InvWalk] click container %d slot %d button %d %s while keys [%s] at %s "
                        + "sprinting=%s%s", click.containerId(), click.slotNum(), click.buttonNum(), click.containerInput(),
                lastKeys, player == null ? "-" : fmt(player.position()), player != null && player.isSprinting(),
                pause > 0 ? ", pausing " + pause + " tick(s)" : ""));
        return false;
    }

    /** The server's last input from him held no movement, jump or sneak, and he is not sprinting. */
    private static boolean stillToServer(LocalPlayer player) {
        Input in = player.getLastSentInput();
        boolean moving = in != null && (in.forward() || in.backward() || in.left() || in.right() || in.jump() || in.shift());
        return !moving && !player.isSprinting();
    }

    private static String inputText(Input in) {
        if (in == null) {
            return "none";
        }
        List<String> keys = new ArrayList<>();
        if (in.forward()) keys.add("forward");
        if (in.backward()) keys.add("back");
        if (in.left()) keys.add("left");
        if (in.right()) keys.add("right");
        if (in.jump()) keys.add("jump");
        if (in.shift()) keys.add("sneak");
        if (in.sprint()) keys.add("sprint");
        return "[" + String.join(",", keys) + "]";
    }

    /** Sends every held click, in order (render thread). */
    private static void flushClicks(Minecraft client, String why) {
        if (heldClicks.isEmpty()) {
            return;
        }
        int n = heldClicks.size();
        LocalPlayer player = client.player;
        LOGGER.info("[InvWalk] {} held click(s) sent after {} tick(s) ({}): server's last input {}, sprinting={}", n,
                heldClickTicks, why, player == null ? "-" : inputText(player.getLastSentInput()),
                player != null && player.isSprinting());
        sendingHeld = true;
        try {
            while (!heldClicks.isEmpty()) {
                Packet<?> p = heldClicks.pollFirst();
                if (client.getConnection() != null) {
                    client.getConnection().send(p);
                }
            }
        } finally {
            sendingHeld = false;
        }
        heldClickTicks = 0;
    }

    public static long clicksHeld() {
        return clicksHeld;
    }

    private static String shortName(String mappingName) {
        return mappingName.startsWith("key.") ? mappingName.substring(4) : mappingName;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    public static boolean isActive() {
        return active;
    }

    public static long activations() {
        return activations;
    }

    public static long walkingTicks() {
        return walkingTicks;
    }

    public static long clicksWhileWalking() {
        return clicksWhileWalking;
    }

    public static long clicksTotal() {
        return clicksTotal;
    }
}
