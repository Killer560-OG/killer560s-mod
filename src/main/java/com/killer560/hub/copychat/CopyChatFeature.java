package com.killer560.hub.copychat;

import com.killer560.hub.copychat.mixin.ChatComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shift+Click any chat message to copy its whole plain text to the clipboard, or Shift+Right-Click to
 *  copy just the one wrapped line under the cursor (see {@link #tryHandleLineClick}) - general QoL, not
 *  dungeon-specific (roadmap item, referenced from quoi's own "Copy chat" module: "Copies chat on mouse
 *  click"). The whole-message gesture shares {@link com.killer560.hub.clicktranslate.ClickTranslateFeature}'s
 *  existing click infrastructure rather than adding a second competing click handler - a chat line's
 *  {@code Style} can only carry one {@code ClickEvent} at a time, so {@code ClickTranslateFeature.tryHandleClick}
 *  checks {@link #isShiftDown()} first and delegates here before falling through to its own translate
 *  logic; {@link com.killer560.hub.clicktranslate.mixin.ChatComponentMixin}'s wrap is likewise gated on
 *  either feature being enabled, not just Translate, so Copy still works with Translate off. Used to be
 *  Ctrl+Click - changed to Shift per killer560's round-11 request (2026-09-09).
 *  <p>
 *  Uses the same PowerShell clipboard shell-out {@link com.killer560.hub.screenshotcopy.ScreenshotCopyFeature}
 *  already established (2026-09-08) - AWT's {@code Toolkit}/{@code Clipboard} throws
 *  {@code HeadlessException} inside Minecraft's process (confirmed via javap:
 *  {@code net.minecraft.client.main.Main}'s static initializer forces {@code java.awt.headless=true}) -
 *  {@code Set-Clipboard} is the plain-text equivalent of that same workaround. Windows-only for now,
 *  matching that same precedent (killer560's own setup is Windows). */
public final class CopyChatFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-copychat");

    // Per killer560's "if i ctrl click a long message... it lags my game and essentially freezes it for
    // quite a few seconds" report (2026-09-09, round 11): #copyToClipboard is called directly from the
    // click handler, which runs on the RENDER thread - spawning + waiting on a real powershell.exe
    // process (a genuinely slow OS operation, worst on its first invocation each session) blocked
    // rendering for however long that took. ScreenshotCopyFeature's own identical shell-out never showed
    // this because it's already invoked from vanilla's screenshot IO worker thread, not the render
    // thread - this gives Copy Chat that same off-thread guarantee explicitly instead of relying on its
    // caller already being off-thread (it wasn't).
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-copychat");
        t.setDaemon(true);
        return t;
    });

    private CopyChatFeature() {
    }

    /** @return whether either Shift key is currently held, read live via GLFW (same direct-window-handle
     *  pattern {@link com.killer560.hub.window.WindowModeFeature} already uses) rather than from a
     *  specific input event - needed because the click hook the whole-message copy feeds
     *  ({@code ChatScreen.handleComponentClicked}) doesn't receive the raw mouse event with its own
     *  {@code hasShiftDown()}, only the already-resolved {@code Style} that was clicked. Per killer560's
     *  "change both to be shift instead of control" request (2026-09-09, round 11) - both copy gestures
     *  (whole message, single line) now use Shift as their modifier, distinguished by mouse button
     *  instead (left = whole message, right = just the line - see {@link #tryHandleLineClick}). Used to
     *  be Ctrl for the whole-message gesture. */
    public static boolean isShiftDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    /** Per killer560's "if i shift click to copy then itll only do the line... shift click would only
     *  get the line my cursor is on" request (2026-09-09) - unlike Ctrl+Click (which copies the whole
     *  underlying message via a baked-in {@code ClickEvent}, see {@code ClickTranslateFeature}), this
     *  works entirely off the raw click position, hooked from
     *  {@link com.killer560.hub.copychat.mixin.ChatScreenLineClickMixin} before vanilla resolves the
     *  click down to a Style - reversing (mouseX, mouseY) into a specific wrapped visual line the exact
     *  same way quoi's own Copy Chat module does it ({@code ChatUtils.toChatLineMY}/
     *  {@code getMessageLineIdx}, decompiled 2026-09-09 as reference), then reading that ONE line's own
     *  {@link net.minecraft.util.FormattedCharSequence} instead of tracing back to its parent message -
     *  quoi's own version always copies the full message regardless of which line was clicked; this is
     *  intentionally narrower.
     *  @return true if the click was on a valid chat line and handled (whether or not it was blank). */
    public static boolean tryHandleLineClick(double mouseX, double mouseY) {
        if (!CopyChatConfig.getInstance().isEnabled() || !isShiftDown()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = client.gui.getChat();
        if (!chat.isChatFocused()) {
            return false;
        }
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> trimmed = accessor.killer560smod$getTrimmedMessages();
        if (trimmed.isEmpty()) {
            return false;
        }

        double scale = accessor.killer560smod$invokeGetScale();
        int lineHeight = accessor.killer560smod$invokeGetLineHeight();
        double chatLineX = mouseX / scale - 4.0;
        double chatLineY = (client.getWindow().getGuiScaledHeight() - mouseY - 40.0) / (scale * lineHeight);

        double maxLineX = ChatComponent.getWidth(client.options.chatWidth().get()) / scale;
        if (chatLineX < -4.0 || chatLineX > maxLineX) {
            return false;
        }
        int lineCount = Math.min(chat.getLinesPerPage(), trimmed.size());
        if (chatLineY < 0.0 || chatLineY >= lineCount) {
            return false;
        }

        int idx = (int) Math.floor(chatLineY + accessor.killer560smod$getChatScrollbarPos());
        if (idx < 0 || idx >= trimmed.size()) {
            return false;
        }

        StringBuilder sb = new StringBuilder();
        trimmed.get(idx).content().accept((position, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        String text = sb.toString();
        if (!text.isBlank()) {
            copyToClipboard(text);
        }
        return true;
    }

    public static void copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (Util.getPlatform() != Util.OS.WINDOWS) {
            LOGGER.warn("Copy Chat: only implemented for Windows right now (detected {})", Util.getPlatform());
            return;
        }
        EXECUTOR.execute(() -> copyToClipboardBlocking(text));
    }

    private static void copyToClipboardBlocking(String text) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "Set-Clipboard -Value $env:KILLER560SMOD_COPY_TEXT");
            builder.environment().put("KILLER560SMOD_COPY_TEXT", text);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Copy Chat: PowerShell timed out");
                return;
            }
            if (process.exitValue() != 0) {
                LOGGER.warn("Copy Chat: PowerShell exited {} - {}", process.exitValue(), output);
                return;
            }
            notifySuccess();
        } catch (IOException e) {
            LOGGER.warn("Copy Chat: clipboard copy failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Copy Chat: clipboard copy interrupted", e);
        }
    }

    private static void notifySuccess() {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal("§6[Killer560's Mod] Chat message copied to clipboard!"));
            }
        });
    }
}
