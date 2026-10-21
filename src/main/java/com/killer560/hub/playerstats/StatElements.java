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
        DEFENCE_BAR("defence_bar", "statbar_defence", "Defense Bar", true, 0xFF55FF55),
        OTHER_BAR("other_bar", "statbar_other", "Other Resource Bar", true, 0xFFAA55FF),
        HEALTH_TEXT("health_text", "stattext_health", "Health Text", false, 0xFFFF5555),
        MANA_TEXT("mana_text", "stattext_mana", "Mana Text", false, 0xFF55FFFF),
        OVERFLOW_TEXT("overflow_text", "stattext_overflow", "Overflow Mana Text", false, 0xFF00AAAA),
        INTELLIGENCE_TEXT("intelligence_text", "stattext_intelligence", "Intelligence Text", false, 0xFF55FFFF),
        DEFENCE_TEXT("defence_text", "stattext_defence", "Defense Text", false, 0xFF55FF55),
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
    /** 1 since 2026-10-13 (was 2): killer560 asked for the default bars "a hair thinner", and with Show Value on (the
     *  default) every bar is drawn at this floor, not at Bar Height - so the floor is what makes the default thinner
     *  (14 -> 12). A Bar Height above it, or Show Value off, is drawn exactly as set. */
    public static final int VALUE_PAD_Y = 1;
    public static final int VALUE_PAD_X = 4;
    /** Even, so half of it is a whole GUI pixel at Auto Scale 0.5 (13 drew 6.5 units and edge drags in the HUD editor
     *  moved by a pixel less than dragged, testkit 399): the digits' body centred, 2.5 units above it and below. */
    public static final int MIN_VALUE_THICKNESS = VALUE_ROWS + 2 * VALUE_PAD_Y + 1;

    /** Rows of a digit (and of the capital letters): the body the eye centres. The comma's tail and the shadow hang
     *  below it, so centring the full {@link #VALUE_ROWS} put the digits a row high. */
    public static final int DIGIT_ROWS = 7;

    /** The number's outline (2026-10-12, killer560: "Can the text stand out a hair more ... I feel like it is a hair hard
     *  to read"): a 1-unit ring in all eight directions round every glyph, so white digits read on the brightest fill,
     *  the gloss band and the sky alike. Vanilla's drop shadow was one quarter-bright copy down-right only. */
    public static final int OUTLINE = 0xFF000000;
    private static final int[] OUT_DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] OUT_DY = {-1, -1, -1, 0, 0, 1, 1, 1};

    /**
     * Draws {@code value} centred on the bar box {@code (x, top, w, h)} at text scale {@code s} (killer560, 2026-10-07:
     * "Can you center the text for my custom health bars in their new slightly larger state"). Centred is the glyphs: the
     * digits' 7 rows vertically (not the 9 the comma and shadow need, which sat them a row high), and their ink
     * horizontally - a string's width ends in one unit of spacing after its last glyph, which is not ink. The outline
     * is the same on every side, so it does not move the centre.
     */
    static void drawCentred(GuiGraphicsExtractor g, Font font, String value, int x, int top, int w, int h, float s,
                            int color) {
        int ink = Math.max(0, font.width(value) - 1);
        drawAt(g, font, value, x + (w - ink * s) / 2f, top, h, s, color);
    }

    /**
     * Draws {@code value} with its left ink at {@code fx}, its digits centred on the rows {@code top..top+h}, at scale
     * {@code s}, outlined. The position is snapped to the nearest screen pixel through the current pose, so the glyphs
     * sit on whole pixels at every GUI scale.
     */
    static void drawAt(GuiGraphicsExtractor g, Font font, String value, float fx, int top, int h, float s, int color) {
        float fy = top + (h - DIGIT_ROWS * s) / 2f;
        var pose = g.pose();
        int gs = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        if (pose.m00() > 0f && pose.m11() > 0f) {
            fx = (Math.round((pose.m00() * fx + pose.m20()) * gs) / (float) gs - pose.m20()) / pose.m00();
            fy = (Math.round((pose.m11() * fy + pose.m21()) * gs) / (float) gs - pose.m21()) / pose.m11();
        }
        pose.pushMatrix();
        try {
            pose.translate(fx, fy);
            if (s != 1f) {
                pose.scale(s, s);
            }
            outlined(g, font, value, color);
        } finally {
            pose.popMatrix();
        }
    }

    /** {@code text} at the current pose's origin with the one-unit outline all round it. */
    static void outlined(GuiGraphicsExtractor g, Font font, String text, int color) {
        for (int k = 0; k < OUT_DX.length; k++) {
            g.text(font, text, OUT_DX[k], OUT_DY[k], OUTLINE, false);
        }
        g.text(font, text, 0, 0, color, false);
    }

    /**
     * The number's scale: 1 for Normal; for Small about 0.85, rounded down so a font pixel is a whole number of screen
     * pixels where that still leaves at least 0.6 (3 of 4 at 4 screen pixels a unit, 2 of 3 at 3). At 2 pixels a unit
     * the only whole step is a half, too small, so Small is 0.85 there.
     */
    static float textScale(GuiGraphicsExtractor g, PlayerStatsConfig cfg) {
        if (cfg.getBarTextSize() != PlayerStatsConfig.TextSize.SMALL) {
            return 1f;
        }
        float p = g.pose().m00() * Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        if (p <= 0f) {
            return SMALL_SCALE;
        }
        float whole = (float) Math.floor(p * SMALL_SCALE + 1e-3f);
        float s = whole / p;
        return s >= 0.6f && s <= SMALL_SCALE + 1e-3f ? s : SMALL_SCALE;
    }

    static final float SMALL_SCALE = 0.85f;

    // ---- Where the readouts drew this frame, in GUI units: the held-item name hides where it would cover one. ----

    private static final int MAX_DRAWN = 32;
    private static final float[] drawnRects = new float[MAX_DRAWN * 4];
    private static int drawnCount;

    /** Forgets last frame's boxes; the readouts' own layer calls this before it draws. Render thread. */
    public static void beginFrame() {
        drawnCount = 0;
    }

    /** Records the box a readout just drew, {@code (x, y, w, h)} in its own pose, as GUI units on screen. */
    static void noteDrawn(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (drawnCount >= MAX_DRAWN) {
            return;
        }
        var pose = g.pose();
        int i = drawnCount++ * 4;
        drawnRects[i] = pose.m00() * x + pose.m20();
        drawnRects[i + 1] = pose.m11() * y + pose.m21();
        drawnRects[i + 2] = pose.m00() * (x + w) + pose.m20();
        drawnRects[i + 3] = pose.m11() * (y + h) + pose.m21();
    }

    /** Whether any readout has drawn this frame (the held-item name has nothing to hide under otherwise). */
    public static boolean anyDrawn() {
        return drawnCount > 0;
    }

    /** Whether any readout drawn this frame overlaps the GUI-unit box {@code [x0,x1) x [y0,y1)}. */
    public static boolean drawnOver(float x0, float y0, float x1, float y1) {
        for (int k = 0; k < drawnCount; k++) {
            int i = k * 4;
            float a0 = Math.min(drawnRects[i], drawnRects[i + 2]);
            float a1 = Math.max(drawnRects[i], drawnRects[i + 2]);
            float b0 = Math.min(drawnRects[i + 1], drawnRects[i + 3]);
            float b1 = Math.max(drawnRects[i + 1], drawnRects[i + 3]);
            if (a0 < x1 && x0 < a1 && b0 < y1 && y0 < b1) {
                return true;
            }
        }
        return false;
    }

    /** The thickness bar {@code r} is drawn at, in its own units: in Predefined every bar shares the tab's Bar Height
     *  (killer560, 2026-10-07: "They are different scales when you use the predefined snap"); in Custom its own
     *  HUD-editor size. A bar showing its number is never thinner than {@link #MIN_VALUE_THICKNESS}. */
    public static int thickness(Readout r) {
        PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
        int h = StatLayout.predefined() ? cfg.getBarHeight() : cfg.getBarHeight(r);
        return cfg.isBarShowValue() ? Math.max(h, MIN_VALUE_THICKNESS) : h;
    }

    /** What fits on a bar {@code w} units long with {@link #VALUE_PAD_X} at each end: {@code value}, else its short
     *  form ({@link #shortValue}), else null. */
    static String fitValue(Font font, String value, int w, float s) {
        return fitValue(font, value, null, w, s);
    }

    /**
     * {@link #fitValue(Font, String, int, float)} with the bar's symbol (Show Symbols, null for none) in front where it
     * fits: the whole value with it, the whole value, the short form with it, the short form, else null. The number comes
     * before the symbol, so a bar that showed its max before Show Symbols still does.
     */
    static String fitValue(Font font, String value, String symbol, int w, float s) {
        if (value == null) {
            return null;
        }
        String shortForm = shortValue(value);
        String[] tries = symbol == null ? new String[]{value, shortForm}
                : new String[]{symbol + value, value, shortForm == null ? null : symbol + shortForm, shortForm};
        for (String t : tries) {
            if (t != null && font.width(t) * s + 2 * VALUE_PAD_X <= w) {
                return t;
            }
        }
        return null;
    }

    /**
     * The value's short form, for a bar too short for all of it: the part before its slash (the current value), else
     * the part before " (" - the Defense bar's "996 (90.9%)" and the XP bar's "Level 30 (45%)" have no slash, so until
     * 2026-10-19 a short bar showed no number for them at all (killer560: "the defence bar is not filling in the defence
     * number"). Null when there is no shorter form.
     */
    static String shortValue(String value) {
        int slash = value.indexOf('/');
        if (slash > 0) {
            return value.substring(0, slash);
        }
        int paren = value.indexOf(" (");
        return paren > 0 ? value.substring(0, paren) : null;
    }

    /** The symbol Show Symbols puts in front of bar {@code r}'s number - Hypixel's own stat glyphs, the ones the text
     *  readouts already use - or null for a bar without one (the Other bar's number already ends in its icon). */
    static String symbol(Readout r) {
        return switch (r) {
            case HEALTH_BAR -> "❤ ";
            case MANA_BAR -> "✎ ";
            case DEFENCE_BAR -> "❈ ";
            case VITALITY_BAR -> BarPainter.VITALITY_GLYPH + " ";
            default -> null;
        };
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
        return r.bar ? readBar(r) != null : text(r, false) != null;
    }

    /** Width in its own units of what text readout {@code r} draws (its label in the editor while it has no value). */
    static int textWidth(Readout r, boolean preview) {
        String t = text(r, preview);
        return t == null ? 1 : measured(r, t);
    }

    // The width of each text readout's string, measured once per string and font (a frame asks for it from the layout, the
    // element's box and the noted draw rect, and an off readout in the editor's label path asks for it too). Strings
    // are rebuilt only when a value changes (liveTextCached), so identity is the key. Render thread.
    private static final String[] widthSource = new String[Readout.values().length];
    private static net.minecraft.client.gui.Font widthFont;
    private static final int[] widthValue = new int[Readout.values().length];

    private static int measured(Readout r, String t) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;
        if (!mc.isSameThread()) {
            return Math.max(1, font.width(t));
        }
        int i = r.ordinal();
        if (widthFont != font) {
            java.util.Arrays.fill(widthSource, null);
            widthFont = font;
        }
        if (widthSource[i] != t) {
            widthValue[i] = Math.max(1, font.width(t));
            widthSource[i] = t;
        }
        return widthValue[i];
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
            // The thickness as drawn (never under MIN_VALUE_THICKNESS while the number shows), so an edge drag starts
            // from the box he sees and moves it by exactly the dragged amount (testkit 399).
            return thickness(r);
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
            return textWidth(r, true);
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
                if (!preview) {
                    noteDrawn(graphics, x, y, width(), height());
                }
            }
        }

        private boolean drawBar(GuiGraphicsExtractor g, PlayerStatsConfig cfg, int x, int y, boolean preview) {
            String value = readBar(r);
            long cur = value == null ? 1 : outCur;
            long max = value == null ? 2 : outMax;
            boolean read = value != null;
            if (value == null) {
                if (!preview) {
                    return false;
                }
                // Nothing read yet: the HUD editor still gets a half-full bar to place.
                value = r.label;
            }
            int w = barWidthNow();
            int h = thickness(r);
            drawStyled(g, cfg, cfg.getBarStyle(), x, y, w, h, cur, max, value, read, preview);
            return true;
        }

        /** Sleek / Bold ({@link BarPainter}): the overflow layer is absorption on the health bar and overflow mana on
         *  the mana bar, each as a share of max; the number goes inside the track or beside it. */
        private void drawStyled(GuiGraphicsExtractor g, PlayerStatsConfig cfg, PlayerStatsConfig.BarStyle style, int x,
                                int y, int w, int h, long cur, long max, String value, boolean read, boolean preview) {
            float fill = max > 0 ? (float) Math.max(0.0, Math.min(1.0, (double) cur / max)) : 0f;
            float over = 0f;
            int overColor = 0;
            if (max > 0 && read) {
                if (r == Readout.HEALTH_BAR) {
                    long abs = absorption();
                    if (abs > 0) {
                        over = (float) Math.min(1.0, (double) abs / max);
                        overColor = cfg.getAbsorptionColor();
                    }
                } else if (r == Readout.MANA_BAR && PlayerStatsFeature.overflowMana > 0) {
                    over = (float) Math.min(1.0, (double) PlayerStatsFeature.overflowMana / max);
                    overColor = cfg.getOverflowManaColor();
                }
            }
            String shown = null;
            int reserve = 0;
            long now = System.nanoTime();
            float ts = textScale(g, cfg);
            if (cfg.isBarShowValue()) {
                Font font = Minecraft.getInstance().font;
                // Show Symbols (killer560, 2026-10-19: "add an option to show symbols as well for the bars").
                String symbol = cfg.isBarSymbols() ? symbol(r) : null;
                if (cfg.getBarValuePosition() == PlayerStatsConfig.ValuePosition.BESIDE) {
                    String full = withSymbol(r, value, symbol);
                    int need = columnReserve(g, r, x, besideReserve(r, font, full, ts), now);
                    // Beside only while the track keeps 24 units; else the number goes inside as usual.
                    if (w - need - BarPainter.iconRoom(cfg, w, h) >= 24) {
                        reserve = need;
                        shown = full;
                    }
                }
                if (reserve == 0) {
                    shown = fitValueCached(r, font, value, symbol, w - BarPainter.iconRoom(cfg, w, h), ts);
                }
            }
            BarPainter.draw(g, cfg, r, style, x, y, w, h, fill, over, overColor, shown, reserve, ts, preview, now);
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

    // ---- Bar numbers, built only when what they show changes (bars draw every frame). Render thread. ----

    private static final int N = Readout.values().length;
    private static final long[] keyA = new long[N];
    private static final long[] keyB = new long[N];
    private static final long[] keyC = new long[N];
    private static final Object[] keyD = new Object[N];
    private static final String[] valueText = new String[N];
    private static final String[] fitSource = new String[N];
    private static final int[] fitWidth = new int[N];
    private static final String[] fitOut = new String[N];
    private static final String[] fitSymbol = new String[N];
    private static final String[] symSource = new String[N];
    private static final String[] symSymbol = new String[N];
    private static final String[] symOut = new String[N];
    private static final float[] fitScale = new float[N];
    private static final float[] reserveScale = new float[N];
    // Beside numbers in one column (2026-10-12, left to us by killer560: bars stacked with the number beside them end at
    // one length). Each bar records its left edge in GUI units and the room its own number needs; a bar takes the widest
    // need among the bars drawn in the last quarter second whose left edges are within COLUMN_SLACK of its own.
    private static final float[] colLeft = new float[N];
    private static final float[] colNeed = new float[N];
    private static final long[] colAt = new long[N];
    static final float COLUMN_SLACK = 3f;
    private static final long COLUMN_FRESH_NANOS = 250_000_000L;
    private static final String[] reserveSource = new String[N];
    private static final int[] reserveWidth = new int[N];
    private static final long[] reserveMaxKey = new long[N];
    /** Units between a bar's end and its number when the number goes Beside. */
    public static final int BESIDE_GAP = 3;
    /** cur and max of the bar {@link #readBar} read last. */
    private static long outCur;
    private static long outMax;

    /**
     * Reads bar {@code r} into {@link #outCur}/{@link #outMax} and returns the number drawn on it, or null when nothing
     * has been read for it. The string is rebuilt only when what it shows changed, so a steady bar allocates nothing.
     */
    static String readBar(Readout r) {
        long cur;
        long max;
        long a;
        Object d = null;
        switch (r) {
            case HEALTH_BAR -> {
                cur = PlayerStatsFeature.healthCur;
                max = PlayerStatsFeature.healthMax;
                // Only the action bar's own health is drawn: no vanilla fallback (killer560, 2026-10-18). Until it
                // has been read, cur is -1 and the bar draws nothing.
                a = cur;
            }
            case MANA_BAR -> {
                cur = PlayerStatsFeature.manaCur;
                max = PlayerStatsFeature.manaMax;
                a = cur;
            }
            case DEFENCE_BAR -> {
                long def = PlayerStatsFeature.defenceValue;
                // Damage reduction as a fraction of 1000 so the shared fill code can use it.
                cur = def < 0 ? -1 : Math.round(1000.0 * def / (def + 100.0));
                max = 1000;
                a = def;
            }
            case VITALITY_BAR -> {
                cur = PlayerStatsFeature.vitalityCur;
                max = PlayerStatsFeature.vitalityMax;
                a = cur;
            }
            case XP_BAR -> {
                net.minecraft.world.entity.player.Player p = Minecraft.getInstance().player;
                if (p == null) {
                    return null;
                }
                cur = Math.round(1000.0 * xpProgress(p));
                max = 1000;
                a = p.experienceLevel;
                d = null;
            }
            default -> {
                cur = PlayerStatsFeature.otherCur;
                max = PlayerStatsFeature.otherMax;
                a = cur;
                d = PlayerStatsFeature.otherIcon;
            }
        }
        if (cur < 0) {
            return null;
        }
        int i = r.ordinal();
        if (valueText[i] == null || keyA[i] != a || keyB[i] != max || keyC[i] != cur || keyD[i] != d) {
            valueText[i] = switch (r) {
                case DEFENCE_BAR -> fmt(a) + " (" + String.format(Locale.US, "%.1f", cur / 10.0) + "%)";
                case XP_BAR -> xpLabel(a, cur / 1000f);
                case OTHER_BAR -> fmt(cur) + "/" + fmt(max) + PlayerStatsFeature.otherIcon;
                default -> fmt(cur) + "/" + fmt(max);
            };
            keyA[i] = a;
            keyB[i] = max;
            keyC[i] = cur;
            keyD[i] = d;
        }
        outCur = cur;
        outMax = max;
        return valueText[i];
    }

    /** {@link #fitValue}, remembered for the last string, symbol and width, so a steady bar cuts no string per frame. */
    static String fitValueCached(Readout r, Font font, String value, String symbol, int w, float s) {
        int i = r.ordinal();
        if (fitSource[i] != value || fitSymbol[i] != symbol || fitWidth[i] != w || fitScale[i] != s) {
            fitSource[i] = value;
            fitSymbol[i] = symbol;
            fitWidth[i] = w;
            fitScale[i] = s;
            fitOut[i] = fitValue(font, value, symbol, w, s);
        }
        return fitOut[i];
    }

    /** {@code symbol + value}, built only when either changes; {@code value} itself when there is no symbol. */
    static String withSymbol(Readout r, String value, String symbol) {
        if (symbol == null || value == null) {
            return value;
        }
        int i = r.ordinal();
        if (symSource[i] != value || symSymbol[i] != symbol) {
            symSource[i] = value;
            symSymbol[i] = symbol;
            symOut[i] = symbol + value;
        }
        return symOut[i];
    }

    /** Room a Beside number needs after the bar: the widest number the bar has shown since its max last changed, so the
     *  bar does not change length as the digits do. */
    static int besideReserve(Readout r, Font font, String value, float s) {
        int i = r.ordinal();
        if (reserveSource[i] != value || reserveScale[i] != s) {
            reserveSource[i] = value;
            reserveScale[i] = s;
            // The glyphs plus the outline's unit after them, at the number's scale.
            int now = (int) Math.ceil(font.width(value) * s) + BESIDE_GAP;
            if (reserveMaxKey[i] != outMax) {
                reserveMaxKey[i] = outMax;
                reserveWidth[i] = now;
            } else {
                reserveWidth[i] = Math.max(reserveWidth[i], now);
            }
        }
        return reserveWidth[i];
    }

    /** The Beside room bar {@code r} (left edge {@code x} in its own units) uses: the widest {@code need} among the bars
     *  of its column, in its own units. Records its own need for the others. */
    static int columnReserve(GuiGraphicsExtractor g, Readout r, int x, int need, long now) {
        var pose = g.pose();
        float sx = pose.m00();
        if (sx <= 0f) {
            return need;
        }
        int i = r.ordinal();
        float left = sx * x + pose.m20();
        colLeft[i] = left;
        colNeed[i] = need * sx;
        colAt[i] = now;
        float best = colNeed[i];
        for (int j = 0; j < N; j++) {
            if (j != i && colAt[j] != 0L && now - colAt[j] < COLUMN_FRESH_NANOS
                    && Math.abs(colLeft[j] - left) <= COLUMN_SLACK) {
                best = Math.max(best, colNeed[j]);
            }
        }
        return Math.max(need, (int) Math.ceil(best / sx - 1e-3f));
    }

    /** The readout a text element draws: the live value, or (in the HUD editor) its label until there is one; null
     *  when it draws nothing. */
    private static String text(Readout r, boolean preview) {
        String text = liveTextCached(r);
        if (text == null && preview) {
            text = r.label;
        }
        return text;
    }

    // The text readouts' strings, rebuilt only when a number they show changes: a frame asked for each one three times
    // (hasValue for the layout, textWidth, the draw), and each answer was one or two String.format("%,d") calls
    // (2026-10-15). Keys are the inputs liveText reads for that readout. Render thread only; anywhere else just builds.
    private static final long[] textKeyA = new long[N];
    private static final long[] textKeyB = new long[N];
    private static final Object[] textKeyC = new Object[N];
    private static final boolean[] textValid = new boolean[N];
    private static final String[] textValue = new String[N];

    private static String liveTextCached(Readout r) {
        if (!Minecraft.getInstance().isSameThread()) {
            return liveText(r);
        }
        long a;
        long b;
        Object c = null;
        switch (r) {
            case HEALTH_TEXT -> {
                a = PlayerStatsFeature.healthCur;
                b = PlayerStatsFeature.healthMax;
            }
            case MANA_TEXT -> {
                a = PlayerStatsFeature.manaCur;
                b = PlayerStatsFeature.manaMax;
            }
            case OVERFLOW_TEXT -> {
                a = PlayerStatsFeature.overflowMana;
                b = 0;
            }
            case INTELLIGENCE_TEXT -> {
                a = PlayerStatsFeature.manaMax;
                b = 0;
            }
            case DEFENCE_TEXT -> {
                a = PlayerStatsFeature.defenceValue;
                b = 0;
            }
            case EFFECTIVE_HEALTH_TEXT -> {
                a = PlayerStatsFeature.healthCur;
                b = PlayerStatsFeature.defenceValue;
            }
            case VITALITY_TEXT -> {
                a = PlayerStatsFeature.vitalityCur;
                b = PlayerStatsFeature.vitalityMax;
            }
            case XP_TEXT -> {
                net.minecraft.world.entity.player.Player p = Minecraft.getInstance().player;
                if (p == null) {
                    return null;
                }
                a = p.experienceLevel;
                b = Float.floatToIntBits(xpProgress(p));
            }
            default -> {
                a = PlayerStatsFeature.otherCur;
                b = PlayerStatsFeature.otherMax;
                c = PlayerStatsFeature.otherIcon;
            }
        }
        int i = r.ordinal();
        if (!textValid[i] || textKeyA[i] != a || textKeyB[i] != b || textKeyC[i] != c) {
            textValue[i] = liveText(r);
            textKeyA[i] = a;
            textKeyB[i] = b;
            textKeyC[i] = c;
            textValid[i] = true;
        }
        return textValue[i];
    }

    private static String liveText(Readout r) {
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
                net.minecraft.world.entity.player.Player p = Minecraft.getInstance().player;
                yield p == null ? null : xpLabel(p.experienceLevel, xpProgress(p));
            }
            default -> PlayerStatsFeature.otherCur < 0 ? null
                    : fmt(PlayerStatsFeature.otherCur) + "/" + fmt(PlayerStatsFeature.otherMax)
                    + PlayerStatsFeature.otherIcon;
        };
        return text;
    }

    /** The client player's experience progress, 0..1. Clamped: it is whatever the server sent, and a server repurposing
     *  the bar can send anything. */
    private static float xpProgress(net.minecraft.world.entity.player.Player p) {
        float progress = p.experienceProgress;
        if (Float.isNaN(progress)) {
            progress = 0f;
        }
        return Math.max(0f, Math.min(1f, progress));
    }

    /** "Level 30 (45%)": the level number the vanilla bar shows over itself, and how far the bar is filled. */
    private static String xpLabel(long level, float progress) {
        return "Level " + fmt(level) + " (" + Math.round(progress * 100f) + "%)";
    }

    private static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }
}
