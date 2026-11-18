package com.killer560.hub.accounts;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * The Swap Accounts screen's sort and proxy filter, kept across restarts (acctswap-1117). A view preference of this
 * instance: not in a profile ({@code ProfileManager.EXCLUDED_FILES}).
 */
public final class AccountViewPrefs {

    public enum Sort {
        DEFAULT("Default"), NAME_AZ("Name A-Z"), NAME_ZA("Name Z-A"), RECENT("Recently Played"), PLAYTIME("Most Playtime");

        public final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    public enum ProxyFilter {
        ALL("All"), WITH("With Proxy"), NONE("No Proxy");

        public final String label;

        ProxyFilter(String label) {
            this.label = label;
        }
    }

    private static final Path PATH = ModPaths.config("killer560smod-account-swapper-view.json");
    private static Sort sort = Sort.DEFAULT;
    private static ProxyFilter filter = ProxyFilter.ALL;
    private static boolean loaded;

    private AccountViewPrefs() {
    }

    public static synchronized Sort sort() {
        load();
        return sort;
    }

    public static synchronized ProxyFilter filter() {
        load();
        return filter;
    }

    public static synchronized void setSort(Sort s) {
        load();
        sort = s;
        save();
    }

    public static synchronized void setFilter(ProxyFilter f) {
        load();
        filter = f;
        save();
    }

    /** Puts the defaults back and reads the file again (a test's reload; a missing file means the defaults). */
    public static synchronized void reload() {
        loaded = false;
        sort = Sort.DEFAULT;
        filter = ProxyFilter.ALL;
        load();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            sort = ConfigJson.getEnum(o, "sort", Sort.class, Sort.DEFAULT);
            filter = ConfigJson.getEnum(o, "proxyFilter", ProxyFilter.class, ProxyFilter.ALL);
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("sort", sort.name());
        o.addProperty("proxyFilter", filter.name());
        ConfigJson.write(PATH, o);
    }
}
