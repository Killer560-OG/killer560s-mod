package com.killer560.hub.p4platform;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
 */
public final class P4PlatformHighlightFeature {

    private static final Pattern CORE_OPENING_REGEX = Pattern.compile("^The Core entrance is opening!$");
    private static final Pattern GOLDOR_START_REGEX =
            Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    private static final Pattern NECRON_P5_REGEX = Pattern.compile("^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$");

    private static final AABB PLATFORM_BOX = new AABB(53.0, 63.0, 113.0, 56.0, 64.0, 116.0);

    private static boolean platformActive = false;
    private static boolean wasInDungeon = false;

    private P4PlatformHighlightFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(P4PlatformHighlightFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(P4PlatformHighlightFeature::onWorldRender);
    }

    private static void onChatMessage(Component message) {
        if (!P4PlatformHighlightConfig.getInstance().isEnabled()) {
            return;
        }
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
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-p4platform");
    private static String diagLastRenderGate;

    private static void onWorldRender(LevelRenderContext context) {
        P4PlatformHighlightConfig cfg = P4PlatformHighlightConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (platformActive) {
            // Diagnostic (2026-09-14): state-change only, and only while the platform window is open.
            String gate = !cfg.isEnabled() ? "hidden: disabled" : !DungeonState.isF7OrM7() ? "hidden: DungeonState.isF7OrM7()=false"
                    : client.level == null ? "hidden: no level" : "DRAWING";
            if (!gate.equals(diagLastRenderGate)) {
                LOGGER.info("[P4Platform] render gate: {} -> {}", diagLastRenderGate, gate);
                diagLastRenderGate = gate;
            }
        } else {
            diagLastRenderGate = null;
        }
        if (!cfg.isEnabled() || !platformActive || !DungeonState.isF7OrM7() || client.level == null) {
            return;
        }
        if (cfg.isFilled()) {
            WorldRenderUtils.renderFilledBox(context, PLATFORM_BOX, 0.2f, 1.0f, 1.0f, 0.35f);
        }
        WorldRenderUtils.renderOutlineBox(context, PLATFORM_BOX, 0.2f, 1.0f, 1.0f, 1.0f, 2f);
    }
}
