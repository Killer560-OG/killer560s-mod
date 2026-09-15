package com.killer560.hub.namechanger;

/**
 * Immutable name -> replacement lookup, built by {@link NameChangerFeature} whenever the config or the set of
 * seen players changes. Keys are Minecraft-name-shaped words ({@code [A-Za-z0-9_]}, up to 16 chars). Lookup is
 * allocation-free: the scanner feeds a reusable char buffer plus a rolling lowercase hash, and this open-addressed
 * table compares against the stored chars directly, so the no-match path (almost every string) never creates a
 * String.
 */
final class NameTable {

    static final int MAX_LEN = 16;

    static final class Entry {
        final char[] exact;
        final char[] lower;
        final boolean caseSensitive;
        final String replacement;
        final int hash;

        Entry(String name, String replacement, boolean caseSensitive) {
            this.exact = name.toCharArray();
            this.lower = new char[exact.length];
            for (int i = 0; i < exact.length; i++) {
                lower[i] = lower(exact[i]);
            }
            this.caseSensitive = caseSensitive;
            this.replacement = replacement;
            this.hash = hashOf(lower, lower.length);
        }

        boolean matches(char[] buf, int len) {
            if (len != exact.length) {
                return false;
            }
            if (caseSensitive) {
                for (int i = 0; i < len; i++) {
                    if (buf[i] != exact[i]) {
                        return false;
                    }
                }
            } else {
                for (int i = 0; i < len; i++) {
                    if (lower(buf[i]) != lower[i]) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private final Entry[] slots;
    private final int mask;
    final int size;

    NameTable(java.util.List<Entry> entries) {
        int cap = 16;
        while (cap < entries.size() * 2 + 1) {
            cap <<= 1;
        }
        slots = new Entry[cap];
        mask = cap - 1;
        int n = 0;
        for (Entry e : entries) {
            int i = mix(e.hash) & mask;
            boolean duplicate = false;
            while (slots[i] != null) {
                Entry other = slots[i];
                if (other.hash == e.hash && other.matches(e.exact, e.exact.length)) {
                    duplicate = true; // first-added wins (own name > manual mappings > randomized)
                    break;
                }
                i = (i + 1) & mask;
            }
            if (!duplicate) {
                slots[i] = e;
                n++;
            }
        }
        size = n;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** @param hash {@link #step}-accumulated hash of the lowercased chars in {@code buf[0..len)}. */
    Entry lookup(char[] buf, int len, int hash) {
        int i = mix(hash) & mask;
        Entry e;
        while ((e = slots[i]) != null) {
            if (e.hash == hash && e.matches(buf, len)) {
                return e;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    static boolean isNameChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    static boolean isValidName(String s) {
        if (s == null || s.length() < 1 || s.length() > MAX_LEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isNameChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    static int step(int hash, char c) {
        return hash * 31 + lower(c);
    }

    static int hashOf(char[] lowerChars, int len) {
        int h = 0;
        for (int i = 0; i < len; i++) {
            h = step(h, lowerChars[i]);
        }
        return h;
    }

    private static int mix(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }
}
