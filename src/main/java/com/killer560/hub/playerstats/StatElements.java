package com.killer560.hub.playerstats;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * The custom stat bars and text readouts under Health and Mana Bars (killer560, 2026-10-04: "make custom
 * health, intel, vitality, defence, true defence, and other such bars that a player may find useful ... make
 * options for custom text, custom bars and whatnot all scalable"). Every readout is its own {@link HudElement},
 * so each one moves and scales on its own in the HUD editor (and from the scale slider in its settings row),
 * and is drawn in game by {@code HudInGameRenderer}.
 * <p>
 * Only what Hypixel actually puts on the action bar can be shown live: health, mana, defence, overflow mana
 * and one further "current/max" resource with its own icon (see {@link PlayerStatsFeature}). True Defence and
 * Vitality never appear there - they are only in the /stats menu - so they have no bar. Intelligence is not
 * on the action bar either, but max mana is 100 + Intelligence, so it is derived from the mana segment.
 * The Defence bar shows damage reduction, defence / (defence + 100), since defence itself has no maximum.
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
        OTHER_TEXT("other_text", "stattext_other", "Other Resource Text", false, 0xFFAA55FF);

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
            HudElementRegistry.register(new Element(r));
        }
    }

    /** True for an id this class registered - {@code HudInGameRenderer} draws these. */
    public static boolean isStatElementId(String id) {
        return id.startsWith("statbar_") || id.startsWith("stattext_");
    }

    private static final class Element implements HudElement {

        private final Readout r;

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
                return PlayerStatsConfig.getInstance().getBarWidth();
            }
            return 90;
        }

        @Override
        public int height() {
            if (r.bar) {
                PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
                return cfg.isBarShowValue() ? Math.max(cfg.getBarHeight(), 9) : cfg.getBarHeight();
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
            int w = cfg.getBarWidth();
            int h = cfg.getBarHeight();
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
                default -> PlayerStatsFeature.otherCur < 0 ? null
                        : fmt(PlayerStatsFeature.otherCur) + "/" + fmt(PlayerStatsFeature.otherMax)
                        + PlayerStatsFeature.otherIcon;
            };
            if (text == null) {
                if (!preview) {
                    return false;
                }
                text = r.label;
            }
            g.text(Minecraft.getInstance().font, text, x, y, cfg.getReadoutColor(r), cfg.isTextShadow());
            return true;
        }
    }

    private static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }
}
