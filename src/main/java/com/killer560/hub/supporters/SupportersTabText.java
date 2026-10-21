package com.killer560.hub.supporters;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Supporter names in the tab list's TEXT rows (killer560, 2026-10-11: "the name of other people should show up in
 * tab"). On Skyblock every visible tab row is one of Hypixel's fake entries ({@code !A-a} profiles) whose display
 * name carries the real player's name as text - "[400] Name (Mage 50)" - so the UUID lookup in
 * {@code SupportersTabListMixin} never matches a real player there. This finds a supporter's exact current IGN as a
 * whole name-shaped word in the row and splices the display text in, the rest of the row's styling untouched.
 * <p>
 * Case-sensitive on purpose (tab rows always show a name in its real capitalisation, and it keeps a supporter called
 * "Mage" off "(Mage 50)"). The row's display-name object is stable until Hypixel updates it, so the result is cached
 * per {@link PlayerInfo} against that object and the current index; the index itself is rebuilt at most once a second.
 * Render thread only.
 */
public final class SupportersTabText {

    private static final long INDEX_MS = 1000L;

    private static Map<String, String> index = Map.of();
    private static long indexAt = 0L;

    private record Row(Component source, Map<String, String> index, Component result) {
    }

    private static final Map<PlayerInfo, Row> ROWS = new WeakHashMap<>();

    private SupportersTabText() {
    }

    /** {@code shown} with every supporter IGN in it replaced, or {@code null} to leave the row alone. */
    public static Component replace(PlayerInfo info, Component shown) {
        if (info == null || shown == null || !SupportersFeature.cosmeticsActive()) {
            return null; // asked per tab row per frame: names off or no supporters is the common case
        }
        Component source = info.getTabListDisplayName();
        if (source == null) {
            return null; // a plain profile-name row: the UUID path covers it
        }
        Map<String, String> idx = index();
        if (idx.isEmpty()) {
            return null;
        }
        Row row = ROWS.get(info);
        if (row != null && row.source() == source && row.index() == idx) {
            return row.result();
        }
        Component result;
        try {
            result = compute(shown, idx);
        } catch (RuntimeException e) {
            result = null; // a cosmetic must never break the tab list
        }
        ROWS.put(info, new Row(source, idx, result));
        return result;
    }

    private static Map<String, String> index() {
        long now = System.currentTimeMillis();
        if (now - indexAt >= INDEX_MS) {
            indexAt = now;
            Map<String, String> fresh = SupportersFeature.shownByIgn();
            if (!fresh.equals(index)) {
                index = fresh.isEmpty() ? Map.of() : fresh;
                ROWS.clear();
            }
        }
        return index;
    }

    static Component compute(Component shown, Map<String, String> idx) {
        String plain = NameSplice.plain(shown, null);
        List<int[]> spans = new ArrayList<>();
        List<String> repl = new ArrayList<>();
        int n = plain.length();
        int i = 0;
        while (i < n) {
            if (!isNameChar(plain.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isNameChar(plain.charAt(j))) {
                j++;
            }
            if (j - i <= 16) {
                String display = idx.get(plain.substring(i, j));
                if (display != null) {
                    spans.add(new int[]{i, j});
                    repl.add(display);
                }
            }
            i = j;
        }
        if (spans.isEmpty()) {
            return null;
        }
        int[] starts = new int[spans.size()];
        int[] ends = new int[spans.size()];
        for (int k = 0; k < starts.length; k++) {
            starts[k] = spans.get(k)[0];
            ends[k] = spans.get(k)[1];
        }
        return NameSplice.splice(shown, starts, ends, repl.toArray(new String[0]));
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
