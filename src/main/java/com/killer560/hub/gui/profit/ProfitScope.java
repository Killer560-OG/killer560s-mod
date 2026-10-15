package com.killer560.hub.gui.profit;

import com.google.gson.JsonObject;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.profiles.ProfileManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>killer560, 2026-10-15: profit is separate per Minecraft account by default, with an option for all accounts, and
 * shared across mod profiles or kept per mod profile ({@link ProfitConfig}). A record's account is its UUID
 * ({@link Key#accountId}; the name beside it is only for display, so a renamed account keeps its profit), and what
 * a screen shows is its saved filter put through {@link #resolve}: the account from Show Profit, the mod profile
 * forced to the current one under Per Mod Profile.
 *
 * <p>Records from before the account UUID was stored are given one the first time the current account is known
 * ({@link #migrated}): the account that recorded it when its name says so (the current account, or one of Prism's
 * saved accounts), otherwise - data from before any names were kept - the current account.
 */
public final class ProfitScope {

    /** A field that was not recorded (data from before scopes, or the profile not known yet). */
    public static final String UNKNOWN = "?";
    /** The mod-profile field when no mod profile has been applied. */
    public static final String NO_MOD_PROFILE = "none";

    /** The last Skyblock profile seen, because {@link ProfileTracker} forgets it on every world change until the
     *  tab list or the join message tells it again. */
    private static String lastSkyblockProfile = "";

    /** Every account UUID seen with the name it last had (records are read oldest first, the live account last). */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    /** Prism's saved accounts, lower-case name -> UUID, read once for the migration. */
    private static Map<String, String> prismIds;

    /** The testkit's stand-in for the logged-in account ({@link #setAccountForTest}); null = the real one. */
    private static volatile String testAccountName;
    private static volatile String testAccountId;

    private ProfitScope() {
    }

    /** Where one record came from. */
    public record Key(String account, String accountId, String profile, String modProfile) {
        public Key {
            account = clean(account);
            String id = normalizeUuid(accountId);
            accountId = id == null ? UNKNOWN : id;
            profile = clean(profile);
            modProfile = clean(modProfile);
            if (id != null && !UNKNOWN.equals(account)) {
                NAMES.put(id, account);
            }
        }

        /** A key with no account UUID (the name alone, or nothing). */
        public Key(String account, String profile, String modProfile) {
            this(account, null, profile, modProfile);
        }

        public void writeTo(JsonObject o) {
            o.addProperty("account", account);
            if (hasAccountId()) {
                o.addProperty("accountId", accountId);
            } else {
                o.remove("accountId");
            }
            o.addProperty("sbProfile", profile);
            o.addProperty("modProfile", modProfile);
        }

        public static Key readFrom(JsonObject o) {
            return new Key(str(o, "account"), str(o, "accountId"), str(o, "sbProfile"), str(o, "modProfile"));
        }

        public boolean hasAccountId() {
            return !UNKNOWN.equals(accountId);
        }

        /** What the account switch and a filter name this account by: its UUID, or its name before UUIDs were kept. */
        public String accountKey() {
            return hasAccountId() ? accountId : account;
        }

        /** Whether {@code a} (a UUID, or a name - older resets and filters hold names) is this record's account. */
        public boolean matchesAccount(String a) {
            if (a == null) {
                return true;
            }
            if (a.equals(accountId) || a.equals(account)) {
                return true;
            }
            String id = normalizeUuid(a);
            if (id == null) {
                return false;
            }
            if (id.equals(accountId)) {
                return true;
            }
            // A name-only record whose name is what that UUID is called (it could not be resolved when migrated).
            String name = NAMES.get(id);
            return !hasAccountId() && name != null && name.equalsIgnoreCase(account);
        }

        public boolean matches(Filter f) {
            return f == null || (matchesAccount(f.account)
                    && (f.profile == null || f.profile.equals(profile))
                    && (f.modProfile == null || f.modProfile.equals(modProfile)));
        }

        public Key withModProfile(String mod) {
            return new Key(account, accountId, profile, mod);
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
                sb.append(accountName(account));
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
        ACCOUNT("Account", Key::accountKey),
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

    // ------------------------------------------------------------------------------------------- what is shown

    /**
     * What a screen's saved filter selects once the shared settings apply: the account from Show Profit (the
     * current account, every account, or the one picked on a screen), and under Per Mod Profile the current mod
     * profile whatever the filter says. Every tracker's numbers, graph and reset go through this.
     */
    public static Filter resolve(Filter view) {
        Filter f = view == null ? new Filter() : view.copy();
        ProfitConfig cfg = ProfitConfig.getInstance();
        if (cfg.getPickedAccount() != null) {
            f.account = cfg.getPickedAccount();
        } else if (cfg.getShow() == ProfitConfig.Show.THIS_ACCOUNT) {
            f.account = currentAccountKey();
        } else {
            f.account = null;
        }
        if (cfg.isPerProfile()) {
            f.modProfile = currentModProfile();
        }
        return f;
    }

    /** The account switch's choices after This Account and All Accounts: every other account in {@code keys}. */
    public static List<String> otherAccounts(Collection<Key> keys) {
        String me = currentAccountKey();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> byName = new HashMap<>();
        for (Key k : keys) {
            String key = k.accountKey();
            if (key.equals(me) || UNKNOWN.equals(key) || k.matchesAccount(me)) {
                continue;
            }
            String name = accountName(key);
            if (!byName.containsKey(name)) {
                byName.put(name, key);
                names.add(name);
            }
        }
        List<String> out = new ArrayList<>();
        for (String n : names) {
            out.add(byName.get(n));
        }
        return out;
    }

    /** Steps the account switch: This Account, All Accounts, then each other account, wrapping ({@code dir} -1 back). */
    public static void cycleAccount(Collection<Key> keys, int dir) {
        ProfitConfig cfg = ProfitConfig.getInstance();
        List<String> others = otherAccounts(keys);
        int n = 2 + others.size();
        int cur;
        if (cfg.getPickedAccount() != null) {
            int at = others.indexOf(cfg.getPickedAccount());
            cur = at < 0 ? 0 : 2 + at;
        } else {
            cur = cfg.getShow() == ProfitConfig.Show.THIS_ACCOUNT ? 0 : 1;
        }
        int next = Math.floorMod(cur + dir, n);
        if (next == 0) {
            cfg.setShow(ProfitConfig.Show.THIS_ACCOUNT);
        } else if (next == 1) {
            cfg.setShow(ProfitConfig.Show.ALL_ACCOUNTS);
        } else {
            cfg.setShow(ProfitConfig.Show.ALL_ACCOUNTS);
            cfg.setPickedAccount(others.get(next - 2));
        }
        cfg.save();
    }

    /** The account switch's text. */
    public static String accountLabel() {
        ProfitConfig cfg = ProfitConfig.getInstance();
        if (cfg.getPickedAccount() != null) {
            return "Account: " + accountName(cfg.getPickedAccount());
        }
        return cfg.getShow().label;
    }

    /** A UUID's name when one is known; anything else as it is. */
    public static String accountName(String account) {
        if (account == null) {
            return "All Accounts";
        }
        String id = normalizeUuid(account);
        if (id != null) {
            if (id.equals(currentAccountId())) {
                return currentAccount();
            }
            String name = NAMES.get(id);
            if (name != null) {
                return name;
            }
        }
        return account;
    }

    // ------------------------------------------------------------------------------------------- now

    /** The key for something recorded right now. */
    public static Key current() {
        return new Key(currentAccount(), currentAccountId(), currentSkyblockProfile(), currentModProfile());
    }

    public static String currentAccount() {
        if (testAccountName != null) {
            return testAccountName;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            return client == null || client.getUser() == null ? UNKNOWN : client.getUser().getName();
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** The logged-in account's UUID (32 lower-case hex digits), or {@link #UNKNOWN}. */
    public static String currentAccountId() {
        if (testAccountId != null) {
            return testAccountId;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.getUser() == null || client.getUser().getProfileId() == null) {
                return UNKNOWN;
            }
            String id = normalizeUuid(client.getUser().getProfileId().toString());
            return id == null ? UNKNOWN : id;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** The current account as a filter names it: its UUID, or its name if the UUID is not known. */
    public static String currentAccountKey() {
        String id = currentAccountId();
        return UNKNOWN.equals(id) ? currentAccount() : id;
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

    /** Test hook: records and This Account use this account instead of the logged-in one; nulls go back to it. */
    public static void setAccountForTest(String name, String uuid) {
        testAccountName = name;
        testAccountId = uuid == null ? null : normalizeUuid(uuid);
        if (testAccountId != null && name != null) {
            NAMES.put(testAccountId, name);
        }
    }

    // ------------------------------------------------------------------------------------------- migration

    /** Whether the current account is known, so older records can be given one. */
    public static boolean canMigrate() {
        return !UNKNOWN.equals(currentAccountId()) && !UNKNOWN.equals(currentAccount());
    }

    /**
     * An older record's key with an account UUID: the account that recorded it when its name names one we know (the
     * current account, or one of Prism's saved accounts), the current account when no name was kept at all; a name
     * nobody knows stays a name (still matched by name, and by UUID once that name is seen with one). A record with
     * no mod profile is given the current one. Returns {@code k} itself when nothing changes.
     */
    public static Key migrated(Key k) {
        if (k.hasAccountId() && !UNKNOWN.equals(k.modProfile())) {
            return k;
        }
        String name = k.account();
        String id = k.hasAccountId() ? k.accountId() : null;
        if (id == null) {
            if (UNKNOWN.equals(name)) {
                name = currentAccount();
                id = currentAccountId();
            } else if (name.equalsIgnoreCase(currentAccount())) {
                id = currentAccountId();
            } else {
                id = idForName(name);
            }
        }
        String mod = UNKNOWN.equals(k.modProfile()) ? currentModProfile() : k.modProfile();
        Key out = new Key(name, id, k.profile(), mod);
        return out.equals(k) ? k : out;
    }

    private static synchronized String idForName(String name) {
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        if (prismIds == null) {
            prismIds = new HashMap<>();
            try {
                for (var acct : com.killer560.hub.accounts.core.PrismAccountStore.load()) {
                    String id = normalizeUuid(acct.uuid());
                    if (id != null && acct.name() != null) {
                        prismIds.put(acct.name().toLowerCase(Locale.ROOT), id);
                    }
                }
            } catch (Exception | LinkageError ignored) {
                // No Prism, or an unreadable file: the name stays a name.
            }
        }
        return prismIds.get(name.toLowerCase(Locale.ROOT));
    }

    /** A mod profile was renamed: every tracker moves its records to the new name. */
    public static void renameModProfile(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)) {
            return;
        }
        try {
            com.killer560.hub.croesus.CroesusProfitLog.renameModProfile(oldName, newName);
        } catch (RuntimeException ignored) {
        }
        try {
            com.killer560.hub.experiments.ExperimentsProfitTracker.renameModProfile(oldName, newName);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** 32 lower-case hex digits for a UUID written with or without dashes, or null for anything else. */
    public static String normalizeUuid(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (t.length() != 32) {
            return null;
        }
        for (int i = 0; i < 32; i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        return t;
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
        if (field == Field.ACCOUNT) {
            return accountLabel();
        }
        String v = field.get(f);
        return field.label + ": " + (v == null ? "All" : v);
    }
}
