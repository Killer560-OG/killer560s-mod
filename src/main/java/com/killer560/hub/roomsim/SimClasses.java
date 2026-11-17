package com.killer560.hub.roomsim;

import com.killer560.hub.dungeonclass.DungeonClass;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sim's dungeon classes (simclasses-1116): who plays which class at which level, for the tab's party rows
 * ("[100] Name (Mage L)"), the sidebar's teammate lines ("[M] Name 20❤"), the class chat lines and
 * {@link SimClassAbilities}. His own pick is the Sim Class / Sim Class Level setting ({@link SimMenuPrefs}); a co-op
 * guest's comes up as the {@code sim_class} payload ({@link SimCoop.ClassPick}); the Sim Teammates take the classes
 * he does not play, Healer, Tank, Archer, Berserk, Mage in that order.
 */
public final class SimClasses {

    public static final int MAX_LEVEL = 50;

    /** One player's class and level. */
    public record Pick(DungeonClass cls, int level) {
        /** The tab's class text, "Mage L". */
        public String tabText() { return name(cls) + " " + roman(level); }
    }

    private static final DungeonClass[] TEAM_ORDER = {DungeonClass.HEALER, DungeonClass.TANK, DungeonClass.ARCHER,
            DungeonClass.BERSERKER, DungeonClass.MAGE};

    /** Co-op guests' picks, server thread; one per guest on the server, dropped when it leaves or the sim ends. */
    private static final Map<UUID, Pick> GUESTS = new HashMap<>();
    /** The same by name, for the tab (any thread). */
    private static volatile Map<String, Pick> guestNames = Map.of();
    /** Bumped whenever any pick changes, so the tab and sidebar rebuild. */
    private static volatile int version;
    /** The sidebar's teammate lines, built on the server tick only when they change. */
    private static volatile List<String> teamLines = List.of();
    private static volatile String hostName = "";
    private static Pick selfPick;
    private static boolean wasRunning;
    private static int lastTeamKey;

    private SimClasses() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimClasses::serverTick);
    }

    /** Hypixel's name for a class: the tab and chat say Berserk. */
    public static String name(DungeonClass c) {
        return c == DungeonClass.BERSERKER ? "Berserk" : c.displayName();
    }

    private static final String[] ROMAN_TENS = {"", "X", "XX", "XXX", "XL", "L"};
    private static final String[] ROMAN_ONES = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};

    static String roman(int n) {
        n = Math.max(1, Math.min(MAX_LEVEL, n));
        return ROMAN_TENS[n / 10] + ROMAN_ONES[n % 10];
    }

    /** Changes whenever any pick does, his own included (a profile apply changes it without a setter). */
    public static int version() {
        Pick s = self();
        return version * 31 + s.cls().ordinal() * 64 + s.level();
    }

    static List<String> teamLines() { return teamLines; }

    /** His own pick (any thread). */
    public static Pick self() {
        SimMenuPrefs p = SimMenuPrefs.cached();
        Pick s = selfPick;
        if (s == null || s.cls() != p.simClass || s.level() != p.classLevel) {
            s = new Pick(p.simClass, p.classLevel);
            selfPick = s;
        }
        return s;
    }

    /** A party member's pick by name (any thread): a Sim Teammate, a co-op guest, else his own. */
    public static Pick of(String name) {
        int mate = SimTeammates.names().indexOf(name);
        if (mate >= 0) return teammate(mate);
        Pick g = guestNames.get(name);
        if (g != null) return g;
        return name.equals(hostName) || hostName.isEmpty() ? self() : new Pick(DungeonClass.MAGE, MAX_LEVEL);
    }

    static Pick of(ServerPlayer sp) {
        Pick g = GUESTS.get(sp.getUUID());
        return g != null ? g : of(sp.getGameProfile().name());
    }

    /** The class Sim Teammate {@code index} plays: the classes he does not, in {@link #TEAM_ORDER}. */
    private static Pick teammate(int index) {
        DungeonClass own = self().cls();
        int i = 0;
        for (DungeonClass c : TEAM_ORDER) {
            if (c == own) continue;
            if (i++ == index) return new Pick(c, MAX_LEVEL);
        }
        return new Pick(DungeonClass.MAGE, MAX_LEVEL);
    }

    /** Whether nobody else in the sim party (real players and Sim Teammates) plays this player's class. */
    static boolean onlyOne(MinecraftServer server, ServerPlayer sp) {
        DungeonClass c = of(sp).cls();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != sp && of(p).cls() == c) return false;
        }
        for (String mate : SimTeammates.names()) {
            if (of(mate).cls() == c) return false;
        }
        return true;
    }

    static Component selectedLine(DungeonClass c) {
        return Component.literal("You have selected the ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(name(c)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" Dungeon Class!").withStyle(ChatFormatting.GREEN));
    }

    /**
     * Client thread: the Sim Class or its level changed in Sim Settings. A guest tells its host; the host's own server
     * says the class line in a live sim, as Hypixel does when a class is picked.
     */
    public static void selfChanged(boolean classChanged) {
        version++;
        Minecraft client = Minecraft.getInstance();
        if (SimGuest.isGuest()) {
            SimGuest.sendClass(classChanged);
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (!classChanged || server == null || client.player == null || !SimState.canAct(client)) return;
        UUID id = client.player.getUUID();
        DungeonClass c = self().cls();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.sendSystemMessage(selectedLine(c));
        });
    }

    /** Server thread: a guest's {@code sim_class} payload. */
    static void guestPick(ServerPlayer sp, int cls, int level, boolean announce) {
        DungeonClass[] all = DungeonClass.values();
        if (cls < 0 || cls >= all.length || !SimAbilities.simServer(sp)) return;
        Pick pick = new Pick(all[cls], Math.max(1, Math.min(MAX_LEVEL, level)));
        Pick was = GUESTS.put(sp.getUUID(), pick);
        if (pick.equals(was)) return;
        publishGuests(sp.level().getServer());
        if (announce && (was == null || was.cls() != pick.cls())) sp.sendSystemMessage(selectedLine(pick.cls()));
    }

    private static void publishGuests(MinecraftServer server) {
        Map<String, Pick> names = new HashMap<>();
        for (Map.Entry<UUID, Pick> e : GUESTS.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) names.put(p.getGameProfile().name(), e.getValue());
        }
        guestNames = Map.copyOf(names);
        version++;
    }

    private static void serverTick(MinecraftServer server) {
        Minecraft client = Minecraft.getInstance();
        boolean live = SimState.canAct(client) && client.getSingleplayerServer() == server;
        if (!live) {
            if (!GUESTS.isEmpty() || wasRunning || !teamLines.isEmpty()) reset();
            return;
        }
        var owner = server.getSingleplayerProfile();
        hostName = owner == null ? "" : owner.name();
        int now = server.getTickCount();
        if (now % 20 == 0 && GUESTS.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null)) {
            publishGuests(server);
        }
        boolean running = SimRun.isRunning();
        if (running && !wasRunning) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (onlyOne(server, sp)) {
                    sp.sendSystemMessage(Component.literal("Your " + name(of(sp).cls())
                            + " stats are doubled because you are the only player using this class!").withStyle(ChatFormatting.GREEN));
                }
            }
            SimClassAbilities.onGo(server);
        }
        wasRunning = running;
        if (now % 10 == 0) updateTeamLines(server);
        SimClassAbilities.tick(server, now);
    }

    /**
     * The sidebar's teammate lines, as Hypixel writes them ("[M] Name 20❤", health green, yellow under half, red under a
     * quarter): every party member but the host. Rebuilt only when a class, a name or a health changed.
     */
    private static void updateTeamLines(MinecraftServer server) {
        int key = version();
        List<ServerPlayer> real = server.getPlayerList().getPlayers();
        List<ServerPlayer> mates = SimTeammates.placed();
        for (ServerPlayer p : real) key = key * 31 + p.getId() * 7 + (int) Math.ceil(p.getHealth());
        for (ServerPlayer p : mates) key = key * 31 + p.getId();
        if (key == lastTeamKey && !(teamLines.isEmpty() && real.size() + mates.size() > 1)) return;
        lastTeamKey = key;
        List<String> out = new ArrayList<>(4);
        for (ServerPlayer p : real) {
            if (!p.getGameProfile().name().equals(hostName)) out.add(teamLine(p));
        }
        for (ServerPlayer p : mates) out.add(teamLine(p));
        teamLines = out.size() > 5 ? List.copyOf(out.subList(0, 5)) : List.copyOf(out);
    }

    private static String teamLine(ServerPlayer p) {
        float max = Math.max(1f, p.getMaxHealth());
        float hp = p.getHealth();
        String color = hp > max * 0.5f ? "§a" : hp > max * 0.25f ? "§e" : "§c";
        return "§e[" + name(of(p).cls()).charAt(0) + "] §b" + p.getGameProfile().name() + " " + color
                + (int) Math.ceil(hp) + "§c❤";
    }

    /** Leaving the sim or a new session: guests' picks, the run edge and the sidebar lines go. */
    static void reset() {
        GUESTS.clear();
        guestNames = Map.of();
        teamLines = List.of();
        wasRunning = false;
        lastTeamKey = 0;
        version++;
        SimClassAbilities.reset();
    }
}
