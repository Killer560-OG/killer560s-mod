package com.killer560.hub.mining.skymall;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.RiftFishingHud;
import com.killer560.hub.mining.MiningAlerts;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sky Mall Display (legit, both builds; SkyHanni's Sky Mall Display): each SkyBlock day Hypixel announces your Heart of the
 * Mountain Sky Mall buff ("New buff: Gain +100⸕ Mining Speed."); this keeps it on a movable HUD, and can flash a title
 * when the new buff is the one you want (Alert On). The buff lasts one SkyBlock day (20 minutes), so a line older than
 * that is dropped. The Heart of the Forest's rotating perks use the same "New buff:" words; only the six Sky Mall buffs
 * are taken. Reads chat only. Off by default.
 */
public final class SkyMallDisplay {

    /** The six Sky Mall buffs: name shown, and words that identify the chat line (SkyHanni's SkymallPerk). */
    public enum Buff {
        MINING_SPEED("+100 Mining Speed", "mining speed"),
        MINING_FORTUNE("+50 Mining Fortune", "mining fortune"),
        EXTRA_POWDER("+15% more Powder", "more powder"),
        ABILITY_COOLDOWN("-20% Pickaxe Ability cooldowns", "pickaxe ability"),
        GOBLIN_CHANCE("10x Golden & Diamond Goblin chance", "golden and"),
        TITANIUM("5x Titanium drops", "titanium");

        public final String label;
        final String words;

        Buff(String label, String words) {
            this.label = label;
            this.words = words;
        }
    }

    /** "alert": 0 = none, 1..6 = Buff.values()[alert - 1]. */
    public static final IslandConfig CFG = IslandConfig.of("mining", "skymall")
            .flag("miningOnly", true)
            .num("alert", 0, 0, Buff.values().length);

    /** SkyHanni's HotxPatterns rotating-perk line; the perk text is bounded. */
    private static final Pattern NEW_BUFF = Pattern.compile("^New buff: (.{1,100})$");
    private static final long DAY_MS = 20 * 60_000L;

    private static volatile Buff current = null;
    private static volatile long seenAt = 0;
    private static volatile List<String> lines = List.of();
    private static int tick = 0;

    private SkyMallDisplay() {
    }

    public static void register() {
        ChatObserver.subscribe(SkyMallDisplay::onChat);
        int bit = FeatureBits.register("Sky Mall Display", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SkyMallDisplay.tick", bit, SkyMallDisplay::onTick));
        HudElementRegistry.register(new RiftFishingHud("mining_sky_mall", "Sky Mall Display", 10, 200,
                CFG::isEnabledRaw, () -> lines));
    }

    /** The Sky Mall buff a "New buff:" perk text names, or null for any other perk. Testkit entry point. */
    public static Buff classify(String perk) {
        if (perk == null) {
            return null;
        }
        String p = perk.toLowerCase(Locale.ROOT);
        for (Buff b : Buff.values()) {
            if (p.contains(b.words)) {
                return b;
            }
        }
        return null;
    }

    /** The buff held now (null when none was seen in the last 20 minutes). Read by the testkit. */
    public static Buff current() {
        return current != null && System.currentTimeMillis() - seenAt < DAY_MS ? current : null;
    }

    public static List<String> lines() {
        return lines;
    }

    /** The alert choice's label for the tab. */
    public static String alertLabel(int i) {
        return i <= 0 || i > Buff.values().length ? "None" : Buff.values()[i - 1].label;
    }

    private static void onChat(Component message) {
        if (!CFG.isEnabled()) {
            return;
        }
        Matcher m = NEW_BUFF.matcher(ChatObserver.strip(message));
        if (!m.matches()) {
            return;
        }
        Buff b = classify(m.group(1));
        if (b == null) {
            return;
        }
        current = b;
        seenAt = System.currentTimeMillis();
        int alert = CFG.num("alert");
        if (alert > 0 && Buff.values()[alert - 1] == b) {
            MiningAlerts.title("§aSky Mall", "§f" + b.label, 60);
        }
        rebuild(Minecraft.getInstance());
    }

    private static void onTick(Minecraft client) {
        if (tick++ % 20 == 0) {
            rebuild(client);
        }
    }

    private static void rebuild(Minecraft client) {
        Buff b = current();
        if (!CFG.isEnabled() || b == null || (CFG.on("miningOnly") && !MiningIsland.onMiningIsland())) {
            lines = List.of();
            return;
        }
        long left = Math.max(0, DAY_MS - (System.currentTimeMillis() - seenAt)) / 1000;
        lines = List.of("§6Sky Mall: §f" + b.label,
                String.format(Locale.ROOT, "§7Changes in %d:%02d", left / 60, left % 60));
    }
}
