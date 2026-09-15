package com.killer560.hub.playerstats;

import com.killer560.hub.hud.HudElement;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
            return "Player Stats";
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
            return 220;
        }

        @Override
        public int height() {
            return 12;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            if (!cfg.isEnabled() || Minecraft.getInstance().screen != null) {
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
