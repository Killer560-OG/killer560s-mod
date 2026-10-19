package com.killer560.hub.chocolatefactory.egg;

/**
 * Hoppity's Hunt calendar maths, pure (no Minecraft): when each meal's Chocolate Egg last spawned, when the next one
 * spawns, and whether the hunt is on. Our own code; the facts it encodes are from the Skyblock wiki's Hoppity's Hunt
 * page (hypixelskyblock.minecraft.wiki, read 2026-10-19) and Skyblocker's EggFinder (LGPL-3.0, read for facts only):
 * <ul>
 * <li>A Skyblock day is 20 real minutes, an hour 50 s, a month 31 days, a year 12 months. The calendar's day 0 began at
 * {@link #EPOCH_MS} (the same constant {@code scoreboard/ScoreboardExtraData} uses, SkyHanni's SkyBlockTime).</li>
 * <li>The hunt runs from Early Spring 1st to the end of Late Spring: the first 93 days of every year, 3 eggs a day,
 * 279 a year.</li>
 * <li>Three meals: Breakfast at 7:00, Lunch at 14:00, Dinner at 21:00 Skyblock time (real XX:00:50, XX:06:40,
 * XX:12:30 and every 20 min after, which this epoch reproduces). On even days of the year (1-based) the same eggs are
 * called Brunch, Dejeuner and Supper.</li>
 * <li>Each meal's egg stays until the next egg of that meal spawns (6 live eggs at most, oldest replaced), so during
 * the hunt every meal always has exactly one live egg.</li>
 * </ul>
 */
public final class HoppityCalendar {

    public static final long EPOCH_MS = 1559829300000L;
    public static final long HOUR_MS = 50_000L;
    public static final long DAY_MS = 24 * HOUR_MS;
    public static final int DAYS_PER_YEAR = 12 * 31;
    public static final long YEAR_MS = DAYS_PER_YEAR * DAY_MS;
    public static final int HUNT_DAYS = 3 * 31;
    public static final int EGGS_PER_HUNT = HUNT_DAYS * 3;

    /** The three spawning meals, in spawn order. */
    public enum Meal {
        BREAKFAST("Breakfast", "Brunch", 7, "§6"),
        LUNCH("Lunch", "Déjeuner", 14, "§9"),
        DINNER("Dinner", "Supper", 21, "§a");

        public final String oddName;
        public final String evenName;
        public final int hour;
        public final String color;

        Meal(String oddName, String evenName, int hour, String color) {
            this.oddName = oddName;
            this.evenName = evenName;
            this.hour = hour;
            this.color = color;
        }

        /** The meal whose egg is called {@code name} on either day parity, or null. */
        public static Meal byName(String name) {
            for (Meal m : values()) {
                if (m.oddName.equalsIgnoreCase(name) || m.evenName.equalsIgnoreCase(name)
                        || ("Dejeuner".equalsIgnoreCase(name) && m == LUNCH)) {
                    return m;
                }
            }
            return null;
        }

        long offsetMs() {
            return hour * HOUR_MS;
        }
    }

    private HoppityCalendar() {
    }

    /** Whole Skyblock days since the epoch. */
    public static long dayIndex(long nowMs) {
        return Math.floorDiv(nowMs - EPOCH_MS, DAY_MS);
    }

    /** 0-based day of the Skyblock year. */
    public static int dayOfYear(long dayIndex) {
        return (int) Math.floorMod(dayIndex, (long) DAYS_PER_YEAR);
    }

    public static boolean huntDay(long dayIndex) {
        return dayOfYear(dayIndex) < HUNT_DAYS;
    }

    public static boolean huntActive(long nowMs) {
        return huntDay(dayIndex(nowMs));
    }

    /** The egg's name on the day it spawned: Breakfast on odd days of the year (1-based), Brunch on even ones. */
    public static String eggName(Meal meal, long spawnDayIndex) {
        return (dayOfYear(spawnDayIndex) + 1) % 2 == 1 ? meal.oddName : meal.evenName;
    }

    private static long spawnAt(Meal meal, long dayIndex) {
        return EPOCH_MS + dayIndex * DAY_MS + meal.offsetMs();
    }

    /** When the live egg of {@code meal} spawned (ms), or -1 when none has spawned this hunt / the hunt is off. */
    public static long lastSpawn(Meal meal, long nowMs) {
        long day = dayIndex(nowMs);
        long at = spawnAt(meal, day);
        if (at > nowMs) {
            day--;
            at -= DAY_MS;
        }
        return huntDay(day) && huntActive(nowMs) ? at : -1;
    }

    /** The day index the egg spawned at {@code spawnMs} belongs to. */
    public static long spawnDay(long spawnMs) {
        return dayIndex(spawnMs);
    }

    /** When {@code meal} next spawns (ms), skipping forward to the next hunt when this one is over. */
    public static long nextSpawn(Meal meal, long nowMs) {
        long day = dayIndex(nowMs);
        long at = spawnAt(meal, day);
        if (at <= nowMs) {
            day++;
            at += DAY_MS;
        }
        if (!huntDay(day)) {
            long yearStart = day - dayOfYear(day);
            day = yearStart + DAYS_PER_YEAR;
            at = spawnAt(meal, day);
        }
        return at;
    }

    /** Whether the egg spawned on {@code spawnDayIndex} carries the odd-day name (Breakfast, Lunch, Dinner). */
    public static boolean oddDay(long spawnDayIndex) {
        return (dayOfYear(spawnDayIndex) + 1) % 2 == 1;
    }

    /** When the live egg of {@code meal} under its odd ({@code odd}) or even name spawned (ms), or -1 when that one has
     *  not spawned this hunt / the hunt is off. Each of the six named eggs stays until its own next spawn, two days on. */
    public static long lastSpawn(Meal meal, boolean odd, long nowMs) {
        long at = lastSpawn(meal, nowMs);
        if (at < 0) {
            return -1;
        }
        if (oddDay(spawnDay(at)) != odd) {
            at -= DAY_MS;
        }
        return huntDay(spawnDay(at)) ? at : -1;
    }

    /** When {@code meal} next spawns under its odd ({@code odd}) or even name (ms), skipping into the next hunt. */
    public static long nextSpawn(Meal meal, boolean odd, long nowMs) {
        long at = nextSpawn(meal, nowMs);
        // Names alternate by day, so the right one is at most two spawns on (three across a hunt's end).
        for (int i = 0; i < 4 && oddDay(spawnDay(at)) != odd; i++) {
            at = nextSpawn(meal, at);
        }
        return at;
    }

    /** When the next hunt starts (ms); in the past or now when one is on. */
    public static long nextHuntStart(long nowMs) {
        long day = dayIndex(nowMs);
        long yearStart = day - dayOfYear(day);
        if (huntDay(day)) {
            return EPOCH_MS + yearStart * DAY_MS;
        }
        return EPOCH_MS + (yearStart + DAYS_PER_YEAR) * DAY_MS;
    }

    /** Eggs spawned so far this hunt (0-279); 0 outside it. */
    public static int eggsSpawnedThisHunt(long nowMs) {
        long day = dayIndex(nowMs);
        if (!huntDay(day)) {
            return 0;
        }
        int count = dayOfYear(day) * 3;
        long into = nowMs - (EPOCH_MS + day * DAY_MS);
        for (Meal m : Meal.values()) {
            if (into >= m.offsetMs()) {
                count++;
            }
        }
        return count;
    }
}
