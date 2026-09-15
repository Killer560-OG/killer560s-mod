package com.killer560.hub.dungeonalerts;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ragnarock - ported from Odin {@code features/impl/skyblock/Ragnarock.kt} and NoammAddons (26.1.2 upstream)
 * {@code features/impl/dungeon/Ragnarock.kt}:
 * <ul>
 * <li>Cancel alert (both): chat "Ragnarock was cancelled due to (being hit|taking damage)!" -&gt; Odin's
 * {@code alert("§cRagnarock Cancelled!")} = title, times 0/20/5, NOTE_BLOCK_PLING vol 1 pitch 1.
 * <li>Cast alert (Odin): a wolf death sound (any wolf sound variant's adult death sound) at pitch exactly
 * 1.4920635 while holding RAGNAROCK_AXE -&gt; {@code alert("§aCasted Rag")}.
 * <li>Strength gained (Odin): "Gained strength: N", N = (lore "Strength: +X") * 1.5 truncated; optional
 * party-chat announce "Gained strength from Ragnarock: N" (Odin, default off).
 * <li>M7 Dragon alert (Noamm, default off): on "[BOSS] Wither King: I no longer wish to fight, but I know that
 * will not stop you." (skipped for Tank/Healer) show "rag" for 40 ticks and play the 7-note pling arpeggio
 * (vol 0.25) at Noamm's exact delays/pitches.
 * <li>Buff timer + "Ragnarock Ended" alert: NOT in either reference - this mod's own, using the wiki's
 * confirmed 10 s buff duration (hypixelskyblock.minecraft.wiki/w/Ragnarock_Axe), started at the cast sound.
 * </ul>
 */
final class RagnarockAlert {

    private static final Pattern CANCEL = Pattern.compile("Ragnarock was cancelled due to (?:being hit|taking damage)!");
    private static final Pattern STRENGTH = Pattern.compile("Strength: \\+(\\d+)");
    private static final float CAST_PITCH = 1.4920635f;
    private static final String M7_RAG_MESSAGE = "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you.";
    private static final long[] M7_DELAYS_MS = {0L, 120L, 240L, 400L, 520L, 640L, 780L};
    private static final float[] M7_PITCHES = {1.22f, 1.13f, 1.29f, 1.60f, 1.60f, 1.72f, 1.89f};
    private static final long BUFF_MS = 10_000L;

    private static long buffEndsAtMs = 0L;
    private static int m7AlertTicks = 0;
    private static long m7SoundStartMs = -1L;
    private static int m7SoundsPlayed = 0;

    private RagnarockAlert() {
    }

    static void register() {
        ChatObserver.subscribe(RagnarockAlert::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (m7AlertTicks > 0) {
            m7AlertTicks--;
        }
        if (m7SoundStartMs >= 0) {
            long elapsed = System.currentTimeMillis() - m7SoundStartMs;
            while (m7SoundsPlayed < M7_DELAYS_MS.length && elapsed >= M7_DELAYS_MS[m7SoundsPlayed]) {
                DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.25f, M7_PITCHES[m7SoundsPlayed]);
                m7SoundsPlayed++;
            }
            if (m7SoundsPlayed >= M7_DELAYS_MS.length) {
                m7SoundStartMs = -1L;
            }
        }
        if (buffEndsAtMs > 0 && System.currentTimeMillis() >= buffEndsAtMs) {
            buffEndsAtMs = 0L;
            DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Ragnarock buff ended");
            if (cfg.ragEnabled && cfg.ragEndAlert) {
                alert("§cRagnarock Ended");
            }
        }
    }

    static void onWorldChange() {
        buffEndsAtMs = 0L;
        m7AlertTicks = 0;
        m7SoundStartMs = -1L;
    }

    private static void alert(String title) {
        DungeonAlertsFeature.showTitle(title, "", 0, 20, 5);
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
    }

    private static void onChat(Component message) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.ragEnabled) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (cfg.ragM7Alert && DungeonState.isF7OrM7() && M7_RAG_MESSAGE.equals(plain)) {
            DungeonClass self = ClassColors.selfClass();
            if (self == DungeonClass.TANK || self == DungeonClass.HEALER) {
                return;
            }
            DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] M7 dragon Rag alert (class={})", self);
            m7AlertTicks = 40;
            m7SoundStartMs = System.currentTimeMillis();
            m7SoundsPlayed = 0;
        } else if (cfg.ragCancelAlert && CANCEL.matcher(plain).matches()) {
            DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Ragnarock cancelled");
            buffEndsAtMs = 0L;
            alert("§cRagnarock Cancelled!");
        }
    }

    static void onSoundPacket(ClientboundSoundPacket packet) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!cfg.ragEnabled || player == null || packet.getPitch() != CAST_PITCH) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!"RAGNAROCK_AXE".equals(skyblockId(held)) || !isWolfDeath(packet.getSound().value().location())) {
            return;
        }
        int gained = (int) (strength(held) * 1.5);
        DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Ragnarock cast detected (strength gained {})", gained);
        buffEndsAtMs = System.currentTimeMillis() + BUFF_MS;
        if (cfg.ragCastAlert) {
            alert("§aCasted Rag");
        }
        if (cfg.ragStrengthMessage) {
            ModChat.send("Ragnarock", ModChat.text("Gained strength: "), ModChat.value(String.valueOf(gained)));
            if (cfg.ragAnnounceStrength) {
                DungeonAlertsFeature.sendPartyChat("Gained strength from Ragnarock: " + gained);
            }
        }
    }

    private static boolean isWolfDeath(Identifier id) {
        for (WolfSoundVariant variant : SoundEvents.WOLF_SOUNDS.values()) {
            if (variant.adultSounds().deathSound().value().location().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String skyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    private static int strength(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && plain.startsWith("Strength:")) {
                Matcher m = STRENGTH.matcher(plain);
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            }
        }
        return 0;
    }

    static final HudElement TIMER_HUD = new HudElement() {
        @Override
        public String id() {
            return "ragnarock_timer";
        }

        @Override
        public String displayName() {
            return "Ragnarock Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 134;
        }

        @Override
        public int width() {
            return 90;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
            boolean example = DungeonAlertsFeature.isEditorOpen();
            long left = buffEndsAtMs - System.currentTimeMillis();
            if (!example && (!cfg.ragEnabled || !cfg.ragBuffTimer || buffEndsAtMs == 0L || left <= 0)) {
                return;
            }
            double seconds = example ? 7.3 : left / 1000.0;
            var font = Minecraft.getInstance().font;
            String label = "Ragnarock: ";
            graphics.text(font, label, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, String.format(Locale.US, "%.1fs", seconds), x + 1 + font.width(label), y + 1,
                    0xFF000000 | ModChat.LIGHT_ORANGE, true);
        }
    };

    static final HudElement M7_HUD = new HudElement() {
        @Override
        public String id() {
            return "ragnarock_m7_alert";
        }

        @Override
        public String displayName() {
            return "Ragnarock M7 Alert";
        }

        @Override
        public int defaultX() {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - 10;
        }

        @Override
        public int defaultY() {
            int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            return (int) (h / 2f - h * 0.056f);
        }

        @Override
        public int width() {
            return 20;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            if (!example && (m7AlertTicks <= 0 || !DungeonAlertsConfig.getInstance().ragM7Alert)) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, "rag", x + 1, y + 1, 0xFFFFFFFF, true);
        }
    };
}
