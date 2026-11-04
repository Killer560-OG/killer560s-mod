package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Commission HUD (legit, both builds; killer560 2026-11-01: "a on screen commission tracker hud and the waypoints for where
 * that commission is. Then if the commission is done have it show emmisaries").
 *
 * <p>The HUD is the tab list's Commissions widget ({@link MiningState}) on a movable element, each commission's progress
 * colored; a mithril or titanium commission's progress moves on with every counted block between the tab's slow refreshes
 * ({@link CommissionProgress}, Live Progress). The waypoints are labels in the world at the place each open commission is done: the five Dwarven Mines areas
 * for an area commission, and the Glacite Tunnels gemstone spots for a "[Gemstone] Collector" commission. When any
 * commission reads DONE, every Emissary gets a label too, so a finished commission can be turned in at the nearest one. The coordinates are Skyblocker's MiningLocationLabel (LGPL-3.0; read for
 * the in-game positions only, no code taken). Labels show through walls, like a waypoint; nothing is clicked or sent.
 */
public final class CommissionHud {

    public static final String HUD_ID = "mining_commissions";

    private record Spot(String label, int color, BlockPos pos) {
    }

    private static final int MITHRIL_COLOR = 0xFF45BDE0;
    private static final int TITANIUM_COLOR = 0xFFD8D6D8;

    private static final List<Spot> AREAS = List.of(
            new Spot("Lava Springs", 0, new BlockPos(60, 197, -15)),
            new Spot("Cliffside Veins", 0, new BlockPos(40, 128, 40)),
            new Spot("Rampart's Quarry", 0, new BlockPos(-100, 150, -20)),
            new Spot("Upper Mines", 0, new BlockPos(-130, 174, -50)),
            new Spot("Royal Mines", 0, new BlockPos(130, 154, 30)));

    private static final List<Spot> EMISSARIES = List.of(
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(58, 198, -8)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(42, 134, 22)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(-72, 153, -10)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(-132, 174, -50)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(171, 150, 31)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(-37, 200, -131)),
            new Spot("Emissary", 0xFFFFFFFF, new BlockPos(89, 198, -92)));

    /** The Emissaries' spots (Commission Route's claim line goes to the nearest). */
    static List<BlockPos> emissaryPositions() {
        List<BlockPos> out = new ArrayList<>();
        for (Spot s : EMISSARIES) {
            out.add(s.pos());
        }
        return out;
    }

    private record Gem(String name, int color, BlockPos[] spots) {
    }

    private static final List<Gem> GEMS = List.of(
            new Gem("Aquamarine", 0xFF334CB1, new BlockPos[]{new BlockPos(20, 136, 370), new BlockPos(-14, 132, 386),
                    new BlockPos(6, 137, 411), new BlockPos(50, 117, 302)}),
            new Gem("Onyx", 0xFF777777, new BlockPos[]{new BlockPos(4, 127, 307), new BlockPos(-3, 139, 434),
                    new BlockPos(77, 118, 411), new BlockPos(-68, 130, 404)}),
            new Gem("Peridot", 0xFF667F33, new BlockPos[]{new BlockPos(66, 144, 284), new BlockPos(94, 154, 284),
                    new BlockPos(-62, 147, 303), new BlockPos(-77, 119, 283), new BlockPos(87, 122, 394),
                    new BlockPos(-73, 122, 456)}),
            new Gem("Citrine", 0xFF9C7A52, new BlockPos[]{new BlockPos(-86, 143, 261), new BlockPos(74, 150, 327),
                    new BlockPos(63, 137, 343), new BlockPos(38, 119, 386), new BlockPos(55, 150, 400),
                    new BlockPos(-45, 127, 415), new BlockPos(-60, 144, 424), new BlockPos(-54, 132, 410)}));

    private static volatile List<String> hudLines = List.of();
    private static volatile List<Spot> waypoints = List.of();
    private static int lastVersion = -1;
    private static boolean lastWaypoints;
    private static boolean lastEmissaries;
    private static boolean lastColor;
    private static int lastProgress = -1;
    private static int labelFrames = 0;
    private static volatile Component lastLabel;

    private CommissionHud() {
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isCommissionHud() || !MiningState.commissionsKnown() || cfg.commissionsHiddenNow()) {
            hudLines = List.of();
            waypoints = List.of();
            lastVersion = -1;
            return;
        }
        int v = MiningState.version();
        int pv = CommissionProgress.version() * 2 + (cfg.isCommissionInterpolate() ? 1 : 0);
        if (v == lastVersion && pv == lastProgress && lastWaypoints == cfg.isCommissionWaypoints()
                && lastEmissaries == cfg.isCommissionEmissaries() && lastColor == cfg.isCommissionColorProgress()) {
            return;
        }
        lastVersion = v;
        lastProgress = pv;
        lastWaypoints = cfg.isCommissionWaypoints();
        lastEmissaries = cfg.isCommissionEmissaries();
        lastColor = cfg.isCommissionColorProgress();
        List<MiningState.Commission> coms = MiningState.commissions();
        hudLines = buildLines(coms, cfg.isCommissionColorProgress());
        waypoints = MiningState.dwarvenMines() ? buildWaypoints(coms, cfg.isCommissionWaypoints(),
                cfg.isCommissionEmissaries()) : List.of();
    }

    static List<String> buildLines(List<MiningState.Commission> coms, boolean color) {
        if (coms.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("§6§lCommissions");
        for (MiningState.Commission c : coms) {
            String shown = CommissionProgress.shown(c);
            out.add("§f" + c.name() + ": " + (color ? progressColor(shown) : "§f") + shown);
        }
        return List.copyOf(out);
    }

    private static String progressColor(String value) {
        if (value.equals("DONE")) {
            return "§a";
        }
        try {
            double pct = Double.parseDouble(value.substring(0, value.length() - 1));
            return pct >= 75 ? "§e" : pct >= 25 ? "§6" : "§c";
        } catch (RuntimeException e) {
            return "§f";
        }
    }

    private static List<Spot> buildWaypoints(List<MiningState.Commission> coms, boolean places, boolean emissaries) {
        List<Spot> out = new ArrayList<>();
        if (places) {
            for (MiningState.Commission c : coms) {
                if (c.done()) {
                    continue;
                }
                for (Spot area : AREAS) {
                    if (c.name().startsWith(area.label() + " ")) {
                        boolean ti = c.name().endsWith("Titanium");
                        out.add(new Spot(c.name(), ti ? TITANIUM_COLOR : MITHRIL_COLOR, area.pos()));
                    }
                }
                for (Gem g : GEMS) {
                    if (c.name().startsWith(g.name() + " ")) {
                        for (BlockPos p : g.spots()) {
                            out.add(new Spot(g.name(), g.color(), p));
                        }
                    }
                }
            }
        }
        if (emissaries && MiningState.anyDone(coms)) {
            out.addAll(EMISSARIES);
        }
        return List.copyOf(out);
    }


    static void render(LevelRenderContext context) {
        List<Spot> snap = waypoints;
        if (snap.isEmpty() || !MiningState.dwarvenMines()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        PoseStack poseStack = context.poseStack();
        if (poseStack == null || client.player == null) {
            return;
        }
        Vec3 cam = McRender.cameraPos(context);
        Font font = client.font;
        for (Spot s : snap) {
            double x = s.pos().getX() + 0.5;
            double y = s.pos().getY() + 1.5;
            double z = s.pos().getZ() + 0.5;
            double d = Math.sqrt(cam.distanceToSqr(x, y, z));
            Component text = label(s.label(), Math.round(d));
            lastLabel = text;
            float scale = 0.025f * (float) Math.min(12.0, Math.max(1.0, d / 10.0));
            poseStack.pushPose();
            try {
                poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
                McRender.rotate(poseStack, McRender.cameraRotation(context));
                poseStack.scale(scale, -scale, scale);
                McRender.drawText(context, font, text, -McRender.textWidth(font, text) / 2f, -font.lineHeight / 2f,
                        s.color() | 0xFF000000, false, poseStack, Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
        labelFrames++;
    }

    /**
     * A waypoint's floating label: the name in the spot's color (the draw color) and the distance in gray, as Component
     * styling. It was the string {@code label + " §7(" + d + "m)"}, and the world-text path printed the code
     * (killer560, 2026-11-03: "Emissary §7(186m)").
     */
    public static Component label(String name, long meters) {
        return Component.literal(name).append(Component.literal(" (" + meters + "m)").withStyle(ChatFormatting.GRAY));
    }


    public static List<String> lines() {
        return hudLines;
    }

    /** "label@x,y,z" per waypoint now shown. */
    public static List<String> waypointNames() {
        List<String> out = new ArrayList<>();
        for (Spot s : waypoints) {
            out.add(String.format(Locale.ROOT, "%s@%d,%d,%d", s.label(), s.pos().getX(), s.pos().getY(), s.pos().getZ()));
        }
        return out;
    }

    public static int labelFrames() {
        return labelFrames;
    }

    /** The last waypoint label drawn, or null (testkit). */
    public static Component lastLabel() {
        return lastLabel;
    }

    /** The movable HUD element. */
    public static final class Element implements HudElement {

        private static final List<String> SAMPLE = List.of("§6§lCommissions", "§fGoblin Slayer: §e75%",
                "§fLava Springs Mithril: §c12%", "§fTitanium Miner: §aDONE");

        private List<String> shown() {
            List<String> l = hudLines;
            if (l.isEmpty() && HudVisibility.editorOpen()) {
                return SAMPLE;
            }
            return l;
        }

        @Override
        public String id() {
            return HUD_ID;
        }

        @Override
        public String displayName() {
            return "Commission HUD";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 120;
        }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            int w = 1;
            for (String s : shown()) {
                w = Math.max(w, font.width(s));
            }
            return Math.max(w, 60);
        }

        @Override
        public int height() {
            return HudText.height(Math.max(1, shown().size()), 10);
        }

        @Override
        public boolean isEnabledInSettings() {
            return MiningQolConfig.getInstance().isCommissionHudRaw();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MiningQolConfig.getInstance().isCommissionHud() || HudVisibility.hidesHud()) {
                return;
            }
            List<String> real = hudLines;
            Font font = Minecraft.getInstance().font;
            int lineY = y;
            for (String s : shown()) {
                graphics.text(font, s, x, lineY, 0xFFFFFFFF, true);
                lineY += 10;
            }
            if (!real.isEmpty()) {
                HudSeen.markDrawn(HUD_ID);
            }
        }
    }
}
