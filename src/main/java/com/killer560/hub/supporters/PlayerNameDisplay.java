package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameChangerFeature;
import com.killer560.hub.players.PlayerNames;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Single chokepoint for "what should be painted instead of this player's real name" - killer560's cosmetics
 * request: "find every place a player name is rendered and route it through one shared replacement helper
 * rather than patching each site differently." Before this existed, {@link SupportersNameTagMixin}/{@link
 * SupportersTabListMixin}/{@link SupportersScaleMixin}/{@link SupportersChatRewriter} each called straight
 * into {@link SupportersFeature}, and a LOCAL per-player rename (Name Changer's manual mapping list, keyed on
 * that player's REAL ign) never got a chance to apply whenever the same player ALSO had a supporter cosmetic
 * name shared over the relay: {@code SupportersFeature}'s own replacement ran upstream of Name Changer's
 * Font-level text scan (see {@code NameChangerFeature}'s class doc), so by the time Font ever saw the name it
 * was already the supporter's cosmetic text, not the real ign Name Changer's mapping is keyed on - a rename
 * you explicitly typed for that person would silently stop applying the moment they also became a supporter.
 * <p>
 * Precedence, most specific first:
 * <ol>
 *   <li>Your own name, if you've set one in Name Changer ({@link SupportersFeature#isSelfOverridden}) -
 *       returns {@code null} here so the normal Font-level rewrite (which already paints your own name,
 *       colour and fade) does the actual work; this only stops the supporter cosmetic from fighting it.</li>
 *   <li>A manual Name Changer rename for THIS player's current real ign - "you explicitly renamed this
 *       specific person" beats "the relay says they picked a name."</li>
 *   <li>Their shared supporter cosmetic name, if any.</li>
 * </ol>
 * Scale has no such collision (Name Changer never carries a scale), so {@link #scaleFor} is a plain
 * passthrough - it lives here anyway so every render site goes through this ONE class, not a mix of this one
 * and {@link SupportersFeature} depending on which mixin you happen to be reading.
 */
public final class PlayerNameDisplay {

    private PlayerNameDisplay() {
    }

    /** @return the Component to paint instead of {@code id}'s real name (nametag/tab list), or {@code null}
     *  to leave it alone. */
    public static Component displayNameFor(UUID id) {
        if (id == null) {
            return null;
        }
        if (SupportersFeature.isSelfOverridden(id)) {
            return null;
        }
        String mapped = NameChangerFeature.mappingDisplayFor(PlayerNames.nameFor(id));
        if (mapped != null) {
            return Component.literal(mapped);
        }
        return SupportersFeature.displayNameFor(id);
    }

    /** Player-model render scale for {@code id} - see {@link SupportersFeature#scaleFor} for what this does
     *  and does not touch. Not affected by the mapping precedence above (Name Changer carries no scale). */
    public static float scaleFor(UUID id) {
        return SupportersFeature.scaleFor(id);
    }

    /**
     * The full model scale for one rendered player (Cosmetics tab "Player Size"). Your own model uses your own
     * size setting and ignores the relay's copy of it, so a shared size never applies twice. Every other real
     * player gets their shared supporter scale times your local "Others' Size". Hypixel's NPC player entities
     * (version-2 UUIDs, the same test Name Changer uses) are left alone.
     */
    public static float modelScaleFor(UUID id, boolean localPlayer) {
        SupportersConfig cfg = SupportersConfig.getInstance();
        if (localPlayer) {
            return cfg.getOwnScale();
        }
        if (id != null && id.version() == 2) {
            return 1.0f;
        }
        return SupportersFeature.scaleFor(id) * cfg.getOthersScale();
    }

    /** @return the text to paint instead of {@code candidateIgn} at a chat line's sender position, or
     *  {@code null} to leave the line alone - same precedence as {@link #displayNameFor}. */
    public static String findChatSender(String candidateIgn) {
        if (candidateIgn == null || candidateIgn.isEmpty()) {
            return null;
        }
        String mapped = NameChangerFeature.mappingDisplayFor(candidateIgn);
        if (mapped != null) {
            return mapped;
        }
        SupportersFeature.ChatMatch match = SupportersFeature.findChatSender(candidateIgn);
        return match == null ? null : match.displayText();
    }
}
