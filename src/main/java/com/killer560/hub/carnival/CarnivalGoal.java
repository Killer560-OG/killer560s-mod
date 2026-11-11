package com.killer560.hub.carnival;

/**
 * Chantelle's yearly Carnival Goals (SkyHanni's list; the wiki names the same kinds). Each is found in the "Carnival
 * Goals" menu by its item (sand, cod or arrow for the game) and the start of its first lore line, and reached when that
 * item's last lore line reads "COMPLETE" or a goal-complete chat line names it. Unverified in game: the lore and chat
 * wording, which is SkyHanni's.
 */
public enum CarnivalGoal {
    FRUIT_PLAY(CarnivalState.Game.FRUIT_DIGGING, "Play 3 games of Fruit Digging.", "Play 3 games"),
    FRUIT_SCORE(CarnivalState.Game.FRUIT_DIGGING, "Reach 3,000 score in a single game of Fruit Digging.", "Reach 3,000 score"),
    FRUIT_APPLES(CarnivalState.Game.FRUIT_DIGGING, "Dig up 3 Apples in a single game of Fruit Digging.", "Dig up 3 Apples"),
    FRUIT_UNIQUE(CarnivalState.Game.FRUIT_DIGGING, "Dig up 5 unique Fruits in a single game of Fruit Digging.", "Dig up 5 unique Fruits"),
    FRUIT_DRAGONFRUIT(CarnivalState.Game.FRUIT_DIGGING, "Dig up a Dragonfruit.", "Dig up a Dragonfruit"),
    FISH_PLAY(CarnivalState.Game.CATCH_A_FISH, "Play 3 games of Catch a Fish.", "Play 3 games"),
    FISH_SCORE(CarnivalState.Game.CATCH_A_FISH, "Reach 3,000 score in a single game of Catch a Fish.", "Reach 3,000 score"),
    FISH_CATCH(CarnivalState.Game.CATCH_A_FISH, "Catch 30 Fish in a single game of Catch a Fish.", "Catch 30 Fish"),
    FISH_YELLOW(CarnivalState.Game.CATCH_A_FISH, "Catch 5 Yellow Fish in a single game of Catch a Fish.", "Catch 5 Yellow Fish"),
    FISH_STREAK(CarnivalState.Game.CATCH_A_FISH, "Reach a 5 Catch Streak in a single game of Catch a Fish.", "Reach a 5 Catch Streak"),
    ZOMBIE_PLAY(CarnivalState.Game.ZOMBIE_SHOOTOUT, "Play 3 games of Zombie Shootout.", "Play 3 games"),
    ZOMBIE_SCORE(CarnivalState.Game.ZOMBIE_SHOOTOUT, "Reach 3,000 score in a single game of Zombie Shootout.", "Reach 3,000 score"),
    ZOMBIE_SHOOT(CarnivalState.Game.ZOMBIE_SHOOTOUT, "Shoot 60 Zombies in a single game of Zombie Shootout.", "Shoot 60 Zombies"),
    ZOMBIE_DIAMOND(CarnivalState.Game.ZOMBIE_SHOOTOUT, "Shoot 5 Diamond Zombies in a single game of Zombie Shootout.", "Shoot 5 Diamond Zombies"),
    ZOMBIE_LAMPS(CarnivalState.Game.ZOMBIE_SHOOTOUT, "Shoot 5 Redstone Lamps in a single game of Zombie Shootout.", "Shoot 5 Redstone Lamps");

    public final CarnivalState.Game game;
    /** The whole goal sentence, as the chat line and the lore (wrapped) carry it. */
    public final String text;
    public final String shortText;

    CarnivalGoal(CarnivalState.Game game, String text, String shortText) {
        this.game = game;
        this.text = text;
        this.shortText = shortText;
    }

    /** The menu item kind of each game's goals: sand, cod, arrow. */
    static CarnivalState.Game gameOfItem(String itemPath) {
        return switch (itemPath) {
            case "sand" -> CarnivalState.Game.FRUIT_DIGGING;
            case "cod" -> CarnivalState.Game.CATCH_A_FISH;
            case "arrow" -> CarnivalState.Game.ZOMBIE_SHOOTOUT;
            default -> CarnivalState.Game.NONE;
        };
    }

    /** The goal of {@code game} whose sentence the lore {@code joined} (its lines joined by spaces) starts with. */
    static CarnivalGoal fromLore(CarnivalState.Game game, String joined) {
        for (CarnivalGoal g : values()) {
            if (g.game == game && joined.startsWith(g.text.substring(0, g.text.length() - 1))) {
                return g;
            }
        }
        return null;
    }

    /** The goal a chat line names: the whole line is its sentence, with an optional " - " in front. */
    static CarnivalGoal fromChat(String line) {
        String s = line.strip();
        if (s.startsWith("- ")) {
            s = s.substring(2).strip();
        }
        for (CarnivalGoal g : values()) {
            if (g.text.equals(s)) {
                return g;
            }
        }
        return null;
    }
}
