package com.killer560.hub.termism;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.terminals.TerminalType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Random;

/** Entry menu for Termism, killer560's own practice-mode request (2026-09-09): open via {@code /termism}
 *  or a settings button, "a button for random and then a button for each of the other actual terminals...
 *  if I press random, it will never be able to select the Melody option." Melody is excluded from both
 *  Random and its own button - it has no real solving logic to practice against (see
 *  {@code TerminalSolverFeature}'s own doc on why it's the one type with no solver), so there's nothing
 *  to generate a practice puzzle for. */
public class TermismMenuScreen extends Screen {

    // Package-visible (not private) so TermismPracticeScreen's own "New Puzzle" reroll (killer560's
    // "make it completely random from all puzzles besides melody" request, 2026-09-09) can reuse the
    // exact same never-Melody type pool this menu's own Random button already draws from.
    static final List<TerminalType> PRACTICE_TYPES = List.of(
            TerminalType.PANES, TerminalType.RUBIX, TerminalType.NUMBERS,
            TerminalType.STARTS_WITH, TerminalType.SELECT
    );

    private final Screen parent;
    private final Random random = new Random();

    public TermismMenuScreen(Screen parent) {
        super(Component.literal("Termism"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = 220;
        int x = this.width / 2 - width / 2;
        int y = this.height / 2 - 118;

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§6Random"), btn ->
                        open(PRACTICE_TYPES.get(random.nextInt(PRACTICE_TYPES.size()))))
                .bounds(x, y, width, 20).build());
        y += 26;

        for (TerminalType type : PRACTICE_TYPES) {
            this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal(type.displayName()), btn -> open(type))
                    .bounds(x, y, width, 20).build());
            y += 24;
        }
        y += 8;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(x, y, width, 20).build());
    }

    private void open(TerminalType type) {
        Minecraft.getInstance().setScreen(new TermismPracticeScreen(this, type));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.centeredText(this.font, "§6Termism §7- Terminal Practice",
                this.width / 2, this.height / 2 - 142, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Generates a fake terminal to practice on - never a real one.",
                this.width / 2, this.height / 2 - 130, 0xFFAAAAAA);
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
}
