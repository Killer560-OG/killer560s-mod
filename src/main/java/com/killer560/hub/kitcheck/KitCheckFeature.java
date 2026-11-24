package com.killer560.hub.kitcheck;

import com.killer560.hub.auction.BoosterCookie;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.quiver.QuiverDisplayFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Kit Check: on the dungeon countdown ("Starting in 5 seconds.") it compares what you carry with the minimums you set
 * (Spirit Leaps, Superboom TNT, Decoys, arrows, a God Potion and a Booster Cookie from the tab footer) and, when
 * something is short, shows one title and one chat line. It only reads: nothing is clicked, swapped or equipped, and
 * sacks are not visible to the client, so only the inventory counts. An item or effect it cannot read (no footer yet)
 * is never reported. Runs once per countdown (the first line seen while the floor is known), never per tick.
 */
public final class KitCheckFeature {

    private static final Pattern COUNTDOWN = Pattern.compile("Starting in [1-5] seconds?\\.");
    private static final long ONCE_MS = 20_000;

    private static long lastCheckMs;
    private static String lastReport;
    private static int checks;

    private KitCheckFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(c -> {
            String s = ChatObserver.strip(c);
            if (s != null && s.startsWith("Starting in ") && COUNTDOWN.matcher(s.trim()).matches()
                    && KitCheckConfig.getInstance().isEnabled()) {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> run(mc));
            }
        });
    }

    /** The floor number of "F7" / "M3" (0 for the Entrance), or -1 when unknown. */
    static int floorNumber(String floor) {
        if (floor == null || floor.isEmpty()) {
            return -1;
        }
        char last = floor.charAt(floor.length() - 1);
        return Character.isDigit(last) ? last - '0' : floor.startsWith("E") ? 0 : -1;
    }

    /** Checks once per countdown; false when it did not run (floor not known yet, or already checked). */
    public static boolean run(Minecraft client) {
        long now = System.currentTimeMillis();
        int floor = floorNumber(DungeonState.getFloor());
        if (client.player == null || floor < 0 || now - lastCheckMs < ONCE_MS) {
            return false;
        }
        lastCheckMs = now;
        checks++;
        KitCheckConfig cfg = KitCheckConfig.getInstance();
        if (floor < cfg.getMinFloor()) {
            return true;
        }
        List<String> missing = new ArrayList<>();
        List<String> refill = new ArrayList<>();
        Inventory inv = client.player.getInventory();
        item(missing, refill, "Spirit Leap", "leap", cfg.getLeaps(), count(inv, "SPIRIT_LEAP"));
        item(missing, refill, "Superboom TNT", "boom", cfg.getBoom(), count(inv, "SUPERBOOM_TNT"));
        item(missing, refill, "Decoy", null, cfg.getDecoys(), count(inv, "DECOY"));
        if (cfg.getArrows() > 0) {
            item(missing, refill, "Arrows", null, cfg.getArrows(), Math.max(0, QuiverDisplayFeature.arrowCount(client.player)));
        }
        String footer = BoosterCookie.footer(client);
        if (cfg.isGodPotion() && footer != null && (footer.contains("Active Effects") || footer.contains("No effects active"))
                && !footer.contains("God Potion")) {
            missing.add("God Potion");
        }
        if (cfg.isCookie() && BoosterCookie.state(client) == BoosterCookie.State.INACTIVE) {
            missing.add("Booster Cookie");
        }
        lastReport = missing.isEmpty() ? null : String.join(", ", missing);
        if (missing.isEmpty()) {
            return true;
        }
        DungeonAlertsFeature.showTitle("§cKit short", "§e" + lastReport, 0, 60, 10);
        ModChat.send("Kit Check", ModChat.bad("Short: " + lastReport),
                ModChat.dim(refill.isEmpty() ? "" : "  Refill with " + String.join(", ", refill)));
        return true;
    }

    private static void item(List<String> out, List<String> refill, String name, String gfs, int want, int have) {
        if (want > 0 && have < want) {
            out.add(name + " " + have + "/" + want);
            if (gfs != null) {
                refill.add("/k gfs " + gfs);
            }
        }
    }

    private static int count(Inventory inv, String id) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (id.equals(CheatUtils.skyblockId(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** For the testkit: forget the last check so the next countdown runs. */
    public static void resetForTest() {
        lastCheckMs = 0;
        lastReport = null;
    }

    public static String lastReport() { return lastReport; }

    public static int checks() { return checks; }
}
