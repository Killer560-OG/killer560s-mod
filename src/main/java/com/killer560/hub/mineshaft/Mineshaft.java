package com.killer560.hub.mineshaft;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.mining.MiningAlerts;
import com.killer560.hub.mining.MiningTextHud;
import com.killer560.hub.mining.cold.ColdOverlay;
import com.killer560.hub.miningqol.BlockScan;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.OutgoingChat;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glacite Mineshafts (legit, display only, every part default OFF and independent): detection of the shaft and its type, the
 * entrance and ladder waypoints, the frozen corpse locator with the loot's value, and a cold / time-in-shaft HUD. It reads
 * the tab's Area line, the sidebar, chat, nearby armor stands and blocks, and sends nothing but the optional party lines.
 * Everything is read every 10 ticks and only while some part is on; state is dropped when the world changes.
 */
public final class Mineshaft {

    public static final IslandConfig CFG = IslandConfig.of("mining", "mineshaft")
            .flag("detect", false).flag("detectParty", false).flag("waypoints", false)
            .flag("corpses", false).flag("corpseParty", false).flag("profit", false)
            .flag("hud", false).flag("coldWarn", false).num("coldAt", 60, 10, 100).flag("scrap", false);

    private static final Pattern CODE = Pattern.compile("\\b([A-Z]{4})_([A-Z0-9])\\b");
    private static final Pattern LOOT_HEAD = Pattern.compile("(LAPIS|UMBER|TUNGSTEN|VANGUARD) CORPSE LOOT!");
    private static final Pattern AMOUNT = Pattern.compile("\\s+x(\\d+)$");
    private static final Map<String, String> AREAS = Map.ofEntries(Map.entry("TOPA", "Topaz"), Map.entry("SAPP", "Sapphire"),
            Map.entry("AMET", "Amethyst"), Map.entry("AMBE", "Amber"), Map.entry("JADE", "Jade"), Map.entry("TITA", "Titanium"),
            Map.entry("UMBE", "Umber"), Map.entry("TUNG", "Tungsten"), Map.entry("FAIR", "Vanguard"), Map.entry("RUBY", "Ruby"),
            Map.entry("ONYX", "Onyx"), Map.entry("AQUA", "Aquamarine"), Map.entry("CITR", "Citrine"), Map.entry("PERI", "Peridot"),
            Map.entry("JASP", "Jasper"), Map.entry("OPAL", "Opal"), Map.entry("LITT", "Littlefoot's Den"));
    private static final java.util.Set<String> NUMBERED = java.util.Set.of("TOPA", "SAPP", "AMET", "AMBE", "JADE", "RUBY", "ONYX",
            "AQUA", "CITR", "PERI");
    /** The helmet a frozen corpse's armor stand wears, by Skyblock id, to its type. */
    private static final Map<String, String> HELMETS = Map.of("LAPIS_ARMOR_HELMET", "Lapis", "MINERAL_HELMET", "Tungsten",
            "ARMOR_OF_YOG_HELMET", "Umber", "VANGUARD_HELMET", "Vanguard");
    private static final int MAX_CORPSES = 16;
    private static final String FEATURE = "Mineshaft";

    /** One frozen corpse seen: where, which type, and whether its loot was taken or its place shared. */
    private static final class Corpse {
        final String type;
        final double x, y, z;
        final String label, lootedLabel;
        final AABB box;
        boolean looted, shared;

        Corpse(String type, ArmorStand s) {
            this.type = type;
            this.x = s.getX();
            this.y = s.getY();
            this.z = s.getZ();
            this.label = type + " Corpse";
            this.lootedLabel = label + " (looted)";
            this.box = new AABB(x - 0.5, y, z - 0.5, x + 0.5, y + 1.0, z + 0.5);
        }
    }

    private static final List<Corpse> CORPSES = new ArrayList<>();
    private static final List<String> DRAWN = new ArrayList<>();
    // The outline arrays are rebuilt only when a corpse or the color changes and never touched after (26.2 draws them later).
    private static AABB[] boxes = new AABB[0];
    private static float[] rgba = new float[0];
    private static int boxKey = -1, version;
    private static final List<String> LOOT = new ArrayList<>();

    private static Level level;
    private static long levelSince;
    private static int ticks;
    private static boolean inside, warned, seenOutside;
    private static String type;
    private static long enteredAt;
    private static BlockPos entrance;
    private static volatile List<BlockPos> ladders = List.of();
    private static volatile List<String> hudLines = List.of();
    private static volatile int cold;
    private static String lootType;
    private static long lootAt;
    private static int drawnBoxes;

    public static final MiningTextHud HUD = new MiningTextHud("mineshaft_hud", "Mineshaft", 10, 360,
            () -> on("hud"), () -> hudLines, List.of("§b§lMineshaft §fTopaz 1", "§7Time in shaft: §f2:30", "§7Cold: §f40"));

    private Mineshaft() {
    }

    private static boolean on(String key) {
        return CFG.on(key) && SkyblockGate.allows();
    }

    private static boolean anyOn() {
        return CFG.on("detect") || CFG.on("waypoints") || CFG.on("corpses") || CFG.on("hud");
    }

    public static void register() {
        int bit = FeatureBits.register("Glacite Mineshafts", Mineshaft::anyOn);
        WorldRenderUtils.initThroughWalls();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("Mineshaft.tick", bit, Mineshaft::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeaturesFlush(bit, Mineshaft::render));
        ChatObserver.subscribe(Mineshaft::onChat);
        MineshaftScrap.register();
    }

    /** True inside a Glacite Mineshaft (the tab's Area line); other features read this. */
    public static boolean inside() {
        return MiningState.mineshaft();
    }

    /** The shaft's type from the sidebar's area code ("Topaz 1"), or null until it shows. Kept while a part is on. */
    public static String type() {
        return type;
    }

    public static int cold() {
        return cold;
    }

    public static List<String> hudLines() {
        return hudLines;
    }

    /** The labels the last frame drew (read by the testkit). */
    public static List<String> drawnLabels() {
        return new ArrayList<>(DRAWN);
    }

    public static BlockPos entrance() {
        return entrance;
    }

    public static int drawnBoxes() {
        return drawnBoxes;
    }

    /** "Type looted=<bool>" for each corpse seen (read by the testkit). */
    public static List<String> corpses() {
        List<String> out = new ArrayList<>();
        for (Corpse c : CORPSES) {
            out.add(c.type + " looted=" + c.looted + " shared=" + c.shared);
        }
        return out;
    }

    private static void reset() {
        inside = false;
        warned = false;
        seenOutside = false;
        type = null;
        entrance = null;
        cold = 0;
        ladders = List.of();
        hudLines = List.of();
        lootType = null;
        CORPSES.clear();
        version++;
    }

    private static void tick(Minecraft mc) {
        if (++ticks % 10 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mc.level != level) {
            reset();
            level = mc.level;
            levelSince = now;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (lootType != null && now - lootAt > 1500) {
            finishLoot();
        }
        boolean in = MiningState.mineshaft();
        if (in != inside) {
            inside = in;
            type = null;
            if (in) {
                enteredAt = now;
                // A spawn point is only known for a shaft entered from outside or right after the world loaded (not one a switch found us in).
                entrance = seenOutside || now - levelSince < 20_000 ? mc.player.blockPosition() : null;
            } else {
                entrance = null;
                ladders = List.of();
                hudLines = List.of();
                CORPSES.clear();
        version++;
            }
        }
        if (!in) {
            seenOutside = true;
            return;
        }
        List<String> side = ScoreboardData.sidebarNow(mc);
        if (type == null) {
            readType(side);
        }
        if (on("hud")) {
            hud(side, now);
        }
        if (on("waypoints") && ticks % 40 == 0) {
            scanLadders(mc);
        }
        if (on("corpses")) {
            scanCorpses(mc);
        }
    }

    private static void readType(List<String> side) {
        for (String line : side) {
            Matcher m = CODE.matcher(ChatObserver.strip(line));
            while (m.find()) {
                String name = AREAS.get(m.group(1));
                if (name == null) {
                    continue;
                }
                String c = m.group(2);
                type = c.equals("C") ? name + " Crystal" : Character.isDigit(c.charAt(0)) && NUMBERED.contains(m.group(1)) ? name + " " + c : name;
                if (on("detect")) {
                    ModChat.send(FEATURE, ModChat.text("You entered a " + type + " Mineshaft."));
                    if (CFG.on("detectParty")) {
                        OutgoingChat.command("pc Mineshaft type: " + type);
                    }
                }
                return;
            }
        }
    }

    private static void hud(List<String> side, long now) {
        int c = ColdOverlay.readCold(side);
        cold = c;
        List<String> lines = new ArrayList<>(4);
        long s = (now - enteredAt) / 1000;
        lines.add("§b§lMineshaft" + (type == null ? "" : " §f" + type));
        lines.add(String.format(Locale.ROOT, "§7Time in shaft: §f%d:%02d", s / 60, s % 60));
        int at = CFG.num("coldAt");
        boolean warn = CFG.on("coldWarn") && c >= at;
        lines.add((warn ? "§c§lCOLD " : "§7Cold: §f") + c);
        hudLines = lines;
        if (warn && !warned) {
            warned = true;
            MiningAlerts.title("§c§lCold " + c, "", 40);
            MiningAlerts.sound(SecretSound.SoundChoice.BELL);
        } else if (c < at - 5) {
            warned = false;
        }
    }

    private static void scanLadders(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        List<BlockPos> found = BlockScan.scan(mc.level, p, 48, 40, st -> st.is(Blocks.LADDER), (pos, st) -> true, 256);
        Map<Long, BlockPos> tops = new HashMap<>();
        for (BlockPos b : found) {
            tops.merge(((long) b.getX() << 32) ^ (b.getZ() & 0xFFFFFFFFL), b, (a, c) -> a.getY() >= c.getY() ? a : c);
        }
        List<BlockPos> out = new ArrayList<>(tops.values());
        out.sort((a, c) -> Double.compare(a.distSqr(p), c.distSqr(p)));
        ladders = List.copyOf(out.subList(0, Math.min(6, out.size())));
    }

    private static void scanCorpses(Minecraft mc) {
        AABB around = mc.player.getBoundingBox().inflate(96);
        for (ArmorStand s : mc.level.getEntitiesOfClass(ArmorStand.class, around, a -> true)) {
            String t = HELMETS.get(ItemIdentity.skyblockId(s.getItemBySlot(EquipmentSlot.HEAD)));
            if (t == null || CORPSES.size() >= MAX_CORPSES || known(t, s)) {
                continue;
            }
            CORPSES.add(new Corpse(t, s));
            version++;
        }
        if (CFG.on("corpseParty")) {
            for (Corpse c : CORPSES) {
                if (!c.shared && !c.looted && mc.player.distanceToSqr(c.x, c.y, c.z) <= 25) {
                    c.shared = true;
                    OutgoingChat.command(String.format(Locale.ROOT, "pc x: %d, y: %d, z: %d | (%s)", (int) Math.floor(c.x),
                            (int) Math.floor(c.y), (int) Math.floor(c.z), c.label));
                }
            }
        }
    }

    private static boolean known(String t, ArmorStand s) {
        for (Corpse c : CORPSES) {
            if (c.type.equals(t) && s.distanceToSqr(c.x, c.y, c.z) < 4) {
                return true;
            }
        }
        return false;
    }

    // ---- chat -----------------------------------------------------------------------------------------------------

    private static void onChat(Component message) {
        if (!anyOn() && lootType == null) {
            return;
        }
        String s = ChatObserver.strip(message);
        if (s == null) {
            return;
        }
        if (on("detect") && s.startsWith("MINESHAFT! A mineshaft portal spawned")) {
            MiningAlerts.title("§b§lMineshaft found", "A portal spawned nearby", 60);
            MiningAlerts.sound(SecretSound.SoundChoice.AMETHYST);
            ModChat.send(FEATURE, ModChat.text("Mineshaft found: a portal spawned nearby."));
            return;
        }
        if (on("corpses")) {
            loot(s.trim());
        }
    }

    private static void loot(String s) {
        long now = System.currentTimeMillis();
        if (lootType != null && now - lootAt > 1500) {
            finishLoot();
        }
        Matcher m = LOOT_HEAD.matcher(s);
        if (m.matches()) {
            String t = m.group(1).charAt(0) + m.group(1).substring(1).toLowerCase(Locale.ROOT);
            lootType = t;
            lootAt = now;
            LOOT.clear();
            markLooted(t);
        } else if (lootType != null) {
            if (s.startsWith("▬")) {
                finishLoot();
            } else if (!s.isEmpty() && LOOT.size() < 30) {
                LOOT.add(s);
            }
        }
    }

    private static void markLooted(String t) {
        Minecraft mc = Minecraft.getInstance();
        Corpse best = null;
        double d = 144;
        for (Corpse c : CORPSES) {
            double dd = mc.player == null ? 0 : mc.player.distanceToSqr(c.x, c.y, c.z);
            if (!c.looted && c.type.equals(t) && dd < d) {
                best = c;
                d = dd;
            }
        }
        if (best != null) {
            best.looted = true;
            version++;
        }
    }

    private static void finishLoot() {
        String t = lootType;
        lootType = null;
        if (t == null || !CFG.on("profit")) {
            return;
        }
        PriceData.touchFromOtherFeature();
        double total = 0;
        int priced = 0;
        for (String line : LOOT) {
            String name = line.replaceFirst("^[^A-Za-z0-9]+", "");
            int n = 1;
            Matcher a = AMOUNT.matcher(name);
            if (a.find()) {
                n = Integer.parseInt(a.group(1));
                name = name.substring(0, a.start());
            }
            double unit = PriceData.unitPrice(BazaarCatalog.idForName(name));
            if (unit > 0) {
                total += unit * n;
                priced++;
            }
        }
        ModChat.send(FEATURE, ModChat.text(String.format(Locale.ROOT, "%s corpse loot: about %,.0f coins (%d of %d items priced).",
                t, total, priced, LOOT.size())));
    }

    // ---- world ----------------------------------------------------------------------------------------------------

    private static void render(LevelRenderContext ctx) {
        DRAWN.clear();
        drawnBoxes = 0;
        if (!inside) {
            return;
        }
        int color = ThemeAccent.accent(0xFFCC6600);
        if (on("waypoints")) {
            if (entrance != null) {
                label(ctx, entrance.getX() + 0.5, entrance.getY() + 1.6, entrance.getZ() + 0.5, "Entrance / Exit", color);
            }
            for (BlockPos b : ladders) {
                label(ctx, b.getX() + 0.5, b.getY() + 1.2, b.getZ() + 0.5, "Ladder", color);
            }
        }
        if (on("corpses")) {
            for (Corpse c : CORPSES) {
                label(ctx, c.x, c.y + 1.6, c.z, c.looted ? c.lootedLabel : c.label, color);
            }
            if (boxKey != version * 31 + color) {
                boxKey = version * 31 + color;
                List<AABB> open = new ArrayList<>();
                for (Corpse c : CORPSES) {
                    if (!c.looted) {
                        open.add(c.box);
                    }
                }
                boxes = open.toArray(new AABB[0]);
                rgba = new float[boxes.length * 4];
                for (int i = 0; i < boxes.length; i++) {
                    rgba[i * 4] = (color >> 16 & 255) / 255f;
                    rgba[i * 4 + 1] = (color >> 8 & 255) / 255f;
                    rgba[i * 4 + 2] = (color & 255) / 255f;
                    rgba[i * 4 + 3] = 1f;
                }
            }
            if (boxes.length > 0) {
                WorldRenderUtils.renderOutlineBoxes(ctx, boxes, rgba, boxes.length, 2f, true);
                drawnBoxes = boxes.length;
            }
        }
    }

    private static void label(LevelRenderContext ctx, double x, double y, double z, String text, int color) {
        IslandRender.label(ctx, x, y, z, text, color, true);
        DRAWN.add(text);
    }
}
