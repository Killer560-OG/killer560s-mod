package com.killer560.hub.mining.puzzler;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puzzler Solver (legit, both builds; Skyblocker's Puzzler): in the Dwarven Mines, the Puzzler's ten arrows are walked
 * from his start block (181, 195, 135) - up is +z, down -z, left +x, right -x - and the block they end on gets a box (Show
 * Box) and its coordinates in chat (Chat Coordinates). Skyblocker turns that block into crimson planks in the client's
 * world; this only draws, so the world stays what the server says. The box moves when he gives new arrows and goes after
 * five minutes or on a world change. Off by default.
 */
public final class PuzzlerSolver {

    public static final IslandConfig CFG = IslandConfig.of("mining", "puzzler")
            .flag("box", true)
            .flag("chat", true);

    /** Skyblocker's line: exactly ten arrows after the NPC prefix. */
    private static final Pattern ARROWS = Pattern.compile("^\\[NPC] Puzzler: ([▲▶◀▼]{10})$");
    private static final int START_X = 181;
    private static final int START_Z = 135;
    private static final int Y = 195;
    private static final long KEEP_MS = 5 * 60_000L;

    private static volatile BlockPos target = null;
    private static volatile long setAt = 0;

    private PuzzlerSolver() {
    }

    public static void register() {
        ChatObserver.subscribe(PuzzlerSolver::onChat);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> target = null));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Puzzler Solver render", CFG::isEnabledRaw), PuzzlerSolver::onWorldRender));
    }

    /** The block the arrows lead to. Testkit entry point. */
    public static BlockPos solve(String arrows) {
        int x = START_X;
        int z = START_Z;
        for (char c : arrows.toCharArray()) {
            switch (c) {
                case '▲' -> z++;
                case '▼' -> z--;
                case '◀' -> x++;
                case '▶' -> x--;
                default -> {
                }
            }
        }
        return new BlockPos(x, Y, z);
    }

    /** The block boxed now, or null (read by the testkit). */
    public static BlockPos target() {
        BlockPos t = target;
        if (t != null && System.currentTimeMillis() - setAt > KEEP_MS) {
            target = null;
            return null;
        }
        return t;
    }

    /** "Puzzler gave you 5,375 Mithril Powder for solving the puzzle!" */
    private static final java.util.regex.Pattern SOLVED =
            java.util.regex.Pattern.compile("^Puzzler gave you [\\d,]{1,12} Mithril Powder for solving the puzzle!$");

    private static void onChat(Component message) {
        if (!CFG.isEnabled() || !MiningIsland.dwarvenMines()) {
            return;
        }
        String plain = ChatObserver.strip(message);
        if (SOLVED.matcher(plain).matches()) {
            // The reward line means the puzzle is done: drop the box at once instead of waiting out the timeout.
            target = null;
            return;
        }
        if (!plain.startsWith("[NPC] Puzzler: ")) {
            return;
        }
        Matcher m = ARROWS.matcher(plain);
        if (!m.matches()) {
            // His other lines are not known word for word, so none of them ends the puzzle; the timeout does.
            return;
        }
        BlockPos pos = solve(m.group(1));
        target = pos;
        setAt = System.currentTimeMillis();
        if (CFG.on("chat")) {
            ModChat.send("Puzzler", ModChat.text("Mine the block at "),
                    ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        BlockPos t = target();
        if (t == null || !CFG.on("box") || !CFG.isEnabled() || !MiningIsland.dwarvenMines()) {
            return;
        }
        AABB box = new AABB(t).inflate(0.002);
        WorldRenderUtils.renderFilledBox(context, box, 0.2f, 1f, 0.2f, 0.35f);
        WorldRenderUtils.renderOutlineBox(context, box, 0.2f, 1f, 0.2f, 1f, 2f);
    }
}
