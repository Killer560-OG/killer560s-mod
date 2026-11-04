package com.killer560.hub.luck;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

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
                    Tracked t = track(entity.getId());
                    t.lastHitMs = System.currentTimeMillis();
                    t.hitByMe = true;
                    refreshName(Minecraft.getInstance(), entity, t);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Luck] Attack hook failed", e);
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LuckKills.onTick", LuckKills::onTick));
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
            return;
        }
        ticks++;
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
        killsCounted = 0;
        lastKill = "";
    }
}
