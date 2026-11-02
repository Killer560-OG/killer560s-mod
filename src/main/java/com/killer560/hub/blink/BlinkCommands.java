package com.killer560.hub.blink;

/**
 * Blink's state readout for the testkit ({@link #debugLines}). It was the {@code /blink} command (start, release,
 * status, debug) until killer560 removed the command on 2026-11-01: Blink runs from its Hold and Charge keys, and every
 * event still goes to the log through {@link BlinkLog}. The class keeps its name because the testkit calls it by name.
 */
final class BlinkCommands {

    private BlinkCommands() {
    }

    /** Every line of the state readout: the state now, then the last {@code events} events. Public for the testkit. */
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
                BlinkCharge.banked(), BlinkCharge.cap(), (BlinkCharge.isArmed() ? " ARMED" : "") + (BlinkCharge.unbacked() > 0 ? " (" + BlinkCharge.unbacked()
                        + " not backed yet)" : ""), BlinkCharge.answersHeld(),
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
}
