package com.killer560.hub.autoroutes;

import com.killer560.hub.ap3.Ap3Node;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.pathfinding.EtherwarpHopper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /ar edit <n>} - one Auto Routes node, every field, editable; AP3's {@code Ap3EditScreen} for routes.
 *
 * <p>killer560 (2026-10-04): "Can you have a /ar edit (node number) command. It should have basically the same
 * concept as the AP3 node editor but also a goto button. If I press that it should etherwarp pathfind to the node,
 * same logic as the interactive map, and once it gets there it should put itself into edit mode." And: "it should
 * have a dropdown section for how many secrets to wait on from 0 to 4 and an option to make it a start node or not".
 *
 * <p>Mirrors {@code Ap3EditScreen}: the same panel, text boxes read back on Save and refused whole if one is not a
 * number (a half-applied form is a node he did not ask for), "Position from me" / "Look from me" filling the boxes
 * without applying, Save / Cancel, full-precision numbers with the zeros trimmed. On top of that: the node's
 * type (the five {@code /ar add} types), Start Node, the Await Secrets dropdown, half-block nudges, the use item,
 * the breaker's blocks, Go To and Delete.
 *
 * <p>Coordinates and yaw are shown in WORLD terms (what F3 shows) and turned back into the room-relative values
 * the route stores on Save. A box left exactly as it was keeps the stored value bit for bit, so opening and saving
 * a node never moves it; a changed position is snapped to the half-block grid every placed node sits on
 * ({@code RouteRecorder.snappedFeet}, AP3's snapping), which is what makes two nodes on one tile a stack.
 *
 * <p>Every change goes through {@link AutoRoutesFeature#applyNodeEdit}, which records it in {@link RouteHistory},
 * so {@code /ar undo} reverts a whole Save and {@code /ar redo} re-applies it.
 */
public class AutoRoutesEditScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PAD = 10;
    private static final int ROW = 16;
    private static final int GAP = 4;
    private static final int LABEL_W = 62;
    /** Tooltip scope - {@code SettingTooltipsData} keys these buttons "ar edit/<label>". */
    private static final String TOOLTIP_SCOPE = "ar edit";

    /** The five {@code /ar add} types, in killer560's order. */
    private static final RouteNode.Type[] TYPES = {RouteNode.Type.BOOM, RouteNode.Type.DUNGEON_BREAKER,
            RouteNode.Type.ETHERWARP, RouteNode.Type.USE_ITEM, RouteNode.Type.WALK};
    /** The Await Secrets dropdown's choices; 0 is "no await". */
    private static final int AWAIT_MAX = 4;

    private final Route route;
    private final RouteNode node;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private String status = "";

    private final List<Field> fields = new ArrayList<>();
    /** Box text carried across a rebuild (the type changing shows or hides rows; the dropdown opening adds one). */
    private final Map<String, String> pending = new HashMap<>();
    /** The text each box started with, so an untouched box keeps the stored value exactly. */
    private final Map<String, String> original = new HashMap<>();

    private boolean loaded;
    private RouteNode.Type type;
    private boolean start;
    /** 0..4 secrets, or -1 for "leave the node's own await alone" (a hand-edited delay or a count above 4). */
    private int awaitChoice;
    private boolean awaitOpen;
    private boolean clearBlocks;
    private boolean confirmDelete;

    private AbstractWidget tooltipWidget;
    private long tooltipSinceMs;

    private record Field(String label, EditBox box) {
    }

    public AutoRoutesEditScreen(Route route, RouteNode node) {
        super(Component.literal("Auto Routes node"));
        this.route = route;
        this.node = node;
    }

    private int number() {
        return route == null ? -1 : route.indexOf(node) + 1;
    }

    private boolean alive() {
        return route != null && route.indexOf(node) >= 0 && route == AutoRoutesFeature.editableRoute()
                && AutoRoutesFeature.editableFrame() != null;
    }

    private static boolean aims(RouteNode.Type t) {
        return t == RouteNode.Type.ETHERWARP || t == RouteNode.Type.USE_ITEM || t == RouteNode.Type.WALK;
    }

    @Override
    protected void init() {
        for (Field f : fields) {
            pending.put(f.label(), f.box().getValue());
        }
        fields.clear();
        RouteCoords.Frame frame = AutoRoutesFeature.editableFrame();
        if (!alive() || frame == null) {
            panelW = PANEL_W;
            panelH = 70;
            panelX = (this.width - panelW) / 2;
            panelY = (this.height - panelH) / 2;
            this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                    .bounds(panelX + PAD, panelY + 40, panelW - PAD * 2, 20).build());
            return;
        }
        if (!loaded) {
            loaded = true;
            type = node.type;
            start = node.start;
            if (!node.awaitEnabled) {
                awaitChoice = 0;
            } else if (node.awaitCondition == RouteNode.AwaitCondition.SECRET && node.awaitAmount >= 1
                    && node.awaitAmount <= AWAIT_MAX) {
                awaitChoice = node.awaitAmount;
            } else {
                awaitChoice = -1;
            }
            Vec3 real = RouteCoords.toReal(frame, node.relativePos());
            original.put("X", fmt(real.x));
            original.put("Y", fmt(real.y));
            original.put("Z", fmt(real.z));
            original.put("Yaw", fmt(RouteCoords.toRealYaw(frame, node.yaw)));
            original.put("Pitch", fmt(node.pitch));
            original.put("Item", node.item == null ? "" : node.item);
            pending.putAll(original);
        }
        boolean aim = aims(type);
        boolean use = type == RouteNode.Type.USE_ITEM;
        boolean breaker = type == RouteNode.Type.DUNGEON_BREAKER;
        int rows = 3 + 1 /* nudges */ + (aim ? 2 : 0) + (use ? 1 : 0) + 3 /* type, start, await */
                + (awaitOpen ? 1 : 0) + (breaker ? 1 : 0) + 1 /* from me */;
        panelW = PANEL_W;
        panelH = 32 + rows * (ROW + GAP) + 4 + 20 + GAP + 20 + 14 + PAD;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(4, (this.height - panelH) / 2);
        int x = panelX + PAD;
        int w = panelW - PAD * 2;
        int y = panelY + 32;

        y = addField(x, y, w, "X");
        y = addField(x, y, w, "Y");
        y = addField(x, y, w, "Z");

        // Half-block nudges on the WORLD axes, snapped like a placed node; a block at a time for the height.
        String[] nudgeLabels = {"X -0.5", "X +0.5", "Z -0.5", "Z +0.5", "Y -1", "Y +1"};
        int nw = (w - GAP * 5) / 6;
        for (int i = 0; i < nudgeLabels.length; i++) {
            final String axis = nudgeLabels[i].substring(0, 1);
            final double step = Double.parseDouble(nudgeLabels[i].substring(2));
            int bx = x + i * (nw + GAP);
            button(nudgeLabels[i], b -> nudge(axis, step), bx, y, i == 5 ? x + w - bx : nw, ROW);
        }
        y += ROW + GAP;

        if (aim) {
            y = addField(x, y, w, "Yaw");
            y = addField(x, y, w, "Pitch");
        }
        if (use) {
            y = addField(x, y, w, "Item");
        }

        button(typeLabel(), b -> {
            type = cycleType(type);
            rebuildWidgets();
        }, x, y, w, ROW);
        y += ROW + GAP;

        button(onOff("Start Node", start), b -> {
            start = !start;
            b.setMessage(onOff("Start Node", start));
        }, x, y, w, ROW);
        y += ROW + GAP;

        button(awaitLabel(), b -> {
            awaitOpen = !awaitOpen;
            rebuildWidgets();
        }, x, y, w, ROW);
        y += ROW + GAP;
        if (awaitOpen) {
            int ow = (w - GAP * AWAIT_MAX) / (AWAIT_MAX + 1);
            for (int i = 0; i <= AWAIT_MAX; i++) {
                final int choice = i;
                int bx = x + i * (ow + GAP);
                String label = (choice == awaitChoice ? "§6" : "§7") + (choice == 0 ? "None" : String.valueOf(choice));
                button(label, b -> {
                    awaitChoice = choice;
                    awaitOpen = false;
                    rebuildWidgets();
                }, bx, y, i == AWAIT_MAX ? x + w - bx : ow, ROW);
            }
            y += ROW + GAP;
        }

        if (breaker) {
            int half = (w - GAP) / 2;
            button("Pick Blocks", b -> pickBlocks(), x, y, half, ROW);
            button(onOff("Clear Blocks", clearBlocks), b -> {
                clearBlocks = !clearBlocks;
                b.setMessage(onOff("Clear Blocks", clearBlocks));
            }, x + half + GAP, y, w - half - GAP, ROW);
            y += ROW + GAP;
        }

        int third = (w - GAP * 2) / 3;
        button("Position from me", b -> fillFromPlayer(true, false, false), x, y, aim || use ? third : w, ROW);
        if (aim) {
            button("Look from me", b -> fillFromPlayer(false, true, false), x + third + GAP, y, third, ROW);
        }
        if (use) {
            int bx = x + (third + GAP) * 2;
            button("Item from hand", b -> fillFromPlayer(false, false, true), bx, y, x + w - bx, ROW);
        }
        y += ROW + GAP + 4;

        int half = (w - GAP) / 2;
        button("§aSave", b -> {
            if (save()) {
                onClose();
            }
        }, x, y, half, 20);
        button("§6Go To", b -> goTo(), x + half + GAP, y, w - half - GAP, 20);
        y += 20 + GAP;
        button(confirmDelete ? "§cConfirm Delete" : "§cDelete", b -> delete(), x, y, half, 20);
        button("Cancel", b -> onClose(), x + half + GAP, y, w - half - GAP, 20);
    }

    private void button(String label, SettingsButtonWidget.OnPress onPress, int x, int y, int w, int h) {
        button(Component.literal(label), onPress, x, y, w, h);
    }

    private void button(Component label, SettingsButtonWidget.OnPress onPress, int x, int y, int w, int h) {
        this.addRenderableWidget(SettingsButtonWidget.builder(label, onPress).bounds(x, y, w, h).build());
    }

    private int addField(int x, int y, int w, String label) {
        EditBox box = new EditBox(this.font, x + LABEL_W, y, w - LABEL_W, ROW, Component.literal(label));
        box.setMaxLength(label.equals("Item") ? RouteStore.MAX_ITEM_ID : 32);
        box.setValue(pending.getOrDefault(label, ""));
        this.addRenderableWidget(box);
        fields.add(new Field(label, box));
        return y + ROW + GAP;
    }

    private String typeLabel() {
        return "Type: §6" + type.label();
    }

    private String awaitLabel() {
        String v;
        if (awaitChoice < 0) {
            v = "§7kept (" + node.modifierTag().replace(" [", "").replace("]", "").replace("start, ", "") + ")";
        } else {
            v = awaitChoice == 0 ? "§7none" : "§6" + awaitChoice + " secret" + (awaitChoice == 1 ? "" : "s");
        }
        return "Await Secrets: " + v + (awaitOpen ? " §7▲" : " §7▼");
    }

    private static RouteNode.Type cycleType(RouteNode.Type current) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == current) {
                return TYPES[(i + 1) % TYPES.length];
            }
        }
        return TYPES[0]; // a legacy type (start / await / ...) steps onto the first real one
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aon" : "§7off"));
    }

    /** Moves the position boxes by {@code step} along a world axis, snapping x/z to the half-block grid. */
    private void nudge(String axis, double step) {
        Double v = num(axis);
        if (v == null) {
            status = "§c" + axis + " is not a number.";
            return;
        }
        double out = axis.equals("Y") ? Ap3Node.snapY(v + step) : Ap3Node.snapCentre(v + step);
        set(axis, fmt(out));
        status = "§7Moved - press Save to apply";
    }

    /** Fills boxes from where he stands / looks / what he holds, without applying anything yet. */
    private void fillFromPlayer(boolean position, boolean look, boolean item) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (position) {
            Vec3 p = RouteRecorder.snappedFeet(player);
            set("X", fmt(p.x));
            set("Y", fmt(p.y));
            set("Z", fmt(p.z));
        }
        if (look) {
            set("Yaw", fmt(player.getYRot()));
            set("Pitch", fmt(player.getXRot()));
        }
        if (item) {
            String id = ItemIdentity.of(player.getMainHandItem());
            if (id == null) {
                status = "§cHold the item first.";
                return;
            }
            set("Item", id);
        }
        status = "§7Filled from you - press Save to apply";
    }

    /**
     * Reads every box into a copy of the node, refusing the whole save when anything is wrong, then hands the copy
     * to {@link AutoRoutesFeature#applyNodeEdit}. @return true when saved (or nothing needed saving)
     */
    private boolean save() {
        RouteCoords.Frame frame = AutoRoutesFeature.editableFrame();
        if (!alive() || frame == null) {
            status = "§cThat node is gone - the route changed or you left the room.";
            return false;
        }
        RouteNode edited = node.copy();
        edited.type = type;

        Vec3 rel = node.relativePos();
        boolean moved = false;
        if (!same("X") || !same("Y") || !same("Z")) {
            Double nx = num("X");
            Double ny = num("Y");
            Double nz = num("Z");
            if (nx == null || ny == null || nz == null) {
                status = "§cA coordinate is not a number - nothing was changed.";
                return false;
            }
            Vec3 r = RouteCoords.toRelative(frame, Ap3Node.snapCentre(nx), Ap3Node.snapY(ny), Ap3Node.snapCentre(nz));
            if (Math.abs(r.x) > RouteStore.MAX_ABS_COORD || Math.abs(r.y) > RouteStore.MAX_ABS_COORD
                    || Math.abs(r.z) > RouteStore.MAX_ABS_COORD) {
                status = "§cThat position is outside the room's range - nothing was changed.";
                return false;
            }
            moved = r.x != rel.x || r.y != rel.y || r.z != rel.z;
            edited.x = r.x;
            edited.y = r.y;
            edited.z = r.z;
        }
        boolean turned = false;
        if (aims(type) && (!same("Yaw") || !same("Pitch"))) {
            Double nyaw = num("Yaw");
            Double npitch = num("Pitch");
            if (nyaw == null || npitch == null) {
                status = "§cYaw or pitch is not a number - nothing was changed.";
                return false;
            }
            if (!same("Yaw")) {
                // Stored yaw is DATA (wrapped for the file, as RouteRecorder stores it); never written to the player.
                edited.yaw = RouteCoords.toRelativeYaw(frame, (float) (double) nyaw);
            }
            if (!same("Pitch")) {
                edited.pitch = (float) Math.max(-90.0, Math.min(90.0, npitch));
            }
            turned = edited.yaw != node.yaw || edited.pitch != node.pitch;
        }
        if (type == RouteNode.Type.USE_ITEM) {
            String item = RouteStore.cleanString(get("Item"), RouteStore.MAX_ITEM_ID);
            if (item == null) {
                status = "§cA Use Item node needs an item - hold it and press Item from hand.";
                return false;
            }
            edited.item = item;
        }
        edited.start = start;
        if (awaitChoice == 0) {
            edited.awaitEnabled = false;
        } else if (awaitChoice > 0) {
            edited.awaitEnabled = true;
            edited.awaitCondition = RouteNode.AwaitCondition.SECRET;
            edited.awaitAmount = awaitChoice;
        }
        if (clearBlocks && type == RouteNode.Type.DUNGEON_BREAKER) {
            edited.breakerBlocks.clear();
        }
        if (moved && !route.path().isEmpty()) {
            // Re-anchor to the recorded movement where the node now stands, as /ar add anchors a new node.
            edited.pathIndex = route.path().nearest(0, route.path().size() - 1, edited.x, edited.y, edited.z);
        }
        if (moved || turned || type != node.type) {
            relandIfEtherwarp(edited, frame);
        }
        return AutoRoutesFeature.applyNodeEdit(route, node, edited);
    }

    /**
     * The landing playback checks an etherwarp against, recomputed from the node's new spot and look with the same
     * prediction {@code RouteRecorder.addNode} uses; any other moved node drops its old landing, which then no longer
     * describes it (playback falls back to "you moved").
     */
    private static void relandIfEtherwarp(RouteNode n, RouteCoords.Frame frame) {
        n.hasLanding = false;
        if (n.type != RouteNode.Type.ETHERWARP) {
            return;
        }
        Vec3 feet = RouteCoords.toReal(frame, n.relativePos());
        Vec3 eye = new Vec3(feet.x, feet.y + TeleportUtils.eyeHeight(true), feet.z);
        double range = Math.max(1.0, EtherwarpHopper.range());
        TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, RouteCoords.toRealYaw(frame, n.yaw), n.pitch, range);
        if (hit.succeeded() && hit.pos() != null) {
            n.setLanding(RouteCoords.toRelative(frame, new Vec3(hit.pos().getX() + 0.5, hit.pos().getY() + 1.05,
                    hit.pos().getZ() + 0.5)));
        }
    }

    /** Go To: saves first (a refused save stays open and says why), then warps the way the Interactive Map does. */
    private void goTo() {
        if (!save()) {
            return;
        }
        onClose();
        AutoRoutesFeature.goToNode(route, node);
    }

    /** Saves, then breaker edit mode on this node - {@code /ar edit db} aimed at it. */
    private void pickBlocks() {
        if (!save()) {
            return;
        }
        onClose();
        AutoRoutesFeature.enterEditModeAt(node);
    }

    /** Two presses: the first arms, the second deletes through the same path as {@code /ar delete <n>}. */
    private void delete() {
        if (!alive()) {
            status = "§cThat node is gone already.";
            return;
        }
        if (!confirmDelete) {
            confirmDelete = true;
            status = "§cPress Confirm Delete to remove #" + number() + " (/ar undo brings it back).";
            rebuildWidgets();
            return;
        }
        int index = route.indexOf(node);
        onClose();
        AutoRoutesCommands.delete(index);
    }

    private boolean same(String label) {
        return get(label).trim().equals(original.getOrDefault(label, "").trim());
    }

    private String get(String label) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                return f.box().getValue();
            }
        }
        return pending.getOrDefault(label, "");
    }

    private void set(String label, String value) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                f.box().setValue(value);
            }
        }
        pending.put(label, value);
    }

    private Double num(String label) {
        try {
            double v = Double.parseDouble(get(label).trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Full precision without a tail of zeros - {@code Ap3EditScreen.fmt}, for the same reason (docs/AP3.md). */
    private static String fmt(double v) {
        String out = String.format(Locale.US, "%.5f", v);
        if (out.contains(".")) {
            out = out.replaceAll("0+$", "");
            if (out.endsWith(".")) {
                out = out.substring(0, out.length() - 1);
            }
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF553311);
        boolean ok = alive();
        String title = ok ? "Auto Routes node #" + number() : "Auto Routes node";
        graphics.centeredText(this.font, title, panelX + panelW / 2, panelY + 8, SectionHeaders.color(true));
        String sub = ok ? "§7" + route.roomName()
                + (type == RouteNode.Type.DUNGEON_BREAKER ? " §8- " + node.breakerBlocks.size() + " block(s)" : "")
                + (node.type == type ? "" : " §8(type changes on Save)")
                : "§cthat node is gone";
        graphics.centeredText(this.font, sub, panelX + panelW / 2, panelY + 19, 0xFF9A8C80);
        for (Field f : fields) {
            graphics.text(this.font, f.label(), panelX + PAD, f.box().getY() + 4, 0xFFBBAA99, false);
        }
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, status, panelX + panelW / 2, panelY + panelH - 12, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawTooltip(graphics, mouseX, mouseY);
    }

    /** The settings menu's hover descriptions ({@code ModScreen.drawSettingTooltip}), for this screen's buttons. */
    private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        AbstractWidget hovered = null;
        for (var child : this.children()) {
            if (child instanceof AbstractWidget w && w.visible && !(w instanceof EditBox)
                    && mouseX >= w.getX() && mouseX < w.getX() + w.getWidth()
                    && mouseY >= w.getY() && mouseY < w.getY() + w.getHeight()) {
                hovered = w;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (hovered != tooltipWidget) {
            tooltipWidget = hovered;
            tooltipSinceMs = now;
        }
        if (hovered == null || now - tooltipSinceMs < 350L) {
            return;
        }
        String text = SettingTooltips.describe(TOOLTIP_SCOPE, hovered.getMessage().getString());
        if (text == null || text.isBlank()) {
            return;
        }
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(text), 220);
        int w = 0;
        for (var line : lines) {
            w = Math.max(w, this.font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + w + 8 > this.width) {
            x = Math.max(4, mouseX - w - 16);
        }
        if (y + h + 8 > this.height) {
            y = Math.max(4, mouseY - h - 16);
        }
        graphics.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0xF00D0D0D);
        graphics.outline(x - 4, y - 4, w + 8, h + 8, 0xFFCC6600);
        int ly = y;
        for (var line : lines) {
            graphics.text(this.font, line, x, ly, 0xFFF0E6DC, false);
            ly += 10;
        }
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
