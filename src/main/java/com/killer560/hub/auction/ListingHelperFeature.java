package com.killer560.hub.auction;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.profileviewer.item.LegacyItems;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * killer560's item 8.1, part 4: "when he is on Hypixel's own 'Create BIN Auction' menu, show the current
 * lowest BIN for that item and a button that copies lowest-minus-one-coin to the clipboard... Plus an
 * estimated value."
 * <p>
 * <b>Honesty note (per the brief)</b>: another agent verified live (2026-09-21) that Hypixel's public item
 * resource now carries real crafting-recipe data for only 1 of 5,655 items (see
 * {@link com.killer560.hub.itembrowser.SkyblockItemRepository}'s own class doc) - a real "raw craft cost"
 * tree is NOT available from Hypixel's API any more. This never pretends otherwise: "Estimated Value"
 * below is always the lowest BIN among similar CURRENT listings (from {@link AuctionHouseApi}'s own
 * already-scanned cache), never a fabricated craft cost.
 * <p>
 * <b>Similarity %</b>, stated plainly: 100% requires the same real {@code ExtraAttributes.id}; on top of
 * that, -20% if the reforge ({@code ExtraAttributes.modifier}) differs, -15% if the dungeon star count
 * ({@code ExtraAttributes.dungeon_item_level}) differs, -10% if the enchant count
 * ({@code ExtraAttributes.enchantments}' key count) differs. These three extra NBT keys are well-
 * established, stable real Hypixel fields (the same ones NEU/Skytils/SkyblockAddons have used for years);
 * this session could not verify them against a live sample (no game launch allowed - see the boot-test
 * rule), so treat the exact percentages as a documented, honest heuristic rather than a Hypixel-published
 * formula. Only listings that share the id are compared at all - a similarity score is never shown for a
 * different item.
 * <p>
 * Never types into or clicks Hypixel's own menu - this only reads the container's item and draws its own
 * separate panel + button beside it, exactly like {@code ChestProfitFeature}'s reward-chest overlay.
 */
public final class ListingHelperFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-listinghelper");

    /** Exact real Hypixel container title, as given by killer560's own report. */
    private static final String SCREEN_TITLE = "Create BIN Auction";

    private static final int PANEL_BG = 0xD0141008;
    private static final int PANEL_BORDER = 0xFF663D1A;
    private static final int BUTTON_BG = 0xFF2A1A0A;
    private static final int BUTTON_BG_HOVER = 0xFF3D2A14;
    private static final int BUTTON_BORDER = 0xFFCC6600;

    private static int[] copyButtonRect = null;
    private static long copiedAtMs = 0;

    private ListingHelperFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ListingHelperFeature::onScreenInit);
    }

    private static boolean active() {
        return AuctionConfig.getInstance().isListingHelperEnabled();
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        copyButtonRect = null;
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            try {
                render(containerScreen, graphics, mouseX, mouseY);
            } catch (Exception e) {
                LOGGER.error("[ListingHelper] Render failed", e);
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!active() || copyButtonRect == null || event.button() != 0) {
                return true;
            }
            int[] r = copyButtonRect;
            if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                copyLowestMinusOne(containerScreen);
                return false;
            }
            return true;
        });
    }

    private static void copyLowestMinusOne(AbstractContainerScreen<?> screen) {
        ItemStack listed = findListedItem(screen);
        if (listed == null || listed.isEmpty()) {
            return;
        }
        Match best = bestMatch(listed);
        if (best == null) {
            ModChat.send("Listing Helper", ModChat.bad("No similar current listings found yet - try Refresh in the Auction House tab."));
            return;
        }
        long suggested = Math.max(1, best.listing().startingBid() - 1);
        Minecraft.getInstance().keyboardHandler.setClipboard(String.valueOf(suggested));
        copiedAtMs = System.currentTimeMillis();
        ModChat.send("Listing Helper", ModChat.text("Copied "), ModChat.value(String.valueOf(suggested)),
                ModChat.text(" coins to your clipboard."));
    }

    /** The real Skyblock item being listed almost always carries {@code ExtraAttributes} - unlike every
     *  filler/button item Hypixel's own menu chrome uses (glass panes, plain vanilla control icons) -
     *  so that's used to find it instead of a hand-guessed fixed slot number, which would silently break
     *  the moment Hypixel rearranges that menu's layout. Falls back to the first non-empty item in the
     *  container half of the inventory (excluding the player's own 36 slots) if nothing has
     *  ExtraAttributes, so a plain-vanilla-material Skyblock item still gets picked up. */
    private static ItemStack findListedItem(AbstractContainerScreen<?> screen) {
        List<ItemStack> topSlots = new ArrayList<>();
        int size = Math.max(0, screen.getMenu().slots.size() - 36);
        for (int i = 0; i < size; i++) {
            topSlots.add(screen.getMenu().slots.get(i).getItem());
        }
        ItemStack fallback = null;
        for (ItemStack stack : topSlots) {
            if (stack.isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = stack;
            }
            if (!LegacyItems.skyblockId(stack).isEmpty()) {
                return stack;
            }
        }
        return fallback;
    }

    private record Match(AuctionListing listing, int similarityPercent) {
    }

    private static Match bestMatch(ItemStack listed) {
        String id = LegacyItems.skyblockId(listed);
        if (id.isEmpty()) {
            return null;
        }
        CustomData data = listed.get(DataComponents.CUSTOM_DATA);
        CompoundTag extra = data == null ? new CompoundTag() : data.copyTag();
        String reforge = extra.getStringOr("modifier", "");
        int stars = extra.getIntOr("dungeon_item_level", 0);
        int enchantCount = extra.getCompoundOrEmpty("enchantments").size();

        List<Match> candidates = new ArrayList<>();
        for (AuctionListing l : AuctionHouseApi.getListings()) {
            if (!id.equals(l.skyblockId())) {
                continue;
            }
            int score = 100;
            // "No reforge" on both sides still counts as a match (both empty strings compare equal) -
            // only an actual difference costs points.
            String otherReforge = extraAttribute(l.icon(), "modifier");
            if (!reforge.equals(otherReforge)) {
                score -= 20;
            }
            int otherStars = extraAttributeInt(l.icon(), "dungeon_item_level");
            if (stars != otherStars) {
                score -= 15;
            }
            int otherEnchants = extraAttributeCompoundSize(l.icon(), "enchantments");
            if (enchantCount != otherEnchants) {
                score -= 10;
            }
            candidates.add(new Match(l, Math.max(0, score)));
        }
        return candidates.stream()
                .min(Comparator.comparingLong((Match m) -> m.listing().startingBid()))
                .orElse(null);
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static String extraAttribute(ItemStack stack, String key) {
        return extraAttributes(stack).getStringOr(key, "");
    }

    private static int extraAttributeInt(ItemStack stack, String key) {
        return extraAttributes(stack).getIntOr(key, 0);
    }

    private static int extraAttributeCompoundSize(ItemStack stack, String key) {
        return extraAttributes(stack).getCompoundOrEmpty(key).size();
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        copyButtonRect = null;
        if (!active() || Minecraft.getInstance().screen != screen) {
            return;
        }
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
        if (!SCREEN_TITLE.equalsIgnoreCase(title)) {
            return;
        }
        ItemStack listed = findListedItem(screen);
        if (listed == null || listed.isEmpty()) {
            return;
        }
        Match best = bestMatch(listed);

        Font font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>();
        lines.add("Listing Helper");
        if (best == null) {
            lines.add("No similar listings scanned yet.");
        } else {
            lines.add("Lowest similar BIN: " + com.killer560.hub.croesus.DungeonChestValuer.formatCoins(best.listing().startingBid()) + " (" + best.similarityPercent() + "% match)");
            lines.add("Estimated value: " + com.killer560.hub.croesus.DungeonChestValuer.formatCoins(best.listing().startingBid()) + " coins");
            lines.add("(lowest current BIN of similar listings - real craft-cost data isn't available)");
        }

        int leftPos = ((AbstractContainerScreenAccessor) screen).killer560smod$getLeftPos();
        int topPos = ((AbstractContainerScreenAccessor) screen).killer560smod$getTopPos();
        // leftPos = (width - imageWidth) / 2, so the container's right edge mirrors its left edge - same
        // trick ChestProfitFeature uses, avoiding needing imageWidth directly.
        int rightEdge = screen.width - leftPos;
        int x = rightEdge + 6;
        int panelW = 0;
        for (String line : lines) {
            panelW = Math.max(panelW, font.width(line));
        }
        int buttonW = 130;
        int buttonH = 16;
        panelW = Math.max(panelW, buttonW) + 8;
        int panelH = lines.size() * 10 + 6 + (best != null ? buttonH + 6 : 0);
        if (x + panelW > screen.width - 2) {
            x = Math.max(2, leftPos - panelW - 6);
        }
        int y = Math.max(2, topPos);

        graphics.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        graphics.outline(x, y, panelW, panelH, PANEL_BORDER);
        int ty = y + 4;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + 4, ty, i == 0 ? 0xFFCC6600 : 0xFFE0D8CC, true);
            ty += 10;
        }
        if (best != null) {
            int bx = x + 4;
            int by = ty + 2;
            boolean hover = mouseX >= bx && mouseX < bx + buttonW && mouseY >= by && mouseY < by + buttonH;
            boolean justCopied = System.currentTimeMillis() - copiedAtMs < 1500;
            graphics.fill(bx, by, bx + buttonW, by + buttonH, hover ? BUTTON_BG_HOVER : BUTTON_BG);
            graphics.outline(bx, by, buttonW, buttonH, BUTTON_BORDER);
            String label = justCopied ? "Copied!" : "Copy Lowest - 1";
            graphics.text(font, label, bx + (buttonW - font.width(label)) / 2, by + 4, 0xFFFFFFFF, false);
            copyButtonRect = new int[]{bx, by, buttonW, buttonH};
        }
    }
}
