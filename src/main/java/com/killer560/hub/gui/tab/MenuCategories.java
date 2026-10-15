package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Option A menu's categories (2026-10-12; killer560 left the grouping to us): Home, then Dungeons, HUD, Chat, Items,
 * Social, Cosmetics, Misc, then Profiles - Favourites sits after Home in the sidebar but is not a category of its own.
 *
 * <p>The feature tabs themselves are still made by the old category folders ({@link DungeonTab}, {@link HudElementsTab},
 * {@link GeneralTab}, {@link DisplayTab}, {@link HelpersTab}, ...), which keep every {@code CHEAT_FEATURES_ENABLED} gate
 * where it was; this class only regroups what they built. Each leaf remembers the category it USED to live in
 * ({@link #legacyCategory}), because the setting tooltips scoped by category ("dungeon/mode", "hud elements/scale",
 * "helpers/mode") are keyed by those names - so no tooltip changes or goes missing with the regroup.
 *
 * <p>Inside a category every legit section comes first and every cheat-only one after it (the menu placement rule: the
 * menu draws the cheat block under one red "Cheat" header), each run alphabetical by display name - see {@link #cheatLast}.
 */
public final class MenuCategories {

    private MenuCategories() {
    }

    /** Leaf (or group) tab -> the top-level category name it had before the regroup. */
    private static final Map<BaseTab, String> LEGACY = new IdentityHashMap<>();
    /** Any tab -> the category it is in now, and the group folder (Secrets, Puzzle Solvers) holding it, if any. */
    private static final Map<BaseTab, BaseTab> PARENT = new IdentityHashMap<>();

    public static final String HOME = "Home";
    public static final String PROFILES = "Profiles";

    /** The top-level list: Home, the seven categories, Profiles. */
    public static List<BaseTab> build() {
        LEGACY.clear();
        HomeTab home = new HomeTab();
        ProfilesTab profiles = new ProfilesTab();
        FolderTab general = new GeneralTab();
        FolderTab display = new DisplayTab();
        FolderTab chat = new ChatTab();
        FolderTab social = new SocialTab();
        FolderTab cosmetics = new CosmeticsCategoryTab();
        FolderTab items = new ItemsTab();
        FolderTab hud = new HudElementsTab();
        FolderTab helpers = new HelpersTab();
        FolderTab dungeon = new DungeonTab();
        for (BaseTab t : List.of(home, profiles, general, display, chat, social, cosmetics, items, hud, helpers, dungeon)) {
            remember(t, t.name);
        }

        Map<Class<?>, BaseTab> pool = new LinkedHashMap<>();
        for (FolderTab f : List.of(general, display, chat, social, cosmetics, items, hud, helpers, dungeon)) {
            for (BaseTab sub : f.subTabs()) {
                pool.put(sub.getClass(), sub);
            }
        }

        List<BaseTab> dungeons = take(pool, dungeon.subTabs());
        List<BaseTab> hudList = new ArrayList<>();
        // Gather the HUD tabs; the order they are shown in is the alphabetical one cheatLast gives every category.
        for (Class<?> c : List.of(HealthAndManaBarsTab.class, CustomScoreboardTab.class, StorageOverlayTab.class,
                AbilityCooldownTab.class, QuiverDisplayTab.class, SpringBootsTab.class, LagDisplayTab.class,
                PositionTab.class, RealTimeTab.class, CrosshairTab.class, HidePlayersTab.class, ObjectHiderTab.class,
                GifPlayerTab.class, DvdTab.class)) {
            BaseTab t = pool.remove(c);
            if (t != null) {
                hudList.add(t);
            }
        }
        hudList.addAll(take(pool, hud.subTabs()));
        List<BaseTab> chatList = take(pool, chat.subTabs());
        List<BaseTab> itemsList = take(pool, items.subTabs());
        BaseTab rarity = pool.remove(ItemRarityTab.class);
        if (rarity != null) {
            itemsList.add(rarity);
        }
        List<BaseTab> socialList = take(pool, social.subTabs());
        List<BaseTab> cosmeticsList = take(pool, cosmetics.subTabs());
        List<BaseTab> misc = new ArrayList<>();
        misc.addAll(take(pool, general.subTabs()));
        misc.addAll(take(pool, display.subTabs()));
        misc.addAll(take(pool, helpers.subTabs()));

        List<BaseTab> top = new ArrayList<>();
        top.add(home);
        top.add(new CategoryTab("Dungeons", "⚔", cheatLast(dungeons)));
        top.add(new CategoryTab("HUD", "▣", cheatLast(hudList)));
        top.add(new CategoryTab("Chat", "✉", cheatLast(chatList)));
        top.add(new CategoryTab("Items", "◆", cheatLast(itemsList)));
        top.add(new CategoryTab("Social", "☺", cheatLast(socialList)));
        top.add(new CategoryTab("Cosmetics", "✦", cheatLast(cosmeticsList)));
        top.add(new CategoryTab("Misc", "⚙", cheatLast(misc)));
        top.add(profiles);
        relink(top);
        return top;
    }

    /** Re-reads which category and group each tab sits in (after the testing build moved tabs into Untested). */
    public static void relink(List<BaseTab> top) {
        PARENT.clear();
        for (BaseTab t : top) {
            if (t instanceof FolderTab f) {
                for (BaseTab sub : f.subTabs()) {
                    PARENT.put(sub, t);
                    if (sub instanceof FolderTab g) {
                        for (BaseTab leaf : g.subTabs()) {
                            PARENT.put(leaf, sub);
                        }
                    }
                }
            }
        }
    }

    /** The folder directly holding {@code tab} (a category, or the Secrets / Puzzle Solvers group), or null at the top. */
    public static BaseTab parent(BaseTab tab) {
        return PARENT.get(tab);
    }

    /** The top-level entry holding {@code tab} (itself when it is top level). */
    public static BaseTab category(BaseTab tab) {
        BaseTab t = tab;
        BaseTab p;
        while ((p = PARENT.get(t)) != null) {
            t = p;
        }
        return t;
    }

    /** The name of the category {@code tab} lived in before 2026-10-12 ("Dungeon", "Hud Elements", "General", ...),
     *  for the category-scoped tooltip keys. Its current category's name for anything made after that. */
    public static String legacyCategory(BaseTab tab) {
        String s = LEGACY.get(tab);
        return s != null ? s : category(tab).name;
    }

    private static void remember(BaseTab tab, String category) {
        LEGACY.putIfAbsent(tab, category);
        if (tab instanceof FolderTab f) {
            for (BaseTab sub : f.subTabs()) {
                remember(sub, category);
            }
        }
    }

    private static List<BaseTab> take(Map<Class<?>, BaseTab> pool, List<BaseTab> order) {
        List<BaseTab> out = new ArrayList<>();
        for (BaseTab t : order) {
            if (pool.remove(t.getClass()) != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The one ordering rule (killer560, 2026-10-14: "sort them alphabetically or something of your choosing"): inside a
     * category every legit tab comes first, then every cheat-only one, and each of those two runs is alphabetical by the
     * tab's display name, ignoring case. The Secrets and Puzzle Solvers groups are placed by their own names like any
     * tab and sort their own sections the same way; the one exception is Solver Highlights, the shared setting of every
     * puzzle solver, which stays the first section of Puzzle Solvers.
     */
    static List<BaseTab> cheatLast(List<BaseTab> in) {
        List<BaseTab> legit = new ArrayList<>();
        List<BaseTab> cheat = new ArrayList<>();
        for (BaseTab t : in) {
            if (t instanceof FolderTab f && !(t instanceof CategoryTab)) {
                f.replaceSubTabs(cheatLastGroup(f.subTabs()));
            }
            (t.isCheatOnly() ? cheat : legit).add(t);
        }
        legit.sort(BY_NAME);
        cheat.sort(BY_NAME);
        legit.addAll(cheat);
        return legit;
    }

    private static final java.util.Comparator<BaseTab> BY_NAME =
            java.util.Comparator.comparing((BaseTab t) -> t.name.toLowerCase(java.util.Locale.ROOT))
                    .thenComparing(t -> t.name);

    /** A group's sections: alphabetical, Solver Highlights first. */
    private static List<BaseTab> cheatLastGroup(List<BaseTab> in) {
        List<BaseTab> out = new ArrayList<>(in);
        out.sort(BY_NAME);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof SolverHighlightsTab) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }
}
