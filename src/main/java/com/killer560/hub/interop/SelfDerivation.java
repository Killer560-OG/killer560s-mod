package com.killer560.hub.interop;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything this client can work out on its own, with no other mod anywhere in the party.
 * <p>
 * killer560 (2026-09-20): a user of this mod must not have to install someone else's mod, and must not need
 * their party mates to either. So before anything is treated as "only another mod knows this", it gets checked
 * against what Hypixel already sends every client: public chat lines, the action bar, the tab list, the dungeon
 * map item, and the entities/blocks in the world. Most of it turns out to be derivable.
 * <p>
 * What this class reads (all of it public, server-sent, and identical for every player in the run):
 * <ul>
 * <li><b>Action bar</b> {@code (2/4) Secrets} - the secrets found in the room YOU are standing in, right now.
 * Paired with {@link LiveMapFeature#currentRoomEntry()} it gives a real per-room count without anyone
 * announcing anything. This is the same signal NoammAddons' own {@code ActionBarParser} uses.</li>
 * <li><b>Terminal / device / lever completion</b> - {@code Name activated a terminal! (3/8)} is a normal
 * server chat line everyone in the party receives, so the party-wide count needs no relay at all.</li>
 * <li><b>Blood door opened</b> and the <b>Watcher's "You may pass"</b> line - same, server chat.</li>
 * <li><b>Prince / Bat bonus lines</b> - {@code A Prince falls. +1 Bonus Score}, server chat.</li>
 * </ul>
 * Facts derived elsewhere in the mod are pushed in by their owning feature rather than re-derived here -
 * {@code ScoreCalculatorFeature} already spots the mimic (a dying baby zombie) and reads the tab list's
 * Secrets/Crypts/Deaths, so it reports those. {@code WitherDragonsFeature} already derives M7 dragon spawns
 * from Hypixel's own flame-particle burst, which is why no dragon call is parsed out of anyone's chat.
 */
public final class SelfDerivation {

    // Server lines. Anchored and exact - a line that merely contains these words must not count.
    private static final Pattern DEVICE_COMPLETE = Pattern.compile(
            "^(\\w{1,16}) (activated|completed) a (lever|device|terminal)! \\((\\d+)/(\\d+)\\)(?:\\s.*)?$");
    private static final Pattern PRINCE_KILLED = Pattern.compile("^A Prince falls\\. \\+1 Bonus Score$");
    private static final Pattern BAT_KILLED = Pattern.compile("^A Bat has been slain\\. \\+1 Bonus Score$");
    private static final Pattern WATCHER_DONE =
            Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$");
    private static final String BLOOD_DOOR_OPENED = "The BLOOD DOOR has been opened!";

    // Hypixel's dungeon action bar carries "§7(§b2§7/§b4§7) Secrets"; formatting-stripped that is "(2/4) Secrets".
    // Older/other renderings drop the brackets, so both shapes are accepted - nothing else on the action bar
    // looks like "n/n Secrets", and the whole thing is gated on actually being in a dungeon.
    private static final Pattern ACTION_BAR_SECRETS = Pattern.compile("\\(?(\\d{1,2})/(\\d{1,2})\\)? Secrets");

    private static String lastRoomReported = "";
    private static int lastFoundReported = -1;

    private SelfDerivation() {
    }

    static void register() {
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
        // ChatObserver deliberately drops overlay (action bar) lines, so the secrets counter needs its own
        // subscription. Overlay lines arrive several times a second - onActionBar does nothing until the
        // numbers actually change.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                onActionBar(message);
            }
        });
    }

    static void onRunReset() {
        lastRoomReported = "";
        lastFoundReported = -1;
    }

    private static void onChat(String plain) {
        if (!active() || plain.isEmpty()) {
            return;
        }
        Matcher m = DEVICE_COMPLETE.matcher(plain);
        if (m.matches()) {
            int done = parseInt(m.group(4));
            PartyInteropState.Counter counter = switch (m.group(3)) {
                case "terminal" -> PartyInteropState.Counter.TERMINALS_DONE;
                case "device" -> PartyInteropState.Counter.DEVICES_DONE;
                default -> PartyInteropState.Counter.LEVERS_DONE;
            };
            PartyInteropState.offerCounter(counter, done, InteropSource.SELF, m.group(1));
            return;
        }
        if (BLOOD_DOOR_OPENED.equals(plain)) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BLOOD_OPENED, InteropSource.SELF, null);
        } else if (WATCHER_DONE.matcher(plain).matches()) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BLOOD_DONE, InteropSource.SELF, null);
        } else if (PRINCE_KILLED.matcher(plain).matches()) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.PRINCE_KILLED, InteropSource.SELF, null);
        } else if (BAT_KILLED.matcher(plain).matches()) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BAT_KILLED, InteropSource.SELF, null);
        }
    }

    private static void onActionBar(Component message) {
        if (!active()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null || plain.indexOf('/') < 0 || !plain.contains("Secrets")) {
            return; // cheapest possible reject - this runs on nearly every action bar tick
        }
        Matcher m = ACTION_BAR_SECRETS.matcher(plain);
        if (!m.find()) {
            return;
        }
        int found = parseInt(m.group(1));
        int total = parseInt(m.group(2));
        if (found < 0 || total <= 0) {
            return;
        }
        RoomEntry room = LiveMapFeature.currentRoomEntry();
        String name = room == null || room.name == null ? "" : room.name;
        if (name.isEmpty()) {
            return; // a count with no room to attach it to tells us nothing another feature can use
        }
        if (name.equals(lastRoomReported) && found == lastFoundReported) {
            return;
        }
        lastRoomReported = name;
        lastFoundReported = found;
        PartyInteropState.offerRoomSecrets(name, found, total, InteropSource.SELF, null);
    }

    private static boolean active() {
        InteropConfig cfg = InteropConfig.getInstance();
        return cfg.isEnabled() && cfg.isSelfDerivation() && DungeonState.isInDungeon();
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
