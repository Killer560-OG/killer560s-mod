package com.killer560.hub.petwheel;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.petwheel.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ActionGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;

import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Right-click-a-slot half of the Pet Wheel's reworked edit mode (killer560, 2026-09-27): "If i right click a
 * slot it opens my pet menu and lets me click a pet, that pet then replaces the pet in that slot." Drives the
 * real {@code /pets} menu open (same {@code sendCommand("pets")} call {@link PetSummoner} already uses - "pets"
 * is never a client command this mod registers, so unlike {@code /fl}/{@code /ah}/{@code /bz} there is no
 * {@code ServerCommands} recursion risk here), then hijacks the PLAYER'S OWN next click on a real pet slot
 * there.
 * <p>
 * That click is captured and CANCELLED, never replayed as a programmatic click of our own - the wheel is a
 * cosmetic "which pet to summon later" list, not the live-equipped pet, so picking a pet to go in a wheel slot
 * must never also equip/despawn whatever was clicked in the real menu as a side effect. This is a deliberate,
 * more conservative reading than "let the click go through AND record it" - see this wave's task brief's own
 * "never send anything the user did not explicitly click" rule; letting a real equip slip out from under an
 * edit action would be exactly that.
 * <p>
 * One pick in flight at a time, same "false/ignored, never queued" shape {@link PetSummoner#request} uses.
 */
final class PetWheelEditor {

    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");

    private static boolean armed = false;
    private static boolean sentCommand = false;
    private static int targetSlotIndex = -1;
    private static Screen returnParent = null;

    private PetWheelEditor() {
    }

    /** Registered once from {@link PetWheelFeature#register()}. */
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PetWheelEditor.tick", PetWheelEditor::tick));
        ScreenEvents.AFTER_INIT.register(PetWheelEditor::onScreenInit);
    }

    /** Called by {@link PetWheelScreen} on a right click of slot {@code wheelIndex} while in edit mode.
     *  {@code editScreen} closes itself the instant this is armed (see {@link #tick}) - real {@code /pets}
     *  only opens once nothing else has a screen up. */
    static void beginPick(int wheelIndex, PetWheelScreen editScreen) {
        if (armed) {
            return;
        }
        armed = true;
        sentCommand = false;
        targetSlotIndex = wheelIndex;
        returnParent = editScreen.editParent();
        Minecraft client = Minecraft.getInstance();
        McCompat.setScreen(client, null);
    }

    private static void tick(Minecraft client) {
        if (!armed || sentCommand) {
            return;
        }
        if (McCompat.screen(client) != null) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || player.connection == null) {
            cancel("no player");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_CMD)) {
            return;
        }
        player.connection.sendCommand("pets");
        sentCommand = true;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int w, int h) {
        if (!armed || !sentCommand) {
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !PETS_TITLE.matcher(container.getTitle().getString()).matches()) {
            return;
        }
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> onPetsClick(container, event));
        ScreenEvents.remove(screen).register(s -> {
            // Still armed here means the menu closed (Escape, world change, etc.) without a pick ever
            // landing in onPetsClick - reopen the edit wheel unchanged rather than leaving the player looking
            // at whatever screen (or none) comes next.
            if (armed) {
                reopenEditScreen();
            }
        });
    }

    private static boolean onPetsClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!armed || event.button() != 0) {
            return true;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        Slot hovered = accessor.killer560smod$getHoveredSlot();
        if (hovered == null) {
            return true; // Next Page / outside any slot - let vanilla handle it normally
        }
        PetEntry entry = PetsMenuScanner.scanSlot(hovered.getItem());
        if (entry == null) {
            return true; // empty slot / non-pet item / navigation item - normal click goes through
        }
        int slot = targetSlotIndex;
        armed = false; // consumed - the ScreenEvents.remove hook above must not also try to reopen
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        cfg.recordSeenPet(entry); // definitely a real pet now, same bookkeeping a passive scan tick would do
        cfg.replaceOrAppendWheelSlot(slot, entry);
        cfg.save();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (McCompat.screen(client) == screen) {
                screen.onClose();
            }
            reopenEditScreen();
        });
        return false; // cancelled - never reaches vanilla's own summon/despawn click handling
    }

    private static void reopenEditScreen() {
        Screen parent = returnParent;
        armed = false;
        sentCommand = false;
        targetSlotIndex = -1;
        returnParent = null;
        Minecraft.getInstance().execute(() -> McCompat.setScreen(Minecraft.getInstance(), PetWheelScreen.forEdit(parent)));
    }

    private static void cancel(String why) {
        armed = false;
        sentCommand = false;
        targetSlotIndex = -1;
        returnParent = null;
    }
}
