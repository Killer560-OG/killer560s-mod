package com.killer560.hub.mining.worms;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.islands.RiftFishingHud;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Worm Alert (legit, both builds): in the Crystal Hollows, Hypixel's "You hear the sound of something approaching..." (a
 * Stoneworm or a Scatha spawning from your mining) gets a title and a sound, and a movable HUD counts the 30-second spawn
 * cooldown down to "Worm ready". In the 10 seconds after that line, a nametag reading "[Lv..] Scatha" within 32 blocks
 * turns the alert into a Scatha one (each Scatha once). Game facts from hypixelskyblock.minecraft.wiki's Worm page; the
 * nametag shape follows SkyHanni's "[Lvl 100] Scatha" pet test and is unverified for the mob. Reads chat and nametags
 * only. Off by default.
 */
public final class WormAlert {

    public static final IslandConfig CFG = IslandConfig.of("mining", "worms")
            .flag("title", true)
            .flag("sound", true)
            .flag("scatha", true)
            .flag("hud", true);

    private static final String SPAWN = "You hear the sound of something approaching...";
    /** "[Lv10] Scatha 2M/2M❤", anywhere in a tag of at most 80 characters (boss tags carry decorations around it);
     *  armor-stand nametags come from the server, never from another player. */
    private static final Pattern SCATHA = Pattern.compile("\\[Lv\\d{1,3}] Scatha\\b");
    private static final long COOLDOWN_MS = 30_000L;
    private static final long SCATHA_WINDOW_MS = 10_000L;

    private static volatile long spawnAt = 0;
    private static volatile int spawns = 0;
    private static volatile int scathas = 0;
    private static final Set<UUID> SEEN = new HashSet<>();
    private static volatile List<String> lines = List.of();
    private static int tick = 0;

    private WormAlert() {
    }

    public static void register() {
        ChatObserver.subscribe(WormAlert::onChat);
        int bit = FeatureBits.register("Worm Alert", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("WormAlert.tick", bit, WormAlert::onTick));
        HudElementRegistry.register(new RiftFishingHud("mining_worm_cooldown", "Worm Cooldown", 10, 220,
                () -> CFG.isEnabledRaw() && CFG.on("hud"), () -> lines));
    }

    /** Worm spawn lines seen this session (read by the testkit). */
    public static int spawns() {
        return spawns;
    }

    /** Scathas announced this session (read by the testkit). */
    public static int scathas() {
        return scathas;
    }

    public static List<String> lines() {
        return lines;
    }

    /** Whether a plain nametag is a Scatha's. Testkit entry point. */
    public static boolean isScathaTag(String plain) {
        return plain != null && plain.length() <= 80 && SCATHA.matcher(plain).find();
    }

    private static void onChat(Component message) {
        if (!CFG.isEnabled() || !MiningIsland.crystalHollows() || !SPAWN.equals(ChatObserver.strip(message))) {
            return;
        }
        spawnAt = System.currentTimeMillis();
        spawns++;
        if (CFG.on("title") || CFG.on("sound")) {
            Islands.alert(CFG.on("title") ? "§c§lWorm!" : "", CFG.on("title") ? "§7Something is approaching" : "",
                    CFG.on("sound"));
        }
        rebuild();
    }

    private static void onTick(Minecraft client) {
        if (!CFG.isEnabled() || client.level == null || client.player == null || !MiningIsland.crystalHollows()) {
            lines = List.of();
            return;
        }
        if (tick++ % 5 != 0) {
            return;
        }
        rebuild();
        long since = System.currentTimeMillis() - spawnAt;
        if (!CFG.on("scatha") || spawnAt == 0 || since > SCATHA_WINDOW_MS) {
            return;
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand) || e.distanceToSqr(client.player) > 32 * 32 || SEEN.contains(e.getUUID())) {
                continue;
            }
            if (isScathaTag(IslandRender.plainName(e))) {
                if (SEEN.size() > 64) {
                    SEEN.clear();
                }
                SEEN.add(e.getUUID());
                scathas++;
                Islands.alert("§d§lSCATHA!", "§7A Scatha spawned", CFG.on("sound"));
                break;
            }
        }
    }

    private static void rebuild() {
        if (!CFG.on("hud") || spawnAt == 0) {
            lines = List.of();
            return;
        }
        long left = COOLDOWN_MS - (System.currentTimeMillis() - spawnAt);
        String first = left > 0
                ? String.format(Locale.ROOT, "§eWorm cooldown: §f%ds", (left + 999) / 1000)
                : "§aWorm ready";
        lines = List.of(first, "§7Worms this session: §f" + spawns + (scathas > 0 ? " §d(" + scathas + " Scatha)" : ""));
    }
}
