package com.killer560.hub.compat;

import java.util.List;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

/**
 * Everything that moved between Minecraft versions, for 26.2.
 *
 * <p>See the 26.1 copy in {@code src/mc26_1/java} for what this class is for and the rule that governs it.
 * Keep the two method-for-method identical in signature: a method in one and not the other turns a clean
 * compile on one Minecraft version into a break on the other.
 */
public final class McCompat {

    private McCompat() {
    }

    // ------------------------------------------------------------------------------------------------ screens
    //
    // 26.2 takes the screen off Minecraft entirely and gives it to Minecraft.gui: the field and setScreen are
    // gone, and nothing on Minecraft returns a Screen any more. Found by disassembling the one survivor,
    // Minecraft.setScreenAndShow, which turns out to be a Gui.setScreen call followed by a frame render - so
    // it is NOT the equivalent of the old setScreen and must not be used as one here, or every screen change
    // would force a render.
    //
    // Gui.screen() is the reader and Gui.setScreen(Screen) the writer. On 26.1.2 both were on Minecraft.
    // This is the single largest difference between the two versions: 396 of the 706 errors a straight 26.2
    // compile produced, across 173 files.

    /** The screen that is open, or null for none. */
    public static Screen screen(Minecraft client) {
        return client.gui.screen();
    }

    /** Opens a screen, or closes the current one when given null. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    /** Whether a screen is open at all - the commonest of the 350 reads, and clearer than a null test. */
    public static boolean hasScreen(Minecraft client) {
        return client.gui.screen() != null;
    }

    // -------------------------------------------------------------------------------------- the in-game overlay
    //
    // 26.2 splits Gui in two. The new net.minecraft.client.gui.Hud owns the in-game overlay and Gui keeps the
    // screen stack, so setTitle, setSubtitle, setTimes, getChat, getTabList and extractDeferredSubtitles are all
    // Hud's now. Hud is reached as Minecraft.gui.hud, a public final field on Gui - there is NO Minecraft.hud,
    // which javap on both classes settled.

    /** Sets the big title line. */
    public static void setTitle(Minecraft client, Component title) {
        client.gui.hud.setTitle(title);
    }

    /** Sets the line under the title. */
    public static void setSubtitle(Minecraft client, Component subtitle) {
        client.gui.hud.setSubtitle(subtitle);
    }

    /** Sets the title's fade-in, hold and fade-out, in ticks. */
    public static void setTimes(Minecraft client, int fadeIn, int stay, int fadeOut) {
        client.gui.hud.setTimes(fadeIn, stay, fadeOut);
    }

    /** The chat component, for adding a client-side line or a history entry. */
    public static ChatComponent chat(Minecraft client) {
        return client.gui.hud.getChat();
    }

    /** The tab-list overlay. */
    public static PlayerTabOverlay tabList(Minecraft client) {
        return client.gui.hud.getTabList();
    }

    /** Vanilla's end-of-background hook, so subtitles still show behind a custom screen background. */
    public static void extractDeferredSubtitles(Minecraft client) {
        client.gui.hud.extractDeferredSubtitles();
    }

    /**
     * Whether the player has hidden the HUD (F1).
     *
     * <p>26.2 takes {@code hideGui} off {@code Options} and makes it {@code Hud}'s own private {@code isHidden},
     * read through {@code isHidden()} and flipped by {@code toggle()}. The mod only reads it, so only the read
     * is here.
     */
    public static boolean hudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }

    /**
     * The sidebar slot a team's colour maps to, or null when the team has no colour.
     *
     * <p>26.2 reshapes this twice over: {@code PlayerTeam.getColor()} is now an {@code Optional<TeamColor>}
     * rather than a {@code ChatFormatting}, and the static {@code DisplaySlot.teamColorToSlot} is gone because
     * each {@code TeamColor} carries its own {@code displaySlot()}. Read off the two classes with javap; the
     * empty Optional is the case 26.1.2 expressed as a null return, so it stays a null return here.
     */
    public static DisplaySlot teamDisplaySlot(PlayerTeam team) {
        return team.getColor().map(TeamColor::displaySlot).orElse(null);
    }

    // ------------------------------------------------------------------------------ the ChatFormatting table
    //
    // See util/ChatColors. 26.2 is the version that no longer has getColor(), so there is nothing here to check
    // the table against - the check is real only on 26.1.2 and this copy exists to keep the signatures matched.

    /**
     * Always empty on 26.2: {@code ChatFormatting.getColor()} is exactly what this version removed, so the
     * table has nothing to be compared with. Reporting "no problems" from a check that cannot run would be a
     * lie, so the 26.1.2 copy is the one that means anything and the caller says so in its log line.
     */
    public static List<String> verifyChatColors() {
        return List.of();
    }

    // ------------------------------------------------------------------------------------------------ monitors
    //
    // 26.2 makes com.mojang.blaze3d.platform.Monitor a record, so the four getters became record components:
    // getCurrentMode/getX/getY/getMonitor are currentMode/x/y/monitor. Window is NOT affected - Window.getX()
    // and getY() still exist in 26.2 (javap), so the borderless code's window reads stay where they are and
    // only these four come through the facade.

    /** The monitor's current video mode. */
    public static VideoMode currentMode(Monitor monitor) {
        return monitor.currentMode();
    }

    /** The monitor's left edge in virtual-desktop coordinates. */
    public static int monitorX(Monitor monitor) {
        return monitor.x();
    }

    /** The monitor's top edge in virtual-desktop coordinates. */
    public static int monitorY(Monitor monitor) {
        return monitor.y();
    }

    /** The monitor's GLFW handle. */
    public static long monitorHandle(Monitor monitor) {
        return monitor.monitor();
    }

}
