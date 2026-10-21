package com.killer560.hub.clicksound;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sound Effects tab's replace step (killer560, 2026-10-18). {@link ClickSounds#replace} hands every game sound that is
 * not the button click here; if it is an enabled {@link SoundEffect} with a choice other than Default, the chosen sound
 * is played where the game's one was - same position, pitch and volume - instead, and the game's own sound is not played
 * at all ({@code SoundManagerClickMixin} cancels it and plays the returned one). Called from the same
 * {@code SoundManager.play} hook as the click, so it covers the same sounds on every screen and in the world.
 *
 * <p>Owner, 2026-10-20: "all those sounds still don't play. Make sure it prevents the normal sound from playing, then
 * plays the new one." The game hands {@code SoundManager.play} an instance that is not resolved yet, so its
 * {@code getVolume()} and {@code getPitch()} throw on the null sound; the pitch read did, every time, and the catch
 * below handed back the game's sound. The instance is now resolved first.
 */
public final class SoundEffects {

    private static final Logger LOGGER = ModLog.get("killer560smod-soundeffects");

    /** One game event can be several sounds in one tick (a knockback crit is two attack sounds): play the choice once. */
    private static final long SAME_EVENT_NANOS = 50_000_000L;
    private static final double SAME_EVENT_DIST_SQ = 1.0;

    private static volatile int replaced;
    private static volatile int merged;
    private static volatile int fallbacks;
    private static volatile String lastEffect = "";
    private static volatile String lastPlayedId = "";
    private static final int[] REPLACED_BY_EFFECT = new int[SoundEffect.values().length];
    /** Effect + choice pairs already reported as missing, so the chat line is said once, not on every hit. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static SoundEffect lastMergeEffect;
    private static long lastMergeNanos;
    private static double lastX;
    private static double lastY;
    private static double lastZ;

    private SoundEffects() {
    }

    /**
     * The instance to play for {@code in}: the chosen effect sound, {@code in} itself (not an effect, off, Default, or the
     * choice is missing), or null when this is a second sound of an event whose replacement already played.
     */
    static SoundInstance replace(SoundInstance in) {
        SoundEffectConfig cfg = SoundEffectConfig.getInstance();
        if (!cfg.anyOn()) {
            return in;
        }
        SoundEffect effect = SoundEffect.of(in.getIdentifier());
        if (effect == null || !cfg.isOn(effect)) {
            return in;
        }
        String choice = cfg.getChoice(effect);
        if (ClickSounds.DEFAULT_ID.equals(choice)) {
            return in;
        }
        try {
            SoundManager sounds = Minecraft.getInstance().getSoundManager();
            if (!ClickSounds.available(choice, sounds)) {
                fallbacks++;
                if (WARNED.add(effect.key + "|" + choice)) {
                    ModChat.send("Sound Effects", ModChat.text(effect.label + ": "),
                            ModChat.bad(ClickSounds.label(choice) + " is missing"),
                            ModChat.text(", playing the game's sound."));
                }
                return in;
            }
            // Resolve the game's instance first: until then it has no Sound, and getVolume / getPitch throw. It is never
            // played, so resolving it only reads its volume and pitch.
            float volume = 1.0f;
            try {
                in.resolve(sounds);
                volume = in.getVolume();
            } catch (RuntimeException ignored) {
                // use the default volume; make() reads the pitch the same careful way
            }
            long now = System.nanoTime();
            if (effect == lastMergeEffect && now - lastMergeNanos < SAME_EVENT_NANOS
                    && distSq(in) < SAME_EVENT_DIST_SQ) {
                merged++;
                return null;
            }
            SoundInstance made = ClickSounds.make(choice, volume, in);
            if (made == null) {
                return in;
            }
            lastMergeEffect = effect;
            lastMergeNanos = now;
            lastX = in.getX();
            lastY = in.getY();
            lastZ = in.getZ();
            replaced++;
            REPLACED_BY_EFFECT[effect.ordinal()]++;
            lastEffect = effect.key;
            lastPlayedId = String.valueOf(made.getIdentifier());
            ClickSounds.recordPlayed(lastPlayedId);
            return made;
        } catch (RuntimeException e) {
            LOGGER.warn("[SoundEffects] could not play the {} sound, using the game's", effect.key, e);
            return in;
        }
    }

    private static double distSq(SoundInstance in) {
        double dx = in.getX() - lastX;
        double dy = in.getY() - lastY;
        double dz = in.getZ() - lastZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Game sounds replaced by an effect (testkit evidence). */
    public static int replaced() {
        return replaced;
    }

    /** Game sounds replaced by {@code key}'s effect (testkit evidence), 0 for an unknown key. */
    public static int replaced(String key) {
        for (SoundEffect e : SoundEffect.values()) {
            if (e.key.equals(key)) {
                return REPLACED_BY_EFFECT[e.ordinal()];
            }
        }
        return 0;
    }

    /** Second sounds of one event that were silenced because the replacement already played (testkit evidence). */
    public static int merged() {
        return merged;
    }

    /** Effect sounds that played the game's own because the chosen preset or import was missing (testkit evidence). */
    public static int fallbacks() {
        return fallbacks;
    }

    /** The key of the last effect replaced, "" before any (testkit evidence). */
    public static String lastEffect() {
        return lastEffect;
    }

    /** The id of the sound the last replaced effect played, "" before any (testkit evidence). */
    public static String lastPlayedId() {
        return lastPlayedId;
    }
}
