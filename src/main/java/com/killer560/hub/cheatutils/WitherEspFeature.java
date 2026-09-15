package com.killer560.hub.cheatutils;

import com.killer560.hub.cheatutils.mixin.BossHealthOverlayAccessor;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wither ESP - highlights the active F7/M7 Wither (Maxor/Storm/Goldor/Necron) through walls with a per-Wither
 * color, ported from NoammAddons 26.1.2 {@code floor7/WitherESP.kt}:
 * <ul>
 * <li>The tracked entity is a {@link WitherBoss} that is NOT invisible and whose invulnerable ticks != 800
 * (Noamm's exact filter - the invisible/800-tick withers are Hypixel's fake display withers). Noamm grabs it
 * from entity-data packets; this polls the level each tick and takes the nearest match.
 * <li>Which Wither it is comes from the boss bar name containing MAXOR/STORM/GOLDOR/NECRON (Noamm's
 * {@code BossBarUpdateEvent}), read via {@link BossHealthOverlayAccessor}. Fallback when no bar names a
 * Wither (e.g. p3sim.net): the last "[BOSS] Name:" chat speaker.
 * <li>Only in F7/M7 boss, never in P5 (Noamm {@code isValidLoc}). Phase filter (default P3 only) per roadmap.
 * <li>Glow is applied by the cheatutils mixins ({@code Minecraft#shouldEntityAppearGlowing} and
 * {@code Entity#getTeamColor}, the same two hooks Noamm uses) - nothing here mutates entity data.
 * </ul>
 */
public final class WitherEspFeature {

    public enum WitherType { MAXOR, STORM, GOLDOR, NECRON }

    private static volatile int glowEntityId = -1;
    private static volatile int glowColor = 0xFFFFFFFF;

    private static WitherType currentType = WitherType.MAXOR;
    private static WitherType chatType = null;
    private static boolean witherKingPhase = false;
    private static Object lastLevel = null;
    private static String lastLoggedState = null;

    private WitherEspFeature() {
    }

    /** Mixin hook: whether this entity should glow as the tracked Wither. */
    public static boolean shouldGlow(Entity entity) {
        return glowEntityId >= 0 && entity != null && entity.getId() == glowEntityId;
    }

    /** Mixin hook: the outline color (ARGB) for the tracked Wither. */
    public static int glowColor() {
        return glowColor;
    }

    static void onChat(String plain) {
        if (!plain.startsWith("[BOSS] ")) {
            return;
        }
        if (plain.startsWith("[BOSS] Maxor:")) {
            chatType = WitherType.MAXOR;
        } else if (plain.startsWith("[BOSS] Storm:")) {
            chatType = WitherType.STORM;
        } else if (plain.startsWith("[BOSS] Goldor:")) {
            chatType = WitherType.GOLDOR;
        } else if (plain.startsWith("[BOSS] Necron:")) {
            chatType = WitherType.NECRON;
        } else if (plain.startsWith("[BOSS] Wither King:")) {
            witherKingPhase = true;
        }
    }

    static void tick(Minecraft client) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            currentType = WitherType.MAXOR;
            chatType = null;
            witherKingPhase = false;
            glowEntityId = -1;
        }
        if (!cfg.isWitherEspEnabled()) {
            // Review fix (2026-09-15): cheap disabled exit - no per-tick state-string building while off.
            glowEntityId = -1;
            logState("disabled");
            return;
        }
        boolean validLoc = client.level != null && client.player != null && DungeonState.isF7OrM7()
                && LiveMapFeature.isInBoss() && !witherKingPhase;
        if (!cfg.isWitherEspEnabled() || !validLoc) {
            glowEntityId = -1;
            logState("inactive enabled=" + cfg.isWitherEspEnabled() + " validLoc=" + validLoc
                    + " witherKingPhase=" + witherKingPhase);
            return;
        }

        WitherType fromBar = readBossBarType(client);
        if (fromBar != null) {
            currentType = fromBar;
        } else if (chatType != null) {
            currentType = chatType;
        }

        CheatUtilsConfig.WitherPhaseFilter filter = cfg.getWitherPhaseFilter();
        boolean phaseAllowed = filter == CheatUtilsConfig.WitherPhaseFilter.ALL
                || filter.ordinal() - 1 == currentType.ordinal();
        if (!phaseAllowed) {
            glowEntityId = -1;
            logState("filtered phase=" + currentType + " filter=" + filter);
            return;
        }

        WitherBoss best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof WitherBoss wither) || wither.isRemoved() || wither.isInvisible()
                    || wither.getInvulnerableTicks() == 800) {
                continue;
            }
            double d = wither.distanceToSqr(client.player);
            if (d < bestDist) {
                bestDist = d;
                best = wither;
            }
        }
        glowEntityId = best == null ? -1 : best.getId();
        glowColor = switch (currentType) {
            case MAXOR -> cfg.getMaxorColor();
            case STORM -> cfg.getStormColor();
            case GOLDOR -> cfg.getGoldorColor();
            case NECRON -> cfg.getNecronColor();
        };
        logState("active phase=" + currentType + " (bar=" + fromBar + " chat=" + chatType + ") filter=" + filter
                + " witherId=" + glowEntityId);
    }

    private static WitherType readBossBarType(Minecraft client) {
        try {
            Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) client.gui.getBossOverlay()).killer560smod$getEvents();
            if (events == null) {
                return null;
            }
            for (LerpingBossEvent event : events.values()) {
                String name = ChatFormatting.stripFormatting(event.getName().getString());
                if (name == null) {
                    continue;
                }
                String upper = name.toUpperCase(Locale.ROOT);
                for (WitherType type : WitherType.values()) {
                    if (upper.contains(type.name())) {
                        return type;
                    }
                }
            }
        } catch (ClassCastException | NullPointerException ignored) {
            // accessor mixin not applied - chat fallback only
        }
        return null;
    }

    private static void logState(String state) {
        if (!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            CheatUtils.LOGGER.info("[CheatUtils] WitherESP {}", state);
        }
    }
}
