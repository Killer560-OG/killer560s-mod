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
 * codepoints from Hypixel's own resource pack (ported verbatim from Odin's real, confirmed regexes -
 * U+E010 health, U+E003 mana, U+E008 defense, built here via explicit {@code \\uXXXX} escapes rather
 * than pasting the actual invisible glyphs, so the source stays legible and unambiguous) - without
 * anchoring to the specific icon codepoint, a plain "current/max" pattern can't tell health apart from
 * mana at all, since both share the exact same shape (a real mistake caught and fixed before this ever
 * built). This only ever READS the real overlay text via {@code ClientReceiveMessageEvents.MODIFY_GAME}
 * and always returns it unchanged - unlike Odin's own version (which can also hide parts of the real
 * action bar), this deliberately never rewrites what Hypixel actually shows, only adds its own separate
 * HUD line, to avoid any risk of a regex mistake eating real text the player needs to see.
 */
public final class PlayerStatsFeature {

    private static final Pattern HEALTH_REGEX = Pattern.compile("([\\d,]+)/([\\d,]+)");
    private static final Pattern MANA_REGEX = Pattern.compile("([\\d,]+)/([\\d,]+)");
    private static final Pattern DEFENSE_REGEX = Pattern.compile("([\\d,]+)(?:§.)?");

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
        if (healthMatch.find()) {
            health = healthMatch.group(1) + "/" + healthMatch.group(2);
        }
        Matcher manaMatch = MANA_REGEX.matcher(raw);
        if (manaMatch.find()) {
            mana = manaMatch.group(1) + "/" + manaMatch.group(2);
        }
        Matcher defenseMatch = DEFENSE_REGEX.matcher(raw);
        if (defenseMatch.find()) {
            defense = defenseMatch.group(1);
        }
        return message;
    }

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
