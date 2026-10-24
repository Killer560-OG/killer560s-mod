package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.social.PlayerHeadRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The dungeon map as a leap menu (killer560, 2026-10-11: "have an option where during clear it shows the map with their
 * head and i click on their player head to leap to them"). Draws the same rooms, doors and labels the Interactive Map
 * does ({@link MapPainter}, the same fit to the floor), then each leapable teammate as a big upright face at their map
 * position, and hands back where those faces are so the leap menu can turn a click on one into the real container click.
 * Only the faces are targets; the rooms are just context.
 */
public final class LeapMapView {

    /** A clickable face: the teammate's name and its box on screen. */
    public record Head(String name, int x0, int y0, int x1, int y1) {
        public boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private LeapMapView() {
    }

    /**
     * Draws the map into the square {@code p} = {x0, y0, x1, y1} and every teammate in {@code targets} (names, case
     * ignored) that the map has a position for. @return their faces, topmost last (so a hit test walks it backward).
     */
    public static List<Head> draw(GuiGraphicsExtractor graphics, Font font, int[] p, Collection<String> targets,
                                  int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        float fit = (p[2] - p[0] - 12) / (float) MapPainter.MAP_UNITS;
        float ppu = fit * MapPainter.floorFit(groups)[0];
        int[] e = MapPainter.gridExtent(groups);
        float ox = (p[0] + p[2]) / 2f - (e[0] + e[2] / 2f) * ppu;
        float oy = (p[1] + p[3]) / 2f - (e[1] + e[3] / 2f) * ppu;

        graphics.fill(p[0], p[1], p[2], p[3], 0xD00D0D0D);
        graphics.outline(p[0] - 1, p[1] - 1, p[2] - p[0] + 2, p[3] - p[1] + 2, 0xFFCC6600);
        List<Head> heads = new ArrayList<>();
        graphics.enableScissor(p[0], p[1], p[2], p[3]);
        try {
            MapPainter.drawDoors(graphics, DungeonLayout.current(), cfg, ox, oy, ppu, -1);
            for (int gid = 0; gid < groups.size(); gid++) {
                LiveMapFeature.RoomGroup group = groups.get(gid);
                if (!MapPainter.isRevealed(group)) {
                    continue; // the same map-item-only rule as the Dungeon Map: it drew every scanned room gray
                }
                MapPainter.drawRoom(graphics, group, gid, MapPainter.roomColor(group, cfg), ox, oy, ppu);
            }
            MapPainter.drawLabels(graphics, font, cfg.getRoomLabels(), cfg, ox, oy, ppu);
            int size = Math.max(14, Math.round(ppu * 9));
            for (InteractiveMapFeature.MapPlayer mp : InteractiveMapFeature.playersCached(client)) {
                if (mp.self() || !containsIgnoreCase(targets, mp.name())) {
                    continue;
                }
                int cx = Math.round(ox + (float) MapPainter.worldToUnits(mp.worldX()) * ppu);
                int cy = Math.round(oy + (float) MapPainter.worldToUnits(mp.worldZ()) * ppu);
                heads.add(new Head(mp.name(), cx - size / 2, cy - size / 2, cx - size / 2 + size, cy - size / 2 + size));
            }
            for (Head h : heads) {
                boolean hover = h.contains(mouseX, mouseY);
                InteractiveMapFeature.MapPlayer mp = find(client, h.name());
                DungeonClass cls = mp == null ? null : mp.dungeonClass();
                int edge = hover ? 0xFFFFFFFF : cls != null ? cls.color() : 0xFFCC6600;
                int w = h.x1() - h.x0();
                graphics.fill(h.x0() - 2, h.y0() - 2, h.x1() + 2, h.y1() + 2, edge);
                drawFace(graphics, h.name(), h.x0(), h.y0(), w);
                float nameScale = Math.max(0.6f, Math.min(1f, w / 20f));
                graphics.pose().pushMatrix();
                try {
                    graphics.pose().translate((h.x0() + h.x1()) / 2f, h.y1() + 3);
                    graphics.pose().scale(nameScale, nameScale);
                    graphics.text(font, h.name(), -font.width(h.name()) / 2, 0, edge, true);
                } finally {
                    graphics.pose().popMatrix();
                }
            }
        } finally {
            graphics.disableScissor();
        }
        return heads;
    }

    private static InteractiveMapFeature.MapPlayer find(Minecraft client, String name) {
        for (InteractiveMapFeature.MapPlayer mp : InteractiveMapFeature.playersCached(client)) {
            if (mp.name().equalsIgnoreCase(name)) {
                return mp;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(Collection<String> names, String name) {
        String n = name.toLowerCase(Locale.US);
        for (String s : names) {
            if (s.toLowerCase(Locale.US).equals(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code size}x{@code size} upright face (face + hat layer) for a teammate: the skin the server sent for them
     * ({@link MapHeads} - their own tab entry, or Hypixel's dungeon tab entry), else {@link PlayerHeadRenderer}'s lookup
     * by name. Shared by the map view and the leap menu boxes.
     */
    public static void drawFace(GuiGraphicsExtractor graphics, String name, int x, int y, int size) {
        PlayerSkin skin = MapHeads.skinFor(new InteractiveMapFeature.MapPlayer(name, 0, 0, 0, false, null));
        if (skin == null) {
            PlayerHeadRenderer.draw(graphics, null, name, x, y, size);
            return;
        }
        Identifier texture = skin.body().texturePath();
        float scale = size / 8.0f;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y);
            graphics.pose().scale(scale, scale);
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
