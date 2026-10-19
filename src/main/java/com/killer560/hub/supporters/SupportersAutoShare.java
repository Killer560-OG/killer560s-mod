package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameColor;
import com.killer560.hub.trail.RibbonTrailConfig;
import com.killer560.hub.trail.TrailSpec;
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
 * <p>
 * Your Ribbon Trail goes along as the relay's {@code trail} while "Share Trail" and the Ribbon Trail itself are on
 * (killer560, 2026-10-13: "it can only be shared by a supporter"; 2026-10-14: only the ribbon, never the squares Trail) -
 * see {@link #trailToShare} and {@link #shareCurrent}, which the Player Cosmetics and Ribbon Trail tabs call on every rebuild and {@code RemoteTrails} every 35 seconds. Linked status is
 * checked once at startup ({@link #tickStartupCheck}), so this works without opening the menu. With no display name the
 * push carries only the trail (or clears the entry); the relay never lets either replace a name staff set.
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
        maybeShare(shareIfSupporter, whitelisted, plainName, argb, colors, null, scale, onError);
    }

    /** @param animation your name's Wave/Pulse ({@code NameChangerFeature.ownShareAnimation}), or null; sent only with
     *  {@code colors}. */
    public static void maybeShare(boolean shareIfSupporter, boolean whitelisted, String plainName, int argb,
                                   int[] colors, com.killer560.hub.namechanger.NameAnimation.Spec animation,
                                   double scale, Consumer<String> onError) {
        maybeShare(shareIfSupporter, whitelisted, plainName, argb, colors, animation, scale, null, onError);
    }

    /**
     * @param trail your Trail as shared ({@link #trailToShare}), or null for none. With a trail and no display name the
     *              entry still goes up, with an empty name (the relay's trail-only entry, shown under your real name);
     *              with neither, your entry is cleared as before.
     */
    public static void maybeShare(boolean shareIfSupporter, boolean whitelisted, String plainName, int argb,
                                   int[] colors, com.killer560.hub.namechanger.NameAnimation.Spec animation,
                                   double scale, TrailSpec trail, Consumer<String> onError) {
        maybeShare(shareIfSupporter, whitelisted, plainName, argb, colors, animation, scale, trail, null, onError);
    }

    /**
     * @param mob the mob you play as ({@link #mobToShare}), or null for none. Like a trail it goes up with an empty name
     *            when you have no display name; with no name, trail or mob the entry is cleared.
     */
    public static void maybeShare(boolean shareIfSupporter, boolean whitelisted, String plainName, int argb,
                                   int[] colors, com.killer560.hub.namechanger.NameAnimation.Spec animation,
                                   double scale, TrailSpec trail, String mob, Consumer<String> onError) {
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
        final com.killer560.hub.namechanger.NameAnimation.Spec sendAnimation = colors == null ? null : animation;
        final String sendMob = com.killer560.hub.mobskin.MobSkins.sanitize(mob);
        String key = rawName + "|" + scale + "|" + java.util.Arrays.toString(colors) + "|" + sendAnimation + "|" + trail
                + "|" + sendMob;
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
        CompletableFuture<SupportersSelfService.SaveResult> future = rawName.isEmpty() && trail == null && sendMob == null
                ? SupportersSelfService.clear()
                : SupportersSelfService.save(rawName, scale, sendColors, sendAnimation, trail, sendMob);
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

    // ---- what to share, from the settings (2026-10-13) ----

    /** Whether the relay last said this account is a linked supporter ({@code GET /supporters/me}, fetched by the
     *  Player Cosmetics tab). Nothing is ever shared before that says yes. */
    private static volatile boolean linked = false;

    public static boolean isLinked() {
        return linked;
    }

    public static void setLinked(boolean value) {
        linked = value;
    }

    // ---- startup check (killer560, 2026-10-13: names and the trail share without opening Player Cosmetics) ----

    private static final long FIRST_CHECK_DELAY_MS = 10_000L;
    private static final long RETRY_BASE_MS = 30_000L;
    private static final long RETRY_MAX_MS = 15 * 60_000L;
    private static volatile boolean startupDone = false;
    private static volatile boolean startupInFlight = false;
    private static volatile long nextStartupCheckAtMs = -1L;
    private static volatile int startupFailures = 0;
    private static volatile String startupState = "waiting";

    /**
     * Called every client tick: once, about {@link #FIRST_CHECK_DELAY_MS} after the game starts, asks the relay
     * ({@code GET /supporters/me}) whether this account is a linked supporter, never blocking. A failure (relay down,
     * offline, no Mojang key yet) retries after 30 s, doubling to at most 15 minutes; an answer ends it. A linked answer
     * pushes your name, size and trail at once.
     */
    public static void tickStartupCheck() {
        if (startupDone || startupInFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (nextStartupCheckAtMs < 0) {
            nextStartupCheckAtMs = now + FIRST_CHECK_DELAY_MS;
            return;
        }
        if (now < nextStartupCheckAtMs) {
            return;
        }
        startupInFlight = true;
        SupportersSelfService.fetchMe().whenComplete((result, error) ->
                Minecraft.getInstance().execute(() -> onStartupMe(result)));
    }

    /** The startup check's answer (on the client thread). Public for the testkit, which feeds it a made-up answer. */
    public static void onStartupMe(SupportersSelfService.MeResult result) {
        startupInFlight = false;
        if (result != null && result.ok()) {
            startupDone = true;
            linked = result.whitelisted();
            startupState = linked ? "linked" : "not linked";
            if (linked) {
                shareCurrent(null);
            }
            return;
        }
        startupFailures++;
        long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(10, startupFailures - 1));
        nextStartupCheckAtMs = System.currentTimeMillis() + delay;
        startupState = "retry in " + delay / 1000 + " s after " + startupFailures + " failure(s)"
                + (result == null || result.error() == null ? "" : ": " + result.error());
    }

    /** "waiting", "linked", "not linked" or "retry in N s after K failure(s): reason" - for the testkit. */
    public static String startupCheckState() {
        return startupState;
    }

    /** Your Ribbon Trail as it is shared: only while Share Trail is on and your Ribbon Trail is on, else null (no trail
     *  field). The squares Trail is never shared (killer560, 2026-10-14). */
    public static TrailSpec trailToShare() {
        if (!SupportersConfig.getInstance().isShareTrail() || !RibbonTrailConfig.getInstance().isEnabledRaw()) {
            return null;
        }
        return TrailSpec.fromConfig(RibbonTrailConfig.getInstance());
    }

    /** Why your trail is not being shared right now, in one line, or null when it is (or would be once linked
     *  status arrives). For the Player Cosmetics tab, beside the Share Trail switch. */
    public static String trailShareBlocker() {
        if (!linked) {
            return "Only supporters can share a trail.";
        }
        if (!SupportersConfig.getInstance().isShareIfSupporter()) {
            return "Turn on Share if Supporter to share your trail.";
        }
        return null;
    }

    /** Your Play as Mob choice as it is shared (Share if Supporter covers it, like your name and size), or null for your
     *  normal player. */
    public static String mobToShare() {
        return com.killer560.hub.mobskin.MobSkins.sanitize(SupportersConfig.getInstance().getOwnMob());
    }

    /** Pushes the current name, colours, animation, size, trail and mob from the settings (no-op unless linked and Share
     *  if Supporter is on, or when nothing changed since the last push). */
    public static void shareCurrent(Consumer<String> onError) {
        SupportersConfig cfg = SupportersConfig.getInstance();
        NameChangerConfig nc = NameChangerConfig.getInstance();
        maybeShare(cfg.isShareIfSupporter(), linked, NameColor.migrate(nc.getOwnDisplayName()).text(), nc.getOwnColor(),
                com.killer560.hub.namechanger.NameChangerFeature.ownShareColours(),
                com.killer560.hub.namechanger.NameChangerFeature.ownShareAnimation(), cfg.getOwnScale(),
                trailToShare(), mobToShare(), onError);
    }
}
