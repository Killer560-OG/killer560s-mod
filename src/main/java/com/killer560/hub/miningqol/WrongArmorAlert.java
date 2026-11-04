package com.killer560.hub.miningqol;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Set;

/**
 * Wrong Armor Alert (killer560, 2026-11-03: "make a mining alert for if you are mining in a non mining armor set ... it
 * should just pop up something on your hud saying wrong armor"). While he is mining - on a mining island
 * ({@link MiningState#onMiningIsland}), holding a mining tool, and digging (attack held on a block within the last
 * {@link #DIG_MS}) - and any worn armor slot holds a piece that is not part of a mining set (an empty slot counts as
 * wrong), a movable "Wrong Armor!" line shows; it goes {@link #LINGER_MS} after the condition clears. Optional sound
 * (off by default) once each time it appears.
 * <p>
 * The pieces are matched by Skyblock item id ({@code ExtraAttributes.id}), case-insensitively, from each set's page on
 * hypixelskyblock.minecraft.wiki (the infobox ids, read 2026-11-03): Miner_Armor, Lapis_Armor, Prospecting_Armor,
 * Heat_Armor, Goblin_Armor, Glacite_Armor, Flamebreaker_Armor, Ember_Ash_Armor, Yog_Armor, Sorrow_Armor,
 * Armor_of_Divan, Mineral_Armor and Glossy_Mineral_Armor. Fallen Star Helmet (FALLEN_STAR_HAT) is left out: the wiki
 * lists no mining stat on it. Mixing pieces of two mining sets is not wrong.
 */
public final class WrongArmorAlert {

    public static final String ID = "mining_wrong_armor";
    static final String TEXT = "§c§lWrong Armor!";
    /** A dig counts this long after the attack key last held on a block. */
    static final long DIG_MS = 3000L;
    static final long LINGER_MS = 500L;

    private static final String[] SETS = {
            "TANK_MINER", "LAPIS_ARMOR", "MINER_OUTFIT", "HEAT", "GOBLIN", "GLACITE", "FLAME_BREAKER", "FLAMEBREAKER",
            "ARMOR_OF_YOG", "SORROW", "DIVAN", "MINERAL", "GLOSSY_MINERAL"};
    private static final String[] PIECES = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
    /** Every mining set piece id, upper case. */
    static final Set<String> MINING_ARMOR_IDS = build();

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private static long lastDigMs = 0L;
    private static long lastWrongMs = 0L;
    private static boolean showing = false;
    // Testkit hooks.
    private static int shownCount = 0;
    private static int frames = 0;
    private static String lastReason = "";

    private WrongArmorAlert() {
    }

    private static Set<String> build() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (String set : SETS) {
            for (String piece : PIECES) {
                s.add(set + "_" + piece);
            }
        }
        return Set.copyOf(s);
    }

    /** True when {@code id} is a piece of one of the mining sets. */
    public static boolean isMiningArmor(String id) {
        return id != null && MINING_ARMOR_IDS.contains(id.toUpperCase(Locale.ROOT));
    }

    /** True for a pickaxe, drill or gauntlet: a Skyblock mining tool (its id, or the "Breaking Power" lore every one has). */
    public static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = ItemIdentity.skyblockId(stack);
        if (id != null) {
            String u = id.toUpperCase(Locale.ROOT);
            if (u.contains("PICKAXE") || u.contains("DRILL") || u.contains("GAUNTLET") || u.contains("PICKONIMBUS")) {
                return true;
            }
        }
        for (String line : com.killer560.hub.cheatutils.CheatUtils.lore(stack)) {
            if (line.startsWith("Breaking Power ")) {
                return true;
            }
        }
        return false;
    }

    /** Client tick (from {@link MiningQolFeatures}). */
    /** Gap between two alarm beeps while the armor stays wrong. */
    static final long ALERT_REPEAT_MS = 1000L;
    private static long lastSoundMs = 0L;
    private static int soundCount = 0;

    /** Alarm beeps played since start (testkit). */
    public static int soundCount() { return soundCount; }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        Player player = client.player;
        long now = System.currentTimeMillis();
        if (!cfg.isWrongArmor() || player == null || client.level == null) {
            showing = false;
            lastReason = "off";
            return;
        }
        if (McCompat.screen(client) == null && client.options.keyAttack.isDown()
                && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            lastDigMs = now;
        }
        boolean wrong;
        if (!MiningState.onMiningIsland()) {
            wrong = false;
            lastReason = "not on a mining island";
        } else if (!isMiningTool(player.getMainHandItem())) {
            wrong = false;
            lastReason = "no mining tool";
        } else if (now - lastDigMs > DIG_MS) {
            wrong = false;
            lastReason = "not digging";
        } else {
            String bad = wrongPiece(player);
            wrong = bad != null;
            lastReason = wrong ? "wrong: " + bad : "mining set";
        }
        if (wrong) {
            if (!showing) {
                shownCount++;
            }
            // An alarm, not one blip: a two-tone beep every ALERT_REPEAT_MS for as long as the wrong armor holds
            // (killer560, 2026-11-03: "have the wrong armor sound keep playing and be more of an alert style").
            if (cfg.isWrongArmorSound() && now - lastSoundMs >= ALERT_REPEAT_MS) {
                lastSoundMs = now;
                soundCount++;
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8f, 0.9f));
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 1.0f));
            }
            showing = true;
            lastWrongMs = now;
        } else if (showing && now - lastWrongMs > LINGER_MS) {
            showing = false;
        }
    }

    /** The first worn slot that is not a mining piece ("HEAD: empty", "CHEST: X"), or null when all four are. */
    static String wrongPiece(Player player) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = player.getItemBySlot(slot);
            if (s.isEmpty()) {
                return slot.name() + ": empty";
            }
            String id = ItemIdentity.skyblockId(s);
            if (!isMiningArmor(id)) {
                return slot.name() + ": " + id;
            }
        }
        return null;
    }

    static void reset() {
        lastDigMs = 0L;
        lastSoundMs = 0L;
        showing = false;
    }


    public static boolean showing() { return showing; }

    public static int shownCount() { return shownCount; }

    public static int frames() { return frames; }

    public static String lastReason() { return lastReason; }

    /** The movable "Wrong Armor!" line. */
    public static final class Popup implements HudElement {

        private static final float SCALE = 2.0f;

        @Override
        public String id() { return ID; }

        @Override
        public String displayName() { return "Wrong Armor Alert"; }

        @Override
        public int defaultX() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 200 : mc.getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() == null ? 60 : mc.getWindow().getGuiScaledHeight() / 4;
        }

        @Override
        public int width() { return (int) Math.ceil(Minecraft.getInstance().font.width(TEXT) * SCALE); }

        @Override
        public int height() { return (int) (10 * SCALE); }

        @Override
        public boolean isEnabledInSettings() { return MiningQolConfig.getInstance().isWrongArmorRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean editor = HudVisibility.editorOpen();
            if (!editor && (!showing || !MiningQolConfig.getInstance().isWrongArmor() || HudVisibility.hidesHud())) {
                return;
            }
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
