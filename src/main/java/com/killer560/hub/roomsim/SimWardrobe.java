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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern STAT = Pattern.compile("^([A-Za-z ]+): [+]?(-?\\d+)%?$");
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
        fill(box, stats, SimArmor.current());
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, box, stats),
                Component.literal(stats ? "Your Equipment and Stats" : "Wardrobe")));
        opened++;
    }

    /** Menu items for the current state: the sets and a Stats button, or the stats sheet. */
    private static void fill(SimpleContainer box, boolean stats, SimArmor worn) {
        box.clearContent();
        ItemStack filler = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("black_stained_glass_pane")));
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(""));
        for (int i = 0; i < 54; i++) box.setItem(i, filler.copy());
        if (stats) {
            int slot = 10;
            for (Map.Entry<String, String> e : statLines(worn).entrySet()) {
                ItemStack s = new ItemStack(Items.PAPER);
                s.set(DataComponents.CUSTOM_NAME, Component.literal(e.getKey()).withStyle(ChatFormatting.GREEN)
                        .withStyle(st -> st.withItalic(false)));
                s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(e.getValue()).withStyle(ChatFormatting.WHITE)
                        .withStyle(st -> st.withItalic(false)))));
                box.setItem(slot, s);
                slot += slot % 9 == 7 ? 3 : 1;
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

    /**
     * What is worn, as label -> value text: each stat the worn pieces' lore lists, summed over the four pieces, then the
     * live Attack Speed, arrows per shot and the set bonus. Empty-handed it still lists Attack Speed and arrows.
     */
    public static Map<String, String> statLines() {
        return statLines(SimArmor.current());
    }

    private static Map<String, String> statLines(SimArmor set) {
        Map<String, Integer> sum = new LinkedHashMap<>();
        if (set != SimArmor.NONE) {
            for (int i = 0; i < 4; i++) {
                ItemLore lore = set.piece(i).get(DataComponents.LORE);
                if (lore == null) continue;
                for (Component c : lore.lines()) {
                    String plain = c.getString().replaceAll("§.", "").strip();
                    Matcher m = STAT.matcher(plain);
                    if (m.matches()) sum.merge(m.group(1), Integer.parseInt(m.group(2)), Integer::sum);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sum.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        out.put("Attack Speed (effective)", String.valueOf(SimAttackSpeed.ATTACK_SPEED + set.attackSpeedBonus()));
        out.put("Bow Arrows Per Shot", String.valueOf(3 + (set == SimArmor.TERROR ? SimArmor.HYDRA_EXTRA_ARROWS : 0)));
        out.put("Arrow Damage Multiplier", String.valueOf(set == SimArmor.TERROR ? 1 + SimArmor.HYDRA_STACKS * SimArmor.HYDRA_DAMAGE_PER_STACK : 1.0));
        out.put("Set Bonus", switch (set) {
            case TERROR -> "Hydra Strike (10 stacks)";
            case THERMODYNAMIC -> "Newton's Demise";
            default -> "None";
        });
        return out;
    }

    /** Wardrobe and stats menus opened since the game started, for tests. */
    public static int openedForTest() { return opened; }

    /** A helmet-slot item (a mask, any helmet) clicked in the inventory half of the stats menu swaps with the worn one, as on Hypixel. */
    private static boolean isHelmet(ItemStack it) {
        var eq = it.get(DataComponents.EQUIPPABLE);
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(it);
        return eq != null && eq.slot() == net.minecraft.world.entity.EquipmentSlot.HEAD
                || id != null && (id.contains("MASK") || id.endsWith("_HELMET"));
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

        private void swapHelmet(ServerPlayer sp, int slot) {
            ItemStack clicked = getSlot(slot).getItem();
            if (clicked.isEmpty() || !isHelmet(clicked)) return;
            ItemStack old = sp.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).copy();
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, clicked.copy());
            getSlot(slot).set(old);
            sp.inventoryMenu.sendAllDataToRemote(); // the helmet slot belongs to the inventory menu, which the open menu does not sync
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player instanceof ServerPlayer sp && slot >= 0 && (slot < 54 || stats)) {
                if (stats && slot >= 54) {
                    swapHelmet(sp, slot);
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
                fill(box, stats, worn);
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
