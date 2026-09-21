package com.killer560.hub.supporters;

/**
 * Client-side mirror of the relay's {@code POST /supporters/me} validation rules
 * (SUPPORTERS-CONTRACT-V2.md: "same rules as before" - max 32 visible characters after stripping {@code &}
 * codes, only {@code &0-9a-fk-or}, the slur filter). Checked by {@code SupportersTab}'s "My Supporter Name"
 * editor before a Save round-trips to the relay, so a stray {@code &} code or an over-length name shows up
 * instantly instead of via a 400 a network hop away. The relay remains the real authority for all of this -
 * this only saves the common-case round trip; a name that somehow passes here but not the relay still comes
 * back as an inline 400 error same as always.
 */
public final class SupporterNameValidator {

    public static final int MAX_VISIBLE_CHARS = 32;
    private static final String VALID_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private SupporterNameValidator() {
    }

    /** @return {@code null} if {@code rawName} (as typed - {@code &} codes and all) is valid to send to the
     *  relay, or a short human-readable reason it isn't. */
    public static String validate(String rawName) {
        String name = rawName == null ? "" : rawName;
        int visible = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '&') {
                if (i + 1 >= name.length() || VALID_CODES.indexOf(name.charAt(i + 1)) < 0) {
                    return "Unknown color code - only &0-9, &a-f, &k-o and &r are allowed.";
                }
                i++; // the code letter itself isn't a visible character either
                continue;
            }
            visible++;
        }
        if (visible == 0) {
            return "Name can't be empty - use Clear to remove it instead.";
        }
        if (visible > MAX_VISIBLE_CHARS) {
            return "Too long (" + visible + "/" + MAX_VISIBLE_CHARS + " visible characters).";
        }
        if (SlurFilter.isBlocked(name)) {
            return "That name isn't allowed.";
        }
        return null;
    }
}
