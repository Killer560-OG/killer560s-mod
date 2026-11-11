package com.killer560.hub.carnival;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the Carnival Tracker keeps between sessions, a RECORD in the shared store ({@link RecordCatalog#CARNIVAL},
 * carnival-history.json for the signed-in account): best score and games played per minigame, tokens earned in all, the
 * UTC day the daily Carnival Tickets were last claimed, and the Carnival Goals last read from Chantelle's menu. The file
 * is read once on the writer thread at register ({@link #warm()}); every save merges onto the disk there.
 */
public final class CarnivalHistory {

    private static final Gson GSON = new Gson();

    /** The saved shape. */
    static final class Data {
        Map<String, Integer> best = new LinkedHashMap<>();
        Map<String, Integer> played = new LinkedHashMap<>();
        long tokensEarned;
        String ticketsClaimedDay = "";
        /** Goal name -> reached. */
        Map<String, Boolean> goals = new LinkedHashMap<>();
        long goalsReadAtMs;
    }

    private static volatile Data data = new Data();

    private CarnivalHistory() {
    }

    /** Reads the record on the writer thread (at register, and again after an account switch). */
    static void warm() {
        SharedWriter.submit(() -> data = fromJson(SharedRecords.load(SharedRecords.file(RecordCatalog.CARNIVAL))));
    }

    static Data get() {
        return data;
    }

    static void save() {
        JsonObject snap = GSON.toJsonTree(data).getAsJsonObject();
        SharedWriter.submit(() -> data = fromJson(SharedRecords.save(SharedRecords.file(RecordCatalog.CARNIVAL), snap)));
    }

    private static Data fromJson(JsonObject o) {
        Data d;
        try {
            d = GSON.fromJson(o, Data.class);
        } catch (Exception e) {
            d = null;
        }
        if (d == null) {
            d = new Data();
        }
        if (d.best == null) {
            d.best = new LinkedHashMap<>();
        }
        if (d.played == null) {
            d.played = new LinkedHashMap<>();
        }
        if (d.goals == null) {
            d.goals = new LinkedHashMap<>();
        }
        if (d.ticketsClaimedDay == null) {
            d.ticketsClaimedDay = "";
        }
        return d;
    }
}
