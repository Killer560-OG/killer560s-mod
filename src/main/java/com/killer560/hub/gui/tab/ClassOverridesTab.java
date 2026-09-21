package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.leapmenu.PartyTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor for the mod-wide class override table ({@link ClassOverrides}): rows of IGN -> class that win over what
 * the tab list says. killer560 (2026-09-16): "You could implement the class override system as something mod wide
 * as it would be useful during stuff like the leap menu being changed as well to fit classes whenever we have
 * duplicates. I already have the ability to do it [a] custom way through that, but not actually set their class."
 * And the reason it exists at all: "in case I am in a party with say five mages but one mage is doing berserk's
 * term, one is doing archer's term, etc." - so an override is about which TERMINAL a person is doing, and the
 * same IGN may be given a role that differs from their real class.
 * <p>
 * NOT cheat-only: it changes the normal Leap Menu, Fast Leap's class targeting and every class-coloured display,
 * so it lives with the legit dungeon tabs (orange headers). The AP3 tab shows the same table read-only.
 * <p>
 * Layout, top to bottom: the current party first ("one click rather than typing IGNs" - every teammate gets a
 * cycling Override button: None -> Mage -> Tank -> Healer -> Archer -> Berserker -> None), then overrides for
 * people who are NOT in the party right now (a friend from last run), then a typed IGN for anyone else. Every
 * mutation goes through {@link #apply}/{@link #forget}, which save immediately - the standing rule that every
 * setting survives a restart.
 */
public class ClassOverridesTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;
    private static final int OVERRIDE_W = 130;
    private static final int REMOVE_W = 100;

    /** Scratch for the "Add By Name" row - survives rebuilds because the tab instance does. */
    private String pendingIgn = "";
    private DungeonClass pendingClass = DungeonClass.MAGE;

    public ClassOverridesTab() {
        super("Class Overrides");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        int[] y = {contentY};

        // Tab description moved into the "Class Overrides" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        Map<String, DungeonClass> overrides = safeAll();
        List<String> party = partyNames();

        buildPartySection(w, contentX, y, contentWidth, party, overrides, requestRebuild);
        buildOthersSection(w, contentX, y, contentWidth, party, overrides, requestRebuild);
        buildAddSection(w, contentX, y, contentWidth, requestRebuild);
        return w;
    }

    private void buildPartySection(List<AbstractWidget> w, int x, int[] y, int width, List<String> party,
                                   Map<String, DungeonClass> overrides, Runnable rebuild) {
        header(w, x, y, width, "Current Party");
        if (party.isEmpty()) {
            label(w, x, y, width, "§7No party known yet - join a party or a dungeon and this fills in by itself.");
            return;
        }
        // "Click Override to cycle..." already covered by the "Current Party" tooltip.
        for (String name : party) {
            row(w, x, y, width, name, overrides, rebuild);
        }
        y[0] += GAP;
    }

    private void buildOthersSection(List<AbstractWidget> w, int x, int[] y, int width, List<String> party,
                                    Map<String, DungeonClass> overrides, Runnable rebuild) {
        List<String> others = new ArrayList<>();
        for (String ign : overrides.keySet()) {
            boolean inParty = false;
            for (String p : party) {
                if (p.equalsIgnoreCase(ign)) {
                    inParty = true;
                    break;
                }
            }
            if (!inParty) {
                others.add(ign);
            }
        }
        header(w, x, y, width, "Other Overrides");
        if (others.isEmpty()) {
            label(w, x, y, width, "§7None.");
            return;
        }
        for (String ign : others) {
            row(w, x, y, width, ign, overrides, rebuild);
        }
        y[0] += GAP;
    }

    /** "Name (detected: Mage)   [Override: Berserker]   [Remove Override]" */
    private void row(List<AbstractWidget> w, int x, int[] y, int width, String name, Map<String, DungeonClass> overrides,
                     Runnable rebuild) {
        DungeonClass override = lookup(overrides, name);
        DungeonClass detected = safeDetected(name);
        String text = "§f" + name + " §7(detected: " + (detected == null ? "unknown" : colourCode(detected) + detected.displayName() + "§7") + ")";
        int labelW = Math.max(1, width - OVERRIDE_W - REMOVE_W - GAP * 2);
        w.add(new StringWidget(x, y[0] + 3, labelW, 12, Component.literal(text), Minecraft.getInstance().font));
        w.add(SettingsButtonWidget.builder(overrideText(override), btn -> {
                    DungeonClass next = next(override);
                    if (next == null) {
                        forget(name);
                    } else {
                        apply(name, next);
                    }
                    rebuild.run();
                }).bounds(x + labelW + GAP, y[0], OVERRIDE_W, ROW).build());
        SettingsButtonWidget remove = SettingsButtonWidget.builder(Component.literal("§cRemove Override"), btn -> {
                    forget(name);
                    rebuild.run();
                }).bounds(x + labelW + GAP + OVERRIDE_W + GAP, y[0], REMOVE_W, ROW).build();
        remove.active = override != null;
        w.add(remove);
        y[0] += ROW + 2;
    }

    private void buildAddSection(List<AbstractWidget> w, int x, int[] y, int width, Runnable rebuild) {
        header(w, x, y, width, "Add By Name");
        // "For someone who isn't in the party yet..." already covered by the "Add By Name" tooltip.
        int classW = 130;
        int addW = 100;
        int boxW = Math.max(60, width - classW - addW - GAP * 2);
        EditBox ign = new EditBox(Minecraft.getInstance().font, x, y[0], boxW, ROW, Component.literal("IGN"));
        ign.setMaxLength(16);
        ign.setHint(Component.literal("§8IGN"));
        ign.setValue(pendingIgn);
        ign.setResponder(text -> pendingIgn = text);
        w.add(ign);
        w.add(SettingsButtonWidget.builder(Component.literal("Override Class: " + colourCode(pendingClass) + pendingClass.displayName()), btn -> {
                    DungeonClass n = next(pendingClass);
                    pendingClass = n == null ? DungeonClass.values()[0] : n;
                    btn.setMessage(Component.literal("Override Class: " + colourCode(pendingClass) + pendingClass.displayName()));
                }).bounds(x + boxW + GAP, y[0], classW, ROW).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Add Override"), btn -> {
                    String name = pendingIgn == null ? "" : pendingIgn.trim();
                    if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                        // Not a possible IGN - leave the box alone so the typo is visible, don't store junk.
                        return;
                    }
                    apply(name, pendingClass);
                    pendingIgn = "";
                    rebuild.run();
                }).bounds(x + boxW + GAP + classW + GAP, y[0], addW, ROW).build());
        y[0] += ROW + GAP;
    }

    // ---- mutations (every one saves) ----

    private static void apply(String ign, DungeonClass cls) {
        try {
            ClassOverrides.set(ign, cls);
            ClassOverrides.getInstance().save();
        } catch (Exception ignored) {
        }
    }

    private static void forget(String ign) {
        try {
            ClassOverrides.clear(ign);
            ClassOverrides.getInstance().save();
        } catch (Exception ignored) {
        }
    }

    // ---- lookups (never throw - a tab that fails to build takes the whole settings screen with it) ----

    private static Map<String, DungeonClass> safeAll() {
        try {
            Map<String, DungeonClass> all = ClassOverrides.all();
            return all == null ? new LinkedHashMap<>() : new LinkedHashMap<>(all);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Case-insensitive on the IGN, whatever casing the store kept. */
    private static DungeonClass lookup(Map<String, DungeonClass> overrides, String name) {
        for (Map.Entry<String, DungeonClass> e : overrides.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** What the tab list says this player is - deliberately NOT routed through ClassOverrides, so the row can show
     *  detected and override side by side. */
    private static DungeonClass safeDetected(String name) {
        try {
            return PartyTracker.classOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** You first, then the party in Hypixel's listing order - the leap menu order people already know. */
    static List<String> partyNames() {
        List<String> out = new ArrayList<>();
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                String self = client.player.getGameProfile().name();
                if (self != null && !self.isBlank()) {
                    out.add(self);
                }
            }
            for (String n : PartyTracker.teammates()) {
                boolean dup = false;
                for (String have : out) {
                    if (have.equalsIgnoreCase(n)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(n);
                }
            }
        } catch (Exception ignored) {
            // no player / tracker not ready
        }
        return out;
    }

    /** None -> Mage -> Tank -> Healer -> Archer -> Berserker -> None (enum order), null meaning "no override". */
    static DungeonClass next(DungeonClass current) {
        DungeonClass[] all = DungeonClass.values();
        if (current == null) {
            return all[0];
        }
        int i = current.ordinal() + 1;
        return i >= all.length ? null : all[i];
    }

    private static Component overrideText(DungeonClass override) {
        return Component.literal("Override: " + (override == null ? "§7None" : colourCode(override) + override.displayName()));
    }

    /**
     * The nearest § code to each class's {@link DungeonClass#color()} - StringWidget/button labels are § strings
     * in this GUI, and these are the codes the rest of the mod's class-coloured text already uses.
     */
    public static String colourCode(DungeonClass cls) {
        if (cls == null) {
            return "§7";
        }
        return switch (cls) {
            case MAGE -> "§9";
            case TANK -> "§a";
            case HEALER -> "§d";
            case ARCHER -> "§c";
            case BERSERKER -> "§6";
        };
    }

    /** "IGN §7-> §9Mage" for read-only listings (the AP3 tab). */
    public static String describeOverride(String ign, DungeonClass cls) {
        return "§f" + ign + " §7-> " + colourCode(cls) + (cls == null ? "?" : cls.displayName());
    }

    // ---- widget helpers ----

    private static void label(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        w.add(new StringWidget(x, y[0], width, 12, Component.literal(text), Minecraft.getInstance().font));
        y[0] += 16;
    }

    /** Legit feature - orange header. */
    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, false), Minecraft.getInstance().font));
        y[0] += 16;
    }
}
