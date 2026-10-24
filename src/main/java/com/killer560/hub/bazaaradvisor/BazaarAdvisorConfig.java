package com.killer560.hub.bazaaradvisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Bazaar Advisor answers (killer560, 2026-10-19). {@code /bzflip} asks four questions - where to sell (NPC or
 * Bazaar), what to optimize (total profit per inventory or best profit percentage), how to buy (Insta Buy or Buy Order) and,
 * for a Bazaar sale, how to sell (Insta Sell or Sell Offer) - and the answers are what every Bazaar menu's recommendation
 * is worked out from. Legit: it only reads the Bazaar and prints chat lines, it never clicks anything.
 */
public final class BazaarAdvisorConfig {

    /** Where the bought items are sold. */
    public enum Mode {
        NPC("NPC"), BAZAAR("Bazaar");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** What the recommendation is ranked by. */
    public enum Goal {
        TOTAL_PROFIT("Total profit per inventory"), PROFIT_PERCENT("Best profit percentage");

        public final String label;

        Goal(String label) {
            this.label = label;
        }
    }

    /** How the items are bought: straight from sell offers, or a buy order that waits for a seller. */
    public enum BuyMethod {
        INSTA_BUY("Insta Buy"), BUY_ORDER("Buy Order");

        public final String label;

        BuyMethod(String label) {
            this.label = label;
        }
    }

    /** How a Bazaar sale is done: straight into buy orders, or a sell offer that waits for a buyer. */
    public enum SellMethod {
        INSTA_SELL("Insta Sell"), SELL_OFFER("Sell Offer");

        public final String label;

        SellMethod(String label) {
            this.label = label;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bazaaradvisor.json");

    private static BazaarAdvisorConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.NPC;
    private Goal goal = Goal.TOTAL_PROFIT;
    private BuyMethod buyMethod = BuyMethod.INSTA_BUY;
    private SellMethod sellMethod = SellMethod.INSTA_SELL;

    private BazaarAdvisorConfig() {
    }

    /** Whether the advisor's questions were answered (saved or "Use last") since the game started. The Bazaar Flipper asks
     *  again every boot until they are (killer560, 2026-10-20: "if i havent set my bazaar flipping preferences on this boot
     *  then it needs to ask me again"). Not saved: it is per boot by design. */
    private static volatile boolean answeredThisBoot;

    public static boolean answeredThisBoot() {
        return answeredThisBoot;
    }

    public static void markAnsweredThisBoot() {
        answeredThisBoot = true;
    }

    public static BazaarAdvisorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BazaarAdvisorConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            BazaarAdvisorConfig cfg = new BazaarAdvisorConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, cfg.mode);
            cfg.goal = ConfigJson.getEnum(obj, "goal", Goal.class, cfg.goal);
            cfg.buyMethod = ConfigJson.getEnum(obj, "buyMethod", BuyMethod.class, cfg.buyMethod);
            cfg.sellMethod = ConfigJson.getEnum(obj, "sellMethod", SellMethod.class, cfg.sellMethod);
            instance = cfg;
        } catch (Exception e) {
            instance = new BazaarAdvisorConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            obj.addProperty("goal", goal.name());
            obj.addProperty("buyMethod", buyMethod.name());
            obj.addProperty("sellMethod", sellMethod.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NPC : mode;
    }

    public Goal getGoal() {
        return goal;
    }

    public void setGoal(Goal goal) {
        this.goal = goal == null ? Goal.TOTAL_PROFIT : goal;
    }

    public BuyMethod getBuyMethod() {
        return buyMethod;
    }

    public void setBuyMethod(BuyMethod buyMethod) {
        this.buyMethod = buyMethod == null ? BuyMethod.INSTA_BUY : buyMethod;
    }

    public SellMethod getSellMethod() {
        return sellMethod;
    }

    public void setSellMethod(SellMethod sellMethod) {
        this.sellMethod = sellMethod == null ? SellMethod.INSTA_SELL : sellMethod;
    }

    /** One line naming the answers, for the tab and the setup summary. */
    public String summary() {
        String sell = mode == Mode.NPC ? "sell to NPC" : "sell on Bazaar (" + sellMethod.label + ")";
        return mode.label + " - " + goal.label + " - " + buyMethod.label + ", " + sell;
    }
}
