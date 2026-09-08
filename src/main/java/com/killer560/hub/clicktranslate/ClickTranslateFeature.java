package com.killer560.hub.clicktranslate;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.translate.TranslateFeature;
import com.killer560.hub.translate.TranslateLanguages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Left-clicking any player chat message translates it to English, per killer560's request. Hooked
 * two ways: {@link com.killer560.hub.clicktranslate.mixin.ChatComponentMixin} wraps every incoming
 * player message with a custom {@link ClickEvent.Custom} whose NBT payload carries the message's
 * own plain text - no side-table of message id -&gt; text is needed, so nothing can go stale or
 * leak. {@link com.killer560.hub.clicktranslate.mixin.ChatScreenClickMixin} then recognizes that
 * custom click event in {@code ChatScreen.handleComponentClicked} and calls {@link #tryHandleClick}
 * instead of letting vanilla try to interpret it as a URL/command.
 */
public final class ClickTranslateFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-clicktranslate");
    private static final Identifier TRANSLATE_ID = Identifier.fromNamespaceAndPath("killer560smod", "translate_click");

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-click-translate");
        t.setDaemon(true);
        return t;
    });

    public static Component wrap(Component message) {
        if (!ClickTranslateConfig.getInstance().isEnabled()) {
            return message;
        }
        String plain = message.getString();
        if (plain.isBlank()) {
            return message;
        }
        ClickEvent clickEvent = new ClickEvent.Custom(TRANSLATE_ID, Optional.of(StringTag.valueOf(plain)));
        return Component.empty().append(message).withStyle(style -> style.withClickEvent(clickEvent));
    }

    /** @return true if {@code style}'s click event was one of ours and has been fully handled. */
    public static boolean tryHandleClick(Style style) {
        LOGGER.info("tryHandleClick: style={} clickEvent={}", style, style == null ? null : style.getClickEvent());
        if (style == null || !(style.getClickEvent() instanceof ClickEvent.Custom custom)) {
            return false;
        }
        if (!TRANSLATE_ID.equals(custom.id())) {
            return false;
        }
        if (!(custom.payload().orElse(null) instanceof StringTag stringTag) || stringTag.value().isBlank()) {
            return true;
        }
        // Strip formatting codes and peel off the "Party > Name: "/"[MVP+] Name: " sender prefix
        // before translating - sending the raw line (codes, rank tags, and all) both feeds the
        // translator noise it doesn't need and, confirmed from a real test, can throw off language
        // auto-detection entirely (a clean Japanese sentence got misdetected as Hungarian once the
        // English-heavy prefix was mixed in). The prefix is kept as-is and reattached for display.
        String plain = ChatFormatting.stripFormatting(stringTag.value());
        int colonIdx = plain.indexOf(": ");
        String prefix = colonIdx >= 0 ? plain.substring(0, colonIdx + 2) : "";
        String content = colonIdx >= 0 ? plain.substring(colonIdx + 2) : plain;
        if (content.isBlank()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        String targetCode = ClickTranslateConfig.getInstance().getTargetLanguageCode();
        CompletableFuture.supplyAsync(() -> {
            try {
                return TranslateFeature.translate(content, "auto", targetCode);
            } catch (Exception e) {
                LOGGER.warn("Click-to-translate failed", e);
                return (TranslateFeature.TranslationResult) null;
            }
        }, EXECUTOR).thenAccept(result -> client.execute(() -> {
            if (result == null) {
                ModOverlayMessage.show("§c[Killer560's Mod] Translation failed", 3000);
                return;
            }
            String fromCode = result.detectedLanguageCode();
            String fromSuffix = (fromCode == null || fromCode.isBlank())
                    ? "" : " §7(from " + TranslateLanguages.nameForCode(fromCode) + ")";
            String targetName = TranslateLanguages.nameForCode(targetCode);
            client.gui.getChat().addClientSystemMessage(
                    Component.literal("§b[Killer560's Mod → " + targetName + "] §f" + prefix + result.text() + fromSuffix));
        }));
        return true;
    }

    private ClickTranslateFeature() {
    }
}
