package com.killer560.hub.abilitytimers;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.killer560.hub.compat.McCompat;

/** Ticks every configured {@link AbilityTimerEntry}'s raw keybind (same hardware-poll pattern
 *  {@code Killer560ModClient}'s HUD-edit keybind already uses, not routed through any screen) and draws
 *  a HUD list of whichever timers are currently counting down. Deliberately simple - no chat/action-bar
 *  detection of a real ability use, since this session couldn't verify any real Hypixel trigger text
 *  for Necron's Handle's mask against a live game; killer560 starts a timer himself by pressing its key
 *  the moment he uses the ability, same as a manual stopwatch. */
public final class AbilityTimersFeature {

    private static final Map<String, Boolean> keyWasDown = new HashMap<>();

    private AbilityTimersFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AbilityTimersFeature", client -> tick()));
    }

    private static void tick() {
        AbilityTimersConfig cfg = AbilityTimersConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to poll raw key
        // state unconditionally, with no check for a screen being open - typing a timer's own bound key
        // into party chat, or into this tab's own "Name" EditBox, silently restarted that timer as if the
        // ability had just been used. The render side already guarded on this same check; tick() didn't.
        // Skips the whole loop (rather than updating keyWasDown from unreliable-while-typing key state)
        // so closing the screen doesn't itself cause a false "just pressed" edge on the next real tick.
        if (McCompat.screen(client) != null) {
            return;
        }
        for (AbilityTimerEntry e : cfg.entries()) {
            if (!e.enabled || e.keyCode < 0) {
                continue;
            }
            boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), e.keyCode);
            boolean wasDown = keyWasDown.getOrDefault(e.id, false);
            if (down && !wasDown) {
                e.start(System.currentTimeMillis());
            }
            keyWasDown.put(e.id, down);
        }
    }

    private static List<AbilityTimerEntry> runningEntries() {
        long now = System.currentTimeMillis();
        List<AbilityTimerEntry> running = new ArrayList<>();
        for (AbilityTimerEntry e : AbilityTimersConfig.getInstance().entries()) {
            if (e.enabled && e.isRunning(now)) {
                running.add(e);
            }
        }
        return running;
    }

    public static final class TimersHudElement implements HudElement {
        @Override
        public String id() {
            return "ability_timers";
        }

        @Override
        public String displayName() {
            return "Ability Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 200;
        }

        @Override
        public int width() {
            // Swatch + the widest drawn line (2026-10-07 box audit: it was a fixed 150). With nothing running it
            // draws nothing, and the box keeps a grabbable 150 for the HUD editor.
            List<AbilityTimerEntry> entries = runningEntries();
            if (entries.isEmpty()) {
                return 150;
            }
            long now = System.currentTimeMillis();
            int w = 1;
            for (AbilityTimerEntry e : entries) {
                w = Math.max(w, 12 + Minecraft.getInstance().font.width(lineText(e, now)));
            }
            return w;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(runningEntries().size(), 12);
        }

        private static String lineText(AbilityTimerEntry e, long now) {
            return String.format(Locale.US, "%s: %.1fs", e.name, (e.expiresAtMs - now) / 1000.0);
        }

        @Override
        public boolean isEnabledInSettings() {
            return AbilityTimersConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!AbilityTimersConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            long now = System.currentTimeMillis();
            int lineY = y;
            for (AbilityTimerEntry e : runningEntries()) {
                graphics.fill(x, lineY + 1, x + 8, lineY + 9, e.color());
                graphics.text(Minecraft.getInstance().font, lineText(e, now), x + 12, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
