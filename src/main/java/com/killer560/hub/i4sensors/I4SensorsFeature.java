package com.killer560.hub.i4sensors;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * "I4" (the F7/M7 P3 section-4 arrow device right before Necron's P4) shared state: whether the player is near
 * the device, and the i4 timeline counted from Storm's death line. (This class used to also be a wide sensor
 * logger built for sim testing; that logging was removed once Auto i4 shipped.)
 * <p>
 * Real device layout now CONFIRMED from NoammAddons' own {@code I4Helper.kt}/{@code AutoI4.kt} (cloned
 * reference at a local NoammAddons checkout): a 3x3 wall of target blocks at z=50, x in {68,66,64}, y in
 * {130,128,126}; a lit target is {@code EMERALD_BLOCK}, a hit one turns {@code BLUE_TERRACOTTA}; the player
 * stands on the device at y~127, x 62-65, z 34-37 and shoots them with a bow. Noamm times its rod swap /
 * mask swap / leap off server ticks counted from Storm's death line (174 / 244 / 307; since 1.2.9 from Goldor's
 * "Who dares trespass" line, 70 / 140 / 203 - the same moments, minus its old 104-tick gap) and treats an armor
 * stand renamed "Active" or the "completed a device!" chat line as completion. The original version of this
 * class only diffed the standing platform (QUOI's pre4Box) and never watched the target wall at all.
 * <p>
 * Nothing here clicks, rotates, or changes anything. It is always on (no toggle) whenever the player is near
 * the device on hypixel.net or p3sim.net. {@link #ticksSinceStormDeath()} counts client ticks since Storm's
 * death line, so it lines up directly with Noamm's server-tick timings.
 */
public final class I4SensorsFeature {

    // Real target wall from NoammAddons' I4Helper.devBlocks - index = row * 3 + col, row 0 = top (y 130),
    // col 0 = x 68.
    static final List<BlockPos> DEV_BLOCKS = List.of(
            new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
            new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
            new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
    private static final AABB NEAR_BOX = new AABB(45, 110, 20, 85, 150, 65);
    private static final String STORM_DEATH_LINE = "[BOSS] Storm: I should have known that I stood no chance.";
    /** P3's real start. Exact line, as in TickTimersFeature / Floor7Tracker. */
    private static final String GOLDOR_START_LINE = "[BOSS] Goldor: Who dares trespass into my domain?";
    /** Storm's death line to Goldor's (P3's start) since SkyBlock 0.27.2: 17 server ticks, NoammAddons 1.2.9's
     *  measurement (dev/Timer.kt), shared with Tick Timers' Goldor "Start:". Was 3000 ms from killer560's two runs
     *  (Storm died 09:57:54 and 10:02:45, Goldor spoke 09:57:57 and 10:02:48, 1 s log resolution) - those disagree with
     *  17 ticks and are unexplained; on that pacing the window below just opens about 2 s earlier than "a little". Before
     *  0.27.2 it was 104 ticks. */
    static final long STORM_TO_GOLDOR_MS = com.killer560.hub.ticktimers.TickTimersFeature.GOLDOR_START_TICKS * 50L;

    // --- session (player near the device) ---
    private static boolean near = false;

    // --- timeline (persists across sessions within a world, reset on world change) ---
    private static long stormDeathAtMs = 0L;
    private static int stormDeathClientTick = -1;
    private static int goldorLineClientTick = -1;
    private static int clientTick = 0;
    private static Object lastLevel = null;

    private I4SensorsFeature() {
    }

    public static void register() {
        // Real bug found and fixed (2026-09-14, real Hypixel F7 log): Fabric's CHAT/GAME events never fired for a
        // line another mod cancels via ALLOW_GAME and re-adds straight to ChatComponent (Odin's Terminal Splits
        // does this to "completed a device!"), so this always-on chat logging stayed silent for it. ChatObserver
        // sees both paths, de-duplicated, non-overlay only.
        ChatObserver.subscribe(message -> onChatMessage(message, false));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("I4SensorsFeature", client -> tick()));
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private static void onChatMessage(Component message, boolean overlay) {
        if (overlay) {
            return;
        }
        String raw = message.getString();
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain == null) {
            plain = raw;
        }
        Minecraft client = Minecraft.getInstance();
        if (!isOnDungeonServer(client)) {
            return;
        }
        if (plain.equals(STORM_DEATH_LINE)) {
            stormDeathAtMs = System.currentTimeMillis();
            stormDeathClientTick = clientTick;
            goldorLineClientTick = -1;
            return;
        }
        if (plain.equals(GOLDOR_START_LINE) && stormDeathClientTick >= 0) {
            goldorLineClientTick = clientTick;
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void tick() {
        clientTick++;
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            if (lastLevel != null) {
                stormDeathAtMs = 0L;
                stormDeathClientTick = -1;
                goldorLineClientTick = -1;
            }
            lastLevel = client.level;
        }
        LocalPlayer player = client.player;
        boolean nowNear = player != null && client.level != null && isOnDungeonServer(client) && com.killer560.hub.util.SkyblockGate.allows()
                && NEAR_BOX.contains(player.position());
        near = nowNear;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Whether the always-on sensors' session is active: on hypixel.net/p3sim.net and inside the area around i4. */
    static boolean isNearDevice() {
        return near;
    }

    /** Client ticks since Storm's death line this world, or -1 if it hasn't been seen. Stands in for Noamm's
     *  server-tick counter (this mod has no server-tick source) - equal to it without lag. */
    static int ticksSinceStormDeath() {
        return stormDeathClientTick < 0 ? -1 : clientTick - stormDeathClientTick;
    }

    /**
     * Whether Auto i4 may prefire before the device's first target lights (killer560, 2026-10-06: "prefiring dev is
     * something that it needs to do as well a little bit before it starts"). P3 - and the device - starts at Goldor's
     * line, which comes {@link #STORM_TO_GOLDOR_MS} after Storm's death on 0.27.2's pacing: the window opens
     * {@code leadMs} before that expected moment, and at Goldor's line itself whichever is first. Closed before
     * Storm's death this world.
     */
    static boolean inPrefireWindow(int leadMs) {
        if (stormDeathClientTick < 0) {
            return false;
        }
        if (goldorLineClientTick >= 0) {
            return true;
        }
        return (clientTick - stormDeathClientTick) * 50L >= STORM_TO_GOLDOR_MS - leadMs;
    }

    /** Wall-clock ms of the last Storm death line (0 = none) - changes when a new timeline starts. */
    static long stormDeathAtMs() {
        return stormDeathAtMs;
    }

    /** Noamm's own isOnDev(): |y - 127| &lt; 0.5, x in [62, 65], z in [34, 37]. */
    static boolean isOnDevice(Vec3 p) {
        return Math.abs(p.y - 127.0) < 0.5 && p.x >= 62.0 && p.x <= 65.0 && p.z >= 34.0 && p.z <= 37.0;
    }

    /** The Skyblock id from CustomData "id", or "" if none. */
    static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return "";
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", "") : "";
    }

    static boolean isOnDungeonServer(Minecraft client) {
        if (client.getCurrentServer() == null) {
            return false;
        }
        String ip = client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        return ip.contains("hypixel.net") || ip.contains("p3sim.net");
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    static String itemDesc(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String skyblockId = null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            skyblockId = tag.contains("id") ? tag.getStringOr("id", null) : null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                + (skyblockId != null ? " sb=" + skyblockId : "")
                + " \"" + ChatFormatting.stripFormatting(stack.getHoverName().getString()) + "\"";
    }

    static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
