package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McPackets;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.MouseButtons;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Held Left Click diagnostics (killer560, 2026-11-03: "while mining something keeps making my left click stop holding
 * break"). Nothing in the mod's code was found that lets go of the attack key or stops a dig in the Dwarven Mines
 * (mining-1103: every key release, screen, mouse, slot, item and dig path was checked against 26.3's javap). On 26.3 a held
 * dig ends for good, until a fresh press, only when a screen opens (Gui.setScreen calls KeyMapping.releaseAll and the
 * re-grab sets missTime) or the operating system sends a left-button release (a focus or capture loss). This logs which of
 * the two it was the next time it happens, so the cause can be read off his log instead of guessed. Read only: nothing is
 * pressed, released, sent or changed.
 *
 * <p>Two lines, both "[AttackHold]" in killer560smod-miningqol, at most one a second:
 * <ul>
 *   <li>A screen initialized while the attack key is held: the screen's class and the code that opened it.</li>
 *   <li>The attack key went up while the game had seen no left-button release (a screen's release, or none at all),
 *       or while the window had lost focus.</li>
 * </ul>
 *
 * <p>His 2026-11-03 log showed the drops were server-opened containers ("Commissions", 2-3 s after "X Commission
 * Complete!", with nothing he clicked in the stack). To name what made the server open them, the probe keeps the last
 * {@link #RING} packets this client SENT (type and a short summary: use item, use item on a block, interact with an
 * entity and its name, player action, container click, command name only - never chat text or command arguments; moves,
 * keep-alives, pongs, tick ends and chunk acks are only counted) and the last {@link #LINES} chat and title lines, and
 * dumps both with the container's title and size whenever a container opens while left click is held (one dump each 5 s
 * at most). Recording is a field write per packet on a mining island; nothing is changed or sent.
 *
 * <p>His next log showed every logged drop was a menu he opened himself (Emissary clicks, HOTM), so the menu case is not
 * his bug. The STUCK DIG line covers the case he means, "when I am actually mining": left click held, no screen, crosshair
 * on the same unchanged block for {@link #STALL_MS}; it dumps the block, held item and the same two rings.
 */
public final class AttackHoldProbe {

    private static final Logger LOGGER = ModLog.get("killer560smod-miningqol");
    private static final long MIN_GAP_MS = 1000L;

    private static boolean wasDown = false;
    private static long lastScreenLogMs = 0L;
    private static long lastReleaseLogMs = 0L;
    private static int screenLines = 0;
    private static int releaseLines = 0;

    static final int RING = 40;
    static final int LINES = 10;
    private static final long DUMP_GAP_MS = 5000L;
    private static final String[] SENT = new String[RING];
    private static final long[] SENT_MS = new long[RING];
    private static int sentHead = 0;
    private static int sentCount = 0;
    private static long quietPackets = 0;
    private static final String[] HEARD = new String[LINES];
    private static final long[] HEARD_MS = new long[LINES];
    private static int heardHead = 0;
    private static int heardCount = 0;
    private static long lastDumpMs = 0L;
    private static int dumps = 0;

    private AttackHoldProbe() {
    }

    static void register() {
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            try {
                onScreen(client, screen);
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
            try {
                HeldBreakResume.tick(client);
            } catch (RuntimeException e) {
                LOGGER.error("[AttackHold] resume tick failed", e);
            }
        });
        // Raw chat events run on the packet path, where a throw disconnects him: everything is caught.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                if (!overlay) {
                    heard("chat", message);
                }
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
        });
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, ts) -> {
                    try {
                        heard("chat", message);
                    } catch (RuntimeException ignored) {
                        // diagnostics only
                    }
                });
    }

    /** A packet this client is sending (MiningSendTapMixin, any thread that sends). */
    public static void onSend(Packet<?> packet) {
        Minecraft client = Minecraft.getInstance();
        if (packet == null || client.player == null || !MiningState.onMiningIsland()) {
            return;
        }
        if (packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundPongPacket || packet instanceof ServerboundClientTickEndPacket
                || packet instanceof ServerboundChunkBatchReceivedPacket) {
            quietPackets++;
            return;
        }
        String s = summary(client, packet);
        synchronized (SENT) {
            SENT[sentHead] = s;
            SENT_MS[sentHead] = System.currentTimeMillis();
            sentHead = (sentHead + 1) % RING;
            sentCount = Math.min(sentCount + 1, RING);
        }
    }

    static String summary(Minecraft client, Packet<?> packet) {
        String type = packet.type().id().getPath();
        if (packet instanceof ServerboundUseItemPacket p) {
            return type + " hand=" + McPackets.useItemHand(p);
        }
        if (packet instanceof ServerboundUseItemOnPacket p) {
            var hit = McPackets.useItemOnHitResult(p);
            return type + " hand=" + McPackets.useItemOnHand(p) + " at " + hit.getBlockPos().toShortString() + " "
                    + hit.getDirection();
        }
        if (packet instanceof ServerboundInteractPacket p) {
            return type + " entity " + p.entityId() + " " + entityName(client, p.entityId()) + " hand=" + p.hand();
        }
        if (packet instanceof ServerboundAttackPacket p) {
            return type + " entity " + p.entityId() + " " + entityName(client, p.entityId());
        }
        if (packet instanceof ServerboundPlayerActionPacket p) {
            return type + " " + p.getAction() + " " + p.getPos().toShortString() + " " + p.getDirection();
        }
        if (packet instanceof ServerboundContainerClickPacket p) {
            return type + " container " + p.containerId() + " slot " + p.slotNum() + " button " + p.buttonNum() + " "
                    + p.containerInput();
        }
        if (packet instanceof ServerboundContainerClosePacket p) {
            return type + " container " + p.getContainerId();
        }
        if (packet instanceof ServerboundSetCarriedItemPacket p) {
            return type + " slot " + p.getSlot();
        }
        if (packet instanceof ServerboundChatCommandPacket p) {
            return type + " /" + commandName(p.command());
        }
        if (packet instanceof ServerboundChatCommandSignedPacket p) {
            return type + " /" + commandName(p.command());
        }
        if (packet instanceof ServerboundChatPacket) {
            return type + " (text not recorded)";
        }
        return type;
    }

    /** The command's name only: arguments can hold anything he typed. */
    static String commandName(String command) {
        if (command == null) {
            return "";
        }
        String c = command.strip();
        int sp = c.indexOf(' ');
        String name = sp < 0 ? c : c.substring(0, sp);
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    private static String entityName(Minecraft client, int id) {
        if (client.level == null) {
            return "?";
        }
        Entity e = client.level.getEntity(id);
        if (e == null) {
            return "(not loaded)";
        }
        String n = net.minecraft.ChatFormatting.stripFormatting(e.getName().getString());
        return e.getType().toShortString() + " \"" + (n == null ? "" : clip(n, 40)) + "\"";
    }

    /** A chat or title line the server sent (title hooks: MiningTitleTapMixin). */
    public static void heard(String kind, Component text) {
        if (text == null || !MiningState.onMiningIsland()) {
            return;
        }
        String plain = net.minecraft.ChatFormatting.stripFormatting(text.getString());
        if (plain == null || plain.isBlank()) {
            return;
        }
        synchronized (HEARD) {
            HEARD[heardHead] = kind + ": " + clip(plain.strip(), 120);
            HEARD_MS[heardHead] = System.currentTimeMillis();
            heardHead = (heardHead + 1) % LINES;
            heardCount = Math.min(heardCount + 1, LINES);
        }
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /** The dump: the opened container, then the sent and heard rings oldest first. */
    private static void dump(Screen screen) {
        long now = System.currentTimeMillis();
        if (now - lastDumpMs < DUMP_GAP_MS) {
            return;
        }
        lastDumpMs = now;
        dumps++;
        String title = net.minecraft.ChatFormatting.stripFormatting(screen.getTitle().getString());
        int size = screen instanceof AbstractContainerScreen<?> acs ? acs.getMenu().slots.size() : -1;
        StringBuilder b = new StringBuilder();
        b.append("[AttackHold] container \"").append(title).append("\" (").append(size)
                .append(" slots) opened while you held left click. Sent before it (oldest first, ").append(quietPackets)
                .append(" moves/keep-alives not listed):");
        synchronized (SENT) {
            for (int i = 0; i < sentCount; i++) {
                int k = (sentHead - sentCount + i + RING) % RING;
                b.append("\n    -").append(now - SENT_MS[k]).append(" ms  ").append(SENT[k]);
            }
        }
        b.append("\n  Heard before it:");
        synchronized (HEARD) {
            for (int i = 0; i < heardCount; i++) {
                int k = (heardHead - heardCount + i + LINES) % LINES;
                b.append("\n    -").append(now - HEARD_MS[k]).append(" ms  ").append(HEARD[k]);
            }
        }
        LOGGER.info(b.toString());
    }

    private static boolean watching(Minecraft client) {
        return client.player != null && client.level != null && MiningState.onMiningIsland();
    }

    private static void onScreen(Minecraft client, Screen screen) {
        // The physical button: Gui.setScreen has already released the key mapping by the time a screen initializes.
        if (!watching(client) || !MouseButtons.isDown(0) || screen instanceof ChatScreen || !gap(true)) {
            return;
        }
        screenLines++;
        StringBuilder trace = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int shown = 0;
        for (int i = 2; i < st.length && shown < 14; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("java.") || c.contains("AttackHoldProbe") || c.contains("fabricmc.fabric.impl")) {
                continue;
            }
            trace.append(" <- ").append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName());
            shown++;
        }
        LOGGER.info("[AttackHold] screen {} opened while you held left click (this releases it){}",
                screen == null ? "null" : screen.getClass().getName(), trace);
        if (screen instanceof AbstractContainerScreen<?>) {
            dump(screen);
        }
    }

    private static void tick(Minecraft client) {
        boolean down = client.options.keyAttack.isDown();
        if (wasDown && !down && watching(client)) {
            boolean buttonStillDown = MouseButtons.isDown(0);
            boolean active = client.isWindowActive();
            Screen screen = McCompat.screen(client);
            // A plain let-go of the button is the normal case and is not logged.
            if ((buttonStillDown || !active || screen != null) && gap(false)) {
                releaseLines++;
                LOGGER.info("[AttackHold] left click let go without your release: button still down {}, window focused {},"
                                + " screen {}, mouse grabbed {}", buttonStillDown, active,
                        screen == null ? "none" : screen.getClass().getSimpleName(), client.mouseHandler.isMouseGrabbed());
            }
        }
        wasDown = down;
        stallTick(client, down);
    }

    // ---- stall while mining (owner 2026-11-03: "it should be when i am actually mining")
    static final long STALL_MS = 5000L;
    private static net.minecraft.core.BlockPos stallPos = null;
    private static net.minecraft.world.level.block.state.BlockState stallState = null;
    private static long stallSinceMs = 0L;
    private static boolean stallLogged = false;
    private static int stallLines = 0;
    private static int stageCount = 0;
    private static int stageLast = -2;
    private static int stageMax = -2;
    private static long stageLastMs = 0L;

    /** The server's crack stage for a block (ObsidianDigPacketMixin, render thread); kept for the stuck block only. */
    public static void onServerStage(net.minecraft.core.BlockPos pos, int progress) {
        if (pos == null || stallPos == null || !pos.equals(stallPos)) {
            return;
        }
        stageCount++;
        stageLast = progress;
        stageMax = Math.max(stageMax, progress);
        stageLastMs = System.currentTimeMillis();
    }

    /**
     * Left click held, no screen, window focused, crosshair on the same block whose state has not changed for
     * {@link #STALL_MS}: the dig is stuck. Logs one dump per stuck block (and at most one per 5 s overall).
     */
    private static void stallTick(Minecraft client, boolean down) {
        net.minecraft.core.BlockPos pos = null;
        net.minecraft.world.level.block.state.BlockState state = null;
        if (down && watching(client) && McCompat.screen(client) == null && client.isWindowActive()
                && client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            pos = hit.getBlockPos();
            state = client.level.getBlockState(pos);
            if (state.isAir()) {
                pos = null;
            }
        }
        long now = System.currentTimeMillis();
        if (pos == null || !pos.equals(stallPos) || state != stallState) {
            stallPos = pos;
            stallState = state;
            stallSinceMs = now;
            stallLogged = false;
            stageCount = 0;
            stageLast = -2;
            stageMax = -2;
            return;
        }
        if (stallLogged || now - stallSinceMs < STALL_MS || now - lastDumpMs < DUMP_GAP_MS) {
            return;
        }
        stallLogged = true;
        stallLines++;
        lastDumpMs = now;
        String p = pos.toShortString();
        int digs = 0;
        StringBuilder b = new StringBuilder();
        b.append("[AttackHold] STUCK DIG: left click held ").append(now - stallSinceMs).append(" ms on ")
                .append(state.getBlock().getName().getString()).append(" at ").append(p).append(" holding \"")
                .append(clip(String.valueOf(net.minecraft.ChatFormatting.stripFormatting(
                        client.player.getMainHandItem().getHoverName().getString())), 40))
                .append("\" slot ").append(client.player.getInventory().getSelectedSlot())
                .append(", no screen. Sent before it (oldest first, ").append(quietPackets)
                .append(" moves/keep-alives not listed):");
        synchronized (SENT) {
            for (int i = 0; i < sentCount; i++) {
                int k = (sentHead - sentCount + i + RING) % RING;
                if (SENT[k] != null && SENT[k].contains(p)) {
                    digs++;
                }
                b.append("\n    -").append(now - SENT_MS[k]).append(" ms  ").append(SENT[k]);
            }
        }
        b.append("\n  Dig packets naming this block in that list: ").append(digs);
        b.append("\n  Server crack stages for this block: ").append(stageCount == 0 ? "none heard"
                : stageCount + " update(s), highest " + stageMax + ", last " + stageLast + " ("
                        + (now - stageLastMs) + " ms ago)");
        b.append("\n  Heard before it:");
        synchronized (HEARD) {
            for (int i = 0; i < heardCount; i++) {
                int k = (heardHead - heardCount + i + LINES) % LINES;
                b.append("\n    -").append(now - HEARD_MS[k]).append(" ms  ").append(HEARD[k]);
            }
        }
        LOGGER.info(b.toString());
    }

    public static int stallLines() {
        return stallLines;
    }

    /** One line of each kind a second at most. */
    private static boolean gap(boolean screenLine) {
        long now = System.currentTimeMillis();
        long last = screenLine ? lastScreenLogMs : lastReleaseLogMs;
        if (now - last < MIN_GAP_MS) {
            return false;
        }
        if (screenLine) {
            lastScreenLogMs = now;
        } else {
            lastReleaseLogMs = now;
        }
        return true;
    }

    // ---- testkit hooks
    public static int screenLines() {
        return screenLines;
    }

    public static int releaseLines() {
        return releaseLines;
    }

    public static int dumps() {
        return dumps;
    }

    /** The sent ring, oldest first (testkit: what the dump lists). */
    public static java.util.List<String> sentForTest() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (SENT) {
            for (int i = 0; i < sentCount; i++) {
                out.add(SENT[(sentHead - sentCount + i + RING) % RING]);
            }
        }
        return out;
    }

    public static java.util.List<String> heardForTest() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (HEARD) {
            for (int i = 0; i < heardCount; i++) {
                out.add(HEARD[(heardHead - heardCount + i + LINES) % LINES]);
            }
        }
        return out;
    }

    /** Lets the next container open dump at once (testkit). */
    public static void resetDumpGapForTest() {
        lastDumpMs = 0L;
        lastScreenLogMs = 0L;
        lastReleaseLogMs = 0L;
    }
}
