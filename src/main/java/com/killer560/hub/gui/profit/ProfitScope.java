package com.killer560.hub.gui.profit;

import com.google.gson.JsonObject;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.profiles.ProfileManager;
import net.minecraft.client.Minecraft;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Which Minecraft account, Skyblock profile and mod profile a piece of profit data belongs to, and which of them a
 * tracker screen is showing.
 *
 * <p>killer560, 2026-10-11: "In profit trackers have an option where you can select specific Minecraft accounts,
 * specific Minecraft profiles, and specific mod profiles, each tracked individually. Also add an option for all
 * profit ever found." Every record a tracker stores carries a {@link Key} (written by {@link Key#writeTo}); a
 * screen picks a {@link Filter} (any field null = every value) and sums only the records that match it. The filter
 * with all three fields null is "All Profit".
 *
 * <p>Records from before this existed have no key and read back as {@link #UNKNOWN} in all three fields, so they
 * still count under All Profit and can be picked out as "?" on each switch.
 */
public final class ProfitScope {

    /** A field that was not recorded (data from before scopes, or the profile not known yet). */
    public static final String UNKNOWN = "?";
    /** The mod-profile field when no mod profile has been applied. */
    public static final String NO_MOD_PROFILE = "none";

    /** The last Skyblock profile seen, because {@link ProfileTracker} forgets it on every world change until the
     *  tab list or the join message tells it again. */
    private static String lastSkyblockProfile = "";

    private ProfitScope() {
    }

    /** Where one record came from. */
    public record Key(String account, String profile, String modProfile) {
        public Key {
            account = clean(account);
            profile = clean(profile);
            modProfile = clean(modProfile);
        }

        public void writeTo(JsonObject o) {
            o.addProperty("account", account);
            o.addProperty("sbProfile", profile);
            o.addProperty("modProfile", modProfile);
        }

        public static Key readFrom(JsonObject o) {
            return new Key(str(o, "account"), str(o, "sbProfile"), str(o, "modProfile"));
        }

        public boolean matches(Filter f) {
            return f == null || ((f.account == null || f.account.equals(account))
                    && (f.profile == null || f.profile.equals(profile))
                    && (f.modProfile == null || f.modProfile.equals(modProfile)));
        }
    }

    /** What a screen shows: null in a field means every value of it. */
    public static final class Filter {
        public String account;
        public String profile;
        public String modProfile;

        public boolean isAll() {
            return account == null && profile == null && modProfile == null;
        }

        public void clear() {
            account = null;
            profile = null;
            modProfile = null;
        }

        /** "All Profit", or the chosen values joined, for a reset prompt or a heading. */
        public String describe() {
            if (isAll()) {
                return "All Profit";
            }
            StringBuilder sb = new StringBuilder();
            if (account != null) {
                sb.append(account);
            }
            if (profile != null) {
                sb.append(sb.length() > 0 ? " / " : "").append(profile);
            }
            if (modProfile != null) {
                sb.append(sb.length() > 0 ? " / " : "").append("mod ").append(modProfile);
            }
            return sb.toString();
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (account != null) {
                o.addProperty("account", account);
            }
            if (profile != null) {
                o.addProperty("sbProfile", profile);
            }
            if (modProfile != null) {
                o.addProperty("modProfile", modProfile);
            }
            return o;
        }

        public static Filter fromJson(JsonObject o) {
            Filter f = new Filter();
            if (o != null) {
                f.account = o.has("account") ? o.get("account").getAsString() : null;
                f.profile = o.has("sbProfile") ? o.get("sbProfile").getAsString() : null;
                f.modProfile = o.has("modProfile") ? o.get("modProfile").getAsString() : null;
            }
            return f;
        }

        public Filter copy() {
            Filter f = new Filter();
            f.account = account;
            f.profile = profile;
            f.modProfile = modProfile;
            return f;
        }
    }

    /** One of the three switches. */
    public enum Field {
        ACCOUNT("Account", Key::account),
        PROFILE("Profile", Key::profile),
        MOD_PROFILE("Mod Profile", Key::modProfile);

        public final String label;
        private final Function<Key, String> read;

        Field(String label, Function<Key, String> read) {
            this.label = label;
            this.read = read;
        }

        public String get(Filter f) {
            return switch (this) {
                case ACCOUNT -> f.account;
                case PROFILE -> f.profile;
                case MOD_PROFILE -> f.modProfile;
            };
        }

        public void set(Filter f, String value) {
            switch (this) {
                case ACCOUNT -> f.account = value;
                case PROFILE -> f.profile = value;
                case MOD_PROFILE -> f.modProfile = value;
            }
        }

        /** Every value of this field in {@code keys}, sorted. */
        public List<String> values(Collection<Key> keys) {
            TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Key k : keys) {
                out.add(read.apply(k));
            }
            return List.copyOf(out);
        }

        /** The next value after the filter's current one: All, then each value seen, then All again
         *  ({@code step} -1 goes backwards, for a right click). */
        public void cycle(Filter f, Collection<Key> keys, int step) {
            List<String> vals = values(keys);
            int n = vals.size() + 1; // index 0 is "All"
            String cur = get(f);
            int i = cur == null ? 0 : vals.indexOf(cur) + 1;
            if (i < 0) {
                i = 0;
            }
            int next = Math.floorMod(i + step, n);
            set(f, next == 0 ? null : vals.get(next - 1));
        }
    }

    /** The key for something recorded right now. */
    public static Key current() {
        return new Key(currentAccount(), currentSkyblockProfile(), currentModProfile());
    }

    public static String currentAccount() {
        try {
            Minecraft client = Minecraft.getInstance();
            return client.getUser() == null ? UNKNOWN : client.getUser().getName();
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    public static String currentSkyblockProfile() {
        String now = ProfileTracker.profileName();
        if (now != null && !now.isBlank()) {
            lastSkyblockProfile = now;
        }
        return lastSkyblockProfile.isBlank() ? UNKNOWN : lastSkyblockProfile;
    }

    public static String currentModProfile() {
        String active = ProfileManager.getActiveProfile();
        return active == null || active.isBlank() ? NO_MOD_PROFILE : active;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? UNKNOWN : s.trim();
    }

    private static String str(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Account: All" style label for one switch. */
    public static String label(Field field, Filter f) {
        String v = field.get(f);
        return field.label + ": " + (v == null ? "All" : v);
    }
}
