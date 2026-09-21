package com.killer560.hub.slotbinds;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.notify.ModOverlayMessage;
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
        // Tracks the first slot picked while setting up a new bind (-1 = not currently setting one up) -
        // a fresh holder per real screen open, matching a fresh Inventory screen instance every time.
        int[] pendingSlot = {-1};

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            Slot hovered = accessor.killer560smod$getHoveredSlot();
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
            if (pendingSlot[0] == -1) {
                pendingSlot[0] = index;
                ModOverlayMessage.show("[Slot Binds] Selected slot " + index + " - hover the slot to bind it to, then press the key again.", 3500);
            } else if (pendingSlot[0] == index) {
                ModOverlayMessage.show("§cYou can't bind a slot to itself.", 2500);
                pendingSlot[0] = -1;
            } else if ((pendingSlot[0] < 36 || pendingSlot[0] >= 45) && (index < 36 || index >= 45)) {
                ModOverlayMessage.show("§cOne of the two slots must be in the hotbar.", 3000);
                pendingSlot[0] = -1;
            } else {
                cfg.addBind(pendingSlot[0], index);
                cfg.save();
                ModOverlayMessage.show("§a[Slot Binds] Bound slot " + pendingSlot[0] + " to " + index + ".", 3000);
                pendingSlot[0] = -1;
            }
            return false;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render((AbstractContainerScreen<?>) screen, graphics, mouseX, mouseY));
    }

    /** Draws a border around each bound slot plus a line to its partner - see this class's doc for why
     *  this replaces a HUD popup. Runs every frame the inventory is open; cheap (at most a handful of
     *  bind pairs) so no caching is needed. */
    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
                                double mouseX, double mouseY) {
        SlotBindsConfig cfg = SlotBindsConfig.getInstance();
        if (!cfg.isEnabled() || cfg.getBinds().isEmpty()) {
            return;
        }
        ContainerScreenPositionAccessor pos = (ContainerScreenPositionAccessor) screen;
        int leftPos = pos.killer560smod$getLeftPos();
        int topPos = pos.killer560smod$getTopPos();
        int color = cfg.getOverlayColor();

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

    /** A straight 2px line between two slot centres, code-only (no texture asset) - same "rotate the pose,
     *  fill a local rectangle" technique already used for the live-map player arrow
     *  ({@code livemap.MapPainter#drawArrow}), just translated once instead of every render row. */
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
