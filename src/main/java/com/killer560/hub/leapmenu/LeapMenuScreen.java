package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opened by {@code /killer560 leaporder}. A reference/organizer menu for the current dungeon party -
 *  NOT an auto-targeting or auto-leap tool (this mod doesn't automate the Spirit Leap ability itself).
 *  Same sorting concept Odin/Noam's own leap menus offer (party order / alphabetical / by class / by
 *  distance), a By Name / By Class display toggle, a custom GUI scale independent of Minecraft's own,
 *  and the two assignment modes killer560 asked for:
 *  <ul>
 *  <li>{@link com.killer560.hub.leapmenu.LeapMenuConfig.AssignMode#CLASS} - click a member's row to
 *  cycle their assigned {@link DungeonClass} (color-coded per class, matching the shared palette).
 *  <li>{@link com.killer560.hub.leapmenu.LeapMenuConfig.AssignMode#CUSTOMIZE} - Up/Down buttons on
 *  each row let you place members in whatever order you want.
 *  </ul>
 *  Both write into the same {@link LeapMenuConfig#getAssignedClass(String)}/custom-order storage that
 *  a future teammate ESP or dungeon map would read to recolor a player's dot/outline - those features
 *  don't exist yet (killer560's own note), this just makes sure the data they'd need is already being
 *  collected. */
public class LeapMenuScreen extends Screen {

    private final Screen parent;
    private int panelX, panelY, panelW, panelH;
    private int rowH;

    public LeapMenuScreen(Screen parent) {
        super(Component.literal("Leap Order"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        float scale = cfg.getGuiScale();
        panelW = (int) (Math.min(this.width - 20, 300) * scale);
        panelH = (int) (Math.min(this.height - 20, 260) * scale);
        panelW = Math.min(panelW, this.width - 10);
        panelH = Math.min(panelH, this.height - 10);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        rowH = (int) (20 * scale);
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        int y = panelY + 34;
        int btnW = (panelW - 30) / 2;

        this.addRenderableWidget(SettingsButtonWidget.builder(sortText(), btn -> {
                    cfg.setSortMode(cfg.getSortMode().next());
                    cfg.save();
                    btn.setMessage(sortText());
                }).bounds(panelX + 10, y, btnW, 18).build());

        this.addRenderableWidget(SettingsButtonWidget.builder(displayText(), btn -> {
                    cfg.setDisplayMode(cfg.getDisplayMode().next());
                    cfg.save();
                    btn.setMessage(displayText());
                }).bounds(panelX + 20 + btnW, y, btnW, 18).build());
        y += 22;

        this.addRenderableWidget(SettingsButtonWidget.builder(modeText(), btn -> {
                    cfg.setAssignMode(cfg.getAssignMode().next());
                    cfg.save();
                    btn.setMessage(modeText());
                    rebuild();
                }).bounds(panelX + 10, y, panelW - 20, 18).build());
        y += 24;

        double scaleNormalized = (cfg.getGuiScale() - LeapMenuConfig.MIN_SCALE) / (LeapMenuConfig.MAX_SCALE - LeapMenuConfig.MIN_SCALE);
        this.addRenderableWidget(new ThemedSliderButton(panelX + 10, y, panelW - 20, 18, scaleText(), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText());
            }

            @Override
            protected void applyValue() {
                cfg.setGuiScale((float) (LeapMenuConfig.MIN_SCALE + this.value * (LeapMenuConfig.MAX_SCALE - LeapMenuConfig.MIN_SCALE)));
                cfg.save();
            }
        });
        y += 26;

        List<Player> members = LeapMenuFeature.currentPartyMembers();
        List<Player> ordered = cfg.getAssignMode() == LeapMenuConfig.AssignMode.CUSTOMIZE
                ? LeapMenuFeature.customOrdered(members)
                : LeapMenuFeature.sorted(members, cfg.getSortMode());

        if (ordered.isEmpty()) {
            this.addRenderableWidget(new StringWidget(panelX + 10, y, panelW - 20, 12,
                    Component.literal("§7No other players nearby (are you in a dungeon?)"), this.font));
        }

        List<String> lowercaseNames = new ArrayList<>();
        for (Player p : ordered) {
            lowercaseNames.add(p.getName().getString().toLowerCase(Locale.US));
        }

        for (Player p : ordered) {
            String name = p.getName().getString();
            DungeonClass assigned = cfg.getAssignedClass(name);
            String label = cfg.getDisplayMode() == LeapMenuConfig.DisplayMode.CLASS && assigned != null
                    ? assigned.displayName()
                    : name;
            int color = assigned != null ? assigned.color() : 0xFFAAAAAA;

            int rowWidgetW = cfg.getAssignMode() == LeapMenuConfig.AssignMode.CUSTOMIZE ? panelW - 20 - 42 : panelW - 20;
            this.addRenderableWidget(new ClassRow(panelX + 10, y, rowWidgetW, rowH - 2, label, color, () -> {
                if (cfg.getAssignMode() == LeapMenuConfig.AssignMode.CLASS) {
                    DungeonClass next = assigned == null ? DungeonClass.values()[0]
                            : (assigned.ordinal() + 1 < DungeonClass.values().length ? DungeonClass.values()[assigned.ordinal() + 1] : null);
                    cfg.setAssignedClass(name, next);
                    cfg.save();
                    rebuild();
                }
            }));

            if (cfg.getAssignMode() == LeapMenuConfig.AssignMode.CUSTOMIZE) {
                int arrowX = panelX + 10 + rowWidgetW + 4;
                this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("^"), btn -> {
                            cfg.moveInCustomOrder(lowercaseNames, name, -1);
                            cfg.save();
                            rebuild();
                        }).bounds(arrowX, y, 18, (rowH - 2) / 2).build());
                this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("v"), btn -> {
                            cfg.moveInCustomOrder(lowercaseNames, name, 1);
                            cfg.save();
                            rebuild();
                        }).bounds(arrowX, y + (rowH - 2) / 2, 18, (rowH - 2) / 2).build());
            }
            y += rowH;
        }

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(panelX + panelW / 2 - 40, panelY + panelH - 24, 80, 18).build());
    }

    private static Component sortText() {
        return Component.literal("Sort: " + LeapMenuConfig.getInstance().getSortMode().label);
    }

    private static Component displayText() {
        return Component.literal("Show: " + LeapMenuConfig.getInstance().getDisplayMode().label);
    }

    private static Component modeText() {
        return Component.literal("Mode: " + LeapMenuConfig.getInstance().getAssignMode().label);
    }

    private static Component scaleText() {
        return Component.literal(String.format(Locale.US, "GUI Scale: %.0f%%", LeapMenuConfig.getInstance().getGuiScale() * 100));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF553311);
        graphics.text(this.font, "Leap Order", panelX + 10, panelY + 10, 0xFFCC6600, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A colored, clickable name row - same boxed look as the rest of the mod's menus, but with a
     *  caller-supplied text/outline color instead of the fixed amber theme, so each row reads in its
     *  assigned class's color. */
    private static final class ClassRow extends AbstractWidget {
        private final String label;
        private final int color;
        private final Runnable onClick;

        ClassRow(int x, int y, int width, int height, String label, int color, Runnable onClick) {
            super(x, y, width, height, Component.literal(label));
            this.label = label;
            this.color = color;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x0 = getX();
            int y0 = getY();
            graphics.fill(x0, y0, x0 + getWidth(), y0 + getHeight(), isHovered ? 0xFF262626 : 0xFF1A1A1A);
            graphics.outline(x0, y0, getWidth(), getHeight(), color);
            graphics.text(Minecraft.getInstance().font, label, x0 + 6, y0 + (getHeight() - 8) / 2, color, false);
        }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            onClick.run();
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
        }
    }
}
