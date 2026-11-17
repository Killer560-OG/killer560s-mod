package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * The sim player's death savers (simmasks-1116): Bonzo's Mask, Spirit Mask and the Phoenix pet, and the rod that swaps the
 * pet between Phoenix and Golden Dragon (an Autopet rule on Hypixel). Chat lines, windows and cooldowns are the ones Mask
 * Invincibility's timers read ({@code MaskInvincibilityFeature}): Spirit 3 s / 30 s, Bonzo 3 s / 180 s, Phoenix 4 s / 60 s.
 *
 * <p>{@link #saveFromDeath} is the ONE place a lethal hit goes through (called first from SimSurvival's ALLOW_DEATH, which otherwise sends the player home), so a later death-tick option hooks in there. Not
 * modeled: the heal each saver gives (the player is put back to full health) and Golden Dragon's stats (the sim's damage
 * model has no place for them).
 */
public final class SimMasks {

    private static final int INVULN_MASK = 60, INVULN_PHOENIX = 80;
    private static final int CD_BONZO = 180 * 20, CD_SPIRIT = 30 * 20, CD_PHOENIX = 60 * 20;

    /** Server ticks at which each saver is ready again, and until when the player is invulnerable. */
    private static long bonzoReady, spiritReady, phoenixReady, invulnUntil;
    private static boolean phoenixOut;

    private SimMasks() {
    }

    /** The head texture of a mask id on Hypixel (items resource), or null. */
    static String skinUrl(String id) {
        return switch (id) {
            case "BONZO_MASK" -> "http://textures.minecraft.net/texture/12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c";
            case "SPIRIT_MASK" -> "http://textures.minecraft.net/texture/9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2";
            default -> null;
        };
    }

    static net.minecraft.world.item.component.ResolvableProfile profile(String url) {
        return com.killer560.hub.armourdye.ArmourDye.headProfile(java.util.Base64.getEncoder()
                .encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer sp && SimState.isActive() && now(sp) < invulnUntil));
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer sp && SimState.isActive() && sp.getItemInHand(hand).is(Items.FISHING_ROD))) return InteractionResult.PASS;
            swapPet(sp);
            return InteractionResult.SUCCESS;
        });
    }

    private static long now(ServerPlayer sp) {
        return sp.level().getServer().getTickCount();
    }

    /** A lethal hit: true when a mask or Phoenix saved the player (the death is cancelled). */
    static boolean saveFromDeath(ServerPlayer sp) {
        long t = now(sp);
        String head = CheatUtils.skyblockId(sp.getItemBySlot(EquipmentSlot.HEAD));
        String line = null;
        int window = INVULN_MASK;
        if (head != null && head.endsWith("BONZO_MASK") && t >= bonzoReady) {
            bonzoReady = t + CD_BONZO;
            line = "Your Bonzo's Mask saved your life!";
        } else if (head != null && head.endsWith("SPIRIT_MASK") && t >= spiritReady) {
            spiritReady = t + CD_SPIRIT;
            line = "Second Wind Activated! Your Spirit Mask saved your life!";
        } else if (phoenixOut && t >= phoenixReady) {
            phoenixReady = t + CD_PHOENIX;
            window = INVULN_PHOENIX;
            line = "Your Phoenix Pet saved you from certain death!";
        }
        if (line == null) return false;
        invulnUntil = t + window;
        sp.setHealth(sp.getMaxHealth());
        sp.sendSystemMessage(Component.literal(line));
        return true;
    }

    /** Casting a rod: the Autopet rule toggles Phoenix and Golden Dragon. */
    private static void swapPet(ServerPlayer sp) {
        phoenixOut = !phoenixOut;
        sp.sendSystemMessage(Component.literal("Autopet equipped your [Lvl 100] " + (phoenixOut ? "Phoenix" : "Golden Dragon") + "! VIEW RULE"));
    }

    /** Forget cooldowns and the pet (a new sim, and tests). */
    public static void reset() {
        bonzoReady = spiritReady = phoenixReady = invulnUntil = 0;
        phoenixOut = false;
    }

    public static boolean phoenixOut() {
        return phoenixOut;
    }
}
