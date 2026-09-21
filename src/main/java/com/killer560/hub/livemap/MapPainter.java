package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one dungeon-map painter, shared by the {@link InteractiveMapScreen} and the
 * {@link LiveMapFeature.LiveMapHudElement} HUD.
 * <p>
 * killer560, 2026-09-17 (screenshots {@code 14.17.05} = ours vs {@code 14.18.46} = the real held map): the HUD map
 * "does not look like the real dungeon map" - it drew every room the same grey on a uniform 8px grid with a 1px inset
 * on all four sides, so rooms, corridors and gaps were all the same size and nothing ever touched anything. Only the
 * interactive map had the real layout, so that painter was pulled out here and the HUD now calls it too.
 * <p>
 * Geometry is NoammAddons' {@code MapRenderer.renderRooms}/{@code drawRoomConnector}: a room is 16 units, the gap
 * between two rooms is 4 units, and a connector starts at exactly {@code +roomSize} so it is flush with both rooms
 * (nothing is ever inset). The whole 11x11 grid is therefore {@link #MAP_UNITS} units square. Everything here works
 * in those units and is placed by an (origin, units-per-pixel) pair, so the same code draws a 116px HUD map and a
 * zoomed full-screen one.
 * <p>
 * Colours are the real map's own bytes, not invented: NoammAddons {@code RoomType.kt}/{@code DoorType.kt} decode
 * Hypixel's {@code MapColor} ids, giving brown normal {@code #724318} (byte 63), green entrance (30), magenta puzzle
 * (66), orange trap (62), yellow miniboss (74), pink fairy (82), red blood (18), grey unopened (85) and near-black
 * wither doors (119). Every one of them is a colour picker in the Dungeon Map tab; these are just the defaults.
 * A plain door takes the colour of its most interesting neighbouring room (NoammAddons {@code DoorTile.getColor},
 * Devonian {@code doorColor}) - that is why corridors on the real map are the same brown as the rooms they join.
 */
final class MapPainter {

    /** 6 rooms of 16 units + 5 gaps of 4 units. */
    static final int MAP_UNITS = 116;
    static final int ROOM_UNITS = 16;
    static final int GAP_UNITS = 4;

    /**
     * killer560, 2026-09-20: "the map the legit version should have is the non funnymap style where it only shows
     * opened rooms". On the legit jar this painter must never draw anything Hypixel has not itself revealed, so a
     * cell is painted only when the vanilla dungeon map item has data for it - our own world block scan and the room
     * database are ignored for DRAWING. (They keep running: the solvers, Secret Waypoints and room names all still
     * need {@code LiveMapFeature.currentRoomEntry()}, which is about the room you are standing in, not a reveal.)
     * The cheat jar keeps the fuller map.
     */
    private static final boolean REVEAL_ONLY_FROM_MAP_ITEM = !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;

    private MapPainter() {
    }

    // ------------------------------------------------------------------------------- what the map item has revealed

    /** p3sim.net has no dungeon map item at all, so the strict reveal-only rule above would leave the map
     *  permanently blank there. It is a practice site, not Hypixel, so the world scan still drives what
     *  {@link #cellRevealed}/{@link #visibleState} allow onto the Interactive Map and the pathfinders that
     *  read through it - same carve-out {@code SkyblockGate.isP3Sim} already makes for the Skyblock gate.
     *  Package-visible (not {@code private}) so {@link LiveMapFeature.LiveMapHudElement} can reuse this same
     *  server-IP test for its own, separate p3sim rule (killer560: hide the Dungeon Map HUD on p3sim, same
     *  as a boss room) instead of a second copy of the check. */
    static boolean onP3Sim() {
        net.minecraft.client.multiplayer.ServerData server = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip != null
                && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    private static boolean hideUnrevealed() {
        return REVEAL_ONLY_FROM_MAP_ITEM && !onP3Sim();
    }

    /** @return whether this single cell may be painted at all. */
    private static boolean cellRevealed(int idx) {
        if (!hideUnrevealed()) {
            return true;
        }
        return DungeonMapScanner.isCalibrated() && DungeonMapScanner.kindAt(idx) != DungeonMapScanner.KIND_NONE
                && DungeonMapScanner.stateAt(idx) != DungeonMapScanner.STATE_UNDISCOVERED;
    }

    /** Map state of a whole room: the most progressed of its tiles, the way NoammAddons merges a {@code UniqueRoom}.
     *  {@code STATE_UNDISCOVERED} means "do not draw this room". */
    static int visibleState(LiveMapFeature.RoomGroup group) {
        if (!DungeonMapScanner.isCalibrated()) {
            // Before the map item exists (start of a run, boss, p3sim): the legit map has nothing to show.
            return hideUnrevealed() ? DungeonMapScanner.STATE_UNDISCOVERED : DungeonMapScanner.STATE_DISCOVERED;
        }
        int best = DungeonMapScanner.STATE_UNDISCOVERED;
        for (int tile : group.tiles) {
            best = Math.min(best, DungeonMapScanner.stateAt(tile));
        }
        // killer560, 2026-09-20 (screenshot 14.52.05, cheat build): "it still isn't properly showing each room as
        // grey - some still have question marks" - two teammates were standing INSIDE a room our own world scan had
        // long since marked visited, yet it kept drawing grey with "?" because the held map's own byte for it was
        // still STATE_UNOPENED, not STATE_UNDISCOVERED - the old check here only forgave UNDISCOVERED, so a room the
        // map item is merely slow to redraw as opened stayed stuck behind its stale "?" even on the cheat build. Any
        // world-scanned tile (grid[] != UNKNOWN, i.e. we resolved a real roof height there) proves the room is open,
        // whatever the map item currently says - the legit build still never takes this branch (hideUnrevealed()).
        if (!hideUnrevealed() && best >= DungeonMapScanner.STATE_UNOPENED) {
            for (int tile : group.tiles) {
                if (LiveMapFeature.isWorldScanned(tile)) {
                    return DungeonMapScanner.STATE_DISCOVERED;
                }
            }
        }
        return best;
    }

    static boolean isRevealed(LiveMapFeature.RoomGroup group) {
        return visibleState(group) != DungeonMapScanner.STATE_UNDISCOVERED;
    }

    /** On the legit map a room the game only shows as a grey "unopened" block must not carry its name, its secret
     *  count or its real type - Hypixel has not told you what is in there yet. */
    static boolean identityRevealed(LiveMapFeature.RoomGroup group) {
        int state = visibleState(group);
        if (state == DungeonMapScanner.STATE_UNDISCOVERED) {
            return false;
        }
        return !hideUnrevealed() || state != DungeonMapScanner.STATE_UNOPENED;
    }

    // ------------------------------------------------------------------------------------------- geometry

    /** Unit offset of grid index {@code g} (even = room, odd = gap). */
    static int cellPos(int g) {
        return (g / 2) * (ROOM_UNITS + GAP_UNITS) + (g % 2 == 1 ? ROOM_UNITS : 0);
    }

    static int cellSize(int g) {
        return g % 2 == 0 ? ROOM_UNITS : GAP_UNITS;
    }

    /** Map-unit position of a world coordinate (tile centres land on tile centres). */
    static double worldToUnits(double world) {
        return (world - LiveMapFeature.START_X) * 0.625 + 8;
    }

    /** @return the grid index under a point given in map units, or -1. */
    static int unitToGrid(double u) {
        if (u < 0) {
            return -1;
        }
        int k = (int) Math.floor(u / (ROOM_UNITS + GAP_UNITS));
        double r = u - k * (double) (ROOM_UNITS + GAP_UNITS);
        int g = r < ROOM_UNITS ? 2 * k : 2 * k + 1;
        return g > 10 ? -1 : g;
    }

    private static int px(float origin, double units, float ppu) {
        return Math.round(origin + (float) units * ppu);
    }

    // ------------------------------------------------------------------------------------------- colours

    static int multiply(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** QUOI {@code base.mix(roomInCol.withAlpha(255), roomInCol.alpha)}. */
    static int mix(int base, int overlay) {
        float t = ((overlay >>> 24) & 0xFF) / 255f;
        int r = Math.round(((base >> 16) & 0xFF) * (1 - t) + ((overlay >> 16) & 0xFF) * t);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - t) + ((overlay >> 8) & 0xFF) * t);
        int b = Math.round((base & 0xFF) * (1 - t) + (overlay & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Database type where the room is identified, else the dungeon map item's own room byte
     *  (NoammAddons {@code RoomType.fromMapColor}). */
    static String roomType(LiveMapFeature.RoomGroup group) {
        if (!identityRevealed(group)) {
            return "UNKNOWN"; // legit map: unopened means unopened, type included
        }
        if (group.entry != null && group.entry.type != null) {
            return group.entry.type.toUpperCase(Locale.ROOT);
        }
        return switch (DungeonMapScanner.roomColorAt(group.mainIdx)) {
            case 18 -> "BLOOD";
            case 82 -> "FAIRY";
            case 34 -> "RARE";
            case 74 -> "CHAMPION";
            case 66 -> "PUZZLE";
            case 62 -> "TRAP";
            case 30 -> "ENTRANCE";
            case 63, 85 -> "NORMAL";
            default -> group.entry == null && !LiveMapFeature.isWorldScanned(group.mainIdx) ? "UNKNOWN" : "NORMAL";
        };
    }

    static int typeColor(String type, LiveMapConfig cfg) {
        return switch (type) {
            case "ENTRANCE" -> cfg.getColorEntrance();
            case "PUZZLE" -> cfg.getColorPuzzle();
            case "TRAP" -> cfg.getColorTrap();
            case "CHAMPION", "MINIBOSS", "YELLOW" -> cfg.getColorMiniboss();
            case "BLOOD" -> cfg.getColorBlood();
            case "FAIRY" -> cfg.getColorFairy();
            case "RARE" -> cfg.getColorRare();
            case "UNKNOWN" -> cfg.getColorUnopened();
            default -> cfg.getColorNormal();
        };
    }

    static String typeName(String type) {
        return switch (type) {
            case "CHAMPION", "MINIBOSS", "YELLOW" -> "Miniboss";
            case "UNKNOWN" -> "Unknown";
            default -> type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    /** Devonian {@code RoomTypes.prio} - which neighbour a plain door borrows its colour from. Fairy sits BELOW
     *  normal because NoammAddons' {@code DoorTile.getColor} explicitly prefers a non-fairy neighbour. */
    private static int typePriority(String type) {
        return switch (type) {
            case "BLOOD" -> 0;
            case "ENTRANCE" -> 10;
            case "PUZZLE" -> 20;
            case "TRAP" -> 30;
            case "CHAMPION", "MINIBOSS", "YELLOW" -> 40;
            case "RARE" -> 50;
            case "FAIRY" -> 85;
            case "UNKNOWN" -> 90;
            default -> 80;
        };
    }

    /** Room fill before the current-room/hover tweaks. NoammAddons {@code RoomTile.getColor}: an unopened room is
     *  grey whatever its real type is, because that is all the map itself knows about it. */
    static int roomColor(LiveMapFeature.RoomGroup group, LiveMapConfig cfg) {
        int state = visibleState(group);
        if (state == DungeonMapScanner.STATE_UNOPENED) {
            return cfg.getColorUnopened();
        }
        if (state == DungeonMapScanner.STATE_UNDISCOVERED) {
            return multiply(cfg.getColorUnopened(), 1f - cfg.getDarkenUnopened());
        }
        return cfg.isColourByType() ? typeColor(roomType(group), cfg) : cfg.getColorNormal();
    }

    /** A teammate-reported room's fill colour: its real type colour once {@link RoomDatabase#lookupByName}
     *  resolves it, else a plain generic box - never the "unopened"/grey treatment {@link #roomColor} gives
     *  an unseen local cell, since a reported cell is not "unseen", it is "seen by someone else". Dimmed the
     *  same way {@link #drawDoors} dims a reported door when {@link LiveMapConfig#isMarkReportedRooms()} is on. */
    static int reportedRoomColor(PartyMapIntel.ReportedRoom room, LiveMapConfig cfg) {
        int color;
        if (room.entry() != null && room.entry().type != null) {
            color = cfg.isColourByType() ? typeColor(room.entry().type.toUpperCase(Locale.ROOT), cfg) : cfg.getColorNormal();
        } else {
            color = cfg.getColorNormal(); // unresolved name - generic discovered room, never dropped
        }
        return cfg.isMarkReportedRooms() ? multiply(color, REPORTED_DIM) : color;
    }

    /** Fills a reported room's single 16-unit anchor cell - see {@link PartyMapIntel.ReportedRoom}'s doc for
     *  why a multi-tile room only ever gets one box here. */
    static void drawReportedRoom(GuiGraphicsExtractor graphics, PartyMapIntel.ReportedRoom room, LiveMapConfig cfg,
                                 float ox, float oy, float ppu) {
        int gx = room.col();
        int gz = room.row();
        int color = reportedRoomColor(room, cfg);
        int x0 = px(ox, cellPos(gx), ppu);
        int y0 = px(oy, cellPos(gz), ppu);
        int x1 = px(ox, cellPos(gx) + ROOM_UNITS, ppu);
        int y1 = px(oy, cellPos(gz) + ROOM_UNITS, ppu);
        graphics.fill(x0, y0, x1, y1, color);
        if (cfg.isMarkReportedRooms()) {
            int m = Math.max(3, Math.round(4 * ppu));
            graphics.fill(x0, y0, Math.min(x1, x0 + m), Math.min(y1, y0 + m), REPORTED_MARK_COLOR);
        }
    }

    /** Same {@code found/total} shape {@link #secretsText} draws for a local room, for a reported one -
     *  {@code found} can only ever come from a teammate's own {@code PartyInteropState} report here, since
     *  this client has not opened the room itself. */
    static String reportedSecretsText(PartyMapIntel.ReportedRoom room) {
        RoomEntry entry = room.entry();
        if (entry == null) {
            return "?";
        }
        if (entry.secrets == 0) {
            return "0";
        }
        com.killer560.hub.interop.PartyInteropState.Fact<com.killer560.hub.interop.PartyInteropState.RoomSecrets> fact =
                com.killer560.hub.interop.PartyInteropState.roomSecrets(entry.name);
        int found = fact != null ? fact.value().found() : -1;
        return (found < 0 ? "?" : String.valueOf(found)) + "/" + entry.secrets;
    }

    /** A reported room's name/secrets label, drawn independently of {@link #labels} (a reported room has no
     *  {@code RoomGroup} - no tiles, no map-item state, no checkmark) but following the same
     *  {@code roomLabels} style so it does not visually contradict the local rooms around it. */
    static void drawReportedLabel(GuiGraphicsExtractor graphics, Font font, int style, LiveMapConfig cfg,
                                  PartyMapIntel.ReportedRoom room, float ox, float oy, float ppu) {
        if (style == 0 || style == 1) {
            return; // style 1 (checkmarks) has nothing to draw here - a reported room has no scan state
        }
        RoomEntry entry = room.entry();
        String type = entry != null && entry.type != null ? entry.type.toUpperCase(Locale.ROOT) : null;
        List<String> lines = new ArrayList<>();
        if (style == 2) {
            lines.add(reportedSecretsText(room));
        } else {
            boolean skipName = "ENTRANCE".equals(type) || "FAIRY".equals(type) || "BLOOD".equals(type);
            if (!skipName) {
                String name = entry != null && entry.name != null ? entry.name : room.reportedName();
                if (name != null && !name.isBlank()) {
                    java.util.Collections.addAll(lines, name.split(" "));
                }
            }
            if (style == 4 && entry != null && entry.secrets > 0) {
                lines.add(reportedSecretsText(room));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        int maxWidth = 0;
        for (String s : lines) {
            maxWidth = Math.max(maxWidth, font.width(s));
        }
        float want = 0.4f * cfg.getFontScale() * ppu;
        float fit = Math.min(ROOM_UNITS * ppu / Math.max(1, maxWidth), ROOM_UNITS * ppu / (lines.size() * font.lineHeight));
        float scale = Math.max(0.3f, Math.min(want, fit));
        float cx = ox + (cellPos(room.col()) + ROOM_UNITS / 2f) * ppu;
        float cy = oy + (cellPos(room.row()) + ROOM_UNITS / 2f) * ppu;
        int color = 0xFFCCCCCC; // neutral - a reported room has no cleared/failed/unopened state to colour by
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale, scale);
        int top = Math.round(-lines.size() * font.lineHeight / 2f);
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            graphics.text(font, s, -font.width(s) / 2, top + i * font.lineHeight, color, cfg.isTextShadow());
        }
        graphics.pose().popMatrix();
    }

    /** Colour a plain corridor inherits from the rooms it joins. */
    private static int connectorColor(int idx, LiveMapConfig cfg) {
        int g = LiveMapFeature.GRID;
        int gx = idx % g;
        int gz = idx / g;
        int best = Integer.MAX_VALUE;
        int color = cfg.getColorNormal();
        for (int side = 0; side < 2; side++) {
            int nx = gx % 2 == 1 ? gx + (side == 0 ? -1 : 1) : gx;
            int nz = gz % 2 == 1 ? gz + (side == 0 ? -1 : 1) : gz;
            if (nx == gx && nz == gz) {
                continue;
            }
            if (nx < 0 || nz < 0 || nx >= g || nz >= g) {
                continue;
            }
            int gid = LiveMapFeature.groupIdAt(nx + nz * g);
            if (gid < 0) {
                continue;
            }
            LiveMapFeature.RoomGroup group = LiveMapFeature.groupsView().get(gid);
            if (!isRevealed(group)) {
                continue;
            }
            String type = roomType(group);
            int prio = typePriority(type);
            if (prio < best) {
                best = prio;
                color = roomColor(group, cfg);
            }
        }
        return color;
    }

    // ------------------------------------------------------------------------------------------- rooms

    static void drawRoom(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int gid, int color,
                         float ox, float oy, float ppu) {
        for (int c : group.cells) {
            int gx = c % LiveMapFeature.GRID;
            int gz = c / LiveMapFeature.GRID;
            if (gx % 2 == 1 && gz % 2 == 1 && !centreOfSquare(gid, gx, gz)) {
                continue; // an L-room's inner corner is not part of the room (QUOI lCorners)
            }
            if (!cellRevealed(c)) {
                continue;
            }
            graphics.fill(px(ox, cellPos(gx), ppu), px(oy, cellPos(gz), ppu),
                    px(ox, cellPos(gx) + cellSize(gx), ppu), px(oy, cellPos(gz) + cellSize(gz), ppu), color);
        }
    }

    static boolean centreOfSquare(int gid, int gx, int gz) {
        int g = LiveMapFeature.GRID;
        return gx > 0 && gz > 0 && gx < g - 1 && gz < g - 1
                && LiveMapFeature.groupIdAt(gx - 1 + gz * g) == gid && LiveMapFeature.groupIdAt(gx + 1 + gz * g) == gid
                && LiveMapFeature.groupIdAt(gx + (gz - 1) * g) == gid && LiveMapFeature.groupIdAt(gx + (gz + 1) * g) == gid;
    }

    static void outlineGroup(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int gid, int color,
                             float ox, float oy, float ppu) {
        int g = LiveMapFeature.GRID;
        for (int c : group.cells) {
            int gx = c % g;
            int gz = c / g;
            if (gx % 2 == 1 && gz % 2 == 1 && !centreOfSquare(gid, gx, gz)) {
                continue;
            }
            int x0 = px(ox, cellPos(gx), ppu);
            int y0 = px(oy, cellPos(gz), ppu);
            int x1 = px(ox, cellPos(gx) + cellSize(gx), ppu);
            int y1 = px(oy, cellPos(gz) + cellSize(gz), ppu);
            if (!sameRoom(gid, gx - 1, gz)) graphics.fill(x0 - 1, y0 - 1, x0, y1 + 1, color);
            if (!sameRoom(gid, gx + 1, gz)) graphics.fill(x1, y0 - 1, x1 + 1, y1 + 1, color);
            if (!sameRoom(gid, gx, gz - 1)) graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, color);
            if (!sameRoom(gid, gx, gz + 1)) graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, color);
        }
    }

    private static boolean sameRoom(int gid, int gx, int gz) {
        int g = LiveMapFeature.GRID;
        if (gx < 0 || gz < 0 || gx >= g || gz >= g || LiveMapFeature.groupIdAt(gx + gz * g) != gid) {
            return false;
        }
        if (gx % 2 == 1 && gz % 2 == 1) {
            return centreOfSquare(gid, gx, gz);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- doors

    /** Fill colour a reported-but-unseen cell is dimmed by, when {@link LiveMapConfig#isMarkReportedRooms()}
     *  is on - "subtly", per killer560s-mod-relay task (2026-09-21): still readable as its real colour, just
     *  not mistaken for something this client actually saw. */
    private static final float REPORTED_DIM = 0.78f;
    /** Accent colour for the small reported-cell corner marker - the same amber already used for the
     *  locked-wither-door outline below, so it reads as "the mod's own accent", not a new colour language. */
    private static final int REPORTED_MARK_COLOR = 0xFFFFAA00;

    /** NoammAddons {@code drawRoomConnector}: the stub starts at exactly {@code +roomSize} so it is flush with the
     *  rooms on both sides, is the 4-unit gap long, and 6 units wide across the doorway. */
    static void drawDoors(GuiGraphicsExtractor graphics, DungeonLayout layout, LiveMapConfig cfg,
                          float ox, float oy, float ppu, int hoveredDoor) {
        for (int idx = 0; idx < LiveMapFeature.GRID * LiveMapFeature.GRID; idx++) {
            int type = layout.doorType(idx);
            boolean locked = layout.isLocked(idx);
            if (hideUnrevealed()) {
                // Legit build: the door AND whether it is still locked come from the map item's own byte, never
                // from the world scan or the chunk cache - an opened wither door turns room-coloured on the real
                // map, so that is the only thing we are allowed to know.
                type = switch (DungeonMapScanner.doorTileAt(idx)) {
                    case DOOR_WITHER -> DungeonLayout.DOOR_WITHER;
                    case DOOR_BLOOD -> DungeonLayout.DOOR_BLOOD;
                    case DOOR_ENTRANCE -> DungeonLayout.DOOR_ENTRANCE;
                    case DOOR_NORMAL -> DungeonLayout.DOOR_NORMAL;
                    default -> DungeonLayout.DOOR_NONE;
                };
                locked = type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD;
            }
            if (type == DungeonLayout.DOOR_NONE || !cellRevealed(idx)) {
                continue;
            }
            int gx = idx % LiveMapFeature.GRID;
            int gz = idx / LiveMapFeature.GRID;
            int color = switch (type) {
                case DungeonLayout.DOOR_WITHER -> locked ? cfg.getColorWitherDoor() : connectorColor(idx, cfg);
                case DungeonLayout.DOOR_BLOOD -> locked ? cfg.getColorBlood() : connectorColor(idx, cfg);
                case DungeonLayout.DOOR_ENTRANCE -> cfg.getColorEntrance();
                default -> connectorColor(idx, cfg);
            };
            if (!LiveMapFeature.isWorldScanned(idx) && DungeonMapScanner.isCalibrated()
                    && DungeonMapScanner.stateAt(idx) == DungeonMapScanner.STATE_UNOPENED) {
                color = multiply(color, 1f - cfg.getDarkenUnopened());
            }
            float ux = gx % 2 == 1 ? cellPos(gx) : cellPos(gx) + 5;
            float uz = gz % 2 == 1 ? cellPos(gz) : cellPos(gz) + 5;
            float uw = gx % 2 == 1 ? GAP_UNITS : 6;
            float uh = gz % 2 == 1 ? GAP_UNITS : 6;
            if (idx == hoveredDoor) {
                color = multiply(color, 1.15f);
            }
            int x0 = px(ox, ux, ppu);
            int y0 = px(oy, uz, ppu);
            int x1 = px(ox, ux + uw, ppu);
            int y1 = px(oy, uz + uh, ppu);
            graphics.fill(x0, y0, x1, y1, color);
            if (type == DungeonLayout.DOOR_WITHER && locked) {
                // killer560, 2026-09-20: "wither doors are very hard to see on the map" - the real map's own byte
                // for a locked wither door is near-black (default #101010), which vanishes into the HUD background.
                // A double amber outline (the mod's own accent colour) makes it read as a warning at a glance
                // without touching the fill colour itself, which is still the real map's own and still a picker.
                graphics.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFFFFAA00);
                graphics.outline(x0, y0, x1 - x0, y1 - y0, 0xFFFFAA00);
            }
            if (idx == hoveredDoor) {
                graphics.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xB4FFFFFF);
            }
        }
    }

    /**
     * killer560s-mod-relay task (2026-09-21): teammate-reported doors this client has not scanned itself yet
     * - drawn entirely independently of {@link #drawDoors}/{@link DungeonLayout}, which also feed the
     * teleport pathfinders and Auto Blood Rush ({@code livemap.autoclear}). Unverified network data must
     * never reach automation, only this display path, fed straight from {@link PartyMapIntel}. A cell
     * {@link PartyMapIntel} has already dropped (local scan settled it) simply is not in
     * {@link PartyMapIntel#reportedDoorsView()} any more, so this can never draw over a real local door.
     * <p>
     * The wire format carries only the door TYPE, never a lock state, so this always draws the LOCKED colour
     * for a wither/blood door - the safer default for a door nobody here has actually checked.
     */
    static void drawReportedDoors(GuiGraphicsExtractor graphics, LiveMapConfig cfg, float ox, float oy, float ppu) {
        boolean mark = cfg.isMarkReportedRooms();
        for (PartyMapIntel.ReportedDoor door : PartyMapIntel.reportedDoorsView()) {
            int idx = door.idx();
            int type = door.type();
            int gx = idx % LiveMapFeature.GRID;
            int gz = idx / LiveMapFeature.GRID;
            int color = switch (type) {
                case DungeonLayout.DOOR_WITHER -> cfg.getColorWitherDoor();
                case DungeonLayout.DOOR_BLOOD -> cfg.getColorBlood();
                case DungeonLayout.DOOR_ENTRANCE -> cfg.getColorEntrance();
                default -> cfg.getColorNormal();
            };
            if (mark) {
                color = multiply(color, REPORTED_DIM);
            }
            float ux = gx % 2 == 1 ? cellPos(gx) : cellPos(gx) + 5;
            float uz = gz % 2 == 1 ? cellPos(gz) : cellPos(gz) + 5;
            float uw = gx % 2 == 1 ? GAP_UNITS : 6;
            float uh = gz % 2 == 1 ? GAP_UNITS : 6;
            int x0 = px(ox, ux, ppu);
            int y0 = px(oy, uz, ppu);
            int x1 = px(ox, ux + uw, ppu);
            int y1 = px(oy, uz + uh, ppu);
            graphics.fill(x0, y0, x1, y1, color);
            if (type == DungeonLayout.DOOR_WITHER) {
                graphics.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFFFFAA00);
                graphics.outline(x0, y0, x1 - x0, y1 - y0, 0xFFFFAA00);
            }
            if (mark) {
                int m = Math.max(2, Math.round(3 * ppu));
                graphics.fill(x0, y0, Math.min(x1, x0 + m), Math.min(y1, y0 + m), REPORTED_MARK_COLOR);
            }
        }
    }

    /** A cell that is a door on the current layout (used for hover/click tests). */
    static boolean isDoorCell(int idx) {
        LiveMapFeature.Tile t = LiveMapFeature.effectiveTile(idx);
        return t == LiveMapFeature.Tile.DOOR_NORMAL || t == LiveMapFeature.Tile.DOOR_WITHER
                || t == LiveMapFeature.Tile.DOOR_BLOOD || t == LiveMapFeature.Tile.DOOR_ENTRANCE;
    }

    // ------------------------------------------------------------------------------------------- checkmarks

    // The real map draws bitmap sprites, not font glyphs (killer560's screenshot: fat white/green checks, a red
    // cross, a black '?'). These are the same shapes drawn as run-length filled rows so no texture is needed.
    private static final String[] CHECK = {
            "........##",
            ".......##.",
            "......##..",
            "#....##...",
            "##..##....",
            ".##.##....",
            "..####....",
            "...##.....",
    };
    private static final String[] CROSS = {
            "##....##",
            "###..###",
            ".######.",
            "..####..",
            "..####..",
            ".######.",
            "###..###",
            "##....##",
    };
    private static final String[] QUESTION = {
            ".#####..",
            "##...##.",
            "##...##.",
            "....##..",
            "...##...",
            "...##...",
            "........",
            "...##...",
    };

    static int stateColor(int state) {
        return switch (state) {
            case DungeonMapScanner.STATE_GREEN -> 0xFF55FF55;
            case DungeonMapScanner.STATE_CLEARED -> 0xFFFFFFFF;
            case DungeonMapScanner.STATE_FAILED -> 0xFFFF5555;
            default -> 0xFFAAAAAA;
        };
    }

    /** @return the mark for a room state, or null when the room has none. */
    private static String[] markSprite(int state) {
        return switch (state) {
            case DungeonMapScanner.STATE_CLEARED, DungeonMapScanner.STATE_GREEN -> CHECK;
            case DungeonMapScanner.STATE_FAILED -> CROSS;
            case DungeonMapScanner.STATE_UNOPENED -> QUESTION;
            default -> null;
        };
    }

    static String markGlyph(int state) {
        return switch (state) {
            case DungeonMapScanner.STATE_CLEARED, DungeonMapScanner.STATE_GREEN -> "✔";
            case DungeonMapScanner.STATE_FAILED -> "✖";
            case DungeonMapScanner.STATE_UNOPENED -> "?";
            default -> null;
        };
    }

    private static void drawSprite(GuiGraphicsExtractor graphics, String[] rows, float cx, float cy, float size, int color) {
        int h = rows.length;
        int w = rows[0].length();
        float unit = size / w;
        float x0 = cx - size / 2f;
        float y0 = cy - h * unit / 2f;
        for (int r = 0; r < h; r++) {
            String row = rows[r];
            int c = 0;
            while (c < w) {
                if (row.charAt(c) != '#') {
                    c++;
                    continue;
                }
                int start = c;
                while (c < w && row.charAt(c) == '#') {
                    c++;
                }
                int ry0 = Math.round(y0 + r * unit);
                int ry1 = Math.max(ry0 + 1, Math.round(y0 + (r + 1) * unit));
                int rx0 = Math.round(x0 + start * unit);
                int rx1 = Math.max(rx0 + 1, Math.round(x0 + c * unit));
                graphics.fill(rx0, ry0, rx1, ry1, color);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- labels

    /** One room's text label, in map units so the same cache serves the HUD and the full-screen map. */
    private record Label(String[] lines, int maxWidth, int color, float boxW, float boxH, float cx, float cy) {
    }

    private static final List<Label> LABEL_CACHE = new ArrayList<>();
    private static int labelTick = Integer.MIN_VALUE;
    private static int labelGeneration = Integer.MIN_VALUE;
    private static int labelStyle = -1;

    /** Rebuilt at most once a tick (fps report 2026-09-20: the map rebuilt every room's line list, and measured every
     *  line, on every single frame). Nothing a label depends on - map state, found secrets, room identity - can
     *  change more often than a tick. */
    private static List<Label> labels(int style, Font font) {
        int tick = LiveMapFeature.tickCount();
        int generation = LiveMapFeature.resetGeneration();
        if (tick == labelTick && generation == labelGeneration && style == labelStyle) {
            return LABEL_CACHE;
        }
        labelTick = tick;
        labelGeneration = generation;
        labelStyle = style;
        LABEL_CACHE.clear();
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            RoomEntry entry = group.entry;
            if (entry == null || !identityRevealed(group)) {
                continue; // unidentified (or, on the legit map, still-unopened) rooms only get a checkmark
            }
            String type = roomType(group);
            List<String> lines = new ArrayList<>();
            String secrets = secretsText(group);
            if (style == 2) {
                lines.add(secrets);
            } else {
                // QUOI renderName: no names on Entrance, Fairy or Blood.
                if (type.equals("ENTRANCE") || type.equals("FAIRY") || type.equals("BLOOD")) {
                    continue;
                }
                java.util.Collections.addAll(lines, group.nameLines);
                if (style == 4 && entry.secrets > 0) {
                    lines.add(secrets);
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            int maxWidth = 0;
            for (String s : lines) {
                maxWidth = Math.max(maxWidth, font.width(s));
            }
            float boxW = group.lShape ? 36 : cellPos(group.maxGX) + ROOM_UNITS - cellPos(group.minGX);
            float boxH = group.lShape ? ROOM_UNITS : cellPos(group.maxGZ) + ROOM_UNITS - cellPos(group.minGZ);
            LABEL_CACHE.add(new Label(lines.toArray(new String[0]), maxWidth,
                    stateColor(visibleState(group)), boxW, boxH,
                    group.labelGX * 10 + 8, group.labelGZ * 10 + 8));
        }
        return LABEL_CACHE;
    }

    static String secretsText(LiveMapFeature.RoomGroup group) {
        RoomEntry entry = group.entry;
        if (entry == null || !identityRevealed(group)) {
            return "?";
        }
        if (entry.secrets == 0) {
            return "0";
        }
        int found = LiveMapFeature.foundSecrets(entry.name);
        if (visibleState(group) == DungeonMapScanner.STATE_GREEN) {
            found = entry.secrets;
        } else {
            // killer560s-mod-relay task (2026-09-21): a teammate's own found-count (PartyInteropState, RELAY
            // source) is at least as fresh as ours - it is monotonic within a run, same as our own action-bar
            // parse - so take whichever is higher instead of only ever trusting our own. Never regresses what
            // we already show ourselves.
            com.killer560.hub.interop.PartyInteropState.Fact<com.killer560.hub.interop.PartyInteropState.RoomSecrets> fact =
                    com.killer560.hub.interop.PartyInteropState.roomSecrets(entry.name);
            if (fact != null) {
                found = Math.max(found, fact.value().found());
            }
        }
        return (found < 0 ? 0 : found) + "/" + entry.secrets;
    }

    /** Checkmarks / secrets / room names over the rooms. Style is
     *  {@code LiveMapConfig.ROOM_LABEL_NAMES}: 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Name + Secrets. */
    static void drawLabels(GuiGraphicsExtractor graphics, Font font, int style, LiveMapConfig cfg,
                           float ox, float oy, float ppu) {
        if (style == 0) {
            return;
        }
        // Rooms with no database entry can only ever show a mark, whatever the style is.
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            if (style != 1 && identityRevealed(group) && group.entry != null) {
                continue;
            }
            if (!isRevealed(group)) {
                continue;
            }
            drawMark(graphics, font, visibleState(group), cfg,
                    ox + (group.labelGX * 10 + 8) * ppu, oy + (group.labelGZ * 10 + 8) * ppu, ppu);
        }
        if (style == 1) {
            return;
        }
        for (Label label : labels(style, font)) {
            float want = 0.4f * cfg.getFontScale() * ppu;
            float fit = Math.min(label.boxW * ppu / Math.max(1, label.maxWidth),
                    label.boxH * ppu / (label.lines.length * font.lineHeight));
            float scale = Math.max(0.3f, Math.min(want, fit));
            graphics.pose().pushMatrix();
            graphics.pose().translate(ox + label.cx * ppu, oy + label.cy * ppu);
            graphics.pose().scale(scale, scale);
            int top = Math.round(-label.lines.length * font.lineHeight / 2f);
            for (int i = 0; i < label.lines.length; i++) {
                String s = label.lines[i];
                graphics.text(font, s, -font.width(s) / 2, top + i * font.lineHeight, label.color, cfg.isTextShadow());
            }
            graphics.pose().popMatrix();
        }
    }

    static void drawMark(GuiGraphicsExtractor graphics, Font font, int state, LiveMapConfig cfg,
                         float cx, float cy, float ppu) {
        int color = stateColor(state);
        if (cfg.isCheckmarkSprites()) {
            String[] sprite = markSprite(state);
            if (sprite != null) {
                drawSprite(graphics, sprite, cx, cy, Math.max(6f, 10f * ppu),
                        state == DungeonMapScanner.STATE_UNOPENED ? 0xFF1A1A1A : color);
            }
            return;
        }
        String glyph = markGlyph(state);
        if (glyph == null) {
            return;
        }
        float scale = Math.max(0.3f, 0.75f * cfg.getFontScale() * ppu);
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale, scale);
        graphics.text(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, color, cfg.isTextShadow());
        graphics.pose().popMatrix();
    }

    // ------------------------------------------------------------------------------------------- players

    /** One player marker. {@code markerScale} is a straight multiplier on the icon size so the full-screen map can
     *  keep its fixed 8px icons while the HUD scales them with the map.
     *  @return whether {@code (mouseX, mouseY)} is over this marker. */
    static boolean drawMarker(GuiGraphicsExtractor graphics, Font font, InteractiveMapFeature.MapPlayer mp,
                              LiveMapConfig cfg, float ox, float oy, float ppu, float markerScale,
                              boolean classColours, boolean showName, double mouseX, double mouseY) {
        float x = ox + (float) worldToUnits(mp.worldX()) * ppu;
        float y = oy + (float) worldToUnits(mp.worldZ()) * ppu;
        int size = Math.max(5, Math.round(8 * cfg.getIconScale() * markerScale));
        DungeonClass cls = mp.dungeonClass();

        // killer560, 2026-09-20: "i do not want it showing the white heads for mobs" - player markers are always
        // the arrow now; the skin-head option (and its border swatch) is gone.
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().rotate((float) Math.toRadians(180.0 + mp.yaw()));
        int fillColor = mp.self() ? 0xFF55FF55 : (classColours && cls != null ? cls.color() : 0xFFFFFFFF);
        drawArrow(graphics, Math.round(size * 0.9f), fillColor);
        graphics.pose().popMatrix();

        if (showName) {
            float nameScale = 0.6f * cfg.getIconScale();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y + size / 2f + 2);
            graphics.pose().scale(nameScale, nameScale);
            int nameColor = classColours && cls != null ? cls.color() : 0xFFFFFFFF;
            graphics.text(font, mp.name(), -font.width(mp.name()) / 2, 0, nameColor, true);
            graphics.pose().popMatrix();
        }
        return Math.abs(mouseX - x) <= size / 2f + 1 && Math.abs(mouseY - y) <= size / 2f + 1;
    }

    /** Map marker pointing towards local -y (rotated to the player's heading by the caller).
     *  <p>
     *  killer560, 2026-09-20 (screenshot 14.52.05): "the player arrow should read as an arrow at small scale" - the
     *  old shape narrowed back down over its last two rows to notch the tail, which at an 8px HUD size (the default)
     *  made a rhombus/diamond, not a pointer - exactly what shows up in his screenshot. Width is now strictly
     *  non-decreasing from the tip (row 0, the heading) to a flat back edge, so it is a plain triangle at any size. */
    private static void drawArrow(GuiGraphicsExtractor graphics, int size, int color) {
        int half = Math.max(3, size / 2);
        int rows = half * 2;
        for (int row = 0; row < rows; row++) {
            int w = Math.max(1, Math.round((row + 1) * (half / (float) rows)));
            graphics.fill(-w, -half + row, w, -half + row + 1, 0xFF000000);
            if (w > 1) {
                graphics.fill(-w + 1, -half + row, w - 1, -half + row + 1, color);
            }
        }
    }
}
