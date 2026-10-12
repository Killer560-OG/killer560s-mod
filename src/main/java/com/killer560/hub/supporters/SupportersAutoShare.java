package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameColor;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Automatic half of "Share if Supporter" (Cosmetics tab) - killer560: "if the account is a supporter, its
 * cosmetics should be shared with others automatically." Before this, sharing your name/scale to the relay
 * needed a manual Save press in the old Supporters tab's own editor ({@link SupportersSelfService}, still
 * used here completely unchanged) - this class is what calls it FOR you, from {@code
 * com.killer560.hub.gui.tab.CosmeticsTab}, whenever its own name/colour/scale fields actually changed since
 * the last successful push.
 * <p>
 * Throttled to at most once every {@link #MIN_PUSH_GAP_MS}: the relay's own self-service endpoint already
 * 429s a push that comes too soon after the last one (see {@link SupportersSelfService}'s own doc), so this
 * is "don't even try yet" on top of that, not a replacement for it. A push skipped for being too soon simply
 * gets retried the next time the tab rebuilds (typing another character, moving a slider, or just reopening
 * the tab), so it always converges to whatever the fields currently say once the player stops changing them.
 * <p>
 * Never sends an over-length name to the relay: {@link SupporterNameValidator}'s own rules (max 32 visible
 * characters, only the 16 legacy {@code &0-9a-fk-or} colour codes) are checked here BEFORE ever calling {@link
 * SupportersSelfService#save}. The name itself carries the nearest legacy {@code &} colour (the fade's FROM colour),
 * which is what a client older than 2026-10-11 shows; a fade or letter colours go alongside it as the relay's
 * {@code colors} list, one true colour per visible letter, which newer clients paint exactly as you see it.
 */
public final class SupportersAutoShare {

    private static final long MIN_PUSH_GAP_MS = 4_000L;

    private static volatile String lastPushedKey;
    private static volatile long lastPushAtMs = -1L;
    private static volatile long requestSeq;

    private SupportersAutoShare() {
    }

    /**
     * @param plainName   the Cosmetics tab's own display name, plain text (no {@code &}/{@code §} codes) -
     *                    same text {@link NameColor#buildFade} would use as its base.
     * @param argb        the picked colour (the fade's FROM colour when fading - see class doc), or {@link
     *                    NameColor#NONE}.
     * @param whitelisted must already be confirmed true by a fresh {@code GET /supporters/me} - this method
     *                    never triggers that check itself.
     * @param onError     called (on the client thread) with a short reason whenever a push this method
     *                    actually attempted came back rejected - null to ignore.
     */
    public static void maybeShare(boolean shareIfSupporter, boolean whitelisted, String plainName, int argb,
                                   double scale, Consumer<String> onError) {
        maybeShare(shareIfSupporter, whitelisted, plainName, argb, null, scale, onError);
    }

    /** @param colors your name's colour per visible letter ({@code NameChangerFeature.ownShareColours}), or null. Sent
     *  only when it has exactly one entry per visible letter of the shared name; otherwise the name goes alone. */
    public static void maybeShare(boolean shareIfSupporter, boolean whitelisted, String plainName, int argb,
                                   int[] colors, double scale, Consumer<String> onError) {
        if (!shareIfSupporter || !whitelisted) {
            return;
        }

        String rawName = plainName == null || plainName.isEmpty() ? ""
                : (argb == NameColor.NONE ? "" : "&" + NameColor.codeFor(argb)) + plainName;
        if (colors != null && (rawName.isEmpty()
                || colors.length != NameColor.visibleLetters(SupportersFeature.colorize(rawName)))) {
            colors = null;
        }
        final int[] sendColors = colors;
        String key = rawName + "|" + scale + "|" + java.util.Arrays.toString(colors);
        if (key.equals(lastPushedKey)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastPushAtMs > 0 && now - lastPushAtMs < MIN_PUSH_GAP_MS) {
            return; // too soon - the next rebuild (another keystroke, or just time passing) retries this
        }
        if (!rawName.isEmpty() && SupporterNameValidator.validate(rawName) != null) {
            return; // would just 400 - the tab's own preview/validation already tells the player why
        }
        lastPushAtMs = now;
        lastPushedKey = key;
        long seq = ++requestSeq;
        CompletableFuture<SupportersSelfService.SaveResult> future =
                rawName.isEmpty() ? SupportersSelfService.clear() : SupportersSelfService.save(rawName, scale, sendColors);
        future.whenComplete((result, ignored) -> Minecraft.getInstance().execute(() -> {
            if (seq != requestSeq) {
                return; // superseded by a newer change
            }
            if (result.ok()) {
                SupportersFeature.refreshNow();
            } else {
                lastPushedKey = null; // let the next rebuild retry instead of getting stuck on one failure
                if (onError != null) {
                    onError.accept(result.error() == null ? "share failed" : result.error());
                }
            }
        }));
    }
}
