package com.killer560.hub.ahflip;

import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /ahmenudump} (dev and cheat builds only; P3 of the 2026-11-01 Doc audit): records Hypixel's Auction House menus
 * as he opens them, so Auto Relist's menu names and slots ({@link AhRelistMacro}, today taken from public mods) can be
 * checked against the real thing. Like {@code /simbosscapture}: <b>reading only</b>, it never clicks, types or sends.
 *
 * <p>Typed once, it records for {@link #ARM_MS} (or until typed again): every container screen that opens (title, size,
 * and each non-empty slot's index, item id, count, name, lore lines as text and SkyBlock id), each time its items
 * settle into a new set; every sign editor's four lines; and every chat line about auctions. Everything is appended to
 * {@code killer560smod-ahflip-menudump.txt} (config/killer560/skyblock/ahflip/), which is not carried in profiles.
 * The menus wanted (killer560 opens them once): /ah, View Bids, a won auction's BIN Auction View (Collect Auction),
 * Manage Auctions with a sold and an expired listing, Create Auction / Create BIN Auction with an item in it, Set Price's
 * sign, Auction Duration, Confirm BIN Auction, and a buy's BIN Auction View and Confirm Purchase.
 */
public final class AhMenuRecorder {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Menu Dump";
    public static final long ARM_MS = 15 * 60_000L;
    private static final long SETTLE_MS = 300L;
    /** Chat lines worth keeping while armed (auction words), checked lower case. */
    private static final String[] CHAT_WORDS = {"auction", "bin", "purchased", "collected", "claimed", "bought", "bid",
            "coins", "escrow", "listing"};

    private static volatile long armedUntilMs;
    private static int seenContainer = Integer.MIN_VALUE;
    private static String seenSignature = "";
    private static long seenSinceMs;
    private static final Set<String> WRITTEN = new HashSet<>();
    private static Screen lastSign;
    private static volatile int pagesWritten;

    private AhMenuRecorder() {
    }

    static void register() {
        if (!com.killer560.hub.BuildVariant.DEV_TOOLS && !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("ahmenudump").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(AhMenuRecorder::toggle);
                    return 1;
                })));
        ClientTickEvents.END_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.end("AhMenuRecorder.tick",
                AhMenuRecorder::tick));
        com.killer560.hub.util.ChatObserver.subscribe(message -> {
            if (!isArmed()) {
                return;
            }
            String line = com.killer560.hub.util.ChatObserver.strip(message).trim();
            String lc = line.toLowerCase(Locale.ROOT);
            for (String w : CHAT_WORDS) {
                if (lc.contains(w)) {
                    append("CHAT " + message.getString());
                    return;
                }
            }
        });
    }

    public static Path dumpPath() {
        return ModPaths.config("killer560smod-ahflip-menudump.txt");
    }

    public static boolean isArmed() {
        return System.currentTimeMillis() < armedUntilMs;
    }

    /** Starts recording, or stops it when it is on. */
    public static void toggle() {
        if (isArmed()) {
            armedUntilMs = 0L;
            append("==== stopped " + LocalDateTime.now() + " (" + pagesWritten + " page(s))");
            ModChat.send(CHAT, ModChat.text("Stopped. " + pagesWritten + " page(s) written to "),
                    ModChat.value(dumpPath().getFileName().toString()));
            return;
        }
        armedUntilMs = System.currentTimeMillis() + ARM_MS;
        WRITTEN.clear();
        seenContainer = Integer.MIN_VALUE;
        seenSignature = "";
        lastSign = null;
        pagesWritten = 0;
        append("==== recording " + LocalDateTime.now() + " (read only; nothing is clicked or sent)");
        ModChat.send(CHAT, ModChat.text("Recording Auction House menus for 15 minutes - open them now: "),
                ModChat.dim("/ah, View Bids, a won auction, Manage Auctions (sold and expired), Create BIN Auction with an "
                        + "item, Set Price, Auction Duration, Confirm BIN Auction, a buy's page and Confirm Purchase. "
                        + "/ahmenudump again to stop."));
        LOGGER.info("[AhMenuDump] recording to {}", dumpPath());
    }

    private static void tick(Minecraft client) {
        if (armedUntilMs == 0L) {
            return;
        }
        if (!isArmed()) {
            toggle(); // the time ran out: says so and writes the end line
            return;
        }
        Screen screen = McCompat.screen(client);
        if (screen instanceof AbstractSignEditScreen sign) {
            if (sign != lastSign) {
                lastSign = sign;
                String[] lines = ((SignEditScreenAccessor) sign).killer560smod$getMessages();
                append("SIGN opened " + Arrays.toString(lines));
            }
            return;
        }
        if (lastSign != null) {
            // What he typed, as the sign closed (its update packet carries these lines).
            String[] lines = ((SignEditScreenAccessor) lastSign).killer560smod$getMessages();
            append("SIGN closed " + Arrays.toString(lines));
            lastSign = null;
        }
        if (!(screen instanceof AbstractContainerScreen<?> c)) {
            seenContainer = Integer.MIN_VALUE;
            return;
        }
        long now = System.currentTimeMillis();
        String sig = BazaarFlipMenus.signature(c.getMenu());
        if (c.getMenu().containerId != seenContainer || !sig.equals(seenSignature)) {
            seenContainer = c.getMenu().containerId;
            seenSignature = sig;
            seenSinceMs = now;
            return;
        }
        if (now - seenSinceMs < SETTLE_MS) {
            return;
        }
        String id = BazaarFlipMenus.titleOf(c) + "#" + sig;
        if (!WRITTEN.add(id)) {
            return;
        }
        append(describe(c));
        pagesWritten++;
        ModChat.log(CHAT, ModChat.dim("Recorded \"" + BazaarFlipMenus.titleOf(c) + "\""));
    }

    /** The whole page as text: what the dump file holds for one menu. */
    static String describe(AbstractContainerScreen<?> c) {
        StringBuilder sb = new StringBuilder();
        var menu = c.getMenu();
        int top = BazaarFlipMenus.menuSlots(menu).size();
        sb.append("MENU \"").append(BazaarFlipMenus.titleOf(c)).append("\" raw=\"").append(c.getTitle().getString())
                .append("\" class=").append(menu.getClass().getSimpleName()).append(" menuSlots=").append(top)
                .append(" containerId=").append(menu.containerId).append(" at ").append(LocalDateTime.now()).append('\n');
        for (Slot slot : menu.slots) {
            ItemStack s = slot.getItem();
            if (s.isEmpty()) {
                continue;
            }
            sb.append(slot.index < top ? "  slot " : "  inv  ").append(slot.index).append(": ")
                    .append(BuiltInRegistries.ITEM.getKey(s.getItem())).append(" x").append(s.getCount())
                    .append(" name=\"").append(s.getHoverName().getString()).append('"');
            CustomData data = s.get(DataComponents.CUSTOM_DATA);
            if (data != null) {
                String sbId = data.copyTag().getStringOr("id", "");
                if (!sbId.isEmpty()) {
                    sb.append(" id=").append(sbId);
                }
            }
            sb.append('\n');
            if (slot.index >= top) {
                continue; // his own items: name and id only
            }
            ItemLore lore = s.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    sb.append("      | ").append(line.getString()).append('\n');
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    private static synchronized void append(String text) {
        try {
            Path p = dumpPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, text + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("[AhMenuDump] couldn't write {}: {}", dumpPath(), e.toString());
        }
    }

    /** Testkit: pages written since recording started. */
    public static int pagesWrittenForTest() {
        return pagesWritten;
    }
}
