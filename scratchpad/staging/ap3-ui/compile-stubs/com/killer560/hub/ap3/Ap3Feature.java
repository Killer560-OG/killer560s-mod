package com.killer560.hub.ap3;

import java.util.List;

/** THROWAWAY STUB - declared API. Return types of addNode/addWaitNode/deleteNode are this half's assumption. */
public final class Ap3Feature {
    public static Ap3Node addNode(Ap3Node.Type type) { return null; }
    public static Ap3Node addWaitNode(int millis) { return null; }
    public static List<Ap3Node> currentChainNodes() { return List.of(); }
    public static boolean deleteNode(int index) { return false; }
    public static void clearCurrentChain() {}
    public static void setEditMode(boolean on) {}
    public static boolean isEditMode() { return false; }
}
