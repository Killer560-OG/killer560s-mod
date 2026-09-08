package com.killer560.hub.cringe;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Cringe lines for {@code /cringe} to pick from at random - anime-protagonist monologues, edgy
 *  one-liners, and internet-trend energy. Lives in a plain-text file in the config folder (one
 *  line per line, "#" for comments) so anyone can add/remove/edit lines themselves; the built-in
 *  {@link #DEFAULTS} are only used to seed that file the first time and as a fallback if it's ever
 *  emptied out or deleted. */
public final class CringeLines {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("killer560smod-cringe").resolve("cringe-lines.txt");

    private static volatile List<String> current = List.of();

    /** @return the current cringe lines - from the config file if present and non-empty,
     *  otherwise the built-in defaults. */
    public static List<String> all() {
        return current;
    }

    public static Path file() {
        return FILE;
    }

    /** (Re)reads the lines file from disk, creating it from {@link #DEFAULTS} if it doesn't exist
     *  yet. Safe to call again later to pick up manual edits without restarting the game. */
    public static void load() {
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                Files.write(FILE, DEFAULTS, StandardCharsets.UTF_8);
            }
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            current = lines.isEmpty() ? DEFAULTS : lines;
        } catch (IOException e) {
            current = DEFAULTS;
        }
    }

    private static final List<String> DEFAULTS = List.of(
            "I am the strongest existence in this world. You just haven't realized it yet.",
            "Do you understand the gap between us? No? I didn't think so.",
            "This isn't even my final form.",
            "You already lost. You just don't know it yet.",
            "My other personality is starting to stir... you should run.",
            "The real fight hasn't even started.",
            "I don't fear death. I fear disappointing my own potential.",
            "Heh. Pathetic. Is that really all you've got?",
            "You've activated my trap card!",
            "Not even my shadow could be defeated by someone like you.",
            "I've been holding back this entire time.",
            "You wouldn't understand this power even if I explained it to you.",
            "Every scar on my body tells a story you could never survive.",
            "I stopped counting my enemies after the hundredth one.",
            "This power... it's incredible. Even I'm surprised sometimes.",
            "I was born different. The rest of you were just born.",
            "You're fighting a war I ended a long time ago.",
            "I don't need luck. I make my own outcomes.",
            "Normal people fear the dark. I am what the dark fears.",
            "They call me a monster. I call it a compliment.",
            "I walk alone because no one can keep up.",
            "Some are born to lead. I was born to dominate.",
            "You think this is a game? For you, maybe. For me, it's a lifestyle.",
            "I don't chase greatness. Greatness chases me.",
            "Weakness is a choice. I simply chose differently.",
            "The moment you doubted yourself, I already won.",
            "I've transcended emotions like fear a long time ago.",
            "Every step I take reshapes the battlefield.",
            "You call it overkill. I call it thoroughness.",
            "My bloodline was never meant for ordinary battles.",
            "I don't need a weapon. I am the weapon.",
            "There's a reason legends whisper my name.",
            "You mistake my silence for weakness. That was your first mistake.",
            "I've already calculated seventeen ways this ends in your defeat.",
            "This scar? A reminder that I never lose twice.",
            "The strong protect. The weak get protected. Guess which one I am.",
            "I don't get angry. I get inevitable.",
            "You're not my rival. You're just a warm-up.",
            "Power isn't given. I took mine.",
            "I stopped explaining myself to people who couldn't keep up years ago.",
            "Sigma energy isn't a choice, it's a lifestyle I was born into.",
            "I move in silence, like a shadow with a grudge.",
            "Some people chase clout. I chase destiny.",
            "You laugh now. You won't be laughing when the prophecy comes true.",
            "I don't have friends. I have people who haven't tested me yet.",
            "My aura alone has ended arguments before they started.",
            "You call it arrogance. I call it self-awareness.",
            "The multiverse isn't ready for what I'm about to become.",
            "I was chosen before I was even born.",
            "This is just phase one. You don't want to see phase two.",
            "I don't sleep. I recharge between conquests.",
            "Everyone has a limit. Mine just hasn't been discovered yet.",
            "You're standing in the presence of a main character.",
            "I don't do small talk. I do prophecy.",
            "The moon isn't the only thing I can eclipse.",
            "My destiny was written before your story even started.",
            "I train in dimensions you haven't unlocked yet.",
            "Call it fate. Call it power. Either way, it's mine.",
            "I don't need an entrance. My presence is the entrance.",
            "You're not ready for the version of me that's coming.",
            "I speak three languages: victory, dominance, and silence.",
            "Some people peak in high school. I haven't peaked yet.",
            "You underestimated me. That's the last mistake you'll get to make.",
            "I don't flinch. Flinching is for people who plan to lose.",
            "My rage is a controlled variable. Yours is a liability.",
            "There's a version of me from another timeline that's even scarier.",
            "I don't need applause. History will do that for me.",
            "You brought a plan. I brought inevitability.",
            "This power was sealed away for a reason. Good thing I broke the seal.",
            "I've already won this in every timeline where I try.",
            "You're the final boss of nothing. I'm the final boss of everything.",
            "My ancestors are watching, and honestly? They're impressed.",
            "I don't need to raise my voice. My presence already speaks.",
            "Underestimate me one more time. I dare you.",

            // Extra batch, requested even cringier - self-written, same over-the-top energy.
            "Careful. My villain arc has a body count.",
            "I didn't choose the dark side. The dark side begged.",
            "Somewhere, a prophecy just updated its main character to me.",
            "You're playing checkers in a game I invented three dimensions ago.",
            "I don't have a glow-up. I have an awakening.",
            "My tears turned to ice the day the world stopped deserving them.",
            "There's a throne with my name on it. I just haven't bled for it yet.",
            "You want my backstory? It's classified. For your safety.",
            "I was cursed with too much power and not enough people worth using it on.",
            "The last person who doubted me is now a cautionary tale.",
            "I don't get hurt. I collect damage and cash it in later.",
            "My eyes changed color the day I stopped being ordinary.",
            "You're not in my league. You're not even in the sport.",
            "I already saw this conversation end in a dream. You lost there too.",
            "Somewhere a bell just tolled. That's just my power, introducing itself.",
            "I don't need a cape to look like I'm about to save the world.",
            "You mistake my calm for peace. It's just power with the volume down.",
            "I don't get nervous. My heart rate is a weapon I keep sheathed.",
            "Every villain thinks they're the hero. I just happen to be right.",
            "I stopped being human the day being human stopped being enough.",
            "You feel that chill? That's just my aura saying hello.",
            "I don't lose fights. I collect origin stories for my enemies.",
            "There's a legend forming right now, and you're just a footnote in it.",
            "My smile is the last thing my enemies see before the plot twist.",
            "I don't need luck on my side. Luck asked to be on mine.",
            "You think that was my final form? Adorable.",
            "I was forged in a tragedy you couldn't survive reading about.",
            "The stars rearranged themselves the night I was born. Coincidence? No.",
            "I don't do rivalries. I do inevitabilities with extra steps.",
            "My silence isn't emptiness. It's a loaded gun with good manners.",
            "You'll tell this story one day. In it, you lose. Beautifully.",
            "I've died in every timeline where I stayed ordinary. So I stopped.",
            "This isn't confidence. This is just what winning looks like from outside.",
            "I don't get replaced. I get remembered.",
            "You're standing where legends are made. Try not to bleed on the good parts.",
            "My rage has a name, and it's not one you're allowed to say yet.",
            "I was never the underdog. I just let you think that for drama.",
            "There's a reason the shadows follow me and not the other way around.",
            "I don't flex. My existence is already a flex.",
            "You brought courage. Cute. I brought consequences.",
            "The sky went quiet the day I decided to stop holding back.",
            "I don't need an origin story. I am the origin.",
            "Every hero needs a final boss. Today, that's you meeting me.",
            "I gave up my old name the day it stopped being big enough for me.",
            "You want to test me? Bold. Foolish. But bold.",
            "My power doesn't sleep. It just waits for a reason.",
            "I don't need to prove anything. The results already did.",
            "There's a version of this fight where you win. This isn't that one.",
            "I walked through my own tragedy and came out as a warning label.",
            "You keep calling it luck. I keep calling it destiny with good timing.",
            "My hands have written more endings than you've read beginnings.",
            "I don't get intimidated. I get curious about how this ends for you.",
            "The world tried to break me once. It's still apologizing.",
            "I don't chase power. I just let it catch up when it's ready.",
            "You're about to become the reason my legend gets a new chapter."
    );

    static {
        load();
    }

    private CringeLines() {
    }
}
