package com.killer560.hub.islands;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudInGameRenderer;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.GuiScaleOverride;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Arrays;
import java.util.List;

/**
 * Melody's Harp Helper (both builds; the legit-side extras players of other SkyBlock mods expect from the harp): while a
 * "Harp - &lt;song&gt;" menu is open it adds
 * <ul>
 *   <li>Key Binds: one key (or mouse button) per note column plays that column's click-row slot (37-43) at once with a
 *       middle click, defaults 1-7. The press is his input: sent in the same tick, never delayed or queued, and a held key
 *       does not repeat. The vanilla number-key swap on the hovered slot is swallowed with it.</li>
 *   <li>Show Keys: the bound key's name in the corner of each click-row slot.</li>
 *   <li>Progress tracker: notes hit / notes seen, percent and combo, drawn over the menu at its HUD position. Counted from
 *       what the menu shows (a note reaching the click row, a click on its column), so it follows his keys, his mouse and
 *       Auto Melody's Harp alike; it is an estimate, the server decides what counts.</li>
 *   <li>Custom GUI Scale for the harp and its song list ({@link GuiScaleOverride}); the saved option is never touched.</li>
 *   <li>Hide Tooltips: no item tooltips inside the harp.</li>
 *   <li>Quick Restart: in the song list, within 2 s of opening it, a click on the close button (slot 40) clicks the
 *       selected song instead.</li>
 * </ul>
 * Switch OFF by default.
 */
public final class HarpHelperFeature {

    public static final IslandConfig CFG = config();

    private static final int COLUMNS = AutoHarpFeature.COLUMNS;
    private static final int FIRST_SLOT = AutoHarpFeature.FIRST_SLOT;
    private static final int CLOSE_SLOT = 40;
    private static final long QUICK_MS = 2000;
    /** An early click's credit lives this long (the lead of Auto Melody's Harp is at most 4 steps). */
    private static final long CREDIT_MS = 1500;

    private static Screen harpScreen;
    private static final boolean[] keyDown = new boolean[COLUMNS];
    private static boolean scaleApplied;

    // Progress tracker.
    private static int container = -1;
    private static int lastHash;
    private static final int[] state = new int[COLUMNS]; // 0 none, 1 a note waits in the click row, 2 it was hit
    private static final boolean[] due = new boolean[COLUMNS];
    private static final int[] credit = new int[COLUMNS];
    private static final long[] creditAt = new long[COLUMNS];
    private static int seen;
    private static int hit;
    private static int combo;
    private static int best;
    private static boolean dirty;
    private static boolean stepPending;
    private static List<String> lines = List.of();
    private static int labelsDrawn;

    private HarpHelperFeature() {
    }

    private static IslandConfig config() {
        IslandConfig c = IslandConfig.of("island", "harphelper").flag("keys", true).flag("numbers", true)
                .flag("tracker", true).flag("hideTips", false).flag("quick", false).flag("scaleOn", false)
                .num("scale", 0, 0, 6);
        for (int i = 0; i < COLUMNS; i++) {
            c.num("key" + (i + 1), McInput.toStoredKey(InputConstants.KEY_1 + i), -120, 400);
        }
        return c;
    }

    public static void register() {
        int bit = FeatureBits.register("Melody's Harp Helper", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("HarpHelperFeature.tick", bit, HarpHelperFeature::tick));
        var hud = new RiftFishingHud("harp_progress", "Harp Progress", 10, 60,
                () -> CFG.isEnabledRaw() && CFG.on("tracker"), () -> lines);
        HudElementRegistry.register(hud);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> s)) {
                return;
            }
            String title = s.getTitle().getString();
            if (AutoHarpFeature.isHarpTitle(title)) {
                harpScreen = screen;
                Arrays.fill(keyDown, false);
                ScreenEvents.remove(screen).register(x -> harpScreen = null);
                ScreenKeyboardEvents.allowKeyPress(screen).register((x, event) -> !onBind(s, McInput.key(event), true));
                ScreenKeyboardEvents.afterKeyRelease(screen).register((x, event) -> {
                    int i = column(McInput.key(event));
                    if (i >= 0) {
                        keyDown[i] = false;
                    }
                });
                ScreenMouseEvents.allowMouseClick(screen).register((x, event) -> {
                    int code = KeyUtil.codeForMouseButton(McInput.button(event));
                    if (onBind(s, code, false)) {
                        return false;
                    }
                    Slot hovered = ((AbstractContainerScreenAccessor) s).killer560smod$getHoveredSlot();
                    if (hovered != null) {
                        noteClick(s.getMenu(), hovered.index);
                    }
                    return true;
                });
                ScreenEvents.afterExtract(screen).register((x, g, mx, my, delta) -> {
                    if (CFG.isEnabled()) {
                        draw(s, g, hud);
                    }
                });
            } else if (isListTitle(title)) {
                long opened = System.nanoTime();
                ScreenMouseEvents.allowMouseClick(screen).register((x, event) -> !quickRestart(s, opened, McInput.button(event)));
            }
        });
    }

    static boolean isListTitle(String title) {
        return "Melody".equals(title) || "Melody's Harp".equals(title);
    }

    private static String path(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ---- key binds ------------------------------------------------------------------------------------------------

    /** The note column bound to {@code code} (a stored key or mouse code), or -1. */
    private static int column(int code) {
        if (code == KeyUtil.NONE) {
            return -1;
        }
        for (int i = 0; i < COLUMNS; i++) {
            if (CFG.num("key" + (i + 1)) == code) {
                return i;
            }
        }
        return -1;
    }

    /** @return whether the press is one of ours and must not reach the screen (a held key's repeats are swallowed too). */
    private static boolean onBind(AbstractContainerScreen<?> screen, int code, boolean keyboard) {
        int i;
        if (!CFG.isEnabled() || !CFG.on("keys") || (i = column(code)) < 0) {
            return false;
        }
        if (keyboard) {
            if (keyDown[i]) {
                return true;
            }
            keyDown[i] = true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && mc.player != null) {
            AbstractContainerMenu menu = screen.getMenu();
            ActionGate.noteHisClick(ActionGate.Actor.HARP_KEY);
            noteClick(menu, FIRST_SLOT + i);
            mc.gameMode.handleContainerInput(menu.containerId, FIRST_SLOT + i, 2, ContainerInput.CLONE, mc.player);
        }
        return true;
    }

    /** The key's short name for the label on column {@code i}. */
    public static String keyLabel(int i) {
        int code = CFG.num("key" + (i + 1));
        if (code == KeyUtil.NONE) {
            return "";
        }
        if (KeyUtil.isMouseCode(code)) {
            return "M" + (KeyUtil.mouseButton(code) + 1);
        }
        String name = KeyUtil.bindDisplayName(code);
        return name.length() > 3 ? name.substring(0, 3) : name;
    }

    // ---- quick restart ----------------------------------------------------------------------------------------------

    private static boolean quickRestart(AbstractContainerScreen<?> screen, long openedNanos, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (!CFG.isEnabled() || !CFG.on("quick") || mc.gameMode == null || mc.player == null
                || System.nanoTime() - openedNanos > QUICK_MS * 1_000_000L) {
            return false;
        }
        Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
        if (hovered == null || hovered.index != CLOSE_SLOT) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        for (Slot s : menu.slots) {
            if (s.index < 36 && isSelectedSong(s.getItem())) {
                mc.gameMode.handleContainerInput(menu.containerId, s.index, button, ContainerInput.PICKUP, mc.player);
                return true;
            }
        }
        return false;
    }

    private static boolean isSelectedSong(ItemStack stack) {
        ItemLore lore = stack.isEmpty() ? null : stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                if (line.getString().contains("Song is selected!")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- tooltips ---------------------------------------------------------------------------------------------------

    /** Whether item tooltips are hidden right now (read by {@code HarpTooltipMixin} on every tooltip build). */
    public static boolean hidesTooltips() {
        return harpScreen != null && CFG.isEnabled() && CFG.on("hideTips") && McCompat.screen(Minecraft.getInstance()) == harpScreen;
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    private static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, RiftFishingHud hud) {
        if (CFG.on("tracker")) {
            HudInGameRenderer.drawOver(g, hud);
        }
        if (!CFG.on("numbers") || !CFG.on("keys")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor acc =
                (com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor) screen;
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < COLUMNS && FIRST_SLOT + i < slots.size(); i++) {
            String label = keyLabel(i);
            Slot s = slots.get(FIRST_SLOT + i);
            if (!label.isEmpty() && !s.getItem().isEmpty()) {
                g.text(mc.font, label, acc.killer560smod$getLeftPos() + s.x + 17 - mc.font.width(label),
                        acc.killer560smod$getTopPos() + s.y + 9, 0xFFFFFFFF, true);
                labelsDrawn++;
            }
        }
    }

    // ---- progress tracker -------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        Screen sc = McCompat.screen(client);
        AbstractContainerScreen<?> screen = sc instanceof AbstractContainerScreen<?> s ? s : null;
        String title = screen == null ? "" : screen.getTitle().getString();
        boolean harp = CFG.isEnabled() && screen != null && AutoHarpFeature.isHarpTitle(title);
        int wanted = 0;
        if (CFG.isEnabled() && CFG.on("scaleOn") && screen != null && (harp || isListTitle(title))) {
            wanted = CFG.num("scale") == 0 ? GuiScaleOverride.AUTO : CFG.num("scale");
        }
        if (wanted > 0 || scaleApplied) {
            scaleApplied = GuiScaleOverride.tick(wanted, scaleApplied);
        }
        if (harp) {
            track(screen.getMenu(), System.nanoTime() / 1_000_000L);
        } else {
            container = -1;
        }
    }

    private static void track(AbstractContainerMenu menu, long now) {
        int hash = AutoHarpFeature.rowsHash(menu);
        if (menu.containerId != container) {
            container = menu.containerId;
            Arrays.fill(state, 0);
            Arrays.fill(due, false);
            Arrays.fill(credit, 0);
            seen = hit = combo = best = 0;
            dirty = true;
            stepPending = true;
        } else if (hash != lastHash) {
            stepPending = true;
        } else if (stepPending) { // the rows moved and now hold still: all of that step's slot updates have arrived
            stepPending = false;
            for (int c = 0; c < COLUMNS && FIRST_SLOT + c < menu.slots.size(); c++) {
                if (credit[c] > 0 && now - creditAt[c] > CREDIT_MS) {
                    credit[c] = 0;
                }
                if ("quartz_block".equals(path(menu.slots.get(FIRST_SLOT + c).getItem()))) {
                    if (state[c] == 0 || due[c]) {
                        if (state[c] == 1) {
                            combo = 0;
                        }
                        due[c] = false;
                        seen++;
                        if (credit[c] > 0) {
                            credit[c]--;
                            state[c] = 2;
                            scored();
                        } else {
                            state[c] = 1;
                        }
                        dirty = true;
                    }
                } else if (state[c] != 0) {
                    if (state[c] == 1) {
                        combo = 0;
                        dirty = true;
                    }
                    state[c] = 0;
                }
                if (isWool(menu, 28 + c)) {
                    due[c] = true;
                }
            }
        }
        lastHash = hash;
        if (dirty) {
            dirty = false;
            lines = List.of("§bMelody's Harp§7: §f" + hit + "§7/§f" + seen + " §7(§a"
                    + (seen == 0 ? 0 : hit * 100 / seen) + "%§7)", "§7Combo §f" + combo + " §7Best §f" + best);
        }
    }

    private static boolean isWool(AbstractContainerMenu menu, int slot) {
        return slot < menu.slots.size() && path(menu.slots.get(slot).getItem()).endsWith("_wool");
    }

    private static void scored() {
        hit++;
        best = Math.max(best, ++combo);
    }

    /**
     * A click on a click-row slot (his key, his mouse or Auto Melody's Harp) is counted: a hit when a note waits in that
     * column's click-row slot, an early hit (credited when the note arrives) when a note is 1-4 rows above it.
     */
    static void noteClick(AbstractContainerMenu menu, int slot) {
        int c = slot - FIRST_SLOT;
        if (!CFG.isEnabledRaw() || c < 0 || c >= COLUMNS || slot >= menu.slots.size()) {
            return;
        }
        if ("quartz_block".equals(path(menu.slots.get(slot).getItem()))) {
            if (state[c] == 1 || state[c] == 0 || due[c]) { // a note waits (or just reached the click row, not yet counted)
                if (state[c] != 1) {
                    seen++;
                    due[c] = false;
                }
                state[c] = 2;
                scored();
                dirty = true;
            }
            return;
        }
        for (int k = 1; k <= AutoHarpFeature.MAX_EARLY; k++) {
            if (slot - 9 * k >= 0 && isWool(menu, slot - 9 * k)) {
                if (credit[c]++ == 0) {
                    creditAt[c] = System.nanoTime() / 1_000_000L;
                }
                return;
            }
        }
    }

    /** Harness: notes hit and seen, combo and best combo of the open song, and key labels drawn since launch. */
    public static int[] counts() {
        return new int[]{hit, seen, combo, best, labelsDrawn};
    }
}
