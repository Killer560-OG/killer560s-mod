package com.killer560.hub.automeow;

import com.killer560.hub.translate.TranslateFeature;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-replies with a random cat noise whenever anyone's chat message contains "meow", "purr",
 * "nya", "mew" (or a stretched-out version like "meowww"/"purrrrr") - per killer560's request. Replies
 * in the same channel the trigger came in on: Party/Guild/Officer/Co-op are detected from Hypixel's
 * own real chat-line prefixes ("Party &gt; ", etc. - "Party &gt; " already confirmed real elsewhere in
 * this codebase, the others are the same well-established Hypixel convention) and routed the same
 * way {@code CringeFeature}/{@code LeapMessageFeature} already route to a specific channel; a
 * whisper ("From &lt;name&gt;: ...") replies directly to that name via {@code /w}; anything else
 * (plain public chat) replies on whatever the current default outgoing channel is.
 * <p>
 * A short cooldown after each reply is the main safety net here, not sender-identity checking -
 * Hypixel's own party/guild/system chat doesn't come through vanilla's signed-chat path at all (see
 * {@code MagicFindTracker}, which also has to listen on both {@code CHAT} and {@code GAME} for this
 * reason), so there's no reliable structured "who sent this" data available for that traffic to
 * exclude killer560's own messages by identity. The cooldown is simpler and doubles as protection
 * against the bot's own generated reply (which itself contains a trigger word like "purr") echoing
 * back through chat and re-triggering itself in a loop.
 */
public final class AutoMeowFeature {

    private static final Pattern TRIGGER = Pattern.compile("(?i)\\b(?:meow+|purr+|nya+|mew+)\\b");
    private static final Pattern PARTY_PREFIX = Pattern.compile("^Party > ");
    private static final Pattern GUILD_PREFIX = Pattern.compile("^Guild > ");
    private static final Pattern OFFICER_PREFIX = Pattern.compile("^Officer > ");
    private static final Pattern COOP_PREFIX = Pattern.compile("^Co-op > ");
    private static final Pattern WHISPER_PREFIX = Pattern.compile("^From (\\w+): ");
    private static final long COOLDOWN_MS = 3000;
    /** Real Minecraft cat sound effects (the "baby" variants are the simple pre-registered constants
     *  this MC version still exposes directly - adult cat sounds are now behind a datapack-driven
     *  registry lookup (CatSoundVariants) that needs a RegistryAccess/RandomSource, unnecessary
     *  complexity for a lighthearted chat-reaction feature). */
    private static final List<Holder<SoundEvent>> CAT_SOUNDS = List.of(
            SoundEvents.CAT_AMBIENT_BABY, SoundEvents.CAT_PURR_BABY, SoundEvents.CAT_PURREOW_BABY);

    private static volatile long lastReplyAtMs = 0;

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
    }

    private static void onMessage(String text) {
        if (!AutoMeowConfig.getInstance().isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAtMs < COOLDOWN_MS) {
            return;
        }
        if (!TRIGGER.matcher(text).find()) {
            return;
        }

        lastReplyAtMs = now;
        String response = AutoMeowLines.ALL.get(ThreadLocalRandom.current().nextInt(AutoMeowLines.ALL.size()));
        TranslateFeature.sendGenerated(response, resolveChannel(text));

        AutoMeowConfig cfg = AutoMeowConfig.getInstance();
        if (cfg.isPlayCatNoises()) {
            Holder<SoundEvent> sound = CAT_SOUNDS.get(ThreadLocalRandom.current().nextInt(CAT_SOUNDS.size()));
            // forUI(SoundEvent, float, float) is (pitch, volume) - confirmed via javap, since the
            // single-float forUI(SoundEvent, float pitch) overload delegates to this one with a
            // hardcoded 0.25f second argument (vanilla's normal fixed UI-sound volume).
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(sound.value(), 1.0f, cfg.getCatVolume()));
        }
    }

    /** @return the commandWord to route the reply through (e.g. "pc", "w PlayerName"), or null for
     *  whatever the current default outgoing channel is. */
    private static String resolveChannel(String text) {
        if (PARTY_PREFIX.matcher(text).find()) {
            return "pc";
        }
        if (GUILD_PREFIX.matcher(text).find()) {
            return "gc";
        }
        if (OFFICER_PREFIX.matcher(text).find()) {
            return "oc";
        }
        if (COOP_PREFIX.matcher(text).find()) {
            return "cc";
        }
        Matcher whisper = WHISPER_PREFIX.matcher(text);
        if (whisper.find()) {
            return "w " + whisper.group(1);
        }
        return null;
    }

    private AutoMeowFeature() {
    }
}
