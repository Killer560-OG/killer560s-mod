package com.killer560.hub.autodebuff;

import com.killer560.hub.autoroutes.ItemIdentity;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autopuzzles.AutoPuzzleUtil;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.witherdragons.P5State;
import com.killer560.hub.witherdragons.WitherDragon;
import com.killer560.hub.witherdragons.WitherDragonsFeature;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * M7 dragon debuff: prefire Last Breath, jump, Ice Spray on the spawn tick, then swap and hit it.
 *
 * <p>killer560's sequence, 2026-09-28: "prefire last breath then stop firing it before the drag spawns. Jump on
 * about 300ms pre it spawning then ice spray the tick it spawns. Then they swap to flay and hit it and follow it
 * until it either dies or goes outside the killbox. As mage its the same but mele weapon instead."
 *
 * <p><b>The timing is the feature.</b> {@code timeToSpawn} counts down in TICKS from 100, so 300ms is exactly six
 * of them, and there is no slack between the jump and the spray - if they drift apart the spray misses the spawn
 * tick and the whole sequence was pointless. That is why the lead is ping-aware rather than a hardcoded six, the
 * same choice Blood Camp already makes for the same reason.
 *
 * <p>Last Breath has to stop early, and that is not an arbitrary delay: the Ice Spray Wand must be in hand and
 * used ON the spawn tick, so the hotbar swap has to have already happened.
 *
 * <p><b>Classes.</b> Mage, Healer and Tank each have their own toggle. Archer and Berserker are never touched and
 * deliberately have no setting - they have no part in this. Healer and Tank finish with Flay (Soul Whip or
 * Flaming Flay, matched by item id so either tier works) paced to Flay's real 0.5s cooldown. Mage melees instead,
 * preferring Dark Claymore, then Midas' Sword, then a Hyperion - Chimera before Ultimate Wise, which are the same
 * item and differ only by the ultimate enchant, so the enchant is read rather than just the id.
 *
 * <p>Every interaction goes through {@link ActionGate}, so this can never share a tick with another automated
 * click, and it ticks on START_CLIENT_TICK because an interaction sent after the tick's own movement packet is an
 * order no vanilla client produces and an anticheat can see exactly that.
 *
 * <p><b>Not finished:</b> walking. Automatic pathing to the split, the melee classes following the dragon, and
 * the walk back to the P5 start on a death are not implemented here - AP3 exposes no public walk-to-point and
 * writing new movement for a boss fight is not something to improvise against the no-direct-movement rule. Until
 * that lands, this positions nothing and only acts when the dragon is already reachable.
 */
public final class AutoDebuffFeature {

    // Item ids, checked against hypixelskyblock.minecraft.wiki on 2026-09-28. SOUL_WHIP upgrades to FLAMING_FLAY
    // with Wither Essence and both carry the Flay ability, so either tier is accepted.
    private static final String LAST_BREATH = "LAST_BREATH";
    private static final String ICE_SPRAY_WAND = "ICE_SPRAY_WAND";
    private static final String[] FLAY_ITEMS = {"FLAMING_FLAY", "SOUL_WHIP"};

    /**
     * Every id here is compared through {@link ItemIdentity#family}, never with {@code equals}.
     *
     * <p>killer560 (2026-09-28): "if I set up a used item node with a Hyperion it should work with any of the
     * other wither blade variant [...] whether they are starred or not starred [...] and if they're recombed or
     * not. Same with the spirit sceptre and any other items that might get used." That applies here as much as
     * to a route node - this feature also picks an item out of the hotbar by id.
     *
     * <p>Two real gaps this closes, both checked against Hypixel's own item list (2026-09-28) rather than
     * assumed. A fragged Midas' Sword is {@code STARRED_MIDAS_SWORD}, so the plain {@code equals} never found
     * one; same for {@code STARRED_LAST_BREATH}. And the mage swap only ever looked for a Hyperion, so an
     * Astraea, Scylla or Valkyrie with the same ultimate enchant was ignored. The wither blades turn out to have
     * no starred form at all - only 30 items do - so the star handling and the family handling are genuinely two
     * separate fixes rather than one.
     *
     * <p>Recombobulating was never a factor: it changes an item's rarity, not its id.
     */
    /** Mage's melee preference, best first. The Hyperions differ only by ultimate enchant. */
    private static final String DARK_CLAYMORE = "DARK_CLAYMORE";
    private static final String MIDAS_SWORD = "MIDAS_SWORD";
    private static final String HYPERION = "HYPERION";
    private static final String ULTIMATE_CHIMERA = "ultimate_chimera";
    private static final String ULTIMATE_WISE = "ultimate_wise";

    /** Flay's real cooldown is 0.5s and costs no mana, so ten ticks is as fast as it legitimately goes. Clicking
     *  faster than the cooldown is precisely the part a hand could not produce. */
    private static final int FLAY_COOLDOWN_TICKS = 10;

    /** Vanilla's own swing rate, held to for the same reason. */
    private static final int MELEE_COOLDOWN_TICKS = 12;

    private static WitherDragon current;
    private static boolean sprayedThisSpawn;
    private static boolean jumpedThisSpawn;
    private static int jumpHeldUntilTick;
    private static int lastActionTick;
    private static int tickCounter;

    private AutoDebuffFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("AutoDebuffFeature.tick", AutoDebuffFeature::tick));
    }

    private static void tick(Minecraft client) {
        tickCounter++;
        AutoDebuffConfig cfg = AutoDebuffConfig.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        releaseJumpIfDue(client);
        if (!cfg.isEnabled()) {
            return;
        }
        // M7 only - killer560: "It doesn't work in f7 as there is no p5."
        if (!DungeonState.isInDungeon() || !isMasterFloorSeven() || !P5State.inP5()) {
            reset();
            return;
        }
        if (!classAllowed(P5State.selfClass(), cfg)) {
            return;
        }
        WitherDragon dragon = WitherDragonsFeature.priorityDragon();
        if (dragon == null) {
            reset();
            return;
        }
        if (dragon != current) {
            current = dragon;
            sprayedThisSpawn = false;
            jumpedThisSpawn = false;
        }
        LocalPlayer player = client.player;
        switch (dragon.state()) {
            case SPAWNING -> preSpawn(client, player, dragon, cfg);
            case ALIVE -> engage(client, player, dragon, cfg);
            case DEAD -> reset();
        }
    }

    /** Prefire, stop, jump, and spray on the spawn tick. */
    private static void preSpawn(Minecraft client, LocalPlayer player, WitherDragon dragon, AutoDebuffConfig cfg) {
        int ticks = dragon.timeToSpawn() - leadOffset(client, cfg);
        Vec3 aim = dragon.box.getCenter();

        if (ticks > cfg.getStopLastBreathTicks()) {
            if (holdItem(player, id -> sameItem(id, LAST_BREATH))) {
                aimAndUse(client, player, aim);
            }
            return;
        }
        // Stopped firing: get the wand in hand now, so the spray can go out on the spawn tick itself.
        holdItem(player, id -> sameItem(id, ICE_SPRAY_WAND));

        if (!jumpedThisSpawn && ticks <= cfg.getJumpLeadTicks() && player.onGround()) {
            pressJump(client);
            jumpedThisSpawn = true;
            return;
        }
        if (!sprayedThisSpawn && ticks <= 0 && aimAndUse(client, player, aim)) {
            sprayedThisSpawn = true;
        }
    }

    /** After it spawns: swap to the damage weapon and keep hitting while it is still in the killbox. */
    private static void engage(Minecraft client, LocalPlayer player, WitherDragon dragon, AutoDebuffConfig cfg) {
        if (!inKillbox(player, dragon)) {
            return;
        }
        boolean mage = P5State.selfClass() == DungeonClass.MAGE;
        if (mage && !cfg.isMeleeAfter() && sprayedThisSpawn) {
            return;
        }
        if (tickCounter - lastActionTick < (mage ? MELEE_COOLDOWN_TICKS : FLAY_COOLDOWN_TICKS)) {
            return;
        }
        boolean held = mage ? holdMageWeapon(player) : holdItem(player, AutoDebuffFeature::isFlayItem);
        if (held && aimAndUse(client, player, dragon.box.getCenter())) {
            lastActionTick = tickCounter;
        }
    }

    /**
     * Whether the dragon is still somewhere this class can actually hit it.
     *
     * <p>killer560: mage has "infinite range since they are on mage in a sence", so for mage this only asks
     * whether it is still a live target. The melee classes have to be on it, which is why they are the ones that
     * have to follow it.
     */
    private static boolean inKillbox(LocalPlayer player, WitherDragon dragon) {
        if (P5State.selfClass() == DungeonClass.MAGE) {
            return dragon.health() > 0f;
        }
        return dragon.box.distanceToSqr(player.getEyePosition()) <= 9.0;
    }

    private static boolean classAllowed(DungeonClass cls, AutoDebuffConfig cfg) {
        if (cls == null) {
            return false;
        }
        return switch (cls) {
            case MAGE -> cfg.isOnMage();
            case HEALER -> cfg.isOnHealer();
            case TANK -> cfg.isOnTank();
            // Never, and deliberately not a setting.
            case ARCHER, BERSERKER -> false;
        };
    }

    /** Ping turned into ticks, so that 300ms stays 300ms on a real connection. */
    private static int leadOffset(Minecraft client, AutoDebuffConfig cfg) {
        int offset = cfg.getManualOffsetTicks();
        if (cfg.isPingCompensation() && client.getConnection() != null && client.player != null) {
            var entry = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (entry != null && entry.getLatency() > 0) {
                offset += Math.round(entry.getLatency() / 50.0f);
            }
        }
        return offset;
    }

    private static boolean isFlayItem(String id) {
        for (String flay : FLAY_ITEMS) {
            if (sameItem(id, flay)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a held item's id is the wanted item, allowing for stars, fragging and variant blades. */
    private static boolean sameItem(String heldId, String wanted) {
        if (heldId == null) {
            return false;
        }
        String a = ItemIdentity.family(heldId);
        String c = ItemIdentity.family(wanted);
        return a != null && a.equals(c);
    }

    /** Mage's order: Dark Claymore, Midas' Sword, Hyperion with Chimera, Hyperion with Ultimate Wise. */
    private static boolean holdMageWeapon(LocalPlayer player) {
        if (holdItem(player, id -> sameItem(id, DARK_CLAYMORE)) || holdItem(player, id -> sameItem(id, MIDAS_SWORD))) {
            return true;
        }
        for (String ult : new String[]{ULTIMATE_CHIMERA, ULTIMATE_WISE}) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (sameItem(skyblockId(stack), HYPERION) && hasEnchant(stack, ult)) {
                    return select(player, slot);
                }
            }
        }
        return false;
    }

    /** Puts a matching hotbar item in hand, or reports there is none. Never opens the inventory. */
    private static boolean holdItem(LocalPlayer player, Predicate<String> match) {
        for (int slot = 0; slot < 9; slot++) {
            String id = skyblockId(player.getInventory().getItem(slot));
            if (id != null && match.test(id)) {
                return select(player, slot);
            }
        }
        return false;
    }

    private static boolean select(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
        return true;
    }

    /**
     * Jumps by holding the real jump key for a tick.
     *
     * <p>Not {@code jumpFromGround} and not a velocity write: the standing rule is discrete key presses only,
     * because Hypixel reconstructs which key combination could have produced a position delta and sets you back
     * when none can. A jump the game performs itself from a held key is a jump; one written into the player is a
     * position it cannot explain.
     */
    private static void pressJump(Minecraft client) {
        if (client.options != null) {
            client.options.keyJump.setDown(true);
            jumpHeldUntilTick = tickCounter + 1;
        }
    }

    private static void releaseJumpIfDue(Minecraft client) {
        if (jumpHeldUntilTick != 0 && tickCounter >= jumpHeldUntilTick && client.options != null) {
            client.options.keyJump.setDown(false);
            jumpHeldUntilTick = 0;
        }
    }

    /** Aims at a point and uses the held item, with the rotation carried on the use packet - the camera is not
     *  moved, and the yaw is deliberately NOT wrapped to 0-360, because Hypixel treats an uncapped running
     *  rotation as the normal signal and a wrapped one as a flag. Pitch is always held inside -90..+90. */
    private static boolean aimAndUse(Minecraft client, LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        Vec3 d = target.subtract(eye);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, flat));
        pitch = Math.max(-90f, Math.min(90f, pitch));
        return AutoPuzzleUtil.useItemRotated(client, player, yaw, pitch);
    }

    private static boolean hasEnchant(ItemStack stack, String enchantId) {
        CustomData data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag ench = data.copyTag().getCompoundOrEmpty("enchantments");
        return ench.contains(enchantId);
    }

    /** Hypixel's ExtraAttributes arrive as the item's custom_data; its "id" is the Skyblock item id. */
    private static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getStringOr("id", null);
    }

    private static boolean isMasterFloorSeven() {
        String floor = DungeonState.getFloor();
        return floor != null && floor.equalsIgnoreCase("M7");
    }

    private static void reset() {
        current = null;
        sprayedThisSpawn = false;
        jumpedThisSpawn = false;
    }
}
