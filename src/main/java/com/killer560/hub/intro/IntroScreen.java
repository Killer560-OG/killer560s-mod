package com.killer560.hub.intro;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoupdate.AutoUpdateConfig;
import com.killer560.hub.banreport.BanReportConfig;
import com.killer560.hub.crashreport.CrashReportConfig;
import com.killer560.hub.discordlink.DiscordLink;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuAnim;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.tab.DiscordLinkTab;
import com.killer560.hub.hud.AutoScale;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.mainmenu.MainMenuThemeConfig;
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
 * speech bubble ({@link SpeechBubble}) whose tail comes from his mouth. It runs once per instance, over the title
 * screen ({@link IntroLauncher}); Home's Replay Intro runs it again. Escape asks before skipping; Skip Guide skips at
 * once. Skipping an auto-opened tour turns on each question left unanswered (a replay changes nothing).
 *
 * <p>Scenes: welcome (time estimate, Skip Guide), how to open the mod (the real Open Menu key), the menu with a theme and
 * a main menu style to pick (nothing changes until a choice is clicked), "change it anytime", which variant this is, the
 * cheat build's Auto Report Bans question, the Auto Crash Reports question and the Auto Update question (each must be answered before Next; Yes is the
 * suggested answer), the menus, then Discord, YouTube and bug reports. Every button is an {@link IntroButton}, drawn like
 * the bubble.
 */
public class IntroScreen extends Screen implements MenuThemedScreen {

    /** One stop of the tour. */
    public enum Scene {
        WELCOME, OPEN_MENU, THEME, THEME_DONE, VARIANT, BAN_LOGS, CRASH_REPORTS, DISCORD_LINK, AUTO_UPDATE, MENUS, COMMUNITY
    }

    private enum Spot { LEFT, NEAR, RIGHT }

    private static final int GAP = 4;
    private static final int NAV_H = 18;
    private static final int CHIP_H = 16;
    /** The narrowest bubble: the nav row under it (Skip Guide, the dots, Previous, Next) fits its width. */
    private static final int NAV_MIN_W = 300;
    private static final String TIME_TEXT = "About 1 minute";

    private final Screen parent;
    private final List<Scene> scenes = new ArrayList<>();
    private int index;
    private final Guide guide = new Guide();
    private final SpeechBubble bubble = new SpeechBubble();
    /** Waiting for the guide to arrive before the bubble pops in. */
    private boolean bubbleWaiting = true;
    private boolean confirmingSkip;
    private boolean finished;

    /** The answers to the two questions, null until one is clicked (Next waits for it). */
    private Boolean banChoice;
    private Boolean crashChoice;
    private Boolean updateChoice;
    // menus scene
    private static final String[] TOUR_CATEGORIES = {"Dungeons", "HUD", "Items", "Cosmetics", "Misc", "Home"};
    private int categoryIndex;
    private long categorySince;
    private ModScreen preview;
    private Scene previewFor;
    /** The size the screen behind was last laid out at. */
    private int parentW = -1;
    private int parentH = -1;
    /** The nav row's span (the bubble's target width). */
    private int navX0;
    private int navX1;

    /** Widgets drawn inside the bubble (they float with it). */
    private final List<AbstractWidget> bubbleWidgets = new ArrayList<>();
    /** Every button on screen by its label, for the testkit. */
    private final Map<String, AbstractWidget> buttons = new LinkedHashMap<>();
    private IntroButton nextButton;
    private final List<List<int[]>> chipRows = new ArrayList<>();
    /** His mouth this frame (GUI units): the bubble's tail is drawn to it. */
    private final float[] mouth = {Float.NaN, Float.NaN};
    private static boolean hideBubbleForTest;

    /** True when the tour opened by itself on first launch: skipping it then turns on every unanswered question. */
    private final boolean autoOpened;

    public IntroScreen(Screen parent) {
        this(parent, false);
    }

    public IntroScreen(Screen parent, boolean autoOpened) {
        super(Component.literal("Killer560's Mod Tour"));
        this.parent = parent;
        this.autoOpened = autoOpened;
        scenes.add(Scene.WELCOME);
        scenes.add(Scene.OPEN_MENU);
        scenes.add(Scene.THEME);
        scenes.add(Scene.THEME_DONE);
        scenes.add(Scene.VARIANT);
        scenes.add(Scene.BAN_LOGS);
        scenes.add(Scene.CRASH_REPORTS);
        scenes.add(Scene.DISCORD_LINK);
        scenes.add(Scene.AUTO_UPDATE);
        scenes.add(Scene.MENUS);
        scenes.add(Scene.COMMUNITY);
    }

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
                yield code == com.killer560.hub.util.KeyUtil.NONE
                        ? "To open the mod, type {/killer560} in chat.\nYou can also set an Open Menu key in Home."
                        : "To open the mod, press [" + KeyUtil.keyName(code) + "] or type {/killer560} in chat.";
            }
            case THEME -> "This is our UI! Pick a {theme} you like, and a {main menu} style: Fancy is animated (its full "
                    + "picture downloads once, a low-res preview shows until then), Simple is calm, Vanilla is Minecraft's own.";
            case THEME_DONE -> "Great! You can change it anytime in {Home}, or create your own theme in the future.";
            case VARIANT -> BuildVariant.CHEAT_FEATURES_ENABLED
                    ? "Heads up: you're playing the <CHEAT> version.\n"
                    + "If you didn't mean to, delete this one and download the proper one."
                    : "You're on the {legit} version.\n"
                    + "If you ever feel like being naughty and using the cheat version, update to it.";
            case BAN_LOGS -> "Want to share your ban logs? If Hypixel ever bans you, {Auto Report Bans} sends the ban "
                    + "and this session's mod log to my staff, so we can find what caused it. Nothing is sent unless you "
                    + "say yes.";
            case CRASH_REPORTS -> "Want to send crash reports? If the game ever crashes, {Auto Crash Reports} sends "
                    + "the crash report, your mods and the features you had on to my admins, with your Minecraft name "
                    + "(you can turn that off in {Home}) and no tokens or IPs, so we can fix it. Nothing is sent unless you "
                    + "say yes.";
            case DISCORD_LINK -> discordLinkText();
            case AUTO_UPDATE -> "{Auto Update} keeps the mod up to date by itself, installing new versions when you close "
                    + "the game. Want it on? You can change it anytime in {Home}.";
            case MENUS -> "Every feature has a card, sorted into the categories on the left. Open a card for its "
                    + "settings, or use the {search} at the top to find anything.";
            case COMMUNITY -> "Last thing! Join the {Discord} if you're not in it already, and subscribe to my "
                    + "{YouTube} channel.\nFound a bug? DM me or make a report on the Discord. Have fun!";
        };
    }

    /** The Discord Link scene's words, by the link's state (a relay that cannot be reached never stops the tour). */
    private static String discordLinkText() {
        String name = DiscordLink.discordName().replaceAll("[{}\\[\\]<>]", "");
        return switch (DiscordLink.state()) {
            case WORKING -> "Asking for a code...";
            case CODE -> (name.isEmpty() ? "" : "You're linked to {" + name + "} already; this code links a different one.\n")
                    + "Type {" + DiscordLink.command() + "} in my Discord. The code works once, for 10 minutes. "
                    + "I'll see it here when it's done.";
            case LINKED -> "Linked to {" + name + "}! Thanks. You can unlink anytime in {Home > " + DiscordLinkTab.NAME + "}.";
            case ERROR -> "Couldn't get a code right now. No problem: try later from {Home > " + DiscordLinkTab.NAME + "}.";
            default -> "Want to link your {Discord}? Then I know who you are on your crash reports. Get a one-time "
                    + "code and type it in my Discord. Or skip, and do it later from {Home > " + DiscordLinkTab.NAME + "}.";
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

    /** True while the scene waits for an answer: Next (and the Right/Enter keys) do nothing until one is clicked. */
    public boolean nextBlocked() {
        return switch (scene()) {
            case BAN_LOGS -> banChoice == null;
            case CRASH_REPORTS -> crashChoice == null;
            case AUTO_UPDATE -> updateChoice == null;
            default -> false;
        };
    }

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
        nextButton = null;
        long now = MenuAnim.now();
        if (confirmingSkip) {
            int cx = this.width / 2;
            int cy = this.height / 2;
            add(new IntroButton(cx - 104, cy + 8, 100, NAV_H, "Keep Going", IntroButton.Kind.PRIMARY, () -> {
                confirmingSkip = false;
                rebuildWidgets();
            }), false);
            add(new IntroButton(cx + 4, cy + 8, 100, NAV_H, "Skip Tour", IntroButton.Kind.PLAIN, this::finish), false);
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

        // ---- the widgets inside the bubble (rows of chips; each list is one row)
        List<List<IntroButton>> rowsOf = new ArrayList<>();
        switch (s) {
            case THEME -> {
                MenuTheme current = MenuConfig.getInstance().getTheme();
                rowsOf.add(java.util.Arrays.stream(MenuTheme.presets()).map(t -> chip(t.label,
                        t == current ? IntroButton.Kind.CHOSEN : IntroButton.Kind.PLAIN, () -> pickTheme(t))).toList());
                MainMenuThemeConfig mm = MainMenuThemeConfig.getInstance();
                List<IntroButton> styles = new ArrayList<>(java.util.Arrays.stream(MainMenuThemeConfig.SceneMode.values())
                        .map(m -> chip(m.label + " Main Menu", mm.isEnabled() && m == mm.getSceneMode()
                                ? IntroButton.Kind.CHOSEN : IntroButton.Kind.PLAIN, () -> pickMainMenu(m))).toList());
                // No custom title screen (themedl-1104, killer560 2026-11-04): Minecraft's own, and nothing downloads.
                styles.add(chip("Vanilla Main Menu", mm.isEnabled() ? IntroButton.Kind.PLAIN : IntroButton.Kind.CHOSEN,
                        this::pickVanillaMainMenu));
                rowsOf.add(styles);
            }
            case BAN_LOGS -> rowsOf.add(yesNo("Yes, share them", banChoice, this::chooseBanLogs));
            case CRASH_REPORTS -> rowsOf.add(yesNo("Yes, send them", crashChoice, this::chooseCrashReports));
            case DISCORD_LINK -> {
                // Never blocks the tour (killer560, 2026-11-05: "prompt them to link in the startup animation as well"):
                // the relay calls run off-thread and the reply rebuilds this scene only while it is still on screen.
                DiscordLink.watch(() -> {
                    if (McCompat.screen(Minecraft.getInstance()) == this && scene() == Scene.DISCORD_LINK) {
                        rebuildWidgets();
                    }
                });
                DiscordLink.State st = DiscordLink.state();
                List<IntroButton> row = new ArrayList<>();
                if (st == DiscordLink.State.CODE) {
                    row.add(chip("Copy /link Command", IntroButton.Kind.PRIMARY,
                            () -> Minecraft.getInstance().keyboardHandler.setClipboard(DiscordLink.command())));
                    row.add(chip("New Code", IntroButton.Kind.PLAIN, DiscordLink::start));
                } else if (st != DiscordLink.State.LINKED && st != DiscordLink.State.WORKING) {
                    row.add(chip("Get Link Code", IntroButton.Kind.PRIMARY, DiscordLink::start));
                }
                row.add(chip(st == DiscordLink.State.LINKED ? "Next" : "Skip", IntroButton.Kind.PLAIN, this::next));
                rowsOf.add(row);
            }
            case AUTO_UPDATE -> rowsOf.add(yesNo("Yes, keep me updated", updateChoice, this::chooseAutoUpdate));
            case COMMUNITY -> rowsOf.add(List.of(
                    chip("Join Discord", IntroButton.Kind.PLAIN, () -> ExternalOpen.uri(ModScreen.DISCORD_URL)),
                    chip("YouTube", IntroButton.Kind.PLAIN, () -> ExternalOpen.uri(ModScreen.YOUTUBE_URL))));
            default -> {
            }
        }
        // The chips are a grid (killer560, 2026-11-02: "the bubbles dont fit quite right ... awkwardly spaced"): every line
        // spans the bubble's whole content width with equal gaps, and the chips of a line share it equally. A row whose
        // chips do not fit side by side in the lane is split into lines of as many as fit.
        int avail = Math.max(60, lane[1] - lane[0] - 2 * SpeechBubble.padX());
        List<List<IntroButton>> lines = new ArrayList<>();
        int need = 0;
        for (List<IntroButton> row : rowsOf) {
            int cell = 0;
            for (IntroButton b : row) {
                cell = Math.max(cell, b.getWidth());
            }
            int k = row.size();
            while (k > 1 && k * cell + (k - 1) * GAP > avail) {
                k--;
            }
            for (int i = 0; i < row.size(); i += k) {
                List<IntroButton> line = row.subList(i, Math.min(row.size(), i + k));
                lines.add(line);
                need = Math.max(need, line.size() * cell + (line.size() - 1) * GAP);
            }
        }
        int innerW = gridWidth(Math.max(Math.max(bubble.textWidth(), need), NAV_MIN_W - 2 * SpeechBubble.padX()),
                Math.max(avail, need), lines);
        int bw = innerW + 2 * SpeechBubble.padX();
        int bh = bubble.textHeight() + 2 * SpeechBubble.padY() + (lines.isEmpty() ? 0 : lines.size() * (CHIP_H + GAP) + 4);
        int bottom = navY() - 10;
        int bx = guideOnLeft() ? lane[0] : lane[1] - bw;
        int by = bottom - bh;
        bubble.setTarget(bx, by, bw, bh, now);
        int cy = by + SpeechBubble.padY() + bubble.textHeight() + 6;
        chipRows.clear();
        for (List<IntroButton> line : lines) {
            int m = line.size();
            int room = innerW - (m - 1) * GAP;
            int cell = room / m;
            int extra = room - cell * m; // 0 whenever gridWidth found a width that divides evenly
            int cx = bx + SpeechBubble.padX();
            List<int[]> cells = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                IntroButton b = line.get(i);
                int w = cell + (i < extra ? 1 : 0);
                b.setX(cx);
                b.setY(cy);
                b.setWidth(w);
                cells.add(new int[]{cx, cy, w, CHIP_H, this.font.width(b.getMessage().getString())});
                cx += w + GAP;
                bubbleWidgets.add(b);
                add(b, true);
            }
            chipRows.add(cells);
            cy += CHIP_H + GAP;
        }

        // ---- the nav row under the bubble, beside the guide's feet
        navX0 = bx;
        navX1 = bx + bw;
        int ny = navY();
        boolean last = index == scenes.size() - 1;
        nextButton = new IntroButton(navX1 - 64, ny, 64, NAV_H, last ? "Finish" : "Next", IntroButton.Kind.PRIMARY,
                this::next);
        nextButton.active = !nextBlocked();
        add(nextButton, false);
        if (index > 0) {
            add(new IntroButton(navX1 - 64 - GAP - 70, ny, 70, NAV_H, "Previous", IntroButton.Kind.PLAIN, this::previous),
                    false);
        }
        if (!last) {
            add(new IntroButton(navX0, ny, 74, NAV_H, "Skip Guide", IntroButton.Kind.PLAIN, this::finish), false);
        }
    }

    /**
     * The content width for a chip grid: at least {@code min}, preferring a width every line divides evenly (equal
     * chips, equal gaps, the same two edges for every line), searched upward to {@code max} and then downward.
     */
    private static int gridWidth(int min, int max, List<List<IntroButton>> lines) {
        if (lines.isEmpty()) {
            return min;
        }
        for (int w = min; w <= Math.max(min, max) && w < min + 120; w++) {
            if (divides(w, lines)) {
                return w;
            }
        }
        return min;
    }

    private static boolean divides(int w, List<List<IntroButton>> lines) {
        for (List<IntroButton> line : lines) {
            int m = line.size();
            if ((w - (m - 1) * GAP) % m != 0) {
                return false;
            }
        }
        return true;
    }

    /** Each chip line as laid out: per chip {x, y, w, h, text width}, GUI units of this screen (read by the testkit). */
    public List<List<int[]>> chipRows() {
        List<List<int[]>> out = new ArrayList<>();
        for (List<int[]> row : chipRows) {
            List<int[]> copy = new ArrayList<>();
            for (int[] c : row) {
                copy.add(c.clone());
            }
            out.add(copy);
        }
        return out;
    }

    /** {left, right} of the bubble's content (its text and chip lines), GUI units of this screen. */
    public int[] contentEdges() {
        return new int[]{navX0 + SpeechBubble.padX(), navX1 - SpeechBubble.padX()};
    }

    private IntroButton chip(String label, IntroButton.Kind kind, Runnable action) {
        int w = this.font.width(label) + 18;
        return new IntroButton(0, 0, w, CHIP_H, label, kind, action);
    }

    /** A Yes / No pair: Yes is the suggested answer (accent) until one is clicked; then the clicked one is CHOSEN. */
    private List<IntroButton> yesNo(String yesLabel, Boolean choice, java.util.function.Consumer<Boolean> set) {
        IntroButton.Kind yesKind = choice == null ? IntroButton.Kind.PRIMARY
                : choice ? IntroButton.Kind.CHOSEN : IntroButton.Kind.PLAIN;
        IntroButton.Kind noKind = choice != null && !choice ? IntroButton.Kind.CHOSEN : IntroButton.Kind.PLAIN;
        return List.of(chip(yesLabel, yesKind, () -> set.accept(true)), chip("No thanks", noKind, () -> set.accept(false)));
    }

    private void add(AbstractWidget w, boolean inBubble) {
        buttons.put(w.getMessage().getString(), w);
        if (inBubble) {
            addWidget(w); // input only: the bubble draws it under its own pose
        } else {
            addRenderableWidget(w);
        }
    }

    /** Goes to the next scene, or finishes after the last. Does nothing while the scene waits for an answer. */
    public void next() {
        if (nextBlocked()) {
            return;
        }
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
        if (s == Scene.MENUS) {
            categoryIndex = 0;
            categorySince = now;
        }
        rebuildWidgets();
    }

    /** Picks a theme: applied and saved at once (the menu and this tour change to it). Nothing changes until a click. */
    public void pickTheme(MenuTheme t) {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setTheme(t);
        cfg.save();
        com.killer560.hub.mainmenu.ThemeAssets.requestLook(com.killer560.hub.mainmenu.MainMenuLook.current()); // its download
        guide.setPose(Guide.Pose.CHEER, MenuAnim.changeTime());
        rebuildWidgets();
    }

    /** Picks the main menu style: Home > Menu Theme's own Main Menu: Fancy / Simple setting, saved. */
    public void pickMainMenu(MainMenuThemeConfig.SceneMode mode) {
        MainMenuThemeConfig cfg = MainMenuThemeConfig.getInstance();
        cfg.setSceneMode(mode);
        if (!cfg.isEnabled()) cfg.setThemed(true);
        cfg.save();
        com.killer560.hub.mainmenu.ThemeAssets.requestLook(com.killer560.hub.mainmenu.MainMenuLook.current()); // its download
        rebuildWidgets();
    }

    /** Picks no custom title screen: the themed main menu off (Display Options' Themed Main Menu), saved. */
    public void pickVanillaMainMenu() {
        MainMenuThemeConfig cfg = MainMenuThemeConfig.getInstance();
        cfg.setThemed(false);
        cfg.save();
        rebuildWidgets();
    }

    /** The ban-log answer: sets Auto Report Bans to it and saves. */
    public void chooseBanLogs(boolean yes) {
        BanReportConfig cfg = BanReportConfig.getInstance();
        cfg.setEnabled(yes);
        cfg.save();
        banChoice = yes;
        rebuildWidgets();
    }

    /** The crash-report answer: sets Auto Crash Reports to it and saves. */
    public void chooseCrashReports(boolean yes) {
        CrashReportConfig cfg = CrashReportConfig.getInstance();
        cfg.setEnabled(yes);
        cfg.save();
        crashChoice = yes;
        rebuildWidgets();
    }

    /** The Auto Update answer: sets Auto Update to it and saves. */
    public void chooseAutoUpdate(boolean yes) {
        AutoUpdateConfig cfg = AutoUpdateConfig.getInstance();
        cfg.setEnabled(yes);
        cfg.save();
        updateChoice = yes;
        rebuildWidgets();
    }

    /** Ends the tour (finished or skipped): it will not show again on its own. An auto-opened tour that ends with a question unanswered (skipped) turns that feature ON, as if Yes was picked. */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (autoOpened) {
            if (banChoice == null) {
                chooseBanLogs(true);
            }
            if (crashChoice == null) {
                chooseCrashReports(true);
            }
            if (updateChoice == null) {
                chooseAutoUpdate(true);
            }
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

        if (showsMenu(s) && !hideBubbleForTest) {
            drawPreview(g, s, partialTick);
        }

        // The guide, and where he faces: the way he walks, else what he talks about.
        int top = boxTop();
        float[] target = target(s, now);
        guide.lookAt(target[0], target[1]);
        if (!guide.walking(now) && bubbleWaiting) {
            bubbleWaiting = false;
            bubble.pop(now);
        }
        guide.update(now);
        guide.draw(g, now, top);
        mouth[0] = guide.mouthX(now);
        mouth[1] = guide.mouthY(now, top);

        if (!bubbleWaiting && !hideBubbleForTest) {
            boolean ready = bubble.settled(now);
            for (AbstractWidget w : bubbleWidgets) {
                w.active = ready;
            }
            bubble.draw(g, now, mouth[0], mouth[1], ready ? () -> {
                for (AbstractWidget w : bubbleWidgets) {
                    w.extractRenderState(g, mouseX, mouseY, partialTick);
                }
            } : null);
        }

        // Nav extras, in the bubble's look: the time estimate in its own small box on the first scene, else the dots.
        if (!confirmingSkip) {
            if (index == 0) {
                drawTimeBox(g, timeBox()[0], navY());
            } else {
                drawDots(g, middleX(dotsWidth()), navY());
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (confirmingSkip) {
            drawConfirm(g);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
        }
    }

    /** {x, y, w, h} of the time estimate's box (on the first scene). */
    public int[] timeBox() {
        int w = this.font.width(TIME_TEXT) + 16;
        return new int[]{middleX(w), navY(), w, NAV_H};
    }

    /** Left x for a box of width {@code w} centered in the nav row's free space: between Skip Guide (or the bubble's
     *  left edge on the last scene) and Previous (or Next on the first scene), so the row's gaps are even. */
    private int middleX(int w) {
        boolean last = index == scenes.size() - 1;
        int left = last ? navX0 : navX0 + 74;
        int right = navX1 - 64 - (index > 0 ? GAP + 70 : 0);
        return left + Math.max(GAP, (right - left - w) / 2);
    }

    private int dotsWidth() {
        int n = scenes.size();
        return 8 + (n - 1) * 4 + (n - 1) * 3 + 14;
    }

    private void drawTimeBox(GuiGraphicsExtractor g, int x, int y) {
        int[] fill = SpeechBubble.fill();
        int w = this.font.width(TIME_TEXT) + 16;
        SpeechBubble.roundBox(g, x, y, w, NAV_H, NAV_H / 2f, fill[0], fill[1], MenuStyle.alpha(MenuStyle.OR, 0xB0), 1f,
                true);
        g.text(this.font, TIME_TEXT, x + 8, y + (NAV_H - 8) / 2 + 1, MenuStyle.DIM, MenuStyle.TEXT_SHADOW);
    }

    private void drawDots(GuiGraphicsExtractor g, int x, int y) {
        int n = scenes.size();
        int w = dotsWidth();
        int[] fill = SpeechBubble.fill();
        SpeechBubble.roundBox(g, x, y, w, NAV_H, NAV_H / 2f, fill[0], fill[1], MenuStyle.alpha(MenuStyle.OR, 0xB0), 1f,
                true);
        int dx = x + 7;
        int dy = y + NAV_H / 2 - 2;
        for (int i = 0; i < n; i++) {
            int c = i == index ? MenuStyle.OR : i < index ? MenuStyle.alpha(MenuStyle.OR, 0x90) : MenuStyle.DIM2;
            int dw = i == index ? 8 : 4;
            SpeechBubble.roundBox(g, dx, dy, dw, 4, 2f, c, c, 0, 0f, false);
            dx += dw + 3;
        }
    }

    private void drawConfirm(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        r.fill(0, 0, this.width, this.height, 0x80000000);
        r.submit();
        int w = 232;
        int h = 64;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 32;
        int[] fill = SpeechBubble.fill();
        SpeechBubble.roundBox(g, x, y, w, h, 14f, fill[0], fill[1], MenuStyle.OR, 1.5f, true);
        ScreenChrome.centered(g, "Skip the tour?", this.width / 2, y + 8, MenuStyle.TXT);
        ScreenChrome.centered(g, "You can replay it from Home > Mod & HUD.", this.width / 2, y + 22, MenuStyle.DIM);
    }

    /** The menus scene steps through a few categories in the picture of the menu. */
    private void tickScene(Scene s, long now) {
        if (s == Scene.MENUS && preview != null) {
            int step = (int) ((now - categorySince) / 1_400_000_000L);
            int want = Math.min(step, TOUR_CATEGORIES.length - 1);
            if (want != categoryIndex) {
                categoryIndex = want;
                preview.selectCategory(TOUR_CATEGORIES[want]);
            }
        }
    }

    /** What the guide faces and points at (GUI units): the menu picture, or his bubble. */
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
        // Arriving: face where the bubble will be, never the viewer's blank middle.
        int[] lane = lane();
        return new float[]{(lane[0] + lane[1]) / 2f, navY() - 40f};
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

    @Override
    public int[] themedPanelBounds() {
        return new int[]{Math.round(bubble.x()), Math.round(bubble.y()), Math.round(bubble.w()), Math.round(bubble.h())};
    }

    public Scene currentScene() {
        return scene();
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

    /** The kind of the tour button {@code label} (PLAIN, PRIMARY or CHOSEN), or null. */
    public String buttonKind(String label) {
        AbstractWidget w = buttons.get(label);
        return w instanceof IntroButton b ? b.kind().name() : null;
    }

    public boolean confirmingSkip() {
        return confirmingSkip;
    }

    /** True once the bubble has popped in and finished typing (screenshots wait for it). */
    public boolean settled() {
        long now = MenuAnim.now();
        return !bubbleWaiting && bubble.settled(now) && bubble.typed(now) && !guide.walking(now);
    }

    /** True while the guide is walking between spots. */
    public boolean guideWalking() {
        return guide.walking(MenuAnim.now());
    }

    /** How many frames the 3D guide has been submitted (0 means the model never drew). */
    public static long guideFrames() {
        return Guide.drawn;
    }

    /** The guide's facing when last drawn, degrees: positive = his face toward the right of the screen. */
    public static float guideTurn() {
        return Guide.lastFacing;
    }

    /** The guide's last drawn box {x0, y0, x1, y1} in unscaled GUI units of the window (screenshot pixels / scale). */
    public static int[] guideWindowBox() {
        return Guide.lastBox.clone();
    }

    /** Where the bubble's tail point was drawn last {x, y}, GUI units of this screen. */
    public float[] tailTip() {
        return new float[]{bubble.lastTipX, bubble.lastTipY};
    }

    /** Where his mouth was computed last frame {x, y} (GUI units of this screen): the point the tail is drawn to. */
    public float[] mouth() {
        return mouth.clone();
    }

    /** Testkit only: leave the bubble and the menu picture out, so only the guide is drawn over the screen behind and
     *  his face can be measured. */
    public static void setHideBubbleForTest(boolean hide) {
        hideBubbleForTest = hide;
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
