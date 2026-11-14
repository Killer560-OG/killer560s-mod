package com.killer560.hub.whatsnew;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The What's New screen's data: the bundled {@code assets/killer560smod/whatsnew.json}, a list (newest first) of
 * {@code {date, summary, sections:[{title, blurb, items:[text | {text, cheat}]}]}}. Read once, lazily, off the render
 * thread (the screen starts it); an item marked {@code cheat} only shows in a cheat build, and a section left with no
 * items is dropped. {@code tools/whatsnew-add.py} appends a ship's changelog lines.
 */
public final class WhatsNewData {

    public record Section(String title, String blurb, List<String> items) {
    }

    public record Update(String date, String summary, List<Section> sections) {
    }

    private static final Logger LOGGER = ModLog.get("killer560smod-whatsnew");
    private static final String RESOURCE = "/assets/killer560smod/whatsnew.json";
    private static volatile List<Update> loaded;
    private static CompletableFuture<Void> loading;

    private WhatsNewData() {
    }

    /** The updates once read, else null (the read runs in the background after the first call). */
    public static synchronized List<Update> get() {
        if (loaded == null && loading == null) {
            loading = CompletableFuture.runAsync(() -> loaded = parse());
        }
        return loaded;
    }

    static List<Update> parse() {
        List<Update> out = new ArrayList<>();
        try (var in = WhatsNewData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject u = e.getAsJsonObject();
                List<Section> secs = new ArrayList<>();
                for (JsonElement se : u.getAsJsonArray("sections")) {
                    JsonObject s = se.getAsJsonObject();
                    List<String> items = new ArrayList<>();
                    for (JsonElement ie : s.getAsJsonArray("items")) {
                        boolean cheat = ie.isJsonObject() && ie.getAsJsonObject().has("cheat")
                                && ie.getAsJsonObject().get("cheat").getAsBoolean();
                        if (!cheat || BuildVariant.CHEAT_FEATURES_ENABLED) {
                            items.add(ie.isJsonObject() ? ie.getAsJsonObject().get("text").getAsString() : ie.getAsString());
                        }
                    }
                    if (!items.isEmpty()) {
                        secs.add(new Section(s.get("title").getAsString(), s.get("blurb").getAsString(), items));
                    }
                }
                if (!secs.isEmpty()) {
                    out.add(new Update(u.get("date").getAsString(), u.get("summary").getAsString(), secs));
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("whatsnew.json unreadable: {}", ex.toString());
        }
        return out;
    }
}
