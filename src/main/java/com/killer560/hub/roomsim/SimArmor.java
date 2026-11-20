package com.killer560.hub.roomsim;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * The armor set the sim loadout wears (Sim Settings "Sim Armor", saved in the loadout file). Four pieces, always.
 *
 * <p>Terror (Infernal tier): the Hydra Strike set bonus sits at 10 stacks all the time, no build-up and no decay:
 * +15% damage and +3% arrow speed per stack, and every bow shot adds two arrows ({@link SimTerminator}).
 * Thermodynamic: Newton's Demise raises the Attack Speed cap by 50, and the player is assumed to sit at that cap
 * ({@link SimAttackSpeed#attackSpeed()}).
 */
public enum SimArmor {
    NONE("None"), TERROR("Terror"), THERMODYNAMIC("Thermodynamic");

    /** Hydra Strike at full stacks (Infernal: +15% damage and +3% arrow speed per stack). */
    public static final int HYDRA_STACKS = 10;
    public static final double HYDRA_DAMAGE_PER_STACK = 0.15;
    public static final double HYDRA_ARROW_SPEED_PER_STACK = 0.03;
    /** The two extra arrows at 10 stacks: 60% Arrow Damage, plus 15% with four pieces worn. */
    public static final int HYDRA_EXTRA_ARROWS = 2;
    public static final double HYDRA_EXTRA_ARROW_DAMAGE = 0.75;
    /** Newton's Demise: +50 Attack Speed cap. */
    public static final int NEWTONS_DEMISE_CAP_BONUS = 50;

    public final String label;

    SimArmor(String label) {
        this.label = label;
    }

    /** The selected set. */
    public static SimArmor current() {
        return SimLoadout.armor();
    }

    /** Damage multiplier of a shot (1 + stacks x 15%) and arrow speed multiplier, 1 without Terror. */
    public static double damageMultiplier() {
        return bonus(TERROR) ? 1 + HYDRA_STACKS * HYDRA_DAMAGE_PER_STACK : 1;
    }

    public static double arrowSpeedMultiplier() {
        return bonus(TERROR) ? 1 + HYDRA_STACKS * HYDRA_ARROW_SPEED_PER_STACK : 1;
    }

    /** Attack Speed this set adds to the effective value (Newton's Demise raises the cap by 50, and the player sits at it). */
    int attackSpeedBonus() {
        return this == THERMODYNAMIC && headIntact(this) ? NEWTONS_DEMISE_CAP_BONUS : 0;
    }

    public static int extraArrows() {
        return bonus(TERROR) ? HYDRA_EXTRA_ARROWS : 0;
    }

    /** The 4-piece bonus needs the set's own helmet: a mask swapped onto the head (the stats menu) breaks it, as on Hypixel. */
    private static boolean bonus(SimArmor set) {
        return current() == set && headIntact(set);
    }

    private static boolean headIntact(SimArmor set) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null || set.pieceId(0).equals(com.killer560.hub.cheatutils.CheatUtils.skyblockId(player.getItemBySlot(EquipmentSlot.HEAD)));
    }

    private static final EquipmentSlot[] SLOTS =
            {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Item[] BASES =
            {Items.PLAYER_HEAD, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
    /** Hypixel's item data (items resource): dye per piece (helmets are heads) and the helmet's skin url. */
    private static final int[] TERROR_DYE = {-1, 0x3E05AF, 0x5D23D1, 0x7C44EC};
    private static final String TERROR_SKIN = "http://textures.minecraft.net/texture/4eadf2954f1629d793d7f467181c449e18d89aa4941bec3ea5211e5909bb567";
    private static final String THERMO_SKIN = "http://textures.minecraft.net/texture/cad67cf33188a54b6f55d2ef5248cd3b72176994e1d203731e86b365a8581726";
    private static final int THERMO_DYE = 0xFF700A;
    private static final String[] PIECES = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};

    /** Skyblock id of a piece (index 0 helmet .. 3 boots), or null for NONE. */
    String pieceId(int i) {
        return switch (this) {
            case TERROR -> "INFERNAL_TERROR_" + PIECES[i];
            case THERMODYNAMIC -> "THERMODYNAMIC_" + PIECES[i];
            default -> null;
        };
    }

    ItemStack piece(int i) {
        String id = pieceId(i);
        ItemStack stack = new ItemStack(BASES[i]);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (i == 0) {
            String url = this == TERROR ? TERROR_SKIN : THERMO_SKIN;
            stack.set(DataComponents.PROFILE, com.killer560.hub.armourdye.ArmourDye.headProfile(java.util.Base64.getEncoder()
                    .encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } else {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(this == TERROR ? TERROR_DYE[i] : THERMO_DYE));
        }
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(id));
        SimItemLore.apply(stack, id);
        return stack;
    }

    /** Server thread: puts the selected set on, or takes a sim set off when NONE. Armor the sim did not give stays. */
    static void wear(ServerPlayer sp, SimArmor set) {
        for (int i = 0; i < 4; i++) {
            ItemStack worn = sp.getItemBySlot(SLOTS[i]);
            String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(worn);
            boolean ours = id != null && (id.startsWith("INFERNAL_TERROR_") || id.startsWith("THERMODYNAMIC_"));
            if (set != NONE) sp.setItemSlot(SLOTS[i], set.piece(i));
            else if (ours) sp.setItemSlot(SLOTS[i], ItemStack.EMPTY);
        }
        sp.inventoryMenu.sendAllDataToRemote(); // an open chest menu does not sync the armor slots (Loadouts and Wardrobe stay open)
    }
}
