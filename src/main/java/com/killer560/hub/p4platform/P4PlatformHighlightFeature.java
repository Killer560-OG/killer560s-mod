package com.killer560.hub.p4platform;

import com.killer560.hub.puzzlesolvers.SolverEspRender;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Real F7/M7 Necron P4 platform highlight, ported from QUOI's own {@code P4PlatformHighlight.kt} - "the
 * 3x3 the player needs to break and the mod will highlight the blocks" (killer560's own description).
 * Real mechanic: after Goldor dies, the real core entrance opens and the boss arena drops you onto a
 * fixed platform above Necron's real Stage 5 room; the player has to mine through a specific real 3x3
 * (a fixed, arena-locked area - it never rotates or moves like the earlier F7 puzzle rooms do) to fall
 * into the fight. This just draws a box over that real fixed area so it's obvious which blocks to break.
 * <p>
 * Stage tracking: QUOI's own {@code Floor7.kt} explicitly avoids matching Goldor's death line directly
 * ("we don't use goldor's death message, since it could be dialogue skipped") and instead starts Stage 5
 * on the real "The Core entrance is opening!" chat line - the same real line this codebase's own
 * {@code TickTimersFeature}/{@code SplitTimersFeature} already use for the exact same reason. This
 * highlight turns off again on the real "[BOSS] Necron: All this, for nothing..." line, which is QUOI's
 * own real Phase 5 (post-platform) trigger - by that point the platform's already been broken.
 * <p>
 * killer560, 2026-09-20: "p4 platform highlight doesn't work". Three things were wrong, none of them the
 * coordinates (byte-verified identical to QUOI's own {@code healerBox}): the chat listener ignored the
 * Core line while the setting was off, so enabling it mid-fight could never arm it; the whole feature
 * hung on that one line, so a run where it never arrived (rejoin, hidden/skipped dialogue, or dropping
 * straight onto the platform - which is exactly what this repo's own F7 log shows) drew nothing at all;
 * and the box was drawn depth-tested exactly on the faces of the blocks it outlines, which z-fights into
 * nothing and is fully occluded the moment you stand on the platform. The chat line still arms it, but a
 * positional fallback now covers the rest, and the highlight stops on its own once the 3x3 is mined out.
 */
public final class P4PlatformHighlightFeature {

    private static final Pattern CORE_OPENING_REGEX = Pattern.compile("^The Core entrance is opening!$");
    private static final Pattern GOLDOR_START_REGEX =
            Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    private static final Pattern NECRON_P5_REGEX = Pattern.compile("^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$");

    private static final AABB PLATFORM_BOX = new AABB(53.0, 63.0, 113.0, 56.0, 64.0, 116.0);

    /** How close the player has to be to the platform for the positional fallback below to count.
     *  The core is its own arena region, well away from any normal dungeon room's coordinates. */
    private static final double NEAR_PLATFORM_RANGE = 32.0;

    private static boolean platformActive = false;
    private static boolean wasInDungeon = false;
    private static boolean nearPlatform = false;
    private static boolean platformBroken = false;
    private static int worldCheckCounter = 0;

    private P4PlatformHighlightFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(P4PlatformHighlightFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(P4PlatformHighlightFeature::onWorldRender);
    }

    private static void onChatMessage(Component message) {
        // Deliberately NOT gated on isEnabled(): the state has to be tracked whether or not the highlight
        // is currently switched on, otherwise turning it on after the Core line (or while SkyblockGate is
        // still settling) leaves platformActive false for the rest of the fight.
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        if (CORE_OPENING_REGEX.matcher(raw).matches()) {
            LOGGER.info("[P4Platform] platformActive {} -> true by line \"{}\" (isF7OrM7={}, floor={})",
                    platformActive, raw, DungeonState.isF7OrM7(), DungeonState.getFloor());
            platformActive = true;
        } else if (GOLDOR_START_REGEX.matcher(raw).matches() || NECRON_P5_REGEX.matcher(raw).matches()) {
            LOGGER.info("[P4Platform] platformActive {} -> false by line \"{}\"", platformActive, raw);
            platformActive = false;
        }
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            if (platformActive) {
                LOGGER.info("[P4Platform] platformActive true -> false (left dungeon)");
            }
            platformActive = false;
        }
        wasInDungeon = inDungeon;

        // World state, sampled off the render thread: whether the 3x3 is still there, and whether the
        // player is standing in the core at all. Both are what let the highlight show up in a run where
        // the Core line never reached us (rejoin, dialogue-skipped/hidden chat, warp straight to the
        // platform - the one case in this repo's own logs) instead of the feature simply never firing.
        if (++worldCheckCounter < 5) {
            return;
        }
        worldCheckCounter = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !DungeonState.isF7OrM7()) {
            nearPlatform = false;
            platformBroken = false;
            return;
        }
        nearPlatform = client.player.position().distanceToSqr(PLATFORM_BOX.getCenter())
                < NEAR_PLATFORM_RANGE * NEAR_PLATFORM_RANGE;
        BlockPos center = BlockPos.containing(PLATFORM_BOX.getCenter());
        if (!client.level.isLoaded(center)) {
            platformBroken = false; // not loaded - say nothing either way
            return;
        }
        boolean anySolid = false;
        for (int x = (int) PLATFORM_BOX.minX; x < (int) PLATFORM_BOX.maxX && !anySolid; x++) {
            for (int z = (int) PLATFORM_BOX.minZ; z < (int) PLATFORM_BOX.maxZ && !anySolid; z++) {
                anySolid = !client.level.getBlockState(new BlockPos(x, (int) PLATFORM_BOX.minY, z)).isAir();
            }
        }
        platformBroken = !anySolid;
    }

    /** @return true when the highlight should be on screen. */
    private static boolean shouldShow() {
        return (platformActive || nearPlatform) && !platformBroken;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-p4platform");
    private static String diagLastRenderGate;

    private static void onWorldRender(LevelRenderContext context) {
        P4PlatformHighlightConfig cfg = P4PlatformHighlightConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (platformActive || nearPlatform) {
            // Diagnostic (2026-09-14): state-change only, and only while the platform window is open.
            String gate = !cfg.isEnabled() ? "hidden: disabled" : !DungeonState.isF7OrM7() ? "hidden: DungeonState.isF7OrM7()=false"
                    : client.level == null ? "hidden: no level" : platformBroken ? "hidden: platform already broken"
                    : "DRAWING (chatLine=" + platformActive + " near=" + nearPlatform + ")";
            if (!gate.equals(diagLastRenderGate)) {
                LOGGER.info("[P4Platform] render gate: {} -> {}", diagLastRenderGate, gate);
                diagLastRenderGate = gate;
            }
        } else {
            diagLastRenderGate = null;
        }
        if (!cfg.isEnabled() || !shouldShow() || !DungeonState.isF7OrM7() || client.level == null) {
            return;
        }
        // Through SolverEspRender, not WorldRenderUtils: this box sits exactly on the faces of the very
        // blocks it outlines, so depth-tested it z-fights into nothing and is completely hidden the moment
        // you stand on the platform - which is the only time you want to see it. QUOI, the mod this was
        // ported from, ships its highlights with the "Depth check" switch off for the same reason.
        if (cfg.isFilled()) {
            SolverEspRender.renderFilledBox(context, PLATFORM_BOX, 0.2f, 1.0f, 1.0f, 0.35f);
        }
        SolverEspRender.renderOutlineBox(context, PLATFORM_BOX, 0.2f, 1.0f, 1.0f, 1.0f, 2f);
    }
}
