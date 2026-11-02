package com.killer560.hub.cheatutils;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Ult - uses the class Ultimate on specific F7/M7 boss lines, ported from NoammAddons 26.1.2
 * {@code dungeon/Abilities.kt} (CHEAT block) + {@code PlayerUtils.useDungeonClassAbility(ult = true)}:
 * <ul>
 * <li>Triggers (exact plain-text match, floor 7, Healer/Tank only - Noamm's {@code ultMessages}):
 * "⚠ Maxor is enraged! ⚠" and "[BOSS] Goldor: You have done it, you destroyed the factory…".
 * (Noamm's F5 Livid / F6 Sadan triggers are out of scope - F7/M7 boss only per task.)
 * <li>Action: {@code ServerboundPlayerActionPacket(DROP_ITEM, BlockPos.ZERO, Direction.DOWN)} - the Drop (Q)
 * key. Confirmed against Hypixel's own text: "... is ready to use! Press DROP to activate it!" (QUOI
 * ChatReplacements) and Mort "Left-click (or Drop) to use your Ultimate!" (Odin/QUOI SplitsManager).
 * <li>Own class: tab list, Odin {@code DungeonUtils.tablistRegex} ({@code [lvl] name ... (Class L)}), or a
 * manual override. Each trigger fires at most once per world.
 * </ul>
 */
public final class AutoUltFeature {

    private static final String MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠";
    private static final String GOLDOR_FACTORY = "[BOSS] Goldor: You have done it, you destroyed the factory…";
    /** Hypixel's Wish lines (DungeonChatHider's ABILITY family, the Healer's ultimate by name). */
    private static final String WISH_USED = "Used Wish!";
    private static final String WISH_READY = "Wish is ready to use! Press DROP to activate it!";
    private static final String WISH_AVAILABLE = "Your Healer ULTIMATE Wish is now available!";
    private static final Pattern ON_COOLDOWN = Pattern.compile("^This ability is on cooldown for (\\d{1,3})s\\.$");
    /** A cooldown line this soon after our own Wish answers it (any later one is some other ability's). */
    private static final long COOLDOWN_REPLY_MS = 1500L;
    /** No line came back at all: treat the Wish as used and wait the Healer's base cooldown before trying again. */
    private static final long WISH_FALLBACK_COOLDOWN_MS = 120_000L;
    private static final int WISH_POLL_TICKS = 5;
    private static final Pattern TABLIST = Pattern.compile("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$");

    /** Ticks a decided-but-not-yet-sent ult keeps asking {@link ActionGate} before it is given up on - two
     *  seconds, comfortably past the gate's longest stand-down ({@code WORLD_SETTLE_TICKS} = 20 ticks) while
     *  still short enough that the ult is never fired into a fight it no longer belongs to. */
    private static final int ULT_GATE_BUDGET_TICKS = 40;

    private static final Set<String> firedThisWorld = new HashSet<>();
    private static Object lastLevel = null;
    private static DungeonClass tabClass = null;
    private static int classScanCooldown = 0;
    private static String pendingUltTrigger = null;
    private static int pendingUltTicksLeft = 0;
    /** Wish on Clear: no Wish is sent before this (cooldown known from Hypixel's lines, or the fallback). */
    private static long wishBlockedUntilMs = 0L;
    private static long wishSentAtMs = -1L;
    private static int wishPoll = 0;
    private static int wishesSent = 0;

    private AutoUltFeature() {
    }

    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            if (pendingUltTrigger != null || !firedThisWorld.isEmpty()) {
                CheatUtils.LOGGER.info("[AutoUlt] world change: fired={} pending dropped={} tick={}", firedThisWorld,
                        pendingUltTrigger, com.killer560.hub.util.SessionRecorderSink.tick());
            }
            lastLevel = client.level;
            firedThisWorld.clear();
            tabClass = null;
            pendingUltTrigger = null;
            wishBlockedUntilMs = 0L;
            wishSentAtMs = -1L;
        }
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isAutoUltEnabled() || !DungeonState.isInDungeon()) {
            // A queued ult must not outlive the moment it was decided in: switched off, or out of the dungeon, it
            // used to sit there (the budget only counts down below) and go out whenever the gate next let it.
            pendingUltTrigger = null;
            return;
        }
        firePendingUlt(client);
        tickWishOnClear(client, cfg);
        // With a class override set the tab list is never read (effectiveClass returns the override first).
        if ("AUTO".equalsIgnoreCase(cfg.getUltClassOverride())) {
            rescanTabClass(client);
        }
    }

    private static void rescanTabClass(Minecraft client) {
        if (--classScanCooldown <= 0) {
            classScanCooldown = 40;
            DungeonClass found = scanTabClass(client);
            if (found != null && found != tabClass) {
                CheatUtils.LOGGER.info("[AutoUlt] tab class: {} -> {}", tabClass, found);
                tabClass = found;
            }
        }
    }

    static void onChat(String plain) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isAutoUltEnabled()) {
            return;
        }
        onWishLine(plain.trim());
        String trigger;
        if (MAXOR_ENRAGED.equals(plain.trim()) && cfg.isUltMaxorEnraged()) {
            trigger = "Maxor enraged";
        } else if (GOLDOR_FACTORY.equals(plain.trim()) && cfg.isUltGoldorFactory()) {
            trigger = "Goldor factory destroyed";
        } else {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !CheatUtils.isOnDungeonServer(client)) {
            CheatUtils.LOGGER.info("[AutoUlt] '{}' seen but skipped: player={} onDungeonServer={}", trigger,
                    client.player != null, CheatUtils.isOnDungeonServer(client));
            return;
        }
        if (!DungeonState.isF7OrM7() || !LiveMapFeature.isInBoss()) {
            CheatUtils.LOGGER.info("[CheatUtils] AutoUlt '{}' seen but not in F7/M7 boss (floor={}) - skipped",
                    trigger, DungeonState.getFloor());
            return;
        }
        DungeonClass clazz = effectiveClass(cfg);
        if (clazz == null || !cfg.isUltClass(clazz)) {
            CheatUtils.LOGGER.info("[CheatUtils] AutoUlt '{}' skipped: class={} (not a selected class)", trigger, clazz);
            return;
        }
        if (!firedThisWorld.add(trigger)) {
            CheatUtils.LOGGER.info("[AutoUlt] '{}' skipped: already fired this world", trigger);
            return;
        }
        CheatUtils.LOGGER.info("[AutoUlt] '{}' queued: class={} (override={}) gateBudgetTicks={} tick={}", trigger, clazz,
                cfg.getUltClassOverride(), ULT_GATE_BUDGET_TICKS, com.killer560.hub.util.SessionRecorderSink.tick());
        // Real gap found (2026-09-21, gate audit): ActionGate.Actor.AUTO_ULT was declared for this feature
        // ("Losing the tick loses the ult, so it sits near the top") but nothing here ever called tryAct -
        // the drop packet went straight out, so the ult could ride along with a Breaker Aura break or a
        // lever flick on the same tick, and it fired with a container screen open. It cannot simply ask the
        // gate HERE and give up on a refusal: this is a one-shot chat line, so a denial would silently eat
        // the ult - the exact failure the Experimentation Table audit was written about. So the ult is
        // handed to the tick loop instead, which re-asks every tick until the gate lets it through and only
        // reports a loss if it never does.
        pendingUltTrigger = trigger;
        pendingUltTicksLeft = ULT_GATE_BUDGET_TICKS;
    }

    /** Sends the queued ult on the first tick {@link ActionGate} allows it - see {@link #onChat}. */
    private static void firePendingUlt(Minecraft client) {
        if (pendingUltTrigger == null) {
            return;
        }
        if (client.player == null) {
            return;
        }
        if (ActionGate.tryAct(ActionGate.Actor.AUTO_ULT)) {
            String what = pendingUltTrigger;
            pendingUltTrigger = null;
            client.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
            CheatUtils.LOGGER.info("[AutoUlt] DROP_ITEM sent for '{}': waitedTicks={} held={} tick={}", what,
                    ULT_GATE_BUDGET_TICKS - pendingUltTicksLeft, CheatUtils.plainName(client.player.getMainHandItem()),
                    com.killer560.hub.util.SessionRecorderSink.tick());
            ModChat.send(CheatUtils.CHAT_TAG, ModChat.text("Used Ultimate "), ModChat.dim("(" + what + ")"));
            return;
        }
        if (pendingUltTicksLeft == ULT_GATE_BUDGET_TICKS || pendingUltTicksLeft % 20 == 0) {
            CheatUtils.LOGGER.info("[AutoUlt] ActionGate refused '{}': ticksLeft={} containerOpen={}", pendingUltTrigger,
                    pendingUltTicksLeft, ActionGate.containerScreenOpen(client));
        }
        if (--pendingUltTicksLeft <= 0) {
            CheatUtils.LOGGER.warn("[CheatUtils] AutoUlt gave up on '{}' - ActionGate never freed up within {} ticks "
                    + "(a container screen open the whole time will do it)", pendingUltTrigger, ULT_GATE_BUDGET_TICKS);
            ModChat.send(CheatUtils.CHAT_TAG, ModChat.bad("Couldn't use your Ultimate"),
                    ModChat.dim(" (" + pendingUltTrigger + ") - nothing was sent."));
            pendingUltTrigger = null;
        }
    }

    // ---- Wish on Clear (killer560, 2026-11-01: "add an option for wish on clear if someone is low. (make sure it doesnt
    // keep trying to wish if they dont have the cooldown for wish.)") ----

    /** Hypixel's own lines keep the cooldown: Used Wish -> blocked until the ready line (or the fallback); a cooldown
     *  line answering our Wish -> blocked for exactly that long; the ready/available line -> free again. */
    private static void onWishLine(String line) {
        long now = System.currentTimeMillis();
        if (WISH_USED.equals(line)) {
            wishBlockedUntilMs = now + WISH_FALLBACK_COOLDOWN_MS;
            wishSentAtMs = -1L;
        } else if (WISH_READY.equals(line) || WISH_AVAILABLE.equals(line)) {
            wishBlockedUntilMs = 0L;
        } else if (wishSentAtMs >= 0 && now - wishSentAtMs <= COOLDOWN_REPLY_MS) {
            Matcher m = ON_COOLDOWN.matcher(line);
            if (m.matches()) {
                wishBlockedUntilMs = now + Integer.parseInt(m.group(1)) * 1000L + 500L;
                wishSentAtMs = -1L;
                CheatUtils.LOGGER.info("[AutoUlt] Wish on cooldown for {}s - not trying again before then", m.group(1));
            }
        }
    }

    private static void tickWishOnClear(Minecraft client, CheatUtilsConfig cfg) {
        if (!cfg.isWishOnClear() || pendingUltTrigger != null || client.player == null) {
            return;
        }
        if (++wishPoll < WISH_POLL_TICKS) {
            return;
        }
        wishPoll = 0;
        long now = System.currentTimeMillis();
        if (wishSentAtMs >= 0 && now - wishSentAtMs > COOLDOWN_REPLY_MS) {
            // nothing answered our Wish (no "Used Wish!", no cooldown line): never assume it is free - wait it out
            wishBlockedUntilMs = Math.max(wishBlockedUntilMs, wishSentAtMs + WISH_FALLBACK_COOLDOWN_MS);
            wishSentAtMs = -1L;
        }
        if (now < wishBlockedUntilMs || wishSentAtMs >= 0) {
            return;
        }
        if (LiveMapFeature.isInBoss() || !CheatUtils.isOnDungeonServer(client)) {
            return;
        }
        DungeonClass clazz = effectiveClass(cfg);
        if (clazz != DungeonClass.HEALER || !cfg.isUltClass(DungeonClass.HEALER)) {
            return;
        }
        String low = lowTeammate(client);
        if (low == null) {
            return;
        }
        wishSentAtMs = now;
        wishBlockedUntilMs = now + COOLDOWN_REPLY_MS; // one attempt, then only Hypixel's answer frees it
        wishesSent++;
        CheatUtils.LOGGER.info("[AutoUlt] Wish on Clear queued: {} is low", low);
        pendingUltTrigger = "Wish: " + low + " is low";
        pendingUltTicksLeft = ULT_GATE_BUDGET_TICKS;
    }

    /** The first teammate whose dungeon-sidebar health Hypixel colors red (SkyHanni's low-health rule), or null. */
    private static String lowTeammate(Minecraft client) {
        String self = client.player.getGameProfile().name();
        for (String line : com.killer560.hub.scoreboard.ScoreboardData.sidebarNow(client)) {
            Matcher m = com.killer560.hub.scoreboard.ScoreboardPattern.TEAMMATES.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group("username");
            String health = m.group("health");
            if (name != null && health != null && !name.equals(self) && "c".equals(m.group("color"))
                    && !"DEAD".equals(health)) {
                return name;
            }
        }
        return null;
    }

    /** Wishes Wish on Clear has sent this session - for the testkit. */
    public static int wishesSent() {
        return wishesSent;
    }

    private static DungeonClass effectiveClass(CheatUtilsConfig cfg) {
        String override = cfg.getUltClassOverride();
        if (override != null && !"AUTO".equalsIgnoreCase(override)) {
            return DungeonClass.byName(override);
        }
        if (tabClass == null) {
            tabClass = scanTabClass(Minecraft.getInstance());
        }
        return tabClass;
    }

    private static DungeonClass scanTabClass(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            return null;
        }
        String self = client.player.getGameProfile().name();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = com.killer560.hub.util.TabText.plain(info);
            if (plain == null) {
                continue;
            }
            Matcher m = TABLIST.matcher(plain.trim());
            if (m.find() && m.group(2).equals(self)) {
                String name = m.group(3);
                return "Berserk".equalsIgnoreCase(name) ? DungeonClass.BERSERKER : DungeonClass.byName(name);
            }
        }
        return null;
    }
}
