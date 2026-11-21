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

    /** The set whose wardrobe slot is the one on the body, null before the sim put one on this game. Server thread. */
    private static SimArmor onBody;

    /**
     * Piece {@code i} as this set's wardrobe slot holds it (wardrobekeep-1120, killer560 2026-11-20: "in hypixel the two would
     * be their own sets. if i trade out a helmet from one and then close and change it over then it keeps that new set"):
     * what was worn in that slot when the set was last left ({@link SimLoadout#keptId}), else the set's own piece.
     */
    ItemStack held(int i) {
        String id = SimLoadout.keptId(this, i);
        if (id == null || id.equals(pieceId(i))) return piece(i);
        if (id.isEmpty()) return ItemStack.EMPTY;
        if (id.indexOf(':') >= 0) {
            return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)));
        }
        return SimItems.build(id);
    }

    /** What the menus show in {@code set}'s slot: the body's pieces for the slot that is on, else what the slot holds. */
    static ItemStack shown(ServerPlayer sp, SimArmor set, int i) {
        return set == onBody ? sp.getItemBySlot(SLOTS[i]).copy() : set.held(i);
    }

    /** How a worn piece is kept: its Skyblock id, a vanilla item's registry id, "" for an empty slot. */
    private static String keepId(ItemStack worn) {
        if (worn.isEmpty()) return "";
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(worn);
        return id != null ? id : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(worn.getItem()).toString();
    }

    private static boolean ours(ItemStack worn) {
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(worn);
        return id != null && (id.startsWith("INFERNAL_TERROR_") || id.startsWith("THERMODYNAMIC_"));
    }

    /**
     * Server thread: wears {@code set}'s slot as Hypixel's wardrobe does. The slot being left keeps exactly what is worn,
     * a piece swapped in by hand included (the piece it replaced is already in the inventory); NONE takes the set off.
     * Wearing the slot that is already on changes nothing, so hand-swapped pieces stay. Armor no wardrobe slot gave goes
     * to the inventory, never lost (with NONE and no slot on, it simply stays worn).
     */
    static void wear(ServerPlayer sp, SimArmor set) {
        ItemStack[] off = new ItemStack[4];
        boolean bare = true;
        for (int i = 0; i < 4; i++) {
            off[i] = sp.getItemBySlot(SLOTS[i]).copy();
            bare &= off[i].isEmpty();
        }
        if (set == onBody && !bare) return;
        if (onBody != null && onBody != NONE && !bare) {
            String[] ids = new String[4];
            for (int i = 0; i < 4; i++) ids[i] = keepId(off[i]);
            SimLoadout.keep(onBody, ids);
        }
        boolean slotOn = onBody != null && onBody != NONE;
        for (int i = 0; i < 4; i++) {
            if (set == NONE) {
                if (slotOn || ours(off[i])) sp.setItemSlot(SLOTS[i], ItemStack.EMPTY);
                continue;
            }
            // No slot on: armor it did not give goes to the inventory (the slot's own kept piece after a restart is not
            // doubled), and stays worn when the inventory is full.
            if (!slotOn && !off[i].isEmpty() && !ours(off[i]) && !keepId(off[i]).equals(keepId(set.held(i)))
                    && !sp.getInventory().add(off[i])) continue;
            sp.setItemSlot(SLOTS[i], set.held(i));
        }
        onBody = set;
        sp.inventoryMenu.sendAllDataToRemote(); // an open chest menu does not sync the armor slots (Loadouts and Wardrobe stay open)
    }
}
