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
 * Auto-replies with a random cat noise whenever another player's chat message contains "meow", "purr", "nya", "mew"
 * (or a stretched-out version like "meowww"/"purrrrr"), and replies in the SAME channel the message came from
 * (killer560, 2026-10-12): party -> {@code /pc}, guild -> {@code /gc}, officer -> {@code /oc}, co-op -> {@code /cc},
 * a private message -> {@code /msg <sender>}, all chat -> {@code /ac}, and the mod's own Mod Chat -> Mod Chat (the relay,
 * never Hypixel; see {@link #onModChat}). {@code /msg} rather than {@code /r}: {@code /r}
 * answers whoever messaged LAST, which is a different player if a second message lands before the reply goes.
 * <p>
 * The channel is read from the whole line, never from a prefix search: every pattern below is anchored on Hypixel's
 * real line shape (the same shapes as {@code partycommands.CommandChatListener}; "Officer > " as in
 * {@code SupportersChatRewriter}) with a {@code [A-Za-z0-9_]{1,16}} name group, checked with {@code matches()}. So a
 * "Party > Mate: meow" typed in all chat arrives as "[VIP] Bob: Party > Mate: meow", which is ALL_LINE with Bob as the
 * sender, and is answered in {@code /ac}. A line that is none of these (an NPC, a server message, your own outgoing
 * "To Name: ..." echo) gets no reply at all.
 * <p>
 * Your OWN lines never trigger it (the sender group is your name, 2026-09-21), which also means a reply that comes back
 * to you in that channel ("Party > [MVP+] You: purr~") cannot answer itself; the 3 s cooldown after each reply stays
 * as the second guard. Only the message body is searched for the trigger, so a player NAMED "Meow" saying "hi" does
 * not trigger it.
 */
public final class AutoMeowFeature {

    private static final Pattern TRIGGER = Pattern.compile("(?i)\\b(?:meow+|purr+|nya+|mew+)\\b");

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final String RANK = "(?:\\[[^]]{1,20}] )?";
    /** Hypixel caps a chat message at 256 characters; nothing longer is a player's line. */
    private static final String BODY = ": ?(.{1,256})$";

    static final Pattern PARTY_LINE = Pattern.compile("^Party > " + RANK + NAME + "(?: [ቾ⚒])?" + BODY);
    static final Pattern GUILD_LINE = Pattern.compile("^Guild > " + RANK + NAME + "(?: \\[[^]]{1,20}])?" + BODY);
    static final Pattern OFFICER_LINE = Pattern.compile("^Officer > " + RANK + NAME + "(?: \\[[^]]{1,20}])?" + BODY);
    static final Pattern COOP_LINE = Pattern.compile("^Co-op > " + RANK + NAME + "(?: [ቾ⚒])?" + BODY);
    /** "From Bob: meow" or, for a ranked sender, "From [MVP+] Bob: meow". */
    static final Pattern WHISPER_LINE = Pattern.compile("^From " + RANK + NAME + BODY);
    /**
     * All chat: "[302] (emblem) [MVP+] Name: text" on SkyBlock, "[MVP+] Name: text" or "Name: text" elsewhere - the
     * same shape as {@code CommandChatListener.ALL_REGEX}. The emblem is non-ASCII, so "To Name: ..." is not all chat,
     * and the rank is a real Hypixel rank, so "[NPC] Elle: ..." is not a player.
     */
    static final Pattern ALL_LINE = Pattern.compile("^(?:\\[\\d{1,4}] )?(?:[^\\x00-\\x7F]{1,2} )?"
            + "(?:\\[(?:VIP|VIP\\+|MVP|MVP\\+|MVP\\+\\+|YOUTUBE|ADMIN|GM|MOD|HELPER|OWNER|MOJANG|EVENTS|PIG\\+\\+\\+|INNIT)] )?"
            + NAME + ": (.{1,256})$");

    private static final long COOLDOWN_MS = 3000;
    /** Real Minecraft cat sound effects (the "baby" variants are the simple pre-registered constants
     *  this MC version still exposes directly - adult cat sounds are now behind a datapack-driven
     *  registry lookup (CatSoundVariants) that needs a RegistryAccess/RandomSource, unnecessary
     *  complexity for a lighthearted chat-reaction feature). */
    private static final List<Holder<SoundEvent>> CAT_SOUNDS = List.of(
            SoundEvents.CAT_AMBIENT_BABY, SoundEvents.CAT_PURR_BABY, SoundEvents.CAT_PURREOW_BABY);

    private static volatile long lastReplyAtMs = 0;

    /** Where a line came from: its channel command word (without the slash), sender and message body. */
    record Source(String commandWord, String sender, String body) {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> safeOnMessage(com.killer560.hub.util.ChatObserver.raw(message)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) { // the action bar is not chat and must never trigger a reply
                safeOnMessage(com.killer560.hub.util.ChatObserver.raw(message));
            }
        });
    }

    /** Raw packet-path listeners: a throw here disconnects from Hypixel, so none may escape. The channel
     *  patterns are matched on the formatting-stripped, trimmed line. */
    private static void safeOnMessage(String raw) {
        try {
            String text = net.minecraft.ChatFormatting.stripFormatting(raw);
            if (text != null) {
                onMessage(text.trim());
            }
        } catch (RuntimeException e) {
            com.killer560.hub.util.ModLog.get("killer560smod-automeow").error("[AutoMeow] Failed to handle a chat line", e);
        }
    }

    private static void onMessage(String text) {
        if (!AutoMeowConfig.getInstance().isEnabled() || text.length() > 512) {
            return;
        }
        // Cheap prefilter: the body is part of the line, so a line without a trigger word cannot trigger, and most
        // chat lines have none - skip the six whole-line channel patterns for them (and while on cooldown).
        if (System.currentTimeMillis() - lastReplyAtMs < COOLDOWN_MS || !TRIGGER.matcher(text).find()) {
            return;
        }
        reply(classify(text));
    }

    /**
     * A Mod Chat line from the relay ({@code ModChatFeature}'s listener, on the client thread). Mod Chat lines never
     * arrive as Hypixel chat: the relay hands over the sender, stamped from a Mojang-verified login, separately from
     * the text, so there is no line to parse and nothing in the text can pose as another sender. The reply goes back
     * into Mod Chat ({@code "kc"}, which {@code TranslateFeature} sends to the relay and never to Hypixel). The relay
     * echoes your own lines, so your reply comes back here as yours and is ignored. Never throws.
     */
    public static void onModChat(String from, String message) {
        try {
            if (from == null || message == null || message.length() > 512 || !from.matches("[A-Za-z0-9_]{1,16}")
                    || !AutoMeowConfig.getInstance().isEnabled()) {
                return;
            }
            reply(new Source("kc", from, message));
        } catch (RuntimeException e) {
            com.killer560.hub.util.ModLog.get("killer560smod-automeow").error("[AutoMeow] Failed to handle a Mod Chat line", e);
        }
    }

    private static void reply(Source source) {
        long now = System.currentTimeMillis();
        if (now - lastReplyAtMs < COOLDOWN_MS) {
            return;
        }
        if (source == null || !TRIGGER.matcher(source.body()).find()) {
            return;
        }
        if (source.sender().equalsIgnoreCase(selfName())) {
            // killer560 (2026-09-21): "Make it so auto meow does not toggle off of a message that I send."
            return;
        }

        lastReplyAtMs = now;
        String response = AutoMeowLines.ALL.get(ThreadLocalRandom.current().nextInt(AutoMeowLines.ALL.size()));
        TranslateFeature.sendGenerated(response, source.commandWord());

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

    private static String selfName() {
        Minecraft client = Minecraft.getInstance();
        String self = client.player != null ? client.player.getGameProfile().name() : client.getUser().getName();
        return self == null ? "" : self;
    }

    /**
     * The channel a formatting-stripped chat line came from, or null when it is not a player's chat line. Party,
     * guild, officer, co-op and private lines are tried before all chat; each is a whole-line {@code matches()}, so
     * one channel's text quoted inside another's is read as the OUTER channel.
     */
    static Source classify(String text) {
        Matcher m;
        if ((m = PARTY_LINE.matcher(text)).matches()) {
            return new Source("pc", m.group(1), m.group(2));
        }
        if ((m = GUILD_LINE.matcher(text)).matches()) {
            return new Source("gc", m.group(1), m.group(2));
        }
        if ((m = OFFICER_LINE.matcher(text)).matches()) {
            return new Source("oc", m.group(1), m.group(2));
        }
        if ((m = COOP_LINE.matcher(text)).matches()) {
            return new Source("cc", m.group(1), m.group(2));
        }
        if ((m = WHISPER_LINE.matcher(text)).matches()) {
            return new Source("msg " + m.group(1), m.group(1), m.group(2));
        }
        if ((m = ALL_LINE.matcher(text)).matches()) {
            return new Source("ac", m.group(1), m.group(2));
        }
        return null;
    }

    private AutoMeowFeature() {
    }
}
