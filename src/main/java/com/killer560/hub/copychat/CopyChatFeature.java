package com.killer560.hub.copychat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Ctrl+Click any chat message to copy its plain text to the clipboard - general QoL, not
 *  dungeon-specific (roadmap item, referenced from quoi's own "Copy chat" module: "Copies chat on mouse
 *  click"). Shares {@link com.killer560.hub.clicktranslate.ClickTranslateFeature}'s existing click
 *  infrastructure rather than adding a second competing click handler - a chat line's {@code Style} can
 *  only carry one {@code ClickEvent} at a time, so {@code ClickTranslateFeature.tryHandleClick} checks
 *  {@link #isControlDown()} first and delegates here before falling through to its own translate logic;
 *  {@link com.killer560.hub.clicktranslate.mixin.ChatComponentMixin}'s wrap is likewise gated on either
 *  feature being enabled, not just Translate, so Copy still works with Translate off.
 *  <p>
 *  Uses the same PowerShell clipboard shell-out {@link com.killer560.hub.screenshotcopy.ScreenshotCopyFeature}
 *  already established (2026-09-08) - AWT's {@code Toolkit}/{@code Clipboard} throws
 *  {@code HeadlessException} inside Minecraft's process (confirmed via javap:
 *  {@code net.minecraft.client.main.Main}'s static initializer forces {@code java.awt.headless=true}) -
 *  {@code Set-Clipboard} is the plain-text equivalent of that same workaround. Windows-only for now,
 *  matching that same precedent (killer560's own setup is Windows). */
public final class CopyChatFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-copychat");

    private CopyChatFeature() {
    }

    /** @return whether either Ctrl key is currently held, read live via GLFW (same direct-window-handle
     *  pattern {@link com.killer560.hub.window.WindowModeFeature} already uses) rather than from a
     *  specific input event - needed because the click hook this feeds
     *  ({@code ChatScreen.handleComponentClicked}) doesn't receive the raw mouse event with its own
     *  {@code hasControlDown()}, only the already-resolved {@code Style} that was clicked. */
    public static boolean isControlDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    public static void copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (Util.getPlatform() != Util.OS.WINDOWS) {
            LOGGER.warn("Copy Chat: only implemented for Windows right now (detected {})", Util.getPlatform());
            return;
        }
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
