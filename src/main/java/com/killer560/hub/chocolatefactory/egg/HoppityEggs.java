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
    static final Pattern NONE_NEARBY = Pattern.compile("^There are no (?:hidden )?Chocolate Rabbit Eggs nearby! Try again later!$");
    /** The Egglocator on an island that spawns no eggs (wiki, Egglocator). */
    static final Pattern WRONG_ISLAND = Pattern.compile("^This island doesn't spawn Chocolate Rabbit Eggs!$");
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
    /** The same, per named egg (killer560, 2026-10-19: the HUD shows all six): index {@link #eggIndex}, Breakfast, Lunch,
     *  Dinner, then Brunch, Déjeuner, Supper. Each named egg stays until its own next spawn, so each has its own state. */
    private static final Status[] eggStatus = new Status[6];
    private static final long[] eggStatusSpawn = new long[6];
    private static boolean initialised;

    private static int sessionEggs;
    private static int sessionNew;
    private static int sessionDupes;
    private static long sessionDupeChocolate;

    public static final HudElement HUD = new EggHud();

    /** The Egglocator: a right click starts a 5 s cooldown and draws a particle trail to the closest unfound egg (wiki). */
    private static final long LOCATOR_COOLDOWN_MS = 5_000L;
    private static final long LOCATOR_RESULT_MS = 60_000L;
    private static long locatorUsedMs;
    private static long locatorResultMs;
    private static String locatorResult = "";
    private static boolean useWasDown;

    private HoppityEggs() {
    }

    public static void register() {
        java.util.Arrays.fill(status, Status.UNKNOWN);
        java.util.Arrays.fill(eggStatus, Status.UNKNOWN);
        ChatObserver.subscribe(HoppityEggs::onChat);
        LocatorGuessRenderer.register();
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            try {
                net.minecraft.world.phys.Vec3 g = on() ? LocatorTrail.guess(System.currentTimeMillis()) : null;
                if (g != null) {
                    LocatorGuessRenderer.draw(ctx, g);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Egglocator] draw failed", e);
            }
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(HoppityEggs::tickLocator);
        // The use itself, as SkyHanni's ItemClickEvent: a tap shorter than a tick is down and up again between two client
        // ticks, so the key-state edge in tickLocator alone missed it, and the trail that followed was ignored.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                locatorClicked(player.getItemInHand(hand));
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                locatorClicked(player.getItemInHand(hand));
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (on()) {
                        EggTracker.tick(System.currentTimeMillis());
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(HoppityEggs::tick);
        ClientTickEvents.END_CLIENT_TICK.register(EggPositions::tick);
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

    /** The helper's master switch: with it off nothing here reads, draws or tracks (it used to run regardless). */
    private static boolean on() {
        return ChocolateFactoryConfig.getInstance().isEnabled();
    }

    private static void tick(Minecraft client) {
        try {
            if (!on()) {
                // Spawns that pass while off are not "seen live": the next enable starts from unknown, not "ready".
                if (initialised) {
                    initialised = false;
                    java.util.Arrays.fill(statusSpawn, 0L);
                    java.util.Arrays.fill(eggStatusSpawn, 0L);
                }
                return;
            }
            long now = System.currentTimeMillis();
            for (Meal m : Meal.values()) {
                long spawn = HoppityCalendar.lastSpawn(m, now);
                int i = m.ordinal();
                if (spawn != statusSpawn[i]) {
                    // A spawn we were online for is a fresh, unfound egg; the one live at login is unknown.
                    status[i] = initialised && spawn > 0 ? Status.AVAILABLE : Status.UNKNOWN;
                    statusSpawn[i] = spawn;
                }
                for (int p = 0; p < 2; p++) {
                    boolean odd = p == 0;
                    long named = HoppityCalendar.lastSpawn(m, odd, now);
                    int e = eggIndex(m, odd);
                    if (named != eggStatusSpawn[e]) {
                        eggStatus[e] = initialised && named > 0 ? Status.AVAILABLE : Status.UNKNOWN;
                        eggStatusSpawn[e] = named;
                    }
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
        if (!on()) {
            return;
        }
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
            LocatorTrail.clearGuess("egg found");
            set(m.group(1), Status.FOUND);
            sessionEggs++;
            EggTracker.eggFound();
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
            java.util.Arrays.fill(eggStatus, Status.FOUND);
            LocatorTrail.clearGuess("none nearby");
            setLocatorResult("§cNo eggs left on this island.");
            return;
        }
        if (WRONG_ISLAND.matcher(plain).matches()) {
            setLocatorResult("§cThis island has no eggs.");
            return;
        }
        if (NEW_RABBIT.matcher(plain).matches()) {
            sessionNew++;
            EggTracker.newRabbit();
            return;
        }
        m = DUPLICATE.matcher(plain);
        if (m.matches()) {
            sessionDupes++;
            try {
                sessionDupeChocolate += Long.parseLong(m.group(1).replace(",", ""));
                EggTracker.duplicate(Long.parseLong(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
                // Bounded to 20 digits above; a value past a long is simply not added.
            }
        }
    }

    private static void set(String eggName, Status s) {
        Meal meal = Meal.byName(eggName);
        if (meal != null) {
            status[meal.ordinal()] = s;
            eggStatus[eggIndex(meal, meal.oddName.equalsIgnoreCase(eggName))] = s;
        }
    }

    /** 0-2 for Breakfast, Lunch, Dinner; 3-5 for Brunch, Déjeuner, Supper. */
    static int eggIndex(Meal meal, boolean odd) {
        return meal.ordinal() + (odd ? 0 : 3);
    }

    /** Chocolate a duplicate of rarity index {@code r} pays at {@code cps}, first-duplicate bonus included. */
    public static long duplicateValue(int r, double cps) {
        return (long) Math.floor(cps * BASE_SECONDS[r] * FIRST_DUPLICATE);
    }

    // ---------------------------------------------------------------------------------------------------- HUD

    /** Egglocator status: cooling down, ready, or the last answer it gave. */
    static String locatorLine(long now) {
        long cooling = locatorUsedMs + LOCATOR_COOLDOWN_MS - now;
        String state = cooling > 0 ? "§7cooling " + ((cooling + 999) / 1000) + "s" : "§aready";
        String last = locatorResult.isEmpty() || now - locatorResultMs > LOCATOR_RESULT_MS ? "" : " §7| " + locatorResult;
        String note = LocatorTrail.unmatched(now) ? " §7| §cunreliable, use it again" : "";
        return "§dEgglocator: " + state + last + note;
    }

    private static void setLocatorResult(String text) {
        locatorResult = text;
        locatorResultMs = System.currentTimeMillis();
    }

    /**
     * Client tick: the rising edge of the use key while holding an Egglocator (a backstop for the use callbacks), and the
     * trail's own tick.
     */
    private static void tickLocator(Minecraft client) {
        if (!on()) {
            useWasDown = false;
            return;
        }
        long now = System.currentTimeMillis();
        boolean down = client.player != null && client.options != null && client.options.keyUse.isDown()
                && isLocator(client.player.getMainHandItem());
        if (down && !useWasDown) {
            locatorClicked(client.player.getMainHandItem());
        }
        useWasDown = down;
        LocatorTrail.tick(client, now);
    }

    private static boolean isLocator(net.minecraft.world.item.ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getHoverName().getString().contains("Egglocator");
    }

    /** A use of {@code stack}: when it is the Egglocator and off cooldown (SkyHanni: 5 s since the last), a trail begins. */
    private static void locatorClicked(net.minecraft.world.item.ItemStack stack) {
        long now = System.currentTimeMillis();
        if (!on() || !isLocator(stack) || !SkyblockGate.allows() || now < locatorUsedMs + LOCATOR_COOLDOWN_MS) {
            return;
        }
        locatorUsedMs = now;
        LocatorTrail.onUse(now);
    }

    /**
     * The HUD's lines. By default only the six egg timers (killer560, 2026-10-19: "by default should show the breakfast
     * lunch and dinner and then the brunch ones all 6 egg things with the timer"): Breakfast, Lunch, Dinner, then Brunch,
     * Déjeuner, Supper, each with its state and when it next spawns. Everything else - the hunt's progress or countdown,
     * the next egg, eggs to find, the Egglocator, the tracker, the session and the duplicate values - is Egg Extra Info.
     */
    static List<String> lines(boolean preview) {
        List<String> out = new ArrayList<>();
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        long now = System.currentTimeMillis();
        boolean active = preview || HoppityCalendar.huntActive(now);
        long soonest = Long.MAX_VALUE;
        int ready = 0;
        for (int p = 0; p < 2; p++) {
            boolean odd = p == 0;
            for (Meal m : Meal.values()) {
                long next = HoppityCalendar.nextSpawn(m, odd, now);
                soonest = Math.min(soonest, next);
                long spawn = HoppityCalendar.lastSpawn(m, odd, now);
                String timer = " §7(next " + ChocolateFactoryMenu.duration((next - now + 999) / 1000) + ")";
                String name = odd ? m.oddName : m.evenName;
                if (spawn <= 0 && !preview) {
                    // No live egg of this name (before its first spawn of the hunt, or between hunts): just its timer.
                    out.add(m.color + name + "§7:" + timer);
                    continue;
                }
                Status s = preview ? (m == Meal.LUNCH ? Status.FOUND : Status.AVAILABLE) : eggStatus[eggIndex(m, odd)];
                String state = switch (s) {
                    case AVAILABLE -> "§aready";
                    case FOUND -> "§8found";
                    case UNKNOWN -> "§e?";
                };
                if (s == Status.AVAILABLE) {
                    ready++;
                }
                out.add(m.color + name + "§7: " + state + timer);
            }
        }
        if (!cfg.isEggExtraInfo()) {
            return out;
        }
        if (!active) {
            long start = HoppityCalendar.nextHuntStart(now);
            out.add("§6Hoppity's Hunt §7in §f" + ChocolateFactoryMenu.duration((start - now + 999) / 1000));
            return out;
        }
        int spawned = preview ? 42 : HoppityCalendar.eggsSpawnedThisHunt(now);
        out.add("§6Hoppity's Hunt §7(" + spawned + "/" + HoppityCalendar.EGGS_PER_HUNT + " eggs)");
        out.add("§7Next egg in §f" + ChocolateFactoryMenu.duration((soonest - now + 999) / 1000)
                + (ready > 0 ? " §7| §a" + ready + " to find" : ""));
        out.add(locatorLine(now));
        if (EggTracker.enabled()) {
            long[] t = EggTracker.totals();
            EggTracker.Hunt h = EggTracker.current();
            out.add("§dTracker: §f" + (h == null ? 0 : h.eggs) + " §7eggs this hunt §8| §f" + t[0] + " §7total over §f"
                    + t[4] + " §7hunts");
        }
        int eggs = preview ? 5 : sessionEggs;
        if (eggs > 0 || preview) {
            int dupes = preview ? 3 : sessionDupes;
            long choc = preview ? 12_300_000L : sessionDupeChocolate;
            out.add("§7Session: §f" + eggs + " §7eggs, §a" + (preview ? 2 : sessionNew) + " §7new, §e" + dupes
                    + " §7dupes §6+" + ChocolateFactoryMenu.compact(choc));
        }
        // Duplicate Rabbit Values had its own switch until 2026-10-19; it is part of Egg Extra Info now.
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

        // The lines change by the second at most, but width(), height() and render() each asked for them every frame:
        // build them (and measure) at most every 100 ms, and at once when the preview flips.
        private long builtMs;
        private boolean builtPreview;
        private List<String> built = List.of();
        private int builtWidth = 1;

        private List<String> cachedLines() {
            boolean preview = HudVisibility.editorOpen();
            long now = System.currentTimeMillis();
            if (builtMs == 0L || preview != builtPreview || now - builtMs >= 100L || now < builtMs) {
                built = lines(preview);
                builtPreview = preview;
                builtMs = now;
                int w = 1;
                for (String line : built) {
                    w = Math.max(w, Minecraft.getInstance().font.width(line));
                }
                builtWidth = w;
            }
            return built;
        }

        @Override
        public int width() {
            cachedLines();
            return builtWidth;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(Math.max(1, cachedLines().size()), 9);
        }

        @Override
        public boolean isEnabledInSettings() {
            return shown();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            List<String> lines = cachedLines();
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
