package com.killer560.hub.accounts.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Which launcher started this game, and its saved accounts (killer560, 2026-11-01: "If i play on modrinth and try to swap
 * accounts ... it is pulling the accounts from my prism launcher instead of accounts from modrinth. Make that swap.").
 * <p>
 * The Modrinth App starts the game through its own main class, {@code com.modrinth.theseus.MinecraftLaunch} (seen in the
 * stack traces of his Modrinth profiles' logs), so the JVM's {@code sun.java.command} names it; a game dir of the form
 * {@code <app>/profiles/<name>} with an app.db two levels up is the fallback. Anything else is Prism, as before. The
 * system property {@code killer560smod.accounts.launcher} ({@code prism} or {@code modrinth}) overrides the detection.
 */
public final class LauncherAccounts {

    private LauncherAccounts() {
    }

    /** The launcher whose accounts this game should list. */
    public static PrismAccount.Source current() {
        return detect(System.getProperty("killer560smod.accounts.launcher"), System.getProperty("sun.java.command"),
                gameDir());
    }

    static PrismAccount.Source detect(String override, String javaCommand, Path gameDir) {
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(Locale.ROOT).startsWith("modrinth")
                    ? PrismAccount.Source.MODRINTH : PrismAccount.Source.PRISM;
        }
        if (javaCommand != null && javaCommand.startsWith("com.modrinth.theseus.")) {
            return PrismAccount.Source.MODRINTH;
        }
        return ModrinthAccountStore.appDbAbove(gameDir) != null ? PrismAccount.Source.MODRINTH : PrismAccount.Source.PRISM;
    }

    /** The current launcher's accounts. */
    public static List<PrismAccount> load() throws IOException {
        return current() == PrismAccount.Source.MODRINTH
                ? ModrinthAccountStore.load(ModrinthAccountStore.appDb(gameDir()))
                : PrismAccountStore.load();
    }

    private static Path gameDir() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (Throwable t) {
            return null;
        }
    }
}
