package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Sim Wardrobe and Stats (simwardrobe-1115, killer560 2026-11-15: "add in custom wardrobe and stats menu and stuff to swap
 * things between"). {@code /wardrobe} opens a chest menu with one slot per {@link SimArmor} set: a click wears it (through
 * {@link SimLoadout#setArmor}, so it is saved and the next bow shot uses it), the worn set shines. {@code /equipment} (and the
 * wardrobe's Stats button) opens the stats of what is worn: every number is read off the worn pieces' own lore, plus the
 * live Attack Speed and arrows per shot. Both commands exist only while the sim is running.
 */
public final class SimWardrobe {

    private static final String[] ROOTS = {"wardrobe", "wd", "equipment", "stats", "eq"};
    static final int[] SET_SLOTS = {20, 22, 24};
    /** The stats menu's four armor slots (helmet to boots), shown in the chest half. */
    static final int[] ARMOR_SLOTS = {20, 21, 22, 23};
    static final net.minecraft.world.entity.EquipmentSlot[] WORN = {net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
    private static volatile int opened;

    private SimWardrobe() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            for (String name : ROOTS) {
                dispatcher.register(ClientCommands.literal(name)
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            open(Minecraft.getInstance(), !name.equals("wardrobe") && !name.equals("wd"));
                            return 1;
                        }));
            }
        });
    }

    /** Opens the wardrobe (or the stats menu) for the sim player; false outside a sim. */
    public static boolean open(Minecraft client, boolean stats) {
        MinecraftServer server = client.getSingleplayerServer();
        if (client.player == null || server == null || !SimState.canAct(client)) return false;
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) show(sp, stats);
        });
        return true;
    }

    private static void show(ServerPlayer sp, boolean stats) {
        SimpleContainer box = new SimpleContainer(54);
        fill(box, stats, SimArmor.current(), sp);
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, box, stats),
                Component.literal(stats ? "Your Equipment and Stats" : "Wardrobe")));
        opened++;
    }

    /** Menu items for the current state: the sets and a Stats button, or the stats sheet. */
    private static void fill(SimpleContainer box, boolean stats, SimArmor worn, ServerPlayer sp) {
        box.clearContent();
        ItemStack filler = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("black_stained_glass_pane")));
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(""));
        for (int i = 0; i < 54; i++) box.setItem(i, filler.copy());
        if (stats) {
            ItemStack empty = labeled(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.Identifier.withDefaultNamespace("gray_stained_glass_pane")), "Empty", ChatFormatting.GRAY, null);
            for (int i = 0; i < 4; i++) {
                ItemStack on = sp.getItemBySlot(WORN[i]);
                box.setItem(ARMOR_SLOTS[i], on.isEmpty() ? empty.copy() : on.copy());
            }
            box.setItem(49, labeled(Items.BARRIER, "Close", ChatFormatting.RED, null));
            return;
        }
        SimArmor[] sets = SimArmor.values();
        for (int i = 0; i < sets.length; i++) {
            SimArmor set = sets[i];
            ItemStack s = set == SimArmor.NONE
                    ? labeled(Items.BARRIER, "No Armor", ChatFormatting.RED, null)
                    : set.piece(1);
            s = s.copy();
            if (set != SimArmor.NONE) s.set(DataComponents.CUSTOM_NAME, Component.literal(set.label + " Armor")
                    .withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal(set == worn ? "Equipped" : "Click to equip")
                    .withStyle(set == worn ? ChatFormatting.GREEN : ChatFormatting.YELLOW).withStyle(st -> st.withItalic(false)));
            s.set(DataComponents.LORE, new ItemLore(lore));
            if (set == worn) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            box.setItem(SET_SLOTS[i], s);
        }
        box.setItem(53, labeled(Items.BARRIER, "Close", ChatFormatting.RED, null));
    }

    private static ItemStack labeled(net.minecraft.world.item.Item item, String name, ChatFormatting color, String lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color).withStyle(st -> st.withItalic(false)));
        if (lore != null) s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                .withStyle(st -> st.withItalic(false)))));
        return s;
    }

    /** Wardrobe and stats menus opened since the game started, for tests. */
    public static int openedForTest() { return opened; }


    /** The armor slot index (0 helmet .. 3 boots) an item goes in, or -1: a mask or helmet id, else the item's own equippable slot. */
    private static int armorIndex(ItemStack it) {
        var eq = it.get(DataComponents.EQUIPPABLE);
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(it);
        if (id != null && (id.contains("MASK") || id.endsWith("_HELMET"))) return 0;
        for (int i = 0; i < 4; i++) if (eq != null && eq.slot() == WORN[i]) return i;
        return -1;
    }

    private static final class Menu extends ChestMenu {
        private final SimpleContainer box;
        private final boolean stats;
        private SimArmor worn = SimArmor.current();

        Menu(int id, Inventory inv, SimpleContainer box, boolean stats) {
            super(MenuType.GENERIC_9x6, id, inv, box, 6);
            this.box = box;
            this.stats = stats;
        }

        /** A worn piece clicked in the chest half goes to the first free inventory slot; a piece clicked in the inventory swaps with the one worn. */
        private void swapArmor(ServerPlayer sp, int slot) {
            int worn = -1;
            for (int i = 0; i < 4; i++) if (slot == ARMOR_SLOTS[i]) worn = i;
            if (worn >= 0) {
                ItemStack on = sp.getItemBySlot(WORN[worn]);
                if (on.isEmpty()) return;
                for (int s = 54; s < slots.size(); s++) {
                    if (getSlot(s).getItem().isEmpty()) {
                        getSlot(s).set(on.copy());
                        sp.setItemSlot(WORN[worn], ItemStack.EMPTY);
                        break;
                    }
                }
            } else {
                ItemStack clicked = getSlot(slot).getItem();
                int i = clicked.isEmpty() ? -1 : armorIndex(clicked);
                if (i < 0) return;
                ItemStack old = sp.getItemBySlot(WORN[i]).copy();
                sp.setItemSlot(WORN[i], clicked.copy());
                getSlot(slot).set(old);
            }
            sp.inventoryMenu.sendAllDataToRemote(); // the armor slots belong to the inventory menu, which the open menu does not sync
            for (SimArmor a : SimArmor.values()) {
                boolean all = a != SimArmor.NONE, none = true;
                for (int i = 0; i < 4; i++) {
                    String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(sp.getItemBySlot(WORN[i]));
                    all &= id != null && id.equals(a.pieceId(i));
                    none &= id == null || !(id.startsWith("INFERNAL_TERROR_") || id.startsWith("THERMODYNAMIC_"));
                }
                if (all || a == SimArmor.NONE && none) { this.worn = a; break; }
            }
            SimArmor now = this.worn;
            if (now != SimArmor.current()) Minecraft.getInstance().execute(() -> SimLoadout.setArmor(now));
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player instanceof ServerPlayer sp && slot >= 0 && (slot < 54 || stats)) {
                if (stats && slot != 49) {
                    swapArmor(sp, slot);
                } else if (stats ? slot == 49 : slot == 53) {
                    sp.closeContainer();
                    return;
                } else {
                    for (int i = 0; i < SET_SLOTS.length; i++) {
                        if (slot == SET_SLOTS[i]) {
                            SimArmor set = SimArmor.values()[i];
                            worn = set;
                            Minecraft.getInstance().execute(() -> SimLoadout.setArmor(set));
                        }
                    }
                }
                fill(box, stats, worn, sp);
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
