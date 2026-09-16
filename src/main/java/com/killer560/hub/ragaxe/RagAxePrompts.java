package com.killer560.hub.ragaxe;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.witherdragons.P5State;
import com.killer560.hub.witherdragons.ServerTickClock;
import com.killer560.hub.witherdragons.WitherDragon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The built-in "rag now" prompts (see {@link RagAxePrompt} for every offset and where it comes from).
 * <p>
 * Four of the five are chat-line triggered, using the exact server lines this mod already keys other features
 * off (so they stay in one style and are known-good):
 * <ul>
 * <li>Storm / Necron - the same two P2 and P4 lines {@code splittimers.SplitTimersFeature} splits on, which are
 * Odin's {@code SplitsManager.kt} {@code floor7SplitGroup} lines.
 * <li>Wither King - NoammAddons' {@code features/impl/dungeon/Ragnarock.kt} M7 alert line.
 * <li>Livid - the same entry line {@code boss.LividSolverFeature} arms its 390-tick invulnerability countdown
 * on (Odin's {@code LividSolver.kt}).
 * </ul>
 * The fifth ({@link RagAxePrompt#M7_DRAGON_SPAWN}) is event-timed instead: it reads the live per-dragon
 * countdown out of {@code com.killer560.hub.witherdragons} on {@link ServerTickClock}'s server ticks and fires
 * one lead-time before that dragon spawns. Nothing is detected twice - this only reads {@link WitherDragon}'s
 * public state, which the Wither Dragons feature owns. That also means it only works while dragon tracking is
 * running (Wither Dragons, King Relics or the P5 split lines on); the GUI says so.
 * <p>
 * Nothing here clicks, aims or sends anything to the server - it is a title/HUD/sound prompt only.
 */
final class RagAxePrompts {

    private static final String LINE_WITHER_KING =
            "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you.";
    private static final String LINE_NECRON =
            "[BOSS] Necron: You went further than any human before, congratulations.";
    private static final String LINE_STORM = "[BOSS] Storm: Pathetic Maxor, just like expected.";
    private static final String LINE_LIVID =
            "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";

    /** Scheduled fire time per prompt (0 = nothing pending). */
    private static final long[] FIRE_AT_MS = new long[RagAxePrompt.values().length];
    /** Per-dragon "already prompted for this spawn" marker (its {@code timesSpawned} value). */
    private static final int[] DRAGON_PROMPTED = new int[WitherDragon.values().length];

    private RagAxePrompts() {
    }

    static void register() {
        ServerTickClock.register();
        ServerTickClock.subscribe(RagAxePrompts::onServerTick);
    }

    static void reset() {
        java.util.Arrays.fill(FIRE_AT_MS, 0L);
        java.util.Arrays.fill(DRAGON_PROMPTED, -1);
    }

    // ------------------------------------------------------------------ triggers

    static void onChat(Component message) {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (LINE_WITHER_KING.equals(plain) && onFloor7()) {
            schedule(RagAxePrompt.M7_DRAGONS);
        } else if (LINE_NECRON.equals(plain) && onFloor7()) {
            schedule(RagAxePrompt.M7_NECRON);
        } else if (LINE_STORM.equals(plain) && onFloor7()) {
            schedule(RagAxePrompt.M7_STORM);
        } else if (LINE_LIVID.equals(plain) && onFloor5()) {
            schedule(RagAxePrompt.F5_LIVID);
        }
    }

    /** {@code promptAt = trigger + max(0, expectedOffset - lead)} - the buff then lands on the moment. */
    private static void schedule(RagAxePrompt prompt) {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        if (!cfg.isPromptEnabled(prompt) || skippedForClass(prompt)) {
            return;
        }
        long delay = Math.max(0L, prompt.expectedOffsetMs - (long) cfg.getPromptLeadMs(prompt));
        FIRE_AT_MS[prompt.ordinal()] = System.currentTimeMillis() + delay;
        RagAxeFeature.LOGGER.info("[RagAxe] {} prompt scheduled in {} ms (offset {}, lead {})", prompt.label, delay,
                prompt.expectedOffsetMs, cfg.getPromptLeadMs(prompt));
    }

    static void clientTick() {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        long now = System.currentTimeMillis();
        for (RagAxePrompt prompt : RagAxePrompt.values()) {
            int i = prompt.ordinal();
            if (FIRE_AT_MS[i] == 0L || now < FIRE_AT_MS[i]) {
                continue;
            }
            FIRE_AT_MS[i] = 0L;
            if (cfg.isEnabled() && cfg.isPromptEnabled(prompt)) {
                fire(prompt);
            }
        }
    }

    /** Odin's own dragon countdown: {@code timeToSpawn} server ticks (100 = 5 s) left on a SPAWNING dragon. */
    private static void onServerTick() {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        RagAxePrompt prompt = RagAxePrompt.M7_DRAGON_SPAWN;
        if (!cfg.isEnabled() || !cfg.isPromptEnabled(prompt) || skippedForClass(prompt) || !P5State.inP5()) {
            return;
        }
        long lead = cfg.getPromptLeadMs(prompt);
        for (WitherDragon dragon : WitherDragon.values()) {
            int i = dragon.ordinal();
            if (dragon.state() != WitherDragon.State.SPAWNING) {
                continue;
            }
            if (DRAGON_PROMPTED[i] == dragon.timesSpawned()) {
                continue;
            }
            if (dragon.timeToSpawn() * 50L <= lead) {
                DRAGON_PROMPTED[i] = dragon.timesSpawned();
                RagAxeFeature.LOGGER.info("[RagAxe] Dragon prompt: {} spawns in {} ticks", dragon.colourName,
                        dragon.timeToSpawn());
                fire(prompt);
            }
        }
    }

    private static void fire(RagAxePrompt prompt) {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        RagAxeFeature.showPrompt(prompt.label, cfg.isPromptSound(prompt), cfg.isPromptTitle());
    }

    // ------------------------------------------------------------------ gates

    /** NoammAddons/Odin both skip the M7 dragon rag call for Tank and Healer (they don't rag for dragons). */
    private static boolean skippedForClass(RagAxePrompt prompt) {
        if (!RagAxeConfig.getInstance().isSkipTankHealer()
                || (prompt != RagAxePrompt.M7_DRAGONS && prompt != RagAxePrompt.M7_DRAGON_SPAWN)) {
            return false;
        }
        DungeonClass self = P5State.selfClass();
        return self == DungeonClass.TANK || self == DungeonClass.HEALER;
    }

    /** F7/M7, or p3sim.net (which simulates the P3 -&gt; P4 hand-off these lines sit around). */
    private static boolean onFloor7() {
        return DungeonState.isF7OrM7() || P5State.isP3Sim(Minecraft.getInstance());
    }

    private static boolean onFloor5() {
        String floor = DungeonState.getFloor();
        return "F5".equals(floor) || "M5".equals(floor);
    }
}
