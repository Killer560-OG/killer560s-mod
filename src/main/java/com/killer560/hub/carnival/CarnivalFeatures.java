package com.killer560.hub.carnival;

import com.killer560.hub.hud.HudElementRegistry;

/**
 * Registers every Carnival feature and its HUD elements (carnival-1110, ships before 2.0: the Hub's Carnival during Foxy's
 * Chivalrous Carnival); called once from {@code Killer560ModClient}. The cheat-only autos register only in the cheat build.
 */
public final class CarnivalFeatures {

    private CarnivalFeatures() {
    }

    public static void register() {
        CarnivalState.register();
        CarnivalTracker.register();
        HudElementRegistry.register(new CarnivalTracker.Hud());
        FruitDigging.register();
        HudElementRegistry.register(new FruitDigging.Hud());
        ZombieShootout.register();
        HudElementRegistry.register(new ZombieShootout.Hud());
        CatchAFish.register();
        HudElementRegistry.register(new CatchAFish.Hud());
        // carnival-register-end
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ZombieTriggerbot.register();
            AutoZombieShootout.register();
            AutoShovelSwap.register();
            AutoFruitDigging.register();
            FruitDiggingTriggerbot.register();
            AutoCatchAFish.register();
            // carnival-cheat-register-end
        }
    }
}
