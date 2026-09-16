package com.killer560.hub.dungeonalerts;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spring Boots Overlay - ported from Odin {@code features/impl/skyblock/SpringBoots.kt} (NoammAddons'
 * {@code features/impl/visual/SpringBoots.kt} uses the identical pitch/height tables). While sneaking with
 * SPRING_BOOTS on, Hypixel plays {@code NOTE_BLOCK_PLING} charge notes: pitch 0.6984127 counts as a "low"
 * (max 2), any of the 7 high pitches adds a "high"; height = {@code HEIGHTS[lows + highs]}. A
 * {@code FIREWORK_ROCKET_LAUNCH} at pitch 0.0952381 or 1.6984127 resets. Each tick, not crouching or no Spring
 * Boots resets. HUD: "Height: " + value colored §c &lt;= 13.5, §e &lt;= 22.5, §6 &lt;= 33, §a &lt;= 43.5, else §b.
 * Optional red wireframe box at the player's position + height (Odin's render).
 */
final class SpringBootsOverlay {

    private static final float[] HIGH_PITCHES = {0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f};
    private static final float LOW_PITCH = 0.6984127f;
    private static final float[] RESET_PITCHES = {0.0952381f, 1.6984127f};
    private static final float[] HEIGHTS = {
            0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
            20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
            34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
            44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
            53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f
    };

    private static float blockAmount = 0f;
    private static int highCount = 0;
    private static int lowCount = 0;

    private SpringBootsOverlay() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LocalPlayer player = client.player;
            if (player == null || blockAmount == 0f && highCount == 0 && lowCount == 0) {
                return;
            }
            if (!player.isCrouching() || !wearingSpringBoots(player)) {
                reset();
            }
        });
    }

    static void reset() {
        if (blockAmount != 0f) {
            DungeonAlertsFeature.LOGGER.debug("[DungeonAlerts] Spring Boots charge reset (was {})", blockAmount);
        }
        blockAmount = 0f;
        highCount = 0;
        lowCount = 0;
    }

    static void onSoundPacket(ClientboundSoundPacket packet) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!DungeonAlertsConfig.getInstance().springBootsEnabled || player == null) {
            return;
        }
        Identifier id = packet.getSound().value().location();
        float pitch = packet.getPitch();
        if (SoundEvents.NOTE_BLOCK_PLING.value().location().equals(id) && player.isCrouching() && wearingSpringBoots(player)) {
            if (pitch == LOW_PITCH) {
                lowCount = Math.min(lowCount + 1, 2);
            } else {
                for (float p : HIGH_PITCHES) {
                    if (p == pitch) {
                        highCount++;
                        break;
                    }
                }
            }
        } else if (SoundEvents.FIREWORK_ROCKET_LAUNCH.location().equals(id) && (pitch == RESET_PITCHES[0] || pitch == RESET_PITCHES[1])) {
            highCount = 0;
            lowCount = 0;
        } else {
            return;
        }
        blockAmount = HEIGHTS[Math.max(0, Math.min(HEIGHTS.length - 1, lowCount + highCount))];
    }

    private static boolean wearingSpringBoots(LocalPlayer player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        CustomData data = boots.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        return "SPRING_BOOTS".equals(tag.getStringOr("id", ""));
    }

    private static String colored(float blocks) {
        String code = blocks <= 13.5f ? "§c" : blocks <= 22.5f ? "§e" : blocks <= 33.0f ? "§6" : blocks <= 43.5f ? "§a" : "§b";
        return code + blocks;
    }

    static void onWorldRender(LevelRenderContext context) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!DungeonAlertsConfig.getInstance().springBootsEnabled || !DungeonAlertsConfig.getInstance().springBootsBox
                || blockAmount == 0f || player == null) {
            return;
        }
        Vec3 pos = player.position().add(0, blockAmount, 0);
        WorldRenderUtils.renderOutlineBox(context, AABB.unitCubeFromLowerCorner(pos), 1f, 0.33f, 0.33f, 1f, 2f);
    }

    static final HudElement HUD = new HudElement() {
        @Override
        public String id() {
            return "spring_boots";
        }

        @Override
        public String displayName() {
            return "Spring Boots";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 120;
        }

        @Override
        public int width() {
            return 80;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public boolean isRelevantNow() {
            return DungeonAlertsConfig.getInstance().springBootsEnabled && com.killer560.hub.util.SkyblockGate.allows();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            if (!example && (!DungeonAlertsConfig.getInstance().springBootsEnabled || blockAmount == 0f)) {
                return;
            }
            var font = Minecraft.getInstance().font;
            String label = "Height: ";
            graphics.text(font, label, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, colored(example ? 33.0f : blockAmount), x + 1 + font.width(label), y + 1, 0xFFFFFFFF, true);
        }
    };
}
