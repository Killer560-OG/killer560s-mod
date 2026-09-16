package com.killer560.hub.witherdragons;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.splittimers.P5Splits;
import com.killer560.hub.splittimers.SplitTimersConfig;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M7 Phase 5 Wither Dragons - port of Odin's {@code WitherDragons} module
 * (https://github.com/odtheking/Odin/tree/main/src/main/kotlin/com/odtheking/odin/features/impl/boss :
 * WitherDragons.kt, WitherDragonsEnum.kt, DragonCheck.kt, DragonPriority.kt), with NoammAddons' additions
 * (arrow-hit counter, packed-ice spray check - local copy a local NoammAddons checkout,
 * features/impl/floor7/dragons). SkyHanni has no M7 dragon features (its dragon code is End dragons only).
 * <p>
 * Flow (Odin):
 * <ul>
 * <li>Spawn: Hypixel plays a FLAME particle burst (count 20, y 19, spread 2/3/2, speed 0, whole-block x/z) over a
 * dragon's statue ~5s before it spawns. The x/z range picks the dragon -&gt; SPAWNING with a 100 server-tick
 * countdown. When two are spawning at once (or at least two have spawned before), the priority dragon is picked
 * ({@link DragonPriority}) and titled.
 * <li>Alive: countdown reaching 0, or an EnderDragon added inside that dragon's box.
 * <li>Dead: synched health &lt;= 0 (timed "alive for"), its statue block turning to air, or a Wither King
 * "counts" line ("Oh, this one hurts!" etc.) for the last/first living dragon.
 * </ul>
 * Pure info/render: nothing is clicked, aimed or sent to the server (legit build).
 */
public final class WitherDragonsFeature {

    static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-witherdragons");

    // Odin WitherDragons.witherKingRegex
    private static final Pattern WITHER_KING_REGEX =
            Pattern.compile("^\\[BOSS] Wither King: (Oh, this one hurts!|I have more of those\\.|My soul is disposable\\.)$");

    private static WitherDragon priorityDragon = null;
    private static WitherDragon lastDragonDeath = null;
    private static final Map<UUID, EnderDragon> DRAGON_ENTITIES = new LinkedHashMap<>();
    private static Object lastLevel = null;

    private WitherDragonsFeature() {
    }

    public static void register() {
        ServerTickClock.register();
        ServerTickClock.subscribe(WitherDragonsFeature::onServerTick);
        ChatObserver.subscribe(WitherDragonsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WitherDragonsFeature::onWorldRender);
        KingRelicsFeature.register();
    }

    /** Detection runs if anything that consumes it is on: this feature, King Relics, or the P5 split lines. */
    static boolean trackingActive() {
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        SplitTimersConfig split = SplitTimersConfig.getInstance();
        return cfg.isEnabled() || cfg.isRelicsEnabled() || (split.isEnabled() && split.isP5DragonLines());
    }

    private static boolean messagesOn() {
        return WitherDragonsConfig.getInstance().isEnabled();
    }

    // ------------------------------------------------------------------ ticks / world change

    private static void onClientTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            // Odin LevelEvent.Load: dragonHealthMap.clear(); WitherDragonsEnum.reset()
            lastLevel = client.level;
            WitherDragon.resetAll();
            DRAGON_ENTITIES.clear();
            priorityDragon = null;
            lastDragonDeath = null;
            P5State.reset();
        }
        // Outside the P5 gate: leaving P5 without changing level (dying, a /warp out) otherwise strands every
        // dragon entity in the map until the next world change.
        if (!DRAGON_ENTITIES.isEmpty()) {
            DRAGON_ENTITIES.values().removeIf(e -> e.isRemoved());
        }
        if (client.level == null || !trackingActive() || !P5State.inP5()) {
            return;
        }
        pollStatues(client.level);
    }

    /** Odin BlockUpdateEvent: a statue block becoming air = that dragon died (not timed). Polled here as a
     *  solid -&gt; air transition, which covers both single-block and section block-update packets. */
    private static void pollStatues(ClientLevel level) {
        for (WitherDragon d : WitherDragon.values()) {
            if (!level.isLoaded(d.statuePos)) {
                continue;
            }
            boolean solid = !level.getBlockState(d.statuePos).isAir();
            if (d.statueWasSolid && !solid) {
                LOGGER.info("[WitherDragons] {} statue block at {} turned to air -> dead", d, d.statuePos);
                setDead(d, false);
            }
            d.statueWasSolid = solid;
        }
    }

    /** Odin TickEvent.Server. */
    private static void onServerTick() {
        for (WitherDragon d : WitherDragon.values()) {
            if (d.timeToSpawn > 0) {
                d.timeToSpawn--;
            } else if (d.state == WitherDragon.State.SPAWNING) {
                setAlive(d, null);
            }
        }
    }

    // ------------------------------------------------------------------ state transitions (WitherDragonsEnum)

    private static void setAlive(WitherDragon d, UUID uuid) {
        if (uuid != null) {
            d.entityUUID = uuid;
        }
        if (d.state != WitherDragon.State.SPAWNING) {
            return;
        }
        d.state = WitherDragon.State.ALIVE;
        d.timesSpawned++;
        d.spawnedTick = ServerTickClock.now();
        d.spawnedMs = System.currentTimeMillis();
        d.sprayed = false;
        d.arrowsHit = 0;
        LOGGER.info("[WitherDragons] {} ALIVE (#{}, uuid={})", d, d.timesSpawned, uuid);
        P5Splits.dragonSpawned(d.name(), d.colored(), d.timesSpawned, d.spawnedMs);
        if (messagesOn() && WitherDragonsConfig.getInstance().isSendSpawned()) {
            send(Component.literal(d.colored() + " §fdragon spawned §8(§7" + d.timesSpawned + "§8)"));
        }
    }

    private static void setDead(WitherDragon d, boolean realTime) {
        boolean wasAlive = d.state == WitherDragon.State.ALIVE;
        d.state = WitherDragon.State.DEAD;
        d.entityUUID = null;
        lastDragonDeath = d;
        boolean wasPriority = priorityDragon == d;
        if (wasPriority) {
            priorityDragon = null;
        }
        if (!wasAlive) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        P5Splits.dragonKilled(d.name(), nowMs);
        LOGGER.info("[WitherDragons] {} DEAD (realTime={}, alive {} ticks, arrows={}, sprayed={})", d, realTime,
                ServerTickClock.now() - d.spawnedTick, d.arrowsHit, d.sprayed);
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        if (!messagesOn() || !realTime) {
            return;
        }
        List<String> stats = new ArrayList<>();
        if (cfg.isSendTime()) {
            stats.add("§7alive for §6" + String.format(Locale.US, "%.2f", (ServerTickClock.now() - d.spawnedTick) / 20f) + "s");
        }
        if (cfg.isSendArrows() && wasPriority) {
            stats.add("§farrows hit §6" + d.arrowsHit);
        }
        if (!stats.isEmpty()) {
            send(Component.literal(d.colored() + " " + String.join(" §8| ", stats)));
        }
    }

    // ------------------------------------------------------------------ packets (from WitherDragonsPackets)

    /** Odin {@code handleSpawnPacket}. */
    static void onParticles(ClientboundLevelParticlesPacket p) {
        if (!trackingActive() || !P5State.inP5()) {
            return;
        }
        if (p.getCount() != 20 || p.getY() != 19.0 || p.getParticle().getType() != ParticleTypes.FLAME
                || p.getXDist() != 2f || p.getYDist() != 3f || p.getZDist() != 2f || p.getMaxSpeed() != 0f
                || p.getX() % 1 != 0.0 || p.getZ() % 1 != 0.0) {
            return;
        }
        int spawned = 0;
        List<WitherDragon> dragons = new ArrayList<>();
        for (WitherDragon d : WitherDragon.values()) {
            spawned += d.timesSpawned;
            if (d.state == WitherDragon.State.SPAWNING) {
                dragons.add(d);
                continue;
            }
            if (!d.particleInRange(p.getX(), p.getZ())) {
                continue;
            }
            d.state = WitherDragon.State.SPAWNING;
            d.timeToSpawn = 100;
            dragons.add(d);
            LOGGER.info("[WitherDragons] {} SPAWNING (particles at {}, {})", d, p.getX(), p.getZ());
            WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
            if (messagesOn() && cfg.isDragonTitle() && cfg.getTitleMode() == WitherDragonsConfig.TitleMode.EVERY) {
                alert(d, "");
            }
        }
        if (!dragons.isEmpty() && (dragons.size() == 2 || spawned >= 2) && priorityDragon == null) {
            DragonPriority.Result result = DragonPriority.findPriority(dragons);
            priorityDragon = result.dragon();
            WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
            if (!messagesOn()) {
                return;
            }
            String list = joinColored(dragons);
            if (cfg.isDragonTitle()) {
                alert(priorityDragon, dragons.size() > 1 && cfg.isDragonPriority()
                        ? "§7" + list + " §8-> §fgo " + priorityDragon.colored() : "");
            }
            if (cfg.isDragonPriority() && dragons.size() > 1) {
                send(Component.literal(list + "§r -> " + priorityDragon.colored() + " §7is your priority dragon! §8("
                        + result.reason() + ")"));
            }
        }
    }

    /** Odin {@code DragonCheck.dragonSpawn} (EntityEvent.Add). */
    static void onAddEntity(Entity entity) {
        if (!(entity instanceof EnderDragon dragon) || !trackingActive() || !P5State.inP5()) {
            return;
        }
        DRAGON_ENTITIES.put(dragon.getUUID(), dragon);
        for (WitherDragon d : WitherDragon.values()) {
            if (d.box.contains(dragon.position())) {
                setAlive(d, dragon.getUUID());
                return;
            }
        }
    }

    /** Odin {@code DragonCheck.dragonUpdate} (synched data id 9 = health), read after vanilla applied it. */
    static void onEntityData(Entity entity) {
        if (!(entity instanceof EnderDragon dragon) || !trackingActive() || !P5State.inP5()) {
            return;
        }
        DRAGON_ENTITIES.put(dragon.getUUID(), dragon);
        float health = dragon.getHealth();
        for (WitherDragon d : WitherDragon.values()) {
            if (dragon.getUUID().equals(d.entityUUID)) {
                d.health = health;
                if (health <= 0f && d.state != WitherDragon.State.DEAD) {
                    setDead(d, true);
                }
                return;
            }
        }
    }

    /** Ice spray: an armor stand given packed ice within 8 blocks of a living, unsprayed dragon. NoammAddons'
     *  check (packed ice REQUIRED); Odin's current {@code DragonCheck.dragonSprayed} returns early ON packed ice,
     *  which looks inverted, so NoammAddons' direction is used. Message wording is Odin's. */
    static void onEquipment(ClientboundSetEquipmentPacket packet, ClientLevel level) {
        if (level == null || !trackingActive() || !P5State.inP5()) {
            return;
        }
        boolean ice = false;
        for (var pair : packet.getSlots()) {
            ItemStack stack = pair.getSecond();
            if (stack != null && stack.is(Items.PACKED_ICE)) {
                ice = true;
                break;
            }
        }
        if (!ice || !(level.getEntity(packet.getEntity()) instanceof ArmorStand stand)) {
            return;
        }
        for (WitherDragon d : WitherDragon.values()) {
            if (d.sprayed || d.state != WitherDragon.State.ALIVE || d.entityUUID == null) {
                continue;
            }
            EnderDragon entity = DRAGON_ENTITIES.get(d.entityUUID);
            if (entity == null || entity.isRemoved() || stand.distanceTo(entity) > 8) {
                continue;
            }
            d.sprayed = true;
            long ticks = ServerTickClock.now() - d.spawnedTick;
            LOGGER.info("[WitherDragons] {} sprayed {} ticks after spawn", d, ticks);
            if (messagesOn() && WitherDragonsConfig.getInstance().isSendSpray()) {
                send(Component.literal(d.colored() + " §fdragon was sprayed in §c" + ticks + " §ftick" + (ticks > 1 ? "s" : "") + "."));
            }
        }
    }

    /** NoammAddons {@code DragonCheck.trackArrows}: arrow-hit sounds while the priority dragon is inside its
     *  skip-kill window. */
    static void onSound(ClientboundSoundPacket packet) {
        if (!trackingActive() || priorityDragon == null || packet.getSound().value() != SoundEvents.ARROW_HIT_PLAYER) {
            return;
        }
        WitherDragon d = priorityDragon;
        if (d.state == WitherDragon.State.ALIVE && ServerTickClock.now() - d.spawnedTick <= d.skipKillTicks) {
            d.arrowsHit++;
        }
    }

    // ------------------------------------------------------------------ chat

    private static void onChat(Component message) {
        if (!trackingActive() || !P5State.inP5()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null || !WITHER_KING_REGEX.matcher(plain).matches()) {
            return;
        }
        WitherDragon target = lastDragonDeath;
        if (target == null) {
            for (WitherDragon d : WitherDragon.values()) {
                if (d.state != WitherDragon.State.DEAD) {
                    target = d;
                    break;
                }
            }
        }
        if (target == null) {
            return;
        }
        if (messagesOn() && WitherDragonsConfig.getInstance().isSendConfirmation()) {
            send(Component.literal(target.colored() + " §fdragon counts."));
        }
        if (target.state != WitherDragon.State.DEAD) {
            setDead(target, false);
        }
        lastDragonDeath = null;
    }

    // ------------------------------------------------------------------ output helpers

    private static void send(Component message) {
        ModChat.send("Wither Dragons", message);
    }

    /** Odin {@code alert(..., playSound = true)}: title 0/20/5 ticks + NOTE_BLOCK_PLING. */
    private static void alert(WitherDragon d, String subtitle) {
        Minecraft client = Minecraft.getInstance();
        client.gui.setTimes(0, 20, 5);
        client.gui.setTitle(Component.literal("§" + d.colorCode + d.displayName().toUpperCase(Locale.ROOT) + " DRAGON IS SPAWNING"));
        client.gui.setSubtitle(Component.literal(subtitle));
        if (WitherDragonsConfig.getInstance().isTitleSound()) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f));
        }
    }

    private static String joinColored(List<WitherDragon> dragons) {
        StringBuilder sb = new StringBuilder();
        for (WitherDragon d : dragons) {
            if (sb.length() > 0) {
                sb.append("§7, ");
            }
            sb.append(d.colored());
        }
        return sb.toString();
    }

    /** Odin {@code getDragonTimer}. */
    static String formatTimer(int ticks) {
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        String color = ticks <= 20 ? "§c" : ticks <= 60 ? "§e" : "§a";
        return color + switch (cfg.getTimerStyle()) {
            case MILLISECONDS -> (ticks * 50) + (cfg.isTimerSymbol() ? "ms" : "");
            case SECONDS -> String.format(Locale.US, "%.1f", ticks / 20f) + (cfg.isTimerSymbol() ? "s" : "");
            case TICKS -> ticks + (cfg.isTimerSymbol() ? "t" : "");
        };
    }

    /** Odin {@code colorHealth}/{@code formatHealth}. */
    static String formatHealth(float health) {
        String color = health >= 750_000_000f ? "§a" : health >= 500_000_000f ? "§e" : health >= 250_000_000f ? "§6" : "§c";
        String value;
        if (health >= 1_000_000_000f) {
            value = String.format(Locale.US, "%.1fb", health / 1_000_000_000f);
        } else if (health >= 1_000_000f) {
            value = String.format(Locale.US, "%.1fm", health / 1_000_000f);
        } else if (health >= 1_000f) {
            value = String.format(Locale.US, "%.1fk", health / 1_000f);
        } else {
            value = String.valueOf((int) health);
        }
        return color + value;
    }

    public static WitherDragon priorityDragon() {
        return priorityDragon;
    }

    // ------------------------------------------------------------------ world render (Odin RenderEvent.Extract)

    private static void onWorldRender(LevelRenderContext context) {
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null || !P5State.inP5()) {
            return;
        }
        if (cfg.isDragonHealth()) {
            float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            for (EnderDragon e : DRAGON_ENTITIES.values()) {
                if (e.isRemoved()) {
                    continue;
                }
                float hp = e.getHealth();
                if (hp > 0f) {
                    Vec3 pos = e.getPosition(partial);
                    renderWorldText(context, formatHealth(hp), pos.x, pos.y, pos.z, 5f);
                }
            }
        }
        for (WitherDragon d : WitherDragon.values()) {
            if (cfg.isDragonTimer() && d.timeToSpawn > 0) {
                renderWorldText(context, "§" + d.colorCode + d.displayName().charAt(0) + ": " + formatTimer(d.timeToSpawn),
                        d.spawnPos.getX() + 0.5, d.spawnPos.getY() + 0.5, d.spawnPos.getZ() + 0.5, 5f);
            }
            if (cfg.isDragonBoxes() && d.state != WitherDragon.State.DEAD) {
                float[] c = WorldRenderUtils.argbToFloats(d.argb);
                WorldRenderUtils.renderOutlineBox(context, d.box, c[0], c[1], c[2], 1f, 2f);
            }
        }
        WitherDragon p = priorityDragon;
        if (cfg.isDragonTracer() && p != null && p.state == WitherDragon.State.SPAWNING) {
            float[] c = WorldRenderUtils.argbToFloats(p.argb);
            Vec3 target = new Vec3(p.spawnPos.getX() + 0.5, p.spawnPos.getY() + 0.5, p.spawnPos.getZ() + 0.5);
            WorldRenderUtils.renderLineStrip(context, List.of(client.player.getEyePosition(), target), c[0], c[1], c[2], 1f, 2f);
        }
    }

    /** Billboarded see-through world text - same transform as DungeonAlertsFeature.renderWorldText /
     *  BloodCampFeature (proven on 26.1.2). */
    static void renderWorldText(LevelRenderContext context, String text, double x, double y, double z, float scale) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        var camera = client.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
        poseStack.mulPose(camera.rotation());
        float s = 0.025f * scale;
        poseStack.scale(s, -s, s);
        Component component = Component.literal(text);
        float width = font.width(component);
        int background = (int) (0.25f * 255f) << 24;
        font.drawInBatch(component, -width / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ HUD

    /** Dragon spawn timers list (Odin "Dragon Timer HUD", extended to every spawning dragon; the priority one
     *  is marked with "»"). */
    public static final class DragonTimersHudElement implements HudElement {
        @Override
        public String id() {
            return "wither_dragon_timers";
        }

        @Override
        public String displayName() {
            return "Wither Dragon Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // 200 is Ability Timers' default (AbilityTimersFeature.TimersHudElement), 240 is King Relic Timer's,
            // 260 Dungeon Info / Simon Says, 320 Split Timers - 280 is the free slot in the x=10 column.
            return 280;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, lines().size());
        }

        private static List<String> lines() {
            List<String> out = new ArrayList<>();
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof HudEditorScreen) {
                out.add("§8» §5Purple: §a4500ms");
                out.add("§cRed: §a4500ms");
                return out;
            }
            if (!WitherDragonsConfig.getInstance().isEnabled() || !WitherDragonsConfig.getInstance().isDragonTimer() || !P5State.inP5()) {
                return out;
            }
            for (WitherDragon d : WitherDragon.values()) {
                if (d.timeToSpawn > 0) {
                    out.add((d == priorityDragon ? "§8» " : "") + d.colored() + ": " + formatTimer(d.timeToSpawn));
                }
            }
            return out;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            if (!WitherDragonsConfig.getInstance().isEnabled() || (client.screen != null && !(client.screen instanceof HudEditorScreen))) {
                return;
            }
            int lineY = y;
            for (String line : lines()) {
                graphics.text(client.font, line, x, lineY, 0xFFFFFFFF, true);
                lineY += 12;
            }
        }
    }
}
