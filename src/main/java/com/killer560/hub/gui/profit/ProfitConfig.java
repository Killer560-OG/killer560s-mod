package com.killer560.hub.gui.profit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The two settings every profit tracker shares (killer560, 2026-10-15: "For profit trackers by default have it separate
 * profit from different accounts on the same profile. Have an option to show all profit though from all accounts. Also
 * have options to share it between profiles or separate by profiles."):
 * <ul>
 *   <li><b>Show Profit</b>: This Account (the default) counts only what the Minecraft account you are logged in on
 *       recorded; All Accounts adds every account together. The account switch on each /profit screen is this same
 *       setting, and can also pick one other account by name ({@link #getPickedAccount()}).</li>
 *   <li><b>Profit Data</b>: Shared Across Profiles (the default) shows profit recorded under any mod profile, so
 *       switching mod profile never hides it; Per Mod Profile shows only what was recorded while the mod profile you
 *       are on now was active. Every record already carries its mod profile, so switching either way moves and loses
 *       nothing.</li>
 * </ul>
 * One file, never part of a profile ({@code ProfileManager.EXCLUDED_FILES}): the choice is the same in every profile,
 * like the profit data itself, so applying a profile can never flip which pot you are looking at.
 */
public final class ProfitConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-profit-trackers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = ModPaths.config("killer560smod-profit-trackers.json");

    public enum Show {
        THIS_ACCOUNT("This Account"),
        ALL_ACCOUNTS("All Accounts");

        public final String label;

        Show(String label) {
            this.label = label;
        }
    }

    public enum DataMode {
        SHARED("Shared Across Profiles"),
        PER_PROFILE("Per Mod Profile");

        public final String label;

        DataMode(String label) {
            this.label = label;
        }
    }

    private static ProfitConfig instance;

    private Show show = Show.THIS_ACCOUNT;
    /** One other account picked on a /profit screen's account switch (its UUID, or its name for data recorded before
     *  UUIDs), or null to follow {@link #show}. */
    private String pickedAccount = null;
    private DataMode dataMode = DataMode.SHARED;

    private ProfitConfig() {
    }

    public static synchronized ProfitConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        ProfitConfig cfg = new ProfitConfig();
        if (Files.exists(PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.show = ConfigJson.getEnum(o, "show", Show.class, Show.THIS_ACCOUNT);
                String picked = ConfigJson.getString(o, "pickedAccount", "");
                cfg.pickedAccount = picked == null || picked.isBlank() ? null : picked;
                cfg.dataMode = ConfigJson.getEnum(o, "dataMode", DataMode.class, DataMode.SHARED);
            } catch (Exception e) {
                LOGGER.warn("Couldn't read {} - using the defaults", PATH.getFileName(), e);
                cfg = new ProfitConfig();
            }
        }
        instance = cfg;
    }

    public synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("show", show.name());
            o.addProperty("pickedAccount", pickedAccount == null ? "" : pickedAccount);
            o.addProperty("dataMode", dataMode.name());
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", PATH.getFileName(), e);
        }
    }

    public Show getShow() {
        return show;
    }

    /** Also drops a picked account: the setting then says exactly what is shown. */
    public void setShow(Show show) {
        this.show = show == null ? Show.THIS_ACCOUNT : show;
        this.pickedAccount = null;
    }

    public String getPickedAccount() {
        return pickedAccount;
    }

    public void setPickedAccount(String account) {
        this.pickedAccount = account == null || account.isBlank() ? null : account;
    }

    public DataMode getDataMode() {
        return dataMode;
    }

    public void setDataMode(DataMode dataMode) {
        this.dataMode = dataMode == null ? DataMode.SHARED : dataMode;
    }

    public boolean isPerProfile() {
        return dataMode == DataMode.PER_PROFILE;
    }
}
