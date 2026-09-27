package com.killer560.hub.slotbinds;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Map;

/**
 * Real inventory "Slot Binds" feature, ported from Odin's own {@code SlotBinds.kt} (simplified to a
 * single global bind list rather than Odin's 6 profiles). Links two slots in your real vanilla
 * Inventory screen (E menu) together; shift-clicking either one swaps it with its bound partner using
 * the exact same real vanilla mechanic pressing a number key over a slot already uses
 * ({@code MultiPlayerGameMode#handleContainerInput} with the real {@code ContainerInput.SWAP} click
 * type - this mod isn't inventing a new kind of click, just triggering the real vanilla one
 * programmatically). One of the two bound slots must be a real hotbar slot (36-44), matching that same
 * real vanilla SWAP click's own requirement that its "button" parameter is a hotbar index.
 * <p>
 * Needs one real accessor mixin ({@link AbstractContainerScreenAccessor}) since vanilla's own
 * {@code AbstractContainerScreen#hoveredSlot} field is {@code protected} with no public getter - a
 * read-only accessor that injects no behavior, the lowest-risk real category of mixin. Everything else
 * is built on the same real Fabric Screen API this session already verified via {@code javap} for the
 * Custom Leap Menu overlay.
 * <p>
 * <b>In-screen display</b> (killer560, 2026-09-21: "do not have the hud popup, instead show a border
 * around each spot and a line for where it goes. Make a toggle to only show this on hover or always.") -
 * every bound pair gets a border around both of its slots plus a connecting line, drawn from
 * {@link #render}, gated by {@link SlotBindsConfig#getOverlayMode()} (always visible, or only while the
 * mouse is over one of the pair). No HUD popup/text notification is used for this. Reuses
 * {@link ContainerScreenPositionAccessor} (Inventory Search's {@code leftPos}/{@code topPos} accessor)
 * and hooks {@code ScreenEvents.afterExtract} the same way {@code ItemProtectFeature} draws its own
 * slot markers, so the border/line lands on top of the item instead of under it.
 * <p>
 * <b>2026-09-27 rework</b> (killer560: "remove the hud popup thing... If i press the bind key while
 * hoving a already created bind it should delete it. Also when I press the bind button to start a
 * bind, it should highlight that square and draw a line to my cursor as it moves around then once i
 * click on another spot it should then put the box there with the line."):
 * <ul>
 *   <li>Every {@code ModOverlayMessage.show(...)} call this feature used to make (the "Selected slot",
 *   "bound slot X to Y", error text) is gone - creating/deleting a bind is communicated purely by the
 *   border/line drawn in-screen, never a HUD popup.</li>
 *   <li>Pressing the bind key while hovering a slot that is already part of a bind now deletes that
 *   bind pair instead of starting a new one - see the key-press handler below.</li>
 *   <li>Creating a bind is now a press-then-click flow instead of press-then-press: pressing the bind
 *   key over an unbound slot arms {@code pendingSlot}, which {@link #render} highlights every frame with
 *   a line running from that slot to the live cursor position; the NEXT plain left click (anywhere, not
 *   just while holding the bind key) finalizes the pair on whatever slot was clicked, or silently cancels
 *   if that slot isn't a legal partner (same hotbar-slot requirement as before). Pressing the bind key
 *   again while a bind is pending backs out instead of leaving it armed.</li>
 * </ul>
 */
public final class SlotBindsFeature {

    private SlotBindsFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SlotBindsFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!SlotBindsConfig.getInstance().isEnabled() || !(screen instanceof InventoryScreen)) {
            return;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        // Tracks the slot picked while setting up a new bind (-1 = not currently placing one) - a fresh
        // holder per real screen open, matching a fresh Inventory screen instance every time. Also read
        // by render() every frame to draw the in-progress highlight/line to the cursor.
        int[] pendingSlot = {-1};

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            Slot hovered = accessor.killer560smod$getHoveredSlot();

            if (pendingSlot[0] != -1) {
                // Second click of the two-click bind flow (d): the first slot is already highlighted with
                // a line running to the cursor via render(); this click either drops the box on the
                // hovered slot and finalizes the pair, or silently cancels if the target isn't legal (no
                // HUD popup for either outcome, per this rework's class doc).
                int first = pendingSlot[0];
                pendingSlot[0] = -1;
                if (hovered == null || hovered.index < 5 || hovered.index >= 45 || hovered.index == first) {
                    return false;
                }
                boolean firstIsHotbar = first >= 36 && first < 45;
                boolean secondIsHotbar = hovered.index >= 36 && hovered.index < 45;
                if (!firstIsHotbar && !secondIsHotbar) {
                    // Neither slot is in the hotbar - not a legal real vanilla SWAP pair (see class doc).
                    return false;
                }
                cfg.addBind(first, hovered.index);
                cfg.save();
                return false;
            }

            if (hovered == null || !event.hasShiftDown() || hovered.index < 5 || hovered.index >= 45) {
                return true;
            }
            Integer bound = cfg.getBinds().get(hovered.index);
            if (bound == null) {
                return true;
            }
            int from;
            int to;
            if (hovered.index >= 36 && hovered.index < 45) {
                from = bound;
                to = hovered.index;
            } else if (bound >= 36 && bound < 45) {
                from = hovered.index;
                to = bound;
            } else {
                return true;
            }
            client.gameMode.handleContainerInput(
                    ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen)
                            .getMenu().containerId,
                    from, to % 36, ContainerInput.SWAP, client.player);
            return false;
        });

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            if (cfg.getBindKey() == -1 || event.key() != cfg.getBindKey()) {
                return true;
            }
            Slot hovered = accessor.killer560smod$getHoveredSlot();
            if (hovered == null || hovered.index < 5 || hovered.index >= 45) {
                return true;
            }
            int index = hovered.index;
            if (pendingSlot[0] != -1) {
                // Already placing a bind - pressing the key again backs out rather than leaving a stray
                // half-made bind armed with no way to cancel it.
                pendingSlot[0] = -1;
                return false;
            }
            if (cfg.getBinds().containsKey(index)) {
                // (c) killer560, 2026-09-27: "If i press the bind key while hoving a already created
                // bind it should delete it" - hovering a slot that's already bound deletes that bind pair
                // instead of starting a new one.
                cfg.removeBind(index);
                cfg.save();
                return false;
            }
            // (d) Arm the two-click flow: this slot gets highlighted with a line to the cursor in
            // render() until the next plain left click places the other end.
            pendingSlot[0] = index;
            return false;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render((AbstractContainerScreen<?>) screen, graphics, mouseX, mouseY, pendingSlot[0]));
    }

    /** Draws a border around each bound slot plus a line to its partner, and (while a bind is being
     *  placed) a border around the pending slot plus a line following the live cursor - see this
     *  class's doc for why this replaces a HUD popup. Runs every frame the inventory is open; cheap (at
     *  most a handful of bind pairs plus one pending slot) so no caching is needed. */
    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
                                double mouseX, double mouseY, int pendingSlot) {
        SlotBindsConfig cfg = SlotBindsConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        ContainerScreenPositionAccessor pos = (ContainerScreenPositionAccessor) screen;
        int leftPos = pos.killer560smod$getLeftPos();
        int topPos = pos.killer560smod$getTopPos();
        int color = cfg.getOverlayColor();

        if (pendingSlot != -1) {
            Slot slot = findSlot(screen, pendingSlot);
            if (slot != null) {
                int sx = leftPos + slot.x;
                int sy = topPos + slot.y;
                graphics.outline(sx - 1, sy - 1, 18, 18, color);
                drawLink(graphics, sx + 8, sy + 8, (int) mouseX, (int) mouseY, color);
            }
        }

        if (cfg.getBinds().isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : cfg.getBinds().entrySet()) {
            // The map stores both directions of every pair (a->b and b->a) so shift-click lookup works
            // from either end - only draw once per pair.
            if (entry.getKey() >= entry.getValue()) {
                continue;
            }
            Slot slotA = findSlot(screen, entry.getKey());
            Slot slotB = findSlot(screen, entry.getValue());
            if (slotA == null || slotB == null) {
                continue;
            }
            int ax = leftPos + slotA.x;
            int ay = topPos + slotA.y;
            int bx = leftPos + slotB.x;
            int by = topPos + slotB.y;

            if (cfg.getOverlayMode() == SlotBindsConfig.OverlayMode.ON_HOVER
                    && !isHoveringSlot(mouseX, mouseY, ax, ay) && !isHoveringSlot(mouseX, mouseY, bx, by)) {
                continue;
            }

            graphics.outline(ax - 1, ay - 1, 18, 18, color);
            graphics.outline(bx - 1, by - 1, 18, 18, color);
            drawLink(graphics, ax + 8, ay + 8, bx + 8, by + 8, color);
        }
    }

    private static Slot findSlot(AbstractContainerScreen<?> screen, int index) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index == index) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isHoveringSlot(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17;
    }

    /** A straight 2px line between two points, code-only (no texture asset) - same "rotate the pose,
     *  fill a local rectangle" technique already used for the live-map player arrow
     *  ({@code livemap.MapPainter#drawArrow}), just translated once instead of every render row. Used
     *  both for a confirmed pair's slot-to-slot link and (while a bind is pending) the slot-to-cursor
     *  line, since both are just "a line between two points" to this method. */
    private static void drawLink(GuiGraphicsExtractor graphics, int ax, int ay, int bx, int by, int color) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }
        float angle = (float) Math.atan2(dy, dx);
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) ax, (float) ay);
        graphics.pose().rotate(angle);
        graphics.fill(0, -1, Math.round(length), 1, color);
        graphics.pose().popMatrix();
    }
}
