package com.killer560.hub.armourdye;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hypixel's item skins, for Custom Items' Skin row (killer560, 2026-11-01: "I tried wither goggles and it doesnt show the
 * wither goggles custom skins ... Make sure itll work even with the item being starred or recombed or fragged").
 * <p>
 * The old Hypixel Skin list read Hypixel's public items resource and kept the entries whose NAME contained the selected
 * item's full display name. Two faults: that resource holds only ~170 skins and none of the Wither Goggles ones (checked
 * 2026-11-01), and a display name carries a reforge, stars and a fragged mark ("Heroic Wither Goggles ✪✪✪✪✪"), so nothing
 * matched. This table is the NotEnoughUpdates-REPO's 180 item skins (tools/item-skins-extract.py), each with the items its
 * lore says it "can be applied to". A stack matches a skin when its Skyblock id ({@code STARRED_} dropped by
 * {@link ArmourDye#identityOf}; a recomb only changes the rarity in the lore) names one of those items in the Hypixel
 * catalog, or, without a catalog, when its cleaned display name ends with one of them.
 */
public final class ItemSkins {

    private static final Logger LOGGER = ModLog.get("killer560smod-armourdye");
    private static final String PATH = "/assets/killer560smod/armourdye/item-skins.json";

    /** One skin: its Skyblock id, display name, the item names it applies to, and the head texture it draws. */
    public record Skin(String id, String name, List<String> targets, String texture) {
    }

    private static List<Skin> skins;

    private ItemSkins() {
    }

    public static synchronized List<Skin> all() {
        if (skins == null) {
            List<Skin> list = new ArrayList<>();
            try (InputStream in = ItemSkins.class.getResourceAsStream(PATH)) {
                if (in != null) {
                    JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("skins")) {
                        JsonObject o = el.getAsJsonObject();
                        List<String> targets = new ArrayList<>();
                        for (JsonElement t : o.getAsJsonArray("targets")) {
                            targets.add(t.getAsString());
                        }
                        list.add(new Skin(o.get("id").getAsString(), o.get("name").getAsString(), List.copyOf(targets),
                                o.get("texture").getAsString()));
                    }
                } else {
                    LOGGER.warn("[CustomItems] bundled item skin table missing ({})", PATH);
                }
            } catch (Exception e) {
                LOGGER.warn("[CustomItems] bundled item skin table unreadable", e);
            }
            skins = List.copyOf(list);
        }
        return skins;
    }

    /** Every skin that can be applied to {@code stack}, in table order. Empty when there is none ("Skin: N/A"). */
    public static List<Skin> forItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String id = ArmourDye.identityOf(stack);
        SkyblockItemEntry catalog = id == null ? null : SkyblockItemRepository.findById(id);
        String catalogName = catalog == null || catalog.name() == null ? null : catalog.name().toLowerCase(Locale.ROOT);
        String cleaned = baseName(stack.getHoverName().getString());
        return forNames(catalogName, cleaned);
    }

    /** The skins whose targets equal {@code catalogName} or end {@code cleanedName} at a word boundary. Public for the testkit. */
    public static List<Skin> forNames(String catalogName, String cleanedName) {
        List<Skin> out = new ArrayList<>();
        for (Skin s : all()) {
            for (String target : s.targets()) {
                String t = target.toLowerCase(Locale.ROOT);
                if (t.equals(catalogName) || endsWithWord(cleanedName, t)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean endsWithWord(String name, String target) {
        if (name == null || target.isEmpty() || !name.endsWith(target)) {
            return false;
        }
        int at = name.length() - target.length();
        return at == 0 || name.charAt(at - 1) == ' ';
    }

    /**
     * A display name without what a reforge, stars or a fragment add: formatting, the {@code ⚚} fragged mark, the
     * dungeon and master stars and anything else that is not a letter, digit, apostrophe or space, lower-cased. The reforge
     * word stays in front; {@link #endsWithWord} ignores it.
     */
    public static String baseName(String display) {
        if (display == null) {
            return "";
        }
        String plain = ChatFormatting.stripFormatting(display);
        StringBuilder sb = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 0x2000 || c == '\'' || c == ' ' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
