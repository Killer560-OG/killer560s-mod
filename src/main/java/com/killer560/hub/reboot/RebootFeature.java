package com.killer560.hub.reboot;

import com.killer560.hub.autoupdate.AutoUpdateConfig;
import com.killer560.hub.autoupdate.AutoUpdateFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * /reboot (killer560, 2026-10-13: "is it possible to make the command /reboot that closes the game and will reboot it
 * once it is fully closed?"), also the Reboot button on the menu's Home page.
 *
 * <p>Only a game Prism Launcher (or PolyMC / MultiMC) started can be restarted: the game process's PARENT must be the
 * launcher, and the game folder must be an instance's {@code minecraft} folder, whose parent folder name is the
 * instance id {@code --launch} takes. Anything else (the official launcher, CurseForge, a dev run, the testkit) gets a
 * chat line saying so and nothing happens. Connected to a server, it asks first: a clickable line or
 * {@code /reboot confirm} (or the Home button again) within 10 s.
 *
 * <p>Rebooting starts {@link RebootHelper} - a separate process, the same one-class mechanism Auto Update's installer
 * uses - which waits for this game to exit (2 minutes at most), waits for Auto Update's installer when one started,
 * then runs {@code <launcher> --launch "<instance id>"}. Then the game closes through {@code Minecraft.stop()}, the
 * window close button's own path, so every config saves and a staged update installs as usual.
 */
public final class RebootFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-reboot");
    private static final String NAME = "Reboot";
    public static final long CONFIRM_MS = 10_000;
    public static final long HELPER_TIMEOUT_S = 120;

    private static long confirmUntil;
    private static boolean registered;
    /** The last line shown (or that would have been, with no player), for the testkit and the log. */
    private static volatile String lastMessage = "";

    private RebootFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("reboot")
                        .executes(ctx -> {
                            request();
                            return 1;
                        })
                        .then(ClientCommands.literal("confirm").executes(ctx -> {
                            confirm();
                            return 1;
                        }))));
    }

    // ---- detection ---------------------------------------------------------------------------------------------------

    /** What /reboot found: the launcher to run and the instance id, or why it cannot reboot. */
    public record Detection(String launcher, String launcherName, String instanceId, String reason) {
        public boolean ok() {
            return reason == null;
        }
    }

    /**
     * Pure: {@code parentCommand} is the full path of the process that started this game (null when it has exited or
     * cannot be read), {@code gameDir} the game folder. Prism's instance id is the instance folder's name, the parent
     * of {@code minecraft} (or the older {@code .minecraft}), which holds {@code instance.cfg}.
     */
    public static Detection detect(String parentCommand, Path gameDir) {
        if (parentCommand == null || parentCommand.isBlank()) {
            return new Detection(null, null, null,
                    "the program that started this game is no longer running, so it is not known to be Prism Launcher");
        }
        String exe = Path.of(parentCommand).getFileName().toString();
        String base = exe.toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) {
            base = base.substring(0, base.length() - 4);
        }
        String launcherName = switch (base) {
            case "prismlauncher" -> "Prism Launcher";
            case "polymc" -> "PolyMC";
            case "multimc" -> "MultiMC";
            default -> null;
        };
        if (launcherName == null) {
            return new Detection(null, null, null, "this game was not started by Prism Launcher (it was started by "
                    + exe + ")");
        }
        if (gameDir == null) {
            return new Detection(null, launcherName, null, "the game folder is unknown");
        }
        Path dir = gameDir.toAbsolutePath().normalize();
        Path leaf = dir.getFileName();
        Path instance = dir.getParent();
        if (leaf == null || instance == null || instance.getFileName() == null
                || !(leaf.toString().equals("minecraft") || leaf.toString().equals(".minecraft"))
                || !Files.isRegularFile(instance.resolve("instance.cfg"))) {
            return new Detection(null, launcherName, null, "the game folder " + dir + " is not a " + launcherName
                    + " instance's minecraft folder");
        }
        String id = instance.getFileName().toString();
        if (id.isBlank()) {
            return new Detection(null, launcherName, null, "the instance folder has no name");
        }
        return new Detection(parentCommand, launcherName, id, null);
    }

    /** {@link #detect} for the testkit: "OK|<launcher name>|<instance id>" or "NO|<reason>". */
    public static String detectForTest(String parentCommand, String gameDir) {
        Detection d = detect(parentCommand, gameDir == null ? null : Path.of(gameDir));
        return d.ok() ? "OK|" + d.launcherName() + "|" + d.instanceId() : "NO|" + d.reason();
    }

    /** This game: its parent process and Fabric's game folder. */
    public static Detection detectCurrent() {
        String parent = ProcessHandle.current().parent()
                .filter(ProcessHandle::isAlive)
                .flatMap(p -> p.info().command())
                .orElse(null);
        return detect(parent, FabricLoader.getInstance().getGameDir());
    }

    public static String lastMessage() {
        return lastMessage;
    }

    // ---- the command and the button -----------------------------------------------------------------------------

    /** /reboot. Not a Prism game: says so. In a server: asks for confirmation. Otherwise reboots now. */
    public static void request() {
        Detection d = detectCurrent();
        if (!d.ok()) {
            confirmUntil = 0;
            say(ModChat.bad("Can't reboot: "), ModChat.text(d.reason() + ". Close and start the game yourself."));
            LOGGER.info("[Reboot] refused: {}", d.reason());
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (needsConfirm(client)) {
            confirmUntil = System.currentTimeMillis() + CONFIRM_MS;
            MutableComponent link = Component.literal("[Click to reboot]")
                    .withStyle(s -> s.withColor(ChatFormatting.GOLD).withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/reboot confirm"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("/reboot confirm"))));
            String where = DungeonState.isInDungeon() ? "You are in a dungeon. " : "";
            say(ModChat.text(where + "This closes the game and starts "), ModChat.value(d.instanceId()),
                    ModChat.text(" again. "), link, ModChat.dim(" or /reboot confirm within 10 s."));
            return;
        }
        reboot(d);
    }

    /** /reboot confirm (and the clickable line). */
    public static void confirm() {
        if (confirmUntil == 0 || System.currentTimeMillis() > confirmUntil) {
            confirmUntil = 0;
            say(ModChat.text("Nothing to confirm - type "), ModChat.value("/reboot"), ModChat.text(" first."));
            return;
        }
        confirmUntil = 0;
        Detection d = detectCurrent();
        if (!d.ok()) {
            say(ModChat.bad("Can't reboot: "), ModChat.text(d.reason() + "."));
            return;
        }
        reboot(d);
    }

    /** Home's Reboot button: the first press is /reboot, a second press inside the confirmation window confirms. */
    public static void pressButton() {
        if (confirmUntil != 0 && System.currentTimeMillis() <= confirmUntil) {
            confirm();
        } else {
            request();
        }
    }

    private static boolean needsConfirm(Minecraft client) {
        return client.player != null && !client.hasSingleplayerServer();
    }

    private static void reboot(Detection d) {
        Minecraft client = Minecraft.getInstance();
        try {
            long pid = spawnHelper(ProcessHandle.current().pid(), d.launcher(), List.of(), d.instanceId(), workDir(),
                    AutoUpdateConfig.stagingDir().resolve(AutoUpdateFeature.INSTALLER_PID_FILE), HELPER_TIMEOUT_S);
            LOGGER.info("[Reboot] relauncher started (pid {}) for instance \"{}\" via {}", pid, d.instanceId(),
                    d.launcher());
        } catch (Throwable t) {
            LOGGER.error("[Reboot] could not start the relauncher", t);
            say(ModChat.bad("Can't reboot: "), ModChat.text("the relauncher did not start (" + t + ")."));
            return;
        }
        say(ModChat.text("Closing the game - "), ModChat.value(d.launcherName()), ModChat.text(" will start "),
                ModChat.value(d.instanceId()), ModChat.text(" again once it has fully closed."));
        client.stop();
    }

    /** The relauncher's folder: its class file, reboot.log and the launcher's output. */
    public static Path workDir() {
        return ModPaths.config("killer560smod-reboot");
    }

    /**
     * Starts {@link RebootHelper} waiting on {@code gamePid}; it then runs {@code launcher [prefix...] --launch
     * <instanceId>}. Public for the testkit, which runs it against a dummy process and a stand-in launcher.
     * Returns the helper's PID.
     */
    public static long spawnHelper(long gamePid, String launcher, List<String> prefix, String instanceId, Path work,
                                   Path installerPidFile, long timeoutS) throws Exception {
        Files.createDirectories(work);
        List<String> args = new java.util.ArrayList<>(List.of(Long.toString(gamePid), Long.toString(timeoutS),
                work.toString(), installerPidFile == null ? "-" : installerPidFile.toString(), instanceId, launcher));
        args.addAll(prefix);
        return AutoUpdateFeature.spawnStandalone(RebootHelper.class, work.resolve("helper"),
                work.resolve("helper-out.log"), args);
    }

    private static void say(Component... parts) {
        StringBuilder sb = new StringBuilder();
        for (Component p : parts) {
            sb.append(p.getString());
        }
        lastMessage = sb.toString();
        ModChat.send(NAME, parts);
    }
}
