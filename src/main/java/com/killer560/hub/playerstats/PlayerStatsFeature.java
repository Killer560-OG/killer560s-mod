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
 * real stat line, not some other action-bar use like an ability name), takes the stat segments out of it
 * when "Hypixel Stat Text" is on under Hide -
 * see {@link #actionBarReplacement} - the same "replace it, don't just add to it" treatment
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
    // Overflow mana (2026-10-04): Hypixel shows it as "(3)200<U+02AC>" right after the mana segment once mana is
    // past max - the same pattern SkyHanni's ActionBarStatsData reads. It has no max, so it is text only.
    // Its colour code is (3), a DIGIT, so a bare "([\\d,]+)" read "(3)200" as 3200 (caught by a scratch run of
    // these patterns against a sample line, 2026-10-04). The match now starts where a number cannot continue
    // and takes the colour codes itself. Same guard on OTHER_REGEX below.
    private static final String NUMBER_START = "(?<![\u00A7\\d,])" + CODES;
    private static final Pattern OVERFLOW_REGEX = Pattern.compile(NUMBER_START + "([\\d,]+)" + CODES + "\u02AC");
    // Any OTHER "current/max<icon>" segment on the stat line (2026-10-04): killer560's screenshot of that day
    // shows a fourth one, a red "117/117" with its own resource-pack icon, beside health/defence/mana. What it
    // is changes with what he is doing, so it is read generically: the first n/n followed by a non-ASCII icon
    // that is not the health or mana icon. ASCII is excluded so "(1/3)" or "5/7 Secrets" never count.
    private static final Pattern OTHER_REGEX =
            Pattern.compile(NUMBER_START + "([\\d,]+)/([\\d,]+)" + CODES + "([^\\x00-\\x7F\u00A7])");
    private static final String HEALTH_ICONS = "\uE010\u2764";
    private static final String MANA_ICONS = "\uE003\u270E";
    // What "Hide Hypixel Stat Text" removes from the line. Each takes its own leading colour codes and, for
    // defence and mana, the trailing word Hypixel sometimes prints ("Defense", "Mana").
    private static final Pattern[] STRIP = {
            Pattern.compile(CODES + "[\\d,]+/[\\d,]+" + CODES + "[\uE010\u2764]"),
            Pattern.compile(CODES + "[\\d,]+/[\\d,]+" + CODES + "[\uE003\u270E](?:" + CODES + " Mana)?"),
            Pattern.compile(CODES + "[\\d,]+" + CODES + "[\uE008\u2748](?:" + CODES + " Defense)?"),
            Pattern.compile(CODES + "[\\d,]+" + CODES + "\u02AC"),
            Pattern.compile(CODES + "[\\d,]+/[\\d,]+" + CODES + "[^\\x00-\\x7F\u00A7]"),
    };

    private static String health = null;
    private static String mana = null;
    private static String defense = null;
    // Numeric copies for the custom bars and readouts (StatElements). -1 = not seen yet this session.
    static long healthCur = -1, healthMax = -1;
    static long manaCur = -1, manaMax = -1;
    static long defenceValue = -1;
    static long overflowMana = -1;
    static long otherCur = -1, otherMax = -1;
    static String otherIcon = "";
    /** Re-entry guard for the mixin, which re-sends a stripped line through setOverlayMessage. */
    private static boolean resending = false;

    private PlayerStatsFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(PlayerStatsFeature::onModifyGameMessage);
        registerVanillaSuppression();
        // Experience bar + level (2026-10-04, killer560: "add an option to hide the enchanting bar and its
        // level"). Fabric's INFO_BAR is the contextual bar slot - the XP bar, and also the locator and
        // mount-jump bars that take its place - and EXPERIENCE_LEVEL is the number over it (javap,
        // fabric-rendering-v1 23.3.1 and 25.3.3). Same replaceElement shape as the hides below. Independent of
        // the Stat Bars master toggle; Skyblock Only like the rest of the mod.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement noOp = (graphics, deltaTracker) -> {
        };
        HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, orig -> hidingXp() ? noOp : orig);
        HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, orig -> hidingXp() ? noOp : orig);
    }

    private static boolean hidingXp() {
        return PlayerStatsConfig.getInstance().isHideXpBar() && com.killer560.hub.util.SkyblockGate.allows();
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
        if (healthHit) {
            healthCur = num(healthMatch.group(1));
            healthMax = num(healthMatch.group(2));
        }
        if (manaHit) {
            manaCur = num(manaMatch.group(1));
            manaMax = num(manaMatch.group(2));
        }
        if (defenseHit) {
            defenceValue = num(defenseMatch.group(1));
        }
        if (healthHit || manaHit || defenseHit) {
            // Only read the extras off a confirmed stat line, and clear them when the line no longer carries
            // them, so overflow mana that has drained (or a resource that went away) stops showing.
            Matcher overflowMatch = OVERFLOW_REGEX.matcher(raw);
            overflowMana = overflowMatch.find() ? num(overflowMatch.group(1)) : -1;
            otherCur = -1;
            otherMax = -1;
            Matcher otherMatch = OTHER_REGEX.matcher(raw);
            while (otherMatch.find()) {
                String icon = otherMatch.group(3);
                if (HEALTH_ICONS.contains(icon) || MANA_ICONS.contains(icon)) {
                    continue;
                }
                otherCur = num(otherMatch.group(1));
                otherMax = num(otherMatch.group(2));
                otherIcon = icon;
                break;
            }
        }
        // The stat line is hidden from the screen (killer560, 2026-09-27: "it didn't hide the text that the
        // server normally has"), but NOT here. Fabric chains MODIFY_GAME and then hands the result to GAME and
        // to Gui.setOverlayMessage (javap, fabric-message-api-v1 7.0.5 and 7.0.8), so returning
        // Component.empty() here blanked the line for every later reader: Ability Cooldown's own MODIFY_GAME
        // listener, the GAME overlay readers (live map, Auto Routes, interop room secrets) and the Custom
        // Scoreboard's "x/y Secrets". The line now passes through untouched and the Gui mixin
        // (CustomScoreboardGuiMixin) strips it at setOverlayMessage via actionBarReplacement, after the
        // scoreboard has read it.
        return message;
    }

    /** Parses "1,234" to 1234; -1 on anything unparsable. Never throws - this runs on the packet path, where
     *  a throw disconnects him from Hypixel (see CLAUDE.md). The regex digits are unbounded, so an over-long
     *  run of them is caught here rather than trusted. */
    static long num(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * What {@code setOverlayMessage} should show instead of {@code message}: the same object when nothing
     * changes, {@code null} to drop the line, or a new line with Hypixel's stat segments taken out.
     * <p>
     * Before 2026-10-04 a stat line was dropped WHOLE whenever Stat Bars was on, which also took whatever else
     * Hypixel puts on that line ("5/7 Secrets", an ability name). It is now its own option, "Hypixel Stat
     * Text" under Hide (killer560: "add an option to hide the text Hypixel has like 3000/3000 with the heart
     * symbol"), and only the stat segments go. Only a line carrying health, mana or defence is touched; the
     * extras (overflow mana, the other n/n resource) are only removed from such a line. Called from
     * {@code CustomScoreboardGuiMixin} at {@code setOverlayMessage} HEAD, after the scoreboard has read it.
     */
    public static Component actionBarReplacement(Component message) {
        if (message == null || resending || !PlayerStatsConfig.getInstance().isHideHypixelStatText()
                || !com.killer560.hub.util.SkyblockGate.allows()) {
            return message;
        }
        String raw = message.getString();
        if (!HEALTH_REGEX.matcher(raw).find() && !MANA_REGEX.matcher(raw).find()
                && !DEFENSE_REGEX.matcher(raw).find()) {
            return message;
        }
        String left = raw;
        for (Pattern p : STRIP) {
            left = p.matcher(left).replaceAll("");
        }
        if (left.replaceAll("\u00A7.", "").isBlank()) {
            return null;
        }
        // Hypixel separates segments with runs of spaces; removing some leaves wide gaps, so close them up.
        return Component.literal(left.replaceAll(" {2,}", "     ").trim());
    }

    /** True while the mixin is re-sending a stripped line, so it is not processed (or read) a second time. */
    public static boolean isResending() {
        return resending;
    }

    /** Runs {@code send} (a setOverlayMessage call with the stripped line) with the re-entry guard up. */
    public static void resend(Runnable send) {
        resending = true;
        try {
            send.run();
        } finally {
            resending = false;
        }
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
            // The bars' width or the text line's, whichever is drawn wider (2026-10-07 box audit: it was a fixed 220
            // round 200-unit bars).
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            int w = cfg.isShowBar() && (cfg.isShowHealth() || cfg.isShowMana()) ? BAR_WIDTH : 1;
            if (cfg.isShowText()) {
                w = Math.max(w, Minecraft.getInstance().font.width(textLine(cfg).stripTrailing()));
            }
            return w;
        }

        /** The text row render() draws (empty before anything has been scraped). */
        private static String textLine(PlayerStatsConfig cfg) {
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
            return text.toString();
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
                String text = textLine(cfg);
                if (!text.isEmpty()) {
                    graphics.text(Minecraft.getInstance().font, text, x, rowY, 0xFFFFFFFF, false);
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
