package com.killer560.hub.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autocorrect.AutoCorrectConfig;
import com.killer560.hub.autocorrect.AutoCorrectFeature;
import com.killer560.hub.emotes.ChatEmoteConfig;
import com.killer560.hub.emotes.ChatEmoteFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates outgoing chat, trying Google Translate's public web endpoint first (the same one the
 * "translate" browser extension family uses - no API key needed, but observed returning HTTP 429
 * on real testing 2026-09-02, seemingly because it wants a browser-like User-Agent - added one)
 * and falling back to MyMemory's public translation API (also no key needed, source language
 * fixed to English there since it doesn't support "auto") if Google still fails. Hooked into
 * {@link com.killer560.hub.translate.mixin.ChatScreenMixin}, which cancels the vanilla send and
 * calls {@link #tryIntercept} instead whenever the feature is on and the typed line isn't a
 * command. {@link #tryIntercept} also owns applying Auto Correct
 * ({@link com.killer560.hub.autocorrect.AutoCorrectFeature}) and Chat Emotes
 * ({@link com.killer560.hub.emotes.ChatEmoteFeature}) first, since all three act on the same
 * outgoing-chat pipeline and need to compose in order: Auto Correct's result feeds into Chat
 * Emotes, and whatever text Chat Emotes leaves over (if any) feeds into Translate.
 */
public final class TranslateFeature {

    /** @param detectedLanguageCode the source language Google/MyMemory actually detected (e.g.
     *  "ja"), or null if the provider didn't report one - only meaningful when the source was
     *  passed as "auto" (outgoing chat always passes a fixed "en" source, so it's never populated
     *  there). */
    public record TranslationResult(String text, String detectedLanguageCode) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-translate");

    /**
     * Hypixel routes chat to a specific channel via a leading slash command (e.g. "/ac hello" for
     * All Chat, "/pc" Party, "/gc" Guild, "/oc" Officer, "/cc" Co-op, "/w"/"/msg"/"/tell"/"/t" for a
     * whisper, "/r" to reply) - these are still real chat messages, just with a channel prefix, so
     * they should still get translated (only the payload after the command word, not the command
     * itself). Any other leading-"/" input (e.g. "/killer560", "/help") is a real client/server
     * command and must be left completely alone.
     */
    private static final Pattern CHAT_ROUTING_COMMAND =
            Pattern.compile("^/(ac|pc|gc|oc|cc|w|msg|whisper|tell|t|r|reply)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private static final String GOOGLE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String MYMEMORY_ENDPOINT = "https://api.mymemory.translated.net/get";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-translate");
        t.setDaemon(true);
        return t;
    });

    /**
     * @return true if this call was handled here (the vanilla send must be cancelled), false if
     * the caller should let the vanilla chat-send logic run as normal.
     */
    public static boolean tryIntercept(String normalizedMessage, boolean addToHistory) {
        if (normalizedMessage.isEmpty()) {
            return false;
        }

        TranslateConfig cfg = TranslateConfig.getInstance();
        boolean translateActive = cfg.isEnabled() && !cfg.getTargetLanguageCode().isBlank()
                // Nothing to translate to - and translating anyway would ask MyMemory for English ->
                // English, which it rejects with a literal "PLEASE SELECT TWO DISTINCT LANGUAGES"
                // 200 response that would otherwise get sent to chat as if it were a real translation.
                && !"en".equalsIgnoreCase(cfg.getTargetLanguageCode());
        boolean autoCorrectActive = AutoCorrectConfig.getInstance().isEnabled();
        boolean emotesActive = ChatEmoteConfig.getInstance().isEnabled();
        LOGGER.info("tryIntercept called: message=\"{}\" translateActive={} autoCorrectActive={} emotesActive={}",
                normalizedMessage, translateActive, autoCorrectActive, emotesActive);

        if (!translateActive && !autoCorrectActive && !emotesActive) {
            return false;
        }

        String commandWord = null;
        String toProcess = normalizedMessage;
        if (normalizedMessage.startsWith("/")) {
            Matcher m = CHAT_ROUTING_COMMAND.matcher(normalizedMessage);
            if (!m.matches()) {
                // A real command (e.g. "/killer560", "/help") - leave it to vanilla untouched.
                return false;
            }
            commandWord = m.group(1);
            toProcess = m.group(2);
        }

        processAndSend(toProcess, commandWord, addToHistory, translateActive, autoCorrectActive, emotesActive, cfg);
        return true;
    }

    /**
     * Runs {@code message} through this mod's own outgoing chat pipeline (Auto Correct -> Chat
     * Emotes -> Translate, respecting killer560's current settings for each) and sends it - for
     * anything that generates its own outgoing chat line rather than being typed (e.g. {@code
     * /cringe}), so those lines still get translated/corrected the same way real typed chat would.
     */
    public static void sendGenerated(String message) {
        sendGenerated(message, null);
    }

    /**
     * Same as {@link #sendGenerated(String)}, but routed to a specific Hypixel chat channel (e.g.
     * "pc" for Party Chat) instead of whatever the player's default channel currently is.
     */
    public static void sendGenerated(String message, String commandWord) {
        if (message == null || message.isBlank()) {
            return;
        }
        TranslateConfig cfg = TranslateConfig.getInstance();
        boolean translateActive = cfg.isEnabled() && !cfg.getTargetLanguageCode().isBlank()
                && !"en".equalsIgnoreCase(cfg.getTargetLanguageCode());
        boolean autoCorrectActive = AutoCorrectConfig.getInstance().isEnabled();
        boolean emotesActive = ChatEmoteConfig.getInstance().isEnabled();
        // addToHistory=false: this text was never typed by killer560 (it's a bot-generated line, e.g.
        // /cringe or a Leap Message), so it shouldn't show up when he presses Up Arrow in chat -
        // that history is for recalling what HE typed.
        processAndSend(message, commandWord, false, translateActive, autoCorrectActive, emotesActive, cfg);
    }

    private static void processAndSend(String toProcess, String commandWord, boolean addToHistory,
            boolean translateActive, boolean autoCorrectActive, boolean emotesActive, TranslateConfig cfg) {
        String finalCommandWord = commandWord;

        // Auto Correct runs first and is purely local (no network), so it's applied synchronously
        // regardless of whether Translate also needs to run afterward.
        String corrected = autoCorrectActive ? AutoCorrectFeature.correct(toProcess) : toProcess;

        // Chat Emote triggers are converted next, before Translate ever sees the message - the
        // real emote text is sent directly (never the raw trigger), and it's never handed to
        // Google/MyMemory either, so it can't be altered "no matter what" the target language is.
        // Only whatever's left in between (the "body", if any) still goes through Translate below.
        ChatEmoteFeature.Split emoteSplit = emotesActive
                ? ChatEmoteFeature.split(corrected)
                : new ChatEmoteFeature.Split("", corrected, "");
        String correctedWithEmotes = ChatEmoteFeature.join(emoteSplit.prefix(), emoteSplit.body(), emoteSplit.suffix());

        Minecraft client = Minecraft.getInstance();
        if (addToHistory) {
            String historyText = finalCommandWord == null
                    ? correctedWithEmotes : "/" + finalCommandWord + " " + correctedWithEmotes;
            client.gui.getChat().addRecentChat(historyText);
        }

        if (!translateActive || !emoteSplit.hasBody()) {
            // Either Translate is off, or the whole message was just emote trigger(s) with no real
            // text left to translate - either way, nothing left for Translate to do.
            if (client.player != null) {
                sendFinal(client, finalCommandWord, correctedWithEmotes);
            }
            return;
        }

        String targetCode = cfg.getTargetLanguageCode();
        String bodyToTranslate = emoteSplit.body();
        CompletableFuture.supplyAsync(() -> {
            try {
                return translate(bodyToTranslate, targetCode);
            } catch (Exception e) {
                LOGGER.warn("Translate request failed, sending original message instead", e);
                return (TranslationResult) null;
            }
        }, EXECUTOR).thenAccept(result -> client.execute(() -> {
            if (client.player == null) {
                return;
            }
            String translatedBody = result != null ? result.text() : null;
            if (translatedBody == null) {
                ModOverlayMessage.show("§c[Killer560's Mod] Translation failed, sent in English instead", 3000);
            }
            // Defensive: never send a blank command payload, even if a provider returns "" for a
            // 2xx response (observed once for a short input - not root-caused, this just prevents
            // it turning into a broken "/ac" with no message).
            String bodyOut = (translatedBody != null && !translatedBody.isBlank()) ? translatedBody : bodyToTranslate;
            String outgoing = ChatEmoteFeature.join(emoteSplit.prefix(), bodyOut, emoteSplit.suffix());
            sendFinal(client, finalCommandWord, outgoing);
        }));
    }

    private static void sendFinal(Minecraft client, String commandWord, String text) {
        LOGGER.info("Sending: commandWord={} outgoing=\"{}\"", commandWord, text);
        if (commandWord == null) {
            client.player.connection.sendChat(text);
        } else {
            client.player.connection.sendCommand(commandWord + " " + text);
        }
    }

    /** Convenience overload for outgoing chat, where the typed text is always assumed English. */
    public static TranslationResult translate(String text, String targetCode) throws Exception {
        return translate(text, "en", targetCode);
    }

    /**
     * Tries Google Translate first (its {@code sl=auto} detects the source regardless of
     * {@code sourceCode}), falls back to MyMemory if that fails for any reason - MyMemory has no
     * real "auto" detection, so {@code sourceCode} matters there: pass a real language code (e.g.
     * "en" for outgoing chat, always typed in English) or the literal string {@code "auto"} when
     * the source is genuinely unknown (e.g. Click Translate, translating someone else's message of
     * unknown language TO English) - passing a wrong fixed source (like always assuming English)
     * would ask MyMemory to translate English -&gt; English whenever the target also happens to be
     * English, which it rejects with a literal "PLEASE SELECT TWO DISTINCT LANGUAGES" 200 response
     * (a real bug hit and fixed here, not hypothetical).
     */
    public static TranslationResult translate(String text, String sourceCode, String targetCode) throws Exception {
        try {
            return translateGoogle(text, targetCode);
        } catch (Exception e) {
            LOGGER.warn("Google Translate failed ({}), falling back to MyMemory", e.getMessage());
            return translateMyMemory(text, sourceCode, targetCode);
        }
    }

    private static TranslationResult translateGoogle(String text, String targetCode) throws Exception {
        String url = GOOGLE_ENDPOINT + "?client=gtx&sl=auto&tl=" + urlEncode(targetCode) + "&dt=t&q=" + urlEncode(text);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Google Translate returned HTTP " + response.statusCode());
        }

        JsonArray root = JsonParser.parseString(response.body()).getAsJsonArray();
        JsonArray sentences = root.get(0).getAsJsonArray();
        StringBuilder result = new StringBuilder();
        for (JsonElement el : sentences) {
            JsonArray sentence = el.getAsJsonArray();
            if (!sentence.isEmpty() && !sentence.get(0).isJsonNull()) {
                result.append(sentence.get(0).getAsString());
            }
        }

        // Element index 2 of the root array is the detected source language code (e.g. "ja") when
        // sl=auto - a well-documented shape of this unofficial endpoint (this environment's network
        // is itself rate-limited by Google right now, confirmed via a real curl request returning
        // "automated queries" - couldn't empirically re-verify this exact field from here, so it's
        // extracted defensively: any shape mismatch just leaves the detected language unknown rather
        // than breaking the translation itself).
        String detected = null;
        try {
            if (root.size() > 2 && root.get(2) != null && root.get(2).isJsonPrimitive()) {
                detected = root.get(2).getAsString();
            }
        } catch (Exception ignored) {
        }
        return new TranslationResult(result.toString(), detected);
    }

    /**
     * MyMemory's own keyword for "detect the source language" is the literal string "autodetect".
     * When used, its response includes {@code responseData.detectedLanguage} - confirmed via a real
     * live request (source "こんにちは世界" -&gt; correctly reported "ja"), not assumed.
     */
    private static TranslationResult translateMyMemory(String text, String sourceCode, String targetCode) throws Exception {
        String myMemorySource = "auto".equalsIgnoreCase(sourceCode) ? "autodetect" : sourceCode;
        String url = MYMEMORY_ENDPOINT + "?q=" + urlEncode(text) + "&langpair=" + urlEncode(myMemorySource + "|" + targetCode);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("MyMemory returned HTTP " + response.statusCode());
        }

        LOGGER.info("MyMemory raw response: {}", response.body());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject responseData = root.getAsJsonObject("responseData");
        if (responseData == null || !responseData.has("translatedText")) {
            throw new RuntimeException("MyMemory response missing translatedText");
        }
        String detected = responseData.has("detectedLanguage") && !responseData.get("detectedLanguage").isJsonNull()
                ? responseData.get("detectedLanguage").getAsString() : null;
        return new TranslationResult(responseData.get("translatedText").getAsString(), detected);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private TranslateFeature() {
    }
}
