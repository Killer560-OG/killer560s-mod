package com.killer560.hub.dungeonalerts;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McRender;

/**
 * Entry point + shared helpers for the Dungeon Alerts pack. {@link #register()} wires every sub-feature,
 * the world-render pass, and draws this pack's HUD elements in-game through Fabric's own HUD element API
 * (no Gui mixin needed). The {@link HudElement}s themselves still go into this mod's
 * {@link HudElementRegistry} (see {@link #hudElements()}) so the HUD editor can move/scale them.
 */
public final class DungeonAlertsFeature {

    static final Logger LOGGER = ModLog.get("killer560smod-dungeonalerts");
    private static Object lastLevel = null;

    private DungeonAlertsFeature() {
    }

    public static void register() {
        DungeonAlertsConfig.getInstance();
        ShadowAssassinAlert.register();
        SecretSound.register();
        TerracottaTimer.register();
        SpringBootsOverlay.register();
        ClassColors.register();
        RoomAlerts.register();

        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DungeonAlertsFeature", client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                TerracottaTimer.onWorldChange();
                SpringBootsOverlay.reset();
                ClassColors.onWorldChange();
                RoomAlerts.onWorldChange();
            }
        }));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(DungeonAlertsFeature::onWorldRender);

        for (HudElement element : hudElements()) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    Identifier.fromNamespaceAndPath("killer560smod", "dungeonalerts_" + element.id()),
                    (graphics, deltaTracker) -> drawInGame(graphics, element));
        }
        LOGGER.info("[DungeonAlerts] Registered (all features default OFF)");
    }

    /** The lead registers these into {@link HudElementRegistry} (one call each). Same instances are looked
     *  up by id when drawing in-game, so positions come from the HUD editor. */
    public static List<HudElement> hudElements() {
        return List.of(SpringBootsOverlay.HUD, RoomAlerts.HUD);
    }

    private static void drawInGame(GuiGraphicsExtractor graphics, HudElement element) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide these (killer560), the HUD editor still does.
        if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client) || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        // Position/scale come from HudConfig by id, so this works even before the element is in the editor list.
        HudElementRegistry.drawAt(graphics, element);
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        TerracottaTimer.onWorldRender(context);
        SpringBootsOverlay.onWorldRender(context);
        ClassColors.onWorldRender(context);
    }

    // ---- shared helpers ----

    static boolean isEditorOpen() {
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
    }

    /** @return floor number 0-7 from the sidebar floor string ("F6" -> 6, "E" -> 0), or -1 outside a run. */
    static int floorNumber() {
        String floor = DungeonState.getFloor();
        if (floor == null || floor.isEmpty()) {
            return -1;
        }
        char last = floor.charAt(floor.length() - 1);
        return Character.isDigit(last) ? last - '0' : 0;
    }

    static boolean isMasterMode() {
        String floor = DungeonState.getFloor();
        return floor != null && floor.startsWith("M");
    }

    static boolean inBoss() {
        return LiveMapFeature.isInBoss();
    }

    /** Vanilla title/subtitle with explicit fade/stay/fade ticks. */
    static void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        Minecraft client = Minecraft.getInstance();
        McCompat.setTimes(client, fadeIn, stay, fadeOut);
        McCompat.setTitle(client, Component.literal(title));
        McCompat.setSubtitle(client, Component.literal(subtitle));
    }

    /** Local UI sound, same call Odin's {@code playSoundAtPlayer} / Essential's {@code USound.playSoundStatic} make. */
    static void playSound(SoundEvent sound, float volume, float pitch) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume)));
    }

    /** Billboarded, see-through world text - same transform BloodCampFeature.renderTimerText uses (proven on
     *  26.1.2). Legacy section-sign color codes in {@code text} are honored by the font. */
    static void renderWorldText(LevelRenderContext context, String text, double x, double y, double z, float scale) {
        renderWorldText(context, Component.literal(text), x, y, z, scale);
    }

    static void renderWorldText(LevelRenderContext context, Component text, double x, double y, double z, float scale) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Vec3 cam = McRender.cameraPos(context);
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
        poseStack.mulPose(McRender.cameraRotation(context));
        float s = 0.025f * scale;
        poseStack.scale(s, -s, s);
        float width = font.width(text);
        int background = (int) (0.25f * 255f) << 24;
        McRender.drawText(context, font, text, -width / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false, poseStack, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);
        poseStack.popPose();
    }
}
