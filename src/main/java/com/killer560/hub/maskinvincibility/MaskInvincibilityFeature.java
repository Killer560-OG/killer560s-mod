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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        SPIRIT(Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!$"), 60, 30 * 20,
                "Spirit Mask", MaskSwapper.Target.SPIRIT),
        BONZO(Pattern.compile("^Your (?:. )?Bonzo's Mask saved your life!$"), 60, 180 * 20,
                "Bonzo's Mask", MaskSwapper.Target.BONZO),
        PHOENIX(Pattern.compile("^Your Phoenix Pet saved you from certain death!$"), 80, 60 * 20,
                "Phoenix Pet", MaskSwapper.Target.PHOENIX);

        final Pattern pattern;
        final int activeTicks;
        final int cooldownTicks;
        final String label;
        final MaskSwapper.Target target;

        Type(Pattern pattern, int activeTicks, int cooldownTicks, String label, MaskSwapper.Target target) {
            this.pattern = pattern;
            this.activeTicks = activeTicks;
            this.cooldownTicks = cooldownTicks;
            this.label = label;
            this.target = target;
        }

        static Type of(MaskSwapper.Target target) {
            for (Type t : values()) {
                if (t.target == target) {
                    return t;
                }
            }
            return null;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-masktimers");
    private static final Map<Type, Integer> activeRemaining = new EnumMap<>(Type.class);
    private static final Map<Type, Integer> cooldownRemaining = new EnumMap<>(Type.class);
    /** Last real stack seen for each type, so the icon keeps rendering after the mask leaves your inventory. */
    private static final Map<Type, ItemStack> iconCache = new EnumMap<>(Type.class);
    private static int iconScanCountdown = 0;
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
        MaskSwapper.register();
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
                if (cfg.isAnnounceToParty()) {
                    sendPartyChat(t.label + " popped!");
                }
                if (cfg.isAutoSwapEnabled()) {
                    trySwapMask();
                }
                return;
            }
        }
    }

    /**
     * Hands the swap to {@link MaskSwapper}, the mod's one death-item swap mechanism.
     * <p>
     * Rewritten 2026-09-21 (killer560): the old version needed the other mask in your <i>hotbar</i> and
     * right-clicked it there. It now runs {@code /stats} and clicks the mask in the menu, or throws a rod for
     * Phoenix, with a real delay between each step. Order is his: Spirit, Phoenix, Bonzo. Exactly one attempt
     * per proc - {@code request} refusing is a normal answer, never something to retry.
     */
    private static void trySwapMask() {
        MaskSwapper.Target target = MaskSwapper.pickTarget(MaskSwapper.DEFAULT_ORDER, t -> {
            Type type = Type.of(t);
            return type == null || cooldownRemaining.getOrDefault(type, 0) > 0;
        });
        if (target == null) {
            LOGGER.info("[MaskTimers] Auto-swap: nothing in order Spirit > Phoenix > Bonzo is ready and not already on.");
            return;
        }
        MaskSwapper.request(target, "Mask Timers");
    }

    /** {@code /pc <message>} the same way {@code blessings.BlessingTracker#sendPartyMessage} sends it. */
    private static void sendPartyChat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand("pc " + message);
        }
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
        if (MaskInvincibilityConfig.getInstance().isShowItemIcons() && --iconScanCountdown <= 0) {
            // Once a second, not per frame: the HUD renders every frame and this walks the whole inventory.
            iconScanCountdown = 20;
            refreshIcons();
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

    /**
     * Finds the real stack for each timer so the HUD can draw its actual texture (killer560: "put an image of
     * the items texture next to it"). Masks are matched on the Skyblock id, which also covers {@code STARRED_}
     * variants; the Phoenix pet item is a player head with "Phoenix" in its name. A found stack is remembered
     * for the session, so swapping the mask onto your head - or losing it out of the menu - doesn't blank the
     * icon mid-fight.
     */
    private static void refreshIcons() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        ItemStack worn = client.player.getItemBySlot(EquipmentSlot.HEAD);
        remember(worn);
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            remember(client.player.getInventory().getItem(i));
        }
    }

    private static void remember(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
        if (id != null && id.contains("SPIRIT_MASK")) {
            iconCache.put(Type.SPIRIT, stack.copy());
        } else if (id != null && id.contains("BONZO_MASK")) {
            iconCache.put(Type.BONZO, stack.copy());
        } else if (stack.getHoverName().getString().contains("Phoenix")) {
            iconCache.put(Type.PHOENIX, stack.copy());
        }
    }

    /** The real item if we have ever seen it, otherwise a plain head - all three are player heads on Hypixel. */
    private static ItemStack icon(Type t) {
        ItemStack cached = iconCache.get(t);
        return cached != null && !cached.isEmpty() ? cached : new ItemStack(Items.PLAYER_HEAD);
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
            return rowHeight() * (int) java.util.Arrays.stream(Type.values()).filter(MaskInvincibilityFeature::shown).count();
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
            MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
            boolean icons = cfg.isShowItemIcons();
            // Hiding the name only makes sense when there's an icon to identify the row by, so it is ignored
            // without one - otherwise the HUD would be three unlabelled numbers.
            boolean names = !(icons && cfg.isHideMaskNames());
            int row = rowHeight();
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
                int textX = x;
                if (icons) {
                    graphics.item(icon(t), x, lineY);
                    textX = x + 20;
                }
                // Centre the 8px-tall line against a 16px icon; without icons keep the old flush-top layout.
                int textY = icons ? lineY + 4 : lineY;
                graphics.text(Minecraft.getInstance().font, names ? t.label + ": " + status : status,
                        textX, textY, color, false);
                lineY += row;
            }
        }

        private static int rowHeight() {
            return MaskInvincibilityConfig.getInstance().isShowItemIcons() ? 18 : 12;
        }
    }
}
