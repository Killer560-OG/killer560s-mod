package com.killer560.hub.autokick;

import com.killer560.hub.autokick.AutoKickConfig.ActionMode;
import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.splittimers.SplitTimersFeature;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Auto Kick - killer560's own request, verbatim: "Also create auto kick. I really Like Odins. The kick
 * based off of timed comp of a floor and whatnot." Odin's version removes party members once a run has
 * clearly missed its target time for the current floor, so the party can requeue instead of grinding out
 * a run that was never going to make it. This ports that behaviour on top of what this mod already has,
 * rather than building any new detection:
 * <ul>
 * <li><b>Run clock / elapsed time:</b> {@link SplitTimersFeature#getRunStartedAtMs()} /
 *     {@link SplitTimersFeature#getElapsedMs()} (added alongside this feature - the class already tracked
 *     the run clock internally for its own splits, it just had no public "when did this run start"
 *     getter yet).
 * <li><b>Floor:</b> {@link DungeonState#getFloor()} / {@link DungeonState#isInDungeon()} - the same real
 *     sidebar-scoreboard detection every other dungeon feature in this mod reads.
 * <li><b>Party roster:</b> {@link PartyTracker#teammates()} - "everyone known to be in your party except
 *     you", the same list {@code partycommands.PartyCommandsFeature} matches kick targets against.
 * <li><b>Sending the kick:</b> {@link ServerCommands#toServer(String)}, NOT
 *     {@code connection.sendCommand} - see that class's doc for the real StackOverflow crash
 *     (/fl, /ah, /bz) that using the wrong one caused elsewhere in this mod. {@code partycommands}'
 *     {@code execute()} has the same shape (send the command, then log locally via {@link ModChat}) and
 *     this mirrors it on purpose.
 * </ul>
 * <p>
 * <b>Gating: NOT behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.</b> Auto Kick sends the exact same
 * "p kick &lt;name&gt;" command {@code partycommands.PartyCommandsFeature}'s own Kick command already
 * sends with no such gate (only {@code allowDestructive} - a feature-local opt-in, not the cheat-build
 * split). That is the closest comparable social automation in this repo, and killer560's own instructions
 * for this feature specifically ask to be consistent with how it is gated. The reasoning: kicking a
 * teammate is party management, not an unfair advantage over other players the way an auto-clicker or a
 * triggerbot is (which IS what {@code BuildVariant.CHEAT_FEATURES_ENABLED} exists to separate out - see
 * e.g. {@code AutoSellConfig}, {@code CroesusConfig}). It still acts on real people automatically, which is
 * exactly why every other safety rule below applies regardless of build variant.
 * <p>
 * <b>Safety rules (killer560's own instructions for this feature, followed literally):</b>
 * <ul>
 * <li>Master toggle defaults OFF, default action is {@link ActionMode#WARN_ONLY} - see
 *     {@link AutoKickConfig}.
 * <li><b>Never kick the local player.</b> {@link PartyTracker#teammates()} already excludes yourself by
 *     definition ("everyone known to be in your party except you"); {@link #buildKickTargets} re-checks
 *     it anyway before every single name, belt-and-suspenders.
 * <li><b>Never kick the party leader if that would disband.</b> This mod already has one leader tracker
 *     ({@code partycommands.PartyLeaderTracker}) - not reused here on purpose, for two reasons. First, it
 *     is only ever fed while Party Commands itself is enabled ({@code ChatCommandsFeature#onMessage} only
 *     calls its {@code onServerLine} when {@code PartyCommandsConfig.isEnabled()}), so a killer560 who
 *     runs Auto Kick without Party Commands turned on would get no leader data from it at all. Second, and
 *     more important, its own class doc is explicit that it is "a convenience gate, not the security
 *     boundary" - real enforcement is Hypixel's server, which only lets the actual party leader's account
 *     run "/p kick" at all. Auto Kick leans on that exact same real boundary rather than adding a second,
 *     independently-fed leader guess that could itself be wrong: whichever account is running this code is
 *     the only one whose kicks can ever actually go through, and that account is never a kick target in
 *     the first place (the point directly above). A run led by someone else's client simply can't be
 *     kicked into by this feature - Hypixel refuses the command before it does anything.
 * <li><b>Never act outside a dungeon.</b> Gated on {@link DungeonState#isInDungeon()} every tick.
 * <li><b>Never act more than once per run.</b> {@link #decidedForRunStartedAtMs} latches the run this
 *     feature already made its one decision for; a new run (a new {@link SplitTimersFeature#getRunStartedAtMs()}
 *     value) is the only thing that resets it.
 * <li><b>Rate-limited.</b> Each individual "p kick" - not the warn-only path, which sends no command at
 *     all - has to pass both {@link ActionGate#tryAct(ActionGate.Actor)} (one automated interaction per
 *     client tick, mod-wide) AND this feature's own {@link #KICK_MIN_GAP_MS} spacing on top, so "Kick All"
 *     on a full party never sends five kicks in five ticks.
 * </ul>
 * <p>
 * <b>No default target times are shipped</b> - see {@link AutoKickConfig}'s class doc for why. Every
 * floor starts at 0 (disabled) until killer560 sets his own numbers.
 */
public final class AutoKickFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autokick");

    /** Same shape {@code partycommands.PartyCommandsFeature} validates typed names against before they
     *  ever reach a command string. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** Wall-clock floor between successive "p kick" sends, on top of whatever {@link ActionGate} adds -
     *  killer560's own instructions ask for "one kick per N ticks minimum". 3s (60 client ticks worth of
     *  spacing, checked against {@code System.currentTimeMillis()} rather than a tick counter - same
     *  wall-clock approach {@code ActionGate}'s own spacing floor uses): enough that "Kick All" on a
     *  4-stack reads as a deliberate sequence of party-management commands, not a burst. */
    private static final long KICK_MIN_GAP_MS = 3_000L;

    /** The {@link SplitTimersFeature#getRunStartedAtMs()} value Auto Kick already decided for (warned or
     *  started kicking), so a floor whose target was already missed once this run can't trigger a second
     *  time. 0 = no decision made yet for whatever run is currently running (or no run at all). Reset the
     *  moment the run clock's own start timestamp changes, i.e. a genuinely new run. */
    private static long decidedForRunStartedAtMs = 0L;

    /** Names still queued to kick this run ({@link ActionMode#KICK_ALL}/{@link ActionMode#KICK_SPECIFIC}).
     *  Drained one at a time, rate-limited, across however many ticks it takes. Empty under
     *  {@link ActionMode#WARN_ONLY} - that mode never queues a command. */
    private static final List<String> pendingKicks = new ArrayList<>();

    private static long lastKickAtMs = 0L;

    private AutoKickFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        AutoKickConfig cfg = AutoKickConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon()) {
            // Master toggle off, "Skyblock Only" gate closed, or simply not in a dungeon right now -
            // never act outside a dungeon, per killer560's own instructions for this feature.
            pendingKicks.clear();
            return;
        }

        long runStartedAtMs = SplitTimersFeature.getRunStartedAtMs();
        if (runStartedAtMs <= 0L) {
            // In a dungeon, but the run clock hasn't started yet (still on the countdown / loading in).
            return;
        }
        if (runStartedAtMs != decidedForRunStartedAtMs && !pendingKicks.isEmpty()) {
            // Defensive only - decidedForRunStartedAtMs is only ever set alongside a non-empty
            // pendingKicks in the same call, so these can't actually disagree. Kept so a future change to
            // either can't silently carry a stale kick queue into a run it was never queued for.
            pendingKicks.clear();
        }

        if (decidedForRunStartedAtMs != runStartedAtMs) {
            maybeDecide(cfg, runStartedAtMs);
        }
        if (!pendingKicks.isEmpty()) {
            drainPendingKicks();
        }
    }

    /** Checked once per tick until this run's floor target is exceeded (or the run ends) - the single
     *  point where Auto Kick can ever decide to warn or queue kicks for the CURRENT run. */
    private static void maybeDecide(AutoKickConfig cfg, long runStartedAtMs) {
        Floor floor = Floor.fromRaw(DungeonState.getFloor());
        if (floor == null) {
            // Unrecognised/unsupported floor (Entrance, or a value DungeonState can't parse) - nothing to
            // compare a target time against.
            return;
        }
        int targetSeconds = cfg.getTargetSeconds(floor);
        if (targetSeconds <= 0) {
            // 0 = disabled for this floor (the shipped default for every floor - see AutoKickConfig).
            return;
        }
        long elapsedMs = System.currentTimeMillis() - runStartedAtMs;
        if (elapsedMs < targetSeconds * 1000L) {
            return;
        }

        // Target missed - this is the ONE decision Auto Kick makes for this run (see decidedForRunStartedAtMs).
        decidedForRunStartedAtMs = runStartedAtMs;
        ActionMode mode = cfg.getMode();
        List<String> targets = mode == ActionMode.WARN_ONLY ? List.of() : buildKickTargets(cfg, mode);

        String elapsedStr = formatMmSs(elapsedMs);
        String targetStr = formatMmSs(targetSeconds * 1000L);
        LOGGER.info("[AutoKick] {} target ({}) exceeded - elapsed {} - mode={} targets={}",
                floor.label(), targetStr, elapsedStr, mode, targets);

        switch (mode) {
            case WARN_ONLY -> ModChat.send("Auto Kick",
                    ModChat.value(floor.label()), ModChat.text(" target ("), ModChat.value(targetStr),
                    ModChat.text(") exceeded - elapsed "), ModChat.value(elapsedStr), ModChat.dim(". Warn only, no one was kicked."));
            case KICK_ALL, KICK_SPECIFIC -> {
                if (targets.isEmpty()) {
                    // Nothing valid to kick (empty party, or KICK_SPECIFIC's list matched no current
                    // teammate) - still say so, same as Odin would rather than silently doing nothing.
                    ModChat.send("Auto Kick",
                            ModChat.value(floor.label()), ModChat.text(" target ("), ModChat.value(targetStr),
                            ModChat.text(") exceeded - elapsed "), ModChat.value(elapsedStr),
                            ModChat.dim(". No valid teammates to kick."));
                    return;
                }
                ModChat.send("Auto Kick",
                        ModChat.value(floor.label()), ModChat.text(" target ("), ModChat.value(targetStr),
                        ModChat.text(") exceeded - elapsed "), ModChat.value(elapsedStr),
                        ModChat.text(". Kicking "), ModChat.value(String.join(", ", targets)), ModChat.dim("."));
                pendingKicks.addAll(targets);
            }
        }
    }

    /** @return the real, currently-valid kick targets for {@code mode} - never the local player (belt and
     *  suspenders on top of {@link PartyTracker#teammates()} already excluding it; see this class's doc
     *  for why that's also the party-leader safety rule). {@link ActionMode#KICK_SPECIFIC} additionally
     *  requires each name to be both validly-shaped and an actual current teammate, so a stale or
     *  mistyped name in the settings box never turns into a "player not found" command sent for nothing. */
    private static List<String> buildKickTargets(AutoKickConfig cfg, ActionMode mode) {
        List<String> teammates = PartyTracker.teammates();
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();

        List<String> out = new ArrayList<>();
        if (mode == ActionMode.KICK_ALL) {
            for (String name : teammates) {
                if (self == null || !name.equalsIgnoreCase(self)) {
                    out.add(name);
                }
            }
            return out;
        }
        // KICK_SPECIFIC
        for (String raw : cfg.getSpecificMembers().split(",")) {
            String typed = raw.trim();
            if (typed.isEmpty() || !NAME_PATTERN.matcher(typed).matches()) {
                continue;
            }
            if (self != null && typed.equalsIgnoreCase(self)) {
                continue;
            }
            for (String teammate : teammates) {
                if (teammate.equalsIgnoreCase(typed) && !containsIgnoreCase(out, teammate)) {
                    out.add(teammate);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** Sends one queued kick at most, gated by {@link ActionGate} (one automated interaction per client
     *  tick, mod-wide) and {@link #KICK_MIN_GAP_MS} (this feature's own floor on top). Called every tick
     *  while {@link #pendingKicks} is non-empty, so a full party drains over several seconds rather than
     *  all at once. */
    private static void drainPendingKicks() {
        long now = System.currentTimeMillis();
        if (now - lastKickAtMs < KICK_MIN_GAP_MS) {
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AUTO_KICK)) {
            return;
        }
        String name = pendingKicks.remove(0);
        if (!ServerCommands.toServer("p kick " + name)) {
            // No connection right now (disconnected / world unloaded mid-drain) - the rest of the queue
            // can't go anywhere either, so drop it instead of silently retrying into whatever comes next.
            LOGGER.warn("[AutoKick] Couldn't send \"p kick {}\" - no active connection. Dropping {} remaining queued kick(s).",
                    name, pendingKicks.size());
            pendingKicks.clear();
            return;
        }
        lastKickAtMs = now;
        ModChat.send("Auto Kick", ModChat.value(name), ModChat.text(" kicked "),
                ModChat.dim("(floor target time exceeded)."));
        LOGGER.info("[AutoKick] Sent \"p kick {}\" - {} left queued", name, pendingKicks.size());
    }

    private static String formatMmSs(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
