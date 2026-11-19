package com.killer560.hub.partyinvites;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guild members seen this session, for Auto Accept Invites' Guild group (autoaccept-who-1118). The mod has no guild list and
 * the invite names no guild, so this reads what Hypixel already prints with no command sent: a guild chat line
 * ("Guild > [MVP+] Name [Officer]: hi") or a guild login line ("Guild > Name joined."). A member counts once seen this
 * session; the set is bounded and starts empty each launch.
 */
public final class GuildMembers {

    private static final int MAX = 1000;
    private static final Pattern LINE = Pattern.compile(
            "^Guild > (?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16})(?: \\[[^\\]]{1,20}\\])?(?::| joined\\.| left\\.)");
    private static final Set<String> SEEN = Collections.synchronizedSet(new LinkedHashSet<>());

    private GuildMembers() {
    }

    /** Notes the sender of a guild line (stripped chat text); anything else is ignored. */
    public static void onLine(String plain) {
        if (plain == null || !plain.startsWith("Guild > ")) {
            return;
        }
        Matcher m = LINE.matcher(plain);
        if (!m.find()) {
            return;
        }
        synchronized (SEEN) {
            SEEN.add(m.group(1).toLowerCase(Locale.ROOT));
            if (SEEN.size() > MAX) {
                SEEN.remove(SEEN.iterator().next());
            }
        }
    }

    public static boolean contains(String name) {
        return name != null && SEEN.contains(name.toLowerCase(Locale.ROOT));
    }

    public static void clearForTest() {
        SEEN.clear();
    }
}
