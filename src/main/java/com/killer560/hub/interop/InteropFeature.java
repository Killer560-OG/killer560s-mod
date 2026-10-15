package com.killer560.hub.interop;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

/**
 * Party Interop: one place that collects what the whole party knows about the current dungeon run, from
 * whichever source can supply it.
 * <p>
 * killer560 (2026-09-20): "The issue with reading their mods based of me having them assumes someone else
 * using my mod also has them. Which I don't want them to have to do." So the order of preference is:
 * <ol>
 * <li>{@link SelfDerivation} - work it out from what Hypixel sends every client. Needs nothing installed
 * anywhere and is always on when this feature is on.</li>
 * <li>{@link InteropChatParser} - read other dungeon mods' party-chat announcements. Needs nothing installed
 * on our side; helps whenever a party mate happens to run one of them.</li>
 * <li>Our own relay ({@code com.killer560.hub.relay}) - structured data between users of THIS mod. That is the
 * real answer for a party who all run this mod, and it needs nobody else's cooperation. The seam is
 * {@link PartyInteropState}'s offer/read API with {@link InteropSource#RELAY}; nothing in this package
 * implements the transport.</li>
 * <li>{@link LocalModBridge} - read another mod's own state inside this Minecraft. Off by default, because it
 * only ever does anything on a machine that already has that mod installed.</li>
 * </ol>
 * Nothing here runs outside Skyblock/p3sim ({@link com.killer560.hub.util.SkyblockGate} via
 * {@link InteropConfig#isEnabled()}) or outside a dungeon ({@link DungeonState#isInDungeon()}).
 */
public final class InteropFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-interop");
    /** 10 client ticks - the bridge polls at 2 Hz, which is far more often than any of this actually changes. */
    private static final int BRIDGE_POLL_TICKS = 10;

    private static boolean wasInDungeon = false;
    /** The live map's grid generation last seen: a new floor without leaving the dungeon (a new Dungeon Sim map) is a
     *  new run too. killer560 (2026-10-14): after a new sim map, a room cleared on the previous map showed its secrets
     *  as already found - the room facts are keyed by room name and outlived the floor. */
    private static int lastGridGeneration = -1;
    private static int tickCounter = 0;

    private InteropFeature() {
    }

    public static void register() {
        SelfDerivation.register();
        InteropChatParser.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InteropFeature.tick", InteropFeature::tick));
        LOGGER.info("[Interop] Registered (other dungeon mods detected here: {})", DetectedMods.describe());
    }

    private static void tick(Minecraft client) {
        InteropConfig cfg = InteropConfig.getInstance();
        boolean inDungeon = cfg.isEnabled() && DungeonState.isInDungeon();
        int gen = com.killer560.hub.livemap.LiveMapFeature.resetGeneration();
        boolean newFloor = inDungeon && wasInDungeon && lastGridGeneration >= 0 && gen != lastGridGeneration;
        lastGridGeneration = gen;
        if (inDungeon != wasInDungeon || newFloor) {
            wasInDungeon = inDungeon;
            PartyInteropState.reset();
            SelfDerivation.onRunReset();
            LocalModBridge.onRunReset();
        }
        if (!inDungeon) {
            return;
        }
        if (++tickCounter < BRIDGE_POLL_TICKS) {
            return;
        }
        tickCounter = 0;
        if (cfg.isLocalBridge()) {
            LocalModBridge.poll();
        }
    }

    /** Convenience for other features: was the mimic killed, according to anyone? */
    public static boolean mimicKilled() {
        return InteropConfig.getInstance().isEnabled()
                && PartyInteropState.flag(PartyInteropState.Flag.MIMIC_KILLED);
    }

    /**
     * Whether a party mate's own mod already announced this in party chat this run. Used to stop us repeating
     * an announcement the party has already seen - with five dungeon mods in one party, the same "Mimic
     * Killed!" being said four times is noise, not information.
     */
    public static boolean alreadyAnnouncedInParty(PartyInteropState.Flag flag) {
        if (!InteropConfig.getInstance().isEnabled() || !InteropConfig.getInstance().isChatParsing()) {
            return false;
        }
        return PartyInteropState.announcedInParty(flag);
    }
}
