package com.killer560.hub.ap3;

import java.nio.file.Path;
import java.util.List;

/** THROWAWAY STUB - declared API: chains(), forSection(int), save(), reload(), directory(). */
public final class Ap3Store {
    private static final Ap3Store I = new Ap3Store();
    public static Ap3Store getInstance() { return I; }
    public List<Object> chains() { return List.of(); }
    public Object forSection(int section) { return null; }
    public void save() {}
    public void reload() {}
    public Path directory() { return Path.of("."); }
}
