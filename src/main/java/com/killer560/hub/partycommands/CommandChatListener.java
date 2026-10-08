package com.killer560.hub.partycommands;

import com.killer560.hub.partycommands.PartyCommandsConfig.Channel;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Commands' one Hypixel chat intake (Mod Chat's is {@code ModChatFeature}'s relay listener). Fabric's
 * {@code ClientReceiveMessageEvents.CHAT/GAME} only fire for lines that really arrived in a chat packet, so a line a
 * mod drew into chat locally can never trigger anything. Every pattern is anchored on the real line shape with a
 * {@code [A-Za-z0-9_]{1,16}} name group (CLAUDE.md), so one channel's text quoted inside another's ("[VIP] Bob: Party >
 * Mate: !warp") never reads as the quoted channel. This runs on the packet path: it never throws (a throw there
 * disconnects him from Hypixel), it only parses and schedules.
 */
final class CommandChatListener {

    private static final Logger LOGGER = ModLog.get("killer560smod-partycommands");

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final String RANK = "(?:\\[[^]]{1,20}] )?";

    static final Pattern PARTY_REGEX = Pattern.compile("^Party > " + RANK + NAME + "(?: [ቾ⚒])?: ?(.+)$");
    static final Pattern GUILD_REGEX = Pattern.compile("^Guild > " + RANK + NAME + "(?: \\[[^]]{1,20}])?: ?(.+)$");
    static final Pattern PRIVATE_REGEX = Pattern.compile("^From " + RANK + NAME + ": ?(.+)$");
    static final Pattern COOP_REGEX = Pattern.compile("^Co-op > " + RANK + NAME + "(?: [ቾ⚒])?: ?(.+)$");
    /**
     * All chat: "[302] ⚚ [MVP+] Name: text" on SkyBlock (level, optional emblem, optional rank), "[MVP+] Name: text" or
     * "Name: text" elsewhere. The emblem is non-ASCII only, so "To Name: ..." (your own DM echo) cannot pass as one, and
     * the rank is a real Hypixel rank, so "[NPC] Elle: ..." and "[BOSS] Maxor: ..." are not players.
     */
    static final Pattern ALL_REGEX = Pattern.compile("^(?:\\[\\d{1,4}] )?(?:[^\\x00-\\x7F]{1,2} )?"
            + "(?:\\[(?:VIP|VIP\\+|MVP|MVP\\+|MVP\\+\\+|YOUTUBE|ADMIN|GM|MOD|HELPER|OWNER|MOJANG|EVENTS|PIG\\+\\+\\+|INNIT)] )?"
            + NAME + ": (.+)$");

    private CommandChatListener() {
    }

    static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Action-bar text is not chat - never feed it to the command or leader parsers.
            if (!overlay) {
                onMessage(message);
            }
        });
    }

    private static void onMessage(Component message) {
        try {
            if (!PartyCommandsConfig.getInstance().isEnabled()) {
                return;
            }
            String plain = ChatFormatting.stripFormatting(message.getString());
            String raw = (plain != null ? plain : message.getString()).trim();
            if (raw.length() > 512) {
                return;   // no command line is this long; never run the patterns over a wall of text
            }
            PartyCommandsFeature.onServerLine(raw);
            Matcher m;
            Channel channel;
            if ((m = PARTY_REGEX.matcher(raw)).matches()) {
                channel = Channel.PARTY;
            } else if ((m = GUILD_REGEX.matcher(raw)).matches()) {
                channel = Channel.GUILD;
            } else if ((m = PRIVATE_REGEX.matcher(raw)).matches()) {
                channel = Channel.PRIVATE;
            } else if ((m = COOP_REGEX.matcher(raw)).matches()) {
                channel = Channel.COOP;
            } else if ((m = ALL_REGEX.matcher(raw)).matches()) {
                channel = Channel.ALL;
            } else {
                return;
            }
            String text = m.group(2);
            if (!text.startsWith("!")) {
                return;
            }
            PartyCommandsFeature.handle(m.group(1), text.substring(1).trim(), channel);
        } catch (RuntimeException e) {
            LOGGER.error("[PartyCommands] chat line failed", e);
        }
    }
}
