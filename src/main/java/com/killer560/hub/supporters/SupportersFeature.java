package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.players.PlayerNames;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * killer560's item 8.5: "mod-wide custom IGNs for supporters" - a Discord-staff-only display name (+ player
 * scale) for whoever the relay says is a supporter, shown on their nametag, the tab list, and their own chat
 * messages. See {@code SUPPORTERS-CONTRACT.md} for the relay side (the Discord command and channels are
 * handled there, not here) and this feature's staging notes ({@code impl-supporters.md}) for what still
 * needs wiring into the main client/tab/profile classes.
 * <p>
 * Everything here keys on {@link UUID}, never on the current IGN text (killer560's standing "never by name"
 * rule - see {@code PlayerNames}'s class doc): {@link #displayNameFor} and {@link #scaleFor} are both O(1)
 * map lookups by the entity's/tab row's own account UUID. The one exception is chat, where Hypixel gives the
 * client no structured sender at all (see {@link SupportersChatRewriter}'s class doc) - it falls back to
 * matching the sender's exact CURRENT ign, itself resolved from the UUID via {@link PlayerNames} rather than
 * trusting the stored (vanity) display name, per {@code SUPPORTERS-CONTRACT.md}'s "or exact current IGN".
 * <p>
 * "Everyone can still change their own name client-side" (self override wins): if you are a supporter AND
 * you have set your own name in Name Changer, Name Changer's own-name rewrite wins on your own screen - see
 * {@link #isSelfOverridden}. This does NOT suppress your own scale; only the name is overridable.
 * <p>
 * A resolved entry's display text is pre-built (colourised, slur-checked) once per fetch in {@link #apply},
 * not per render - the hot paths ({@link #displayNameFor}/{@link #scaleFor}, called from the nametag/tab-list/
 * scale mixins every frame) are a single map lookup plus a couple of field reads, no allocation beyond the
 * one {@link Component#literal} wrapper needed to hand back a {@link Component}.
 */
public final class SupportersFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-supporters");

    /** One fetched+resolved supporter, keyed by account UUID. {@code displayText} is {@code null} when the
     *  stored name is blank or trips {@link SlurFilter} - the real IGN is shown instead, but the entry (and
     *  its scale) still exists. */
    private record Resolved(String displayText, float scale) {
    }

    /** What {@link SupportersChatRewriter} needs back for a matched sender. */
    record ChatMatch(UUID uuid, String displayText) {
    }

    private static volatile Map<UUID, Resolved> byUuid = Map.of();
    private static volatile int lastAppliedVersion = -1;

    /** The same callback {@link #register} hands {@link SupportersFetcher#start}, kept so {@link #refreshNow}
     *  can reuse the identical fetch-then-apply-then-persist path instead of duplicating it. */
    private static final BiConsumer<Integer, List<SupporterEntry>> ON_FETCHED =
            (version, entries) -> apply(version, entries, true);

    private SupportersFeature() {
    }

    public static void register() {
        SupportersConfig.getInstance();
        SupportersCache.Loaded cached = SupportersCache.load();
        if (!cached.entries().isEmpty()) {
            apply(cached.version(), cached.entries(), false);
        }
        SupportersChatRewriter.register();
        SupportersFetcher.start(ON_FETCHED);
    }

    /** Forces an immediate re-fetch of {@code GET /supporters} instead of waiting for the next scheduled
     *  poll (up to 5 minutes) - called by {@code SupportersTab}'s "My Supporter Name" editor right after a
     *  successful save/clear, per SUPPORTERS-CONTRACT-V2.md's self-service section: the relay "bumps the
     *  public list version" immediately, so re-fetching now (rather than waiting) is enough to show the
     *  change on this client's own nametag/tab list/chat straight away. */
    public static void refreshNow() {
        SupportersFetcher.fetchNow(ON_FETCHED);
    }

    private static void apply(int version, List<SupporterEntry> entries, boolean persist) {
        Map<UUID, Resolved> map = new java.util.HashMap<>();
        for (SupporterEntry e : entries) {
            String display = e.rawName().isEmpty() || SlurFilter.isBlocked(e.rawName())
                    ? null
                    : colorize(e.rawName());
            map.put(e.uuid(), new Resolved(display, e.scale()));
        }
        byUuid = Map.copyOf(map);
        lastAppliedVersion = version;
        if (persist) {
            SupportersCache.save(version, entries);
        }
        LOGGER.info("[Supporters] {} supporter(s) loaded (list version {})", entries.size(), version);
    }

    public static int supporterCount() {
        return byUuid.size();
    }

    public static int lastVersion() {
        return lastAppliedVersion;
    }

    /** @return true when cosmetics are on and there is at least one supporter to ever match - a cheap
     *  pre-check so {@link SupportersChatRewriter} can skip its regex entirely on the common case. */
    static boolean cosmeticsActive() {
        return SupportersConfig.getInstance().isCustomCosmeticsEnabled() && !byUuid.isEmpty();
    }

    /** The Component to paint instead of {@code id}'s real name, or {@code null} to leave it alone (not a
     *  supporter, cosmetics off, the stored name is blocked by the slur filter, or the local player has
     *  overridden their own name in Name Changer). */
    public static Component displayNameFor(UUID id) {
        if (id == null || !SupportersConfig.getInstance().isCustomCosmeticsEnabled()) {
            return null;
        }
        Resolved r = byUuid.get(id);
        if (r == null || r.displayText() == null) {
            return null;
        }
        if (isSelfOverridden(id)) {
            return null;
        }
        return Component.literal(r.displayText());
    }

    /** Player-model render scale for {@code id} - always {@code 1.0} when not a supporter or cosmetics are
     *  off. Visual only; never touches hitboxes (see {@code SupportersScaleMixin}). Not affected by "self
     *  override" - that only concerns the displayed name. */
    public static float scaleFor(UUID id) {
        if (id == null || !SupportersConfig.getInstance().isCustomCosmeticsEnabled()) {
            return 1.0f;
        }
        Resolved r = byUuid.get(id);
        return r == null ? 1.0f : r.scale();
    }

    /** Looks for a supporter whose CURRENT resolved ign (never their stored vanity name) case-insensitively
     *  equals {@code candidateIgn} - the chat rewriter's fallback for Hypixel's unstructured chat text. */
    static ChatMatch findChatSender(String candidateIgn) {
        if (candidateIgn == null || candidateIgn.isEmpty()) {
            return null;
        }
        for (Map.Entry<UUID, Resolved> entry : byUuid.entrySet()) {
            UUID uuid = entry.getKey();
            String currentIgn = PlayerNames.nameFor(uuid);
            if (currentIgn == null || !currentIgn.equalsIgnoreCase(candidateIgn)) {
                continue;
            }
            Resolved r = entry.getValue();
            if (r.displayText() == null || isSelfOverridden(uuid)) {
                return null; // a real supporter matched, but there is nothing to show for them right now
            }
            return new ChatMatch(uuid, r.displayText());
        }
        return null;
    }

    /** @return true if {@code id} is the local player AND they have set their own name in Name Changer - in
     *  which case Name Changer's own-name rewrite must win over the supporter name on their own screen
     *  (killer560's item 8.5: "everyone can still change their own name client-side"). */
    static boolean isSelfOverridden(UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || id == null) {
            return false;
        }
        if (!id.equals(client.player.getGameProfile().id())) {
            return false;
        }
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        return cfg.isEnabled() && cfg.isOwnNameEnabled() && !cfg.getOwnDisplayName().isEmpty();
    }

    /** {@code &} + a vanilla colour/format code -> {@code §} + that code (same convention Name Changer's own
     *  colour input uses) - a supporter's stored name travels as plain text with {@code &} codes
     *  ({@code SUPPORTERS-CONTRACT.md}), never raw {@code §}. */
    public static String colorize(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                sb.append('§');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
