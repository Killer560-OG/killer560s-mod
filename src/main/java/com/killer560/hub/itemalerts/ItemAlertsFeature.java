package com.killer560.hub.itemalerts;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.itemrarity.ItemRarity;
import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item Alerts (bottlealert-1118): two HUD alerts read off the player's inventory, display only.
 *
 * <p>Bottle Full Alert: the chargeable bottles ({@code THUNDER_IN_A_BOTTLE}, {@code STORM_IN_A_BOTTLE},
 * {@code HURRICANE_IN_A_BOTTLE}, empty form {@code ..._EMPTY}, max 50,000 / 500,000 / 5,000,000 charges; ids and lore
 * from the NEU item repo) alert once per item when the id is the full one or its "Charge: N/M" lore line reaches M.
 * Runebook Rarity Alert: {@code RUNEBOOK} alerts when its lore rarity goes up (it gains a tier per digit of its
 * Runic kill counter). Each item is tracked by its custom-data uuid, so moving it or opening a menu never fires, and
 * whatever is in the inventory at join (or when a switch is turned on) is taken as the baseline and never alerts.
 * Event driven: a container packet only sets a dirty flag, the next client tick scans the 41 slots once.
 */
public final class ItemAlertsFeature {

    private static final Pattern CHARGE = Pattern.compile("Charge: ([\\d,]{1,12})/([\\d,]{1,12})");
    private static final String FULL_SUFFIX = "_IN_A_BOTTLE";
    private static final String EMPTY_SUFFIX = "_IN_A_BOTTLE_EMPTY";
    private static final String RUNEBOOK = "RUNEBOOK";
    /** After a join the first full inventory packet is the baseline; give up waiting for it after this long. */
    private static final long BASELINE_WAIT_MS = 5000L;
    private static final int MAX_TRACKED = 256;

    /** One line on the HUD: {@code pre} + {@code mid} (in {@code midColor}) + {@code post}. */
    private static final class Alert {
        final boolean runebook;
        final String key;
        final String pre;
        final String mid;
        final String post;
        final int midColor;
        final long startMs;
        int width = -1;

        Alert(boolean runebook, String key, String pre, String mid, int midColor, String post, long startMs) {
            this.runebook = runebook;
            this.key = key;
            this.pre = pre;
            this.mid = mid;
            this.midColor = midColor;
            this.post = post;
            this.startMs = startMs;
        }

        int width(Font font) {
            if (width < 0) {
                width = font.width(pre) + font.width(mid) + font.width(post);
            }
            return width;
        }
    }

    // All of this runs on the client thread: the packet mixin's TAIL, the tick and the HUD render.
    private static final List<Alert> ACTIVE = new ArrayList<>();
    private static final Set<String> SEEN_FULL = new LinkedHashSet<>();
    /** Bottles seen below max (by key), and the bottle types that had one in the previous / this scan: a bottle alerts only when it was seen filling. */
    private static final Set<String> NOT_FULL = new LinkedHashSet<>();
    private static Set<String> prevBases = new HashSet<>();
    private static Set<String> curBases = new HashSet<>();
    private static final Map<String, ItemRarity> RUNEBOOK_RARITY = new HashMap<>();
    private static final Set<String> PRESENT = new HashSet<>();
    private static boolean dirty = true;
    /** The next scan only records what is there (join, a switch turned on): nothing alerts. */
    private static boolean rebase = true;
    /** Between a join and its first full inventory packet: no scan, so a persistent alert is not dropped by an empty inventory. */
    private static boolean awaitingContent = false;
    private static long joinMs = 0L;
    private static long alertsFired = 0L;
    private static Alert lastBottle = null;
    private static Alert lastRunebook = null;

    private ItemAlertsFeature() {
    }

    public static void register() {
        ItemAlertsConfig.getInstance();
        HudElementRegistry.register(new AlertHud("bottle_full_alert", "Bottle Full Alert", 10, 60, false));
        HudElementRegistry.register(new AlertHud("runebook_rarity_alert", "Runebook Rarity Alert", 10, 80, true));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ItemAlerts", ItemAlertsFeature::tick));
    }

    /** A setting changed or loaded: what is in the inventory now becomes the baseline. */
    public static void refresh() {
        rebase = true;
        dirty = true;
    }

    private static void onJoin() {
        awaitingContent = true;
        joinMs = System.currentTimeMillis();
        rebase = true;
        dirty = true;
    }

    /** Render thread, tail of a container slot or contents packet ({@code ItemAlertsPacketMixin}). */
    public static void onContainerPacket(int containerId, boolean contents) {
        if (awaitingContent && contents && containerId == 0) {
            awaitingContent = false;
        }
        dirty = true;
    }

    /** For the testkit: what a join does, without a connection. */
    public static void joinForTest() {
        onJoin();
    }

    private static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (awaitingContent && now - joinMs > BASELINE_WAIT_MS) {
            awaitingContent = false;
        }
        if (dirty && !awaitingContent) {
            dirty = false;
            scan(client);
        }
        if (!ACTIVE.isEmpty()) {
            ItemAlertsConfig cfg = ItemAlertsConfig.getInstance();
            for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                if (expired(cfg, ACTIVE.get(i), now)) {
                    ACTIVE.remove(i);
                }
            }
        }
    }

    private static boolean expired(ItemAlertsConfig cfg, Alert a, long now) {
        boolean persistent = a.runebook ? cfg.isRunebookPersistent() : cfg.isBottlePersistent();
        int seconds = a.runebook ? cfg.getRunebookSeconds() : cfg.getBottleSeconds();
        return !persistent && now - a.startMs >= seconds * 1000L;
    }

    /** Scans the inventory (and the carried stack) once. Public for the testkit; the tick calls it when dirty. */
    public static void scan(Minecraft client) {
        if (client.player == null) {
            return;
        }
        ItemAlertsConfig cfg = ItemAlertsConfig.getInstance();
        boolean bottles = cfg.isBottleEnabled();
        boolean runebooks = cfg.isRunebookEnabled();
        boolean quiet = rebase;
        rebase = false;
        if (!bottles && !runebooks) {
            ACTIVE.clear();
            return;
        }
        PRESENT.clear();
        curBases.clear();
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            look(inv.getItem(i), bottles, runebooks, quiet, cfg);
        }
        look(client.player.containerMenu.getCarried(), bottles, runebooks, quiet, cfg);
        ACTIVE.removeIf(a -> !PRESENT.contains(a.key) || (a.runebook ? !runebooks : !bottles));
        Set<String> t = prevBases;
        prevBases = curBases;
        curBases = t;
        trim(SEEN_FULL);
        trim(NOT_FULL);
        if (RUNEBOOK_RARITY.size() > MAX_TRACKED) {
            RUNEBOOK_RARITY.clear();
        }
    }

    private static void trim(Set<String> set) {
        for (Iterator<String> it = set.iterator(); set.size() > MAX_TRACKED && it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    private static void look(ItemStack stack, boolean bottles, boolean runebooks, boolean quiet, ItemAlertsConfig cfg) {
        CompoundTag tag = ItemNbt.view(stack);
        if (tag == null) {
            return;
        }
        String id = tag.getStringOr("id", "");
        if (id.endsWith(FULL_SUFFIX) || id.endsWith(EMPTY_SUFFIX)) {
            if (bottles) {
                bottle(stack, tag, id, quiet, cfg);
            }
        } else if (runebooks && id.equals(RUNEBOOK)) {
            runebook(stack, tag, quiet, cfg);
        }
    }

    private static String keyOf(CompoundTag tag, String base) {
        return base + ":" + tag.getStringOr("uuid", "");
    }

    private static void bottle(ItemStack stack, CompoundTag tag, String id, boolean quiet, ItemAlertsConfig cfg) {
        boolean empty = id.endsWith(EMPTY_SUFFIX);
        String base = id.substring(0, id.length() - (empty ? EMPTY_SUFFIX.length() : FULL_SUFFIX.length()));
        String name = switch (base) {
            case "THUNDER" -> "Thunder in a Bottle";
            case "STORM" -> "Storm in a Bottle";
            case "HURRICANE" -> "Hurricane in a Bottle";
            default -> null;
        };
        if (name == null) {
            return;
        }
        String key = keyOf(tag, base);
        PRESENT.add(key);
        if (empty && !chargeFull(stack)) {
            NOT_FULL.add(key);
            curBases.add(base);
            return;
        }
        // Full: alert once, and only for a bottle seen filling (its own key, or a bottle of its type that was below max in the scan before).
        boolean filled = NOT_FULL.remove(key) | prevBases.contains(base);
        if (!SEEN_FULL.add(key) || quiet || !filled) {
            return;
        }
        ItemRarity r = base.equals("HURRICANE") ? ItemRarity.LEGENDARY : ItemRarity.EPIC;
        fire(new Alert(false, key, "", name, 0xFF000000 | r.rgb, " is full!", System.currentTimeMillis()), cfg.isBottleSound(), 1.6f);
    }

    /** The "Charge: N/M" lore line says N has reached M. */
    private static boolean chargeFull(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGE.matcher(line.getString());
            if (m.find()) {
                String max = m.group(2);
                return !max.equals("0") && m.group(1).equals(max);
            }
        }
        return false;
    }

    private static void runebook(ItemStack stack, CompoundTag tag, boolean quiet, ItemAlertsConfig cfg) {
        ItemRarity r = ItemRarityFeature.getRarity(stack);
        if (r == null) {
            return;
        }
        String key = keyOf(tag, RUNEBOOK);
        PRESENT.add(key);
        ItemRarity before = RUNEBOOK_RARITY.put(key, r);
        if (quiet || before == null || r.ordinal() <= before.ordinal()) {
            return;
        }
        fire(new Alert(true, key, "Runebook upgraded to ", r.loreName, 0xFF000000 | r.rgb, "!", System.currentTimeMillis()),
                cfg.isRunebookSound(), 1.2f);
    }

    private static void fire(Alert alert, boolean sound, float pitch) {
        ACTIVE.removeIf(a -> a.key.equals(alert.key));
        ACTIVE.add(alert);
        alertsFired++;
        if (sound) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1.0f));
        }
    }

    /** For the testkit: alerts raised since the game started. */
    public static long alertsFired() {
        return alertsFired;
    }

    /** For the testkit: how many alerts are live for bottles (false) or the runebook (true). */
    public static int activeCount(boolean runebook) {
        int n = 0;
        for (int i = 0; i < ACTIVE.size(); i++) {
            if (ACTIVE.get(i).runebook == runebook) {
                n++;
            }
        }
        return n;
    }

    /** For the testkit: the text of the last line a bottle (false) or runebook (true) alert HUD drew, or null. */
    public static String lastDrawn(boolean runebook) {
        Alert a = runebook ? lastRunebook : lastBottle;
        return a == null ? null : a.pre + a.mid + a.post;
    }

    /** For the testkit: forget every alert and everything tracked, as a fresh game. */
    public static void resetForTest() {
        ACTIVE.clear();
        SEEN_FULL.clear();
        NOT_FULL.clear();
        prevBases.clear();
        curBases.clear();
        RUNEBOOK_RARITY.clear();
        lastBottle = null;
        lastRunebook = null;
        awaitingContent = false;
        refresh();
    }

    private static boolean visible(Alert a, boolean runebook, long now) {
        return a.runebook == runebook && !expired(ItemAlertsConfig.getInstance(), a, now);
    }

    private static final class AlertHud extends BasicHud {
        private final boolean runebook;

        AlertHud(String id, String name, int x, int y, boolean runebook) {
            super(id, name, x, y);
            this.runebook = runebook;
        }

        private static boolean editor() {
            return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
        }

        private String exampleMid() {
            return runebook ? "LEGENDARY" : "Hurricane in a Bottle";
        }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            long now = System.currentTimeMillis();
            int w = 0;
            for (int i = 0; i < ACTIVE.size(); i++) {
                Alert a = ACTIVE.get(i);
                if (visible(a, runebook, now)) {
                    w = Math.max(w, a.width(font));
                }
            }
            if (w == 0) {
                w = font.width(runebook ? "Runebook upgraded to LEGENDARY!" : "Hurricane in a Bottle is full!");
            }
            return 2 + w;
        }

        @Override
        public int height() {
            int n = Math.max(1, activeCount(runebook));
            return 1 + n * HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            ItemAlertsConfig cfg = ItemAlertsConfig.getInstance();
            return runebook ? cfg.isRunebookEnabled() : cfg.isBottleEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Font font = Minecraft.getInstance().font;
            int text = ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE));
            long now = System.currentTimeMillis();
            int row = 0;
            for (int i = 0; i < ACTIVE.size(); i++) {
                Alert a = ACTIVE.get(i);
                if (!visible(a, runebook, now)) {
                    continue;
                }
                int ly = y + 1 + row++ * HudText.ROW;
                int lx = x + 1;
                graphics.text(font, a.pre, lx, ly, text, true);
                lx += font.width(a.pre);
                graphics.text(font, a.mid, lx, ly, a.midColor, true);
                graphics.text(font, a.post, lx + font.width(a.mid), ly, text, true);
                if (runebook) {
                    lastRunebook = a;
                } else {
                    lastBottle = a;
                }
            }
            if (row == 0 && editor()) {
                String pre = runebook ? "Runebook upgraded to " : "";
                graphics.text(font, pre, x + 1, y + 1, text, true);
                graphics.text(font, exampleMid(), x + 1 + font.width(pre), y + 1, 0xFF000000 | ItemRarity.LEGENDARY.rgb, true);
                graphics.text(font, runebook ? "!" : " is full!", x + 1 + font.width(pre) + font.width(exampleMid()), y + 1, text, true);
                row = 1;
            }
            if (row > 0) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
