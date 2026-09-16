package com.killer560.hub.pathfinding;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pathfinding: "show me the fastest way there" navigation on Hypixel Skyblock islands, built on SkyHanni's public
 * island graph data (see {@link IslandGraph} and {@link GraphRepository} for the data source and licence).
 * <p>
 * Registers the tick/render hooks, the {@code /k560path} command and the HUD element. Everything is off by default.
 */
public final class PathfindingFeature {

    public static final String ELEMENT_ID = "pathfinding";
    private static final String CHAT = "Pathfinding";

    private static boolean resumeKeyWasDown;

    private PathfindingFeature() {
    }

    public static void register() {
        PathfindingConfig.getInstance();
        FairySoulStore.load();
        ProfileTracker.register();
        FairySoulsFeature.register();
        ClientTickEvents.END_CLIENT_TICK.register(PathfindingFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(PathWorldRenderer::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(command()));
        com.killer560.hub.hud.HudElementRegistry.register(HudElementImpl.INSTANCE);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "pathfinding_hud"),
                (graphics, deltaTracker) -> drawHud(graphics));
    }

    // ------------------------------------------------------------------ ticking

    private static Object lastLevel;

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            ProfileTracker.onWorldChange();
            AutoSoulRunner.stop("world change", false);
        }
        IslandDetector.tick(client);
        ProfileTracker.tick(client);
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isEnabled()) {
            if (AutoSoulRunner.isActive()) {
                AutoSoulRunner.stop("pathfinding turned off", false);
            }
            return;
        }
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
            pollResumeKey(client, cfg);
        }
    }

    private static void pollResumeKey(Minecraft client, PathfindingConfig cfg) {
        int code = cfg.getResumeKeyCode();
        if (code == KeyUtil.NONE) {
            resumeKeyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !resumeKeyWasDown && client.screen == null) {
            if (AutoSoulRunner.isActive()) {
                AutoSoulRunner.stop("resume key", true);
            } else {
                AutoSoulRunner.start();
            }
        }
        resumeKeyWasDown = down;
    }

    // ------------------------------------------------------------------ command

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        SuggestionProvider<FabricClientCommandSource> destinations = (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            String island = IslandDetector.graphIsland();
            IslandGraph graph = island == null ? null : GraphRepository.get(island);
            if (graph != null) {
                for (String name : graph.destinationNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name.contains(" ") ? "\"" + name + "\"" : name);
                    }
                }
            }
            return builder.buildFuture();
        };
        return ClientCommands.literal("k560path")
                .executes(ctx -> {
                    usage();
                    return 1;
                })
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    AutoSoulRunner.stop("command", false);
                    FairySoulsFeature.stopGuide(false);
                    NavigationManager.stop("command", true);
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(ctx -> {
                    String island = IslandDetector.graphIsland();
                    if (island == null) {
                        ModChat.send(CHAT, ModChat.bad("Unknown island."));
                        return 0;
                    }
                    GraphRepository.forceReload(island);
                    ModChat.send(CHAT, ModChat.text("Re-downloading the "), ModChat.value(island), ModChat.text(" graph..."));
                    return 1;
                }))
                .then(ClientCommands.literal("info").executes(ctx -> {
                    info();
                    return 1;
                }))
                .then(ClientCommands.literal("souls")
                        .executes(ctx -> {
                            FairySoulsFeature.start(PathfindingConfig.getInstance().getSoulMode());
                            return 1;
                        })
                        .then(ClientCommands.literal("nearest").executes(ctx -> {
                            FairySoulsFeature.guideNearest();
                            return 1;
                        }))
                        .then(ClientCommands.literal("route").executes(ctx -> {
                            FairySoulsFeature.routeIsland();
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            AutoSoulRunner.stop("command", false);
                            FairySoulsFeature.stopGuide(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("auto").executes(ctx -> {
                            if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                                ModChat.send(CHAT, ModChat.bad("Auto walking only exists in the cheat build."));
                                return 0;
                            }
                            AutoSoulRunner.start();
                            return 1;
                        }))
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            FairySoulsFeature.resetProfile();
                            return 1;
                        }))
                        .then(ClientCommands.literal("resetisland").executes(ctx -> {
                            FairySoulsFeature.resetIsland();
                            return 1;
                        }))
                        .then(ClientCommands.literal("foundall").executes(ctx -> {
                            FairySoulsFeature.markIslandFound();
                            return 1;
                        })))
                .then(ClientCommands.argument("destination", StringArgumentType.greedyString())
                        .suggests(destinations)
                        .executes(ctx -> navigateCommand(StringArgumentType.getString(ctx, "destination"))));
    }

    private static int navigateCommand(String input) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isEnabled()) {
            ModChat.send(CHAT, ModChat.bad("Pathfinding is off "),
                    ModChat.dim("(/killer560 > New > Pathfinding)"));
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (player == null) {
            return 0;
        }
        String text = input.trim().replace("\"", "");
        String[] parts = text.split("\\s+");
        if (parts.length == 3) {
            try {
                double x = Double.parseDouble(parts[0]);
                double y = Double.parseDouble(parts[1]);
                double z = Double.parseDouble(parts[2]);
                NavigationManager.navigate(String.format(Locale.US, "%.0f, %.0f, %.0f", x, y, z),
                        new Vec3(x + 0.5, y, z + 0.5), -1, false, null);
                return 1;
            } catch (NumberFormatException ignored) {
                // not coordinates - fall through to a name lookup
            }
        }
        if (graph == null) {
            GraphRepository.ensureLoading(island, false);
            ModChat.send(CHAT, ModChat.bad(island == null ? "No navigation graph for this island."
                    : "Still downloading the " + island + " graph - try again in a moment."));
            return 0;
        }
        List<IslandGraph.Node> matches = graph.named(text);
        if (matches.isEmpty()) {
            for (IslandGraph.Node node : graph.nodes) {
                if (node.cleanName != null && node.cleanName.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
                    matches.add(node);
                }
            }
        }
        if (matches.isEmpty()) {
            ModChat.send(CHAT, ModChat.bad("Nothing called "), ModChat.value(text), ModChat.bad(" on this island."));
            return 0;
        }
        Vec3 pos = player.position();
        IslandGraph.Node start = graph.nearest(pos.x, pos.y + 1.0, pos.z);
        GraphPathfinder.Tree tree = GraphPathfinder.dijkstra(graph, start.index, -1);
        IslandGraph.Node best = matches.get(0);
        double bestCost = Double.MAX_VALUE;
        for (IslandGraph.Node node : matches) {
            double cost = tree.dist()[node.index];
            if (cost < bestCost) {
                bestCost = cost;
                best = node;
            }
        }
        NavigationManager.navigate(best.cleanName == null ? text : best.cleanName,
                NavigationManager.centre(best), best.index, false, null);
        return 1;
    }

    private static void usage() {
        ModChat.send(CHAT, ModChat.text("/k560path <place | x y z>"), ModChat.dim(" - navigate"));
        ModChat.send(CHAT, ModChat.text("/k560path souls [nearest|route|stop|auto|reset|resetisland|foundall]"));
        ModChat.send(CHAT, ModChat.text("/k560path stop | reload | info"));
    }

    private static void info() {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        ModChat.send(CHAT, ModChat.text("Island: "), ModChat.value(IslandDetector.islandName().isEmpty()
                        ? "unknown" : IslandDetector.islandName()),
                ModChat.dim(" (" + (island == null ? "no graph" : island) + ")"));
        ModChat.send(CHAT, ModChat.text("Graph: "), graph == null
                ? ModChat.bad("not loaded" + (island != null && GraphRepository.lastError(island) != null
                        ? " - " + GraphRepository.lastError(island) : ""))
                : ModChat.value(graph.nodes.length + " nodes, " + graph.withTag(IslandGraph.TAG_FAIRY_SOUL).size()
                        + " fairy souls"));
        int[] counts = FairySoulsFeature.islandCounts();
        if (counts != null) {
            FairySoulStore.IslandRecord record = FairySoulsFeature.menuRecord();
            ModChat.send(CHAT, ModChat.text("Souls found: "), ModChat.value(counts[0] + "/" + counts[1]),
                    ModChat.dim(" profile " + ProfileTracker.displayName()
                            + (record == null ? "" : ", Hypixel menu said " + record.menuFound + "/" + record.menuTotal)));
        }
    }

    // ------------------------------------------------------------------ HUD

    private static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this (killer560), the HUD editor still does.
        if (client.player == null || client.options.hideGui || HudVisibility.menuOpen()) {
            return;
        }
        int[] pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(HudElementImpl.INSTANCE);
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(HudElementImpl.INSTANCE);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            HudElementImpl.INSTANCE.render(graphics, 0, 0);
        } catch (RuntimeException ignored) {
            // one broken element never takes down the HUD frame
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** Lines the HUD shows: fairy soul progress on this island, and the current navigation target. */
    static List<String> hudLines() {
        List<String> lines = new ArrayList<>(2);
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (cfg.isFairySouls() && cfg.isSoulHud()) {
            int[] counts = FairySoulsFeature.islandCounts();
            if (counts != null) {
                FairySoulStore.IslandRecord record = FairySoulsFeature.menuRecord();
                String suffix = record != null && !record.menuComplete() && record.menuFound > 0
                        ? " §7(menu " + record.menuFound + "/" + record.menuTotal + ")" : "";
                lines.add("§6Fairy Souls §f" + counts[0] + "/" + counts[1] + suffix);
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
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Pathfinding";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // 120 is already taken by posmsg and the Spring Boots alert; 180 is the first free row in this column.
            return 180;
        }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            int width = 90;
            if (font != null) {
                for (String line : hudLines()) {
                    width = Math.max(width, font.width(line) + 2);
                }
            }
            return width;
        }

        @Override
        public int height() {
            return Math.max(10, hudLines().size() * 10);
        }

        @Override
        public boolean isRelevantNow() {
            return PathfindingConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            PathfindingConfig cfg = PathfindingConfig.getInstance();
            boolean editor = client.screen instanceof HudEditorScreen;
            if (!cfg.isEnabled() && !editor) {
                return;
            }
            List<String> lines = hudLines();
            if (lines.isEmpty() && editor) {
                lines = List.of("§6Fairy Souls §f0/80");
            }
            int row = y;
            for (String line : lines) {
                graphics.text(client.font, line, x, row, 0xFFFFFFFF, true);
                row += 10;
            }
        }
    }
}
