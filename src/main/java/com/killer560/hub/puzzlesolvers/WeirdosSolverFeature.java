package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Real Hypixel dungeon "Three Weirdos" puzzle solver, ported from Odin's own {@code WeirdosSolver.kt}.
 * The 3 real NPCs each say one real line of dialogue that either definitively identifies which of their
 * chests has the reward (a fixed real "solution" line - ported verbatim) or definitively rules one out (a
 * fixed real "wrong" line); this listens for the real {@code [NPC] <name>: <message>} chat line, matches
 * it against both real line lists, and highlights the responsible NPC's chest.
 * <p>
 * The chest position is found by reading the NPC entity's own real live world position (no bundled
 * per-room data needed at all, unlike Boulder/Quiz/Ice Fill) and round-tripping it through
 * {@link RoomDatabase#toRelativeCoord}/{@link RoomDatabase#toRealCoord} to apply a real fixed
 * relative-space offset (the chest sits one relative block over from the NPC) - this only depends on this
 * mod's own already-shipped corner/rotation detection (already proven by Secret Waypoints), not on
 * matching any other mod's independently-captured data, so it doesn't carry Boulder/Quiz/Ice Fill's
 * cross-mod corner-convention risk.
 */
public final class WeirdosSolverFeature {

    private static final Pattern NPC_LINE = Pattern.compile("\\[NPC] (.+): (.+)\\.?");

    private static final List<Pattern> SOLUTIONS = List.of(
            Pattern.compile("The reward is not in my chest!"),
            Pattern.compile("At least one of them is lying, and the reward is not in .+'s chest\\.?"),
            Pattern.compile("My chest doesn't have the reward\\. We are all telling the truth\\.?"),
            Pattern.compile("My chest has the reward and I'm telling the truth!"),
            Pattern.compile("The reward isn't in any of our chests\\.?"),
            Pattern.compile("Both of them are telling the truth\\. Also, .+ has the reward in their chest\\.?")
    );

    private static final List<Pattern> WRONG = List.of(
            Pattern.compile("One of us is telling the truth!"),
            Pattern.compile("They are both telling the truth\\. The reward isn't in .+'s chest\\."),
            Pattern.compile("We are all telling the truth!"),
            Pattern.compile(".+ is telling the truth and the reward is in his chest\\."),
            Pattern.compile("My chest doesn't have the reward\\. At least one of the others is telling the truth!"),
            Pattern.compile("One of the others is lying\\."),
            Pattern.compile("They are both telling the truth, the reward is in .+'s chest\\."),
            Pattern.compile("They are both lying, the reward is in my chest!"),
            Pattern.compile("The reward is in my chest\\."),
            Pattern.compile("The reward is not in my chest\\. They are both lying\\."),
            Pattern.compile(".+ is telling the truth\\."),
            Pattern.compile("My chest has the reward\\.")
    );

    private static volatile BlockPos correctPos = null;
    private static final Set<BlockPos> wrongPositions = ConcurrentHashMap.newKeySet();

    private WeirdosSolverFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
                reset();
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WeirdosSolverFeature::onWorldRender);
    }

    private static void onMessage(Component message) {
        if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();

        var matcher = NPC_LINE.matcher(raw);
        if (!matcher.find()) {
            return;
        }
        String npc = matcher.group(1);
        String dialogue = matcher.group(2);

        boolean isSolution = SOLUTIONS.stream().anyMatch(p -> p.matcher(dialogue).matches());
        boolean isWrong = !isSolution && WRONG.stream().anyMatch(p -> p.matcher(dialogue).matches());
        if (!isSolution && !isWrong) {
            return;
        }

        BlockPos chestPos = findChestPos(npc);
        if (chestPos == null) {
            return;
        }
        if (isSolution) {
            correctPos = chestPos;
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f));
        } else {
            wrongPositions.add(chestPos);
        }
    }

    private static BlockPos findChestPos(String npcName) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            return null;
        }
        Entity npc = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand && entity.getName().getString().equals(npcName)) {
                npc = entity;
                break;
            }
        }
        if (npc == null) {
            return null;
        }
        BlockPos npcBlockPos = new BlockPos((int) Math.floor(npc.getX()) - 1, 69, (int) Math.floor(npc.getZ()) - 1);
        RoomEntry.Pos relative = RoomDatabase.toRelativeCoord(
                npcBlockPos, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
        relative.x += 1;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    private static void onWorldRender(LevelRenderContext context) {
        WeirdosSolverConfig cfg = WeirdosSolverConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Three Weirdos".equals(current.name)) {
            return;
        }
        if (correctPos != null) {
            WorldRenderUtils.renderOutlineBox(context, new AABB(correctPos), 0.2f, 1.0f, 0.3f, 1f, 2f);
        }
        if (cfg.isShowWrongChests()) {
            for (BlockPos pos : wrongPositions) {
                WorldRenderUtils.renderOutlineBox(context, new AABB(pos), 1.0f, 0.2f, 0.2f, 1f, 2f);
            }
        }
    }

    private static void reset() {
        correctPos = null;
        wrongPositions.clear();
    }
}
