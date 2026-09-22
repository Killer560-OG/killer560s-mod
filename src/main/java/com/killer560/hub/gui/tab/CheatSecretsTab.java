package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The red, cheat-build-only "Secrets" folder, right after the normal one - killer560 (2026-09-21): "move lever aura
 * under secret aura as they should both be there. Can you also create a Secrets tab that is red and right after the
 * normal secrets tab that has all the cheat related secret things in it." Every sub-tab is cheat-only, so
 * {@link FolderTab#isCheatOnly()} draws the folder red; it is only ever constructed inside {@link DungeonTab}'s
 * {@code CHEAT_FEATURES_ENABLED} block, so the legit jar never has it. The normal {@link SecretsTab} keeps the
 * legit sections only.
 */
public class CheatSecretsTab extends FolderTab {

    public CheatSecretsTab() {
        super("Secrets", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>();
        tabs.add(new SecretAuraTab());
        tabs.add(new LeverAuraTab()); // directly under Secret Aura
        tabs.add(new SecretTriggerbotTab());
        tabs.add(new FullBlockTab());
        return tabs;
    }
}
