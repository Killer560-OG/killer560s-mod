package com.killer560.hub.gui;

import java.util.Map;

/** Setting descriptions for the Mining tabs of staging-mining-3 (pulled to main 2026-11-04). Scoped "tab name/label" except
 *  each switch. */
final class SettingTooltipsMining3 {

    private SettingTooltipsMining3() {
    }

    static void register(Map<String, String> d) {
        d.put("cold overlay", "In the Glacite Tunnels and Mineshafts, draws frost over your screen that thickens as the sidebar's Cold rises, the way powder snow looks. A campfire clears it. Draws only.");
        d.put("cold overlay/strength", "How strong the frost is at 100 Cold; it fades in from 0 Cold.");



        d.put("fetchur solver","In the Dwarven Mines, answers Fetchur's daily riddle with the item he wants. Reads chat only; nothing is sent.");
        d.put("fetchur solver/answer in chat", "Adds a chat line under Fetchur's riddle naming the item.");
        d.put("fetchur solver/answer title", "Also shows the item as a title on screen.");


        d.put("fossil solver", "In the Fossil Excavator, works out from the tiles you have dug which fossils can still be under the dirt and marks the tiles most likely to hold one. Draws only; you dig.");
        d.put("fossil solver/show chances", "Fills every other possible tile in blue, stronger the more likely it is; the best tiles are always outlined green.");
        d.put("fossil solver/solver info line", "A line above the menu with how many fossil placements still fit, the fossil once only one kind does, and the fewest tiles left to dig against your chisel charges.");

        d.put("puzzler solver", "In the Dwarven Mines, follows the Puzzler's ten arrows from his start block and marks the block they end on. Draws only; you mine it yourself.");
        d.put("puzzler solver/show box", "Draws a green box on the block to mine. Walls hide it.");
        d.put("puzzler solver/chat coordinates", "Says the block's coordinates in chat when the Puzzler gives his arrows.");

        d.put("sky mall display", "Keeps today's Heart of the Mountain Sky Mall buff on a HUD you can move, from Hypixel's \"New buff\" chat line, with the time until it changes. Reads chat only.");
        d.put("sky mall display/mining islands only", "Shows the HUD only on the mining islands, where the buff works.");
        d.put("sky mall display/alert on", "Shows a title when the new buff is this one. Left click goes forward, right click back.");

        d.put("deployables", "Finds placed lanterns (Dwarven, Mithril, Titanium, Glacite, Will-o'-wisp) by Hypixel's timer label over them, draws the 30 block area they buff and keeps their time left on a HUD. Draws only.");
        d.put("deployables/show radius circle", "Draws the area each deployable buffs. It turns the In Radius color while you stand inside it and get its effect.");
        d.put("deployables/range style", "Circle draws a ring of the radius on the ground under it. Sphere draws a see-through ball of the radius: inside it you get the effect. Left click goes forward, right click back.");
        d.put("deployables/circle thickness", "How thick the ring's line is, in pixels.");
        d.put("deployables/circle height", "How far above the ground the ring is drawn.");
        d.put("deployables/circle color", "The circle or sphere's color while you are outside it.");
        d.put("deployables/in radius color", "The circle or sphere's color while you are inside it and get its effect.");
        d.put("deployables/timer hud", "A HUD with each deployable's name and time left. Move it in the HUD editor.");
        d.put("deployables/timer shows", "Always lists every deployable in sight. Only In Radius lists only the ones you are inside. Left click goes forward, right click back.");
        d.put("deployables/other deployables", "Also tracks power orbs (Radiant, Mana Flux, Overflux, Plasmaflux), the Umberella and Black Holes, anywhere on Skyblock. Flares have no timer label and are not tracked.");

        d.put("wishing compass solver", "In the Crystal Hollows, reads two Wishing Compass trails taken 8 or more blocks apart and marks where they meet, which is what the compass points at. It never stops a use; it reads the particles only.");
        d.put("wishing compass solver/show waypoint", "Draws a box and a label at the solved spot. The coordinates are always said in chat.");

        d.put("worm alert","In the Crystal Hollows, alerts you when your mining spawns a worm (\"You hear the sound of something approaching...\"), says when it is a Scatha, and counts the 30 second spawn cooldown. Reads chat and nametags only.");
        d.put("worm alert/spawn title", "Shows \"Worm!\" on screen when one spawns.");
        d.put("worm alert/spawn sound", "Plays a pling when one spawns.");
        d.put("worm alert/scatha alert", "Shows \"SCATHA!\" when a Scatha's nametag appears near you within 10 seconds of a spawn.");
        d.put("worm alert/cooldown hud", "A HUD counting the 30 second cooldown before the next worm can spawn, and the worms this session. Move it in the HUD editor.");
    }
}
