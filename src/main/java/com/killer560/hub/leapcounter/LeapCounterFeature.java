package com.killer560.hub.leapcounter;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leap Counter - entry point. Ticks {@link LeapTracker}, feeds it chat (through {@link ChatObserver}, so Hypixel's
 * "You have teleported to Name!" line still arrives when another mod cancelled and re-added it), shows the title +
 * sound when the expected number of teammates have leapt to you, and owns the movable/scalable HUD element.
 * Default OFF ({@link LeapCounterConfig}); informational only, so it ships on both jars.
 * <p>
 * Alert defaults follow NoammAddons' LeapCounter ("&aEveryone Leaped!" title, experience-orb pickup sound); the HUD
 * line is its "{count}/{max} Players Leaped" shape with Devonian's colour-by-progress.
 */
public final class LeapCounterFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-leapcounter");

    private static final LeapHud LEAP_HUD = new LeapHud();
    public static final HudElement HUD = LEAP_HUD;

    private static boolean tickErrorLogged = false;

    private LeapCounterFeature() {
    }

    public static void register() {
        LeapCounterConfig.getInstance();
        ChatObserver.subscribe(LeapCounterFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(LeapCounterFeature::tick);
        // Own Fabric HUD layer, like F7 Spots' crush HUD: HudInGameRenderer only draws the ids on its own list.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "leapcounter_" + HUD.id()),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
        LOGGER.info("[LeapCounter] Registered (default OFF)");
    }

    private static void onChat(Component message) {
        if (!LeapCounterConfig.getInstance().isEnabled()) {
            return;
        }
        LeapTracker.onChat(ChatObserver.strip(message));
    }

    private static void tick(Minecraft client) {
        try {
            if (!LeapCounterConfig.getInstance().isEnabled()) {
                LeapTracker.deactivate();
                return;
            }
            LeapTracker.tick(client);
            if (LeapTracker.consumeCompletion()) {
                alert(client);
            }
        } catch (RuntimeException e) {
            // Never let a tick exception escape; log the first one so it can actually be found.
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                LOGGER.error("[LeapCounter] Tick failed", e);
            }
        }
    }

    private static void alert(Minecraft client) {
        LeapCounterConfig cfg = LeapCounterConfig.getInstance();
        if (!cfg.isAlert() || client.gui == null) {
            return;
        }
        String text = cfg.getAlertText();
        if (text != null && !text.isBlank()) {
            // Same fade/stay/fade as the crush title; "&" codes so the text box is easy to type in.
            client.gui.setTimes(0, 25, 5);
            client.gui.setTitle(Component.literal(text.replace('&', '§')));
        }
        if (cfg.isSound()) {
            // forUI(SoundEvent, float pitch, float volume) - the same call Dungeon Alerts / Auto Meow make.
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f));
        }
    }

    /** "§c1§7/§b4 §fLeaped" - red until one short, yellow at one short, green when complete. Null = nothing to show. */
    static String hudText() {
        LeapCounterConfig cfg = LeapCounterConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHud()) {
            return null;
        }
        LeapTracker.Section section = LeapTracker.section();
        int target = LeapTracker.target();
        if (section == null || target <= 0) {
            return null;
        }
        int count = LeapTracker.count();
        if (count == 0 && !cfg.isHudShowAtZero()) {
            return null;
        }
        return format(count, target, section);
    }

    private static String format(int count, int target, LeapTracker.Section section) {
        String color = count >= target ? "§a" : (target - count == 1 ? "§e" : "§c");
        return color + count + "§7/§b" + target + " §fLeaped §8(" + section.label() + ")";
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // HudVisibility, not "screen != null": chat must not hide the HUD, and the HUD editor draws the element itself.
        if (client.player == null || client.options.hideGui || HudVisibility.menuOpen() || !SkyblockGate.allows()
                || !LEAP_HUD.isVisible()) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(HUD);
        float scale = HudElementRegistry.resolveScale(HUD);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            HUD.render(graphics, 0, 0);
        } catch (RuntimeException ignored) {
            // A broken HUD element must never take down the HUD frame.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** Movable/scalable in the HUD editor, same pattern as F7 Spots' CrushHud. */
    private static final class LeapHud implements HudElement {

        @Override
        public String id() {
            return "leap_counter";
        }

        @Override
        public String displayName() {
            return "Leap Counter";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Deliberately high up: y=60 is on screen at every GUI scale (even 4x on 1080p, where anything past
            // ~270 is off the bottom), and no other element defaults to 60 (Real Time 40, Custom Scoreboard 80).
            return 60;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 11;
        }

        /** Drawn only while there is a count to show (or in the HUD editor, where the preview line stands in).
         *  Same shape as {@code etherwarp/EtherwarpHudElement.isVisible()}; chat never hides it ({@link HudVisibility}). */
        public boolean isVisible() {
            if (HudVisibility.editorOpen()) {
                return true;
            }
            return !HudVisibility.hidesHud() && hudText() != null;
        }

        /** Listed in the HUD editor only while the feature is on and you're in the F7/M7 boss - the same gate the
         *  count itself has, so it can't show up while editing HUDs in, say, the Hub. */
        @Override
        public boolean isRelevantNow() {
            LeapCounterConfig cfg = LeapCounterConfig.getInstance();
            return cfg.isEnabled() && cfg.isHud() && Floor7Tracker.inF7Boss();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            String line;
            if (HudVisibility.editorOpen()) {
                line = format(2, 4, LeapTracker.Section.S2);
            } else {
                line = hudText();
                if (line == null) {
                    return;
                }
            }
            graphics.text(client.font, line, x, y, 0xFFFFFFFF, true);
        }
    }
}
