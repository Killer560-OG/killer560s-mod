package com.killer560.hub.maskinvincibility;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
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

    private static final Map<Type, Integer> activeRemaining = new EnumMap<>(Type.class);
    private static final Map<Type, Integer> cooldownRemaining = new EnumMap<>(Type.class);
    /** Last real stack seen for each type, so the icon keeps rendering after the mask leaves your inventory. */
    private static final Map<Type, ItemStack> iconCache = new EnumMap<>(Type.class);
    /** Stand-in icons (catalog skin or plain head), kept so render does not rebuild a stack every frame. */
    private static final Map<Type, ItemStack> fallbackIcon = new EnumMap<>(Type.class);
    private static final Type[] TYPES = Type.values();
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MaskInvincibilityFeature", client -> tick()));
        MaskSwapper.register();
        AutoMask.register();
    }

    private static void onChatMessage(Component message) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): matched the raw un-stripped
        // string - real Hypixel chat lines are confirmed (DungeonState's own BOSS_START_PATTERN fix) to
        // embed §-codes mid-word, which can silently break a regex match.
        String raw = com.killer560.hub.util.ChatObserver.strip(message);
        for (Type t : Type.values()) {
            if (t.pattern.matcher(raw).matches()) {
                activeRemaining.put(t, t.activeTicks);
                cooldownRemaining.put(t, t.cooldownTicks);
                if (cfg.isAnnounceToParty()) {
                    sendPartyChat(t.label + " popped!");
                }
                if (cfg.isAutoSwapEnabled()) {
                    AutoMask.onProc(t.label);
                }
                return;
            }
        }
    }

    /**
     * True when Auto Mask must not pick {@code target}: on cooldown from its last proc, or switched off in this tab's
     * Spirit/Bonzo/Phoenix row (killer560 9.1: "allow them to select which invulnerabilities they actually own"). Auto
     * Mask ({@link AutoMask}) decides when to swap and hands the swap to {@link MaskSwapper}; a refusal is a normal
     * answer, never something to retry.
     */
    static boolean unusable(MaskSwapper.Target target) {
        Type type = Type.of(target);
        return type == null || cooldownRemaining.getOrDefault(type, 0) > 0 || !shown(type);
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

        // The timers count down whether or not the switch is on: a mask cooldown is Hypixel's, so switching the
        // feature off and on (or leaving Skyblock) must not freeze it and show a stale time afterward.
        for (Type t : TYPES) {
            int active = activeRemaining.get(t);
            if (active > 0) {
                activeRemaining.put(t, active - 1);
            }
            int cooldown = cooldownRemaining.get(t);
            if (cooldown > 0) {
                cooldownRemaining.put(t, cooldown - 1);
            }
        }
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (cfg.isEnabled() && cfg.isShowItemIcons() && --iconScanCountdown <= 0) {
            // Once a second, not per frame: the HUD renders every frame and this walks the whole inventory.
            iconScanCountdown = 20;
            refreshIcons();
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
        } else if (stack.is(Items.PLAYER_HEAD) && stack.getHoverName().getString().contains("Phoenix")) {
            iconCache.put(Type.PHOENIX, stack.copy());
        }
    }

    /** The real item if we have ever seen it, otherwise a plain head - all three are player heads on Hypixel.
     *  killer560 9.1: "The spirit mask does not have an icon it is just steves head" - unlike Bonzo's Mask
     *  and the Phoenix pet, which most players are carrying or wearing already so {@link #refreshIcons()}
     *  finds a real stack within a second of the HUD turning on, Spirit Mask is the one of the three that is
     *  often not sitting in the inventory at all (still on cooldown from an earlier run, never picked up this
     *  session, etc.) - so it fell straight through to the blank-head fallback far more often than the other
     *  two. Falls back to the real catalog skin ({@link SkyblockItemRepository}/{@link SkyblockItemStackFactory}
     *  - the same stable-UUID-from-texture head-building the item browser uses, not a random one, so the
     *  resolve doesn't flash/retry every frame) instead of a blank head whenever the real stack hasn't been
     *  seen yet. */
    private static ItemStack icon(Type t) {
        ItemStack cached = iconCache.get(t);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        // Built once per type, not per frame: this is called from the HUD's render every frame.
        ItemStack fallback = fallbackIcon.get(t);
        if (fallback != null) {
            return fallback;
        }
        if (t == Type.SPIRIT) {
            SkyblockItemEntry entry = SkyblockItemRepository.findById("SPIRIT_MASK");
            if (entry != null && entry.skinValue() != null) {
                fallback = SkyblockItemStackFactory.build(entry);
                fallbackIcon.put(t, fallback);
                return fallback;
            }
        }
        // The catalog may not be loaded yet for Spirit, so a plain head is not remembered for it.
        fallback = new ItemStack(Items.PLAYER_HEAD);
        if (t != Type.SPIRIT) {
            fallbackIcon.put(t, fallback);
        }
        return fallback;
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

        /**
         * The box is the rows as drawn (2026-10-07, killer560: the editor box was "wider/taller than its 3 rows"):
         * the widest row's icon + text, and every row but the last at full row pitch with the last one only as tall
         * as its icon or text. It was a fixed 150 wide and a whole row pitch per row.
         */
        @Override
        public int width() {
            boolean icons = MaskInvincibilityConfig.getInstance().isShowItemIcons();
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            int w = 0;
            for (Type t : TYPES) {
                if (shown(t)) {
                    w = Math.max(w, (icons ? 20 : 0) + font.width(rowText(t)));
                }
            }
            return Math.max(20, w);
        }

        @Override
        public int height() {
            int rows = 0;
            for (Type t : TYPES) {
                if (shown(t)) {
                    rows++;
                }
            }
            if (rows == 0) {
                return rowHeight();
            }
            int last = MaskInvincibilityConfig.getInstance().isShowItemIcons() ? 16 : Minecraft.getInstance().font.lineHeight;
            return (rows - 1) * rowHeight() + last;
        }

        // rowText's last answer per type: the text changes at most once a tick, render and width() ask every frame
        // (a concat, and a String.format while counting, each frame: 95-perf-features 2026-10-15). Render thread.
        private static final String[] ROW_TEXT = new String[Type.values().length];
        private static final int[] ROW_ACTIVE = new int[Type.values().length];
        private static final int[] ROW_COOLDOWN = new int[Type.values().length];
        private static final boolean[] ROW_NAMES = new boolean[Type.values().length];

        /** One row's text, exactly as render() draws it. */
        private static String rowText(Type t) {
            MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
            // Hiding the name only makes sense when there's an icon to identify the row by, so it is ignored
            // without one - otherwise the HUD would be three unlabeled numbers.
            boolean names = !(cfg.isShowItemIcons() && cfg.isHideMaskNames());
            int active = activeRemaining.get(t);
            int cooldown = cooldownRemaining.get(t);
            int i = t.ordinal();
            if (ROW_TEXT[i] != null && ROW_ACTIVE[i] == active && ROW_COOLDOWN[i] == cooldown && ROW_NAMES[i] == names) {
                return ROW_TEXT[i];
            }
            ROW_ACTIVE[i] = active;
            ROW_COOLDOWN[i] = cooldown;
            ROW_NAMES[i] = names;
            return ROW_TEXT[i] = rowTextUncached(t, names, active, cooldown);
        }

        private static String rowTextUncached(Type t, boolean names, int active, int cooldown) {
            String status = active > 0
                    ? String.format(Locale.US, "%.1fs", active / 20f)
                    : cooldown > 0
                    ? String.format(Locale.US, "%.1fs", cooldown / 20f)
                    : "Ready";
            return names ? t.label + ": " + status : status;
        }

        @Override
        public boolean isEnabledInSettings() {
            // Setting only; passesLocationGates() is a location test, which the draw stamp reports instead.
            return MaskInvincibilityConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            // The HUD editor previews it wherever he is, so Only In Dungeons / Boss Only never leave an empty box.
            if (!MaskInvincibilityConfig.getInstance().isEnabled() || HudVisibility.hidesHud()
                    || !(passesLocationGates() || HudVisibility.editorOpen())) {
                return;
            }
            MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
            boolean icons = cfg.isShowItemIcons();
            int row = rowHeight();
            int lineY = y;
            for (Type t : TYPES) {
                if (!shown(t)) {
                    continue;
                }
                int active = activeRemaining.get(t);
                int cooldown = cooldownRemaining.get(t);
                int color = active > 0 ? 0xFFFFAA00 : cooldown > 0 ? 0xFFFF5555 : 0xFF55FF55;
                int textX = x;
                if (icons) {
                    graphics.item(icon(t), x, lineY);
                    textX = x + 20;
                }
                // Center the 8px-tall line against a 16px icon; without icons keep the old flush-top layout.
                int textY = icons ? lineY + 4 : lineY;
                graphics.text(Minecraft.getInstance().font, rowText(t), textX, textY, color, false);
                lineY += row;
            }
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }

        private static int rowHeight() {
            return MaskInvincibilityConfig.getInstance().isShowItemIcons() ? 18 : 12;
        }

        /** killer560 9.1: "add an option to only show the invulnerabilites in dungeons, and one for boss
         *  only" - same shape as {@code SecretsFeature}'s own Boss Only gate (a plain "off, or the condition
         *  holds" OR, not a nested requirement, so Boss Only works even if Only In Dungeons is left off). */
        private static boolean passesLocationGates() {
            MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
            if (cfg.isOnlyInDungeons() && !DungeonState.isInDungeon()) {
                return false;
            }
            return !cfg.isBossOnly() || DungeonState.isBossPhaseActive();
        }
    }
}
