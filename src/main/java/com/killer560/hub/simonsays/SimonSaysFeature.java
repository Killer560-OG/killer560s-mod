package com.killer560.hub.simonsays;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7/M7 boss-fight Simon Says solver.
 * <p>
 * The real device layout and detection logic below is ported from Odin's and QUOI's own confirmed,
 * compiling implementations for this exact Minecraft version (both pin {@code minecraft_version=26.1.2}
 * in their {@code gradle.properties}) - not guessed. The button grid is a 4x4 wall at x=110 (stone
 * buttons) with a matching sea-lantern wall at x=111, y in [120,123], z in [92,95], with the device's
 * start button at (110, 121, 91).
 * <p>
 * This mod has no {@code BlockUpdateEvent}-style hook (Odin/QUOI each define their own via a Mixin this
 * codebase hasn't added, deliberately - see this mod's standing "no unconfirmed Mixin target" rule), so
 * detection here is done by polling {@link net.minecraft.world.level.Level#getBlockState} once a client
 * tick and diffing against the previous tick's snapshot, same safe mixin-free technique the original
 * diagnostic-only version of this class used. 20Hz polling is far finer than a human's real click cadence.
 */
public final class SimonSaysFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-simonsays");

    private static final BlockPos START_BUTTON = new BlockPos(110, 121, 91);
    private static final double ACTIVE_RANGE_SQ = 30.0 * 30.0;

    // "SS 3/5" - deliberately the same wire format Odin's own announceProgress uses (its real chat call
    // is literally `pc SS ${clickInOrder.size}/5`), so this mod's party progress tracker also understands
    // teammates running Odin or QUOI, not just other killer560s-mod users.
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(?:^|[:>\\]]\\s*)(\\S+?)\\s*:\\s*SS (\\d+)/(\\d+)");
    private static final Pattern PROGRESS_PATTERN_SIMPLE = Pattern.compile("SS (\\d+)/(\\d+)");

    private static final List<BlockPos> GRID_LANTERNS = buildGrid(111);
    private static final List<BlockPos> GRID_BUTTONS = buildGrid(110);

    private static List<BlockPos> buildGrid(int x) {
        List<BlockPos> list = new ArrayList<>();
        for (int y = 120; y <= 123; y++) {
            for (int z = 92; z <= 95; z++) {
                list.add(new BlockPos(x, y, z));
            }
        }
        return list;
    }

    // --- solve state (Odin's real, confirmed sequence-detection logic, ported) ---
    private static final List<BlockPos> clickInOrder = new ArrayList<>();
    private static int clickNeeded = 0;
    private static boolean firstPhase = true;
    // Real bug found and fixed (2026-09-14): this mod's port of the firstPhase reveal-order quirk
    // (size 2 -> reverse, size 3 -> drop the middle) only ever cleared firstPhase after a FULL
    // successful click-through - Odin's own real code instead clears it on a real TIMEOUT once the
    // reveal flash settles (see below), independent of whether you've clicked anything yet. Without
    // that timeout, every subsequent lantern reveal past the 3rd kept re-triggering the same
    // size-3-drops-the-middle correction forever (add a 4th -> size 3 again -> drop again -> stays at
    // 2), which is exactly the real symptom killer560 reported: "only keeping 2 highlighted" and
    // highlights "moving off early." lastLanternChangeTick counts ticks since the last real lantern
    // step was recorded; once 10 ticks pass with the grid back to mostly real buttons (not still mid-
    // flash), firstPhase clears for the rest of this device's reveal, matching Odin's own real logic.
    private static int lastLanternChangeTick = -1;
    private static BlockState lastStartButtonState = null;
    private static final Map<BlockPos, BlockState> lastGridStates = new HashMap<>();
    private static boolean wasActive = false;
    private static boolean wasGridReset = false;
    private static long solveStartedAtMs = 0L;

    // --- reset keybind ---
    private static boolean resetKeyWasDown = false;

    // --- auto-start (skip) pacing ---
    private static boolean autoStartRunning = false;
    private static int autoStartClicksSent = 0;
    private static long autoStartNextClickAtMs = 0L;
    private static long autoStartDeadlineMs = 0L;

    // --- auto-solve (no-rotate) pacing ---
    private static long lastAutoClickAtMs = 0L;
    private static BlockPos lastAutoClickedPos = null;

    // --- trigger bot debounce ---
    private static BlockPos lastTriggerBotTarget = null;

    // --- party progress tracker: sender name -> progress ---
    private static final Map<String, PartyProgress> partyProgress = new HashMap<>();

    // --- legacy diagnostic logger state ---
    private static Map<BlockPos, BlockState> diagnosticLastStates = new HashMap<>();
    private static boolean diagnosticWasActive = false;

    private SimonSaysFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(SimonSaysFeature::onWorldRender);
    }

    private static boolean isDeviceInRange(Minecraft client) {
        return DungeonState.isF7OrM7() && client.player != null
                && client.player.distanceToSqr(Vec3.atCenterOf(START_BUTTON)) <= ACTIVE_RANGE_SQ;
    }

    /** For {@code SimonSaysMisclickMixin} - "Prevent Misclicks", killer560's own request, same real
     *  behavior as Odin's own "Block Wrong Clicks" toggle: only blocks a real click on one of the 16
     *  main grid buttons that ISN'T the one you actually need next, and shift always overrides it (so a
     *  manual "click it anyway" is never fully locked out). Never blocks the start button, and never
     *  blocks anything once the device isn't actively being tracked - this is a safety net during a
     *  real attempt, not a general block-interaction filter. */
    public static boolean shouldBlockClick(BlockPos pos, boolean shiftDown) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isPreventMisclicksEnabled() || !wasActive || shiftDown) {
            return false;
        }
        if (!GRID_BUTTONS.contains(pos)) {
            return false;
        }
        if (clickNeeded >= clickInOrder.size()) {
            return true;
        }
        BlockPos correctButton = clickInOrder.get(clickNeeded).west();
        return !pos.equals(correctButton);
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();

        if (cfg.isDiagnosticLoggingEnabled() && raw.toLowerCase(Locale.ROOT).contains("simon says")) {
            LOGGER.info("[SimonSays] Chat line mentioning Simon Says: \"{}\"", raw);
        }

        if (!cfg.isPartyProgressTrackerEnabled()) {
            return;
        }
        Matcher m = PROGRESS_PATTERN.matcher(raw);
        String sender;
        int progress;
        int total;
        if (m.find()) {
            sender = m.group(1);
            progress = Integer.parseInt(m.group(2));
            total = Integer.parseInt(m.group(3));
        } else {
            Matcher simple = PROGRESS_PATTERN_SIMPLE.matcher(raw);
            if (!simple.find()) {
                return;
            }
            sender = raw.length() > 20 ? raw.substring(0, 20).trim() : raw.trim();
            progress = Integer.parseInt(simple.group(1));
            total = Integer.parseInt(simple.group(2));
        }
        long now = System.currentTimeMillis();
        PartyProgress p = partyProgress.computeIfAbsent(sender, s -> new PartyProgress());
        if (progress <= 1 || p.total != total) {
            p.startedAtMs = now;
        }
        p.progress = progress;
        p.total = total;
        p.lastUpdateMs = now;
    }

    // ------------------------------------------------------------------
    // Tick: detection, auto-start pacing, auto-solve pacing, trigger bot, reset key
    // ------------------------------------------------------------------

    private static void tick() {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean active = cfg.isEnabled() && client.player != null && client.level != null && isDeviceInRange(client);

        tickResetKeybind(client, cfg);
        tickLegacyDiagnostics(cfg, client, active);

        if (!active) {
            if (wasActive) {
                LOGGER.info("[SimonSays] Left device range/floor - clearing solve state.");
                resetSolveState();
                lastStartButtonState = null;
            }
            wasActive = false;
            return;
        }
        if (!wasActive) {
            // Missing before 2026-09-14 - only the "left" transition was logged, so a real test log
            // could never distinguish "never got in range" from "was in range the whole time but
            // detected nothing" (a real question that came up investigating a p3sim.net report).
            LOGGER.info("[SimonSays] Entered device range on F7/M7 at distance {} - now watching for grid changes.",
                    String.format(Locale.US, "%.1f", Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(START_BUTTON)))));
            // A fresh encounter with the device - one of firstPhase's two real trigger points (see
            // resetSolveState's doc comment), matching Odin's own LevelEvent.Load reset.
            firstPhase = true;
        }
        wasActive = true;

        tickStartButton(client);

        detectGridChanges(client, cfg);
        tickAutoStart(client, cfg);
        tickAutoSolveAndTriggerBot(client, cfg);
    }

    private static void tickResetKeybind(Minecraft client, SimonSaysConfig cfg) {
        if (cfg.getResetKeyCode() < 0 || client.getWindow() == null) {
            resetKeyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), cfg.getResetKeyCode());
        if (down && !resetKeyWasDown) {
            LOGGER.info("[SimonSays] Manual reset via keybind.");
            resetSolveState();
            if (cfg.isAutoSendResetMessage() && client.player != null) {
                client.player.connection.sendCommand("pc " + cfg.getResetMessageText());
            }
        }
        resetKeyWasDown = down;
    }

    private static void resetSolveState() {
        clickInOrder.clear();
        clickNeeded = 0;
        // Deliberately does NOT touch firstPhase - matching Odin's own real resetSolution(), which
        // never does either. Real bug found and fixed (2026-09-14): this used to force firstPhase=true
        // right here, unconditionally - but this method is also called on the routine "grid reset
        // detected" between EVERY round of a real multi-round device (the sequence genuinely grows and
        // replays from the top each round, like classic Simon Says), not just once per device. That
        // silently re-armed the reveal-order quirk (reverse at size 2, drop-the-middle at size 3) for
        // round 2 onward, scrambling a sequence that should never have gotten that correction again -
        // exactly killer560's report ("first one should always be the first one", seeing only the last
        // two entries highlighted). firstPhase is now set true ONLY at its own two real trigger points:
        // entering device range fresh, and a real start-button press (see below) - both matching Odin's
        // own explicit reset sites, never the general per-round reset.
        lastLanternChangeTick = -1;
        lastGridStates.clear();
        autoStartRunning = false;
        autoStartClicksSent = 0;
        lastAutoClickedPos = null;
        lastTriggerBotTarget = null;
        solveStartedAtMs = 0L;
    }

    /** Real start-button-press detection, ported from Odin's own {@code BlockUpdateEvent} check for
     *  {@code pos == startButton}. This is firstPhase's OTHER real trigger point besides entering
     *  device range fresh (see resetSolveState's doc comment) - a real press means the attempt is
     *  restarting from scratch (e.g. after a failure), so the reveal-order quirk needs to apply again
     *  for the new reveal that follows. */
    private static void tickStartButton(Minecraft client) {
        BlockState now = client.level.getBlockState(START_BUTTON);
        BlockState old = lastStartButtonState;
        lastStartButtonState = now;
        if (old == null) {
            return;
        }
        boolean nowPowered = now.is(Blocks.STONE_BUTTON) && now.getValue(BlockStateProperties.POWERED);
        boolean oldPowered = old.is(Blocks.STONE_BUTTON) && old.getValue(BlockStateProperties.POWERED);
        if (nowPowered && !oldPowered) {
            resetSolveState();
            firstPhase = true;
        }
    }

    /** Ported from Odin's real {@code BlockUpdateEvent} handler, adapted to tick-polling: a lantern
     *  going from lit (sea lantern) to dark (obsidian) records that position as the next step in the
     *  real sequence order; a button (x=110) transitioning to powered advances progress. The
     *  first-activation reveal quirk (size 2 -&gt; reverse, size 3 -&gt; reverse again + drop the
     *  middle one) is Hypixel's own real behavior, confirmed by Odin against a live run - not a guess. */
    private static void detectGridChanges(Minecraft client, SimonSaysConfig cfg) {
        for (BlockPos pos : GRID_LANTERNS) {
            BlockState now = client.level.getBlockState(pos);
            BlockState old = lastGridStates.put(pos, now);
            if (old == null) {
                continue;
            }
            if (now.is(Blocks.OBSIDIAN) && old.is(Blocks.SEA_LANTERN) && !clickInOrder.contains(pos)) {
                clickInOrder.add(pos.immutable());
                lastLanternChangeTick = 0;
                if (clickInOrder.size() == 1) {
                    solveStartedAtMs = System.currentTimeMillis();
                }
                if (firstPhase) {
                    if (clickInOrder.size() == 2) {
                        java.util.Collections.reverse(clickInOrder);
                    } else if (clickInOrder.size() == 3) {
                        clickInOrder.remove(clickInOrder.size() - 2);
                    }
                }
                if (cfg.isDiagnosticLoggingEnabled()) {
                    LOGGER.info("[SimonSays] Recorded step {} at {}", clickInOrder.size(), pos);
                }
            }
        }

        int airCount = 0;
        int stoneButtonCount = 0;
        for (BlockPos pos : GRID_BUTTONS) {
            BlockState now = client.level.getBlockState(pos);
            BlockState old = lastGridStates.put(pos, now);
            if (now.isAir()) {
                airCount++;
            }
            if (now.is(Blocks.STONE_BUTTON)) {
                stoneButtonCount++;
            }
            if (old == null) {
                continue;
            }
            boolean nowPowered = now.is(Blocks.STONE_BUTTON) && now.getValue(BlockStateProperties.POWERED);
            boolean oldPowered = old.is(Blocks.STONE_BUTTON) && old.getValue(BlockStateProperties.POWERED);
            if (nowPowered && !oldPowered) {
                onButtonPressed(pos, cfg, client);
            }
        }
        // Real timeout ported from Odin's own TickEvent.Server check (see this class's firstPhase field
        // doc comment) - the reveal-order quirk only ever applies during the initial flash; 10 ticks
        // after the last real lantern change, once the grid has settled back to mostly real buttons,
        // firstPhase clears for the rest of THIS device so later reveals stop getting truncated.
        if (firstPhase && lastLanternChangeTick >= 0) {
            lastLanternChangeTick++;
            if (lastLanternChangeTick > 10 && stoneButtonCount > 8) {
                firstPhase = false;
                if (cfg.isDiagnosticLoggingEnabled()) {
                    LOGGER.info("[SimonSays] Reveal flash settled - firstPhase quirk correction now off for this device.");
                }
            }
        }
        boolean gridReset = airCount > 8;
        // THE real bug behind "Simon Says does nothing on p3sim.net" (2026-09-14) - not just noisy
        // logging. This used to call resetSolveState() every single tick for as long as the grid
        // looked reset (16 air blocks), not just on the transition into that state. resetSolveState()
        // clears lastGridStates - so on almost every tick (since normally only a few of 16 slots are
        // lit at once, the other 8+ read as air and trip this), the "old" state needed to compare
        // against next tick got wiped before a real transition could ever be caught. A real p3sim.net
        // log proved it: a real button/lantern change was independently confirmed (by the separate
        // player-centered diagnostic below) at the exact same coordinates on the exact same tick this
        // logged "16 air blocks" and cleared the map - so the transition was always one tick too late to
        // compare against. Edge-triggered now, same pattern as the enter/leave device-range logging
        // above - lastGridStates only gets wiped once, on the real transition into a reset state.
        if (gridReset && !wasGridReset) {
            if (cfg.isDiagnosticLoggingEnabled()) {
                LOGGER.info("[SimonSays] Grid reset detected ({} air blocks).", airCount);
            }
            resetSolveState();
        }
        wasGridReset = gridReset;
    }

    private static void onButtonPressed(BlockPos buttonPos, SimonSaysConfig cfg, Minecraft client) {
        BlockPos lanternPos = buttonPos.east();
        int index = clickInOrder.indexOf(lanternPos);
        if (index < 0) {
            return;
        }
        clickNeeded = index + 1;
        // Real format ported from Odin's own announceProgress ("pc SS ${clickInOrder.size}/5") - only
        // sent on the LAST click of the current round (real Hypixel Simon Says is always exactly 5
        // rounds, round N has N steps, so clickInOrder.size() at round-completion IS the round number).
        // Killer560's explicit fix request (2026-09-14): this used to send on every single click
        // ("1/2 2/2 1/3...", the click index within the current round), not just once per round.
        if (cfg.isAnnounceProgress() && client.player != null && clickNeeded >= clickInOrder.size()) {
            client.player.connection.sendCommand("pc SS " + clickInOrder.size() + "/5");
        }
        if (clickNeeded >= clickInOrder.size()) {
            long tookMs = solveStartedAtMs > 0 ? System.currentTimeMillis() - solveStartedAtMs : 0;
            LOGGER.info("[SimonSays] Device completed in {} ms.", tookMs);
            resetSolveState();
            firstPhase = false;
        }
    }

    // ------------------------------------------------------------------
    // Auto-start (skip presets) - timer-with-variance pacing instead of a flat per-click delay
    // ------------------------------------------------------------------

    private static void tickAutoStart(Minecraft client, SimonSaysConfig cfg) {
        if (!cfg.isAutoStartEnabled()) {
            autoStartRunning = false;
            return;
        }
        long now = System.currentTimeMillis();
        int totalClicks = cfg.getSkipClicks(cfg.getAutoStartMode());

        if (!autoStartRunning) {
            return;
        }
        if (now >= autoStartDeadlineMs || autoStartClicksSent >= totalClicks) {
            LOGGER.info("[SimonSays] Auto-start finished ({} of {} clicks sent).", autoStartClicksSent, totalClicks);
            autoStartRunning = false;
            return;
        }
        if (now < autoStartNextClickAtMs) {
            return;
        }
        sendNoRotateInteract(client, START_BUTTON);
        autoStartClicksSent++;
        autoStartNextClickAtMs = now + cfg.getAutoStartClickDelayMs();
        if (cfg.isDiagnosticLoggingEnabled()) {
            LOGGER.info("[SimonSays] Auto-start click {}/{} sent.", autoStartClicksSent, totalClicks);
        }
    }

    /** Call from the GUI's "Start" button to begin the timer-paced auto-start sequence. */
    public static void beginAutoStart() {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        long now = System.currentTimeMillis();
        int variance = cfg.getClickTimerVarianceMs();
        long jitter = variance <= 0 ? 0 : (long) ((Math.random() * 2 - 1) * variance);
        autoStartDeadlineMs = now + cfg.getClickTimerTargetMs() + jitter;
        autoStartNextClickAtMs = now;
        autoStartClicksSent = 0;
        autoStartRunning = true;
        LOGGER.info("[SimonSays] Auto-start armed: mode={}, target clicks={}, window={}ms",
                cfg.getAutoStartMode(), cfg.getSkipClicks(cfg.getAutoStartMode()), cfg.getClickTimerTargetMs());
    }

    // ------------------------------------------------------------------
    // Auto-solve (no-rotate) + trigger bot
    // ------------------------------------------------------------------

    private static void tickAutoSolveAndTriggerBot(Minecraft client, SimonSaysConfig cfg) {
        if (clickNeeded >= clickInOrder.size()) {
            return;
        }
        if (cfg.isSkipCompatibility() && cfg.isAutoStartEnabled() && autoStartRunning) {
            return;
        }
        BlockPos nextLantern = clickInOrder.get(clickNeeded);
        BlockPos nextButton = nextLantern.west();

        if (cfg.isAutoSolveEnabled()) {
            long now = System.currentTimeMillis();
            boolean sameTarget = nextButton.equals(lastAutoClickedPos);
            long minDelay = sameTarget ? 400 : 0;
            if (now - lastAutoClickAtMs >= Math.max(150, minDelay)) {
                sendNoRotateInteract(client, nextButton);
                lastAutoClickAtMs = now;
                lastAutoClickedPos = nextButton;
            }
            return;
        }

        if (cfg.isTriggerBotEnabled() && client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(nextButton) && !nextButton.equals(lastTriggerBotTarget)) {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
            client.player.swing(InteractionHand.MAIN_HAND);
            lastTriggerBotTarget = nextButton;
        } else if (!(client.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(nextButton)) {
            lastTriggerBotTarget = null;
        }
    }

    /** Interacts with a block without needing the player's crosshair on it - the "no rotate" click
     *  killer560 asked for ("just like QUOI"), which sends the interact packet directly via a synthetic
     *  {@link BlockHitResult} instead of first turning the camera to aim. Real automation - callers must
     *  already be behind a {@code BuildVariant.CHEAT_FEATURES_ENABLED} check. */
    private static void sendNoRotateInteract(Minecraft client, BlockPos pos) {
        if (client.player == null || client.gameMode == null) {
            return;
        }
        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.EAST, pos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static void onWorldRender(LevelRenderContext context) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isSolverEnabled() || clickInOrder.isEmpty()) {
            return;
        }
        if (cfg.isSkipCompatibility() && cfg.isAutoStartEnabled() && autoStartRunning) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        for (int index = clickNeeded; index < clickInOrder.size(); index++) {
            BlockPos lanternPos = clickInOrder.get(index);
            double x = lanternPos.getX();
            double y = lanternPos.getY();
            double z = lanternPos.getZ();
            AABB box = new AABB(x + 0.05, y + 0.37, z + 0.3, x - 0.15, y + 0.63, z + 0.7);

            int colorArgb = switch (index - clickNeeded) {
                case 0 -> cfg.getFirstColor();
                case 1 -> cfg.getSecondColor();
                default -> cfg.getThirdColor();
            };
            float[] rgba = WorldRenderUtils.argbToFloats(colorArgb);

            switch (cfg.getStyle()) {
                case FILLED -> WorldRenderUtils.renderFilledBox(context, box, rgba[0], rgba[1], rgba[2], rgba[3]);
                case OUTLINE -> WorldRenderUtils.renderOutlineBox(context, box, rgba[0], rgba[1], rgba[2], 1f, 2f);
                case FILLED_OUTLINE -> {
                    WorldRenderUtils.renderFilledBox(context, box, rgba[0], rgba[1], rgba[2], rgba[3] * 0.5f);
                    WorldRenderUtils.renderOutlineBox(context, box, rgba[0], rgba[1], rgba[2], 1f, 2f);
                }
            }

            if (cfg.isNumberOverlay()) {
                // Real bug found and fixed (2026-09-14): this used to render at the full block's
                // center (x+0.5/y+0.5/z+0.5), NOT the highlight box's own center - killer560's report
                // ("the number thing isn't on") was very likely this number rendering ~0.55 blocks away
                // from the actual highlight, potentially clipped inside the wall behind the lantern.
                // Now uses the box's real center directly, matching "exact same spot as the highlight."
                renderNumber(context, box.getCenter().x, box.getCenter().y, box.getCenter().z,
                        index - clickNeeded + 1, cfg.getNumberScale());
            }
        }
    }

    /** Billboard text - the same "translate to the world position, rotate to face the camera, draw
     *  through the font" technique vanilla itself uses for entity name tags. */
    private static void renderNumber(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                      int number, float scaleMultiplier) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        var mainCamera = client.gameRenderer.getMainCamera();
        Vec3 cam = mainCamera.position();
        String text = String.valueOf(number);
        float scale = 0.02f * scaleMultiplier;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(mainCamera.rotation());
        poseStack.scale(-scale, -scale, scale);

        float width = font.width(text);
        int background = (int) (0.4f * 255f) << 24;
        font.drawInBatch(text, -width / 2f, 0f, 0xFFFFFFFF, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.NORMAL, background, 0xF000F0);

        poseStack.popPose();
    }

    // ------------------------------------------------------------------
    // Legacy diagnostic block-state logger (kept as an optional supplementary data source)
    // ------------------------------------------------------------------

    private static void tickLegacyDiagnostics(SimonSaysConfig cfg, Minecraft client, boolean deviceActive) {
        boolean active = cfg.isDiagnosticLoggingEnabled() && DungeonState.isInDungeon()
                && client.player != null && client.level != null;

        if (!active) {
            if (diagnosticWasActive) {
                diagnosticLastStates = new HashMap<>();
            }
            diagnosticWasActive = false;
            return;
        }
        if (!diagnosticWasActive) {
            LOGGER.info("[SimonSays] Diagnostic logging started - watching a {}x{}x{} box around you.",
                    cfg.getHorizontalRadius() * 2 + 1, cfg.getVerticalRadius() * 2 + 1, cfg.getHorizontalRadius() * 2 + 1);
            diagnosticLastStates = new HashMap<>();
        }
        diagnosticWasActive = true;

        BlockPos center = client.player.blockPosition();
        int hr = cfg.getHorizontalRadius();
        int vr = cfg.getVerticalRadius();
        Map<BlockPos, BlockState> currentStates = new HashMap<>();

        for (int dx = -hr; dx <= hr; dx++) {
            for (int dy = -vr; dy <= vr; dy++) {
                for (int dz = -hr; dz <= hr; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = client.level.getBlockState(pos);
                    currentStates.put(pos, state);
                    BlockState previous = diagnosticLastStates.get(pos);
                    if (previous != null && !previous.equals(state)) {
                        LOGGER.info("[SimonSays] Block changed at {}: {} -> {}", pos, previous, state);
                    }
                }
            }
        }
        diagnosticLastStates = currentStates;
    }

    // ------------------------------------------------------------------
    // Party progress tracker HUD
    // ------------------------------------------------------------------

    private static final class PartyProgress {
        int progress;
        int total = 5;
        long startedAtMs;
        long lastUpdateMs;
    }

    public static final class PartyProgressHudElement implements HudElement {
        @Override
        public String id() {
            return "simonsays_party_progress";
        }

        @Override
        public String displayName() {
            return "Simon Says Party Progress";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 260;
        }

        @Override
        public int width() {
            return 180;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, visibleEntries().size());
        }

        private List<Map.Entry<String, PartyProgress>> visibleEntries() {
            long now = System.currentTimeMillis();
            List<Map.Entry<String, PartyProgress>> entries = new ArrayList<>();
            for (Map.Entry<String, PartyProgress> e : partyProgress.entrySet()) {
                if (now - e.getValue().lastUpdateMs < 30_000 && e.getValue().progress < e.getValue().total) {
                    entries.add(e);
                }
            }
            return entries;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!SimonSaysConfig.getInstance().isPartyProgressTrackerEnabled() || Minecraft.getInstance().screen != null) {
                return;
            }
            long now = System.currentTimeMillis();
            int lineY = y;
            for (Map.Entry<String, PartyProgress> e : visibleEntries()) {
                PartyProgress p = e.getValue();
                String eta = "";
                long elapsed = now - p.startedAtMs;
                if (p.progress > 0 && elapsed > 0) {
                    double msPerStep = elapsed / (double) p.progress;
                    long remainingMs = (long) (msPerStep * (p.total - p.progress));
                    eta = String.format(Locale.US, " (~%.1fs)", remainingMs / 1000.0);
                }
                graphics.text(Minecraft.getInstance().font,
                        String.format(Locale.US, "%s: %d/%d%s", e.getKey(), p.progress, p.total, eta),
                        x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}
