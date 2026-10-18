package com.killer560.hub.autoroutes;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The Auto Routes Progress HUD (killer560, 2026-10-15: "add a toggle to show what percent done with a rooms auto routes you
 * are as a hud. If using auto clear have a list of the rooms it is going to do in order with a total percent at the top and
 * a configurable amount of rooms it shows it is about to do").
 * <p>
 * Two shapes. With no planner running it is one line: the room you are in, how far its route got this run, and the node
 * count. While Auto Clear, Auto Secret or Dungeon Autopilot runs it is the plan: the planner's name and the whole run's
 * percent at the top, then the room it is on (with that room's route percent once its route has started), then the next
 * {@link AutoRoutesConfig#getProgressHudRooms()} rooms in the order the planner chose them, and a "+N more" line for the
 * rest. The queue is {@link RunPlan}, which each planner fills from its own ordering at the moment it picks.
 */
public final class AutoRoutesProgressHud {

    /** One drawn row: its text and 0xRRGGBB colour. */
    record Line(String text, int rgb) {
    }

    private static final int PITCH = 10;

    private AutoRoutesProgressHud() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        HudElementRegistry.register(ELEMENT);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "autoroutes_progress"), (graphics, deltaTracker) -> draw(graphics));
    }

    // ------------------------------------------------------------------------------------------- content

    /** "40%" for {@code done} of {@code total} nodes (whole percent, rounded); "0%" for a route with none done. */
    static int percent(int done, int total) {
        return total <= 0 ? 0 : Math.max(0, Math.min(100, Math.round(done * 100f / total)));
    }

    /**
     * The rows for a plan: the header, the room under way, up to {@code upcoming} rooms after it, then "+N more".
     * {@code progress} gives {nodes done, nodes in route} for a room, or null when its route has not started.
     */
    static List<Line> planLines(RunPlan.Snapshot plan, int upcoming, Function<String, int[]> progress) {
        List<Line> out = new ArrayList<>();
        // The planner's estimate of the time the rest takes, counting down (killer560, 2026-10-18); none without one.
        double left = plan.secondsLeft(System.currentTimeMillis());
        out.add(new Line(plan.source().label() + " " + plan.percent() + "%  (" + plan.done() + "/" + plan.total() + ")"
                + (Double.isNaN(left) || plan.rooms().isEmpty() ? "" : "  ~" + RunPlan.clock(left) + " left"),
                ModChat.LIGHT_ORANGE));
        List<String> rooms = plan.rooms();
        for (int i = 0; i < rooms.size() && i <= upcoming; i++) {
            String name = rooms.get(i);
            String tag = plan.tags().get(i);
            String text = (i == 0 ? "> " : "  ") + (tag.isEmpty() ? "" : tag + " ") + name;
            if (i == 0) {
                int[] p = progress.apply(name);
                if (p != null) {
                    text += "  " + percent(p[0], p[1]) + "%";
                }
            }
            out.add(new Line(text, i == 0 ? ModChat.GOOD : ModChat.TEXT));
        }
        int hidden = rooms.size() - 1 - upcoming;
        if (hidden > 0) {
            out.add(new Line("+" + hidden + " more", ModChat.DIM));
        }
        return out;
    }

    /** The single line for a room's route: "Auto Routes: Mushroom 43%  (3/7 nodes)". */
    static List<Line> routeLines(String room, int[] progress) {
        int done = progress == null ? 0 : progress[0];
        int total = progress == null ? 0 : progress[1];
        String text = "Auto Routes: " + room + " " + percent(done, total) + "%"
                + (total > 0 ? "  (" + done + "/" + total + " nodes)" : "");
        return List.of(new Line(text, ModChat.LIGHT_ORANGE));
    }

    /** The room whose route the one-line shape shows: the one playing, else the one he stands in. Null when neither has a route. */
    static String currentRouteRoom() {
        String running = RouteExecutor.runningRoom();
        if (running != null) {
            return running;
        }
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        if (frame == null) {
            return null;
        }
        return RouteStore.getInstance().forRoom(frame.roomName()) != null ? frame.roomName() : null;
    }

    /** What the HUD shows right now (for the testkit, the box and the draw): empty when there is nothing to show. */
    static List<Line> lines() {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        RunPlan.Snapshot plan = RunPlan.active();
        if (plan != null) {
            return planLines(plan, cfg.getProgressHudRooms(), RouteRunMemory::progressOf);
        }
        String room = currentRouteRoom();
        if (room != null) {
            return routeLines(room, RouteRunMemory.progressOf(room));
        }
        if (HudVisibility.menuOpen()) {
            return planLines(new RunPlan.Snapshot(RunPlan.Source.AUTO_CLEAR,
                    List.of("Mushroom", "Zombie Room", "Spider Room", "Lava Ravine", "Tomb", "Crypt", "Withermancer"),
                    List.of("", "", "", "", "", "", ""), 3), cfg.getProgressHudRooms(), r -> new int[]{3, 7});
        }
        return List.of();
    }

    /** The row texts as drawn right now, for the testkit. */
    public static List<String> shownText() {
        List<String> out = new ArrayList<>();
        for (Line l : lines()) {
            out.add(l.text());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- element

    static final HudElement ELEMENT = new HudElement() {
        @Override
        public String id() {
            return "autoroutes_progress";
        }

        @Override
        public String displayName() {
            return "Auto Routes Progress";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 190;
        }

        @Override
        public int width() {
            List<Line> l = shown();
            if (l.isEmpty()) {
                return 20;
            }
            String[] texts = new String[l.size()];
            for (int i = 0; i < texts.length; i++) {
                texts[i] = l.get(i).text();
            }
            return HudText.width(texts);
        }

        @Override
        public int height() {
            return HudText.height(Math.max(1, shown().size()), PITCH);
        }

        @Override
        public boolean isEnabledInSettings() {
            return BuildVariant.CHEAT_FEATURES_ENABLED && AutoRoutesConfig.getInstance().isEnabledRaw()
                    && AutoRoutesConfig.getInstance().isProgressHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            List<Line> l = shown();
            if (l.isEmpty()) {
                return;
            }
            var font = Minecraft.getInstance().font;
            HudSeen.markDrawn(id());
            graphics.fill(x - 1, y - 1, x + width() + 1, y + height() + 1, 0x66000000);
            for (int i = 0; i < l.size(); i++) {
                graphics.text(font, l.get(i).text(), x, y + i * PITCH, 0xFF000000 | l.get(i).rgb(), true);
            }
        }
    };

    private static void draw(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
            if (client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen() || !cfg.isEnabled()
                    || !cfg.isProgressHud()) {
                return;
            }
            // Built once per frame (fps-auto-1015): render() and the box's width() and height() each rebuilt them,
            // a room lookup and string building three times a frame; with nothing to show nothing is laid out.
            List<Line> l = lines();
            if (l.isEmpty()) {
                return;
            }
            frameLines = l;
            try {
                HudElementRegistry.drawAt(graphics, ELEMENT);
            } finally {
                frameLines = null;
            }
        } catch (RuntimeException e) {
            // never take the HUD frame down over a status line
        }
    }

    /** This frame's rows while {@link #draw} runs, else null (the editor and the testkit ask {@link #lines()}). */
    private static List<Line> frameLines;

    private static List<Line> shown() {
        List<Line> l = frameLines;
        return l != null ? l : lines();
    }
}
