package com.killer560.hub.ragaxe;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one Ragnarock cast detector and the three timers it drives. Detection is Odin's
 * ({@code features/impl/skyblock/Ragnarock.kt}): a wolf <i>death</i> sound at pitch exactly
 * {@value #CAST_PITCH} while holding a {@code RAGNAROCK_AXE}. NoammAddons' copy
 * ({@code features/impl/dungeon/Ragnarock.kt}, local C:\Users\Hunter\noammaddonsmod) is the same check with
 * the pitch comparison inverted, which is a bug there - Odin's sense is used.
 * <p>
 * Ability numbers, all from hypixelskyblock.minecraft.wiki/w/Ragnarock ("RIGHT CLICK - Mana Cost: 500 -
 * Cooldown: 20s - Begin a channel. After not taking damage for 3s, gain 1.5x this weapon's Strength for 10s"):
 * <ul>
 * <li>{@link #CHANNEL_MS} 3000 - the wind-up. Taking damage inside it produces "Ragnarock was cancelled due
 * to (being hit|taking damage)!" and no buff.
 * <li>{@link #BUFF_MS} 10000 - the strength buff, starting when the channel completes.
 * <li>Cooldown 20 s from the cast, configurable ({@link RagAxeConfig#getCooldownSeconds()}) because the client
 * cannot see a player's ability-cooldown reduction: Hypixel sends no cooldown value to the client, and nothing
 * in Odin/NoammAddons/Skytils/SkyHanni reads one, so the timer ASSUMES the base 20 s unless the slider is
 * moved. Mana cost is not tracked at all for the same reason (a failed cast for lack of mana simply never
 * produces the cast sound, so no timer starts).
 * </ul>
 * <p>
 * UNVERIFIED against a live game: whether the cast sound marks the START of the channel (this mod's default,
 * and the only reading consistent with a cancel message arriving after a "Casted Rag" alert) or the moment the
 * buff applies. {@link RagAxeConfig#isSoundIsBuffStart()} flips the model if a live run shows otherwise; with
 * it on, the channel line never shows and the buff starts at the sound (which is exactly what the old
 * Dungeon Alerts Ragnarock timer did).
 */
public final class RagAxeState {

    static final long CHANNEL_MS = 3_000L;
    static final long BUFF_MS = 10_000L;
    /** Odin: the ability's own wolf-death sound pitch. */
    private static final float CAST_PITCH = 1.4920635f;
    private static final Pattern CANCEL =
            Pattern.compile("Ragnarock was cancelled due to (?:being hit|taking damage)!");
    private static final Pattern STRENGTH = Pattern.compile("Strength: \\+(\\d+)");

    private static long buffStartMs = 0L;
    private static long buffEndMs = 0L;
    private static long cooldownEndMs = 0L;
    private static int pendingStrength = 0;
    private static boolean strengthSent = false;
    private static boolean endAlerted = false;
    private static boolean readyAlerted = false;

    private RagAxeState() {
    }

    static void reset() {
        buffStartMs = 0L;
        buffEndMs = 0L;
        cooldownEndMs = 0L;
        pendingStrength = 0;
        strengthSent = false;
        endAlerted = false;
        readyAlerted = false;
    }

    // ------------------------------------------------------------------ detection

    /** From {@code DungeonAlertsPackets.onSound} (main thread, already Skyblock-gated). */
    static void onSoundPacket(ClientboundSoundPacket packet) {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!cfg.isEnabled() || player == null || packet.getPitch() != CAST_PITCH) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!"RAGNAROCK_AXE".equals(skyblockId(held)) || !isWolfDeath(packet.getSound().value().location())) {
            return;
        }
        long now = System.currentTimeMillis();
        pendingStrength = (int) (loreStrength(held) * 1.5);
        buffStartMs = cfg.isSoundIsBuffStart() ? now : now + CHANNEL_MS;
        buffEndMs = buffStartMs + BUFF_MS;
        cooldownEndMs = now + (long) (cfg.getCooldownSeconds() * 1000f);
        strengthSent = false;
        endAlerted = false;
        readyAlerted = false;
        RagAxeFeature.LOGGER.info("[RagAxe] Cast detected (strength +{}, channel {} ms, cooldown {} s)",
                pendingStrength, cfg.isSoundIsBuffStart() ? 0 : CHANNEL_MS, cfg.getCooldownSeconds());
        if (cfg.isCastAlert()) {
            RagAxeFeature.alert("§aCasted Rag");
        }
    }

    /** Cancel line - the channel (and therefore the buff) is gone; the cooldown already started at the cast
     *  and keeps running, so it is deliberately left alone. */
    static void onChat(Component message) {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        // Deliberately NOT gated on isCancelAlert(): that setting only controls the title/sound. The state
        // clear has to happen either way, or a cancelled rag would still run the buff timer down and still
        // announce "Gained strength" (and party-announce it) 3 s later for strength that was never gained.
        if (!cfg.isEnabled()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (!CANCEL.matcher(plain).matches()) {
            return;
        }
        RagAxeFeature.LOGGER.info("[RagAxe] Ragnarock cancelled");
        buffStartMs = 0L;
        buffEndMs = 0L;
        pendingStrength = 0;
        strengthSent = true;
        endAlerted = true;
        if (cfg.isCancelAlert()) {
            RagAxeFeature.alert("§cRagnarock Cancelled!");
        }
    }

    // ------------------------------------------------------------------ tick

    static void tick() {
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        if (!cfg.isEnabled() || !SkyblockGate.allows()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!strengthSent && buffStartMs > 0 && now >= buffStartMs) {
            // Announced when the channel actually completes, so a cancelled rag never claims strength.
            strengthSent = true;
            if (cfg.isStrengthMessage()) {
                ModChat.send("Ragnarock", ModChat.text("Gained strength: "),
                        ModChat.value(String.valueOf(pendingStrength)));
                if (cfg.isAnnounceStrength()) {
                    RagAxeFeature.sendPartyChat("Gained strength from Ragnarock: " + pendingStrength);
                }
            }
        }
        if (!endAlerted && buffEndMs > 0 && now >= buffEndMs) {
            endAlerted = true;
            RagAxeFeature.LOGGER.info("[RagAxe] Ragnarock buff ended");
            if (cfg.isEndAlert()) {
                RagAxeFeature.alert("§cRagnarock Ended");
            }
        }
        if (!readyAlerted && cooldownEndMs > 0 && now >= cooldownEndMs) {
            readyAlerted = true;
            if (cfg.isReadyAlert()) {
                RagAxeFeature.alert("§aRagnarock Ready");
            }
        }
    }

    // ------------------------------------------------------------------ HUD queries (ms left, 0 = inactive)

    public static long channelRemainingMs() {
        long left = buffStartMs - System.currentTimeMillis();
        return buffStartMs > 0 && left > 0 ? left : 0L;
    }

    public static long buffRemainingMs() {
        long now = System.currentTimeMillis();
        if (buffEndMs <= 0 || now < buffStartMs || now >= buffEndMs) {
            return 0L;
        }
        return buffEndMs - now;
    }

    public static long cooldownRemainingMs() {
        long left = cooldownEndMs - System.currentTimeMillis();
        return cooldownEndMs > 0 && left > 0 ? left : 0L;
    }

    public static int lastStrength() {
        return pendingStrength;
    }

    // ------------------------------------------------------------------ item helpers (Odin ItemUtils)

    private static boolean isWolfDeath(Identifier id) {
        for (WolfSoundVariant variant : SoundEvents.WOLF_SOUNDS.values()) {
            if (variant.adultSounds().deathSound().value().location().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String skyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    /** Odin: the buff is 1.5x the axe's own "Strength: +N" lore line. */
    private static int loreStrength(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && plain.startsWith("Strength:")) {
                Matcher m = STRENGTH.matcher(plain);
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            }
        }
        return 0;
    }
}
