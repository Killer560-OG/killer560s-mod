package com.killer560.hub.fonts;

import com.killer560.hub.fonts.mixin.FontManagerAccessor;
import com.killer560.hub.fonts.mixin.FontSetAccessor;
import com.killer560.hub.fonts.mixin.MinecraftFontManagerAccessor;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The chosen font, built through Minecraft's own font provider system (killer560, 2026-10-15).
 * <p>
 * A {@link TrueTypeGlyphProvider} is made from the font file exactly as vanilla's {@code ttf} provider definition makes
 * one (FreeType face from memory, unicode charmap), and put FIRST in a {@link FontSet} of its own whose remaining
 * providers are the default font's, so any character the font lacks (Hypixel's symbols, accents, CJK) still comes from
 * Minecraft's font. Glyphs are baked into that set's own atlas by vanilla's {@link GlyphStitcher}. Nothing per draw call:
 * {@code fonts.mixin.FontGlyphSourceMixin} swaps the glyph source {@code Font.getGlyphSource(FontDescription)} returns
 * for the default font description, which every width and every prepared glyph goes through, and only while
 * {@link #applies(Font)} - always for Everything, while one of the mod's screens is being built or drawn for Mod Menu Only
 * ({@link FontScope}). Fonts with another description (the enchanting table's, a resource pack's own) are left alone.
 * <p>
 * The set is rebuilt on the client thread at END_CLIENT_TICK when the choice, the size or the GUI scale changes (the
 * glyphs are oversampled to the GUI scale so they stay sharp), and dropped the moment the font manager reloads, because
 * the default providers it borrows are closed then.
 */
public final class CustomFonts {

    private static final Logger LOGGER = ModLog.get("killer560smod-fonts");

    private static volatile GlyphSource anyGlyphs;
    private static volatile GlyphSource nonFishyGlyphs;
    private static volatile boolean everywhere;

    private static FontSet set;
    private static TrueTypeGlyphProvider ttf;
    private static int builtVersion = Integer.MIN_VALUE;
    private static int builtScale = -1;
    private static boolean dirty = true;
    private static int generation;
    private static String builtId = FontChoices.MINECRAFT;
    private static volatile String lastError;
    private static boolean registered;
    /** Builds done, for the testkit (a font that never built is not a font that works). */
    private static volatile int builds;

    private CustomFonts() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        FontChoices.rescan();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FontScope.resetDepth();
            refresh(client);
        });
    }

    // ------------------------------------------------------------------ the hook

    /** Called by the Font mixin for every glyph-source lookup; returns {@code original} unless the font applies. */
    public static GlyphSource replace(Font font, GlyphSource original, FontDescription description) {
        GlyphSource any = anyGlyphs;
        if (any == null) {
            return original;
        }
        if (description != FontDescription.DEFAULT && !FontDescription.DEFAULT.equals(description)) {
            return original;
        }
        if (!everywhere && !FontScope.menuNow()) {
            return original;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && font == mc.fontFilterFishy) {
            GlyphSource nf = nonFishyGlyphs;
            return nf != null ? nf : original;
        }
        return any;
    }

    /** True while a custom font is built and would be used for {@code font}'s default text right now. */
    public static boolean applies(Font font) {
        return anyGlyphs != null && (everywhere || FontScope.menuNow());
    }

    /** A custom font is built (any scope). */
    public static boolean active() {
        return anyGlyphs != null;
    }

    /** Whether Mod Menu Only scoping is wanted (a font built and its scope is the mod's screens). */
    static boolean menuScoped() {
        return anyGlyphs != null && !everywhere;
    }

    public static String lastError() {
        return lastError;
    }

    public static String builtId() {
        return builtId;
    }

    public static int builds() {
        return builds;
    }

    /** The mod's own screens (the same test Auto Scale uses): any screen class of this mod. */
    public static boolean isModScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("com.killer560.hub.");
    }

    // ------------------------------------------------------------------ lifecycle

    /** The font manager is about to reload: the default providers this set borrows are about to be closed. */
    public static void dropForReload() {
        anyGlyphs = null;
        nonFishyGlyphs = null;
        closeSet();
        dirty = true;
    }

    /** Something the set depends on changed (font options): rebuild at the next tick. */
    public static void markDirty() {
        dirty = true;
    }

    /** Rebuilds now if anything changed (client thread). */
    public static void refresh(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        FontConfig cfg = FontConfig.getInstance();
        int scale = Math.max(1, (int) Math.ceil(mc.getWindow().getGuiScale()));
        everywhere = cfg.getScope() == FontConfig.Scope.EVERYTHING;
        if (!dirty && builtVersion == FontConfig.version() && builtScale == scale) {
            return;
        }
        dirty = false;
        builtVersion = FontConfig.version();
        builtScale = scale;
        rebuild(mc, cfg, scale);
    }

    /** Forces the next {@link #refresh} to rebuild and runs it now (Reload Fonts). */
    public static void reloadNow(Minecraft mc) {
        FontChoices.rescan();
        dirty = true;
        refresh(mc);
    }

    private static void rebuild(Minecraft mc, FontConfig cfg, int scale) {
        anyGlyphs = null;
        nonFishyGlyphs = null;
        closeSet();
        lastError = null;
        builtId = FontChoices.MINECRAFT;
        FontChoices.Choice choice = FontChoices.find(cfg.getFont());
        if (choice == null) {
            if (!FontChoices.MINECRAFT.equals(cfg.getFont())) {
                lastError = "Font not found: " + cfg.getFont().replaceFirst("^[a-z]+:", "");
            }
            return;
        }
        if (choice.isMinecraft()) {
            return;
        }
        TrueTypeGlyphProvider provider = null;
        try {
            FontManager manager = ((MinecraftFontManagerAccessor) mc).killer560smod$fontManager();
            FontSet vanilla = ((FontManagerAccessor) manager).killer560smod$fontSets().get(Minecraft.DEFAULT_FONT);
            List<GlyphProvider.Conditional> fallback = vanilla == null ? List.of()
                    : ((FontSetAccessor) vanilla).killer560smod$allProviders();
            float size = choice.sizePx() * cfg.getSize() / 100f;
            // Oversampled to the GUI scale: a glyph pixel is a screen pixel at GUI scale, crisp instead of stretched.
            float oversample = Math.max(2f, scale);
            provider = load(readBytes(choice), size, oversample, 0f, choice.shiftY());
            List<GlyphProvider.Conditional> providers = new ArrayList<>();
            providers.add(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS));
            if (fallback != null) {
                providers.addAll(fallback);
            }
            GlyphStitcher stitcher = new GlyphStitcher(mc.getTextureManager(),
                    Identifier.fromNamespaceAndPath("killer560smod", "custom_font_" + (++generation)));
            FontSet fresh = new FontSet(stitcher);
            fresh.reload(providers, fontOptions(mc));
            set = fresh;
            ttf = provider;
            provider = null;
            anyGlyphs = fresh.source(false);
            nonFishyGlyphs = fresh.source(true);
            builtId = choice.id();
            builds++;
            LOGGER.info("[Fonts] Using {} at {}px (oversample {}) for {}", choice.label(), size, oversample,
                    cfg.getScope().label);
        } catch (Throwable t) {
            lastError = "Could not load " + choice.label() + ": " + t.getMessage();
            LOGGER.warn("[Fonts] could not load {}", choice.id(), t);
            if (provider != null) {
                try {
                    provider.close();
                } catch (Throwable ignored) {
                }
            }
            closeSet();
        }
    }

    private static void closeSet() {
        FontSet old = set;
        TrueTypeGlyphProvider oldTtf = ttf;
        set = null;
        ttf = null;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable t) {
                LOGGER.warn("[Fonts] closing the old font set failed", t);
            }
        }
        if (oldTtf != null) {
            try {
                oldTtf.close();
            } catch (Throwable t) {
                LOGGER.warn("[Fonts] closing the old font failed", t);
            }
        }
    }

    private static Set<FontOption> fontOptions(Minecraft mc) {
        Set<FontOption> out = EnumSet.noneOf(FontOption.class);
        if (mc.options.forceUnicodeFont().get()) {
            out.add(FontOption.UNIFORM);
        }
        if (mc.options.japaneseGlyphVariants().get()) {
            out.add(FontOption.JAPANESE_VARIANTS);
        }
        return out;
    }

    private static byte[] readBytes(FontChoices.Choice choice) throws Exception {
        if (choice.file() != null) {
            return Files.readAllBytes(choice.file());
        }
        try (InputStream in = CustomFonts.class.getResourceAsStream(choice.resource())) {
            if (in == null) {
                throw new IllegalStateException("missing bundled font " + choice.resource());
            }
            return in.readAllBytes();
        }
    }

    /**
     * A FreeType face from memory and the provider around it - vanilla's {@code TrueTypeGlyphProviderDefinition.load}
     * (javap, 26.1.2 and 26.2), minus its "TrueType only" check: FreeType reads OpenType CFF outlines (.otf) the same
     * way. The provider owns the buffer and the face once built and frees both in {@code close()}.
     */
    static TrueTypeGlyphProvider load(byte[] bytes, float size, float oversample, float shiftX, float shiftY) throws Exception {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        FT_Face face = null;
        try {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer out = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, out),
                            "Initializing font face");
                    face = FT_Face.create(out.get());
                }
                String format = FreeType.FT_Get_Font_Format(face);
                if (!"TrueType".equals(format) && !"CFF".equals(format)) {
                    throw new IllegalStateException("not a TrueType or OpenType font (" + format + ")");
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap");
                return new TrueTypeGlyphProvider(buffer, face, size, oversample, shiftX, shiftY, "");
            }
        } catch (Throwable t) {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                if (face != null) {
                    FreeType.FT_Done_Face(face);
                }
            }
            MemoryUtil.memFree(buffer);
            throw t;
        }
    }
}
