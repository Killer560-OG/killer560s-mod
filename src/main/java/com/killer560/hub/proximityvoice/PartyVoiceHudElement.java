package com.killer560.hub.proximityvoice;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;

/**
 * "New HUD, toggleable, PARTY MEMBERS ONLY" - killer560, 2026-09-20, explicit that this must NOT show
 * everyone in the lobby, only real {@code /party} members ({@link PartyTracker#teammates()}), one row per
 * teammate (head + name, Discord-overlay style): fully lit while they're transmitting, dimmed while silent.
 * <p>
 * Head icons reuse the exact mechanism {@code LegacyItems.skull}/{@code skullProfile} already use elsewhere
 * in this mod for a player-head {@code ItemStack} - {@code ResolvableProfile.createResolved(GameProfile)} -
 * except the {@link com.mojang.authlib.GameProfile} here is the teammate's own live one straight off their
 * loaded {@link Player} entity (the same profile Minecraft is already using to render their in-world skin),
 * so this never does its own network skin lookup.
 * <p>
 * "Talking" comes from {@link ProximityVoiceFeature#isTalking(String)}, which only reads a timestamp map
 * the network receive thread maintains - this render path never touches audio hardware itself.
 */
public final class PartyVoiceHudElement implements HudElement {

    private static final int ROW_HEIGHT = 18;
    private static final int WIDTH = 130;

    @Override
    public String id() {
        return "proximity_voice_party";
    }

    @Override
    public String displayName() {
        return "Party Voice";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 200;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return Math.max(1, PartyTracker.teammates().size()) * ROW_HEIGHT;
    }

    @Override
    public boolean isRelevantNow() {
        return ProximityVoiceConfig.getInstance().isShowPartyVoiceHud() && ProximityVoiceFeature.isActive();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        if (!cfg.isShowPartyVoiceHud() || !ProximityVoiceFeature.isActive() || HudVisibility.hidesHud()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        List<Player> loaded = LeapMenuFeature.currentPartyMembers();
        int rowY = y;
        for (String name : PartyTracker.teammates()) {
            boolean talking = ProximityVoiceFeature.isTalking(name);
            Player player = findLoaded(loaded, name);
            if (player != null) {
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
                graphics.item(head, x + 2, rowY + 1);
            }
            graphics.text(client.font, name, x + 20, rowY + 5, talking ? 0xFFFFFFFF : 0xFFA0A0A0, false);
            if (talking) {
                graphics.outline(x, rowY, WIDTH, ROW_HEIGHT - 2, 0xFFCC6600);
            } else {
                // Faded/transparent while silent (killer560: "like the Discord overlay") - vanilla's item
                // renderer doesn't reliably respect an alpha tint, so this dims the whole row with a
                // translucent overlay drawn ON TOP of the head+name instead of trying to alpha-blend the
                // head render itself.
                graphics.fill(x, rowY, x + WIDTH, rowY + ROW_HEIGHT - 2, 0x99000000);
            }
            rowY += ROW_HEIGHT;
        }
    }

    private static Player findLoaded(List<Player> loaded, String name) {
        for (Player p : loaded) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
