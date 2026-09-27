package com.killer560.hub.blessings;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The run's current blessing levels, parsed from the player-list FOOTER exactly like NoammAddons'
 * {@code DungeonListener.kt} does ({@code is ClientboundTabListPacket -> Blessing.entries.forEach { ... }},
 * lines 85-89) and Devonian's {@code BlessingsDisplay.kt} ({@code on<TabFooterEvent>}).
 * <p>
 * <b>One parser, one packet hook.</b> The footer arrives through the mod's existing tab-list hook -
 * {@code witherdragons/WitherDragonsPacketMixin#handleTabListCustomisation} -> {@code WitherDragonsPackets.onTabList}
 * - not a new mixin. {@code witherdragons/P5State} already parsed Power and Time out of that same footer for dragon
 * priority; this class parses all five with the same strings, and P5State should be reduced to
 * {@code BlessingTracker.level(Blessing.POWER)} / {@code (Blessing.TIME)} so there is only ever one parse (see the
 * handoff notes - that edit is the integrator's, this package does not touch P5State).
 * <p>
 * <b>Chat lines:</b> none. The task allowed "Blessing of X" chat pickup lines "if the exact strings are verified
 * from the sources" - neither NoammAddons, Devonian nor anything else in this repo contains a blessing chat
 * message, so nothing is matched against chat and pickups are detected purely as a footer level INCREASE.
 */
public final class BlessingTracker {

    private static final Map<Blessing, Integer> LEVELS = new EnumMap<>(Blessing.class);

    private BlessingTracker() {
    }

    /** Called from {@code WitherDragonsPackets.onTabList} with {@code packet.footer()} (client thread).
     *  Only parsed inside a dungeon, same guard as {@code P5State.onTabFooter}. */
    public static void onTabFooter(Component footer) {
        if (footer == null || !DungeonState.isInDungeon()) {
            return;
        }
        String text = ChatFormatting.stripFormatting(footer.getString());
        if (text == null) {
            return;
        }
        for (Blessing blessing : Blessing.values()) {
            Matcher m = blessing.pattern().matcher(text);
            if (!m.find()) {
                continue;
            }
            int level = Blessing.parseRoman(m.group(1));
            if (level <= 0) {
                continue;
            }
            int previous = level(blessing);
            if (level == previous) {
                continue;
            }
            LEVELS.put(blessing, level);
        }
    }

    public static int level(Blessing blessing) {
        Integer value = LEVELS.get(blessing);
        return value == null ? 0 : value;
    }

    public static boolean any() {
        for (Blessing blessing : Blessing.values()) {
            if (level(blessing) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Cleared on world change (NoammAddons resets {@code Blessing.reset()} on dungeon end, Devonian on
     *  {@code WorldChangeEvent}). */
    public static void reset() {
        LEVELS.clear();
    }
}
