package com.killer560.hub.reparty;

import com.killer560.hub.lfg.LfgFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.OutgoingChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reparty (Skyblocker, Skytils): {@code /rp} or {@code /reparty} lists the party, disbands it, waits the Invite Delay
 * and invites everyone back. Only the party leader can disband, so anyone else gets one line saying so and nothing is sent.
 * <p>
 * Auto Rejoin: when another player disbands the party ("X has disbanded the party!") and then invites you
 * ("X has invited you to join their party!") within the Rejoin Window, {@code /party accept X} goes out, but only for
 * a player who was in the party that was just disbanded (a snapshot of {@link PartyTracker#teammates()} taken before
 * the disband line), and once per disband.
 * <p>
 * Steps run from the client tick, never a sleep: {@code party list} (its reply is read for a second and a half), then
 * {@code party disband}, then one {@code party invite a b c} a moment later. Every send goes through
 * {@link OutgoingChat} (Hypixel's half-second line gap) and waits until no movement key is held and the player is not
 * sprinting, because a command sent mid-walk draws experimental Grim's ChatC. A new world or 60 seconds without finishing
 * drops the job; a job never starts while another runs.
 */
public final class RepartyFeature {

    private static final Pattern NAME = Pattern.compile("(?:\\[[^\\]]+\\] ?)?([A-Za-z0-9_]{1,16}) ?●");
    private static final long LIST_WAIT_MS = 1_500L;
    private static final long GIVE_UP_MS = 60_000L;

    private enum Phase { IDLE, SEND_LIST, LISTING, SEND_DISBAND, SEND_INVITE }

    private static Phase phase = Phase.IDLE;
    private static long dueMs;
    private static long startedMs;
    private static final List<String> LAST_MEMBERS = new ArrayList<>();
    private static List<String> formerMembers = List.of();
    private static long disbandedAtMs;
    private static String acceptName;
    private static long acceptUntilMs;
    private static String leader;
    private static final List<String> OTHERS = new ArrayList<>();
    private static Object level;

    private RepartyFeature() {
    }

    public static void register() {
        RepartyConfig.getInstance();
        ChatObserver.subscribe(m -> onLine(ChatObserver.strip(m), System.currentTimeMillis()));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("RepartyFeature", c -> step(System.currentTimeMillis())));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : new String[]{"rp", "reparty"}) {
                dispatcher.register(ClientCommands.literal(name).executes(ctx -> {
                    start();
                    return 1;
                }));
            }
        });
    }

    /** Drops a running job and any rejoin state; the testkit calls it between cases (a second /rp while one runs is ignored). */
    public static synchronized void reset() {
        phase = Phase.IDLE;
        acceptName = null;
        formerMembers = List.of();
        LAST_MEMBERS.clear();
        disbandedAtMs = 0L;
    }

    /** Starts a reparty; public so the testkit can drive it. */
    public static synchronized void start() {
        if (!RepartyConfig.getInstance().isEnabled()) {
            ModChat.send("Reparty", ModChat.text("Reparty is off. Turn it on in the menu first."));
            return;
        }
        if (phase != Phase.IDLE) {
            return;
        }
        long now = System.currentTimeMillis();
        phase = Phase.SEND_LIST;
        startedMs = now;
        dueMs = now;
        leader = null;
        OTHERS.clear();
        level = Minecraft.getInstance().level;
    }

    /** One real chat line, already stripped; public so the testkit can feed party lists and the run end. */
    public static synchronized void onLine(String plain, long now) {
        if (phase == Phase.LISTING) {
            String label = plain.startsWith("Party Leader:") ? "L" : plain.startsWith("Party Moderators:") || plain.startsWith("Party Members:") ? "M" : null;
            if (label != null) {
                Matcher m = NAME.matcher(plain.substring(plain.indexOf(':') + 1));
                while (m.find()) {
                    if ("L".equals(label)) {
                        leader = m.group(1);
                    } else {
                        OTHERS.add(m.group(1));
                    }
                }
            }
        }
        RepartyConfig cfg = RepartyConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isAutoRejoin()) {
            return;
        }
        Matcher dis = LfgFeature.DISBANDED.matcher(plain);
        if (dis.matches()) {
            if (LAST_MEMBERS.stream().anyMatch(dis.group(1)::equalsIgnoreCase)) {
                formerMembers = List.copyOf(LAST_MEMBERS);
                disbandedAtMs = now;
            }
            LAST_MEMBERS.clear();
            return;
        }
        Matcher inv = LfgFeature.INVITE.matcher(plain);
        if (inv.matches()) {
            String who = inv.group(1);
            if (disbandedAtMs != 0L && now - disbandedAtMs <= cfg.getRejoinWindow() * 1000L
                    && formerMembers.stream().anyMatch(who::equalsIgnoreCase)) {
                acceptName = who;
                acceptUntilMs = now + 10_000L;
                disbandedAtMs = 0L;
            }
        } else if (plain.length() < 200) {
            LAST_MEMBERS.clear();
            LAST_MEMBERS.addAll(PartyTracker.teammates());
        }
    }

    /** One tick of the job; public so the testkit can step it with exact times. */
    public static synchronized void step(long now) {
        if (acceptName != null) {
            if (now > acceptUntilMs) {
                acceptName = null;
            } else if (OutgoingChat.standingStill()) {
                OutgoingChat.command("party accept " + acceptName);
                ModChat.log("Reparty", ModChat.text("Rejoining " + acceptName + "'s party."));
                acceptName = null;
            }
        }
        if (phase == Phase.IDLE) {
            return;
        }
        if (Minecraft.getInstance().level != level || now - startedMs > GIVE_UP_MS) {
            phase = Phase.IDLE;
            return;
        }
        if (now < dueMs || (phase != Phase.LISTING && !OutgoingChat.standingStill())) {
            return;
        }
        switch (phase) {
            case SEND_LIST -> {
                OutgoingChat.command("party list");
                phase = Phase.LISTING;
                dueMs = now + LIST_WAIT_MS;
            }
            case LISTING -> {
                String self = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getGameProfile().name();
                OTHERS.removeIf(self::equalsIgnoreCase);
                if (leader == null) {
                    ModChat.send("Reparty", ModChat.bad("You are not in a party."));
                    phase = Phase.IDLE;
                } else if (!leader.equalsIgnoreCase(self)) {
                    ModChat.send("Reparty", ModChat.bad("Only the party leader can reparty (" + leader + " leads)."));
                    phase = Phase.IDLE;
                } else if (OTHERS.isEmpty()) {
                    ModChat.send("Reparty", ModChat.bad("Nobody else is in the party."));
                    phase = Phase.IDLE;
                } else {
                    ModChat.send("Reparty", ModChat.text("Re-inviting " + OTHERS.size() + " player" + (OTHERS.size() == 1 ? "" : "s") + "."));
                    phase = Phase.SEND_DISBAND;
                }
            }
            case SEND_DISBAND -> {
                OutgoingChat.command("party disband");
                phase = Phase.SEND_INVITE;
                dueMs = now + (long) (RepartyConfig.getInstance().getInviteDelay() * 1000);
            }
            case SEND_INVITE -> {
                OutgoingChat.command("party invite " + String.join(" ", OTHERS));
                ModChat.log("Reparty", ModChat.text("Invited " + String.join(", ", OTHERS) + "."));
                phase = Phase.IDLE;
            }
            default -> {
            }
        }
    }
}
