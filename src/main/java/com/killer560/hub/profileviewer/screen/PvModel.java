package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.profileviewer.data.SbProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The /pv player model wearing the profile's armor, as the originals draw it (NEU: a fake player at scale 36 that faces
 *  the mouse; SkyBlockPV: a mannequin with the armor, shown bare while right click is held over it). The entity is made
 *  once per page build and dressed once when the inventory decode finishes; nothing is allocated per frame. */
final class PvModel extends AbstractWidget {
    private static final int MODEL_ID = -110_200;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
    private final Model entity;
    private final CompletableFuture<SbProfile.Inventories> inventories;
    private final int scale;
    private final boolean bareOnRightClick;
    private List<ItemStack> armor;
    private boolean bare;

    PvModel(int x, int y, int w, int h, int scale, Supplier<PlayerSkin> skin, CompletableFuture<SbProfile.Inventories> inventories,
            boolean bareOnRightClick) {
        super(x, y, w, h, Component.empty());
        this.entity = new Model(skin, !bareOnRightClick);
        this.inventories = inventories;
        this.scale = scale;
        this.bareOnRightClick = bareOnRightClick;
        this.active = false;
    }

    /** Null when there is no world to make the entity in (the caller then keeps the plain skin widget). */
    static PvModel create(int x, int y, int w, int h, int scale, Supplier<PlayerSkin> skin,
                          CompletableFuture<SbProfile.Inventories> inventories, boolean bareOnRightClick) {
        return Minecraft.getInstance().level == null ? null : new PvModel(x, y, w, h, scale, skin, inventories, bareOnRightClick);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        if (armor == null && inventories != null && inventories.isDone() && !inventories.isCompletedExceptionally()) {
            SbProfile.Inventories inv = inventories.getNow(null);
            armor = inv == null || inv.armor().size() != 4 ? List.of() : inv.armor();
            dress(false);
        }
        boolean wantBare = bareOnRightClick && isHovered() && Minecraft.getInstance().mouseHandler.isRightPressed();
        if (wantBare != bare) dress(wantBare);
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, getX(), getY(), getX() + width, getY() + height, scale, 0.0625f,
                mx, my, entity);
    }

    private void dress(boolean bare) {
        this.bare = bare;
        for (int i = 0; i < SLOTS.length; i++) {
            entity.setItemSlot(SLOTS[i], bare || armor == null || armor.size() != 4 ? ItemStack.EMPTY : armor.get(i));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
    }

    /** A client mannequin with the viewed player's skin, no "NPC" line, no name tag; the cape only in NEU's look (SkyBlockPV hides it). */
    private static final class Model extends ClientMannequin {
        private final Supplier<PlayerSkin> skin;
        private final boolean cape;

        Model(Supplier<PlayerSkin> skin, boolean cape) {
            super(Minecraft.getInstance().level, Minecraft.getInstance().playerSkinRenderCache());
            this.skin = skin;
            this.cape = cape;
            setId(MODEL_ID); // the renderer seeds item models from the id; never looked up in the world
        }

        @Override
        public PlayerSkin getSkin() { return skin.get(); }

        @Override
        public Component belowNameDisplay() { return null; }

        @Override
        public boolean shouldShowName() { return false; }

        @Override
        public boolean isModelPartShown(PlayerModelPart part) { return (cape || part != PlayerModelPart.CAPE) && super.isModelPartShown(part); }
    }
}
