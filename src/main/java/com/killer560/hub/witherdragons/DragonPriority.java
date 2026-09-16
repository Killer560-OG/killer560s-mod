package com.killer560.hub.witherdragons;

import com.killer560.hub.dungeonclass.DungeonClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which spawning dragon YOUR team goes to - port of Odin's {@code DragonPriority.kt}
 * (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/features/impl/boss/DragonPriority.kt;
 * NoammAddons' copy is the same logic).
 * <ul>
 * <li>Priority off: lowest index in the default order Red, Orange, Blue, Purple, Green.
 * <li>Total power = Power blessing (x1.25 with Paul Buff) + 2.5 if a Time blessing is active.
 * <li>If power &gt;= Normal Power, or Purple is one of the spawning dragons and power &gt;= Easy Power, the team
 * split applies: Berserker/Mage use Orange, Green, Red, Blue, Purple; everyone else (Archer/Tank/Healer, or an
 * unknown class) uses that list reversed. Otherwise the default order is used for everybody.
 * <li>Solo debuff (power &gt;= Easy Power, Purple spawning or "Solo Debuff on All Splits"): the helper class
 * flips to the Berserker/Mage choice.
 * </ul>
 * One deliberate deviation: Odin's healer branch is {@code else if (playerClass == HEALER && ...)} with no
 * {@code soloDebuff} check, so with "Purple Solo Debuff: Healer" BOTH tank and healer flip to b/m and nobody
 * debuffs purple. The setting's own description ("The class that solo debuffs purple, the other class helps
 * b/m") says only the non-selected class flips, which is what this does.
 */
final class DragonPriority {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-witherdragons");

    private static final List<WitherDragon> DEFAULT_ORDER =
            List.of(WitherDragon.RED, WitherDragon.ORANGE, WitherDragon.BLUE, WitherDragon.PURPLE, WitherDragon.GREEN);
    private static final List<WitherDragon> BERS_MAGE_ORDER =
            List.of(WitherDragon.ORANGE, WitherDragon.GREEN, WitherDragon.RED, WitherDragon.BLUE, WitherDragon.PURPLE);
    private static final List<WitherDragon> ARCH_TEAM_ORDER =
            List.of(WitherDragon.PURPLE, WitherDragon.BLUE, WitherDragon.RED, WitherDragon.GREEN, WitherDragon.ORANGE);

    /** Result: the chosen dragon plus a short human-readable reason for the subtitle / chat. */
    record Result(WitherDragon dragon, String reason) {
    }

    private DragonPriority() {
    }

    static Result findPriority(List<WitherDragon> spawning) {
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        List<WitherDragon> dragons = new ArrayList<>(spawning);
        if (!cfg.isDragonPriority()) {
            dragons.sort(Comparator.comparingInt(DEFAULT_ORDER::indexOf));
            return new Result(dragons.get(0), "default order");
        }

        double totalPower = P5State.powerBlessing() * (cfg.isPaulBuff() ? 1.25 : 1.0) + (P5State.timeBlessing() > 0 ? 2.5 : 0.0);
        DungeonClass clazz = P5State.selfClass();
        boolean purple = dragons.contains(WitherDragon.PURPLE);

        boolean split = totalPower >= cfg.getNormalPower() || (purple && totalPower >= cfg.getEasyPower());
        List<WitherDragon> order;
        String reason;
        if (split) {
            boolean bersMage = clazz == DungeonClass.BERSERKER || clazz == DungeonClass.MAGE;
            order = bersMage ? BERS_MAGE_ORDER : ARCH_TEAM_ORDER;
            reason = (bersMage ? "B/M team" : "A/T/H team") + (purple && totalPower < cfg.getNormalPower() ? " (easy split)" : " (split)");
        } else {
            order = DEFAULT_ORDER;
            reason = "no split (power " + formatPower(totalPower) + ")";
        }
        dragons.sort(Comparator.comparingInt(order::indexOf));

        if (totalPower >= cfg.getEasyPower() && (purple || cfg.isSoloDebuffOnAll())) {
            DungeonClass helper = cfg.getSoloDebuff() == WitherDragonsConfig.SoloDebuff.HEALER ? DungeonClass.TANK : DungeonClass.HEALER;
            if (clazz == helper) {
                dragons.sort(Comparator.comparingInt((WitherDragon d) -> order.indexOf(d)).reversed());
                reason = "helping B/M (" + cfg.getSoloDebuff().label + " solo debuffs)";
            }
        }

        LOGGER.info("[WitherDragons] Priority: power={} class={} dragons={} -> {} ({})", formatPower(totalPower),
                clazz, spawning, dragons.get(0), reason);
        if (clazz == null) {
            reason += ", class unknown";
        }
        return new Result(dragons.get(0), reason);
    }

    static String formatPower(double p) {
        return p == Math.floor(p) ? String.valueOf((int) p) : String.valueOf(p);
    }
}
