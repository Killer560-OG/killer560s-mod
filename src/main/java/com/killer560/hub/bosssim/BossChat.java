package com.killer560.hub.bosssim;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the boss says, sent the way Hypixel's server sends it: system chat lines in section-sign colors turned into styled
 * components (so a feature's {@code getString()} reads the plain text), and titles as times, title, subtitle.
 */
final class BossChat {

    private BossChat() {
    }

    /** A {@code [BOSS]} line: {@code §4[BOSS] <name>§r§c: <line>}. */
    static void boss(MinecraftServer server, String name, String line) {
        chat(server, "§4[BOSS] " + name + "§r§c: " + line);
        BossFight.speak(name, line);
    }

    static void chat(MinecraftServer server, String legacyText) {
        Component c = legacy(legacyText);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(c);
        }
    }

    static void title(MinecraftServer server, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
            p.connection.send(new ClientboundSetTitleTextPacket(legacy(title)));
            if (subtitle != null && !subtitle.isEmpty()) {
                p.connection.send(new ClientboundSetSubtitleTextPacket(legacy(subtitle)));
            }
        }
    }

    /** Section-sign text as styled components (the 16 colors reset the style, the rest add to it). */
    static Component legacy(String text) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '§' && i + 1 < text.length()) {
                ChatFormatting f = ChatFormatting.getByCode(text.charAt(i + 1));
                if (f != null) {
                    if (run.length() > 0) {
                        out.append(Component.literal(run.toString()).setStyle(style));
                        run.setLength(0);
                    }
                    if (f == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (f.ordinal() < 16) {
                        style = Style.EMPTY.applyFormat(f);
                    } else {
                        style = style.applyFormat(f);
                    }
                    i++;
                    continue;
                }
            }
            run.append(ch);
        }
        if (run.length() > 0 || out.getSiblings().isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
        }
        return out;
    }
}
