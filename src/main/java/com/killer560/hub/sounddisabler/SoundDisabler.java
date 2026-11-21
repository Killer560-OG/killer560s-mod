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
import com.killer560.hub.clicksound.mixin.SoundVolumeAccessor;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
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

    private static boolean layering;
    private static volatile int layers;
    private static volatile int scaled;
    private static volatile String lastScaledId = "";
    private static volatile int lastScaledPercent;
    /** Ids already judged (bounded) and, of those, the ones the game has no sound event for. */
    private static final Set<Identifier> SEEN = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> UNREGISTERED = ConcurrentHashMap.newKeySet();
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

    /** Whether {@code in} is muted (a mute, or a volume of 0%). Never throws: a sound that cannot be judged plays. */
    public static boolean mute(SoundInstance in) {
        return factor(in) <= 0f;
    }

    /**
     * The hook ClickSounds.replace calls for every sound: null when it is muted (or at 0%), else the same instance, its
     * volume already scaled in place. The engine clamps one instance's gain to 0..1 BEFORE the category volume, so above 100%
     * the first copy plays at full gain and a second copy of the sound (same place, pitch and source) adds the rest: 200% is
     * two full copies, a true doubling of amplitude that still scales with the category and master sliders. A delayed sound
     * ({@code delay} ticks) delays its extra copy the same.
     */
    public static SoundInstance adjust(SoundInstance in, int delay) {
        float f = factor(in);
        if (f <= 0f) return null;
        if (f == 1f || !(in instanceof AbstractSoundInstance)) return in;
        SoundVolumeAccessor raw = (SoundVolumeAccessor) in;
        float vol = raw.killer560$getVolume();
        float eff = Math.min(vol, 1f) * f;
        raw.killer560$setVolume(f < 1f ? (vol > 1f ? f : vol * f) : (vol > 1f ? vol : Math.min(eff, 1f)));
        if (eff > 1f && !in.isLooping()) {
            SoundInstance copy = new SimpleSoundInstance(in.getIdentifier(), in.getSource(), eff - 1f, raw.killer560$getPitch(),
                    SoundInstance.createUnseededRandom(), false, 0, in.getAttenuation(), in.getX(), in.getY(), in.getZ(),
                    in.isRelative());
            SoundManager sounds = Minecraft.getInstance().getSoundManager();
            layering = true;
            try {
                if (delay > 0) sounds.playDelayed(copy, delay); else sounds.play(copy);
            } finally {
                layering = false;
            }
            layers++;
        }
        scaled++;
        lastScaledId = in.getIdentifier().toString();
        lastScaledPercent = Math.round(f * 100f);
        return in;
    }

    /** The Custom Sounds screen's Preview button: plays {@code id} at {@code percent} (two copies above 100%), hook bypassed. */
    public static void preview(Identifier id, int percent) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        float v = percent / 100f;
        layering = true;
        try {
            for (float left = v; left > 0f; left -= 1f) {
                sounds.play(SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(id), 1f,
                        Math.min(left, 1f)));
            }
        } finally {
            layering = false;
        }
    }

    /** True while an extra copy of a loud sound is being played, so the hook leaves it alone. */
    public static boolean layering() { return layering; }

    /** Every id that played while the feature was on and is not a registered sound event (Hypixel's own), newest bounded. */
    public static Set<Identifier> unregisteredSeen() { return UNREGISTERED; }

    /**
     * The volume factor for {@code in}: 0 or less when it is muted, 1 when untouched. Preset rows (muted ones first win)
     * multiply by their slider while Volume Sliders is on, then the sound's own entry from the Custom Sounds screen.
     */
    private static float factor(SoundInstance in) {
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        if (!cfg.isEnabled() || in == null || layering) return 1f;
        Identifier id;
        try {
            id = in.getIdentifier();
        } catch (RuntimeException e) {
            return 1f;
        }
        if (id == null) return 1f;
        checked++;
        if (SEEN.size() < 4096 && SEEN.add(id) && BuiltInRegistries.SOUND_EVENT.get(id).isEmpty()) {
            UNREGISTERED.add(id);
        }
        String row = null;
        float f = 1f;
        SoundSource source = null;
        try {
            source = in.getSource();
            boolean sliders = cfg.isVolumeSliders();
            for (MutedSound s : MutedSound.ALL) {
                boolean muteRow = cfg.isOn(s);
                if (!muteRow && (!sliders || cfg.getPresetPercent(s) == 100) || !s.matches(id, source)) {
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
                if (!muteRow) {
                    f *= cfg.getPresetPercent(s) / 100f;
                    continue;
                }
                row = s.key;
                MUTED_BY_ROW[s.ordinal()]++;
                break;
            }
            SoundDisablerConfig.Setting own = row == null ? cfg.sound(id) : null;
            if (own != null) {
                if (own.muted()) {
                    row = "custom";
                    mutedCustom++;
                } else {
                    f *= own.percent() / 100f;
                }
            }
        } catch (RuntimeException e) {
            row = null;
            f = 1f;
        }
        if (cfg.isShowIds()) {
            show(id, source, row != null);
        }
        if (row == null) return f;
        muted++;
        lastMutedId = id.toString();
        lastMutedRow = row;
        return 0f;
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

    /** Sounds whose volume was scaled, extra copies started for sounds above 100%, and the last scaled sound (testkit evidence). */
    public static int scaled() { return scaled; }

    public static int layers() { return layers; }

    public static String lastScaledId() { return lastScaledId; }

    public static int lastScaledPercent() { return lastScaledPercent; }

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
