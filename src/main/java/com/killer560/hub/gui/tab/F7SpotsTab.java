package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.f7spots.AimSituation;
import com.killer560.hub.f7spots.AimSpot;
import com.killer560.hub.f7spots.F7SpotsConfig;
import com.killer560.hub.f7spots.F7SpotsFeature;
import com.killer560.hub.f7spots.WalkWaypoint;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.witherdragons.P5State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * F7 Spots settings - walk-to waypoints and Last Breath aim spots (see {@link F7SpotsFeature}). Everything
 * defaults OFF and both coordinate lists start empty; the "Add ... Here" buttons and
 * {@code killer560smod-f7spots.json} are how they get filled.
 * <p>
 * The "New waypoint"/"New aim spot" dropdowns only decide what the NEXT added entry is tagged with - they are
 * screen-local, not saved settings. "Auto" tags it with wherever you're standing right now (current floor/phase,
 * your class).
 * <p>
 * <b>2026-09-21:</b> Storm's crush timer (HUD, title, purple pad highlight, pad cycle) moved to the Tick Timers
 * tab - killer560: "Move them to Tick Timers. One home per timer. F7 Spots keeps its waypoints; every countdown
 * lives on Tick Timers." See {@code ticktimers.CrushTimer} / {@code TickTimersTab}.
 */
public class F7SpotsTab extends BaseTab {

    private static final String AUTO = "AUTO";
    private static final String[] PHASES = {AUTO, "ANY", "P1", "P2", "P3", "P4", "P5"};
    private static final String[] FLOORS = {AUTO, "BOTH", "F7", "M7"};
    private static final String[] CLASSES = {AUTO, "ANY", "MAGE", "ARCHER", "HEALER", "BERSERKER", "TANK"};

    private static int newPhaseIndex = 0;
    private static int newFloorIndex = 0;
    private static int newClassIndex = 0;
    /** null = auto (the situation for the phase you're in). */
    private static AimSituation newSituation = null;

    public F7SpotsTab() {
        super("F7 Spots");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        // ---- Walk-to waypoints ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Walk-To Waypoints", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Walk Waypoints", cfg::getWalkWaypointsRaw, cfg::setWalkWaypoints));
        widgets.add(colorButton(col2X, y, colW, "Waypoint Color", cfg.getWalkColor(), F7SpotsConfig.DEFAULT_WALK_COLOR,
                argb -> cfg.setWalkColor(argb)));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Waypoint Labels", cfg::isWalkLabels, cfg::setWalkLabels));
        widgets.add(toggle(col2X, y, colW, "Waypoint Distance", cfg::isWalkDistance, cfg::setWalkDistance));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Current Phase Only", cfg::isWalkCurrentPhaseOnly, cfg::setWalkCurrentPhaseOnly));
        widgets.add(new ThemedSliderButton(col2X, y, colW, 18, beamText(cfg),
                cfg.getWalkBeamHeight() / F7SpotsConfig.MAX_BEAM_HEIGHT) {
            @Override
            protected void updateMessage() {
                setMessage(beamText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setWalkBeamHeight((float) (this.value * F7SpotsConfig.MAX_BEAM_HEIGHT));
                cfg.save();
            }
        });
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("New Waypoint Phase: §b" + PHASES[newPhaseIndex]), btn -> {
                    newPhaseIndex = (newPhaseIndex + 1) % PHASES.length;
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("New Waypoint Floor: §b" + FLOORS[newFloorIndex]), btn -> {
                    newFloorIndex = (newFloorIndex + 1) % FLOORS.length;
                    requestRebuild.run();
                }).bounds(col2X, y, colW, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Waypoint Here"), btn -> {
                    addWaypoint(cfg, mc);
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Remove Last Waypoint: " + cfg.getWalkWaypoints().size() + " saved"), btn -> {
                    if (cfg.removeLastWalkWaypoint()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(col2X, y, colW, 18).build());
        y += 28;

        // ---- Aim spots ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Last Breath Aim Spots", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Aim Spots", cfg::getAimSpotsRaw, cfg::setAimSpots));
        widgets.add(colorButton(col2X, y, colW, "Aim Spot Color", cfg.getAimColor(), F7SpotsConfig.DEFAULT_AIM_COLOR,
                argb -> cfg.setAimColor(argb)));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Aim Spot Labels", cfg::isAimLabels, cfg::setAimLabels));
        widgets.add(toggle(col2X, y, colW, "Aim Spot Distance", cfg::isAimDistance, cfg::setAimDistance));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Show All Classes", cfg::isAimAllClasses, cfg::setAimAllClasses));
        widgets.add(toggle(col2X, y, colW, "Show All Situations", cfg::isAimAllSituations, cfg::setAimAllSituations));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Arrow Stack Spots", cfg::isAimArrowStack, cfg::setAimArrowStack));
        widgets.add(toggle(col2X, y, colW, "Devonian LB Spots", cfg::isAimDevonianLb, cfg::setAimDevonianLb));
        y += 22;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, sizeText(cfg),
                (cfg.getAimSize() - F7SpotsConfig.MIN_AIM_SIZE) / (F7SpotsConfig.MAX_AIM_SIZE - F7SpotsConfig.MIN_AIM_SIZE)) {
            @Override
            protected void updateMessage() {
                setMessage(sizeText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setAimSize((float) (F7SpotsConfig.MIN_AIM_SIZE
                        + this.value * (F7SpotsConfig.MAX_AIM_SIZE - F7SpotsConfig.MIN_AIM_SIZE)));
                cfg.save();
            }
        });
        y += 22;
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("New Aim Situation: §b" + (newSituation == null ? AUTO : newSituation.label)), btn -> {
                    newSituation = newSituation == null ? AimSituation.ANY
                            : (newSituation == AimSituation.P5_PURPLE ? null : newSituation.next());
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("New Aim Class: §b" + CLASSES[newClassIndex]), btn -> {
                    newClassIndex = (newClassIndex + 1) % CLASSES.length;
                    requestRebuild.run();
                }).bounds(col2X, y, colW, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Aim Spot Here"), btn -> {
                    addAimSpot(cfg, mc);
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Remove Last Aim Spot: " + cfg.getAimSpots().size() + " saved"), btn -> {
                    if (cfg.removeLastAimSpot()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(col2X, y, colW, 18).build());
        return widgets;
    }

    /** Adds the block you're standing on, tagged with the selected (or current) phase and floor. */
    private static void addWaypoint(F7SpotsConfig cfg, Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        var pos = mc.player.blockPosition();
        String phase = PHASES[newPhaseIndex];
        if (AUTO.equals(phase)) {
            Floor7Tracker.Phase current = Floor7Tracker.getPhase() == Floor7Tracker.Phase.UNKNOWN
                    ? Floor7Tracker.getPhaseAt() : Floor7Tracker.getPhase();
            phase = current == Floor7Tracker.Phase.UNKNOWN ? WalkWaypoint.ANY_PHASE : current.name();
        }
        String floor = FLOORS[newFloorIndex];
        if (AUTO.equals(floor)) {
            String current = DungeonState.getFloor();
            floor = current == null ? WalkWaypoint.BOTH_FLOORS : current.toUpperCase(Locale.ROOT);
        }
        int n = cfg.getWalkWaypoints().size() + 1;
        cfg.addWalkWaypoint(new WalkWaypoint(pos.getX(), pos.getY(), pos.getZ(), "Spot " + n, 0, phase, floor));
        cfg.save();
        ModChat.send("F7 Spots", ModChat.text("Added waypoint " + n + " at "),
                ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                ModChat.dim(" (" + phase + " / " + floor + ")"));
    }

    /** Adds the point you're looking at (the crosshair's hit position), falling back to your eye position. */
    private static void addAimSpot(F7SpotsConfig cfg, Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        HitResult hit = mc.hitResult;
        Vec3 at = hit != null && hit.getType() != HitResult.Type.MISS ? hit.getLocation() : mc.player.getEyePosition();
        AimSituation situation = newSituation;
        if (situation == null) {
            Floor7Tracker.Phase current = Floor7Tracker.getPhase() == Floor7Tracker.Phase.UNKNOWN
                    ? Floor7Tracker.getPhaseAt() : Floor7Tracker.getPhase();
            situation = switch (current) {
                case P2 -> AimSituation.P2_STORM;
                case P3 -> AimSituation.P3_TERMS;
                default -> AimSituation.ANY;
            };
        }
        String clazz = CLASSES[newClassIndex];
        if (AUTO.equals(clazz)) {
            DungeonClass self = P5State.selfClass();
            clazz = self == null ? AimSpot.ANY_CLASS : self.name();
        }
        int n = cfg.getAimSpots().size() + 1;
        cfg.addAimSpot(new AimSpot(round(at.x), round(at.y), round(at.z), "Aim " + n, situation, clazz, 0));
        cfg.save();
        ModChat.send("F7 Spots", ModChat.text("Added aim spot " + n + " at "),
                ModChat.value(String.format(Locale.US, "%.2f, %.2f, %.2f", at.x, at.y, at.z)),
                ModChat.dim(" (" + situation.name() + " / " + clazz + ")"));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Component beamText(F7SpotsConfig cfg) {
        float h = cfg.getWalkBeamHeight();
        return Component.literal(h <= 0f ? "Waypoint Beam: §cOFF"
                : String.format(Locale.US, "Waypoint Beam: %.1f", h));
    }

    private static Component sizeText(F7SpotsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Aim Spot Size: %.2f", cfg.getAimSize()));
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                F7SpotsConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            F7SpotsConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
