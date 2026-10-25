package com.killer560.hub.bosshealth;

import com.killer560.hub.boss.LividSolverFeature;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.mixin.BossHealthOverlayAccessor;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boss Health (legit): one movable HUD line per Catacombs boss that is up, F1-M7 - Bonzo, Scarf and his four undead, the
 * Professor and his four Guardians, Thorn (lives), Livid (the real one), Sadan and his giants, and Maxor, Storm, Goldor and
 * Necron. The M7 dragons are Wither Dragons' (not repeated here). Numbers and their sources: {@link BossHealthText}.
 * <p>
 * Shown only in the boss room ({@link LiveMapFeature#isInBoss()}: the per-floor boss-room box, or F7's boss phase), so a
 * reincarnated Bonzo or Livid in a later floor's Blood Room is never read as the boss. Refreshed every 4 client ticks.
 */
public final class BossHealthFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosshealth");
    private static final int REFRESH_TICKS = 4;

    public static final HudElement HUD = new BossHealthHud();

    private static volatile List<String> lines = List.of();
    private static final Map<String, Long> SEEN_MAX = new HashMap<>();
    private static Object lastLevel;
    private static int tickCounter;
    private static String loggedNames = "";

    private BossHealthFeature() {
    }

    public static void register() {
        BossHealthConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BossHealthFeature.tick",
                FeatureBits.register("Boss Health tick", () -> BossHealthConfig.getInstance().isEnabledRaw()),
                BossHealthFeature::tick));
    }

    /** The lines the HUD draws right now (color codes included). Read by the testkit. */
    public static List<String> currentLines() {
        return lines;
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            SEEN_MAX.clear();
            lines = List.of();
        }
        if (++tickCounter < REFRESH_TICKS) {
            return;
        }
        tickCounter = 0;
        List<String> out = compute(client);
        lines = out;
        String names = namesOf(out);
        if (!names.equals(loggedNames)) {
            loggedNames = names;
            LOGGER.info("[BossHealth] showing: {}", out.isEmpty() ? "(nothing)" : String.join(" | ", out.stream()
                    .map(ChatObserver::stripCodes).toList()));
        }
    }

    private static String namesOf(List<String> out) {
        StringBuilder sb = new StringBuilder();
        // Logged when WHICH bosses show changes, not on every health tick: a line is "§c§l<name>§r ...".
        for (String l : out) {
            int end = l.indexOf("§r");
            sb.append(end > 4 ? l.substring(4, end) : l).append(';');
        }
        return sb.toString();
    }

    private static List<String> compute(Minecraft client) {
        BossHealthConfig cfg = BossHealthConfig.getInstance();
        if (!cfg.isEnabled() || client.level == null || client.player == null || !DungeonState.isInDungeon()) {
            return List.of();
        }
        String floorStr = DungeonState.getFloor();
        int floor = BossHealthText.floorNumber(floorStr);
        boolean master = BossHealthText.isMaster(floorStr);
        if (floor < 1 || floor > 7 || !LiveMapFeature.isInBoss()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        // Boss bars first: F4 Thorn and the F7 withers.
        if (floor == 4 || floor == 7) {
            BossHealthOverlay overlay = McCompat.bossOverlay(client);
            if (overlay != null) {
                for (LerpingBossEvent event : ((BossHealthOverlayAccessor) overlay).killer560smod$getEvents().values()) {
                    String boss = BossHealthText.barBoss(ChatObserver.strip(event.getName().getString()), floor);
                    if (boss == null) {
                        continue;
                    }
                    long max = BossHealthText.barMax(boss, master);
                    long current = Math.round(Math.max(0f, Math.min(1f, event.getProgress())) * max);
                    out.add(BossHealthText.line(boss, current, max, cfg.isShowMax(), cfg.isShowPercent(), "Thorn".equals(boss)));
                }
            }
        }
        BossHealthText.FloorBosses names = BossHealthText.tagNames(floor);
        if (names.bosses().isEmpty()) {
            return out;
        }
        // Name tags: nearest stand per name, every Livid stand kept so the real one can be picked.
        Map<String, ArmorStand> nearest = new LinkedHashMap<>();
        Map<ArmorStand, BossHealthText.Tag> tags = new HashMap<>();
        List<ArmorStand> livids = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand)) {
                continue;
            }
            Component custom = stand.getCustomName();
            if (custom == null) {
                continue;
            }
            BossHealthText.Tag tag = BossHealthText.parseTag(ChatObserver.strip(custom.getString()), floor);
            if (tag == null || (!tag.boss() && !cfg.isShowMinions())) {
                continue;
            }
            tags.put(stand, tag);
            if ("Livid".equals(tag.name())) {
                livids.add(stand);
                continue;
            }
            ArmorStand prev = nearest.get(tag.name());
            if (prev == null || stand.distanceToSqr(client.player) < prev.distanceToSqr(client.player)) {
                nearest.put(tag.name(), stand);
            }
        }
        ArmorStand livid = pickLivid(client, livids);
        if (livid != null) {
            nearest.put("Livid", livid);
        }
        // Bosses before minions, each in the floor's listed order.
        List<String> order = new ArrayList<>(names.bosses());
        order.addAll(names.minions());
        for (String name : order) {
            ArmorStand stand = nearest.get(name);
            if (stand == null) {
                continue;
            }
            BossHealthText.Tag tag = tags.get(stand);
            long seen = Math.max(SEEN_MAX.getOrDefault(name, 0L), tag.health());
            SEEN_MAX.put(name, seen);
            long max = Math.max(Math.max(BossHealthText.tagMax(name, master), tag.tagMax()), seen);
            out.add(BossHealthText.line(name, tag.health(), max, cfg.isShowMax(), cfg.isShowPercent(), false));
        }
        return out;
    }

    /**
     * The real Livid's name tag. Every Livid has one; the real one is told apart by the wool clue (Livid Solver's read,
     * whether or not that solver is on): the tag standing nearest the Livid player that carries the clue's name. With
     * one Livid tag in sight it is that one; with several and no clue yet, none (a clone's health is not the boss's).
     */
    private static ArmorStand pickLivid(Minecraft client, List<ArmorStand> livids) {
        if (livids.isEmpty()) {
            return null;
        }
        if (livids.size() == 1) {
            return livids.get(0);
        }
        String real = LividSolverFeature.realLividName(client.level);
        if (real == null) {
            return null;
        }
        Entity body = null;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof Player && real.equals(e.getName().getString())) {
                body = e;
                break;
            }
        }
        if (body == null) {
            return null;
        }
        ArmorStand best = null;
        double bestD = 9.0; // within 3 blocks horizontally
        for (ArmorStand s : livids) {
            double dx = s.getX() - body.getX();
            double dz = s.getZ() - body.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** Movable and scalable in the HUD editor; drawn by {@code HudInGameRenderer} (id in its UNDRAWN_ELEMENT_IDS). */
    private static final class BossHealthHud implements HudElement {

        private static final List<String> SAMPLE = List.of(
                BossHealthText.line("Maxor", 52_300_000L, 100_000_000L, true, false, false),
                BossHealthText.line("Healthy Guardian", 400_000L, 1_000_000L, true, false, false));

        private static List<String> rows() {
            if (McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen) {
                return SAMPLE;
            }
            return lines;
        }

        @Override
        public String id() {
            return "boss_health";
        }

        @Override
        public String displayName() {
            return "Boss Health";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 480;
        }

        @Override
        public int width() {
            return HudText.width(rows());
        }

        @Override
        public int height() {
            return HudText.height(rows().size(), 10);
        }

        @Override
        public boolean isEnabledInSettings() {
            return BossHealthConfig.getInstance().isEnabledRaw();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            List<String> rows = rows();
            for (int i = 0; i < rows.size(); i++) {
                graphics.text(client.font, rows.get(i), x, y + i * 10, 0xFFFFFFFF, true);
            }
            if (!rows.isEmpty() && !(McCompat.screen(client) instanceof HudEditorScreen)) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
