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
 * and the mana cost are what you check when you are learning what a class can do, and the rarity color is how
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
     * Name color and lore per Skyblock id.
     *
     * <p>Keyed on the id in CUSTOM_DATA rather than on the sim's own enum, so anything that hands out an item -
     * the picker, a loadout, a secret drop, a wither key - gets the same tooltip without knowing about this
     * class.
     */
    private static final Map<String, String[]> LORE = Map.ofEntries(
        Map.entry("INFERNAL_TERROR_HELMET", new String[]{
            "§6Infernal Terror Helmet",
            "§7Health: §a+403",
            "§7Defense: §a++126",
            "§7Crit Damage: §9+126%",
            "§7Intelligence: §a+38",
            "§7Speed: §a+30",
            "",
            "§6Full Set Bonus: Hydra Strike §8(Sim: always 10 stacks)",
            "§7Each §6Hydra Strike §7stack grants §c+15% Damage",
            "§7and §f+3% Arrow Speed§7.",
            "§7At 10 stacks, shoot §a+2 §7arrows that deal",
            "§a75% §7Arrow Damage.",
            "",
            "§6§lLEGENDARY HELMET"
        }),
        Map.entry("INFERNAL_TERROR_CHESTPLATE", new String[]{
            "§6Infernal Terror Chestplate",
            "§7Health: §a+580",
            "§7Defense: §a++164",
            "§7Crit Damage: §9+126%",
            "§7Intelligence: §a+13",
            "§7Speed: §a+30",
            "",
            "§6Full Set Bonus: Hydra Strike §8(Sim: always 10 stacks)",
            "§7Each §6Hydra Strike §7stack grants §c+15% Damage",
            "§7and §f+3% Arrow Speed§7.",
            "§7At 10 stacks, shoot §a+2 §7arrows that deal",
            "§a75% §7Arrow Damage.",
            "",
            "§6§lLEGENDARY CHESTPLATE"
        }),
        Map.entry("INFERNAL_TERROR_LEGGINGS", new String[]{
            "§6Infernal Terror Leggings",
            "§7Health: §a+517",
            "§7Defense: §a++139",
            "§7Crit Damage: §9+126%",
            "§7Intelligence: §a+13",
            "§7Speed: §a+30",
            "",
            "§6Full Set Bonus: Hydra Strike §8(Sim: always 10 stacks)",
            "§7Each §6Hydra Strike §7stack grants §c+15% Damage",
            "§7and §f+3% Arrow Speed§7.",
            "§7At 10 stacks, shoot §a+2 §7arrows that deal",
            "§a75% §7Arrow Damage.",
            "",
            "§6§lLEGENDARY LEGGINGS"
        }),
        Map.entry("INFERNAL_TERROR_BOOTS", new String[]{
            "§6Infernal Terror Boots",
            "§7Health: §a+328",
            "§7Defense: §a++101",
            "§7Crit Damage: §9+126%",
            "§7Intelligence: §a+13",
            "§7Speed: §a+30",
            "",
            "§6Full Set Bonus: Hydra Strike §8(Sim: always 10 stacks)",
            "§7Each §6Hydra Strike §7stack grants §c+15% Damage",
            "§7and §f+3% Arrow Speed§7.",
            "§7At 10 stacks, shoot §a+2 §7arrows that deal",
            "§a75% §7Arrow Damage.",
            "",
            "§6§lLEGENDARY BOOTS"
        }),
        Map.entry("THERMODYNAMIC_HELMET", new String[]{
            "§6Thermodynamic Helmet",
            "§7Defense: §a+135",
            "§7Attack Speed: §e+5",
            "§7Speed: §a+25",
            "",
            "§6Full Set Bonus: Newton's Demise",
            "§7Grants §e+50 Attack Speed Cap§7.",
            "",
            "§6§lLEGENDARY HELMET"
        }),
        Map.entry("THERMODYNAMIC_CHESTPLATE", new String[]{
            "§6Thermodynamic Chestplate",
            "§7Defense: §a+240",
            "§7Attack Speed: §e+5",
            "§7Speed: §a+25",
            "",
            "§6Full Set Bonus: Newton's Demise",
            "§7Grants §e+50 Attack Speed Cap§7.",
            "",
            "§6§lLEGENDARY CHESTPLATE"
        }),
        Map.entry("THERMODYNAMIC_LEGGINGS", new String[]{
            "§6Thermodynamic Leggings",
            "§7Defense: §a+200",
            "§7Attack Speed: §e+5",
            "§7Speed: §a+25",
            "",
            "§6Full Set Bonus: Newton's Demise",
            "§7Grants §e+50 Attack Speed Cap§7.",
            "",
            "§6§lLEGENDARY LEGGINGS"
        }),
        Map.entry("THERMODYNAMIC_BOOTS", new String[]{
            "§6Thermodynamic Boots",
            "§7Defense: §a+125",
            "§7Attack Speed: §e+5",
            "§7Speed: §a+25",
            "",
            "§6Full Set Bonus: Newton's Demise",
            "§7Grants §e+50 Attack Speed Cap§7.",
            "",
            "§6§lLEGENDARY BOOTS"
        }),
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
            // auto blaze wanted to look toward the middle."
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
        Map.entry("MACHINE_GUN_BOW", new String[]{
            "§9Machine Gun Shortbow",
            "§6Shortbow: Instantly shoots!",
            "§6Ability: Rapid Fire §e§lLEFT CLICK",
            "§7Shoots §a5 §7arrows per second for",
            "§a8 §7seconds! Arrows deal §a70% §7of what",
            "§7they would normally deal.",
            "§8Cooldown: §a100s",
            "",
            "§9§lRARE DUNGEON BOW",
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
        // simitems-1105: Hypixel's own lore lines (the wiki's item pages, 2026-11-05), rarity line last.
        Map.entry("BONZO_STAFF", new String[]{
            "§9Bonzo's Staff",
            "§6Ability: Showtime  §e§lRIGHT CLICK",
            "§7Shoots balloons that create a large",
            "§7explosion on impact, dealing up to",
            "§c1,000 §7damage.",
            "§8Mana Cost: §390",
            "",
            "§9§lRARE DUNGEON SWORD",
        }),
        Map.entry("JERRY_STAFF", new String[]{
            "§5Jerry-chine Gun",
            "§6Ability: Rapid-fire  §e§lRIGHT CLICK",
            "§7Shoots a Jerry bullet, dealing §c500",
            "§7damage on impact and knocking you",
            "§7back. Each shot costs §b+30 §7mana more",
            "§7than the previous, resetting after",
            "§a4s §7of not firing.",
            "§8Mana Cost: §330",
            "",
            "§5§lEPIC SWORD",
        }),
        Map.entry("SPRING_BOOTS", new String[]{
            "§9Spring Boots",
            "§9Feather Falling XX",
            "",
            "§6Ability: To the moon!  §e§lSNEAK",
            "§7Charge your jump by sneaking. The",
            "§7longer you sneak, the higher you",
            "§7will jump.",
            "",
            "§9§lRARE BOOTS",
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
            "§8Charges: 20/20",
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
        // The wiki's ability text and cooldowns (siminfleap-1116); line breaks and colors are estimates (docs/SIM.md).
        Map.entry("SPIRIT_LEAP", new String[]{
            "§9Spirit Leap",
            "§6Ability: Spirit Leap  §e§lRIGHT CLICK",
            "§7Allows you to teleport to any",
            "§7teammate! Grants §a1 §7second of",
            "§7immunity after teleporting,",
            "§7immunity is cancelled upon",
            "§7dealing damage.",
            "§8Cooldown: §a5s",
            "",
            "§9§lRARE DUNGEON ITEM",
        }),
        Map.entry("INFINITE_SPIRIT_LEAP", new String[]{
            "§5InfiniLeap™",
            "§6Ability: Spirit Leap  §e§lRIGHT CLICK",
            "§7Allows you to teleport to any",
            "§7teammate! Grants §a1 §7second of",
            "§7immunity after teleporting,",
            "§7immunity is cancelled upon",
            "§7dealing damage.",
            "§8Cooldown: §a2s",
            "",
            "§5§lEPIC DUNGEON ITEM",
        })
    );

    private SimItemLore() {
    }

    /**
     * Puts the Skyblock name and lore on a stack.
     *
     * <p>The first line of the table is the NAME, which is where the rarity color lives - an item's color is
     * how you find it in a hotbar, and a white "Hyperion" is not a Hyperion. Unknown ids are left alone rather
     * than given an invented tooltip.
     *
     * @return true when a tooltip was applied
     */
    public static boolean apply(ItemStack stack, String skyblockId) {
        if (stack == null || skyblockId == null) return false;
        String[] text = LORE.get(skyblockId.toUpperCase(Locale.ROOT));
        if (text == null || text.length < 2) return false;
        stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(text[0]));
        List<Component> lines = new ArrayList<>(text.length - 1);
        for (int i = 1; i < text.length; i++) {
            lines.add(LegacyText.parse(text[i]));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines, lines));
        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
        return true;
    }


    private static int refreshCounter;

    /**
     * Keeps every sim item the player carries on the CURRENT tooltip, the way Hypixel's server re-sends an item's
     * lore whenever it changes.
     *
     * <p>killer560's log, 2026-10-04 18:07: "[AutoIcePath] waiting: no shortbow ("Shortbow: Instantly shoots!" in
     * its lore) in the hotbar", over and over, with a Terminator in slot 5. The sim's save is reused
     * ({@link SimWorld}), so the player's inventory is too, and the Terminator in it had been built before the
     * Shortbow line was added on 2026-10-01: its saved lore, read out of the world's player file, is "Ability:
     * Salvation / Shoots 3 arrows at once. / (blank) / LEGENDARY BOW" - no Shortbow line. Fixing the table fixed
     * every Terminator handed out after that, and none already in his hotbar. Every 20 server ticks while the sim
     * is active, any stack whose id is in the table and whose lore reads differently from the table gets the table's
     * tooltip again. The Dungeon Breaker's "Charges" line is the one line the server changes on purpose
     * ({@link SimBreakerState}), so it is left out of the comparison and kept.
     */
    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++refreshCounter % 20 != 0 || !SimState.isActive()) return;
            for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
                for (ItemStack stack : sp.getInventory().getNonEquipmentItems()) {
                    refreshIfStale(stack);
                }
            }
        });
    }

    /** @return true when the stack's tooltip was out of date and has been rewritten. Server thread. */
    static boolean refreshIfStale(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
        if (id == null) return false;
        String[] text = LORE.get(id.toUpperCase(Locale.ROOT));
        if (text == null || text.length < 2) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> have = lore == null ? List.of() : lore.lines();
        boolean stale = have.size() != text.length - 1;
        String keptCharges = null;
        for (int i = 0; !stale && i < have.size(); i++) {
            String got = have.get(i).getString();
            String want = LegacyText.strip(text[i + 1]);
            if (got.equals(want)) continue;
            if (got.startsWith("Charges: ") && want.startsWith("Charges: ")) continue;
            stale = true;
        }
        if (!stale) return false;
        for (Component line : have) {
            if (line.getString().startsWith("Charges: ")) {
                keptCharges = line.getString();
            }
        }
        apply(stack, id);
        if (keptCharges != null) {
            ItemLore fresh = stack.get(DataComponents.LORE);
            if (fresh != null) {
                List<Component> lines = new ArrayList<>(fresh.lines());
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).getString().startsWith("Charges: ")) {
                        lines.set(i, LegacyText.parse("§8" + keptCharges));
                    }
                }
                stack.set(DataComponents.LORE, new ItemLore(lines, lines));
            }
        }
        return true;
    }
}
