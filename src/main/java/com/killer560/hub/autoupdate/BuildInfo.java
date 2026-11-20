package com.killer560.hub.autoupdate;

import com.killer560.hub.BuildVariant;

import java.io.InputStream;
import java.util.Properties;

/**
 * What this jar is, from {@code killer560smod-build.properties} (build.gradle's generateBuildInfo task): the commit it
 * was built from and its Minecraft version. The variant comes from {@link BuildVariant}, the one source of truth.
 */
public final class BuildInfo {

    public static final String RESOURCE = "/killer560smod-build.properties";

    private static volatile Properties props;

    private BuildInfo() {
    }

    private static Properties props() {
        Properties p = props;
        if (p == null) {
            p = new Properties();
            try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
                if (in != null) {
                    p.load(in);
                }
            } catch (Exception ignored) {
            }
            props = p;
        }
        return p;
    }

    /** The full 40-hex commit, or {@code "unknown"}. */
    public static String commit() {
        String c = props().getProperty("commit", "unknown").trim();
        return c.matches("[0-9a-f]{40}") ? c : "unknown";
    }

    /** The first 7 characters of {@link #commit()}. */
    public static String shortCommit() {
        String c = commit();
        return c.length() >= 7 && !c.equals("unknown") ? c.substring(0, 7) : c;
    }

    /** The Minecraft version in this jar's name ({@code 26.1.2}, {@code 26.2}), or {@code null}. */
    public static String minecraft() {
        String v = props().getProperty("minecraft");
        return v == null || v.isBlank() ? null : v.trim();
    }

    public static String variant() {
        return BuildVariant.CHEAT_FEATURES_ENABLED ? "cheat" : "legit";
    }

    private static boolean flag(String key) {
        return "true".equals(props().getProperty(key, "").trim());
    }

    /** Bug report lines saying exactly which build runs: full commit, variant, release or dev, testing, jar name and size. */
    public static String reportLines() {
        StringBuilder sb = new StringBuilder();
        sb.append("Build commit: ").append(commit()).append('\n');
        sb.append("Build variant: ").append(variant()).append('\n');
        sb.append("Build channel: ").append(flag("release") ? "release" : "dev").append('\n');
        sb.append("Build testing: ").append(flag("testing")).append('\n');
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("killer560smod").ifPresent(m -> {
            try {
                java.nio.file.Path jar = m.getOrigin().getPaths().get(0);
                if (java.nio.file.Files.isRegularFile(jar)) {
                    sb.append("Mod jar: ").append(jar.getFileName()).append(" (").append(java.nio.file.Files.size(jar)).append(" bytes)\n");
                }
            } catch (Exception ignored) {
            }
        });
        return sb.toString();
    }
}
