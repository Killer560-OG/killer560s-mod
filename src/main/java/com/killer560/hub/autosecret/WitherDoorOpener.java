package com.killer560.hub.autosecret;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Auto Secret's wither door click (killer560, 2026-10-06: when opening a wither door is the only thing left, "Auto
 * Secret opens the wither door itself"). One right click on the door's lock block, sent the way the mod's other
 * world clicks are sent: from START_CLIENT_TICK (docs/AP3.md - an END click is GrimAC Post), the BODY turned to the
 * click's surface point a tick before with the camera held ({@link BodyAim} - a click from another facing is
 * RotationPlace), at a point on the block's real surface ({@link BlockHits#surface}), within the measured reach, and
 * through {@link ActionGate}. A hotbar Wither Key item (only the dungeon sim has one; Hypixel's key is not an item) is
 * selected first through the game mode's own carried-item send, so the slot reaches the server once, before the use.
 */
final class WitherDoorOpener {

    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");
    private static final String WITHER_KEY_ID = "WITHER_KEY";
    private static final BodyAim BODY = new BodyAim(() -> { });

    private enum Step { IDLE, AIM, CLICK }

    private static Step step = Step.IDLE;
    private static BlockPos lock;
    /** The door is the blood door: no wither key item is selected for it (the blood key is the team's, not an item). */
    private static boolean blood;
    private static int clicks;
    private static String lastResult;

    private WitherDoorOpener() {
    }

    static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("WitherDoorOpener.tick", WitherDoorOpener::tick));
    }

    /** Asks for one click on {@code lockBlock}, sent over the next two START ticks. */
    static void click(BlockPos lockBlock) {
        click(lockBlock, false);
    }

    /** As {@link #click(BlockPos)}; {@code bloodDoor} clicks the blood door, holding whatever is in hand. */
    static void click(BlockPos lockBlock, boolean bloodDoor) {
        lock = lockBlock;
        blood = bloodDoor;
        step = Step.AIM;
        lastResult = null;
    }

    static void cancel() {
        step = Step.IDLE;
        lock = null;
    }

    static boolean isBusy() {
        return step != Step.IDLE;
    }

    /** Clicks sent since start (for the log and the testkit). */
    static int clicks() {
        return clicks;
    }

    /** Why the last click was not sent, or null. */
    static String lastResult() {
        return lastResult;
    }

    /** True when the team has a wither key: Hypixel's sidebar count, or a key item in the hotbar (the sim's). */
    static boolean haveKey(LocalPlayer player) {
        return DungeonState.sidebarWitherKeys() > 0 || keySlot(player) >= 0;
    }

    private static int keySlot(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (WITHER_KEY_ID.equals(ItemIdentity.skyblockId(player.getInventory().getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    /** SkyBlock items whose right click does something of its own (teleports, abilities, menus). */
    private static final java.util.Set<String> ACTIVE_ITEMS = java.util.Set.of("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END",
            "ETHERWARP_CONDUIT", "HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE", "BAT_WAND", "STARRED_BAT_WAND",
            "SKYBLOCK_MENU", "INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT", "BONZO_STAFF", "STARRED_BONZO_STAFF", "JERRY_STAFF",
            "ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND", "GYROKINETIC_WAND", "DUNGEONBREAKER", "SPRING_BOOTS",
            "GRAPPLING_HOOK", "TERMINATOR", "LAST_BREATH", "JUJU_SHORTBOW", "INK_WAND", "SOUL_WHIP", "FLOWER_OF_TRUTH");

    /**
     * The hotbar slot to click a door with when there is no key item: an empty hand first (the selected slot if it is
     * empty, else the first empty one) - the list below cannot know every ability - then the selected slot if it holds
     * nothing on {@link #ACTIVE_ITEMS}, then any such slot. -1 = keep the hand (every slot is an ability item).
     */
    private static int quietSlot(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).isEmpty()) {
            return selected;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        if (quiet(player, selected)) {
            return selected;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (quiet(player, slot)) {
                return slot;
            }
        }
        LOGGER.warn("[AutoSecret] every hotbar slot holds an ability item - clicking the door with the one in hand");
        return -1;
    }

    private static boolean quiet(LocalPlayer player, int slot) {
        var stack = player.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            return true;
        }
        String id = ItemIdentity.skyblockId(stack);
        return id != null && !ACTIVE_ITEMS.contains(id);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        BODY.tick(player, step == Step.CLICK);
        if (step == Step.IDLE || player == null || client.level == null || client.gameMode == null) {
            if (player == null) {
                step = Step.IDLE;
            }
            return;
        }
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = BlockHits.surface(client.level, lock, eye);
        double reach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
        if (hit == null || BlockHits.boxDistanceSq(eye, lock) > reach * reach) {
            fail(hit == null ? "the door block has no surface (already open?)" : "the door is out of reach");
            return;
        }
        if (step == Step.AIM) {
            // Tick t: the slot and the body's facing; this tick's movement packet reports the facing.
            // The key item when there is one (the sim's); otherwise a hand that does nothing on a right click: a held
            // Aspect of the Void turned the sim's blood-door click into Instant Transmission (141-sim-autopilot,
            // 2026-10-06), and Hypixel's abilities fire on a block click the same way.
            int slot = blood ? -1 : keySlot(player);
            if (slot < 0) {
                slot = quietSlot(player);
            }
            if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
                player.getInventory().setSelectedSlot(slot);
                if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
                    invoker.killer560smod$invokeEnsureHasSentCarriedItem();
                } else {
                    player.connection.send(new ServerboundSetCarriedItemPacket(slot));
                }
            }
            Vec3 d = hit.getLocation().subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            BODY.turnTo(player, yaw, Mth.clamp(pitch, -90f, 90f));
            step = Step.CLICK;
            return;
        }
        // Tick t+1: the use, at the START, before this tick's movement packet; the body still faces the point.
        if (!ActionGate.tryAct(ActionGate.Actor.DOOR_OPENER)) {
            return; // the same click goes on the next tick the gate allows
        }
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        clicks++;
        LOGGER.info("[AutoSecret] right-clicked the {} door at {} (face {}, from {})", blood ? "blood" : "wither",
                lock.toShortString(),
                hit.getDirection(), String.format(java.util.Locale.US, "%.2f %.2f %.2f", eye.x, eye.y, eye.z));
        step = Step.IDLE;
    }

    private static void fail(String why) {
        lastResult = why;
        LOGGER.info("[AutoSecret] wither door click not sent: {}", why);
        step = Step.IDLE;
    }
}
