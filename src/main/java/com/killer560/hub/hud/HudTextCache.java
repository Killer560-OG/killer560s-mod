package com.killer560.hub.hud;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The visual-order text of the mod's own HUD strings, kept between frames.
 *
 * <p>{@code GuiGraphicsExtractor.text(Font, String, ...)} turns the string into a {@code FormattedCharSequence} with
 * {@code Language.getVisualOrder(FormattedText.of(s))} on every call (javap, 26.1.2 and 26.2), and that runs ICU's
 * {@code Bidi} over the text and splits it into styled runs - every string, every frame. A {@code Component} keeps its
 * visual order; a {@code String} does not, and nearly every HUD line in this mod is drawn as a String (95-fps-bench JFR:
 * Bidi under the Secrets HUD, Advanced Position and the Custom Scoreboard, 2026-10-07).
 *
 * <p>While {@link #begin()}..{@link #end()} is open (the mod's own HUD layers, render thread only),
 * {@code hud/mixin/HudTextVisualOrderMixin} draws such a string with the sequence kept here for the same text. The
 * value is exactly what vanilla computes - the same call on the same {@link Language} - and the cache is dropped when
 * the language object changes, which is the only other input. Outside those layers (vanilla, other mods, every
 * screen) nothing changes.
 */
public final class HudTextCache {

    private static final int MAX = 512;

    private static final Map<String, FormattedCharSequence> CACHE = new LinkedHashMap<>(MAX * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FormattedCharSequence> eldest) {
            return size() > MAX;
        }
    };

    private static Language cachedFor;
    private static int depth;

    private HudTextCache() {
    }

    /** Opens the scope (render thread). Always pair with {@link #end()} in a finally. */
    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) {
            depth--;
        }
    }

    public static boolean active() {
        return depth > 0;
    }

    /** {@code Language.getInstance().getVisualOrder(FormattedText.of(text))}, from the cache when it has it. */
    public static FormattedCharSequence visualOrder(String text) {
        Language language = Language.getInstance();
        if (language != cachedFor) {
            CACHE.clear();
            cachedFor = language;
        }
        FormattedCharSequence seq = CACHE.get(text);
        if (seq == null) {
            seq = language.getVisualOrder(FormattedText.of(text));
            CACHE.put(text, seq);
        }
        return seq;
    }
}
