package com.killer560.hub.blink;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import com.killer560.hub.util.ModChat;

/**
 * {@code /blink} starts one hold (or releases the one running), {@code /blink <ticks>} starts one with that many Hold
 * Ticks, {@code /blink release} releases, {@code /blink status} says what the last hold was, {@code /blink debug} prints
 * the whole state (hold, queue, bank, answers waiting and their age) and the last events, and writes it to the log. Cheat
 * build only (registered from {@link BlinkFeature#register}).
 */
final class BlinkCommands {

    private BlinkCommands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("blink")
                        .executes(c -> toggle())
                        .then(ClientCommands.literal("release").executes(c -> {
                            BlinkFeature.end("command");
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(c -> status()))
                        .then(ClientCommands.literal("debug").executes(c -> debug(12))
                                .then(ClientCommands.argument("events", IntegerArgumentType.integer(0, BlinkLog.KEEP))
                                        .executes(c -> debug(IntegerArgumentType.getInteger(c, "events")))))
                        .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(BlinkConfig.MIN_TICKS,
                                BlinkConfig.MAX_TICKS)).executes(c -> {
                            BlinkConfig cfg = BlinkConfig.getInstance();
                            cfg.setHoldTicks(IntegerArgumentType.getInteger(c, "ticks"));
                            cfg.save();
                            return start();
                        }))));
    }

    private static int toggle() {
        if (BlinkFeature.isHolding()) {
            BlinkFeature.end("command");
            return 1;
        }
        return start();
    }

    private static int start() {
        if (!BlinkConfig.getInstance().isEnabled()) {
            ModChat.send("Blink", ModChat.bad("Blink is switched off in its settings."));
            return 0;
        }
        BlinkFeature.begin("command");
        return 1;
    }

    /** Every line of {@code /blink debug}: the state now, then the last {@code events} events. Public for the testkit. */
    public static java.util.List<String> debugLines(int events) {
        BlinkConfig cfg = BlinkConfig.getInstance();
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Locale r = java.util.Locale.ROOT;
        out.add(String.format(r, "Blink %s; %s", cfg.isEnabled() ? "ON" : "OFF", BlinkLog.settings()));
        out.add(String.format(r, "Hold: %s, %d/%d tick(s), queue %d packet(s) (%d move(s)), largest queue %d; wanted by %s%s; "
                        + "%d release(s) / %d packet(s) in all, last %d tick(s) / %d packet(s), longest %d, %d tick(s) since "
                        + "the last", BlinkFeature.isHolding() ? "HOLDING (" + BlinkFeature.holdOwner() + ")" : "idle",
                BlinkFeature.heldTicks(), cfg.getHoldTicks(), BlinkFeature.heldPackets(), BlinkFeature.heldMoves(),
                BlinkFeature.peakQueue(), BlinkFeature.wantedBy(), BlinkFeature.holdLatched() ? ", waiting for the key to come up"
                        : "", BlinkFeature.releasedHolds(), BlinkFeature.releasedPackets(), BlinkFeature.lastHoldTicks(),
                BlinkFeature.lastHoldPackets(), BlinkFeature.longestHoldTicks(),
                Math.min(BlinkFeature.ticksSinceRelease(), 999999)));
        out.add(String.format(r, "Charge: %s, bank %d/%d%s; answers waiting %d (oldest %d ms, delay %d ms; longest wait %d ms, "
                        + "most waiting %d, %d held in all); %d shot(s) / %d tick(s), %d set back, last %d tick(s) %.2f "
                        + "blocks; %d tick(s) banked in all; %d look(s) carried", BlinkCharge.isActive() ? "CHARGING" : "off",
                BlinkCharge.banked(), BlinkCharge.cap(), BlinkCharge.isArmed() ? " ARMED" : BlinkCharge.settling() ? " (settling after a teleport)" : "", BlinkCharge.answersHeld(),
                BlinkCharge.oldestAnswerAgeMs(), BlinkCharge.answerDelayMs(), BlinkCharge.maxAnswerWaitMs(),
                BlinkCharge.maxAnswersWaiting(), BlinkCharge.answersHeldTotal(), BlinkCharge.shots(), BlinkCharge.shotTicks(),
                BlinkCharge.shotsSetBack(), BlinkCharge.lastShotTicks(), BlinkCharge.lastShotBlocks(), BlinkCharge.bankedTotal(),
                BlinkCharge.rotationsCarried()));
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        out.add(String.format(r, "Server: has him at %s, client at %s; %d teleport(s) held through, %d refused, %d in-place "
                        + "re-sync(s) and %d moving teleport(s) while charging, %d menu click(s) sent live during holds",
                BlinkLog.fmt(BlinkFeature.serverPosition()), mc.player == null ? "-" : BlinkLog.fmt(mc.player.position()),
                BlinkFeature.teleportsHeld(), BlinkFeature.refused(), BlinkCharge.inPlaceTeleports(),
                BlinkCharge.movingTeleports(), BlinkFeature.clicksLiveTotal()));
        java.util.List<String> recent = BlinkLog.recent(events);
        if (events > 0) {
            out.add("Last " + recent.size() + " event(s):");
            out.addAll(recent);
        }
        return out;
    }

    private static int debug(int events) {
        java.util.List<String> lines = debugLines(events);
        for (String line : lines) {
            BlinkLog.LOGGER.info("[Blink] /blink debug: {}", line);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            ModChat.send("Blink", ModChat.text(i < 4 ? line : "§7" + line));
        }
        return 1;
    }

    private static int status() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        ModChat.send("Blink", ModChat.text((BlinkFeature.isHolding() ? "holding " + BlinkFeature.heldTicks() + " tick(s), "
                + BlinkFeature.heldPackets() + " packet(s)" : "not holding") + "; Hold Ticks " + cfg.getHoldTicks()
                + "; last hold " + BlinkFeature.lastHoldTicks() + " tick(s) / " + BlinkFeature.lastHoldPackets()
                + " packet(s); longest " + BlinkFeature.longestHoldTicks() + " tick(s)"));
        return 1;
    }
}
