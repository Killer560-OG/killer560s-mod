package com.killer560.hub.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which feature tabs are tested, in a testing build only (see build.gradle's testingBuild).
 *
 * <p>Two sources. The repo list {@code testing/tested-<variant>.txt} (bundled into the testing jar) is the source of
 * truth Claude Code edits when killer560 approves a feature. His own marks, made with the Mark tested / Mark untested
 * button on a tab, are kept in {@code killer560smod-tested-<variant>.json} in his config and win over the repo list,
 * so a mark takes effect at once; {@code /k560tested list} prints them so they can be copied into the repo list.
 * The cheat and legit testing builds keep separate lists and separate marks.
 *
 * <p>A tab is named by its class's simple name (e.g. {@code AutoRoutesTab}): stable, unique, and independent of the
 * label shown, which may change.
 */
public final class TestedFeatures {

    private static final Logger LOGGER = ModLog.get("killer560smod-testing");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Set<String> repo;
    private static final Set<String> markedTested = new TreeSet<>();
    private static final Set<String> markedUntested = new TreeSet<>();
    private static boolean marksLoaded;

    private TestedFeatures() {
    }

    public static String id(BaseTab tab) {
        return tab.getClass().getSimpleName();
    }

    public static synchronized boolean isTested(BaseTab tab) {
        String id = id(tab);
        ensureLoaded();
        if (markedUntested.contains(id)) {
            return false;
        }
        return markedTested.contains(id) || repo.contains(id);
    }

    /** Record killer560's mark for {@code tab} and save it. */
    public static synchronized void mark(BaseTab tab, boolean tested) {
        ensureLoaded();
        String id = id(tab);
        if (tested) {
            markedUntested.remove(id);
            markedTested.add(id);
        } else {
            markedTested.remove(id);
            markedUntested.add(id);
        }
        save();
        LOGGER.info("[Testing] {} marked {} ({} build)", id, tested ? "tested" : "untested", TestingBuild.VARIANT);
    }

    public static synchronized Set<String> repoList() {
        ensureLoaded();
        return Collections.unmodifiableSet(repo);
    }

    public static synchronized List<String> marksTested() {
        ensureLoaded();
        return new ArrayList<>(markedTested);
    }

    public static synchronized List<String> marksUntested() {
        ensureLoaded();
        return new ArrayList<>(markedUntested);
    }

    public static Path marksFile() {
        return ModPaths.config("killer560smod-tested-" + TestingBuild.VARIANT + ".json");
    }

    /** Forget everything read so far (the testkit points the config dir elsewhere between cases). */
    public static synchronized void reload() {
        repo = null;
        marksLoaded = false;
        markedTested.clear();
        markedUntested.clear();
    }

    private static void ensureLoaded() {
        if (repo == null) {
            repo = readRepoList();
        }
        if (!marksLoaded) {
            marksLoaded = true;
            readMarks();
        }
    }

    private static Set<String> readRepoList() {
        Set<String> out = new LinkedHashSet<>();
        String resource = "/killer560smod-testing/tested-" + TestingBuild.VARIANT + ".txt";
        try (InputStream in = TestedFeatures.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("[Testing] {} is not in the jar - every feature counts as untested", resource);
                return out;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                int hash = line.indexOf('#');
                String id = (hash >= 0 ? line.substring(0, hash) : line).trim();
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Testing] could not read {}", resource, e);
        }
        return out;
    }

    private static void readMarks() {
        Path file = marksFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            addAll(o.get("tested"), markedTested);
            addAll(o.get("untested"), markedUntested);
        } catch (Exception e) {
            LOGGER.warn("[Testing] could not read {}", file, e);
        }
    }

    private static void addAll(JsonElement e, Set<String> into) {
        if (e == null || !e.isJsonArray()) {
            return;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            if (x != null && x.isJsonPrimitive()) {
                into.add(x.getAsString());
            }
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("variant", TestingBuild.VARIANT);
        JsonArray t = new JsonArray();
        markedTested.forEach(t::add);
        JsonArray u = new JsonArray();
        markedUntested.forEach(u::add);
        o.add("tested", t);
        o.add("untested", u);
        Path file = marksFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Testing] could not save {}", file, e);
        }
    }
}
