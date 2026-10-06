package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;

/**
 * The mod's own sounds, from {@code assets/killer560smod/sounds.json}.
 *
 * <p>{@code killer560smod:correction_alarm} is an original synthesized siren wail (2.6 s, mono Ogg Vorbis, generated with
 * numpy - no sampled audio) that plays when the server corrects his position under an automation (killer560,
 * 2026-10-06: "like a scary alarm"). The event is created with {@link SoundEvent#createVariableRangeEvent} and NOT added
 * to {@code BuiltInRegistries.SOUND_EVENT}: a client-side play needs only the sounds.json entry (the sound manager
 * resolves the event by its location), and a client-only entry in a registry the server syncs is a mismatch waiting to
 * happen. Same API on 26.1.2 and 26.2 (javap: {@code createVariableRangeEvent(Identifier)} and
 * {@code SimpleSoundInstance.forUI(SoundEvent, pitch, volume)}, category UI).
 */
public final class ModSounds {

    private static final Logger LOGGER = ModLog.get("killer560smod-sounds");

    public static final SoundEvent CORRECTION_ALARM =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("killer560smod", "correction_alarm"));

    /** The alarm is 2.6 s long; a burst of corrections inside that plays it once. */
    private static final long ALARM_MIN_GAP_MS = 3000L;

    private static long lastAlarmMs;
    private static int alarmsPlayed;
    private static int alarmsSuppressed;

    private ModSounds() {
    }

    /**
     * Plays the correction alarm, client-side only, at the Correction Alarm volume (Home tab), at most once every 3 s.
     * Safe from any thread. Does nothing when the alarm is switched off or its volume is 0.
     */
    public static void playCorrectionAlarm() {
        CorrectionAlarmConfig cfg = CorrectionAlarmConfig.getInstance();
        float volume = cfg.getVolume();
        if (!cfg.isEnabled() || volume <= 0f) {
            return;
        }
        synchronized (ModSounds.class) {
            long now = System.currentTimeMillis();
            if (lastAlarmMs != 0L && now - lastAlarmMs < ALARM_MIN_GAP_MS) {
                alarmsSuppressed++;
                return;
            }
            lastAlarmMs = now;
            alarmsPlayed++;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                client.getSoundManager().play(SimpleSoundInstance.forUI(CORRECTION_ALARM, 1.0f, volume));
            } catch (RuntimeException e) {
                LOGGER.warn("[Sounds] correction alarm failed to play", e);
            }
        });
    }

    /** How many times the alarm actually started (the testkit reads this; the test client is muted). */
    public static int correctionAlarmsPlayed() {
        return alarmsPlayed;
    }

    /** Alarms skipped by the 3 s rate limit. */
    public static int correctionAlarmsSuppressed() {
        return alarmsSuppressed;
    }
}
