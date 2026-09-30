package com.killer560.hub.compat;

import com.killer560.hub.util.ChatColors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Everything that moved between Minecraft versions, for 26.1.2.
 *
 * <p>There is one of these per supported Minecraft version, in {@code src/mc26_1/java},
 * {@code src/mc26_2/java} and so on, and {@code build.gradle} puts exactly one of them on the source path
 * according to {@code minecraft_version}. They are plain classes with identical public statics rather than an
 * interface and implementations: there is no runtime indirection, no registry to keep in step, and the wrong
 * one cannot be selected because only one is ever compiled. It is the same shape as the generated
 * {@code BuildVariant}.
 *
 * <p><b>The rule.</b> Nothing under {@code src/main/java} may touch a Minecraft API that differs between
 * versions; it calls this instead. That is what makes a new Minecraft version a new directory here rather than
 * another sweep of 229 files - which is what porting to 26.2 measured at before this existed.
 *
 * <p><b>Keep every version's copy method-for-method identical in signature.</b> A method present in one and
 * missing in another turns a clean compile on one version into a break on the other, which is exactly the
 * failure this class exists to prevent. When you add one, add it everywhere.
 */
public final class McCompat {

    private McCompat() {
    }

    // ------------------------------------------------------------------------------------------------ screens
    //
    // 26.2 removes both `Minecraft.screen` and `Minecraft.setScreen`, and nothing on Minecraft returns a Screen
    // any more - 396 of the 706 errors a straight 26.2 compile produces, across 158 files. On 26.1.2 these are
    // the field and the method they always were.

    /** The screen that is open, or null for none. */
    public static Screen screen(Minecraft client) {
        return client.screen;
    }

    /** Opens a screen, or closes the current one when given null. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    /** Whether a screen is open at all - the commonest of the 350 reads, and clearer than a null test. */
    public static boolean hasScreen(Minecraft client) {
        return client.screen != null;
    }

    // -------------------------------------------------------------------------------------- the in-game overlay
    //
    // 26.2 splits Gui in two: a new net.minecraft.client.gui.Hud owns the in-game overlay and Gui keeps the
    // screen stack. Titles, the chat component, the tab list and the deferred-subtitle hook all moved across,
    // and Hud is reached as Minecraft.gui.hud - NOT Minecraft.hud, which does not exist (checked with javap on
    // both classes, because the port notes said otherwise). On 26.1.2 they are all still Gui's.

    /** Sets the big title line. */
    public static void setTitle(Minecraft client, Component title) {
        client.gui.setTitle(title);
    }

    /** Sets the line under the title. */
    public static void setSubtitle(Minecraft client, Component subtitle) {
        client.gui.setSubtitle(subtitle);
    }

    /** Sets the title's fade-in, hold and fade-out, in ticks. */
    public static void setTimes(Minecraft client, int fadeIn, int stay, int fadeOut) {
        client.gui.setTimes(fadeIn, stay, fadeOut);
    }

    /** The chat component, for adding a client-side line or a history entry. */
    public static ChatComponent chat(Minecraft client) {
        return client.gui.getChat();
    }

    /** The tab-list overlay. */
    public static PlayerTabOverlay tabList(Minecraft client) {
        return client.gui.getTabList();
    }

    /** Vanilla's end-of-background hook, so subtitles still show behind a custom screen background. */
    public static void extractDeferredSubtitles(Minecraft client) {
        client.gui.extractDeferredSubtitles();
    }

    /**
     * Whether the player has hidden the HUD (F1).
     *
     * <p>26.2 moves this off Options and onto {@code Hud.isHidden()}. The mod only ever reads it, at twelve
     * HUD-render gates, so a getter is the whole of what is needed - there is no setter here on purpose.
     */
    public static boolean hudHidden(Minecraft client) {
        return client.options.hideGui;
    }

    /**
     * The sidebar slot a team's colour maps to, or null when the team has no colour.
     *
     * <p>26.1.2: {@code PlayerTeam.getColor()} is a {@code ChatFormatting} and {@code DisplaySlot} has a static
     * {@code teamColorToSlot} for it. 26.2 replaces both - the colour is an {@code Optional<TeamColor>} and the
     * slot hangs off the colour as {@code TeamColor.displaySlot()} - so the whole two-step lives here.
     */
    public static DisplaySlot teamDisplaySlot(PlayerTeam team) {
        return DisplaySlot.teamColorToSlot(team.getColor());
    }

    // ------------------------------------------------------------------------------ the ChatFormatting table
    //
    // See util/ChatColors: the five accessors 26.2 deleted are derived there, shared by both versions, because
    // four of the five follow from toString()/ordinal()/name(). The one that does not is the 16-entry RGB
    // table, and this is where it gets checked against the API that still has it.

    /**
     * Compares {@link ChatColors#RGB} against the real {@code ChatFormatting.getColor()}, on the version that
     * still has that method.
     *
     * @return one line per disagreement, empty when the table is right.
     */
    public static List<String> verifyChatColors() {
        List<String> problems = new ArrayList<>();
        for (ChatFormatting f : ChatFormatting.values()) {
            Integer real = f.getColor();
            Integer ours = ChatColors.color(f);
            if (!Objects.equals(real, ours)) {
                problems.add(f.name() + ": vanilla " + describe(real) + ", ChatColors " + describe(ours));
            }
            if (f.isColor() != ChatColors.isColor(f)) {
                problems.add(f.name() + ": vanilla isColor " + f.isColor() + ", ChatColors "
                        + ChatColors.isColor(f));
            }
            if (f.getChar() != ChatColors.code(f)) {
                problems.add(f.name() + ": vanilla char '" + f.getChar() + "', ChatColors '"
                        + ChatColors.code(f) + "'");
            }
            if (!f.getName().equals(ChatColors.name(f))) {
                problems.add(f.name() + ": vanilla name " + f.getName() + ", ChatColors " + ChatColors.name(f));
            }
            if (ChatFormatting.getByName(f.getName()) != ChatColors.byName(f.getName())) {
                problems.add(f.name() + ": byName(" + f.getName() + ") disagrees");
            }
        }
        return problems;
    }

    private static String describe(Integer rgb) {
        return rgb == null ? "null" : String.format("0x%06X", rgb);
    }

    // ------------------------------------------------------------------------------------------------ monitors
    //
    // 26.2 turns com.mojang.blaze3d.platform.Monitor into a record, so getCurrentMode/getX/getY/getMonitor
    // become currentMode/x/y/monitor. Window's own getX()/getY() are UNCHANGED - both still exist in 26.2, which
    // javap confirmed, so only the Monitor reads come through here and the borderless code's window reads do not.

    /** The monitor's current video mode. */
    public static VideoMode currentMode(Monitor monitor) {
        return monitor.getCurrentMode();
    }

    /** The monitor's left edge in virtual-desktop coordinates. */
    public static int monitorX(Monitor monitor) {
        return monitor.getX();
    }

    /** The monitor's top edge in virtual-desktop coordinates. */
    public static int monitorY(Monitor monitor) {
        return monitor.getY();
    }

    /** The monitor's GLFW handle. */
    public static long monitorHandle(Monitor monitor) {
        return monitor.getMonitor();
    }

}
