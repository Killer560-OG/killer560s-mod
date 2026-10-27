package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Secret Clicked and the chest/lever sound mutes (2026-10-25).
 * <ul>
 * <li><b>Secret Clicked</b> (Odin {@code SecretClicked.kt}, RSM, NoammAddons, Devonian): a box for {@link #BOX_MS} on
 * every secret you collect - the same detections Secret Sound uses (chest, lever, essence, key skull, bat, item) - and
 * the box turns red, with an anvil sound, when Hypixel answers the chest click with "That chest is locked!". The
 * locked line is matched whole (a player's message always carries a prefix, so it cannot be forged).</li>
 * <li><b>Mute Chest Sounds / Mute Lever Sounds</b> (SkyHanni {@code SecretChimeConfig}): in a dungeon, the server's
 * chest open/close and lever click sounds are dropped before they play. Nothing is sent; only those sound packets are
 * cancelled ({@code DungeonAlertsPacketMixin}).</li>
 * </ul>
 */
public final class SecretClicked {

    static final long BOX_MS = 7000L;
    /** A locked line further than this after the click belongs to no box of ours. */
    private static final long LOCKED_WINDOW_MS = 1500L;
    private static final String LOCKED_LINE = "That chest is locked!";
    private static final float[] GOLD = {1.0f, 0.67f, 0.0f, 0.8f};
    private static final float[] RED = {1.0f, 0.2f, 0.2f, 0.8f};

    private static final class Box {
        final AABB aabb;
        final long atMs;
        boolean locked;

        Box(AABB aabb, long atMs) {
            this.aabb = aabb;
            this.atMs = atMs;
        }
    }

    private static final List<Box> boxes = new ArrayList<>();
    private static int boxesAdded = 0;
    private static int lockedMarked = 0;
    private static int soundsMuted = 0;
    private static int boxesDrawnFrames = 0;

    private SecretClicked() {
    }

    static void register() {
        ChatObserver.subscribe(SecretClicked::onChat);
    }

    /** Whether Secret Sound's detections must run for this feature. */
    static boolean wanted() {
        return DungeonAlertsConfig.getInstance().isSecretClickedEnabled();
    }

    /** A secret was collected at {@code pos} (called by {@link SecretSound}'s detection). Render thread. */
    static void onSecret(BlockPos pos) {
        if (!wanted() || pos == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Box b : boxes) {
            if (b.aabb.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) && now - b.atMs < BOX_MS) {
                return;
            }
        }
        boxes.add(new Box(new AABB(pos), now));
        boxesAdded++;
    }

    private static void onChat(Component message) {
        if (!wanted() || !DungeonState.isInDungeon() || boxes.isEmpty()) {
            return;
        }
        if (!LOCKED_LINE.equals(ChatObserver.strip(message).trim())) {
            return;
        }
        Box last = boxes.get(boxes.size() - 1);
        if (System.currentTimeMillis() - last.atMs > LOCKED_WINDOW_MS || last.locked) {
            return;
        }
        last.locked = true;
        lockedMarked++;
        if (DungeonAlertsConfig.getInstance().isSecretClickedLockedSound()) {
            DungeonAlertsFeature.playSound(SoundEvents.ANVIL_LAND, 0.6f, 1.0f);
        }
    }

    static void onWorldRender(LevelRenderContext context) {
        if (boxes.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boxes.removeIf(b -> now - b.atMs > BOX_MS);
        if (!wanted() || !DungeonState.isInDungeon()) {
            return;
        }
        for (Box b : boxes) {
            float[] c = b.locked ? RED : GOLD;
            WorldRenderUtils.renderFilledBox(context, b.aabb, c[0], c[1], c[2], c[3] * 0.35f);
            WorldRenderUtils.renderOutlineBox(context, b.aabb, c[0], c[1], c[2], c[3], 2f);
        }
        if (!boxes.isEmpty()) {
            boxesDrawnFrames++;
        }
    }

    static void onWorldChange() {
        boxes.clear();
    }

    /** True to drop this sound packet: a chest or lever sound in a dungeon with its mute on. Render thread. */
    static boolean shouldMute(ClientboundSoundPacket packet) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.isMuteChestSounds() && !cfg.isMuteLeverSounds()) {
            return false;
        }
        if (!DungeonState.isInDungeon() || !com.killer560.hub.util.SkyblockGate.allows()) {
            return false;
        }
        // By id, not identity: a sound sent inline (a direct holder) decodes to a new SoundEvent object.
        net.minecraft.resources.Identifier id = packet.getSound().value().location();
        boolean chest = id.equals(SoundEvents.CHEST_OPEN.location()) || id.equals(SoundEvents.CHEST_CLOSE.location());
        boolean lever = id.equals(SoundEvents.LEVER_CLICK.location());
        if ((chest && cfg.isMuteChestSounds()) || (lever && cfg.isMuteLeverSounds())) {
            soundsMuted++;
            return true;
        }
        return false;
    }

    /** For the testkit: boxes added since the client started. */
    public static int boxesAdded() {
        return boxesAdded;
    }

    /** For the testkit: boxes turned red by the locked line. */
    public static int lockedMarked() {
        return lockedMarked;
    }

    /** For the testkit: sound packets muted. */
    public static int soundsMuted() {
        return soundsMuted;
    }

    /** For the testkit: frames in which at least one box was drawn. */
    public static int boxesDrawnFrames() {
        return boxesDrawnFrames;
    }

    /** For the testkit: boxes alive now, and how many of them are red. */
    public static String liveBoxes() {
        int red = 0;
        for (Box b : boxes) {
            if (b.locked) {
                red++;
            }
        }
        return boxes.size() + "/" + red;
    }
}
