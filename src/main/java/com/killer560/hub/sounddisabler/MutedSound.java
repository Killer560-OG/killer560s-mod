package com.killer560.hub.sounddisabler;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * The sounds the Sound Disabler tab can mute (killer560, 2026-11-02: "add a sound disabler to hide stuff like bonzo staff
 * sound effect"). Each row is matched by the sound event id the game plays, the same id a server sound packet and a
 * locally played sound both carry. All but {@link #BONZO_STAFF} mute EVERY use of their ids: a Minecraft sound has no
 * owner, so "an enderman" and "Voidgloom's enderman" are the same {@code entity.enderman.*} sound. The tooltips say so.
 *
 * <p>Bonzo's Staff is the one row matched more narrowly (see {@link SoundDisabler#isBonzoShot}): its shot is a ghast sound
 * played where the player holding the staff stands, and its balloon blast a firework sound played shortly after a shot,
 * so a real ghast, a Blaze Slayer demon's fireball or a firework somewhere else still plays. The ids come from the Hypixel
 * forum (the staff "is using" firework and ghast sounds) and match the 1.8 sounds Hypixel plays through ViaVersion
 * ({@code mob.ghast.*} -> {@code entity.ghast.*}, {@code fireworks.*} -> {@code entity.firework_rocket.*}). The other rows
 * are the sound hiders other Skyblock mods offer: Skyblocker's Mute Endermen / Blaze / Wolf / Spider sounds and SkyHanni's
 * Mute Phantom, plus the common dungeon complaints (explosions, bow spam, the F7 Withers, rain).
 *
 * <p>{@link #SKYBLOCK_WEATHER} (wxmute-1104) is the island weather Hypixel added in 0.27.2 (2026-10-06): every sound in
 * the game's Weather category plus the rain and lightning ids, only while {@code SkyblockGate} sees Skyblock. No public source
 * names the ids the newer weathers play (wiki, changelogs, Hypixel's server pack holds only music); Show Sound IDs names
 * them in chat with their category, and Custom Sound IDs takes any that still play.
 */
public enum MutedSound {
    BONZO_STAFF("bonzo_staff", "Bonzo's Staff", false),
    EXPLOSIONS("explosions", "Explosions", false),
    BOW_SHOTS("bow_shots", "Bow Shots", false),
    ENDERMEN("endermen", "Endermen", false),
    BLAZES("blazes", "Blazes", false),
    WOLVES("wolves", "Wolves", false),
    SPIDERS("spiders", "Spiders", false),
    PHANTOMS("phantoms", "Phantoms", false),
    WITHERS("withers", "Withers", false),
    RAIN_AND_THUNDER("rain_and_thunder", "Rain And Thunder", false),
    SKYBLOCK_WEATHER("skyblock_weather", "Skyblock Weather", false),
    /** Candidate test only: an arrow shot by a player holding Last Breath ({@link SoundDisabler#isLastBreath}). */
    LAST_BREATH("last_breath", "Last Breath", false),
    /** Candidate test only: the note block bass right after an "on cooldown" chat line ({@link SoundDisabler#cooldownSoundWindow}). */
    ABILITY_COOLDOWN("ability_cooldown", "Ability Cooldown", false);

    /** The key the config file uses. */
    public final String key;
    /** The row's name in the tab. */
    public final String label;
    /** Whether the row is on in a fresh config (none: killer560 2026-11-02 "dont have it on by default"). */
    public final boolean defaultOn;

    MutedSound(String key, String label, boolean defaultOn) {
        this.key = key;
        this.label = label;
        this.defaultOn = defaultOn;
    }

    /** Cached: {@link #values()} copies the array, and the matcher runs for every sound the game plays. */
    static final MutedSound[] ALL = values();

    /**
     * Whether {@code id} played in {@code source} is one of this row's sounds. For {@link #BONZO_STAFF} this is only the
     * candidate test (a ghast or firework sound); {@link SoundDisabler} decides whether that candidate was the staff, and
     * whether {@link #SKYBLOCK_WEATHER} applies where the player is.
     */
    boolean matches(Identifier id, SoundSource source) {
        if (this == SKYBLOCK_WEATHER && source == SoundSource.WEATHER) {
            return true;
        }
        if (!"minecraft".equals(id.getNamespace())) {
            return false;
        }
        String p = id.getPath();
        return switch (this) {
            case BONZO_STAFF -> isBonzoShotId(p) || isBonzoBlastId(p);
            case EXPLOSIONS -> p.equals("entity.generic.explode");
            case BOW_SHOTS -> p.equals("entity.arrow.shoot");
            case ENDERMEN -> p.startsWith("entity.enderman.");
            case BLAZES -> p.startsWith("entity.blaze.");
            case WOLVES -> p.startsWith("entity.wolf.") || p.startsWith("entity.wolf_");
            case SPIDERS -> p.startsWith("entity.spider.") || p.startsWith("entity.cave_spider.");
            case PHANTOMS -> p.startsWith("entity.phantom.");
            // "entity.wither." leaves out entity.wither_skeleton.*: only the boss sounds (F7's Withers, their skulls).
            case WITHERS -> p.startsWith("entity.wither.");
            case RAIN_AND_THUNDER -> p.startsWith("weather.rain") || p.startsWith("entity.lightning_bolt.");
            case SKYBLOCK_WEATHER -> p.startsWith("weather.") || p.startsWith("entity.lightning_bolt.");
            case LAST_BREATH -> p.equals("entity.arrow.shoot");
            case ABILITY_COOLDOWN -> p.equals("block.note_block.bass");
        };
    }

    /** A ghast sound: Hypixel's 1.8 {@code mob.ghast.fireball} (and its other ghast sounds) arrive as {@code entity.ghast.*}. */
    static boolean isBonzoShotId(String path) { return path.startsWith("entity.ghast."); }

    /** A firework blast or twinkle, near or far: the balloon's explosion. */
    static boolean isBonzoBlastId(String path) {
        return path.startsWith("entity.firework_rocket.blast") || path.startsWith("entity.firework_rocket.large_blast")
                || path.startsWith("entity.firework_rocket.twinkle");
    }
}
