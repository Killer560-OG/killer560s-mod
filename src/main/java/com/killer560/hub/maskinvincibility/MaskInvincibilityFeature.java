package com.killer560.hub.maskinvincibility;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mask/pet invincibility-proc timers - killer560's "mask invulnerability cooldown timers from
 * Noamm/odin" request. Real chat trigger lines and real default durations ported directly from Odin's
 * own confirmed {@code InvincibilityTimer.kt}:
 * <ul>
 *   <li>Spirit Mask: "Second Wind Activated! Your Spirit Mask saved your life!" - 3s active, 30s cooldown</li>
 *   <li>Bonzo's Mask: "Your [.] Bonzo's Mask saved your life!" - 3s active, 180s cooldown</li>
 *   <li>Phoenix Pet: "Your Phoenix Pet saved you from certain death!" - 4s active, 60s cooldown</li>
 * </ul>
 * <b>Simplification vs. Odin:</b> Odin reads Bonzo's Mask's REAL remaining cooldown straight from the
 * item's own tooltip lore ("Cooldown: Ns") after each proc, since Hypixel actually varies it slightly.
 * This codebase has no established pattern yet for reading item tooltip lore off an equipped item, so
 * this uses the fixed 180s default instead - close, but correct that once lore-reading exists elsewhere
 * in this mod to reuse.
 */
public final class MaskInvincibilityFeature {

    private enum Type {
        SPIRIT(Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!$"), 60, 30 * 20, "Spirit Mask"),
        BONZO(Pattern.compile("^Your (?:. )?Bonzo's Mask saved your life!$"), 60, 180 * 20, "Bonzo's Mask"),
        PHOENIX(Pattern.compile("^Your Phoenix Pet saved you from certain death!$"), 80, 60 * 20, "Phoenix Pet");

        final Pattern pattern;
        final int activeTicks;
        final int cooldownTicks;
        final String label;

        Type(Pattern pattern, int activeTicks, int cooldownTicks, String label) {
            this.pattern = pattern;
            this.activeTicks = activeTicks;
            this.cooldownTicks = cooldownTicks;
            this.label = label;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-masktimers");
    private static final Map<Type, Integer> activeRemaining = new EnumMap<>(Type.class);
    private static final Map<Type, Integer> cooldownRemaining = new EnumMap<>(Type.class);
    private static boolean wasInDungeon = false;

    private MaskInvincibilityFeature() {
        for (Type t : Type.values()) {
            activeRemaining.put(t, 0);
            cooldownRemaining.put(t, 0);
        }
    }

    public static void register() {
        for (Type t : Type.values()) {
            activeRemaining.put(t, 0);
            cooldownRemaining.put(t, 0);
        }
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(MaskInvincibilityFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void onChatMessage(Component message) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): matched the raw un-stripped
        // string - real Hypixel chat lines are confirmed (DungeonState's own BOSS_START_PATTERN fix) to
        // embed §-codes mid-word, which can silently break a regex match.
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        for (Type t : Type.values()) {
            if (t.pattern.matcher(raw).matches()) {
                LOGGER.info("[MaskTimers] {} PROC detected: \"{}\" (previous cooldownLeft={}t) -> active {}t, cooldown {}t, autoSwap={}",
                        t.label, raw, cooldownRemaining.getOrDefault(t, 0), t.activeTicks, t.cooldownTicks, cfg.isAutoSwapEnabled());
                activeRemaining.put(t, t.activeTicks);
                cooldownRemaining.put(t, t.cooldownTicks);
                if (cfg.isAnnounceInChat()) {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null) {
                        client.player.sendSystemMessage(ModChat.line("Mask", ModChat.value(t.label), ModChat.text(" procced!")));
                    }
                }
                if (cfg.isAutoSwapEnabled() && (t == Type.SPIRIT || t == Type.BONZO)) {
                    trySwapMask(t == Type.SPIRIT ? Type.BONZO : Type.SPIRIT);
                }
                return;
            }
        }
    }

    /** Right-clicking a helmet-type item (found by display name, not a guessed NBT id) equips it
     *  directly to your head slot - real, long-stable vanilla behavior that works with your inventory
     *  closed, unlike a container-input shift-click swap. Your previous helmet goes back into whatever
     *  hotbar slot you swapped from, same as manually pressing the number key and right-clicking would
     *  do. Only fires when the OTHER mask (not the one that just procced) is actually sitting in your
     *  hotbar, off cooldown. */
    private static void trySwapMask(Type other) {
        if (cooldownRemaining.getOrDefault(other, 0) > 0) {
            LOGGER.info("[MaskTimers] Auto-swap to {} skipped: it is on cooldown ({}t left)", other.label, cooldownRemaining.getOrDefault(other, 0));
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }
        ItemStack worn = client.player.getItemBySlot(EquipmentSlot.HEAD);
        if (worn.isEmpty() || !worn.getHoverName().getString().contains(other == Type.SPIRIT ? "Bonzo" : "Spirit")) {
            LOGGER.info("[MaskTimers] Auto-swap to {} skipped: worn helmet \"{}\" isn't the mask that just procced",
                    other.label, worn.isEmpty() ? "(none)" : worn.getHoverName().getString());
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack item = client.player.getInventory().getItem(i);
            if (!item.isEmpty() && item.getHoverName().getString().contains(other.label.split(" ")[0])) {
                LOGGER.info("[MaskTimers] Auto-swap: equipping \"{}\" from hotbar slot {}", item.getHoverName().getString(), i);
                client.player.getInventory().setSelectedSlot(i);
                client.player.connection.send(new ServerboundSetCarriedItemPacket(i));
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                return;
            }
        }
        LOGGER.info("[MaskTimers] Auto-swap to {} failed: no matching item in hotbar", other.label);
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to also zero
            // cooldownRemaining here, but a real mask/pet cooldown is a real Hypixel-side item timer
            // independent of location - it keeps ticking whether you're in a dungeon or not. Zeroing it
            // on dungeon exit made the HUD claim "Ready" the instant a run ended even with, say, 150s of
            // a real 180s Bonzo's Mask cooldown still remaining. The active (currently-invincible) window
            // genuinely does end when leaving, so that part is still cleared.
            for (Type t : Type.values()) {
                activeRemaining.put(t, 0);
            }
        }
        wasInDungeon = inDungeon;

        if (!MaskInvincibilityConfig.getInstance().isEnabled()) {
            return;
        }
        for (Type t : Type.values()) {
            int active = activeRemaining.get(t);
            if (active > 0) {
                activeRemaining.put(t, active - 1);
            }
            if (active == 1) {
                LOGGER.info("[MaskTimers] {} invincibility window ENDED", t.label);
            }
            int cooldown = cooldownRemaining.get(t);
            if (cooldown > 0) {
                cooldownRemaining.put(t, cooldown - 1);
            }
            if (cooldown == 1) {
                LOGGER.info("[MaskTimers] {} cooldown READY", t.label);
            }
        }
    }

    private static boolean shown(Type t) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        return switch (t) {
            case SPIRIT -> cfg.isShowSpirit();
            case BONZO -> cfg.isShowBonzo();
            case PHOENIX -> cfg.isShowPhoenix();
        };
    }

    public static final class MaskInvincibilityHudElement implements HudElement {
        @Override
        public String id() {
            return "mask_invincibility";
        }

        @Override
        public String displayName() {
            return "Mask Invincibility Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 440;
        }

        @Override
        public int width() {
            return 150;
        }

        @Override
        public int height() {
            return 12 * (int) java.util.Arrays.stream(Type.values()).filter(MaskInvincibilityFeature::shown).count();
        }

        @Override
        public boolean isRelevantNow() {
            return MaskInvincibilityConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MaskInvincibilityConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            int lineY = y;
            for (Type t : Type.values()) {
                if (!shown(t)) {
                    continue;
                }
                int active = activeRemaining.get(t);
                int cooldown = cooldownRemaining.get(t);
                String status = active > 0
                        ? String.format(Locale.US, "%.1fs", active / 20f)
                        : cooldown > 0
                        ? String.format(Locale.US, "%.1fs", cooldown / 20f)
                        : "Ready";
                int color = active > 0 ? 0xFFFFAA00 : cooldown > 0 ? 0xFFFF5555 : 0xFF55FF55;
                graphics.text(Minecraft.getInstance().font, t.label + ": " + status, x, lineY, color, false);
                lineY += 12;
            }
        }
    }
}
