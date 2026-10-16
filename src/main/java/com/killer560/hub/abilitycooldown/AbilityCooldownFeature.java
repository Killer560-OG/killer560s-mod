package com.killer560.hub.abilitycooldown;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.util.List;
import java.util.Locale;

/**
 * Automatic item/ability cooldown HUD - gap-analysis item 1.9 ("Automatic item / ability cooldown HUD").
 *
 * <p>It detects which ability you actually used (sound / action bar / chat, see {@link AbilityCooldownState}) and
 * counts down a real, ported cooldown from {@link ItemAbility}. (The manual, keybind-started Ability Timers were
 * removed on 2026-10-11 at killer560's request: "Remove the ability timers setting that is unneeded.")
 *
 * <p>Not duplicated here: Ragnarock Axe ({@code com.killer560.hub.ragaxe}) and the Spirit/Bonzo/Phoenix
 * death-save procs ({@code com.killer560.hub.maskinvincibility}) - see {@link ItemAbility}'s class doc.
 *
 * <p>Ships OFF. The HUD element is movable in the HUD editor like every other one, and "Text Only" drops
 * the colour swatch so it renders as a bare text list.
 */
public final class AbilityCooldownFeature {

    /** HUD element id - also the key its position/scale are stored under in {@code killer560smod-hud.json}. */
    public static final String HUD_ID = "ability_cooldowns";

    private AbilityCooldownFeature() {
    }

    public static void register() {
        // Action bar: MODIFY_GAME with overlay = true, the same path PlayerStatsFeature reads the real
        // Hypixel action bar on. The message is always returned unchanged - this never rewrites the bar.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) {
                try {
                    AbilityCooldownState.onActionBar(message.getString());
                } catch (RuntimeException ignored) {
                    // Never let a regex bug eat the player's action bar.
                }
            }
            return message;
        });

        // Chat (Creeper Veil): ChatObserver, not Fabric's CHAT/GAME - Odin/NoammAddons/Skyblocker can cancel
        // a server line via ALLOW_GAME and re-add their own copy straight to ChatComponent.
        ChatObserver.subscribe(AbilityCooldownState::onChat);

        // "You clicked an item just now" - the 400 ms gate that keeps a teammate's ability sound from
        // starting your countdown (SkyHanni's ItemAbilityCooldown.onItemClick).
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                AbilityCooldownState.noteItemClick();
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                AbilityCooldownState.noteItemClick();
            }
            return InteractionResult.PASS;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AbilityCooldownState.reset());
    }

    /**
     * Movable cooldown list. Sound detection reaches {@link AbilityCooldownState#onSoundPacket} through the
     * existing {@code DungeonAlertsPackets.onSound} bridge - see this feature's registration notes.
     */
    public static final class CooldownHudElement implements HudElement {

        private static final int COLOR_SOON = 0xFFFF5555;
        private static final int COLOR_MID = 0xFFFFAA00;
        private static final int COLOR_FAR = 0xFFFFFFFF;
        private static final int COLOR_READY = 0xFF55FF55;

        @Override
        public String id() {
            return HUD_ID;
        }

        @Override
        public String displayName() {
            return "Ability Cooldowns";
        }

        @Override
        public int defaultX() {
            // Second column (x=200): the old 10/320 sat exactly on Split Timers.
            return 200;
        }

        @Override
        public int defaultY() {
            return 200;
        }

        @Override
        public int width() {
            // Swatch (unless Text Only) + the widest drawn line (2026-10-07 box audit: it was a fixed 150); 150 with
            // nothing on cooldown, so the HUD editor still has something to grab.
            List<Line> lines = lines();
            if (lines.isEmpty()) {
                return 150;
            }
            int indent = AbilityCooldownConfig.getInstance().isTextOnly() ? 0 : 12;
            int w = 1;
            for (Line line : lines) {
                w = Math.max(w, indent + Minecraft.getInstance().font.width(line.text));
            }
            return w;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(lines().size(), 12);
        }

        @Override
        public boolean isEnabledInSettings() {
            return AbilityCooldownConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            boolean textOnly = cfg.isTextOnly();
            int lineY = y;
            for (Line line : lines()) {
                if (!textOnly) {
                    graphics.fill(x, lineY + 1, x + 8, lineY + 9, line.color);
                }
                graphics.text(font, line.text, textOnly ? x : x + 12, lineY,
                        textOnly ? line.color : COLOR_FAR, false);
                lineY += 12;
            }
            // lineY only moves if a row was actually drawn, so this is the draw itself reporting in.
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }

        private record Line(String text, int color) {
        }

        private static List<Line> lines() {
            AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
            java.util.List<Line> out = new java.util.ArrayList<>();
            for (ItemAbility ability : AbilityCooldownState.running()) {
                long ms = AbilityCooldownState.remainingMs(ability);
                double remaining = ms / 1000.0;
                int color = cfg.isColorByRemaining() ? colorFor(remaining) : COLOR_FAR;
                out.add(cachedLine(ability, ms, remaining, color));
                if (out.size() >= cfg.getMaxLines()) {
                    return out;
                }
            }
            if (cfg.isShowWhenReady()) {
                for (ItemAbility ability : AbilityCooldownState.readyAgain()) {
                    out.add(new Line(ability.label() + ": READY", COLOR_READY));
                    if (out.size() >= cfg.getMaxLines()) {
                        return out;
                    }
                }
            }
            return out;
        }

        // One row per ability, kept while its shown tenth and colour are the same: the text was a String.format per
        // running ability per frame, and again for width()/height() (2026-10-15). "%.1f" rounds the exact decimal
        // ms/1000 half up, so every ms in ((t - 0.05) s, (t + 0.05) s] shows t: the tenth key (ms + 50) / 100 is that t.
        private static final Line[] ROW = new Line[ItemAbility.values().length];
        private static final long[] ROW_KEY = new long[ItemAbility.values().length];

        private static Line cachedLine(ItemAbility ability, long ms, double remaining, int color) {
            int i = ability.ordinal();
            long key = ((ms + 50) / 100) * 0x1_0000_0000L + (color & 0xFFFFFFFFL);
            Line line = ROW[i];
            if (line == null || ROW_KEY[i] != key || !Minecraft.getInstance().isSameThread()) {
                line = new Line(String.format(Locale.US, "%s: %.1fs", ability.label(), remaining), color);
                if (Minecraft.getInstance().isSameThread()) {
                    ROW[i] = line;
                    ROW_KEY[i] = key;
                }
            }
            return line;
        }

        private static int colorFor(double remainingSeconds) {
            if (remainingSeconds <= 1.0) {
                return COLOR_SOON;
            }
            return remainingSeconds <= 3.0 ? COLOR_MID : COLOR_FAR;
        }
    }

    /** Entry point for the existing {@code DungeonAlertsPackets.onSound} bridge - same shape as
     *  {@code RagAxeFeature.onSoundPacket}, so the bridge gets one more guarded line and no new mixin. */
    public static void onSoundPacket(net.minecraft.network.protocol.game.ClientboundSoundPacket packet) {
        AbilityCooldownState.onSoundPacket(packet);
    }

    /** Small convenience for the settings tab's summary line. */
    public static Component describeTable() {
        return Component.literal("§7" + ItemAbility.values().length + " abilities, cooldowns ported from SkyHanni");
    }
}
