package com.killer560.hub.maxor;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maxor's Crystals (F7/M7 Phase 1) - a port of NoammAddons'
 * {@code src/main/kotlin/com/github/noamm9/features/impl/floor7/MaxorsCrystals.kt} (local copy at
 * {@code C:\Users\Hunter\noammaddonsmod}). Everything is default OFF ({@link MaxorConfig}).
 *
 * <h2>Ported exactly, with its source line</h2>
 * <ul>
 * <li><b>Respawn timer</b> - {@code spawnRegex = "^\\[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!$|^\\[BOSS]
 * Maxor: YOU TRICKED ME!$"} sets {@code tickTimer = 34} and it counts down one per SERVER tick
 * (MaxorsCrystals.kt lines 24, 44-46, 92-98). Shown as seconds with 2 decimals ({@code tickTimer / 20.0}).</li>
 * <li><b>Place timer</b> - {@code pickupRegex = "^(\\w+) picked up an Energy Crystal!"}, only for your own
 * name (lines 23, 38-41); the stopwatch stops when an END_CRYSTAL entity appears at {@code y == 224} within
 * 5 blocks (2D) of you (lines 50-62), and the time is printed to chat, with a personal best (lines 64-70).</li>
 * <li><b>Place alert</b> - in F7 phase 1, if hotbar slot 8 holds an item named "Energy Crystal" (lines 79-83),
 * the warning line is drawn (Noamm's text: "⚠ Crystal ⚠").</li>
 * <li><b>Crystals active counter</b> - {@code "^(\\d)/(\\d) Energy Crystals are now active!$"}, NoammAddons
 * {@code features/impl/floor7/F7Titles.kt} line 34.</li>
 * </ul>
 *
 * <h2>Deliberately NOT ported / not invented</h2>
 * No crystal pad COORDINATES exist in any consulted source (NoammAddons' file has none, and this repo's
 * {@code f7spots} only ships P2 pad boxes), so no crystal spots are shipped: the optional highlight boxes
 * the END_CRYSTAL entities that are really in the world, and nothing is drawn from a hardcoded position.
 * The place-time personal best is stored in this feature's own config ({@code bestPlaceMs}) because the repo
 * has no {@code PersonalBest} store.
 *
 * <h2>One-tick caveat</h2>
 * NoammAddons stops the stopwatch inside the {@code ClientboundAddEntityPacket} handler; this port sees the
 * crystal on the next client tick instead (no new packet hook), so a placement time can read up to ~50 ms high.
 * If exact parity matters, forward the existing {@code WitherDragonsPackets.onAddEntity} entity to
 * {@link #onCrystalEntity(Entity)} - it is written to be callable from there unchanged.
 */
public final class MaxorCrystalsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-maxor");

    /** NoammAddons MaxorsCrystals.kt line 23. */
    private static final Pattern PICKUP = Pattern.compile("^(\\w+) picked up an Energy Crystal!");
    /** NoammAddons MaxorsCrystals.kt line 24. */
    private static final Pattern SPAWN = Pattern.compile(
            "^\\[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!$|^\\[BOSS] Maxor: YOU TRICKED ME!$");
    /** NoammAddons F7Titles.kt line 34. */
    private static final Pattern ACTIVE = Pattern.compile("^(\\d)/(\\d) Energy Crystals are now active!$");

    /** MaxorsCrystals.kt line 46. */
    private static final int RESPAWN_TICKS = 34;
    /** MaxorsCrystals.kt lines 55-57: the crystal's spawn Y and the 2D range from the player. */
    private static final int PLACE_Y = 224;
    private static final double PLACE_RANGE = 5.0;
    /** MaxorsCrystals.kt line 81. */
    private static final int CRYSTAL_HOTBAR_SLOT = 8;

    public static final HudElement HUD = new CrystalsHud();

    private static final Set<Integer> SEEN_CRYSTALS = new HashSet<>();

    private static Object lastLevel = null;
    private static boolean tickSubscribed = false;
    private static long pickupTimeMs = 0L;
    private static int tickTimer = -1;
    private static int activeCrystals = 0;
    private static int totalCrystals = 0;

    private MaxorCrystalsFeature() {
    }

    public static void register() {
        MaxorConfig.getInstance();
        // ChatObserver (not Fabric CHAT/GAME) so a line another mod cancelled and re-added still arrives.
        ChatObserver.subscribe(MaxorCrystalsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(MaxorCrystalsFeature::tick);
        ensureTickSubscribed();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!SkyblockGate.allows()) {
                return;
            }
            MaxorCrystalsRenderer.render(context);
        });
        LOGGER.info("[Maxor] Registered (all features default OFF)");
    }

    private static void ensureTickSubscribed() {
        if (tickSubscribed) {
            return;
        }
        tickSubscribed = true;
        ServerTickClock.register();
        ServerTickClock.subscribe(MaxorCrystalsFeature::onServerTick);
    }

    public static void reset() {
        SEEN_CRYSTALS.clear();
        pickupTimeMs = 0L;
        tickTimer = -1;
        activeCrystals = 0;
        totalCrystals = 0;
    }

    /** MaxorsCrystals.kt's {@code TickEvent.Server} countdown, on this mod's shared server-tick clock. */
    private static void onServerTick() {
        if (tickTimer > 0) {
            tickTimer--;
        } else if (tickTimer == 0) {
            tickTimer = -1;
        }
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        MaxorConfig cfg = MaxorConfig.getInstance();

        if (cfg.isPlaceTimerEnabled()) {
            Matcher pickup = PICKUP.matcher(plain);
            if (pickup.find() && pickup.group(1).equalsIgnoreCase(selfName())) {
                pickupTimeMs = System.currentTimeMillis();
            }
        }
        if (cfg.isSpawnTimerEnabled() && SPAWN.matcher(plain).matches()) {
            tickTimer = RESPAWN_TICKS;
        }
        Matcher active = ACTIVE.matcher(plain);
        if (active.matches()) {
            activeCrystals = Integer.parseInt(active.group(1));
            totalCrystals = Integer.parseInt(active.group(2));
        }
    }

    private static String selfName() {
        Minecraft client = Minecraft.getInstance();
        return client.getUser() == null ? "" : client.getUser().getName();
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.player == null || client.level == null) {
            return;
        }
        // Newly visible End Crystals; see the one-tick caveat in the class doc.
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getType() == EntityType.END_CRYSTAL && SEEN_CRYSTALS.add(entity.getId())) {
                onCrystalEntity(entity);
            }
        }
    }

    /** MaxorsCrystals.kt lines 50-70: y == 224 and within 5 blocks (2D) of you stops the placement stopwatch. */
    public static void onCrystalEntity(Entity entity) {
        MaxorConfig cfg = MaxorConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isPlaceTimerEnabled() || pickupTimeMs == 0L || entity == null || client.player == null) {
            return;
        }
        if ((int) Math.floor(entity.getY()) != PLACE_Y) {
            return;
        }
        double dx = entity.getX() - client.player.getX();
        double dz = entity.getZ() - client.player.getZ();
        if (Math.sqrt(dx * dx + dz * dz) >= PLACE_RANGE) {
            return;
        }
        long elapsedMs = System.currentTimeMillis() - pickupTimeMs;
        pickupTimeMs = 0L;
        long best = cfg.getBestPlaceMs();
        boolean isPb = best == 0L || elapsedMs < best;
        if (isPb) {
            cfg.setBestPlaceMs(elapsedMs);
            cfg.save();
        }
        String seconds = String.format(Locale.US, "%.3f", elapsedMs / 1000.0);
        String previous = best == 0L ? "none" : String.format(Locale.US, "%.3fs", best / 1000.0);
        if (isPb) {
            ModChat.send("Maxor", ModChat.text("Crystal placed in "), ModChat.value(seconds + "s"),
                    ModChat.good(" (PB)"), ModChat.dim(" old best: " + previous));
        } else {
            ModChat.send("Maxor", ModChat.text("Crystal placed in "), ModChat.value(seconds + "s"),
                    ModChat.dim(" best: " + previous));
        }
    }

    /** MaxorsCrystals.kt line 82: F7 phase 1 only. Chat-driven phase first, position fallback - the mod's own
     *  {@link Floor7Tracker}, so this also works on p3sim.net wherever that already does. */
    static boolean inP1() {
        if (!Floor7Tracker.inF7Boss()) {
            return false;
        }
        Floor7Tracker.Phase phase = Floor7Tracker.getPhase() == Floor7Tracker.Phase.UNKNOWN
                ? Floor7Tracker.getPhaseAt() : Floor7Tracker.getPhase();
        return phase == Floor7Tracker.Phase.P1;
    }

    /** MaxorsCrystals.kt line 81: hotbar slot 8 holding an "Energy Crystal". */
    private static boolean holdingUnplacedCrystal() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        ItemStack stack = client.player.getInventory().getItem(CRYSTAL_HOTBAR_SLOT);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return "energy crystal".equalsIgnoreCase(ChatObserver.strip(stack.getHoverName().getString()));
    }

    /** Movable/scalable in the HUD editor; drawn by {@code hud/HudInGameRenderer} (its id is in
     *  {@code UNDRAWN_ELEMENT_IDS}). Up to three lines: respawn countdown, unplaced-crystal alert, active counter. */
    private static final class CrystalsHud implements HudElement {

        @Override
        public String id() {
            return "maxor_crystals";
        }

        @Override
        public String displayName() {
            return "Maxor's Crystals";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Free row in the x=10 column (100/140/160/180/200/220/280/300/320/340 and 360 are taken).
            return 460;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 29;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            MaxorConfig cfg = MaxorConfig.getInstance();
            if (client.screen instanceof HudEditorScreen) {
                graphics.text(client.font, "§bCrystals: §f1.70", x, y, 0xFFFFFFFF, true);
                graphics.text(client.font, "§e⚠ §bCrystal §e⚠", x, y + 10, 0xFFFFFFFF, true);
                graphics.text(client.font, "§6Active: §e2§7/§e5", x, y + 20, 0xFFFFFFFF, true);
                return;
            }
            int row = 0;
            if (cfg.isSpawnTimerEnabled() && tickTimer >= 0) {
                graphics.text(client.font, "§bCrystals: §f" + String.format(Locale.US, "%.2f", tickTimer / 20.0),
                        x, y + row * 10, 0xFFFFFFFF, true);
                row++;
            }
            if (cfg.isPlaceAlertEnabled() && inP1() && holdingUnplacedCrystal()) {
                graphics.text(client.font, "§e⚠ §bCrystal §e⚠", x, y + row * 10, 0xFFFFFFFF, true);
                row++;
            }
            if (cfg.isActiveCounterEnabled() && totalCrystals > 0) {
                graphics.text(client.font, "§6Active: §e" + activeCrystals + "§7/§e" + totalCrystals,
                        x, y + row * 10, 0xFFFFFFFF, true);
            }
        }
    }
}
