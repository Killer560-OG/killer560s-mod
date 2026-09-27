package com.killer560.hub.namechanger;

import com.killer560.hub.util.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outgoing half of "real ign on the wire" - see {@link IdentityResolver}'s class doc for the killer560 quotes
 * this exists to satisfy. Hooked from {@link com.killer560.hub.namechanger.mixin.IdentityCommandMixin}, the
 * exact same {@code ChatScreen.handleChatInput} HEAD hook {@code com.killer560.hub.translate.mixin.ChatScreenMixin}
 * and {@code com.killer560.hub.experiments.mixin.AutoCorrectCommandMixin} already use for outgoing chat - safe
 * to run alongside both, since Translate's own regex only ever matches chat-CHANNEL commands (/ac, /pc, /w, ...),
 * Auto Correct's {@code @ModifyArg} only ever touches the command NAME word, and {@link #tryIntercept} below
 * only ever matches party-invite/friend-add - none of the three can ever fire on the same typed line as
 * either of the others.
 * <p>
 * Only ever REWRITES to a real ign this client already has cached, or CANCELS with a disambiguation prompt -
 * anything it doesn't specifically recognise (including a nickname it can't yet resolve) falls straight
 * through to vanilla, completely unchanged, exactly like today. Whatever it DOES send goes through {@link
 * ServerCommands#toServer} - never {@code ClientPacketListener.sendCommand} - since {@code /party}/{@code
 * /friend} are commands meant for Hypixel, not this mod (see that class's own doc for the recursion bug that
 * rule exists to avoid).
 */
public final class IdentityCommandFeature {

    /** "/p invite <name>" / "/party invite <name>" - the explicit form. */
    private static final Pattern PARTY_EXPLICIT_INVITE =
            Pattern.compile("^/(?:p|party)\\s+invite\\s+(\\S{1,32})$", Pattern.CASE_INSENSITIVE);
    /** "/p <name>" / "/party <name>" - Hypixel's own shorthand for the SAME invite, as long as the argument
     *  isn't actually one of party's other subcommands (checked against {@link #PARTY_SUBCOMMANDS} below). */
    private static final Pattern PARTY_SHORTHAND =
            Pattern.compile("^/(?:p|party)\\s+(\\S{1,32})$", Pattern.CASE_INSENSITIVE);
    /** "/f add <name>" / "/friend add <name>" - killer560's own other named example ("same for friending").
     *  Deliberately only the explicit "add" form: bare "/f <name>" isn't a real Hypixel command (plain "/f"
     *  opens the friends GUI), so there is no shorthand to also cover here. */
    private static final Pattern FRIEND_ADD =
            Pattern.compile("^/(?:f|friend)\\s+add\\s+(\\S{1,32})$", Pattern.CASE_INSENSITIVE);

    /** Real Hypixel {@code /party ...}/{@code /p ...} subcommands - never mistaken for a target name under
     *  {@link #PARTY_SHORTHAND}. */
    private static final Set<String> PARTY_SUBCOMMANDS = Set.of(
            "invite", "accept", "kick", "promote", "demote", "warp", "warpall", "transfer", "leave",
            "disband", "list", "chat", "mute", "unmute", "settings", "help", "finder", "cancel", "reload",
            "pdisband", "ignore", "unignore");

    private IdentityCommandFeature() {
    }

    /** @return true if this feature handled (sent or prompted for) the message itself - the caller must
     *  cancel vanilla's own send in that case, same contract as {@code TranslateFeature#tryIntercept}. */
    public static boolean tryIntercept(String normalizedMessage, boolean addToHistory) {
        if (normalizedMessage == null || !normalizedMessage.startsWith("/")) {
            return false;
        }
        String verb;
        String typedName;
        Matcher m = PARTY_EXPLICIT_INVITE.matcher(normalizedMessage);
        if (m.matches()) {
            verb = "party invite";
            typedName = m.group(1);
        } else if ((m = PARTY_SHORTHAND.matcher(normalizedMessage)).matches()
                && !PARTY_SUBCOMMANDS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            verb = "party invite";
            typedName = m.group(1);
        } else if ((m = FRIEND_ADD.matcher(normalizedMessage)).matches()) {
            verb = "friend add";
            typedName = m.group(1);
        } else {
            return false; // not a command this feature knows how to translate - leave it to vanilla
        }

        IdentityResolver.Resolution res = IdentityResolver.resolve(typedName);
        if (res.isCollision()) {
            addHistory(normalizedMessage, addToHistory);
            promptCollision(verb, res);
            return true;
        }
        if (!res.isRewrite()) {
            return false; // typed text already IS the real ign (or isn't a known nickname at all)
        }
        addHistory(normalizedMessage, addToHistory);
        ServerCommands.toServer(verb + " " + res.realIgn());
        return true;
    }

    private static void addHistory(String normalizedMessage, boolean addToHistory) {
        if (addToHistory) {
            Minecraft.getInstance().gui.getChat().addRecentChat(normalizedMessage);
        }
    }

    /** Client-side-only prompt (never touches the server) - clicking either option re-runs the SAME typed
     *  command with the chosen real ign substituted, through vanilla's own click-to-run-command path (the
     *  same one {@code PartyCommandsFeature}'s "click to invite" confirm already relies on) - which then
     *  re-enters this exact hook with the real ign as plain typed text, resolves to no further rewrite, and
     *  falls straight through to vanilla unchanged. */
    private static void promptCollision(String verb, IdentityResolver.Resolution res) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        MutableComponent line = Component.literal("§6[Killer560's Mod] §fTwo players are named §e"
                + res.typedText() + "§f - which one did you mean?");
        Component nicknameOption = option(" §a[renamed player]", verb, res.realIgn());
        Component mojangOption = option(" §b[Mojang account]", verb, res.collisionRealIgn());
        client.player.sendSystemMessage(line.append(nicknameOption).append(mojangOption));
    }

    private static Component option(String label, String verb, String realIgn) {
        String command = "/" + verb + " " + realIgn;
        return Component.literal(label).withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to send " + command))));
    }
}
