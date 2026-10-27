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
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
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
    /**
     * Clicks and menu closes made while moving, in order, held until a still tick has reached the server (Stop To Click).
     * GrimAC MultiActionsC flags a click, and MultiActionsD a close, while the server has a movement key in his last input,
     * a sprint or a sneak - and drops the click, so its item move is lost (testkit 1027-iw-timing, 2026-10-27).
     */
    private static final ArrayDeque<Packet<?>> heldClicks = new ArrayDeque<>();
    private static boolean sendingHeld;
    private static int heldClickTicks;
    private static long clicksHeld;
    private static long closesHeld;
    private static long gaveUp;
    /** Ticks to wait for a still tick before sending anyway (the still tick is forced, so it takes one). */
    private static final int GIVE_UP_TICKS = 10;
    /**
     * The last input and sprint state that really LEFT the connection ({@link #noteLeft}, from Blink's send hook, which
     * sees what Blink and Charge keep back). {@code getLastSentInput} is what the client handed to the connection; with a
     * Blink hold running the server has not seen it yet.
     */
    private static volatile Input serverInput;
    private static volatile Boolean serverSprinting;

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
        if (player == null || client.getConnection() == null) {
            serverInput = null;
            serverSprinting = null;
            if (!heldClicks.isEmpty()) {
                LOGGER.info("[InvWalk] {} held packet(s) dropped: no connection", heldClicks.size());
                heldClicks.clear();
            }
        } else if (!heldClicks.isEmpty()) {
            // Before the screen check: a close is held after its screen has gone, and still needs its still tick.
            heldClickTicks++;
            if (readyToSend(player)) {
                flushClicks(client, "still tick reached the server");
            } else if (heldClickTicks > GIVE_UP_TICKS) {
                gaveUp++;
                flushClicks(client, "gave up waiting for a still tick");
            }
        }
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
        if (!InventoryWalkConfig.getInstance().isEnabled() || !InventoryWalkConfig.getInstance().isStopToClick()) {
            flushClicks(client, "Inventory Walk off: " + why);
        }
        // Otherwise a click or close still held keeps waiting for its still tick (the tick handles it with no screen).
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
        boolean isClick = packet instanceof ServerboundContainerClickPacket;
        boolean isClose = packet instanceof ServerboundContainerClosePacket;
        boolean isButton = packet instanceof ServerboundContainerButtonClickPacket;
        if (!heldClicks.isEmpty() && (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundInteractPacket)) {
            // A use can open a new menu: a close still held must not reach the server after it (it would close that one).
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                flushClicks(mc, "a use is going out");
            }
            return false;
        }
        if (!isClick && !isClose && !isButton) {
            return false;
        }
        if (isClick) {
            clicksTotal++;
        }
        Minecraft mc0 = Minecraft.getInstance();
        if (holdsMenuPackets(mc0) && (!heldClicks.isEmpty() || !readyToSend(mc0.player))) {
            heldClicks.addLast(packet);
            if (isClose) {
                closesHeld++;
            } else {
                clicksHeld++;
            }
            if (heldClicks.size() == 1) {
                heldClickTicks = 0;
            }
            LOGGER.info(String.format(Locale.ROOT, "[InvWalk] %s held (%d held): his last input %s (the server's %s), "
                            + "sprinting=%s - stopping for a still tick first", describe(packet), heldClicks.size(),
                    inputText(mc0.player.getLastSentInput()), inputText(serverInput), mc0.player.isSprinting()));
            return true;
        }
        if (!isClick || !active) {
            return false;
        }
        ServerboundContainerClickPacket click = (ServerboundContainerClickPacket) packet;
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

    /**
     * Stop To Click applies: the cheat build, on, Stop To Click on, the render thread, and either walking in a screen now,
     * packets already held, or a screen it walks in is up (a click the very frame it opened, before this tick saw it).
     */
    private static boolean holdsMenuPackets(Minecraft mc) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || mc.player == null || !mc.isSameThread()) {
            return false;
        }
        InventoryWalkConfig cfg = InventoryWalkConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isStopToClick()) {
            return false;
        }
        if (active || !heldClicks.isEmpty()) {
            return true;
        }
        Screen screen = McCompat.screen(mc);
        return screen != null && eligible(screen, cfg) && SkyblockGate.allows();
    }

    /**
     * The held packets may go: his last input handed to the connection holds no movement, jump or sneak and he is not
     * sprinting, and the server has that input too - or a Blink hold has it queued, and they join the hold behind it.
     */
    private static boolean readyToSend(LocalPlayer player) {
        if (moving(player.getLastSentInput()) || player.isSprinting()) {
            return false;
        }
        return serverStill() || com.killer560.hub.blink.BlinkFeature.isHolding();
    }

    private static boolean serverStill() {
        return !moving(serverInput) && !Boolean.TRUE.equals(serverSprinting);
    }

    /** The last input and sprint state that LEFT the connection hold no movement (Blink Charge's teleport hold asks). */
    public static boolean serverHasStillInput() {
        return serverStill();
    }

    private static boolean moving(Input in) {
        return in != null && (in.forward() || in.backward() || in.left() || in.right() || in.jump() || in.shift());
    }

    /** {@code mixin/InventoryWalkInputMixin}: this tick's input is all keys up while a click or close is held. */
    public static boolean wantsStillTick() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && !heldClicks.isEmpty();
    }

    /** Every packet that really left the connection (Blink's send hook): the server's view of his input and sprint. */
    public static void noteLeft(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerInputPacket in) {
            serverInput = in.input();
        } else if (packet instanceof ServerboundPlayerCommandPacket cmd) {
            if (cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                serverSprinting = true;
            } else if (cmd.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                serverSprinting = false;
            }
        }
    }

    private static String describe(Packet<?> p) {
        if (p instanceof ServerboundContainerClickPacket c) {
            return String.format(Locale.ROOT, "click container %d slot %d button %d %s", c.containerId(), c.slotNum(),
                    c.buttonNum(), c.containerInput());
        }
        if (p instanceof ServerboundContainerClosePacket c) {
            return "close of container " + c.getContainerId();
        }
        if (p instanceof ServerboundContainerButtonClickPacket b) {
            return "button " + b.buttonId() + " in container " + b.containerId();
        }
        return p.getClass().getSimpleName();
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
        boolean joinHold = !serverStill() && com.killer560.hub.blink.BlinkFeature.isHolding();
        LOGGER.info("[InvWalk] {} held packet(s) sent after {} tick(s) ({}): his last input {} (the server's {}), "
                        + "sprinting={}{}", n, heldClickTicks, why, player == null ? "-" : inputText(player.getLastSentInput()),
                inputText(serverInput), player != null && player.isSprinting(), joinHold ? " - into the Blink hold, behind "
                        + "his still tick" : "");
        sendingHeld = true;
        com.killer560.hub.blink.BlinkFeature.clicksJoinHold = joinHold;
        try {
            while (!heldClicks.isEmpty()) {
                Packet<?> p = heldClicks.pollFirst();
                if (client.getConnection() != null) {
                    client.getConnection().send(p);
                }
            }
        } finally {
            sendingHeld = false;
            com.killer560.hub.blink.BlinkFeature.clicksJoinHold = false;
        }
        heldClickTicks = 0;
    }

    public static long clicksHeld() {
        return clicksHeld;
    }

    public static long closesHeld() {
        return closesHeld;
    }

    public static long gaveUp() {
        return gaveUp;
    }

    public static int heldNow() {
        return heldClicks.size();
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
