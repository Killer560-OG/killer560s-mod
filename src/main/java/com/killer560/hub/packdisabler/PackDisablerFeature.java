package com.killer560.hub.packdisabler;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * "Pack Disabler" - stops Hypixel's forced Skyblock resource pack (or any server pack) from ever downloading or
 * loading, PackDisabler-style. {@code PackDisablerMixin} intercepts
 * {@code ClientCommonPacketListenerImpl.handleResourcePackPush} (shared by the configuration and play phases - neither
 * subclass overrides it, verified with javap) right after vanilla's main-thread hop, answers the server itself and
 * skips the vanilla prompt / {@code DownloadedPackSource.pushPack}.
 * <p>
 * Answer: the 1.20.3+ protocol lets a client say DECLINED. Hypixel's Skyblock pack is sent as optional
 * ({@code required=false}), so declining is honest and safe. For packs the server marks required - where a decline
 * gets you disconnected - the classic PackDisabler trick is used instead: report ACCEPTED, DOWNLOADED,
 * SUCCESSFULLY_LOADED (the same status sequence vanilla sends after a real load) without fetching or applying
 * anything. The mode can force either answer. Ships disabled by default; {@link #register()} flushes the optional
 * chat notice once a player exists (packs usually arrive during the configuration phase, before one does).
 */
public final class PackDisablerFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-packdisabler");

    private static volatile String pendingNotice;

    private PackDisablerFeature() {
    }

    public static void register() {
        PackDisablerConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String notice = pendingNotice;
            if (notice != null && client.player != null) {
                pendingNotice = null;
                ModChat.send("Pack Disabler", ModChat.text("Blocked the server resource pack "), ModChat.dim("(" + notice + ")"));
            }
        });
    }

    /** Another mod that intercepts the same packet. Cached after the first call - mods cannot load at runtime. */
    private static Boolean conflictingMod;

    /** @return the id of a loaded mod that also intercepts the resource-pack push, or null. */
    public static String conflictingModId() {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        for (String id : new String[]{"packdisabler", "detexturify"}) {
            if (loader.isModLoaded(id)) {
                return id;
            }
        }
        return null;
    }

    public static boolean conflictingModLoaded() {
        if (conflictingMod == null) {
            String id = conflictingModId();
            conflictingMod = id != null;
            if (id != null) {
                LOGGER.info("[PackDisabler] {} is installed and intercepts the same packet - standing down.", id);
            }
        }
        return conflictingMod;
    }

    /** @return true if the push was handled here and vanilla handling must be skipped. */
    public static boolean onResourcePackPush(ClientCommonPacketListenerImpl listener, ClientboundResourcePackPushPacket packet,
                                              ServerData listenerServer) {
        PackDisablerConfig cfg = PackDisablerConfig.getInstance();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (conflictingModLoaded()) {
            // Noamm's PackDisabler and Detexturify inject at this exact method, cancellable, same as we do -
            // so with both installed whichever mixin happens to run first wins and the other silently does
            // nothing. Theirs does strictly more than ours (it re-serves Hypixel's pack underneath and maps
            // items back to their old textures), so we stand down rather than race it (2026-09-16).
            return false;
        }
        ServerData server = listenerServer != null ? listenerServer : Minecraft.getInstance().getCurrentServer();
        String ip = server == null || server.ip == null ? "" : server.ip.toLowerCase(Locale.US);
        if (cfg.isHypixelOnly() && !ip.contains("hypixel.net")) {
            LOGGER.info("[PackDisabler] Not on hypixel.net ({}), letting vanilla handle pack {}", ip.isEmpty() ? "no server" : ip, packet.id());
            return false;
        }

        boolean fakeLoad = switch (cfg.getMode()) {
            case DECLINE -> false;
            case FAKE_LOADED -> true;
            case SMART -> packet.required();
        };
        try {
            if (fakeLoad) {
                listener.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
                listener.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
                listener.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            } else {
                listener.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DECLINED));
            }
        } catch (Exception e) {
            LOGGER.warn("[PackDisabler] Failed to answer pack push {}, falling back to vanilla handling", packet.id(), e);
            return false;
        }
        LOGGER.info("[PackDisabler] Blocked resource pack {} from {} (required={}, url={}) - answered {}",
                packet.id(), ip.isEmpty() ? "unknown server" : ip, packet.required(), packet.url(),
                fakeLoad ? "ACCEPTED+DOWNLOADED+SUCCESSFULLY_LOADED (not applied)" : "DECLINED");
        if (cfg.isChatNotice()) {
            pendingNotice = fakeLoad ? "pretended loaded" : "declined";
        }
        return true;
    }

    /** Removes any server pack that was already applied before the feature was turned on (same as a server pop-all). */
    public static void unloadServerPacksNow() {
        Minecraft client = Minecraft.getInstance();
        try {
            client.getDownloadedPackSource().popAll();
            LOGGER.info("[PackDisabler] Unloaded all server resource packs on request");
        } catch (Exception e) {
            LOGGER.warn("[PackDisabler] Could not unload server packs", e);
        }
    }
}
