package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import com.killer560.hub.bosssim.BossLeapSpots;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spirit Leap and the InfiniLeap in the sim (siminfleap-1116, killer560 2026-11-16: "for sim can you add the infini leap?").
 * Answered on the sim's SERVER like every ability ({@link SimItems#useOnServer}), so a co-op guest's leap is the same code.
 *
 * <p>From Hypixel (wiki, api.hypixel.net, his 26.3 logs): a use opens the 4-row "Spirit Leap" menu with a head for every
 * other living teammate; a click teleports him to that player and Hypixel says "You have teleported to Name!"; the Spirit
 * Leap is used up per teleport (5 s cooldown), the InfiniLeap never (2 s cooldown); used on cooldown it opens no menu and
 * says "This ability is on cooldown for Ns."; one second of immunity after the teleport, ended by dealing damage. The look
 * the teleport carries is the target's, ABSOLUTE, unless another teleport came within about a second, when it is relative
 * 0/0 (fastleap/LeapRotation, from his log). Head slots, filler, colors and when the cooldown starts are estimates
 * (docs/SIM.md "Spirit Leap and InfiniLeap").
 */
public final class SimLeap {

    private static final Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-sim");
    static final String TITLE = "Spirit Leap";
    static final int SPIRIT_LEAP_COOLDOWN_TICKS = 100;
    static final int INFINILEAP_COOLDOWN_TICKS = 40;
    static final int IMMUNITY_TICKS = 20;
    /** A teleport this soon after the last one keeps the player's own look (relative 0/0), as Hypixel's does. */
    static final int RELATIVE_LOOK_TICKS = 20;
    /** Head slots by how many teammates (estimate: centered on the second row; his clicks were in 11-15). */
    private static final int[][] SLOTS = {{}, {13}, {12, 14}, {11, 13, 15}, {11, 12, 14, 15}};

    /** Per real player, server ticks: cooldown ends, immunity ends, last teleport. Cleared on reset (at most a party). */
    private static final Map<UUID, Integer> READY_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> IMMUNE_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_TELEPORT = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int leaps;
    private static volatile int menus;
    private static volatile int wrongLeaps;
    private static volatile double[] lastLanding;

    private SimLeap() {
    }

    /** Once at client init (SimAbilities.register): immunity after a leap, ended by dealing damage. */
    static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (IMMUNE_UNTIL.isEmpty()) return true;
            Entity by = source.getEntity();
            if (by != null) IMMUNE_UNTIL.remove(by.getUUID());
            Integer until = IMMUNE_UNTIL.get(entity.getUUID());
            return until == null || entity.level().getServer() == null
                    || entity.level().getServer().getTickCount() >= until;
        });
    }

    /** Server thread: a use packet with a leap item. Always consumed (an ender pearl must never be thrown). */
    static boolean use(ServerPlayer sp, String id) {
        boolean infinite = "INFINITE_SPIRIT_LEAP".equals(id);
        int now = sp.level().getServer().getTickCount();
        Integer ready = READY_AT.get(sp.getUUID());
        if (ready != null && now < ready) {
            int seconds = (ready - now + 19) / 20;
            sp.sendSystemMessage(Component.literal("This ability is on cooldown for " + seconds + "s.").withStyle(ChatFormatting.RED));
            return true;
        }
        List<Target> targets = targets(sp);
        SimpleContainer box = new SimpleContainer(36);
        ItemStack filler = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("black_stained_glass_pane")));
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 36; i++) box.setItem(i, filler.copy());
        int[] slots = SLOTS[Math.min(targets.size(), SLOTS.length - 1)];
        Target[] bySlot = new Target[36];
        for (int i = 0; i < slots.length; i++) {
            Target t = targets.get(i);
            box.setItem(slots[i], head(t));
            bySlot[slots[i]] = t;
        }
        sp.openMenu(new SimpleMenuProvider((cid, inv, p) -> new Menu(cid, inv, box, bySlot, infinite), Component.literal(TITLE)));
        menus++;
        return true;
    }

    /**
     * A head in the menu: a player on the server ({@code id} set; a Sim Teammate is one too) or, in a single-player boss
     * fight, a class with no Sim Teammate placed for it ({@code id} null), named as that teammate would be.
     */
    private record Target(UUID id, String name, DungeonClass cls, GameProfile profile, boolean real) {
    }

    /**
     * Every other living teammate: the real players in join order, then the Sim Teammates. In a single-player boss fight
     * (simleapspots-1117: "have the default player locations for names have the classes they are not playing") always the
     * four classes he does not play, Teammate1-4 in Sim Teammates' order, placed or not.
     */
    private static List<Target> targets(ServerPlayer self) {
        List<Target> out = new ArrayList<>(4);
        List<ServerPlayer> real = self.level().getServer().getPlayerList().getPlayers();
        for (ServerPlayer p : real) {
            if (p != self && p.isAlive() && !p.isSpectator() && p.level() == self.level()) out.add(of(p, true));
        }
        List<ServerPlayer> mates = SimTeammates.placed();
        if (real.size() == 1 && BossLeapSpots.active()) {
            for (int i = 0; i < SimTeammates.MAX; i++) {
                String name = "Teammate" + (i + 1);
                ServerPlayer placed = mates.stream().filter(m -> m.getGameProfile().name().equals(name)).findFirst().orElse(null);
                out.add(placed != null ? of(placed, false) : new Target(null, name, SimClasses.teammate(i).cls(),
                        new GameProfile(UUID.nameUUIDFromBytes(("sim-teammate:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name), false));
            }
            return out;
        }
        for (ServerPlayer p : mates) {
            if (p.isAlive() && p.level() == self.level()) out.add(of(p, false));
        }
        return out;
    }

    private static Target of(ServerPlayer p, boolean real) {
        return new Target(p.getUUID(), p.getGameProfile().name(), SimClasses.of(p).cls(), p.getGameProfile(), real);
    }

    private static ItemStack head(Target t) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(t.profile()));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(t.name()).withStyle(ChatFormatting.GREEN)
                .withStyle(st -> st.withItalic(false)));
        List<Component> lore = List.of(Component.literal(SimClasses.name(t.cls())).withStyle(ChatFormatting.GRAY)
                .withStyle(st -> st.withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(lore, lore));
        return s;
    }

    /**
     * Whether a leap to {@code t} goes to its class's boss spot ({@link BossLeapSpots}): in a boss fight, always alone, and
     * in co-op with Co-op Leaps on Default Leaps; Player Leaps sends a leap to a real player to where that player stands.
     */
    private static boolean toSpot(Target t) {
        return BossLeapSpots.active() && (!t.real() || !SimMenuPrefs.cached().playerLeaps);
    }

    /** Server thread: the leap itself, from a click on a head. */
    private static void leap(ServerPlayer sp, Target target, boolean infinite) {
        if (!(sp.level() instanceof ServerLevel level)) return;
        sp.closeContainer();
        double x, y, z;
        float yaw, pitch;
        boolean keepLook = false;
        if (toSpot(target)) {
            BossLeapSpots.Spot spot = BossLeapSpots.spot(level, target.cls(), SimClasses.of(sp).cls());
            if (spot == null) {
                sp.sendSystemMessage(Component.literal("[Sim] You leaped wrong: " + target.name() + " (" + SimClasses.name(target.cls())
                        + ") has no spot in " + BossLeapSpots.stageName() + ". Try again!").withStyle(ChatFormatting.RED));
                wrongLeaps++;
                LOGGER.info("[SimLeap] {} leapt wrong: {} ({}) has no spot in {}", sp.getGameProfile().name(), target.name(),
                        target.cls(), BossLeapSpots.stageName());
                return;
            }
            x = spot.x();
            y = spot.y();
            z = spot.z();
            yaw = spot.yaw();
            pitch = spot.pitch();
            keepLook = spot.keepLook();
        } else {
            Entity t = target.id() == null ? null : level.getEntity(target.id());
            if (!(t instanceof ServerPlayer to) || !to.isAlive() || to.isSpectator()) {
                LOGGER.info("[SimLeap] {} picked a teammate who is gone", sp.getGameProfile().name());
                return;
            }
            x = to.getX();
            y = to.getY();
            z = to.getZ();
            yaw = to.getYRot();
            pitch = Math.max(-90f, Math.min(90f, to.getXRot()));
        }
        if (!infinite && !takeOne(sp)) return;
        MinecraftServer server = level.getServer();
        int now = server.getTickCount();
        Integer last = LAST_TELEPORT.get(sp.getUUID());
        boolean relative = keepLook || last != null && now - last < RELATIVE_LOOK_TICKS;
        Set<Relative> rel = relative ? Set.of(Relative.Y_ROT, Relative.X_ROT) : Set.of();
        SimArrival.teleport(sp, level, x, y, z, rel, relative ? 0f : yaw, relative ? 0f : pitch);
        lastLanding = new double[]{x, y, z, sp.getYRot(), sp.getXRot()};
        READY_AT.put(sp.getUUID(), now + (infinite ? INFINILEAP_COOLDOWN_TICKS : SPIRIT_LEAP_COOLDOWN_TICKS));
        IMMUNE_UNTIL.put(sp.getUUID(), now + IMMUNITY_TICKS);
        com.killer560.hub.roomsim.puzzles.SimIceFillPuzzle.onTeleport(sp);
        SimSecretItems.pickupOnLanding(sp);
        String name = target.name();
        sp.sendSystemMessage(Component.literal("You have teleported to ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("!").withStyle(ChatFormatting.LIGHT_PURPLE)));
        leaps++;
        LOGGER.info("[SimLeap] {} leapt to {} ({}, look {})", sp.getGameProfile().name(), name,
                infinite ? "InfiniLeap" : "Spirit Leap", relative ? "kept" : "target's");
    }

    /** One Spirit Leap out of his inventory, the held stack first. */
    private static boolean takeOne(ServerPlayer sp) {
        Inventory inv = sp.getInventory();
        ItemStack held = sp.getMainHandItem();
        if ("SPIRIT_LEAP".equals(CheatUtils.skyblockId(held))) {
            held.shrink(1);
            return true;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if ("SPIRIT_LEAP".equals(CheatUtils.skyblockId(s))) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Server thread, from every sim teleport of a player: Hypixel keeps the look of a teleport that follows within ~1 s. */
    static void noteTeleport(ServerPlayer sp) {
        MinecraftServer server = sp.level().getServer();
        if (server != null && !SimMiniboss.isPlaced(sp)) LAST_TELEPORT.put(sp.getUUID(), server.getTickCount());
    }

    /** A session ended or a new map: no cooldown, immunity or teleport time carries over. */
    static void reset() {
        READY_AT.clear();
        IMMUNE_UNTIL.clear();
        LAST_TELEPORT.clear();
    }

    /** Leaps and leap menus since the game started, for tests. */
    public static int leapsForTest() { return leaps; }

    public static int menusForTest() { return menus; }

    /** Wrong leaps (a class with no boss spot in the stage) since the game started, for tests. */
    public static int wrongLeapsForTest() { return wrongLeaps; }

    /** The last leap's landing as the server set it: x, y, z, yaw, pitch (null before one), for tests. */
    public static double[] lastLandingForTest() { return lastLanding; }

    private static final class Menu extends ChestMenu {
        private final Target[] bySlot;
        private final boolean infinite;
        private boolean done;

        Menu(int id, Inventory inv, SimpleContainer box, Target[] bySlot, boolean infinite) {
            super(MenuType.GENERIC_9x4, id, inv, box, 4);
            this.bySlot = bySlot;
            this.infinite = infinite;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (!done && player instanceof ServerPlayer sp && slot >= 0 && slot < bySlot.length && bySlot[slot] != null) {
                done = true;
                leap(sp, bySlot[slot], infinite);
                return;
            }
            setCarried(ItemStack.EMPTY);
            broadcastFullState();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
