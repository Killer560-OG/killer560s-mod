package com.killer560.hub.spiritleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom Spirit Leap menu. Replaces Hypixel's "Spirit Leap" chest GUI with 4 big boxes (one per teammate, name
 * only) - the real chest and your inventory are hidden while it is open, same as the terminal Custom GUI
 * (see {@code spiritleap/mixin/SpiritLeapHideMixin}). Clicking a quarter of the screen (or pressing 1-4) sends
 * the same container click a click on that teammate's head would.
 * <p>
 * Spot order comes from the Leap Order editor ({@link com.killer560.hub.leapmenu.LeapOrderScreen}) for the class
 * you are playing; anyone not placed there fills the remaining spots in Hypixel's own order. Targets are
 * re-read from the live slots every frame because Hypixel's items arrive after the screen opens (NoammAddons
 * 26.1.2 LeapMenu does the same) - but only once those slots have settled, see {@code MenuState}.
 * <p>
 * 2026-10-11 batch (killer560): a face in each box (Show Player Heads); the Map in Clear style (the dungeon map with a
 * clickable face per teammate during the clear, the boxes in boss); targets taken on the first frame when the whole menu
 * has arrived (the old settle window was the "really long delay" he felt); the layout frozen while a leap click is in
 * flight; and no carried head drawn on the cursor ({@code SpiritLeapHideMixin}).
 */
public final class SpiritLeapOverlayFeature {

    private static final Pattern IGN = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");

    private static final int BOX_W = 180;
    private static final int BOX_H = 50;
    private static final int GAP = 6;

    private record LeapTarget(String name, int slotIndex) {
    }

    /**
     * Per-screen "the menu has settled" state. Hypixel sends the screen first and the head stacks a few ticks later
     * (and can re-send them), so the first frames of a leap menu read empty or half-filled slots - which would draw
     * the wrong names in the wrong spots and, worse, leap to whoever happened to be in the quarter you clicked.
     * Same content-stability gate {@code terminals/TerminalSolverFeature} uses instead of a blind delay: targets are
     * only accepted once two consecutive frames read the same slots with at least one head in them, and nothing is
     * drawn or clicked before that.
     */
    private static final class MenuState {
        private List<ItemStack> snapshot = List.of();
        private long stableSinceMs;
        private boolean pendingRead;
        private boolean settled;
        private List<LeapTarget> targets = List.of();
        /** The full-contents packet this screen last took its targets from (see {@link #onFullContents}). */
        private long seenFullSeq;
        /** Until when a leap click holds the layout still (see {@link #FREEZE_MS}). */
        private long frozenUntilMs;
        /** Map style: the faces drawn this frame and the fallback boxes for targets the map has no position for. */
        private List<com.killer560.hub.livemap.LeapMapView.Head> heads = List.of();
    }

    /**
     * How long a leap click holds the layout still (killer560, 2026-10-11: "when I click on a name it shifts the names
     * around for a split second on my screen because it thinks the player is gone since I am holding their head").
     * The click picks the head up client-side until Hypixel answers, so for a moment that slot is empty and a re-read
     * would drop the teammate and slide everyone after them along. Hypixel closes the menu on a leap well inside this;
     * if it does not (a refused leap), reading resumes afterwards.
     */
    private static final long FREEZE_MS = 1500L;

    /** Bumped by every full container-contents packet ({@code SpiritLeapContentMixin}), with its container id. */
    private static volatile long fullContentSeq;
    private static volatile int fullContentContainerId = -1;

    /** When the current leap screen opened, and how long until its targets were ready / first drawn (ms; testkit). */
    private static volatile long openedAtNanos;
    private static volatile double lastOpenToReadyMs = -1;
    private static volatile double lastOpenToDrawMs = -1;
    /** What the last frame drew: "BOXES" or "MAP" (testkit). */
    private static volatile String lastView = "";
    /** The faces the map style drew last frame (testkit). */
    private static volatile List<com.killer560.hub.livemap.LeapMapView.Head> lastHeads = List.of();

    /** A full contents packet for {@code containerId} has just been applied (render thread). */
    public static void onFullContents(int containerId) {
        fullContentContainerId = containerId;
        lastContainerPacketFull = true;
        fullContentSeq++;
    }

    /** A single-slot packet has just been applied (render thread): the slots are no longer one packet's. */
    public static void onSetSlot(int containerId) {
        if (containerId == fullContentContainerId) {
            lastContainerPacketFull = false;
        }
    }

    private static volatile boolean lastContainerPacketFull;

    public static double lastOpenToReadyMs() {
        return lastOpenToReadyMs;
    }

    public static double lastOpenToDrawMs() {
        return lastOpenToDrawMs;
    }

    /** The names the last box frame drew in spots 0-3 ("" = empty) (testkit). */
    private static volatile List<String> lastDrawnSpots = List.of();

    public static List<String> lastDrawnSpots() {
        return lastDrawnSpots;
    }

    public static String lastView() {
        return lastView;
    }

    public static List<com.killer560.hub.livemap.LeapMapView.Head> lastHeads() {
        return lastHeads;
    }

    /** True when the leap menu draws the dungeon map: the Map in Clear style, in a dungeon, before the boss. */
    public static boolean mapStyleNow() {
        return SpiritLeapOverlayConfig.getInstance().getStyle() == SpiritLeapOverlayConfig.LeapStyle.MAP_IN_CLEAR
                && com.killer560.hub.secrets.DungeonState.isInDungeon()
                && !com.killer560.hub.secrets.DungeonState.isBossPhaseActive();
    }

    /**
     * How long the slots have to stay unchanged before they count as settled. Frame-to-frame stability alone is not
     * enough: at any normal frame rate two consecutive frames are ~5-16ms apart, well inside one 50ms tick, so a menu
     * arriving slot-by-slot reads "stable" between two packets and a click could still leap to the wrong player -
     * exactly what this gate exists to stop. Two ticks' worth covers that without a blind fixed delay, since the
     * window only starts once the contents have stopped changing.
     */
    private static final long SETTLE_MS = 110L;

    /** The leap screen currently being replaced, or null - read by the hide mixin. */
    private static volatile Screen activeScreen = null;

    private SpiritLeapOverlayFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SpiritLeapOverlayFeature::onScreenInit);
    }

    /** True while {@code screen} is a leap menu this feature is drawing over (hide its background, slots, labels). */
    public static boolean isHiding(Object screen) {
        return screen != null && screen == activeScreen && SpiritLeapOverlayConfig.getInstance().isEnabled();
    }

    /** Exact titles only (QUOI uses equalsOneOf("Spirit Leap", "Teleport to Player")) - a substring match would also
     *  hijack and hide e.g. a Bazaar/AH page for the Spirit Leap item. */
    private static boolean isLeapTitle(String title) {
        String plain = ChatFormatting.stripFormatting(title);
        String t = plain == null ? "" : plain.trim();
        return t.equalsIgnoreCase("Spirit Leap") || t.equalsIgnoreCase("Teleport to Player");
    }

    /** One leap per press: a double-click or a held 1-4 key (key repeat reaches keyPressed) would otherwise send a
     *  second container click before Hypixel closes the menu. */
    private static final long LEAP_DEBOUNCE_MS = 750L;

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!SpiritLeapOverlayConfig.getInstance().isEnabled()
                || !(screen instanceof AbstractContainerScreen<?> containerScreen)
                || !isLeapTitle(containerScreen.getTitle().getString())) {
            return;
        }
        activeScreen = screen;
        openedAtNanos = System.nanoTime();
        lastOpenToReadyMs = -1;
        lastOpenToDrawMs = -1;
        ScreenEvents.remove(screen).register(s -> {
            if (activeScreen == s) {
                activeScreen = null;
            }
        });

        MenuState state = new MenuState();
        AtomicLong lastLeapAtMs = new AtomicLong(0L);

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
            poll(containerScreen, state);
            if (isHiding(s)) {
                // nothing is drawn until the slots settle - see poll(): at once when the whole menu is in
                if (state.settled && lastOpenToDrawMs < 0) {
                    lastOpenToDrawMs = (System.nanoTime() - openedAtNanos) / 1e6;
                }
                if (mapStyleNow()) {
                    lastView = "MAP";
                    state.heads = renderMap(graphics, s.width, s.height, state.targets, mouseX, mouseY);
                    lastHeads = state.heads;
                } else {
                    lastView = "BOXES";
                    state.heads = List.of();
                    LeapTarget[] spots = layout(state.targets);
                    String[] names = new String[4];
                    for (int i = 0; i < 4; i++) {
                        names[i] = spots[i] == null ? "" : spots[i].name();
                    }
                    lastDrawnSpots = List.of(names);
                    render(graphics, s.width, s.height, spots, mouseX, mouseY);
                }
            }
        });

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!isHiding(s)) {
                return true;
            }
            poll(containerScreen, state);
            if (mapStyleNow()) {
                // Map style: only a click on a teammate's face (or a fallback box) leaps; the rooms are context.
                List<com.killer560.hub.livemap.LeapMapView.Head> heads = state.heads;
                for (int i = heads.size() - 1; i >= 0; i--) {
                    if (state.settled && heads.get(i).contains(event.x(), event.y())) {
                        LeapTarget t = byName(state.targets, heads.get(i).name());
                        if (t != null) {
                            leap(client, containerScreen, t, state, "map head " + t.name(), lastLeapAtMs);
                        }
                        break;
                    }
                }
                return false;
            }
            LeapTarget[] spots = layout(state.targets);
            int spot = spotFor(event.x(), event.y(), s.width, s.height);
            if (state.settled && spot >= 0 && spots[spot] != null) {
                leap(client, containerScreen, spots[spot], state, "click spot " + spot, lastLeapAtMs);
            }
            return false; // never let a click reach the hidden chest/inventory
        });
        // Releases/drags are harmless without a press, but block them anyway so nothing reaches the hidden slots.
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !isHiding(s));

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!isHiding(s)) {
                return true;
            }
            int key = event.key();
            if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_4) {
                poll(containerScreen, state);
                LeapTarget[] spots = layout(state.targets);
                LeapTarget t = spots[key - InputConstants.KEY_1];
                if (state.settled && t != null) {
                    leap(client, containerScreen, t, state, "key " + (key - InputConstants.KEY_1 + 1), lastLeapAtMs);
                }
                return false;
            }
            // Escape and the inventory key still close the menu; everything else (hotbar swaps, drop) is swallowed
            // because it would act on whatever hidden slot the mouse happens to be over.
            return key == InputConstants.KEY_ESCAPE || client.options.keyInventory.matches(event);
        });
    }

    private static LeapTarget byName(List<LeapTarget> targets, String name) {
        for (LeapTarget t : targets) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    private static void leap(Minecraft client, AbstractContainerScreen<?> screen, LeapTarget target, MenuState state,
                             String how, AtomicLong lastLeapAtMs) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLeapAtMs.get() < LEAP_DEBOUNCE_MS) {
            return;
        }
        lastLeapAtMs.set(now);
        state.frozenUntilMs = now + FREEZE_MS; // the head goes onto the cursor: keep everyone where they are
        client.gameMode.handleContainerInput(screen.getMenu().containerId, target.slotIndex(), 0,
                ContainerInput.PICKUP, client.player);
    }

    /**
     * Re-reads the menu's own slots and keeps {@code state.targets} in step with them, but only once the slots have
     * settled (see {@link MenuState}). After that the targets are refreshed whenever they really change - Hypixel
     * re-sends the contents of an open leap menu (a teammate dying/leaving) - but a read that has gone empty is
     * ignored: that is the menu being torn down, not a new set of targets.
     */
    private static void poll(AbstractContainerScreen<?> containerScreen, MenuState state) {
        long now = System.currentTimeMillis();
        if (now < state.frozenUntilMs) {
            return; // a leap click is in flight: the layout holds still (FREEZE_MS)
        }
        List<ItemStack> items = containerItems(containerScreen);
        boolean anyHead = items.stream().anyMatch(item -> !item.isEmpty() && item.is(Items.PLAYER_HEAD));
        // Fast path (2026-10-11, killer560: "it feels like there is a really long delay between when i use my leap
        // then when the menu actually opens"). The settle window below is the delay he felt: SETTLE_MS of nothing
        // drawn after the heads arrive. It exists so a menu still arriving slot by slot is never clicked half-filled,
        // and in two cases there is nothing still arriving: a full contents packet replaces every slot at once, and a
        // menu already holding a head for every teammate the party tracker knows is complete. Either is taken on the
        // first frame; anything else still goes through the window.
        long seq = fullContentSeq;
        boolean fullPacket = false;
        if (seq != state.seenFullSeq && fullContentContainerId == containerScreen.getMenu().containerId) {
            state.seenFullSeq = seq;
            // Only while no single-slot packet has landed since: then the slots are exactly that packet's.
            fullPacket = lastContainerPacketFull;
        }
        int known = PartyTracker.teammates().size();
        boolean complete = !state.settled && known > 0 && headCount(items) >= known;
        if (anyHead && (fullPacket || complete)) {
            List<LeapTarget> fresh = readTargets(containerScreen);
            if (!fresh.isEmpty()) {
                state.snapshot = List.copyOf(items);
                state.pendingRead = false;
                accept(state, fresh);
                return;
            }
        }
        if (!anyHead || !ItemStack.listMatches(items, state.snapshot)) {
            // Still arriving (or the menu is being torn down): remember this frame's contents and restart the window.
            // Whatever was already settled stays in use - a read that has gone empty is a teardown, not new targets.
            state.snapshot = anyHead ? List.copyOf(items) : List.of();
            state.stableSinceMs = now;
            state.pendingRead = anyHead;
            return;
        }
        if (!state.pendingRead || now - state.stableSinceMs < SETTLE_MS) {
            return;
        }
        state.pendingRead = false;
        List<LeapTarget> fresh = readTargets(containerScreen);
        if (fresh.isEmpty()) {
            return;
        }
        accept(state, fresh);
    }

    private static int headCount(List<ItemStack> items) {
        int n = 0;
        for (ItemStack item : items) {
            if (!item.isEmpty() && item.is(Items.PLAYER_HEAD)) {
                n++;
            }
        }
        return n;
    }

    private static void accept(MenuState state, List<LeapTarget> fresh) {
        boolean first = !state.settled;
        state.settled = true;
        if (first) {
            lastOpenToReadyMs = (System.nanoTime() - openedAtNanos) / 1e6;
        }
        if (first || !fresh.equals(state.targets)) {
            // Hypixel re-sends the contents of an open leap menu (a teammate dying/leaving); a change goes through
            // the same window before it is trusted.
            state.targets = fresh;
            PartyTracker.noteTeammates(fresh.stream().map(LeapTarget::name).toList());
        }
    }

    /**
     * The Map in Clear style: the dungeon map in a square filling most of the screen, a face per leapable teammate at
     * their position; any target the map has no position for gets a small name box in a row under it (still a target).
     */
    private static List<com.killer560.hub.livemap.LeapMapView.Head> renderMap(GuiGraphicsExtractor graphics, int width,
            int height, List<LeapTarget> targets, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        int size = Math.max(60, Math.min(width - 20, height - 50));
        int x0 = (width - size) / 2;
        int y0 = Math.max(6, (height - size - 24) / 2);
        int[] panel = {x0, y0, x0 + size, y0 + size};
        List<String> names = targets.stream().map(LeapTarget::name).toList();
        List<com.killer560.hub.livemap.LeapMapView.Head> heads = new ArrayList<>(
                com.killer560.hub.livemap.LeapMapView.draw(graphics, font, panel, names, mouseX, mouseY));
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            if (heads.stream().noneMatch(h -> h.name().equalsIgnoreCase(n))) {
                missing.add(n);
            }
        }
        int bw = 80;
        int bh = 16;
        int rowX = width / 2 - (missing.size() * (bw + 4) - 4) / 2;
        int rowY = panel[3] + 6;
        for (String n : missing) {
            var box = new com.killer560.hub.livemap.LeapMapView.Head(n, rowX, rowY, rowX + bw, rowY + bh);
            boolean hover = box.contains(mouseX, mouseY);
            DungeonClass cls = LeapMenuConfig.getInstance().getAssignedClass(n);
            com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.LEAP_MENU);
            int color = cls != null ? cls.color() : (menu == null ? 0xFFCC6600 : menu.accent());
            graphics.fill(box.x0(), box.y0(), box.x1(), box.y1(), menu == null ? (hover ? 0xE0262626 : 0xCC0D0D0D)
                    : (hover ? com.killer560.hub.gui.OverlayPalette.alpha(menu.cardHover(), 0xE0) : com.killer560.hub.gui.OverlayPalette.alpha(menu.panel(), 0xCC)));
            graphics.outline(box.x0(), box.y0(), bw, bh, hover ? 0xFFFFFFFF : color);
            graphics.centeredText(font, n, rowX + bw / 2, rowY + 4, color);
            heads.add(box);
            rowX += bw + 4;
        }
        return heads;
    }

    /** The container's own slot contents (never the 36 appended player-inventory slots). */
    private static List<ItemStack> containerItems(AbstractContainerScreen<?> containerScreen) {
        List<Slot> slots = containerScreen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<ItemStack> items = new ArrayList<>(containerSlotCount);
        for (int i = 0; i < containerSlotCount; i++) {
            items.add(slots.get(i).getItem());
        }
        return items;
    }

    /** Player heads from the container's own slots (never the 36 appended player-inventory slots). */
    private static List<LeapTarget> readTargets(AbstractContainerScreen<?> containerScreen) {
        List<Slot> slots = containerScreen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<LeapTarget> targets = new ArrayList<>();
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack item = slots.get(i).getItem();
            if (item.isEmpty() || !item.is(Items.PLAYER_HEAD)) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(item.getHoverName().getString());
            if (plain == null) {
                continue;
            }
            Matcher m = IGN.matcher(plain.trim());
            targets.add(new LeapTarget(m.find() ? m.group(1) : plain.trim(), slots.get(i).index));
        }
        return targets;
    }

    /** Targets placed into the 4 spots by the Leap Order for the class you are playing. */
    private static LeapTarget[] layout(List<LeapTarget> targets) {
        List<String> names = targets.stream().map(LeapTarget::name).toList();
        String[] arranged = LeapMenuFeature.layout(names, LeapMenuFeature.playingClass());
        LeapTarget[] out = new LeapTarget[4];
        for (int spot = 0; spot < 4; spot++) {
            if (arranged[spot] == null) {
                continue;
            }
            for (LeapTarget t : targets) {
                if (t.name().equals(arranged[spot])) {
                    out[spot] = t;
                    break;
                }
            }
        }
        return out;
    }

    /** The whole screen counts as 4 quarters for clicking (Odin's behaviour) - the boxes are a visual guide. */
    private static int spotFor(double mouseX, double mouseY, int width, int height) {
        int row = mouseY >= height / 2.0 ? 1 : 0;
        int col = mouseX >= width / 2.0 ? 1 : 0;
        return row * 2 + col;
    }

    private static void render(GuiGraphicsExtractor graphics, int width, int height, LeapTarget[] spots, int mouseX, int mouseY) {
        float scale = SpiritLeapOverlayConfig.getInstance().getScale();
        // Up to 400% (SpiritLeapOverlayConfig.MAX_SCALE): whatever the setting, the 2x2 grid (boxes + gaps) is shrunk to
        // fit inside the window with a 10px margin, so it never runs off-screen on small windows / big GUI scales.
        float fit = Math.min((width - 20) / (float) (BOX_W * 2 + GAP * 2), (height - 20) / (float) (BOX_H * 2 + GAP * 2));
        scale = Math.max(0.1f, Math.min(scale, fit));
        int boxW = Math.max(1, (int) (BOX_W * scale));
        int boxH = Math.max(1, (int) (BOX_H * scale));
        int gap = Math.max(2, Math.round(GAP * scale));
        int centerX = width / 2;
        int centerY = height / 2;
        int hovered = spotFor(mouseX, mouseY, width, height);
        var font = Minecraft.getInstance().font;

        for (int spot = 0; spot < 4; spot++) {
            LeapTarget target = spots[spot];
            if (target == null) {
                continue;
            }
            int x0 = spot % 2 == 0 ? centerX - gap - boxW : centerX + gap;
            int y0 = spot < 2 ? centerY - gap - boxH : centerY + gap;
            DungeonClass cls = LeapMenuConfig.getInstance().getAssignedClass(target.name());
            com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.LEAP_MENU);
            int color = SpiritLeapOverlayConfig.getInstance().isUseClassColors() && cls != null ? cls.color()
                    : (menu == null ? 0xFFCC6600 : menu.accent());

            // Dead teammates (tab list "(DEAD)") get a red-tinted box - no extra text, and the click still goes through.
            boolean dead = PartyTracker.isDead(target.name());
            int fill = dead ? (spot == hovered ? 0xE0501414 : 0xCC350A0A) : (menu == null ? (spot == hovered ? 0xE0262626 : 0xCC0D0D0D)
                    : (spot == hovered ? com.killer560.hub.gui.OverlayPalette.alpha(menu.cardHover(), 0xE0)
                    : com.killer560.hub.gui.OverlayPalette.alpha(menu.panel(), 0xCC)));
            graphics.fill(x0, y0, x0 + boxW, y0 + boxH, fill);
            graphics.outline(x0, y0, boxW, boxH, color);
            // Their face on the left of the box (Show Player Heads); the name centres in what is left.
            int face = SpiritLeapOverlayConfig.getInstance().isShowHeads() ? Math.max(8, (int) (boxH * 0.6f)) : 0;
            int textLeft = x0;
            if (face > 0) {
                int fx = x0 + (boxH - face) / 2;
                int fy = y0 + (boxH - face) / 2;
                graphics.fill(fx - 1, fy - 1, fx + face + 1, fy + face + 1, 0xFF000000);
                com.killer560.hub.livemap.LeapMapView.drawFace(graphics, target.name(), fx, fy, face);
                textLeft = fx + face;
            }
            int textW = x0 + boxW - textLeft;
            // name grows with the box but never spills out of it (long IGNs at high scale / shrunk boxes)
            float maxText = Math.min((textW - 8) / (float) Math.max(1, font.width(target.name())), (boxH - 4) / 9.0f);
            float textScale = Math.max(0.5f, Math.min(Math.max(1.0f, scale * 1.5f), maxText));
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(textLeft + textW / 2.0f, y0 + boxH / 2.0f);
                graphics.pose().scale(textScale, textScale);
                graphics.centeredText(font, target.name(), 0, -4, color);
            } finally {
                graphics.pose().popMatrix();
            }
        }
    }
}
