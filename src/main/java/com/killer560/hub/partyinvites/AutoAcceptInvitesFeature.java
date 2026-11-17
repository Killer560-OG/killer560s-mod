package com.killer560.hub.partyinvites;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.lfg.LfgCommandGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Auto Accept Invites (killer560, 2026-10-20): accepts a Hypixel party invite while you are not in a party - every
 * invite in All Invites mode, or in Reinvites Only mode just one from someone who was in the party you left in the last
 * two minutes. The decision is {@link AutoAcceptRule}; this class feeds it the party ({@link PartyTracker#teammates()},
 * which follows Hypixel's party lines and the dungeon tab list) and Hypixel's framed invite message.
 * <p>
 * The only thing it ever sends is {@code /party accept <name>}, through {@link LfgCommandGate} (a name of
 * {@code [A-Za-z0-9_]{1,16}}, at most one every 5 s and four a minute, shared with the Custom Party Finder), which sends
 * it below the client dispatcher. One chat line when it accepts; everything else goes to the log.
 */
public final class AutoAcceptInvitesFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-partyinvites");
    static final String CHAT = "Auto Accept Invites";
    /** The same invite reaching us twice (Fabric and the chat component) is accepted once. */
    private static final long SAME_INVITE_MS = 10_000L;
    private static final Pattern YOU_JOINED =
            Pattern.compile("^You have joined (?:\\[[A-Z+]{1,12}\\] )?[A-Za-z0-9_]{1,16}'s? party!$");

    private static final AutoAcceptRule RULE = new AutoAcceptRule();
    private static String lastAcceptKey = null;
    private static long lastAcceptAt = 0L;
    /** Test hook: what each invite was answered with, newest last. */
    private static final List<String> EVENTS = new ArrayList<>();

    private AutoAcceptInvitesFeature() {
    }

    public static void register() {
        // After PartyTracker.register (Killer560ModClient), so a line has already moved the party when it reaches us.
        ChatObserver.subscribe(AutoAcceptInvitesFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoAcceptInvites", client -> {
            if (client.player != null) {
                RULE.observe(PartyTracker.teammates(), System.currentTimeMillis());
            }
        }));
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.indexOf("arty") < 0) {
            return;
        }
        String trimmed = plain.trim();
        if (trimmed.startsWith("You have joined ") && YOU_JOINED.matcher(trimmed).matches()) {
            RULE.joinedNewParty();
            return;
        }
        if (!plain.contains(" has invited you to join ")) {
            return;
        }
        AutoAcceptRule.Invite invite = AutoAcceptRule.parse(plain.trim());
        if (invite != null) {
            onInvite(invite, clickCommand(message));
        }
    }

    /** The first {@code /party accept} command on the message's click events, or null. */
    private static String clickCommand(Component root) {
        List<Component> stack = new ArrayList<>();
        stack.add(root);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < 256) {
            Component c = stack.remove(stack.size() - 1);
            if (c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run && run.command() != null
                    && AutoAcceptRule.CLICK_COMMAND.matcher(run.command().trim()).matches()) {
                return run.command().trim();
            }
            stack.addAll(c.getSiblings());
        }
        return null;
    }

    static void onInvite(AutoAcceptRule.Invite invite, String clickCommand) {
        long now = System.currentTimeMillis();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            RULE.observe(PartyTracker.teammates(), now);
        }
        AutoAcceptInvitesConfig cfg = AutoAcceptInvitesConfig.getInstance();
        AutoAcceptRule.Decision d = RULE.decide(cfg.isEnabled(), cfg.getMode(), invite, now);
        String from = invite.inviter().equalsIgnoreCase(invite.leader())
                ? invite.inviter() : invite.inviter() + " for " + invite.leader();
        event("invite from " + from + ": " + d);
        if (d != AutoAcceptRule.Decision.ACCEPT) {
            if (d != AutoAcceptRule.Decision.OFF) {
                ModChat.log(CHAT, ModChat.text("Ignored " + from + "'s invite: " + why(d) + "."));
            }
            return;
        }
        String name = AutoAcceptRule.acceptName(invite, clickCommand);
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals(lastAcceptKey) && now - lastAcceptAt < SAME_INVITE_MS) {
            return;
        }
        lastAcceptKey = key;
        lastAcceptAt = now;
        List<String> sentBefore = LfgCommandGate.sent();
        String refused = LfgCommandGate.accept(name);
        if (refused == null) {
            event("accepted " + name);
            ModChat.send(CHAT, ModChat.text("Accepted "), ModChat.value(invite.leader()), ModChat.text("'s party invite."));
            return;
        }
        // The Custom Party Finder may have just accepted this very invite through the same gate: nothing to say then.
        String last = sentBefore.isEmpty() ? "" : sentBefore.get(sentBefore.size() - 1);
        if (last.equalsIgnoreCase("/party accept " + name)) {
            event("already accepted " + name);
            return;
        }
        event("accept refused " + name + ": " + refused);
        LOGGER.warn("[AutoAcceptInvites] /party accept {} refused: {}", name, refused);
        ModChat.send(CHAT, ModChat.bad("Couldn't accept " + invite.leader() + "'s invite: " + refused));
    }

    private static String why(AutoAcceptRule.Decision d) {
        return switch (d) {
            case IN_PARTY -> "you are in a party";
            case NO_RECENT_PARTY -> "no party left in the last 2 minutes";
            case NOT_IN_LAST_PARTY -> "not from your last party";
            default -> d.name();
        };
    }

    private static void event(String e) {
        EVENTS.add(e);
        if (EVENTS.size() > 100) {
            EVENTS.remove(0);
        }
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    public static List<String> events() {
        return List.copyOf(EVENTS);
    }

    /** Feeds a stripped chat message as if Hypixel had sent it (no click event). */
    public static void onPlainForTest(String plain) {
        AutoAcceptRule.Invite invite = AutoAcceptRule.parse(plain == null ? null : plain.trim());
        if (invite != null) {
            onInvite(invite, null);
        }
    }

    public static List<String> lastPartyForTest() {
        return RULE.lastParty();
    }

    public static void resetForTest() {
        RULE.reset();
        lastAcceptKey = null;
        lastAcceptAt = 0L;
        EVENTS.clear();
    }
}
