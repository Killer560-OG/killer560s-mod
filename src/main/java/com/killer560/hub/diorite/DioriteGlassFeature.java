package com.killer560.hub.diorite;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.regex.Pattern;

/**
 * "I Hate Diorite" - real F7/M7 Storm-phase pillar swap, ported from Noamm's own {@code
 * IHateDiorite.kt}. Real mechanic: killer560's own description - "it just changes the texture to be
 * glass for the f7 pillars" - Storm's real boss arena has 4 real diorite pillars she can hide behind
 * during her fight; this replaces their real diorite/polished diorite blocks with real stained glass
 * client-side (same real {@code Level#setBlock} technique Noamm's own confirmed, shipped mod uses,
 * flag 19 - a real client-only visual block swap, never touched server-side). Glass has the exact same
 * real solid full-cube collision box as diorite, so this changes nothing about movement or hitboxes -
 * purely lets you see through the pillar. No legit variant exists for this (same as Noamm's own
 * reference, which gates the whole feature behind its own cheat flag) - the real information this
 * exposes (Storm's exact position/aim behind a decorative pillar) isn't something a normal client would
 * ever be able to see, unlike {@code MobEspFeature}'s legit line-of-sight mode.
 */
public final class DioriteGlassFeature {

    private static final Pattern STORM_START_REGEX =
            Pattern.compile("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$");
    private static final Pattern STORM_END_REGEX =
            Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$");

    private static final int RADIUS = 3;
    private static final int HEIGHT = 37;

    private record Pillar(BlockPos center, BlockState glass) {
    }

    private static final Pillar[] PILLARS = {
            new Pillar(new BlockPos(46, 169, 41), Blocks.LIME_STAINED_GLASS.defaultBlockState()),
            new Pillar(new BlockPos(46, 169, 65), Blocks.YELLOW_STAINED_GLASS.defaultBlockState()),
            new Pillar(new BlockPos(100, 169, 65), Blocks.PURPLE_STAINED_GLASS.defaultBlockState()),
            new Pillar(new BlockPos(100, 169, 41), Blocks.RED_STAINED_GLASS.defaultBlockState()),
    };

    private static boolean stormPhaseActive = false;
    private static boolean wasInDungeon = false;
    private static int tickCounter = 0;

    private DioriteGlassFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(DioriteGlassFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private static void onChatMessage(Component message) {
        if (!DioriteGlassConfig.getInstance().isEnabled()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        if (STORM_START_REGEX.matcher(raw).matches()) {
            LOGGER.info("[Diorite] Storm start line matched - stormPhaseActive=true");
            stormPhaseActive = true;
        } else if (STORM_END_REGEX.matcher(raw).matches()) {
            LOGGER.info("[Diorite] Storm end line matched - stormPhaseActive=false");
            stormPhaseActive = false;
        } else if (raw.contains("[BOSS] Storm")) {
            LOGGER.info("[Diorite] Storm line (no start/end match): \"{}\"", raw);
        }
    }

    // [Diorite] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-diorite");
    private static String lastLoggedGates = null;
    private static int swappedSinceLastLog = 0;
    private static long lastSwapLogMs = 0;

    private static void tick(Minecraft client) {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            stormPhaseActive = false;
        }
        wasInDungeon = inDungeon;

        String gates = "enabled=" + DioriteGlassConfig.getInstance().isEnabled() + " stormPhase=" + stormPhaseActive
                + " f7OrM7=" + DungeonState.isF7OrM7();
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[Diorite] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }

        DioriteGlassConfig cfg = DioriteGlassConfig.getInstance();
        if (!cfg.isEnabled() || !stormPhaseActive || !DungeonState.isF7OrM7()
                || client.level == null || client.player == null) {
            return;
        }
        // Real per-block scan is cheap but not free - throttled to every 10 ticks (0.5s), same real
        // idiom Odin's own InactiveWaypoints uses for its own real per-tick entity scan.
        if (tickCounter++ % 10 != 0) {
            return;
        }
        int swapped = 0;
        for (Pillar pillar : PILLARS) {
            BlockPos min = pillar.center().offset(-RADIUS, 0, -RADIUS);
            BlockPos max = pillar.center().offset(RADIUS, HEIGHT, RADIUS);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                var block = client.level.getBlockState(pos).getBlock();
                if (block == Blocks.DIORITE || block == Blocks.POLISHED_DIORITE) {
                    client.level.setBlock(pos.immutable(), pillar.glass(), 19);
                    swapped++;
                }
            }
        }
        swappedSinceLastLog += swapped;
        if (swappedSinceLastLog > 0 && System.currentTimeMillis() - lastSwapLogMs >= 2000) {
            LOGGER.info("[Diorite] Swapped {} diorite blocks to glass since last log (player pos={})", swappedSinceLastLog,
                    client.player.blockPosition());
            swappedSinceLastLog = 0;
            lastSwapLogMs = System.currentTimeMillis();
        }
    }
}
