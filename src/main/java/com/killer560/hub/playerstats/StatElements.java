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
                return PlayerStatsConfig.getInstance().getBarWidth(r);
            }
            // The drawn readout (2026-10-07 box audit: it was a fixed 90); its label when there is no value yet.
            String text = text(true);
            return Math.max(1, Minecraft.getInstance().font.width(text));
        }

        @Override
        public int height() {
            if (r.bar) {
                PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
                return cfg.isBarShowValue() ? Math.max(cfg.getBarHeight(r), 9) : cfg.getBarHeight(r);
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
            if (!cfg.isEnabled() || !cfg.isReadoutOn(r) || HudVisibility.hidesHud()) {
                return;
            }
            boolean preview = HudVisibility.editorOpen();
            boolean drew = r.bar ? drawBar(graphics, cfg, x, y, preview) : drawText(graphics, cfg, x, y, preview);
            if (drew) {
                HudSeen.markDrawn(id());
            }
        }

        private boolean drawBar(GuiGraphicsExtractor g, PlayerStatsConfig cfg, int x, int y, boolean preview) {
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
            if (value == null) {
                if (!preview) {
                    return false;
                }
                // Nothing read yet: the HUD editor still gets a half-full bar to place.
                cur = 1;
                max = 2;
                value = r.label;
            }
            int w = cfg.getBarWidth(r);
            int h = cfg.getBarHeight(r);
            int top = y + Math.max(0, (height() - h) / 2);
            g.fill(x, top, x + w, top + h, cfg.getBarBackground());
            int color = cfg.getReadoutColor(r);
            if (max > 0) {
                if (r == Readout.HEALTH_BAR && cur > max) {
                    // Past max health (absorption): the whole bar is health, and the share of the total that is
                    // over the max is drawn at the right end in the absorption colour.
                    g.fill(x, top, x + w, top + h, color);
                    int over = (int) Math.round(w * (double) (cur - max) / cur);
                    if (over > 0) {
                        g.fill(x + w - over, top, x + w, top + h, cfg.getAbsorptionColor());
                    }
                } else {
                    int filled = (int) Math.round(w * Math.max(0.0, Math.min(1.0, (double) cur / max)));
                    if (filled > 0) {
                        g.fill(x, top, x + filled, top + h, color);
                    }
                }
            }
            if (cfg.isBarShowValue()) {
                Font font = Minecraft.getInstance().font;
                int tx = x + (w - font.width(value)) / 2;
                int ty = y + (height() - 8) / 2;
                g.text(font, value, tx, ty, 0xFFFFFFFF, true);
            }
            return true;
        }

        private boolean drawText(GuiGraphicsExtractor g, PlayerStatsConfig cfg, int x, int y, boolean preview) {
            String text = text(preview);
            if (text == null) {
                return false;
            }
            g.text(Minecraft.getInstance().font, text, x, y, cfg.getReadoutColor(r), cfg.isTextShadow());
            return true;
        }

        /** The readout drawText draws: the live value, or (in the HUD editor) its label until there is one; null
         *  when it draws nothing. */
        private String text(boolean preview) {
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
