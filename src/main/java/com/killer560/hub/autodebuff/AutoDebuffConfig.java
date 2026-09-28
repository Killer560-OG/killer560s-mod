package com.killer560.hub.autodebuff;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings for {@link AutoDebuffFeature}. Everything persists, per the project rule that no setting may be
 *  lost on restart. Off by default, like every automation feature. */
public final class AutoDebuffConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autodebuff.json");

    private static AutoDebuffConfig instance;

    private boolean enabled = false;

    /** Per class, because killer560 asked to pick which classes it runs on while he is playing them.
     *  Archer and Berserker have no toggle at all and are never touched - they have no part in the debuff. */
    private boolean onMage = true;
    private boolean onHealer = true;
    private boolean onTank = true;

    /** Mage only: whether to keep meleeing after the opening hit. */
    private boolean meleeAfter = true;

    /**
     * Ticks before spawn to stop firing Last Breath.
     *
     * <p>It cannot run up to the spawn: the Ice Spray Wand has to be in hand and used ON the spawn tick, so the
     * swap must finish first, and the jump goes in 6 ticks out. Ten leaves four ticks of slack before the jump.
     */
    private int stopLastBreathTicks = 10;

    /** Ticks before spawn to jump - killer560: "jump on about 300ms pre it spawning", and 300ms is 6 ticks. */
    private int jumpLeadTicks = 6;

    /**
     * Shift everything by your real ping instead of assuming a perfect connection.
     *
     * <p>300ms is 6 ticks only at 0 ping, and the jump and the spray have no slack between them - if they drift
     * apart the spray misses the spawn tick entirely. Same choice Blood Camp already offers.
     */
    private boolean pingCompensation = true;

    /** Extra ticks applied on top, for hand-tuning when ping compensation is off or not quite right. */
    private int manualOffsetTicks = 0;

    /** Walk to the split by itself. Off by default: killer560 asked for a manual option, and manual is the
     *  setting that cannot put you somewhere you did not intend during a boss. */
    private boolean autoPathing = false;

    private AutoDebuffConfig() {
    }

    public static AutoDebuffConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoDebuffConfig();
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            AutoDebuffConfig c = new AutoDebuffConfig();
            c.enabled = ConfigJson.getBool(o, "enabled", false);
            c.onMage = ConfigJson.getBool(o, "onMage", true);
            c.onHealer = ConfigJson.getBool(o, "onHealer", true);
            c.onTank = ConfigJson.getBool(o, "onTank", true);
            c.meleeAfter = ConfigJson.getBool(o, "meleeAfter", true);
            c.stopLastBreathTicks = ConfigJson.getInt(o, "stopLastBreathTicks", 10);
            c.jumpLeadTicks = ConfigJson.getInt(o, "jumpLeadTicks", 6);
            c.pingCompensation = ConfigJson.getBool(o, "pingCompensation", true);
            c.manualOffsetTicks = ConfigJson.getInt(o, "manualOffsetTicks", 0);
            c.autoPathing = ConfigJson.getBool(o, "autoPathing", false);
            instance = c;
        } catch (Exception e) {
            instance = new AutoDebuffConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("onMage", onMage);
            o.addProperty("onHealer", onHealer);
            o.addProperty("onTank", onTank);
            o.addProperty("meleeAfter", meleeAfter);
            o.addProperty("stopLastBreathTicks", stopLastBreathTicks);
            o.addProperty("jumpLeadTicks", jumpLeadTicks);
            o.addProperty("pingCompensation", pingCompensation);
            o.addProperty("manualOffsetTicks", manualOffsetTicks);
            o.addProperty("autoPathing", autoPathing);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isOnMage() { return onMage; }
    public void setOnMage(boolean v) { onMage = v; }
    public boolean isOnHealer() { return onHealer; }
    public void setOnHealer(boolean v) { onHealer = v; }
    public boolean isOnTank() { return onTank; }
    public void setOnTank(boolean v) { onTank = v; }
    public boolean isMeleeAfter() { return meleeAfter; }
    public void setMeleeAfter(boolean v) { meleeAfter = v; }
    public int getStopLastBreathTicks() { return stopLastBreathTicks; }
    public void setStopLastBreathTicks(int v) { stopLastBreathTicks = Math.max(0, Math.min(60, v)); }
    public int getJumpLeadTicks() { return jumpLeadTicks; }
    public void setJumpLeadTicks(int v) { jumpLeadTicks = Math.max(0, Math.min(40, v)); }
    public boolean isPingCompensation() { return pingCompensation; }
    public void setPingCompensation(boolean v) { pingCompensation = v; }
    public int getManualOffsetTicks() { return manualOffsetTicks; }
    public void setManualOffsetTicks(int v) { manualOffsetTicks = Math.max(-20, Math.min(20, v)); }
    public boolean isAutoPathing() { return autoPathing; }
    public void setAutoPathing(boolean v) { autoPathing = v; }
}
