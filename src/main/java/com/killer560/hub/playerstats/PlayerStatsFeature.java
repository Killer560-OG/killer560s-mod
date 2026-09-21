package com.killer560.hub.playerstats;

import com.killer560.hub.hud.HudElement;
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
 * built). This only ever READS the real overlay text via {@code ClientReceiveMessageEvents.MODIFY_GAME}
 * and always returns it unchanged - unlike Odin's own version (which can also hide parts of the real
 * action bar), this deliberately never rewrites what Hypixel actually shows, only adds its own separate
 * HUD line, to avoid any risk of a regex mistake eating real text the player needs to see.
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
        String hits = "health=" + healthHit + " mana=" + manaHit + " defense=" + defenseHit;
        if (!hits.equals(lastLoggedHits)) {
            // State-change only: which icon-anchored patterns matched, with the raw bar to check against.
            LOGGER.info("[PlayerStats] Pattern hits changed: {} -> health={} mana={} defense={} raw=\"{}\"",
                    hits, health, mana, defense, raw);
            lastLoggedHits = hits;
        }
        // [PlayerStats] diagnostics - at most one line per 10s, raw action bar included so the regexes can be checked.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDiagLogMs >= 10000) {
            lastDiagLogMs = nowMs;
            LOGGER.info("[PlayerStats] Action bar raw=\"{}\" -> health={} mana={} defense={}", raw, health, mana, defense);
        }
        return message;
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-playerstats");
    private static long lastDiagLogMs = 0;
    private static String lastLoggedHits = null;

    public static final class StatsHudElement implements HudElement {
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
            return 12;
        }

        @Override
        public boolean isRelevantNow() {
            return PlayerStatsConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
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
            if (text.isEmpty()) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, text.toString(), x, y, 0xFFFFFFFF, false);
        }
    }
}
