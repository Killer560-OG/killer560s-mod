package com.killer560.hub.sounddisabler;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Sound Disabler (killer560, 2026-11-02): decides whether a sound the game is about to play is muted. Asked by
 * {@code ClickSounds.replace}, which {@code SoundManagerClickMixin} runs at the head of {@code SoundManager.play} and
 * {@code SoundManager.playDelayed}: every sound a server packet asks for ({@code ClientPacketListener.handleSoundEvent} /
 * {@code handleSoundEntityEvent} go through {@code ClientLevel.playSound} into those two) and every sound the client plays
 * itself passes there, and a muted one is dropped before it reaches the sound engine. Nothing is done to any packet:
 * the server, the anticheat and every other feature that reads the sound packet (Secret Sound, Dungeon Alerts, Auto Routes'
 * awaits) see exactly what they did before. Ticking sounds ({@code queueTickingSound}: minecarts, bees, the elytra) do
 * not pass there and are not muted.
 */
public final class SoundDisabler {

    /** A shot is the staff's when a player holding it stands within this distance of where the sound plays (blocks). */
    static final double SHOT_RADIUS = 4.0;
    /** A firework blast this soon after a muted staff shot ... */
    static final long BLAST_WINDOW_NANOS = 4_000_000_000L;
    /** ... and this close to where the shot was is the shot's balloon exploding (blocks). */
    static final double BLAST_RADIUS = 48.0;
    /** Show Sound IDs says an id at most this often. */
    private static final long SHOW_EVERY_NANOS = 2_000_000_000L;

    /** The note block bass of an on-cooldown ability arrives within this of its chat line. */
    static final long COOLDOWN_WINDOW_NANOS = 600_000_000L;
    private static volatile long lastCooldownNanos = Long.MIN_VALUE / 2;
    private static volatile long lastShotNanos = Long.MIN_VALUE / 2;
    private static volatile double shotX;
    private static volatile double shotY;
    private static volatile double shotZ;

    private static volatile int checked;
    private static volatile int muted;
    private static volatile String lastMutedId = "";
    private static volatile String lastMutedRow = "";
    private static final int[] MUTED_BY_ROW = new int[MutedSound.ALL.length];
    private static volatile int mutedCustom;
    private static volatile int bonzoCandidatesPassed;
    private static final Map<String, Long> SHOWN = new ConcurrentHashMap<>();

    private SoundDisabler() {
    }

    /** Whether {@code in} is muted. Never throws: a sound that cannot be judged plays. */
    public static boolean mute(SoundInstance in) {
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        if (!cfg.isEnabled() || in == null) return false;
        Identifier id;
        try {
            id = in.getIdentifier();
        } catch (RuntimeException e) {
            return false;
        }
        if (id == null) return false;
        checked++;
        String row = null;
        SoundSource source = null;
        try {
            source = in.getSource();
            for (MutedSound s : MutedSound.ALL) {
                if (!cfg.isOn(s) || !s.matches(id, source)) {
                    continue;
                }
                if (s == MutedSound.SKYBLOCK_WEATHER && !SkyblockGate.isOnSkyblock()) {
                    continue;
                }
                if (s == MutedSound.BONZO_STAFF && !isBonzo(in, id.getPath())) {
                    bonzoCandidatesPassed++;
                    continue;
                }
                if (s == MutedSound.LAST_BREATH && !holderNear(in.getX(), in.getY(), in.getZ(), SoundDisabler::isLastBreath)) {
                    continue;
                }
                if (s == MutedSound.ABILITY_COOLDOWN && !cooldownSoundWindow()) {
                    continue;
                }
                row = s.key;
                MUTED_BY_ROW[s.ordinal()]++;
                break;
            }
            if (row == null && cfg.customCount() > 0 && cfg.customMatches(id.toString())) {
                row = "custom";
                mutedCustom++;
            }
        } catch (RuntimeException e) {
            row = null;
        }
        if (cfg.isShowIds()) {
            show(id, source, row != null);
        }
        if (row == null) return false;
        muted++;
        lastMutedId = id.toString();
        lastMutedRow = row;
        return true;
    }

    /** A ghast sound where a Bonzo's Staff holder stands, or a firework blast just after one and near it. */
    private static boolean isBonzo(SoundInstance in, String path) {
        double x = in.getX();
        double y = in.getY();
        double z = in.getZ();
        if (MutedSound.isBonzoShotId(path)) {
            if (!isBonzoShot(x, y, z)) return false;
            lastShotNanos = System.nanoTime();
            shotX = x;
            shotY = y;
            shotZ = z;
            return true;
        }
        if (System.nanoTime() - lastShotNanos > BLAST_WINDOW_NANOS) return false;
        double dx = x - shotX;
        double dy = y - shotY;
        double dz = z - shotZ;
        return dx * dx + dy * dy + dz * dz <= BLAST_RADIUS * BLAST_RADIUS;
    }

    /** Whether a player holding a Bonzo's Staff (or the Starred one) stands within {@link #SHOT_RADIUS} of the sound. */
    static boolean isBonzoShot(double x, double y, double z) {
        return holderNear(x, y, z, SoundDisabler::isBonzoStaff);
    }

    /** Whether a player whose main-hand item passes {@code held} stands within {@link #SHOT_RADIUS} of the sound. */
    private static boolean holderNear(double x, double y, double z, Predicate<ItemStack> held) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.distanceToSqr(x, y, z) <= SHOT_RADIUS * SHOT_RADIUS && held.test(p.getMainHandItem())) return true;
        }
        return false;
    }

    /** Hypixel's {@code LAST_BREATH} / {@code STARRED_LAST_BREATH} by Skyblock id, or by name when the id is not sent. */
    static boolean isLastBreath(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = ItemNbt.view(stack);
        String sbId = tag == null ? null : tag.getStringOr("id", null);
        if (sbId != null) return sbId.equals("LAST_BREATH") || sbId.equals("STARRED_LAST_BREATH");
        return stack.getHoverName().getString().contains("Last Breath");
    }

    /** Subscribed once at start-up: remembers when Hypixel last said an ability is on cooldown. */
    public static void register() {
        ChatObserver.subscribe(m -> {
            if (SoundDisablerConfig.getInstance().isOn(MutedSound.ABILITY_COOLDOWN)
                    && ChatObserver.strip(m).startsWith("This ability is on cooldown for ")) {
                lastCooldownNanos = System.nanoTime();
            }
        });
    }

    /** A note block bass within {@link #COOLDOWN_WINDOW_NANOS} after the cooldown line is the cooldown's own sound. */
    static boolean cooldownSoundWindow() {
        return System.nanoTime() - lastCooldownNanos <= COOLDOWN_WINDOW_NANOS;
    }

    /** Test hook: the cooldown chat line just arrived. */
    public static void noteCooldownForTest() { lastCooldownNanos = System.nanoTime(); }

    /** Hypixel's {@code BONZO_STAFF} / {@code STARRED_BONZO_STAFF} by Skyblock id, or by name when the id is not sent. */
    static boolean isBonzoStaff(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = ItemNbt.view(stack);
        String sbId = tag == null ? null : tag.getStringOr("id", null);
        if (sbId != null) return sbId.equals("BONZO_STAFF") || sbId.equals("STARRED_BONZO_STAFF");
        return stack.getHoverName().getString().contains("Bonzo's Staff");
    }

    /**
     * Show Sound IDs: the id and its category in chat (once per {@link #SHOW_EVERY_NANOS} per id), marked when it was muted;
     * clicking the id copies it, for the Custom Sound IDs box (wxmute-1104: how an unknown Skyblock event sound is found).
     */
    private static void show(Identifier id, SoundSource source, boolean wasMuted) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        String key = id.toString();
        long now = System.nanoTime();
        Long last = SHOWN.get(key);
        if (last != null && now - last < SHOW_EVERY_NANOS) return;
        SHOWN.put(key, now);
        if (SHOWN.size() > 512) {
            SHOWN.clear();
        }
        ModChat.send("Sound Disabler", ModChat.value(key).withStyle(st -> st.withClickEvent(new ClickEvent.CopyToClipboard(key))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy")))),
                ModChat.dim(source == null ? "" : " [" + source.getName() + "]"),
                wasMuted ? ModChat.dim(" (muted)") : ModChat.text(""));
    }


    /** Sounds judged while the feature was on. */
    public static int checked() { return checked; }

    /** Sounds muted, every row and the custom list together. */
    public static int muted() { return muted; }

    /** Sounds muted by row {@code key} ("custom" for the Custom Sound IDs list), 0 for an unknown key. */
    public static int muted(String key) {
        if ("custom".equals(key)) return mutedCustom;
        for (MutedSound s : MutedSound.ALL) {
            if (s.key.equals(key)) return MUTED_BY_ROW[s.ordinal()];
        }
        return 0;
    }

    /** Ghast and firework sounds the Bonzo's Staff row let through because they were not the staff's. */
    public static int bonzoCandidatesPassed() { return bonzoCandidatesPassed; }

    /** The id of the last muted sound, "" before any. */
    public static String lastMutedId() { return lastMutedId; }

    /** The row that muted the last muted sound, "" before any. */
    public static String lastMutedRow() { return lastMutedRow; }

    /** Forgets the last staff shot, so a test's firework blast is judged on its own. */
    public static void forgetShot() { lastShotNanos = Long.MIN_VALUE / 2; }
}
