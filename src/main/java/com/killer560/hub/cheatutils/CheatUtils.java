package com.killer560.hub.cheatutils;

import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single entry point + shared helpers for the cheat-build-only Cheat Utils group: {@link SecretAuraFeature},
 * {@link AutoGfsFeature}, {@link AutoUltFeature}, {@link ChocolateFactoryFeature}. Also ticks {@link WitherEspFeature},
 * which is now only the F7/M7 boss-Wither detection Dungeon ESP uses (the Wither ESP toggle moved there).
 * Registering is harmless on the legit build - every feature checks its {@link CheatUtilsConfig} master getter,
 * which is hard-gated on {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 */
public final class CheatUtils {

    public static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-cheatutils");
    public static final String CHAT_TAG = "Cheat Utils";

    private CheatUtils() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WitherEspFeature.tick(client);
            SecretAuraFeature.tick(client);
            AutoGfsFeature.tick(client);
            AutoUltFeature.tick(client);
            ChocolateFactoryFeature.tick(client);
        });
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(CheatUtils::onChat);
        LOGGER.info("[CheatUtils] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    private static void onChat(Component message) {
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null) {
            return;
        }
        WitherEspFeature.onChat(plain);
        AutoGfsFeature.onChat(plain);
        AutoUltFeature.onChat(plain);
    }

    /** hypixel.net or p3sim.net (same server gate SecretsFeature uses privately). */
    public static boolean isOnDungeonServer(Minecraft client) {
        if (client == null) {
            return false;
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) {
            return false;
        }
        String ip = server.ip.toLowerCase(Locale.US);
        return ip.contains("hypixel.net") || ip.contains("p3sim.net");
    }

    /** Hypixel Skyblock item id from CUSTOM_DATA "id" (same technique as DungeonBreakerFeature), or null. */
    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    /** Formatting-stripped lore lines (empty list if none). */
    public static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return out;
        }
        for (Component line : lore.lines()) {
            String s = ChatFormatting.stripFormatting(line.getString());
            out.add(s == null ? "" : s);
        }
        return out;
    }

    public static String plainName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String s = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return s == null ? "" : s;
    }
}
