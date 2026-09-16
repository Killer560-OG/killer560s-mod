package com.killer560.hub.autoroutes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dense per-tick recording behind a route - killer560: "I want it to in theory work the exact same as the first
 * time that I did it... The only thing that could mess it up will be lag."
 * <p>
 * Every sample is one client tick: room-relative feet position, relative look, the raw movement keys and on-ground.
 * Playback does NOT replay the keys for a fixed tick count; it <b>seeks</b> along the samples ({@link #seek}) and
 * walks toward the next unreached one, so a lag spike leaves it behind on the same path instead of desynced, and a
 * rubber-band re-converges on the same sample. The keys are still recorded (they cost little; a future mode may
 * want them) - see the spec's recording model.
 * <p>
 * Stored as ONE compact line in the routes file ({@link #encode}) so the human-readable nodes stay findable in
 * Notepad: {@code x y z yaw pitch keys ground;x y z ...}, positions to 3 decimals, angles to 1.
 */
public final class RoutePath {

    public static final int K_FORWARD = 1;
    public static final int K_BACKWARD = 2;
    public static final int K_LEFT = 4;
    public static final int K_RIGHT = 8;
    public static final int K_JUMP = 16;
    public static final int K_SNEAK = 32;
    public static final int K_SPRINT = 64;

    /** One recorded tick. */
    public record Sample(double x, double y, double z, float yaw, float pitch, int keys, boolean onGround) {
        public boolean forward() { return (keys & K_FORWARD) != 0; }
        public boolean backward() { return (keys & K_BACKWARD) != 0; }
        public boolean left() { return (keys & K_LEFT) != 0; }
        public boolean right() { return (keys & K_RIGHT) != 0; }
        public boolean jump() { return (keys & K_JUMP) != 0; }
        public boolean sneak() { return (keys & K_SNEAK) != 0; }
        public boolean sprint() { return (keys & K_SPRINT) != 0; }

        public double distanceXZ(double px, double pz) {
            double dx = x - px;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public double distance(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static int keysOf(boolean forward, boolean backward, boolean left, boolean right, boolean jump,
                             boolean sneak, boolean sprint) {
        return (forward ? K_FORWARD : 0) | (backward ? K_BACKWARD : 0) | (left ? K_LEFT : 0) | (right ? K_RIGHT : 0)
                | (jump ? K_JUMP : 0) | (sneak ? K_SNEAK : 0) | (sprint ? K_SPRINT : 0);
    }

    private final List<Sample> samples = new ArrayList<>();

    public int size() {
        return samples.size();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public Sample get(int index) {
        return samples.get(Math.max(0, Math.min(samples.size() - 1, index)));
    }

    public Sample last() {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    public void add(Sample sample) {
        samples.add(sample);
    }

    public void clear() {
        samples.clear();
    }

    public List<Sample> samples() {
        return samples;
    }

    /** True when the player at {@code (px, py, pz)} counts as standing on sample {@code i}. Horizontal tolerance is
     *  tight (the path shape is the point), vertical is loose (a jump arc's samples are reached from the ground). */
    public boolean reached(int i, double px, double py, double pz, double reachXZ, double reachY) {
        Sample s = samples.get(i);
        return s.distanceXZ(px, pz) <= reachXZ && Math.abs(s.y - py) <= reachY;
    }

    /**
     * The furthest sample in {@code [cursor, cursor + window]} the player has reached, or {@code cursor} when none.
     * Skipping over samples the player never exactly touched is deliberate - a sprint-jump lands past several
     * samples at once, and a rubber-band that drops the player back onto an earlier stretch simply re-reaches the
     * same samples again from a lower cursor (the caller keeps the maximum).
     */
    public int seek(int cursor, int window, double px, double py, double pz, double reachXZ, double reachY) {
        int best = cursor;
        int end = Math.min(samples.size() - 1, cursor + window);
        for (int i = Math.max(0, cursor); i <= end; i++) {
            if (reached(i, px, py, pz, reachXZ, reachY)) {
                best = i;
            }
        }
        return best;
    }

    /** Index of the nearest sample to the point within {@code [from, to]}, by 3D distance. */
    public int nearest(int from, int to, double px, double py, double pz) {
        int best = Math.max(0, from);
        double bestDist = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i <= Math.min(samples.size() - 1, to); i++) {
            double d = samples.get(i).distance(px, py, pz);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** First sample at least {@code distance} blocks (along the path) past {@code cursor} - the "carrot". */
    public int lookahead(int cursor, double distance) {
        double acc = 0;
        int i = Math.max(0, cursor);
        while (i < samples.size() - 1 && acc < distance) {
            Sample a = samples.get(i);
            Sample b = samples.get(i + 1);
            acc += a.distance(b.x, b.y, b.z);
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------------------------------- file format

    public String encode() {
        StringBuilder sb = new StringBuilder(samples.size() * 40);
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(String.format(Locale.US, "%.3f %.3f %.3f %.1f %.1f %d %d",
                    s.x, s.y, s.z, s.yaw, s.pitch, s.keys, s.onGround ? 1 : 0));
        }
        return sb.toString();
    }

    /**
     * Parses {@link #encode}'s format. Never throws: a malformed sample ends the path there (the samples before it
     * are kept), anything non-finite or absurdly far from the room is dropped, and at most {@code maxSamples} are
     * read - this is data another person may have written.
     */
    public static RoutePath decode(String text, int maxSamples, double maxAbsCoord) {
        RoutePath path = new RoutePath();
        if (text == null || text.isBlank()) {
            return path;
        }
        String[] parts = text.split(";");
        for (String part : parts) {
            if (path.samples.size() >= maxSamples) {
                break;
            }
            String[] f = part.trim().split("\\s+");
            if (f.length < 7) {
                break;
            }
            try {
                double x = Double.parseDouble(f[0]);
                double y = Double.parseDouble(f[1]);
                double z = Double.parseDouble(f[2]);
                float yaw = Float.parseFloat(f[3]);
                float pitch = Float.parseFloat(f[4]);
                int keys = Integer.parseInt(f[5]);
                boolean ground = !"0".equals(f[6]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)
                        || !Float.isFinite(pitch) || Math.abs(x) > maxAbsCoord || Math.abs(y) > maxAbsCoord
                        || Math.abs(z) > maxAbsCoord) {
                    continue;
                }
                path.samples.add(new Sample(x, y, z, yaw, Math.max(-90f, Math.min(90f, pitch)), keys & 127, ground));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return path;
    }
}
