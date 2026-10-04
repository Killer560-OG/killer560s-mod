package com.killer560.hub.roomsim;

import com.killer560.hub.profileviewer.item.LegacyText;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Skyblock tooltips for the sim's items.
 *
 * <p>killer560 (2026-09-29): "can you make them have the same tooltip as main as well for all items the player
 * gets."
 *
 * <p>A sim item was a vanilla stack with a plain white name on it, so the tooltip read "Superboom TNT" and
 * under it "TNT". That is not the item he uses on Hypixel, and the tooltip is not decoration: the ability line
 * and the mana cost are what you check when you are learning what a class can do, and the rarity colour is how
 * you tell two items apart in a hotbar at a glance.
 *
 * <p><b>What is here and what is deliberately not.</b> These are the ability blocks and the rarity lines, which
 * are stable and well known. The numeric STAT blocks - damage, strength, crit - are not, and they change with
 * reforges, stars, enchantments and Hypixel's own balance passes. A tooltip that showed made-up stat numbers
 * would look more authentic and be worth less than one that shows none, so the stats are left out rather than
 * guessed at. If he wants them, they should come from the same place the item browser's data does.
 *
 * <p>Vanilla's own lines are hidden the way {@code profileviewer.item.LegacyItems} already hides them, so a
 * Superboom does not explain that TNT can be lit by flint and steel.
 */
public final class SimItemLore {

    private static final TooltipDisplay HIDE_VANILLA_LINES;

    static {
        SequencedSet<DataComponentType<?>> hidden = new LinkedHashSet<>();
        hidden.add(DataComponents.ATTRIBUTE_MODIFIERS);
        hidden.add(DataComponents.ENCHANTMENTS);
        hidden.add(DataComponents.STORED_ENCHANTMENTS);
        hidden.add(DataComponents.UNBREAKABLE);
        HIDE_VANILLA_LINES = new TooltipDisplay(false, hidden);
    }

    /**
     * Name colour and lore per Skyblock id.
     *
     * <p>Keyed on the id in CUSTOM_DATA rather than on the sim's own enum, so anything that hands out an item -
     * the picker, a loadout, a secret drop, a wither key - gets the same tooltip without knowing about this
     * class.
     */
    private static final Map<String, String[]> LORE = Map.ofEntries(
        Map.entry("ASPECT_OF_THE_VOID", new String[]{
            "§5Aspect of the Void",
            "§6Ability: Instant Transmission  §e§lRIGHT CLICK",
            "§7Teleport §a12 blocks §7ahead of you",
            "§7and gain §a+50§f ✦ Speed §7for 3",
            "§7seconds.",
            "§8Mana Cost: 50",
            "",
            "§6Ability: Etherwarp  §e§lSNEAK RIGHT CLICK",
            "§7Teleport to the block you are",
            "§7looking at, up to §a61 blocks §7away.",
            "§8Mana Cost: 10",
            "",
            "§5§lEPIC SWORD",
        }),
        Map.entry("HYPERION", new String[]{
            "§6Hyperion",
            "§6Ability: Wither Impact  §e§lRIGHT CLICK",
            "§7Teleport §a10 blocks §7ahead of you.",
            "§7Then implode dealing §c10,000 §7damage",
            "§7to nearby enemies.",
            "§8Mana Cost: 300",
            "",
            "§6§lLEGENDARY SWORD",
        }),
        Map.entry("BAT_WAND", new String[]{
            "§6Spirit Sceptre",
            "§6Ability: Bat Swarm  §e§lRIGHT CLICK",
            "§7Shoots a swarm of §a10 §7bats, each",
            "§7dealing §c3,000 §7damage.",
            "§8Mana Cost: 50",
            "",
            "§6§lLEGENDARY WAND",
        }),
        Map.entry("TERMINATOR", new String[]{
            "§6Terminator",
            // THE LINE THREE AUTOS LOOK FOR. killer560 (2026-10-01): "the auto puzzles none were working except
            // auto blaze wanted to look towards the middle."
            //
            // That is this line's absence, exactly. Auto Creeper Beams, Auto Ice Path and Auto Blaze all gate
            // their shot on AutoPuzzleUtil.isShortbow, which is a lore search for "Shortbow: Instantly shoots!"
            // and nothing else - it is how Hypixel marks every shortbow, Terminator included - and this lore did
            // not have it. So all three ran their aim and then declined to fire, and Auto Blaze stopping with
            // the crosshair on the middle blaze is precisely what that looks like. AutoReposition's own
            // swap-to-a-bow (swapTo(isShortbow)) was failing for the same reason.
            "§6Shortbow: Instantly shoots!",
            "§6Ability: Salvation §e§lLEFT CLICK",
            "§7Shoots §a3 §7arrows at once.",
            "",
            "§6§lLEGENDARY BOW",
        }),
        Map.entry("SUPERBOOM_TNT", new String[]{
            "§9Superboom TNT",
            "§7Blast the fragile walls in §cThe",
            "§cCatacombs §7to open crypts and",
            "§7uncover secrets.",
            "",
            "§9§lRARE",
        }),
        Map.entry("ARCHITECT_FIRST_DRAFT", new String[]{
            "§5Architect's First Draft",
            "§6Ability: Solve  §e§lRIGHT CLICK",
            "§7Instantly completes the puzzle in",
            "§7the room you are standing in.",
            "§8Consumed on use",
            "",
            "§5§lEPIC",
        }),
        Map.entry("TACTICAL_INSERTION", new String[]{
            "§9Tactical Insertion",
            "§6Ability: Insert  §e§lRIGHT CLICK",
            "§7Place a beacon. Use it again to",
            "§7teleport back to it.",
            "",
            "§9§lRARE",
        }),
        Map.entry("DUNGEONBREAKER", new String[]{
            "§5Dungeon Breaker",
            "§7Breaks dungeon blocks instantly.",
            "§8Charges: 5/5",
            "",
            "§5§lEPIC PICKAXE",
        }),
        Map.entry("ENDER_PEARL", new String[]{
            "§fEnder Pearl",
            "§7Throw it to teleport where it lands.",
            "",
            "§f§lCOMMON",
        }),
        Map.entry("WITHER_KEY", new String[]{
            "§6Wither Key",
            "§7Opens a §8Wither Door §7in",
            "§cThe Catacombs§7.",
            "",
            "§6§lLEGENDARY",
        }),
        Map.entry("DECOY", new String[]{
            "§aDecoy",
            "§7Places a decoy that draws mobs",
            "§7towards it.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("TRAP", new String[]{
            "§aTrap",
            "§7Slows down nearby enemies.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("DEFUSE_KIT", new String[]{
            "§aDefuse Kit",
            "§7Defuses a §cSuperboom §7trap.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("INFLATABLE_JERRY", new String[]{
            "§aInflatable Jerry",
            "§7Places an inflatable Jerry that",
            "§7distracts nearby mobs.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("TRAINING_WEIGHTS", new String[]{
            "§aTraining Weights",
            "§7Slows you down, but you hit harder.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("SPIRIT_LEAP", new String[]{
            "§9Spirit Leap",
            "§6Ability: Leap  §e§lRIGHT CLICK",
            "§7Teleport to a party member.",
            "",
            "§9§lRARE",
        })
    );

    private SimItemLore() {
    }

    /**
     * Puts the Skyblock name and lore on a stack.
     *
     * <p>The first line of the table is the NAME, which is where the rarity colour lives - an item's colour is
     * how you find it in a hotbar, and a white "Hyperion" is not a Hyperion. Unknown ids are left alone rather
     * than given an invented tooltip.
     *
     * @return true when a tooltip was applied
     */
    public static boolean apply(ItemStack stack, String skyblockId) {
        if (stack == null || skyblockId == null) {
            return false;
        }
        String[] text = LORE.get(skyblockId.toUpperCase(Locale.ROOT));
        if (text == null || text.length < 2) {
            return false;
        }
        stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(text[0]));
        List<Component> lines = new ArrayList<>(text.length - 1);
        for (int i = 1; i < text.length; i++) {
            lines.add(LegacyText.parse(text[i]));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines, lines));
        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
        return true;
    }

    /** Whether there is a tooltip for this id at all - for tests, which should not assert on a typo. */
    public static boolean knows(String skyblockId) {
        return skyblockId != null && LORE.containsKey(skyblockId.toUpperCase(Locale.ROOT));
    }
}
