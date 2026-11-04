package com.killer560.hub.miningqol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the pre-2.0 mining features (killer560's to-do Doc, 2026-11-01: "the only mining features I want you to add
 * before 2.0"): Commission HUD, Pickobulus Estimate, Ability Ready Alert, Carpet Highlighter, Mithril As Stone, Titanium
 * Highlight and Raffle Ticket Highlight. One file, {@code killer560smod-miningqol.json}; every feature ships OFF.
 *
 * <p>The {@code is...} getters a feature reads are the gated ones: OFF outside Skyblock (Skyblock Only), and the two
 * through-walls switches are false in a legit jar whatever a copied file says.
 */
public final class MiningQolConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-miningqol.json");

    public static final int DEFAULT_PICKOBULUS_COLOR = 0xFF55FFFF;
    public static final int DEFAULT_CARPET_COLOR = 0x80FF3030;
    public static final int DEFAULT_TITANIUM_COLOR = 0xFFF0F0F0;
    public static final int DEFAULT_RAFFLE_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_COMMISSION_MOB_COLOR = 0xFFFF5555;
    public static final int DEFAULT_COMMISSION_ROUTE_COLOR = 0xFF55FFFF;
    public static final int DEFAULT_POWDER_GHAST_COLOR = 0xFFFF55FF;
    public static final int DEFAULT_GOLDEN_GOBLIN_COLOR = 0xFFFFD700;

    /** When the Titanium Highlight shows titanium. */
    public enum TitaniumMode {
        ALWAYS("Always"),
        COMMISSION("Commission Area");

        private final String label;

        TitaniumMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How Commission Mobs draws a mob (killer560, 2026-11-03: "let me choose outline, fill, fill+outline or glow"). Drawn
     * with the shared paths: {@code WorldRenderUtils.renderOutlineBoxes}/{@code renderFilledBoxes} for the boxes, and the
     * vanilla glow outline through the cheatutils WitherGlow mixins Dungeon ESP's Glow style uses.
     */
    /** Which drill Swap To Drill On Pigeon picks. */
    public enum PigeonDrillMode {
        AUTO("Auto"),
        MITHRIL("Mithril Powder"),
        GEMSTONE("Gemstone Powder"),
        SPECIFIC("Specific Drill");

        private final String label;

        PigeonDrillMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How the Commission Route gets there (killer560, 2026-11-03: "one for etherwarp one for aote one for walking"). */
    public enum CommissionRouteMode {
        WALK("Walk"),
        AOTE("AOTE"),
        ETHERWARP("Etherwarp");

        private final String label;

        CommissionRouteMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum CommissionMobStyle {
        OUTLINE("Outline", true, false, false),
        FILL("Fill", false, true, false),
        FILL_OUTLINE("Fill + Outline", true, true, false),
        GLOW("Glow", false, false, true);

        private final String label;
        public final boolean outline;
        public final boolean fill;
        public final boolean glow;

        CommissionMobStyle(String label, boolean outline, boolean fill, boolean glow) {
            this.label = label;
            this.outline = outline;
            this.fill = fill;
            this.glow = glow;
        }

        public String label() {
            return label;
        }
    }

    private static MiningQolConfig instance;

    // Commission HUD
    private boolean commissionHud = false;
    private boolean commissionColorProgress = true;
    private boolean commissionInterpolate = true;
    private boolean commissionWaypoints = true;
    private boolean commissionEmissaries = true;
    /** Commission Mobs (killer560, 2026-11-03: "it should highlight commission mobs as an option as well"). */
    private boolean commissionMobs = false;
    /** Hide Commissions While Powder Mining (killer560, 2026-11-03). */
    private boolean hideCommissionsPowder = true;
    private boolean commissionMobsEsp = false;
    private int commissionMobColor = DEFAULT_COMMISSION_MOB_COLOR;
    private CommissionMobStyle commissionMobStyle = CommissionMobStyle.OUTLINE;
    /** Completed Commissions in the Commissions menu (killer560, 2026-11-03: "have it highlight completed commissions"). */
    private boolean commissionMenuHighlight = true;
    /** Commission Route (killer560, 2026-11-03: "draw a pathfinding line to wherever i need to go to do a comission"). */
    private boolean commissionRoute = false;
    private int commissionRouteColor = DEFAULT_COMMISSION_ROUTE_COLOR;
    private CommissionRouteMode commissionRouteMode = CommissionRouteMode.WALK;
    /** Route to the nearest Emissary when a commission completes (killer560, 2026-11-03); off pulses the Royal Pigeon slot. */
    private boolean commissionRouteEmissary = true;
    /** Pulse the Royal Pigeon's hotbar slot while a claim is due (killer560, 2026-11-03), apart from the route switches. */
    private boolean commissionPigeon = true;
    /** Swap To Drill On Pigeon (killer560, 2026-11-03), cheat-only. */
    private boolean pigeonDrill = false;
    private PigeonDrillMode pigeonDrillMode = PigeonDrillMode.AUTO;
    /** For {@link PigeonDrillMode#SPECIFIC}: part of the drill's name or Skyblock id. */
    private String pigeonDrillName = "";
    // Pickobulus Estimate
    private boolean pickobulus = false;
    private int pickobulusColor = DEFAULT_PICKOBULUS_COLOR;
    // Ability Ready Alert
    private boolean abilityAlert = false;
    private boolean abilityTimer = true;
    private boolean abilitySound = true;
    // Wrong Armor Alert (killer560, 2026-11-03: "a mining alert for if you are mining in a non mining armor set")
    private boolean wrongArmor = true;
    private boolean wrongArmorSound = true;
    // Crystal Hollows Walls: one color (RGB), opacity (percent) and near-only distance (blocks, 0 = always) per zone
    private boolean chWalls = false;
    private final int[] chWallColor = CrystalHollowsWalls.defaultColors();
    private final int[] chWallOpacity = CrystalHollowsWalls.defaultFill(CrystalHollowsWalls.DEFAULT_OPACITY);
    private final int[] chWallNear = CrystalHollowsWalls.defaultFill(CrystalHollowsWalls.DEFAULT_NEAR);
    // Carpet Highlighter
    private boolean carpet = false;
    private int carpetColor = DEFAULT_CARPET_COLOR;
    // Mithril As Stone. (A "universalAccess" switch lived here until mining-1103: a commission that counts everywhere has
    // no area in its name - "Mithril Miner" - and MiningState.wants already applies it everywhere, so the key is ignored.)
    private boolean mithrilStone = false;
    // Dark Stone (killer560 2026-11-04): stone-type blocks drawn darker, level in percent
    private boolean darkStone = false;
    private int darkStoneLevel = 50;
    private boolean darkStoneMithril = false;
    // Titanium Highlight
    private boolean titanium = false;
    private TitaniumMode titaniumMode = TitaniumMode.COMMISSION;
    private int titaniumColor = DEFAULT_TITANIUM_COLOR;
    private boolean titaniumEsp = false;
    /** With Through Walls: only titanium with a face open to air (owner 2026-11-03: "so you could actually mine it"). */
    private boolean titaniumExposedOnly = true;
    // Raffle Ticket Highlight
    private boolean raffle = false;
    private int raffleColor = DEFAULT_RAFFLE_COLOR;
    private boolean raffleEsp = false;
    // Resume Held Break After Menus (killer560 2026-11-03: a server menu opening mid-dig let go of his held left click)
    private boolean resumeHeldBreak = true;
    // Powder Ghast: the one feature here that ships ON (the brief: alert, box and tracer legit and on; party and through
    // walls off).
    private boolean powderGhast = true;
    private boolean powderGhastTitle = true;
    private boolean powderGhastSound = true;
    private boolean powderGhastParty = false;
    private boolean powderGhastBox = true;
    private int powderGhastBoxColor = DEFAULT_POWDER_GHAST_COLOR;
    private boolean powderGhastTracer = true;
    private int powderGhastTracerColor = DEFAULT_POWDER_GHAST_COLOR;
    private boolean powderGhastTimer = true;
    private boolean powderGhastEsp = false;
    // Golden Goblin (killer560, 2026-11-03): box + tracer on the Golden and Diamond Goblins. Off by default.
    private boolean goldenGoblin = false;
    private boolean goldenGoblinBox = true;
    private int goldenGoblinBoxColor = DEFAULT_GOLDEN_GOBLIN_COLOR;
    private boolean goldenGoblinTracer = true;
    private int goldenGoblinTracerColor = DEFAULT_GOLDEN_GOBLIN_COLOR;
    private boolean goldenGoblinEsp = false;

    private MiningQolConfig() {
    }

    public static MiningQolConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MiningQolConfig cfg = new MiningQolConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.commissionHud = ConfigJson.getBool(o, "commissionHud", cfg.commissionHud);
                cfg.commissionColorProgress = ConfigJson.getBool(o, "commissionColorProgress", cfg.commissionColorProgress);
                cfg.commissionInterpolate = ConfigJson.getBool(o, "commissionInterpolate", cfg.commissionInterpolate);
                cfg.commissionWaypoints = ConfigJson.getBool(o, "commissionWaypoints", cfg.commissionWaypoints);
                cfg.commissionEmissaries = ConfigJson.getBool(o, "commissionEmissaries", cfg.commissionEmissaries);
                cfg.commissionMobs = ConfigJson.getBool(o, "commissionMobs", cfg.commissionMobs);
                cfg.hideCommissionsPowder = ConfigJson.getBool(o, "hideCommissionsPowder", cfg.hideCommissionsPowder);
                cfg.commissionMobsEsp = ConfigJson.getBool(o, "commissionMobsEsp", cfg.commissionMobsEsp);
                cfg.commissionMobColor = intOr(o, "commissionMobColor", cfg.commissionMobColor);
                cfg.commissionMobStyle = ConfigJson.getEnum(o, "commissionMobStyle", CommissionMobStyle.class,
                        CommissionMobStyle.OUTLINE);
                cfg.commissionMenuHighlight = ConfigJson.getBool(o, "commissionMenuHighlight", cfg.commissionMenuHighlight);
                cfg.commissionRoute = ConfigJson.getBool(o, "commissionRoute", cfg.commissionRoute);
                cfg.commissionRouteColor = intOr(o, "commissionRouteColor", cfg.commissionRouteColor);
                cfg.commissionRouteEmissary = ConfigJson.getBool(o, "commissionRouteEmissary", cfg.commissionRouteEmissary);
                cfg.commissionPigeon = ConfigJson.getBool(o, "commissionPigeon", cfg.commissionPigeon);
                cfg.commissionRouteMode = ConfigJson.getEnum(o, "commissionRouteMode", CommissionRouteMode.class,
                        CommissionRouteMode.WALK);
                cfg.pigeonDrill = ConfigJson.getBool(o, "pigeonDrill", cfg.pigeonDrill);
                cfg.pigeonDrillMode = ConfigJson.getEnum(o, "pigeonDrillMode", PigeonDrillMode.class, PigeonDrillMode.AUTO);
                cfg.pigeonDrillName = ConfigJson.getString(o, "pigeonDrillName", cfg.pigeonDrillName);
                cfg.pickobulus = ConfigJson.getBool(o, "pickobulus", cfg.pickobulus);
                cfg.pickobulusColor = intOr(o, "pickobulusColor", cfg.pickobulusColor);
                cfg.abilityAlert = ConfigJson.getBool(o, "abilityAlert", cfg.abilityAlert);
                cfg.abilityTimer = ConfigJson.getBool(o, "abilityTimer", cfg.abilityTimer);
                cfg.abilitySound = ConfigJson.getBool(o, "abilitySound", cfg.abilitySound);
                cfg.wrongArmor = ConfigJson.getBool(o, "wrongArmor", cfg.wrongArmor);
                cfg.wrongArmorSound = ConfigJson.getBool(o, "wrongArmorSound", cfg.wrongArmorSound);
                cfg.chWalls = ConfigJson.getBool(o, "chWalls", cfg.chWalls);
                for (CrystalHollowsWalls.Zone z : CrystalHollowsWalls.Zone.values()) {
                    int i = z.ordinal();
                    cfg.chWallColor[i] = intOr(o, "chWall" + z.key + "Color", cfg.chWallColor[i]) & 0xFFFFFF;
                    cfg.chWallOpacity[i] = Math.max(CrystalHollowsWalls.MIN_OPACITY, Math.min(100,
                            intOr(o, "chWall" + z.key + "Opacity", cfg.chWallOpacity[i])));
                    cfg.chWallNear[i] = Math.max(0, Math.min(1024, intOr(o, "chWall" + z.key + "Near", cfg.chWallNear[i])));
                }
                cfg.carpet = ConfigJson.getBool(o, "carpet", cfg.carpet);
                cfg.carpetColor = intOr(o, "carpetColor", cfg.carpetColor);
                cfg.mithrilStone = ConfigJson.getBool(o, "mithrilStone", cfg.mithrilStone);
                cfg.darkStone = ConfigJson.getBool(o, "darkStone", cfg.darkStone);
                cfg.darkStoneLevel = Math.max(10, Math.min(90, intOr(o, "darkStoneLevel", cfg.darkStoneLevel)));
                cfg.darkStoneMithril = ConfigJson.getBool(o, "darkStoneMithril", cfg.darkStoneMithril);
                cfg.titanium = ConfigJson.getBool(o, "titanium", cfg.titanium);
                try {
                    if (o.has("titaniumMode")) {
                        cfg.titaniumMode = TitaniumMode.valueOf(o.get("titaniumMode").getAsString());
                    }
                } catch (RuntimeException ignored) {
                    // unknown name: keep the default
                }
                cfg.titaniumColor = intOr(o, "titaniumColor", cfg.titaniumColor);
                cfg.titaniumEsp = ConfigJson.getBool(o, "titaniumEsp", cfg.titaniumEsp);
                cfg.titaniumExposedOnly = ConfigJson.getBool(o, "titaniumExposedOnly", cfg.titaniumExposedOnly);
                cfg.raffle = ConfigJson.getBool(o, "raffle", cfg.raffle);
                cfg.raffleColor = intOr(o, "raffleColor", cfg.raffleColor);
                cfg.raffleEsp = ConfigJson.getBool(o, "raffleEsp", cfg.raffleEsp);
                cfg.resumeHeldBreak = ConfigJson.getBool(o, "resumeHeldBreak", cfg.resumeHeldBreak);
                cfg.powderGhast = ConfigJson.getBool(o, "powderGhast", cfg.powderGhast);
                cfg.powderGhastTitle = ConfigJson.getBool(o, "powderGhastTitle", cfg.powderGhastTitle);
                cfg.powderGhastSound = ConfigJson.getBool(o, "powderGhastSound", cfg.powderGhastSound);
                cfg.powderGhastParty = ConfigJson.getBool(o, "powderGhastParty", cfg.powderGhastParty);
                cfg.powderGhastBox = ConfigJson.getBool(o, "powderGhastBox", cfg.powderGhastBox);
                cfg.powderGhastBoxColor = intOr(o, "powderGhastBoxColor", cfg.powderGhastBoxColor);
                cfg.powderGhastTracer = ConfigJson.getBool(o, "powderGhastTracer", cfg.powderGhastTracer);
                cfg.powderGhastTracerColor = intOr(o, "powderGhastTracerColor", cfg.powderGhastTracerColor);
                cfg.powderGhastTimer = ConfigJson.getBool(o, "powderGhastTimer", cfg.powderGhastTimer);
                cfg.powderGhastEsp = ConfigJson.getBool(o, "powderGhastEsp", cfg.powderGhastEsp);
                cfg.goldenGoblin = ConfigJson.getBool(o, "goldenGoblin", cfg.goldenGoblin);
                cfg.goldenGoblinBox = ConfigJson.getBool(o, "goldenGoblinBox", cfg.goldenGoblinBox);
                cfg.goldenGoblinBoxColor = intOr(o, "goldenGoblinBoxColor", cfg.goldenGoblinBoxColor);
                cfg.goldenGoblinTracer = ConfigJson.getBool(o, "goldenGoblinTracer", cfg.goldenGoblinTracer);
                cfg.goldenGoblinTracerColor = intOr(o, "goldenGoblinTracerColor", cfg.goldenGoblinTracerColor);
                cfg.goldenGoblinEsp = ConfigJson.getBool(o, "goldenGoblinEsp", cfg.goldenGoblinEsp);
            } catch (Exception e) {
                cfg = new MiningQolConfig();
            }
        }
        instance = cfg;
        MithrilStone.settingsChanged();
    }

    private static int intOr(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("commissionHud", commissionHud);
            o.addProperty("commissionColorProgress", commissionColorProgress);
            o.addProperty("commissionInterpolate", commissionInterpolate);
            o.addProperty("commissionWaypoints", commissionWaypoints);
            o.addProperty("commissionEmissaries", commissionEmissaries);
            o.addProperty("commissionMobs", commissionMobs);
            o.addProperty("hideCommissionsPowder", hideCommissionsPowder);
            o.addProperty("commissionMobsEsp", commissionMobsEsp);
            o.addProperty("commissionMobColor", commissionMobColor);
            o.addProperty("commissionMobStyle", commissionMobStyle.name());
            o.addProperty("commissionMenuHighlight", commissionMenuHighlight);
            o.addProperty("commissionRoute", commissionRoute);
            o.addProperty("commissionRouteColor", commissionRouteColor);
            o.addProperty("commissionRouteEmissary", commissionRouteEmissary);
            o.addProperty("commissionPigeon", commissionPigeon);
            o.addProperty("commissionRouteMode", commissionRouteMode.name());
            o.addProperty("pigeonDrill", pigeonDrill);
            o.addProperty("pigeonDrillMode", pigeonDrillMode.name());
            o.addProperty("pigeonDrillName", pigeonDrillName);
            o.addProperty("pickobulus", pickobulus);
            o.addProperty("pickobulusColor", pickobulusColor);
            o.addProperty("abilityAlert", abilityAlert);
            o.addProperty("abilityTimer", abilityTimer);
            o.addProperty("abilitySound", abilitySound);
            o.addProperty("wrongArmor", wrongArmor);
            o.addProperty("wrongArmorSound", wrongArmorSound);
            o.addProperty("chWalls", chWalls);
            for (CrystalHollowsWalls.Zone z : CrystalHollowsWalls.Zone.values()) {
                o.addProperty("chWall" + z.key + "Color", chWallColor[z.ordinal()]);
                o.addProperty("chWall" + z.key + "Opacity", chWallOpacity[z.ordinal()]);
                o.addProperty("chWall" + z.key + "Near", chWallNear[z.ordinal()]);
            }
            o.addProperty("carpet", carpet);
            o.addProperty("carpetColor", carpetColor);
            o.addProperty("mithrilStone", mithrilStone);
            o.addProperty("darkStone", darkStone);
            o.addProperty("darkStoneLevel", darkStoneLevel);
            o.addProperty("darkStoneMithril", darkStoneMithril);
            o.addProperty("titanium", titanium);
            o.addProperty("titaniumMode", titaniumMode.name());
            o.addProperty("titaniumColor", titaniumColor);
            o.addProperty("titaniumEsp", titaniumEsp);
            o.addProperty("titaniumExposedOnly", titaniumExposedOnly);
            o.addProperty("raffle", raffle);
            o.addProperty("raffleColor", raffleColor);
            o.addProperty("raffleEsp", raffleEsp);
            o.addProperty("resumeHeldBreak", resumeHeldBreak);
            o.addProperty("powderGhast", powderGhast);
            o.addProperty("powderGhastTitle", powderGhastTitle);
            o.addProperty("powderGhastSound", powderGhastSound);
            o.addProperty("powderGhastParty", powderGhastParty);
            o.addProperty("powderGhastBox", powderGhastBox);
            o.addProperty("powderGhastBoxColor", powderGhastBoxColor);
            o.addProperty("powderGhastTracer", powderGhastTracer);
            o.addProperty("powderGhastTracerColor", powderGhastTracerColor);
            o.addProperty("powderGhastTimer", powderGhastTimer);
            o.addProperty("powderGhastEsp", powderGhastEsp);
            o.addProperty("goldenGoblin", goldenGoblin);
            o.addProperty("goldenGoblinBox", goldenGoblinBox);
            o.addProperty("goldenGoblinBoxColor", goldenGoblinBoxColor);
            o.addProperty("goldenGoblinTracer", goldenGoblinTracer);
            o.addProperty("goldenGoblinTracerColor", goldenGoblinTracerColor);
            o.addProperty("goldenGoblinEsp", goldenGoblinEsp);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a failed save keeps the in-memory values
        }
        MithrilStone.settingsChanged();
    }

    // ---- Resume Held Break After Menus
    public boolean isResumeHeldBreak() {
        return resumeHeldBreak && sb();
    }

    public boolean isResumeHeldBreakRaw() {
        return resumeHeldBreak;
    }

    public void setResumeHeldBreak(boolean v) {
        resumeHeldBreak = v;
    }

    private static boolean sb() {
        return SkyblockGate.allows();
    }

    // ---- Commission HUD
    public boolean isCommissionHud() {
        return commissionHud && sb();
    }

    public boolean isCommissionHudRaw() {
        return commissionHud;
    }

    public void setCommissionHud(boolean v) {
        commissionHud = v;
    }

    public boolean isCommissionColorProgress() {
        return commissionColorProgress;
    }

    public void setCommissionColorProgress(boolean v) {
        commissionColorProgress = v;
    }

    public boolean isCommissionInterpolate() {
        return commissionInterpolate;
    }

    public void setCommissionInterpolate(boolean v) {
        commissionInterpolate = v;
    }

    public boolean isCommissionWaypoints() {
        return commissionWaypoints;
    }

    public void setCommissionWaypoints(boolean v) {
        commissionWaypoints = v;
    }

    public boolean isCommissionEmissaries() {
        return commissionEmissaries;
    }

    public void setCommissionEmissaries(boolean v) {
        commissionEmissaries = v;
    }

    /** Commission Mobs as set (the feature also needs the Commission HUD switch, see {@link CommissionMobs}). */
    public boolean isCommissionMobs() {
        return commissionMobs;
    }

    public void setCommissionMobs(boolean v) {
        commissionMobs = v;
    }

    public boolean isHideCommissionsPowder() {
        return hideCommissionsPowder;
    }

    public void setHideCommissionsPowder(boolean v) {
        hideCommissionsPowder = v;
    }

    /** The commissions' HUD, waypoints and route are hidden right now: the option is on and he is powder mining. */
    public boolean commissionsHiddenNow() {
        return hideCommissionsPowder && com.killer560.hub.powdermining.PowderMining.activeRecently();
    }

    /** Commission Mobs through walls: cheat build only. */
    public boolean isCommissionMobsEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && commissionMobsEsp;
    }

    public void setCommissionMobsEsp(boolean v) {
        commissionMobsEsp = v;
    }

    /** Swap To Drill On Pigeon: cheat jars only. */
    public boolean isPigeonDrill() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && pigeonDrill;
    }

    public boolean isPigeonDrillRaw() {
        return pigeonDrill;
    }

    public void setPigeonDrill(boolean v) {
        pigeonDrill = v;
    }

    public PigeonDrillMode getPigeonDrillMode() {
        return pigeonDrillMode;
    }

    public void setPigeonDrillMode(PigeonDrillMode v) {
        pigeonDrillMode = v == null ? PigeonDrillMode.AUTO : v;
    }

    /** Left click forward, right click back, wrapping. */
    public void stepPigeonDrillMode(int dir) {
        PigeonDrillMode[] all = PigeonDrillMode.values();
        pigeonDrillMode = all[Math.floorMod(pigeonDrillMode.ordinal() + dir, all.length)];
    }

    public String getPigeonDrillName() {
        return pigeonDrillName;
    }

    public void setPigeonDrillName(String v) {
        pigeonDrillName = v == null ? "" : v;
    }

    public boolean isCommissionPigeon() {
        return commissionPigeon;
    }

    public void setCommissionPigeon(boolean v) {
        commissionPigeon = v;
    }

    public boolean isCommissionRouteEmissary() {
        return commissionRouteEmissary;
    }

    public void setCommissionRouteEmissary(boolean v) {
        commissionRouteEmissary = v;
    }

    /** Commission Route as set: its own switch, not gated by the Commission HUD (killer560, 2026-11-03). */
    public boolean isCommissionRoute() {
        return commissionRoute && sb();
    }

    public boolean isCommissionRouteRaw() {
        return commissionRoute;
    }

    public void setCommissionRoute(boolean v) {
        commissionRoute = v;
    }

    public int getCommissionRouteColor() {
        return commissionRouteColor;
    }

    public void setCommissionRouteColor(int v) {
        commissionRouteColor = v;
    }

    public CommissionRouteMode getCommissionRouteMode() {
        return commissionRouteMode;
    }

    public void setCommissionRouteMode(CommissionRouteMode v) {
        commissionRouteMode = v == null ? CommissionRouteMode.WALK : v;
    }

    /** Left click forward, right click back, wrapping. */
    public void stepCommissionRouteMode(int dir) {
        CommissionRouteMode[] all = CommissionRouteMode.values();
        commissionRouteMode = all[Math.floorMod(commissionRouteMode.ordinal() + dir, all.length)];
    }

    public int getCommissionMobColor() {
        return commissionMobColor;
    }

    public void setCommissionMobColor(int v) {
        commissionMobColor = v;
    }

    public boolean isCommissionMenuHighlight() {
        return commissionMenuHighlight;
    }

    public void setCommissionMenuHighlight(boolean v) {
        commissionMenuHighlight = v;
    }

    // ---- Pickobulus Estimate
    public boolean isPickobulus() {
        return pickobulus && sb();
    }

    public boolean isPickobulusRaw() {
        return pickobulus;
    }

    public void setPickobulus(boolean v) {
        pickobulus = v;
    }

    public int getPickobulusColor() {
        return pickobulusColor;
    }

    public void setPickobulusColor(int v) {
        pickobulusColor = v;
    }

    // ---- Ability Ready Alert
    public boolean isAbilityAlert() {
        return abilityAlert && sb();
    }

    public boolean isAbilityAlertRaw() {
        return abilityAlert;
    }

    public void setAbilityAlert(boolean v) {
        abilityAlert = v;
    }

    public boolean isAbilityTimer() {
        return abilityTimer;
    }

    public void setAbilityTimer(boolean v) {
        abilityTimer = v;
    }

    public boolean isAbilitySound() {
        return abilitySound;
    }

    public void setAbilitySound(boolean v) {
        abilitySound = v;
    }

    // ---- Wrong Armor Alert
    public boolean isWrongArmor() {
        return wrongArmor && sb();
    }

    public boolean isWrongArmorRaw() {
        return wrongArmor;
    }

    public void setWrongArmor(boolean v) {
        wrongArmor = v;
    }

    public boolean isWrongArmorSound() {
        return wrongArmorSound;
    }

    public void setWrongArmorSound(boolean v) {
        wrongArmorSound = v;
    }

    // ---- Crystal Hollows Walls
    public boolean isChWalls() {
        return chWalls && sb();
    }

    public boolean isChWallsRaw() {
        return chWalls;
    }

    public void setChWalls(boolean v) {
        chWalls = v;
    }

    /** RGB (no alpha) of the zone's walls. */
    public int getChWallColor(CrystalHollowsWalls.Zone z) {
        return chWallColor[z.ordinal()];
    }

    public void setChWallColor(CrystalHollowsWalls.Zone z, int argb) {
        chWallColor[z.ordinal()] = argb & 0xFFFFFF;
    }

    /** Percent, {@link CrystalHollowsWalls#MIN_OPACITY}..100. */
    public int getChWallOpacity(CrystalHollowsWalls.Zone z) {
        return chWallOpacity[z.ordinal()];
    }

    public void setChWallOpacity(CrystalHollowsWalls.Zone z, int v) {
        chWallOpacity[z.ordinal()] = Math.max(CrystalHollowsWalls.MIN_OPACITY, Math.min(100, v));
    }

    /** Blocks: a wall shows only within this distance of the player; 0 = always. */
    public int getChWallNear(CrystalHollowsWalls.Zone z) {
        return chWallNear[z.ordinal()];
    }

    public void setChWallNear(CrystalHollowsWalls.Zone z, int v) {
        chWallNear[z.ordinal()] = Math.max(0, Math.min(1024, v));
    }

    // ---- Carpet Highlighter
    public boolean isCarpet() {
        return carpet && sb();
    }

    public boolean isCarpetRaw() {
        return carpet;
    }

    public void setCarpet(boolean v) {
        carpet = v;
    }

    public int getCarpetColor() {
        return carpetColor;
    }

    public void setCarpetColor(int v) {
        carpetColor = v;
    }

    // ---- Mithril As Stone
    public boolean isMithrilStone() {
        return mithrilStone && sb();
    }

    public boolean isMithrilStoneRaw() {
        return mithrilStone;
    }

    public void setMithrilStone(boolean v) {
        mithrilStone = v;
    }

    // ---- Dark Stone
    public boolean isDarkStone() {
        return darkStone && sb();
    }

    public boolean isDarkStoneRaw() {
        return darkStone;
    }

    public void setDarkStone(boolean v) {
        darkStone = v;
    }

    public int getDarkStoneLevel() {
        return darkStoneLevel;
    }

    public void setDarkStoneLevel(int v) {
        darkStoneLevel = Math.max(10, Math.min(90, v));
    }

    public boolean isDarkStoneMithril() {
        return darkStoneMithril;
    }

    public void setDarkStoneMithril(boolean v) {
        darkStoneMithril = v;
    }

    // ---- Titanium Highlight
    public boolean isTitanium() {
        return titanium && sb();
    }

    public boolean isTitaniumRaw() {
        return titanium;
    }

    public void setTitanium(boolean v) {
        titanium = v;
    }

    public CommissionMobStyle getCommissionMobStyle() {
        return commissionMobStyle;
    }

    public void setCommissionMobStyle(CommissionMobStyle v) {
        commissionMobStyle = v == null ? CommissionMobStyle.OUTLINE : v;
    }

    /** Left click forward, right click back, wrapping. */
    public void stepCommissionMobStyle(int dir) {
        CommissionMobStyle[] all = CommissionMobStyle.values();
        commissionMobStyle = all[Math.floorMod(commissionMobStyle.ordinal() + dir, all.length)];
    }

    public TitaniumMode getTitaniumMode() {
        return titaniumMode;
    }

    public void setTitaniumMode(TitaniumMode v) {
        titaniumMode = v == null ? TitaniumMode.COMMISSION : v;
    }

    public int getTitaniumColor() {
        return titaniumColor;
    }

    public void setTitaniumColor(int v) {
        titaniumColor = v;
    }

    /** Through walls: cheat build only. */
    public boolean isTitaniumEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && titaniumEsp;
    }

    public void setTitaniumEsp(boolean v) {
        titaniumEsp = v;
    }

    public boolean isTitaniumExposedOnly() {
        return titaniumExposedOnly;
    }

    public void setTitaniumExposedOnly(boolean v) {
        titaniumExposedOnly = v;
    }

    // ---- Raffle Ticket Highlight
    public boolean isRaffle() {
        return raffle && sb();
    }

    public boolean isRaffleRaw() {
        return raffle;
    }

    public void setRaffle(boolean v) {
        raffle = v;
    }

    public int getRaffleColor() {
        return raffleColor;
    }

    public void setRaffleColor(int v) {
        raffleColor = v;
    }

    /** Through walls: cheat build only. */
    public boolean isRaffleEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && raffleEsp;
    }

    public void setRaffleEsp(boolean v) {
        raffleEsp = v;
    }

    // ---- Powder Ghast (killer560, 2026-11-03: "also add powder ghast alerts tracers and esp and such")
    /** The feature switch as gated: OFF outside Skyblock. {@link PowderGhast} also needs the Dwarven Mines. */
    public boolean isPowderGhast() {
        return powderGhast && sb();
    }

    public boolean isPowderGhastRaw() {
        return powderGhast;
    }

    public void setPowderGhast(boolean v) {
        powderGhast = v;
    }

    public boolean isPowderGhastTitle() {
        return powderGhastTitle;
    }

    public void setPowderGhastTitle(boolean v) {
        powderGhastTitle = v;
    }

    public boolean isPowderGhastSound() {
        return powderGhastSound;
    }

    public void setPowderGhastSound(boolean v) {
        powderGhastSound = v;
    }

    /** Party-chat callout: OFF by default, nothing is sent unless he turns it on. */
    public boolean isPowderGhastParty() {
        return powderGhastParty;
    }

    public void setPowderGhastParty(boolean v) {
        powderGhastParty = v;
    }

    public boolean isPowderGhastBox() {
        return powderGhastBox;
    }

    public void setPowderGhastBox(boolean v) {
        powderGhastBox = v;
    }

    public int getPowderGhastBoxColor() {
        return powderGhastBoxColor;
    }

    public void setPowderGhastBoxColor(int v) {
        powderGhastBoxColor = v;
    }

    public boolean isPowderGhastTracer() {
        return powderGhastTracer;
    }

    public void setPowderGhastTracer(boolean v) {
        powderGhastTracer = v;
    }

    public int getPowderGhastTracerColor() {
        return powderGhastTracerColor;
    }

    public void setPowderGhastTracerColor(int v) {
        powderGhastTracerColor = v;
    }

    public boolean isPowderGhastTimer() {
        return powderGhastTimer;
    }

    public void setPowderGhastTimer(boolean v) {
        powderGhastTimer = v;
    }

    /** Box through walls: cheat build only. */
    public boolean isPowderGhastEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && powderGhastEsp;
    }

    public void setPowderGhastEsp(boolean v) {
        powderGhastEsp = v;
    }

    // ---- Golden Goblin
    /** The feature switch as gated: OFF outside Skyblock. {@link GoldenGoblin} also needs a mining island. */
    public boolean isGoldenGoblin() {
        return goldenGoblin && sb();
    }

    public boolean isGoldenGoblinRaw() {
        return goldenGoblin;
    }

    public void setGoldenGoblin(boolean v) {
        goldenGoblin = v;
    }

    public boolean isGoldenGoblinBox() {
        return goldenGoblinBox;
    }

    public void setGoldenGoblinBox(boolean v) {
        goldenGoblinBox = v;
    }

    public int getGoldenGoblinBoxColor() {
        return goldenGoblinBoxColor;
    }

    public void setGoldenGoblinBoxColor(int v) {
        goldenGoblinBoxColor = v;
    }

    public boolean isGoldenGoblinTracer() {
        return goldenGoblinTracer;
    }

    public void setGoldenGoblinTracer(boolean v) {
        goldenGoblinTracer = v;
    }

    public int getGoldenGoblinTracerColor() {
        return goldenGoblinTracerColor;
    }

    public void setGoldenGoblinTracerColor(int v) {
        goldenGoblinTracerColor = v;
    }

    /** Through walls: cheat build only. */
    public boolean isGoldenGoblinEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && goldenGoblinEsp;
    }

    public void setGoldenGoblinEsp(boolean v) {
        goldenGoblinEsp = v;
    }
}
