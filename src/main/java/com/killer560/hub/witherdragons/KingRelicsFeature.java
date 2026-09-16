package com.killer560.hub.witherdragons;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.splittimers.P5Splits;
import com.killer560.hub.splittimers.SplitTimersConfig;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M7 Phase 5 relic helpers - port of Odin's {@code KingRelics} module
 * (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/features/impl/boss/KingRelics.kt)
 * plus NoammAddons' {@code M7Relics.kt} (local copy a local NoammAddons checkout, features/impl/floor7):
 * <ul>
 * <li>Relic spawn timer: Necron's "All this, for nothing..." line starts a {@code Relic Spawn Ticks} (Odin default
 * 38) server-tick countdown shown on the "King Relic Timer" HUD.
 * <li>Held relic (by Skyblock id, or the "Corrupted &lt;Colour&gt; Relic" name) highlights its own cauldron
 * (Odin relic beacon / NoammAddons relic box), with an optional tracer.
 * <li>Your placement: Odin times a cauldron/anvil click while holding a relic from the Necron line. Here the click
 * only counts once the relic actually leaves your inventory within 1s, so a click on the wrong cauldron (which
 * Hypixel rejects) doesn't record a bogus time.
 * <li>Party placements: NoammAddons' check - an armor stand wearing a relic head standing on a relic's cauldron
 * x/z (&lt; 1.5 blocks) means that relic is placed; who picked it comes from "X picked the Corrupted Y Relic!".
 * Summary once all five are placed.
 * <li>Relic spawn: the first relic head equipped on an armor stand after the Necron line (Odin "relic spawned in").
 * Odin only checks this while you already hold a relic, which can't happen before they spawn - that gate is dropped.
 * </ul>
 * Everything also feeds {@link P5Splits} for the Split Timers "Relic" lines. Info/render only (legit).
 */
public final class KingRelicsFeature {

    // Odin KingRelics.relicPickupRegex (the Necron line that starts P5)
    private static final Pattern NECRON_END = Pattern.compile("^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$");
    // NoammAddons M7Relics.relicPickUpRegex
    private static final Pattern PICKUP = Pattern.compile("^(\\w{3,16}) picked the Corrupted (\\w{3,6}) Relic!$");

    private static long p5StartTick = 0L;
    private static int relicTicksToSpawn = 0;
    private static boolean announcedSpawn = false;
    private static KingRelic currentRelic = null;
    private static boolean yourRelicPlaced = false;
    private static KingRelic pendingRelic = null;
    private static long pendingTick = 0L;
    private static long pendingMs = 0L;
    private static final Map<KingRelic, String> PICKERS = new EnumMap<>(KingRelic.class);
    private static final Map<KingRelic, Double> PLACED = new EnumMap<>(KingRelic.class);
    private static boolean summarySent = false;
    private static Object lastLevel = null;

    private KingRelicsFeature() {
    }

    static void register() {
        ServerTickClock.subscribe(KingRelicsFeature::onServerTick);
        ChatObserver.subscribe(KingRelicsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(KingRelicsFeature::onWorldRender);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hit != null) {
                onBlockClick(player, level, hit.getBlockPos());
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            onBlockClick(player, level, pos);
            return InteractionResult.PASS;
        });
    }

    /** Relic tracking runs for this feature or the Split Timers relic lines. */
    static boolean active() {
        SplitTimersConfig split = SplitTimersConfig.getInstance();
        return WitherDragonsConfig.getInstance().isRelicsEnabled() || (split.isEnabled() && split.isP5RelicLines());
    }

    private static boolean messagesOn() {
        return WitherDragonsConfig.getInstance().isRelicsEnabled();
    }

    private static void reset() {
        p5StartTick = 0L;
        relicTicksToSpawn = 0;
        announcedSpawn = false;
        currentRelic = null;
        yourRelicPlaced = false;
        pendingRelic = null;
        PICKERS.clear();
        PLACED.clear();
        summarySent = false;
    }

    // ------------------------------------------------------------------ events

    private static void onChat(Component message) {
        if (!active()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // NoammAddons gates on "in boss + M7" rather than Odin's y<=45 P5 check: players can still be above y 45
        // when Necron's last line arrives.
        // F7 has no Phase 5 (Necron's line ends the run there) - only M7, p3sim, or the manual sim override.
        boolean inBoss = (DungeonState.isBossPhaseActive() && (DungeonState.isSimOverrideActive() || !"F7".equals(DungeonState.getFloor())))
                || P5State.isP3Sim(client);
        if (inBoss && NECRON_END.matcher(plain).matches()) {
            reset();
            p5StartTick = ServerTickClock.now();
            relicTicksToSpawn = WitherDragonsConfig.getInstance().getRelicSpawnTicks();
            P5Splits.p5Started(System.currentTimeMillis());
            WitherDragonsFeature.LOGGER.info("[KingRelics] P5 started (Necron line), relic spawn in {} ticks", relicTicksToSpawn);
            return;
        }
        Matcher m = PICKUP.matcher(plain);
        if (p5StartTick != 0L && m.matches()) {
            KingRelic relic = KingRelic.fromColour(m.group(2));
            if (relic != null) {
                PICKERS.put(relic, m.group(1));
            }
        }
    }

    private static void onServerTick() {
        if (!active()) {
            return;
        }
        if (relicTicksToSpawn > 0) {
            relicTicksToSpawn--;
        }
        if (!P5State.inP5()) {
            return;
        }
        currentRelic = heldRelic();
        if (pendingRelic != null) {
            if (currentRelic != pendingRelic) {
                placeYours(pendingRelic);
            } else if (ServerTickClock.now() - pendingTick > 20) {
                pendingRelic = null; // still holding it - the click didn't place it
            }
        }
    }

    private static void onClientTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.level == null || p5StartTick == 0L || !active() || !P5State.inP5() || PLACED.size() == KingRelic.values().length) {
            return;
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand)) {
                continue;
            }
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty() || !head.getHoverName().getString().contains("Relic")) {
                continue;
            }
            for (KingRelic r : KingRelic.values()) {
                if (PLACED.containsKey(r)) {
                    continue;
                }
                double dx = stand.getX() - r.placedX;
                double dz = stand.getZ() - r.placedZ;
                if (Math.sqrt(dx * dx + dz * dz) < 1.5) {
                    double seconds = (ServerTickClock.now() - p5StartTick) / 20.0;
                    PLACED.put(r, seconds);
                    P5Splits.relicPlaced(r.key(), r.colored(), System.currentTimeMillis(), PICKERS.get(r));
                    WitherDragonsFeature.LOGGER.info("[KingRelics] {} relic placed at cauldron after {}s (picker={})", r, seconds, PICKERS.get(r));
                }
            }
        }
        if (!summarySent && PLACED.size() == KingRelic.values().length) {
            summarySent = true;
            if (messagesOn() && WitherDragonsConfig.getInstance().isRelicSummary()) {
                List<Map.Entry<KingRelic, Double>> sorted = new ArrayList<>(PLACED.entrySet());
                sorted.sort(Map.Entry.comparingByValue());
                for (Map.Entry<KingRelic, Double> entry : sorted) {
                    String who = PICKERS.get(entry.getKey());
                    send(entry.getKey().colored() + " §aRelic placed in §e" + String.format(Locale.US, "%.2f", entry.getValue())
                            + "s§a." + (who != null ? " §7(" + who + ")" : ""));
                }
            }
        }
    }

    /** Odin {@code EntityEvent.SetItemSlot}: a relic head on an armor stand = relics have spawned. */
    static void onEquipment(ClientboundSetEquipmentPacket packet) {
        if (!active() || p5StartTick == 0L || announcedSpawn) {
            return;
        }
        for (var pair : packet.getSlots()) {
            ItemStack stack = pair.getSecond();
            if (stack == null || !stack.is(Items.PLAYER_HEAD)) {
                continue;
            }
            KingRelic relic = KingRelic.fromStack(stack);
            if (relic == null) {
                continue;
            }
            announcedSpawn = true;
            double seconds = (ServerTickClock.now() - p5StartTick) / 20.0;
            P5Splits.relicSpawned(System.currentTimeMillis());
            WitherDragonsFeature.LOGGER.info("[KingRelics] Relics spawned {}s after P5 start", seconds);
            if (messagesOn()) {
                send(relic.colored() + " relic §7spawned in §6" + String.format(Locale.US, "%.2f", seconds) + "s");
            }
            return;
        }
    }

    private static void onBlockClick(Player player, Level level, BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (!level.isClientSide() || player != client.player || pos == null || !active() || p5StartTick == 0L
                || yourRelicPlaced || pendingRelic != null || !P5State.inP5()) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractCauldronBlock) && !state.is(BlockTags.ANVIL)) {
            return;
        }
        KingRelic held = heldRelic();
        if (held == null) {
            return;
        }
        pendingRelic = held;
        pendingTick = ServerTickClock.now();
        pendingMs = System.currentTimeMillis();
    }

    private static void placeYours(KingRelic relic) {
        yourRelicPlaced = true;
        pendingRelic = null;
        double seconds = (pendingTick - p5StartTick) / 20.0;
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        P5Splits.relicPlaced(relic.key(), relic.colored(), pendingMs, self);
        WitherDragonsFeature.LOGGER.info("[KingRelics] You placed the {} relic {}s after P5 start", relic, seconds);
        if (messagesOn() && WitherDragonsConfig.getInstance().isRelicPlaceTime()) {
            send(relic.colored() + " relic §7placed in §6" + String.format(Locale.US, "%.2f", seconds) + "s§7!");
        }
    }

    /** Odin: {@code Relic.entries.find { inventory contains item with that id }}. */
    private static KingRelic heldRelic() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            KingRelic r = KingRelic.fromStack(inv.getItem(i));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static void send(String text) {
        ModChat.send("King Relics", Component.literal(text));
    }

    // ------------------------------------------------------------------ render

    private static void onWorldRender(LevelRenderContext context) {
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        KingRelic relic = currentRelic;
        if (!cfg.isRelicsEnabled() || !cfg.isRelicHighlight() || relic == null || client.player == null || !P5State.inP5()) {
            return;
        }
        float[] c = WorldRenderUtils.argbToFloats(relic.argb);
        BlockPos p = relic.cauldronPos;
        AABB block = new AABB(p);
        WorldRenderUtils.renderFilledBox(context, block, c[0], c[1], c[2], 0.35f);
        WorldRenderUtils.renderOutlineBox(context, block.inflate(0.002), c[0], c[1], c[2], 1f, 3f);
        // Odin drawCustomBeacon: a tall beam over the cauldron so it's findable from across the room.
        AABB beam = new AABB(p.getX() + 0.35, p.getY() + 1, p.getZ() + 0.35, p.getX() + 0.65, p.getY() + 40, p.getZ() + 0.65);
        WorldRenderUtils.renderFilledBox(context, beam, c[0], c[1], c[2], 0.25f);
        if (cfg.isRelicTracer()) {
            WorldRenderUtils.renderLineStrip(context, List.of(client.player.getEyePosition(),
                    new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)), c[0], c[1], c[2], 1f, 2f);
        }
    }

    // ------------------------------------------------------------------ HUD

    /** Odin "Relic Hud": "Relics: 1.85s" while the relic spawn countdown runs. */
    public static final class RelicTimerHudElement implements HudElement {
        @Override
        public String id() {
            return "king_relic_timer";
        }

        @Override
        public String displayName() {
            return "King Relic Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 240;
        }

        @Override
        public int width() {
            return 90;
        }

        @Override
        public int height() {
            return 12;
        }

        @Override
        public boolean isRelevantNow() {
            WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
            return cfg.isRelicsEnabled() && cfg.isRelicSpawnTimer() && DungeonState.isF7OrM7();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
            if (!cfg.isRelicsEnabled() || !cfg.isRelicSpawnTimer()) {
                return;
            }
            String text;
            if (client.screen instanceof HudEditorScreen) {
                text = "§3Relics: 1.90s";
            } else if (!HudVisibility.hidesHud() && relicTicksToSpawn > 0) {
                text = "§3Relics: " + String.format(Locale.US, "%.2f", relicTicksToSpawn / 20f) + "s";
            } else {
                return;
            }
            graphics.text(client.font, text, x, y, 0xFFFFFFFF, true);
        }
    }
}
