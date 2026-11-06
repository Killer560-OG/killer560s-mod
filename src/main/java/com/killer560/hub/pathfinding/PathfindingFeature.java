package com.killer560.hub.pathfinding;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * Pathfinding: "show me the fastest way there" navigation on Hypixel Skyblock islands, built on SkyHanni's public
 * island graph data (see {@link IslandGraph} and {@link GraphRepository} for the data source and license).
 * <p>
 * Registers the tick/render hooks and the HUD element. Everything is off by default.
 */
public final class PathfindingFeature {

    public static final String ELEMENT_ID = "pathfinding";

    private static volatile boolean startKeyPressed;
    private static boolean resumeMouseWasDown;

    private PathfindingFeature() {
    }

    public static void register() {
        com.killer560.hub.macrostop.MacroStop.register("Etherwarp Hopper", com.killer560.hub.macrostop.MacroStop.Kind.EMERGENCY, EtherwarpHopper::isBusy,
                why -> EtherwarpHopper.cancel());
        com.killer560.hub.macrostop.MacroStop.register("Ender Pearl Hopper", com.killer560.hub.macrostop.MacroStop.Kind.EMERGENCY, EnderPearlHopper::isBusy,
                why -> EnderPearlHopper.cancel());
        PathfindingConfig.getInstance();
        FairySoulStore.load();
        ProfileTracker.register();
        FairySoulsFeature.register();
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoWalker.register(); // Hypixel's death line
        }
        // START: this drives AutoSoulRunner and EnderPearlHopper, which both send interactions. See
        // ActionGate's class doc.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("PathfindingFeature.tick", PathfindingFeature::tick));
        LevelRenderEvents.COLLECT_SUBMITS.register(com.killer560.hub.util.FeatureGuard.collectFeatures(
                com.killer560.hub.util.FeatureBits.register("Pathfinding render", () -> PathfindingConfig.getInstance().isEnabledRaw()), PathWorldRenderer::render));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Auto Fairy Souls' camera turns every render frame (WalkCamera), not in 20 Hz tick steps.
            LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> AutoWalker.frame());
        }
        com.killer560.hub.hud.HudElementRegistry.register(HudElementImpl.INSTANCE);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "pathfinding_hud"),
                (graphics, deltaTracker) -> drawHud(graphics));
    }


    private static Object lastLevel;

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            ProfileTracker.onWorldChange();
            if (AutoSoulRunner.isActive() && client.level != null && AutoSoulRunner.isWarping()) {
                // Its own warp back to the island's spawn (Warp When Stuck): the runner walks on once it lands.
            } else if (AutoSoulRunner.isActive() && client.level != null && AutoWalker.inDeathGrace()) {
                // A respawn that rebuilt the level is the death's, not a trip to another world: keep going.
                NavigationManager.recalculate(true);
            } else if (AutoSoulRunner.isActive()) {
                // Failsafe (killer560, 2026-10-11): sent to the lobby or limbo, warped, or disconnected - the alarm
                // plays and the chat line says so (it lands once the new world has a player).
                com.killer560.hub.util.ModSounds.playCorrectionAlarm();
                AutoSoulRunner.stop(client.level == null ? "failsafe: disconnected"
                        : "failsafe: the server moved you to another world (lobby, limbo or a warp)", true);
            }
        }
        IslandDetector.tick(client);
        ProfileTracker.tick(client);
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Ahead of the Fairy Souls gate: Auto Fairy Souls starts and runs with that switch off (killer560, 2026-10-17).
            pollResumeKey(client, cfg);
        }
        if (!cfg.isEnabled() && !FairySoulsFeature.autoRunning()) return;
        String island = IslandDetector.graphIsland();
        if (island != null) {
            GraphRepository.ensureLoading(island, false);
        }
        FairySoulMenu.tick(client);
        FairySoulsFeature.tick(client);
        NavigationManager.tick(client);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoSoulRunner.tick(client);
            AutoWalker.tick(client);
            EnderPearlHopper.tick(client);
        }
    }

    /** A press of the Start/Stop key with no screen open (from {@code PathStopKeyMixin}, between ticks). */
    static void onStartKey() { startKeyPressed = true; }

    /** The Start/Stop key's press, taken at the top of the tick: a fresh press toggles, a held key does nothing more
     *  (the same press event the "any key stops it" rule reads, so the two can never disagree about one press). */
    private static void pollResumeKey(Minecraft client, PathfindingConfig cfg) {
        // A mouse-button Start/Stop key (2026-11-02) never reaches PathStopKeyMixin (a keyboard hook): poll its edge.
        int code = cfg.getResumeKeyCode();
        if (KeyUtil.isMouseCode(code)) {
            boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
            if (down && !resumeMouseWasDown && McCompat.screen(client) == null) {
                startKeyPressed = true;
            }
            resumeMouseWasDown = down;
        }
        if (!startKeyPressed) return;
        startKeyPressed = false;
        if (cfg.getResumeKeyCode() == KeyUtil.NONE || McCompat.screen(client) != null) return;
        if (AutoSoulRunner.isActive()) {
            AutoSoulRunner.stop("the Start/Stop key", true);
        } else {
            AutoSoulRunner.start();
        }
    }


    private static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this (killer560), the HUD editor still does.
        if (client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen()
                || !HudElementImpl.INSTANCE.isEnabledInSettings()) {
            // Off: its render draws nothing, so do not resolve its position and pose every frame either (95-perf, 2026-10-15).
            return;
        }
        com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HudElementImpl.INSTANCE);
    }

    // The HUD's fairy soul line. It was rebuilt every frame on any island with a graph (the HUD is on by default with
    // Pathfinding): two whole-graph tag scans, a synchronized found-log walk per soul and two profile keys (fps pass
    // 2026-10-19). Its inputs are the island, its graph, the profile and the found log/menu counts, whose every change
    // is followed by FairySoulStore.save() and so bumps its version.
    private static String soulLineIsland;
    private static IslandGraph soulLineGraph;
    private static String soulLineProfile;
    private static int soulLineVersion = -1;
    private static String soulLine;

    private static String soulLine() {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = GraphRepository.get(island);
        String profile = ProfileTracker.key();
        int version = FairySoulStore.version();
        if (graph == soulLineGraph && version == soulLineVersion && java.util.Objects.equals(island, soulLineIsland)
                && profile.equals(soulLineProfile)) {
            return soulLine;
        }
        String line = null;
        int[] counts = FairySoulsFeature.islandCounts();
        if (counts != null) {
            FairySoulStore.IslandRecord record = FairySoulsFeature.menuRecord();
            String suffix = record != null && !record.menuComplete() && record.menuFound > 0
                    ? " §7(menu " + record.menuFound + "/" + record.menuTotal + ")" : "";
            line = "§6Fairy Souls §f" + counts[0] + "/" + counts[1] + suffix;
        }
        soulLine = line;
        soulLineIsland = island;
        soulLineGraph = graph;
        soulLineProfile = profile;
        soulLineVersion = version;
        return line;
    }

    /** Lines the HUD shows: fairy soul progress on this island, and the current navigation target. */
    static List<String> hudLines() {
        List<String> lines = new ArrayList<>(2);
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (cfg.isFairySouls()) {
            String soulLine = soulLine();
            if (soulLine != null) {
                lines.add(soulLine);
            }
        }
        if (NavigationManager.isActive()) {
            lines.add(String.format(Locale.US, "§6%s §f%.0fm", NavigationManager.target().label,
                    NavigationManager.remainingDistance()));
        }
        return lines;
    }

    /** Draggable/scalable in the HUD editor like every other element in this mod. */
    public static final class HudElementImpl implements HudElement {

        public static final HudElementImpl INSTANCE = new HudElementImpl();

        private HudElementImpl() {
        }

        @Override
        public String id() { return ELEMENT_ID; }

        @Override
        public String displayName() { return "Pathfinding"; }

        @Override
        public int defaultX() { return 10; }

        @Override
        public int defaultY() {
            // 120 is already taken by posmsg and the Spring Boots alert; 180 is the first free row in this column.
            return 180;
        }

        @Override
        public int width() {
            // The drawn lines (2026-10-07 box audit: it had a 90 floor and 2 spare units).
            return com.killer560.hub.hud.HudText.width(drawnLines());
        }

        @Override
        public int height() { return com.killer560.hub.hud.HudText.height(drawnLines().size(), 10); }

        /** What render() draws: the HUD lines, or the editor's sample when there are none. */
        private static List<String> drawnLines() {
            List<String> lines = hudLines();
            if (lines.isEmpty() && McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen) {
                return List.of("§6Fairy Souls §f0/80");
            }
            return lines;
        }

        @Override
        public boolean isEnabledInSettings() {
            return PathfindingConfig.getInstance().isEnabled() && PathfindingConfig.getInstance().isSoulHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            PathfindingConfig cfg = PathfindingConfig.getInstance();
            boolean editor = McCompat.screen(client) instanceof HudEditorScreen;
            if (!(cfg.isEnabled() && cfg.isSoulHud()) && !editor) return;
            List<String> lines = drawnLines();
            if (!lines.isEmpty()) {
                HudSeen.markDrawn(id());
            }
            int row = y;
            for (String line : lines) {
                graphics.text(client.font, line, x, row, 0xFFFFFFFF, true);
                row += 10;
            }
        }
    }
}
