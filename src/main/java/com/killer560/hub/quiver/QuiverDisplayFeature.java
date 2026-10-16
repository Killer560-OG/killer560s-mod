package com.killer560.hub.quiver;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Skyblock quiver-arrow-count display, ported from Odin's own {@code QuiverDisplay.kt}. Real
 * arrow/quiver items (Flint Arrow, Spirit Bow ammo, etc.) show their remaining count on a real visible
 * lore line ("Arrows Remaining: N") rather than exposing it any other way. Scans the player's own real
 * inventory (hotbar, main inventory, and off hand) each tick for a Feather- or Arrow-type item carrying
 * that lore line - simpler and more robust than Odin's own fixed-slot-index check, which depends on
 * exactly which menu is open and isn't something this session could confirm still matches a specific
 * slot number in this Minecraft version.
 */
public final class QuiverDisplayFeature {

    private static final Pattern ARROWS_REMAINING = Pattern.compile("^Arrows Remaining: ([\\d,]+)$");

    private static String cachedName = null;
    private static String cachedCount = null;
    /** True once the low-arrow alert fired for this dip; re-armed when the count is back at or above the threshold. */
    private static boolean alertLatched = false;
    /** Alerts fired since launch, and the last title shown - read by the testkit. */
    private static int alertsFired = 0;
    private static String lastAlertTitle = null;

    private QuiverDisplayFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("QuiverDisplayFeature.tick", com.killer560.hub.util.FeatureBits.register("Quiver Display tick", () -> QuiverDisplayConfig.getInstance().isEnabledRaw()), QuiverDisplayFeature::tick));
    }

    private static void tick(Minecraft client) {
        if (!QuiverDisplayConfig.getInstance().isEnabled() || client.player == null) {
            return;
        }
        Player player = client.player;
        ItemStack found = null;
        // Real 36-slot hotbar+main-inventory bound, same convention already established elsewhere in
        // this mod (e.g. AutoLeapFeature's own bounded slot search) - Inventory#items is private in
        // this version, getItem(int) is the real public accessor.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isQuiverItem(stack)) {
                found = stack;
                break;
            }
        }
        if (found == null && isQuiverItem(player.getOffhandItem())) {
            found = player.getOffhandItem();
        }
        if (found == null) {
            cachedName = null;
            cachedCount = null;
            return;
        }
        String count = readArrowsRemaining(found);
        if (count == null) {
            return;
        }
        cachedName = found.getHoverName().getString();
        cachedCount = count;
        checkLowAlert(client, count);
    }

    /** Fires the low-arrow alert once each time the count drops below the threshold. */
    static void checkLowAlert(Minecraft client, String countText) {
        QuiverDisplayConfig cfg = QuiverDisplayConfig.getInstance();
        int count;
        try {
            // Bounded by the lore regex's [\d,]+ only, so guard the parse: a huge number is simply "not low".
            count = Integer.parseInt(countText.replace(",", ""));
        } catch (NumberFormatException e) {
            return;
        }
        if (count >= cfg.getAlertThreshold()) {
            alertLatched = false;
            return;
        }
        if (!cfg.isAlertEnabled() || alertLatched) {
            return;
        }
        alertLatched = true;
        alertsFired++;
        String text = cfg.getAlertText().replace("{count}", countText).replace('&', '§');
        lastAlertTitle = text;
        if (client.gui != null && !text.isBlank()) {
            McCompat.setTimes(client, 0, 40, 10);
            McCompat.setTitle(client, Component.literal(text));
        }
        if (cfg.isAlertSound()) {
            // forUI(SoundEvent, float pitch, float volume) - the call Leap Counter's alert makes.
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f));
        }
    }

    public static int alertsFired() {
        return alertsFired;
    }

    public static String lastAlertTitle() {
        return lastAlertTitle;
    }

    private static boolean isQuiverItem(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.FEATHER) || stack.is(Items.ARROW));
    }

    private static String readArrowsRemaining(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (Component line : lore.lines()) {
            Matcher matcher = ARROWS_REMAINING.matcher(line.getString());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    public static final class QuiverHudElement implements HudElement {
        @Override
        public String id() {
            return "quiver_display";
        }

        @Override
        public String displayName() {
            return "Quiver Display";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 420;
        }

        @Override
        public int width() {
            // The drawn text (2026-10-07 box audit: it was a fixed 150); 150 before the quiver has been read.
            String text = text();
            return text == null ? 150 : Minecraft.getInstance().font.width(text);
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.ROW;
        }

        /** What render() draws, or null before the quiver count is known. */
        private static String text() {
            if (cachedCount == null) {
                return null;
            }
            // Plain text, drawn in the Text Color setting (formatting codes would override it).
            return (QuiverDisplayConfig.getInstance().isShowName() && cachedName != null
                    ? net.minecraft.ChatFormatting.stripFormatting(cachedName) + " x" : "x") + cachedCount;
        }

        @Override
        public boolean isEnabledInSettings() {
            return QuiverDisplayConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!QuiverDisplayConfig.getInstance().isEnabled() || HudVisibility.hidesHud()
                    || cachedCount == null) {
                return;
            }
            String text = text();
            HudSeen.markDrawn(id());
            graphics.text(Minecraft.getInstance().font, text, x, y, QuiverDisplayConfig.getInstance().getTextColor(), false);
        }
    }
}
