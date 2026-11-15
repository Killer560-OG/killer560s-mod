package com.killer560.hub.carnival;

/** Auto Accept Game settings (see {@link AutoAcceptGame}). Cheat build only; off by default. */
public final class AutoAcceptGameConfig extends CarnivalSettings {

    private static final AutoAcceptGameConfig INSTANCE = new AutoAcceptGameConfig();

    private AutoAcceptGameConfig() {
        super("autoaccept");
        declare("enabled", false);
    }

    public static AutoAcceptGameConfig getInstance() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reload();
    }
}
