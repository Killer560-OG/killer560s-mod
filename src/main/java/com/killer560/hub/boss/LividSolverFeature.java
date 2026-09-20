package com.killer560.hub.boss;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.puzzlesolvers.SolverEspRender;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Real Hypixel Floor 5 boss (Livid) solver, ported from Odin's own {@code LividSolver.kt}. Livid spawns
 * 8 real fake copies plus 1 real one, distinguishable only by a real wool block that appears at a fixed
 * real world position ({@code (5, 108, 43)}, with NoammAddons' {@code (5, 108, 40)} as a fallback - the
 * boss arena itself is a fixed, non-randomized room, so unlike every dungeon puzzle solver this mod has
 * ported this needs no room-rotation transform at all) the instant the fight starts, whose color maps to
 * one specific real Livid's real display name ("&lt;Color&gt; Livid"). That clue is re-read twice a
 * second, not once, and the highlight is drawn in the identified Livid's own colour, with an optional
 * line to it (skipped while you have the real Blindness effect, since you can't see anything then
 * anyway); a real countdown shows Livid's opening invulnerability. Never attacks - only highlights.
 */
public final class LividSolverFeature {

    private enum Livid {
        // Box/line colour is the Livid's own wool colour, so "which one did it pick?" is answerable at a
        // glance - killer560, 2026-09-20: "the color changes at some point and ours stays red the whole time".
        VENDETTA("Vendetta", Blocks.WHITE_WOOL, 1.00f, 1.00f, 1.00f),
        CROSSED("Crossed", Blocks.MAGENTA_WOOL, 0.85f, 0.25f, 0.85f),
        ARCADE("Arcade", Blocks.YELLOW_WOOL, 1.00f, 0.90f, 0.20f),
        SMILE("Smile", Blocks.LIME_WOOL, 0.45f, 0.95f, 0.25f),
        DOCTOR("Doctor", Blocks.GRAY_WOOL, 0.55f, 0.55f, 0.55f),
        PURPLE("Purple", Blocks.PURPLE_WOOL, 0.55f, 0.25f, 0.80f),
        SCREAM("Scream", Blocks.BLUE_WOOL, 0.25f, 0.40f, 0.95f),
        FROG("Frog", Blocks.GREEN_WOOL, 0.25f, 0.65f, 0.25f),
        HOCKEY("Hockey", Blocks.RED_WOOL, 1.00f, 0.20f, 0.20f);

        final String entityName;
        final Block wool;
        final float[] color;

        Livid(String entityName, Block wool, float r, float g, float b) {
            this.entityName = entityName;
            this.wool = wool;
            this.color = new float[]{r, g, b};
        }
    }

    // Odin and Devonian both read (5,108,43); NoammAddons reads (5,108,40). Ours was Odin's, checked once
    // and then never again. Both are tried now, primary first, so a run where only the other one carries
    // the clue (and p3sim, which need not place both) still resolves.
    private static final BlockPos[] WOOL_LOCATIONS = {new BlockPos(5, 108, 43), new BlockPos(5, 108, 40)};
    private static final String LIVID_START_LINE =
            "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";

    /** 10 ticks = the 0.5s re-check killer560 asked for. NoammAddons re-reads the wool every single tick
     *  and Odin re-reads it on every block update; a one-shot read was our bug. */
    private static final int RESOLVE_INTERVAL_TICKS = 10;

    private static Livid currentLivid = null;
    private static Entity lividEntity = null;
    private static int invulnTicks = 0;
    private static boolean wasOnFloor5 = false;
    private static int resolveCounter = 0;

    private LividSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(LividSolverFeature::onMessage);
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
        // killer560, 2026-09-20: "the color changes at some point and ours stays red the whole time" - we
        // read the wool once and then locked the answer in, so a clue that arrived late (or changed) was
        // never picked up. Re-resolved every RESOLVE_INTERVAL_TICKS instead.
        if (++resolveCounter < RESOLVE_INTERVAL_TICKS) {
            return;
        }
        resolveCounter = 0;
        resolve(level);
    }

    private static void resolve(ClientLevel level) {
        Livid resolved = null;
        BlockPos resolvedAt = null;
        for (BlockPos woolPos : WOOL_LOCATIONS) {
            Block block = level.getBlockState(woolPos).getBlock();
            for (Livid livid : Livid.values()) {
                if (livid.wool == block) {
                    resolved = livid;
                    resolvedAt = woolPos;
                    break;
                }
            }
            if (resolved != null) {
                break;
            }
        }
        // No wool in range right now (chunk not loaded yet, clue not placed yet): keep whatever we had
        // rather than throwing away a good answer mid-fight.
        if (resolved != null && resolved != currentLivid) {
            LOGGER.info("[Livid] Wool at {} is {} -> real Livid = \"{} Livid\" (was {})", resolvedAt,
                    resolved.wool, resolved.entityName,
                    currentLivid == null ? "unknown" : currentLivid.entityName);
            currentLivid = resolved;
            lividEntity = null;
        }
        if (currentLivid == null) {
            return;
        }
        String targetName = currentLivid.entityName + " Livid";
        if (lividEntity != null && !lividEntity.isRemoved() && lividEntity.getName().getString().equals(targetName)) {
            return;
        }
        lividEntity = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Player && entity.getName().getString().equals(targetName)) {
                lividEntity = entity;
                LOGGER.info("[Livid] Found real Livid entity \"{}\" at {}", targetName, entity.position());
                break;
            }
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        LividSolverConfig cfg = LividSolverConfig.getInstance();
        if (!cfg.isEnabled() || lividEntity == null || lividEntity.isRemoved() || currentLivid == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.hasEffect(MobEffects.BLINDNESS)) {
            return;
        }
        float[] c = currentLivid.color;
        SolverEspRender.renderOutlineBox(context, lividEntity.getBoundingBox(), c[0], c[1], c[2], 1f, 2f);
        // killer560, 2026-09-20: "add an option to draw a line to the correct livid".
        if (cfg.isShowLine() && client.player != null) {
            Vec3 target = lividEntity.getBoundingBox().getCenter();
            SolverEspRender.renderLineStrip(context, List.of(client.player.getEyePosition(), target),
                    c[0], c[1], c[2], 1f, 2f);
        }
    }

    private static void reset() {
        currentLivid = null;
        lividEntity = null;
        invulnTicks = 0;
        resolveCounter = 0;
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
        public boolean isRelevantNow() {
            LividSolverConfig cfg = LividSolverConfig.getInstance();
            String floor = DungeonState.getFloor();
            // Livid only exists on floor 5.
            return cfg.isEnabled() && cfg.isShowTimer() && floor != null && floor.endsWith("5");
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!LividSolverConfig.getInstance().isEnabled() || !LividSolverConfig.getInstance().isShowTimer()
                    || HudVisibility.hidesHud() || invulnTicks <= 0) {
                return;
            }
            String color = invulnTicks > 260 ? "§a" : invulnTicks > 130 ? "§e" : "§c";
            String text = color + "Livid: " + invulnTicks + "t";
            graphics.text(Minecraft.getInstance().font, text, x, y, 0xFFFFFFFF, false);
        }
    }
}
