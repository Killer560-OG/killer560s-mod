package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drop-in replacement for vanilla {@link net.minecraft.client.gui.components.Button}, matching its
 *  builder API shape exactly (same method names/chaining, a {@code Component}+{@code OnPress}
 *  constructor argument pair, {@code .bounds(x,y,w,h).build()}) so every existing
 *  {@code Button.builder(...)} call site across the mod's tab files converts to
 *  {@code SettingsButtonWidget.builder(...)} with just the class name changed - no other code needed to
 *  change, since {@code btn -> { ...; btn.setMessage(...); }} lambdas keep working unchanged (this class
 *  also extends {@link AbstractWidget} and exposes the same inherited {@code getMessage()}/
 *  {@code setMessage(Component)}) and embedded legacy "§" color codes in a label still render correctly
 *  via the {@code Component} text-draw overload, exactly like vanilla {@code Button} already did.
 *  <p>
 *  Built (2026-09-07), part 2 of the black+amber GUI overhaul: vanilla {@code Button} has no way to
 *  recolor its own fixed 9-slice texture, so once the menu chrome (phase 1) was reworked, every actual
 *  settings control still rendered as a plain grey vanilla button, clashing with the new theme - per
 *  killer560's "make those boxes a different color, something more fitting." Deliberately styled with a
 *  DIMMER border than {@link MenuRowWidget}'s accordion headers (full amber, always) so headers still
 *  visually lead over individual controls instead of every box in the menu competing at once.
 *  <p>
 *  Extended (2026-09-27) per killer560's "for the sort buttons or any button that toggles through a
 *  bunch of options, make it so if i right click then it goes back one" - a second, optional
 *  {@code OnPress} set via {@code .secondaryPress(...)} fires on right-click instead of left. Left-click
 *  is untouched: {@code onClick(...)} below is still only ever invoked by vanilla's own left-button-only
 *  {@code mouseClicked}, so every existing call site with no {@code secondaryPress} keeps behaving
 *  exactly as before (right-click on it simply does nothing, same as pre-2026-09-27). Only call sites
 *  that opt in with {@code .secondaryPress(...)} - the enum-cycling buttons - respond to right-click. */
public final class SettingsButtonWidget extends AbstractWidget {

    private static final int BORDER = 0xFF663D1A;
    private static final int BORDER_HOVER = 0xFFCC6600;
    private static final int BG = 0xFF1A1A1A;
    private static final int BG_HOVER = 0xFF262626;
    private static final int TEXT = 0xFFFFFFFF;
    /** The {@link Builder#primary()} style: an amber-filled box with a full-amber border, for a screen's one main
     *  action (the Auto Routes node editor's Go To) so it reads first among plain buttons. */
    private static final int PRIMARY_BORDER = 0xFFCC6600;
    private static final int PRIMARY_BORDER_HOVER = 0xFFFFA040;
    private static final int PRIMARY_BG = 0xFF4A2A08;
    private static final int PRIMARY_BG_HOVER = 0xFF63380B;
    /** The {@link Builder#danger()} style: a red box for a destructive action waiting on its confirming click
     *  (killer560, 2026-10-11: "if i press the reset button it then turns red when it asks if you want to reset it"). */
    private static final int DANGER_BORDER = 0xFFCC2222;
    private static final int DANGER_BORDER_HOVER = 0xFFFF4444;
    private static final int DANGER_BG = 0xFF5A1010;
    private static final int DANGER_BG_HOVER = 0xFF741616;

    public interface OnPress {
        void onPress(SettingsButtonWidget button);
    }

    private final OnPress onPress;
    private final OnPress onSecondaryPress;
    private final boolean primary;
    private final boolean danger;

    private SettingsButtonWidget(Component message, OnPress onPress, OnPress onSecondaryPress, boolean primary,
            boolean danger, int x, int y, int width, int height) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.onSecondaryPress = onSecondaryPress;
        this.primary = primary;
        this.danger = danger;
    }

    /** Whether this was built with {@link Builder#danger()} (read by the testkit). */
    public boolean isDanger() {
        return danger;
    }

    public static Builder builder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    /** One step of a {@link #cycling} button: {@code dir} is +1 for a left click and -1 for a right click. */
    public interface OnStep {
        void onStep(SettingsButtonWidget button, int dir);
    }

    /**
     * A button that steps through a setting's values: left click forward, right click back, both wrapping (killer560,
     * 2026-10-11: "for any setting that you click a few times to toggle through different things make it so if i left
     * click it goes forward and right click it goes backward"). Every cycling setting is built through this, so
     * {@link #isCycling()} names them all (the testkit's 806 walks the menu by it).
     */
    public static Builder cycling(Component message, OnStep onStep) {
        return new Builder(message, b -> onStep.onStep(b, 1)).secondaryPress(b -> onStep.onStep(b, -1));
    }

    /** The constant {@code dir} steps away from {@code current} in declaration order, wrapping at both ends. */
    public static <E extends Enum<E>> E step(E current, int dir) {
        E[] all = current.getDeclaringClass().getEnumConstants();
        return all[Math.floorMod(current.ordinal() + dir, all.length)];
    }

    /** {@code index} moved by {@code dir} in a list of {@code size}, wrapping at both ends. */
    public static int step(int index, int dir, int size) {
        return Math.floorMod(index + dir, size);
    }

    /**
     * A value on the grid {@code min, min + step, ...} up to {@code max}, moved one grid step by {@code dir} and wrapped
     * at both ends (past the top goes to {@code min}, below {@code min} to the top grid value). An off-grid value snaps
     * to the nearest grid point first.
     */
    public static double step(double value, double step, double min, double max, int dir) {
        int count = (int) Math.floor((max - min) / step + 1e-6) + 1;
        int idx = (int) Math.round((value - min) / step);
        idx = Math.floorMod(Math.max(0, Math.min(count - 1, idx)) + dir, count);
        return Math.round((min + idx * step) * 1e6) / 1e6;
    }

    /** True for a button that steps through values (built with a right-click handler). Read by the testkit. */
    public boolean isCycling() {
        return onSecondaryPress != null;
    }

    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private OnPress onSecondaryPress;
        private boolean primary;
        private boolean danger;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        /** Right-click handler - used by cycling/toggle buttons to step backwards instead of forwards.
         *  Left unset (the default) for ordinary buttons, so right-click on them stays a no-op. */
        public Builder secondaryPress(OnPress onSecondaryPress) {
            this.onSecondaryPress = onSecondaryPress;
            return this;
        }

        /** Draws this as the screen's primary action (amber fill and border) instead of the plain dark box. */
        public Builder primary() {
            this.primary = true;
            return this;
        }

        /** Draws this red: a destructive action asking for its confirming click. Wins over {@link #primary()}. */
        public Builder danger() {
            this.danger = true;
            return this;
        }

        /** {@link #danger()} when {@code on}, so a two-click button can pass its armed state straight in. */
        public Builder danger(boolean on) {
            this.danger = on;
            return this;
        }

        public SettingsButtonWidget build() {
            return new SettingsButtonWidget(message, onPress, onSecondaryPress, primary, danger, x, y, width, height);
        }
    }

    /** How the mod menu draws this button, read from its message and flags (2026-10-12, Option A menu). */
    public enum Look {
        /** "Label: ON" / "Label: OFF" - a label and a switch. */
        SWITCH,
        /** A {@link #cycling} button - a label and its value in a pill with step arrows. */
        PILL,
        /** "Label: value" on a plain button (a key bind, a status) - a label and a value chip. */
        CHIP,
        /** A {@code CollapsibleSection} header ("▶ Title" / "▼ Title"). */
        HEADER,
        /** Anything else - a raised orange-ringed button. */
        ACTION
    }

    private static final java.util.regex.Pattern SWITCH_TEXT =
            java.util.regex.Pattern.compile("^(.+?):\\s*(ON|OFF)(?:\\s+(.*))?$");

    /** The parts of a message as the menu draws them: label, value (ON/OFF or the cycling value) and a trailing note. */
    public record Parts(Look look, String label, String value, String note, boolean on, boolean dimLabel) {
    }

    private String partsFor;
    private Parts parts;

    /** This button's message read the way the menu draws it. Cached per message text. */
    public Parts parts() {
        String raw = getMessage().getString();
        if (parts != null && raw.equals(partsFor)) {
            return parts;
        }
        partsFor = raw;
        parts = parse(raw, isCycling());
        return parts;
    }

    /** True when the menu draws this as an on/off switch. */
    public boolean isSwitch() {
        return parts().look() == Look.SWITCH;
    }

    /** For a switch: whether it reads ON. */
    public boolean isOn() {
        Parts p = parts();
        return p.look() == Look.SWITCH && p.on();
    }

    static Parts parse(String raw, boolean cycling) {
        String plain = net.minecraft.ChatFormatting.stripFormatting(raw);
        if (plain == null) {
            plain = raw;
        }
        plain = plain.trim();
        boolean dim = raw.startsWith("§7") || raw.startsWith("§8");
        if (plain.length() > 1 && (plain.charAt(0) == '▶' || plain.charAt(0) == '▼')) {
            return new Parts(Look.HEADER, plain.substring(1).trim(), plain.charAt(0) == '▼' ? "open" : "closed", "",
                    plain.charAt(0) == '▼', false);
        }
        java.util.regex.Matcher m = SWITCH_TEXT.matcher(plain);
        if (m.matches()) {
            String note = m.group(3) == null ? "" : m.group(3).trim();
            return new Parts(Look.SWITCH, m.group(1).trim(), m.group(2), note, "ON".equals(m.group(2)), dim);
        }
        int colon = plain.indexOf(':');
        if (cycling) {
            if (colon > 0 && colon < plain.length() - 1) {
                return new Parts(Look.PILL, plain.substring(0, colon).trim(), plain.substring(colon + 1).trim(), "",
                        false, dim);
            }
            return new Parts(Look.PILL, "", plain, "", false, dim);
        }
        if (colon > 0 && colon < plain.length() - 1) {
            // The value keeps its own colour codes (a key bind's §b, a status's §a/§c).
            int rawColon = raw.indexOf(':');
            String value = rawColon >= 0 ? raw.substring(rawColon + 1).trim() : plain.substring(colon + 1).trim();
            return new Parts(Look.CHIP, plain.substring(0, colon).trim(), value, "", false, dim);
        }
        return new Parts(Look.ACTION, plain, "", "", false, dim);
    }

    /** Whether a widget is being drawn inside the mod menu right now, where it takes the menu's row look. Elsewhere (the
     *  title screen, the AR editor, every other screen that uses this button) it keeps its plain box. */
    static boolean inMenu() {
        return com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()) instanceof ModScreen;
    }

    /** A left click, as the menu's card and search switches send it: the same {@code onPress} a real click runs. */
    public void press() {
        if (active && visible && onPress != null) {
            onPress.onPress(this);
        }
    }

    /** Where the switch / pill sits inside this row, in screen units: {x, y, w, h}. */
    public int[] controlBounds() {
        Parts p = parts();
        int h = getHeight();
        int right = getX() + getWidth() - 4;
        return switch (p.look()) {
            case SWITCH -> new int[]{right - MenuStyle.SWITCH_W, getY() + (h - MenuStyle.SWITCH_H) / 2,
                    MenuStyle.SWITCH_W, MenuStyle.SWITCH_H};
            case PILL, CHIP -> {
                int w = pillWidth(p);
                yield new int[]{right - w, getY() + 3, w, h - 6};
            }
            default -> new int[]{getX(), getY(), getWidth(), h};
        };
    }

    private int pillWidth(Parts p) {
        var font = Minecraft.getInstance().font;
        int labelMin = p.label().isEmpty() ? 0 : Math.min(font.width(p.label()), 40) + 12;
        int max = Math.max(24, getWidth() - 8 - labelMin);
        int want = font.width(p.value()) + (p.look() == Look.PILL ? 22 : 10);
        if (p.label().isEmpty()) {
            return getWidth() - 8;
        }
        return Math.min(max, Math.max(30, want));
    }

    private void renderMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Parts p = parts();
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        var font = Minecraft.getInstance().font;
        boolean hover = isHovered && active;
        int textY = y0 + (h - 8) / 2;
        com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
        if (danger || primary || p.look() == Look.ACTION) {
            int bw = w;
            if (danger) {
                MenuStyle.dangerButton(r, x0 + 1, y0 + 1, bw - 2, h - 2, hover);
            } else {
                MenuStyle.raisedButton(r, x0 + 1, y0 + 1, bw - 2, h - 2, hover, primary);
            }
            r.submit();
            String label = MenuStyle.ellipsize(getMessage().getString(), w - 10);
            int color = !active ? MenuStyle.DIM2 : (danger ? 0xFFFFD0D0 : (hover || primary ? MenuStyle.OR_HOVER : MenuStyle.OR));
            graphics.text(font, label, x0 + (w - font.width(label)) / 2, textY, faded(color), MenuStyle.TEXT_SHADOW);
            return;
        }
        if (p.look() == Look.HEADER) {
            MenuStyle.sectionBand(r, x0, y0 + 1, w, h - 2, getMessage().getString().contains("§c"));
            if (hover) {
                r.fill(x0, y0 + 2, x0 + w, y0 + h - 1, MenuStyle.alpha(MenuStyle.TXT, 0x10));
            }
            r.submit();
            boolean red = getMessage().getString().contains("§c");
            int color = red ? MenuStyle.RED_TITLE : (hover ? MenuStyle.OR_HOVER : MenuStyle.OR);
            graphics.text(font, p.on() ? "▼" : "▶", x0 + 5, textY, faded(color), MenuStyle.TEXT_SHADOW);
            graphics.text(font, net.minecraft.network.chat.Component.literal(p.label())
                    .withStyle(net.minecraft.ChatFormatting.BOLD), x0 + 16, textY, faded(color), MenuStyle.TEXT_SHADOW);
            return;
        }
        // A setting row: faint hover band and a separator under it, label left, control right.
        if (hover) {
            r.fill(x0, y0, x0 + w, y0 + h - 1, MenuStyle.alpha(MenuStyle.TXT, 0x0E));
        }
        MenuStyle.separator(r, x0, x0 + w, y0 + h - 2);
        int[] c = controlBounds();
        switch (p.look()) {
            case SWITCH -> MenuStyle.toggle(r, c[0], c[1], p.on(), hover);
            case PILL -> MenuStyle.pill(r, c[0], c[1], c[2], c[3], false, hover);
            default -> {
                r.outline(c[0], c[1], c[2], c[3], hover ? MenuStyle.OR2 : MenuStyle.BLACK);
                r.fill(c[0] + 1, c[1] + 1, c[0] + c[2] - 1, c[1] + c[3] - 1, MenuStyle.PILL_BG);
                r.fill(c[0] + 1, c[1] + 1, c[0] + c[2] - 1, c[1] + 2, MenuStyle.shadow(0xC0));
            }
        }
        r.submit();
        int labelColor = !active ? MenuStyle.DIM2 : (p.dimLabel() ? MenuStyle.DIM : (hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT));
        int labelMax = c[0] - x0 - 10;
        String label = p.label();
        if (!p.note().isEmpty() && font.width(label + " " + p.note()) <= labelMax) {
            graphics.text(font, label, x0 + 4, textY, faded(labelColor), MenuStyle.TEXT_SHADOW);
            graphics.text(font, p.note(), x0 + 4 + font.width(label) + 4, textY, faded(MenuStyle.DIM2), MenuStyle.TEXT_SHADOW);
        } else {
            graphics.text(font, MenuStyle.ellipsize(label, labelMax), x0 + 4, textY, faded(labelColor), MenuStyle.TEXT_SHADOW);
        }
        int vy = c[1] + (c[3] - 8) / 2;
        if (p.look() == Look.PILL) {
            int arrow = hover ? MenuStyle.OR : MenuStyle.DIM2;
            graphics.text(font, "‹", c[0] + 3, vy, faded(arrow), false);
            graphics.text(font, "›", c[0] + c[2] - 3 - font.width("›"), vy, faded(arrow), false);
            String v = MenuStyle.ellipsize(p.value(), c[2] - 18);
            graphics.text(font, v, c[0] + (c[2] - font.width(v)) / 2, vy, faded(hover ? MenuStyle.OR_HOVER : MenuStyle.OR),
                    MenuStyle.TEXT_SHADOW);
        } else if (p.look() == Look.CHIP) {
            Integer swatch = swatchColour();
            if (swatch != null) {
                // A colour setting ("Colour: ■" from ColorSwatch): the chip shows the colour itself.
                com.killer560.hub.hud.GuiRects sw = com.killer560.hub.hud.GuiRects.begin(graphics);
                sw.fill(c[0] + 3, c[1] + 3, c[0] + c[2] - 3, c[1] + c[3] - 3, 0xFF000000 | swatch);
                sw.outline(c[0] + 2, c[1] + 2, c[2] - 4, c[3] - 4, MenuStyle.BLACK);
                sw.submit();
                return;
            }
            String v = MenuStyle.ellipsize(p.value(), c[2] - 8);
            int plainW = font.width(v);
            graphics.text(font, MenuStyle.LIGHT ? MenuStyle.lightReadable(v) : v, c[0] + (c[2] - plainW) / 2, vy,
                    faded(MenuStyle.TXT_SOFT), MenuStyle.TEXT_SHADOW);
        }
    }

    /**
     * The look on a {@link ThemedScreen} (a standalone screen in the menu's theme): always a bevelled button in its own
     * bounds. A label starting {@code §6} is the screen's "this one is chosen" mark and draws pressed in the accent, one
     * starting {@code §c} (or a {@link Builder#danger()} button) draws red; "Label: ON/OFF" gets a small switch at the
     * right, "Label: value" the value in the accent (a colour swatch as its colour). The message text is untouched.
     */
    private void renderThemed(GuiGraphicsExtractor graphics) {
        // The palette is the screen's own (ScreenChrome.begin derived it this frame).
        Parts p = parts();
        String raw = getMessage().getString();
        boolean chosen = raw.startsWith("§6");
        boolean red = danger || raw.startsWith("§c");
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        var font = Minecraft.getInstance().font;
        boolean hover = isHovered && active;
        int textY = y0 + (h - 8) / 2;
        com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
        if (red) {
            MenuStyle.dangerButton(r, x0 + 1, y0 + 1, w - 2, h - 2, hover);
        } else {
            MenuStyle.raisedButton(r, x0 + 1, y0 + 1, w - 2, h - 2, hover, primary || chosen);
        }
        int right = x0 + w - 5;
        if (p.look() == Look.SWITCH) {
            MenuStyle.toggle(r, right - MenuStyle.SWITCH_W, y0 + (h - MenuStyle.SWITCH_H) / 2, p.on(), hover);
        }
        Integer swatch = p.look() == Look.CHIP ? swatchColour() : null;
        if (swatch != null) {
            int s = Math.max(6, h - 8);
            r.fill(right - s, y0 + (h - s) / 2, right, y0 + (h - s) / 2 + s, 0xFF000000 | swatch);
            r.outline(right - s - 1, y0 + (h - s) / 2 - 1, s + 2, s + 2, MenuStyle.BLACK);
        }
        r.submit();
        int labelColor = !active ? MenuStyle.DIM2 : red ? 0xFFFFD0D0
                : (chosen || primary) ? MenuStyle.OR_HOVER : (hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
        switch (p.look()) {
            case SWITCH -> {
                String label = MenuStyle.ellipsize(p.label(), w - MenuStyle.SWITCH_W - 14);
                graphics.text(font, label, x0 + 6, textY, faded(labelColor), MenuStyle.TEXT_SHADOW);
            }
            case PILL, CHIP -> {
                String label = p.label().isEmpty() ? "" : p.label() + ": ";
                if (swatch != null) {
                    graphics.text(font, MenuStyle.ellipsize(p.label(), w - h - 6), x0 + 6, textY, faded(labelColor),
                            MenuStyle.TEXT_SHADOW);
                    break;
                }
                String value = net.minecraft.ChatFormatting.stripFormatting(p.value());
                value = value == null ? p.value() : value;
                int lw = font.width(label);
                int vw = font.width(value);
                int start = x0 + Math.max(5, (w - lw - vw) / 2);
                if (lw + vw > w - 10) {
                    start = x0 + 5;
                    value = MenuStyle.ellipsize(value, Math.max(10, w - 10 - lw));
                }
                int dim = !active ? MenuStyle.DIM2 : MenuStyle.DIM;
                graphics.text(font, label, start, textY, faded(dim), MenuStyle.TEXT_SHADOW);
                int valueColor = !active ? MenuStyle.DIM2 : hover ? MenuStyle.OR_HOVER : MenuStyle.OR;
                graphics.text(font, value, start + lw, textY, faded(valueColor), MenuStyle.TEXT_SHADOW);
            }
            default -> {
                String plain = net.minecraft.ChatFormatting.stripFormatting(raw);
                String label = MenuStyle.ellipsize(plain == null ? raw : plain, w - 8);
                graphics.text(font, label, x0 + (w - font.width(label)) / 2, textY, faded(labelColor),
                        MenuStyle.TEXT_SHADOW);
            }
        }
    }

    /** The RGB of a {@code ColorSwatch} label's "■", or null when this is not a colour setting. */
    private Integer swatchColour() {
        for (net.minecraft.network.chat.Component part : getMessage().getSiblings()) {
            if ("■".equals(part.getString()) && part.getStyle().getColor() != null) {
                return part.getStyle().getColor().getValue();
            }
        }
        return null;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (inMenu()) {
            renderMenu(graphics, mouseX, mouseY);
            return;
        }
        if (com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()) instanceof ThemedScreen) {
            renderThemed(graphics);
            return;
        }
        int x0 = getX();
        int y0 = getY();
        int bg = primary ? (isHovered ? PRIMARY_BG_HOVER : PRIMARY_BG) : (isHovered ? BG_HOVER : BG);
        int border = primary ? (isHovered ? PRIMARY_BORDER_HOVER : PRIMARY_BORDER) : (isHovered ? BORDER_HOVER : BORDER);
        if (danger) {
            bg = isHovered ? DANGER_BG_HOVER : DANGER_BG;
            border = isHovered ? DANGER_BORDER_HOVER : DANGER_BORDER;
        }
        graphics.fill(x0, y0, x0 + getWidth(), y0 + getHeight(), faded(bg));
        graphics.outline(x0, y0, getWidth(), getHeight(), faded(border));
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x0 + getWidth() / 2,
                y0 + (getHeight() - 8) / 2, faded(TEXT));
    }

    /**
     * Draws a box in this button's style, for a widget that has to be its own class but must look like one of these
     * (the sim's filter chips, which carry a row and a selected state). {@code selected} is the mod's "this one is
     * chosen" look: the full-amber border this button shows on hover, kept on, with the label in §6 by the caller.
     */
    public static void drawBox(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean hovered,
            boolean selected) {
        int border = selected ? (hovered ? PRIMARY_BORDER_HOVER : BORDER_HOVER) : (hovered ? BORDER_HOVER : BORDER);
        graphics.fill(x, y, x + w, y + h, hovered ? BG_HOVER : BG);
        graphics.outline(x, y, w, h, border);
    }

    /**
     * This colour at the widget's current opacity.
     *
     * <p>Every colour here was a hardcoded {@code 0xFF......}, so the widget drew fully opaque no matter what
     * {@link #setAlpha} had been told. That is why the Dungeon Sim button did not fade in with the rest of the
     * title screen (killer560, 2026-09-28 and again 2026-09-30).
     *
     * <p>Checked against the mapped 26.1.2 jar rather than assumed, because the first look was misleading:
     * {@code TitleScreen} contains no {@code setAlpha} call at all. It calls {@code Screen.fadeWidgets(float)},
     * and THAT walks {@code children()} calling {@code AbstractWidget.setAlpha} on every one. Our button is in
     * that list and was being handed the right number every frame - it just threw it away.
     *
     * <p>Fixed here rather than on the menu, because it is true of the button everywhere it is used - anything
     * that fades a screen holding one had the same problem.
     */
    private int faded(int argb) {
        float a = Math.max(0f, Math.min(1f, this.alpha));
        if (a >= 1f) {
            return argb;
        }
        int base = (argb >>> 24) & 0xFF;
        return (Math.round(base * a) << 24) | (argb & 0x00FFFFFF);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (onPress != null) {
            onPress.onPress(this);
        }
    }

    /** Vanilla's own {@code mouseClicked} only ever calls {@code onClick} above for the left button
     *  (button 0), so left-click behavior here is completely untouched - this only adds a right-click
     *  (button 1) path, and only when a {@code secondaryPress} was actually set, so a plain button with
     *  no cycling behavior can never have right-click do anything. The right click plays the same click sound
     *  vanilla's left click does ({@code AbstractWidget.mouseClicked} -> {@code playDownSound}, javap 26.1.2 and 26.2). */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (onSecondaryPress != null && active && visible && event.button() == 1
                && isMouseOver(event.x(), event.y())) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onSecondaryPress.onPress(this);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
