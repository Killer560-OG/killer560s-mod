package com.killer560.hub.ap3;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "/ap3 edit &lt;n&gt;" - one node, the fields its type uses, editable.
 *
 * <p>killer560 (2026-09-29): "add in /ap3 edit (node number). This should pop up a gui showing the coordinates
 * of the node the width and length of it any things like jump or edge attached to it and the pitch and yaw of
 * it. Everything for that node should be editable there."
 *
 * <p>killer560 (2026-10-06): "for the edit menu make it so the type is a drop down of sorts"; "For nodes like align
 * i do not need yaw or pitch so dont have it saved, if i swap to a look node then add those boxes in"; "For use item
 * have it be where it reads my hotbar and that is a dropdown where i click on the item"; and the close gate is gone.
 * So: the Type button opens a grid of every type (the same expand-in-place dropdown as {@code /ar edit}'s Await
 * Secrets, so nothing is ever drawn over another control), and the panel shows only the rows the chosen type reads -
 * {@link Ap3Node.Type#usesYaw()}, {@link Ap3Node.Type#usesPitch()}, {@link Ap3Node.Type#usesItem()},
 * {@link Ap3Node.Type#usesName()}, {@link Ap3Node.Type#usesPrecise()}. Rows come and go the moment the type changes;
 * a yaw or pitch that appears for a node that never had one starts at his current view. A Use node's item is a
 * second dropdown listing his nine hotbar slots, plus "held item" and the node's saved item when it is no longer
 * on the bar.
 *
 * <p><b>Every change goes through {@link Ap3Feature#editNode}</b>, which is the same path the commands use: it
 * writes the chain to disk and prints the same confirmation line, and its {@link Ap3Node#roundToSaved()} drops the
 * fields the type does not read, so what is in memory after Save is exactly what the file holds.
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
    private static final int LINE = ROW + GAP;
    private static final int TYPE_COLS = 4;
    private static final int ITEM_COLS = 2;
    private static final int ICON = 16;

    private final Screen parent;
    private final int index;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int statusY;
    private String status = "";

    /** The boxes on screen now, so Save can read them back by name. */
    private final List<Field> fields = new ArrayList<>();
    /** Box text carried across a rebuild - the type changing adds or removes rows. */
    private final Map<String, String> pending = new HashMap<>();
    /** Label text per field: Width reads "Width X" (and Length "Length Z") for a type with no yaw. */
    private final Map<String, String> labels = new HashMap<>();
    /** Item icons drawn over their buttons after the widgets (hotbar dropdown and the Item button). */
    private final List<Icon> icons = new ArrayList<>();

    private boolean loaded;
    private Ap3Node.Type type;
    private Ap3Node.JumpMod jumpMod;
    private boolean precise;
    /** USE: the Skyblock id to swap to, or null for "use whatever is held". */
    private String itemId;
    private boolean typeOpen;
    private boolean itemOpen;
    /** Whether the Yaw / Pitch text holds a real angle (the node's own, or his view once a type needed one). */
    private boolean yawKnown;
    private boolean pitchKnown;

    private record Field(String label, EditBox box, int labelX) {
    }

    private record Icon(ItemStack stack, int x, int y) {
    }

    /** One choice in the hotbar dropdown: {@code id} null = held item; {@code slot} -1 = not on the bar. */
    private record ItemChoice(String id, int slot, ItemStack stack, String text, boolean usable) {
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
        capture();
        icons.clear();
        Ap3Node n = node();
        if (n == null) {
            panelW = PANEL_W;
            panelH = 70;
            panelX = (this.width - panelW) / 2;
            panelY = (this.height - panelH) / 2;
            statusY = -1;
            this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                    .bounds(panelX + PAD, panelY + 40, panelW - PAD * 2, 20).build());
            return;
        }
        if (!loaded) {
            loaded = true;
            type = n.type();
            jumpMod = n.jumpMod == null ? Ap3Node.JumpMod.NONE : n.jumpMod;
            precise = n.precise;
            itemId = n.useItemId == null || n.useItemId.isBlank() ? null : n.useItemId;
            pending.put("X", fmt(n.x));
            pending.put("Y", fmt(n.y));
            pending.put("Z", fmt(n.z));
            pending.put("Width", fmt(n.width));
            pending.put("Length", fmt(n.length));
            pending.put("Wait ms", String.valueOf(n.waitAfterMs));
            pending.put("Name", n.name == null ? "" : n.name);
            yawKnown = type.usesYaw();
            pitchKnown = type.usesPitch();
            pending.put("Yaw", yawKnown ? fmt(n.yaw) : "");
            pending.put("Pitch", pitchKnown ? fmt(n.pitch) : "");
        }

        List<ItemChoice> choices = type.usesItem() && itemOpen ? itemChoices() : List.of();
        int typeRows = (Ap3Node.Type.values().length + TYPE_COLS - 1) / TYPE_COLS;
        int itemRows = (choices.size() + ITEM_COLS - 1) / ITEM_COLS;
        int rows = 1 + (typeOpen ? typeRows : 0) + 1 /* xyz */ + 1 /* box */ + (type.usesYaw() ? 1 : 0)
                + 1 /* wait, name */ + (type.usesItem() ? 1 + itemRows : 0) + 1 /* jump, precise */ + 1 /* from me */;
        panelW = PANEL_W;
        panelH = 32 + rows * LINE + 12 /* status */ + 20 + PAD;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(4, (this.height - panelH) / 2);
        int x = panelX + PAD;
        int w = panelW - PAD * 2;
        int half = (w - GAP) / 2;
        int y = panelY + 32;

        // Type, as a dropdown: the grid of every type opens UNDER the button and pushes the rest down.
        button("Type: §6" + type.label() + (typeOpen ? " §7▲" : " §7▼"), b -> {
            typeOpen = !typeOpen;
            itemOpen = false;
            rebuild();
        }, x, y, w, ROW);
        y += LINE;
        if (typeOpen) {
            Ap3Node.Type[] all = Ap3Node.Type.values();
            int cw = (w - GAP * (TYPE_COLS - 1)) / TYPE_COLS;
            for (int i = 0; i < all.length; i++) {
                final Ap3Node.Type t = all[i];
                int col = i % TYPE_COLS;
                int bx = x + col * (cw + GAP);
                int by = y + (i / TYPE_COLS) * LINE;
                button((t == type ? "§6" : "§7") + t.label(), b -> chooseType(t), bx, by,
                        col == TYPE_COLS - 1 ? x + w - bx : cw, ROW);
            }
            y += typeRows * LINE;
        }

        int third = (w - GAP * 2) / 3;
        addField(x, y, third, "X", 12);
        addField(x + third + GAP, y, third, "Y", 12);
        addField(x + (third + GAP) * 2, y, w - (third + GAP) * 2, "Z", 12);
        y += LINE;

        // A type without a yaw has its box square to the world: width is east-west, length north-south.
        boolean world = !type.usesYaw();
        addField(x, y, half, "Width", world ? "Width X" : "Width", 46);
        addField(x + half + GAP, y, w - half - GAP, "Length", world ? "Length Z" : "Length", 50);
        y += LINE;

        if (type.usesYaw()) {
            if (type.usesPitch()) {
                addField(x, y, half, "Yaw", 26);
                addField(x + half + GAP, y, w - half - GAP, "Pitch", 30);
            } else {
                addField(x, y, half, "Yaw", 26);
            }
            y += LINE;
        }

        if (type.usesName()) {
            addField(x, y, half, "Wait ms", 46);
            addField(x + half + GAP, y, w - half - GAP, "Name", 30);
        } else {
            addField(x, y, half, "Wait ms", 46);
        }
        y += LINE;

        if (type.usesItem()) {
            ItemStack shown = hotbarStack(itemId);
            String text = itemId == null ? "§7held item / none" : "§6" + itemName(shown, itemId)
                    + (shown == null ? " §c(not in hotbar)" : "");
            int bx = x;
            button(pad(shown) + "Item: " + text + (itemOpen ? " §7▲" : " §7▼"), b -> {
                itemOpen = !itemOpen;
                typeOpen = false;
                rebuild();
            }, bx, y, w, ROW);
            if (shown != null) {
                icons.add(new Icon(shown, bx + 2, y));
            }
            y += LINE;
            if (itemOpen) {
                int cw = (w - GAP * (ITEM_COLS - 1)) / ITEM_COLS;
                for (int i = 0; i < choices.size(); i++) {
                    ItemChoice ch = choices.get(i);
                    int col = i % ITEM_COLS;
                    int cx = x + col * (cw + GAP);
                    int cy = y + (i / ITEM_COLS) * LINE;
                    int cwi = col == ITEM_COLS - 1 ? x + w - cx : cw;
                    boolean chosen = java.util.Objects.equals(ch.id(), itemId) && ch.usable();
                    String label = this.font.plainSubstrByWidth(ch.text(), cwi - 2 * (ICON + 6));
                    SettingsButtonWidget opt = button((ch.stack() != null ? "    " : "") + (chosen ? "§6" : ch.usable() ? "§f" : "§8") + label,
                            b -> chooseItem(ch), cx, cy, cwi, ROW);
                    opt.active = ch.usable();
                    if (ch.stack() != null) {
                        icons.add(new Icon(ch.stack(), cx + 2, cy));
                    }
                }
                y += itemRows * LINE;
            }
        }

        if (type.usesPrecise()) {
            button("Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT), b -> {
                jumpMod = cycle(Ap3Node.JumpMod.values(), jumpMod);
                b.setMessage(Component.literal("Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT)));
            }, x, y, half, ROW);
            button(onOff("Precise", precise).getString(), b -> {
                precise = !precise;
                b.setMessage(onOff("Precise", precise));
            }, x + half + GAP, y, w - half - GAP, ROW);
        } else {
            button("Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT), b -> {
                jumpMod = cycle(Ap3Node.JumpMod.values(), jumpMod);
                b.setMessage(Component.literal("Jump / Edge: §6" + jumpMod.name().toLowerCase(Locale.ROOT)));
            }, x, y, w, ROW);
        }
        y += LINE;

        if (type.usesYaw()) {
            button("Position from me", b -> fillFromPlayer(true, false), x, y, half, ROW);
            button("Look from me", b -> fillFromPlayer(false, true), x + half + GAP, y, w - half - GAP, ROW);
        } else {
            button("Position from me", b -> fillFromPlayer(true, false), x, y, w, ROW);
        }
        y += LINE;

        statusY = y + 1;
        y += 12;
        button("§aSave", b -> save(), x, y, half, 20);
        button("Cancel", b -> onClose(), x + half + GAP, y, w - half - GAP, 20);
    }

    /** Copies every box on screen into {@link #pending} and forgets the boxes (they are about to be rebuilt). */
    private void capture() {
        for (Field f : fields) {
            pending.put(f.label(), f.box().getValue());
        }
        fields.clear();
    }

    private void rebuild() {
        capture();
        rebuildWidgets();
    }

    /**
     * The Type dropdown's pick. A yaw or pitch box that appears for a node that never had that angle starts at his
     * current view, as a freshly placed node would. Going the other way, a box that lay along an east or west yaw has
     * its width and length swapped, so dropping the yaw does not turn the box on the ground (the same rule
     * {@link Ap3Node#clearUnusedFields()} applies on Save).
     */
    private void chooseType(Ap3Node.Type t) {
        capture();
        Ap3Node.Type old = type;
        type = t;
        typeOpen = false;
        if (old.usesYaw() && !t.usesYaw()) {
            Double yaw = parse(pending.get("Yaw"));
            if (yaw != null && Math.abs(Math.abs(Math.round(Mth.wrapDegrees(yaw.floatValue()) / 90f) * 90f) - 90f) < 1e-3f) {
                String wv = pending.get("Width");
                pending.put("Width", pending.get("Length"));
                pending.put("Length", wv);
            }
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (t.usesYaw() && !yawKnown && player != null) {
            pending.put("Yaw", fmt(Mth.wrapDegrees(Ap3FreezeState.placementYaw(player))));
            yawKnown = true;
        }
        if (t.usesPitch() && !pitchKnown && player != null) {
            pending.put("Pitch", fmt(Mth.clamp(Ap3FreezeState.placementPitch(player), -90f, 90f)));
            pitchKnown = true;
        }
        status = "§7Type: " + t.label() + " - press Save to apply";
        rebuildWidgets();
    }

    private void chooseItem(ItemChoice ch) {
        if (!ch.usable()) {
            return;
        }
        itemId = ch.id();
        itemOpen = false;
        status = "§7Item: " + (itemId == null ? "held item" : itemId) + " - press Save to apply";
        rebuild();
    }

    /**
     * What the hotbar dropdown offers: "held item / none", then hotbar slots 1-9 in order, then the node's saved item
     * when it is no longer on the bar (so opening the editor never loses it). A slot holding an item with no Skyblock
     * id is listed but cannot be picked - a Use node finds its item by that id ({@code ItemIdentity.findHotbarSlotById}),
     * the same identity it is recorded with at placement.
     */
    private List<ItemChoice> itemChoices() {
        List<ItemChoice> out = new ArrayList<>();
        out.add(new ItemChoice(null, -1, null, "Held item / none", true));
        LocalPlayer player = Minecraft.getInstance().player;
        boolean savedOnBar = itemId == null;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                out.add(new ItemChoice(null, slot, null, (slot + 1) + ": empty", false));
                continue;
            }
            String id = ItemIdentity.skyblockId(stack);
            String name = itemName(stack, id);
            if (id == null || id.isBlank()) {
                out.add(new ItemChoice(null, slot, stack, (slot + 1) + ": " + name + " (no id)", false));
                continue;
            }
            savedOnBar |= id.equalsIgnoreCase(itemId);
            out.add(new ItemChoice(id, slot, stack, (slot + 1) + ": " + name, true));
        }
        if (!savedOnBar) {
            out.add(new ItemChoice(itemId, -1, null, itemId + " (saved, not in hotbar)", true));
        }
        return out;
    }

    /** The hotbar stack whose Skyblock id is {@code id}, or null. */
    private static ItemStack hotbarStack(String id) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (id == null || player == null) {
            return null;
        }
        int slot = ItemIdentity.findHotbarSlotById(player, id);
        return slot < 0 ? null : player.getInventory().getItem(slot);
    }

    private static String itemName(ItemStack stack, String id) {
        if (stack == null || stack.isEmpty()) {
            return id == null ? "" : id;
        }
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return name == null || name.isBlank() ? (id == null ? "" : id) : name.trim();
    }

    /** Leading room for an icon drawn at a button's left edge (the label is centred). */
    private static String pad(ItemStack icon) {
        return icon == null ? "" : "    ";
    }

    private SettingsButtonWidget button(String label, SettingsButtonWidget.OnPress onPress, int x, int y, int w, int h) {
        return this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal(label), onPress)
                .bounds(x, y, w, h).build());
    }

    private void addField(int x, int y, int w, String label, int labelW) {
        addField(x, y, w, label, label, labelW);
    }

    /** A text box {@code w} wide at {@code x}, {@code shown} drawn in its first {@code labelW} pixels. */
    private void addField(int x, int y, int w, String label, String shown, int labelW) {
        EditBox box = new EditBox(this.font, x + labelW, y, w - labelW, ROW, Component.literal(shown));
        box.setMaxLength(32);
        box.setValue(pending.getOrDefault(label, ""));
        this.addRenderableWidget(box);
        fields.add(new Field(label, box, labelW > 0 ? x : -1));
        labels.put(label, shown);
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
            set("Yaw", fmt(Mth.wrapDegrees(Ap3FreezeState.placementYaw(client.player))));
            set("Pitch", fmt(Mth.clamp(Ap3FreezeState.placementPitch(client.player), -90f, 90f)));
        }
        status = "§7Filled from your position - press Save to apply";
    }

    /**
     * Reads every box on screen, refuses the whole save if any of them is not a number, and applies the rest in one
     * {@link Ap3Feature#editNode} call. Fields the type does not show are not read; {@link Ap3Node#roundToSaved()}
     * clears them, so they are not saved either.
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
        Double nlen = num("Length");
        Double nwid = num("Width");
        Double nwait = num("Wait ms");
        Double nyaw = type.usesYaw() ? num("Yaw") : Double.valueOf(0);
        Double npitch = type.usesPitch() ? num("Pitch") : Double.valueOf(0);
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
        final String newName = type.usesName() && !get("Name").isBlank() ? get("Name").trim() : null;
        final String newItem = type.usesItem() ? itemId : null;
        final Ap3Node.Type newType = type;
        Ap3Feature.editNode(index, "everything", node -> {
            node.x = nx;
            node.y = ny;
            node.z = nz;
            // A type without a yaw already had its box turned to the world in chooseType (or never had a yaw), so the
            // width and length typed here are its X and Z extents and the yaw is simply 0.
            node.yaw = (float) (double) nyaw;
            node.pitch = (float) Math.max(-90.0, Math.min(90.0, npitch));
            node.setLength(nlen);
            node.setWidth(nwid);
            node.setWaitAfterMs((int) Math.round(nwait));
            node.type = newType;
            node.jumpMod = jumpMod;
            if (newType.usesPrecise()) {
                node.precise = precise;
            }
            node.name = newName == null ? null : newName.substring(0, Math.min(16, newName.length()));
            node.useItemId = newItem;
        });
        onClose();
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
        boolean onScreen = false;
        for (Field f : fields) {
            if (f.label().equals(label)) {
                f.box().setValue(value);
                onScreen = true;
            }
        }
        if (!onScreen) {
            pending.put(label, value);
        }
        if (label.equals("Yaw")) {
            yawKnown = true;
        } else if (label.equals("Pitch")) {
            pitchKnown = true;
        }
    }

    private Double num(String label) {
        return parse(get(label));
    }

    private static Double parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : null;
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
     * A number in a field, at the node file's precision ({@link Ap3Node#SAVED_DECIMALS}) but without a tail of zeros.
     *
     * <p>The boxes are read back on Save, so this must print at least what the file keeps: at two decimals, simply
     * opening a node's editor and saving it quantised its position and angle; at five (until 2026-10-06) it still
     * dropped the sixth decimal the file has kept since 2026-10-05. Trailing zeros are trimmed so an ordinary value
     * still reads as "3" and not "3.000000".
     */
    private static String fmt(double v) {
        String out = String.format(Locale.US, "%." + Ap3Node.SAVED_DECIMALS + "f", v);
        if (out.contains(".")) {
            out = out.replaceAll("0+$", "");
            if (out.endsWith(".")) {
                out = out.substring(0, out.length() - 1);
            }
        }
        return out.equals("-0") ? "0" : out;
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
            if (f.labelX() >= 0) {
                graphics.text(this.font, labels.getOrDefault(f.label(), f.label()), f.labelX(), f.box().getY() + 4,
                        0xFFBBAA99, false);
            }
        }
        if (!status.isEmpty() && statusY >= 0) {
            graphics.centeredText(this.font, status, panelX + panelW / 2, statusY, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        for (Icon icon : icons) {
            graphics.item(icon.stack(), icon.x(), icon.y());
        }
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
