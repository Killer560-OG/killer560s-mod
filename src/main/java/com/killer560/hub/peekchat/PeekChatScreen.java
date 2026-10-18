package com.killer560.hub.peekchat;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Peek Chat's read-only chat (killer560, 2026-10-13: "a peek chat button where it opens chat and you can scroll up and
 * down it while not able to type in it").
 * <p>
 * It IS a {@link ChatScreen}, so everything vanilla gives an open chat is kept as it is: the full-height history, the
 * mouse wheel scrolling it ({@code ChatScreen.mouseScrolled -> ChatComponent.scrollChat}), hover text and clicked
 * links/commands ({@code ChatScreen.mouseClicked}), and every mod feature that reads "chat is open". What it takes away:
 * <ul>
 * <li>the input line - its EditBox is invisible, never focused and not editable, and its dark bar is not drawn
 * ({@code peekchat/mixin/PeekChatInputBarMixin});</li>
 * <li>typing - every character is swallowed, and so is every key except Escape, the Peek Chat key itself (both close
 * it), and Up/Down/Page Up/Page Down/Home (scroll one line / one page / back to the newest);</li>
 * <li>the draft - closing it never touches a half-typed message saved in the real chat
 * ({@code ChatScreen.removed} would discard or overwrite it, so {@link #removed} only resets the scroll).</li>
 * </ul>
 * Shift-click insertion has no box to insert into, so it does nothing.
 */
public final class PeekChatScreen extends ChatScreen {

    public PeekChatScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        input.setVisible(false);
        input.setEditable(false);
        input.setFocused(false);
        setFocused(null);
    }

    /** No initial focus: the hidden input must never take keys. */
    @Override
    protected void setInitialFocus() {
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean hold = PeekChatConfig.getInstance().isHoldToPeek();
        // Hold to Peek: the key being held is what keeps it open (its auto-repeat must not close it); Escape still does.
        if (key == InputConstants.KEY_ESCAPE || (!hold && key == PeekChatConfig.getInstance().getKeyCode())) {
            if (key != InputConstants.KEY_ESCAPE) {
                PeekChatFeature.closedByKey();
            }
            onClose();
            return true;
        }
        ChatComponent chat = McCompat.chat(minecraft);
        int page = Math.max(1, chat.getLinesPerPage() - 1);
        if (key == InputConstants.KEY_UP) {
            chat.scrollChat(1);
        } else if (key == InputConstants.KEY_DOWN) {
            chat.scrollChat(-1);
        } else if (key == InputConstants.KEY_PAGEUP) {
            chat.scrollChat(page);
        } else if (key == InputConstants.KEY_PAGEDOWN) {
            chat.scrollChat(-page);
        } else if (key == InputConstants.KEY_HOME || key == InputConstants.KEY_END) {
            chat.resetChatScroll();
        }
        return true;
    }

    /** A Peek Chat bind on a mouse button closes it from that button too; every other click is vanilla chat's. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int bind = PeekChatConfig.getInstance().getKeyCode();
        if (!PeekChatConfig.getInstance().isHoldToPeek() && KeyUtil.isMouseCode(bind) && KeyUtil.mouseButton(bind) == event.button()) {
            PeekChatFeature.closedByKey();
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The wheel always scrolls the chat here (the same as vanilla's open chat: 7 lines a notch, 1 with Shift), and
     *  is always consumed, so it can never reach the hotbar (killer560, 2026-10-18). */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double dy = Math.max(-1.0, Math.min(1.0, scrollY));
        if (dy != 0.0) {
            McCompat.chat(minecraft).scrollChat((int) (shiftHeld() ? dy : dy * 7.0));
        }
        return true;
    }

    private static boolean shiftHeld() {
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        return KeyUtil.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || KeyUtil.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public void insertText(String text, boolean override) {
    }

    @Override
    public void removed() {
        McCompat.chat(minecraft).resetChatScroll();
    }
}
