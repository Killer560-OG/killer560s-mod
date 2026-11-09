package com.killer560.hub.milestone;

import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Milestone Display (featF-1108; SkyHanni DungeonMilestonesDisplay): a small HUD "Milestone: N" counting the dungeon
 * milestone lines Hypixel prints ("Milestone ❖: You have dealt 1.2M damage so far! (3s)") this run, red at 0 and 1,
 * yellow at 2, green at 3 (the last one). The count is per world: a new dungeon is a new level and starts at 0. Reads
 * the line through {@link ChatObserver}, hides nothing. The HUD is drawn by {@code hud/HudInGameRenderer}.
 */
public final class MilestoneFeature {

    /** SkyHanni's {@code dungeon.milestone.colorless} shape, with the damage / tank / heal wording of all three classes. The prefix before "Milestone" has no colon or bracket, so a player's chat ("Eve: Milestone ...", "[MVP+] Eve: ...") never counts. */
    static final Pattern MILESTONE = Pattern.compile("^[^:\\[]{0,6}Milestone .: You have (?:tanked and )?(?:dealt|healed) .*so far! .*$");

    public static final HudElement HUD = new MilestoneHud();

    private static final int MAX = 3;
    private static int count;
    private static Object level;

    private MilestoneFeature() {
    }

    public static void register() {
        MilestoneConfig.getInstance();
        ChatObserver.subscribe(MilestoneFeature::onChat);
    }

    private static void onChat(Component message) {
        if (!MilestoneConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        String plain = ChatObserver.strip(message);
        if (plain.length() < 200 && plain.contains("Milestone ") && MILESTONE.matcher(plain).matches()) {
            sync();
            count = Math.min(MAX, count + 1);
        }
    }

    /** A new world (a new run) starts at zero. */
    private static void sync() {
        Object now = Minecraft.getInstance().level;
        if (now != level) {
            level = now;
            count = 0;
        }
    }

    /** The milestones counted this run; 0 before the first. Public for the testkit. */
    public static int count() {
        sync();
        return count;
    }

    /** Test hook: back to zero. */
    public static void resetForTest() {
        sync();
        count = 0;
    }

    private static final class MilestoneHud extends BasicHud {
        MilestoneHud() {
            super("milestone", "Milestone", 10, 380);
        }

        @Override
        public int width() {
            return 70;
        }

        @Override
        public int height() {
            return 9;
        }

        @Override
        public boolean isEnabledInSettings() {
            return MilestoneConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Minecraft client = Minecraft.getInstance();
            boolean editor = McCompat.screen(client) instanceof HudEditorScreen;
            int n = editor ? 2 : count();
            if (!editor && (n == 0 || !MilestoneConfig.getInstance().isEnabled())) {
                return;
            }
            int color = n >= MAX ? 0xFF55FF55 : n == 2 ? 0xFFFFFF55 : 0xFFFF5555;
            graphics.text(client.font, "Milestone: " + n, x, y, color, true);
            HudSeen.markDrawn(id());
        }
    }
}
