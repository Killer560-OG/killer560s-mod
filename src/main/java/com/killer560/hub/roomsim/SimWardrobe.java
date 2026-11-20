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

import com.killer560.hub.compat.McItems;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Sim Wardrobe, Stats and Loadouts (simwardrobe-1115, simmenus-1118; killer560 2026-11-15: "add in custom wardrobe and stats
 * menu and stuff to swap things between", 2026-11-18: Hypixel's slots). {@code /wardrobe}: "Wardrobe (1/2)", Terror in
 * slot 1 and Thermodynamic in slot 2, each a column of four pieces with its equip dye under it (36 + column); a click on
 * the dye wears the set (or takes it off) through {@link SimLoadout#setArmor}. {@code /stats} and {@code /equipment}:
 * "Stats & Equipment", the held item at 2 and the armor column at 11/20/29/38. {@code /loadouts}: "(1/1) Loadouts", the
 * worn armor column on the left and Hypixel's 12 spots on the right (14-16, 23-25, 32-34, 41-43), each set in the spot
 * {@link SimLoadout#spot} says, so Loadout Keybinds' Open, Swap, Close and the Inventory Sorter's loadout step work on it
 * unchanged. All exist only while the sim is running.
 */
public final class SimWardrobe {

    private static final String[] ROOTS = {"wardrobe", "wd", "equipment", "stats", "eq", "loadouts"};
    /** The wardrobe's sets, one column each: Terror is slot 1, Thermodynamic slot 2. */
    private static final SimArmor[] WARDROBE_SETS = {SimArmor.TERROR, SimArmor.THERMODYNAMIC};
    /** The stats menu's armor column (helmet to boots), and the loadouts menu's worn column beside it, as on Hypixel. */
    static final int[] ARMOR_SLOTS = {11, 20, 29, 38};
    private static final int[] LOADOUT_WORN_SLOTS = {10, 19, 28, 37};
    private static final int HELD_SLOT = 2;

    /** Class menu slots, Hypixel's class order (Healer, Mage, Berserk, Archer, Tank), and the item each shows. */
    private static final DungeonClass[] CLASS_ORDER = {DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.BERSERKER, DungeonClass.ARCHER, DungeonClass.TANK};
    private static final net.minecraft.world.item.Item[] CLASS_ITEMS = {Items.POTION, Items.BLAZE_ROD, Items.IRON_SWORD, Items.BOW, Items.IRON_CHESTPLATE};

    private enum Kind { WARDROBE, STATS, LOADOUTS, CLASSES }
    /** The class just clicked in the open class menu (the setting itself lands a moment later on the client thread). */
    private static DungeonClass pickedClass;
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
                            show(Minecraft.getInstance(), name.equals("wardrobe") || name.equals("wd") ? Kind.WARDROBE
                                    : name.equals("loadouts") ? Kind.LOADOUTS : Kind.STATS);
                            return 1;
                        }));
            }
            dispatcher.register(ClientCommands.literal("class")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .executes(ctx -> {
                        show(Minecraft.getInstance(), Kind.CLASSES);
                        return 1;
                    })
                    .then(ClientCommands.argument("class", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                    java.util.Arrays.stream(CLASS_ORDER).map(c -> SimClasses.name(c).toLowerCase(java.util.Locale.ROOT)), b))
                            .executes(ctx -> {
                                String arg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "class");
                                DungeonClass c = arg.equalsIgnoreCase("berserk") ? DungeonClass.BERSERKER : DungeonClass.byName(arg);
                                if (c == null) {
                                    ctx.getSource().sendError(Component.literal("Unknown class: " + arg + " (healer, mage, berserk, archer, tank)"));
                                    return 0;
                                }
                                SimClasses.pick(c);
                                return 1;
                            })));
        });
    }

    /** Opens the wardrobe (or the stats menu) for the sim player; false outside a sim. */
    public static boolean open(Minecraft client, boolean stats) {
        return show(client, stats ? Kind.STATS : Kind.WARDROBE);
    }

    /** Opens the sim's /loadouts menu; false outside a sim. */
    public static boolean openLoadouts(Minecraft client) {
        return show(client, Kind.LOADOUTS);
    }

    private static boolean show(Minecraft client, Kind kind) {
        MinecraftServer server = client.getSingleplayerServer();
        if (client.player == null || server == null || !SimState.canAct(client)) return false;
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) show(sp, kind);
        });
        return true;
    }

    private static void show(ServerPlayer sp, Kind kind) {
        pickedClass = null;
        SimpleContainer box = new SimpleContainer(54);
        fill(box, kind, SimArmor.current(), sp);
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, box, kind),
                Component.literal(switch (kind) {
                    case STATS -> "Stats & Equipment";
                    case LOADOUTS -> "(1/1) Loadouts";
                    case CLASSES -> "Dungeon Classes";
                    default -> "Wardrobe (1/2)";
                })));
        opened++;
    }

    /** Menu items for the current state: the wardrobe columns, the stats armor column or the loadout spots. */
    private static void fill(SimpleContainer box, Kind kind, SimArmor worn, ServerPlayer sp) {
        box.clearContent();
        ItemStack filler = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("black_stained_glass_pane")));
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(""));
        for (int i = 0; i < 54; i++) box.setItem(i, filler.copy());
        box.setItem(49, labeled(Items.BARRIER, "Close", ChatFormatting.RED, null));
        if (kind == Kind.WARDROBE) {
            for (int c = 0; c < 9; c++) {
                SimArmor set = c < WARDROBE_SETS.length ? WARDROBE_SETS[c] : SimArmor.NONE;
                for (int r = 0; r < 4; r++) box.setItem(r * 9 + c, set == SimArmor.NONE ? ItemStack.EMPTY : set.piece(r));
                boolean on = set != SimArmor.NONE && set == worn;
                box.setItem(36 + c, set == SimArmor.NONE
                        ? labeled(McItems.GRAY_DYE, "Slot " + (c + 1), ChatFormatting.GRAY, "Empty")
                        : labeled(on ? McItems.LIME_DYE : McItems.PINK_DYE, "Slot " + (c + 1),
                                on ? ChatFormatting.GREEN : ChatFormatting.RED, on ? "Equipped" : "Click to equip"));
            }
        } else if (kind == Kind.CLASSES) {
            for (int i = 0; i < CLASS_ORDER.length; i++) {
                boolean on = (pickedClass != null ? pickedClass : SimClasses.self().cls()) == CLASS_ORDER[i];
                ItemStack s = labeled(CLASS_ITEMS[i], SimClasses.name(CLASS_ORDER[i]), ChatFormatting.GREEN, on ? "Currently selected" : "Click to select!");
                if (on) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                box.setItem(11 + i, s);
            }
        } else if (kind == Kind.STATS) {
            ItemStack empty = labeled(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.Identifier.withDefaultNamespace("gray_stained_glass_pane")), "Empty", ChatFormatting.GRAY, null);
            for (int i = 0; i < 4; i++) {
                ItemStack on = sp.getItemBySlot(WORN[i]);
                box.setItem(ARMOR_SLOTS[i], on.isEmpty() ? empty.copy() : on.copy());
            }
            if (!sp.getMainHandItem().isEmpty()) box.setItem(HELD_SLOT, sp.getMainHandItem().copy());
        } else {
            for (int i = 0; i < 4; i++) {
                // the worn set, not the player slots: the wear itself lands a moment later on the server thread
                if (worn != SimArmor.NONE) box.setItem(LOADOUT_WORN_SLOTS[i], worn.piece(i));
            }
            for (int n = 1; n <= 12; n++) {
                SimArmor set = SimLoadout.setAt(n);
                ItemStack s = set == SimArmor.NONE ? labeled(McItems.GRAY_DYE, "Loadout " + n, ChatFormatting.GRAY, "Empty")
                        : set.piece(0).copy();
                if (set != SimArmor.NONE) {
                    s.set(DataComponents.CUSTOM_NAME, Component.literal("Loadout " + n).withStyle(ChatFormatting.GREEN)
                            .withStyle(st -> st.withItalic(false)));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.literal(set.label + " Armor").withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
                    // Hypixel marks the loadout on only by leaving "Left-click to equip!" out of its lore.
                    if (set != worn) lore.add(Component.literal("Left-click to equip!").withStyle(ChatFormatting.YELLOW).withStyle(st -> st.withItalic(false)));
                    s.set(DataComponents.LORE, new ItemLore(lore));
                    if (set == worn) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                box.setItem(LoadoutKeybindsFeature.slotOf(n), s);
            }
        }
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
        private final Kind kind;
        private SimArmor worn = SimArmor.current();

        Menu(int id, Inventory inv, SimpleContainer box, Kind kind) {
            super(MenuType.GENERIC_9x6, id, inv, box, 6);
            this.box = box;
            this.kind = kind;
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
            } else if (slot >= 54) {
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
            if (player instanceof ServerPlayer sp && slot >= 0 && (slot < 54 || kind == Kind.STATS)) {
                if (slot == 49) {
                    sp.closeContainer();
                    return;
                }
                if (kind == Kind.STATS) {
                    swapArmor(sp, slot);
                } else if (kind == Kind.CLASSES) {
                    int i = slot - 11;
                    if (i >= 0 && i < CLASS_ORDER.length) {
                        pickedClass = CLASS_ORDER[i];
                        Minecraft.getInstance().execute(() -> SimClasses.pick(CLASS_ORDER[i]));
                    }
                } else if (kind == Kind.WARDROBE) {
                    int c = slot - 36;
                    if (c >= 0 && c < WARDROBE_SETS.length) equip(WARDROBE_SETS[c] == worn ? SimArmor.NONE : WARDROBE_SETS[c]);
                } else {
                    int n = LoadoutKeybindsFeature.loadoutAt(slot);
                    SimArmor set = n == 0 ? SimArmor.NONE : SimLoadout.setAt(n);
                    if (set != SimArmor.NONE && set != worn) {
                        equip(set);
                        sp.sendSystemMessage(Component.literal("You equipped Loadout " + n + "!").withStyle(ChatFormatting.GREEN));
                    }
                }
                fill(box, kind, worn, sp);
            }
            setCarried(ItemStack.EMPTY);
            broadcastFullState();
        }

        private void equip(SimArmor set) {
            worn = set;
            Minecraft.getInstance().execute(() -> SimLoadout.setArmor(set));
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
