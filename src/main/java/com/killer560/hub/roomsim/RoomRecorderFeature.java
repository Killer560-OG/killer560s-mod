package com.killer560.hub.roomsim;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;

import com.killer560.hub.util.KeyUtil;

import org.lwjgl.glfw.GLFW;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ActionGate;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Random;

/**
 * Fills the room library by running F7 over and over and reading whatever loads.
 *
 * <p>killer560's loop, 2026-09-28: "Every 30s it'll do /f7 and open the f7 run. It gets 25s to scan what it can
 * see then it does /dh. Once it gets to the dh it waits for 7s then does /f7 again. If it gets sent to limbo
 * have it wait 65s then do /skyblock and rejoin then do /f7 after 5s."
 *
 * <p><b>DEV BUILDS ONLY.</b> Gated on {@link BuildVariant#DEV_TOOLS}, which is compiled out of a release, so this
 * cannot reach anyone else's game. It is a tool for building the library that ships, not a feature.
 *
 * <p><b>It stops on any key.</b> Touch the keyboard and it is off, with a message saying so - not paused, off.
 * This drives real dungeon entry on a real server for hours unattended, and the one thing that must always work
 * is the ability to take it back instantly without remembering which key is the right one.
 *
 * <p>Timings carry a few seconds of jitter rather than firing on an exact metronome. A fixed 30.000s period
 * repeated for hours is not something a person produces, and the whole point of this loop is to look like
 * somebody grinding rooms.
 */
public final class RoomRecorderFeature {

    private static final Random JITTER = new Random();

    /** Fractional wobble applied to every wait. Enough to break the metronome, not enough to break the loop. */
    private static final double VARIANCE = 0.12;

    /** Most extra time added on top of a server swap's one-second floor. */
    private static final double SWAP_EXTRA_MAX_SECONDS = 1.5;

    private enum Stage {
        /** Not running. */
        OFF,
        /** Waiting to send the joininstance for F7. */
        ENTER,
        /**
         * Sent the join, waiting on Hypixel's "Undersized party!" confirm menu.
         *
         * <p>killer560 (2026-09-28): "it does need to click this after running the command." Joining F7 solo
         * does not drop you into the floor - Hypixel opens a menu warning that the instance wants five players,
         * and nothing happens until something clicks "Click to play anyway!". Without this the loop queued a
         * dungeon it never entered.
         */
        CONFIRM,
        /** Inside the run, reading rooms. */
        SCAN,
        /** Sent /dh, waiting to arrive. */
        LEAVING,
        /** In the hub, waiting before the next floor join. */
        HUB,
        /** Dropped to limbo: the long wait before /skyblock. */
        LIMBO,
        /** Sent /skyblock, waiting before the floor join. */
        REJOIN,
        /** Five-puzzle run: handed over to him, still capturing whatever he walks into. */
        PAUSED
    }

    private static Stage stage = Stage.OFF;
    private static int waitTicks;
    private static int roomsAddedThisRun;
    private static int runs;

    private RoomRecorderFeature() {
    }

    public static void register() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("RoomRecorderFeature.tick", RoomRecorderFeature::tick));
    }

    public static boolean isRunning() {
        return stage != Stage.OFF;
    }

    /**
     * Only arms in the instance made for it.
     *
     * <p>DEV_TOOLS is true in every non-release build, so without this the command exists in his Dungeons
     * instance too - the one he actually plays. This drives real dungeon entry on a loop, and starting it by
     * accident in the wrong instance is the expensive mistake here, so it checks the game directory it is
     * running in rather than trusting the command not to be typed.
     */
    public static boolean inRecorderInstance() {
        try {
            String dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .toAbsolutePath().toString().toLowerCase(Locale.ROOT);
            return dir.contains("map logger");
        } catch (Exception e) {
            return false;
        }
    }

    public static void start() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        if (!inRecorderInstance()) {
            say("Room Recorder only runs in the Map Logger instance - it enters dungeons on a loop and this is "
                    + "not the instance for that.");
            return;
        }
        RoomLibrary.load();
        stage = Stage.ENTER;
        waitTicks = seconds(2);
        runs = 0;
        // killer560 (2026-09-28): "if i turn it on it auto turns off". This is why, and it made the feature
        // impossible to start from the command: the RETURN key that submits "/killer560 roomrecorder" is still
        // physically down on the next tick, the any-key stop sees it and switches straight back off. The resume
        // key path already armed this guard; start() never did. Cleared once the keyboard is clear, so a key
        // held at the moment of starting never counts as taking the controls back - only a fresh press does.
        suppressKeyStop = true;
        say("Room Recorder ON. It will run F7 on a loop. Press any key to stop.");
    }

    private static int alertTicks;
    private static boolean resumeKeyWasDown;
    /** Quarter of a second, his figure. */
    private static final int TOGGLE_DEBOUNCE_TICKS = 5;
    private static int lastToggleTick = -1000;
    /** Ticks since the client started - only ever compared against itself, for the toggle debounce. */
    private static int tickCounter;
    private static boolean suppressKeyStop;
    private static int confirmTicks;
    private static int scanCursor;

    /**
     * New columns to read per tick, across all rooms.
     *
     * <p>256 columns is about 21k block reads a tick, small beside what the client already does each frame, and
     * over a 25 second window it is far more than a whole map needs - so the cap costs no coverage at all.
     */
    private static final int COLUMNS_PER_TICK = 256;

    /**
     * Tick to click the undersized-party confirm on, or -1 when the menu is not up.
     *
     * <p>killer560 (2026-09-28): "make sure it doesnt insta click the join button give it a random delay
     * between .5s and 1s." A click on the same tick the menu renders is not a thing a hand does - nobody has
     * reacted to a window before it has drawn - and this loop opens that menu every thirty seconds all night,
     * so the one perfectly-zero reaction time would be the most repeated signal in the session.
     */
    private static int confirmClickTick = -1;

    /** Reaction-time window for that click, in ticks: 0.5s to 1.0s. */
    private static final int CONFIRM_DELAY_MIN_TICKS = 10;
    private static final int CONFIRM_DELAY_MAX_TICKS = 20;

    /** How long to wait for the undersized-party menu before giving up on it and scanning anyway. */
    private static final int CONFIRM_TIMEOUT_TICKS = 100;

    /**
     * The rebindable key. One key, three states: running pauses, paused resumes, off starts.
     *
     * <p>killer560 (2026-09-28): "The key bind should pause and unpause it as one key bind along with the auto
     * pause on puzzles." So a manual pause is the same state the five-puzzle detection puts it in - it keeps
     * capturing whatever he walks into, and the any-key stop stays disabled, because a pause he asked for is a
     * pause he intends to be playing through.
     *
     * <p>Edge-triggered so holding it does not fire repeatedly, and it always sets {@link #suppressKeyStop}:
     * the key is by definition still down on the next tick, and the any-key stop would otherwise instantly
     * undo whatever it just did.
     */
    private static void pollResumeKey(Minecraft client) {
        int code = RoomRecorderConfig.getInstance().getResumeKeyCode();
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
        boolean pressed = down && !resumeKeyWasDown;
        resumeKeyWasDown = down;
        if (!pressed || client.screen != null) {
            return; // not while a screen is open: he is typing, not commanding
        }
        // A quarter second between toggles, on his request, so a stutter on the key cannot pause and unpause
        // in the same breath - which would look exactly like the key not working.
        if (tickCounter - lastToggleTick < TOGGLE_DEBOUNCE_TICKS) {
            return;
        }
        lastToggleTick = tickCounter;
        suppressKeyStop = true;
        switch (stage) {
            case OFF -> start();
            case PAUSED -> resume();
            default -> pause("key");
        }
    }

    /**
     * Holds the loop where it is, still capturing.
     *
     * <p>Same state the five-puzzle detection uses. It does not send /dh or /f7 while paused, so the run he is
     * standing in stays open for as long as he wants to walk it.
     */
    public static void pause(String reason) {
        if (stage == Stage.OFF || stage == Stage.PAUSED) {
            return;
        }
        stage = Stage.PAUSED;
        alertTicks = 0;
        RoomLibrary.saveAll();
        say("paused (" + reason + ") - still capturing what you walk into. Press the key again to carry on.");
    }


    /** Leaves a five-puzzle pause and carries on with the loop. */
    public static void resume() {
        if (stage != Stage.PAUSED) {
            return;
        }
        RoomLibrary.saveAll();
        stage = Stage.SCAN;
        waitTicks = 1;
        // Same reason as start(): "/killer560 roomrecorder resume" is submitted with Return, and coming back
        // from a pause straight into the any-key stop would look exactly like the resume having done nothing.
        suppressKeyStop = true;
        say("resumed");
    }

    public static boolean isPaused() {
        return stage == Stage.PAUSED;
    }

    /** Chat plus a sound, because he will not be looking at chat while walking a run. */
    private static void alert(Minecraft client, String message) {
        say(message);
        try {
            if (client != null && client.getSoundManager() != null) {
                client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f));
                client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
            }
        } catch (Throwable ignored) {
            // a missing sound must never stop the recorder
        }
    }

    public static void stop(String reason) {
        if (stage == Stage.OFF) {
            return;
        }
        stage = Stage.OFF;
        RoomLibrary.saveAll();
        say("Room Recorder OFF (" + reason + "). " + RoomLibrary.completeCount() + " of "
                + RoomLibrary.roomCount() + " rooms complete.");
    }

    private static void tick(Minecraft client) {
        if (client == null) {
            return;
        }
        tickCounter++;
        pollResumeKey(client);
        if (stage == Stage.OFF) {
            return;
        }
        // Tracked in EVERY stage, paused included: the instance cooldown keeps running while he walks a
        // five-puzzle run by hand, and resuming into a stale clock would fire /f7 straight into a refusal.
        DungeonInstanceCooldown.tick(client);
        // The key that started or resumed it is still held on the next tick, and the any-key stop would
        // instantly undo it. Wait for a clean keyboard before arming that again.
        if (client.screen != null) {
            // A screen is open, so every key belongs to it, not to the world. This is the other half of the
            // "if i turn it on it auto turns off" bug (killer560, 2026-09-28): Return submits the command that
            // starts it, and Escape closes the settings tab that starts it, and both were being read as him
            // taking the controls back. Re-armed rather than merely skipped, so the tick after the screen
            // closes still waits for a clean keyboard instead of seeing the key that closed it.
            suppressKeyStop = true;
        } else if (suppressKeyStop) {
            if (!anyKeyDown(client)) {
                suppressKeyStop = false;
            }
        } else if (stage != Stage.PAUSED && firstKeyDown(client) != -1) {
            // Not while paused: the pause exists so he can walk the run himself, and stopping the moment he
            // touches a movement key would make the feature impossible to use for the thing it is for.
            stop(KeyUtil.bindDisplayName(firstKeyDown(client)) + " pressed");
            return;
        }
        if (client.player == null || client.level == null) {
            // Between worlds: that is normal here, it is changing servers constantly.
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            if (stage == Stage.SCAN || stage == Stage.PAUSED) {
                // Paused included: he is walking the run to load rooms, and that is exactly when capturing
                // every tick matters most.
                scan(client);
            }
            return;
        }
        switch (stage) {
            case ENTER, REJOIN -> {
                // Hypixel allows a new instance every 30s. Waiting for the real cooldown rather than counting
                // to 30 ourselves means it never fires early into a refusal, and never sits idle after one.
                long cd = DungeonInstanceCooldown.instanceCooldownRemainingMs();
                if (cd > 0) {
                    waitTicks = (int) Math.max(1, cd / 50);
                    return;
                }
                ServerCommands.toServer(joinFloorCommand());
                roomsAddedThisRun = 0;
                stage = Stage.CONFIRM;
                confirmTicks = 0;
                confirmClickTick = -1;
                waitTicks = 2;
            }
            case CONFIRM -> {
                if (confirmUndersizedParty(client)) {
                    // Clicked. The floor load follows, and the scan clock starts from the join rather than from
                    // the click, so a slow menu does not eat the 25 seconds of scanning.
                    stage = Stage.SCAN;
                    waitTicks = seconds(25);
                    return;
                }
                confirmTicks++;
                if (confirmTicks > CONFIRM_TIMEOUT_TICKS) {
                    // No menu and no floor: either the join was refused or the party is big enough that Hypixel
                    // never asked. Carry on into the scan rather than stalling here forever - a run that turns
                    // out to be empty costs one cycle, a stuck loop costs the night.
                    stage = Stage.SCAN;
                    waitTicks = seconds(25);
                    return;
                }
                waitTicks = 1;
            }
            case SCAN -> {
                ServerCommands.toServer("dh");
                runs++;
                RoomLibrary.saveAll();
                say(String.format(Locale.US, "run %d: %d new column(s), %d/%d rooms complete",
                        runs, roomsAddedThisRun, RoomLibrary.completeCount(), RoomLibrary.roomCount()));
                stage = Stage.LEAVING;
                waitTicks = swapDelay(7);
            }
            case LEAVING, HUB -> {
                stage = Stage.ENTER;
                waitTicks = swapDelay(1);
            }
            case PAUSED -> {
                scan(client);
                // Keep reminding, quietly spaced: a single alert is easy to miss and this run is the one
                // worth not missing.
                alertTicks++;
                if (alertTicks % 200 == 0) {
                    alert(client, "still paused - /killer560 roomrecorder resume when you are done");
                }
                waitTicks = 20;
            }
            case LIMBO -> {
                // Back when the client is somewhere real again, not when a number says so.
                if (!DungeonInstanceCooldown.inPlayableWorld(client)
                        || DungeonInstanceCooldown.looksLikeLimbo(client)) {
                    waitTicks = seconds(2);
                    return;
                }
                ServerCommands.toServer("skyblock");
                stage = Stage.REJOIN;
                waitTicks = swapDelay(5);
            }
            default -> { }
        }
    }

    /**
     * Reads whatever of the current room is loaded.
     *
     * <p>Every tick of the scan window rather than once at the end, because the rooms that load are the ones
     * being walked past - waiting until the end would only ever capture wherever it happened to stop.
     */
    private static void scan(Minecraft client) {
        if (!DungeonState.isInDungeon()) {
            // Not in a run when we expected to be: most often limbo, which has its own long recovery.
            if (DungeonInstanceCooldown.looksLikeLimbo(client)) {
                stage = Stage.LIMBO;
                // 65s is his figure and stays as the floor; the stage itself then waits for a real world
                // rather than assuming 65 was long enough.
                waitTicks = seconds(65);
                say("limbo - waiting, then rejoining when the world is back");
            }
            return;
        }
        if (stage == Stage.SCAN && RoomRecorderConfig.getInstance().isPauseOnFivePuzzles()
                && com.killer560.hub.runsummary.RunSummaryFeature.puzzleCount() == 5) {
            stage = Stage.PAUSED;
            alertTicks = 0;
            alert(client, "FIVE PUZZLE RUN - paused. Walk it yourself to load rare rooms, then "
                    + "/killer560 roomrecorder resume");
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null) {
            return;
        }
        // killer560 (2026-09-28): "make sure it doesnt just scan the room i am in but every room in my render
        // distance." Standing in a doorway already loads four rooms, and a loop that only recorded the tile
        // underfoot threw away most of what the client had in memory.
        //
        // Nothing here decides what is "in render distance". RoomLibrary.capture already tests every column
        // against the chunk cache and skips the ones that are not loaded, so an out-of-range room contributes
        // nothing rather than a room-shaped block of air. Keeping that the single place load state is judged is
        // what keeps this honest - asking the question twice, in two ways, is how you store air and call it seen.
        int rooms = layout.roomCount();
        if (rooms > 0) {
            int budget = COLUMNS_PER_TICK;
            for (int i = 0; i < rooms && budget > 0; i++) {
                // Rotated start, so the room the budget runs out on is not the same one every tick and the far
                // side of the map is not permanently starved by the near side.
                int added = RoomLibrary.capture(client.level, layout, (scanCursor + i) % rooms, budget);
                budget -= added;
                roomsAddedThisRun += added;
            }
            scanCursor = (scanCursor + 1) % rooms;
        }
        captureMobSpawns(client, layout);
        // Measures what the sim is currently guessing at - the wither door's real size and shape. Costs nothing
        // when there is no unlogged door loaded, and never touches the room library.
        SimMeasure.scanDoors(client);
    }

    /**
     * Records where starred mobs are standing, in room-local coordinates.
     *
     * <p>Hypixel puts the star on a separate invisible armour stand rather than on the mob itself, which is the
     * same thing Mob ESP already relies on - so these are the stands, and the stand is where the mob is. Without
     * this a rebuilt room is scenery: the geometry is right and nothing lives in it.
     */
    /**
     * Records every starred mob in the loaded world, into whichever room it actually stands in.
     *
     * <p>One sweep for the whole map rather than one per room. It used to take a room and walk the entity list
     * for it, which is a full sweep per room now that every room is scanned - and it decided whether a stand
     * belonged to that room by subtracting the room's origin and rejecting negatives, with NO UPPER BOUND. A
     * stand in the room to the +x or +z side produced positive coordinates that landed inside the array and was
     * filed under the wrong room. Asking {@code roomAtWorld} where the stand is cannot make that mistake, and
     * costs one sweep instead of one per room.
     */
    private static void captureMobSpawns(Minecraft client, DungeonLayout layout) {
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) {
                continue;
            }
            var custom = stand.getCustomName();
            if (custom == null || !custom.getString().contains(STAR)) {
                continue;
            }
            int room = layout.roomAtWorld(stand.getX(), stand.getZ());
            if (room < 0) {
                continue;
            }
            String name = layout.name(room);
            if (name == null || name.isBlank() || "Unknown".equals(name)) {
                continue;
            }
            int[] tiles = layout.tiles(room);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            int minGx = Integer.MAX_VALUE;
            int minGz = Integer.MAX_VALUE;
            for (int idx : tiles) {
                minGx = Math.min(minGx, idx % DungeonLayout.GRID);
                minGz = Math.min(minGz, idx / DungeonLayout.GRID);
            }
            var origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
            int lx = (int) Math.floor(stand.getX()) - (origin.getX() - RoomLibrary.TILE / 2);
            int ly = (int) Math.floor(stand.getY());
            int lz = (int) Math.floor(stand.getZ()) - (origin.getZ() - RoomLibrary.TILE / 2);
            if (lx < 0 || lz < 0 || ly < RoomLibrary.MIN_Y || ly > RoomLibrary.MAX_Y) {
                continue;
            }
            RoomLibrary.recordMobSpawn(name, lx, ly, lz, "STARRED");
        }
    }

    /** Hypixel's starred-mob marker, the same character Mob ESP matches on. */
    private static final String STAR = "✯";

    /**
     * Whether the user has touched the keyboard.
     *
     * <p>Polled across the whole keyboard rather than hooking one key, because the promise is "any key" and a
     * list of keys is a list someone will press something missing from. Modifier keys are included: holding
     * shift to sprint is still a person taking the controls back.
     */
    private static boolean anyKeyDown(Minecraft client) {
        return firstKeyDown(client) != -1;
    }

    /**
     * The first key found held, or -1.
     *
     * <p>Returns WHICH key rather than a yes/no so the stop message can name it. "Room Recorder OFF (key
     * pressed)" is a report; "OFF (W pressed)" is a diagnosis, and the difference between him having walked and
     * the feature having stopped itself was guesswork without it.
     */
    private static int firstKeyDown(Minecraft client) {
        var window = client.getWindow();
        if (window == null) {
            return -1;
        }
        // KeyUtil rather than raw GLFW: glfwGetKey rejects codes outside its keyboard range, and sweeping the
        // whole range is exactly how you hand it an invalid one.
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (KeyUtil.isKeyDown(window, key)) {
                return key;
            }
        }
        return -1;
    }

    /**
     * Clicks through Hypixel's "Undersized party!" warning, if it is open.
     *
     * <p>Found by the ITEM'S OWN TEXT rather than by a slot number. A slot index is a guess read off one
     * screenshot that breaks silently the day Hypixel moves it; "Click to play anyway!" is the thing the menu
     * exists to offer and is what a person reads to find it too.
     *
     * <p>Restricted to the container's own slots - never the player's inventory - so a coincidentally named
     * item in his hotbar can never be the thing this clicks.
     *
     * @return whether the confirm was clicked
     */
    private static boolean confirmUndersizedParty(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            confirmClickTick = -1;
            return false;
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        if (!title.toLowerCase(Locale.ROOT).contains("undersized party")) {
            confirmClickTick = -1;
            return false;
        }
        // Wait out a human-looking reaction before touching it, drawn fresh each time the menu appears so the
        // delay is never the same twice.
        if (confirmClickTick < 0) {
            confirmClickTick = tickCounter + CONFIRM_DELAY_MIN_TICKS
                    + JITTER.nextInt(CONFIRM_DELAY_MAX_TICKS - CONFIRM_DELAY_MIN_TICKS + 1);
            return false;
        }
        if (tickCounter < confirmClickTick) {
            return false;
        }

        AbstractContainerMenu menu = screen.getMenu();
        for (Slot slot : menu.slots) {
            if (client.player != null && slot.container == client.player.getInventory()) {
                continue;
            }
            if (!looksLikePlayAnyway(slot.getItem())) {
                continue;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.ROOM_RECORDER_MENU, screen)) {
                return false;
            }
            client.gameMode.handleContainerInput(menu.containerId, slot.index, 0,
                    ContainerInput.PICKUP, client.player);
            confirmClickTick = -1;
            say("undersized party - playing anyway");
            return true;
        }
        return false;
    }

    /** The confirm item: its name or lore offers to play anyway. */
    private static boolean looksLikePlayAnyway(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (CheatUtils.plainName(stack).toLowerCase(Locale.ROOT).contains("play anyway")) {
            return true;
        }
        for (String line : CheatUtils.lore(stack)) {
            if (line.toLowerCase(Locale.ROOT).contains("play anyway")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The command that actually opens a floor.
     *
     * <p>killer560 (2026-09-28): "it cannot just do /f7 it needs to do the joininstance one." Exactly right, and
     * the bug was mine: {@code /f7} is THIS MOD'S OWN client-side shortcut (see
     * {@link com.killer560.hub.commandshortcuts.CommandShortcutsFeature}), not a Hypixel command.
     * {@code ServerCommands.toServer} sends below the client dispatcher on purpose - that is how it avoids the
     * recursion that forwarding a self-registered name causes - so it put the literal text "/f7" in front of a
     * server that has never heard of it, and the loop sat waiting for a dungeon that was never queued.
     *
     * <p>Read off {@code Shortcut.F7} rather than written out again, so the id has ONE definition. That enum is
     * also where the provenance lives: it was taken from this mod's shipped {@code /joininstance} usage and
     * cross-checked against NoammAddons, not guessed.
     */
    private static String joinFloorCommand() {
        return "joininstance "
                + com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Shortcut.F7.instanceId;
    }

    /** Ticks for a wait, with a few per cent of jitter so the loop is not a metronome. */
    private static int seconds(double s) {
        double wobble = 1.0 + (JITTER.nextDouble() * 2.0 - 1.0) * VARIANCE;
        return Math.max(1, (int) Math.round(s * 20.0 * wobble));
    }

    /**
     * Ticks before a command that changes server, on killer560's rule (2026-09-28): "a minimum of 1s delay with
     * a random amount added after as well".
     *
     * <p>A floor plus an addition rather than a percentage of the wait, because the risk being managed is not
     * the length of the pause but how tightly a swap follows whatever came before it. A percentage of a short
     * wait is still a short wait; a floor is a floor.
     */
    private static int swapDelay(double baseSeconds) {
        double base = Math.max(1.0, baseSeconds);
        return Math.max(20, (int) Math.round((base + JITTER.nextDouble() * SWAP_EXTRA_MAX_SECONDS) * 20.0));
    }

    private static void say(String message) {
        try {
            ModChat.send("Room Recorder", ModChat.text(message));
        } catch (Throwable ignored) {
            // no player yet
        }
    }
}
