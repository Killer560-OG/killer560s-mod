package com.killer560.hub.bloodcamp;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * "Kill Popup" - killer560 (2026-09-21): "make it so it gives a hud popup saying kill as an option for time to kill
 * to skip dialogue."
 * <p>
 * The signal is not the mobs, it is the Watcher's own schedule. He greets the room with one of
 * {@link #BLOOD_OPEN} when the blood door opens, and says {@link #BLOOD_MOVE} ("Let's see how you can handle this.")
 * when he sends the last wave out. From the gap between those two lines the moment he MOVES can be predicted, and
 * killing the remaining mobs on that moment is what skips his closing dialogue. The bucket table below is the same
 * one SkyHanni's own {@code features/dungeon/BloodTimer} and Odin's own {@code dungeon/BloodCamp} use (both
 * javap-read on the real jars, 2026-09-21, and they agree constant for constant):
 * <pre>
 *   gap 31-34s -&gt; he moves 36s after the greeting     gap 25-28s -&gt; 30s
 *   gap 28-31s -&gt; 33s                                 gap 22-25s -&gt; 27s
 *   gap  1-22s -&gt; 24s                                 anything else -&gt; gap + 3s (Odin's generalisation)
 * </pre>
 * i.e. the move lands on the next 3-second boundary a couple of seconds after the last wave goes out. SkyHanni fires
 * its own "Kill Blood" title 150 ms (3 ticks) before that instant; that lead is {@link BloodCampConfig#getKillPopupLeadTicks()}
 * here so killer560 can tune it against his own ping instead of living with a hardcoded number.
 * <p>
 * Timed in {@code ClientLevel#getGameTime()} ticks, not wall clock, for the same reason the rest of this feature is:
 * it is the one clock the Watcher and this mod share. Unlike SkyHanni there is no separate server-time mark to take a
 * client/server drift correction from, so the prediction carries whatever drift the tick clock has - flagged, not
 * fixed, because it cannot be checked without a live blood room.
 * <p>
 * Also owns {@link #isFirstSpawns()}: the Watcher's first wave settles 40 ticks slower than every later one, and that
 * "first" state ends exactly on the {@link #BLOOD_MOVE} line (Odin sets its own {@code firstSpawns = false} on that
 * same line). {@link BloodCampFeature} used to clear its own copy after the first tracked mob instead, which made
 * every other mob of the first wave count down two seconds early.
 */
public final class BloodCampMoveTimer {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bloodcamp");

    /** The Watcher's greeting when the blood door opens - verbatim from Odin's own {@code BLOOD_START_REGEX}
     *  (the same list {@code splittimers.SplitTimersFeature} already matches on). */
    private static final Pattern BLOOD_OPEN = Pattern.compile(
            "^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|"
                    + "Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|"
                    + "You've managed to scratch and claw your way here, eh\\?|"
                    + "I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|"
                    + "Things feel a little more roomy now, eh\\?)$");

    /** The last wave going out - Odin's own {@code BLOOD_MOVE_REGEX}. */
    private static final Pattern BLOOD_MOVE =
            Pattern.compile("^\\[BOSS] The Watcher: Let's see how you can handle this\\.$");

    /** How long "KILL" stays up once the window opens - SkyHanni's own title length. */
    private static final int KILL_HOLD_TICKS = 30;
    /** The countdown only appears this close to the move, so the popup is not a permanent fixture. */
    private static final int COUNTDOWN_SHOW_TICKS = 200;

    private static long bloodOpenTick = -1L;
    private static long moveAtTick = -1L;
    private static boolean firstSpawns = true;

    private BloodCampMoveTimer() {
    }

    static void register() {
        // ChatObserver, not Fabric's CHAT/GAME: Odin/NoammAddons/Skyblocker cancel and re-add boss lines, which
        // skips Fabric's events for everyone else (see ChatObserver's class doc).
        ChatObserver.subscribe(BloodCampMoveTimer::onChat);
    }

    /** True until the Watcher sends the last wave out - his first wave settles 40 ticks slower. */
    static boolean isFirstSpawns() {
        return firstSpawns;
    }

    static void reset() {
        bloodOpenTick = -1L;
        moveAtTick = -1L;
        firstSpawns = true;
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message).trim();
        if (plain.isEmpty() || !plain.startsWith("[BOSS] The Watcher:")) {
            return;
        }
        long now = nowTick();
        if (now < 0) {
            return;
        }
        if (BLOOD_OPEN.matcher(plain).matches()) {
            bloodOpenTick = now;
            moveAtTick = -1L;
            firstSpawns = true;
            LOGGER.info("[BloodCamp] Watcher greeting seen at tick {} - move prediction armed.", now);
        } else if (BLOOD_MOVE.matcher(plain).matches()) {
            firstSpawns = false;
            if (bloodOpenTick < 0) {
                // Joined mid-blood, or the greeting was eaten by another mod's chat rewrite.
                LOGGER.info("[BloodCamp] Watcher's last-wave line seen with no greeting - no move prediction.");
                return;
            }
            double gapSeconds = (now - bloodOpenTick) / 20.0;
            double moveAtSeconds = moveAtSeconds(gapSeconds);
            moveAtTick = bloodOpenTick + Math.round(moveAtSeconds * 20.0);
            LOGGER.info("[BloodCamp] Last wave out {} s after the greeting -> Watcher moves at {} s ({} ticks away).",
                    String.format(Locale.US, "%.2f", gapSeconds),
                    String.format(Locale.US, "%.0f", moveAtSeconds), moveAtTick - now);
        }
    }

    /** Seconds after the greeting at which the Watcher moves - see this class's own doc for the source. */
    private static double moveAtSeconds(double gapSeconds) {
        if (gapSeconds >= 31.0 && gapSeconds <= 34.0) {
            return 36.0;
        }
        if (gapSeconds >= 28.0 && gapSeconds <= 31.0) {
            return 33.0;
        }
        if (gapSeconds >= 25.0 && gapSeconds <= 28.0) {
            return 30.0;
        }
        if (gapSeconds >= 22.0 && gapSeconds <= 25.0) {
            return 27.0;
        }
        if (gapSeconds >= 1.0 && gapSeconds <= 22.0) {
            return 24.0;
        }
        return gapSeconds + 3.0;
    }

    private static long nowTick() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? -1L : client.level.getGameTime();
    }

    /** Ticks until the Watcher is predicted to move, or {@link Double#NaN} while there is no prediction. */
    private static double ticksUntilMove() {
        long now = nowTick();
        if (moveAtTick < 0 || now < 0) {
            return Double.NaN;
        }
        return (double) (moveAtTick - now);
    }

    private static boolean relevant() {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        return cfg.isEnabled() && cfg.isKillPopup() && DungeonState.isInDungeon();
    }

    /** The Watcher's dialogue is the same on every floor, so this one is not restricted to F7/M7 the way the
     *  mob tracking is. Registered into the HUD editor by {@code Killer560ModClient}; drawn in-game by
     *  {@link BloodCampFeature}'s own Fabric HUD layer. */
    static final HudElement HUD = new HudElement() {
        @Override
        public String id() {
            return "blood_camp_kill";
        }

        @Override
        public String displayName() {
            return "Blood Camp Kill Popup";
        }

        @Override
        public int defaultX() {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            return (int) (Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.38f);
        }

        @Override
        public int width() {
            return 76;
        }

        @Override
        public int height() {
            return 14;
        }

        @Override
        public boolean isRelevantNow() {
            return relevant();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean preview = HudVisibility.editorOpen();
            String text;
            int color;
            if (preview) {
                text = "KILL";
                color = 0xFFFF5555;
            } else {
                if (!relevant() || HudVisibility.hidesHud()) {
                    return;
                }
                double ticks = ticksUntilMove() - BloodCampConfig.getInstance().getKillPopupLeadTicks();
                if (Double.isNaN(ticks) || ticks > COUNTDOWN_SHOW_TICKS || ticks < -KILL_HOLD_TICKS) {
                    return;
                }
                if (ticks > 0) {
                    text = String.format(Locale.US, "Kill in %.1fs", ticks / 20.0);
                    color = 0xFF000000 | ModChat.LIGHT_ORANGE;
                } else {
                    text = "KILL";
                    color = 0xFFFF5555;
                }
            }
            Minecraft client = Minecraft.getInstance();
            graphics.fill(x, y, x + width(), y + height(), 0x66000000);
            graphics.centeredText(client.font, text, x + width() / 2, y + 3, color);
        }
    };
}
