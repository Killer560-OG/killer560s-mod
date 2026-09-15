package com.killer560.hub.boss;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Real Hypixel Floor 5 boss (Livid) solver, ported from Odin's own {@code LividSolver.kt}. Livid spawns
 * 8 real fake copies plus 1 real one, distinguishable only by a real wool block that appears at a fixed
 * real world position ({@code (5, 108, 43)} - the boss arena itself is a fixed, non-randomized room, so
 * unlike every dungeon puzzle solver this mod has ported this needs no room-rotation transform at all)
 * the instant the fight starts, whose color maps to one specific real Livid's real display name
 * ("&lt;Color&gt; Livid"). Highlights that one real entity's hitbox (skipped while you have the real
 * Blindness effect, since you can't see anything then anyway) and shows a real countdown until Livid's
 * opening invulnerability ends. Never attacks anything - only identifies and highlights.
 */
public final class LividSolverFeature {

    private enum Livid {
        VENDETTA("Vendetta", Blocks.WHITE_WOOL),
        CROSSED("Crossed", Blocks.MAGENTA_WOOL),
        ARCADE("Arcade", Blocks.YELLOW_WOOL),
        SMILE("Smile", Blocks.LIME_WOOL),
        DOCTOR("Doctor", Blocks.GRAY_WOOL),
        PURPLE("Purple", Blocks.PURPLE_WOOL),
        SCREAM("Scream", Blocks.BLUE_WOOL),
        FROG("Frog", Blocks.GREEN_WOOL),
        HOCKEY("Hockey", Blocks.RED_WOOL);

        final String entityName;
        final Block wool;

        Livid(String entityName, Block wool) {
            this.entityName = entityName;
            this.wool = wool;
        }
    }

    private static final BlockPos WOOL_LOCATION = new BlockPos(5, 108, 43);
    private static final String LIVID_START_LINE =
            "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";

    private static Livid currentLivid = null;
    private static Entity lividEntity = null;
    private static int invulnTicks = 0;
    private static boolean wasOnFloor5 = false;

    private LividSolverFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(LividSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(LividSolverFeature::onWorldRender);
    }

    private static boolean onFloor5() {
        String floor = DungeonState.getFloor();
        return "F5".equals(floor) || "M5".equals(floor);
    }

    private static void onMessage(Component message) {
        if (!LividSolverConfig.getInstance().isEnabled() || !onFloor5()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        if (raw.equals(LIVID_START_LINE)) {
            LOGGER.info("[Livid] Start line seen - invulnerability timer armed (390t)");
            invulnTicks = 390;
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-livid");

    private static void tick(Minecraft client) {
        boolean onFloor5 = LividSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon() && onFloor5();
        if (onFloor5 != wasOnFloor5) {
            LOGGER.info("[Livid] solver {} (enabled={}, inDungeon={}, floor={})", onFloor5 ? "ACTIVE" : "INACTIVE",
                    LividSolverConfig.getInstance().isEnabled(), DungeonState.isInDungeon(), DungeonState.getFloor());
        }
        if (!onFloor5 && wasOnFloor5) {
            reset();
        }
        wasOnFloor5 = onFloor5;
        if (!onFloor5) {
            return;
        }

        if (invulnTicks > 0) {
            invulnTicks--;
        }

        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        if (currentLivid == null) {
            Block block = level.getBlockState(WOOL_LOCATION).getBlock();
            for (Livid livid : Livid.values()) {
                if (livid.wool == block) {
                    currentLivid = livid;
                    LOGGER.info("[Livid] Wool at {} is {} -> real Livid = \"{} Livid\"", WOOL_LOCATION, block, livid.entityName);
                    break;
                }
            }
        }
        if (currentLivid != null && (lividEntity == null || lividEntity.isRemoved())) {
            String targetName = currentLivid.entityName + " Livid";
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Player && entity.getName().getString().equals(targetName)) {
                    lividEntity = entity;
                    LOGGER.info("[Livid] Found real Livid entity \"{}\" at {}", targetName, entity.position());
                    break;
                }
            }
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!LividSolverConfig.getInstance().isEnabled() || lividEntity == null || lividEntity.isRemoved()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.hasEffect(MobEffects.BLINDNESS)) {
            return;
        }
        WorldRenderUtils.renderOutlineBox(context, lividEntity.getBoundingBox(), 1.0f, 0.2f, 0.2f, 1f, 2f);
    }

    private static void reset() {
        currentLivid = null;
        lividEntity = null;
        invulnTicks = 0;
    }

    public static final class InvulnTimerHudElement implements HudElement {
        @Override
        public String id() {
            return "livid_invuln_timer";
        }

        @Override
        public String displayName() {
            return "Livid Invulnerability Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 400;
        }

        @Override
        public int width() {
            return 140;
        }

        @Override
        public int height() {
            return 12;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!LividSolverConfig.getInstance().isEnabled() || !LividSolverConfig.getInstance().isShowTimer()
                    || Minecraft.getInstance().screen != null || invulnTicks <= 0) {
                return;
            }
            String color = invulnTicks > 260 ? "§a" : invulnTicks > 130 ? "§e" : "§c";
            String text = color + "Livid: " + invulnTicks + "t";
            graphics.text(Minecraft.getInstance().font, text, x, y, 0xFFFFFFFF, false);
        }
    }
}
