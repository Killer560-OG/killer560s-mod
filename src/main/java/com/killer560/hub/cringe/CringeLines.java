package com.killer560.hub.cringe;

import com.killer560.hub.util.ModPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Cringe lines for {@code /cringe} to pick from at random - anime-protagonist monologues, edgy
 *  one-liners, and internet-trend energy. Lives in a plain-text file in the config folder (one
 *  line per line, "#" for comments) so anyone can add/remove/edit lines themselves. The built-in
 *  {@link #DEFAULTS} seed that file the first time, are the fallback if it's ever emptied out or
 *  deleted, and any built-in line added in a mod update is added to the file on the next load (see
 *  {@link #load()}). */
public final class CringeLines {

    private static final Path FILE = ModPaths.config("killer560smod-cringe").resolve("cringe-lines.txt");

    private static volatile List<String> current = List.of();

    /** @return the current cringe lines - from the config file if present and non-empty,
     *  otherwise the built-in defaults. */
    public static List<String> all() {
        // The static block below already loads on first use; this only guards a caller ever seeing the
        // empty placeholder (randomLine's nextInt(0) would throw).
        List<String> lines = current;
        return lines.isEmpty() ? DEFAULTS : lines;
    }

    public static Path file() {
        return FILE;
    }

    /* Keeping the file up to date with the built-in list.
     *
     * The file carries a few lines of its own bookkeeping, all starting with BOOK_PREFIX ("#k560 "),
     * which are comments as far as /cringe is concerned:
     *   "#k560 built-in <stamp>"    the stamp of the built-in list the file was last brought up to;
     *   "#k560 offered <h> <h> ..." the hash of every built-in line the file has ever been given.
     *
     * The stamp is a content hash of DEFAULTS (builtInStamp()), so it changes by itself whenever a
     * built-in line is added, removed or reworded; there is no counter to remember to bump. When the
     * file's stamp differs from the current one, load() adds every built-in line that is neither in the
     * file already nor in the "offered" set, keeps every other line exactly as it was and in the same
     * order (the player's own, edited and commented lines included), appends the new lines at the end,
     * and rewrites the bookkeeping with the new stamp and every current built-in hash added to the set.
     * So a built-in line the player deleted stays deleted: it was offered once and is never offered
     * again. A file from before the bookkeeping existed has no "offered" set, so on its first update it
     * gets every built-in line it lacks, which can bring back lines deleted before then, once.
     *
     * A line's hash is String.hashCode() in hex: specified by the Java language, so stable across runs
     * and versions, and with a couple of hundred lines a collision is not a practical concern. */
    private static final String BOOK_PREFIX = "#k560 ";
    private static final String STAMP_KEY = BOOK_PREFIX + "built-in ";
    private static final String OFFERED_KEY = BOOK_PREFIX + "offered ";
    private static final String BOOK_NOTE = BOOK_PREFIX
            + "These #k560 lines let the mod add new built-in lines when it updates without bringing back ones you deleted. Leave them be.";
    /** Hashes per "offered" line, so no single line gets unreadably long. */
    private static final int HASHES_PER_LINE = 16;

    private static String lineHash(String line) {
        return Integer.toHexString(line.strip().hashCode());
    }

    /** Content hash of the whole built-in list, in order. */
    private static String builtInStamp() {
        return Integer.toHexString(String.join("\n", DEFAULTS).hashCode());
    }

    /** (Re)reads the lines file from disk, creating it from {@link #DEFAULTS} if it doesn't exist
     *  yet, and first bringing it up to the current built-in list if the mod's list has changed since
     *  the file was last stamped (see the comment above). Safe to call again later to pick up manual
     *  edits without restarting the game. */
    public static void load() {
        try {
            String stamp = builtInStamp();
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                Set<String> offered = new LinkedHashSet<>();
                for (String d : DEFAULTS) {
                    offered.add(lineHash(d));
                }
                writeFile(new ArrayList<>(DEFAULTS), stamp, offered);
            }
            List<String> raw = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            if (!raw.isEmpty() && !raw.get(0).isEmpty() && raw.get(0).charAt(0) == 0xFEFF) {
                raw.set(0, raw.get(0).substring(1));
            }

            String fileStamp = null;
            Set<String> offered = new LinkedHashSet<>();
            List<String> body = new ArrayList<>();
            for (String line : raw) {
                String trimmed = line.strip();
                if (trimmed.startsWith(STAMP_KEY)) {
                    fileStamp = trimmed.substring(STAMP_KEY.length()).strip();
                } else if (trimmed.startsWith(OFFERED_KEY)) {
                    for (String h : trimmed.substring(OFFERED_KEY.length()).strip().split("\\s+")) {
                        if (!h.isEmpty()) {
                            offered.add(h);
                        }
                    }
                } else if (!trimmed.startsWith(BOOK_PREFIX.strip())) {
                    body.add(line);
                }
            }

            if (!stamp.equals(fileStamp)) {
                Set<String> present = new HashSet<>();
                for (String line : body) {
                    present.add(line.strip());
                }
                for (String d : DEFAULTS) {
                    String h = lineHash(d);
                    if (!present.contains(d) && !offered.contains(h)) {
                        body.add(d);
                        present.add(d);
                    }
                    offered.add(h);
                }
                writeFile(body, stamp, offered);
            }

            List<String> lines = new ArrayList<>();
            for (String line : body) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            current = lines.isEmpty() ? DEFAULTS : lines;
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: this runs from the static initializer, where an escaping throw would
            // turn into ExceptionInInitializerError and break /cringe for the session. A file that could
            // not be read is never rewritten, so nothing the player wrote is lost.
            current = DEFAULTS;
        }
    }

    /** Writes the bookkeeping lines, then the body, through a temporary file so a failed write can
     *  never leave the player's file half written. */
    private static void writeFile(List<String> body, String stamp, Set<String> offered) throws IOException {
        List<String> out = new ArrayList<>();
        out.add(BOOK_NOTE);
        out.add(STAMP_KEY + stamp);
        StringBuilder row = new StringBuilder();
        int n = 0;
        for (String h : offered) {
            if (n > 0 && n % HASHES_PER_LINE == 0) {
                out.add(OFFERED_KEY + row);
                row.setLength(0);
            }
            if (row.length() > 0) {
                row.append(' ');
            }
            row.append(h);
            n++;
        }
        if (row.length() > 0) {
            out.add(OFFERED_KEY + row);
        }
        out.addAll(body);
        Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.write(tmp, out, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
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
            "You're about to become the reason my legend gets a new chapter.",

            // Third batch, same voice - self-written, none of them repeat a line above.
            "I don't walk into rooms. Rooms rearrange themselves around me.",
            "My shadow keeps training even while I rest.",
            "You heard thunder. I heard my power clearing its throat.",
            "I don't have weaknesses. I have plot armor with a warranty.",
            "Fate tried to write my ending. I took the pen.",
            "The wind only blows dramatically when I'm about to speak.",
            "I've been the chosen one so long the prophecy asks me for advice.",
            "You're not facing me. You're facing the consequences of meeting me.",
            "Every flashback I have is somebody else's worst day.",
            "I don't level up. The levels come to me.",
            "The ground cracks when I stand still. Imagine when I move.",
            "I keep my power sealed in my left arm. For your sake, not mine.",
            "Some people have a dark past. Mine has a sequel.",
            "I don't take damage. I take notes.",
            "You blinked. In that time I won twice.",
            "My theme song starts playing the second a fight begins.",
            "I've read the last page of this story. You're not in it.",
            "Mercy is a gift. Unfortunately, I'm out of stock.",
            "They told me to go find myself. I found a legend instead.",
            "My training arc lasted three years. Your fight lasts three seconds."
    );

    static {
        load();
    }

    private CringeLines() {
    }
}
