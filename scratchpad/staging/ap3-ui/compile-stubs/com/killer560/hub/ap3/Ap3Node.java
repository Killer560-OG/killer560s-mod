package com.killer560.hub.ap3;

/** THROWAWAY STUB - only so the UI half type-checks. The core agent's real class replaces this. */
public final class Ap3Node {
    public enum Type { LINE, AXIS_LINE, WALK, RUN, LEAP, LEAP_DETECTOR, TERMINAL, WAIT, STOP, LOOK, BREAKER }

    public Type type() { return Type.LINE; }
    public double x() { return 0; }
    public double y() { return 0; }
    public double z() { return 0; }
    public double length() { return 0; }
    public double width() { return 0; }
    public int waitMillis() { return 0; }
    public String leapModifier() { return null; }
}
