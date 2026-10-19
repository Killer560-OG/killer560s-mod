package com.killer560.hub.chocolatefactory.egg;

import com.killer560.hub.chocolatefactory.ChocolateFactoryConfig;
import com.killer560.hub.chocolatefactory.ChocolateFactoryHelper;
import com.killer560.hub.chocolatefactory.ChocolateFactoryMenu;
import com.killer560.hub.chocolatefactory.egg.HoppityCalendar.Meal;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hoppity's Hunt egg calculator - the legit half of the egg request (killer560, 2026-10-19: "make the chocolate factory
 * also do egg calculations"). Read-only: it never clicks, never sends anything. It works out, the way SkyHanni's
 * Hoppity egg timers and Skyblocker's egg finder do (both read for behaviour only, see {@link HoppityCalendar}):
 * <ul>
 * <li>for each meal (Breakfast/Brunch, Lunch/Dejeuner, Dinner/Supper) whether its live egg is still to find, found,
 * or unknown (you logged in after it spawned and no chat line has said), and when the next one spawns;</li>
 * <li>how far through the hunt we are (eggs spawned of 279), or when the next hunt starts;</li>
 * <li>what a duplicate rabbit pays at your production: chocolate per second x the rarity's base seconds (Common 150,
 * Uncommon 300, Rare 750, Epic 1,500, Legendary 4,000, Mythic 8,000, Divine 15,000 - the wiki's table) x 1.1, the
 * first-duplicate bonus. Chocolate per second is the factory menu's own number, remembered between opens;</li>
 * <li>a session tally from Hypixel's own lines: eggs found, new rabbits, duplicates and the chocolate they paid.</li>
 * </ul>
 * Every pattern is anchored on Hypixel's line shape and bounded; a player's chat line always starts with a name and a
 * colon, so none of these can be forged from chat.
 */
public final class HoppityEggs {

    private static final Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");

    private static final String MEALS = "(Breakfast|Lunch|Dinner|Brunch|D[ée]jeuner|Supper)";
    static final Pattern APPEARED = Pattern.compile("^HOPPITY'S HUNT A Chocolate " + MEALS + " Egg has appeared!$");
    static final Pattern FOUND = Pattern.compile("^HOPPITY'S HUNT You found a Chocolate " + MEALS + " Egg.{0,80}$");
    static final Pattern ALREADY = Pattern.compile("^You have already collected this Chocolate " + MEALS + " Egg!.{0,60}$");
    static final Pattern NONE_NEARBY = Pattern.compile("^There are no hidden Chocolate Rabbit Eggs nearby! Try again later!$");
    static final Pattern NEW_RABBIT = Pattern.compile("^NEW RABBIT! .{0,120}$");
    static final Pattern DUPLICATE = Pattern.compile("^DUPLICATE RABBIT! \\+([\\d,]{1,20}) Chocolate.{0,80}$");

    /** Base seconds of production a duplicate pays, by rarity (wiki). */
    static final String[] RARITY = {"C", "U", "R", "E", "L", "M", "D"};
    static final String[] RARITY_COLOR = {"§f", "§a", "§9", "§5", "§6", "§d", "§b"};
    static final int[] BASE_SECONDS = {150, 300, 750, 1500, 4000, 8000, 15000};
    static final double FIRST_DUPLICATE = 1.1;

    public enum Status { UNKNOWN, AVAILABLE, FOUND }

    private static final Status[] status = new Status[3];
    /** The spawn (ms) each status refers to; 0 before the first tick. */
    private static final long[] statusSpawn = new long[3];
    private static boolean initialised;

    private static int sessionEggs;
    private static int sessionNew;
    private static int sessionDupes;
    private static long sessionDupeChocolate;

    public static final HudElement HUD = new EggHud();

    private HoppityEggs() {
    }

    public static void register() {
        java.util.Arrays.fill(status, Status.UNKNOWN);
        ChatObserver.subscribe(HoppityEggs::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(HoppityEggs::tick);
        HudElementRegistry.register(HUD);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "chocolatefactory_" + HUD.id()),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
        EggEsp.register();
    }

    /** Status of {@code meal}'s live egg, as last known. */
    public static Status status(Meal meal) {
        return status[meal.ordinal()];
    }

    public static int sessionEggs() {
        return sessionEggs;
    }

    public static int sessionDupes() {
        return sessionDupes;
    }

    public static long sessionDupeChocolate() {
        return sessionDupeChocolate;
    }

    private static void tick(Minecraft client) {
        try {
            long now = System.currentTimeMillis();
            for (Meal m : Meal.values()) {
                long spawn = HoppityCalendar.lastSpawn(m, now);
                int i = m.ordinal();
                if (spawn != statusSpawn[i]) {
                    // A spawn we were online for is a fresh, unfound egg; the one live at login is unknown.
                    status[i] = initialised && spawn > 0 ? Status.AVAILABLE : Status.UNKNOWN;
                    statusSpawn[i] = spawn;
                }
            }
            initialised = true;
            ChocolateFactoryMenu.State st = ChocolateFactoryHelper.state();
            ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
            if (st != null && st.cps() > 0 && Math.abs(st.cps() - cfg.getLastCps()) > Math.max(0.01, cfg.getLastCps() * 0.001)) {
                cfg.setLastCps(st.cps());
                cfg.save();
            }
        } catch (RuntimeException e) {
            LOGGER.error("[HoppityEggs] tick failed", e);
        }
    }

    private static void onChat(Component message) {
        onChatText(ChatObserver.strip(message));
    }

    /** Package-visible for tests: feeds one plain chat line. */
    static void onChatText(String plain) {
        if (plain == null || plain.isEmpty() || plain.length() > 200) {
            return;
        }
        Matcher m = APPEARED.matcher(plain);
        if (m.matches()) {
            set(m.group(1), Status.AVAILABLE);
            return;
        }
        m = FOUND.matcher(plain);
        if (m.matches()) {
            set(m.group(1), Status.FOUND);
            sessionEggs++;
            return;
        }
        m = ALREADY.matcher(plain);
        if (m.matches()) {
            set(m.group(1), Status.FOUND);
            return;
        }
        if (NONE_NEARBY.matcher(plain).matches()) {
            // The Egglocator's "none left" answer: Skyblocker reads it as every live egg collected, and so do we.
            java.util.Arrays.fill(status, Status.FOUND);
            return;
        }
        if (NEW_RABBIT.matcher(plain).matches()) {
            sessionNew++;
            return;
        }
        m = DUPLICATE.matcher(plain);
        if (m.matches()) {
            sessionDupes++;
            try {
                sessionDupeChocolate += Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // Bounded to 20 digits above; a value past a long is simply not added.
            }
        }
    }

    private static void set(String eggName, Status s) {
        Meal meal = Meal.byName(eggName);
        if (meal != null) {
            status[meal.ordinal()] = s;
        }
    }

    /** Chocolate a duplicate of rarity index {@code r} pays at {@code cps}, first-duplicate bonus included. */
    public static long duplicateValue(int r, double cps) {
        return (long) Math.floor(cps * BASE_SECONDS[r] * FIRST_DUPLICATE);
    }

    // ---------------------------------------------------------------------------------------------------- HUD

    /** The HUD's lines; empty when there is nothing to show. */
    static List<String> lines(boolean preview) {
        List<String> out = new ArrayList<>();
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean active = preview || HoppityCalendar.huntActive(now);
        if (!active) {
            long start = HoppityCalendar.nextHuntStart(now);
            out.add("§6Hoppity's Hunt §7in §f" + ChocolateFactoryMenu.duration((start - now + 999) / 1000));
            return out;
        }
        int spawned = preview ? 42 : HoppityCalendar.eggsSpawnedThisHunt(now);
        out.add("§6Hoppity's Hunt §7(" + spawned + "/" + HoppityCalendar.EGGS_PER_HUNT + " eggs)");
        long soonest = Long.MAX_VALUE;
        int ready = 0;
        for (Meal m : Meal.values()) {
            long next = HoppityCalendar.nextSpawn(m, now);
            soonest = Math.min(soonest, next);
            long spawn = HoppityCalendar.lastSpawn(m, now);
            String name = spawn > 0 ? HoppityCalendar.eggName(m, HoppityCalendar.spawnDay(spawn))
                    : HoppityCalendar.eggName(m, HoppityCalendar.spawnDay(next));
            Status s = preview ? (m == Meal.LUNCH ? Status.FOUND : Status.AVAILABLE) : status(m);
            if (spawn <= 0 && !preview) {
                s = Status.UNKNOWN;
            }
            String state = switch (s) {
                case AVAILABLE -> "§aready";
                case FOUND -> "§8found";
                case UNKNOWN -> "§e?";
            };
            if (s == Status.AVAILABLE) {
                ready++;
            }
            out.add(m.color + name + "§7: " + state + " §7(next " + ChocolateFactoryMenu.duration((next - now + 999) / 1000) + ")");
        }
        out.add("§7Next egg in §f" + ChocolateFactoryMenu.duration((soonest - now + 999) / 1000)
                + (ready > 0 ? " §7| §a" + ready + " to find" : ""));
        int eggs = preview ? 5 : sessionEggs;
        if (eggs > 0 || preview) {
            int dupes = preview ? 3 : sessionDupes;
            long choc = preview ? 12_300_000L : sessionDupeChocolate;
            out.add("§7Session: §f" + eggs + " §7eggs, §a" + (preview ? 2 : sessionNew) + " §7new, §e" + dupes
                    + " §7dupes §6+" + ChocolateFactoryMenu.compact(choc));
        }
        if (cfg.isEggDupeValues()) {
            double cps = preview && cfg.getLastCps() <= 0 ? 5000 : cfg.getLastCps();
            if (cps > 0) {
                StringBuilder b = new StringBuilder("§7Dupe:");
                for (int r = 0; r < RARITY.length; r++) {
                    b.append(' ').append(RARITY_COLOR[r]).append(RARITY[r]).append(" §6")
                            .append(ChocolateFactoryMenu.compact(duplicateValue(r, cps)));
                }
                out.add(b.toString());
            } else {
                out.add("§7Dupe: §8open the factory once to read your production");
            }
        }
        return out;
    }

    private static boolean shown() {
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        return cfg.isEnabled() && cfg.isEggHud();
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen() || !SkyblockGate.allows()
                    || HudVisibility.editorOpen() || HudVisibility.hidesHud() || !shown()) {
                return;
            }
            HudElementRegistry.drawAt(graphics, HUD);
        } catch (RuntimeException e) {
            LOGGER.error("[HoppityEggs] HUD draw failed", e);
        }
    }

    /** Movable and scalable in Edit HUD. */
    private static final class EggHud implements HudElement {

        @Override
        public String id() {
            return "hoppity_eggs";
        }

        @Override
        public String displayName() {
            return "Hoppity Eggs";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 120;
        }

        @Override
        public int width() {
            int w = 1;
            for (String line : lines(HudVisibility.editorOpen())) {
                w = Math.max(w, Minecraft.getInstance().font.width(line));
            }
            return w;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(Math.max(1, lines(HudVisibility.editorOpen()).size()), 9);
        }

        @Override
        public boolean isEnabledInSettings() {
            return shown();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            List<String> lines = lines(HudVisibility.editorOpen());
            if (lines.isEmpty()) {
                return;
            }
            HudSeen.markDrawn(id());
            Minecraft client = Minecraft.getInstance();
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(client.font, lines.get(i), x, y + i * 9, 0xFFFFFFFF, true);
            }
        }
    }
}
