package com.killer560.hub.leftclicksecrets;

import com.killer560.hub.dungeonbreaker.BreakerGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

/**
 * Left Click Opens Secrets (cheat build only, off by default; killer560 2026-11-05: "make an option for left click to also
 * open secrets ... left click a chest and it wouldn't mine it but would instead right click it", "It should work with left
 * click on any item"). In a dungeon, the player's own left click on a secret block ({@link BreakerGuard#isSecretBlock}:
 * chest, trapped chest, a lever that is not a dummy, Wither Essence, Redstone Key) is one vanilla right click on that
 * block instead, whatever is in his hand: vanilla's attack never runs (no dig START, no swing from it), and
 * {@code MultiPlayerGameMode.useItemOn} sends the block use the way a right click does ({@code startPrediction}, his own
 * look, no turn, no hotbar change). One use per press: the held button ({@code continueAttack}) neither digs nor clicks
 * again while the crosshair stays on the secret. Only the attack key's own path is hooked
 * ({@code leftclickew/mixin/LeftClickEtherwarpMinecraftMixin}, which asks this first), so Breaker Aura and every other
 * automation keep refusing secrets as before.
 */
public final class LeftClickSecrets {

    private static final Logger LOGGER = ModLog.get("killer560smod-leftclicksecrets");

    private static long uses;

    private LeftClickSecrets() {
    }

    /** The secret under the crosshair this click/hold is about, or null when the feature does not apply. */
    private static BlockHitResult target(Minecraft mc) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || !LeftClickSecretsConfig.getInstance().isEnabled()
                || !DungeonState.isInDungeon() || mc.player == null || mc.level == null || mc.gameMode == null
                || mc.player.isSpectator() || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return BreakerGuard.isSecretBlock(mc.level, hit.getBlockPos()) ? hit : null;
    }

    /** HEAD of {@code Minecraft.startAttack}. True: the click was turned into a use and vanilla's attack must not run. */
    public static boolean onLeftClick(Minecraft mc) {
        BlockHitResult hit = target(mc);
        if (hit == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            com.killer560.hub.compat.McCompat.swingUse(player);
        }
        uses++;
        LOGGER.info("[LeftClickSecrets] left click on the {} at {} sent as a right click (result {}, {} held)",
                mc.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString(),
                hit.getBlockPos().toShortString(), result, player.getMainHandItem().getHoverName().getString());
        return true;
    }

    /** HEAD of {@code Minecraft.continueAttack}: a held left click on a secret neither digs it nor clicks it again. */
    public static boolean suppressHeld(Minecraft mc, boolean leftDown) {
        return leftDown && target(mc) != null;
    }

    /** Read by the testkit (reflection). */
    public static long uses() {
        return uses;
    }
}
