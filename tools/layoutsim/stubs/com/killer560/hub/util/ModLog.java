package com.killer560.hub.util;

import java.lang.reflect.Proxy;

/** Offline stand-in: prints WARN and ERROR (INFO when -Dverbose=true) with slf4j's {} filled in. */
public final class ModLog {
    public static int warnings;

    public static org.slf4j.Logger get(String name) {
        boolean verbose = Boolean.getBoolean("verbose");
        return (org.slf4j.Logger) Proxy.newProxyInstance(ModLog.class.getClassLoader(),
                new Class<?>[]{org.slf4j.Logger.class}, (p, m, a) -> {
                    String level = m.getName();
                    if (m.getReturnType() == boolean.class) {
                        return true;
                    }
                    if (a == null || a.length == 0 || !(a[0] instanceof String fmt)) {
                        return null;
                    }
                    if (level.equals("warn")) {
                        warnings++;
                    }
                    if (!(level.equals("warn") || level.equals("error") || (verbose && level.equals("info")))) {
                        return null;
                    }
                    Object[] args = a.length == 2 && a[1] instanceof Object[] arr ? arr
                            : java.util.Arrays.copyOfRange(a, 1, a.length);
                    StringBuilder sb = new StringBuilder();
                    int ai = 0;
                    for (int i = 0; i < fmt.length(); i++) {
                        if (fmt.startsWith("{}", i) && ai < args.length) {
                            sb.append(args[ai++]);
                            i++;
                        } else {
                            sb.append(fmt.charAt(i));
                        }
                    }
                    System.out.println("  [" + level.toUpperCase() + "] " + sb);
                    return null;
                });
    }
}
