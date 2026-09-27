package com.killer560.hub.dungeonclass;

import com.killer560.hub.dungeonclass.mixin.ContainerScreenPosAccessor;
import com.killer560.hub.leapmenu.PartyTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560 9.1, verbatim: "Baisically once I click on one of the 5 classes on that main page, have it have a
 * list of party members with their current class at the top of my screen. Then in each of those boxes in the
 * middle it should show something like archer, tank, healer, whatever it would normally be if I were playing
 * the originally selected class, but as a faint sort of background. Then I should be able to drag and drop or
 * click and it replaces/goes to that spot."
 * <p>
 * <b>The existing class-selection screen handling this builds on:</b> {@code partyfinder.PartyFinderParser}
 * already knows the Catacombs Gate class item shows {@code "Currently Selected: X"} lore, and
 * {@code partyfinder.PartyFinderOverlay} already reads it for the highlight feature's "don't double up on my
 * own class" rule. Neither one, though, needs to know exactly which slot holds which of the five class icons -
 * there is nowhere in this codebase (or documented anywhere Hypixel publishes) that pins those five slot
 * indices down, and guessing them would silently misplace this overlay the moment the guess was wrong. So
 * instead of hard-coding slot numbers, {@link #rescan} finds them the same way the rest of this mod finds
 * everything else about a Hypixel menu: by reading what the item actually says (its plain display name is
 * exactly the class name, e.g. {@code "Healer"}) - which works regardless of the exact slot layout or which
 * screen the game happens to show the five classes in, and keeps working if Hypixel ever rearranges either.
 * <p>
 * <b>Assignment.</b> There is no real Hypixel mechanic for putting a class on someone else - each player picks
 * their own. So "drag and drop or click and it replaces/goes to that spot" is implemented as a local planning
 * tool over the mod's own existing "who is meant to be playing what" store, {@link ClassOverrides} (the exact
 * same one the Class Overrides tab already edits, and every class-coloured display in the mod already reads) -
 * not a second, throwaway assignment table. Interaction is click-based (the request explicitly offers it as an
 * alternative to drag-and-drop): click a name in the top bar to pick it up, then click one of the five class
 * boxes to drop it there (or click the same box again to put it back down without moving it).
 */
public final class ClassSelectionOverlay {

    private static final Map<String, DungeonClass> CLASS_NAME_ALIASES = buildAliases();

    /** Minimum number of the five class-name items that must be found among a screen's own slots before this
     *  treats it as the real class-selection screen - guards against some unrelated menu coincidentally
     *  containing one item literally named "Mage". */
    private static final int MIN_CLASSES_TO_ACTIVATE = 3;

    private static final int BAR_Y = 6;
    private static final int CHIP_H = 14;
    private static final int CHIP_GAP = 4;

    private static AbstractContainerScreen<?> scannedScreen;
    private static final Map<DungeonClass, Slot> classSlots = new EnumMap<>(DungeonClass.class);
    /** Party member currently "picked up" from the top bar, waiting for a class box click - null means nobody. */
    private static String pickedName;
    /** Chip hit-boxes from the last frame drawn, {@code [x0, y0, x1, y1]} - {@link #mouseClicked} hit-tests
     *  against these rather than recomputing layout itself, so the two can never disagree. */
    private static final Map<String, int[]> chipBounds = new LinkedHashMap<>();

    private ClassSelectionOverlay() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClassSelectionOverlay::tick);
    }

    private static void tick(Minecraft client) {
        ClassSelectionOverlayConfig cfg = ClassSelectionOverlayConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
            clear();
            return;
        }
        if (screen != scannedScreen) {
            clear();
        }
        rescan(client, screen);
        if (classSlots.isEmpty()) {
            clear();
        }
    }

    private static void rescan(Minecraft client, AbstractContainerScreen<?> screen) {
        Map<DungeonClass, Slot> found = new EnumMap<>(DungeonClass.class);
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == client.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            DungeonClass cls = classOfName(ChatFormatting.stripFormatting(stack.getHoverName().getString()));
            if (cls != null) {
                found.putIfAbsent(cls, slot);
            }
        }
        if (found.size() >= MIN_CLASSES_TO_ACTIVATE) {
            scannedScreen = screen;
            classSlots.clear();
            classSlots.putAll(found);
        } else if (screen == scannedScreen) {
            clear();
        }
    }

    private static void clear() {
        scannedScreen = null;
        classSlots.clear();
        pickedName = null;
        chipBounds.clear();
    }

    private static DungeonClass classOfName(String raw) {
        if (raw == null) {
            return null;
        }
        return CLASS_NAME_ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, DungeonClass> buildAliases() {
        Map<String, DungeonClass> m = new LinkedHashMap<>();
        for (DungeonClass c : DungeonClass.values()) {
            m.put(c.displayName().toLowerCase(Locale.ROOT), c);
        }
        // Hypixel's own item/chat text says "Berserk", not this enum's "Berserker" (same alias PartyTracker's
        // parseClass already applies for the exact same reason).
        m.put("berserk", DungeonClass.BERSERKER);
        return m;
    }

    // ------------------------------------------------------------------------------------------ rendering

    /** Called from {@link com.killer560.hub.dungeonclass.mixin.ClassSelectionScreenMixin} once per frame,
     *  after the screen has drawn its own contents - draws the top party bar and the five class boxes' faint
     *  labels/assignments on top of everything else. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (screen != scannedScreen || classSlots.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Font font = client.font;
        renderPartyBar(graphics, font, client);
        renderClassBoxes(screen, graphics, font);
    }

    private static void renderPartyBar(GuiGraphicsExtractor graphics, Font font, Minecraft client) {
        chipBounds.clear();
        List<String> names = new ArrayList<>();
        String self = client.player.getGameProfile().name();
        if (self != null && !self.isBlank()) {
            names.add(self);
        }
        names.addAll(PartyTracker.teammates());

        int x = 6;
        for (String name : names) {
            DungeonClass cls = PartyTracker.classOf(name);
            int color = cls != null ? cls.color() : 0xFFAAAAAA;
            int textW = font.width(name);
            int chipW = textW + 10;
            boolean picked = name.equalsIgnoreCase(pickedName);
            // Filled when picked up (so it's obvious which name is waiting to be dropped), outlined otherwise.
            if (picked) {
                graphics.fill(x, BAR_Y, x + chipW, BAR_Y + CHIP_H, (0x90 << 24) | (color & 0xFFFFFF));
            }
            graphics.outline(x, BAR_Y, chipW, CHIP_H, 0xFF000000 | (color & 0xFFFFFF));
            graphics.text(font, name, x + 5, BAR_Y + 3, picked ? 0xFF000000 : 0xFFFFFFFF, false);
            chipBounds.put(name, new int[]{x, BAR_Y, x + chipW, BAR_Y + CHIP_H});
            x += chipW + CHIP_GAP;
        }
    }

    private static void renderClassBoxes(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Font font) {
        for (Map.Entry<DungeonClass, Slot> e : classSlots.entrySet()) {
            DungeonClass cls = e.getKey();
            Slot slot = e.getValue();
            int x0 = screenX(screen, slot);
            int y0 = screenY(screen, slot);

            // The faint background label - "whatever it would normally be", i.e. the class this box has always
            // meant, kept legible underneath whichever teammate ends up assigned to it. Translucency comes from
            // the colour argument's own alpha byte (DungeonClass#colorWithAlpha), not RenderSystem.setShaderColor
            // - that call does not exist on this MC version (javap-verified against the mapped jar).
            graphics.centeredText(font, cls.displayName().toUpperCase(Locale.ROOT), x0 + 8, y0 + 4,
                    cls.colorWithAlpha(0x50));

            String assigned = assignedTo(cls);
            if (assigned != null) {
                graphics.outline(x0 - 1, y0 - 1, 18, 18, cls.color());
                graphics.centeredText(font, assigned, x0 + 8, y0 + 18, 0xFFFFFFFF);
            }
        }
    }

    /** First party member (self included) this box's class is the {@link ClassOverrides} for, or null. */
    private static String assignedTo(DungeonClass cls) {
        Minecraft client = Minecraft.getInstance();
        List<String> names = new ArrayList<>();
        if (client.player != null) {
            names.add(client.player.getGameProfile().name());
        }
        names.addAll(PartyTracker.teammates());
        for (String name : names) {
            if (ClassOverrides.classOf(name, null) == cls) {
                return name;
            }
        }
        return null;
    }

    private static int screenX(AbstractContainerScreen<?> screen, Slot slot) {
        return leftPos(screen) + slot.x;
    }

    private static int screenY(AbstractContainerScreen<?> screen, Slot slot) {
        return topPos(screen) + slot.y;
    }

    private static int leftPos(AbstractContainerScreen<?> screen) {
        return ((ContainerScreenPosAccessor) screen).killer560smod$leftPos();
    }

    private static int topPos(AbstractContainerScreen<?> screen) {
        return ((ContainerScreenPosAccessor) screen).killer560smod$topPos();
    }

    // ------------------------------------------------------------------------------------------ input

    /** @return true if this click was ours (a chip or a class box) and vanilla should not also handle it -
     *  called from {@link com.killer560.hub.dungeonclass.mixin.ClassSelectionScreenMixin} at the very start of
     *  {@code mouseClicked}, before vanilla gets a chance to treat a click above the menu's own texture as
     *  "clicked outside, close the screen". */
    public static boolean mouseClicked(AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
        if (screen != scannedScreen || classSlots.isEmpty() || button != 0) {
            return false;
        }
        for (Map.Entry<String, int[]> e : chipBounds.entrySet()) {
            int[] b = e.getValue();
            if (mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3]) {
                pickedName = e.getKey().equalsIgnoreCase(pickedName) ? null : e.getKey();
                return true;
            }
        }
        if (pickedName == null) {
            // Nothing picked up - let a real click on a real slot behave exactly like it always has (including
            // the player's own real class selection), the same as if this overlay weren't running at all.
            return false;
        }
        for (Map.Entry<DungeonClass, Slot> e : classSlots.entrySet()) {
            int x0 = screenX(screen, e.getValue());
            int y0 = screenY(screen, e.getValue());
            if (mouseX >= x0 - 1 && mouseX < x0 + 17 && mouseY >= y0 - 1 && mouseY < y0 + 17) {
                ClassOverrides.set(pickedName, e.getKey());
                pickedName = null;
                return true;
            }
        }
        return false;
    }
}
