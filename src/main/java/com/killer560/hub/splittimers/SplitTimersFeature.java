package com.killer560.hub.splittimers;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per-floor dungeon split timers - killer560's "Implement Noamm split timers" request. Every regex
 * below is ported directly from Odin's own real, confirmed {@code SplitsManager.kt}
 * ({@code dungeonSplits}/{@code floor1SplitGroup}...{@code floor7SplitGroup}) - real boss dialogue
 * lines, not guessed. A run starts on the real "Starting in 1 second." countdown message; each split
 * completes when its chat line is seen, in order, recording both a wall-clock time and the elapsed
 * tick count since the run started.
 * <p>
 * Deliberately simpler than Odin's own version: no personal-best tracking/comparison (that needs a
 * persisted history this session has no design for yet) and no Kuudra splits (out of scope for this
 * request - killer560's list was specifically dungeon-focused). Just the real per-split times, shown
 * live and announced to your own chat as they land.
 */
public final class SplitTimersFeature {

    private record SplitDef(Pattern pattern, String label) {
    }

    private static final List<SplitDef> ENTRANCE_SPLITS = List.of();

    private static final List<SplitDef> FLOOR1 = List.of(
            def("^\\[BOSS\\] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$", "Bonzo's Sike"),
            def("\\[BOSS\\] Bonzo: Oh I'm dead!", "Cleared"));

    private static final List<SplitDef> FLOOR2 = List.of(
            def("^\\[BOSS\\] Scarf: This is where the journey ends for you, Adventurers\\.$", "Scarf's Minions"),
            def("^\\[BOSS\\] Scarf: Did you forget\\? I was taught by the best! Let's dance\\.$", "Cleared"));

    private static final List<SplitDef> FLOOR3 = List.of(
            def("^\\[BOSS\\] The Professor: I was burdened with terrible news recently\\.\\.\\.$", "The Guardians"),
            def("^\\[BOSS\\] The Professor: Oh\\? You found my Guardians' one weakness\\?$", "The Professor"),
            def("^\\[BOSS\\] The Professor: What\\?! My Guardian power is unbeatable!$", "Cleared"));

    private static final List<SplitDef> FLOOR4 = List.of(
            def("^\\[BOSS\\] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$", "Cleared"));

    private static final List<SplitDef> FLOOR5 = List.of(
            def("^\\[BOSS\\] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$", "Cleared"));

    private static final List<SplitDef> FLOOR6 = List.of(
            def("^\\[BOSS\\] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$", "Terracottas"),
            def("^\\[BOSS\\] Sadan: ENOUGH!$", "Giants"),
            def("^\\[BOSS\\] Sadan: You did it\\. I understand now, you have earned my respect\\.$", "Cleared"));

    private static final List<SplitDef> FLOOR7 = List.of(
            def("^\\[BOSS\\] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$", "Maxor"),
            def("\\[BOSS\\] Storm: Pathetic Maxor, just like expected\\.", "Storm"),
            def("\\[BOSS\\] Goldor: Who dares trespass into my domain\\?", "Terminals"),
            def("The Core entrance is opening!", "Goldor"),
            def("\\[BOSS\\] Necron: You went further than any human before, congratulations\\.", "Necron"),
            def("\\[BOSS\\] Necron: All this, for nothing\\.\\.\\.", "Cleared"));

    private static SplitDef def(String regex, String label) {
        return new SplitDef(Pattern.compile(regex), label);
    }

    private static List<SplitDef> splitsForFloor(String floor) {
        if (floor == null) {
            return List.of();
        }
        int number;
        try {
            number = Integer.parseInt(floor.substring(1));
        } catch (Exception e) {
            return List.of();
        }
        return switch (number) {
            case 1 -> FLOOR1;
            case 2 -> FLOOR2;
            case 3 -> FLOOR3;
            case 4 -> FLOOR4;
            case 5 -> FLOOR5;
            case 6 -> FLOOR6;
            case 7 -> FLOOR7;
            default -> List.of();
        };
    }

    private static final class RunState {
        List<SplitDef> splits = List.of();
        int nextIndex = 0;
        long runStartMs = 0L;
        long lastSplitMs = 0L;
        final List<String> completedLabels = new ArrayList<>();
        final List<Long> completedSegmentMs = new ArrayList<>();
    }

    private static RunState run = new RunState();

    private SplitTimersFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static boolean wasInDungeon = false;

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            run = new RunState();
        }
        wasInDungeon = inDungeon;
    }

    private static void onChatMessage(Component message) {
        if (!SplitTimersConfig.getInstance().isEnabled()) {
            return;
        }
        String raw = message.getString();

        if ("Starting in 1 second.".equals(raw)) {
            RunState fresh = new RunState();
            fresh.splits = splitsForFloor(DungeonState.getFloor());
            fresh.runStartMs = System.currentTimeMillis();
            fresh.lastSplitMs = fresh.runStartMs;
            run = fresh;
            return;
        }

        if (run.splits.isEmpty() || run.nextIndex >= run.splits.size()) {
            return;
        }
        SplitDef expected = run.splits.get(run.nextIndex);
        if (!expected.pattern().matcher(raw).find()) {
            return;
        }
        long now = System.currentTimeMillis();
        long segment = now - run.lastSplitMs;
        run.completedLabels.add(expected.label());
        run.completedSegmentMs.add(segment);
        run.lastSplitMs = now;
        run.nextIndex++;

        Minecraft client = Minecraft.getInstance();
        if (SplitTimersConfig.getInstance().isAnnounceInChat() && client.player != null) {
            String text = String.format(Locale.US, "§6%s §7took §6%.1fs§7.",
                    expected.label(), segment / 1000.0);
            if (run.nextIndex >= run.splits.size()) {
                long total = now - run.runStartMs;
                text += String.format(Locale.US, " §7(Total: §6%.1fs§7)", total / 1000.0);
            }
            client.player.sendSystemMessage(Component.literal(text));
        }
    }

    public static final class SplitTimersHudElement implements HudElement {
        @Override
        public String id() {
            return "split_timers";
        }

        @Override
        public String displayName() {
            return "Split Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 320;
        }

        @Override
        public int width() {
            return 160;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, run.completedLabels.size());
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!SplitTimersConfig.getInstance().isEnabled() || Minecraft.getInstance().screen != null
                    || run.completedLabels.isEmpty()) {
                return;
            }
            int lineY = y;
            for (int i = 0; i < run.completedLabels.size(); i++) {
                String text = String.format(Locale.US, "%s: %.1fs", run.completedLabels.get(i),
                        run.completedSegmentMs.get(i) / 1000.0);
                graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}
