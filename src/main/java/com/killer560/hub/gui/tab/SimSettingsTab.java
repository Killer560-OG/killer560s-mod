package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.roomsim.SimBreakerState;
import com.killer560.hub.roomsim.SimRoomCycle;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Sim settings. For now one: how many Dungeon Breaker charges each secret gives back, which is the Wither
 * Essence Shop perk "Echoes of the Lost" (levels 1 to 5 give 1 to 5 charges; 0 here is the perk not bought).
 * killer560 (2026-10-06): "the regaining charges should be an optional slider or setting somewhere."
 * The route-practice keys (Next Room, Previous Room, the same as {@code /next} and {@code /back}; one store,
 * {@link SimRoomCycle}) are a section below, merged in from the old Sim Keybinds card (killer560, 2026-11-04: "move sim
 * keybinds and sim settings into one block").
 */
public class SimSettingsTab extends BaseTab implements KeyCaptureTab {

    /** 0 none, 1 Next Room, 2 Previous Room. */
    private int capturing;

    public SimSettingsTab() {
        super("Sim Settings");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void cancelKeyCapture() {
        capturing = 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        apply(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        apply(KeyUtil.codeForMouseButton(button));
    }

    private void apply(int code) {
        if (capturing == 1) {
            SimRoomCycle.setNextKey(code);
        } else if (capturing == 2) {
            SimRoomCycle.setBackKey(code);
        }
        capturing = 0;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        w.add(new ThemedSliderButton(contentX, contentY, contentWidth, 18, echoesText(),
                SimBreakerState.secretCharges() / (double) SimBreakerState.MAX_SECRET_CHARGES) {
            @Override
            protected void updateMessage() {
                setMessage(echoesText());
            }

            @Override
            protected void applyValue() {
                SimBreakerState.setSecretCharges((int) Math.round(this.value * SimBreakerState.MAX_SECRET_CHARGES));
            }
        });
        // Spawn Starred Mobs (killer560, 2026-11-03: "Someone should be able to choose whether the mobs spawn or not for the
        // sim"): the Map Designer's Mobs choice, the same saved setting - a floor with starred mobs (on their learned
        // spawn spots where Blood Rush Helper knows them), Fels, wither doors and keys. Off by default, as it was.
        w.add(onOffButton("Spawn Starred Mobs", () -> com.killer560.hub.roomsim.SimMenuPrefs.load().mobs, on -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            prefs.mobs = on;
            prefs.save();
            com.killer560.hub.roomsim.SimMobFloor.setEnabled(on);
        }, () -> { }, requestRebuild, contentX, contentY + 22, contentWidth, 18));
        int y = contentY + 44;
        // Real Mob Looks (simblood-1110): the starred mobs as Blood Rush Helper's learning saw them on Hypixel (type, gear,
        // skin, facing); a spot with no recorded look keeps the dummy. Only with the mobs on.
        com.killer560.hub.roomsim.SimMenuPrefs shown = com.killer560.hub.roomsim.SimMenuPrefs.cached();
        if (shown.mobs || isSearchScan()) {
            w.add(onOffButton("Real Mob Looks", () -> com.killer560.hub.roomsim.SimMenuPrefs.cached().realMobs, on -> {
                com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
                prefs.realMobs = on;
                prefs.save();
            }, () -> { }, null, contentX, y, contentWidth, 18));
            y += 22;
        }
        // Watcher Blood Camp (simblood-1110, killer560 2026-11-10: "fully recreate the blood camp"): the Watcher, his lines
        // and his waves in the Blood room; OFF keeps the five seconds in Blood that open the portal.
        w.add(onOffButton("Watcher Blood Camp", () -> com.killer560.hub.roomsim.SimMenuPrefs.cached().bloodCamp, on -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            prefs.bloodCamp = on;
            prefs.save();
        }, () -> { }, null, contentX, y, contentWidth, 18));
        y += 22;
        // Sim Armor (simarmor-1115): the set the sim player wears, four pieces, bonuses always at max.
        w.add(SettingsButtonWidget.cycleEnum(() -> Component.literal("Sim Armor: " + com.killer560.hub.roomsim.SimLoadout.armor().label),
                com.killer560.hub.roomsim.SimLoadout::armor, com.killer560.hub.roomsim.SimLoadout::setArmor, () -> { })
                .bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        // Sim Teammates (siminfleap-1116): standing teammates to leap to and to fill the tab's party, 0-4.
        w.add(SettingsButtonWidget.cycling(teammatesText(), (btn, dir) -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            prefs.teammates = SettingsButtonWidget.step(prefs.teammates, dir, com.killer560.hub.roomsim.SimTeammates.MAX + 1);
            prefs.save();
            btn.setMessage(teammatesText());
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        // Sim Class and level (simclasses-1116): the class Drop / Drop Stack use, shown in the tab and sidebar.
        w.add(SettingsButtonWidget.cycling(classText(), (btn, dir) -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            com.killer560.hub.dungeonclass.DungeonClass[] all = com.killer560.hub.dungeonclass.DungeonClass.values();
            prefs.simClass = all[SettingsButtonWidget.step(prefs.simClass.ordinal(), dir, all.length)];
            prefs.save();
            com.killer560.hub.roomsim.SimClasses.selfChanged(true);
            btn.setMessage(classText());
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        w.add(SettingsButtonWidget.cycling(classLevelText(), (btn, dir) -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            prefs.classLevel = 1 + SettingsButtonWidget.step(prefs.classLevel - 1, dir, com.killer560.hub.roomsim.SimClasses.MAX_LEVEL);
            prefs.save();
            com.killer560.hub.roomsim.SimClasses.selfChanged(false);
            btn.setMessage(classLevelText());
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        // Receive Sim Invites (coopsim phase 3): ON keeps the relay socket a room owner's invite arrives through.
        var coop = com.killer560.hub.roomsim.SimCoopConfig.get();
        w.add(onOffButton("Receive Sim Invites", coop::receiveInvites, coop::setReceiveInvites, () -> { }, null,
                contentX, y, contentWidth, 18));
        y += 22;
        int blocked = coop.blocked().size();
        if (blocked > 0) {
            w.add(SettingsButtonWidget.builder(Component.literal("Clear Blocked Inviters: " + blocked), b -> {
                coop.clearBlocked();
                requestRebuild.run();
            }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }
        y += 2;
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Keybinds", false));
        y += 16;
        w.add(SettingsButtonWidget.builder(text("Next Room", 1, SimRoomCycle.getNextKey(), "/next"), b -> {
            capturing = 1;
            b.setMessage(Component.literal("Next Room Key: §ePress any key..."));
        }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        w.add(SettingsButtonWidget.builder(text("Previous Room", 2, SimRoomCycle.getBackKey(), "/back"), b -> {
            capturing = 2;
            b.setMessage(Component.literal("Previous Room Key: §ePress any key..."));
        }).bounds(contentX, y, contentWidth, 20).build());
        com.killer560.hub.gui.ScreenThemes.append(w, com.killer560.hub.roomsim.SimMenuScreen.THEME_ID, contentX,
                contentWidth, contentY + 22);
        return w;
    }

    private Component text(String label, int which, int code, String command) {
        if (capturing == which) {
            return Component.literal(label + " Key: §ePress any key...");
        }
        String name = code == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(code);
        return Component.literal(label + " Key: " + name + " §8" + command);
    }

    private static Component classText() {
        return Component.literal("Sim Class: " + com.killer560.hub.roomsim.SimClasses.name(
                com.killer560.hub.roomsim.SimMenuPrefs.cached().simClass));
    }

    private static Component classLevelText() {
        return Component.literal("Sim Class Level: " + com.killer560.hub.roomsim.SimMenuPrefs.cached().classLevel);
    }

    private static Component teammatesText() {
        int n = com.killer560.hub.roomsim.SimMenuPrefs.cached().teammates;
        return Component.literal("Sim Teammates: " + (n == 0 ? "None" : String.valueOf(n)));
    }

    private static Component echoesText() {
        int n = SimBreakerState.secretCharges();
        return Component.literal("Echoes of the Lost: " + (n == 0 ? "Off" : "Level " + n + " (+" + n + ")"));
    }
}
