package com.killer560.hub.miningqol;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Jungle Pickaxe Alert (killer560, 2026-11-05: "an alert if you are mining with jungle pic outside of the jungle area").
 * In the Crystal Hollows, holding the Jungle Pickaxe (JUNGLE_PICKAXE) and digging (attack held on a block within
 * {@link #DIG_MS}) while standing outside the Jungle zone ({@link CrystalHollowsWalls#inJungle}) shows a movable "Jungle
 * Pickaxe!" line, with a two-tone beep once per stretch (optional). It goes {@link #LINGER_MS} after the condition clears.
 */
public final class JunglePickAlert {

    public static final String ID = "mining_jungle_pick";
    static final String TEXT = "§c§lJungle Pickaxe Outside The Jungle!";
    static final long DIG_MS = 3000L;
    static final long LINGER_MS = 500L;

    private static long lastDigMs = 0L;
    private static long lastWrongMs = 0L;
    private static boolean showing = false;
    // Testkit hooks.
    private static int shownCount = 0;
    private static int soundCount = 0;
    private static int frames = 0;
    private static String lastReason = "";

    private JunglePickAlert() {
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        Player player = client.player;
        long now = System.currentTimeMillis();
        if (!cfg.isJunglePick() || player == null || client.level == null) {
            showing = false;
            lastReason = "off";
            return;
        }
        if (McCompat.screen(client) == null && client.options.keyAttack.isDown()
                && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            lastDigMs = now;
        }
        boolean wrong = false;
        if (!MiningState.crystalHollows()) {
            lastReason = "not in the Crystal Hollows";
        } else if (!"JUNGLE_PICKAXE".equalsIgnoreCase(ItemIdentity.skyblockId(player.getMainHandItem()))) {
            lastReason = "no Jungle Pickaxe";
        } else if (now - lastDigMs > DIG_MS) {
            lastReason = "not digging";
        } else if (CrystalHollowsWalls.inJungle(player.getX(), player.getY(), player.getZ())) {
            lastReason = "in the Jungle";
        } else {
            wrong = true;
            lastReason = "outside the Jungle";
        }
        if (wrong) {
            if (!showing) {
                shownCount++;
                if (cfg.isJunglePickSound()) {
                    soundCount++;
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8f, 0.9f));
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 1.0f));
                }
            }
            showing = true;
            lastWrongMs = now;
        } else if (showing && now - lastWrongMs > LINGER_MS) {
            showing = false;
        }
    }

    static void reset() {
        lastDigMs = 0L;
        showing = false;
    }

    public static boolean showing() { return showing; }

    public static int shownCount() { return shownCount; }

    public static int soundCount() { return soundCount; }

    public static int frames() { return frames; }

    public static String lastReason() { return lastReason; }

    /** The movable alert line. */
    public static final class Popup implements HudElement {

        private static final float SCALE = 2.0f;
        private Font widthFont;
        private int widthValue;

        @Override
        public String id() { return ID; }

        @Override
        public String displayName() { return "Jungle Pickaxe Alert"; }

        @Override
        public int defaultX() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 200 : mc.getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 80 : mc.getWindow().getGuiScaledHeight() / 4 + 24;
        }

        /** TEXT never changes: measured once per Font object (a resource reload makes a new one). */
        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            if (font != widthFont) {
                widthValue = (int) Math.ceil(font.width(TEXT) * SCALE);
                widthFont = font;
            }
            return widthValue;
        }

        @Override
        public int height() { return (int) (10 * SCALE); }

        @Override
        public boolean isEnabledInSettings() { return MiningQolConfig.getInstance().isJunglePickRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean editor = HudVisibility.editorOpen();
            if (!editor && (!showing || !MiningQolConfig.getInstance().isJunglePick() || HudVisibility.hidesHud())) return;
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(SCALE, SCALE);
            graphics.text(Minecraft.getInstance().font, TEXT, 0, 0, 0xFFFFFFFF, true);
            pose.popMatrix();
            if (!editor) {
                frames++;
                HudSeen.markDrawn(ID);
            }
        }
    }
}
