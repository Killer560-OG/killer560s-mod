package com.killer560.hub.gui;

import java.util.Map;

/** Setting descriptions for the Carnival category (carnival-1110, from staging-carnival-3). Every label is scoped
 *  "tab name/label" except each tab's switch. */
final class SettingTooltipsCarnival {

    /** What every Carnival auto's tooltips start with. */
    private static final String BAN = "Automation: can get you banned; keep it off on the main server. ";

    private SettingTooltipsCarnival() {
    }

    static void register(Map<String, String> d) {
        d.put("carnival tracker", "In the Hub's Carnival, a HUD with the game you are playing, your Carnival Tokens, this session's games, your best scores, the goals still to reach and whether today's tickets are claimed. Display only.");
        d.put("carnival tracker/current game", "The game you are playing: its score, fruits, catch streak, kills and accuracy, and time left, from the sidebar.");
        d.put("carnival tracker/session", "How many games you finished this session and the tokens they earned.");
        d.put("carnival tracker/best scores", "Your best score in each game, kept between sessions, with this session's best beside it.");
        d.put("carnival tracker/goals left", "The Carnival Goals you have not reached, read when you open Chantelle's Carnival Goals menu.");
        d.put("carnival tracker/ticket reminder", "A HUD line, and a chat line when you walk into the Carnival, while today's 25 Carnival Tickets from the Carnival Leader are unclaimed.");
        d.put("fruit digging solver", "In Fruit Digging, works out from every clue which tile to dig next and with which shovel mode, and labels the grid. Display only.");
        d.put("fruit digging solver/best dig", "Boxes the tile to dig next, in the color of the shovel mode to dig it with, with the mode and its bomb odds over it.");
        d.put("fruit digging solver/clue labels", "Over each dug tile, the clue it gave: bombs nearby (Mines), the best fruit nearby (Treasure, \u2264), the worst (Anchor, \u2265).");
        d.put("fruit digging solver/fruit labels", "Over each tile whose content is known, what it holds: yellow if still in the ground, gray once dug.");
        d.put("fruit digging solver/tile odds", "Over each unknown tile, the chance it holds a bomb or rum.");
        d.put("fruit digging solver/solver hud", "A HUD with digs used, the next dig's multiplier, the tile and shovel mode to use, and its odds (move it in Edit HUD).");
        d.put("fruit digging solver/fruit left", "Adds the fruit still in the ground, per kind, to the HUD.");
        d.put("fruit digging solver/mines color", "The best tile's color when it should be dug in Mines mode (shows the bombs around it).");
        d.put("fruit digging solver/anchor color", "The best tile's color when it should be dug in Anchor mode (marks the lowest fruit around it).");
        d.put("fruit digging solver/treasure color", "The best tile's color when it should be dug in Treasure mode (names the best fruit around it).");
        d.put("zombie shootout helper", "In Zombie Shootout, outlines and times each zombie, boxes the best target and the lit lamps, and shows a HUD. Display only.");
        d.put("zombie shootout helper/zombie outlines", "An outline round each zombie in its armor's color: leather brown, iron gray, gold, diamond aqua.");
        d.put("zombie shootout helper/highest only", "Outlines and times only the zombies worth the most points right now.");
        d.put("zombie shootout helper/zombie timers", "Over each zombie, the seconds before it disappears and its points.");
        d.put("zombie shootout helper/best target", "A filled box on what to shoot first: a lit lamp, else the zombie worth the most and soonest gone.");
        d.put("zombie shootout helper/lit lamps", "A red box round each lit Redstone Lamp (100 points and 1.2x points for 6 seconds).");
        d.put("zombie shootout helper/lamp line", "A line from your crosshair to each lit lamp.");
        d.put("zombie shootout helper/shootout hud", "A HUD with the best target, the lit lamp's time left and how many zombies are up (move it in Edit HUD).");
        d.put("zombie shootout helper/best target color", "Color of the best target's box.");
        d.put("catch a fish helper", "In Catch a Fish, outlines the fish, marks the one to go for (yellow first) and shows your streak and hook on a HUD. Display only.");
        d.put("catch a fish helper/fish outlines", "An outline round every fish in the pond, the Yellow Fish (200 points) in their own color.");
        d.put("catch a fish helper/yellow only", "Outlines only the Yellow Fish.");
        d.put("catch a fish helper/best fish", "A filled box on the fish to go for: the nearest Yellow Fish, else the nearest fish.");
        d.put("catch a fish helper/line to fish", "A line from your crosshair to that fish.");
        d.put("catch a fish helper/fish hud", "A HUD with the fish up, your catch streak, HOOKED while a fish is on your hook, and how long the streak lasts without one (move it in Edit HUD).");
        d.put("catch a fish helper/yellow fish color", "Color of the Yellow Fish outlines and box.");
        d.put("catch a fish helper/other fish color", "Color of the other fish's outlines and box.");
        d.put("zombie shootout triggerbot", "Automation: can get you banned; keep it off on the main server. In Zombie Shootout, shoots the moment your crosshair is on a zombie or a lit lamp. It never aims for you.");
        d.put("zombie shootout triggerbot/shoot with", "Automation: can get you banned; keep it off on the main server. The key it taps to shoot: Use (right click) or Attack (left click). Left or right click steps through them.");
        d.put("zombie shootout triggerbot/dart tube only", "Automation: can get you banned; keep it off on the main server. Shoots only while you hold the Carnival Dart Tube.");
        d.put("zombie shootout triggerbot/shot delay", "Automation: can get you banned; keep it off on the main server. The least time between two shots (100 to 1000 ms); each gap is rolled up to a quarter longer.");
        d.put("zombie shootout triggerbot/aim delay", "Automation: can get you banned; keep it off on the main server. How long the crosshair must be on a new target before the first shot (0 to 500 ms).");
        d.put("auto zombie shootout", "Automation: can get you banned; keep it off on the main server. In Zombie Shootout, turns you to the best target in sight and shoots it. Moving the mouse stops it for that game.");
        d.put("auto zombie shootout/shoot with", "Automation: can get you banned; keep it off on the main server. The key it taps to shoot: Use (right click) or Attack (left click). Left or right click steps through them.");
        d.put("auto zombie shootout/dart tube only", "Automation: can get you banned; keep it off on the main server. Runs only while you hold the Carnival Dart Tube.");
        d.put("auto zombie shootout/shot delay", "Automation: can get you banned; keep it off on the main server. The least time between two shots (100 to 1000 ms); each gap is rolled up to a quarter longer.");
        d.put("auto zombie shootout/aim delay", "Automation: can get you banned; keep it off on the main server. How long the crosshair must be on a new target before it shoots (0 to 500 ms).");
        d.put("auto zombie shootout/lamps first", "Automation: can get you banned; keep it off on the main server. Turns to a lit Redstone Lamp before any zombie (100 points and 1.2x points for 6 seconds). Off: lamps only when no zombie is in sight.");
        d.put("auto zombie shootout/aim mode", BAN + "Legit turns your view to each target in an eased, visible turn. No Rotate turns only the look the server hears: your screen holds still (Free Camera) while your body turns.");
        d.put("auto shovel swap", "Automation: can get you banned; keep it off on the main server. In Fruit Digging, switches your Carnival Shovel to the mode the solver wants for the next dig.");
        d.put("auto shovel swap/swap delay", "Automation: can get you banned; keep it off on the main server. The least time between two swaps or mode taps (100 to 2000 ms).");
        d.put("auto fruit digging", "Automation: can get you banned; keep it off on the main server. In Fruit Digging, turns you to the solver's best tile and digs it. It never walks; moving the mouse stops it for that game.");
        d.put("auto fruit digging/dig delay", "Automation: can get you banned; keep it off on the main server. The least time between one dig landing and the next (100 to 2000 ms).");
        d.put("auto fruit digging/free camera", "Automation: can get you banned; keep it off on the main server. Holds your screen still while your body turns to each tile; your mouse looks around freely.");
        d.put("auto catch a fish", "Automation: can get you banned; keep it off on the main server. In Catch a Fish, turns you to the best fish, casts, and holds Use to reel in a hooked fish. Moving the mouse stops it for that game.");
        d.put("auto catch a fish/cast delay", "Automation: can get you banned; keep it off on the main server. The least time between two casts (100 to 2000 ms).");
        d.put("auto catch a fish/hook wait", "Automation: can get you banned; keep it off on the main server. How long a cast may go without hooking a fish before it is reeled back and cast again (1 to 10 seconds).");
        d.put("auto catch a fish/free camera", "Automation: can get you banned; keep it off on the main server. Holds your screen still while your body turns to each fish; your mouse looks around freely.");
        d.put("fruit digging solver/prevent misclicks", "Cancels your dig on any grid tile but the solver's best tile, so a misclick never wastes a dig. Only blocks your own click.");
        d.put("fruit digging solver/prevent wrong shovel", "Cancels your dig while the Carnival Shovel is not in the mode the solver wants (right click cycles the mode). Only blocks your own click.");
        d.put("fruit digging solver/sneak to dig anyway", "While you sneak, Prevent Misclicks and Prevent Wrong Shovel let your dig through.");
        d.put("fruit digging triggerbot", BAN + "In Fruit Digging, digs the moment your crosshair is on the solver's best tile (with the shovel in its mode). It never turns you and never digs another tile.");
        d.put("fruit digging triggerbot/reaction delay", BAN + "How long your crosshair must be on the tile before it digs (100 to 2000 ms).");
        // carnival-tooltips-end
    }
}
