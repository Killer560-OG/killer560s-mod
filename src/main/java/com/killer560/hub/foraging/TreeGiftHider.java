package com.killer560.hub.foraging;

import com.killer560.hub.islands.IslandConfig;

import java.util.regex.Pattern;

/**
 * Tree Gift Messages: hides the chat block Hypixel prints when a Galatea tree falls (a separator line, "TREE GIFT", a blank
 * line, "You helped cut N% of the X Tree.", "+N rewards gained! (hover)", any "BONUS GIFT" / item lines and "A X fell from
 * the Tree!", then a closing separator), one server line at a time, called from {@code ChatTidy.shouldHide}. The opening
 * separator is already in chat when "TREE GIFT" arrives, so that verdict also asks the mixin to take it back. Rare drops
 * (BONUS GIFT, a mob falling from the tree) stay visible while Keep Rare Drops is on (default). A separator that belongs
 * to any other message is never touched: only the one directly before "TREE GIFT" and the one closing the block.
 */
public final class TreeGiftHider {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "treegift").flag("rare", true);

    public enum Verdict { SHOW, HIDE, HIDE_AND_RETRACT }

    private static final Pattern SEPARATOR = Pattern.compile("^\\s*[▬]{10,}\\s*$");
    private static final Pattern CUT = Pattern.compile("^You helped cut [\\d.]+% of the [A-Za-z' ]{1,30}\\.$");
    private static final Pattern REWARDS = Pattern.compile("^\\+\\d{1,3} rewards? gained! \\(hover\\)$");
    private static final Pattern FELL = Pattern.compile("^An? [A-Za-z' ]{1,40} fell from the Tree!$");
    private static final long BLOCK_MS = 4_000L;

    private static boolean afterSeparator;
    private static boolean inBlock;
    private static boolean nextIsItem;
    private static boolean rareShown;
    private static long blockStart;

    private TreeGiftHider() {
    }

    public static boolean isSeparator(String plain) {
        return plain != null && SEPARATOR.matcher(plain).matches();
    }

    /** The verdict for the next server chat line (plain text, no codes); {@code now} in milliseconds. */
    public static synchronized Verdict next(String plain, long now) {
        String t = plain == null ? "" : plain.trim();
        if (inBlock && now - blockStart > BLOCK_MS) {
            inBlock = false;
        }
        boolean keepRare = CFG.on("rare");
        Verdict v = Verdict.SHOW;
        if (!inBlock) {
            if (afterSeparator && t.equals("TREE GIFT")) {
                inBlock = true;
                nextIsItem = false;
                rareShown = false;
                blockStart = now;
                v = Verdict.HIDE_AND_RETRACT;
            }
        } else if (isSeparator(t)) {
            inBlock = false;
            v = rareShown ? Verdict.SHOW : Verdict.HIDE;
        } else if (nextIsItem && !t.isEmpty()) {
            nextIsItem = false;
            v = keepRare ? Verdict.SHOW : Verdict.HIDE;
            rareShown |= keepRare;
        } else if (t.isEmpty() || CUT.matcher(t).matches() || REWARDS.matcher(t).matches()) {
            v = Verdict.HIDE;
        } else if (t.equals("BONUS GIFT")) {
            nextIsItem = true;
            v = keepRare ? Verdict.SHOW : Verdict.HIDE;
            rareShown |= keepRare;
        } else if (FELL.matcher(t).matches()) {
            v = keepRare ? Verdict.SHOW : Verdict.HIDE;
            rareShown |= keepRare;
        } else {
            inBlock = false; // another message arrived inside the block: stop hiding
        }
        afterSeparator = v == Verdict.SHOW && isSeparator(t);
        return v;
    }
}
