package com.killer560.hub.voicetotext;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Open Mic's end-of-speech detector (killer560, 2026-10-08: "If my mic is on open mic for voice to text then no message
 * will ever come through. It needs to detect whenever I stop talking and send the message").
 * <p>
 * What was wrong: the old cut was "RMS over a FIXED 500 means voice", and an open mic's room noise (fan, PC, keyboard,
 * a gain set for talking) sits above 500 on many headsets. Every chunk then counted as voice, the 1.2 s of silence never
 * came, the utterance never ended and nothing was ever sent; the buffer just grew. Push To Talk never asked the
 * question, so it kept working.
 * <p>
 * Now the threshold follows the room: the noise floor is the QUIETEST chunk of the last ~3 s (speech has gaps between
 * words, so the minimum is room noise even mid-sentence), and a chunk is voice when it is clearly louder than that floor
 * ({@link #VOICE_OVER_FLOOR} times, and at least {@link #MIN_VOICE_RMS}). An utterance starts on the first voice chunk
 * (with a short pre-roll so the first syllable is not clipped), and ends after {@code silenceMs} without voice; it is
 * returned for transcription if it held at least {@link #MIN_VOICED_MS} of voice, otherwise dropped as a click or a
 * cough. A noise that never stops cannot hold the mic forever: {@link #MAX_UTTERANCE_MS} cuts it, and since the floor
 * rises to a steady noise within ~3 s, such a noise stops counting as voice. Pure Java, no audio device: the capture
 * thread feeds it live chunks and the testkit feeds it synthetic ones (timestamps are passed in).
 */
public final class OpenMicSegmenter {

    /** A chunk is voice when its RMS is this many times the room's noise floor ... */
    static final double VOICE_OVER_FLOOR = 3.0;
    /** ... and at least this loud (16-bit samples), so a dead-silent line does not call breathing speech. */
    static final double MIN_VOICE_RMS = 250.0;
    /** How far back the noise floor looks for its quietest chunk. */
    static final long FLOOR_WINDOW_MS = 3000;
    /** Audio kept from before the first voice chunk. */
    static final long PRE_ROLL_MS = 300;
    /** Less voice than this in an utterance is a click, a cough or a bump: dropped, not sent. Counted in whole 128 ms
     *  chunks, so a 100 ms click straddling two chunks reads 256 ms; 300 needs three voiced chunks. */
    static final long MIN_VOICED_MS = 300;
    /** An utterance is cut here even if it never goes quiet. */
    static final long MAX_UTTERANCE_MS = 15_000;

    /** 16 kHz, 16-bit mono: 32 bytes per millisecond. */
    private static final int BYTES_PER_MS = 32;

    public enum Action { CONTINUE, FINALISE, DISCARD }

    /** What one chunk did. {@code audio} is the finished utterance for FINALISE, otherwise null. */
    public record Result(Action action, byte[] audio, long voicedMs, long silenceMs) {
    }

    private final long silenceMs;
    private final ArrayDeque<long[]> floorWindow = new ArrayDeque<>(); // {timeMs, rms}
    private final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
    private int preRollBytes;
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private boolean speaking;
    private long utteranceStart;
    private long lastVoiceAt;
    private long voicedMs;

    public OpenMicSegmenter(long silenceMs) {
        this.silenceMs = Math.max(200, silenceMs);
    }

    /** Feed one chunk of 16 kHz 16-bit little-endian mono PCM that ended at {@code nowMs}. */
    public Result feed(byte[] buf, int len, long nowMs) {
        double rms = rms(buf, len);
        long chunkMs = Math.max(1, len / BYTES_PER_MS);
        double floor = floor(nowMs, rms);
        boolean voice = rms >= MIN_VOICE_RMS && rms > floor * VOICE_OVER_FLOOR;

        if (!speaking) {
            keepPreRoll(buf, len);
            if (!voice) {
                return new Result(Action.CONTINUE, null, 0, 0);
            }
            speaking = true;
            utterance.reset();
            for (byte[] b : preRoll) {
                utterance.write(b, 0, b.length);
            }
            preRoll.clear();
            preRollBytes = 0;
            utteranceStart = nowMs - chunkMs;
            lastVoiceAt = nowMs;
            voicedMs = chunkMs;
            return new Result(Action.CONTINUE, null, voicedMs, 0);
        }

        utterance.write(buf, 0, len);
        if (voice) {
            lastVoiceAt = nowMs;
            voicedMs += chunkMs;
        }
        long quiet = nowMs - lastVoiceAt;
        if (quiet >= silenceMs || nowMs - utteranceStart >= MAX_UTTERANCE_MS) {
            speaking = false;
            byte[] audio = utterance.toByteArray();
            utterance.reset();
            long voiced = voicedMs;
            voicedMs = 0;
            return voiced >= MIN_VOICED_MS
                    ? new Result(Action.FINALISE, audio, voiced, quiet)
                    : new Result(Action.DISCARD, null, voiced, quiet);
        }
        return new Result(Action.CONTINUE, null, voicedMs, quiet);
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** The quietest chunk of the last {@link #FLOOR_WINDOW_MS}, this one included. */
    private double floor(long nowMs, double rms) {
        floorWindow.addLast(new long[]{nowMs, Math.round(rms)});
        while (!floorWindow.isEmpty() && nowMs - floorWindow.peekFirst()[0] > FLOOR_WINDOW_MS) {
            floorWindow.removeFirst();
        }
        long min = Long.MAX_VALUE;
        for (long[] e : floorWindow) {
            min = Math.min(min, e[1]);
        }
        return min;
    }

    private void keepPreRoll(byte[] buf, int len) {
        byte[] copy = java.util.Arrays.copyOf(buf, len);
        preRoll.addLast(copy);
        preRollBytes += len;
        while (preRollBytes - preRoll.peekFirst().length >= PRE_ROLL_MS * BYTES_PER_MS) {
            preRollBytes -= preRoll.removeFirst().length;
        }
    }

    /** Root-mean-square of {@code len} bytes of 16-bit signed little-endian mono audio. */
    static double rms(byte[] buf, int len) {
        long sumSquares = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sumSquares += (long) sample * sample;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sumSquares / samples);
    }
}
