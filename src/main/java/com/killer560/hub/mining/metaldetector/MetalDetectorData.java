package com.killer560.hub.mining.metaldetector;

import net.minecraft.core.Vec3i;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixed facts the Mines of Divan treasure hunt runs on.
 *
 * <p>killer560 (2026-09-30): "there needs to be a legit metal detector setting that just does the
 * calculations and shows waypoint with a line to it", and "you should be able to reference tons of other
 * mods for how they calculate the metal detector". He named SkyHanni and Skyblocker.
 *
 * <p><b>Where these numbers come from.</b> Every value here was read out of Skyblocker's own
 * {@code MetalDetector} class in {@code skyblocker-6.4.1+1.21.11.jar} on 2026-09-30 - the two patterns, the
 * four Keeper offsets and all 42 candidate chest offsets - by disassembling its static initialiser and
 * parsing the pushed constants programmatically rather than transcribing them by eye. Forty-two triples read
 * by hand would have had a digit wrong somewhere, and a wrong digit here is a waypoint on the wrong block
 * with nothing on screen to say so.
 *
 * <p><b>Why a fixed candidate list works at all.</b> The Mines of Divan is the one part of the Crystal
 * Hollows that is NOT laid out differently per lobby: its interior is a fixed structure, so a chest can only
 * be at one of a known set of positions relative to the mines' own centre. That is what turns "I am 37.4m
 * from something" into a solvable problem - it is not trilateration in open space, it is elimination over a
 * list of 42.
 */
public final class MetalDetectorData {

    /**
     * The treasure readout, off the action bar: {@code TREASURE: 37.4m}.
     *
     * <p>The section signs are real - Hypixel colours the line, and the pattern matches the coloured form
     * because that is what arrives. Matching the stripped text instead would also match a player typing it.
     */
    public static final Pattern TREASURE = Pattern.compile("(§3§lTREASURE: §b)(\\d+\\.?\\d?)m");

    /** The four armour stands that mark the mines' corners: "Keeper of Diamond" and friends. */
    public static final Pattern KEEPER = Pattern.compile("Keeper of (\\w+)");

    /**
     * Each Keeper's offset from the CENTRE of the mines.
     *
     * <p>Finding any one Keeper therefore locates the centre, and the centre is what every candidate offset
     * below is measured from. Four of them rather than one because you will not always be able to see a
     * particular corner from where you are standing.
     */
    public static final Map<String, Vec3i> KEEPER_OFFSETS = Map.of(
            "Diamond", new Vec3i(33, 0, 3),
            "Lapis", new Vec3i(-33, 0, -3),
            "Emerald", new Vec3i(-3, 0, 33),
            "Gold", new Vec3i(3, 0, -33));

    /** Every position a treasure chest can be, relative to the mines' centre. */
    public static final List<Vec3i> CHEST_OFFSETS = List.of(
            new Vec3i(-38, -22, 26),
            new Vec3i(38, -22, -26),
            new Vec3i(-40, -22, 18),
            new Vec3i(-41, -20, 22),
            new Vec3i(-5, -21, 16),
            new Vec3i(40, -22, -30),
            new Vec3i(-42, -20, -28),
            new Vec3i(-43, -22, -40),
            new Vec3i(42, -19, -41),
            new Vec3i(43, -21, -16),
            new Vec3i(-1, -22, -20),
            new Vec3i(6, -21, 28),
            new Vec3i(7, -21, 11),
            new Vec3i(7, -21, 22),
            new Vec3i(-12, -21, -44),
            new Vec3i(12, -22, 31),
            new Vec3i(12, -22, -22),
            new Vec3i(12, -21, 7),
            new Vec3i(12, -21, -43),
            new Vec3i(-14, -21, 43),
            new Vec3i(-14, -21, 22),
            new Vec3i(-17, -21, 20),
            new Vec3i(-20, -22, 0),
            new Vec3i(1, -21, 20),
            new Vec3i(19, -22, 29),
            new Vec3i(20, -22, 0),
            new Vec3i(20, -21, -26),
            new Vec3i(-23, -22, 40),
            new Vec3i(22, -21, -14),
            new Vec3i(-24, -22, 12),
            new Vec3i(23, -22, 26),
            new Vec3i(23, -22, -39),
            new Vec3i(24, -22, 27),
            new Vec3i(25, -22, 17),
            new Vec3i(29, -21, -44),
            new Vec3i(-31, -21, -12),
            new Vec3i(-31, -21, -40),
            new Vec3i(30, -21, -25),
            new Vec3i(-32, -21, -40),
            new Vec3i(-36, -20, 42),
            new Vec3i(-37, -21, -14),
            new Vec3i(-37, -21, -22));

    /**
     * How far a candidate's distance may differ from the readout and still be kept.
     *
     * <p>Skyblocker's own figure. The readout is rounded to one decimal place, so an exact comparison would
     * discard the right answer; a quarter of a block covers that rounding without being loose enough to keep
     * two candidates that a single reading should have separated.
     */
    public static final double TOLERANCE = 0.25;

    private MetalDetectorData() {
    }
}
