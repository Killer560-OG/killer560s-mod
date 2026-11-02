package com.killer560.hub.gui;

import com.killer560.hub.compat.McInput;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.util.Keys;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * The mod's text box: an {@link EditBox} where Enter commits what was typed (killer560, 2026-11-01: "any text box I type
 * into if i press enter should save that value. Wether it has a set button or not. If it doesnt have a set button add it
 * as well."). Enter runs the box's commit action - the same one its Set (or Save, Add...) button runs - then the box lets
 * go of the cursor and its edge flashes green for a moment, so a box that already saved on every keystroke still shows
 * that Enter took it.
 *
 * <p>Nothing commits until the call site says how: {@link #onEnter(Runnable)} for an action of its own,
 * {@link #pressOnEnter(SettingsButtonWidget)} for the button next to it, {@link #enterRunsResponder()} for a box whose
 * responder already saves. A search box's live filter, chat and sign screens are never wired (owner's rule), so Enter
 * there does what it always did. {@link #withSet} adds the Set button to a box that has no commit button.
 *
 * <p>Enter is read as the stored GLFW code through {@link McInput#key} (26.3's SDL scancodes are translated there).
 */
public class CommitEditBox extends EditBox {

    /** How long the green edge stays after a commit. */
    private static final long FLASH_MS = 700L;
    private static final int FLASH = 0xFF55DD55;
    /** Width of a Set button {@link #withSet} adds, and the gap before it. */
    public static final int SET_W = 34;
    public static final int SET_GAP = 4;

    /** Commits made through Enter or a Set button, mod-wide (read by testkit 1101-ui-textbox-enter). */
    private static int commits;

    private Consumer<String> responder;
    private Runnable onEnter;
    private long flashUntil;

    public CommitEditBox(Font font, int x, int y, int w, int h, Component narration) {
        super(font, x, y, w, h, narration);
    }

    @Override
    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
        super.setResponder(responder);
    }

    /** Enter runs {@code action} (the box's save). Replaces any earlier commit action. */
    public CommitEditBox onEnter(Runnable action) {
        this.onEnter = action;
        return this;
    }

    /** Enter presses {@code button}, the commit button beside the box (its Set, Save, Add...). */
    public CommitEditBox pressOnEnter(SettingsButtonWidget button) {
        return onEnter(button::press);
    }

    /** Enter presses a vanilla {@code button}, the commit button beside the box. */
    public CommitEditBox pressOnEnter(AbstractButton button) {
        return onEnter(() -> {
            if (button.active && button.visible) {
                button.onPress(lastEnter);
            }
        });
    }

    private KeyEvent lastEnter;

    /** Enter runs the box's own responder on its current value: for a box that saves as it is typed in. Call after
     *  {@link #setResponder}. */
    public CommitEditBox enterRunsResponder() {
        return onEnter(this::runResponder);
    }

    /** Runs the box's responder on its current value (nothing when it has none). */
    public void runResponder() {
        if (responder != null) {
            responder.accept(getValue());
        }
    }

    /** True once a commit action is set. */
    public boolean commits() {
        return onEnter != null;
    }

    /** Runs the commit action as Enter does (the Set button calls this). False when the box has none. */
    public boolean commit() {
        if (onEnter == null) {
            return false;
        }
        commits++;
        flashUntil = System.currentTimeMillis() + FLASH_MS;
        onEnter.run();
        return true;
    }

    /** Commits made so far, mod-wide. */
    public static int commitCount() {
        return commits;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (onEnter != null && isActive() && isFocused()) {
            int key = McInput.key(event);
            if (key == Keys.ENTER || key == Keys.KP_ENTER) {
                lastEnter = event;
                commit();
                setFocused(false);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
        if (isVisible() && System.currentTimeMillis() < flashUntil) {
            int[] b = drawnBox();
            GuiRects r = GuiRects.begin(g);
            r.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, FLASH);
            r.submit();
        }
    }

    /** {x, y, w, h} of the box as drawn ({@link ThemedEditBox} draws a larger well around its text line). */
    public int[] drawnBox() {
        return new int[]{getX(), getY(), getWidth(), getHeight()};
    }

    /** Makes the drawn box {@code w} wide, keeping its left edge. */
    public void setDrawnWidth(int w) {
        setWidth(w);
    }

    /**
     * Gives {@code box} a "Set" button: the box loses {@link #SET_W} + {@link #SET_GAP} of its width and the button takes
     * that place at its right, the box's height. The button and Enter run the same commit; a box with no commit action
     * yet gets {@link #enterRunsResponder()}. Returns the button, already added to {@code widgets}.
     */
    public static SettingsButtonWidget withSet(List<? super AbstractWidget> widgets, CommitEditBox box) {
        return withSet((Consumer<SettingsButtonWidget>) widgets::add, box);
    }

    /** {@link #withSet(List, CommitEditBox)} for a screen: {@code add} is its {@code addRenderableWidget}. */
    public static SettingsButtonWidget withSet(Consumer<SettingsButtonWidget> add, CommitEditBox box) {
        if (!box.commits()) {
            box.enterRunsResponder();
        }
        int[] b = box.drawnBox();
        int boxW = Math.max(16, b[2] - SET_W - SET_GAP);
        box.setDrawnWidth(boxW);
        SettingsButtonWidget set = SettingsButtonWidget.builder(Component.literal("Set"), btn -> box.commit())
                .bounds(b[0] + boxW + SET_GAP, b[1], b[0] + b[2] - (b[0] + boxW + SET_GAP), b[3]).build();
        add.accept(set);
        return set;
    }
}
