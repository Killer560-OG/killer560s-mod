package com.killer560.hub.ap3;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * "/ap3 edit &lt;n&gt;" - one node, every field, editable.
 *
 * <p>killer560 (2026-09-29): "add in /ap3 edit (node number). This should pop up a gui showing the coordinates
 * of the node the width and length of it any things like jump or edge attached to it and the pitch and yaw of
 * it. Everything for that node should be editable there."
 *
 * <p>Until now a node could only be changed a field at a time through {@code /ap3 set &lt;n&gt; ...}, with the
 * current values only visible in {@code /ap3 list}'s one-line summary - so adjusting a node meant reading a
 * line, typing a command, reading the line again. This is the same data on one panel.
 *
 * <p><b>Every change goes through {@link Ap3Feature#editNode}</b>, which is the same path the commands use: it
 * writes the chain to disk and prints the same confirmation line. Nothing here touches a node directly, so a
 * field edited on this screen and the same field edited by a command cannot behave differently, and there is
 * no second place that has to remember to save.
 *
 * <p>Applied on Save rather than per keystroke, because a half-typed coordinate is a real coordinate: typing
 * "-" then "1" then "2" through a live responder would move the node to -1 on the way to -12, and a node that
 * moves while you are typing its position is worse than one that waits.
 */
public class Ap3EditScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PAD = 10;
    private static final int ROW = 16;
    private static final int GAP = 4;
    private static final int LABEL_W = 62;

    private final Screen parent;
    private final int index;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private String status = "";

    /** The boxes, in the order they are drawn, so Save can read them back by name. */
    private final List<Field> fields = new ArrayList<>();

    private Ap3Node.Type type;
    private Ap3Node.JumpMod jumpMod;
    private boolean precise;
    private boolean closeGate;

    private record Field(String label, EditBox box) {
    }

    public Ap3EditScreen(Screen parent, int index) {
        super(Component.literal("AP3 node #" + (index + 1)));
        this.parent = parent;
        this.index = index;
    }

    /** The node this screen edits, or null when the chain changed under it. */
    private Ap3Node node() {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        return index < 0 || index >= nodes.size() ? null : nodes.get(index);
    }

    @Override
    protected void init() {
        fields.clear();
        Ap3Node n = node();
        if (n == null) {
            panelW = PANEL_W;
            panelH = 70;
            panelX = (this.width - panelW) / 2;
            panelY = (this.height - panelH) / 2;
            this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                    .bounds(panelX + PAD, panelY + 40, panelW - PAD * 2, 20).build());
            return;
        }
        if (type == null) {
            type = n.type();
            jumpMod = n.jumpMod == null ? Ap3Node.JumpMod.NONE : n.jumpMod;
            precise = n.precise;
            closeGate = n.closeGate;
        }

        // 9 text rows, 4 toggle rows, the two "from me" buttons, and Save/Cancel.
        panelW = PANEL_W;
        panelH = 32 + 10 * (ROW + GAP) + 4 * (ROW + GAP) + (ROW + GAP) + 24 + PAD;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int x = panelX + PAD;
        int w = panelW - PAD * 2;
        int y = panelY + 32;

        y = addField(x, y, w, "X", fmt(n.x));
        y = addField(x, y, w, "Y", fmt(n.y));
        y = addField(x, y, w, "Z", fmt(n.z));
        y = addField(x, y, w, "Yaw", fmt(n.yaw));
        y = addField(x, y, w, "Pitch", fmt(n.pitch));
        y = addField(x, y, w, "Length", fmt(n.length));
        y = addField(x, y, w, "Width", fmt(n.width));
        y = addField(x, y, w, "Wait ms", String.valueOf(n.waitAfterMs));
        y = addField(x, y, w, "Name", n.name == null ? "" : n.name);
        // The Use node's item. Shown for every type - it is only read for a USE, and hiding it would mean the
        // panel changed shape as he cycled the type, which is worse than one row that sometimes does nothing.
        y = addField(x, y, w, "Use item", n.useItemId == null ? "" : n.useItemId);

        // Type, and the jump/edge modifier he asked about by name.
        this.addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal("Type: §6" + type.name().toLowerCase(Locale.ROOT)), b -> {
                    type = cycle(Ap3Node.Type.values(), type);
                    b.setMessage(Component.literal("Type: §6" + type.name().toLowerCase(Locale.ROOT)));
                }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;

        this.addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal("Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT)), b -> {
                    jumpMod = cycle(Ap3Node.JumpMod.values(), jumpMod);
                    b.setMessage(Component.literal(
                            "Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT)));
                }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;

        this.addRenderableWidget(SettingsButtonWidget.builder(onOff("Precise", precise), b -> {
            precise = !precise;
            b.setMessage(onOff("Precise", precise));
        }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;

        this.addRenderableWidget(SettingsButtonWidget.builder(onOff("Close Gate", closeGate), b -> {
            closeGate = !closeGate;
            b.setMessage(onOff("Close Gate", closeGate));
        }).bounds(x, y, w, ROW).build());
        y += ROW + GAP;

        int half = (w - GAP) / 2;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Position from me"),
                b -> fillFromPlayer(true, false)).bounds(x, y, half, ROW).build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Look from me"),
                b -> fillFromPlayer(false, true)).bounds(x + half + GAP, y, w - half - GAP, ROW).build());
        y += ROW + GAP + 4;

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aSave"), b -> save())
                .bounds(x, y, half, 20).build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x + half + GAP, y, w - half - GAP, 20).build());
    }

    private int addField(int x, int y, int w, String label, String value) {
        EditBox box = new EditBox(this.font, x + LABEL_W, y, w - LABEL_W, ROW, Component.literal(label));
        box.setMaxLength(32);
        box.setValue(value);
        this.addRenderableWidget(box);
        fields.add(new Field(label, box));
        return y + ROW + GAP;
    }

    /** Fills the position and/or look boxes from where he is standing, without applying anything yet. */
    private void fillFromPlayer(boolean position, boolean look) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (position) {
            set("X", fmt(client.player.getX()));
            set("Y", fmt(client.player.getY()));
            set("Z", fmt(client.player.getZ()));
        }
        if (look) {
            set("Yaw", fmt(client.player.getYRot()));
            set("Pitch", fmt(client.player.getXRot()));
        }
        status = "§7Filled from your position - press Save to apply";
    }

    /**
     * Reads every box, refuses the whole save if any of them is not a number, and applies the rest in one
     * {@link Ap3Feature#editNode} call.
     *
     * <p>All-or-nothing on purpose: applying the valid half of a form and reporting the other half as an error
     * leaves the node in a state he did not ask for and did not see.
     */
    private void save() {
        Ap3Node n = node();
        if (n == null) {
            status = "§cThat node is gone - the chain changed.";
            return;
        }
        Double nx = num("X");
        Double ny = num("Y");
        Double nz = num("Z");
        Double nyaw = num("Yaw");
        Double npitch = num("Pitch");
        Double nlen = num("Length");
        Double nwid = num("Width");
        Double nwait = num("Wait ms");
        if (nx == null || ny == null || nz == null || nyaw == null || npitch == null
                || nlen == null || nwid == null || nwait == null) {
            status = "§cOne of the numbers is not a number - nothing was changed.";
            return;
        }
        if (Math.abs(nx) > Ap3Store.MAX_ABS_COORD || Math.abs(ny) > Ap3Store.MAX_ABS_COORD
                || Math.abs(nz) > Ap3Store.MAX_ABS_COORD) {
            status = "§cCoordinates are limited to +/-" + (int) Ap3Store.MAX_ABS_COORD + ".";
            return;
        }
        // The setters clamp length, width and wait to their own limits, and pitch to +/-90. Yaw is left
        // uncapped on purpose: this mod never wraps a yaw to 0..360 - see the project's CLAUDE.md.
        final String newName = get("Name").isBlank() ? null : get("Name").trim();
        final String newUseItem = get("Use item").isBlank() ? null : get("Use item").trim();
        Ap3Feature.editNode(index, "everything", node -> {
            node.x = nx;
            node.y = ny;
            node.z = nz;
            node.yaw = (float) (double) nyaw;
            node.pitch = (float) Math.max(-90.0, Math.min(90.0, npitch));
            node.setLength(nlen);
            node.setWidth(nwid);
            node.setWaitAfterMs((int) Math.round(nwait));
            node.type = type;
            node.jumpMod = jumpMod;
            node.precise = precise;
            node.closeGate = closeGate;
            node.name = newName;
            node.useItemId = newUseItem;
        });
        onClose();
    }

    private String get(String label) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                return f.box().getValue();
            }
        }
        return "";
    }

    private void set(String label, String value) {
        for (Field f : fields) {
            if (f.label().equals(label)) {
                f.box().setValue(value);
            }
        }
    }

    private Double num(String label) {
        try {
            return Double.parseDouble(get(label).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> T cycle(T[] values, T current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aon" : "§7off"));
    }

    /**
     * A number in a field, at full precision but without a tail of zeros.
     *
     * <p>This printed two decimals, and the fields are read back on Save - so simply opening a node's editor
     * and saving it quantised its position and its angle to a hundredth, and "Look from me" recorded a rounded
     * copy of where he was looking rather than where he was looking. Five decimals is past what a float
     * carries, and the trailing zeros are trimmed so an ordinary value still reads as "3" and not "3.00000".
     */
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
        graphics.centeredText(this.font, this.title, panelX + panelW / 2, panelY + 8,
                SectionHeaders.color(true));
        Ap3Node n = node();
        String sub = n == null ? "§cno such node" : "§7" + Ap3Feature.currentChainLabel();
        graphics.centeredText(this.font, sub, panelX + panelW / 2, panelY + 19, 0xFF9A8C80);
        for (Field f : fields) {
            graphics.text(this.font, f.label(), panelX + PAD, f.box().getY() + 4, 0xFFBBAA99, false);
        }
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, status, panelX + panelW / 2, panelY + panelH - 26, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
