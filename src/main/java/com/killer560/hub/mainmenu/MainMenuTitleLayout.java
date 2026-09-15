package com.killer560.hub.mainmenu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Themed title screen button layout (2026-09-15, killer560): no Realms button, no language / accessibility
 * icon buttons, and "Swap Accounts" moved from the top-left corner into the main button column:
 * <pre>
 *   Singleplayer
 *   Multiplayer
 *   [other full-width column buttons, e.g. ModMenu's "Mods", dev "Create Test World"]
 *   Swap Accounts
 *   Options... | Quit Game
 *        Playing as &lt;name&gt;   (dim line, or button tooltip when there's no vertical room)
 * </pre>
 * Runs from a Fabric {@code ScreenEvents.AFTER_INIT} listener, which fires after {@code TitleScreen.init()}
 * and every mixin TAIL on it, on first open AND on every resize / GUI-scale change / return from a child
 * screen (Fabric wraps both {@code Screen.init(II)} and {@code Screen.resize(II)}). The listener is
 * registered in a phase ordered AFTER Fabric's default phase, so ModMenu's own AFTER_INIT listener (default
 * phase; finds the Realms button by its "menu.online" key to insert/replace/shrink next to it) has always
 * run first - removing Realms before ModMenu saw it would make ModMenu silently skip its Mods button.
 * <p>
 * Nothing is keyed on pixel positions from other mods: Realms / language / accessibility / Options / Quit are
 * found by their translation keys (javap 26.1.2: TitleScreen uses "menu.online", "menu.options", "menu.quit";
 * CommonButtons.language/accessibility use "options.language" / "options.accessibility"), ModMenu's widgets
 * by class name (no hard runtime dependency), and our button by its marker class. The column is rebuilt from
 * scratch each init, so whatever ModMenu's button style shifted, it ends up in the same place.
 * <p>
 * Removing the Realms button is safe: TitleScreen keeps no field for it (fields: splash,
 * realmsNotificationsScreen, fading, fadeInStart, logoRenderer). The only thing tied to its position is
 * {@code RealmsNotificationsScreen}, which draws its news/invite/trial icons at the hard-coded Realms slot -
 * {@code mixin.MainMenuRealmsNotificationsMixin} cancels that draw while {@link #isAppliedTo} the current
 * screen. ModMenu's update badge is drawn by ModMenuButtonWidget relative to its own x/y/width, so it moves
 * with the button.
 * <p>
 * Only while {@link MainMenuTheme#active()}; otherwise nothing is touched and (see accounts TitleScreenMixin)
 * the old top-left SettingsButtonWidget is used, i.e. the title screen is fully vanilla again.
 */
public final class MainMenuTitleLayout {

    private static final Identifier PHASE = Identifier.fromNamespaceAndPath("killer560smod", "title_layout");

    private static final int COLUMN_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 24;
    private static final int HALF_WIDTH = 98;
    /** Vanilla gap from the last column row's y to the Options/Quit row's y. */
    private static final int OPTIONS_ROW_GAP = 36;
    /** Vanilla column has 3 rows; each extra row moves the column start up by half a step (same as ModMenu). */
    private static final int VANILLA_ROWS = 3;
    /** Keep the "Playing as" line clear of the bottom-left footer lines (mod tag at height-20, version at height-10). */
    private static final int FOOTER_CLEARANCE = 22;

    private static final String KEY_REALMS = "menu.online";
    private static final String KEY_OPTIONS = "menu.options";
    private static final String KEY_QUIT = "menu.quit";
    private static final String KEY_LANGUAGE = "options.language";
    private static final String KEY_ACCESSIBILITY = "options.accessibility";
    private static final String MODMENU_PACKAGE = "com.terraformersmc.modmenu.";

    private static volatile WeakReference<Screen> appliedTo = new WeakReference<>(null);
    private static boolean registered;

    private MainMenuTitleLayout() {
    }

    /** Call once from the client entrypoint. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        ScreenEvents.AFTER_INIT.register(PHASE, (client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                apply(screen);
            }
        });
    }

    /** True while the themed layout (Realms removed) is in effect on {@code screen}. Deliberately not gated on
     *  {@link MainMenuTheme#active()}: if a later error flips the theme off while this screen is open, the Realms
     *  button is still physically gone until the next init (which resets {@link #appliedTo}), so its
     *  notification icons must stay hidden too instead of reappearing next to Swap Accounts. */
    public static boolean isAppliedTo(Screen screen) {
        return screen != null && appliedTo.get() == screen;
    }

    // ---------------------------------------------------------------- swap accounts button

    /** Marker subclass so the layout can find our button without matching on its label. A plain vanilla
     *  Button, so MainMenuButtonMixin themes it exactly like Singleplayer/Multiplayer. */
    public static final class SwapAccountsButton extends Button.Plain {
        private SwapAccountsButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }
    }

    /** Themed-mode Swap Accounts button. Starts at the old top-left bounds with the old long label, so if the
     *  layout never runs (listener missing, or it failed) the button is still where users know it. */
    public static Button createSwapAccountsButton(Button.OnPress onPress) {
        return new SwapAccountsButton(4, 4, 260, ROW_HEIGHT, Component.literal(fallbackLabel()), onPress);
    }

    private static String playerName() {
        try {
            User user = Minecraft.getInstance().getUser();
            return user != null ? user.getName() : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String fallbackLabel() {
        return "Swap Accounts - Playing as " + playerName();
    }

    // ---------------------------------------------------------------- layout

    private static void apply(Screen screen) {
        appliedTo = new WeakReference<>(null);
        if (!MainMenuTheme.active()) {
            return;
        }
        SwapAccountsButton swap = null;
        try {
            List<AbstractWidget> widgets = Screens.getWidgets(screen);

            AbstractWidget realms = null;
            AbstractWidget language = null;
            AbstractWidget accessibility = null;
            AbstractWidget options = null;
            AbstractWidget quit = null;
            AbstractWidget mods = null;
            for (AbstractWidget w : widgets) {
                if (w instanceof SwapAccountsButton s) {
                    swap = s;
                    continue;
                }
                String key = translationKey(w);
                if (KEY_REALMS.equals(key)) {
                    realms = w;
                } else if (KEY_LANGUAGE.equals(key)) {
                    language = w;
                } else if (KEY_ACCESSIBILITY.equals(key)) {
                    accessibility = w;
                } else if (KEY_OPTIONS.equals(key) && options == null) {
                    options = w;
                } else if (KEY_QUIT.equals(key) && quit == null) {
                    quit = w;
                } else if (mods == null && isModMenuButton(w)) {
                    mods = w;
                }
            }
            if (options == null || quit == null) {
                // Unrecognised title screen (another mod rebuilt it): don't guess, leave it alone.
                return;
            }

            int cx = screen.width / 2;
            int oldRowY = options.getY();
            boolean modsIsIcon = mods != null && mods.getWidth() <= ROW_HEIGHT;

            // Column: every visible full-width centred widget above the Options row, plus ModMenu's (possibly
            // shrunk) Mods button; Realms / icons / our own button excluded. Stable-sorted by current y.
            List<AbstractWidget> column = new ArrayList<>();
            for (AbstractWidget w : widgets) {
                if (w == swap || w == realms || w == language || w == accessibility || w == options || w == quit) {
                    continue;
                }
                if (w == mods && !modsIsIcon) {
                    column.add(w);
                } else if (w != mods && w.visible && w.getWidth() == COLUMN_WIDTH && w.getX() == cx - COLUMN_WIDTH / 2
                        && w.getY() < oldRowY) {
                    column.add(w);
                }
            }
            if (column.isEmpty()) {
                return;
            }
            column.sort(Comparator.comparingInt(AbstractWidget::getY));
            if (swap != null) {
                column.add(swap);
            }

            int rows = column.size();
            int base = screen.height / 4 + 48;
            int start = base - (ROW_STEP / 2) * Math.max(0, rows - VANILLA_ROWS);
            for (int i = 0; i < rows; i++) {
                AbstractWidget w = column.get(i);
                w.setX(cx - COLUMN_WIDTH / 2);
                w.setY(start + ROW_STEP * i);
                w.setWidth(COLUMN_WIDTH);
            }
            int rowY = start + ROW_STEP * (rows - 1) + OPTIONS_ROW_GAP;

            // Other mods' widgets that sat on the old Options row move with it.
            int dy = rowY - oldRowY;
            if (dy != 0) {
                for (AbstractWidget w : widgets) {
                    if (w != options && w != quit && w != language && w != accessibility && w != swap
                            && !column.contains(w) && w.getY() == oldRowY) {
                        w.setY(w.getY() + dy);
                    }
                }
            }
            options.setX(cx - COLUMN_WIDTH / 2);
            options.setY(rowY);
            options.setWidth(HALF_WIDTH);
            quit.setX(cx + COLUMN_WIDTH / 2 - HALF_WIDTH);
            quit.setY(rowY);
            quit.setWidth(HALF_WIDTH);
            if (modsIsIcon) {
                // ModMenu "icon" style: take the slot right of Quit that the accessibility icon used to have.
                mods.setX(cx + COLUMN_WIDTH / 2 + 4);
                mods.setY(rowY);
            }

            // Removal goes through Fabric's ButtonList, which removes from renderables, children and
            // narratables together.
            removeWidget(screen, widgets, realms);
            removeWidget(screen, widgets, language);
            removeWidget(screen, widgets, accessibility);

            if (swap != null) {
                String name = playerName();
                swap.setMessage(Component.literal("Swap Accounts"));
                // Tab order: right after the last column button instead of at the end of the list.
                AbstractWidget before = rows >= 2 ? column.get(rows - 2) : null;
                int idx = before != null ? widgets.indexOf(before) : -1;
                if (idx >= 0 && widgets.indexOf(swap) != idx + 1) {
                    // Fabric's ButtonList.add(int, w) removes an already-present w first and corrects the
                    // index itself, so no manual remove (which would make idx off by one).
                    widgets.add(idx + 1, swap);
                }
                installPlayingAs(screen, swap, name, cx, rowY + ROW_HEIGHT + 5);
            }
            appliedTo = new WeakReference<>(screen);
        } catch (Throwable t) {
            MainMenuTheme.fail("title layout", t);
            appliedTo = new WeakReference<>(null);
            if (swap != null) {
                try {
                    swap.setMessage(Component.literal(fallbackLabel()));
                    swap.setTooltip(null);
                    swap.setX(4);
                    swap.setY(4);
                    swap.setWidth(260);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void removeWidget(Screen screen, List<AbstractWidget> widgets, AbstractWidget w) {
        if (w == null) {
            return;
        }
        GuiEventListener focused = screen.getFocused();
        widgets.remove(w);
        if (focused == w) {
            screen.setFocused(null);
        }
    }

    /** "Playing as <name>" centred under the Options/Quit row when it fits above the footer lines;
     *  otherwise (very small windows / huge GUI scale) as the button's tooltip instead. */
    private static void installPlayingAs(Screen screen, SwapAccountsButton swap, String name, int cx, int textY) {
        Font font = Minecraft.getInstance().font;
        if (textY + font.lineHeight > screen.height - FOOTER_CLEARANCE) {
            swap.setTooltip(Tooltip.create(Component.literal("Playing as " + name)));
            return;
        }
        String prefix = "Playing as ";
        int prefixW = font.width(prefix);
        int total = prefixW + font.width(name);
        int x = cx - total / 2;
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            if (!MainMenuTheme.active() || !swap.visible) {
                return;
            }
            try {
                int alpha = Math.round(swap.getAlpha() * 255f);
                if (alpha <= 4) {
                    return;
                }
                graphics.text(font, prefix, x, textY, MainMenuTheme.argb(MainMenuTheme.DIM, alpha));
                graphics.text(font, name, x + prefixW, textY, MainMenuTheme.argb(MainMenuTheme.LIGHT_ORANGE, alpha));
            } catch (Throwable t) {
                MainMenuTheme.fail("playing-as line", t);
            }
        });
    }

    // ---------------------------------------------------------------- identification

    private static String translationKey(AbstractWidget w) {
        Component message = w.getMessage();
        if (message == null) {
            return null;
        }
        ComponentContents contents = message.getContents();
        return contents instanceof TranslatableContents tc ? tc.getKey() : null;
    }

    /** ModMenu's Mods button (ModMenuButtonWidget) or its icon variant (UpdateCheckerTexturedButtonWidget),
     *  matched by class name so ModMenu stays optional at runtime. */
    private static boolean isModMenuButton(AbstractWidget w) {
        String name = w.getClass().getName();
        return name.startsWith(MODMENU_PACKAGE)
                && (name.endsWith(".ModMenuButtonWidget") || name.endsWith(".UpdateCheckerTexturedButtonWidget"));
    }
}
