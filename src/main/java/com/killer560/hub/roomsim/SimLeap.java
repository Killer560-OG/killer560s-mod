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
import net.minecraft.world.item.component.ResolvableProfile;
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
        List<ServerPlayer> targets = targets(sp);
        SimpleContainer box = new SimpleContainer(36);
        ItemStack filler = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("black_stained_glass_pane")));
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 36; i++) box.setItem(i, filler.copy());
        int[] slots = SLOTS[Math.min(targets.size(), SLOTS.length - 1)];
        UUID[] bySlot = new UUID[36];
        for (int i = 0; i < slots.length; i++) {
            ServerPlayer t = targets.get(i);
            box.setItem(slots[i], head(t));
            bySlot[slots[i]] = t.getUUID();
        }
        sp.openMenu(new SimpleMenuProvider((cid, inv, p) -> new Menu(cid, inv, box, bySlot, infinite), Component.literal(TITLE)));
        menus++;
        return true;
    }

    /** Every other living teammate: the real players in join order, then the Sim Teammates. */
    private static List<ServerPlayer> targets(ServerPlayer self) {
        List<ServerPlayer> out = new ArrayList<>(4);
        for (ServerPlayer p : self.level().getServer().getPlayerList().getPlayers()) {
            if (p != self && p.isAlive() && !p.isSpectator() && p.level() == self.level()) out.add(p);
        }
        for (ServerPlayer p : SimTeammates.placed()) {
            if (p.isAlive() && p.level() == self.level()) out.add(p);
        }
        return out;
    }

    private static ItemStack head(ServerPlayer t) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(t.getGameProfile()));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(t.getGameProfile().name()).withStyle(ChatFormatting.GREEN)
                .withStyle(st -> st.withItalic(false)));
        return s;
    }

    /** Server thread: the leap itself, from a click on a head. */
    private static void leap(ServerPlayer sp, UUID target, boolean infinite) {
        if (!(sp.level() instanceof ServerLevel level)) return;
        Entity t = level.getEntity(target);
        sp.closeContainer();
        if (!(t instanceof ServerPlayer to) || !to.isAlive() || to.isSpectator()) {
            LOGGER.info("[SimLeap] {} picked a teammate who is gone", sp.getGameProfile().name());
            return;
        }
        if (!infinite && !takeOne(sp)) return;
        MinecraftServer server = level.getServer();
        int now = server.getTickCount();
        Integer last = LAST_TELEPORT.get(sp.getUUID());
        boolean relative = last != null && now - last < RELATIVE_LOOK_TICKS;
        Set<Relative> rel = relative ? Set.of(Relative.Y_ROT, Relative.X_ROT) : Set.of();
        SimArrival.teleport(sp, level, to.getX(), to.getY(), to.getZ(), rel,
                relative ? 0f : to.getYRot(), relative ? 0f : Math.max(-90f, Math.min(90f, to.getXRot())));
        READY_AT.put(sp.getUUID(), now + (infinite ? INFINILEAP_COOLDOWN_TICKS : SPIRIT_LEAP_COOLDOWN_TICKS));
        IMMUNE_UNTIL.put(sp.getUUID(), now + IMMUNITY_TICKS);
        com.killer560.hub.roomsim.puzzles.SimIceFillPuzzle.onTeleport(sp);
        SimSecretItems.pickupOnLanding(sp);
        String name = to.getGameProfile().name();
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

    private static final class Menu extends ChestMenu {
        private final UUID[] bySlot;
        private final boolean infinite;
        private boolean done;

        Menu(int id, Inventory inv, SimpleContainer box, UUID[] bySlot, boolean infinite) {
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
