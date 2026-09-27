package com.killer560.hub.namechanger;

import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.ChatFormatting;

import java.util.UUID;

/**
 * Resolves a typed nickname back to the REAL Minecraft ign before a party/friend command reaches Hypixel -
 * killer560's explicit requirement: "make it so if someone renames themselves to johndoe and I party someone
 * I can party johndoe based off of their name, same for friending and whatnot. So it will need to server
 * side send their real ign. also make it so it still goes off of their real ign." Two sources of "nickname",
 * both already local knowledge - never a network call, since a command has to go out right now, not once an
 * async lookup finishes later:
 * <ol>
 *   <li>A manual Name Changer rename YOU set for that specific player ({@link NameChangerConfig.Mapping}) -
 *       {@code real} IS the real ign already (you typed it yourself when you made the rename), so no lookup
 *       is even needed.</li>
 *   <li>Someone else's OWN shared cosmetic name (the supporters relay, {@link SupportersFeature}) - their
 *       real ign comes from whatever {@link PlayerNames} already has cached for their account (tab list scans,
 *       past resolutions) - never fetched fresh here.</li>
 * </ol>
 * killer560's own collision example: he renames someone "john" while a real Mojang account also happens to
 * be named John. {@link #resolve} never guesses in that case - it reports BOTH the nickname's real ign and
 * the literal typed name's own real ign, and {@link IdentityCommandFeature} turns that into a clickable
 * "which John did you mean?" prompt instead of sending anything.
 */
final class IdentityResolver {

    private IdentityResolver() {
    }

    /**
     * One resolution outcome for a typed name token.
     *
     * @param realIgn          the nickname's resolved real ign, or {@code null} if {@code typedText} isn't a
     *                         known nickname at all (nothing to rewrite - send it exactly as typed).
     * @param collisionRealIgn only set when a DIFFERENT, currently-known real account has this exact literal
     *                         name too - {@code realIgn} is still the nickname's own real ign in that case.
     * @param typedText        the original typed token, unchanged.
     */
    record Resolution(String realIgn, String collisionRealIgn, String typedText) {

        boolean isCollision() {
            return collisionRealIgn != null;
        }

        /** @return true if this should be rewritten to {@link #realIgn} before sending - a unique nickname
         *  match that actually differs from what was typed. */
        boolean isRewrite() {
            return realIgn != null && collisionRealIgn == null && !realIgn.equalsIgnoreCase(typedText);
        }
    }

    static Resolution resolve(String typedName) {
        if (typedName == null || typedName.isBlank()) {
            return new Resolution(null, null, typedName);
        }
        String nicknameReal = mappingReal(typedName);
        if (nicknameReal == null) {
            nicknameReal = SupportersFeature.realIgnForDisplayName(typedName);
        }
        if (nicknameReal == null) {
            return new Resolution(null, null, typedName);
        }
        // Is there ALSO a different, currently-known real account with this exact literal name? Cache-only
        // (PlayerNames.uuidFor never blocks on the network) - this has to answer synchronously, right now.
        UUID literalUuid = PlayerNames.uuidFor(typedName);
        String literalReal = literalUuid == null ? null : PlayerNames.nameFor(literalUuid);
        if (literalReal != null && !literalReal.equalsIgnoreCase(nicknameReal)) {
            return new Resolution(nicknameReal, literalReal, typedName);
        }
        return new Resolution(nicknameReal, null, typedName);
    }

    /** {@code real} of the first enabled manual mapping whose (formatting-stripped) {@code display}
     *  case-insensitively equals {@code typedName} - first-added wins on a duplicate, same convention
     *  {@link NameTable} uses for render-time lookups. */
    private static String mappingReal(String typedName) {
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isMappingsEnabled()) {
            return null;
        }
        for (NameChangerConfig.Mapping m : cfg.mappings()) {
            String plain = plainOf(m.display);
            if (plain != null && plain.equalsIgnoreCase(typedName) && m.real != null && !m.real.isBlank()) {
                return m.real.trim();
            }
        }
        return null;
    }

    private static String plainOf(String display) {
        return display == null ? null : ChatFormatting.stripFormatting(NameChangerFeature.colorize(display));
    }
}
