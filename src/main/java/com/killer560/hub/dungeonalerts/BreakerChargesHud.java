package com.killer560.hub.dungeonalerts;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaker Charges HUD (from the 2026-10-18 review): the Dungeon Breaker's "Charges: N/M" lore line as a movable HUD
 * line, read from the first breaker in your hotbar, while you are in a dungeon. Hypixel rewrites the lore as the
 * charges are spent and come back, so the line follows it. The digits are bounded.
 */
public final class BreakerChargesHud {

    private static final Pattern CHARGES = Pattern.compile("Charges: (\\d{1,4})/(\\d{1,4})");
    private static String lastDrawn = null;

    private BreakerChargesHud() {
    }

    /** {charges, max} of the first Dungeon Breaker in the hotbar, or null. */
    static int[] hotbarCharges(Minecraft client) {
        if (client.player == null) {
            return null;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !ItemIdentity.isDungeonBreaker(stack)) {
                continue;
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                return null;
            }
            for (Component line : lore.lines()) {
                Matcher m = CHARGES.matcher(line.getString());
                if (m.find()) {
                    return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                }
            }
            return null;
        }
        return null;
    }

    private static String text(int[] c) {
        String color = c[0] * 3 >= c[1] * 2 ? "§a" : c[0] * 3 >= c[1] ? "§e" : "§c";
        return color + c[0] + "§7/§f" + c[1];
    }

    /** For the testkit: the value text last drawn in game, or null. */
    public static String lastDrawn() {
        return lastDrawn;
    }

    static final HudElement HUD = new HudElement() {
        private static final String LABEL = "Breaker: ";

        @Override
        public String id() {
            return "breaker_charges";
        }

        @Override
        public String displayName() {
            return "Breaker Charges";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 140;
        }

        @Override
        public int width() {
            var font = Minecraft.getInstance().font;
            int[] c = hotbarCharges(Minecraft.getInstance());
            return 1 + font.width(LABEL) + font.width(text(c == null ? new int[]{20, 20} : c));
        }

        @Override
        public int height() {
            return 1 + com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            return DungeonAlertsConfig.getInstance().isBreakerChargesHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = DungeonAlertsFeature.isEditorOpen();
            Minecraft client = Minecraft.getInstance();
            int[] c = example ? new int[]{20, 20} : null;
            if (!example) {
                if (!DungeonAlertsConfig.getInstance().isBreakerChargesHud() || !DungeonState.isInDungeon()) {
                    return;
                }
                c = hotbarCharges(client);
                if (c == null) {
                    return;
                }
            }
            String value = text(c);
            if (!example) {
                lastDrawn = value;
            }
            var font = client.font;
            HudSeen.markDrawn(id());
            graphics.text(font, LABEL, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, value, x + 1 + font.width(LABEL), y + 1, 0xFFFFFFFF, true);
        }
    };
}
