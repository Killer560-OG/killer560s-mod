package com.killer560.hub.util;

import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Every place the mod hands something to the operating system to open - a folder in Explorer, a link in the
 * default browser, a browser window it launches itself - goes through here, so a test harness can stop a test
 * run from popping windows on the desktop.
 *
 * <p>With {@code -Dkiller560.test.noExternalOpen=true} nothing is opened and {@code [TestHook] would open
 * <target>} is logged instead (at WARN, so it survives the root INFO level). Without the property every method
 * does exactly what its call site did before.
 */
public final class ExternalOpen {

    private static final Logger LOGGER = ModLog.get("killer560smod-externalopen");

    public static final String NO_EXTERNAL_OPEN_PROPERTY = "killer560.test.noExternalOpen";

    private ExternalOpen() {
    }

    /** True when the harness asked for no OS opens. Read on every call. */
    public static boolean suppressed() {
        return Boolean.getBoolean(NO_EXTERNAL_OPEN_PROPERTY);
    }

    /** {@code Util.getPlatform().openUri(uri)}, or a log line under the test property. */
    public static void uri(String uri) {
        if (suppress(uri)) return;
        Util.getPlatform().openUri(uri);
    }

    /** {@code Util.getPlatform().openPath(path)}, or a log line under the test property. */
    public static void path(Path path) {
        if (suppress(String.valueOf(path))) return;
        Util.getPlatform().openPath(path);
    }

    /**
     * For a launch the caller does itself (a browser process): returns true, after logging, when the launch must
     * be skipped; false when the caller should go ahead.
     */
    public static boolean suppress(String target) {
        if (!suppressed()) return false;
        LOGGER.warn("[TestHook] would open {}", target);
        return true;
    }

    /** {@link #suppress(String)} for a command line. */
    public static boolean suppress(List<String> command) {
        return suppress(String.join(" ", command));
    }
}
