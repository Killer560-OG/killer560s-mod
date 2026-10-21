package com.killer560.hub.slotbinds;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.itemprotect.ItemProtectConfig;
import com.killer560.hub.itemprotect.ItemProtectFeature;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.item.ItemStack;

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
 *   <li>Creating a bind: pressing the bind key over an unbound slot arms {@code pendingSlot}, which
 *   {@link #render} highlights every frame with a line running to the live cursor; pressing the bind key
 *   AGAIN over another slot finalizes the pair there (killer560, 2026-10-11: "press that keybind to start
 *   and stop it not the keybind then left click"), or cancels if that slot isn't a legal partner or is the
 *   same slot. Left clicks are never consumed by the bind flow.</li>
 * </ul>
 * <p>
 * <b>2026-10-18 hold and release</b> (killer560: "press the keybind while hovering a slot then you keep
 * holding it and release it on the slot you want it to go to"). The bind key press is now a gesture that
 * is decided when the key comes UP, from where the cursor is then:
 * <ul>
 *   <li><b>Dragged to another slot</b> (released over a different slot, cursor moved at least
 *   {@link #DRAG_MIN_PX}): the two slots are swapped with the same real SWAP click the shift-click bind uses,
 *   sent one tick later through {@link ActionGate.Actor#SLOT_BIND}. One end must be a hotbar slot, like a
 *   bind. Nothing is bound and Protect never toggles. Lock In Place items are refused.</li>
 *   <li><b>Released over the same slot</b> (or without a real drag): the single press exactly as before -
 *   deletes the bind if the slot is bound, otherwise arms it for a second press. Only the timing moved, from
 *   key-down to key-up.</li>
 *   <li><b>Shared with Protect Item</b> (both features on the same key, see {@link #ownsSharedKey}): a
 *   quick press - released within {@link #QUICK_PRESS_MS} with no drag - toggles protect on the item under
 *   the press. A press that dragged is a slot bind only. A slow hold with no drag does nothing.</li>
 * </ul>
 */
public final class SlotBindsFeature {

    /** Cursor travel, in GUI pixels, before a held bind key counts as a drag. Half of an 18-pixel slot pitch,
     *  so a hand tremor across a slot edge is not a move. */
    static final double DRAG_MIN_PX = 9.0;

    /** Shared with Protect Item: a press released sooner than this with no drag toggles protect. A constant, not a
     *  setting - the drag itself is what decides, so this only separates a tap from a slow hold. */
    static final long QUICK_PRESS_MS = 300L;

    /** Client ticks a finished drag may wait for {@link ActionGate} before it is dropped. */
    private static final int DRAG_MAX_WAIT_TICKS = 4;

    /** One held press of the bind key, from key-down to key-up. */
    private static final class Gesture {
        /** The slot under the cursor at key-down, or null when pressed over no slot. */
        final Slot startSlot;
        final double startX;
        final double startY;
        final long startMs;
        /** True when Protect Item uses this same key (decided at key-down). */
        final boolean sharedWithProtect;

        Gesture(Slot startSlot, double startX, double startY, long startMs, boolean sharedWithProtect) {
            this.startSlot = startSlot;
            this.startX = startX;
            this.startY = startY;
            this.startMs = startMs;
            this.sharedWithProtect = sharedWithProtect;
        }
    }

    /** A decided drag, waiting for {@link ActionGate} to let its one SWAP click through. */
    private static final class QueuedDrag {
        final AbstractContainerScreen<?> screen;
        /** Container slot index passed to the SWAP click (the end that is not the hotbar one, or the start). */
        final int slot;
        /** Hotbar index 0-8 passed as the SWAP button. */
        final int hotbarButton;
        int ticksLeft = DRAG_MAX_WAIT_TICKS;

        QueuedDrag(AbstractContainerScreen<?> screen, int slot, int hotbarButton) {
            this.screen = screen;
            this.slot = slot;
            this.hotbarButton = hotbarButton;
        }
    }

    /** Only one drag can be waiting: a new release replaces it. Cleared when its screen is no longer open. */
    private static QueuedDrag queuedDrag;

    private SlotBindsFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SlotBindsFeature::onScreenInit);
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SlotBindsFeature.drag",
                SlotBindsFeature::onStartTick));
    }

    /**
     * Whether the Slot Binds key and the Protect Item key are the same key and both features are live on this screen.
     * Then the key's gesture belongs to Slot Binds: Protect's own key press steps aside and {@link #resolve} calls
     * {@link ItemProtectFeature#toggleProtectFromSlot} on release when the press was quick. Off the inventory screen
     * Slot Binds is not active, so Protect keeps its press behaviour there.
     */
    public static boolean ownsSharedKey(Screen screen, int key) {
        if (key == KeyUtil.NONE || !(screen instanceof InventoryScreen)) {
            return false;
        }
        SlotBindsConfig cfg = SlotBindsConfig.getInstance();
        ItemProtectConfig protect = ItemProtectConfig.getInstance();
        return cfg.isEnabled() && cfg.getBindKey() == key && protect.isProtectItemEnabled()
                && protect.getProtectKey() == key;
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
        // The bind key press currently held down, if any (2026-10-18 hold and release).
        Gesture[] gesture = {null};
        // Last cursor position seen by render(), so the key handlers (which get no mouse position) know where
        // the cursor is at key-down and key-up.
        double[] mouse = {0, 0};

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
            // Item Protect's Lock In Place hooks the screen's own slot click, which this direct SWAP bypasses
            // (the drag path checks it too), so a locked item at either end must be refused here.
            Slot partner = findSlot((AbstractContainerScreen<?>) screen, bound);
            if (lockedInPlace(hovered.getItem()) || (partner != null && lockedInPlace(partner.getItem()))) {
                ModChat.send("Slot Binds", ModChat.bad("Lock In Place holds that item, so it stays where it is."));
                return false;
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
            if (gesture[0] != null) {
                // Key repeat while the press is already held: the gesture is still the same press.
                return false;
            }
            Slot hovered = accessor.killer560smod$getHoveredSlot();
            Integer hoveredIndex = hovered == null ? null : hovered.index;
            if (pendingSlot[0] != -1) {
                // killer560, 2026-10-11: "press that keybind to start and stop it not the keybind then left
                // click" - the SECOND press of the bind key places the other end on the hovered slot. Decided
                // on key-down, as before; a pending bind has no drag.
                finishBind(cfg, pendingSlot, hoveredIndex);
                return false;
            }
            boolean shared = ownsSharedKey(screen, event.key());
            if (!shared && (hovered == null || hovered.index < 5 || hovered.index >= 45)) {
                return true;
            }
            // Nothing happens yet: the press is decided on key-up (see resolve), from where the cursor is then.
            gesture[0] = new Gesture(hovered, mouse[0], mouse[1], System.currentTimeMillis(), shared);
            return false;
        });

        ScreenKeyboardEvents.allowKeyRelease(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            if (gesture[0] == null || cfg.getBindKey() == -1 || event.key() != cfg.getBindKey()) {
                return true;
            }
            Gesture held = gesture[0];
            gesture[0] = null;
            Slot released = accessor.killer560smod$getHoveredSlot();
            resolve((AbstractContainerScreen<?>) screen, cfg, pendingSlot, held, released, mouse[0], mouse[1]);
            return false;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            mouse[0] = mouseX;
            mouse[1] = mouseY;
            render((AbstractContainerScreen<?>) screen, graphics, mouseX, mouseY, pendingSlot[0], gesture[0]);
        });
    }

    /**
     * Decides a bind key press on key-up. A drag to another slot is a swap, never a protect toggle. Otherwise a
     * shared key's quick press toggles protect, and an unshared key does the single press it always did.
     */
    private static void resolve(AbstractContainerScreen<?> screen, SlotBindsConfig cfg, int[] pendingSlot,
                                Gesture held, Slot released, double x, double y) {
        long heldMs = System.currentTimeMillis() - held.startMs;
        double travel = Math.hypot(x - held.startX, y - held.startY);
        boolean dragged = released != null && held.startSlot != null
                && released.index != held.startSlot.index && travel >= DRAG_MIN_PX;
        if (dragged) {
            queueDrag(screen, held.startSlot, released);
            return;
        }
        if (held.sharedWithProtect) {
            if (heldMs < QUICK_PRESS_MS) {
                ItemProtectFeature.toggleProtectFromSlot(held.startSlot);
            }
            return;
        }
        if (held.startSlot != null) {
            pressBind(cfg, pendingSlot, held.startSlot.index);
        }
    }

    /** The single press of the bind key over {@code index}, as it has always worked: deletes the bind if the slot is
     *  bound, otherwise arms it so the next press places the other end. */
    private static void pressBind(SlotBindsConfig cfg, int[] pendingSlot, int index) {
        if (cfg.getBinds().containsKey(index)) {
            // (c) killer560, 2026-09-27: "If i press the bind key while hoving a already created bind it should
            // delete it" - hovering a slot that's already bound deletes that bind pair instead of starting a new one.
            cfg.removeBind(index);
            cfg.save();
            return;
        }
        // Arm the bind: this slot gets highlighted with a line to the cursor in render() until the next press of
        // the bind key places the other end (killer560, 2026-10-11 - no left click).
        pendingSlot[0] = index;
    }

    /**
     * Checks a hold-and-release drag and queues its one SWAP click. The swap is the same vanilla SWAP the shift-click
     * bind sends: the start item goes to the released slot, and whatever was there comes back to the start. One end
     * must be a hotbar slot (36-44), and both must be bindable inventory slots (5-44).
     */
    private static void queueDrag(AbstractContainerScreen<?> screen, Slot from, Slot to) {
        int a = from.index;
        int b = to.index;
        if (a < 5 || a >= 45 || b < 5 || b >= 45) {
            return; // crafting grid, output or offhand: not a slot a bind can use
        }
        boolean aHotbar = a >= 36 && a < 45;
        boolean bHotbar = b >= 36 && b < 45;
        if (!aHotbar && !bHotbar) {
            ModChat.send("Slot Binds", ModChat.bad("A drag needs one end on the hotbar."));
            return;
        }
        if (lockedInPlace(from.getItem()) || lockedInPlace(to.getItem())) {
            ModChat.send("Slot Binds", ModChat.bad("Lock In Place holds that item, so it stays where it is."));
            return;
        }
        int hotbarEnd = bHotbar ? b : a;
        int slot = bHotbar ? a : b;
        queuedDrag = new QueuedDrag(screen, slot, hotbarEnd - 36);
    }

    private static boolean lockedInPlace(ItemStack stack) {
        ItemProtectConfig protect = ItemProtectConfig.getInstance();
        return protect.isLockInPlace() && ItemProtect.isExplicitlyProtected(stack);
    }

    /** Sends a queued drag's SWAP click once {@link ActionGate} grants this tick. Dropped if the inventory closed. */
    private static void onStartTick(Minecraft client) {
        QueuedDrag drag = queuedDrag;
        if (drag == null) {
            return;
        }
        if (McCompat.screen(client) != drag.screen || client.gameMode == null || client.player == null
                || drag.ticksLeft-- <= 0) {
            queuedDrag = null;
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.SLOT_BIND, drag.screen)) {
            return; // another automated click owns this tick; try again next tick
        }
        queuedDrag = null;
        client.gameMode.handleContainerInput(drag.screen.getMenu().containerId, drag.slot, drag.hotbarButton,
                ContainerInput.SWAP, client.player);
    }

    /** Second press of the bind key while a bind is pending: binds the pending slot to {@code target} when
     *  the pair is legal (one end in the hotbar, a real inventory slot 5..44, not the same slot), otherwise
     *  just cancels. Either way nothing is left pending. Public for the testkit (case 703). */
    public static boolean finishBind(SlotBindsConfig cfg, int[] pendingSlot, Integer target) {
        int first = pendingSlot[0];
        pendingSlot[0] = -1;
        if (first == -1 || target == null || target < 5 || target >= 45 || target == first) {
            return false;
        }
        boolean firstIsHotbar = first >= 36 && first < 45;
        boolean secondIsHotbar = target >= 36 && target < 45;
        if (!firstIsHotbar && !secondIsHotbar) {
            return false; // neither slot is in the hotbar - not a legal vanilla SWAP pair (see class doc)
        }
        cfg.addBind(first, target);
        cfg.save();
        return true;
    }

    /** Draws a border around each bound slot plus a line to its partner, and (while a bind is being
     *  placed) a border around the pending slot plus a line following the live cursor - see this
     *  class's doc for why this replaces a HUD popup. While a bind key is held and the cursor has moved
     *  past {@link #DRAG_MIN_PX}, the held slot is highlighted with a line to the cursor, so a drag reads as a
     *  drag. Runs every frame the inventory is open; cheap (at most a handful of bind pairs plus one pending
     *  slot) so no caching is needed. */
    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
                               double mouseX, double mouseY, int pendingSlot, Gesture held) {
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

        if (held != null && held.startSlot != null
                && Math.hypot(mouseX - held.startX, mouseY - held.startY) >= DRAG_MIN_PX) {
            int sx = leftPos + held.startSlot.x;
            int sy = topPos + held.startSlot.y;
            graphics.outline(sx - 1, sy - 1, 18, 18, color);
            drawLink(graphics, sx + 8, sy + 8, (int) mouseX, (int) mouseY, color);
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
