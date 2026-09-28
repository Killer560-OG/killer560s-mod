package com.killer560.hub.roomsim;

import com.killer560.hub.architect.ArchitectDraftConfig;
import com.killer560.hub.util.ModChat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * The Architect's First Draft feature, working inside the sim.
 *
 * <p>killer560 (2026-09-28): "We should have the draft feature we already have automatic get from sacks draft
 * setting that works on main server."
 *
 * <p>His existing feature ({@link com.killer560.hub.architect.ArchitectDraftFeature}) triggers off Hypixel's own
 * "PUZZLE FAIL!" broadcast and answers it with {@code /gfs architect_first_draft 1}. Neither half survives a local
 * world: nothing broadcasts a fail line, and there is no sack to pull from. So the sim drives the same SETTINGS
 * down a different path - the puzzles say they failed directly, and the item is handed over rather than fetched.
 *
 * <p>Deliberately NOT done by injecting a fake "PUZZLE FAIL!" line into chat, which would have been fewer lines.
 * A feature that watches chat and a feature that fabricates chat for it to watch is a loop that lies to every
 * other chat observer in the mod at the same time - the run tracker and the puzzle solvers would all have seen a
 * fail that never came from a server.
 *
 * <p><b>Why the RAW settings.</b> {@code isClickMessage()} and {@code isAutoGet()} both gate on
 * {@link com.killer560.hub.util.SkyblockGate}, and auto-get also gates on the cheat build. In a singleplayer world
 * there is no Skyblock scoreboard, so the real getters are false in here by construction and reading them would
 * mean the feature could never fire. The cheat gate is dropped for the same reason it exists: it is there because
 * pulling from a sack on Hypixel is automation aimed at a real server, and in here there is no sack, no server and
 * nothing to automate against. Everything still sits behind {@link SimState#canAct}, which is the one way in.
 */
public final class SimArchitect {

    /** The same few seconds the chat-driven path uses, so one fail cannot hand over two drafts. */
    private static final long COOLDOWN_MS = 3000;

    private static long lastMs;

    private SimArchitect() {
    }

    /** Forgets the cooldown, so the first fail of a new run always counts. */
    public static void reset() {
        lastMs = 0;
    }

    /**
     * A sim puzzle just failed.
     *
     * @param puzzleName the puzzle, for the message - so a fail in a room he is not looking at still says which
     */
    public static void onPuzzleFail(String puzzleName) {
        ArchitectDraftConfig cfg = ArchitectDraftConfig.getInstance();
        if (!cfg.isClickMessageRaw() && !cfg.isAutoGetRaw()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMs < COOLDOWN_MS) {
            return;
        }
        lastMs = now;

        String label = puzzleName == null || puzzleName.isBlank() ? "A puzzle" : puzzleName;
        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            // Auto Get wins over Click Message when both are on, matching the chat path's ordering: the point of
            // auto-get is not having to click, so offering a click as well would be noise.
            if (cfg.isAutoGetRaw()) {
                SimItems.give(client, SimItems.ARCHITECT_DRAFT_ID);
                ModChat.send("Architect", ModChat.text(label + " failed - here is an "),
                        ModChat.value("Architect's First Draft"));
                return;
            }
            MutableComponent link = Component.literal("[Click to get an Architect's First Draft]")
                    .withStyle(s -> s.withColor(ChatFormatting.GOLD).withUnderlined(true)
                            // The sim's own give command, not /gfs: there is no sack here, and a click that ran
                            // a command the world cannot answer would look like the feature was broken.
                            .withClickEvent(new ClickEvent.RunCommand("/simitem architect_first_draft"))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal("/simitem architect_first_draft"))));
            ModChat.send("Architect", ModChat.text(label + " failed. "));
            ModChat.send("Architect", link);
        });
    }
}
