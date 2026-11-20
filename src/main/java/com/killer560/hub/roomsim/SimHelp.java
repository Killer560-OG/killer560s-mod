package com.killer560.hub.roomsim;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeon Sim guide (simhelp-1117, killer560 2026-11-17: "in our sim if they run /help have it open a custom menu that
 * helps walk them through how the sim works"). {@code /help} while the sim runs opens {@link SimHelpScreen}; outside the sim the
 * command does not exist (gated like the other sim commands), so Hypixel's and vanilla's own /help is untouched.
 *
 * <p>The guide is data: {@link #pages()} is a list of pages, each a list of lines, built when the screen opens. A new sim
 * command or feature adds ONE line here: a {@link Line#cmd} row for a command (its roots are what the testkit checks against
 * the live command dispatcher, so a sim command with no row fails 1117-sim-help), a {@link Line#text} (one short bullet) for anything else.
 */
public final class SimHelp {

    /** A line of a page: a heading, a bullet, or a command row (display text, what it does, its roots, runs bare). */
    public record Line(char kind, String text, String desc, List<String> roots, boolean run) {
        public static Line head(String s) { return new Line('h', s, "", List.of(), false); }
        public static Line text(String s) { return new Line('t', s, "", List.of(), false); }
        /** A command row; click runs it when {@code run}, else puts it in chat. {@code roots} are every name it answers to. */
        public static Line cmd(String shown, String desc, boolean run, String... roots) {
            return new Line('c', shown, desc, List.of(roots), run);
        }
        /** The command to run or type: the shown text up to its first " <" or " [" argument. */
        public String command() {
            int i = text.indexOf(" <"), j = text.indexOf(" [");
            int cut = i < 0 ? j : j < 0 ? i : Math.min(i, j);
            return cut < 0 ? text : text.substring(0, cut);
        }
    }

    /** A picture under a page's text: its file in textures/gui/simhelp/ and its pixel size (laid out before it loads). */
    public record Pic(String file, int w, int h) { }

    public record Page(String title, List<Line> lines, boolean settingsButton, List<Pic> pics) { }

    private SimHelp() {
    }

    // The pages' pictures (simhelp-polish-1118): testkit 1118-sim-help-shots captures at 2560x1440 GUI 4 (twice the GUI pixels), cut: screens lossless WebP, world shots WebP q90 at 1600 wide.
    private static final Pic FLOOR = new Pic("floor.webp", 1600, 900);
    private static final Pic DESIGNER = new Pic("designer.webp", 1864, 1180);
    private static final Pic PREVIOUS = new Pic("previous.webp", 1488, 372);
    private static final Pic SETTINGS = new Pic("settings.webp", 1056, 336);
    private static final Pic CHAT = new Pic("chat.webp", 840, 116);
    private static final Pic ITEMS = new Pic("items.webp", 880, 448);
    private static final Pic BOSS_START = new Pic("bossstart.webp", 1032, 760);
    private static final Pic BOSS = new Pic("boss.webp", 1600, 900);
    private static final Pic COOP = new Pic("coop.webp", 1864, 496);
    private static final Pic SIDEBAR = new Pic("sidebar.webp", 680, 340);
    private static final Pic NOT_YET = new Pic("notyet.webp", 1056, 200);
    private static final Pic KEYS = new Pic("keys.webp", 1056, 136);

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("help")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> open(mc));
                            return 1;
                        })));
    }

    public static void open(Minecraft mc) {
        mc.setScreenAndShow(new SimHelpScreen(com.killer560.hub.compat.McCompat.screen(mc)));
    }

    /** The first time the sim opens on this instance: one chat line, never again. */
    public static void hintOnce() {
        SimMenuPrefs p = SimMenuPrefs.cached();
        if (p.helpHintShown) return;
        p.helpHintShown = true;
        p.save();
        ModChat.send("Sim", ModChat.text("Type /help for a guide to the Dungeon Sim."));
    }

    /** Every command root the guide has a row for. */
    public static List<String> coveredRoots() {
        List<String> out = new ArrayList<>();
        for (Page p : pages()) for (Line l : p.lines()) out.addAll(l.roots());
        return out;
    }

    public static List<Page> pages() {
        boolean cheat = BuildVariant.CHEAT_FEATURES_ENABLED;
        List<Page> pages = new ArrayList<>();
        pages.add(new Page("Welcome", List.of(
                Line.text("Practice dungeon that runs on your own computer"),
                Line.text("Connects to nothing: a local world the mod builds"),
                Line.text("Nothing you do here can touch Hypixel"),
                Line.text("Pick a floor or a single room; clear it like on Hypixel"),
                Line.text("The mod keeps your time and score"),
                Line.text("Pages: click one on the left, or use Next and Back"),
                Line.text("Command rows: click to run, or to put in chat if they need a name or number"),
                Line.text("Esc: close this guide"),
                cheat ? Line.text("Cheat build: practice Auto Routes, Auto Clear and the other automation here too") : Line.text("Everything you practice here carries over to the real dungeon"))
                , false, List.of(FLOOR)));
        pages.add(new Page("Starting a Floor", List.of(
                Line.head("The Dungeon Sim menu"),
                Line.text("Open it: /map, or the Dungeon Sim button on the title screen"),
                Line.text("Create a New Map: the Map Designer; pick floor, puzzles and rooms to blood, or draw the floor"),
                Line.text("Previous Runs: finished runs with time and score"),
                Line.text("Load a Room: any captured room alone, with search box and Filters"),
                Line.text("Route Practice: rooms with routes of yours, to repeat"),
                Line.text("F7 Boss: start the boss at any phase (see Boss)"),
                Line.text("Multiplayer: see Co-op"),
                Line.head("Moving between rooms"),
                Line.text("Pause menu (Esc): Change Room button"),
                Line.text("/next and /back: step through rooms"),
                Line.text("/reset: rebuild the room you are in"),
                Line.text("/goto: go to a room on the floor by name")), false, List.of(DESIGNER, PREVIOUS)));
        pages.add(new Page("Settings", List.of(
                Line.text("Sim Settings (mod menu): what you set before a floor"),
                Line.text("Spawn Starred Mobs: starred mobs, Fels, wither doors and keys"),
                Line.text("Real Mob Looks (with it): mobs dressed as recorded on Hypixel"),
                Line.text("Watcher Blood Camp: the Watcher runs the blood camp"),
                Line.text("Watcher Blood Camp off: five seconds in Blood opens the portal"),
                Line.text("Sim Armor: wear Terror or Thermodynamic"),
                Line.text("Also there: Dungeon Breaker charges, invites, Next / Previous Room keys"),
                Line.text("/simloadout: keeps your hotbar"),
                Line.text("/wardrobe and /stats: your armor sets and numbers")), true, List.of(SETTINGS)));
        pages.add(new Page("Commands", List.of(
                Line.head("Rooms and movement"),
                Line.cmd("/map", "Open the Dungeon Sim menu.", true, "map"),
                Line.cmd("/next", "Go to the next room.", true, "next"),
                Line.cmd("/back", "Go to the previous room.", true, "back"),
                Line.cmd("/reset", "Rebuild this room (also /reload).", true, "reset", "reload"),
                Line.cmd("/goto <room>", "Teleport into a room by name; Tab completes (also /tpto).", false, "goto", "tpto"),
                Line.cmd("/warp <room>", "Same as /goto.", false, "warp"),
                Line.cmd("/c <blocks>", "Clip that far along your look; negative goes back.", false, "c"),
                Line.cmd("/center", "Stand in the center of the block you are on.", true, "center"),
                Line.cmd("/fly", "Toggle flight.", true, "fly"),
                Line.cmd("/speed [amount]", "Show or set your walk speed.", true, "speed"),
                Line.cmd("/simwhere", "Say which room you are in.", true, "simwhere"),
                Line.head("Gear and loadout"),
                Line.cmd("/simitem", "Open the item toolkit (also /item, /items).", true, "simitem", "item", "items"),
                Line.cmd("/wardrobe", "Armor sets: click one to wear it (also /wd).", true, "wardrobe", "wd"),
                Line.cmd("/stats", "Your equipment and numbers (also /equipment, /eq).", true, "stats", "equipment", "eq"),
                Line.cmd("/simloadout [save|apply|clear|auto]", "Save, restore or clear your hotbar.", true, "simloadout"),
                Line.head("Mobs, puzzles and score"),
                Line.cmd("/summon <mob>", "Spawn a zombie, skeleton, fel, bat or miniboss on you.", false, "summon"),
                Line.cmd("/simmobs [on|off]", "Starred mobs for the next floor.", true, "simmobs"),
                Line.cmd("/simlive [off|per tile]", "Live mobs from the next floor.", true, "simlive"),
                Line.cmd("/simpuzzle reset", "Reset failed puzzles.", true, "simpuzzle"),
                Line.cmd("/simscore [paul]", "Your score so far; paul toggles Paul's EZPZ.", true, "simscore"),
                Line.head("Building, boss, co-op and chat"),
                Line.cmd("/simbuild <flat|code>", "Build a flat test room or a floor from a map code.", false, "simbuild"),
                Line.cmd("/simboss [phase]", "Start the F7 boss at a phase (p1, p2, s1-s4, core, p4, p5, stop); also /start.", true, "simboss", "start"),
                Line.cmd("/siminvite <join|decline|block> <id>", "Answer a co-op invite.", false, "siminvite"),
                Line.cmd("/pc <text>", "Party chat; the other chat commands are answered here too.", false,
                        SimPartyChat.SIM_CHAT_ROOTS.toArray(new String[0])),
                Line.cmd("/help", "This guide.", true, "help")), false, List.of(CHAT)));
        pages.add(new Page("Items", List.of(
                Line.text("/simitem: opens an item grid"),
                Line.text("Click an item: take one; Give me everything: all of them"),
                Line.text("Aspect of the Void, Hyperion, Spirit Sceptre, Terminator"),
                Line.text("Machine Gun Shortbow, Architect's First Draft, Superboom TNT"),
                Line.text("Ender Pearl, Tactical Insertion, Dungeon Breaker, Bonzo's Staff"),
                Line.text("Jerry-chine Gun, Spring Boots"),
                Line.text("Items carry Hypixel's own ids, so routes and item nodes recorded there match"),
                Line.text("Class: Mage with the left-click beam"),
                Line.text("Mobs have one health, so the beam kills what it hits")), false, List.of(ITEMS)));
        pages.add(new Page("Boss", List.of(
                Line.text("Clear Blood: a portal opens in the Blood room; step in to start the F7 boss"),
                Line.text("Or start it from the menu's F7 Boss entry or /simboss"),
                Line.text("Start at P1 Maxor, P2 Storm, P3 sections 1 to 4, the core, P4 Necron or P5 dragons"),
                Line.text("Floor 7 or Master Mode"),
                Line.text("Spawn Withers off: a quiet arena for setting up routes and waypoints"),
                Line.text("/tpto: go to boss spots during a fight"),
                Line.cmd("/simboss p1", "Start the boss at P1.", true, "simboss")), false, List.of(BOSS_START, BOSS)));
        pages.add(new Page("Co-op", List.of(
                Line.text("Multiplayer (menu): play the boss sim with friends"),
                Line.text("Create: room name, password, up to 5 players, start phase"),
                Line.text("Join: lists open rooms"),
                Line.text("Friends on another Minecraft version are told which version to open"),
                Line.text("Receive Sim Invites (Sim Settings): turns invites on"),
                Line.text("/siminvite: answer an invite"),
                Line.text("The game is a direct connection to the host"),
                Line.text("Only the room's name and address go through the mod's relay")), true, List.of(COOP)));
        pages.add(new Page("Keys and Records", List.of(
                Line.text("Next Room and Previous Room keys (Sim Settings): same as /next and /back"),
                Line.text("Run time and score: on the sidebar and in chat as you clear"),
                Line.text("/simscore: says them again"),
                Line.text("Finished runs: saved on the menu's Previous Runs page"),
                Line.text("Settings and records are the mod's: they follow your profile and the same PC's other instances")), false, List.of(KEYS, SIDEBAR)));
        pages.add(new Page("Not Simulated Yet", List.of(
                Line.text("Mage is the only class, with the left-click beam"),
                Line.text("No sheep, no ultimate, no class abilities"),
                Line.text("Trap rooms: not working yet"),
                Line.text("Machine Gun Shortbow's Rapid Fire (left click): not modeled"),
                Line.text("Necron's platform break: not modeled; the older wiki text about it does not apply"),
                Line.text("A friend on another Minecraft version cannot join your co-op room yet")), false, List.of(NOT_YET)));
        return pages;
    }
}
