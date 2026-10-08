package com.killer560.hub.playerstats;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.ResizableHudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * The custom stat bars and text readouts under Health and Mana Bars (killer560, 2026-10-04: "make custom
 * health, intel, vitality, defence, true defence, and other such bars that a player may find useful ... make
 * options for custom text, custom bars and whatnot all scalable"). Every readout is its own {@link HudElement},
 * so each one moves and scales on its own in the HUD editor (the settings row's own scale slider was removed on
 * 2026-10-07; it wrote this same HudConfig scale), and is drawn in game by {@code HudInGameRenderer}.
 * <p>
 * Only what Hypixel actually puts on the action bar can be shown live: health, mana, defence, overflow mana,
 * vitality and one further "current/max" resource with its own icon (see {@link PlayerStatsFeature}). True Defence
 * never appears there - it is only in the /stats menu - so it has no bar. Intelligence is not on the action bar
 * either, but max mana is 100 + Intelligence, so it is derived from the mana segment. The Defence bar shows damage
 * reduction, defence / (defence + 100), since defence itself has no maximum.
 * <p>
 * Vitality and the XP readouts came on 2026-10-07 (killer560: "It needs to detect vitality ... it needs a dedicated
 * vitality one. Also there should be a custom XP bar one as well."). The XP bar and text read the client's own
 * experience level and progress ({@code Player.experienceLevel} / {@code experienceProgress}, the same public fields
 * on 26.1.2 and 26.2, javap) - whatever the server put there, which on Hypixel is not always experience (some areas
 * use it as a countdown), so nothing is assumed about its meaning. They are appended to the enum so no existing
 * readout's ordinal - and so its default row - moves.
 * <p>
 * Every bar is a {@link ResizableHudElement}: its length and thickness are dragged by the box's edges in the HUD
 * editor and stored per bar ({@link PlayerStatsConfig#getBarWidth(Readout)}).
 */
public final class StatElements {

    private StatElements() {
    }

    /** One readout. {@code key} is the persistence key in PlayerStatsConfig and, prefixed, the HUD id. */
    public enum Readout {
        HEALTH_BAR("health_bar", "statbar_health", "Health Bar", true, 0xFFFF5555),
        MANA_BAR("mana_bar", "statbar_mana", "Mana Bar", true, 0xFF55AAFF),
        DEFENCE_BAR("defence_bar", "statbar_defence", "Defence Bar", true, 0xFF55FF55),
        OTHER_BAR("other_bar", "statbar_other", "Other Resource Bar", true, 0xFFAA55FF),
        HEALTH_TEXT("health_text", "stattext_health", "Health Text", false, 0xFFFF5555),
        MANA_TEXT("mana_text", "stattext_mana", "Mana Text", false, 0xFF55FFFF),
        OVERFLOW_TEXT("overflow_text", "stattext_overflow", "Overflow Mana Text", false, 0xFF00AAAA),
        INTELLIGENCE_TEXT("intelligence_text", "stattext_intelligence", "Intelligence Text", false, 0xFF55FFFF),
        DEFENCE_TEXT("defence_text", "stattext_defence", "Defence Text", false, 0xFF55FF55),
        EFFECTIVE_HEALTH_TEXT("ehp_text", "stattext_ehp", "Effective Health Text", false, 0xFF00AA00),
        OTHER_TEXT("other_text", "stattext_other", "Other Resource Text", false, 0xFFAA55FF),
        VITALITY_BAR("vitality_bar", "statbar_vitality", "Vitality Bar", true, 0xFFE0405A),
        XP_BAR("xp_bar", "statbar_xp", "XP Bar", true, 0xFF80FF20),
        VITALITY_TEXT("vitality_text", "stattext_vitality", "Vitality Text", false, 0xFFE0405A),
        XP_TEXT("xp_text", "stattext_xp", "XP Text", false, 0xFF80FF20);

        public final String key;
        public final String hudId;
        public final String label;
        public final boolean bar;
        public final int defaultColor;

        Readout(String key, String hudId, String label, boolean bar, int defaultColor) {
            this.key = key;
            this.hudId = hudId;
            this.label = label;
            this.bar = bar;
            this.defaultColor = defaultColor;
        }
    }

    /** Registers one HUD element per readout. Call once from the client entrypoint. */
    public static void registerAll() {
        for (Readout r : Readout.values()) {
            HudElementRegistry.register(r.bar ? new BarElement(r) : new Element(r));
        }
    }

    /**
     * Room round the number on a bar (2026-10-07, killer560: "I don't like how that text almost feels trapped by the
     * boxes, they need to be a bit bigger"). A digit is 7 rows, the comma in "10,464" one row lower and its shadow
     * one more, so the number is {@link #VALUE_ROWS} tall (measured: 18 px at GUI scale 2, testkit 444); a bar that shows it is at least {@link #MIN_VALUE_THICKNESS} thick, which leaves
     * {@link #VALUE_PAD_Y} units above and below it, and the number is only drawn where {@link #VALUE_PAD_X} units are
     * left at each end (else just the current value, else nothing). All in the bar's own units, so the room scales with
     * the bar at every GUI scale, HUD scale and Auto Scale. Before this a Show Value bar was max(thickness, 9) tall
     * with the default thickness 8: the number filled all but one row of it.
     */
    public static final int VALUE_ROWS = 9;
    public static final int VALUE_PAD_Y = 2;
    public static final int VALUE_PAD_X = 4;
    public static final int MIN_VALUE_THICKNESS = VALUE_ROWS + 2 * VALUE_PAD_Y;

    /** The thickness bar {@code r} is drawn at, in its own units: in Predefined every bar shares the tab's Bar Height
     *  (killer560, 2026-10-07: "They are different scales when you use the predefined snap"); in Custom its own
     *  HUD-editor size. A bar showing its number is never thinner than {@link #MIN_VALUE_THICKNESS}. */
    public static int thickness(Readout r) {
        PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
        int h = StatLayout.predefined() ? cfg.getBarHeight() : cfg.getBarHeight(r);
        return cfg.isBarShowValue() ? Math.max(h, MIN_VALUE_THICKNESS) : h;
    }

    /** What fits on a bar {@code w} units long with {@link #VALUE_PAD_X} at each end: {@code value}, else the part
     *  before its slash (the current value), else null. */
    static String fitValue(Font font, String value, int w) {
        if (value == null || font.width(value) + 2 * VALUE_PAD_X <= w) {
            return value;
        }
        int slash = value.indexOf('/');
        if (slash > 0 && font.width(value.substring(0, slash)) + 2 * VALUE_PAD_X <= w) {
            return value.substring(0, slash);
        }
        return null;
    }

    /**
     * The absorption to show on the health bar, in health points: what Hypixel's action bar says is past max health
     * (current over max), or the player's own absorption ({@code getAbsorptionAmount}, in vanilla half-hearts) as a
     * share of the vanilla max health applied to the SkyBlock max, whichever is larger - they describe the same thing,
     * so they are never added. 0 when there is none.
     */
    static long absorption() {
        long cur = PlayerStatsFeature.healthCur;
        long max = PlayerStatsFeature.healthMax;
        if (cur < 0 || max <= 0) {
            return 0;
        }
        long fromBar = Math.max(0, cur - max);
        long fromPlayer = 0;
        net.minecraft.world.entity.player.Player p = Minecraft.getInstance().player;
        if (p != null) {
            float a = p.getAbsorptionAmount();
            float vm = p.getMaxHealth();
            if (a > 0f && vm > 0f && Float.isFinite(a)) {
                fromPlayer = Math.round(max * (double) a / vm);
            }
        }
        return Math.max(fromBar, fromPlayer);
    }

    /** Whether {@code r} has something to show in game right now (a value read, or for XP a player). */
    static boolean hasValue(Readout r) {
        return r.bar ? barValue(r) != null : text(r, false) != null;
    }

    /** Width in its own units of what text readout {@code r} draws (its label in the editor while it has no value). */
    static int textWidth(Readout r, boolean preview) {
        String t = text(r, preview);
        return t == null ? 1 : Math.max(1, Minecraft.getInstance().font.width(t));
    }

    /** True for an id this class registered - {@code HudInGameRenderer} draws these. */
    public static boolean isStatElementId(String id) {
        return id.startsWith("statbar_") || id.startsWith("stattext_");
    }

    /** A bar readout: an {@link Element} whose length and thickness drag separately in the HUD editor. */
    private static final class BarElement extends Element implements ResizableHudElement {

        BarElement(Readout r) {
            super(r);
        }

        @Override
        public int resizeWidth() {
            return PlayerStatsConfig.getInstance().getBarWidth(r);
        }

        @Override
        public int resizeHeight() {
            return PlayerStatsConfig.getInstance().getBarHeight(r);
        }

        @Override
        public void resizeTo(int width, int height) {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            cfg.setBarWidth(r, width);
            cfg.setBarHeight(r, height);
        }

        @Override
        public void saveSize() {
            PlayerStatsConfig.getInstance().save();
        }
    }

    private static class Element implements HudElement {

        final Readout r;

        Element(Readout r) {
            this.r = r;
        }

        @Override
        public String id() {
            return r.hudId;
        }

        @Override
        public String displayName() {
            return r.label;
        }

        @Override
        public int defaultX() {
            // A column at the left edge, one row per readout, so turning several on never stacks them.
            return 10;
        }

        @Override
        public int defaultY() {
            return 150 + r.ordinal() * 14;
        }

        @Override
        public int width() {
            if (r.bar) {
                return barWidthNow();
            }
            // The drawn readout (2026-10-07 box audit: it was a fixed 90); its label when there is no value yet.
            String text = text(r, true);
            return Math.max(1, Minecraft.getInstance().font.width(text));
        }

        /** The bar's length now: the Predefined layout's share of its area, else its own length. */
        int barWidthNow() {
            StatLayout.Placed p = StatLayout.placed(r);
            return p != null && p.widthLocal() > 0 ? p.widthLocal() : PlayerStatsConfig.getInstance().getBarWidth(r);
        }

        @Override
        public int[] layoutPosition() {
            StatLayout.Placed p = StatLayout.placed(r);
            return p == null ? null : p.pos();
        }

        @Override
        public float layoutScale() {
            // Predefined: one scale for every readout (the tab's Predefined Scale, which scrolling any of them in the
            // HUD editor changes), so a row never mixes sizes. Custom: each one's own HUD-editor scale.
            return StatLayout.predefined() ? PlayerStatsConfig.getInstance().getPredefinedScale() : 0f;
        }

        @Override
        public int height() {
            if (r.bar) {
                // The box is the bar: the number sits inside it with room to spare (thickness()).
                return thickness(r);
            }
            return 9;
        }

        @Override
        public boolean isEnabledInSettings() {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            return cfg.isEnabledRaw() && cfg.isReadoutOn(r);
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
            // No "a screen is open" gate here (2026-10-07): the bars draw behind an open inventory like vanilla's hearts.
            // HudInGameRenderer is the in-game caller and decides that; the HUD editor is the other.
            if (!cfg.isEnabled() || !cfg.isReadoutOn(r)) {
                return;
            }
            boolean preview = HudVisibility.editorOpen();
            if (!preview && StatLayout.predefined() && StatLayout.placed(r) == null) {
                // Predefined: Hidden, or nothing to show - the layout gave it no place, so it draws nothing.
                return;
            }
            boolean drew = r.bar ? drawBar(graphics, cfg, x, y, preview) : drawText(graphics, cfg, x, y, preview);
            if (drew) {
                HudSeen.markDrawn(id());
            }
        }

        private boolean drawBar(GuiGraphicsExtractor g, PlayerStatsConfig cfg, int x, int y, boolean preview) {
            Object[] v = barValue(r);
            long cur = v == null ? 1 : (Long) v[0];
            long max = v == null ? 2 : (Long) v[1];
            String value = v == null ? null : (String) v[2];
            if (value == null) {
                if (!preview) {
                    return false;
                }
                // Nothing read yet: the HUD editor still gets a half-full bar to place.
                value = r.label;
            }
            int w = barWidthNow();
            int h = thickness(r);
            int top = y;
            g.fill(x, top, x + w, top + h, cfg.getBarBackground());
            int color = cfg.getReadoutColor(r);
            if (max > 0) {
                // The fill is the share of max, never more (a reading past max is absorption, below).
                int filled = (int) Math.round(w * Math.max(0.0, Math.min(1.0, (double) cur / max)));
                if (filled > 0) {
                    g.fill(x, top, x + filled, top + h, color);
                }
                long abs = r == Readout.HEALTH_BAR && v != null ? absorption() : 0;
                if (abs > 0) {
                    // Absorption (2026-10-07, killer560: "Whenever I get absorption it breaks the health one"): its own
                    // segment in the absorption colour, as long as its share of max health, straight after the health
                    // it adds to - like vanilla's golden hearts after the red ones. Where that runs past the end (full
                    // health) it ends at the bar's end instead, over the health fill, so it always shows.
                    int seg = (int) Math.max(1, Math.min(w, Math.round(w * (double) abs / max)));
                    int healthPart = (int) Math.round(w * Math.max(0.0, Math.min(1.0, (double) (cur > max ? max : cur)
                            / max)));
                    int a0 = Math.min(healthPart, w - seg);
                    g.fill(x + a0, top, x + a0 + seg, top + h, cfg.getAbsorptionColor());
                }
            }
            if (cfg.isBarShowValue()) {
                Font font = Minecraft.getInstance().font;
                // A number never runs into the bar's ends (or the next bar in a Predefined row): the full value, else
                // the current value alone, else nothing, each with VALUE_PAD_X to spare at both ends.
                value = fitValue(font, value, w);
                if (value != null) {
                    int tx = x + (w - font.width(value)) / 2;
                    int ty = top + (h - VALUE_ROWS) / 2;
                    g.text(font, value, tx, ty, 0xFFFFFFFF, true);
                }
            }
            return true;
        }

        private boolean drawText(GuiGraphicsExtractor g, PlayerStatsConfig cfg, int x, int y, boolean preview) {
            String text = text(r, preview);
            if (text == null) {
                return false;
            }
            g.text(Minecraft.getInstance().font, text, x, y, cfg.getReadoutColor(r), cfg.isTextShadow());
            return true;
        }
    }

    /** {cur, max, the number drawn on the bar} for bar {@code r}, or null when nothing has been read for it. */
    private static Object[] barValue(Readout r) {
        long cur;
        long max;
        String value;
        switch (r) {
            case HEALTH_BAR -> {
                cur = PlayerStatsFeature.healthCur;
                max = PlayerStatsFeature.healthMax;
                value = cur < 0 ? null : fmt(cur) + "/" + fmt(max);
            }
            case MANA_BAR -> {
                cur = PlayerStatsFeature.manaCur;
                max = PlayerStatsFeature.manaMax;
                value = cur < 0 ? null : fmt(cur) + "/" + fmt(max);
            }
            case DEFENCE_BAR -> {
                long def = PlayerStatsFeature.defenceValue;
                // Damage reduction as a fraction of 1000 so the shared fill code can use it.
                cur = def < 0 ? -1 : Math.round(1000.0 * def / (def + 100.0));
                max = 1000;
                value = def < 0 ? null : fmt(def) + " (" + String.format(Locale.US, "%.1f", cur / 10.0) + "%)";
            }
            case VITALITY_BAR -> {
                cur = PlayerStatsFeature.vitalityCur;
                max = PlayerStatsFeature.vitalityMax;
                value = cur < 0 ? null : fmt(cur) + "/" + fmt(max);
            }
            case XP_BAR -> {
                float[] xp = xp();
                cur = xp == null ? -1 : Math.round(1000.0 * xp[1]);
                max = 1000;
                value = xp == null ? null : xpLabel(xp);
            }
            default -> {
                cur = PlayerStatsFeature.otherCur;
                max = PlayerStatsFeature.otherMax;
                value = cur < 0 ? null : fmt(cur) + "/" + fmt(max) + PlayerStatsFeature.otherIcon;
            }
        }
        return value == null ? null : new Object[]{cur, max, value};
    }

    /** The readout a text element draws: the live value, or (in the HUD editor) its label until there is one; null
     *  when it draws nothing. */
    private static String text(Readout r, boolean preview) {
        String text = switch (r) {
            case HEALTH_TEXT -> PlayerStatsFeature.healthCur < 0 ? null
                    : "❤ " + fmt(PlayerStatsFeature.healthCur) + "/" + fmt(PlayerStatsFeature.healthMax);
            case MANA_TEXT -> PlayerStatsFeature.manaCur < 0 ? null
                    : "✎ " + fmt(PlayerStatsFeature.manaCur) + "/" + fmt(PlayerStatsFeature.manaMax);
            case OVERFLOW_TEXT -> PlayerStatsFeature.overflowMana < 0 ? null
                    : "ʬ " + fmt(PlayerStatsFeature.overflowMana);
            case INTELLIGENCE_TEXT -> PlayerStatsFeature.manaMax < 100 ? null
                    : "Intelligence " + fmt(PlayerStatsFeature.manaMax - 100);
            case DEFENCE_TEXT -> PlayerStatsFeature.defenceValue < 0 ? null
                    : "❈ " + fmt(PlayerStatsFeature.defenceValue);
            case EFFECTIVE_HEALTH_TEXT -> PlayerStatsFeature.healthCur < 0 || PlayerStatsFeature.defenceValue < 0
                    ? null
                    : "EHP " + fmt(Math.round(PlayerStatsFeature.healthCur
                    * (1.0 + PlayerStatsFeature.defenceValue / 100.0)));
            case VITALITY_TEXT -> PlayerStatsFeature.vitalityCur < 0 ? null
                    : "Vitality " + fmt(PlayerStatsFeature.vitalityCur) + "/" + fmt(PlayerStatsFeature.vitalityMax);
            case XP_TEXT -> {
                float[] xp = xp();
                yield xp == null ? null : xpLabel(xp);
            }
            default -> PlayerStatsFeature.otherCur < 0 ? null
                    : fmt(PlayerStatsFeature.otherCur) + "/" + fmt(PlayerStatsFeature.otherMax)
                    + PlayerStatsFeature.otherIcon;
        };
        if (text == null && preview) {
            text = r.label;
        }
        return text;
    }

    /** {level, progress 0..1} from the client's own player, or null with no player. Progress is clamped: it is
     *  whatever the server sent, and a server repurposing the bar can send anything. */
    private static float[] xp() {
        net.minecraft.world.entity.player.Player p = Minecraft.getInstance().player;
        if (p == null) {
            return null;
        }
        float progress = p.experienceProgress;
        if (Float.isNaN(progress)) {
            progress = 0f;
        }
        return new float[]{p.experienceLevel, Math.max(0f, Math.min(1f, progress))};
    }

    /** "Level 30 (45%)": the level number the vanilla bar shows over itself, and how far the bar is filled. */
    private static String xpLabel(float[] xp) {
        return "Level " + fmt((long) xp[0]) + " (" + Math.round(xp[1] * 100f) + "%)";
    }

    private static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }
}
