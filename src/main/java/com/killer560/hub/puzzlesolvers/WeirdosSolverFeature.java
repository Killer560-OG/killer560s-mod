package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

    // Anchored to line start (2026-09-15, ChatObserver migration): ChatObserver also delivers this mod's own
    // client-side lines, so an unanchored find() could pick an "[NPC] ..." quote out of the middle of one.
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC] (.+): (.+)\\.?");

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

    // [WeirdosSolver] diagnostics - logging only (chat-rate).
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");

    private static volatile BlockPos correctPos = null;
    private static final Set<BlockPos> wrongPositions = ConcurrentHashMap.newKeySet();

    private WeirdosSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(WeirdosSolverFeature::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
            boolean inBoss = LiveMapFeature.isInBoss();
            if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || inBoss) {
                if (correctPos != null || !wrongPositions.isEmpty()) {
                    LOGGER.info("[WeirdosSolver] Reset (enabled={} inDungeon={} inBoss={})",
                            WeirdosSolverConfig.getInstance().isEnabled(), DungeonState.isInDungeon(), inBoss);
                }
                reset();
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WeirdosSolverFeature::onWorldRender);
    }

    private static void onMessage(Component message) {
        if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
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
        RoomEntry room = LiveMapFeature.currentRoomEntry();
        if (room != null && "Three Weirdos".equals(room.name)) {
            LOGGER.info("[WeirdosSolver] NPC line: npc=\"{}\" dialogue=\"{}\" isSolution={} isWrong={}",
                    npc, dialogue, isSolution, isWrong);
        }
        if (!isSolution && !isWrong) {
            return;
        }

        BlockPos chestPos = findChestPos(npc);
        LOGGER.info("[WeirdosSolver] Chest for \"{}\" -> {} (clayRot={})", npc, chestPos,
                java.util.Arrays.toString(LiveMapFeature.currentRoomClayAndRotation()));
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
        // Real bug found and fixed (2026-09-14, code review): npcName comes from the color-stripped chat
        // line, but this compared it against the RAW entity name, which can still carry section-sign
        // color codes - so the NPC was never found and no chest was ever highlighted. Strip both sides.
        String wanted = stripName(npcName);
        Entity npc = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand && stripName(entity.getName().getString()).equals(wanted)) {
                npc = entity;
                break;
            }
        }
        if (npc == null) {
            StringBuilder nearby = new StringBuilder();
            int listed = 0;
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof ArmorStand && listed < 15 && client.player != null
                        && entity.distanceToSqr(client.player) < 32 * 32 && !stripName(entity.getName().getString()).isBlank()) {
                    nearby.append('"').append(stripName(entity.getName().getString())).append("\" ");
                    listed++;
                }
            }
            LOGGER.info("[WeirdosSolver] No ArmorStand named \"{}\" (color-stripped); nearby named stands: [{}]", wanted, nearby.toString().trim());
            return null;
        }
        // killer560, 2026-09-20: "each chest is exactly one block to the left of the NPC, off by -1 on
        // one axis and +1 on the other". Root cause: the "-1, -1" below was applied to the NPC's REAL
        // (world-space) x/z before ever rotating into the room's own relative space, while the "one
        // relative block over" correction a few lines down IS applied post-rotation. Mixing a raw
        // real-space nudge with a rotated relative-space nudge means the two only cancel out for one
        // specific room orientation and combine into a diagonal miss for every other rotation - exactly
        // the "off by -1 on one axis and +1 on the other" he saw. The class doc's own design is "the
        // chest sits one relative block over from the NPC", so the fix is to do the ENTIRE offset in
        // relative space (the existing `relative.x += 1` below) and nothing before it.
        BlockPos npcBlockPos = new BlockPos((int) Math.floor(npc.getX()), 69, (int) Math.floor(npc.getZ()));
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
        // killer560, 2026-09-20: "make it a filled box rather than an outline".
        if (correctPos != null) {
            SolverEspRender.renderFilledBox(context, new AABB(correctPos), 0.2f, 1.0f, 0.3f, 0.5f);
        }
        if (cfg.isShowWrongChests()) {
            for (BlockPos pos : wrongPositions) {
                SolverEspRender.renderFilledBox(context, new AABB(pos), 1.0f, 0.2f, 0.2f, 0.5f);
            }
        }
    }

    private static String stripName(String name) {
        String stripped = ChatFormatting.stripFormatting(name);
        return (stripped != null ? stripped : name).trim();
    }

    public static BlockPos getCorrectChestPos() {
        return correctPos;
    }

    public static int getWrongChestCount() {
        return wrongPositions.size();
    }

    private static void reset() {
        correctPos = null;
        wrongPositions.clear();
    }
}
