package com.killer560.hub.terminals;

import com.killer560.hub.storageoverlay.mixin.SlotClickInvoker;
import com.killer560.hub.util.ChatObserver;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Terminal QoL pack for F7/M7 P3 - five small terminal-adjacent features that are independent of the
 * Terminal Solver's own highlighting and work whether that is on or off. Every one is ported from
 * Devonian (Kotlin source read 2026-09-16 at {@code C:\Users\Hunter\UsersHunterdevonian\src\main\kotlin\
 * com\github\synnerz\devonian\features\dungeons\f7\}), named per feature below:
 * <ul>
 * <li><b>Terminal Protection</b> ({@code TerminalProtection.kt}) - swallows the FIRST click that lands
 *     within a threshold of the terminal opening, so a ghost/double click queued before the GUI even
 *     populated can't fire at a slot you never meant to click. Devonian's threshold slider is 100-700 ms,
 *     default 400, with the note {@code "lowest current safe value is 400 - ping" - LegendaryJG}; the
 *     ping subtraction is a toggle here instead of something the player has to do in their head.
 *     One-shot per terminal, exactly like Devonian ({@code terminalStart = -1L} after a swallow) - the
 *     feature never traps you in a terminal you can't click.
 * <li><b>Drop Key</b> ({@code TerminalDropKey.kt}) - re-binds the vanilla Drop key while a terminal is
 *     open so Q can't throw your Necron's Blade mid-terms. See {@link #restoreDropKeyAfterCrash()} for
 *     the one thing this does that Devonian's version does not.
 * <li><b>Melody Keys</b> ({@code MelodyKeys.kt}) - number keys 1-4 click Melody's four row buttons
 *     (real slots 16/25/34/43, i.e. {@code row * 9 + 16} - the same constants
 *     {@link TerminalSolverFeature}'s own {@code MELODY_CLAY_SLOTS} already uses).
 * <li><b>Terminal GUI Scale</b> ({@code CustomTerminalScale.kt}) - a separate GUI scale while a terminal
 *     is open, and a second one just for Melody (whose board is much wider). 0 = auto = leave Minecraft's
 *     own scale alone, same as Devonian's sliders.
 * <li><b>Hide Completion</b> ({@code TerminalHideCompletion.kt}) - hides the "Player activated a terminal!
 *     (3/7)" title/subtitle, and optionally the matching chat line. Devonian only does the title; the chat
 *     half is killer560's own ask and is a separate toggle because of the Terminal Timers interaction
 *     documented on {@link #shouldHideCompletionChat}.
 * </ul>
 * All five are LEGIT by this mod's own rules - four of them only ever BLOCK or restyle your own input and
 * your own screen. Melody Keys is the one grey area: it sends one slot click per keypress. That is the
 * exact same class of action as the mod's existing Slot Binds, Loadout Keybinds and Custom Leap Menu keys
 * 1-4 (the player initiates every single click, nothing is automated, no rotation, no timing advantage),
 * so it lives here with the other legit keybinds rather than under Auto Terminals' cheat gate.
 * <p>
 * Ships entirely OFF - see {@link TerminalQolConfig}.
 */
public final class TerminalQolFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-terminalqol");

    /** Devonian's own {@code terminalTitleRegex}, widened with the same optional trailing group
     *  {@code DeviceTimesFeature}/{@code TerminalTimersFeature} already use, so a line another mod (or this
     *  mod's own Terminal Timers) has appended split times to still matches. */
    private static final Pattern COMPLETION_REGEX = Pattern.compile(
            "^(\\w{1,16}) (?:activated a (?:terminal|lever)|completed a device)! \\(\\d+/\\d+\\)(?:\\s.*)?$");

    /** Melody's four clickable row buttons. Same real slots {@link TerminalSolverFeature}'s own Melody code
     *  uses ({@code MELODY_CLAY_SLOTS = 16, 25, 34, 43}); Devonian computes the identical {@code i * 9 + 16}. */
    private static final int[] MELODY_BUTTON_SLOTS = {16, 25, 34, 43};

    private static final int[] MELODY_KEYS = {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4};
    private static final int[] MELODY_NUMPAD_KEYS =
            {GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3, GLFW.GLFW_KEY_KP_4};

    // ---- live terminal tracking (independent of TerminalSolverFeature, which only tracks while IT is on) ----
    private static TerminalType currentType;
    private static int currentScreenIdentity;
    private static long terminalOpenedAtMs = -1L;

    // ---- drop key ----
    private static boolean dropKeySwapped = false;

    // ---- gui scale ----
    private static boolean guiScaleApplied = false;
    private static int appliedGuiScale = -1;

    private static boolean registered = false;

    private TerminalQolFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        restoreDropKeyAfterCrash();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ------------------------------------------------------------------ terminal tracking

    /** Recomputes which terminal (if any) is open right now. Called from the client tick AND from the head of
     *  every input hook: a click can land before the first tick after the screen opened, and Terminal
     *  Protection's whole job is to judge that exact click, so it must never see a stale open time. */
    private static void syncScreen() {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            currentType = null;
            currentScreenIdentity = 0;
            terminalOpenedAtMs = -1L;
            return;
        }
        TerminalType type = matchType(container.getTitle().getString());
        if (type == null) {
            currentType = null;
            currentScreenIdentity = 0;
            terminalOpenedAtMs = -1L;
            return;
        }
        int identity = System.identityHashCode(screen);
        if (type != currentType || identity != currentScreenIdentity) {
            currentType = type;
            currentScreenIdentity = identity;
            terminalOpenedAtMs = System.currentTimeMillis();
        }
    }

    /** Title -> terminal type, on the shared {@link TerminalType} regexes (formatting stripped first, the same
     *  way every other chat/title match in this mod does - a real Hypixel title can carry mid-word § codes). */
    private static TerminalType matchType(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String title = ChatObserver.strip(rawTitle);
        for (TerminalType type : TerminalType.values()) {
            if (type.titlePattern().matcher(title).matches()) {
                return type;
            }
        }
        return null;
    }

    /** @return the terminal currently open, or null. Public so the settings GUI / future features can ask. */
    public static TerminalType getOpenTerminal() {
        return currentType;
    }

    // ------------------------------------------------------------------ Terminal Protection

    /** Devonian {@code TerminalProtection}: true when this click landed too soon after the terminal opened and
     *  must be swallowed. Plays the same note-block "nope" it does, and - exactly like Devonian - arms itself
     *  off afterwards ({@code terminalStart = -1L}), so only the first too-early click of a given terminal is
     *  ever eaten. */
    public static boolean shouldSwallowClick() {
        syncScreen();
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        if (!cfg.isProtectionEnabled() || currentType == null || terminalOpenedAtMs < 0) {
            return false;
        }
        long threshold = cfg.getProtectionThresholdMs();
        if (cfg.isProtectionSubtractPing()) {
            threshold -= ping();
        }
        if (threshold <= 0) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - terminalOpenedAtMs;
        if (elapsed > threshold) {
            return false;
        }
        terminalOpenedAtMs = -1L;
        playBlockedSound();
        LOGGER.info("[TerminalQol] Swallowed a {} click {}ms after the terminal opened (threshold {}ms{})",
                currentType, elapsed, threshold, cfg.isProtectionSubtractPing() ? ", ping " + ping() + "ms subtracted" : "");
        return true;
    }

    /** Non-consuming twin of {@link #shouldSwallowClick()}, for features that send a click on killer560's
     *  behalf ({@link HoverTerminalFeature}) instead of judging one he actually made.
     *  @return true while the open terminal is still inside Terminal Protection's opening window. A
     *  synthetic click is simply not SENT while this is true, rather than sent and then swallowed -
     *  swallowing burns Protection's deliberate one-shot ({@code terminalStart = -1L} after the first
     *  swallow, exactly like Devonian), which would leave killer560's own real first click unprotected
     *  because an automated click he never made had already spent it. */
    public static boolean withinProtectionWindow() {
        syncScreen();
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        if (!cfg.isProtectionEnabled() || currentType == null || terminalOpenedAtMs < 0) {
            return false;
        }
        long threshold = cfg.getProtectionThresholdMs();
        if (cfg.isProtectionSubtractPing()) {
            threshold -= ping();
        }
        return threshold > 0 && System.currentTimeMillis() - terminalOpenedAtMs <= threshold;
    }

    private static int ping() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) {
            return 0;
        }
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    private static void playBlockedSound() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            // Devonian's own PREVENTED_SOUND: NOTE_BLOCK_BASS on MASTER at volume 1, pitch 0.5.
            client.level.playPlayerSound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 1f, 0.5f);
        }
    }

    // ------------------------------------------------------------------ Melody Keys

    /** Devonian {@code MelodyKeys}: 1-4 (and the numpad equivalents) click Melody's four row buttons.
     *  @return true when the key was consumed, so the mixin cancels it - otherwise 1-4 would be vanilla's
     *  hotbar-swap keys and would try to SWAP an item into the terminal slot. */
    public static boolean handleMelodyKey(AbstractContainerScreen<?> screen, int keyCode) {
        syncScreen();
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        if (!cfg.isMelodyKeysEnabled() || currentType != TerminalType.MELODY || screen == null) {
            return false;
        }
        int row = -1;
        for (int i = 0; i < MELODY_KEYS.length; i++) {
            if (keyCode == MELODY_KEYS[i] || keyCode == MELODY_NUMPAD_KEYS[i]) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            return false;
        }
        int slotIndex = MELODY_BUTTON_SLOTS[row];
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex >= slots.size()) {
            LOGGER.warn("[TerminalQol] Melody key {} ignored: slot {} out of range (menu has {} slots)",
                    row + 1, slotIndex, slots.size());
            return false;
        }
        Slot slot = slots.get(slotIndex);
        // Same real click path Custom GUI / Auto Terminals already use, so the click is indistinguishable from
        // a mouse click on that slot (sounds, carried-item bookkeeping, the real network packet).
        ((SlotClickInvoker) (Object) screen).killer560smod$slotClicked(slot, slot.index, 0, ContainerInput.PICKUP);
        return true;
    }

    // ------------------------------------------------------------------ Hide Completion

    /** Devonian {@code TerminalHideCompletion}: true for a "Player activated a terminal! (3/7)" style title or
     *  subtitle that should be suppressed. With "Only Others" on (Devonian's own {@code onlyShowOwn} default),
     *  your own line still shows - it's the party's spam that fills the screen mid-terminal, not your own. */
    public static boolean shouldHideCompletionTitle(Component text) {
        return TerminalQolConfig.getInstance().isHideCompletionTitles() && matchesHiddenCompletion(text);
    }

    /** The chat half of Hide Completion. A separate toggle from the title half on purpose: this mod's own
     *  Terminal Timers feature REWRITES exactly these lines to append "(3.21s | 14.44s)" splits, so hiding
     *  them throws away that feature's whole output. Device Times / Terminal Timers' own parsing is NOT
     *  affected either way - see {@code TerminalQolChatMixin} for how the line still reaches
     *  {@code ChatObserver}'s subscribers before it is dropped. */
    public static boolean shouldHideCompletionChat(Component text) {
        return TerminalQolConfig.getInstance().isHideCompletionChat() && matchesHiddenCompletion(text);
    }

    private static boolean matchesHiddenCompletion(Component text) {
        if (text == null) {
            return false;
        }
        String plain = ChatObserver.strip(text);
        var match = COMPLETION_REGEX.matcher(plain);
        if (!match.matches()) {
            return false;
        }
        if (!TerminalQolConfig.getInstance().isHideCompletionOnlyOthers()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return client.player == null || !match.group(1).equals(client.player.getName().getString());
    }

    // ------------------------------------------------------------------ per-tick work (drop key + gui scale)

    private static void tick() {
        syncScreen();
        tickDropKey();
        tickGuiScale();
    }

    // ---- Drop Key ----

    /** Devonian {@code TerminalDropKey}, with one addition: the real Drop key's name is written to this mod's
     *  own config the moment it is swapped out and cleared the moment it is put back, so
     *  {@link #restoreDropKeyAfterCrash()} can undo the swap on the next launch. Without that, a crash (or the
     *  player alt-F4ing) with a terminal open would leave {@code options.keyDrop} pointing at the replacement
     *  key - and Minecraft saves options.txt on shutdown, so the rebind would be PERMANENT and would look like
     *  the game itself broke. */
    private static void tickDropKey() {
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }
        boolean wanted = cfg.isDropKeyEnabled() && currentType != null;
        if (wanted && !dropKeySwapped) {
            String realKey = client.options.keyDrop.saveString();
            cfg.setSavedDropKeyName(realKey);
            cfg.save();
            client.options.keyDrop.setKey(keyFor(cfg.getDropKeyCode()));
            dropKeySwapped = true;
            LOGGER.info("[TerminalQol] Drop key swapped from {} to {} for the duration of this terminal",
                    realKey, keyFor(cfg.getDropKeyCode()).getName());
        } else if (!wanted && dropKeySwapped) {
            restoreDropKey(client, cfg);
        }
    }

    private static void restoreDropKey(Minecraft client, TerminalQolConfig cfg) {
        String saved = cfg.getSavedDropKeyName();
        if (!saved.isEmpty() && client.options != null) {
            client.options.keyDrop.setKey(InputConstants.getKey(saved));
            LOGGER.info("[TerminalQol] Drop key restored to {}", saved);
        }
        cfg.setSavedDropKeyName("");
        cfg.save();
        dropKeySwapped = false;
    }

    /** Called once from {@link #register()}: if the config still holds a real Drop key, the last session ended
     *  (crash, force-quit, /exit) while the swap was live - put it back before the player ever notices. */
    private static void restoreDropKeyAfterCrash() {
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        String saved = cfg.getSavedDropKeyName();
        if (saved.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            client.options.keyDrop.setKey(InputConstants.getKey(saved));
            LOGGER.warn("[TerminalQol] Drop key was still swapped out from a previous session - restored to {}", saved);
        }
        cfg.setSavedDropKeyName("");
        cfg.save();
    }

    private static InputConstants.Key keyFor(int glfwKeyCode) {
        return glfwKeyCode < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(glfwKeyCode);
    }

    // ---- Custom Terminal Scale ----

    /** Devonian {@code CustomTerminalScale}, implemented the simple way: Devonian swaps the scale per FRAME
     *  around {@code Screen.extractRenderStateWithTooltipAndSubtitles}, which also needs MouseHandler
     *  width/height overrides to keep the cursor mapped correctly. Setting the window's real GUI scale once on
     *  open and putting it back once on close needs none of that - the mouse handler naturally scales against
     *  whatever the window's current scale is, so clicks land where they look like they land.
     *  <p>
     *  Re-applied every tick when it doesn't match: vanilla's own {@code Minecraft.resizeDisplay} recomputes the
     *  scale straight from {@code options.guiScale()} whenever the window is resized, which would otherwise
     *  silently drop the override mid-terminal. */
    private static void tickGuiScale() {
        Minecraft client = Minecraft.getInstance();
        Window window = client.getWindow();
        if (window == null || client.options == null) {
            return;
        }
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        int wanted = currentType == null ? 0
                : currentType == TerminalType.MELODY ? cfg.getMelodyGuiScale() : cfg.getTerminalGuiScale();
        if (wanted > 0) {
            int target = window.calculateScale(wanted, client.isEnforceUnicode());
            if (window.getGuiScale() != target) {
                applyScale(client, window, target);
            }
            guiScaleApplied = true;
            appliedGuiScale = target;
        } else if (guiScaleApplied) {
            guiScaleApplied = false;
            appliedGuiScale = -1;
            // Recomputed from the live option rather than remembered, so a scale the player changed in Video
            // Settings while a terminal was open is still honoured when it closes.
            int vanilla = window.calculateScale(client.options.guiScale().get(), client.isEnforceUnicode());
            if (window.getGuiScale() != vanilla) {
                applyScale(client, window, vanilla);
            }
        }
    }

    private static void applyScale(Minecraft client, Window window, int scale) {
        window.setGuiScale(scale);
        Screen screen = client.screen;
        if (screen != null) {
            screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        }
    }

    /** Diagnostic only - the GUI scale currently forced by this feature, or -1. */
    public static int getAppliedGuiScale() {
        return appliedGuiScale;
    }
}
