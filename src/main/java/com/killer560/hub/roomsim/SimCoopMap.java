package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.HeldMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The co-op guests' dungeon map (coopsim-p5). A guest's client is not in the sim (SimState is the host's), so its
 * map features read a Hypixel map item in hotbar slot 9, as on Hypixel. The host paints that map from its own sim
 * stand-in ({@link HeldMap}, {@link SimRoomState}) in Hypixel's pixel format (DungeonMapScanner: 16 px rooms at a
 * 20 px pitch from corner 5,5, the F5-F7 layout every 6x6 sim floor uses) and sends it to the guests only: the host
 * keeps reading his stand-in exactly as before. Painted every 10 client ticks while guests are present (one 16 KB
 * array, ~121 cell reads), sent only when it changed or to a guest who has not had it. Pre-run there is no map, as
 * Hypixel hands it over at the start (HeldMap.emulated is false then).
 */
public final class SimCoopMap {

    /** The guests' map id: far above any map a sim world makes. */
    public static final int MAP_ID = 1_234_560;
    private static final int SIZE = 128;
    private static final int CORNER = 5;
    private static final int ROOM = 16;
    private static final int GRID = 11;

    private static byte[] last;
    /** Guests sent the current picture; at most the party, cleared with it. */
    private static final Set<UUID> SENT = new HashSet<>();
    private static int ticks;
    private static volatile int sends;

    private SimCoopMap() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SimCoopMap::tick);
    }

    /** Map packets sent to guests (test hook). */
    public static int sendsForTest() { return sends; }

    private static void tick(Minecraft client) {
        if (++ticks < 10) return;
        ticks = 0;
        List<SimCoop.Spot> guests = SimCoop.guestSpots();
        MinecraftServer server = client.getSingleplayerServer();
        if (guests.isEmpty() || server == null || !SimState.isActive() || !HeldMap.emulated()) {
            last = null;
            SENT.clear();
            return;
        }
        byte[] colors = paint();
        boolean changed = last == null || !Arrays.equals(last, colors);
        if (changed) {
            SENT.clear();
            last = colors;
        }
        List<UUID> to = new ArrayList<>();
        for (SimCoop.Spot g : guests) {
            if (SENT.add(g.uuid())) to.add(g.uuid());
        }
        if (to.isEmpty()) return;
        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(new MapId(MAP_ID), (byte) 0, true,
                Optional.of(List.<MapDecoration>of()), Optional.of(new MapItemSavedData.MapPatch(0, 0, SIZE, SIZE, colors)));
        server.execute(() -> {
            for (UUID id : to) {
                ServerPlayer sp = server.getPlayerList().getPlayer(id);
                if (sp == null) continue;
                ItemStack held = sp.getInventory().getItem(8);
                MapId has = held.get(DataComponents.MAP_ID);
                if (has == null || has.id() != MAP_ID) {
                    if (!held.isEmpty() && has == null) continue; // his own item in slot 9 is never replaced
                    ItemStack map = new ItemStack(Items.FILLED_MAP);
                    map.set(DataComponents.MAP_ID, new MapId(MAP_ID));
                    sp.getInventory().setItem(8, map);
                }
                sp.connection.send(packet);
                sends++;
            }
        });
    }

    /** The whole map in Hypixel's colors, from the host's stand-in answers. Client thread. */
    public static byte[] paint() {
        byte[] c = new byte[SIZE * SIZE];
        DungeonLayout layout = DungeonLayout.current();
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                int idx = gz * GRID + gx;
                int px = CORNER + (gx / 2) * (ROOM + 4) + (gx % 2 == 1 ? ROOM : 0);
                int pz = CORNER + (gz / 2) * (ROOM + 4) + (gz % 2 == 1 ? ROOM : 0);
                boolean roomX = gx % 2 == 0;
                boolean roomZ = gz % 2 == 0;
                if (roomX && roomZ) {
                    int state = HeldMap.roomState(idx);
                    if (state == HeldMap.STATE_NOT_SHOWN) continue;
                    if (state == HeldMap.STATE_UNOPENED) {
                        fill(c, px, pz, ROOM, ROOM, 85);
                        fill(c, px + ROOM / 2, pz + ROOM / 2, 1, 1, 119);
                        continue;
                    }
                    int color = roomColor(HeldMap.roomType(idx));
                    fill(c, px, pz, ROOM, ROOM, color);
                    // SimRoomState: 0 green check, 1 white check, 3 failed (DungeonMapScanner's STATE_*).
                    int sim = SimRoomState.stateFor(layout.name(layout.roomOfCell(idx)));
                    int mark = sim == 1 ? 34 : sim == 0 && color != 30 ? 30 : sim == 3 ? 18 : 0;
                    if (mark != 0) fill(c, px + ROOM / 2, pz + ROOM / 2, 1, 1, mark);
                } else if (roomX != roomZ) {
                    // A gap between two tiles: a separator inside one room fills it, a door is a 4x4 in its middle.
                    int[] sides = HeldMap.sides(idx);
                    if (HeldMap.joined(idx)) {
                        int color = roomColor(HeldMap.roomType(sides[0]));
                        if (roomX) fill(c, px, pz, ROOM, 4, color); else fill(c, px, pz, 4, ROOM, color);
                        continue;
                    }
                    int type = HeldMap.doorType(idx);
                    if (type == DungeonLayout.DOOR_NONE) continue;
                    int color = switch (type) {
                        case DungeonLayout.DOOR_WITHER -> 119;
                        case DungeonLayout.DOOR_BLOOD -> 18;
                        case DungeonLayout.DOOR_ENTRANCE -> 30;
                        default -> HeldMap.roomOpened(sides[0]) && HeldMap.roomOpened(sides[1]) ? 63 : 85;
                    };
                    if (roomX) fill(c, px + 6, pz, 4, 4, color); else fill(c, px, pz + 6, 4, 4, color);
                } else {
                    // The middle of a 2x2 room.
                    int a = layout.roomOfCell(idx - GRID - 1);
                    if (a >= 0 && a == layout.roomOfCell(idx + GRID + 1) && HeldMap.roomOpened(idx - GRID - 1)) {
                        fill(c, px, pz, 4, 4, roomColor(HeldMap.roomType(idx - GRID - 1)));
                    }
                }
            }
        }
        return c;
    }

    /** DungeonMapScanner's room colors by HeldMap.roomType. */
    private static int roomColor(String type) {
        if (type == null) return 63;
        return switch (type) {
            case "ENTRANCE" -> 30;
            case "BLOOD" -> 18;
            case "PUZZLE" -> 66;
            case "CHAMPION", "YELLOW" -> 74;
            case "FAIRY" -> 82;
            case "TRAP" -> 62;
            default -> 63;
        };
    }

    private static void fill(byte[] c, int x0, int z0, int w, int h, int color) {
        for (int z = Math.max(0, z0); z < Math.min(SIZE, z0 + h); z++) {
            for (int x = Math.max(0, x0); x < Math.min(SIZE, x0 + w); x++) c[z * SIZE + x] = (byte) color;
        }
    }
}
