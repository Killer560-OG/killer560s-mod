package com.killer560.hub.powdermining;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server lines the powder grinding features read, on Fabric's ALLOW_GAME (system chat; a player's chat line arrives on
 * ALLOW_CHAT and never here, and every pattern is matched whole besides):
 * <ul>
 *   <li>"You uncovered a treasure chest!" - {@link TreasureChests} and the Treasure Chest Alert.</li>
 *   <li>The loot block (Hypixel, 2026; SkyHanni PowderMiningChatFilter): a line of "▬", "  CHEST LOCKPICKED " (or "  LOOT
 *       CHEST COLLECTED "), "  REWARDS", one line per reward ("    Gemstone Powder x537", no " xN" means one), and the "▬"
 *       line again. Counted by the {@link PowderTracker}; with Chest Loot Compact on, the whole block is hidden and one line
 *       takes its place, with every reward on hover.</li>
 *   <li>"2X POWDER STARTED!" / "2X POWDER ENDED!" - the 2x Powder Alert. Hypixel's line can carry words around it, so it is
 *       matched as the whole line with up to 40 characters either side and no ':' anywhere (a player's line always has
 *       one after the name).</li>
 * </ul>
 *
 * <p>The opening "▬" line cannot be told from any other until the next line comes, so with Compact on it is held back (in
 * the Crystal Hollows only) and put back into chat, unchanged, when the next line is not a chest header (or after 3 ticks
 * with no next line). This is ALLOW_GAME, on the packet path: a throw would disconnect him, so all of it is caught and a
 * failure lets the line through.
 */
public final class PowderChat {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-powdermining");

    static final Pattern UNCOVERED = Pattern.compile("^You uncovered a treasure chest!$");
    static final Pattern HEADER = Pattern.compile("^(?:CHEST LOCKPICKED|LOOT CHEST COLLECTED)$");
    static final Pattern REWARDS = Pattern.compile("^REWARDS$");
    /** A reward line, trimmed: an optional gemstone icon ("⸕ Rough Amber Gemstone x24"), the name, an optional " xN". */
    static final Pattern REWARD = Pattern.compile(
            "^(?:[^A-Za-z0-9\\s]{1,2} )?([A-Za-z0-9'][A-Za-z0-9' \\-]{0,47}?)(?: x([0-9,]{1,11}))?$");
    static final Pattern DOUBLE = Pattern.compile("^[^:]{0,40}?2X POWDER (STARTED|ENDED)![^:]{0,40}$");

    private static boolean inBlock = false;
    private static boolean blockHidden = false;
    private static long blockAt = 0L;
    private static final Map<String, Long> rewards = new LinkedHashMap<>();
    private static final StringBuilder rewardText = new StringBuilder();
    private static Component heldSeparator = null;
    private static long heldAt = 0L;
    private static long tickCount = 0L;
    private static boolean doublePowder = false;
    private static Object level = null;
    private static boolean flushing = false;

    private static int hidden = 0;
    private static int compactLines = 0;
    private static int doubleAlerts = 0;
    private static String lastCompact = null;

    private PowderChat() {
    }

    static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register(PowderChat::allow);
    }

    private static boolean allow(Component message, boolean overlay) {
        if (overlay || message == null || flushing) {
            return true;
        }
        try {
            return handle(message);
        } catch (RuntimeException e) {
            LOGGER.error("[PowderChat] line failed", e);
            return true;
        }
    }

    private static boolean isSeparator(String t) {
        if (t.length() < 20 || t.length() > 80) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != '▬') {
                return false;
            }
        }
        return true;
    }

    /** @return false to drop the line. */
    private static boolean handle(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.length() > 2000) {
            return true;
        }
        if (plain.indexOf('\n') >= 0) {
            return handleMulti(plain);
        }
        String t = plain.trim();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        boolean hollows = MiningState.crystalHollows();
        boolean compact = cfg.isLootCompact() && hollows;

        if (inBlock) {
            if (isSeparator(t)) {
                boolean wasHidden = blockHidden;
                finish();
                return !wasHidden;
            }
            // Hypixel sends a blank line between the header and REWARDS (killer560's log, 2026-11-03 22:56); it used to end
            // the block before any reward, so nothing was counted and Compact printed "[Chest] nothing".
            if (t.isEmpty()) {
                return !blockHidden;
            }
            if (REWARDS.matcher(t).matches()) {
                return !blockHidden;
            }
            if (plain.startsWith("    ") && reward(t)) {
                return !blockHidden;
            }
            finish(); // any other line ends the block
        }
        if (t.length() <= 40 && HEADER.matcher(t).matches() && hollows) {
            boolean hide = compact && heldSeparator != null;
            heldSeparator = null;
            // Not held (Compact switched on mid-block): the block shows whole, and is still counted.
            begin(hide);
            return !hide;
        }
        flushHeld();
        if (isSeparator(t)) {
            if (compact) {
                heldSeparator = message;
                heldAt = tickCount;
                hidden++;
                return false;
            }
            return true;
        }
        if (t.length() <= 40 && UNCOVERED.matcher(t).matches()) {
            TreasureChests.onUncovered();
            return true;
        }
        if (t.length() <= 100 && t.contains("2X POWDER")) {
            Matcher m = DOUBLE.matcher(t);
            if (m.matches()) {
                onDouble("STARTED".equals(m.group(1)));
            }
        }
        return true;
    }

    /** A loot block sent as one message with line breaks: counted, and hidden whole with Compact on. */
    private static boolean handleMulti(String plain) {
        boolean header = false;
        for (String line : plain.split("\n", 64)) {
            if (HEADER.matcher(line.trim()).matches()) {
                header = true;
                break;
            }
        }
        if (!header || !MiningState.crystalHollows()) {
            return true;
        }
        flushHeld();
        boolean hide = PowderMiningConfig.getInstance().isLootCompact();
        begin(hide);
        for (String line : plain.split("\n", 64)) {
            String t = line.trim();
            if (line.startsWith("    ")) {
                reward(t);
            }
        }
        finish();
        if (hide) {
            hidden++;
        }
        return !hide;
    }

    private static void begin(boolean hide) {
        inBlock = true;
        blockHidden = hide;
        blockAt = tickCount;
        rewards.clear();
        rewardText.setLength(0);
        if (hide) {
            hidden++;
        }
    }

    private static boolean reward(String t) {
        Matcher m = REWARD.matcher(t);
        if (!m.matches()) {
            return false;
        }
        String name = m.group(1).trim();
        long n = 1L;
        if (m.group(2) != null) {
            try {
                n = Long.parseLong(m.group(2).replace(",", ""));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        rewards.merge(name, n, Long::sum);
        if (rewardText.length() > 0) {
            rewardText.append('\n');
        }
        rewardText.append(t);
        if (blockHidden) {
            hidden++;
        }
        return true;
    }

    private static void finish() {
        if (!inBlock) {
            return;
        }
        inBlock = false;
        Map<String, Long> got = new LinkedHashMap<>(rewards);
        PowderTracker.onChest(got);
        TreasureChests.onLooted();
        if (blockHidden) {
            compactLine(got, rewardText.toString());
        }
        blockHidden = false;
    }

    /**
     * Rare Crystal Hollows treasure chest drops: those with a roll weight of 10 or less in the pools on
     * hypixelskyblock.minecraft.wiki "Treasure Chest/Crystal Hollows Loot" (read 2026-11-03; under 1% a chest): Fine and
     * Flawless gemstones (Fine 0.06-0.13%, Flawless 0.06%), Prehistoric Egg (0.3%) and Pickonimbus 2000 (0.6%).
     */
    static boolean isRare(String name) {
        return name.equals("Prehistoric Egg") || name.equals("Pickonimbus 2000")
                || (name.endsWith(" Gemstone") && (name.startsWith("Fine ") || name.startsWith("Flawless ")));
    }

    static boolean isPowder(String name) {
        return name.equals("Gemstone Powder") || name.equals("Mithril Powder") || name.equals("Glacite Powder");
    }

    static boolean shown(String name, PowderMiningConfig.LootShow mode) {
        return switch (mode) {
            case ALL -> true;
            case POWDER_ONLY -> isPowder(name);
            case POWDER_RARE -> isPowder(name) || isRare(name);
            case HIDE_ALL -> false;
        };
    }

    private static void compactLine(Map<String, Long> all, String fullHover) {
        PowderMiningConfig.LootShow mode = PowderMiningConfig.getInstance().getLootShow();
        if (mode == PowderMiningConfig.LootShow.HIDE_ALL) {
            return;
        }
        Map<String, Long> got = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : all.entrySet()) {
            if (shown(e.getKey(), mode)) {
                got.put(e.getKey(), e.getValue());
            }
        }
        String hover = fullHover;
        if (mode != PowderMiningConfig.LootShow.ALL) {
            if (got.isEmpty()) {
                return;
            }
            StringBuilder h = new StringBuilder();
            for (String line : fullHover.split("\\n")) {
                Matcher m = REWARD.matcher(line.trim());
                if (m.matches() && shown(m.group(1).trim(), mode)) {
                    if (h.length() > 0) {
                        h.append('\n');
                    }
                    h.append(line);
                }
            }
            hover = h.toString();
        }
        StringBuilder sb = new StringBuilder();
        long gem = got.getOrDefault("Gemstone Powder", 0L);
        long mith = got.getOrDefault("Mithril Powder", 0L);
        int others = 0;
        for (String k : got.keySet()) {
            if (!k.equals("Gemstone Powder") && !k.equals("Mithril Powder")) {
                others++;
            }
        }
        MutableComponent body = Component.empty();
        if (gem > 0) {
            body.append(Component.literal("+" + PowderTracker.compact(gem) + " Gemstone").withStyle(ChatFormatting.LIGHT_PURPLE));
            sb.append("+").append(gem).append(" Gemstone");
        }
        if (mith > 0) {
            if (!sb.isEmpty()) {
                body.append(ModChat.dim(", "));
            }
            body.append(Component.literal("+" + PowderTracker.compact(mith) + " Mithril").withStyle(ChatFormatting.DARK_GREEN));
            sb.append(" +").append(mith).append(" Mithril");
        }
        if (others > 0) {
            body.append(ModChat.dim((sb.isEmpty() ? "" : ", ") + "+" + others + (others == 1 ? " item" : " items")));
            sb.append(" +").append(others).append(" items");
        }
        if (got.isEmpty()) {
            body.append(ModChat.dim("nothing"));
        }
        Component hoverText = Component.literal(hover.isEmpty() ? "No rewards" : hover);
        MutableComponent line = Component.empty().append(body)
                .withStyle(st -> st.withHoverEvent(new HoverEvent.ShowText(hoverText)));
        lastCompact = sb.toString().trim();
        compactLines++;
        ModChat.sendBare(line);
    }

    private static void flushHeld() {
        Component held = heldSeparator;
        if (held == null) {
            return;
        }
        heldSeparator = null;
        hidden = Math.max(0, hidden - 1);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            // LocalPlayer.sendSystemMessage goes through the ChatListener, so ALLOW_GAME sees the line again: let it pass.
            flushing = true;
            try {
                client.player.sendSystemMessage(held);
            } finally {
                flushing = false;
            }
        }
    }

    private static void onDouble(boolean started) {
        boolean was = doublePowder;
        doublePowder = started;
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (!started || was || !cfg.isDoublePowder() || !MiningState.onMiningIsland()) {
            return;
        }
        doubleAlerts++;
        Minecraft client = Minecraft.getInstance();
        McCompat.setTimes(client, 5, 50, 10);
        McCompat.setTitle(client, Component.literal("§b§l2X POWDER"));
        McCompat.setSubtitle(client, Component.literal("§7Powder is doubled for this event"));
        if (cfg.isDoublePowderSound()) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f));
        }
    }

    static void tick(Minecraft client) {
        tickCount++;
        if (level != client.level) {
            level = client.level;
            doublePowder = false; // a new lobby: its own event
        }
        if (heldSeparator != null && tickCount - heldAt > 3) {
            flushHeld();
        }
        if (inBlock && tickCount - blockAt > 40) {
            finish();
        }
    }

    public static boolean doublePowderActive() {
        return doublePowder;
    }

    // ---- testkit hooks

    public static int hiddenLines() {
        return hidden;
    }

    public static int compactLines() {
        return compactLines;
    }

    public static int doubleAlerts() {
        return doubleAlerts;
    }

    public static String lastCompact() {
        return lastCompact;
    }
}
