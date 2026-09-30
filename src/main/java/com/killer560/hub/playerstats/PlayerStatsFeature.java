package com.killer560.hub.playerstats;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Hypixel Skyblock action-bar stat reader, ported from Odin's own {@code PlayerDisplay.kt}. The
 * real action bar text embeds current/max Health, Mana, and Defense using real private-use-area icon
 * codepoints from Hypixel's own resource pack (cross-checked against NoammAddons' 26.1.2 ActionBarParser -
 * U+E010/U+2764 health, U+E003/U+270E mana, U+E008/U+2748 defense, built here via explicit {@code \\uXXXX} escapes rather
 * than pasting the actual invisible glyphs, so the source stays legible and unambiguous) - without
 * anchoring to the specific icon codepoint, a plain "current/max" pattern can't tell health apart from
 * mana at all, since both share the exact same shape (a real mistake caught and fixed before this ever
 * built). Reads the real overlay text via {@code ClientReceiveMessageEvents.MODIFY_GAME} and, once a
 * line has matched at least one of the three icon-anchored patterns below (i.e. it's confirmed to be the
 * real stat line, not some other action-bar use like an ability name), drops that one line - see
 * {@link #onModifyGameMessage} - the same "replace it, don't just add to it" treatment
 * {@link #registerVanillaSuppression()} already gives the vanilla hearts/hunger/armour/air bars. Any
 * action-bar text that doesn't match a pattern is left completely alone.
 * <p>
 * Renamed "Player Stats" -&gt; "Stat Bars" (killer560, 2026-09-21) and given the ability to hide the
 * vanilla hearts/hunger/armour/air bars it sits alongside - see {@link #registerVanillaSuppression()},
 * ported from Skyblocker's mixin-free {@code fancybars.FancyStatusBars}. Only display strings changed;
 * every persistence key ({@code id()} below, the config file name, every {@code PlayerStatsConfig} JSON
 * key) is untouched so existing HUD positions and settings survive the rename.
 */
public final class PlayerStatsFeature {

    // Real bug found and fixed (2026-09-14): these patterns held the icon codepoints as RAW, invisible
    // private-use characters pasted into the source (so they looked like identical bare "n/n" patterns in
    // most editors/diffs) and only ever accepted the resource-pack icon - an action bar using the classic
    // glyphs never matched at all. Now written as explicit escapes and accepting either form, matching
    // NoammAddons' 26.1.2 ActionBarParser: health U+E010 or U+2764, defense U+E008 or U+2748, mana U+E003
    // or U+270E. Real format e.g. "(c)1234/1234<heart>     (a)567(a)<defense> Defense     (b)890/890<quill> Mana".
    // Optional section-sign color codes are allowed between the number and its icon.
    private static final String CODES = "(?:\u00A7.)*";
    private static final Pattern HEALTH_REGEX = Pattern.compile("([\\d,]+)/([\\d,]+)" + CODES + "[\uE010\u2764]");
    private static final Pattern MANA_REGEX = Pattern.compile("([\\d,]+)/([\\d,]+)" + CODES + "[\uE003\u270E]");
    private static final Pattern DEFENSE_REGEX = Pattern.compile("([\\d,]+)" + CODES + "[\uE008\u2748]");

    private static String health = null;
    private static String mana = null;
    private static String defense = null;

    private PlayerStatsFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(PlayerStatsFeature::onModifyGameMessage);
        registerVanillaSuppression();
    }

    /**
     * Hides the vanilla HUD bars our own Stat Bars line replaces - ported from Skyblocker's
     * {@code fancybars.FancyStatusBars#init()}, ONE difference from that mixin-free approach: Skyblocker
     * hides the whole bar block at once, ours is per-bar so hearts/hunger/armour/air can each be toggled
     * independently (killer560, 2026-09-21). Uses Fabric's own HUD-element API - the exact same import
     * already proven at {@code hud.HudInGameRenderer.java:3} - and deliberately {@code replaceElement}
     * rather than {@code removeElement}: the replacement function re-runs every frame, so a toggle (or
     * walking into/out of The Rift) takes effect immediately with no re-registration and no way to get
     * stuck permanently hidden.
     */
    public static void registerVanillaSuppression() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement noOp = (graphics, deltaTracker) -> {
        };
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, orig ->
                suppressing() && PlayerStatsConfig.getInstance().isHideVanillaHearts() && !heartsForcedByRift()
                        ? noOp : orig);
        HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, orig ->
                suppressing() && PlayerStatsConfig.getInstance().isHideVanillaHunger() ? noOp : orig);
        HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, orig ->
                suppressing() && PlayerStatsConfig.getInstance().isHideVanillaArmour() ? noOp : orig);
        HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR, orig ->
                suppressing() && PlayerStatsConfig.getInstance().isHideVanillaAir() ? noOp : orig);
    }

    private static boolean suppressing() {
        return PlayerStatsConfig.getInstance().isEnabled();
    }

    /** True while the vanilla heart bar should stay visible despite {@code hideVanillaHearts} - killer560:
     *  "Add a toggle to unhide hearts in the rift", where hearts show a different (real HP) meaning. Reads
     *  {@code IslandDetector.graphIsland()} directly rather than adding a helper there - that field is
     *  ticked unconditionally 4x/sec by {@code PathfindingFeature} regardless of which features are on. */
    private static boolean heartsForcedByRift() {
        return PlayerStatsConfig.getInstance().isShowHeartsInRift()
                && "THE_RIFT".equals(com.killer560.hub.pathfinding.IslandDetector.graphIsland());
    }

    private static Component onModifyGameMessage(Component message, boolean overlay) {
        if (!overlay || !PlayerStatsConfig.getInstance().isEnabled()) {
            return message;
        }
        String raw = message.getString();

        Matcher healthMatch = HEALTH_REGEX.matcher(raw);
        boolean healthHit = healthMatch.find();
        if (healthHit) {
            health = healthMatch.group(1) + "/" + healthMatch.group(2);
        }
        Matcher manaMatch = MANA_REGEX.matcher(raw);
        boolean manaHit = manaMatch.find();
        if (manaHit) {
            mana = manaMatch.group(1) + "/" + manaMatch.group(2);
        }
        Matcher defenseMatch = DEFENSE_REGEX.matcher(raw);
        boolean defenseHit = defenseMatch.find();
        if (defenseHit) {
            defense = defenseMatch.group(1);
        }
        // Real bug found and fixed (2026-09-27), killer560: "it didn't hide the text that the server
        // normally has. It does show its own text though." This class doc used to say the real overlay
        // is "always returned unchanged" - that was the bug, not a design choice: Stat Bars is meant to
        // REPLACE this exact line (same as it already replaces the vanilla hearts/hunger/armour/air bars
        // via registerVanillaSuppression()), so once we've actually read the numbers off it, the real
        // line itself is dropped. Only for a line that matched at least one of the three patterns - an
        // action bar showing something else entirely (an ability name, a warning) is never touched.
        if (healthHit || manaHit || defenseHit) {
            return Component.empty();
        }
        return message;
    }

    public static final class StatsHudElement implements HudElement {

        // Real bug found and fixed (2026-09-27), killer560: "Stat bars is hiding the normal stuff but it
        // didn't create the bars." render() below only ever called graphics.text(...) - there was no bar-
        // drawing code at all, on any path, so no setting or render layer could have made one appear.
        // These four constants and drawBar()/fraction() below are the missing piece. Health = red (matches
        // the §c used in the text line), mana = blue (matches §b) - defense has no bar since the action bar
        // never gives a max defense to compute a fraction against, only a text value.
        private static final int BAR_WIDTH = 200;
        private static final int BAR_HEIGHT = 4;
        private static final int BAR_GAP = 2;
        private static final int TEXT_HEIGHT = 10;
        private static final int HEALTH_BAR_BG = 0xFF550000;
        private static final int HEALTH_BAR_FILL = 0xFFFF5555;
        private static final int MANA_BAR_BG = 0xFF002A55;
        private static final int MANA_BAR_FILL = 0xFF55AAFF;

        @Override
        public String id() {
            return "player_stats";
        }

        @Override
        public String displayName() {
            // HUD editor label only - the persisted id() below stays "player_stats" so saved HUD
            // positions/scales survive the "Player Stats" -> "Stat Bars" rename (killer560, 2026-09-21).
            return "Stat Bars";
        }

        @Override
        public int defaultX() {
            // Second column (x=200): the old 10/440 sat exactly on Mask Invincibility Timers.
            return 200;
        }

        @Override
        public int defaultY() {
            return 440;
        }

        @Override
        public int width() {
            return 220;
        }

        @Override
        public int height() {
            // Same live-config sizing InventoryHudFeature's own width()/height() already use, so the HUD
            // editor's drag box and the on-screen clamp both track whichever of Show Text/Show Bar (and
            // which stats) are actually on right now.
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            int barRows = cfg.isShowBar() ? (cfg.isShowHealth() ? 1 : 0) + (cfg.isShowMana() ? 1 : 0) : 0;
            int h = barRows * (BAR_HEIGHT + BAR_GAP);
            if (cfg.isShowText()) {
                h += TEXT_HEIGHT;
            }
            return Math.max(h, 1);
        }

        @Override
        public boolean isEnabledInSettings() {
            return PlayerStatsConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            int rowY = y;
            if (cfg.isShowBar()) {
                if (cfg.isShowHealth() && health != null) {
                    rowY = drawBar(graphics, x, rowY, HEALTH_BAR_BG, HEALTH_BAR_FILL, fraction(health));
                }
                if (cfg.isShowMana() && mana != null) {
                    rowY = drawBar(graphics, x, rowY, MANA_BAR_BG, MANA_BAR_FILL, fraction(mana));
                }
            }
            boolean drew = rowY != y;
            if (cfg.isShowText()) {
                StringBuilder text = new StringBuilder();
                if (cfg.isShowHealth() && health != null) {
                    text.append("§cHP: §f").append(health).append("  ");
                }
                if (cfg.isShowMana() && mana != null) {
                    text.append("§bMP: §f").append(mana).append("  ");
                }
                if (cfg.isShowDefense() && defense != null) {
                    text.append("§aDEF: §f").append(defense);
                }
                if (!text.isEmpty()) {
                    graphics.text(Minecraft.getInstance().font, text.toString(), x, rowY, 0xFFFFFFFF, false);
                    drew = true;
                }
            }
            // Bars and text are both optional and both can come out empty (no health/mana scraped yet), so
            // the stamp has to follow what was actually put on screen, not just the enabled checks.
            if (drew) {
                HudSeen.markDrawn(id());
            }
        }

        /** Draws one background+fill bar at (x, y). @return the y the next row should draw at. */
        private static int drawBar(GuiGraphicsExtractor graphics, int x, int y, int bg, int fill, float fraction) {
            graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, bg);
            int filledWidth = Math.round(BAR_WIDTH * fraction);
            if (filledWidth > 0) {
                graphics.fill(x, y, x + filledWidth, y + BAR_HEIGHT, fill);
            }
            return y + BAR_HEIGHT + BAR_GAP;
        }

        /** Parses a "current/max" string (commas allowed, same as {@link PlayerStatsFeature#HEALTH_REGEX}/
         *  {@link PlayerStatsFeature#MANA_REGEX} capture) into current/max clamped to [0, 1]. Never throws
         *  mid-frame - an unparsable value just draws an empty bar. */
        private static float fraction(String currentOverMax) {
            try {
                int slash = currentOverMax.indexOf('/');
                long current = Long.parseLong(currentOverMax.substring(0, slash).replace(",", ""));
                long max = Long.parseLong(currentOverMax.substring(slash + 1).replace(",", ""));
                return max <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) current / max));
            } catch (RuntimeException e) {
                return 0f;
            }
        }
    }
}
