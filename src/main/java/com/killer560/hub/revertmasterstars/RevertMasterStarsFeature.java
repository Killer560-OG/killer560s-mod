package com.killer560.hub.revertmasterstars;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Optional;

/**
 * Reverts Hypixel's real Master Star item-name display back to the old all-red-stars look, ported from
 * QUOI's own {@code RevertMasterStars.kt}. Real mechanic: Hypixel changed Master Star items' real
 * display name from "some stars red, rest white" to a single numbered pip character (➊-➎) appended
 * after the real "✪✪✪✪✪" star sequence; this finds that real pattern and recolors exactly as many
 * leading stars red as the pip number says, removing the pip character itself - a purely cosmetic
 * text-recolor of the item's own hover name, no gameplay effect, no new item data.
 */
public final class RevertMasterStarsFeature {

    private static final String STAR_SEQUENCE = "✪✪✪✪✪";
    private static final char FIRST_MASTER_STAR = '➊';
    private static final char LAST_MASTER_STAR = '➎';

    private RevertMasterStarsFeature() {
    }

    /**
     * Name object -> what {@link #rebuild} made of it (the same object when it has no master stars). On the client
     * thread only: {@code ItemStack.getHoverName} runs for every slot, tooltip and item label drawn, each frame, and
     * the answer is a pure function of the name's text and styles, which a stack's name object never changes - so the
     * text is built and searched once per name instead of once per call (mixin FPS pass, 2026-10-15). Other threads
     * (the integrated server) compute it as before.
     */
    private static final java.util.Map<Component, Component> CACHE = new java.util.IdentityHashMap<>();

    public static Component modifyHoverName(Component component) {
        if (!RevertMasterStarsConfig.getInstance().isEnabled()) {
            return component;
        }
        if (component == null) {
            return rebuild(component);
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) {
            return rebuild(component);
        }
        Component known = CACHE.get(component);
        if (known == null) {
            known = rebuild(component);
            if (CACHE.size() >= 2048) {
                CACHE.clear();
            }
            CACHE.put(component, known);
        }
        return known;
    }

    private static Component rebuild(Component component) {
        String text = component.getString();
        int starsStart = findMasterStars(text);
        if (starsStart == -1) {
            return component;
        }

        int masterStarIndex = starsStart + STAR_SEQUENCE.length();
        int redStarsEnd = starsStart + (text.charAt(masterStarIndex) - FIRST_MASTER_STAR + 1);
        MutableComponent result = Component.empty();
        int[] segmentStart = {0};

        component.visit((style, value) -> {
            appendSegment(result, value, style, segmentStart[0], starsStart, redStarsEnd, masterStarIndex);
            segmentStart[0] += value.length();
            return Optional.empty();
        }, Style.EMPTY);

        return result;
    }

    private static int findMasterStars(String text) {
        int searchFrom = 0;
        while (true) {
            int starsStart = text.indexOf(STAR_SEQUENCE, searchFrom);
            if (starsStart == -1) {
                return -1;
            }
            int pipIndex = starsStart + STAR_SEQUENCE.length();
            if (pipIndex < text.length()) {
                char masterStar = text.charAt(pipIndex);
                if (masterStar >= FIRST_MASTER_STAR && masterStar <= LAST_MASTER_STAR) {
                    return starsStart;
                }
            }
            searchFrom = starsStart + 1;
        }
    }

    private static void appendSegment(MutableComponent result, String value, Style style, int segmentStart,
                                       int redStart, int redEnd, int removedIndex) {
        int segmentEnd = segmentStart + value.length();
        int cursor = segmentStart;

        while (cursor < segmentEnd) {
            if (cursor == removedIndex) {
                cursor++;
                continue;
            }

            int nextBoundary = segmentEnd;
            if (redStart > cursor) {
                nextBoundary = Math.min(nextBoundary, redStart);
            }
            if (redEnd > cursor) {
                nextBoundary = Math.min(nextBoundary, redEnd);
            }
            if (removedIndex > cursor) {
                nextBoundary = Math.min(nextBoundary, removedIndex);
            }

            int localStart = cursor - segmentStart;
            int localEnd = nextBoundary - segmentStart;
            Style outputStyle = (cursor >= redStart && cursor < redEnd)
                    ? style.withColor(ChatFormatting.RED)
                    : style;

            result.append(Component.literal(value.substring(localStart, localEnd)).withStyle(outputStyle));
            cursor = nextBoundary;
        }
    }
}
