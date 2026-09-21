package com.killer560.hub.petwheel;

import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Entry point: polls the wheel's own keybind (same raw-poll style {@code AbilityKeybindsFeature} and
 * {@code StorageSearchFeature} already use for a keybind that opens a screen) and drives
 * {@link PetWheelScreen} open/closed for both interaction modes.
 * <p>
 * killer560's "do not open while another screen is open" is the {@code client.screen == null} check below -
 * this never steals focus from a menu, chat, or the mod's own settings screen.
 */
public final class PetWheelFeature {

    private static boolean keyWasDown = false;

    private PetWheelFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PetWheelFeature::tick);
        PetSummoner.register();
    }

    private static void tick(Minecraft client) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        if (!cfg.isEnabled() || !SkyblockGate.allows()) {
            keyWasDown = false;
            return;
        }
        PetsMenuScanner.tick(client);

        if (client.player == null || client.getWindow() == null || cfg.getKeyCode() == KeyUtil.NONE) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isBindDown(client.getWindow(), cfg.getKeyCode());
        boolean pressedEdge = down && !keyWasDown;
        boolean releasedEdge = !down && keyWasDown;
        keyWasDown = down;

        if (pressedEdge && client.screen == null && !PetSummoner.isBusy()) {
            openWheel(client, cfg);
        } else if (releasedEdge && cfg.getMode() == PetWheelConfig.InteractionMode.HOLD_RELEASE
                && client.screen instanceof PetWheelScreen wheel) {
            wheel.confirmSelection();
        }
    }

    private static void openWheel(Minecraft client, PetWheelConfig cfg) {
        List<PetEntry> pets = cfg.getWheelPets();
        if (pets.isEmpty()) {
            return;
        }
        client.setScreen(new PetWheelScreen(pets, cfg.getSliceCount(), cfg.getScalePercent() / 100f, cfg.getMode()));
    }

    /** Called by {@link PetWheelScreen} once a slice is confirmed (click, or key release in hold mode). */
    static void summon(PetEntry pet) {
        if (pet != null) {
            PetSummoner.request(pet);
        }
    }
}
