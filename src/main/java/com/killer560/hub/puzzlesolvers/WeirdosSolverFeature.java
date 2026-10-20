package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
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
    // Name group non-greedy and bounded, so a colon inside the dialogue cannot become part of the name.
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC] ([^:]{1,32}?): (.{1,200})$");

    // Punctuation is NOT fixed: Hypixel ends the same sentence with "." in one run and "!" in another, and joins two
    // clauses with ". " or ", ". killer560, 2026-10-19: Hope said "They are both telling the truth, the reward is in
    // Marshall's chest!" while this list wanted a "." there, so her chest was never marked red and Auto Three Weirdos
    // waited for a second wrong chest that never came. So every sentence end is "[.!]?" and every clause join "[.,!]?".
    // A SOLUTION is matched before a WRONG, so the looser ends cannot turn a solution line into a wrong one.
    private static final List<Pattern> SOLUTIONS = List.of(
            Pattern.compile("The reward is not in my chest[.!]?"),
            Pattern.compile("At least one of them is lying[.,!]? and the reward is not in .+'s chest[.!]?"),
            Pattern.compile("My chest doesn't have the reward[.,!]? We are all telling the truth[.!]?"),
            Pattern.compile("My chest has the reward and I'm telling the truth[.!]?"),
            Pattern.compile("The reward isn't in any of our chests[.!]?"),
            Pattern.compile("Both of them are telling the truth[.,!]? Also, .+ has the reward in their chest[.!]?")
    );

    private static final List<Pattern> WRONG = List.of(
            Pattern.compile("One of us is telling the truth[.!]?"),
            Pattern.compile("They are both telling the truth[.,!]? The reward isn't in .+'s chest[.!]?"),
            Pattern.compile("We are all telling the truth[.!]?"),
            Pattern.compile(".+ is telling the truth and the reward is in his chest[.!]?"),
            Pattern.compile("My chest doesn't have the reward[.,!]? At least one of the others is telling the truth[.!]?"),
            Pattern.compile("One of the others is lying[.!]?"),
            Pattern.compile("They are both telling the truth[.,!]? the reward is in .+'s chest[.!]?"),
            Pattern.compile("They are both lying[.,!]? the reward is in my chest[.!]?"),
            Pattern.compile("The reward is in my chest[.!]?"),
            Pattern.compile("The reward is not in my chest[.,!]? They are both lying[.!]?"),
            Pattern.compile(".+ is telling the truth[.!]?"),
            Pattern.compile("My chest has the reward[.!]?")
    );

    // [WeirdosSolver] diagnostics - logging only (chat-rate).
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzles");

    private static final Pattern TALK_FIRST = Pattern.compile("Talk to all of us first[.!]?");

    private static volatile BlockPos correctPos = null;
    private static final Set<BlockPos> wrongPositions = ConcurrentHashMap.newKeySet();
    /** Weirdos (color-stripped names) that have said their line in this room; Hypixel opens no chest before all three. */
    private static final Set<String> heardNpcs = ConcurrentHashMap.newKeySet();
    /** "Talk to all of us first!" lines heard in this room: each one is a chest click the server refused. */
    private static volatile int refusals = 0;

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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("WeirdosSolverFeature", com.killer560.hub.util.FeatureBits.register("Weirdos Solver tick", () -> WeirdosSolverConfig.getInstance().isEnabledRaw()), client -> {
            // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
            boolean inBoss = LiveMapFeature.isInBoss();
            // The ROOM INSTANCE, like every other solver in this package.
            //
            // This was the one solver with no room check, so a solved Three Weirdos stayed solved for the
            // rest of the run: walk into a SECOND Three Weirdos and the highlight still pointed at the first
            // room's chest, and Auto Three Weirdos read "two wrong answers already" the moment the new room's
            // first line arrived and went for a chest thirty blocks away. Keyed on the instance rather than
            // the name, because a floor can hold two rooms of the same name.
            com.killer560.hub.roomdatabase.RoomEntry room = LiveMapFeature.currentRoomEntry();
            if (room != lastRoomEntry) {
                if (hasState()) {
                    LOGGER.info("[WeirdosSolver] room changed to {}: dropping answer {} wrong={} heard={} refusals={}",
                            room == null ? "no known room" : room.name,
                            correctPos == null ? "none" : correctPos.toShortString(), wrongPositions.size(),
                            heardNpcs.size(), refusals);
                }
                if (room != null && "Three Weirdos".equals(room.name)) {
                    int[] cr = LiveMapFeature.currentRoomClayAndRotation();
                    LOGGER.info("[WeirdosSolver] Three Weirdos entered: clay/rotation {}",
                            cr == null ? "not known yet" : cr[0] + "," + cr[1] + " rot " + cr[2]);
                } else if (lastRoomEntry != null && "Three Weirdos".equals(lastRoomEntry.name)) {
                    LOGGER.info("[WeirdosSolver] left Three Weirdos: now in {}", room == null ? "no known room" : room.name);
                }
                lastRoomEntry = room;
                reset();
            }
            if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || inBoss) {
                if (hasState()) {
                    LOGGER.info("[WeirdosSolver] solver idle: enabled={} inDungeon={} inBoss={} - answer cleared",
                            WeirdosSolverConfig.getInstance().isEnabled(), DungeonState.isInDungeon(), inBoss);
                }
                reset();
            }
        }));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(com.killer560.hub.util.FeatureGuard.afterTerrain(
                com.killer560.hub.util.FeatureBits.register("Weirdos Solver render", () -> WeirdosSolverConfig.getInstance().isEnabledRaw()), WeirdosSolverFeature::onWorldRender));
    }

    /** The room the current answer belongs to, so leaving it drops the answer. */
    private static com.killer560.hub.roomdatabase.RoomEntry lastRoomEntry;

    private static void onMessage(Component message) {
        if (!WeirdosSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return;
        }
        // Same room gate the render uses: an "[NPC] ..." line heard anywhere else must not mark chests here.
        com.killer560.hub.roomdatabase.RoomEntry room = LiveMapFeature.currentRoomEntry();
        if (room == null || !"Three Weirdos".equals(room.name)) {
            // Logging only: say so when a weirdo-shaped line is dropped because the map does not put him in the room.
            String p = ChatFormatting.stripFormatting(message.getString());
            var m = NPC_LINE.matcher((p != null ? p : message.getString()).trim());
            if (m.matches()) {
                String d = m.group(2);
                if (SOLUTIONS.stream().anyMatch(s -> s.matcher(d).matches())
                        || WRONG.stream().anyMatch(s -> s.matcher(d).matches())) {
                    LOGGER.info("[WeirdosSolver] weirdo line from \"{}\" ignored: the current room is {}, not Three Weirdos",
                            m.group(1), room == null ? "not known" : room.name);
                }
            }
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = (plain != null ? plain : message.getString()).trim();

        var matcher = NPC_LINE.matcher(raw);
        if (!matcher.matches()) {
            return;
        }
        String npc = matcher.group(1);
        String dialogue = matcher.group(2);

        // Hypixel's refusal when a chest is clicked before all three have spoken: nothing opens and the puzzle is not
        // failed (Mod Only Test log, 2026-10-19 16:13:52 - Auto Three Weirdos opened the right chest with two of three
        // talked to and got this). Counted so the auto knows its click did nothing and goes back to talking.
        if (TALK_FIRST.matcher(dialogue).matches()) {
            refusals++;
            LOGGER.info("[WeirdosSolver] \"{}\" refused the chest: \"{}\" ({} of 3 weirdos heard so far)", npc, dialogue,
                    heardNpcs.size());
            return;
        }
        if (heardNpcs.add(stripName(npc))) {
            LOGGER.info("[WeirdosSolver] heard \"{}\" ({} of 3 weirdos)", npc, heardNpcs.size());
        }

        boolean isSolution = SOLUTIONS.stream().anyMatch(p -> p.matcher(dialogue).matches());
        boolean isWrong = !isSolution && WRONG.stream().anyMatch(p -> p.matcher(dialogue).matches());
        if (!isSolution && !isWrong) {
            // Said out loud so a line Hypixel words differently from both lists is seen, not silently dropped.
            LOGGER.info("[WeirdosSolver] Line from \"{}\" matches neither list - not used: \"{}\"", npc, dialogue);
            return;
        }

        BlockPos chestPos = findChestPos(npc);
        if (chestPos == null) {
            return;
        }
        if (isSolution) {
            correctPos = chestPos;
            wrongPositions.remove(chestPos);
            deducedLogged = false;
            LOGGER.info("[WeirdosSolver] Answer: \"{}\" said a solution line (\"{}\") - the reward is in {}'s chest at {}",
                    npc, dialogue, npc, chestPos.toShortString());
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f));
        } else {
            if (!chestPos.equals(correctPos)) {
                wrongPositions.add(chestPos);
            } else {
                LOGGER.info("[WeirdosSolver] wrong line names the chest already marked correct at {} - kept as correct",
                        chestPos.toShortString());
            }
            LOGGER.info("[WeirdosSolver] Wrong: \"{}\" said \"{}\" - {}'s chest at {} is not it ({} wrong known)",
                    npc, dialogue, npc, chestPos.toShortString(), wrongPositions.size());
            if (correctPos == null && wrongPositions.size() >= 2) {
                deduceThirdChest();
            }
        }
    }

    private static boolean deducedLogged = false;

    /**
     * Two weirdos ruled their own chests out and nobody has said a solution line yet (it may be worded in a way neither
     * list knows): the reward is in the third weirdo's chest. The third chest is the one beside another named stand in
     * this room, found with the same relative offset {@link #findChestPos} uses, that is a chest and is not one of the
     * two wrong ones. Only an answer when exactly one such chest exists.
     */
    private static void deduceThirdChest() {
        Minecraft client = Minecraft.getInstance();
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (client.level == null || cr == null) {
            LOGGER.info("[WeirdosSolver] cannot deduce the third chest: level={} clay/rotation known={}",
                    client.level != null, cr != null);
            return;
        }
        Set<BlockPos> candidates = new java.util.HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }
            String name = stripName(entity.getName().getString());
            if (name.isBlank() || name.contains("CLICK")) {
                continue;
            }
            boolean near = false;
            for (BlockPos w : wrongPositions) {
                near |= entity.distanceToSqr(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5) < 12 * 12;
            }
            if (!near) {
                continue;
            }
            BlockPos chest = chestPosFor(entity, cr);
            if (wrongPositions.contains(chest)) {
                continue;
            }
            if (client.level.getBlockState(chest).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                candidates.add(chest);
            }
        }
        if (candidates.size() == 1) {
            correctPos = candidates.iterator().next();
            LOGGER.info("[WeirdosSolver] Answer (deduced): two weirdos ruled their chests out and no solution line was"
                    + " heard - the reward is in the remaining chest at {}", correctPos.toShortString());
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f));
        } else if (!deducedLogged) {
            deducedLogged = true;
            LOGGER.info("[WeirdosSolver] Two wrong chests known but {} candidate third chest(s) beside named stands - no"
                    + " answer deduced", candidates.size());
        }
    }

    private static BlockPos findChestPos(String npcName) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            // The one path in here that used to fail SILENTLY, and the only one that fits his report:
            // "It only shows the middle one as red the other two do not get highlights" (2026-09-24). The three
            // weirdos speak within a moment of each other, so if the room's clay/rotation is not resolved for
            // some of those lines, those NPCs get no chest and nothing is written down - one highlight, two
            // missing, no evidence. Every other refusal in this class already says why; this one now does too,
            // so the next run settles whether that is what happens rather than leaving it to be reasoned about.
            LOGGER.info("[WeirdosSolver] No chest for \"{}\" - the room's clay/rotation is not resolved yet"
                    + " (LiveMapFeature.currentRoomClayAndRotation() == null)", npcName);
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
        return chestPosFor(npc, clayAndRotation);
    }

    /** The chest of the weirdo standing at {@code npc}: one relative block over, on the dungeon floor's height. */
    private static BlockPos chestPosFor(Entity npc, int[] clayAndRotation) {
        // 69 is Hypixel's dungeon floor; the sim shifts the whole map, so the literal has to move with it.
        // Same DungeonLayout.simYOffset() every other height in this mod goes through, zero on a real run.
        BlockPos npcBlockPos = new BlockPos((int) Math.floor(npc.getX()),
                69 + com.killer560.hub.livemap.DungeonLayout.simYOffset(), (int) Math.floor(npc.getZ()));
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
            SolverEspRender.renderWaypoint(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.WEIRDOS, context, new AABB(correctPos), 0.2f, 1.0f, 0.3f, 2f);
        }
        if (cfg.isShowWrongChests()) {
            for (BlockPos pos : wrongPositions) {
                SolverEspRender.renderWaypoint(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.WEIRDOS, context, new AABB(pos), 1.0f, 0.2f, 0.2f, 2f);
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

    /** How many different weirdos have said their line in this room (Hypixel opens no chest before all three). */
    public static int getHeardCount() {
        return heardNpcs.size();
    }

    /** How many times a weirdo has said "Talk to all of us first!" in this room (a refused chest click each). */
    public static int getRefusalCount() {
        return refusals;
    }

    /**
     * Forgets this room's answer so a fresh set of three lines can be read.
     *
     * <p>The same {@link #reset} the room change does, exposed for the dungeon sim. A real Three Weirdos is
     * answered once and never speaks again, so the solver has never had to cope with a second round in one
     * room - but the sim's version hands out a new round after a wrong chest, and without this the previous
     * round's correct chest and both its wrong chests were still highlighted alongside the new ones.
     */
    public static void clearForNewRound() {
        LOGGER.info("[WeirdosSolver] new round requested (sim): dropping answer {} wrong={} heard={} refusals={}",
                correctPos == null ? "none" : correctPos.toShortString(), wrongPositions.size(), heardNpcs.size(), refusals);
        reset();
    }

    /** Logging only: whether there is anything for a reset to drop. */
    private static boolean hasState() {
        return correctPos != null || !wrongPositions.isEmpty() || !heardNpcs.isEmpty() || refusals != 0;
    }

    private static void reset() {
        correctPos = null;
        wrongPositions.clear();
        heardNpcs.clear();
        refusals = 0;
        deducedLogged = false;
    }
}
