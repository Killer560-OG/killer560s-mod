package com.killer560.hub.diorite;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private static void onChatMessage(Component message) {
        if (!DioriteGlassConfig.getInstance().isEnabled()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        if (STORM_START_REGEX.matcher(raw).matches()) {
            stormPhaseActive = true;
        } else if (STORM_END_REGEX.matcher(raw).matches()) {
            stormPhaseActive = false;
        }
    }

    private static void tick(Minecraft client) {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            stormPhaseActive = false;
        }
        wasInDungeon = inDungeon;

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
        for (Pillar pillar : PILLARS) {
            BlockPos min = pillar.center().offset(-RADIUS, 0, -RADIUS);
            BlockPos max = pillar.center().offset(RADIUS, HEIGHT, RADIUS);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                var block = client.level.getBlockState(pos).getBlock();
                if (block == Blocks.DIORITE || block == Blocks.POLISHED_DIORITE) {
                    client.level.setBlock(pos.immutable(), pillar.glass(), 19);
                }
            }
        }
    }
}
