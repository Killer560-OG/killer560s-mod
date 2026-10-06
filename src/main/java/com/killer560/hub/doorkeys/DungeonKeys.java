package com.killer560.hub.doorkeys;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The team's dungeon keys and the keys lying on the ground. Hypixel keys belong to the whole team
 * (hypixelskyblock.minecraft.wiki, Wither Key / Blood Key: "usable by any person within the dungeon, regardless of
 * whether or not they are the player who obtained it"), so possession is the team's:
 * <ul>
 *   <li>the sidebar's "Keys: ■ ✓ ■ 1x" line when there is one ({@link DungeonState#sidebarBloodKey},
 *       {@link DungeonState#sidebarWitherKeys}) - it is the server's own count;</li>
 *   <li>otherwise the chat lines: "&lt;name&gt; has obtained Wither Key!" adds one, "&lt;name&gt; opened a WITHER door!"
 *       spends one, "&lt;name&gt; has obtained Blood Key!" / "The BLOOD DOOR has been opened!" for the blood key. A
 *       teammate's pickup is the same line, so it counts the same.</li>
 * </ul>
 * A dropped key is a named armour stand ("Wither Key" / "Blood Key"), as {@link DoorKeysFeature} draws them.
 * Every pattern is anchored with a {@code [A-Za-z0-9_]{1,16}} name: these lines make automation click doors and travel,
 * and a player's chat always has a prefix before the name (CLAUDE.md, "anchor every chat pattern that gates an action").
 */
public final class DungeonKeys {

    public static final Pattern WITHER_OBTAINED = Pattern.compile("^[A-Za-z0-9_]{1,16} has obtained Wither Key!?$");
    public static final Pattern BLOOD_OBTAINED = Pattern.compile("^[A-Za-z0-9_]{1,16} has obtained Blood Key!?$");
    /** Hypixel has been seen both with and without a leading "A ". */
    public static final Pattern WITHER_PICKED_UP = Pattern.compile("^(?:A )?Wither Key was picked up!?$");
    public static final Pattern BLOOD_PICKED_UP = Pattern.compile("^(?:A )?Blood Key was picked up!?$");
    public static final Pattern WITHER_DOOR_OPENED = Pattern.compile("^[A-Za-z0-9_]{1,16} opened a WITHER door!?$");
    public static final Pattern BLOOD_DOOR_OPENED = Pattern.compile("^The BLOOD DOOR has been opened!?$");

    // ---- pickup range (killer560, 2026-10-06: "make sure they have the new updated longer pickup range ... assuming they
    // have the magnetic talisman") ----
    /**
     * SOURCED: Hypixel SkyBlock 0.27.2 (the Minister Update, a permanent addition - "the complete set of 'Minister Perks'"),
     * F22_Raptor's Folf Shard & Dungeon Improvements: "Increased the pickup range of Wither Keys and Blood Keys in the
     * Catacombs by 5 blocks" (hypixel.net patch notes; hypixelskyblock.minecraft.wiki Changelog/2026/October 6).
     */
    public static final double KEY_RANGE_BONUS = 5.0;
    /**
     * SOURCED for ITEMS: hypixelskyblock.minecraft.wiki Magnetic Talisman - "increases the player's item pickup range by 3x
     * the normal range. Its effects do not stack." UNVERIFIED that it touches keys, which are armour stands, not items.
     */
    public static final double TALISMAN_MULTIPLIER = 3.0;
    /**
     * UNVERIFIED default for the base key pickup range: neither the wiki nor the patch notes give one. 1 block is
     * vanilla's own item pickup reach (26.1.2 {@code Player.aiStep}: the hitbox inflated by 1, 0.5, 1 - javap), the
     * "normal range" the talisman multiplies for items. A setting (Key Base Range) so a measurement can replace it.
     */
    public static final double DEFAULT_BASE_RANGE = 1.0;

    /** Blocks from his feet to a key within which it is picked up: base (x3 with the talisman) + the 0.27.2 bonus. */
    public static double pickupRange(double base, boolean talisman) {
        return Math.max(0.0, base) * (talisman ? TALISMAN_MULTIPLIER : 1.0) + KEY_RANGE_BONUS;
    }

    /** A key on the ground: its entity id, where it is, and which key. */
    public record Dropped(int id, double x, double y, double z, boolean blood) {
    }

    private static int chatWither;
    private static boolean chatBlood;
    private static Object level;
    private static volatile String lastPickupLine;

    private DungeonKeys() {
    }

    public static void register() {
        ChatObserver.subscribe(m -> onChat(m.getString()));
    }

    static void onChat(String raw) {
        String msg = ChatFormatting.stripFormatting(raw);
        if (msg == null) {
            return;
        }
        msg = msg.trim();
        sync();
        if (WITHER_OBTAINED.matcher(msg).matches()) {
            chatWither++;
            lastPickupLine = msg;
        } else if (WITHER_PICKED_UP.matcher(msg).matches()) {
            // May come beside the "has obtained" line: never more than one key from it.
            chatWither = Math.max(chatWither, 1);
            lastPickupLine = msg;
        } else if (WITHER_DOOR_OPENED.matcher(msg).matches()) {
            chatWither = Math.max(0, chatWither - 1);
        } else if (BLOOD_OBTAINED.matcher(msg).matches() || BLOOD_PICKED_UP.matcher(msg).matches()) {
            chatBlood = true;
            lastPickupLine = msg;
        } else if (BLOOD_DOOR_OPENED.matcher(msg).matches()) {
            chatBlood = false;
        }
    }

    /** Chat counts belong to one run: a new world forgets them. */
    private static void sync() {
        Minecraft client = Minecraft.getInstance();
        Object now = client == null ? null : client.level;
        if (now != level) {
            level = now;
            chatWither = 0;
            chatBlood = false;
        }
    }

    /** The team's wither keys: the sidebar's count when it shows one, else what chat said. */
    public static int witherKeys() {
        sync();
        int sidebar = DungeonState.sidebarWitherKeys();
        return sidebar >= 0 ? sidebar : chatWither;
    }

    /** Whether the team holds the blood key: 1 yes, 0 no, -1 unknown (no Keys line and no chat line yet). */
    public static int bloodKey() {
        sync();
        int sidebar = DungeonState.sidebarBloodKey();
        if (sidebar >= 0) {
            return sidebar;
        }
        return chatBlood ? 1 : -1;
    }

    /** The last pickup line seen ("Bob has obtained Wither Key!"), for logs and tests. */
    public static String lastPickupLine() {
        return lastPickupLine;
    }

    /** Every dropped key the client can see. Client thread. */
    public static List<Dropped> dropped(Minecraft client) {
        List<Dropped> out = new ArrayList<>();
        if (client == null || client.level == null) {
            return out;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || entity.isRemoved()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(entity.getName().getString());
            if ("Wither Key".equals(name) || "Blood Key".equals(name)) {
                out.add(new Dropped(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), "Blood Key".equals(name)));
            }
        }
        return out;
    }
}
