package com.killer560.hub.abilitycooldown;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All of the automatic detection and every running countdown for {@link ItemAbility}.
 *
 * <p>Ported from SkyHanni {@code beta},
 * {@code features/itemabilities/abilitycooldown/ItemAbilityCooldown.kt} (fetched 2026-09-16). Three
 * independent detection paths, exactly as SkyHanni has them:
 * <ol>
 *   <li><b>Sound</b> - every sound id / pitch / volume triple below is SkyHanni's {@code onPlaySound}
 *       verbatim. Reached through the mod's existing {@code DungeonAlertsPackets.onSound} bridge (the same
 *       one {@code ragaxe} reuses) rather than a second sound mixin.</li>
 *   <li><b>Action bar</b> - Hypixel writes {@code -N Mana (AbilityName)} into the action bar whenever a mana
 *       ability fires; SkyHanni's {@code abilityUsePattern} is {@code ".*-\d+ Mana \((?<type>.*)\).*"} with
 *       the colour codes spelled out. Only the abilities SkyHanni gives an {@code abilityName} to are matched
 *       (the ones it notes "doesn't have a sound"), because those are the only real Hypixel ability names any
 *       reference mod actually confirms - nothing here invents one.</li>
 *   <li><b>Chat</b> - {@code Creeper Veil Activated!} for the Wither Cloak.</li>
 * </ol>
 *
 * <p><b>The 400 ms click gate.</b> SkyHanni's {@code ItemAbility.sound()} only starts the timer when the
 * player clicked an item within the last 400 ms, so a teammate firing the same ability next to you can never
 * start your countdown. Reproduced here off Fabric's {@code UseItemCallback}/{@code UseBlockCallback}
 * (see {@link AbilityCooldownFeature}); the window is a slider because it really is a ping-sensitive value.
 *
 * <p><b>Simplifications vs. SkyHanni, all deliberate:</b>
 * <ul>
 *   <li>SkyHanni draws two-phase timers over the hotbar item (a coloured "effect active" phase, then the
 *       rest of the cooldown). This draws one countdown per ability in a movable HUD list, so the
 *       Wither Shield's 5 s shield phase and Tactical Insertion's 3 s placed phase are folded into the one
 *       10 s / 20 s number. The numbers themselves are unchanged.</li>
 *   <li>SkyHanni's {@code recentlyHeld} (anything held in the last few seconds) is simplified to "the item
 *       in your main hand right now", used only to disambiguate sounds two different items share
 *       (Voodoo Doll vs. Wilted, Weird vs. Weirder Tuba, the flares, Totem of Corruption).</li>
 *   <li>Mage cooldown reduction is OFF by default and, when on, uses a class level you set yourself -
 *       nothing in this codebase reads your dungeon class LEVEL from the tab list yet
 *       ({@code PartyTracker.selfClass()} gives the class, not the level).</li>
 * </ul>
 */
public final class AbilityCooldownState {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-abilitycooldown");

    /** SkyHanni {@code abilityUsePattern}: {@code ".*§b-\d+ Mana \(§6(?<type>.*)§b\).*"}. Written here so the
     *  colour codes are optional - this mod's own {@code PlayerStatsFeature} learned the hard way that a real
     *  action bar does not always carry the codes the reference mod's regex assumed. */
    private static final Pattern ABILITY_USE =
            Pattern.compile("-[\\d,]+ Mana \\((?:\u00A7.)*([^\u00A7()]+?)(?:\u00A7.)*\\)");
    /** SkyHanni {@code onChat}: {@code "§dCreeper Veil §r§aActivated!"} (stripped of colour codes here). */
    private static final Pattern CREEPER_VEIL_ON = Pattern.compile("^Creeper Veil Activated!$");

    private static final Map<ItemAbility, Long> ENDS_AT = new EnumMap<>(ItemAbility.class);

    private static long lastItemClickMs = 0L;
    private static String lastActionBarAbility = "";

    private AbilityCooldownState() {
    }

    // ------------------------------------------------------------------ lifecycle

    public static void reset() {
        ENDS_AT.clear();
        lastItemClickMs = 0L;
        lastActionBarAbility = "";
    }

    /** Called from Fabric's use-item / use-block events: "you personally clicked something just now". */
    static void noteItemClick() {
        lastItemClickMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ timers

    /** Starts (or restarts) {@code ability} for its table cooldown, after the mage multiplier. */
    private static void activate(ItemAbility ability) {
        activate(ability, (long) (ability.baseCooldownMs() * multiplier(ability)));
    }

    private static void activate(ItemAbility ability, long durationMs) {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.shows(ability) || durationMs <= 0) {
            return;
        }
        ENDS_AT.put(ability, System.currentTimeMillis() + durationMs);
        LOGGER.info("[AbilityCooldown] {} started ({} ms)", ability.label(), durationMs);
    }

    /** SkyHanni's {@code ItemAbility.sound()} - only yours if you clicked an item inside the window. */
    private static void sound(ItemAbility ability) {
        long since = System.currentTimeMillis() - lastItemClickMs;
        if (lastItemClickMs == 0L || since >= AbilityCooldownConfig.getInstance().getClickWindowMs()) {
            return;
        }
        activate(ability);
    }

    public static boolean isOnCooldown(ItemAbility ability) {
        Long end = ENDS_AT.get(ability);
        return end != null && end > System.currentTimeMillis();
    }

    /** @return remaining ms, or 0 when ready. */
    public static long remainingMs(ItemAbility ability) {
        Long end = ENDS_AT.get(ability);
        if (end == null) {
            return 0L;
        }
        return Math.max(0L, end - System.currentTimeMillis());
    }

    /** Abilities currently counting down, soonest-ready first. */
    public static List<ItemAbility> running() {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        List<ItemAbility> out = new ArrayList<>();
        for (ItemAbility a : ItemAbility.values()) {
            if (cfg.shows(a) && isOnCooldown(a)) {
                out.add(a);
            }
        }
        out.sort((x, y) -> Long.compare(remainingMs(x), remainingMs(y)));
        return out;
    }

    /** Abilities that fired at least once this session and are now ready again - only used by the optional
     *  "Show When Ready" line, so a never-used ability never clutters the HUD. */
    public static List<ItemAbility> readyAgain() {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        List<ItemAbility> out = new ArrayList<>();
        for (Map.Entry<ItemAbility, Long> e : ENDS_AT.entrySet()) {
            if (cfg.shows(e.getKey()) && !isOnCooldown(e.getKey())) {
                out.add(e.getKey());
            }
        }
        out.sort((x, y) -> x.label().compareTo(y.label()));
        return out;
    }

    // ------------------------------------------------------------------ mage cooldown reduction

    /**
     * SkyHanni {@code ItemAbility.getMageCooldownReduction()}: in a dungeon, as a Mage, the base reduction is
     * 50% when you are the only one of your class ("unique class") and 25% otherwise, minus a further 1% for
     * every second class level. {@code WAND_OF_ATONEMENT} and {@code RAGNAROCK_AXE} are excluded by SkyHanni
     * because they are effects over time rather than real cooldowns.
     */
    private static double multiplier(ItemAbility ability) {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        if (!cfg.isMageReduction() || ability.ignoresMageReduction()) {
            return 1.0;
        }
        DungeonClass self = PartyTracker.selfClass();
        if (self != DungeonClass.MAGE) {
            return 1.0;
        }
        double m = 1.0 - (cfg.isMageUniqueClass() ? 0.5 : 0.25);
        m -= 0.01 * Math.floor(cfg.getMageClassLevel() / 2.0);
        return Math.max(0.0, m);
    }

    // ------------------------------------------------------------------ action bar

    /** Fed the raw (colour-coded) action bar text. */
    static void onActionBar(String raw) {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isActionBarDetection() || raw == null) {
            return;
        }
        Matcher m = ABILITY_USE.matcher(raw);
        if (!m.find()) {
            lastActionBarAbility = "";
            return;
        }
        String name = m.group(1).trim();
        // SkyHanni: the same line is re-sent every tick while the bar holds it, so only a CHANGE counts as
        // a new use.
        if (name.equals(lastActionBarAbility)) {
            return;
        }
        lastActionBarAbility = name;
        for (ItemAbility ability : ItemAbility.values()) {
            if (name.equals(ability.actionBarName())) {
                activate(ability);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ chat

    /** Wither Cloak. SkyHanni instead shows a purple "veil active" phase and starts the 10 s cooldown on the
     *  de-activation lines; this starts the table's 10 s cooldown right at activation, which is the same
     *  number without the second phase. */
    static void onChat(Component message) {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (CREEPER_VEIL_ON.matcher(plain).matches()) {
            activate(ItemAbility.WITHER_CLOAK);
        }
    }

    // ------------------------------------------------------------------ sounds

    /**
     * Every branch below is SkyHanni's {@code onPlaySound} verbatim (sound id, pitch, volume). Reached from
     * {@code DungeonAlertsPackets.onSound}, which is already on the client thread and already Skyblock-gated.
     */
    public static void onSoundPacket(ClientboundSoundPacket packet) {
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isSoundDetection()) {
            return;
        }
        Identifier id = packet.getSound().value().location();
        if (!"minecraft".equals(id.getNamespace())) {
            return;
        }
        String path = id.getPath();
        float pitch = packet.getPitch();
        float volume = packet.getVolume();
        ItemStack held = heldItem();
        String heldId = skyblockId(held);

        switch (path) {
            case "entity.zombie_villager.cure" -> {
                // Wither shield / impact, and Tactical Insertion's second phase.
                if (eq(pitch, 0.6984127f) && eq(volume, 1f)) {
                    Set<ItemAbility> scrolls = abilityScrolls(held);
                    if (scrolls.size() == 3) {
                        sound(ItemAbility.WITHER_IMPACT);
                    } else {
                        for (ItemAbility scroll : scrolls) {
                            sound(scroll);
                        }
                    }
                } else if (eq(pitch, 1.8888888f) && eq(volume, 0.7f)) {
                    // SkyHanni: TACTICAL_INSERTION.activate(null, 17_000) - the 17 s left after the 3 s placed
                    // phase. Not gated on the click window: it is the server answering your own insertion.
                    activate(ItemAbility.TACTICAL_INSERTION, 17_000L);
                }
            }
            case "block.lava.extinguish" -> {
                if (eq(pitch, 0.4920635f) && eq(volume, 1f)
                        && abilityScrolls(held).contains(ItemAbility.SHADOW_WARP_SCROLL)) {
                    sound(ItemAbility.SHADOW_WARP_SCROLL);
                }
            }
            case "block.lava.pop" -> {
                if (eq(pitch, 1f) && eq(volume, 1f)) {
                    sound(ItemAbility.FIRE_FURY_STAFF);
                } else if (eq(pitch, 0.7619048f) && eq(volume, 0.15f)) {
                    sound(ItemAbility.WAND_OF_ATONEMENT);
                }
            }
            case "entity.ender_dragon.growl" -> {
                if (eq(pitch, 1f) && eq(volume, 1f)) {
                    sound(ItemAbility.ICE_SPRAY_WAND);
                } else if (eq(pitch, 0.4920635f) && eq(volume, 2f)) {
                    sound(ItemAbility.ENRAGER);
                }
            }
            case "entity.enderman.teleport" -> {
                if (eq(pitch, 0.61904764f) && eq(volume, 1f)) {
                    sound(ItemAbility.GYROKINETIC_WAND_LEFT);
                }
                if (ItemAbility.SHADOW_FURY.matchesItem(heldId)) {
                    sound(ItemAbility.SHADOW_FURY);
                }
            }
            case "block.anvil.land" -> {
                if (eq(pitch, 0.4920635f) && eq(volume, 0.5f)) {
                    sound(ItemAbility.GIANTS_SWORD);
                }
            }
            case "entity.ghast.ambient" -> {
                if (eq(pitch, 0.4920635f) && eq(volume, 0.15f)) {
                    sound(ItemAbility.ATOMSPLIT_KATANA);
                }
            }
            case "entity.bat.hurt" -> {
                if (eq(volume, 0.1f)) {
                    sound(ItemAbility.STARLIGHT_WAND);
                }
            }
            case "entity.ghast.hurt" -> {
                if (eq(volume, 1f) && pitch >= 1.6f && pitch <= 1.7f) {
                    if (ItemAbility.VOODOO_DOLL.matchesItem(heldId)) {
                        sound(ItemAbility.VOODOO_DOLL);
                    } else if (ItemAbility.VOODOO_DOLL_WILTED.matchesItem(heldId)) {
                        sound(ItemAbility.VOODOO_DOLL_WILTED);
                    }
                }
            }
            case "entity.generic.explode" -> {
                if (eq(pitch, 1f) && eq(volume, 1f)
                        && abilityScrolls(held).contains(ItemAbility.IMPLOSION_SCROLL)) {
                    sound(ItemAbility.IMPLOSION_SCROLL);
                }
                if (eq(pitch, 4.047619f) && eq(volume, 0.2f)) {
                    sound(ItemAbility.GOLEM_SWORD);
                }
                if (eq(pitch, 0.4920635f) && eq(volume, 0.5f)) {
                    sound(ItemAbility.STAFF_OF_THE_VOLCANO);
                }
            }
            case "entity.zombie_villager.converted" -> {
                if (eq(pitch, 2f) && eq(volume, 0.3f)) {
                    sound(ItemAbility.END_STONE_SWORD);
                }
            }
            case "entity.wolf.pant" -> {
                if (eq(pitch, 1.3968254f) && eq(volume, 0.4f)) {
                    sound(ItemAbility.SOUL_ESOWARD);
                }
            }
            case "entity.piglin.angry" -> {
                if (eq(pitch, 2f) && eq(volume, 0.3f)) {
                    sound(ItemAbility.PIGMAN_SWORD);
                }
            }
            case "entity.ghast.shoot" -> {
                if (eq(pitch, 1f) && eq(volume, 0.3f)) {
                    sound(ItemAbility.EMBER_ROD);
                }
            }
            case "entity.elder_guardian.ambient" -> {
                if (eq(pitch, 2f) && eq(volume, 0.2f)) {
                    sound(ItemAbility.FIRE_FREEZE_STAFF);
                }
            }
            case "entity.generic.eat" -> {
                if (eq(pitch, 1f) && eq(volume, 1f)) {
                    sound(ItemAbility.STAFF_OF_THE_VOLCANO);
                } else if (eq(pitch, 0.4920635f) && eq(volume, 1f)) {
                    sound(ItemAbility.WAND_OF_STRENGTH);
                }
            }
            case "entity.generic.drink" -> {
                if (Math.abs(pitch - 1.8f) < 0.05f && eq(volume, 1f)) {
                    sound(ItemAbility.HOLY_ICE);
                }
            }
            case "entity.bat.ambient" -> {
                if (eq(pitch, 0.4920635f) && eq(volume, 1f)) {
                    sound(ItemAbility.ROYAL_PIGEON);
                }
            }
            case "item.flintandsteel.use" -> {
                // SkyHanni: TACTICAL_INSERTION.activate(DARK_PURPLE, 3_000) - the 3 s placed phase. Here the
                // whole 20 s runs from this moment; the cure sound above refreshes it to the real 17 s left.
                if (eq(pitch, 0.74603176f) && eq(volume, 1f)) {
                    sound(ItemAbility.TACTICAL_INSERTION);
                }
            }
            case "block.lever.click" -> {
                if (eq(pitch, 0.84126985f) && eq(volume, 0.5f)
                        && ItemAbility.TOTEM_OF_CORRUPTION.matchesItem(heldId)) {
                    sound(ItemAbility.TOTEM_OF_CORRUPTION);
                }
            }
            case "entity.firework_rocket.launch" -> {
                if (eq(pitch, 1f) && eq(volume, 3f)) {
                    if (ItemAbility.ALERT_FLARE.matchesItem(heldId)) {
                        sound(ItemAbility.ALERT_FLARE);
                    } else if (ItemAbility.SOS_FLARE.matchesItem(heldId)) {
                        sound(ItemAbility.SOS_FLARE);
                    }
                }
            }
            default -> {
                // Weird / Weirder Tuba: SkyHanni keys on "entity.wolf.death" at volume 0.5. On 26.1.2 the wolf
                // death sound is per-variant, so it is matched the same way ragaxe/RagAxeState matches
                // Ragnarock's cast sound - against every WolfSoundVariant's own death sound.
                if (eq(volume, 0.5f) && isWolfDeath(id)) {
                    if (ItemAbility.WEIRD_TUBA.matchesItem(heldId)) {
                        sound(ItemAbility.WEIRD_TUBA);
                    } else if (ItemAbility.WEIRDER_TUBA.matchesItem(heldId)) {
                        sound(ItemAbility.WEIRDER_TUBA);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean eq(float a, float b) {
        return Math.abs(a - b) < 1.0e-4f;
    }

    private static ItemStack heldItem() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    /** Same read {@code ragaxe/RagAxeState#skyblockId} uses - Hypixel's ExtraAttributes arrive as the item's
     *  {@code custom_data}, whose {@code id} is the Skyblock item id. */
    private static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    /** The ability scrolls applied to an item, as {@link ItemAbility} constants. Devonian reads exactly this
     *  list in {@code misc/WitherShieldTimer.kt} ({@code data.getList("ability_scroll")}). */
    private static Set<ItemAbility> abilityScrolls(ItemStack stack) {
        Set<ItemAbility> out = EnumSet.noneOf(ItemAbility.class);
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return out;
        }
        ListTag scrolls = data.copyTag().getListOrEmpty("ability_scroll");
        for (int i = 0; i < scrolls.size(); i++) {
            String name = scrolls.getStringOr(i, "");
            if (ItemAbility.WITHER_SHIELD_SCROLL.name().equals(name)) {
                out.add(ItemAbility.WITHER_SHIELD_SCROLL);
            } else if (ItemAbility.SHADOW_WARP_SCROLL.name().equals(name)) {
                out.add(ItemAbility.SHADOW_WARP_SCROLL);
            } else if (ItemAbility.IMPLOSION_SCROLL.name().equals(name)) {
                out.add(ItemAbility.IMPLOSION_SCROLL);
            }
        }
        return out;
    }

    /** Copied from {@code ragaxe/RagAxeState#isWolfDeath} - 26.1.2 has one death sound per wolf variant. */
    private static boolean isWolfDeath(Identifier id) {
        for (WolfSoundVariant variant : SoundEvents.WOLF_SOUNDS.values()) {
            if (variant.adultSounds().deathSound().value().location().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
