package com.killer560.hub.intro;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.banreport.BanReportConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuAnim;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.AutoScale;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first-launch tour (killer560, 2026-11-01, "ONBOARDING / INTRO SPEC" in the handoff): killer560 himself, as a 3D
 * player model in his own skin ({@link Guide}), walks the new player through the mod in a few scenes with a themed
 * speech bubble ({@link SpeechBubble}). It runs once, over the title screen, as soon as the game has loaded
 * ({@link IntroLauncher}); Home's Replay Intro runs it again. Escape asks before skipping; Skip Guide skips at once.
 *
 * <p>Scenes: welcome (time estimate, Skip Guide), how to open the mod (the real Open Menu key), the menu with a theme to
 * pick (the presets cycle until one is clicked), "change it anytime", which variant this is, the cheat build's Auto
 * Report Bans opt-in (No unless Yes is clicked), Auto Update, the menus, then Discord, YouTube and bug reports.
 */
public class IntroScreen extends Screen implements MenuThemedScreen {

    /** One stop of the tour. */
    public enum Scene {
        WELCOME, OPEN_MENU, THEME, THEME_DONE, VARIANT, BAN_LOGS, AUTO_UPDATE, MENUS, COMMUNITY
    }

    private enum Spot { LEFT, NEAR, RIGHT }

    private static final int GAP = 4;
    private static final int NAV_H = 18;
    private static final int CHIP_H = 16;
    /** The narrowest bubble: the nav row under it (Skip Guide, the dots, Previous, Next) fits its width. */
    private static final int NAV_MIN_W = 280;

    private final Screen parent;
    private final List<Scene> scenes = new ArrayList<>();
    private int index;
    private final Guide guide = new Guide();
    private final SpeechBubble bubble = new SpeechBubble();
    /** Waiting for the guide to arrive before the bubble pops in. */
    private boolean bubbleWaiting = true;
    private boolean confirmingSkip;
    private boolean finished;

    // theme scene
    private final MenuTheme themeBefore;
    private MenuTheme picked;
    private int previewIndex;
    private long previewSince = Long.MIN_VALUE;
    // ban logs scene
    private boolean banYes;
    private boolean banSetByTour;
    // menus scene
    private static final String[] TOUR_CATEGORIES = {"Dungeons", "HUD", "Items", "Cosmetics", "Misc", "Home"};
    private int categoryIndex;
    private long categorySince;
    private ModScreen preview;
    private Scene previewFor;
    /** The size the screen behind was last laid out at. */
    private int parentW = -1;
    /** The nav row's span (the bubble's target width). */
    private int navX0;
    private int navX1;
    private int parentH = -1;

    /** Widgets drawn inside the bubble (they float with it), by label. */
    private final List<AbstractWidget> bubbleWidgets = new ArrayList<>();
    /** Every button on screen by its label, for the testkit. */
    private final Map<String, AbstractWidget> buttons = new LinkedHashMap<>();

    public IntroScreen(Screen parent) {
        super(Component.literal("Killer560's Mod Tour"));
        this.parent = parent;
        scenes.add(Scene.WELCOME);
        scenes.add(Scene.OPEN_MENU);
        scenes.add(Scene.THEME);
        scenes.add(Scene.THEME_DONE);
        scenes.add(Scene.VARIANT);
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            scenes.add(Scene.BAN_LOGS);
        }
        scenes.add(Scene.AUTO_UPDATE);
        scenes.add(Scene.MENUS);
        scenes.add(Scene.COMMUNITY);
        themeBefore = MenuConfig.getInstance().getTheme();
        banYes = BanReportConfig.getInstance().isEnabled();
    }

    // ---- scenes ----------------------------------------------------------------------------------------------------

    private Scene scene() {
        return scenes.get(index);
    }

    private static Spot spot(Scene s) {
        return switch (s) {
            case WELCOME -> Spot.LEFT;
            case OPEN_MENU -> Spot.NEAR;
            default -> Spot.RIGHT;
        };
    }

    private static boolean showsMenu(Scene s) {
        return s == Scene.THEME || s == Scene.THEME_DONE || s == Scene.AUTO_UPDATE || s == Scene.MENUS;
    }

    /** What the guide says. Markup: {accent} [key cap] <red>, \n a new paragraph. American spelling. */
    String text(Scene s) {
        return switch (s) {
            case WELCOME -> "Hey, thanks for downloading {Killer560's Mod!}\n"
                    + "I'll be your tour guide and show you a few things about the mod before you get to playing.";
            case OPEN_MENU -> {
                int code = HudConfig.getInstance().getOpenMenuKeyCode();
                yield code < 0
                        ? "To open the mod, type {/killer560} in chat.\nYou can also set an Open Menu key in Home."
                        : "To open the mod, press [" + KeyUtil.keyName(code) + "] or type {/killer560} in chat.";
            }
            case THEME -> picked == null
                    ? "This is our UI! Please select a {theme}. I'll show you each one until you pick."
                    : "This is our UI! You picked {" + picked.label + "}. Try another, or press Next.";
            case THEME_DONE -> "Great! You can change it anytime in {Home}, or create your own theme in the future.";
            case VARIANT -> BuildVariant.CHEAT_FEATURES_ENABLED
                    ? "Heads up: you're playing the <CHEAT> version.\n"
                    + "If you didn't mean to, delete this one and download the proper one."
                    : "You're on the {legit} version.\n"
                    + "If you ever feel like being naughty and using the cheat version, update to it.";
            case BAN_LOGS -> "Want to share your ban logs? If Hypixel ever bans you, {Auto Report Bans} sends the ban "
                    + "and this session's mod log to my staff, so we can find what caused it. Nothing is sent unless you "
                    + "say yes.";
            case AUTO_UPDATE -> "{Auto Update} keeps the mod up to date by itself. Its switch and channel are here in "
                    + "{Home}, along with Check Now.";
            case MENUS -> "Every feature has a card, sorted into the categories on the left. Open a card for its "
                    + "settings, or use the {search} at the top to find anything.";
            case COMMUNITY -> "Last thing! Join the {Discord} if you're not in it already, and subscribe to my "
                    + "{YouTube} channel.\nFound a bug? DM me or make a report on the Discord. Have fun!";
        };
    }

    private Guide.Pose pose(Scene s) {
        return switch (s) {
            case WELCOME, COMMUNITY -> Guide.Pose.WAVE;
            case THEME_DONE -> Guide.Pose.CHEER;
            case VARIANT -> BuildVariant.CHEAT_FEATURES_ENABLED ? Guide.Pose.POINT : Guide.Pose.WAVE;
            default -> Guide.Pose.POINT;
        };
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private float spotX(Spot s) {
        return switch (s) {
            case LEFT -> 16;
            case NEAR -> Math.min(this.width * 0.16f, 90);
            case RIGHT -> this.width - Guide.BOX_W - 16;
        };
    }

    private int boxTop() {
        return Math.round(this.height - 20 - Guide.BOX_H * Guide.FEET);
    }

    private boolean guideOnLeft() {
        return spot(scene()) != Spot.RIGHT;
    }

    /** {x0, x1} of the strip beside the guide where the bubble and the buttons go. */
    private int[] lane() {
        float gx = spotX(spot(scene()));
        if (guideOnLeft()) {
            int x0 = Math.round(gx + Guide.BOX_W + 10);
            return new int[]{x0, Math.min(this.width - 16, x0 + 340)};
        }
        int x1 = Math.round(gx - 10);
        return new int[]{Math.max(16, x1 - 340), x1};
    }

    private int navY() {
        return this.height - 20 - NAV_H;
    }

    /** {x, y, w, h} of the menu picture. */
    private int[] previewRect() {
        int right = Math.round(spotX(Spot.RIGHT)) - 12;
        float s = Math.min((right - 12f) / this.width, (this.height - 70f) / this.height);
        s = Math.max(0.3f, Math.min(0.72f, s));
        int w = Math.round(this.width * s);
        int h = Math.round(this.height * s);
        return new int[]{Math.max(12, right - w), 12, w, h};
    }

    @Override
    protected void init() {
        // The screen behind is drawn live: lay it out at the window's size too (a fresh title screen from Replay Intro
        // was never laid out, and a resize must reach it).
        if (parent != null) {
            var window = Minecraft.getInstance().getWindow();
            int pw = window.getGuiScaledWidth();
            int ph = window.getGuiScaledHeight();
            if (pw != parentW || ph != parentH) {
                parentW = pw;
                parentH = ph;
                try {
                    parent.init(pw, ph);
                } catch (RuntimeException ignored) {
                }
            }
        }
        buttons.clear();
        bubbleWidgets.clear();
        long now = MenuAnim.now();
        if (confirmingSkip) {
            int cx = this.width / 2;
            int cy = this.height / 2;
            add(SettingsButtonWidget.builder(Component.literal("Keep Going"), b -> {
                confirmingSkip = false;
                rebuildWidgets();
            }).bounds(cx - 104, cy + 8, 100, NAV_H).primary().build(), false);
            add(SettingsButtonWidget.builder(Component.literal("Skip Tour"), b -> finish())
                    .bounds(cx + 4, cy + 8, 100, NAV_H).build(), false);
            return;
        }
        Scene s = scene();
        guide.moveTo(spotX(spot(s)), now);
        if (index == 0) {
            guide.setPose(pose(s), now);
        }
        int[] lane = lane();
        int maxText = Math.max(120, Math.min(250, lane[1] - lane[0] - 2 * SpeechBubble.padX()));
        bubble.setText(text(s), maxText, now);

        // ---- the widgets inside the bubble
        List<SettingsButtonWidget> row = new ArrayList<>();
        switch (s) {
            case THEME -> {
                MenuTheme shown = MenuConfig.getInstance().getTheme();
                for (MenuTheme t : MenuTheme.presets()) {
                    boolean on = picked != null ? t == picked : t == shown;
                    row.add(chip(t.label, on, () -> pickTheme(t)));
                }
            }
            case BAN_LOGS -> {
                row.add(chip("Yes, share them", banYes, () -> chooseBanLogs(true)));
                row.add(chip("No thanks", !banYes, () -> chooseBanLogs(false)));
            }
            case COMMUNITY -> {
                row.add(chip("Join Discord", false, () -> ExternalOpen.uri(ModScreen.DISCORD_URL)));
                row.add(chip("YouTube", false, () -> ExternalOpen.uri(ModScreen.YOUTUBE_URL)));
            }
            default -> {
            }
        }
        int rowW = 0;
        for (SettingsButtonWidget b : row) {
            rowW += b.getWidth() + (rowW > 0 ? GAP : 0);
        }
        // A row wider than the text wraps onto a second line of chips.
        int innerMax = maxText;
        int rows = row.isEmpty() ? 0 : 1;
        int lineW = 0;
        int widestRow = 0;
        for (SettingsButtonWidget b : row) {
            int add = b.getWidth() + (lineW > 0 ? GAP : 0);
            if (lineW > 0 && lineW + add > innerMax) {
                rows++;
                widestRow = Math.max(widestRow, lineW);
                lineW = b.getWidth();
            } else {
                lineW += add;
            }
        }
        widestRow = Math.max(widestRow, lineW);
        int innerW = Math.max(bubble.textWidth(), widestRow);
        int bw = Math.max(NAV_MIN_W, innerW + 2 * SpeechBubble.padX());
        int bh = bubble.textHeight() + 2 * SpeechBubble.padY() + (rows > 0 ? rows * (CHIP_H + GAP) + 4 : 0);
        int bottom = navY() - 10;
        int bx = guideOnLeft() ? lane[0] : lane[1] - bw;
        int by = bottom - bh;
        bubble.setTarget(bx, by, bw, bh, now);
        // Place the chips in the bubble's final rectangle.
        int cx = bx + SpeechBubble.padX();
        int cy = by + SpeechBubble.padY() + bubble.textHeight() + 6;
        int used = 0;
        for (SettingsButtonWidget b : row) {
            if (used > 0 && used + GAP + b.getWidth() > innerW) {
                cy += CHIP_H + GAP;
                used = 0;
            }
            b.setX(cx + used + (used > 0 ? GAP : 0));
            b.setY(cy);
            used += b.getWidth() + (used > 0 ? GAP : 0);
            bubbleWidgets.add(b);
            add(b, true);
        }

        // ---- the nav row under the bubble, beside the guide's feet
        navX0 = bx;
        navX1 = bx + bw;
        int ny = navY();
        boolean last = index == scenes.size() - 1;
        add(SettingsButtonWidget.builder(Component.literal(last ? "Finish" : "Next"), b -> next())
                .bounds(navX1 - 64, ny, 64, NAV_H).primary().build(), false);
        if (index > 0) {
            add(SettingsButtonWidget.builder(Component.literal("Previous"), b -> previous())
                    .bounds(navX1 - 64 - GAP - 64, ny, 64, NAV_H).build(), false);
        }
        if (!last) {
            add(SettingsButtonWidget.builder(Component.literal("Skip Guide"), b -> finish())
                    .bounds(navX0, ny, 70, NAV_H).build(), false);
        }
    }

    private SettingsButtonWidget chip(String label, boolean on, Runnable action) {
        int w = this.font.width(label) + 16;
        return SettingsButtonWidget.builder(Component.literal(label), b -> action.run())
                .bounds(0, 0, w, CHIP_H).toggle(on).build();
    }

    private void add(AbstractWidget w, boolean inBubble) {
        buttons.put(w.getMessage().getString(), w);
        if (inBubble) {
            addWidget(w); // input only: the bubble draws it under its own pose
        } else {
            addRenderableWidget(w);
        }
    }

    // ---- actions ---------------------------------------------------------------------------------------------------

    /** Goes to the next scene, or finishes after the last. */
    public void next() {
        if (index >= scenes.size() - 1) {
            finish();
            return;
        }
        go(index + 1);
    }

    /** Goes back a scene (there is nothing before the first). */
    public void previous() {
        if (index > 0) {
            go(index - 1);
        }
    }

    private void go(int to) {
        Scene from = scene();
        leave(from);
        long now = MenuAnim.changeTime();
        index = to;
        Scene s = scene();
        float x = spotX(spot(s));
        boolean moves = Math.abs(x - guide.targetXEnd()) > 0.5f;
        guide.moveTo(x, now);
        guide.setPose(pose(s), now);
        if (moves) {
            bubbleWaiting = true; // he walks first; the bubble pops in where he stops
        }
        if (s == Scene.THEME && picked == null) {
            previewSince = now;
        }
        if (s == Scene.MENUS) {
            categoryIndex = 0;
            categorySince = now;
        }
        rebuildWidgets();
    }

    /** Undoes what a scene shows only while it is up. */
    private void leave(Scene s) {
        if (s == Scene.THEME && picked == null) {
            MenuConfig.getInstance().previewTheme(themeBefore);
        }
    }

    /** Picks a theme: applied and saved at once (the menu and this tour change to it). */
    public void pickTheme(MenuTheme t) {
        picked = t;
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setTheme(t);
        cfg.save();
        guide.setPose(Guide.Pose.CHEER, MenuAnim.changeTime());
        rebuildWidgets();
    }

    /** The ban-log answer: Yes turns Auto Report Bans on; No turns it back off only if this tour turned it on. */
    public void chooseBanLogs(boolean yes) {
        BanReportConfig cfg = BanReportConfig.getInstance();
        if (yes) {
            if (!cfg.isEnabled()) {
                cfg.setEnabled(true);
                cfg.save();
                banSetByTour = true;
            }
        } else if (banSetByTour) {
            cfg.setEnabled(false);
            cfg.save();
            banSetByTour = false;
        }
        banYes = yes;
        rebuildWidgets();
    }

    /** Ends the tour (finished or skipped): it will not show again on its own. */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        leave(scene());
        if (picked == null) {
            MenuConfig.getInstance().previewTheme(themeBefore);
        }
        resetPreview();
        IntroConfig cfg = IntroConfig.getInstance();
        cfg.setDone(true);
        cfg.save();
        McCompat.setScreen(this.minecraft, parent);
    }

    /** Leaves the real menu as the tour found it: Home, no drawer open. */
    private void resetPreview() {
        if (preview != null) {
            try {
                preview.closeDrawer();
                preview.selectCategory("Home");
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onClose() {
        // Escape: ask first (Escape again keeps going).
        confirmingSkip = !confirmingSkip;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!confirmingSkip) {
            int k = event.key();
            if (k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_RETURN) {
                MenuAnim.input();
                if (!bubble.typed(MenuAnim.now())) {
                    bubble.finishTyping();
                } else {
                    next();
                }
                return true;
            }
            if (k == InputConstants.KEY_LEFT) {
                MenuAnim.input();
                previous();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MenuAnim.input();
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        // A click on the bubble shows the rest of the text at once.
        double mx = event.x();
        double my = event.y();
        if (!confirmingSkip && mx >= bubble.x() && mx < bubble.x() + bubble.w() && my >= bubble.y()
                && my < bubble.y() + bubble.h()) {
            bubble.finishTyping();
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        if (parent != null && !(parent instanceof ModScreen)) {
            parent.tick();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing ---------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The screen the tour opened over stays live behind it (the title screen and its animation).
        if (parent != null) {
            float mine = AutoScale.appliedFactor(this);
            float theirs = AutoScale.appliedFactor(parent);
            g.pose().pushMatrix();
            if (mine != theirs && mine > 0) {
                g.pose().scale(theirs / mine, theirs / mine);
            }
            try {
                parent.extractBackground(g, -1, -1, partialTick);
                parent.extractRenderState(g, -1, -1, partialTick);
            } catch (RuntimeException ignored) {
                // a picture behind the tour is never worth a crash
            } finally {
                g.pose().popMatrix();
                MenuStyle.use();
            }
        }
        // A soft dark rise from the bottom so the guide and his bubble read over any background.
        GuiRects r = GuiRects.begin(g);
        int steps = 16;
        int top = this.height / 3;
        for (int i = 0; i < steps; i++) {
            int y0 = top + (this.height - top) * i / steps;
            int y1 = top + (this.height - top) * (i + 1) / steps;
            int a = Math.round(0x90 * (i + 1) / (float) steps);
            r.fill(0, y0, this.width, y1, a << 24);
        }
        r.fill(0, 0, this.width, top, 0x18000000);
        r.submit();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        MenuAnim.frame();
        MenuStyle.use();
        long now = MenuAnim.now();
        Scene s = scene();
        tickScene(s, now);

        if (showsMenu(s)) {
            drawPreview(g, s, partialTick);
        }

        // The guide, and where his attention goes.
        int top = boxTop();
        float[] target = target(s, now);
        guide.lookAt(target[0], target[1]);
        if (!guide.walking(now) && bubbleWaiting) {
            bubbleWaiting = false;
            bubble.pop(now);
        }
        guide.draw(g, now, top);

        if (!bubbleWaiting) {
            float hx = guide.headX(now, top);
            float hy = guide.headY(now, top);
            boolean ready = bubble.settled(now);
            for (AbstractWidget w : bubbleWidgets) {
                w.active = ready;
            }
            bubble.draw(g, now, hx, hy, ready ? () -> {
                for (AbstractWidget w : bubbleWidgets) {
                    w.extractRenderState(g, mouseX, mouseY, partialTick);
                }
            } : null);
        }

        // Nav extras: the time estimate on the first scene, and where you are.
        if (!confirmingSkip) {
            int ny = navY() + (NAV_H - 8) / 2;
            int px = navX0 + (index < scenes.size() - 1 ? 76 : 0);
            if (index == 0) {
                ScreenChrome.text(g, "About 1 minute", px, ny, MenuStyle.DIM);
            } else {
                drawDots(g, px, ny + 2);
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (confirmingSkip) {
            drawConfirm(g);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawDots(GuiGraphicsExtractor g, int x, int y) {
        GuiRects r = GuiRects.begin(g);
        for (int i = 0; i < scenes.size(); i++) {
            int c = i == index ? MenuStyle.OR : i < index ? MenuStyle.alpha(MenuStyle.OR, 0x90) : MenuStyle.DIM2;
            int w = i == index ? 8 : 4;
            r.fill(x, y, x + w, y + 4, c);
            x += w + 3;
        }
        r.submit();
    }

    private void drawConfirm(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        r.fill(0, 0, this.width, this.height, 0x80000000);
        r.submit();
        int w = 232;
        int h = 64;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 32;
        ScreenChrome.frame(g, x, y, w, h);
        ScreenChrome.centered(g, "Skip the tour?", this.width / 2, y + 8, MenuStyle.TXT);
        ScreenChrome.centered(g, "You can replay it from Home > Mod & HUD.", this.width / 2, y + 22, MenuStyle.DIM);
    }

    /** Scene clocks: the theme preview cycling, the menus scene stepping through categories. */
    private void tickScene(Scene s, long now) {
        if (s == Scene.THEME && picked == null && !confirmingSkip) {
            if (previewSince == Long.MIN_VALUE) {
                previewSince = now;
            }
            int step = (int) ((now - previewSince) / 1_500_000_000L);
            MenuTheme[] presets = MenuTheme.presets();
            int want = Math.floorMod(step, presets.length);
            if (want != previewIndex || MenuConfig.getInstance().getTheme() != presets[want]) {
                previewIndex = want;
                MenuConfig.getInstance().previewTheme(presets[want]);
                rebuildWidgets();
            }
        }
        if (s == Scene.MENUS && preview != null) {
            int step = (int) ((now - categorySince) / 1_400_000_000L);
            int want = Math.min(step, TOUR_CATEGORIES.length - 1);
            if (want != categoryIndex) {
                categoryIndex = want;
                preview.selectCategory(TOUR_CATEGORIES[want]);
            }
        }
    }

    /** Where the guide points or looks (GUI units). */
    private float[] target(Scene s, long now) {
        if (showsMenu(s)) {
            int[] p = previewRect();
            float px = p[0] + p[2] * 0.55f;
            float py = p[1] + p[3] * 0.45f;
            if (s == Scene.MENUS) {
                px = p[0] + p[2] * 0.08f; // the category list on the left
            }
            if (s == Scene.AUTO_UPDATE) {
                px = p[0] + p[2] * 0.78f; // the drawer
            }
            return new float[]{px, py};
        }
        if (!bubbleWaiting) {
            return new float[]{bubble.x() + bubble.w() / 2f, bubble.y() + bubble.h() / 2f};
        }
        return new float[]{Float.NaN, Float.NaN};
    }

    private void drawPreview(GuiGraphicsExtractor g, Scene s, float partialTick) {
        ModScreen p = previewScreen(s);
        if (p == null) {
            return;
        }
        int[] r = previewRect();
        float scale = r[2] / (float) Math.max(1, p.width);
        // A frame and a soft shadow around the picture of the menu.
        GuiRects rects = GuiRects.begin(g);
        for (int i = 4; i >= 1; i--) {
            rects.fill(r[0] - i + 3, r[1] - i + 4, r[0] + r[2] + i + 3, r[1] + r[3] + i + 4, (0x12 * (5 - i)) << 24);
        }
        rects.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, MenuStyle.OR);
        rects.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], MenuStyle.PANEL_BOTTOM);
        rects.submit();
        g.enableScissor(r[0], r[1], r[0] + r[2], r[1] + r[3]);
        g.pose().pushMatrix();
        g.pose().translate(r[0], r[1]);
        g.pose().scale(scale, scale);
        try {
            p.extractRenderState(g, -1, -1, partialTick);
        } catch (RuntimeException ignored) {
            // a picture of the menu is never worth a crash
        } finally {
            g.pose().popMatrix();
            g.disableScissor();
            MenuStyle.use();
        }
    }

    private ModScreen previewScreen(Scene s) {
        if (preview == null || previewFor != s) {
            Scene before = previewFor;
            previewFor = s;
            boolean drawer = s == Scene.AUTO_UPDATE;
            boolean keep = preview != null && before != null && (before == Scene.THEME || before == Scene.THEME_DONE)
                    && (s == Scene.THEME || s == Scene.THEME_DONE);
            if (!keep) {
                resetPreview();
                preview = ModScreen.atTab(this, drawer ? "Auto Update" : "Home");
                var window = Minecraft.getInstance().getWindow();
                preview.init(window.getGuiScaledWidth(), window.getGuiScaledHeight());
                if (!drawer) {
                    preview.closeDrawer();
                }
            }
        }
        return preview;
    }

    // ---- MenuThemedScreen ------------------------------------------------------------------------------------------

    @Override
    public int[] themedPanelBounds() {
        return new int[]{Math.round(bubble.x()), Math.round(bubble.y()), Math.round(bubble.w()), Math.round(bubble.h())};
    }

    // ---- read by the testkit ---------------------------------------------------------------------------------------

    public Scene currentScene() {
        return scene();
    }

    public int sceneIndex() {
        return index;
    }

    public int sceneCount() {
        return scenes.size();
    }

    public List<Scene> sceneList() {
        return List.copyOf(scenes);
    }

    /** The bubble's words for the current scene, markup removed. */
    public String bubbleText() {
        return bubble.plainText();
    }

    /** The current scene's buttons by label ("Next", "Previous", "Skip Guide", a theme name, ...). */
    public Map<String, AbstractWidget> buttons() {
        return new LinkedHashMap<>(buttons);
    }

    public boolean confirmingSkip() {
        return confirmingSkip;
    }

    /** True once the bubble has popped in and finished typing (screenshots wait for it). */
    public boolean settled() {
        long now = MenuAnim.now();
        return !bubbleWaiting && bubble.settled(now) && bubble.typed(now) && !guide.walking(now);
    }

    /** How many frames the 3D guide has been submitted (0 means the model never drew). */
    public static long guideFrames() {
        return Guide.drawn;
    }

    /** The guide's last body turn in degrees (0 faces the viewer; he turns toward what he points at). */
    public static float guideTurn() {
        return Guide.lastTurn;
    }

    /** {x, y, w, h} of the bubble as drawn, GUI units of this screen. */
    public float[] bubbleRect() {
        return new float[]{bubble.x(), bubble.y(), bubble.w(), bubble.h()};
    }

    /** {x, y, w, h} of the guide's box now. */
    public int[] guideBox() {
        long now = MenuAnim.now();
        return new int[]{Math.round(guide.currentX(now)), boxTop(), Guide.BOX_W, Guide.BOX_H};
    }

    public Screen parent() {
        return parent;
    }
}
