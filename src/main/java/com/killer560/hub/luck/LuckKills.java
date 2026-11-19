package com.killer560.hub.luck;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordinary mob and sea creature kills for How Lucky Are You. Hypixel sends no line for a mob kill, so a kill is a mob he
 * hit (Fabric's attack callback: his own melee hit, the packet his client sends) that then dies, or, with Mob Kills set to
 * Within 8 Blocks, any named mob that dies that close (ability and bow kills, and others' kills near him). A mob is named
 * by its Hypixel nametag, "[Lv73] Zealot 13,000/13,000❤", on the mob itself or on the armor stand Hypixel puts over it
 * (the stand right after the mob's id, like Mob ESP reads it), and looked up in the table by name and level.
 *
 * <p>Death: the mob's health reaches 0 ({@code isDeadOrDying}), its nametag reads 0❤, or - for a mob he hit himself in the
 * last 3 s - it is removed. Each mob counts once. Looting is the held weapon's ({@code enchantments.looting} in its
 * Skyblock data) when the death is seen, as the Looting page says: the weapon held when the mob dies.
 *
 * <p>"Hit by me" is also set (luckcount-1118; before it only melee set it, so kills by Implosion, Spirit Sceptre and the
 * other abilities, and by arrows, were never counted: 86 of ~6,100 combo kills in his logs of 11-16..18): (a) Hypixel's
 * line "Your Implosion hit 4 enemies for 36,865,522.3 damage." marks the 4 nearest named mobs within the ability's reach
 * (read on the next tick, at most once per 3 ticks), (b) a bow or crossbow use marks the named mobs on the look ray (up to
 * 3, an arrow volley). A mob counts once (COUNTED remembers the last 256 ids for 20 s, so a corpse hit again is not a new
 * kill).
 */
final class LuckKills {

    private static final Logger LOGGER = ModLog.get("killer560smod-luck");
    /** "[Lv73] Zealot 13,000/13,000❤", "Sea Guardian 5k❤", "[Lv250] ♆ Lord Jawbus 100M/100M❤". Group 1 level, 2 name, 3 health. */
    static final Pattern NAMETAG = Pattern.compile("^(?:\\[Lv(\\d{1,4})\\]\\s+)?(?:[^A-Za-z0-9\\[]{1,3}\\s+)?"
            + "([A-Za-z][A-Za-z0-9' .\\-]{0,40}?)\\s+([\\d.,]{1,12}[kKmMbB]?)(?:/[\\d.,]{1,12}[kKmMbB]?)?❤$");
    private static final long HIT_MEMORY_MS = 30_000;
    private static final long REMOVED_AFTER_HIT_MS = 3_000;
    private static final double NEAR = 8.0;
    private static final int MAX_TRACKED = 96;
    /** "Your Implosion hit 4 enemies for 36,865,522.3 damage." / "Your Guided Sheep hit 1 enemy for 9.9M damage." */
    static final Pattern ABILITY = Pattern.compile(
            "^Your ([A-Za-z][A-Za-z' ]{1,30}) hit (\\d{1,3}) enem(?:y|ies) for [\\d,.]{1,30}[kKmMbBtT]? damage\\.$");
    private static final double ABILITY_RANGE = 24.0;
    private static final double IMPLOSION_RANGE = 9.0;
    private static final double SHOT_RANGE = 50.0;
    private static final int MARK_GAP_TICKS = 3;
    private static final long COUNTED_MS = 20_000;

    private static final class Tracked {
        final int id;
        long lastHitMs;
        boolean hitByMe;
        String name;
        int level;
        boolean zeroTag;

        Tracked(int id) {
            this.id = id;
        }
    }

    // Client thread only.
    private static final Map<Integer, Tracked> TRACKED = new LinkedHashMap<>();
    private static int ticks;
    /** Enemies the last ability lines said they hit, marked on the next tick; reach of the widest ability. */
    private static int pendingHits;
    private static double pendingRange;
    private static int lastMarkTick = -100;
    /** Mob ids counted lately (bounded), so a corpse hit again is not counted twice. */
    private static final Map<Integer, Long> COUNTED = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > 256;
        }
    };
    /** Test hooks. */
    static volatile int killsCounted;
    static volatile String lastKill = "";

    private LuckKills() {
    }

    static void register() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            try {
                if (level.isClientSide() && player == Minecraft.getInstance().player && entity instanceof LivingEntity
                        && !(entity instanceof Player) && !(entity instanceof ArmorStand) && enabled()) {
                    mark(Minecraft.getInstance(), entity);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Luck] Attack hook failed", e);
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            try {
                if (level.isClientSide() && player == Minecraft.getInstance().player && enabled()
                        && (player.getMainHandItem().is(Items.BOW) || player.getMainHandItem().is(Items.CROSSBOW))
                        && ticks - lastMarkTick >= 2) {
                    lastMarkTick = ticks;
                    markShot(Minecraft.getInstance());
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Luck] Use hook failed", e);
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LuckKills.onTick", LuckKills::onTick));
    }

    /** An ability line "Your X hit N enemies": the N nearest named mobs in its reach are his hits (next tick). */
    static void onAbilityLine(String ability, int n) {
        if (enabled()) {
            pendingHits = Math.min(80, pendingHits + n);
            pendingRange = Math.max(pendingRange, "Implosion".equals(ability) ? IMPLOSION_RANGE : ABILITY_RANGE);
        }
    }

    /** His hit on {@code e}: tracked as a mob he hit now (nothing for a mob already counted). */
    private static void mark(Minecraft mc, Entity e) {
        Long done = COUNTED.get(e.getId());
        if (done != null && System.currentTimeMillis() - done < COUNTED_MS) {
            return;
        }
        Tracked t = track(e.getId());
        t.lastHitMs = System.currentTimeMillis();
        t.hitByMe = true;
        refreshName(mc, e, t);
    }

    private static boolean mobLike(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand);
    }

    private static void markNearest(Minecraft mc, int n, double range) {
        Vec3 me = mc.player.position();
        List<Entity> found = new ArrayList<>(mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(range),
                LuckKills::mobLike));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
        for (Entity e : found) {
            if (n <= 0) {
                break;
            }
            if (e.distanceToSqr(me) <= range * range && nametag(mc, e) != null) {
                mark(mc, e);
                n--;
            }
        }
    }

    /** An arrow volley: up to 3 named mobs on the look ray, nearest first. */
    private static void markShot(Minecraft mc) {
        Vec3 from = mc.player.getEyePosition();
        Vec3 to = from.add(mc.player.getLookAngle().scale(SHOT_RANGE));
        List<Entity> found = new ArrayList<>(mc.level.getEntities(mc.player, new AABB(from, to).inflate(2.0),
                e -> mobLike(e) && e.getBoundingBox().inflate(1.0).clip(from, to).isPresent()));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        int left = 3;
        for (Entity e : found) {
            if (left > 0 && nametag(mc, e) != null) {
                mark(mc, e);
                left--;
            }
        }
    }

    private static boolean enabled() {
        LuckConfig cfg = LuckConfig.getInstance();
        return cfg.isEnabled() && cfg.getMobKills() != LuckConfig.MobKills.OFF
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    private static Tracked track(int id) {
        Tracked t = TRACKED.get(id);
        if (t == null) {
            if (TRACKED.size() >= MAX_TRACKED) {
                Iterator<Integer> it = TRACKED.keySet().iterator();
                it.next();
                it.remove();
            }
            t = new Tracked(id);
            TRACKED.put(id, t);
        }
        return t;
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !enabled()) {
            TRACKED.clear();
            COUNTED.clear();
            pendingHits = 0;
            return;
        }
        ticks++;
        if (pendingHits > 0 && ticks - lastMarkTick >= MARK_GAP_TICKS) {
            lastMarkTick = ticks;
            markNearest(mc, pendingHits, pendingRange);
            pendingHits = 0;
            pendingRange = 0;
        }
        long now = System.currentTimeMillis();
        if (LuckConfig.getInstance().getMobKills() == LuckConfig.MobKills.NEARBY && ticks % 5 == 0) {
            AABB box = mc.player.getBoundingBox().inflate(NEAR);
            for (Entity e : mc.level.getEntities(mc.player, box, e -> e instanceof LivingEntity
                    && !(e instanceof Player) && !(e instanceof ArmorStand))) {
                Tracked t = TRACKED.get(e.getId());
                if (t == null) {
                    String[] tag = nametag(mc, e);
                    if (tag == null) {
                        continue;
                    }
                    t = track(e.getId());
                }
                t.lastHitMs = Math.max(t.lastHitMs, now);
            }
        }
        for (Iterator<Tracked> it = TRACKED.values().iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            Entity e = mc.level.getEntity(t.id);
            if (e == null || e.isRemoved()) {
                if (t.hitByMe && now - t.lastHitMs <= REMOVED_AFTER_HIT_MS) {
                    kill(mc, t, "removed right after your hit");
                } else if (t.zeroTag) {
                    kill(mc, t, "nametag read 0 health");
                }
                it.remove();
                continue;
            }
            refreshName(mc, e, t);
            if ((e instanceof LivingEntity le && le.isDeadOrDying()) || t.zeroTag) {
                kill(mc, t, t.zeroTag ? "nametag read 0 health" : "died");
                it.remove();
                continue;
            }
            if (now - t.lastHitMs > HIT_MEMORY_MS) {
                it.remove();
            }
        }
    }

    private static void kill(Minecraft mc, Tracked t, String why) {
        if (t.name == null) {
            return; // never named: not a Skyblock mob the table could know
        }
        String activity = DropTable.get().mobActivity(t.name, t.level);
        if (activity == null) {
            lastKill = t.name + " (not in the table)";
            return;
        }
        Long done = COUNTED.get(t.id);
        if (done != null && System.currentTimeMillis() - done < COUNTED_MS) {
            return;
        }
        COUNTED.put(t.id, System.currentTimeMillis());
        int looting = looting(mc.player.getMainHandItem());
        killsCounted++;
        lastKill = activity + " L" + looting;
        LuckDetector.onMobKill(activity, looting, why);
    }

    /** The Looting level in a Skyblock item's data ({@code enchantments.looting}), 0 for none. */
    static int looting(ItemStack held) {
        CompoundTag tag = ItemNbt.view(held);
        return tag == null ? 0 : Math.max(0, Math.min(10, tag.getCompoundOrEmpty("enchantments").getIntOr("looting", 0)));
    }

    private static void refreshName(Minecraft mc, Entity e, Tracked t) {
        String[] tag = nametag(mc, e);
        if (tag == null) {
            return;
        }
        t.name = tag[1];
        try {
            t.level = tag[0] == null ? 0 : Integer.parseInt(tag[0]);
        } catch (NumberFormatException ignored) {
            t.level = 0;
        }
        t.zeroTag = tag[2].matches("0(?:[.,]0+)?");
    }

    /** {level or null, name, health text} from the mob's own name or its nametag stand, or null. */
    static String[] nametag(Minecraft mc, Entity mob) {
        String[] own = parse(mob.hasCustomName() && mob.getCustomName() != null ? mob.getCustomName().getString() : null);
        if (own != null) {
            return own;
        }
        Entity next = mc.level.getEntity(mob.getId() + 1);
        if (next instanceof ArmorStand stand && stand.hasCustomName()) {
            String[] s = parse(stand.getCustomName().getString());
            if (s != null) {
                return s;
            }
        }
        List<Entity> above = mc.level.getEntities(mob, mob.getBoundingBox().expandTowards(0, 1.5, 0),
                x -> x instanceof ArmorStand st && st.hasCustomName());
        for (Entity x : above) {
            String[] s = parse(x.getCustomName().getString());
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    static String[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = ChatFormatting.stripFormatting(raw);
        if (s == null) {
            return null;
        }
        Matcher m = NAMETAG.matcher(s.trim());
        return !m.matches() ? null : new String[]{m.group(1), m.group(2).trim(), m.group(3)};
    }

    static void resetForTest() {
        TRACKED.clear();
        COUNTED.clear();
        pendingHits = 0;
        killsCounted = 0;
        lastKill = "";
    }
}
