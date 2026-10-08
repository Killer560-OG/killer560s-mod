package com.killer560.hub.copychat;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.copychat.mixin.ChatComponentAccessor;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Copy Chat (killer560, 2026-10-08): "if you hold shift or control while left or right clicking it does its respective
 * action". With the chat open, Shift or Ctrl + LEFT click on a message copies the WHOLE message (every wrapped line of
 * it); Shift or Ctrl + RIGHT click copies only the one wrapped line under the cursor. A plain click is left to vanilla.
 * Copied text never carries formatting codes.
 * <p>
 * Why it did not work before: the whole-message copy rode on Click Translate's {@code ClickEvent} baked into each line,
 * so it only fired when vanilla's {@code ClickableStyleFinder} found that event under the cursor. A Hypixel line whose
 * text has its own click event (names, "Click here" links, party invites) overrides the parent's on that text, a click
 * past the end of the text finds no style at all, and lines received before the feature was switched on were never
 * wrapped - and the modifier was Shift only, read from GLFW, never Ctrl. Both gestures now go through ONE hook at the
 * head of {@code ChatScreen.mouseClicked} ({@code copychat/mixin/ChatScreenLineClickMixin}), take the modifiers from
 * the click event itself, and find the line under the cursor by asking vanilla's own chat layout
 * ({@code ChatComponent.captureClickableText}, which lays out exactly the lines it draws, with the pose it draws them
 * with), so the geometry cannot drift from what is on screen on 26.1.2 or 26.2. The whole message is the line's
 * {@code GuiMessage.Line.parent()}.
 * <p>
 * The clipboard is Minecraft's own ({@code keyboardHandler.setClipboard}, GLFW), the same call the Crosshair share code
 * and Waypoint Routes use - instant, on the render thread, no PowerShell process (the old shell-out took seconds on its
 * first call and only worked on Windows).
 */
public final class CopyChatFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-copychat");

    // Test hooks (testkit, by reflection).
    private static volatile String lastCopied = "";
    private static volatile String lastKind = "";
    private static long copies;

    private CopyChatFeature() {
    }

    /** Shift or Ctrl held right now, read from the window (for callers that have no input event, like
     *  {@code ChatScreen.handleComponentClicked}). */
    public static boolean isCopyModifierDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /**
     * From the chat screen's mouse press, before vanilla. True when the click was a copy and is consumed.
     *
     * @param button   0 left (whole message), 1 right (the line)
     * @param modifier Shift or Ctrl was held, read from the click event
     */
    public static boolean onChatClick(double mouseX, double mouseY, int button, boolean modifier) {
        if (!modifier || (button != 0 && button != 1) || !CopyChatConfig.getInstance().isEnabled()) {
            return false;
        }
        try {
            GuiMessage.Line line = lineAt(mouseX, mouseY);
            if (line == null) {
                return false;
            }
            boolean whole = button == 0;
            String text = whole ? messageText(line.parent()) : plain(line.content());
            if (text.isBlank()) {
                return true; // a blank line: consumed, nothing to copy
            }
            copy(text, whole ? "message" : "line");
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Copy Chat: click handling failed; left to vanilla", e);
            return false;
        }
    }

    /**
     * The wrapped chat line under (mouseX, mouseY) in GUI coordinates, or null. Lays the chat out through vanilla's own
     * {@code captureClickableText} with a collector that records each line's position and pose instead of searching
     * styles, then hit-tests the line's full row (vanilla's entry rectangle: {@code entryHeight} tall, ending
     * {@code entryBottomToMessageY} below the text's y), so a click anywhere along the row counts, not only on a glyph.
     */
    public static GuiMessage.Line lineAt(double mouseX, double mouseY) {
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = McCompat.chat(client);
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> trimmed = accessor.killer560smod$getTrimmedMessages();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<Placed> placed = new ArrayList<>();
        ActiveTextCollector.ClickableStyleFinder recorder =
                new ActiveTextCollector.ClickableStyleFinder(client.font, (int) mouseX, (int) mouseY) {
                    @Override
                    public void accept(TextAlignment alignment, int x, int y, ActiveTextCollector.Parameters parameters,
                                       FormattedCharSequence text) {
                        placed.add(new Placed(new Matrix3x2f(parameters.pose()), x, y, text));
                    }
                };
        chat.captureClickableText(recorder, client.getWindow().getGuiScaledHeight(), 0,
                ChatComponent.DisplayMode.FOREGROUND);

        // Vanilla's row geometry (ChatComponent.extractRenderState, javap 26.1.2 and 26.2): entryHeight = 9 * (spacing
        // + 1), and the text sits entryBottomToMessageY = round(8 * (spacing + 1) - 4 * spacing) above the row's bottom.
        double spacing = client.options.chatLineSpacing().get();
        int entryHeight = (int) (9.0 * (spacing + 1.0));
        int bottomToText = (int) Math.round(8.0 * (spacing + 1.0) - 4.0 * spacing);
        double scale = accessor.killer560smod$invokeGetScale();
        double rowWidth = Math.ceil(ChatComponent.getWidth(client.options.chatWidth().get()) / scale);

        for (Placed p : placed) {
            Vector2f local = new Matrix3x2f(p.pose()).invert().transformPosition(new Vector2f((float) mouseX, (float) mouseY));
            int rowBottom = p.y() + bottomToText;
            if (local.y < rowBottom - entryHeight || local.y >= rowBottom || local.x < p.x() - 4 || local.x > rowWidth + 8) {
                continue;
            }
            for (GuiMessage.Line line : trimmed) {
                if (line.content() == p.text()) {
                    return line;
                }
            }
            return null; // the queue / restricted prompt row: not a message
        }
        return null;
    }

    private record Placed(Matrix3x2f pose, int x, int y, FormattedCharSequence text) {
    }

    /** The whole message, plain: every wrapped line of it, without a Chat Hider stack count. */
    static String messageText(GuiMessage message) {
        return strip(com.killer560.hub.chattidy.ChatTidy.unstackedContent(message).getString());
    }

    static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((position, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return strip(sb.toString());
    }

    private static String strip(String s) {
        String out = ChatFormatting.stripFormatting(s);
        return out == null ? s : out;
    }

    private static void copy(String text, String kind) {
        Minecraft client = Minecraft.getInstance();
        client.keyboardHandler.setClipboard(text);
        lastCopied = text;
        lastKind = kind;
        copies++;
        if (client.player != null) {
            client.player.sendSystemMessage(ModChat.line("Killer560's Mod",
                    ModChat.text("message".equals(kind) ? "Chat message copied to clipboard!" : "Chat line copied to clipboard!")));
        }
    }

    /** Puts {@code text} on the clipboard (Cosmetics' copy button). Render thread. */
    public static void copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().keyboardHandler.setClipboard(text));
    }

    // ---- test hooks ---------------------------------------------------------------------------------------------

    public static String testLastCopied() {
        return lastCopied;
    }

    public static String testLastKind() {
        return lastKind;
    }

    public static long testCopies() {
        return copies;
    }

    /** The plain text of the wrapped line under (x, y) and of its whole message, or null. Render thread. */
    public static String[] testLineAt(double x, double y) {
        GuiMessage.Line line = lineAt(x, y);
        return line == null ? null : new String[]{plain(line.content()), messageText(line.parent())};
    }
}
