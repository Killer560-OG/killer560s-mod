package com.killer560.hub.ragaxe;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Rag Axe - Ragnarock Axe cast detection, channel / buff / cooldown timers and the built-in "rag now" prompts
 * for F7-M7 and F5/M5. This is the whole Ragnarock feature; the old
 * {@code dungeonalerts.RagnarockAlert} was folded into this package in the same batch (one cast detector, one
 * set of HUD ids - {@code ragnarock_timer} and {@code ragnarock_m7_alert} are deliberately kept so existing HUD
 * editor positions survive the move).
 * <p>
 * Sources: Odin {@code features/impl/skyblock/Ragnarock.kt} (detection, cancel alert, 1.5x strength),
 * NoammAddons {@code features/impl/dungeon/Ragnarock.kt} (M7 "rag" overlay + its 7-note pling arpeggio, local
 * copy C:\Users\Hunter\noammaddonsmod), hypixelskyblock.minecraft.wiki/w/Ragnarock (3 s channel, 10 s buff,
 * 20 s cooldown, 500 mana). Skytils and SkyHanni have no Ragnarock feature to port from.
 * <p>
 * Everything ships OFF. Purely informational: no clicking, no aiming, nothing sent to the server except the
 * optional party-chat strength line the user turns on themselves.
 */
public final class RagAxeFeature {

    static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ragaxe");

    /** NoammAddons' exact delays/pitches for the M7 rag cue (vol 0.25). */
    private static final long[] CUE_DELAYS_MS = {0L, 120L, 240L, 400L, 520L, 640L, 780L};
    private static final float[] CUE_PITCHES = {1.22f, 1.13f, 1.29f, 1.60f, 1.60f, 1.72f, 1.89f};
    /** NoammAddons shows its "rag" text for 40 ticks. */
    private static final int PROMPT_TICKS = 40;

    private static int promptTicks = 0;
    private static long cueStartMs = -1L;
    private static int cuePlayed = 0;
    private static Object lastLevel = null;

    private RagAxeFeature() {
    }

    public static void register() {
        RagAxeConfig.getInstance();
        RagAxePrompts.register();
        RagAxePrompts.reset();
        ChatObserver.subscribe(RagAxeFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        for (HudElement element : hudElements()) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    Identifier.fromNamespaceAndPath("killer560smod", "ragaxe_" + element.id()),
                    (graphics, deltaTracker) -> drawInGame(graphics, element));
        }
        LOGGER.info("[RagAxe] Registered (master and all prompts default OFF)");
    }

    /** The lead registers these into this mod's {@link HudElementRegistry} so the HUD editor can move them. */
    public static List<HudElement> hudElements() {
        return List.of(TIMERS_HUD, PROMPT_HUD);
    }

    private static void onChat(Component message) {
        RagAxeState.onChat(message);
        RagAxePrompts.onChat(message);
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            RagAxeState.reset();
            RagAxePrompts.reset();
            promptTicks = 0;
            cueStartMs = -1L;
        }
        if (promptTicks > 0) {
            promptTicks--;
        }
        if (cueStartMs >= 0) {
            long elapsed = System.currentTimeMillis() - cueStartMs;
            while (cuePlayed < CUE_DELAYS_MS.length && elapsed >= CUE_DELAYS_MS[cuePlayed]) {
                playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.25f, CUE_PITCHES[cuePlayed]);
                cuePlayed++;
            }
            if (cuePlayed >= CUE_DELAYS_MS.length) {
                cueStartMs = -1L;
            }
        }
        RagAxeState.tick();
        RagAxePrompts.clientTick();
    }

    /** Entry point from {@code dungeonalerts.DungeonAlertsPackets.onSound} (main thread, Skyblock-gated).
     *  Routed through that existing bridge on purpose - one sound-packet mixin for the whole mod. */
    public static void onSoundPacket(ClientboundSoundPacket packet) {
        RagAxeState.onSoundPacket(packet);
    }

    // ------------------------------------------------------------------ output helpers

    static void showPrompt(String what, boolean sound, boolean title) {
        LOGGER.info("[RagAxe] Prompt: {}", what);
        promptTicks = PROMPT_TICKS;
        if (sound) {
            cueStartMs = System.currentTimeMillis();
            cuePlayed = 0;
        }
        if (title) {
            showTitle(RagAxeConfig.getInstance().getPromptText(), "", 0, PROMPT_TICKS, 5);
        }
    }

    /** Odin's {@code alert()}: title, 0/20/5 ticks, NOTE_BLOCK_PLING vol 1 pitch 1. */
    static void alert(String title) {
        showTitle(title, "", 0, 20, 5);
        playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
    }

    private static void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        Minecraft client = Minecraft.getInstance();
        client.gui.setTimes(fadeIn, stay, fadeOut);
        client.gui.setTitle(Component.literal(title));
        client.gui.setSubtitle(Component.literal(subtitle));
    }

    private static void playSound(SoundEvent sound, float volume, float pitch) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume)));
    }

    static void sendPartyChat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand("pc " + message);
        }
    }

    // ------------------------------------------------------------------ HUD

    private static boolean editorOpen() {
        return Minecraft.getInstance().screen instanceof HudEditorScreen;
    }

    private static void drawInGame(GuiGraphicsExtractor graphics, HudElement element) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null || client.options.hideGui || !SkyblockGate.allows()) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(element);
        float scale = HudElementRegistry.resolveScale(element);
        graphics.pose().pushMatrix();
        graphics.pose().translate(pos[0], pos[1]);
        graphics.pose().scale(scale, scale);
        element.render(graphics, 0, 0);
        graphics.pose().popMatrix();
    }

    /** Up to three optional lines, each its own setting: channel wind-up, buff remaining, cooldown remaining. */
    public static final HudElement TIMERS_HUD = new HudElement() {
        @Override
        public String id() {
            return "ragnarock_timer";
        }

        @Override
        public String displayName() {
            return "Ragnarock Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 100;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 30;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            RagAxeConfig cfg = RagAxeConfig.getInstance();
            boolean example = editorOpen();
            if (!example && !cfg.isEnabled()) {
                return;
            }
            int row = 0;
            if (cfg.isChannelTimer()) {
                long left = example ? 2_400L : RagAxeState.channelRemainingMs();
                if (left > 0) {
                    line(graphics, x, y + row * 10, "Rag in: ", left);
                    row++;
                }
            }
            if (cfg.isBuffTimer()) {
                long left = example ? 7_300L : RagAxeState.buffRemainingMs();
                if (left > 0) {
                    line(graphics, x, y + row * 10, "Ragnarock: ", left);
                    row++;
                }
            }
            if (cfg.isCooldownTimer()) {
                long left = example ? 12_100L : RagAxeState.cooldownRemainingMs();
                if (left > 0) {
                    line(graphics, x, y + row * 10, "Rag CD: ", left);
                }
            }
        }

        private void line(GuiGraphicsExtractor graphics, int x, int y, String label, long remainingMs) {
            Font font = Minecraft.getInstance().font;
            graphics.text(font, label, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, String.format(Locale.US, "%.1fs", remainingMs / 1000.0), x + 1 + font.width(label),
                    y + 1, 0xFF000000 | ModChat.LIGHT_ORANGE, true);
        }
    };

    /** NoammAddons' centred "rag" text, now driven by every prompt rather than just the Wither King line. */
    public static final HudElement PROMPT_HUD = new HudElement() {
        @Override
        public String id() {
            return "ragnarock_m7_alert";
        }

        @Override
        public String displayName() {
            return "Rag Prompt";
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
            return 40;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            RagAxeConfig cfg = RagAxeConfig.getInstance();
            boolean example = editorOpen();
            if (!example && (promptTicks <= 0 || !cfg.isEnabled())) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, cfg.getPromptText(), x + 1, y + 1, 0xFFFFFFFF, true);
        }
    };
}
